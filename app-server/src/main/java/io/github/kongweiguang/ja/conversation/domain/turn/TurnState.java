// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.turn;

import java.util.Objects;

/**
 * Turn 对外可观察且必须单调推进的七态闭集。
 */
public enum TurnState {
    /**
     * 已完成准入并等待所属 Thread FIFO 执行。
     */
    QUEUED,
    /**
     * 正在执行 Provider 或 Tool 工作。
     */
    RUNNING,
    /**
     * 已持久化审批请求并等待用户决定。
     */
    WAITING_APPROVAL,
    /**
     * 进程中断后保留了可校验执行游标，只有用户显式恢复才可再次产生外部作用。
     */
    SUSPENDED,
    /**
     * 已提交最终 assistant 消息的成功终态。
     */
    COMPLETED,
    /**
     * 已提交稳定错误类别的失败终态。
     */
    FAILED,
    /**
     * 已完成取消清理并提交取消终态。
     */
    CANCELLED;

    /**
     * 终态集合与 SQLite CHECK 保持一致，调用方不得自行推导其它终态。
     */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /**
     * 只允许领域定义的单调边，拒绝终态重写和无事实的同态提交。
     */
    public boolean canTransitionTo(TurnState target) {
        Objects.requireNonNull(target, "target");
        if (this == target || terminal()) return false;
        return switch (this) {
            case QUEUED, WAITING_APPROVAL -> target == RUNNING || target == SUSPENDED || target.terminal();
            case RUNNING -> target == WAITING_APPROVAL || target == SUSPENDED || target.terminal();
            case SUSPENDED -> target == QUEUED || target.terminal();
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }
}
