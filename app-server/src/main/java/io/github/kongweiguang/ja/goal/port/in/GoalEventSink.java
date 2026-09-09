// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.port.in;

import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 当前运行连接的 Goal 事件出口；关闭订阅只释放内存引用，不改变持久 Goal。 */
@FunctionalInterface
public interface GoalEventSink {
    /** 接受一个已持久化 mutation 的完整投影，并通过 CompletionStage 提供背压。 */
    CompletionStage<Void> publish(GoalEvent event);

    /**
     * continuation 入队前为当前仍观察 Goal 的连接登记 Turn 关联；返回 false 的连接不会收到该
     * Turn 后续事件，避免后台执行泄露给未展开 Goal 的窗口。
     */
    default boolean registerTurn(String goalId, String turnId, String workspaceId, String threadId,
                                 long initialThreadRevision) {
        Objects.requireNonNull(goalId, "goalId");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(threadId, "threadId");
        if (initialThreadRevision < 0) throw new IllegalArgumentException("invalid thread revision");
        return false;
    }

    /**
     * 只向已经接受登记且仍观察 Goal 的连接发布 continuation Turn 事件；默认空实现保留内部
     * 调度订阅和无 UI 恢复路径。
     */
    default CompletionStage<Void> publishTurn(String goalId, TurnEvent event) {
        Objects.requireNonNull(goalId, "goalId");
        Objects.requireNonNull(event, "event");
        return CompletableFuture.completedFuture(null);
    }

    /** admission、取消或连接解除观察时幂等释放该连接的 Turn 通知关联。 */
    default void abandonTurn(String goalId, String turnId) {
        Objects.requireNonNull(goalId, "goalId");
        Objects.requireNonNull(turnId, "turnId");
    }
}
