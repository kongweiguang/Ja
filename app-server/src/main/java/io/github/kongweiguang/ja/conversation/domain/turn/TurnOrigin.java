// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.turn;

/** Turn 的权威来源；内部续跑不得伪装成用户输入。 */
public enum TurnOrigin {
    /** 用户通过公开 Composer 发起。 */ USER,
    /** 用户显式继续同一未答问题；不创建第二条可见 USER 消息。 */ USER_CONTINUATION,
    /** Task 创建者提供了真实首条用户输入。 */ CHILD_TASK,
    /** Goal coordinator 通过隐藏上下文续跑。 */ GOAL_CONTINUATION,
    /** 用户显式执行已批准 Plan 后启动一次隐藏 Turn。 */ PLAN_EXECUTION;

    /** Goal/Plan 与用户显式继续均复用既有 USER 历史，不创建新的 USER message。 */
    public boolean internal() {
        return this == USER_CONTINUATION || this == GOAL_CONTINUATION || this == PLAN_EXECUTION;
    }
}
