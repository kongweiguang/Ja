// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import io.github.kongweiguang.ja.conversation.domain.model.ProviderRequestProfile;
import io.github.kongweiguang.ja.conversation.domain.model.ProviderRequestUsage;
import io.github.kongweiguang.ja.conversation.domain.model.AttachmentSummary;
import io.github.kongweiguang.ja.conversation.domain.model.InputQueue;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.model.TurnChangeSet;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;

import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolRecoveryDecision;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;

import io.github.kongweiguang.ja.agent.domain.turn.TurnState;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEvent;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证领域事件映射的脱敏与关联字段，连接级元数据由 RpcSession 另行测试。 */
final class TurnEventWireMapperTest {
    /** 草稿只保留 Turn、流序号和文本，避免丢失的 delta 被误认为持久历史。 */
    @Test
    void mapsDeltaWithoutDurableFields() {
        TurnEventWireMapper mapper = new TurnEventWireMapper(new ObjectMapper(), "srv_test");
        TurnEventWireMapper.WireEvent wire = mapper.map(
                new TurnEvent.TextDelta("turn_test", 7, "hello", "modelreq_wire_test"));
        assertEquals("assistant/text-delta", wire.method());
        assertEquals(Set.of("turnId", "streamSeq", "text"),
                Set.copyOf(wire.params().properties().stream().map(entry -> entry.getKey()).toList()));
        assertFalse(wire.params().has("threadRevision"));
    }

    /** Provider 分片的最坏 JSON 控制字符转义仍须明显低于单帧上限。 */
    @Test
    void keepsMaximumStreamDeltaInsideFrameBudget() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TurnEventWireMapper eventMapper = new TurnEventWireMapper(mapper, "srv_test");
        TurnEventWireMapper.WireEvent wire = eventMapper.map(
                new TurnEvent.TextDelta("turn_test", 8, "\u0001".repeat(65_536), "modelreq_wire_test"));
        ObjectNode frame = mapper.createObjectNode().put("jsonrpc", "2.0").put("method", wire.method());
        frame.set("params", wire.params());

