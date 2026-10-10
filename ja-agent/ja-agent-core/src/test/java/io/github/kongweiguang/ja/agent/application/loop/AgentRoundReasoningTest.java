// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.TurnEvent;
import io.github.kongweiguang.ja.agent.application.api.TurnEventSink;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.model.ModelContent;
import io.github.kongweiguang.ja.agent.domain.model.ReasoningContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证公开摘要与 Provider 原生推理块在模型消息中的顺序和身份约束。 */
final class AgentRoundReasoningTest {
    private static final AgentIdentity IDENTITY =
            new AgentIdentity("tenant_test", "thread_reasoning", "turn_reasoning", "user_test");

    /** 公开摘要按原文完整累积；内容长度不应成为模型响应成功与否的隐藏限制。 */
    @Test
    void longPublicReasoningDoesNotStopTheRound() {
        AgentRound round = round("modelreq_long_reasoning");
        String chunk = "思".repeat(600_000);
        round.onEvent(new ModelPort.ReasoningSummaryDelta(chunk)).toCompletableFuture().join();
        round.onEvent(new ModelPort.ReasoningSummaryDelta(chunk)).toCompletableFuture().join();
        round.close();

        assertEquals(1_200_000, round.reasoningSummary().length());
    }

    /** 原生 reasoning 块切开相邻正文，保证 text -> reasoning -> text 能按原顺序重建。 */
    @Test
    void freezesReasoningBetweenTextBlocks() {
        AgentRound round = round("modelreq_reasoning_order");
        ReasoningContent reasoning = reasoning("opaque-complete");
        round.onEvent(new ModelPort.TextDelta("before")).toCompletableFuture().join();
        round.onEvent(new ModelPort.ReasoningBlockReady(reasoning)).toCompletableFuture().join();
        round.onEvent(new ModelPort.TextDelta("after")).toCompletableFuture().join();
        round.close();

        List<ModelContent> content = round.message().content();
        assertEquals(3, content.size());
        assertEquals("before", assertInstanceOf(TextContent.class, content.get(0)).text());
        assertEquals(reasoning, content.get(1));
        assertEquals("after", assertInstanceOf(TextContent.class, content.get(2)).text());
    }

    /** Provider 终态补全只替换原生块原位置，不能把同一块追加到消息末尾。 */
    @Test
    void replacesReasoningBlockInPlaceWithoutPublishingReplacement() {
        AgentRound round = round("modelreq_reasoning_replace");
        ReasoningContent previous = reasoning("opaque-partial");
        ReasoningContent replacement = reasoning("opaque-complete");
        round.onEvent(new ModelPort.TextDelta("before")).toCompletableFuture().join();
        round.onEvent(new ModelPort.ReasoningBlockReady(previous)).toCompletableFuture().join();
        round.onEvent(new ModelPort.ReasoningBlockReplaced(previous, replacement)).toCompletableFuture().join();
        round.onEvent(new ModelPort.TextDelta("after")).toCompletableFuture().join();
        round.close();

        List<ModelContent> content = round.message().content();
        assertEquals(3, content.size());
        assertEquals("before", assertInstanceOf(TextContent.class, content.get(0)).text());
        assertEquals(replacement, content.get(1));
        assertEquals("after", assertInstanceOf(TextContent.class, content.get(2)).text());
    }

    /** 替换既有块时跳过仍未填充的其它槽，避免内部占位污染唯一目标查找。 */
    @Test
    void replacesExistingBlockWhileAnotherDeferredSlotIsUnfilled() {
        AgentRound round = round("modelreq_reasoning_replace_with_deferred");
        ReasoningContent previous = reasoning("opaque-before-terminal");
        ReasoningContent replacement = reasoning("opaque-after-terminal");
        round.onEvent(new ModelPort.ReasoningBlockDeferred("reason_slot_unfilled", null))
                .toCompletableFuture().join();
        round.onEvent(new ModelPort.ReasoningBlockReady(previous)).toCompletableFuture().join();
        round.onEvent(new ModelPort.ReasoningBlockReplaced(previous, replacement)).toCompletableFuture().join();
        round.onEvent(new ModelPort.TextDelta("answer")).toCompletableFuture().join();
        round.close();

        List<ModelContent> content = round.message().content();
        assertEquals(2, content.size());
        assertEquals(replacement, content.getFirst());
        assertEquals("answer", assertInstanceOf(TextContent.class, content.get(1)).text());
    }

