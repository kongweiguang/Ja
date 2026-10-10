// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.infrastructure.persistence;


import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.tool.ToolState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.EventWrite;

/** 固定持久事件版本合同，避免循环与重连客户端对同一事实使用不同字段语义。 */
class ExecutionEventCodecTest {
    private final JacksonExecutionEventCodec codec = new JacksonExecutionEventCodec(new ObjectMapper());

    /** 每一种可提交事件都能无损还原，工具和模型正文只保留记录引用。 */
    @Test
    void roundTripsEveryEventKind() {
        List<ExecutionEvent> events = List.of(
                new ExecutionEvent.Admitted("USER_INPUT", "message", null),
                new ExecutionEvent.Admitted("INTERNAL", null, "instruction"),
                new ExecutionEvent.Admitted("COMPACTION", null, null),
                new ExecutionEvent.StateChanged(TurnState.QUEUED, TurnState.RUNNING),
                new ExecutionEvent.ModelCompleted("message", 1, List.of("call"), "usage"),
                new ExecutionEvent.ToolStarted("call", "batch", "tool", 0),
                new ExecutionEvent.ToolCompleted("call", ToolState.FAILED, "result", "message", "BUSINESS_ERROR"),
                new ExecutionEvent.ToolRecoveryChanged("recovery", "call", 2, "decision_record"),
                new ExecutionEvent.WaitRequested("wait", "clarification", "request"),
                new ExecutionEvent.WaitResolved("wait", List.of("answer")),
                new ExecutionEvent.InputAccepted("message"),
                new ExecutionEvent.Terminal(TurnState.COMPLETED, "完成", null, null, "message", "usage"),
                new ExecutionEvent.RetryScheduled("request", 1, 500, "TRANSIENT_FAILURE"),
                new ExecutionEvent.ContextUsageEstimated("usage_estimate"),
                new ExecutionEvent.ContextCompactionStarted("compaction", 12, 24000),
                new ExecutionEvent.ContextCompacted("compaction", ExecutionEvent.CompactionOutcome.COMPACTED,
                        "checkpoint", "checkpoint_record", 12, 15, 24000, 9000),
                new ExecutionEvent.ContextCompacted("compaction_unchanged", ExecutionEvent.CompactionOutcome.UNCHANGED,
                        null, null, 15, 15, 9000, 9000),
                new ExecutionEvent.ContextCompactionFailed("compaction_failed", 15, 20000, "SUMMARY_FAILURE"),
                new ExecutionEvent.ControlRecorded(ExecutionEvent.ControlAction.CANCEL_REQUESTED,
                        "cancel_record", "cancel_op", "USER_REQUESTED"),
                new ExecutionEvent.ControlRecorded(ExecutionEvent.ControlAction.SUSPEND_CONSUMED,
                        "consumed_record", "resume_op", null),
                new ExecutionEvent.ControlRecorded(ExecutionEvent.ControlAction.INPUT_CHANGED,
                        "input_command", "input_op", null),
                new ExecutionEvent.ControlRecorded(ExecutionEvent.ControlAction.WAIT_RESOLVED,
                        "answer_record", "answer_op", null),
                new ExecutionEvent.ControlRecorded(ExecutionEvent.ControlAction.TOOL_RECOVERY_DECIDED,
                        "recovery_record", "recovery_op", "RETRY"));
        for (ExecutionEvent event : events) {
            EventWrite encoded = codec.encode("event", event, Instant.EPOCH);
            assertEquals(1, encoded.payloadSchemaVersion());
            if (event instanceof ExecutionEvent.ContextUsageEstimated) {
                assertEquals("agent.context.usage.estimated", encoded.eventType());
            }
            assertEquals(event, codec.decode(encoded.eventType(), encoded.payloadSchemaVersion(),
                    io.github.kongweiguang.ja.agent.infrastructure.json.JsonValueCodecFactory.standard()
                            .encode(encoded.payload())));
        }
    }

    /** 损坏事件不能通过宽松 JSON 或默认值伪装成已接收输入。 */
    @Test
    void rejectsDuplicateMissingAndExtraFields() {
        for (String json : List.of("{\"messageId\":\"first\",\"messageId\":\"second\"}", "{}",
                "{\"messageId\":null}", "{\"messageId\":1}", "{\"messageId\":\"message\"} {}",
                "{\"messageId\":\"message\",\"extra\":true}")) {
            StorageException failure = assertThrows(StorageException.class, () -> codec.decode("agent.input.accepted", 1, json));
            assertEquals("invalid execution event payload", failure.getMessage());
            assertNotNull(failure.getCause());
            assertFalse(failure.toString().contains("second"));
            assertFalse(failure.getCause().toString().contains("second"));
        }
    }

    /** 未知枚举仍是 INVALID_STATE，但其载荷文本不得通过任一包装层进入诊断链。 */
    @Test
    void rejectsUnknownEnumWithoutLeakingValueThroughCauseChain() {
        String sentinel = "unknown-enum-value-sentinel";
        StorageException failure = assertThrows(StorageException.class,
                () -> codec.decode("agent.execution.state", 1,
                        "{\"from\":\"" + sentinel + "\",\"to\":\"RUNNING\"}"));

        assertEquals(StorageException.Code.INVALID_STATE, failure.code());
        assertEquals("invalid execution event payload", failure.getMessage());
        assertFalse(failure.getMessage().contains(sentinel));
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            assertFalse(cause.toString().contains(sentinel));
        }
    }

    /** 数值溢出和重复引用不能改变恢复位置或让一条工具结果投影两次。 */
    @Test
    void rejectsOverflowAndDuplicateReferences() {
        assertThrows(StorageException.class, () -> codec.decode("agent.model.completed", 1,
                "{\"messageRecordId\":\"message\",\"modelRound\":2147483648,\"toolCallIds\":[],\"usageRecordId\":\"usage\"}"));
        assertThrows(StorageException.class, () -> codec.decode("agent.model.completed", 1,
                "{\"messageRecordId\":\"message\",\"modelRound\":1,\"toolCallIds\":[\"call\",\"call\"],\"usageRecordId\":\"usage\"}"));
        assertThrows(StorageException.class, () -> codec.decode("agent.model.retry", 1,
                "{\"modelRequestId\":\"request\",\"attempt\":1,\"delayMillis\":9223372036854775808,\"reasonCode\":\"RETRY\"}"));
        assertThrows(StorageException.class, () -> codec.decode("agent.context.usage.estimated", 1,
                "{\"usageRecordId\":\"usage\",\"extra\":true}"));
    }

    /** 未知版本与类型必须明确失败，不能把未来协议或扩展事件当成当前 core 事件忽略。 */
    @Test
    void rejectsUnknownProtocolAndImpossibleState() {
        assertThrows(StorageException.class, () -> codec.decode("agent.input.accepted", 2, "{\"messageId\":\"m\"}"));
        assertThrows(StorageException.class, () -> codec.decode("custom.event", 1, "{}"));
        assertThrows(StorageException.class, () -> codec.decode("agent.execution.state", 1,
                "{\"from\":\"COMPLETED\",\"to\":\"RUNNING\"}"));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionEvent.Terminal(TurnState.SUSPENDED, "", null, null, null, null));
    }
}
