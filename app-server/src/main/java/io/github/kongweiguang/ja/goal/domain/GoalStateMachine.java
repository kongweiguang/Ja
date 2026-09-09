// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.domain;

import io.github.kongweiguang.ja.goal.domain.GoalModels.GoalPhase;
import io.github.kongweiguang.ja.goal.domain.GoalModels.GoalStatus;

import java.util.EnumSet;
import java.util.Set;

/** Goal 生命周期的唯一转换策略；错误和评估未通过都保持可恢复。 */
public final class GoalStateMachine {
    /** 目标状态必须和 phase 投影一致，避免持久层保存 UI 无法解释的组合。 */
    public static void requireCombination(GoalStatus status, GoalPhase phase) {
        Set<GoalPhase> allowed = switch (status) {
            case ACTIVE -> EnumSet.of(GoalPhase.WORKING, GoalPhase.WAITING_APPROVAL,
                    GoalPhase.WAITING_INPUT, GoalPhase.VERIFYING);
            case PAUSED -> EnumSet.of(GoalPhase.PAUSED, GoalPhase.NEEDS_ATTENTION);
            case ACHIEVED -> EnumSet.of(GoalPhase.ACHIEVED);
            case STOPPED -> EnumSet.of(GoalPhase.STOPPED);
        };
        if (!allowed.contains(phase)) throw new IllegalStateException("invalid Goal status and phase");
    }

    /** 用户暂停可从任一非终态进入；丢弃换版草稿也可回到旧批准暂停态。 */
    public static boolean mayTransition(GoalStatus from, GoalStatus to) {
        if (from == to) return true;
        return switch (from) {
            case ACTIVE -> to == GoalStatus.PAUSED || to == GoalStatus.ACHIEVED || to == GoalStatus.STOPPED;
            case PAUSED -> to == GoalStatus.ACTIVE || to == GoalStatus.STOPPED;
            case ACHIEVED, STOPPED -> false;
        };
    }

    /** 同签名三次且没有新证据才触发熔断；新证据由调用方清零计数。 */
    public static boolean shouldPauseForRepeatedFailure(String previousSignature, int previousCount,
                                                        String currentSignature, boolean newEvidence) {
        if (newEvidence || currentSignature == null || !currentSignature.equals(previousSignature)) return false;
        return previousCount + 1 >= 3;
    }

    /** 三个连续 Turn 无状态或证据进展进入 attention，不把预算作为终止条件。 */
    public static boolean shouldPauseForNoProgress(int previousTurns, boolean progressed) {
        return !progressed && previousTurns + 1 >= 3;
    }

    /** 状态机不进入容器。 */
    private GoalStateMachine() { }
}
