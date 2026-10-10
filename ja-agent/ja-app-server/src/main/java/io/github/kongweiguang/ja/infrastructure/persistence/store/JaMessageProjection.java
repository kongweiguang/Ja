// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.conversation.UserMessage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFact;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.AssistantMessage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ModelRequestIdentity;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ReasoningSummary;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolMessage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.Usage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.UsageCertainty;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.ResourceReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.conversation.domain.model.JaMessageIds;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.derivedItemId;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.encode;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.nextTimelineSequence;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.nullable;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.readStoredRecord;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.requireOne;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.sha256;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.stored;

/** 消息投影独占公开历史和有界导航；仅借用当前 Store 连接，绝不单独提交。 */
final class JaMessageProjection {
    private final ObjectMapper mapper;
    private final StorePayloadCodec facts;

    /** 所有 codec 共用组合根配置；此协作者只接收连接，不持有数据库生命周期。 */
    JaMessageProjection(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.facts = new StorePayloadCodec(mapper);
    }

    /**
     * 读取并锁在写事务中的 Thread 最大序号；调用方始终单连接执行，避免并发 writer 分配同一位置。
     */
    private static long nextMessageOrdinal(Connection connection, String threadId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(ordinal),0)+1 FROM ja_conversation_message WHERE thread_id=?")) {
            statement.setString(1, threadId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Ja message ordinal query returned no row");
                }
                return rows.getLong(1);
            }
        }
    }

    /**
     * 时间线只保存稳定消息引用及有界预览，完整正文由 Core record 或 Mailbox 按需读取。
     */
    private static void insertTimeline(Connection connection, String itemId, AgentStore.ExecutionKey execution,
                                       String kind, String messageId, String text, Integer modelRound,
                                       String attachmentsJson, String contextReferencesJson,
                                       Instant occurredAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                    INSERT INTO ja_conversation_timeline_entry(item_id,thread_id,turn_id,sequence,kind,is_current_path,\
                    message_id,mailbox_message_id,preview_text,total_characters,attachments_json,context_references_json,\
                    model_round,created_at_ms,source_thread_id,source_title) \
                    VALUES(?,?,?,?,?,1,?,NULL,?,?,?,?,?,?,NULL,NULL)\
                    """)) {
            statement.setString(1, itemId);
            statement.setString(2, execution.conversation().threadId());
            statement.setString(3, execution.turnId());
            statement.setLong(4, nextTimelineSequence(connection, execution.conversation().threadId()));
            statement.setString(5, kind);
            statement.setString(6, messageId);
            statement.setString(7, ThreadSnapshot.textPreview(text));
            statement.setInt(8, text.codePointCount(0, text.length()));
            statement.setString(9, attachmentsJson);
            statement.setString(10, contextReferencesJson);
            if (modelRound == null) {
                statement.setNull(11, java.sql.Types.INTEGER);
            }
            else {
                statement.setInt(11, modelRound);
            }
            statement.setLong(12, occurredAt.toEpochMilli());
            requireOne(statement.executeUpdate(), "Ja timeline projection insert failed");
        }
    }

    /**
     * Ja 历史 profile 解码依赖完整身份字段，缺少时拒绝写入不可读的半成品审计行。
     */
    private static String requiredTextAttribute(JsonObject source, String field) throws SQLException {
        if (!(source.get(field) instanceof JsonText(String value)) || value.isBlank()) {
            throw new SQLException("Ja provider profile attribute is missing or invalid");
        }
        return value;
    }

    /**
     * Jackson 对象字段与 JDBC 可空参数分开处理，保留显式 null 的历史 profile 语义。
     */
    private static void putNullableText(com.fasterxml.jackson.databind.node.ObjectNode target,
                                        String field, String value) {
        if (value == null) {
            target.putNull(field);
        }
        else {
            target.put(field, value);
        }
    }

    /**
     * 新请求行始终先写 UNKNOWN；若本次提交已拿到计量，再于同一事务按既有 CAS 结算，外部看不到中间态。
     */
    private static void insertUsage(Connection connection, AgentStoreTransactionContext transaction,
                                    Usage value, String profileJson, Long input, Long output, Long total,
                                    Long cacheRead, Long cacheWrite, Long newInput, String accounting) throws SQLException {
        Long estimate = value.certainty() == UsageCertainty.UNKNOWN ? value.estimatedContextTokens() : null;
        insertUnknownUsage(connection, transaction, value, profileJson, estimate);
        if (value.certainty() == UsageCertainty.KNOWN) {
            settleUsage(connection, transaction, value, profileJson, input, output, total,
                    cacheRead, cacheWrite, newInput, accounting);
        }
    }

    /**
     * 在 Provider 结果结算前建立满足 SQLite 追加约束的 UNKNOWN 基线；调用方已在 Core 层校验估算资格。
     */
    private static void insertUnknownUsage(Connection connection, AgentStoreTransactionContext transaction,
                                           Usage value, String profileJson, Long estimate) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                    INSERT INTO ja_turn_usage(usage_id,model_request_id,thread_id,turn_id,model_round,model_request_ordinal,\
                    purpose,certainty,profile_json,\
                    input_tokens,output_tokens,total_tokens,created_at_ms,cache_read_tokens,cache_write_tokens,\
                    new_input_tokens,\
                    input_accounting,estimated_context_tokens) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)\
                    """)) {
            statement.setString(1, usageId(value.modelRequestId()));
            statement.setString(2, value.modelRequestId());
            statement.setString(3, transaction.execution().conversation().threadId());
            statement.setString(4, transaction.execution().turnId());
            statement.setInt(5, value.modelRound());
            statement.setInt(6, value.modelRequestOrdinal());
            statement.setString(7, value.purpose().name());
            statement.setString(8, UsageCertainty.UNKNOWN.name());
            statement.setString(9, profileJson);
            nullable(statement, 10, (Long) null);
            nullable(statement, 11, (Long) null);
            nullable(statement, 12, (Long) null);
            statement.setLong(13, transaction.occurredAt().toEpochMilli());
            nullable(statement, 14, (Long) null);
            nullable(statement, 15, (Long) null);
            nullable(statement, 16, (Long) null);
            statement.setString(17, "UNKNOWN");
            nullable(statement, 18, estimate);
            requireOne(statement.executeUpdate(), "Ja ja_turn_usage insert failed");
        }
    }

    /**
     * 结算旧 UNKNOWN 计量时以 request 身份和口径 CAS 更新，拒绝覆盖已知或不同模型请求。
     */
    private static void settleUsage(Connection connection, AgentStoreTransactionContext transaction,
                                    Usage value, String profileJson, Long input, Long output, Long total,
                                    Long cacheRead, Long cacheWrite, Long newInput, String accounting) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                    UPDATE ja_turn_usage SET certainty='KNOWN',profile_json=?,input_tokens=?,output_tokens=?,total_tokens=? ,\
                    cache_read_tokens=?,cache_write_tokens=?,new_input_tokens=?,input_accounting=?,\
                    estimated_context_tokens=NULL \
                    WHERE model_request_id=? AND thread_id=? AND turn_id=? AND model_round=? AND \
                    model_request_ordinal=? \
                    AND purpose=? AND certainty='UNKNOWN'\
                    """)) {
            statement.setString(1, profileJson);
            statement.setLong(2, Objects.requireNonNull(input));
            statement.setLong(3, Objects.requireNonNull(output));
            statement.setLong(4, Objects.requireNonNull(total));
            nullable(statement, 5, cacheRead);
            nullable(statement, 6, cacheWrite);
            nullable(statement, 7, newInput);
            statement.setString(8, accounting);
            statement.setString(9, value.modelRequestId());
            statement.setString(10, transaction.execution().conversation().threadId());
            statement.setString(11, transaction.execution().turnId());
            statement.setInt(12, value.modelRound());
            statement.setInt(13, value.modelRequestOrdinal());
            statement.setString(14, value.purpose().name());
            requireOne(statement.executeUpdate(), "Ja unknown usage settlement CAS failed");
        }
    }

    /**
     * 用量展示行要求 `usage_` 前缀；直接复用既有 call-independent 请求身份或做稳定内容寻址。
     */
    private static String usageId(String modelRequestId) {
        return modelRequestId.startsWith("usage_") ? modelRequestId : "usage_" + sha256(modelRequestId);
    }

    /**
     * 消息行仅保存已提交 Core record 的稳定引用，timeline 仅附加有界可见预览。
     * STOP 结算答复不属于模型进度轮次，按持久 CHECK 约束不写 model_round。
     * 只读取分支实际需要的事实字段，Tool 正文继续归 Core record 而不复制到 Ja 投影。
     */
    void applyMessages(Connection connection, AgentStoreTransactionContext transaction,
                               Map<String, ExecutionFact> executionFactsByRecordId,
                               Set<String> mailboxMessageIdsConsumed, ExecutionEvent.Terminal terminalEvent,
                               Set<String> settledAssistantRecordIds)
            throws SQLException {
        for (AgentStore.RecordWrite record : transaction.records()) {
            if ("agent.user.message".equals(record.recordType())) {
                UserMessage user = facts.decodeUser(stored(record, 1));
                String itemId = JaMessageIds.toItemId(user.messageId());
                UserContent content = user.content();
                String attachments = encode(mapper, attachmentReferences(content));
                String references = encode(mapper, contextReferences(content));
                insertMessage(connection, itemId, transaction.execution(), record.recordId(), ModelRole.USER,
                        "USER_INPUT", content.text(), attachments, references, record.occurredAt());
                if (!mailboxMessageIdsConsumed.contains(user.messageId())) {
                    insertTimeline(connection, itemId, transaction.execution(), "USER_INPUT", itemId,
                            content.text(), null, attachments, references, record.occurredAt());
                }
            }
        }
        for (AgentStore.RecordWrite record : transaction.records()) {
            ExecutionFact fact = executionFactsByRecordId.get(record.recordId());
            if (fact == null) {
                continue;
            }
            if (fact instanceof AssistantMessage assistant) {
                String itemId = JaMessageIds.toItemId(assistant.messageId());
                String emptyReferences = "[]";
                insertMessage(connection, itemId, transaction.execution(), record.recordId(), ModelRole.ASSISTANT,
                        "ASSISTANT", assistant.publicText(), emptyReferences, emptyReferences, record.occurredAt());
                if (!assistant.publicText().isEmpty()) {
                    String kind = settledAssistantRecordIds.contains(record.recordId())
                                  || terminalEvent != null && terminalEvent.state() == TurnState.COMPLETED
                                  && record.recordId().equals(terminalEvent.messageRecordId())
                            ? "FINAL_ANSWER" : "ASSISTANT_PROGRESS";
                    insertTimeline(connection, itemId, transaction.execution(), kind, itemId,
                            assistant.publicText(), "FINAL_ANSWER".equals(kind) ? null : assistant.modelRound(),
                            emptyReferences, emptyReferences, record.occurredAt());
                }
                if (assistant.reasoningSummary() != null) {
                    insertTimeline(connection, JaMessageIds.toItemId(
                                    derivedItemId(assistant.messageId(), "reasoning")),
                            transaction.execution(), "REASONING_SUMMARY", itemId, assistant.reasoningSummary(),
                            assistant.modelRound(), emptyReferences, emptyReferences, record.occurredAt());
                }
            } else if (fact instanceof ReasoningSummary reasoning) {
                boolean embeddedInAssistant = executionFactsByRecordId.values().stream().anyMatch(candidate ->
                        candidate instanceof AssistantMessage assistant
                        && assistant.messageId().equals(reasoning.messageId())
                        && Objects.equals(assistant.reasoningSummary(), reasoning.text()));
                if (!embeddedInAssistant) {
                    String itemId = JaMessageIds.toItemId(reasoning.messageId());
                    String emptyReferences = "[]";
                    insertMessage(connection, itemId, transaction.execution(), record.recordId(), ModelRole.ASSISTANT,
                            "REASONING_SUMMARY", reasoning.text(), emptyReferences, emptyReferences,
                            record.occurredAt());
                    insertTimeline(connection, itemId, transaction.execution(), "REASONING_SUMMARY", itemId,
                            reasoning.text(), reasoning.modelRound(), emptyReferences, emptyReferences,
                            record.occurredAt());
                }
            } else if (fact instanceof ToolMessage toolMessage) {
                String emptyReferences = "[]";
                insertMessage(connection, JaMessageIds.toItemId(toolMessage.messageId()), transaction.execution(),
                        record.recordId(),
                        ModelRole.TOOL, "TOOL", "", emptyReferences, emptyReferences, record.occurredAt());
            }
        }
    }

    /**
     * Terminal 只可把事件精确引用的助手记录提升为最终答复；同一 Turn 已有更早 STOP 答复时，
     * 仍保留所有结算边界，不按“最后一条”推断或压成单个终态答案。
     */
    void finalizeTerminalAssistant(Connection connection, AgentStoreTransactionContext transaction,
                                           ExecutionEvent.Terminal terminalEvent, Map<String, ExecutionFact> currentFacts) throws SQLException {
        if (terminalEvent == null || terminalEvent.state() != TurnState.COMPLETED
            || terminalEvent.messageRecordId() == null) {
                return;
            }
        String recordId = terminalEvent.messageRecordId();
        ExecutionFact referenced = currentFacts.get(recordId);
        if (referenced == null) {
            referenced = readPersistedAssistantFact(connection, transaction, recordId);
        }
        if (!(referenced instanceof AssistantMessage assistant)) {
            throw new SQLException("completed terminal does not reference an assistant message fact");
        }
        String text = assistant.publicText();
        if (text.isEmpty()) {
            return;
        }
        String itemId = JaMessageIds.toItemId(assistant.messageId());
        String threadId = transaction.execution().conversation().threadId();
        String turnId = transaction.execution().turnId();
        String kind;
        Integer modelRound;
        try (PreparedStatement query = connection.prepareStatement(
                """
                    SELECT kind,message_id,total_characters,model_round \
                    FROM ja_conversation_timeline_entry \
                    WHERE item_id=? AND thread_id=? AND turn_id=?\
                    """)) {
            query.setString(1, itemId);
            query.setString(2, threadId);
            query.setString(3, turnId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("terminal assistant has no persisted timeline row");
                }
                kind = rows.getString(1);
                if (!itemId.equals(rows.getString(2))
                    || rows.getInt(3) != text.codePointCount(0, text.length())) {
                    throw new SQLException("terminal assistant reference differs from its timeline projection");
                }
                int value = rows.getInt(4);
                modelRound = rows.wasNull() ? null : value;
                if (rows.next()) {
                    throw new SQLException("terminal assistant timeline identity is not unique");
                }
            }
        }
        if ("FINAL_ANSWER".equals(kind)) {
            if (modelRound != null) {
                throw new SQLException("final answer carries a model round");
            }
            return;
        }
        if (!"ASSISTANT_PROGRESS".equals(kind) || !Objects.equals(modelRound, assistant.modelRound())) {
            throw new SQLException("terminal assistant is not a matching progress timeline row");
        }
        try (PreparedStatement update = connection.prepareStatement(
                """
                    UPDATE ja_conversation_timeline_entry SET kind='FINAL_ANSWER',model_round=NULL \
                    WHERE item_id=? AND thread_id=? AND turn_id=? AND kind='ASSISTANT_PROGRESS' \
                    AND model_round=? AND message_id=? AND total_characters=?\
                    """)) {
            update.setString(1, itemId);
            update.setString(2, threadId);
            update.setString(3, turnId);
            update.setInt(4, assistant.modelRound());
            update.setString(5, itemId);
            update.setInt(6, text.codePointCount(0, text.length()));
            requireOne(update.executeUpdate(), "Ja terminal assistant projection CAS failed");
        }
    }

    /**
     * Terminal 对旧提交消息的引用按完整 Store execution key 和 record ID 回读，绝不查找最新正文。
     */
    private AssistantMessage readPersistedAssistantFact(Connection connection,
                                                        AgentStoreTransactionContext transaction, String recordId) throws SQLException {
        AgentStore.StoredRecord record = readStoredRecord(connection, transaction, recordId,
                "terminal assistant record is missing from Store",
                "terminal assistant Store identity is not unique");
        if (!"agent.assistant".equals(record.recordType())) {
            throw new SQLException("terminal record does not identify an assistant fact");
        }
        try {
            ExecutionFact decoded = facts.decode(record);
            if (decoded instanceof AssistantMessage assistant) {
                return assistant;
            }
            throw new SQLException("terminal record decoded as a non-assistant fact");
        } catch (RuntimeException invalid) {
            throw new SQLException("terminal assistant Store fact is invalid", invalid);
        }
    }

    /**
     * Ja 消息行只保留 Core 完整身份引用和有限展示元数据；正文始终从同一 Core record 按需读取。
     */
    private void insertMessage(Connection connection, String messageId, AgentStore.ExecutionKey execution,
                               String recordId, ModelRole role, String kind, String preview,
                               String attachmentsJson, String contextReferencesJson, Instant occurredAt)
            throws SQLException {
        AgentStore.ConversationKey conversation = execution.conversation();
        String sql = """
            INSERT INTO ja_conversation_message(message_id,thread_id,turn_id,ordinal,role,kind,total_characters,\
            preview_text,attachments_json,context_references_json,created_at_ms,core_tenant_id,core_thread_id,\
            core_turn_id,core_record_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)\
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, messageId);
            statement.setString(2, conversation.threadId());
            statement.setString(3, execution.turnId());
            statement.setLong(4, nextMessageOrdinal(connection, execution.conversation().threadId()));
            statement.setString(5, role.name());
            statement.setString(6, kind);
            statement.setInt(7, preview.codePointCount(0, preview.length()));
            statement.setString(8, ThreadSnapshot.textPreview(preview));
            statement.setString(9, attachmentsJson);
            statement.setString(10, contextReferencesJson);
            statement.setLong(11, occurredAt.toEpochMilli());
            statement.setString(12, conversation.tenantId());
            statement.setString(13, conversation.threadId());
            statement.setString(14, execution.turnId());
            statement.setString(15, recordId);
            requireOne(statement.executeUpdate(), "Ja message insert failed");
        }
    }

    /** 附件只保留身份和明确顺序，读取正文或 blob 必须经过原有附件权限边界。 */
    static List<Map<String, Object>> attachmentReferences(UserContent content) {
        List<Map<String, Object>> references = new ArrayList<>();
        for (String attachmentId : content.attachmentIds()) {
            references.add(Map.of("ordinal", references.size(), "attachmentId", attachmentId));
        }
        return List.copyOf(references);
    }

    /**
     * 用户输入摘要不复制造成文本泄漏；只保存附件 ID 和资源/Skill 身份引用，顺序沿用规范 UserContent。
     */
    static List<Map<String, Object>> contextReferences(UserContent content) {
        List<Map<String, Object>> references = new ArrayList<>();
        for (io.github.kongweiguang.ja.agent.domain.model.UserContentBlock block : content.blocks()) {
            if (block instanceof ResourceReferenceContent resource) {
                Map<String, Object> reference = new LinkedHashMap<>();
                reference.put("ordinal", references.size());
                reference.put("kind", "resource");
                reference.put("resourceKind", resource.kind());
                reference.put("sourceId", resource.sourceId());
                reference.put("resourceId", resource.resourceId());
                references.add(Map.copyOf(reference));
            } else if (block instanceof SkillReferenceContent skill) {
                Map<String, Object> reference = new LinkedHashMap<>();
                reference.put("ordinal", references.size());
                reference.put("kind", "skill");
                reference.put("skillId", skill.skillId());
                references.add(Map.copyOf(reference));
            }
        }
        return List.copyOf(references);
    }

    /**
     * 用量镜像保留供应商原始统计与缓存口径，唯一允许的旧行更新是 UNKNOWN 到 KNOWN 的核心结算。
     */
    void applyUsage(Connection connection, AgentStoreTransactionContext transaction,
                            List<ExecutionFact> values) throws SQLException {
        for (ExecutionFact fact : values) {
            if (!(fact instanceof Usage usage)) {
                continue;
            }
            String threadId = transaction.execution().conversation().threadId();
            String turnId = transaction.execution().turnId();
            ModelRequestIdentity profile = usage.profile();
            String profileJson = providerProfileJson(profile);
            Long input = usage.usage() == null ? null : usage.usage().inputTokens();
            Long output = usage.usage() == null ? null : usage.usage().outputTokens();
            Long total = usage.usage() == null ? null : usage.usage().totalTokens();
            Long cacheRead = usage.usage() == null ? null : usage.usage().cacheReadTokens();
            Long cacheWrite = usage.usage() == null ? null : usage.usage().cacheWriteTokens();
            Long newInput = usage.usage() == null ? null : usage.usage().newInputTokens();
            String accounting = usage.usage() == null ? "UNKNOWN" : usage.usage().inputAccounting().name();
            if (usage.certainty() == UsageCertainty.KNOWN && usage.usage() == null) {
                throw new SQLException("known Agent usage omitted provider counters");
            }
            if (usage.certainty() == UsageCertainty.UNKNOWN && usage.usage() != null) {
                throw new SQLException("unknown Agent usage contains provider counters");
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT certainty FROM ja_turn_usage WHERE model_request_id=? AND thread_id=? AND turn_id=?")) {
                query.setString(1, usage.modelRequestId());
                query.setString(2, threadId);
                query.setString(3, turnId);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        insertUsage(connection, transaction, usage, profileJson, input, output, total,
                                cacheRead, cacheWrite, newInput, accounting);
                    } else if ("UNKNOWN".equals(rows.getString(1)) && usage.certainty() == UsageCertainty.KNOWN) {
                        settleUsage(connection, transaction, usage, profileJson, input, output, total,
                                cacheRead, cacheWrite, newInput, accounting);
                    }
                }
            }
        }
    }

    /**
     * 将供应商身份和 Ja 非敏感配置修订装入旧展示 JSON，避免丢失 access mode 与上下文配置版本。
     */
    private String providerProfileJson(ModelRequestIdentity profile) throws SQLException {
        com.fasterxml.jackson.databind.node.ObjectNode value = mapper.createObjectNode()
                .put("providerId", profile.providerId()).put("modelId", profile.modelId()).put("api", profile.api())
                .put("upstreamModel", profile.upstreamModel()).put("contextWindowTokens", profile.contextWindowTokens())
                .put("maxOutputTokens", profile.maxOutputTokens());
        putNullableText(value, "requestedReasoning", profile.requestedReasoning());
        putNullableText(value, "effectiveReasoning", profile.effectiveReasoning());
        JsonObject attributes = profile.attributes();
        value.put("accessMode", requiredTextAttribute(attributes, "accessMode"));
        value.put("collaborationMode", requiredTextAttribute(attributes, "collaborationMode"));
        value.put("configGeneration", requiredTextAttribute(attributes, "configGeneration"));
        value.put("promptRevision", requiredTextAttribute(attributes, "promptRevision"));
        value.put("toolCatalogRevision", requiredTextAttribute(attributes, "toolCatalogRevision"));
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new SQLException("cannot encode Ja usage profile", failure);
        }
    }
}
