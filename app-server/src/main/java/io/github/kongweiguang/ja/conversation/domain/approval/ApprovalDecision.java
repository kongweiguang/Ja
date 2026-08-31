// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.approval;

/**
 * 用户对单次 Tool 审批请求可作出的封闭决策。
 */
public enum ApprovalDecision {
    /**
     * 仅允许当前调用执行一次，不产生后续授权。
     */
    APPROVE,
    /**
     * 拒绝当前调用且不建立任何授权。
     */
    DENY
}
