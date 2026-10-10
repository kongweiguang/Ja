// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Ja Turn 边界同时发布通用执行事件和 Ja 专属 Thread 元数据，不把宿主事件塞回可复用 core。
 */
public interface ConversationTurnEventSink extends TurnEventSink, ThreadMetadataEventSink {
    /**
     * 在真实 Engine admission 已提交、执行尚未调度时同步推进通知基线；非 RPC sink 无需维护该观察状态。
     */
    default void onThreadRevision(AgentIdentity identity, long revision) {
        /* 非 RPC sink 不维护连接级通知基线。 */
    }

    /**
     * 为不产生持久 UI 或标题事件的测试与内部路径提供明确空接收器。
     */
    static ConversationTurnEventSink noop() {
        return new ConversationTurnEventSink() {
            /** 空接收器丢弃 Turn 投影事件；完成阶段立即就绪，不伪造远端投递结果。 */
            @Override
            public CompletionStage<Void> publish(TurnEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            /** 空接收器不发布 Thread 元数据，供明确不维护 UI 通知的内部路径使用。 */
            @Override
            public CompletionStage<Void> publish(ThreadMetadataEvent event) {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    /**
     * 明确把仅有 Turn 路由的内部接缝提升为 Ja 组合类型；元数据仍按旧 core no-op 语义处理。
     */
    static ConversationTurnEventSink turnEventsOnly(TurnEventSink turnEvents) {
        java.util.Objects.requireNonNull(turnEvents, "turnEvents");
        return new ConversationTurnEventSink() {
            /** 保留 core Turn 事件的真实发布阶段，调用方仍可等待 sink 的异步失败。 */
            @Override
            public CompletionStage<Void> publish(TurnEvent event) {
                return turnEvents.publish(event);
            }

            /** 透传投影失败通知，防止 Turn-only 适配器让权威重读请求落入默认 no-op。 */
            @Override
            public CompletionStage<Void> publishProjectionFailure(TurnProjectionFailure failure) {
                return turnEvents.publishProjectionFailure(failure);
            }

            /** 当前适配器只接通 Turn 路由，不伪造未接入的 Thread 元数据事件。 */
            @Override
            public CompletionStage<Void> publish(ThreadMetadataEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            /** 将提交水位同步给原 sink，使仅补充元数据接口的装配不丢失活动流 fencing。 */
            @Override
            public void observeCommittedTurn(String threadId, String turnId, long threadRevision,
                                             long turnMutationVersion, int modelRound, TurnEvent event,
                                             java.time.Instant occurredAt) {
                turnEvents.observeCommittedTurn(threadId, turnId, threadRevision,
                        turnMutationVersion, modelRound, event, occurredAt);
            }

            /** 压缩生命周期仍由原 Turn sink 发布，避免 Turn-only 适配器截断上下文事件。 */
            @Override
            public CompletionStage<Void> publish(ContextCompactionEvent event) {
                return turnEvents.publish(event);
            }
        };
    }
}