    /** 延迟槽只保留模型历史位置，不阻止后续正文增量进入公开草稿流。 */
    @Test
    void deferredReasoningKeepsFollowingTextPublic() {
        List<TurnEvent> published = new ArrayList<>();
        AgentRound round = round("modelreq_deferred_public", published::add);
        round.onEvent(new ModelPort.ReasoningBlockDeferred("reason_slot_public", null))
                .toCompletableFuture().join();
        round.onEvent(new ModelPort.TextDelta("visible answer"))
                .toCompletableFuture().join();

        assertEquals(1, published.size());
        TurnEvent.TextDelta delta = assertInstanceOf(TurnEvent.TextDelta.class, published.getFirst());
        assertEquals("visible answer", delta.text());
    }

    /** 终态才补齐的 opaque block 填回原位，保留前后正文的 Provider 顺序。 */
    @Test
    void fillsLateReasoningAtItsDeclaredPosition() {
        AgentRound round = round("modelreq_deferred_order");
        ReasoningContent reasoning = reasoning("opaque-late");
        round.onEvent(new ModelPort.TextDelta("before")).toCompletableFuture().join();
        round.onEvent(new ModelPort.ReasoningBlockDeferred("reason_slot_order", null))
                .toCompletableFuture().join();
        round.onEvent(new ModelPort.TextDelta("after")).toCompletableFuture().join();
        round.onEvent(new ModelPort.ReasoningBlockDeferred("reason_slot_order", reasoning))
                .toCompletableFuture().join();
        round.close();

        List<ModelContent> content = round.message().content();
        assertEquals(3, content.size());
        assertEquals("before", assertInstanceOf(TextContent.class, content.get(0)).text());
        assertEquals(reasoning, content.get(1));
        assertEquals("after", assertInstanceOf(TextContent.class, content.get(2)).text());
    }

    /** 没有可回放 opaque 内容时，槽位在消息物化阶段消失且不会产生 null 持久内容。 */
    @Test
    void omitsUnfilledReasoningSlotFromMessage() {
        AgentRound round = round("modelreq_deferred_empty");
        round.onEvent(new ModelPort.ReasoningBlockDeferred("reason_slot_empty", null))
                .toCompletableFuture().join();
        round.onEvent(new ModelPort.TextDelta("answer without native block"))
                .toCompletableFuture().join();
        round.close();

        List<ModelContent> content = round.message().content();
        assertEquals(1, content.size());
        assertEquals("answer without native block", assertInstanceOf(TextContent.class, content.getFirst()).text());
    }

    /** 多个终态块即使逆序到达，也必须填回各自声明位置而不重排正文。 */
    @Test
    void fillsMultipleReasoningSlotsInReverseArrivalOrder() {
        AgentRound round = round("modelreq_deferred_reverse");
        ReasoningContent first = reasoning("opaque-first");
        ReasoningContent second = reasoning("opaque-second");
        round.onEvent(new ModelPort.TextDelta("before")).toCompletableFuture().join();
        round.onEvent(new ModelPort.ReasoningBlockDeferred("reason_slot_first", null))
                .toCompletableFuture().join();
        round.onEvent(new ModelPort.TextDelta("between")).toCompletableFuture().join();
        round.onEvent(new ModelPort.ReasoningBlockDeferred("reason_slot_second", null))
                .toCompletableFuture().join();
        round.onEvent(new ModelPort.TextDelta("after")).toCompletableFuture().join();
        round.onEvent(new ModelPort.ReasoningBlockDeferred("reason_slot_second", second))
                .toCompletableFuture().join();
        round.onEvent(new ModelPort.ReasoningBlockDeferred("reason_slot_first", first))
                .toCompletableFuture().join();
        round.close();

        List<ModelContent> content = round.message().content();
        assertEquals(5, content.size());
        assertEquals("before", assertInstanceOf(TextContent.class, content.get(0)).text());
        assertEquals(first, content.get(1));
        assertEquals("between", assertInstanceOf(TextContent.class, content.get(2)).text());
        assertEquals(second, content.get(3));
        assertEquals("after", assertInstanceOf(TextContent.class, content.get(4)).text());
    }

