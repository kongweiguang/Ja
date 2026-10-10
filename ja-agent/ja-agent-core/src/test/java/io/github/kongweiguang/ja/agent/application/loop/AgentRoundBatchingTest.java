// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.TurnEvent;
import io.github.kongweiguang.ja.agent.application.api.TurnEventSink;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.model.ModelContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证大量模型增量聚合为有界消息块，同时保留流式内容和结构化调用顺序。 */
final class AgentRoundBatchingTest {
    private static final int TEXT_BLOCK_CHARS = 262_144;
    private static final AgentIdentity IDENTITY =
            new AgentIdentity("tenant_test", "thread_round", "turn_round", "user_test");

    /** 后续用量快照覆盖旧快照；只有无 Tool 调用的正文才能成为终态文本。 */
    @Test
    void repeatedUsageKeepsLatestSnapshotAndAnswer() {
        AgentRound round = round("modelreq_usage", TurnEventSink.noop());
        ModelUsage earlier = new ModelUsage(2, 3, 5);
        ModelUsage latest = new ModelUsage(4, 6, 10);
        round.onEvent(new ModelPort.UsageEvent(earlier)).toCompletableFuture().join();
        round.onEvent(new ModelPort.TextDelta("answer")).toCompletableFuture().join();
        round.onEvent(new ModelPort.UsageEvent(latest)).toCompletableFuture().join();
        round.close();

        assertEquals(latest, round.usage());
        assertEquals("answer", round.terminalText(false));
        assertEquals("answer", round.partialText());
    }

    /** 十万个小增量在历史里形成一个正文块，避免按网络分片制造碎片化模型消息。 */
    @Test
    void manySmallDeltasMaterializeOneTextBlock() {
        AgentRound round = round("modelreq_small_deltas", TurnEventSink.noop());
        for (int index = 0; index < 100_000; index++) {
            round.onEvent(new ModelPort.TextDelta("x")).toCompletableFuture().join();
        }
        round.close();

        List<ModelContent> content = round.message().content();
        assertEquals(1, content.size());
        assertEquals("x".repeat(100_000), assertInstanceOf(TextContent.class, content.getFirst()).text());
    }

    /** 超长正文按固定上限拆成多个消息块，历史重读仍能无损拼回原文。 */
    @Test
    void assistantTextAboveFourMillionCharactersUsesBoundedBlocks() {
        AgentRound round = round("modelreq_long_text", TurnEventSink.noop());
        String text = "a".repeat(4_000_000) + "beyond old limit";
        round.onEvent(new ModelPort.TextDelta(text)).toCompletableFuture().join();
        round.close();

        List<ModelContent> content = round.message().content();
        List<String> blocks = content.stream().map(item -> assertInstanceOf(TextContent.class, item).text()).toList();
        assertTrue(blocks.size() > 1);
        assertTrue(blocks.stream().allMatch(block -> block.length() <= TEXT_BLOCK_CHARS));
        assertEquals(text, String.join("", blocks));
        assertTrue(blocks.getLast().endsWith("beyond old limit"));
    }

    /** Tool 边界冻结前后正文并保留原生 callId，避免把中间轮正文误当成最终答复。 */
    @Test
    void textToolTextClosePreservesAssistantBlockOrder() {
        AgentRound round = round("modelreq_tool_order", TurnEventSink.noop());
        round.onEvent(new ModelPort.TextDelta("before")).toCompletableFuture().join();
        round.onEvent(new ModelPort.ToolCallReady("call_demo", "echo", new JsonObject(Map.of()), 0))
                .toCompletableFuture().join();
        round.onEvent(new ModelPort.TextDelta("after")).toCompletableFuture().join();
        round.close();

        List<ModelContent> content = round.message().content();
        assertEquals(3, content.size());
        assertEquals("before", assertInstanceOf(TextContent.class, content.get(0)).text());
        ToolCallContent call = assertInstanceOf(ToolCallContent.class, content.get(1));
        assertEquals("call_demo", call.callId());
        assertEquals("echo", call.name());
        assertEquals("after", assertInstanceOf(TextContent.class, content.get(2)).text());
        assertEquals("", round.terminalText(true));
    }

    /** DSML 样式示例仍是普通文本；响应关闭后忽略迟到 Provider 回调，不改变已冻结正文。 */
    @Test
    void preservesSplitDsmlTextAndIgnoresLateCallbacksAfterClose() {
        List<TurnEvent> events = new ArrayList<>();
        AgentRound round = round("modelreq_plain_text", events::add);
        List<String> fragments = List.of("<", "||DS", "ML||tool_", "calls> as documentation");
        fragments.forEach(fragment -> round.onEvent(new ModelPort.TextDelta(fragment)).toCompletableFuture().join());
        String expected = String.join("", fragments);
        round.close();
        int committedDraftCount = events.size();
        round.onEvent(new ModelPort.TextDelta("late")).toCompletableFuture().join();

        assertEquals(committedDraftCount, events.size());
        assertEquals(expected, round.terminalText(false));
        assertEquals(expected, round.partialText());
        assertEquals(expected, assertInstanceOf(TextContent.class, round.message().content().getFirst()).text());
        assertEquals(expected, events.stream()
                .map(event -> assertInstanceOf(TurnEvent.TextDelta.class, event).text())
                .reduce("", String::concat));
    }

    /** 复用独立身份、共享序号源和无副作用时钟，使断言聚焦响应聚合语义。 */
    private static AgentRound round(String modelRequestId, TurnEventSink sink) {
        AtomicLong sequence = new AtomicLong();
        return new AgentRound(IDENTITY, modelRequestId, CancellationToken.none(),
                sink, sequence::incrementAndGet, Clock.systemUTC());
    }
}
