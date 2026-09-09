// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.port.in;

import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** standalone Plan run 的发起连接事件出口；它不创建 Goal observation 或第二份持久状态。 */
public interface PlanExecutionEventSink {
    /**
     * Turn admission 前登记连接级通知上下文；连接已经关闭时返回 false，执行仍可在后台完成，
     * 但不得把事件广播给其它窗口。
     */
    boolean registerTurn(String turnId, String workspaceId, String threadId, long initialThreadRevision);

    /** 只发布已经成功登记的 Plan Turn 事件，连接关闭或上下文已释放时允许静默结束。 */
    CompletionStage<Void> publish(TurnEvent event);

    /** admission 失败或 Turn 完成后幂等释放连接关联，不改变 Plan run 的持久终态。 */
    void abandonTurn(String turnId);

    /** 无 UI 发起者的恢复和测试路径继续执行，但明确不制造可见事件路由。 */
    static PlanExecutionEventSink noop() {
        return new PlanExecutionEventSink() {
            /** 无连接时不登记通知上下文。 */
            @Override public boolean registerTurn(String turnId, String workspaceId, String threadId,
                                                  long initialThreadRevision) {
                Objects.requireNonNull(turnId, "turnId");
                Objects.requireNonNull(workspaceId, "workspaceId");
                Objects.requireNonNull(threadId, "threadId");
                if (initialThreadRevision < 0) throw new IllegalArgumentException("invalid thread revision");
                return false;
            }

            /** 无连接时保留已完成 stage，不能阻塞后台 Plan run。 */
            @Override public CompletionStage<Void> publish(TurnEvent event) {
                Objects.requireNonNull(event, "event");
                return CompletableFuture.completedFuture(null);
            }

            /** 未登记路径没有连接资源需要释放。 */
            @Override public void abandonTurn(String turnId) {
                Objects.requireNonNull(turnId, "turnId");
            }
        };
    }
}
