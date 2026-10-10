// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionOption;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestion;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestionType;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;
import io.github.kongweiguang.ja.agent.domain.execution.ControlRequest;
import io.github.kongweiguang.ja.agent.domain.execution.SuspendConsumption;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswerSubmission;
import io.github.kongweiguang.ja.agent.domain.tool.ToolRecoveryDecision;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 检验控制记录的严格编码解码，尤其保留答案、取消与过期的不同语义。 */
final class ControlPayloadCodecTest {
    private final ControlPayloadCodec codec = new ControlPayloadCodec(new ObjectMapper());
    private final Instant now = Instant.parse("2026-09-26T01:02:03Z");

    /** 坏时间不能逃逸为日期异常；恢复调用方必须收到统一存储损坏分类。 */
    @Test
    void classifiesInvalidControlTimestampsAsCorruptStorage() {
        AgentStore.RecordWrite encoded = codec.encodeCancelRequest("record_bad_time",
                new ControlRequest("operation_bad_time", "USER_REQUESTED", now));
        AgentStore.StoredRecord tampered = new AgentStore.StoredRecord(encoded.recordId(), encoded.recordType(),
                encoded.payloadSchemaVersion(), 1,
                PersistenceTestJson.mutate(encoded.payload(), json -> json.replace(now.toString(), "invalid-time")), now);

        StorageException failure = assertThrows(StorageException.class, () -> codec.decodeCancelRequest(tampered));

        assertEquals(StorageException.Code.INVALID_STATE, failure.code());
    }

    /** 等待扩展属性必须保留精确十进制和 scale，重启不能改变策略比较的输入。 */
    @Test
    void preservesExactNumbersInWaitAttributes() {
        InteractionQuestion question = new InteractionQuestion("question_scope", "Which scope?",
                InteractionQuestionType.TEXT, List.of(), true, true);
        JsonObject attributes = new JsonObject(Map.of("limit",
                new JsonNumber(new BigDecimal("12345678901234567890.123456789000"))));
        InteractionWait wait = new InteractionWait("interaction_decimal", null, "operation_decimal", attributes,
                List.of(question), List.of(), InteractionStatus.PENDING, 0, now, now);

        InteractionWait restored = codec.decodeWait(stored(codec.encodeWait("record_decimal", wait, now)));

        assertEquals(wait, restored);
    }

    /** 往返已回答等待，验证答案仍受持久化问题契约约束。 */
    @Test
    void answeredWaitRoundTripsWithQuestionConstraints() {
        InteractionQuestion question = new InteractionQuestion("question_scope", "Which scope?",
                InteractionQuestionType.SINGLE,
                List.of(new InteractionOption("option_read", "Read", "Read-only", true)), true, true);
        JsonObject attributes = new JsonObject(Map.of(
                "agent.wait.request", new JsonObject(Map.of(
                        "kind", new JsonText("plan_review"),
                        "prompt", new JsonText("Review the proposed plan"),
                        "payload", new JsonObject(Map.of("revisionId", new JsonText("revision_123"))))),
                "ja.owner", new JsonObject(Map.of(
                        "planRevisionId", new JsonText("revision_123"),
                        "runId", new JsonText("run_123")))));
        InteractionWait wait = new InteractionWait("interaction_123", "call_123", "operation_123", attributes,
                List.of(question), List.of(new InteractionAnswer("question_scope", List.of("option_read"), null, false)),
                InteractionStatus.ANSWERED, 2, now.minusSeconds(1), now);

        InteractionWait restored = codec.decodeWait(stored(codec.encodeWait("record_wait", wait, now)));

        assertEquals(wait, restored);
    }

