// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.task.port.in;

import io.github.kongweiguang.ja.conversation.port.in.TurnEventSink;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 同一连接同时接收 Task 低频事件与 Child Turn Timeline，保证 UI 不需要轮询拼接。 */
public interface TaskEventSink {
    /** 发布一个已经提交或可丢弃的 Task 事件。 */
    CompletionStage<Void> publish(TaskEvent event);

    /** 返回 Child Turn 复用的既有 Timeline sink。 */
    TurnEventSink turnEvents();

    /** Child 入队前登记 Timeline 关联；默认空实现供无 UI 的恢复与测试路径复用。 */
    default void registerTurn(String turnId, String workspaceId, String threadId, long initialThreadRevision) {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(threadId, "threadId");
        if (initialThreadRevision < 0) throw new IllegalArgumentException("invalid thread revision");
    }

    /** admission 或调度失败时移除尚未产生终态的关联，避免连接期上下文泄漏。 */
    default void abandonTurn(String turnId) {
        Objects.requireNonNull(turnId, "turnId");
    }

    /** 测试和无 UI 恢复路径使用的空 sink，不改变持久化事实。 */
    static TaskEventSink noop() {
        return new TaskEventSink() {
            /** 空订阅立即完成，避免后台线程。 */
            @Override public CompletionStage<Void> publish(TaskEvent event) {
                Objects.requireNonNull(event, "event");
                return CompletableFuture.completedFuture(null);
            }

            /** Child Turn 事件同样被明确丢弃。 */
            @Override public TurnEventSink turnEvents() {
                return event -> {
                    Objects.requireNonNull(event, "event");
                    return CompletableFuture.completedFuture(null);
                };
            }
        };
    }
}
