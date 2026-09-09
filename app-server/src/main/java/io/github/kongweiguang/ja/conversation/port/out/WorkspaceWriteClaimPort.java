// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Conversation Tool 写工作区前使用的持久 FIFO/fencing 端口。 */
public interface WorkspaceWriteClaimPort {
    /** 追加 WAITING 声明并返回 SQLite 分配的序号与 fencing token。 */
    WriteClaim enqueue(String claimId, String workspaceId, String threadId,
                       String turnId, long processGeneration, Instant requestedAt);

    /** 仅 FIFO 队首且 fencing token 匹配时进入 HELD。 */
    Optional<WriteClaim> tryAcquire(String claimId, long fencingToken, Instant acquiredAt);

    /** 只有当前 HELD owner 能刷新心跳，空值表示所有权已丢失。 */
    Optional<WriteClaim> heartbeat(String claimId, long fencingToken, Instant occurredAt);

    /** 正常 Tool 完成时由当前 HELD owner 提交 RELEASED。 */
    Optional<WriteClaim> release(String claimId, long fencingToken, Instant occurredAt);

    /** 取消、超时和恢复清理将未完成声明永久推进到 ABANDONED。 */
    Optional<WriteClaim> abandon(String claimId, long fencingToken, Instant occurredAt);

    /** 写声明状态与 fencing token 共同阻止旧进程恢复后继续写工作区。 */
    enum State {
        /** 声明已进入 SQLite FIFO，尚未取得写权限。 */
        WAITING,
        /** 当前 fencing owner 持有写权限并必须持续心跳。 */
        HELD,
        /** owner 正常完成并释放，终态重试只能幂等回读。 */
        RELEASED,
        /** 等待超时、取消或恢复清理后废弃，旧 token 永久失效。 */
        ABANDONED
    }

    /** SQLite 权威写声明；时刻组合与状态闭集在端口边界再次校验。 */
    record WriteClaim(long sequence, String claimId, String workspaceId, String threadId,
                      String turnId, long processGeneration, long fencingToken,
                      State state, Instant requestedAt, Instant acquiredAt,
                      Instant heartbeatAt, Instant releasedAt) {
        /** 非法时刻组合失败关闭，避免 Loop 接受损坏 lease 后执行外部写。 */
        public WriteClaim {
            if (sequence < 1 || processGeneration < 1 || fencingToken < 1) {
                throw new IllegalArgumentException("invalid write claim sequence or generation");
            }
            Objects.requireNonNull(claimId, "claimId");
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(requestedAt, "requestedAt");
            if ((state == State.WAITING) != (acquiredAt == null && heartbeatAt == null && releasedAt == null)
                    || (state == State.HELD) != (acquiredAt != null && heartbeatAt != null && releasedAt == null)
                    || ((state == State.RELEASED || state == State.ABANDONED) != (releasedAt != null))) {
                throw new IllegalArgumentException("invalid write claim state facts");
            }
        }
    }
}
