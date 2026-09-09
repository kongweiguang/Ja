// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestUsage;
import io.github.kongweiguang.ja.conversation.domain.AttachmentSummary;
import io.github.kongweiguang.ja.conversation.domain.InputQueue;
import io.github.kongweiguang.ja.conversation.domain.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;

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
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                usage(2, new ModelUsage(2, 1, 3))));
        assertEquals("turn/terminal", wire.method());
        assertEquals(3, wire.params().path("threadRevision").longValue());
        assertEquals("item_test", wire.params().path("finalMessage").path("messageId").textValue());
        assertEquals(2, wire.params().path("usage").path("modelRound").intValue());
        assertEquals("complete", wire.params().path("changeSet").path("state").textValue());
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

    /** 失败终态必须携带运行时生成的安全收口回复，同时保留失败码而不能投影为成功。 */
    @Test
    void mapsFailedTerminalWithSafeFinalMessage() {
        TurnEventWireMapper mapper = new TurnEventWireMapper(new ObjectMapper(), "srv_test");
        TurnEvent.Context context = new TurnEvent.Context("evt_failed", "thr_test", "turn_test", 5,
                Instant.parse("2026-08-25T00:00:02Z"));
        TurnEventWireMapper.WireEvent wire = mapper.map(new TurnEvent.Terminal(
                context, TurnState.FAILED, "safe failure reply", "BUDGET_EXCEEDED", "Model round limit reached",
                new TurnEvent.FinalMessage("item_failure_reply", "safe failure reply"), null));

        assertEquals("failed", wire.params().path("state").textValue());
        assertEquals("BUDGET_EXCEEDED", wire.params().path("errorCode").textValue());
        assertEquals("safe failure reply", wire.params().path("finalMessage").path("text").textValue());
    }

    /** 在领域端口拒绝没有安全回复的 FAILED，Transport 不能再把空白终态发送给 Renderer。 */
    @Test
    void rejectsFailedTerminalWithoutSafeFinalMessage() {
        TurnEvent.Context context = new TurnEvent.Context("evt_failed_empty", "thr_test", "turn_test", 5,
                Instant.parse("2026-08-25T00:00:02Z"));

        assertThrows(IllegalArgumentException.class, () -> new TurnEvent.Terminal(
                context, TurnState.FAILED, "", "INTERNAL_ERROR", "turn failed", null, null));
    }

    /** 验证一次模型步骤将 usage 和有序 Tool 调用投影为同一事件。 */
    @Test
    void mapsCommittedModelStep() {
        TurnEventWireMapper mapper = new TurnEventWireMapper(new ObjectMapper(), "srv_test");
        TurnEvent.Context context = new TurnEvent.Context("evt_model", "thr_test", "turn_test", 4,
                Instant.parse("2026-08-25T00:00:01Z"));
        TurnEventWireMapper.WireEvent wire = mapper.map(new TurnEvent.ModelStepCommitted(
                context, "item_assistant", "read requested", "Checking the file", 2,
                usage(2, new ModelUsage(10, 4, 14)),
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

    /** started 只携带稳定关联，不重复发送命令、参数或 presentation。 */
    @Test
    void mapsCommittedToolStartedWithoutSensitivePresentation() {
        TurnEventWireMapper mapper = new TurnEventWireMapper(new ObjectMapper(), "srv_test");
        TurnEvent.Context context = new TurnEvent.Context("evt_tool_started", "thr_test", "turn_test", 5,
                Instant.parse("2026-08-25T00:00:02Z"));
        TurnEventWireMapper.WireEvent wire = mapper.map(
                new TurnEvent.ToolStarted(context, "call_test", 7));

        assertEquals("tool/started", wire.method());
        assertEquals("call_test", wire.params().path("callId").textValue());
        assertEquals(7, wire.params().path("ordinal").intValue());
        assertFalse(wire.params().has("presentation"));
        assertFalse(wire.params().has("arguments"));
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

    /** 消费事件内联同一附件摘要，避免 Renderer 在队列行消失后失去消息级预览身份。 */
    @Test
    void mapsConsumedInputWithMessageAttachmentSummaries() {
        TurnEventWireMapper mapper = new TurnEventWireMapper(new ObjectMapper(), "srv_test");
        Instant occurredAt = Instant.parse("2026-09-03T00:00:00Z");
        TurnEvent.Context context = new TurnEvent.Context(
                "evt_consumed", "thr_test", "turn_test", 7, occurredAt);
        UserContent content = new UserContent(List.of(new AttachmentContent("att_test")));
        AttachmentSummary summary = new AttachmentSummary(
                "att_test", "capture.png", 128, "image", "image/png");
        InputQueue.QueuedInput input = new InputQueue.QueuedInput(
                "input_test", "turn_test", content, InputQueue.Kind.FOLLOW_UP,
                List.of(summary), InputQueue.Status.PENDING, null, 1, occurredAt);
        InputQueue queue = new InputQueue("turn_test", 2, true, List.of());
        TurnEvent.UserItem userItem = new TurnEvent.UserItem(
                "item_user", occurredAt, "turn_test", content, List.of(summary));

        TurnEventWireMapper.WireEvent wire = mapper.map(
                new TurnEvent.InputConsumed(context, input, userItem, queue, null));

        assertEquals("att_test", wire.params().path("input").path("attachments")
                .get(0).path("attachmentId").textValue());
        assertEquals("capture.png", wire.params().path("userItem").path("attachments")
                .get(0).path("displayName").textValue());
        assertFalse(wire.params().path("userItem").path("attachments").get(0).has("state"));
    }

    /** 实时事件测试使用与历史相同的安全展示 DTO。 */
    private static ToolPresentation presentation(ToolPresentation.Status status) {
        return new ToolPresentation(ToolPresentation.Kind.READ, "read", status, "a.txt", "ok",
                List.of("a.txt"), null, ".", null, null, null, 1L, false, null);
    }

    /** 事件测试使用完整请求级 Usage，避免旧的 Turn 级 runtime 或裸 Token 重新进入 Wire。 */
    private static ProviderRequestUsage usage(int round, ModelUsage tokens) {
        ProviderRequestProfile profile = new ProviderRequestProfile(
                "provider_test", "model_test", "openai_responses", "gpt-test",
                "medium", "medium", AccessMode.APPROVAL_REQUIRED,
                io.github.kongweiguang.ja.conversation.domain.CollaborationMode.DEFAULT, "cfg_test",
                "prompt_test", "0".repeat(64), 100_000, 8_192);
        return new ProviderRequestUsage("request_round_" + round, round, round,
                ProviderRequestUsage.Purpose.ASSISTANT, ProviderRequestUsage.Certainty.KNOWN,
                profile, tokens);
    }
}
