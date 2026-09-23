// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

import java.util.Objects;

/**
 * 表示按全局 Turn 身份执行取消或审批关联时所需的最小持久化投影。
 */
public record TurnSummary(String threadId, String turnId, String status, long threadRevision,
                          long turnMutationVersion, boolean cancellationRequested) {
    /**
     * 不暴露存储行或内部版本，仅保留跨端口关联所需字段。
     */
    public TurnSummary {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(status, "status");
        if (threadRevision < 0 || turnMutationVersion < 0) {
            throw new IllegalArgumentException("invalid turn revisions");
        }
    }

    /** 非持久化调用方的窄构造；真实恢复必须携带 SQLite Turn mutation 水位。 */
    public TurnSummary(String threadId, String turnId, String status, long threadRevision,
                       boolean cancellationRequested) {
        this(threadId, turnId, status, threadRevision, 0, cancellationRequested);
    }
}
