// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.execution.ControlRequest;
import io.github.kongweiguang.ja.agent.domain.execution.SuspendConsumption;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswerSubmission;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionOption;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestion;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestionType;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.tool.RecoveryCommand;
import io.github.kongweiguang.ja.agent.domain.tool.RecoveryCommandConsumption;
import io.github.kongweiguang.ja.agent.domain.tool.ToolRecoveryDecision;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValues;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 为可恢复等待和未知工具结果提供版本化控制事实编解码。
 */
public final class ControlPayloadCodec implements ExecutionControlCodec {
    private final ObjectMapper mapper;
    private final JacksonJsonValueCodec json;

    /**
     * 复制宿主映射器、固定精确十进制并关闭源文本定位，保证恢复属性不漂移且诊断不泄漏正文。
     */
    public ControlPayloadCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy()
            .disable(com.fasterxml.jackson.core.JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION)
            .configure(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
        json = new JacksonJsonValueCodec(this.mapper);
    }

    /**
     * 在解析不可信持久 JSON 前拒绝类型或版本不匹配的记录。
     */
    private static void requireRecord(AgentStore.StoredRecord record, String expectedType) {
        Objects.requireNonNull(record, "record");
        requireEnvelope(record.recordType(), record.payloadSchemaVersion(), expectedType);
    }

    /**
     * 校验事务内尚未分配 sequence 的值，避免借造 Store 回读类型绕过事实边界。
     */
    private static void requireRecord(AgentStore.RecordWrite record, String expectedType) {
        Objects.requireNonNull(record, "record");
        requireEnvelope(record.recordType(), record.payloadSchemaVersion(), expectedType);
    }

    /**
     * 类型与版本作为一个协议身份校验，避免新旧记录信封维护两份版本映射。
     * @author kongweiguang
     */
    private static void requireEnvelope(String recordType, int schemaVersion, String expectedType) {
        int expectedVersion = switch (expectedType) {
            case WAIT_RECORD_TYPE -> WAIT_SCHEMA_VERSION;
            case TOOL_RECOVERY_RECORD_TYPE -> TOOL_RECOVERY_SCHEMA_VERSION;
            case RECOVERY_COMMAND_RECORD_TYPE, RECOVERY_COMMAND_CONSUMPTION_RECORD_TYPE ->
                RECOVERY_COMMAND_SCHEMA_VERSION;
            case INPUT_QUEUE_COMMAND_RECORD_TYPE -> INPUT_QUEUE_SCHEMA_VERSION;
            case ANSWER_SUBMISSION_RECORD_TYPE -> ANSWER_SUBMISSION_SCHEMA_VERSION;
            case CANCEL_REQUEST_RECORD_TYPE, SUSPEND_REQUEST_RECORD_TYPE, SUSPEND_CONSUMED_RECORD_TYPE ->
                EXECUTION_CONTROL_SCHEMA_VERSION;
            case SUSPENSION_RECORD_TYPE -> SUSPENSION_SCHEMA_VERSION;
            default -> throw new IllegalArgumentException("unknown control record type");
        };
        if (!expectedType.equals(recordType) || schemaVersion != expectedVersion) {
            throw corrupt();
        }
    }

