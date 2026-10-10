// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFact;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.AssistantMessage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.RecoveryEvidence;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolMessage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolPrepared;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolResult;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolStarted;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference;
import io.github.kongweiguang.ja.agent.domain.tool.ToolExecutionBinding;
import io.github.kongweiguang.ja.agent.domain.tool.ToolRecoveryDecision;
import io.github.kongweiguang.ja.agent.domain.tool.ToolState;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.JacksonExecutionEventCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.conversation.application.presentation.ToolPresentationProjector;
import io.github.kongweiguang.ja.attachment.application.gateway.AttachmentRepository;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.AgentStoreConnectionScope;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.exists;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.nullable;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.requireOne;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.sha256;

/** 工具投影负责展示、绑定和恢复审计；不执行工具，也不按投影推断真实副作用。 */
final class JaToolProjection {
    private final StorePayloadCodec facts;
    private final ControlPayloadCodec controls;
    private final JsonValueCodec jsonValues;
    private final JacksonExecutionEventCodec executionEvents;
    private final ToolPresentationCodec toolPresentations;
    private final AttachmentRepository attachments;

    /** codec 共用组合根 mapper；附件仓储加入 Store connection scope，使媒体 owner 与回执共事务提交。 */
    JaToolProjection(ObjectMapper mapper, AttachmentRepository attachments) {
        Objects.requireNonNull(mapper, "mapper");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.facts = new StorePayloadCodec(mapper);
        this.controls = new ControlPayloadCodec(mapper);
        this.jsonValues = new JacksonJsonValueCodec(mapper);
        this.executionEvents = new JacksonExecutionEventCodec(mapper);
        this.toolPresentations = new ToolPresentationCodec(mapper);
    }

