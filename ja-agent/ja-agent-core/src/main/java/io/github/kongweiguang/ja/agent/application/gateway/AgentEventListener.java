// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;

import java.util.Objects;

/**
 * 只读观察已提交的公开执行事件；监听器用于集成与诊断，不参与控制循环的结果决策。
 */
public interface AgentEventListener {
    /**
     * 返回稳定的订阅身份，使注册阶段即可发现重复监听器并保持通知配置明确。
     */
    String id();

    /**
     * 仅接收 Store 已编号并提交的事件；回调失败可被报告，但不得改写已经提交的执行事实。
     */
    void onEvent(AgentEvent event);

    /**
     * 缺口出现时必须从持久事件游标补读；默认拒绝静默跳过，dispatcher 会记录未处理的重同步要求。
     */
    default void onReplayRequired(ReplayRequired replay) {
        throw new UnsupportedOperationException("listener has no durable replay handler");
    }

    /**
     * 告知监听器需补读的持久事件区间，区间左端独占、右端包含。
     *
     * @param identity 包含租户、对话、执行与用户的完整身份
     * @param afterSequence 从该序号之后开始读取
     * @param throughSequence 当前快照覆盖到的顺序号
     * @param reasonCode 原因代码
     */
    record ReplayRequired(AgentIdentity identity,
                          long afterSequence,
                          long throughSequence,
                          String reasonCode) {
        /**
         * 校验重放水位单调且原因是稳定诊断码，避免监听器误把缺口当成普通通知。
         */
        public ReplayRequired {
            Objects.requireNonNull(identity, "identity");
            if (afterSequence < 0 || throughSequence <= afterSequence) {
                throw new IllegalArgumentException("invalid replay event range");
            }
            if (reasonCode == null || !reasonCode.matches("[a-z][a-z0-9_]{0,63}")) {
                throw new IllegalArgumentException("invalid replay reason code");
            }
        }
    }
}