    /**
     * 原因码不承载异常文本或本地化文案，避免敏感上下文进入可跨层投影的记录。
     */
    private static String checkedReasonCode(String reasonCode) {
        if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("invalid suspension reason code");
        }
        return reasonCode;
    }

    /**
     * 严格解码有限问题集合及选项身份，不对损坏 JSON 做类型转换。
     */
    private static List<InteractionQuestion> readQuestions(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            throw corrupt();
        }
        List<InteractionQuestion> result = new ArrayList<>();
        for (JsonNode question : node) {
            exact(question, "questionId", "prompt", "type", "required", "allowFreeText", "options");
            JsonNode required = required(question, "required");
            JsonNode allowFreeText = required(question, "allowFreeText");
            if (!required.isBoolean() || !allowFreeText.isBoolean()) {
                throw corrupt();
            }
            result.add(new InteractionQuestion(text(question, "questionId"), text(question, "prompt"),
                InteractionQuestionType.valueOf(text(question, "type")),
                readOptions(required(question, "options")), required.booleanValue(), allowFreeText.booleanValue()));
        }
        return List.copyOf(result);
    }

    /**
     * 以严格 JSON 闭集恢复等待扩展属性，避免把业务等待 envelope 丢在进程内。
     */
    private static JsonObject object(JsonNode node, String field) {
        if (!(JacksonJsonValues.fromNode(required(node, field)) instanceof JsonObject value)) {
            throw corrupt();
        }
        return value;
    }

    /**
     * 将有界选项 ID 与展示标签分开解码，避免展示文字成为回答身份。
     */
    private static List<InteractionOption> readOptions(JsonNode node) {
        if (!node.isArray()) {
            throw corrupt();
        }
        List<InteractionOption> result = new ArrayList<>();
        for (JsonNode option : node) {
            exact(option, "optionId", "label", "description", "recommended");
            JsonNode recommended = required(option, "recommended");
            if (!recommended.isBoolean()) {
                throw corrupt();
            }
            result.add(new InteractionOption(text(option, "optionId"), text(option, "label"),
                text(option, "description"), recommended.booleanValue()));
        }
        return List.copyOf(result);
    }

    /**
     * 保留可空自由文本与显式跳过状态后再解码回答。
     */
    private static List<InteractionAnswer> readAnswers(JsonNode node) {
        if (!node.isArray()) {
            throw corrupt();
        }
        List<InteractionAnswer> result = new ArrayList<>();
        for (JsonNode answer : node) {
            exact(answer, "questionId", "skipped", "freeText", "optionIds");
            JsonNode skipped = required(answer, "skipped");
            JsonNode optionIds = required(answer, "optionIds");
            if (!skipped.isBoolean() || !optionIds.isArray()) {
                throw corrupt();
            }
            List<String> ids = new ArrayList<>();
            optionIds.forEach(id -> {
                if (!id.isTextual()) {
                    throw corrupt();
                }
                ids.add(id.textValue());
            });
            result.add(new InteractionAnswer(text(answer, "questionId"), ids, nullableText(answer, "freeText"),
                skipped.booleanValue()));
        }
        return List.copyOf(result);
    }

    /**
     * 编码单题回答的选项、自填文本与跳过语义，供 wait 和 answer record 共用闭集。
     */
    private static void writeAnswer(ObjectNode encoded, InteractionAnswer answer) {
        encoded.put("questionId", answer.questionId()).put("skipped", answer.skipped())
            .put("freeText", answer.freeText());
        ArrayNode optionIds = encoded.putArray("optionIds");
        answer.optionIds().forEach(optionIds::add);
    }

    /**
     * 元数据对象只允许字符串键值，结构限制再由领域提交信封统一校验。
     */
    private static Map<String, String> readStringMap(JsonNode node) {
        if (!node.isObject() || node.size() > 32) {
            throw corrupt();
        }
        Map<String, String> result = new TreeMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw corrupt();
            }
            result.put(entry.getKey(), entry.getValue().textValue());
        });
        return Map.copyOf(result);
    }

    /**
     * 要求持久对象字段集合完全一致；格式变化必须显式提升版本。
     */
    private static void exact(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) {
            throw corrupt();
        }
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(Set.of(fields))) {
            throw corrupt();
        }
    }

    /**
     * 要求字段存在，同时为可空字段保留显式 JSON null。
     */
    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw corrupt();
        }
        return value;
    }

    /**
     * 读取 nullable Issue 信封并拒绝字段缺少或类型转换。
     */
    private static AgentInputQueue.Issue readIssue(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        exact(node, "code", "message", "retryable");
        JsonNode retryable = required(node, "retryable");
        if (!retryable.isBoolean()) {
            throw corrupt();
        }
        return new AgentInputQueue.Issue(text(node, "code"), text(node, "message"), retryable.booleanValue());
    }

    /**
     * 仅读取文本，不接受 Jackson 将数字、布尔值或数组强制转换成字符串。
     */
    private static String text(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isTextual()) {
            throw corrupt();
        }
        return value.textValue();
    }

    /**
     * 保留显式 null，避免与字段缺失混为一谈。
     */
    private static String nullableText(JsonNode node, String field) {
        JsonNode value = required(node, field);
        return value.isNull() ? null : text(node, field);
    }

    /**
     * 恢复持久版本和尝试次数时拒绝数值强制转换及溢出。
     */
    private static long number(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw corrupt();
        }
        return value.longValue();
    }

    /**
     * 直接保存枚举稳定名称，避免 toString 将来变为展示文本后破坏协议。
     */
    private static String nullableName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /**
     * 时间损坏属于持久事实错误，不能逃逸为日期异常触发错误的重试策略。
     */
    private static Instant instant(JsonNode node, String field) {
        try {
            return Instant.parse(text(node, field));
        } catch (DateTimeException failure) {
            throw corrupt(failure);
        }
    }

    /**
     * 不回显不可信载荷正文，只向调用方提供稳定的存储错误类别。
     */
    private static StorageException corrupt() {
        return new StorageException(StorageException.Code.INVALID_STATE, "invalid Agent control payload");
    }

    /**
     * 保留持久载荷解析根因供本地诊断，稳定消息不回显用户或 Tool 数据。
     */
    private static StorageException corrupt(Throwable cause) {
        return new StorageException(StorageException.Code.INVALID_STATE, "invalid Agent control payload", cause);
    }

    /**
     * 将结果未知导致的执行挂起保存为独立事实，不与用户的挂起请求混淆。
     */
    @Override
    public AgentStore.RecordWrite encodeSuspension(String recordId, String reasonCode, Instant occurredAt) {
        ObjectNode payload = mapper.createObjectNode().put("reasonCode", checkedReasonCode(reasonCode));
        return new AgentStore.RecordWrite(recordId, SUSPENSION_RECORD_TYPE, SUSPENSION_SCHEMA_VERSION,
            payload(payload), occurredAt);
    }

    /**
     * 仅接受稳定原因码和唯一字段，Ja 投影可复用 Core 已有的严格控制载荷解析。
     */
    @Override
    public String decodeSuspension(AgentStore.StoredRecord record) {
        requireRecord(record, SUSPENSION_RECORD_TYPE);
        try {
            JsonNode payload = strictTree(json.encode(record.payload()));
            exact(payload, "reasonCode");
            return checkedReasonCode(text(payload, "reasonCode"));
        } catch (IOException | IllegalArgumentException failure) {
            throw corrupt(failure);
        }
    }

    /**
     * 保存队列操作本身；输入正文由独立 typed record 同一事务写入，便于候选过滤与编辑审计。
     */
    @Override
    public AgentStore.RecordWrite encodeInputQueueCommand(String recordId, AgentInputQueue.Command command,
                                                          Instant occurredAt) {
        Objects.requireNonNull(command, "command");
        if (!command.occurredAt().equals(Objects.requireNonNull(occurredAt, "occurredAt"))) {
            throw new IllegalArgumentException("queue command and record timestamps differ");
        }
        ObjectNode node = mapper.createObjectNode().put("commandId", command.commandId())
            .put("action", command.action().name()).put("inputId", command.inputId())
            .put("kind", nullableName(command.kind()))
            .put("expectedInputRevision", command.expectedInputRevision())
            .put("queueRevision", command.queueRevision())
            .put("messageRecordId", command.messageRecordId())
            .put("status", command.action() == AgentInputQueue.Action.NEEDS_ATTENTION
                ? AgentInputQueue.Status.NEEDS_ATTENTION.name()
                : command.action() == AgentInputQueue.Action.ENQUEUE
                  || command.action() == AgentInputQueue.Action.UPDATE
                  || command.action() == AgentInputQueue.Action.RETRY
                  ? AgentInputQueue.Status.PENDING.name() : null)
            .put("occurredAt", occurredAt.toString());
        writeStringMap(node, "hostMetadata", command.hostMetadata());
        if (command.issue() == null) {
            node.putNull("issue");
        } else {
            node.set("issue", mapper.createObjectNode().put("code", command.issue().code())
                .put("message", command.issue().message()).put("retryable", command.issue().retryable()));
        }
        return new AgentStore.RecordWrite(recordId, INPUT_QUEUE_COMMAND_RECORD_TYPE,
            INPUT_QUEUE_SCHEMA_VERSION, payload(node), occurredAt);
    }

    /**
     * 重建队列 reducer 输入并复用领域闭集校验，拒绝 revision 跳跃或伪造状态字段。
     */
    @Override
    public AgentInputQueue.Command decodeInputQueueCommand(AgentStore.StoredRecord record) {
        requireRecord(record, INPUT_QUEUE_COMMAND_RECORD_TYPE);
        return decodeInputQueueCommandPayload(json.encode(record.payload()));
    }

    /**
     * 在事务参与阶段解码 command 写入值，不要求 Store 预先分配全局 record sequence。
     */
    @Override
    public AgentInputQueue.Command decodeInputQueueCommand(AgentStore.RecordWrite record) {
        requireRecord(record, INPUT_QUEUE_COMMAND_RECORD_TYPE);
        return decodeInputQueueCommandPayload(json.encode(record.payload()));
    }

    /**
     * 统一验证两种存储边界共用的封闭 queue-command JSON schema。
     */
    private AgentInputQueue.Command decodeInputQueueCommandPayload(String payloadJson) {
        try {
            JsonNode node = strictTree(payloadJson);
            exact(node, "commandId", "action", "inputId", "kind", "expectedInputRevision", "queueRevision",
                "messageRecordId", "status", "issue", "occurredAt", "hostMetadata");
            AgentInputQueue.Action action = AgentInputQueue.Action.valueOf(text(node, "action"));
            String rawKind = nullableText(node, "kind");
            AgentInputQueue.Kind kind = rawKind == null ? null : AgentInputQueue.Kind.valueOf(rawKind);
            String rawStatus = nullableText(node, "status");
            AgentInputQueue.Status expectedStatus = switch (action) {
                case ENQUEUE, UPDATE, RETRY -> AgentInputQueue.Status.PENDING;
                case NEEDS_ATTENTION -> AgentInputQueue.Status.NEEDS_ATTENTION;
                case DELETE, PRIORITIZE, CONSUME -> null;
            };
            if (!Objects.equals(rawStatus, expectedStatus == null ? null : expectedStatus.name())) {
                throw corrupt();
            }
            return new AgentInputQueue.Command(text(node, "commandId"), action, text(node, "inputId"), kind,
                number(node, "expectedInputRevision"), number(node, "queueRevision"),
                nullableText(node, "messageRecordId"), readIssue(required(node, "issue")),
                instant(node, "occurredAt"), readStringMap(required(node, "hostMetadata")));
        } catch (IOException | IllegalArgumentException | ArithmeticException failure) {
            throw corrupt(failure);
        }
    }

    /**
     * 编码一次原子回答的完整字段，客户端元数据保留在普通严格 JSON 对象中。
     */
    @Override
    public AgentStore.RecordWrite encodeAnswerSubmission(String recordId, InteractionAnswerSubmission answer) {
        Objects.requireNonNull(answer, "answer");
        ObjectNode node = mapper.createObjectNode().put("answerId", answer.answerId()).put("interactionId", answer.interactionId())
            .put("expectedInteractionRevision", answer.expectedInteractionRevision()).put("operationKey", answer.operationKey())
            .put("occurredAt", answer.occurredAt().toString());
        writeStringMap(node, "hostMetadata", answer.hostMetadata());
        ArrayNode answers = node.putArray("answers");
        for (InteractionAnswer value : answer.answers()) {
            writeAnswer(answers.addObject(), value);
        }
        return new AgentStore.RecordWrite(recordId, ANSWER_SUBMISSION_RECORD_TYPE,
            ANSWER_SUBMISSION_SCHEMA_VERSION, payload(node), answer.occurredAt());
    }

    /**
     * 严格恢复回答信封，未知字段不能被宿主误当成已验证的幂等回执。
     */
    @Override
    public InteractionAnswerSubmission decodeAnswerSubmission(AgentStore.StoredRecord record) {
        requireRecord(record, ANSWER_SUBMISSION_RECORD_TYPE);
        return decodeAnswerSubmissionPayload(json.encode(record.payload()));
    }

    /**
     * 同事务验证即将写入的答案信封，避免伪造已分配 sequence 的 StoredRecord。
     */
    @Override
    public InteractionAnswerSubmission decodeAnswerSubmission(AgentStore.RecordWrite record) {
        requireRecord(record, ANSWER_SUBMISSION_RECORD_TYPE);
        return decodeAnswerSubmissionPayload(json.encode(record.payload()));
    }

    /**
     * 统一执行 answer payload 的严格 schema 校验。
     */
    private InteractionAnswerSubmission decodeAnswerSubmissionPayload(String payloadJson) {
        try {
            JsonNode node = strictTree(payloadJson);
            exact(node, "answerId", "interactionId", "expectedInteractionRevision", "operationKey", "occurredAt",
                "hostMetadata", "answers");
            return new InteractionAnswerSubmission(text(node, "answerId"), text(node, "interactionId"),
                number(node, "expectedInteractionRevision"), text(node, "operationKey"),
                readAnswers(required(node, "answers")), readStringMap(required(node, "hostMetadata")),
                instant(node, "occurredAt"));
        } catch (IOException | IllegalArgumentException | ArithmeticException failure) {
            throw corrupt(failure);
        }
    }

    /**
     * 编码问题生命周期，不持久化 Ja 的计划、目标、线程或 UI 投影对象。
     */
    public AgentStore.RecordWrite encodeWait(String recordId, InteractionWait wait, Instant occurredAt) {
        Objects.requireNonNull(wait, "wait");
        Objects.requireNonNull(occurredAt, "occurredAt");
        ObjectNode node = mapper.createObjectNode().put("interactionId", wait.interactionId())
            .put("toolCallId", wait.toolCallId()).put("operationKey", wait.operationKey())
            .put("status", wait.status().name()).put("interactionRevision", wait.interactionRevision())
            .put("createdAt", wait.createdAt().toString()).put("updatedAt", wait.updatedAt().toString());
        node.set("attributes", JacksonJsonValues.toNode(mapper, wait.attributes()));
        ArrayNode questions = node.putArray("questions");
        for (InteractionQuestion question : wait.questions()) {
            ObjectNode encoded = questions.addObject().put("questionId", question.questionId())
                .put("prompt", question.prompt()).put("type", question.type().name())
                .put("required", question.required()).put("allowFreeText", question.allowFreeText());
            ArrayNode options = encoded.putArray("options");
            for (InteractionOption option : question.options()) {
                options.addObject().put("optionId", option.optionId()).put("label", option.label())
                    .put("description", option.description()).put("recommended", option.recommended());
            }
        }
        ArrayNode answers = node.putArray("answers");
        for (InteractionAnswer answer : wait.answers()) {
            ObjectNode encoded = answers.addObject().put("questionId", answer.questionId())
                .put("skipped", answer.skipped()).put("freeText", answer.freeText());
            ArrayNode optionIds = encoded.putArray("optionIds");
            answer.optionIds().forEach(optionIds::add);
        }
        return new AgentStore.RecordWrite(recordId, WAIT_RECORD_TYPE, WAIT_SCHEMA_VERSION,
            payload(node), occurredAt);
    }

    /**
     * 重建领域聚合并复用其约束，拒绝非法问题与答案组合。
     */
    public InteractionWait decodeWait(AgentStore.StoredRecord record) {
        requireRecord(record, WAIT_RECORD_TYPE);
        try {
            JsonNode node = strictTree(json.encode(record.payload()));
            exact(node, "interactionId", "toolCallId",
                "operationKey", "status", "interactionRevision", "createdAt",
                "updatedAt", "attributes", "questions", "answers");
            List<InteractionQuestion> questions = readQuestions(required(node, "questions"));
            List<InteractionAnswer> answers = readAnswers(required(node, "answers"));
            return new InteractionWait(text(node, "interactionId"), nullableText(node, "toolCallId"),
                text(node, "operationKey"), object(node, "attributes"), questions, answers,
                InteractionStatus.valueOf(text(node, "status")), number(node, "interactionRevision"),
                instant(node, "createdAt"), instant(node, "updatedAt"));
        } catch (IOException | IllegalArgumentException | ArithmeticException failure) {
            throw corrupt(failure);
        }
    }

    /**
     * 将已授权的操作身份与稳定原因码写入不可变事实，恢复时能分辨相同操作的重试。
     */
    @Override
    public AgentStore.RecordWrite encodeCancelRequest(String recordId, ControlRequest request) {
        return encodeControlRequest(recordId, CANCEL_REQUEST_RECORD_TYPE, request);
    }

    /**
     * 严格恢复取消意图；不能把未知字段或版本解释为取消已完成。
     */
    @Override
    public ControlRequest decodeCancelRequest(AgentStore.StoredRecord record) {
        return decodeControlRequest(record, CANCEL_REQUEST_RECORD_TYPE);
    }

    /**
     * 持久化显式挂起请求，让执行者只在安全点停止后续 Provider 与 Tool 派发。
     */
    @Override
    public AgentStore.RecordWrite encodeSuspendRequest(String recordId, ControlRequest request) {
        return encodeControlRequest(recordId, SUSPEND_REQUEST_RECORD_TYPE, request);
    }

    /**
     * 严格恢复挂起意图，损坏载荷必须阻止执行继续而非丢弃控制事实。
     */
    @Override
    public ControlRequest decodeSuspendRequest(AgentStore.StoredRecord record) {
        return decodeControlRequest(record, SUSPEND_REQUEST_RECORD_TYPE);
    }

    /**
     * 将恢复阶段消费的具体挂起请求追加为稳定事实，不清除并发到达的新请求。
     */
    @Override
    public AgentStore.RecordWrite encodeSuspendConsumption(String recordId, SuspendConsumption consumption) {
        Objects.requireNonNull(consumption, "consumption");
        ObjectNode node = mapper.createObjectNode().put("suspendRequestRecordId", consumption.suspendRequestRecordId())
            .put("suspendOperationKey", consumption.suspendOperationKey())
            .put("resumeOperationKey", consumption.resumeOperationKey())
            .put("occurredAt", consumption.occurredAt().toString());
        return new AgentStore.RecordWrite(recordId, SUSPEND_CONSUMED_RECORD_TYPE,
            EXECUTION_CONTROL_SCHEMA_VERSION, payload(node), consumption.occurredAt());
    }

    /**
     * 恢复已记录的消费引用；当前可见性及重复消费由 reducer 在 CAS 快照中校验。
     */
    @Override
    public SuspendConsumption decodeSuspendConsumption(AgentStore.StoredRecord record) {
        requireRecord(record, SUSPEND_CONSUMED_RECORD_TYPE);
        try {
            JsonNode node = strictTree(json.encode(record.payload()));
            exact(node, "suspendRequestRecordId", "suspendOperationKey", "resumeOperationKey", "occurredAt");
            return new SuspendConsumption(text(node, "suspendRequestRecordId"),
                text(node, "suspendOperationKey"), text(node, "resumeOperationKey"),
                instant(node, "occurredAt"));
        } catch (IOException | IllegalArgumentException | ArithmeticException failure) {
            throw corrupt(failure);
        }
    }

    /**
     * 在工具派发前持久化重试意图，恢复时不能把未知结果推断成未执行。
     */
    public AgentStore.RecordWrite encodeToolRecoveryDecision(
        String recordId, ToolRecoveryDecision decision, Instant occurredAt) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(occurredAt, "occurredAt");
        ObjectNode node = mapper.createObjectNode().put("recoveryId", decision.recoveryId())
            .put("callId", decision.callId()).put("revision", decision.revision())
            .put("status", decision.status().name())
            .put("disposition", nullableName(decision.disposition())).put("attempt", decision.attempt())
            .put("attemptId", decision.attemptId()).put("toolResultRecordId", decision.toolResultRecordId())
            .put("verificationFingerprint", decision.verificationFingerprint())
            .put("updatedAt", decision.updatedAt().toString());
        return new AgentStore.RecordWrite(recordId, TOOL_RECOVERY_RECORD_TYPE, TOOL_RECOVERY_SCHEMA_VERSION,
            payload(node), occurredAt);
    }

    /**
     * 只恢复已持久化的核对状态，当前工具能力不能替换既有执行证据。
     */
    public ToolRecoveryDecision decodeToolRecoveryDecision(AgentStore.StoredRecord record) {
        requireRecord(record, TOOL_RECOVERY_RECORD_TYPE);
        try {
            JsonNode node = strictTree(json.encode(record.payload()));
            exact(node, "recoveryId", "callId", "revision", "status", "disposition", "attempt", "attemptId",
                "toolResultRecordId", "verificationFingerprint", "updatedAt");
            String disposition = nullableText(node, "disposition");
            return new ToolRecoveryDecision(text(node, "recoveryId"), text(node, "callId"),
                number(node, "revision"), ToolRecoveryDecision.Status.valueOf(text(node, "status")),
                disposition == null ? null : ToolRecoveryDecision.Disposition.valueOf(disposition),
                Math.toIntExact(number(node, "attempt")), nullableText(node, "attemptId"),
                nullableText(node, "toolResultRecordId"), nullableText(node, "verificationFingerprint"),
                instant(node, "updatedAt"));
        } catch (IOException | IllegalArgumentException | ArithmeticException failure) {
            throw corrupt(failure);
        }
    }

    /**
     * 保存 RETRY 或 SKIP 授权，但不声称外部副作用已核实或安全可重放。
     */
    @Override
    public AgentStore.RecordWrite encodeRecoveryCommand(String recordId, RecoveryCommand command) {
        Objects.requireNonNull(command, "command");
        ObjectNode node = mapper.createObjectNode().put("recoveryId", command.recoveryId())
            .put("callId", command.callId()).put("expectedRevision", command.expectedRevision())
            .put("decision", command.decision().name()).put("authorizationId", command.authorizationId())
            .put("operationKey", command.operationKey()).put("occurredAt", command.occurredAt().toString());
        return new AgentStore.RecordWrite(recordId, RECOVERY_COMMAND_RECORD_TYPE,
            RECOVERY_COMMAND_SCHEMA_VERSION, payload(node), command.occurredAt());
    }

    /**
     * 只接受完整字段闭集；授权记录不得用宽松解析跳过未知恢复策略。
     */
    @Override
    public RecoveryCommand decodeRecoveryCommand(AgentStore.StoredRecord record) {
        requireRecord(record, RECOVERY_COMMAND_RECORD_TYPE);
        return decodeRecoveryCommandPayload(json.encode(record.payload()));
    }

    /**
     * 事务参与者检查未分配序号的授权事实时，复用与 Store 回读相同的严格 schema。
     */
    public RecoveryCommand decodeRecoveryCommand(AgentStore.RecordWrite record) {
        requireRecord(record, RECOVERY_COMMAND_RECORD_TYPE);
        return decodeRecoveryCommandPayload(json.encode(record.payload()));
    }

    /**
     * 严格解析授权字段并再次执行领域级闭集验证。
     */
    private RecoveryCommand decodeRecoveryCommandPayload(String payloadJson) {
        try {
            JsonNode node = strictTree(payloadJson);
            exact(node, "recoveryId", "callId", "expectedRevision", "decision", "authorizationId",
                "operationKey", "occurredAt");
            return new RecoveryCommand(text(node, "recoveryId"), text(node, "callId"),
                number(node, "expectedRevision"), RecoveryCommand.Decision.valueOf(text(node, "decision")),
                text(node, "authorizationId"), text(node, "operationKey"),
                instant(node, "occurredAt"));
        } catch (IOException | IllegalArgumentException | ArithmeticException failure) {
            throw corrupt(failure);
        }
    }

    /**
     * 以稳定引用记录授权已进入唯一安全点，恢复时可识别重复消费。
     */
    @Override
    public AgentStore.RecordWrite encodeRecoveryCommandConsumption(
        String recordId, RecoveryCommandConsumption consumption) {
        Objects.requireNonNull(consumption, "consumption");
        ObjectNode node = mapper.createObjectNode().put("commandRecordId", consumption.commandRecordId())
            .put("operationKey", consumption.operationKey()).put("occurredAt", consumption.occurredAt().toString());
        return new AgentStore.RecordWrite(recordId, RECOVERY_COMMAND_CONSUMPTION_RECORD_TYPE,
            RECOVERY_COMMAND_SCHEMA_VERSION, payload(node), consumption.occurredAt());
    }

    /**
     * 解码消费引用，不能把它混同为 ToolResult 或副作用核验凭据。
     */
    @Override
    public RecoveryCommandConsumption decodeRecoveryCommandConsumption(AgentStore.StoredRecord record) {
        requireRecord(record, RECOVERY_COMMAND_CONSUMPTION_RECORD_TYPE);
        return readRecoveryCommandConsumption(json.encode(record.payload()));
    }

    /**
     * 事务参与者尚未取得 Store sequence 时，也能校验消费写入的字段闭集。
     */
    public RecoveryCommandConsumption decodeRecoveryCommandConsumption(AgentStore.RecordWrite record) {
        requireRecord(record, RECOVERY_COMMAND_CONSUMPTION_RECORD_TYPE);
        return readRecoveryCommandConsumption(json.encode(record.payload()));
    }

    /**
     * 两种存储信封共用正文规则，事务内投影与提交后恢复不能采用不同的字段闭集。
     */
    private RecoveryCommandConsumption readRecoveryCommandConsumption(String payload) {
        try {
            JsonNode node = strictTree(payload);
            exact(node, "commandRecordId", "operationKey", "occurredAt");
            return new RecoveryCommandConsumption(text(node, "commandRecordId"), text(node, "operationKey"),
                instant(node, "occurredAt"));
        } catch (IOException | IllegalArgumentException | ArithmeticException failure) {
            throw corrupt(failure);
        }
    }

    /**
     * 编码取消或挂起信封时复用同一字段闭集，并以 recordType 保持动作语义明确。
     */
    private AgentStore.RecordWrite encodeControlRequest(String recordId, String recordType,
                                                        ControlRequest request) {
        Objects.requireNonNull(request, "request");
        ObjectNode node = mapper.createObjectNode().put("operationKey", request.operationKey())
            .put("reasonCode", request.reasonCode()).put("occurredAt", request.occurredAt().toString());
        return new AgentStore.RecordWrite(recordId, recordType, EXECUTION_CONTROL_SCHEMA_VERSION,
            payload(node), request.occurredAt());
    }

    /**
     * 严格解码共享的控制请求字段，避免任意 reason 文本或记录类型混淆执行状态。
     */
    private ControlRequest decodeControlRequest(AgentStore.StoredRecord record, String expectedType) {
        requireRecord(record, expectedType);
        try {
            JsonNode node = strictTree(json.encode(record.payload()));
            exact(node, "operationKey", "reasonCode", "occurredAt");
            return new ControlRequest(text(node, "operationKey"), text(node, "reasonCode"),
                instant(node, "occurredAt"));
        } catch (IOException | IllegalArgumentException | ArithmeticException failure) {
            throw corrupt(failure);
        }
    }

    /**
     * 拒绝重复对象键，避免 JSON 解析器与迁移或宿主适配器对字段含义产生分歧。
     */
    private JsonNode strictTree(String payload) throws IOException {
        return mapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(payload);
    }

    /**
     * Jackson 节点只存在于 Codec 内；Store 公共边界保存不可变、与序列化库无关的 JSON 对象。
     */
    private JsonObject payload(ObjectNode node) {
        if (!(JacksonJsonValues.fromNode(Objects.requireNonNull(node, "node")) instanceof JsonObject value)) {
            throw new IllegalArgumentException("record payload must be an object");
        }
        return value;
    }

    /**
     * 按键排序编码宿主回执，减少同一命令重复提交时的 JSON 字节差异。
     */
    private void writeStringMap(ObjectNode parent, String field, Map<String, String> values) {
        ObjectNode object = parent.putObject(field);
        new TreeMap<>(Objects.requireNonNull(values, field)).forEach(object::put);
    }
}
