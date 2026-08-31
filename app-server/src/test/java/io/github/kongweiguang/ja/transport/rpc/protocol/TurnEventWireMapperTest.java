// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.ToolPresentation;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;

import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 验证领域事件映射的脱敏与关联字段，连接级元数据由 RpcSession 另行测试。 */
final class TurnEventWireMapperTest {
    /** 草稿只保留 Turn、流序号和文本，避免丢失的 delta 被误认为持久历史。 */
    @Test
    void mapsDeltaWithoutDurableFields() {
        TurnEventWireMapper mapper = new TurnEventWireMapper(new ObjectMapper(), "srv_test");
        TurnEventWireMapper.WireEvent wire = mapper.map(new TurnEvent.TextDelta("turn_test", 7, "hello"));
        assertEquals("assistant/text-delta", wire.method());
        assertEquals(Set.of("turnId", "streamSeq", "text"),
                Set.copyOf(wire.params().properties().stream().map(entry -> entry.getKey()).toList()));
        assertFalse(wire.params().has("threadRevision"));
    }

    /** 持久终态保留 revision 和事件身份，sequence 由连接出站边界分配。 */
    @Test
    void mapsCommittedTerminal() {
        TurnEventWireMapper mapper = new TurnEventWireMapper(new ObjectMapper(), "srv_test");
        TurnEvent.Context context = new TurnEvent.Context("evt_test", "thr_test", "turn_test", 3,
                Instant.parse("2026-08-25T00:00:00Z"));
        TurnEventWireMapper.WireEvent wire = mapper.map(new TurnEvent.Terminal(
                context, TurnState.COMPLETED, "done", null, null,
                new TurnEvent.FinalMessage("item_test", "done"),
                new TurnEvent.TerminalUsage(new ModelUsage(2, 1, 3), 2)));
        assertEquals("turn/terminal", wire.method());
        assertEquals(3, wire.params().path("threadRevision").longValue());
        assertEquals("item_test", wire.params().path("finalMessage").path("messageId").textValue());
        assertEquals(2, wire.params().path("usage").path("modelRound").intValue());
        assertFalse(wire.params().has("seq"));
    }

    /** 取消是独立终态而非失败别名，Wire 中不得出现错误字段或伪造最终消息。 */
    @Test
    void mapsCancelledTerminalWithoutFailureFields() {
        TurnEventWireMapper mapper = new TurnEventWireMapper(new ObjectMapper(), "srv_test");
        TurnEvent.Context context = new TurnEvent.Context("evt_cancelled", "thr_test", "turn_test", 4,
                Instant.parse("2026-08-25T00:00:01Z"));
        TurnEventWireMapper.WireEvent wire = mapper.map(new TurnEvent.Terminal(
                context, TurnState.CANCELLED, "", null, null, null, null));

        assertEquals("cancelled", wire.params().path("state").textValue());
        assertFalse(wire.params().has("errorCode"));
        assertFalse(wire.params().has("errorMessage"));
        assertFalse(wire.params().has("finalMessage"));
    }

    /** 验证一次模型步骤将 usage 和有序 Tool 调用投影为同一事件。 */
    @Test
    void mapsCommittedModelStep() {
        TurnEventWireMapper mapper = new TurnEventWireMapper(new ObjectMapper(), "srv_test");
        TurnEvent.Context context = new TurnEvent.Context("evt_model", "thr_test", "turn_test", 4,
                Instant.parse("2026-08-25T00:00:01Z"));
        TurnEventWireMapper.WireEvent wire = mapper.map(new TurnEvent.ModelStepCommitted(
                context, "item_assistant", "read requested", "Checking the file", 2,
                new ModelUsage(10, 4, 14),
                List.of(new TurnEvent.ToolCall("call_test", "read_file", presentation(
                        ToolPresentation.Status.PENDING), 0))));
        assertEquals("assistant/model-step-committed", wire.method());
        assertEquals(2, wire.params().path("modelRound").intValue());
        assertEquals(14, wire.params().path("usage").path("totalTokens").intValue());
        assertEquals("call_test", wire.params().path("toolCalls").get(0).path("callId").textValue());
        assertEquals("a.txt", wire.params().path("toolCalls").get(0)
                .path("presentation").path("relativePaths").get(0).textValue());
        assertEquals("Checking the file", wire.params().path("reasoningSummary").textValue());
    }

    /** 验证 Tool 批次保留服务实例关联、结果顺序和非敏感的工作区脏状态。 */
    @Test
    void mapsCommittedToolBatch() {
        TurnEventWireMapper mapper = new TurnEventWireMapper(new ObjectMapper(), "srv_test");
        TurnEvent.Context context = new TurnEvent.Context("evt_tool", "thr_test", "turn_test", 5,
                Instant.parse("2026-08-25T00:00:02Z"));
        TurnEventWireMapper.WireEvent wire = mapper.map(new TurnEvent.ToolBatchCommitted(
                context,
                List.of(new TurnEvent.ToolBatchResult("call_test", ToolOutcome.SUCCEEDED,
                        presentation(ToolPresentation.Status.SUCCESS), 0, null))));
        assertEquals("tool/batch-committed", wire.method());
        assertEquals("srv_test", wire.params().path("serverInstanceId").textValue());
        assertFalse(wire.params().has("workspaceDirty"));
    }

    /** 实时事件测试使用与历史相同的安全展示 DTO。 */
    private static ToolPresentation presentation(ToolPresentation.Status status) {
        return new ToolPresentation(ToolPresentation.Kind.READ, "read", status, "a.txt", "ok",
                List.of("a.txt"), null, ".", null, null, null, 1L, false, null);
    }
}