    /** EXPIRED 等待保留空答案和原问题，不会被解码为用户拒绝。 */
    @Test
    void expiredWaitRoundTripsWithoutInventingAnAnswer() {
        InteractionQuestion question = new InteractionQuestion("question_approval", "Approve?",
                InteractionQuestionType.SINGLE,
                List.of(new InteractionOption("option_approve", "Approve", "Approve action", true),
                        new InteractionOption("option_deny", "Deny", "Reject action", false)),
                true, false);
        InteractionWait pending = new InteractionWait("interaction_approval", "call_123", "operation_123",
                new JsonObject(Map.of("kind", new JsonText("approval"))), List.of(question), List.of(),
                InteractionStatus.PENDING, 0, now, now);
        InteractionWait expired = pending.close(InteractionStatus.EXPIRED, now.plusSeconds(10));

        InteractionWait restored = codec.decodeWait(stored(codec.encodeWait("record_wait_expired", expired,
                now.plusSeconds(10))));

        assertEquals(expired, restored);
        assertEquals(List.of(), restored.answers());
    }

    /** 保留未结算的重试意图，防止重启恢复把未知完成状态误当成未派发。 */
    @Test
    void pendingRetryIntentRoundTripsWithoutInventingAResult() {
        ToolRecoveryDecision decision = new ToolRecoveryDecision("recovery_123", "call_123", 4,
                ToolRecoveryDecision.Status.PENDING, ToolRecoveryDecision.Disposition.RETRY,
                1, "attempt_123", null, null, now);

        ToolRecoveryDecision restored = codec.decodeToolRecoveryDecision(
                stored(codec.encodeToolRecoveryDecision("record_recovery", decision, now)));

        assertEquals(decision, restored);
    }

    /** 队列命令携带单项与全局revision，重启后可按持久记录顺序继续reducer。 */
    @Test
    void inputQueueCommandRoundTripsRevisionMessageKindAndHostMetadata() {
        AgentInputQueue.Command command = new AgentInputQueue.Command("command_123",
                AgentInputQueue.Action.ENQUEUE, "input_123", AgentInputQueue.Kind.MESSAGE,
                0, 1, "record_123", null, now, Map.of("ja.clientOperationId", "op_123"));

        AgentInputQueue.Command restored = codec.decodeInputQueueCommand(
                stored(codec.encodeInputQueueCommand("record_command", command, now)));

        assertEquals(command, restored);
    }

    /** 回答记录保留预期 wait revision 和宿主幂等键，participant 可在同一事务核验。 */
    @Test
    void answerSubmissionRoundTripsRevisionAndHostMetadata() {
        InteractionAnswerSubmission answer = new InteractionAnswerSubmission("answer_123", "interaction_123", 4,
                "operation_123", List.of(new InteractionAnswer("question_scope", List.of("option_read"), null, false)),
                Map.of("ja.clientOperationId", "client_123", "ja.requestFingerprint", "a".repeat(64)), now);

        InteractionAnswerSubmission restored = codec.decodeAnswerSubmission(
                stored(codec.encodeAnswerSubmission("record_answer", answer)));

        assertEquals(answer, restored);
    }

    /** 挂起消费记录明确引用既有 request 与恢复操作，不会被解释为消费最新挂起意图。 */
    @Test
    void suspendConsumptionRoundTripsExactRequestReference() {
        SuspendConsumption consumption = new SuspendConsumption("record_suspend_request", "operation_suspend",
                "operation_resume", now);

        SuspendConsumption restored = codec.decodeSuspendConsumption(stored(
                codec.encodeSuspendConsumption("record_suspend_consumed", consumption)));

        assertEquals(consumption, restored);
    }

    /** 取消与挂起使用独立记录身份，重试同一操作时仍恢复相同控制事实。 */
    @Test
    void cancelAndSuspendRequestsRoundTripWithDistinctRecordTypes() {
        ControlRequest request = new ControlRequest("operation_123", "USER_REQUESTED", now);

        ControlRequest cancelled = codec.decodeCancelRequest(
                stored(codec.encodeCancelRequest("record_cancel", request)));
        ControlRequest suspended = codec.decodeSuspendRequest(
                stored(codec.encodeSuspendRequest("record_suspend", request)));

        assertEquals(request, cancelled);
        assertEquals(request, suspended);
        assertEquals(ExecutionControlCodec.CANCEL_REQUEST_RECORD_TYPE,
                codec.encodeCancelRequest("record_cancel", request).recordType());
        assertEquals(ExecutionControlCodec.SUSPEND_REQUEST_RECORD_TYPE,
                codec.encodeSuspendRequest("record_suspend", request).recordType());
    }

