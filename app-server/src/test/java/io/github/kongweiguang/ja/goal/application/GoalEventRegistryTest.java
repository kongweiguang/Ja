// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.goal.port.in.GoalEvent;
import io.github.kongweiguang.ja.goal.port.in.GoalEventSink;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证 continuation Turn 只进入登记时明确接受的 Goal 观察连接。 */
final class GoalEventRegistryTest {
    /** 多连接中拒绝登记的连接不接收流事件，完成后每个接受者只清理一次。 */
    @Test
    void routesTurnOnlyToAcceptedObserversAndCleansThem() {
        GoalEventRegistry registry = new GoalEventRegistry();
        RecordingSink observed = new RecordingSink(true);
        RecordingSink hidden = new RecordingSink(false);
        registry.subscribe(observed);
        registry.subscribe(hidden);

        GoalEventRegistry.RoutedTurn route = registry.registerTurn(
                "goal_one", "turn_one", "ws_one", "thr_one", 4);
        route.sink().publish(new TurnEvent.TextDelta("turn_one", 1, "等待批准"))
                .toCompletableFuture().join();
        route.abandon();

        assertEquals(1, observed.registrations.get());
        assertEquals(1, observed.turnEvents.get());
        assertEquals(1, observed.abandons.get());
        assertEquals(1, hidden.registrations.get());
        assertEquals(0, hidden.turnEvents.get());
        assertEquals(0, hidden.abandons.get());
    }

    /** 最小连接探针分别记录登记、流事件和释放，不伪造 Goal 持久状态。 */
    private static final class RecordingSink implements GoalEventSink {
        private final boolean accepts;
        private final AtomicInteger registrations = new AtomicInteger();
        private final AtomicInteger turnEvents = new AtomicInteger();
        private final AtomicInteger abandons = new AtomicInteger();

        /** 每个 fixture 固定是否观察目标 Goal，使路由断言确定。 */
        private RecordingSink(boolean accepts) {
            this.accepts = accepts;
        }

        /** 本测试不发布低频 Goal 事件。 */
        @Override public java.util.concurrent.CompletionStage<Void> publish(GoalEvent event) {
            return CompletableFuture.completedFuture(null);
        }

        /** 记录每个订阅者都被询问，但只让观察者进入冻结路由。 */
        @Override public boolean registerTurn(String goalId, String turnId, String workspaceId,
                                              String threadId, long initialThreadRevision) {
            registrations.incrementAndGet();
            return accepts;
        }

        /** 只有接受登记的连接应被注册表调用。 */
        @Override public java.util.concurrent.CompletionStage<Void> publishTurn(
                String goalId, TurnEvent event) {
            turnEvents.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }

        /** 路由结束时仅清理接受登记的连接。 */
        @Override public void abandonTurn(String goalId, String turnId) {
            abandons.incrementAndGet();
        }
    }
}
