// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.context.ToolProjection;
import io.github.kongweiguang.ja.agent.domain.conversation.ContextInstruction;
import io.github.kongweiguang.ja.agent.domain.conversation.UserMessage;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFact;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.Approval;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.AssistantMessage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ModelRequestIdentity;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ReasoningSummary;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.RecoveryEvidence;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolMessage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolPrepared;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolRecoveryVerified;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolResult;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolRetryIntent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolStarted;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.Usage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.UsageCertainty;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.UsagePurpose;


import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.tool.ToolExecutionBinding;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolState;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentRecordIds;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValues;

import java.time.Instant;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * 在线提交与事务数据迁移共用的版本化执行事实编解码器。
 */
public final class StorePayloadCodec implements ExecutionRecordCodec {
    /**
     * 所有记录共用的载荷格式版本；持久结构变化必须配套明确迁移。
     */
    public static final int SCHEMA_VERSION = 1;
    private final ObjectMapper mapper;
    private final JacksonJsonValueCodec json;
    private final PersistenceCodec messages;
    private final InitialContextSeedCodec initialSeeds;

    /**
     * 复制宿主映射器并固定精确十进制解析，持久参数与绑定不能因浮点转换或小数位归一化发生漂移。
     */
    public StorePayloadCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy()
                .disable(com.fasterxml.jackson.core.JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION)
                .configure(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
                .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
        json = new JacksonJsonValueCodec(this.mapper);
        messages = new PersistenceCodec(this.mapper);
        initialSeeds = new InitialContextSeedCodec(json);
    }

    /**
     * 严格恢复父 Seed 来源与 ordinal，避免把不相关会话摘要用于当前模型请求。
     */
    private static Optional<SeedSummaryReference> readSeedSummaryReference(JsonNode value) {
        if (value.isNull()) {
            return Optional.empty();
        }
        exact(value, "sourceIdentity", "sourceOrdinal");
        JsonNode source = required(value, "sourceIdentity");
        exact(source, "tenantId", "threadId", "turnId");
        return Optional.of(new SeedSummaryReference(new InitialContextSeed.SourceIdentity(
                text(source, "tenantId"), text(source, "threadId"), text(source, "turnId")),
                number(value, "sourceOrdinal")));
    }

    /**
     * checkpoint 标识在冻结时必须满足与恢复路径相同的长度及控制字符约束。
     */
    private static boolean validCheckpointId(String value) {
        return !value.isBlank() && value.length() <= 256 && value.indexOf('\0') < 0
               && value.indexOf('\n') < 0 && value.indexOf('\r') < 0;
    }

    /**
     * 空绑定仅表示原始调用指向未知工具；恢复时不推断或替换成当前同名工具。
     */
    private static ToolExecutionBinding readBinding(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        exact(node, "toolName", "schemaFingerprint", "catalogFingerprint", "permissionFingerprint", "route");
        return new ToolExecutionBinding(text(node, "toolName"), text(node, "schemaFingerprint"),
                text(node, "catalogFingerprint"), text(node, "permissionFingerprint"), object(node, "route"));
    }

    /**
     * 接受自定义供应商的协议标识，但拒绝缺失或格式错误的身份元数据。
     */
    private static ModelRequestIdentity readProfile(JsonNode node) {
        exact(node, "providerId", "modelId", "api", "upstreamModel", "requestedReasoning", "effectiveReasoning",
                "contextWindowTokens", "maxOutputTokens", "attributes");
        return new ModelRequestIdentity(text(node, "providerId"), text(node, "modelId"), text(node, "api"),
                text(node, "upstreamModel"), nullableText(node, "requestedReasoning"), nullableText(node, "effectiveReasoning"),
                integer(node, "contextWindowTokens"), integer(node, "maxOutputTokens"), object(node, "attributes"));
    }

    /**
     * 路由和扩展证据必须是 JSON 对象，不接受可被 Jackson 强制转换的其他值。
     */
    private static JsonObject object(JsonNode node, String field) {
        if (!(JacksonJsonValues.fromNode(required(node, field)) instanceof JsonObject value)) {
            throw corrupt();
        }
        return value;
    }

