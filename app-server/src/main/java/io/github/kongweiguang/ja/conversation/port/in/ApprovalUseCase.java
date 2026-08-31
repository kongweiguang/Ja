// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.in;

import io.github.kongweiguang.ja.conversation.domain.approval.ApprovalDecision;

import java.time.Instant;

/**
 * 接收外部审批决定的窄入站端口，不暴露应用层待审批队列及取消协调细节。
 */
@FunctionalInterface
public interface ApprovalUseCase {
    /**
     * 仅解析仍处于等待状态的审批；迟到或重复响应返回 {@code false}，由入站适配器映射为冲突错误。
     */
    boolean resolve(String approvalId, ApprovalDecision decision, Instant resolvedAt);
}
