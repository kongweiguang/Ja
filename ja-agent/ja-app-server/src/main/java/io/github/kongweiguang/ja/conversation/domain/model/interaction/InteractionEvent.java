// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.domain.model.interaction;

import java.time.Instant;

/**
 * Interaction 轻量事件；客户端收到后必须 read 对账完整 Snapshot。
 *
 * @param threadId 所属对话的稳定标识
 * @param interactionId 交互标识
 * @param interactionRevision 交互修订版本
 * @param eventSequence 事件序号
 * @param kind 类型分类
 * @param occurredAt 事件发生时间
 */
public record InteractionEvent(String threadId,
                               String interactionId,
                               long interactionRevision,
                               long eventSequence,
                               Kind kind,
                               Instant occurredAt) {
    /**
     * 事件身份和序列必须同时有效，客户端才能在重连后检测缺口而不是猜测状态。
     */
    public InteractionEvent {
        if (threadId == null || interactionId == null || interactionRevision < 0 || eventSequence <= 0
            || kind == null || occurredAt == null) {
                throw new IllegalArgumentException("invalid interaction event");
            }
    }

    /**
     * 每次变化只作失效提示，消费者须按服务端序列对账快照。
     */
    public enum Kind {
        /**
         * 新问题批次已经持久化。
         */
        CREATED,
        /**
         * 本地草稿已保存。
         */
        DRAFT_CHANGED,
        /**
         * 全部问题答案已原子确认。
         */
        ANSWERED,
        /**
         * 问题被用户显式取消。
         */
        CANCELLED,
        /**
         * 问题被新的 steering 或请求替代。
         */
        SUPERSEDED
    }
}
