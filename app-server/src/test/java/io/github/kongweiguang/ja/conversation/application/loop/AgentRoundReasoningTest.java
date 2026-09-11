// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.domain.model.ModelContent;
import io.github.kongweiguang.ja.conversation.domain.model.ReasoningContent;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 AgentRound 将公开文本与原生 reasoning 按 Provider 事件顺序冻结。 */
final class AgentRoundReasoningTest {
    /** reasoning block 必须切断相邻文本，保证 text -> reasoning -> text 的历史顺序可回放。 */
    @Test
    void freezesReasoningBetweenTextBlocks() {
        List<ModelContent> content;
        AgentRound round = new AgentRound(
                "turn_reasoning", CancellationToken.none(), event -> CompletableFuture.completedFuture(null),
                () -> false, 1, idleTimer(), new Sequences());
        ReasoningContent reasoning = new ReasoningContent(
                "provider_test", "model_test", "openai_responses", "gpt-5",
                ReasoningContent.endpointFingerprint(URI.create("https://api.example/v1")), "reasoning",
                "{\"type\":\"reasoning\"}");

        round.onEvent(new ModelPort.TextDelta("before"));
        round.onEvent(new ModelPort.ReasoningBlockReady(reasoning));
        round.onEvent(new ModelPort.TextDelta("after"));
        round.close();
        content = round.assistantContent();

        assertEquals(3, content.size());
        assertEquals("before", assertInstanceOf(TextContent.class, content.get(0)).text());
        assertEquals(reasoning, content.get(1));
        assertEquals("after", assertInstanceOf(TextContent.class, content.get(2)).text());
    }

    /** 终态完整块只能替换原位置，不能把同一个 reasoning 追加到 assistant 历史末尾。 */
    @Test
    void replacesReasoningBlockInPlaceWithoutPublishingReplacement() {
        List<ModelContent> content;
        ReasoningContent previous = reasoning("opaque-partial");
        ReasoningContent replacement = reasoning("opaque-complete");
        AgentRound round = new AgentRound(
                "turn_reasoning_replace", CancellationToken.none(),
                event -> CompletableFuture.completedFuture(null), () -> false, 1,
                idleTimer(), new Sequences());

        round.onEvent(new ModelPort.TextDelta("before"));
        round.onEvent(new ModelPort.ReasoningBlockReady(previous));
        round.onEvent(new ModelPort.ReasoningBlockReplaced(previous, replacement));
        round.onEvent(new ModelPort.TextDelta("after"));
        round.close();
        content = round.assistantContent();

        assertEquals(3, content.size());
        assertEquals("before", assertInstanceOf(TextContent.class, content.get(0)).text());
        assertEquals(replacement, content.get(1));
        assertEquals("after", assertInstanceOf(TextContent.class, content.get(2)).text());
    }

    /** replacement 不能借终态补全跨 Provider 或端点写入另一份 opaque 状态。 */
    @Test
    void rejectsReasoningReplacementWithChangedIdentity() {
        ReasoningContent previous = reasoning("opaque-partial");
        ReasoningContent changed = new ReasoningContent(
                "other-provider", previous.modelId(), previous.api(), previous.upstreamModel(),
                previous.endpointFingerprint(), previous.wireField(), "opaque-complete");
        AgentRound round = new AgentRound(
                "turn_reasoning_identity", CancellationToken.none(),
                event -> CompletableFuture.completedFuture(null), () -> false, 1,
                idleTimer(), new Sequences());
        round.onEvent(new ModelPort.ReasoningBlockReady(previous));

        assertThrows(RuntimeException.class, () -> round.onEvent(
                new ModelPort.ReasoningBlockReplaced(previous, changed))
                .toCompletableFuture().join());
    }

    /** 两个相同旧块会使替换目标不可判定，必须拒绝而不是随机改写其中一个。 */
    @Test
    void rejectsAmbiguousReasoningReplacementTarget() {
        ReasoningContent previous = reasoning("opaque-partial");
        ReasoningContent replacement = reasoning("opaque-complete");
        AgentRound round = new AgentRound(
                "turn_reasoning_ambiguous", CancellationToken.none(),
                event -> CompletableFuture.completedFuture(null), () -> false, 1,
                idleTimer(), new Sequences());
        round.onEvent(new ModelPort.ReasoningBlockReady(previous));
        round.onEvent(new ModelPort.ReasoningBlockReady(previous));

        assertThrows(RuntimeException.class, () -> round.onEvent(
                new ModelPort.ReasoningBlockReplaced(previous, replacement))
                .toCompletableFuture().join());
    }

    /** 构造只改变 opaque 载荷的同身份 reasoning，模拟 output_item.done 到 terminal 的补全。 */
    private static ReasoningContent reasoning(String nativePayload) {
        return new ReasoningContent(
                "provider_test", "model_test", "openai_responses", "gpt-5",
                ReasoningContent.endpointFingerprint(URI.create("https://api.example/v1")),
                "reasoning", "{\"type\":\"reasoning\",\"encrypted_content\":\""
                        + nativePayload + "\"}");
    }

    /** 构造不自动触发回调的 Timer，使测试只观察事件冻结而不依赖真实时间。 */
    private static StreamingDeltaBatcher.Timer idleTimer() {
        return new StreamingDeltaBatcher.Timer() {
            /** 测试 Timer 刻意不执行回调，避免异步批处理改变本测试观察到的内容顺序。 */
            @Override
            public Task schedule(Runnable callback, Duration delay) {
                return () -> {
                };
            }

            /** 空 Timer 没有线程或句柄需要释放，关闭操作保持幂等且无副作用。 */
            @Override
            public void close() {
            }
        };
    }

    /** 为 AgentRound 提供不共享生产运行态的确定性序号。 */
    private static final class Sequences implements AgentRound.SequenceAllocator {
        private long stream = 1;

        /** 为公开增量分配单调序号。 */
        @Override
        public long allocateStreamSequence() {
            return stream++;
        }

        /** reasoning-only 测试不产生 Tool，固定返回零起点。 */
        @Override
        public int allocateToolOrdinals(int count) {
            return 0;
        }
    }
}
