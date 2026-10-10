// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.engine;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.TurnEvent;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentStreamSubscription;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证临时流只按完整执行身份路由，且慢消费者不会扩张内存或拖住 Loop。 */
final class AgentStreamRegistryTest {
    /** 提前建立的订阅接收对应 execution 的增量，其他 execution 的内容不会串流。 */
    @Test
    void routesTransientEventsByCompleteIdentity() throws InterruptedException {
        AgentStreamRegistry registry = new AgentStreamRegistry(2);
        AgentIdentity identity = identity("turn_a");
        AgentStreamSubscription subscription = registry.subscribe(identity, 2);
        AgentStreamSubscription observer = registry.subscribe(identity, 2);

        registry.sink(identity).publish(event(identity, 1, "first"));
        registry.sink(identity("turn_b")).publish(event(identity("turn_b"), 1, "other"));

        AgentStreamSubscription.PollResult result = subscription.poll(Duration.ZERO);
        assertTrue(result.event().isPresent());
        assertEquals("first", ((TurnEvent.TextDelta) result.event().orElseThrow()).text());
        assertEquals(AgentStreamSubscription.State.OPEN, result.state());
        assertEquals("first", ((TurnEvent.TextDelta) observer.poll(Duration.ZERO).event().orElseThrow()).text());
        registry.close();
    }

    /** 慢订阅者因不同请求的真实队列帧溢出时独立重同步，正常观察者仍完整收到两帧。 */
    @Test
    void overflowMarksOnlyThatSubscriptionForResynchronization() throws InterruptedException {
        AgentStreamRegistry registry = new AgentStreamRegistry(2);
        AgentIdentity identity = identity("turn_a");
        AgentStreamSubscription subscription = registry.subscribe(identity, 1);
        AgentStreamSubscription observer = registry.subscribe(identity, 2);

        registry.sink(identity).publish(event(identity, "request_a", 1, "first"));
        registry.sink(identity).publish(event(identity, "request_b", 2, "second"));

        AgentStreamSubscription.PollResult result = subscription.poll(Duration.ZERO);
        assertFalse(result.event().isPresent());
        assertEquals(AgentStreamSubscription.State.RESYNC_REQUIRED, result.state());
        assertEquals("first", ((TurnEvent.TextDelta) observer.poll(Duration.ZERO).event().orElseThrow()).text());
        assertEquals("second", ((TurnEvent.TextDelta) observer.poll(Duration.ZERO).event().orElseThrow()).text());
        assertEquals(AgentStreamSubscription.State.OPEN, observer.state());
        registry.close();
    }

    /** 容量与单 execution 订阅数都有硬上限，关闭后等待者可观察到资源释放。 */
    @Test
    void rejectsDuplicateAndOutOfBoundSubscriptionsAndWakesOnClose() throws InterruptedException {
        AgentStreamRegistry registry = new AgentStreamRegistry(2);
        AgentIdentity identity = identity("turn_a");
        AgentStreamSubscription subscription = registry.subscribe(identity, 1);
        registry.subscribe(identity, 1);

        assertThrows(IllegalStateException.class, () -> registry.subscribe(identity("turn_b"), 1));
        assertThrows(IllegalArgumentException.class, () -> registry.subscribe(identity("turn_c"), 257));
        registry.close();
        assertEquals(AgentStreamSubscription.State.CLOSED, subscription.poll(Duration.ZERO).state());
    }

    /** 每个测试执行身份显式提供租户、会话与调用方字段，覆盖 Registry 的完整键语义。 */
    private static AgentIdentity identity(String turnId) {
        return new AgentIdentity("tenant_a", "thread_a", turnId, "user_a");
    }

    /** 生成只用于瞬时传输的正文片段，不构造任何可持久化事件。 */
    private static TurnEvent event(AgentIdentity identity, long sequence, String text) {
        return new TurnEvent.TextDelta(new TurnEvent.Context(identity, "modelreq_a", sequence, Instant.EPOCH), text);
    }

    /** 构造指定请求边界的片段，确保慢订阅溢出测试不会被合法正文合并抵消。 */
    private static TurnEvent event(AgentIdentity identity, String request, long sequence, String text) {
        return new TurnEvent.TextDelta(new TurnEvent.Context(identity, request, sequence, Instant.EPOCH), text);
    }
}
