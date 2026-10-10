// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.api.engine;

/**
 * 已持久化准入的不可变水位；重放返回原事实，不能用后续执行状态重建。
 *
 * @param threadRevision 对话修订版本
 * @param mutationVersion 状态变更版本
 * @param firstEventSequence 准入事件区间的首序号
 * @param lastEventSequence 准入事件区间的末序号
 */
public record AgentAdmissionReceipt(long threadRevision,
                                    long mutationVersion,
                                    long firstEventSequence,
                                    long lastEventSequence) {
    /**
     * 与 Store 回执约束保持一致，拒绝不存在或倒置的持久水位。
     */
    public AgentAdmissionReceipt {
        if (threadRevision < 1 || mutationVersion < 1 || firstEventSequence < 0
            || lastEventSequence < firstEventSequence) {
            throw new IllegalArgumentException("invalid admission receipt");
        }
    }
}
