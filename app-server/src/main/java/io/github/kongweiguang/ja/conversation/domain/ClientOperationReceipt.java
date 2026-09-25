// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

import java.util.Objects;

/**
 * 一次客户端提交已与业务事实同事务落库的公开回执；只保留身份、指纹与安全结果，
 * 不持久化请求正文、凭据、Tool 参数或连接代际。
 */
public record ClientOperationReceipt(String clientOperationId, String method, String requestFingerprint,
                                     String threadId, String turnId, long threadRevision, boolean queued,
                                     String approvalId, String decision) {
    /** 回读时重新验证闭集，损坏记录不能被当作可安全重试或可重放成功。 */
    public ClientOperationReceipt {
        if (clientOperationId == null || !clientOperationId.matches("op_[0-9a-f]{32}")) {
            throw new IllegalArgumentException("invalid client operation id");
        }
        if (requestFingerprint == null || !requestFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid client operation fingerprint");
        }
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(turnId, "turnId");
        if (threadRevision < 0) throw new IllegalArgumentException("invalid operation revision");
        boolean approval = "approval/respond".equals(method);
        if (!approval && !("turn/start".equals(method) || "turn/continue".equals(method)
                || "turn/reask".equals(method))) {
            throw new IllegalArgumentException("invalid client operation method");
        }
        if (approval != (approvalId != null && ("approve".equals(decision) || "deny".equals(decision)))) {
            throw new IllegalArgumentException("invalid approval operation result");
        }
        if (approval == queued) throw new IllegalArgumentException("invalid operation queued state");
    }

    /** 同 ID 只有完全同一请求才可复用；不同方法或载荷必须向调用方报告冲突。 */
    public boolean matches(String expectedMethod, String expectedFingerprint) {
        return method.equals(expectedMethod) && requestFingerprint.equals(expectedFingerprint);
    }
}
