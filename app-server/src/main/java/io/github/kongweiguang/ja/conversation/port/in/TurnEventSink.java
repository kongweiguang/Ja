// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.in;

import java.util.concurrent.CompletionStage;

/**
 * 在持久化事务返回后按顺序发布 Turn 事件，并允许入站传输层施加背压。
 */
@FunctionalInterface
public interface TurnEventSink extends ContextCompactionEventSink, ThreadMetadataEventSink {
    /** Root 恢复不绑定可见 Timeline；显式 no-op 避免调用方伪造事件订阅生命周期。 */
    static TurnEventSink noop() {
        return event -> java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    /**
     * 接收已提交事件或不可恢复草稿；完成阶段决定应用是否可以继续推进。
     */
    CompletionStage<Void> publish(TurnEvent event);

    /** 测试或非 RPC Sink 可不消费生命周期；生产连接必须显式覆盖以保持通知可见。 */
    @Override
    default CompletionStage<Void> publish(ContextCompactionEvent event) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    /** 测试或旧的进程内 Sink 可忽略元数据通知；生产 RPC Sink 必须覆盖以确定刷新会话列表。 */
    @Override
    default CompletionStage<Void> publish(ThreadMetadataEvent event) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
}
