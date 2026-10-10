// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import java.util.concurrent.CompletionStage;

/**
 * 在持久化事务返回后按顺序发布 Turn 事件；只读投影失败不得反向改变已提交执行。
 */
@FunctionalInterface
public interface TurnEventSink extends ContextCompactionEventSink {
    /**
     * Root 恢复不绑定可见 Timeline；显式 no-op 避免调用方伪造事件订阅生命周期。
     */
    static TurnEventSink noop() {
        return event -> java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    /**
     * 接收已提交事件或临时草稿；sink 失败只影响观察与传输，不改写业务终态。
     */
    CompletionStage<Void> publish(TurnEvent event);

    /**
     * 投影无法解释已提交事实时请求宿主读取 Thread 权威快照，默认非 RPC sink 无需外部恢复。
     */
    default CompletionStage<Void> publishProjectionFailure(TurnProjectionFailure failure) {
        java.util.Objects.requireNonNull(failure, "failure");
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    /**
     * 观察同一 SQLite 提交的内部边界；它不生成 wire 帧，用来覆盖 dispatch UNKNOWN 等没有公开
     * TurnEvent 的 mutation，并把已完成 modelRound 与活动流 registry 保持在同一提交点。
     */
    default void observeCommittedTurn(String threadId, String turnId, long threadRevision,
                                      long turnMutationVersion, int modelRound, TurnEvent event,
                                      java.time.Instant occurredAt) {
        /* 进程内测试或非 RPC sink 没有恢复基线，默认不做外部副作用。 */
    }

    /**
     * 测试或非 RPC Sink 可不消费生命周期；生产连接必须显式覆盖以保持通知可见。
     */
    @Override
    default CompletionStage<Void> publish(ContextCompactionEvent event) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

}
