// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.domain.interaction;

/** 交互请求的持久生命周期；回答、取消和替代互斥且只能发生一次。 */
public enum InteractionStatus {
    /** 等待全部问题答案。 */ PENDING,
    /** 答案已原子提交并等待或完成恢复。 */ ANSWERED,
    /** 用户主动取消，迟到答案不可生效。 */ CANCELLED,
    /** 被新请求替代，旧请求不可复活。 */ SUPERSEDED
}
