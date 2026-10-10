// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.TurnEvent;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 临时草稿和完整模型消息的边界测试，不用假流结果代替真实 Provider 验收。 */
class AgentRoundStreamTest {
    private final AgentIdentity identity = new AgentIdentity("tenant_test", "thread_test", "turn_test", "user_test");

    /** 重试和下一轮共享序号，Reset 之后不能重复使用旧请求帧号。 */
    @Test void streamSequenceIsMonotonicAcrossRequests() {
        List<TurnEvent> events = new ArrayList<>();
        AtomicLong sequence = new AtomicLong(1L << 32);
        AgentRound first = round("modelreq_first", events, sequence);
        first.onEvent(new ModelPort.TextDelta("未提交的片段")).toCompletableFuture().join();
        first.close();
        first.reset("MODEL_UNAVAILABLE");
        AgentRound second = round("modelreq_second", events, sequence);
        second.onEvent(new ModelPort.TextDelta("完整回答")).toCompletableFuture().join();
        assertEquals(List.of((1L << 32) + 1, (1L << 32) + 2, (1L << 32) + 3),
                events.stream().map(event -> event.context().sequence()).toList());
        assertInstanceOf(TurnEvent.Reset.class, events.get(1));
        assertEquals("完整回答", second.partialText());
    }

    /** 大片段分帧不能切开代理对，也不能截断模型文本。 */
    @Test void framesPreserveSupplementaryUnicode() {
        List<TurnEvent> events = new ArrayList<>();
        AgentRound round = round("modelreq_unicode", events, new AtomicLong());
        String text = "文".repeat(65_535) + "😀" + "末";
        round.onEvent(new ModelPort.TextDelta(text)).toCompletableFuture().join();
        round.close();
        assertEquals(text, events.stream().map(event -> ((TurnEvent.TextDelta) event).text())
                .collect(java.util.stream.Collectors.joining()));
        assertTrue(events.stream().allMatch(event -> ((TurnEvent.TextDelta) event).text().length() <= 65_536));
        assertEquals(text, round.partialText());
    }

    /** 已完成的调用保持原生身份和位置，重复身份即使来自其他 ordinal 也不得重复派发。 */
    @Test void structuredCallsRemainOrderedAndUnique() {
        AgentRound round = round("modelreq_tools", new ArrayList<>(), new AtomicLong());
        round.onEvent(new ModelPort.TextDelta("先读取")).toCompletableFuture().join();
        round.onEvent(new ModelPort.ToolCallReady("raw.provider:call", "read", new JsonObject(Map.of()), 0))
                .toCompletableFuture().join();
        assertInstanceOf(ToolCallContent.class, round.message().content().getLast());
        assertEquals("raw.provider:call", round.calls().getFirst().callId());
        assertThrows(RuntimeException.class, () -> round.onEvent(new ModelPort.ToolCallReady(
                "raw.provider:call", "read", new JsonObject(Map.of()), 1)));
    }

    /** 测试显式共享序号来源，不能每个 round 构造新计数器掩盖恢复顺序问题。 */
    private AgentRound round(String modelRequestId, List<TurnEvent> events, AtomicLong sequence) {
        return new AgentRound(identity, modelRequestId, CancellationToken.none(), events::add,
                sequence::incrementAndGet, Clock.systemUTC());
    }
}