    /**
     * 提案行与 ModelCompleted 同连接写入；其复合来源键不可被之后的 PREPARED 事实覆盖。
     */
    private static void insertToolCallPreview(Connection connection, AgentStoreTransactionContext transaction,
                                              String messageRecordId, AgentStore.EventWrite event,
                                              long eventSequence, AgentTool.Invocation invocation,
                                              String presentationJson) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                    INSERT INTO ja_tool_call_preview(thread_id,turn_id,assistant_message_record_id,source_event_id,\
                    source_event_sequence,call_id,ordinal,tool_name,presentation_json,created_at_ms) \
                    VALUES(?,?,?,?,?,?,?,?,?,?)\
                    """)) {
            statement.setString(1, transaction.execution().conversation().threadId());
            statement.setString(2, transaction.execution().turnId());
            statement.setString(3, messageRecordId);
            statement.setString(4, event.eventId());
            statement.setLong(5, eventSequence);
            statement.setString(6, invocation.callId());
            statement.setInt(7, invocation.ordinal());
            statement.setString(8, invocation.toolName());
            statement.setString(9, presentationJson);
            statement.setLong(10, event.occurredAt().toEpochMilli());
            requireOne(statement.executeUpdate(), "Ja model tool preview insert failed");
        }
    }

    /**
     * 只认 Ja 当前实现的文件后像证据，路径和摘要保持与 Core opaque payload 一致。
     */
    private static RecoveryEvidenceColumns recoveryEvidenceColumns(RecoveryEvidence evidence) throws SQLException {
        if (evidence == null) {
            return RecoveryEvidenceColumns.none();
        }
        if (!"ja.file.postimage".equals(evidence.typeId())
            || !evidence.payload().members().keySet().equals(java.util.Set.of(
                "relativePath", "expectedAfterBytes", "expectedAfterSha256"))) {
            throw new SQLException("Ja cannot project an unsupported recovery evidence type");
        }
        if (!(evidence.payload().get("relativePath") instanceof JsonText(String relativePath))
            || !(evidence.payload().get("expectedAfterBytes") instanceof JsonNumber(java.math.BigDecimal value1))
            || !(evidence.payload().get("expectedAfterSha256") instanceof JsonText(String value))) {
            throw new SQLException("Ja file recovery evidence fields are invalid");
        }
        long expectedBytes;
        try {
            expectedBytes = value1.longValueExact();
        } catch (ArithmeticException invalid) {
            throw new SQLException("Ja file recovery byte count is invalid", invalid);
        }
        if (!safeRelativePath(relativePath) || expectedBytes < 0 || !value.matches("[0-9a-f]{64}")) {
            throw new SQLException("Ja file recovery evidence violates its path or digest constraints");
        }
        return new RecoveryEvidenceColumns("FILE_TEXT", relativePath, value, expectedBytes);
    }

    /**
     * 恢复界面仅展示规范相对路径；持久化前拒绝盘符、绝对路径、反斜杠和目录穿越。
     */
    private static boolean safeRelativePath(String value) {
        return value != null && !value.isBlank() && !value.startsWith("/") && value.indexOf('\\') < 0
               && !value.matches("(?i)^[a-z]:.*")
               && !java.util.Arrays.asList(value.split("/", -1)).contains("..")
               && !java.util.Arrays.asList(value.split("/", -1)).contains("");
    }

    /**
     * 新恢复身份必须关联现有的 Ja Thread、Turn 和 Tool 展示行后才能创建审计投影。
     */
    private static void insertRecovery(Connection connection, AgentStoreTransactionContext transaction,
                                       ToolRecoveryDecision decision, RecoveryEvidenceColumns evidence)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                    INSERT INTO ja_tool_recovery(recovery_id,thread_id,turn_id,call_id,evidence_kind,state,\
                    recovery_revision,target_relative_path,expected_after_sha256,expected_after_bytes,\
                    operation_key,verified_at_ms,resolved_at_ms,created_at_ms,updated_at_ms) \
                    VALUES(?,?,?, ?,?,'PENDING', ?,?,?,?,NULL,NULL,NULL,?,?)\
                    """)) {
            statement.setString(1, decision.recoveryId());
            statement.setString(2, transaction.execution().conversation().threadId());
            statement.setString(3, transaction.execution().turnId());
            statement.setString(4, decision.callId());
            statement.setString(5, evidence.kind());
            statement.setLong(6, decision.revision());
            nullable(statement, 7, evidence.relativePath());
            nullable(statement, 8, evidence.sha256());
            if (evidence.bytes() == null) {
                statement.setNull(9, java.sql.Types.BIGINT);
            }
            else {
                statement.setLong(9, evidence.bytes());
            }
            statement.setLong(10, decision.updatedAt().toEpochMilli());
            statement.setLong(11, decision.updatedAt().toEpochMilli());
            requireOne(statement.executeUpdate(), "Ja recovery insert failed");
        }
    }

    /**
     * 按完整 recovery composite identity读取旧投影，跨 Turn 或 callId 命中一律视为数据损坏。
     */
    private static RecoveryRow readRecovery(Connection connection, String recoveryId, String threadId,
                                            String turnId, String callId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                    SELECT recovery_id,call_id,recovery_revision,state FROM ja_tool_recovery \
                    WHERE recovery_id=? AND thread_id=? AND turn_id=? AND call_id=?\
                    """)) {
            statement.setString(1, recoveryId);
            statement.setString(2, threadId);
            statement.setString(3, turnId);
            statement.setString(4, callId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                RecoveryRow value = new RecoveryRow(rows.getString(1), rows.getString(2), rows.getLong(3),
                        rows.getString(4));
                if (rows.next()) {
                    throw new SQLException("Ja recovery identity is not unique");
                }
                return value;
            }
        }
    }

    /**
     * 同 revision CAS 同时保存 UNKNOWN 结果的恢复结论和 Ja 时间戳投影。
     */
    private static void updateRecovery(Connection connection, AgentStoreTransactionContext transaction,
                                       RecoveryRow previous, ToolRecoveryDecision decision) throws SQLException {
        String state = recoveryProjectionState(decision.status());
        boolean verified = decision.status() == ToolRecoveryDecision.Status.VERIFIED;
        boolean resolved = decision.status() != ToolRecoveryDecision.Status.PENDING;
        try (PreparedStatement statement = connection.prepareStatement(
                """
                    UPDATE ja_tool_recovery SET state=?,recovery_revision=?,verified_at_ms=?,resolved_at_ms=?,updated_at_ms=? \
                    WHERE recovery_id=? AND thread_id=? AND turn_id=? AND call_id=? \
                    AND recovery_revision=? AND state='PENDING'\
                    """)) {
            statement.setString(1, state);
            statement.setLong(2, decision.revision());
            nullable(statement, 3, verified ? decision.updatedAt().toEpochMilli() : null);
            nullable(statement, 4, resolved ? decision.updatedAt().toEpochMilli() : null);
            statement.setLong(5, decision.updatedAt().toEpochMilli());
            statement.setString(6, decision.recoveryId());
            statement.setString(7, transaction.execution().conversation().threadId());
            statement.setString(8, transaction.execution().turnId());
            statement.setString(9, decision.callId());
            statement.setLong(10, previous.revision());
            requireOne(statement.executeUpdate(), "Ja recovery revision CAS failed");
        }
    }

    /**
     * 新控制事实和旧 UI 使用同一闭集状态，未知副作用在任何结果提交前一直显示为 PENDING。
     */
    private static String recoveryProjectionState(ToolRecoveryDecision.Status status) {
        return switch (status) {
            case PENDING -> "PENDING";
            case VERIFIED -> "VERIFIED";
            case RETRIED -> "RETRIED";
            case SKIPPED -> "SKIPPED";
        };
    }

    /**
     * 单独记录重试意图或终局核验动作，重启后不根据工具行状态重新派发。
     */
    private static void persistRecoveryAttempt(Connection connection, ToolRecoveryDecision decision)
            throws SQLException {
        String disposition;
        int attemptNumber;
        String operationKey;
        if (decision.status() == ToolRecoveryDecision.Status.PENDING
            && decision.disposition() == ToolRecoveryDecision.Disposition.RETRY) {
            disposition = "RETRY";
            attemptNumber = decision.attempt();
            operationKey = decision.attemptId();
        } else if (decision.status() == ToolRecoveryDecision.Status.VERIFIED) {
            disposition = "VERIFY";
            attemptNumber = nextRecoveryAttempt(connection, decision.recoveryId());
            operationKey = null;
        } else if (decision.status() == ToolRecoveryDecision.Status.SKIPPED) {
            disposition = "SKIP";
            attemptNumber = nextRecoveryAttempt(connection, decision.recoveryId());
            operationKey = null;
        } else {
            validateRecoveryAttempt(connection, decision);
            return;
        }
        String attemptId = "rattempt_" + sha256(decision.recoveryId() + "\0" + decision.revision()
                                                + "\0" + disposition + "\0" + Objects.toString(operationKey, ""));
        try (PreparedStatement find = connection.prepareStatement(
                """
                    SELECT recovery_attempt_id,disposition,operation_key FROM ja_tool_recovery_attempt \
                    WHERE recovery_id=? AND attempt_number=?\
                    """)) {
            find.setString(1, decision.recoveryId());
            find.setInt(2, attemptNumber);
            try (ResultSet rows = find.executeQuery()) {
                if (rows.next()) {
                    if (!attemptId.equals(rows.getString(1)) || !disposition.equals(rows.getString(2))
                        || !Objects.equals(operationKey, rows.getString(3))) {
                        throw new SQLException("Ja recovery attempt conflicts with persisted audit");
                    }
                    return;
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                """
                    INSERT INTO ja_tool_recovery_attempt(recovery_attempt_id,recovery_id,attempt_number,\
                    disposition,operation_key,created_at_ms) VALUES(?,?,?,?,?,?)\
                    """)) {
            insert.setString(1, attemptId);
            insert.setString(2, decision.recoveryId());
            insert.setInt(3, attemptNumber);
            insert.setString(4, disposition);
            nullable(insert, 5, operationKey);
            insert.setLong(6, decision.updatedAt().toEpochMilli());
            requireOne(insert.executeUpdate(), "Ja recovery attempt insert failed");
        }
    }

    /**
     * 已结算的 RETRY 必须找到此前记录的稳定派发意图，不能补造一个事后 attempt。
     */
    private static void validateRecoveryAttempt(Connection connection, ToolRecoveryDecision decision)
            throws SQLException {
        if ((decision.status() == ToolRecoveryDecision.Status.PENDING
             && decision.disposition() == ToolRecoveryDecision.Disposition.RETRY)
            || decision.status() == ToolRecoveryDecision.Status.RETRIED) {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                        SELECT disposition,operation_key FROM ja_tool_recovery_attempt \
                        WHERE recovery_id=? AND attempt_number=?\
                        """)) {
                statement.setString(1, decision.recoveryId());
                statement.setInt(2, decision.attempt());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next() || !"RETRY".equals(rows.getString(1))
                        || !decision.attemptId().equals(rows.getString(2))) {
                        throw new SQLException("Ja recovery retry intent is missing");
                    }
                }
            }
        }
    }

    /**
     * 唯一真实当前最大尝试号用于 VERIFY/SKIP 审计顺序，不作为工具重放次数预算。
     */
    private static int nextRecoveryAttempt(Connection connection, String recoveryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(attempt_number),0)+1 FROM ja_tool_recovery_attempt WHERE recovery_id=?")) {
            statement.setString(1, recoveryId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Ja recovery attempt sequence is unavailable");
                }
                return rows.getInt(1);
            }
        }
    }

    /**
     * 建立 PREPARED 展示行时要求 namespace presentation 与 Turn 全局序号同一事务到达，Core batch ordinal 不改写。
     */
    private static void insertTool(Connection connection, AgentStoreTransactionContext transaction,
                                   ToolPrepared prepared, int ordinal,
                                   JaProjectionPayloadCodec.ToolPresentationSnapshot presentation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                    INSERT INTO ja_tool_call(call_id,thread_id,turn_id,ordinal,tool_name,side_effect,presentation_json,\
                    artifact_id,\
                    state,revision,created_at_ms,updated_at_ms) VALUES(?,?,?,?,?,?,?,NULL,'PREPARED',?,?,?)\
                    """)) {
            statement.setString(1, prepared.callId());
            statement.setString(2, transaction.execution().conversation().threadId());
            statement.setString(3, transaction.execution().turnId());
            statement.setInt(4, ordinal);
            statement.setString(5, prepared.toolName());
            statement.setString(6, prepared.sideEffect().name());
            statement.setString(7, presentation.presentationJson());
            statement.setLong(8, presentation.revision());
            statement.setLong(9, transaction.occurredAt().toEpochMilli());
            statement.setLong(10, transaction.occurredAt().toEpochMilli());
            requireOne(statement.executeUpdate(), "Ja tool insert failed");
        }
    }

    /**
     * 工具执行授权绑定必须可逐字段解释为旧 Ja 路由列，Core 的 permission fingerprint 仍保留在通用事实中。
     */
    private static void insertToolBinding(Connection connection, AgentStoreTransactionContext transaction,
                                          ToolPrepared prepared, ToolExecutionBinding binding) throws SQLException {
        JsonObject route = binding.route();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                    INSERT INTO ja_tool_binding(turn_id,batch_id,call_id,route_kind,local_name,server_id,remote_name,\
                    schema_hash,route_hash,catalog_revision,access_mode,created_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)\
                    """)) {
            statement.setString(1, transaction.execution().turnId());
            statement.setString(2, prepared.batchId());
            statement.setString(3, prepared.callId());
            statement.setString(4, routeText(route, "routeKind"));
            statement.setString(5, routeText(route, "localName"));
            statement.setString(6, routeText(route, "serverId"));
            statement.setString(7, routeText(route, "remoteName"));
            statement.setString(8, routeText(route, "schemaHash"));
            statement.setString(9, routeText(route, "routeHash"));
            statement.setString(10, binding.catalogFingerprint());
            statement.setString(11, routeText(route, "accessMode"));
            statement.setLong(12, transaction.occurredAt().toEpochMilli());
            requireOne(statement.executeUpdate(), "Ja tool binding insert failed");
        }
    }

    /**
     * 查询结果行而不接受已完成行重放，保护未派发分支不吞掉真实 PREPARED 事实。
     */
    private static boolean hasToolProjection(Connection connection, AgentStore.ExecutionKey execution,
                                             String callId) throws SQLException {
        return exists(connection, "SELECT 1 FROM ja_tool_call WHERE thread_id=? AND turn_id=? AND call_id=?",
                execution.conversation().threadId(), execution.turnId(), callId);
    }

    /**
     * 绑定行代表参数和授权已冻结，未派发终态不得将其伪装成从未准备过。
     */
    private static boolean hasToolBinding(Connection connection, AgentStore.ExecutionKey execution,
                                          String callId) throws SQLException {
        return exists(connection, """
            SELECT 1 FROM ja_tool_binding b JOIN ja_tool_call t \
            ON t.turn_id=b.turn_id AND t.call_id=b.call_id \
            WHERE t.thread_id=? AND b.turn_id=? AND b.call_id=?\
            """,
                execution.conversation().threadId(), execution.turnId(), callId);
    }

    /**
     * 所有被写入的 presentation 都要与提交后的 Ja tools.state 完全相等，不能只通过状态优先级折算。
     */
    static void validatePresentationStates(Connection connection, AgentStoreTransactionContext transaction,
                                                   Map<String, JaProjectionPayloadCodec.ToolPresentationSnapshot> values)
            throws SQLException {
        for (JaProjectionPayloadCodec.ToolPresentationSnapshot presentation : values.values()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT state FROM ja_tool_call WHERE thread_id=? AND turn_id=? AND call_id=?")) {
                statement.setString(1, transaction.execution().conversation().threadId());
                statement.setString(2, transaction.execution().turnId());
                statement.setString(3, presentation.callId());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next() || !presentation.state().name().equals(rows.getString(1))) {
                        throw new SQLException("Ja tool presentation differs from committed outcome");
                    }
                }
            }
        }
    }

    /**
     * 对 route JSON 做强类型字段读取；缺失或变类型必须回滚权限绑定，而不能用空字符串顶替。
     */
    private static String routeText(JsonObject route, String field) throws SQLException {
        if (!(route.get(field) instanceof JsonText(String value1))) {
            throw new SQLException("Ja tool route is incomplete");
        }
        return value1;
    }

    /**
     * 按 ModelCompleted 的原生调用顺序持久化无参数预览，不把提议伪装成已校验的 PREPARED Tool。
     */
    void applyModelCallPreviews(Connection connection, AgentStoreTransactionContext transaction,
                                        Map<String, ExecutionFact> executionFactsByRecordId) throws SQLException {
        Set<String> completedMessages = new HashSet<>();
        for (int eventIndex = 0; eventIndex < transaction.events().size(); eventIndex++) {
            AgentStore.EventWrite event = transaction.events().get(eventIndex);
            if (!"agent.model.completed".equals(event.eventType())) {
                continue;
            }
            ExecutionEvent decoded;
            try {
                decoded = executionEvents.decode(event.eventType(), event.payloadSchemaVersion(), jsonValues.encode(event.payload()));
            } catch (RuntimeException invalid) {
                throw new SQLException("Ja model-completed event is invalid", invalid);
            }
            if (!(decoded instanceof ExecutionEvent.ModelCompleted completed)
                || !completedMessages.add(completed.messageRecordId())) {
                throw new SQLException("Ja model-completed event identity is duplicated or invalid");
            }
            ExecutionFact messageFact = executionFactsByRecordId.get(completed.messageRecordId());
            if (!(messageFact instanceof AssistantMessage assistant)) {
                throw new SQLException("Ja model-completed event has no referenced assistant message fact");
            }
            List<ToolCallContent> calls = assistant.message().content().stream()
                    .filter(ToolCallContent.class::isInstance).map(ToolCallContent.class::cast).toList();
            if (!completed.toolCallIds().equals(calls.stream().map(ToolCallContent::callId).toList())) {
                throw new SQLException("Ja model-completed tool order differs from assistant message");
            }
            long sequence;
            try {
                sequence = Math.addExact(transaction.firstEventSequence(), eventIndex);
            } catch (ArithmeticException overflow) {
                throw new SQLException("Ja ModelCompleted event sequence overflows", overflow);
            }
            for (int ordinal = 0; ordinal < calls.size(); ordinal++) {
                ToolCallContent call = calls.get(ordinal);
                AgentTool.Invocation invocation = new AgentTool.Invocation(call.callId(), call.name(),
                        call.arguments(), ordinal, call.preflightFailureCode());
                ToolPresentation proposal;
                try {
                    proposal = ToolPresentationProjector.modelCallPreview(invocation);
                } catch (RuntimeException invalid) {
                    throw new SQLException("Ja model tool preview cannot be projected", invalid);
                }
                if (proposal.status() != ToolPresentation.Status.PENDING) {
                    throw new SQLException("Ja model tool preview must remain a non-authorizing proposal");
                }
                insertToolCallPreview(connection, transaction, completed.messageRecordId(), event,
                        sequence, invocation, toolPresentations.write(proposal));
            }
        }
    }

    /**
     * 以执行事实推进 Ja 工具行；媒体仓储借用当前连接，图片 owner 与 receipt 共用 Store 回滚边界。
     */
    void applyTools(Connection connection, AgentStoreTransactionContext transaction,
                            List<ExecutionFact> values, Map<String, ExecutionFact> factsByRecordId,
                            Map<String, JaProjectionPayloadCodec.ToolPresentationSnapshot> presentations)
            throws SQLException {
        Map<String, ToolPrepared> prepared = new LinkedHashMap<>();
        Map<String, Integer> batchOrder = new HashMap<>();
        for (ExecutionFact fact : values) {
            if (fact instanceof ToolPrepared value) {
                batchOrder.computeIfAbsent(value.batchId(), ignored -> batchOrder.size());
                prepared.put(value.callId(), value);
            }
        }
        List<ToolPrepared> orderedPrepared = new ArrayList<>(prepared.values());
        orderedPrepared.sort(Comparator.comparingInt((ToolPrepared value) -> batchOrder.get(value.batchId()))
                .thenComparingInt(ToolPrepared::ordinal)
                .thenComparing(ToolPrepared::callId));
        long nextOrdinal = orderedPrepared.isEmpty() ? 0 : nextToolOrdinal(connection,
                transaction.execution().turnId());
        for (ToolPrepared value : orderedPrepared) {
            JaProjectionPayloadCodec.ToolPresentationSnapshot presentation = presentations.get(value.callId());
            boolean missingPresentation = presentation == null;
            String previewJson = null;
            boolean matchingProposal = false;
            if (missingPresentation || presentation.state() != ToolState.PREPARED) {
                previewJson = matchingToolCallPreviewJson(connection, transaction.execution(), value);
                matchingProposal = previewJson != null;
            }
            if (missingPresentation) {
                if (!hasMatchingUnstartedResult(transaction, values, factsByRecordId, value, null,
                        matchingProposal)) {
                    throw new SQLException("Ja prepared tool presentation is missing or inconsistent");
                }
                presentation = unavailableToolPresentation(value, previewJson);
            }
            if (presentation == null || !presentation.callId().equals(value.callId())
                || (presentation.state() != ToolState.PREPARED && missingPresentation == false
                    && !hasMatchingUnstartedResult(transaction, values, factsByRecordId, value, presentation,
                            matchingProposal))) {
                throw new SQLException("Ja prepared tool presentation is missing or inconsistent");
            }
            insertTool(connection, transaction, value, Math.toIntExact(nextOrdinal++), presentation);
            if (value.binding() != null) {
                insertToolBinding(connection, transaction, value, value.binding());
            }
        }
        for (ExecutionFact fact : values) {
            if (fact instanceof ToolStarted started) {
                if (!updateToolState(connection, transaction.execution(), started.callId(), ToolState.RUNNING,
                        transaction.occurredAt())) {
                    throw new SQLException("Ja started tool has no prepared projection");
                }
            } else if (fact instanceof ToolResult result) {
                if (!updateToolState(connection, transaction.execution(), result.callId(), result.state(),
                        transaction.occurredAt())) {
                    validateUnpreparedTerminalResult(connection, transaction, values, result);
                }
            }
        }
        for (JaProjectionPayloadCodec.ToolPresentationSnapshot presentation : presentations.values()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ja_tool_call SET presentation_json=?,revision=?,updated_at_ms=? WHERE turn_id=? AND call_id=?")) {
                statement.setString(1, presentation.presentationJson());
                statement.setLong(2, presentation.revision());
                statement.setLong(3, transaction.occurredAt().toEpochMilli());
                statement.setString(4, transaction.execution().turnId());
                statement.setString(5, presentation.callId());
                if (statement.executeUpdate() == 0 && !prepared.containsKey(presentation.callId())) {
                    throw new SQLException("Ja presentation has no matching prepared tool");
                }
            }
        }
        AgentStoreConnectionScope.call(connection, () -> {
            bindSuccessfulToolResultMedia(transaction, values);
            return null;
        });
    }

    /**
     * 成功 ToolResult 的图片 owner 与 receipt 同事务提交；失败、孤立或内容不匹配的引用均拒绝绑定。
     */
    private void bindSuccessfulToolResultMedia(AgentStoreTransactionContext transaction,
                                               List<ExecutionFact> values) throws SQLException {
        Map<String, List<ToolResult>> resultsByCall = new HashMap<>();
        for (ExecutionFact fact : values) {
            if (fact instanceof ToolResult result) {
                resultsByCall.computeIfAbsent(result.callId(), ignored -> new ArrayList<>()).add(result);
            }
        }
        for (ExecutionFact fact : values) {
            if (!(fact instanceof ToolMessage message)) {
                continue;
            }
            for (io.github.kongweiguang.ja.agent.domain.model.ModelContent content : message.message().content()) {
                if (!(content instanceof ToolResultContent result) || result.media().isEmpty()) {
                    continue;
                }
                List<ToolResult> matching = resultsByCall.getOrDefault(result.callId(), List.of());
                if (matching.size() != 1 || matching.getFirst().state() != ToolState.SUCCEEDED
                    || matching.getFirst().error() || result.error()
                    || !matching.getFirst().content().equals(result.content())) {
                    throw new SQLException("Ja ToolResult media has no matching successful receipt");
                }
                List<ToolMediaReference> media = result.media();
                for (int ordinal = 0; ordinal < media.size(); ordinal++) {
                    attachments.bindToolResultAttachment(new AttachmentRepository.ToolResultAttachmentBind(
                            media.get(ordinal).attachmentId(),
                            transaction.execution().conversation().threadId(),
                            transaction.execution().turnId(), result.callId(), ordinal, transaction.occurredAt()));
                }
            }
        }
    }

    /** 只接受唯一提案、Prepared、无 Started、同态结果消息及确切模型提案身份。 */
    private boolean hasMatchingUnstartedResult(AgentStoreTransactionContext transaction,
            List<ExecutionFact> factsInCommit, Map<String, ExecutionFact> factsByRecordId,
            ToolPrepared prepared, JaProjectionPayloadCodec.ToolPresentationSnapshot presentation,
            boolean matchingProposal) throws SQLException {
        ToolState expectedState = presentation == null ? ToolState.FAILED : presentation.state();
        if (presentation == null) {
            if (!hasMatchingUnavailableToolCompletion(transaction, factsByRecordId, prepared.callId())) {
                return false;
            }
        } else if (expectedState != ToolState.FAILED && expectedState != ToolState.CANCELLED) {
            return false;
        }
        List<ToolPrepared> matchingPrepared = factsInCommit.stream().filter(ToolPrepared.class::isInstance)
                .map(ToolPrepared.class::cast).filter(value -> value.callId().equals(prepared.callId())).toList();
        if (matchingPrepared.size() != 1 || !prepared.equals(matchingPrepared.getFirst())
            || factsInCommit.stream().anyMatch(fact -> fact instanceof ToolStarted started
                                                       && started.callId().equals(prepared.callId()))) {
            return false;
        }
        List<ToolResult> matchingResults = factsInCommit.stream().filter(ToolResult.class::isInstance)
                .map(ToolResult.class::cast).filter(result -> result.callId().equals(prepared.callId())).toList();
        if (matchingResults.size() != 1 || matchingResults.getFirst().state() != expectedState
            || (presentation == null && !matchingResults.getFirst().error())) {
            return false;
        }
        ToolResult result = matchingResults.getFirst();
        List<ToolResultContent> matchingMessages = factsInCommit.stream().filter(ToolMessage.class::isInstance)
                .map(ToolMessage.class::cast).flatMap(message -> message.message().content().stream())
                .filter(ToolResultContent.class::isInstance).map(ToolResultContent.class::cast)
                .filter(message -> message.callId().equals(prepared.callId())).toList();
        return matchingMessages.size() == 1 && matchingMessages.getFirst().content().equals(result.content())
               && matchingMessages.getFirst().error() == result.error()
               && matchingProposal;
    }

    /**
     * 缺失展示事实只在 Core 明确标记 TOOL_NOT_AVAILABLE 时使用唯一匹配的 ToolResult 和 ToolMessage 回执。
     */
    private boolean hasMatchingUnavailableToolCompletion(AgentStoreTransactionContext transaction,
            Map<String, ExecutionFact> factsByRecordId, String callId) throws SQLException {
        List<Map.Entry<String, ToolResult>> results = factsByRecordId.entrySet().stream()
                .filter(entry -> entry.getValue() instanceof ToolResult result && result.callId().equals(callId))
                .map(entry -> Map.entry(entry.getKey(), (ToolResult) entry.getValue())).toList();
        List<Map.Entry<String, ToolMessage>> messages = factsByRecordId.entrySet().stream()
                .filter(entry -> entry.getValue() instanceof ToolMessage message
                        && message.message().content().stream().anyMatch(content ->
                                content instanceof ToolResultContent result && result.callId().equals(callId)))
                .map(entry -> Map.entry(entry.getKey(), (ToolMessage) entry.getValue())).toList();
        if (results.size() != 1 || messages.size() != 1 || results.getFirst().getValue().state() != ToolState.FAILED
            || !results.getFirst().getValue().error()) {
            return false;
        }
        List<ExecutionEvent.ToolCompleted> completions = new ArrayList<>();
        for (AgentStore.EventWrite write : transaction.events()) {
            if (!"agent.tool.completed".equals(write.eventType())) {
                continue;
            }
            ExecutionEvent event;
            try {
                event = executionEvents.decode(write.eventType(), write.payloadSchemaVersion(), jsonValues.encode(write.payload()));
            } catch (RuntimeException invalid) {
                throw new SQLException("Ja tool completion event is invalid", invalid);
            }
            if (event instanceof ExecutionEvent.ToolCompleted completed && completed.callId().equals(callId)) {
                completions.add(completed);
            }
        }
        return completions.size() == 1
               && completions.getFirst().state() == ToolState.FAILED
               && "TOOL_NOT_AVAILABLE".equals(completions.getFirst().errorCode())
               && completions.getFirst().resultRecordId().equals(results.getFirst().getKey())
               && completions.getFirst().toolMessageRecordId().equals(messages.getFirst().getKey());
    }

    /**
     * 调用方已校验同批唯一 TOOL_NOT_AVAILABLE 回执；仅沿用提案的类型和标题，清除待校验占位与参数，
     * 用固定原因让错误详情可读而不依赖模型或 Tool 的自由正文。
     *
     * @author kongweiguang
     */
    private JaProjectionPayloadCodec.ToolPresentationSnapshot unavailableToolPresentation(
            ToolPrepared prepared, String previewJson) throws SQLException {
        if (previewJson == null) {
            throw new SQLException("Ja unavailable tool has no matching model-call preview");
        }
        try {
            ToolPresentation preview = toolPresentations.read(previewJson);
            if (preview.status() != ToolPresentation.Status.PENDING) {
                throw new SQLException("Ja unavailable tool preview is not pending");
            }
            ToolPresentation error = new ToolPresentation(preview.kind(), preview.title(),
                    ToolPresentation.Status.ERROR, null, "工具当前不可用，本次调用未执行。", null, List.of(),
                    null, null, null, null, null, null, false, null);
            return new JaProjectionPayloadCodec.ToolPresentationSnapshot(prepared.callId(), 1, ToolState.FAILED,
                    toolPresentations.write(error));
        } catch (RuntimeException invalid) {
            throw new SQLException("Ja unavailable tool preview is invalid", invalid);
        }
    }

    /** 按稳定执行键读取唯一提案，并同时校验 callId、Core batch ordinal、名称和待校验快照。 */
    private static String matchingToolCallPreviewJson(Connection connection, AgentStore.ExecutionKey execution,
                                                       ToolPrepared prepared) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ordinal,tool_name,presentation_json FROM ja_tool_call_preview "
                        + "WHERE thread_id=? AND turn_id=? AND call_id=?")) {
            statement.setString(1, execution.conversation().threadId());
            statement.setString(2, execution.turnId());
            statement.setString(3, prepared.callId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                int ordinal = rows.getInt(1);
                String toolName = rows.getString(2);
                String presentationJson = rows.getString(3);
                return !rows.next() && ordinal == prepared.ordinal() && prepared.toolName().equals(toolName)
                        ? presentationJson : null;
            }
        }
    }

    /**
     * 读取同一 Turn 已提交的展示水位，使新 batch 的局部序号不会与历史 Ja 工具行冲突。
     */
    private static long nextToolOrdinal(Connection connection, String turnId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(ordinal), -1) FROM ja_tool_call WHERE turn_id=?")) {
            statement.setString(1, turnId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Ja tool ordinal query returned no row");
                }
                return Math.addExact(rows.getLong(1), 1);
            }
        }
    }

    /**
     * 将 Core 的恢复裁决映射到 Ja 审计表，且只接受同次提交内引用的真实 ToolResult。
     */
    void applyRecoveries(Connection connection, AgentStoreTransactionContext transaction,
                                 List<ExecutionFact> factsInCommit,
                                 List<AgentStore.StoredRecord> controlRecords) throws SQLException {
        Map<String, ToolResultReference> results = toolResultsInCommit(transaction);
        for (AgentStore.StoredRecord record : controlRecords) {
            if (!ExecutionControlCodec.TOOL_RECOVERY_RECORD_TYPE.equals(record.recordType())) {
                continue;
            }
            ToolRecoveryDecision decision;
            try {
                decision = controls.decodeToolRecoveryDecision(record);
            } catch (RuntimeException invalid) {
                throw new SQLException("Ja tool recovery decision is invalid", invalid);
            }
            ToolResultReference result = null;
            if (decision.toolResultRecordId() != null) {
                result = results.get(decision.toolResultRecordId());
                if (result == null || !decision.callId().equals(result.callId())) {
                    throw new SQLException("Ja recovery outcome does not reference its committed tool result");
                }
            }
            RecoveryEvidence evidence = findRecoveryEvidence(connection, transaction, factsInCommit,
                    decision.callId());
            applyRecoveryDecision(connection, transaction, decision, evidence, result);
        }
    }

    /**
     * 索引本次事务中新写入的 ToolResult recordId，防止裁决用 callId 猜测关联结果。
     */
    private Map<String, ToolResultReference> toolResultsInCommit(AgentStoreTransactionContext transaction)
            throws SQLException {
        Map<String, ToolResultReference> values = new HashMap<>();
        for (AgentStore.RecordWrite record : transaction.records()) {
            if (!"agent.tool.result".equals(record.recordType())) {
                continue;
            }
            ExecutionFact fact;
            try {
                fact = facts.decode(record.recordType(), record.payloadSchemaVersion(), jsonValues.encode(record.payload()));
            } catch (RuntimeException invalid) {
                throw new SQLException("Ja committed tool result is invalid", invalid);
            }
            if (!(fact instanceof ToolResult result)) {
                throw new SQLException("Tool result record has wrong fact type");
            }
            if (values.putIfAbsent(record.recordId(), new ToolResultReference(result.callId(), result.state())) != null) {
                throw new SQLException("duplicate Tool result record identity");
            }
        }
        return values;
    }

    /**
     * 历史证据可能早于当前事务，按相同租户、会话和执行读取追加日志而不跨执行搜索。
     */
    private RecoveryEvidence findRecoveryEvidence(Connection connection,
                                                  AgentStoreTransactionContext transaction,
                                                  List<ExecutionFact> factsInCommit, String callId)
            throws SQLException {
        for (ExecutionFact fact : factsInCommit) {
            if (fact instanceof RecoveryEvidence evidence && callId.equals(evidence.callId())) {
                return evidence;
            }
        }
        String sql = """
            SELECT record_type,payload_schema_version,payload_json FROM agent_record \
            WHERE tenant_id=? AND thread_id=? AND turn_id=? AND record_type='agent.tool.recovery' \
            ORDER BY record_sequence DESC\
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, transaction.execution().conversation().tenantId());
            statement.setString(2, transaction.execution().conversation().threadId());
            statement.setString(3, transaction.execution().turnId());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ExecutionFact decoded;
                    try {
                        decoded = facts.decode(rows.getString(1), rows.getInt(2), rows.getString(3));
                    } catch (RuntimeException invalid) {
                        throw new SQLException("Ja stored recovery evidence is invalid", invalid);
                    }
                    if (!(decoded instanceof RecoveryEvidence evidence)) {
                        throw new SQLException("Agent recovery record has wrong fact type");
                    }
                    if (callId.equals(evidence.callId())) {
                        return evidence;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 校验恢复证据、裁决 revision 和结果身份后，原子更新 Ja 恢复审计状态及尝试行。
     */
    private void applyRecoveryDecision(Connection connection, AgentStoreTransactionContext transaction,
                                       ToolRecoveryDecision decision, RecoveryEvidence evidence,
                                       ToolResultReference result) throws SQLException {
        RecoveryEvidenceColumns columns = recoveryEvidenceColumns(evidence);
        String threadId = transaction.execution().conversation().threadId();
        String turnId = transaction.execution().turnId();
        RecoveryRow previous = readRecovery(connection, decision.recoveryId(), threadId, turnId, decision.callId());
        if (previous == null) {
            if (decision.revision() != 1 || decision.status() != ToolRecoveryDecision.Status.PENDING
                || decision.toolResultRecordId() != null) {
                throw new SQLException("Ja recovery must begin with a pending unknown outcome");
            }
            insertRecovery(connection, transaction, decision, columns);
        } else {
            if (!previous.recoveryId().equals(decision.recoveryId())
                || !previous.callId().equals(decision.callId())) {
                throw new SQLException("Ja recovery owner differs from durable Core decision");
            }
            if (previous.revision() == decision.revision()) {
                if (!previous.state().equals(recoveryProjectionState(decision.status()))) {
                    throw new SQLException("Ja recovery state conflicts at the same revision");
                }
                validateRecoveryAttempt(connection, decision);
                return;
            }
            if (decision.revision() != previous.revision() + 1 || !"PENDING".equals(previous.state())) {
                throw new SQLException("Ja recovery decision is not a valid next transition");
            }
            updateRecovery(connection, transaction, previous, decision);
        }
        persistRecoveryAttempt(connection, decision);
        if (decision.toolResultRecordId() != null && result == null) {
            throw new SQLException("Ja settled recovery is missing its linked result");
        }
    }

    /**
     * ToolStarted 同事务推进脱敏展示状态，不能让重连历史把真实 RUNNING 读成 PENDING。
     */
    private boolean updateToolState(Connection connection, AgentStore.ExecutionKey execution,
                                    String callId, ToolState state, Instant occurredAt) throws SQLException {
        String presentationJson = null;
        if (state == ToolState.RUNNING) {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT presentation_json FROM ja_tool_call WHERE turn_id=? AND call_id=? AND state='PREPARED'")) {
                query.setString(1, execution.turnId());
                query.setString(2, callId);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        return false;
                    }
                    ToolPresentation prepared = toolPresentations.read(rows.getString(1));
                    if (prepared.status() != ToolPresentation.Status.PENDING || rows.next()) {
                        throw new SQLException("Ja prepared tool presentation is not pending");
                    }
                    presentationJson = toolPresentations.write(ToolPresentationProjector.withStatus(
                            prepared, ToolPresentation.Status.RUNNING));
                }
            }
        }
        if (presentationJson != null) {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                        UPDATE ja_tool_call SET state=?,presentation_json=?,updated_at_ms=? WHERE turn_id=? AND call_id=? \
                        AND state='PREPARED'\
                        """)) {
                statement.setString(1, state.name());
                statement.setString(2, presentationJson);
                statement.setLong(3, occurredAt.toEpochMilli());
                statement.setString(4, execution.turnId());
                statement.setString(5, callId);
                return statement.executeUpdate() == 1;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                """
                    UPDATE ja_tool_call SET state=?,updated_at_ms=? WHERE turn_id=? AND call_id=? \
                    AND state IN ('PREPARED','RUNNING')\
                    """)) {
            statement.setString(1, state.name());
            statement.setLong(2, occurredAt.toEpochMilli());
            statement.setString(3, execution.turnId());
            statement.setString(4, callId);
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * 仅既有无参数模型提案可以直达未派发失败或取消，且结果消息与真实结果必须同次提交。
     */
    private void validateUnpreparedTerminalResult(Connection connection, AgentStoreTransactionContext transaction,
                                                  List<ExecutionFact> factsInCommit, ToolResult result)
            throws SQLException {
        if (result.state() != ToolState.FAILED && result.state() != ToolState.CANCELLED
            || factsInCommit.stream().anyMatch(fact -> fact instanceof ToolPrepared prepared
                                                       && prepared.callId().equals(result.callId())
                                                       || fact instanceof ToolStarted started && started.callId().equals(result.callId()))) {
            throw new SQLException("Ja unprepared tool result is not a permitted terminal outcome");
        }
        long matchingMessages = factsInCommit.stream().filter(ToolMessage.class::isInstance)
                .map(ToolMessage.class::cast).flatMap(message -> message.message().content().stream())
                .filter(ToolResultContent.class::isInstance).map(ToolResultContent.class::cast)
                .filter(message -> message.callId().equals(result.callId())
                                   && message.content().equals(result.content()) && message.error() == result.error()).count();
        if (matchingMessages != 1 || hasToolProjection(connection, transaction.execution(), result.callId())
            || hasToolBinding(connection, transaction.execution(), result.callId())) {
            throw new SQLException("Ja unprepared tool outcome lacks a unique same-commit result message");
        }
        String previewJson;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT presentation_json FROM ja_tool_call_preview WHERE thread_id=? AND turn_id=? AND call_id=?")) {
            query.setString(1, transaction.execution().conversation().threadId());
            query.setString(2, transaction.execution().turnId());
            query.setString(3, result.callId());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Ja unprepared tool outcome has no model-call preview");
                }
                previewJson = rows.getString(1);
                if (rows.next()) {
                    throw new SQLException("Ja model-call preview identity is ambiguous");
                }
            }
        }
        if (toolPresentations.read(previewJson).status() != ToolPresentation.Status.PENDING) {
            throw new SQLException("Ja unprepared tool preview is not pending");
        }
    }

    /**
     * 为 UI 关联 exact generic ToolResult，状态事实本身仍由 AgentStore 保持权威。
     *
     * @param callId 模型生成的工具调用标识
     * @param state 当前状态
     */
    private record ToolResultReference(String callId,
                                       ToolState state) {
    }

    /**
     * Ja 工具恢复表只展示文件后像证据，具体指纹和调用事实由 Core 追加记录保存。
     *
     * @param kind 类型分类
     * @param relativePath 相对于工作区的路径
     * @param sha256 内容的 SHA-256 摘要
     * @param bytes 字节数
     */
    private record RecoveryEvidenceColumns(String kind,
                                           String relativePath,
                                           String sha256,
                                           Long bytes) {
        /**
         * 没有恢复证据时显式使用既有无证据类型。
         */
        private static RecoveryEvidenceColumns none() {
            return new RecoveryEvidenceColumns("NONE", null, null, null);
        }
    }

    /**
     * Ja 恢复行的内部 CAS 快照。
     *
     * @param recoveryId 恢复标识
     * @param callId 模型生成的工具调用标识
     * @param revision 当前修订版本
     * @param state 当前状态
     */
    private record RecoveryRow(String recoveryId,
                               String callId,
                               long revision,
                               String state) {
    }
}
