// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.conversation.port.in.TurnEventSink;
import io.github.kongweiguang.ja.goal.port.in.GoalEvent;
import io.github.kongweiguang.ja.goal.port.in.GoalEventSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/** 进程内 Goal 订阅注册表，同时为隐藏 continuation 冻结一组明确的观察连接。 */
public final class GoalEventRegistry {
    private final CopyOnWriteArrayList<GoalEventSink> subscribers = new CopyOnWriteArrayList<>();

    /** 每个订阅句柄只移除自己的连接出口，不改变持久 Goal 或其它观察者。 */
    public AutoCloseable subscribe(GoalEventSink sink) {
        Objects.requireNonNull(sink, "sink");
        if (!subscribers.addIfAbsent(sink)) throw new IllegalStateException("Goal event sink already subscribed");
        return () -> subscribers.remove(sink);
    }

    /** 已提交 Goal 事实按稳定订阅顺序发布，连接背压失败不得被静默吞掉。 */
    public void publish(GoalEvent event) {
        Objects.requireNonNull(event, "event");
        for (GoalEventSink sink : subscribers) sink.publish(event).toCompletableFuture().join();
    }

    /** 没有订阅者时调用方可跳过额外的快照与事件查询。 */
    public boolean isEmpty() {
        return subscribers.isEmpty();
    }

    /**
     * Turn admission 前冻结接受登记的连接；任一登记失败会回滚此前登记，禁止生成部分可见的
     * 审批流。后续新观察者通过权威快照恢复，不加入已经开始的流式 Turn。
     */
    public RoutedTurn registerTurn(String goalId, String turnId, String workspaceId, String threadId,
                                   long initialThreadRevision) {
        List<GoalEventSink> routes = new ArrayList<>();
        try (RegistrationRollback rollback = new RegistrationRollback(routes, goalId, turnId)) {
            for (GoalEventSink sink : subscribers) {
                if (sink.registerTurn(goalId, turnId, workspaceId, threadId, initialThreadRevision)) {
                    routes.add(sink);
                }
            }
            rollback.commit();
            return new RoutedTurn(goalId, turnId, routes);
        }
    }

    /** 清理失败作为 suppressed 证据保留，不能覆盖原始 admission 或发布失败。 */
    private static void abandon(List<GoalEventSink> routes, String goalId, String turnId, Throwable owner) {
        for (GoalEventSink sink : routes) {
            try {
                sink.abandonTurn(goalId, turnId);
            } catch (RuntimeException cleanupFailure) {
                owner.addSuppressed(cleanupFailure);
            }
        }
    }

    /** try-with-resources 让登记异常保持为主失败，并把回滚异常自动挂为 suppressed。 */
    private static final class RegistrationRollback implements AutoCloseable {
        private final List<GoalEventSink> routes;
        private final String goalId;
        private final String turnId;
        private boolean committed;

        /** 保存仍在构建的路由引用，使失败点之前的所有登记都可被撤销。 */
        private RegistrationRollback(List<GoalEventSink> routes, String goalId, String turnId) {
            this.routes = routes;
            this.goalId = goalId;
            this.turnId = turnId;
        }

        /** 完整登记后关闭回滚职责，返回的 RoutedTurn 接管后续清理。 */
        private void commit() {
            committed = true;
        }

        /** 未提交时聚合清理失败；try-with-resources 会保留原始登记异常。 */
        @Override public void close() {
            if (committed) return;
            RuntimeException failure = new IllegalStateException("Goal continuation registration rollback failed");
            GoalEventRegistry.abandon(routes, goalId, turnId, failure);
            if (failure.getSuppressed().length > 0) throw failure;
        }
    }

    /** 一次 continuation 固定使用登记时的路由集合，避免多连接之间串流。 */
    public static final class RoutedTurn implements AutoCloseable {
        private final String goalId;
        private final String turnId;
        private final List<GoalEventSink> routes;
        private boolean retained;

        /** 路由列表冻结后不受后续 subscribe/unsubscribe 迭代变化影响。 */
        private RoutedTurn(String goalId, String turnId, List<GoalEventSink> routes) {
            this.goalId = Objects.requireNonNull(goalId, "goalId");
            this.turnId = Objects.requireNonNull(turnId, "turnId");
            this.routes = List.copyOf(routes);
        }

        /** 聚合所有目标连接的 publish stage，确保 Turn 生命周期观察到真实出站背压。 */
        public TurnEventSink sink() {
            return event -> {
                CompletableFuture<?>[] publications = routes.stream()
                        .map(route -> route.publishTurn(goalId, event).toCompletableFuture())
                        .toArray(CompletableFuture[]::new);
                return CompletableFuture.allOf(publications);
            };
        }

        /** admission 和 gate 登记都成功后把生命周期交给 completion 回调。 */
        public void retain() {
            retained = true;
        }

        /** admission 失败或 Turn 完成后幂等释放所有连接关联。 */
        public void abandon() {
            RuntimeException failure = new IllegalStateException("Goal continuation route cleanup failed");
            GoalEventRegistry.abandon(routes, goalId, turnId, failure);
            if (failure.getSuppressed().length > 0) throw failure;
        }

        /** admission 作用域异常退出时自动清理；retain 后由 completion 显式 abandon。 */
        @Override public void close() {
            if (!retained) abandon();
        }
    }
}
