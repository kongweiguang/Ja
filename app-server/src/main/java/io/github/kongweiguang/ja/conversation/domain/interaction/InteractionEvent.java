// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.domain.interaction;

import java.time.Instant;

/** Interaction 轻量事件；客户端收到后必须 read 对账完整 Snapshot。 */
public record InteractionEvent(String threadId, String requestId, long requestRevision,
                               long eventSequence, Kind kind, Instant occurredAt) {
    /** 事件身份和序列必须同时有效，客户端才能在重连后检测缺口而不是猜测状态。 */
    public InteractionEvent {
        if (threadId == null || requestId == null || requestRevision < 0 || eventSequence <= 0
                || kind == null || occurredAt == null) throw new IllegalArgumentException("invalid interaction event");
    }

    /** 每次变化只作失效提示，消费者须按服务端序列对账快照。 */
    public enum Kind {
        /** 新问题批次已经持久化。 */ CREATED,
        /** 本地草稿已保存。 */ DRAFT_CHANGED,
        /** 全部问题答案已原子确认。 */ ANSWERED,
        /** 问题被用户显式取消。 */ CANCELLED,
        /** 问题被新的 steering 或请求替代。 */ SUPERSEDED
    }
}
