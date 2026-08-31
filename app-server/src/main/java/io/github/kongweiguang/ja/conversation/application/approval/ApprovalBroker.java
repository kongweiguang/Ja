// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.approval;

import io.github.kongweiguang.ja.conversation.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.conversation.domain.permission.PermissionRequest;
import io.github.kongweiguang.ja.conversation.port.in.ApprovalUseCase;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * 协调进程内待审批状态、超时与取消，不代表任何外部系统 SPI。
 */
public interface ApprovalBroker extends ApprovalUseCase {
    /**
     * 登记唯一待审批项并返回其最终决定；取消和超时必须收敛为拒绝。
     */
    CompletionStage<Resolution> request(ApprovalRequest request, CancellationToken cancellationToken);

    /**
     * 关闭指定 Turn 的全部待审批项，防止取消后仍能恢复 Tool 执行。
     */
    void cancelTurn(String threadId, String turnId, String reason);

    /**
     * 与单个 Tool 权限请求绑定的有界审批登记。
     */
    record ApprovalRequest(
            String approvalId,
            PermissionRequest permission,
            String reason,
            Instant expiresAt) {
        /**
         * 冻结审批 ID、权限快照和截止时刻，使异步响应只能关联到最初登记的请求。
         */
        public ApprovalRequest {
            approvalId = ContractChecks.identifier(approvalId, "approvalId");
            Objects.requireNonNull(permission, "permission");
            reason = ContractChecks.text(reason, "reason", 16_384, false);
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /**
     * 已完成审批的决定与权威完成时刻。
     */
    record Resolution(String approvalId, ApprovalDecision response, Instant resolvedAt) {
        /**
         * 校验完成回执的关联 ID 与权威时间，避免未绑定的决定进入 Tool 执行链。
         */
        public Resolution {
            approvalId = ContractChecks.identifier(approvalId, "approvalId");
            Objects.requireNonNull(response, "response");
            Objects.requireNonNull(resolvedAt, "resolvedAt");
        }
    }
}
