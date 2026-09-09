// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.turn;

/** Turn 的权威来源；内部续跑不得伪装成用户输入。 */
public enum TurnOrigin {
    /** 用户通过公开 Composer 发起。 */ USER,
    /** Task 创建者提供了真实首条用户输入。 */ CHILD_TASK,
    /** Goal coordinator 通过隐藏上下文续跑。 */ GOAL_CONTINUATION,
    /** 用户显式执行已批准 Plan 后启动一次隐藏 Turn。 */ PLAN_EXECUTION;

    /** 只有 Goal/Plan 内部来源允许缺少 UserContent 和 USER message。 */
    public boolean internal() {
        return this == GOAL_CONTINUATION || this == PLAN_EXECUTION;
    }
}