        assertTrue(mapper.writeValueAsBytes(frame).length < JaRpcCodec.DEFAULT_MAX_FRAME_BYTES);
    }

    /** retry 通知只发布当前尝试序号，保留持久事件 identity，且不暴露人工最大轮数。 */
    @Test
    void mapsRetryStartedWithAttemptNumber() {
        TurnEventWireMapper mapper = new TurnEventWireMapper(new ObjectMapper(), "srv_test");
        TurnEvent.Context context = new TurnEvent.Context("evt_retry", "thr_test", "turn_test", 8,
                0, Instant.parse("2026-09-23T00:00:00Z"));
        TurnEventWireMapper.WireEvent wire = mapper.map(new TurnEvent.RetryStarted(context, 3));

        assertEquals("turn/retry-started", wire.method());
        assertEquals(3, wire.params().path("attempt").intValue());
        assertEquals(false, wire.params().has("maxAttempts"));
        assertEquals("evt_retry", wire.params().path("eventId").textValue());
    }

    /** 持久终态保留 revision 和事件身份，sequence 由连接出站边界分配。 */
    @Test
    void mapsCommittedTerminal() {
        TurnEventWireMapper mapper = new TurnEventWireMapper(new ObjectMapper(), "srv_test");
        TurnEvent.Context context = new TurnEvent.Context("evt_test", "thr_test", "turn_test", 3,
                0, Instant.parse("2026-08-25T00:00:00Z"));
        TurnEventWireMapper.WireEvent wire = mapper.map(new TurnEvent.Terminal(
                context, TurnState.COMPLETED, "done", null, null,
                new TurnEvent.FinalMessage("item_test", "done"),
                usage(2, new ModelUsage(2, 1, 3)), TurnChangeSet.emptyComplete()));
        assertEquals("turn/terminal", wire.method());
        assertEquals(3, wire.params().path("threadRevision").longValue());
        assertEquals("item_test", wire.params().path("finalMessage").path("messageId").textValue());
        assertEquals(2, wire.params().path("usage").path("modelRound").intValue());
        assertEquals("complete", wire.params().path("changeSet").path("state").textValue());
        assertFalse(wire.params().has("seq"));
    }

    /** COMPLETED 表示变更冻结已和终态提交，不能由 wire 层制造缺失的空清单。 */
    @Test
    void completedTerminalRequiresCommittedChangeSet() {
        TurnEvent.Context context = new TurnEvent.Context("evt_completed_missing_changes", "thr_test", "turn_test", 3,
                0, Instant.parse("2026-08-25T00:00:00Z"));

        assertThrows(IllegalArgumentException.class, () -> new TurnEvent.Terminal(context, TurnState.COMPLETED,
                "done", null, null, new TurnEvent.FinalMessage("item_test", "done"), null, null));
    }

    /** 取消是独立终态而非失败别名，Wire 中不得出现错误字段或伪造最终消息。 */
    @Test
    void mapsCancelledTerminalWithoutFailureFields() {
        TurnEventWireMapper mapper = new TurnEventWireMapper(new ObjectMapper(), "srv_test");
        TurnEvent.Context context = new TurnEvent.Context("evt_cancelled", "thr_test", "turn_test", 4,
                0, Instant.parse("2026-08-25T00:00:01Z"));
        TurnEventWireMapper.WireEvent wire = mapper.map(new TurnEvent.Terminal(
                context, TurnState.CANCELLED, "", null, null, null, null, null));

        assertEquals("cancelled", wire.params().path("state").textValue());
        assertFalse(wire.params().has("errorCode"));
        assertFalse(wire.params().has("errorMessage"));
        assertFalse(wire.params().has("finalMessage"));
    }

    /** 普通澄清等待使用独立 wire 状态词汇，不能由 Java 枚举名字推导协议字段。 */
    @Test
    void mapsWaitingInputStateExplicitly() {
        assertEquals("waiting_input", TurnStateWire.value(TurnState.WAITING_INPUT));
        assertEquals("waiting_approval", TurnStateWire.value(TurnState.WAITING_APPROVAL));
    }

    /** 失败终态必须携带运行时生成的安全收口回复，同时保留失败码而不能投影为成功。 */
    @Test
    void mapsFailedTerminalWithSafeFinalMessage() {
        TurnEventWireMapper mapper = new TurnEventWireMapper(new ObjectMapper(), "srv_test");
        TurnEvent.Context context = new TurnEvent.Context("evt_failed", "thr_test", "turn_test", 5,
                0, Instant.parse("2026-08-25T00:00:02Z"));
        TurnEventWireMapper.WireEvent wire = mapper.map(new TurnEvent.Terminal(
                context, TurnState.FAILED, "safe failure reply", "BUDGET_EXCEEDED", "Model round limit reached",
                new TurnEvent.FinalMessage("item_failure_reply", "safe failure reply"), null, null));

        assertEquals("failed", wire.params().path("state").textValue());
        assertEquals("BUDGET_EXCEEDED", wire.params().path("errorCode").textValue());
        assertEquals("safe failure reply", wire.params().path("finalMessage").path("text").textValue());
    }

    /** 原生失败事实允许没有最终助手消息，Wire 仍保留真实机器码与错误说明。 */
    @Test
    void mapsFailedTerminalWithoutAssistantMessage() {
        TurnEvent.Context context = new TurnEvent.Context("evt_failed_empty", "thr_test", "turn_test", 5,
                0, Instant.parse("2026-08-25T00:00:02Z"));

        TurnEventWireMapper.WireEvent wire = new TurnEventWireMapper(new ObjectMapper(), "srv_test").map(
                new TurnEvent.Terminal(context, TurnState.FAILED, "", "INTERNAL_ERROR", "turn failed", null, null,
                        null));

        assertFalse(wire.params().has("finalMessage"));
        assertEquals("INTERNAL_ERROR", wire.params().path("errorCode").textValue());
        assertEquals("turn failed", wire.params().path("errorMessage").textValue());
        assertTrue(wire.params().has("changeSet"));
        assertTrue(wire.params().path("changeSet").isNull());
    }

    /** 验证一次模型步骤将 usage 和有序 Tool 调用投影为同一事件。 */
    @Test
    void mapsCommittedModelStep() {
        TurnEventWireMapper mapper = new TurnEventWireMapper(new ObjectMapper(), "srv_test");
        TurnEvent.Context context = new TurnEvent.Context("evt_model", "thr_test", "turn_test", 4,
                0, Instant.parse("2026-08-25T00:00:01Z"));
        TurnEventWireMapper.WireEvent wire = mapper.map(new TurnEvent.ModelStepCommitted(
                context, "item_assistant", "read requested", "Checking the file", 2,
                usage(2, new ModelUsage(10, 4, 14)),
                List.of(new TurnEvent.ToolCall("toolu_01Jxyz:part.2", "read_file", presentation(
                        ToolPresentation.Status.PENDING), 0))));
        assertEquals("assistant/model-step-committed", wire.method());
        assertEquals(2, wire.params().path("modelRound").intValue());
        assertEquals(14, wire.params().path("usage").path("totalTokens").intValue());
        assertEquals("toolu_01Jxyz:part.2", wire.params().path("toolCalls").get(0).path("callId").textValue());
        assertEquals("a.txt", wire.params().path("toolCalls").get(0)
                .path("presentation").path("relativePaths").get(0).textValue());
        assertEquals("Checking the file", wire.params().path("reasoningSummary").textValue());
    }

    /** Dispatch 的本地 estimate 与真实 Provider Usage 分开标注，并沿相同请求画像发布。 */
    @Test
    void mapsDispatchContextEstimateWithoutInventingProviderUsage() {
        TurnEventWireMapper mapper = new TurnEventWireMapper(new ObjectMapper(), "srv_test");
        Instant occurredAt = Instant.parse("2026-08-25T00:00:03Z");
        ProviderRequestProfile profile = usage(1, new ModelUsage(10, 1, 11)).profile();
        ProviderRequestUsage unknown = new ProviderRequestUsage("modelreq_estimate", 2, 2,
                ProviderRequestUsage.Purpose.ASSISTANT, ProviderRequestUsage.Certainty.UNKNOWN,
                profile, null);
        TurnEvent.Context context = new TurnEvent.Context("evt_estimate", "thr_test", "turn_test", 5,
                1, occurredAt);

        TurnEventWireMapper.WireEvent wire = mapper.map(
                new TurnEvent.ContextUsageEstimated(context, unknown, 24_000));

        assertEquals("assistant/context-estimated", wire.method());
        assertEquals("unknown", wire.params().path("usage").path("certainty").textValue());
        assertTrue(wire.params().path("usage").path("inputTokens").isNull());
        assertEquals("estimated", wire.params().path("usage").path("contextEstimate")
                .path("certainty").textValue());
        assertEquals(24_000, wire.params().path("usage").path("contextEstimate")
                .path("usedTokens").intValue());
        assertEquals(100_000, wire.params().path("usage").path("contextEstimate")
                .path("limitTokens").intValue());
    }

    /** started 只携带稳定关联，不重复发送命令、参数或 presentation。 */
    @Test
    void mapsCommittedToolStartedWithoutSensitivePresentation() {
        TurnEventWireMapper mapper = new TurnEventWireMapper(new ObjectMapper(), "srv_test");
        TurnEvent.Context context = new TurnEvent.Context("evt_tool_started", "thr_test", "turn_test", 5,
                0, Instant.parse("2026-08-25T00:00:02Z"));
        TurnEventWireMapper.WireEvent wire = mapper.map(
                new TurnEvent.ToolStarted(context, "call_test", 7));

        assertEquals("tool/started", wire.method());
        assertEquals("call_test", wire.params().path("callId").textValue());
        assertEquals(7, wire.params().path("ordinal").intValue());
        assertFalse(wire.params().has("presentation"));
        assertFalse(wire.params().has("arguments"));
    }

    /** 审批通知复用冻结的 Prepared 展示，让用户能核对命令且不接触原始参数。 */
    @Test
    void mapsApprovalRequestedWithFrozenPresentation() {
        TurnEventWireMapper mapper = new TurnEventWireMapper(new ObjectMapper(), "srv_test");
        TurnEvent.Context context = new TurnEvent.Context("evt_approval", "thr_test", "turn_test", 6,
                0, Instant.parse("2026-08-25T00:00:03Z"));
        ToolPresentation prepared = new ToolPresentation(ToolPresentation.Kind.SHELL, "执行命令",
                ToolPresentation.Status.PENDING, null, null, null, List.of(), List.of(), "pnpm test",
                "workspace", null, null, null, null, false, null);

        TurnEventWireMapper.WireEvent wire = mapper.map(new TurnEvent.ApprovalRequested(context,
                "appr_test", "call_test", "shell", prepared, "运行测试", context.occurredAt().plusSeconds(60)));

        assertEquals("approval/requested", wire.method());
        assertEquals("pnpm test", wire.params().path("presentation").path("command").textValue());
        assertEquals("pending", wire.params().path("presentation").path("status").textValue());
        assertEquals("workspace", wire.params().path("presentation").path("relativeCwd").textValue());
        assertFalse(wire.params().has("arguments"));
    }

    /** 不允许在审批事实中携带已经进入执行态的展示，避免把授权请求和执行结果混淆。 */
    @Test
    void rejectsNonPendingApprovalPresentation() {
        TurnEvent.Context context = new TurnEvent.Context("evt_approval_invalid", "thr_test", "turn_test", 6,
                0, Instant.parse("2026-08-25T00:00:03Z"));

        assertThrows(IllegalArgumentException.class, () -> new TurnEvent.ApprovalRequested(context,
                "appr_invalid", "call_test", "shell", presentation(ToolPresentation.Status.SUCCESS),
                "运行测试", context.occurredAt().plusSeconds(60)));
    }

    /** 验证 Tool 批次保留服务实例关联、结果顺序和非敏感的工作区脏状态。 */
    @Test
    void mapsCommittedToolBatch() {
        TurnEventWireMapper mapper = new TurnEventWireMapper(new ObjectMapper(), "srv_test");
        TurnEvent.Context context = new TurnEvent.Context("evt_tool", "thr_test", "turn_test", 5,
                0, Instant.parse("2026-08-25T00:00:02Z"));
        TurnEventWireMapper.WireEvent wire = mapper.map(new TurnEvent.ToolBatchCommitted(
                context,
                List.of(new TurnEvent.ToolBatchResult("call_test", ToolOutcome.SUCCEEDED,
                        presentation(ToolPresentation.Status.SUCCESS), 0, null))));
        assertEquals("tool/batch-committed", wire.method());
        assertEquals("srv_test", wire.params().path("serverInstanceId").textValue());
        assertFalse(wire.params().has("workspaceDirty"));
    }

    /** 未知副作用的 PENDING 仅暴露恢复身份和 CAS，不改变真实 Tool 结果状态。 */
    @Test
    void mapsToolRecoveryDecisionAndSystemApprovalClose() {
        TurnEventWireMapper mapper = new TurnEventWireMapper(new ObjectMapper(), "srv_test");
        TurnEvent.Context context = new TurnEvent.Context("evt_recovery", "thr_test", "turn_test", 9,
                0, Instant.parse("2026-09-26T00:00:00Z"));
        TurnEventWireMapper.WireEvent recovery = mapper.map(new TurnEvent.ToolRecoveryChanged(context,
                "recovery_test", "toolu_native:call", 1, ToolRecoveryDecision.Status.PENDING, null));

        assertEquals("tool/recovery-changed", recovery.method());
        assertEquals("toolu_native:call", recovery.params().path("callId").textValue());
        assertEquals(1, recovery.params().path("revision").intValue());
        assertEquals("pending", recovery.params().path("status").textValue());
        assertTrue(recovery.params().path("requiresUserAction").booleanValue());

        TurnEventWireMapper.WireEvent closed = mapper.map(new TurnEvent.ApprovalClosed(
                context, "appr_wait", InteractionStatus.SUPERSEDED));
        assertEquals("approval/closed", closed.method());
        assertEquals("appr_wait", closed.params().path("approvalId").textValue());
        assertEquals("superseded", closed.params().path("reason").textValue());
        assertFalse(closed.params().has("decision"));

        TurnEventWireMapper.WireEvent expired = mapper.map(new TurnEvent.ApprovalClosed(
                context, "appr_expired", InteractionStatus.EXPIRED));
        assertEquals("expired", expired.params().path("reason").textValue());
        assertFalse(expired.params().has("decision"));
    }

    /** 消费事件内联同一附件摘要，避免 Renderer 在队列行消失后失去消息级预览身份。 */
    @Test
    void mapsConsumedInputWithMessageAttachmentSummaries() {
        TurnEventWireMapper mapper = new TurnEventWireMapper(new ObjectMapper(), "srv_test");
        Instant occurredAt = Instant.parse("2026-09-03T00:00:00Z");
        TurnEvent.Context context = new TurnEvent.Context(
                "evt_consumed", "thr_test", "turn_test", 7, 0, occurredAt);
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

    /** mailbox 事件保留落库来源与权威 revision，严格排除快照专属 origin 以遵守实时通知字段集。 */
    @Test
    void mapsReceivedMessagesWithCommittedContext() {
        TurnEventWireMapper mapper = new TurnEventWireMapper(new ObjectMapper(), "srv_test");
        Instant occurredAt = Instant.parse("2026-09-03T00:00:00Z");
        TurnEvent.Context context = new TurnEvent.Context(
                "evt_received", "thr_target", "turn_target", 9, 0, occurredAt);
        ThreadSnapshot.ThreadMessageItem item = new ThreadSnapshot.ThreadMessageItem(
                "item_message", occurredAt, "turn_target", "thr_sender", "发送方标题", "阶段结果", 4);

        TurnEventWireMapper.WireEvent wire = mapper.map(new TurnEvent.MessagesReceived(context, List.of(item)));

        assertEquals("turn/messages_received", wire.method());
        assertEquals("srv_test", wire.params().path("serverInstanceId").textValue());
        assertEquals(9, wire.params().path("threadRevision").longValue());
        assertEquals("thread_message", wire.params().path("items").get(0).path("kind").textValue());
        assertEquals("thr_sender", wire.params().path("items").get(0).path("sourceThreadId").textValue());
        assertEquals(4, wire.params().path("items").get(0).path("totalCharacters").intValue());
        assertFalse(wire.params().path("items").get(0).path("truncated").booleanValue());
        assertEquals(Set.of("itemId", "createdAt", "turnId", "kind", "sourceThreadId", "sourceTitle",
                        "content", "totalCharacters", "truncated"),
                Set.copyOf(wire.params().path("items").get(0).propertyStream()
                        .map(entry -> entry.getKey()).toList()));
    }

    /** 实时事件测试使用与历史相同的安全展示 DTO。 */
    private static ToolPresentation presentation(ToolPresentation.Status status) {
        return new ToolPresentation(ToolPresentation.Kind.READ, "read", status, "a.txt", "ok",
                "读取完成", List.of("a.txt"), null, ".", null, null, null, 1L, false, null);
    }

    /** 事件测试使用完整请求级 Usage，避免旧的 Turn 级 runtime 或裸 Token 重新进入 Wire。 */
    private static ProviderRequestUsage usage(int round, ModelUsage tokens) {
        ProviderRequestProfile profile = new ProviderRequestProfile(
                "provider_test", "model_test", "openai_responses", "gpt-test",
                "medium", "medium", AccessMode.APPROVAL_REQUIRED,
                io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode.DEFAULT, "cfg_test",
                "prompt_test", "0".repeat(64), 100_000, 8_192);
        return new ProviderRequestUsage("modelreq_round_" + round, round, round,
                ProviderRequestUsage.Purpose.ASSISTANT, ProviderRequestUsage.Certainty.KNOWN,
                profile, tokens);
    }
}
