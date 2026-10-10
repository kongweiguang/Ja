// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.acl;

import io.github.kongweiguang.ja.conversation.application.gateway.ConversationWorkspaceWriteClaimPort;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceWriteClaimPort;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 将 Workspace 持久 claim owner 适配为 Conversation 自有消费端口，避免 Loop 依赖仓储实现。
 */
public final class WorkspaceWriteClaimAcl implements ConversationWorkspaceWriteClaimPort {
    private final WorkspaceWriteClaimPort delegate;

    /**
     * ACL 只转发已经校验的 claim 合同，不复制状态机或维护第二份 lease 状态。
     */
    public WorkspaceWriteClaimAcl(WorkspaceWriteClaimPort delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** 将排队声明交给 Workspace owner。 */
    @Override
    public WriteClaim enqueue(String claimId, String workspaceId, String threadId,
                              String turnId, long processGeneration, Instant requestedAt) {
        return delegate.enqueue(claimId, workspaceId, threadId, turnId, processGeneration, requestedAt);
    }

    /** 只允许 owner 根据 FIFO 与 fencing token 尝试取得声明。 */
    @Override
    public Optional<WriteClaim> tryAcquire(String claimId, long fencingToken, Instant acquiredAt) {
        return delegate.tryAcquire(claimId, fencingToken, acquiredAt);
    }

    /** 心跳结果为空时由 Conversation lease coordinator 立即撤销写能力。 */
    @Override
    public Optional<WriteClaim> heartbeat(String claimId, long fencingToken, Instant occurredAt) {
        return delegate.heartbeat(claimId, fencingToken, occurredAt);
    }

    /** 正常完成只释放当前 fencing owner。 */
    @Override
    public Optional<WriteClaim> release(String claimId, long fencingToken, Instant occurredAt) {
        return delegate.release(claimId, fencingToken, occurredAt);
    }

    /** 取消、超时和恢复统一推进为不可重用的 ABANDONED 状态。 */
    @Override
    public Optional<WriteClaim> abandon(String claimId, long fencingToken, Instant occurredAt) {
        return delegate.abandon(claimId, fencingToken, occurredAt);
    }
}
