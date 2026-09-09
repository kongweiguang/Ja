// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.observation;

import io.github.kongweiguang.ja.conversation.port.out.ExecutionObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 锁定观察器的显式订阅、确定性排序与逐个故障隔离。 */
final class ExecutionObserversTest {
    /** 只向订阅当前类型的观察器分发，并按 order、ID 稳定排序。 */
    @Test
    void dispatchesOnlyToSubscribersInStableOrder() {
        List<String> trace = new ArrayList<>();
        ExecutionObservers observers = new ExecutionObservers(List.of(
                observer("zeta", 10, Set.of(ExecutionObserver.EventKind.TURN_STARTED), trace, false),
                observer("ignored", -1, Set.of(ExecutionObserver.EventKind.MODEL_STARTED), trace, false),
                observer("beta", 0, Set.of(ExecutionObserver.EventKind.TURN_STARTED), trace, false),
                observer("alpha", 0, Set.of(ExecutionObserver.EventKind.TURN_STARTED), trace, false)));

        observers.observe(new ExecutionObserver.TurnStarted("thr_fixture", "turn_fixture"));

        assertEquals(List.of("alpha", "beta", "zeta"), trace);
    }

    /** 单个观察器失败不能阻止后续订阅者，也不能向执行主线传播。 */
    @Test
    void isolatesObserverFailure() {
        List<String> trace = new ArrayList<>();
        ExecutionObservers observers = new ExecutionObservers(List.of(
                observer("broken", 0, Set.of(ExecutionObserver.EventKind.COMMITTED), trace, true),
                observer("healthy", 1, Set.of(ExecutionObserver.EventKind.COMMITTED), trace, false)));

        observers.observe(new ExecutionObserver.Committed(
                "thr_fixture", "turn_fixture", "ToolBatchCommitted", 7));

        assertEquals(List.of("broken", "healthy"), trace);
    }

    /** 重复观察器身份在组合时失败，避免同一实现被重复通知。 */
    @Test
    void rejectsDuplicateObserverIds() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionObservers(List.of(
                observer("same", 0, Set.of(ExecutionObserver.EventKind.TURN_STARTED),
                        new ArrayList<>(), false),
                observer("same", 1, Set.of(ExecutionObserver.EventKind.TURN_COMPLETED),
                        new ArrayList<>(), false))));
    }

    /** 构造显式订阅的同步观察器，异常正文用于证明分发器不会泄漏或传播它。 */
    private static ExecutionObserver observer(
            String id, int order, Set<ExecutionObserver.EventKind> subscriptions,
            List<String> trace, boolean fail) {
        return new ExecutionObserver() {
            /** 返回稳定注册身份。 */
            @Override public String id() { return id; }
            /** 返回显式优先级，验证分发不依赖注入列表。 */
            @Override public int order() { return order; }
            /** 仅订阅夹具声明的事件类型。 */
            @Override public Set<EventKind> subscriptions() { return subscriptions; }
            /** 记录实际分发；失败由 ExecutionObservers 隔离。 */
            @Override public void observe(Event event) {
                trace.add(id);
                if (fail) throw new IllegalStateException("secret fixture");
            }
        };
    }
}