    /** 重复声明、重复填充和未知槽都属于 Provider 状态机错误，不能猜测正文位置。 */
    @Test
    void rejectsDuplicateAndUnknownReasoningSlots() {
        AgentRound duplicateDeclaration = round("modelreq_deferred_duplicate_declaration");
        duplicateDeclaration.onEvent(new ModelPort.ReasoningBlockDeferred("reason_slot_duplicate", null))
                .toCompletableFuture().join();
        assertThrows(AgentRound.AgentRoundFailure.class,
                () -> duplicateDeclaration.onEvent(
                        new ModelPort.ReasoningBlockDeferred("reason_slot_duplicate", null)));

        AgentRound repeatedFill = round("modelreq_deferred_duplicate_fill");
        ReasoningContent reasoning = reasoning("opaque-repeat");
        repeatedFill.onEvent(new ModelPort.ReasoningBlockDeferred("reason_slot_repeat", null))
                .toCompletableFuture().join();
        repeatedFill.onEvent(new ModelPort.ReasoningBlockDeferred("reason_slot_repeat", reasoning))
                .toCompletableFuture().join();
        assertThrows(AgentRound.AgentRoundFailure.class,
                () -> repeatedFill.onEvent(new ModelPort.ReasoningBlockDeferred("reason_slot_repeat", reasoning)));

        AgentRound unknownFill = round("modelreq_deferred_unknown_fill");
        assertThrows(AgentRound.AgentRoundFailure.class,
                () -> unknownFill.onEvent(new ModelPort.ReasoningBlockDeferred("reason_slot_unknown", reasoning)));

        AgentRound duplicateContent = round("modelreq_deferred_duplicate_content");
        duplicateContent.onEvent(new ModelPort.ReasoningBlockDeferred("reason_slot_first_content", null))
                .toCompletableFuture().join();
        duplicateContent.onEvent(new ModelPort.ReasoningBlockDeferred("reason_slot_second_content", null))
                .toCompletableFuture().join();
        duplicateContent.onEvent(new ModelPort.ReasoningBlockDeferred("reason_slot_first_content", reasoning))
                .toCompletableFuture().join();
        assertThrows(AgentRound.AgentRoundFailure.class,
                () -> duplicateContent.onEvent(
                        new ModelPort.ReasoningBlockDeferred("reason_slot_second_content", reasoning)));
    }

    /** 内部槽标识必须安全且有界，事件诊断不能暴露槽 ID 或 opaque 内容。 */
    @Test
    void deferredReasoningValidatesAndRedactsItsIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModelPort.ReasoningBlockDeferred(" ", null));
        ModelPort.ReasoningBlockDeferred event = new ModelPort.ReasoningBlockDeferred(
                "private_reason_slot", reasoning("secret-opaque-value"));

        assertFalse(event.toString().contains("private_reason_slot"));
        assertFalse(event.toString().contains("secret-opaque-value"));
    }

    /** replacement 只能补全同一 Provider、模型和端点的 opaque 块，身份变化必须失败关闭。 */
    @Test
    void rejectsReasoningReplacementWithChangedIdentity() {
        AgentRound round = round("modelreq_reasoning_identity");
        ReasoningContent previous = reasoning("opaque-partial");
        ReasoningContent changed = new ReasoningContent(
                "other-provider", previous.modelId(), previous.api(), previous.upstreamModel(),
                previous.endpointFingerprint(), previous.wireField(), "opaque-complete");
        round.onEvent(new ModelPort.ReasoningBlockReady(previous)).toCompletableFuture().join();

        assertThrows(AgentRound.AgentRoundFailure.class,
                () -> round.onEvent(new ModelPort.ReasoningBlockReplaced(previous, changed)));
    }

    /** 两个相同原生块令替换目标不可判定，必须报协议错误而不能随机改写一项。 */
    @Test
    void rejectsAmbiguousReasoningReplacementTarget() {
        AgentRound round = round("modelreq_reasoning_ambiguous");
        ReasoningContent previous = reasoning("opaque-partial");
        round.onEvent(new ModelPort.ReasoningBlockReady(previous)).toCompletableFuture().join();
        round.onEvent(new ModelPort.ReasoningBlockReady(previous)).toCompletableFuture().join();

        assertThrows(AgentRound.AgentRoundFailure.class,
                () -> round.onEvent(new ModelPort.ReasoningBlockReplaced(previous, reasoning("opaque-complete"))));
    }

    /** 用相同端点和 Provider 身份构造原生块，只改变 opaque 载荷以模拟终态补全。 */
    private static ReasoningContent reasoning(String nativePayload) {
        return new ReasoningContent(
                "provider_test", "model_test", "openai_responses", "gpt-5",
                ReasoningContent.endpointFingerprint(URI.create("https://api.example/v1")), "reasoning",
                "{\"type\":\"reasoning\",\"encrypted_content\":\"" + nativePayload + "\"}");
    }

    /** 每个测试使用独立响应和序号源，避免状态复用掩盖跨响应协议错误。 */
    private static AgentRound round(String modelRequestId) {
        return round(modelRequestId, TurnEventSink.noop());
    }

    /** 测试可替换草稿 sink 以验证占位事件不影响独立公开流。 */
    private static AgentRound round(String modelRequestId, TurnEventSink stream) {
        AtomicLong sequence = new AtomicLong();
        return new AgentRound(IDENTITY, modelRequestId, CancellationToken.none(),
                stream, sequence::incrementAndGet, Clock.systemUTC());
    }
}