    /**
     * 原样恢复供应商用量，不重新计算总数，也不补造缺失的缓存计数。
     */
    private static ModelUsage readUsage(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        exact(node, "inputTokens", "outputTokens", "totalTokens", "cacheReadTokens", "cacheWriteTokens", "inputAccounting");
        return new ModelUsage(number(node, "inputTokens"), number(node, "outputTokens"), number(node, "totalTokens"),
                nullableNumber(node, "cacheReadTokens"), nullableNumber(node, "cacheWriteTokens"),
                ModelUsage.InputAccounting.valueOf(text(node, "inputAccounting")));
    }

    /**
     * 精确校验对象键集合，以区分字段缺失、未知扩展字段与合法的显式空值。
     */
    private static void exact(JsonNode node, String... names) {
        if (node == null || !node.isObject()) {
            throw corrupt();
        }
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(Set.of(names))) {
            throw corrupt();
        }
    }

    /**
     * 必需字段缺失表示记录损坏；显式空值只交由允许为空的字段读取器处理。
     */
    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw corrupt();
        }
        return value;
    }

    /**
     * 拒绝对象或数字等非文本节点，防止 Jackson 强制转换出伪造的有效标识。
     */
    private static String text(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isTextual()) {
            throw corrupt();
        }
        return value.textValue();
    }

    /**
     * 保留显式空值，区分“不可用”与“已测得但为空”的字段语义。
     */
    private static String nullableText(JsonNode node, String field) {
        return required(node, field).isNull() ? null : text(node, field);
    }

    /**
     * 转换前检查长整数范围，防止损坏序号溢出后伪装成有效比较并交换版本。
     */
    private static long number(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw corrupt();
        }
        return value.longValue();
    }

    /**
     * 用量计数允许为空，序列化往返后仍保持未知状态。
     */
    private static Long nullableNumber(JsonNode node, String field) {
        return required(node, field).isNull() ? null : number(node, field);
    }

    /**
     * 整数字段必须精确落入领域类型范围，不截断持久化数据。
     */
    private static int integer(JsonNode node, String field) {
        return Math.toIntExact(number(node, field));
    }

    /**
     * 严格读取 JSON 布尔字段，拒绝 Jackson 对文本或数值的宽松强制转换。
     */
    private static boolean bool(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isBoolean()) {
            throw corrupt();
        }
        return value.booleanValue();
    }

    /**
     * 存储异常不回显载荷正文，避免泄露用户内容或工具参数。
     */
    private static StorageException corrupt() {
        return new StorageException(StorageException.Code.INVALID_STATE, "invalid Agent store payload");
    }

    /** 时间损坏属于不可恢复的存储格式错误，保留解析根因而不回显载荷。 */
    private static Instant instant(JsonNode node, String field) {
        try {
            return Instant.parse(text(node, field));
        } catch (DateTimeException failure) {
            throw corrupt(failure);
        }
    }

    /**
     * 保留 Store 记录解码根因供本地诊断，稳定消息仍不回显用户或 Tool 数据。
     */
    private static StorageException corrupt(Throwable cause) {
        return new StorageException(StorageException.Code.INVALID_STATE, "invalid Agent store payload", cause);
    }

    /**
     * 以严格显式 Seed schema 编码背景历史，不让 Jackson 反射漏掉 opaque Reasoning metadata。
     */
    @Override
    public AgentStore.RecordWrite encodeInitialContextSeed(String recordId, InitialContextSeed seed,
                                                           Instant occurredAt) {
        Objects.requireNonNull(seed, "seed");
        return new AgentStore.RecordWrite(recordId, InitialContextSeedCodec.RECORD_TYPE,
                InitialContextSeedCodec.SCHEMA_VERSION, json.decodeObject(initialSeeds.encode(seed)), occurredAt);
    }

    /**
     * 只解码 Seed 自有类型和版本，损坏数据不能被误解释为无背景新会话。
     */
    @Override
    public InitialContextSeed decodeInitialContextSeed(AgentStore.StoredRecord record) {
        Objects.requireNonNull(record, "record");
        if (!InitialContextSeedCodec.RECORD_TYPE.equals(record.recordType())
            || record.payloadSchemaVersion() != InitialContextSeedCodec.SCHEMA_VERSION) {
            throw corrupt();
        }
        try {
            return initialSeeds.decode(json.encode(record.payload()));
        } catch (IllegalArgumentException failure) {
            throw corrupt(failure);
        }
    }

    /**
     * 压缩阶段的持久边界由本 codec 统一校验，避免 Session 与取消路径各自解释字段。
     */
    @Override
    public AgentStore.RecordWrite encodeContextCompaction(String recordId, ContextCompaction value,
                                                          Instant occurredAt) {
        Objects.requireNonNull(value, "value");
        ObjectNode payload = mapper.createObjectNode().put("compactionId", value.id())
                .put("sourceRevision", value.sourceRevision()).put("inputTokensBefore", value.inputTokensBefore())
                .put("active", value.active());
        return new AgentStore.RecordWrite(recordId, CONTEXT_COMPACTION_RECORD_TYPE, SCHEMA_VERSION,
                payload(payload), occurredAt);
    }

    /**
     * 解码唯一已知的压缩标记格式；未知类型、版本、字段或负水位都表示存储损坏。
     */
    @Override
    public ContextCompaction decodeContextCompaction(AgentStore.StoredRecord record) {
        Objects.requireNonNull(record, "record");
        if (!CONTEXT_COMPACTION_RECORD_TYPE.equals(record.recordType())
            || record.payloadSchemaVersion() != SCHEMA_VERSION) {
            throw corrupt();
        }
        try {
            JsonNode payload = strictTree(record.payload());
            exact(payload, "compactionId", "sourceRevision", "inputTokensBefore", "active");
            return new ContextCompaction(text(payload, "compactionId"), number(payload, "sourceRevision"),
                    number(payload, "inputTokensBefore"), bool(payload, "active"));
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw corrupt(failure);
        }
    }

    /**
     * 让应用投影层复用 Core 对所有 context 事实的类型、版本和字段闭集校验。
     */
    @Override
    public void validateContextRecord(AgentStore.StoredRecord record) {
        Objects.requireNonNull(record, "record");
        switch (record.recordType()) {
            case INITIAL_CONTEXT_SEED_RECORD_TYPE -> decodeInitialContextSeed(record);
            case CONTEXT_INSTRUCTION_RECORD_TYPE -> decodeInstruction(record);
            case CONTEXT_CHECKPOINT_RECORD_TYPE -> decodeCheckpoint(record);
            case CONTEXT_PROJECTION_RECORD_TYPE -> decodeProjection(record);
            case CONTEXT_COMPACTION_RECORD_TYPE -> decodeContextCompaction(record);
            default -> throw corrupt();
        }
    }

    /**
     * 在 Provider 派发前冻结模型能力，并由执行身份与消息身份派生稳定记录键。
     */
    @Override
    public AgentStore.RecordWrite encodeModelCapabilities(AgentStore.ExecutionKey execution,
                                                          ModelCapabilities value, Instant occurredAt) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(value, "value");
        ObjectNode payload = mapper.createObjectNode().put("checkpointId", value.checkpointId().orElse(null))
                .put("messageId", value.messageId());
        payload.set("seedSummaryRef", writeSeedSummaryReference(value.seedSummaryRef()));
        ArrayNode visibleTools = payload.putArray("visibleTools");
        value.visibleTools().forEach(visibleTools::add);
        String recordId = AgentRecordIds.derive(execution, MODEL_CAPABILITIES_RECORD_TYPE, value.messageId());
        return new AgentStore.RecordWrite(recordId, MODEL_CAPABILITIES_RECORD_TYPE,
                MODEL_CAPABILITIES_SCHEMA_VERSION, payload(payload), occurredAt);
    }

    /**
     * 按执行身份核对能力快照，拒绝错误版本、额外字段和与模型消息脱节的记录。
     */
    @Override
    public ModelCapabilities decodeModelCapabilities(AgentStore.StoredRecord record,
                                                     AgentStore.ExecutionKey execution) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(execution, "execution");
        if (!MODEL_CAPABILITIES_RECORD_TYPE.equals(record.recordType())
            || record.payloadSchemaVersion() != MODEL_CAPABILITIES_SCHEMA_VERSION) {
            throw corrupt();
        }
        try {
            JsonNode payload = strictTree(record.payload());
            exact(payload, "checkpointId", "messageId", "seedSummaryRef", "visibleTools");
            String messageId = text(payload, "messageId");
            String expectedRecordId = AgentRecordIds.derive(execution, MODEL_CAPABILITIES_RECORD_TYPE, messageId);
            if (!expectedRecordId.equals(record.recordId())) {
                throw corrupt();
            }
            String checkpointId = nullableText(payload, "checkpointId");
            if (checkpointId != null && !validCheckpointId(checkpointId)) {
                throw corrupt();
            }
            Optional<SeedSummaryReference> seedSummaryRef = readSeedSummaryReference(
                    required(payload, "seedSummaryRef"));
            if (checkpointId != null && seedSummaryRef.isPresent()) {
                throw corrupt();
            }
            JsonNode tools = required(payload, "visibleTools");
            if (!tools.isArray()) {
                throw corrupt();
            }
            List<String> visibleTools = new ArrayList<>();
            Set<String> uniqueTools = new HashSet<>();
            tools.forEach(value -> {
                if (!value.isTextual() || value.textValue().isBlank() || !uniqueTools.add(value.textValue())) {
                    throw corrupt();
                }
                visibleTools.add(value.textValue());
            });
            return new ModelCapabilities(messageId, visibleTools, Optional.ofNullable(checkpointId),
                    seedSummaryRef);
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw corrupt(failure);
        }
    }

    /**
     * 将可选父 Seed 摘要引用编码为稳定字段闭集，不把它伪装成子执行 checkpoint。
     */
    private JsonNode writeSeedSummaryReference(Optional<SeedSummaryReference> reference) {
        if (reference.isEmpty()) {
            return mapper.nullNode();
        }
        SeedSummaryReference value = reference.orElseThrow();
        InitialContextSeed.SourceIdentity source = value.sourceIdentity();
        ObjectNode sourceIdentity = mapper.createObjectNode().put("tenantId", source.tenantId())
                .put("threadId", source.threadId()).put("turnId", source.turnId());
        ObjectNode encoded = mapper.createObjectNode().set("sourceIdentity", sourceIdentity);
        return encoded.put("sourceOrdinal", value.sourceOrdinal());
    }

    /**
     * Store 已冻结的 JSON 值只在 Codec 内映射回 Jackson 树，保持持久文本不进入领域端口。
     */
    private JsonNode strictTree(JsonObject payload) {
        return JacksonJsonValues.toNode(mapper, Objects.requireNonNull(payload, "payload"));
    }

    /**
     * Jackson 树在离开 Codec 前转换为不可变、与序列化库无关的 Store payload。
     */
    private JsonObject payload(ObjectNode node) {
        if (!(JacksonJsonValues.fromNode(Objects.requireNonNull(node, "node")) instanceof JsonObject value)) {
            throw new IllegalArgumentException("record payload must be an object");
        }
        return value;
    }

    /**
     * 内部指令不产生用户消息事实，其类型让模型上下文与 UI 投影各自正确处理。
     */
    @Override
    public AgentStore.RecordWrite encodeInstruction(String recordId, ContextInstruction instruction, Instant occurredAt) {
        Objects.requireNonNull(instruction, "instruction");
        ObjectNode node = mapper.createObjectNode().put("messageId", instruction.messageId()).put("text", instruction.text());
        return new AgentStore.RecordWrite(recordId, "agent.context.instruction", SCHEMA_VERSION, payload(node), occurredAt);
    }

    /**
     * 字段和版本严格校验，不能把持久指令损坏解释成一条新的用户消息。
     */
    @Override
    public ContextInstruction decodeInstruction(AgentStore.StoredRecord record) {
        if (!"agent.context.instruction".equals(record.recordType()) || record.payloadSchemaVersion() != SCHEMA_VERSION)
            {
                throw corrupt();
            }
        try {
            JsonNode node = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json.encode(record.payload()));
            exact(node, "messageId", "text");
            return new ContextInstruction(text(node, "messageId"), text(node, "text"));
        } catch (java.io.IOException | IllegalArgumentException failure) {
            throw corrupt(failure);
        }
    }

    /**
     * 将用户输入写入同一事实日志，但使用独立类型，避免与助手执行事实混淆。
     */
    public AgentStore.RecordWrite encodeUser(String recordId, String messageId, UserContent content, Instant occurredAt) {
        return encodeUserRecord("agent.user.message", recordId, messageId, content, occurredAt);
    }

    /**
     * 待消费输入使用独立类型，恢复历史时不能提前向模型暴露。
     */
    public AgentStore.RecordWrite encodeQueuedUser(String recordId, String messageId, UserContent content, Instant occurredAt) {
        return encodeUserRecord("agent.input.message", recordId, messageId, content, occurredAt);
    }

    /**
     * 共享内容编码但保留调用方选择的语义类型，避免队列与历史混淆。
     */
    private AgentStore.RecordWrite encodeUserRecord(String type, String recordId, String messageId, UserContent content, Instant occurredAt) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId required");
        }
        ObjectNode node = mapper.createObjectNode().put("messageId", messageId).put("role", "USER")
                .put("blocks", messages.writeUserContent(Objects.requireNonNull(content, "content")));
        return new AgentStore.RecordWrite(recordId, type, SCHEMA_VERSION, payload(node), occurredAt);
    }

    /**
     * 在迁移和准入重放时保留原消息身份及全部用户内容块。
     */
    public UserMessage decodeUser(AgentStore.StoredRecord record) {
        return decodeUserRecord(record, "agent.user.message");
    }

    /**
     * 队列恢复只接受待消费类型，消费后的历史消息不能重复入队。
     */
    public UserMessage decodeQueuedUser(AgentStore.StoredRecord record) {
        return decodeUserRecord(record, "agent.input.message");
    }

    /**
     * 校验真实记录类型后再解码，不通过伪造记录绕过类型边界。
     */
    private UserMessage decodeUserRecord(AgentStore.StoredRecord record, String expectedType) {
        if (!expectedType.equals(record.recordType()) || record.payloadSchemaVersion() != SCHEMA_VERSION)
            {
                throw corrupt();
            }
        try {
            JsonNode node = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json.encode(record.payload()));
            exact(node, "messageId", "role", "blocks");
            if (!"USER".equals(text(node, "role"))) {
                throw corrupt();
            }
            return new UserMessage(text(node, "messageId"), messages.readUserContent(text(node, "blocks")));
        } catch (java.io.IOException | IllegalArgumentException failure) {
            throw corrupt(failure);
        }
    }

    /**
     * 检查点同时保存来源边界和未压缩的 Turn 后缀，而不只保存摘要正文。
     */
    public AgentStore.RecordWrite encodeCheckpoint(String recordId, CheckpointStore.ContextCheckpoint checkpoint, Instant occurredAt) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        ObjectNode node = mapper.createObjectNode().put("checkpointId", checkpoint.checkpointId())
                .put("threadId", checkpoint.threadId()).put("throughOrdinal", checkpoint.throughOrdinal())
                .put("retainedFromOrdinal", checkpoint.retainedFromOrdinal()).put("sourceRevision", checkpoint.sourceRevision())
                .put("retainedSplit", messages.writeRetainedSplit(checkpoint.retainedSplit()))
                .put("summary", messages.writeSummary(checkpoint.summary())).put("estimatedTokens", checkpoint.estimatedTokens())
                .put("envelopeFingerprint", checkpoint.envelopeFingerprint()).put("strategyVersion", checkpoint.strategyVersion())
                .put("usage", messages.writeCheckpointUsage(checkpoint.usage())).put("createdAt", checkpoint.createdAt().toString());
        return new AgentStore.RecordWrite(recordId, "agent.context.checkpoint", SCHEMA_VERSION, payload(node), occurredAt);
    }

    /**
     * 从记录恢复检查点时使用已保存的历史范围，不根据当前对话重新推测保留内容。
     */
    public CheckpointStore.ContextCheckpoint decodeCheckpoint(AgentStore.StoredRecord record) {
        if (!"agent.context.checkpoint".equals(record.recordType()) || record.payloadSchemaVersion() != SCHEMA_VERSION)
            {
                throw corrupt();
            }
        try {
            JsonNode node = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json.encode(record.payload()));
            exact(node, "checkpointId", "threadId", "throughOrdinal", "retainedFromOrdinal", "sourceRevision", "retainedSplit",
                    "summary", "estimatedTokens", "envelopeFingerprint", "strategyVersion", "usage", "createdAt");
            return new CheckpointStore.ContextCheckpoint(text(node, "checkpointId"), text(node, "threadId"),
                    number(node, "throughOrdinal"), number(node, "retainedFromOrdinal"), number(node, "sourceRevision"),
                    messages.readRetainedSplit(nullableText(node, "retainedSplit")), messages.readSummary(text(node, "summary")),
                    integer(node, "estimatedTokens"), text(node, "envelopeFingerprint"), text(node, "strategyVersion"),
                    messages.readCheckpointUsage(text(node, "usage")), instant(node, "createdAt"));
        } catch (java.io.IOException | IllegalArgumentException | ArithmeticException failure) {
            throw corrupt(failure);
        }
    }

    /**
     * 冻结的工具结果选择属于请求身份，进程重启后也必须保持不变。
     */
    public AgentStore.RecordWrite encodeProjection(String recordId, CheckpointStore.ProjectionSnapshot projection, Instant occurredAt) {
        Objects.requireNonNull(projection, "projection");
        ObjectNode node = mapper.createObjectNode().put("stageId", projection.stageId()).put("threadId", projection.threadId())
                .put("stageNumber", projection.stageNumber()).put("sourceRevision", projection.sourceRevision())
                .put("reason", projection.reason().name()).put("modelBinding", projection.binding().modelBinding())
                .put("toolBinding", projection.binding().toolBinding());
        ObjectNode selections = node.putObject("selections");
        projection.selections().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> selections.put(entry.getKey(), entry.getValue().name()));
        return new AgentStore.RecordWrite(recordId, "agent.context.projection", SCHEMA_VERSION, payload(node), occurredAt);
    }

    /**
     * 投影记录损坏时直接报错，不重新计算历史工具输出的可见范围。
     */
    public CheckpointStore.ProjectionSnapshot decodeProjection(AgentStore.StoredRecord record) {
        if (!"agent.context.projection".equals(record.recordType()) || record.payloadSchemaVersion() != SCHEMA_VERSION)
            {
                throw corrupt();
            }
        try {
            JsonNode node = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json.encode(record.payload()));
            exact(node, "stageId", "threadId", "stageNumber", "sourceRevision", "reason", "modelBinding", "toolBinding", "selections");
            JsonNode values = required(node, "selections");
            if (!values.isObject()) {
                throw corrupt();
            }
            java.util.Map<String, ToolProjection> selections = new java.util.LinkedHashMap<>();
            values.properties().forEach(entry -> {
                if (!entry.getValue().isTextual()) {
                    throw corrupt();
                }
                selections.put(entry.getKey(), ToolProjection.valueOf(entry.getValue().textValue()));
            });
            return new CheckpointStore.ProjectionSnapshot(text(node, "stageId"), text(node, "threadId"), number(node, "stageNumber"),
                    number(node, "sourceRevision"), CheckpointStore.ProjectionReason.valueOf(text(node, "reason")),
                    new CheckpointStore.ProjectionBinding(text(node, "modelBinding"), text(node, "toolBinding")), selections);
        } catch (java.io.IOException | IllegalArgumentException | ArithmeticException failure) {
            throw corrupt(failure);
        }
    }

    /**
     * 使用事务所有者分配的记录身份，避免提交响应丢失后生成另一条事实。
     */
    public AgentStore.RecordWrite encode(String recordId, ExecutionFact fact, Instant occurredAt) {
        Objects.requireNonNull(fact, "fact");
        ObjectNode node = mapper.createObjectNode();
        String type = switch (fact) {
            case AssistantMessage value -> {
                node.put("messageId", value.messageId()).put("blocks", messages.writeMessage(value.message()))
                        .put("publicText", value.publicText()).put("reasoningSummary", value.reasoningSummary())
                        .put("modelRound", value.modelRound());
                yield "agent.assistant";
            }
            case ReasoningSummary value -> {
                node.put("messageId", value.messageId()).put("text", value.text()).put("modelRound", value.modelRound());
                yield "agent.reasoning_summary";
            }
            case ToolMessage value -> {
                node.put("messageId", value.messageId()).put("blocks", messages.writeMessage(value.message()));
                yield "agent.tool.message";
            }
            case ToolPrepared value -> {
                node.put("batchId", value.batchId()).put("callId", value.callId()).put("toolName", value.toolName())
                        .put("ordinal", value.ordinal()).put("sideEffect", value.sideEffect().name())
                        .put("approvalRequired", value.approvalRequired());
                node.set("arguments", JacksonJsonValues.toNode(mapper, value.arguments()));
                node.set("binding", value.binding() == null ? mapper.nullNode() : binding(value.binding()));
                yield "agent.tool.prepared";
            }
            case RecoveryEvidence value -> {
                node.put("callId", value.callId()).put("attempt", value.attempt()).put("typeId", value.typeId())
                        .put("schemaVersion", value.schemaVersion()).put("fingerprint", value.fingerprint());
                node.set("payload", JacksonJsonValues.toNode(mapper, value.payload()));
                yield "agent.tool.recovery";
            }
            case ToolStarted value -> {
                node.put("callId", value.callId()).put("attempt", value.attempt());
                yield "agent.tool.started";
            }
            case ToolRetryIntent value -> {
                node.put("callId", value.callId()).put("verifiedAttempt", value.verifiedAttempt())
                        .put("nextAttempt", value.nextAttempt())
                        .put("verificationFingerprint", value.verificationFingerprint());
                yield "agent.tool.retry.intent";
            }
            case ToolRecoveryVerified value -> {
                node.put("callId", value.callId()).put("attempt", value.attempt())
                        .put("verificationFingerprint", value.verificationFingerprint());
                yield "agent.tool.recovery.verified";
            }
            case ToolResult value -> {
                node.put("callId", value.callId()).put("state", value.state().name())
                        .put("content", value.content()).put("error", value.error())
                        .put("artifactContent", value.artifactContent());
                yield "agent.tool.result";
            }
            case Approval value -> {
                node.put("approvalId", value.approvalId()).put("callId", value.callId())
                        .put("decision", value.decision() == null ? null : value.decision().name())
                        .put("expiresAt", value.expiresAt().toString());
                yield "agent.approval";
            }
            case Usage value -> {
                node.put("modelRequestId", value.modelRequestId()).put("modelRound", value.modelRound())
                        .put("modelRequestOrdinal", value.modelRequestOrdinal()).put("purpose", value.purpose().name())
                        .put("certainty", value.certainty().name()).put("estimatedContextTokens", value.estimatedContextTokens());
                node.set("profile", profile(value.profile()));
                node.set("usage", value.usage() == null ? mapper.nullNode() : usage(value.usage()));
                yield "agent.usage";
            }
        };
        return new AgentStore.RecordWrite(recordId, type, SCHEMA_VERSION, payload(node), occurredAt);
    }

    /**
     * 仅在存储适配器确认租户、执行和排序身份后解码记录。
     */
    public ExecutionFact decode(AgentStore.StoredRecord record) {
        Objects.requireNonNull(record, "record");
        return decode(record.recordType(), record.payloadSchemaVersion(), json.encode(record.payload()));
    }

    /**
     * 复用存储消息编解码器的严格 SummaryDocument schema，避免游标自建第二套 JSON 解析器。
     */
    @Override
    public String encodeSummaryDocument(SummaryDocument summary) {
        return messages.writeSummary(Objects.requireNonNull(summary, "summary"));
    }

    /**
     * 与 checkpoint 使用相同领域校验恢复滚动摘要，不接受部分字段或未知属性。
     */
    @Override
    public SummaryDocument decodeSummaryDocument(String encoded) {
        return messages.readSummary(Objects.requireNonNull(encoded, "encoded"));
    }

    /**
     * 遇到未来版本或字段损坏时明确拒绝，避免静默改变恢复决策。
     */
    public ExecutionFact decode(String type, int schemaVersion, String json) {
        if (schemaVersion != SCHEMA_VERSION) {
            throw corrupt();
        }
        try {
            JsonNode value = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(Objects.requireNonNull(json, "json"));
            if (!(value instanceof ObjectNode node)) {
                throw corrupt();
            }
            return switch (type) {
                case "agent.assistant" -> {
                    exact(node, "messageId", "blocks", "publicText", "reasoningSummary", "modelRound");
                    yield new AssistantMessage(text(node, "messageId"), messages.readMessage("ASSISTANT", text(node, "blocks")),
                            text(node, "publicText"), nullableText(node, "reasoningSummary"), integer(node, "modelRound"));
                }
                case "agent.reasoning_summary" -> {
                    exact(node, "messageId", "text", "modelRound");
                    yield new ReasoningSummary(text(node, "messageId"), text(node, "text"), integer(node, "modelRound"));
                }
                case "agent.tool.message" -> {
                    exact(node, "messageId", "blocks");
                    yield new ToolMessage(text(node, "messageId"), messages.readMessage("TOOL", text(node, "blocks")));
                }
                case "agent.tool.prepared" -> {
                    exact(node, "batchId", "callId", "toolName", "ordinal", "sideEffect",
                            "approvalRequired", "arguments", "binding");
                    if (!(JacksonJsonValues.fromNode(required(node, "arguments")) instanceof JsonObject arguments))
                        {
                            throw corrupt();
                        }
                    yield new ToolPrepared(text(node, "batchId"), text(node, "callId"), text(node, "toolName"), arguments,
                            integer(node, "ordinal"), ToolSideEffect.valueOf(text(node, "sideEffect")),
                            readBinding(required(node, "binding")), bool(node, "approvalRequired"));
                }
                case "agent.tool.recovery" -> {
                    exact(node, "callId", "attempt", "typeId", "schemaVersion", "payload", "fingerprint");
                    yield new RecoveryEvidence(text(node, "callId"), integer(node, "attempt"), text(node, "typeId"), integer(node, "schemaVersion"),
                            object(node, "payload"), text(node, "fingerprint"));
                }
                case "agent.tool.started" -> {
                    exact(node, "callId", "attempt");
                    yield new ToolStarted(text(node, "callId"), integer(node, "attempt"));
                }
                case "agent.tool.retry.intent" -> {
                    exact(node, "callId", "verifiedAttempt", "nextAttempt", "verificationFingerprint");
                    yield new ToolRetryIntent(text(node, "callId"), integer(node, "verifiedAttempt"),
                            integer(node, "nextAttempt"), text(node, "verificationFingerprint"));
                }
                case "agent.tool.recovery.verified" -> {
                    exact(node, "callId", "attempt", "verificationFingerprint");
                    yield new ToolRecoveryVerified(text(node, "callId"), integer(node, "attempt"),
                            text(node, "verificationFingerprint"));
                }
                case "agent.tool.result" -> {
                    exact(node, "callId", "state", "content", "error", "artifactContent");
                    JsonNode error = required(node, "error");
                    if (!error.isBoolean()) {
                        throw corrupt();
                    }
                    yield new ToolResult(text(node, "callId"), ToolState.valueOf(text(node, "state")),
                            text(node, "content"), error.booleanValue(), text(node, "artifactContent"));
                }
                case "agent.approval" -> {
                    exact(node, "approvalId", "callId", "decision", "expiresAt");
                    String decision = nullableText(node, "decision");
                    yield new Approval(text(node, "approvalId"), text(node, "callId"),
                            decision == null ? null : ApprovalDecision.valueOf(decision), instant(node, "expiresAt"));
                }
                case "agent.usage" -> {
                    exact(node, "modelRequestId", "modelRound", "modelRequestOrdinal", "purpose", "certainty", "estimatedContextTokens", "profile", "usage");
                    yield new Usage(text(node, "modelRequestId"), readUsage(required(node, "usage")),
                            integer(node, "modelRound"), integer(node, "modelRequestOrdinal"), UsagePurpose.valueOf(text(node, "purpose")),
                            UsageCertainty.valueOf(text(node, "certainty")), readProfile(required(node, "profile")),
                            nullableNumber(node, "estimatedContextTokens"));
                }
                default -> throw corrupt();
            };
        } catch (java.io.IOException | IllegalArgumentException | ArithmeticException failure) {
            throw corrupt(failure);
        }
    }

    /**
     * 将完整工具绑定作为不可变执行证据保存，不在恢复时查询当前工具目录替代。
     */
    private ObjectNode binding(ToolExecutionBinding binding) {
        ObjectNode node = mapper.createObjectNode().put("toolName", binding.toolName())
                .put("schemaFingerprint", binding.schemaFingerprint()).put("catalogFingerprint", binding.catalogFingerprint())
                .put("permissionFingerprint", binding.permissionFingerprint());
        node.set("route", JacksonJsonValues.toNode(mapper, binding.route()));
        return node;
    }

    /**
     * 只持久化不含密钥的请求身份，并让执行循环将宿主元数据视为不透明值。
     */
    private ObjectNode profile(ModelRequestIdentity value) {
        ObjectNode node = mapper.createObjectNode().put("providerId", value.providerId()).put("modelId", value.modelId())
                .put("api", value.api()).put("upstreamModel", value.upstreamModel())
                .put("requestedReasoning", value.requestedReasoning()).put("effectiveReasoning", value.effectiveReasoning())
                .put("contextWindowTokens", value.contextWindowTokens()).put("maxOutputTokens", value.maxOutputTokens());
        node.set("attributes", JacksonJsonValues.toNode(mapper, value.attributes()));
        return node;
    }

    /**
     * 保留可空缓存计数和供应商用量字段，避免未知用量被误写为零。
     */
    private ObjectNode usage(ModelUsage value) {
        return mapper.createObjectNode().put("inputTokens", value.inputTokens()).put("outputTokens", value.outputTokens())
                .put("totalTokens", value.totalTokens()).put("cacheReadTokens", value.cacheReadTokens())
                .put("cacheWriteTokens", value.cacheWriteTokens()).put("inputAccounting", value.inputAccounting().name());
    }
}