    /** 未知副作用挂起标记使用独立稳定原因码，并拒绝未知类型、版本与载荷字段。 */
    @Test
    void suspensionMarkerRoundTripsAndRejectsUnsupportedPayloads() {
        AgentStore.RecordWrite encoded = codec.encodeSuspension("record_suspension", "TOOL_RESULT_UNKNOWN", now);
        AgentStore.StoredRecord stored = stored(encoded);

        assertEquals(ExecutionControlCodec.SUSPENSION_RECORD_TYPE, stored.recordType());
        assertEquals(ExecutionControlCodec.SUSPENSION_SCHEMA_VERSION, stored.payloadSchemaVersion());
        assertEquals("TOOL_RESULT_UNKNOWN", codec.decodeSuspension(stored));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encodeSuspension("record_bad_reason", "lowercase reason", now));
        assertThrows(StorageException.class, () -> codec.decodeSuspension(new AgentStore.StoredRecord(
                stored.recordId(), "agent.execution.future", stored.payloadSchemaVersion(), stored.sequence(),
                stored.payload(), now)));
        assertThrows(StorageException.class, () -> codec.decodeSuspension(new AgentStore.StoredRecord(
                stored.recordId(), stored.recordType(), 2, stored.sequence(), stored.payload(), now)));
        assertThrows(StorageException.class, () -> codec.decodeSuspension(new AgentStore.StoredRecord(
                stored.recordId(), stored.recordType(), stored.payloadSchemaVersion(), stored.sequence(),
                PersistenceTestJson.mutate(stored.payload(), json -> json.substring(0, json.length() - 1)
                        + ",\"extra\":true}"), now)));
    }

    /** 拒绝跨动作解码，避免把挂起或取消载荷当成另一类授权。 */
    @Test
    void rejectsControlRequestWithWrongRecordType() {
        ControlRequest request = new ControlRequest("operation_123", "USER_REQUESTED", now);
        AgentStore.StoredRecord cancel = stored(codec.encodeCancelRequest("record_cancel", request));

        assertThrows(StorageException.class, () -> codec.decodeSuspendRequest(cancel));
    }

    /** 拒绝未知字段；持久格式变化应由迁移明确定义，而非宽松解码。 */
    @Test
    void rejectsUnknownWaitFields() {
        InteractionQuestion question = new InteractionQuestion("question_scope", "Which scope?",
                InteractionQuestionType.TEXT, List.of(), true, false);
        InteractionWait wait = new InteractionWait("interaction_123", null, "operation_123", JsonObject.empty(),
                List.of(question), List.of(),
                InteractionStatus.PENDING, 0, now, now);
        AgentStore.RecordWrite encoded = codec.encodeWait("record_wait", wait, now);
        JsonObject payload = PersistenceTestJson.mutate(encoded.payload(), json -> json.substring(0, json.length() - 1)
                + ",\"implicitFallback\":true}");
        AgentStore.StoredRecord tampered = new AgentStore.StoredRecord(encoded.recordId(), encoded.recordType(),
                encoded.payloadSchemaVersion(), 1, payload, now);

        assertThrows(StorageException.class, () -> codec.decodeWait(tampered));
    }

    /** 在适配器边界分配序号，使测试记录形状与存储读取值一致。 */
    private static AgentStore.StoredRecord stored(AgentStore.RecordWrite write) {
        return new AgentStore.StoredRecord(write.recordId(), write.recordType(), write.payloadSchemaVersion(),
                1, write.payload(), write.occurredAt());
    }
}
