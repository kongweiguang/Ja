// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

/**
 * 首次接纳后供 Ja Task/mailbox 投影使用的通用存储回执。
 *
 * @param threadId 所属对话的稳定标识
 * @param turnId 本次可恢复执行的稳定标识
 * @param threadRevision 对话修订版本
 * @param turnMutationVersion 当前执行的状态变更版本
 * @param provisionalTitle 临时标题
 */
public record JaAdmissionReceipt(String threadId,
                                 String turnId,
                                 long threadRevision,
                                 long turnMutationVersion,
                                 String provisionalTitle) {
    /**
     * 回执保留 Store 确认的两个版本，Task 不得从预期值自行推算。
     */
    public JaAdmissionReceipt {
        threadId = prefixed(threadId, "thr_", "threadId");
        turnId = prefixed(turnId, "turn_", "turnId");
        if (threadRevision < 0 || turnMutationVersion < 0) {
            throw new IllegalArgumentException("invalid admission receipt versions");
        }
        if (provisionalTitle != null) {
            provisionalTitle = ContractChecks.text(
                provisionalTitle, "provisionalTitle", 4096, false);
        }
    }

    /**
     * ID 必须保留 Ja 的稳定前缀，失败时不能构造部分回执。
     */
    private static String prefixed(String value, String prefix, String field) {
        String checked = ContractChecks.identifier(value, field);
        if (!checked.startsWith(prefix)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return checked;
    }

    /**
     * 根据回执是否明确授予标题所有权决定能否发布首轮自动标题。
     */
    public boolean createdProvisionalTitle() {
        return provisionalTitle != null;
    }
}
