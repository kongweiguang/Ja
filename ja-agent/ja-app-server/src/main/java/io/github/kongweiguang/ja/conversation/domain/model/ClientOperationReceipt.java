// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import java.util.Objects;

/**
 * 一次客户端提交已与业务事实同事务落库的公开回执；只保留身份、指纹与安全结果，
 * 不持久化请求正文、凭据、Tool 参数或连接代际。
 *
 * @param operationKey 客户端操作标识
 * @param method 方法
 * @param requestFingerprint 请求指纹
 * @param threadId 所属对话的稳定标识
 * @param turnId 本次可恢复执行的稳定标识
 * @param threadRevision 对话修订版本
 * @param queued 是否已进入队列
 * @param approvalId 审批标识
 * @param decision 决策
 */
public record ClientOperationReceipt(String operationKey,
                                     String method,
                                     String requestFingerprint,
                                     String threadId,
                                     String turnId,
                                     long threadRevision,
                                     boolean queued,
                                     String approvalId,
                                     String decision) {
    /**
     * 回读时重新验证闭集，损坏记录不能被当作可安全重试或可重放成功。
     */
    public ClientOperationReceipt {
        if (operationKey == null || !operationKey.matches("op_[0-9a-f]{32}")) {
            throw new IllegalArgumentException("invalid operation key");
        }
        if (requestFingerprint == null || !requestFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid client operation fingerprint");
        }
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(turnId, "turnId");
        if (threadRevision < 0) {
            throw new IllegalArgumentException("invalid operation revision");
        }
        boolean approval = "approval/respond".equals(method);
        if (!approval && !("turn/start".equals(method) || "turn/continue".equals(method)
                           || "turn/reask".equals(method))) {
            throw new IllegalArgumentException("invalid client operation method");
        }
        if (approval) {
            if (approvalId == null || !("approve".equals(decision) || "deny".equals(decision))) {
                throw new IllegalArgumentException("invalid approval operation result");
            }
        } else if (approvalId != null || decision != null) {
            throw new IllegalArgumentException("non-approval operation has approval result");
        }
        if (approval == queued) {
            throw new IllegalArgumentException("invalid operation queued state");
        }
    }

    /**
     * 同 ID 只有完全同一请求才可复用；不同方法或载荷必须向调用方报告冲突。
     */
    public boolean matches(String expectedMethod, String expectedFingerprint) {
        return method.equals(expectedMethod) && requestFingerprint.equals(expectedFingerprint);
    }
}
