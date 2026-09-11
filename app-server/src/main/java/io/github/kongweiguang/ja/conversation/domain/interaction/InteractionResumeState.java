// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.domain.interaction;

import java.util.Optional;

/** Interaction 与关联 Turn 的恢复投影；它只描述事实，不提供执行授权。 */
public enum InteractionResumeState {
    /** 当前没有需要展示的交互请求。 */ NONE,
    /** 用户尚未提交答案，不得自动采用默认值。 */ WAITING_FOR_ANSWER,
    /** 答案已保存，原 Turn 仍待安全恢复。 */ WAITING_TO_RESUME,
    /** 原 Turn 已恢复准入或执行。 */ RESUMING,
    /** 原 Turn 已结算，问答仅供历史查阅。 */ SETTLED,
    /** 请求已取消或被新需求替代。 */ CLOSED;

    /** 从请求状态与同事务读取的 Turn 状态推导，缺失 Turn 时保持保守的 settled 投影。 */
    public static InteractionResumeState from(Optional<InteractionRequest> request, String turnState) {
        if (request.isEmpty()) return NONE;
        InteractionRequest value = request.orElseThrow();
        return switch (value.status()) {
            case PENDING -> WAITING_FOR_ANSWER;
            case ANSWERED -> "SUSPENDED".equals(turnState) ? WAITING_TO_RESUME
                    : turnState != null && !isTerminal(turnState) ? RESUMING : SETTLED;
            case CANCELLED, SUPERSEDED -> CLOSED;
        };
    }

    /** 未知 Turn 状态按非终态处理，避免把恢复中的工作误报为已完成。 */
    private static boolean isTerminal(String value) {
        return "COMPLETED".equals(value) || "FAILED".equals(value) || "CANCELLED".equals(value);
    }
}
