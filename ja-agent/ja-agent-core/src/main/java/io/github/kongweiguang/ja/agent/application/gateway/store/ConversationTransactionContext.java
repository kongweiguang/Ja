// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway.store;

import java.time.Instant;
import java.util.Objects;

/**
 * 会话创建或 revision CAS 阶段的短生命周期事务视图；资源所有权属于 AgentStore。
 *
 * @param resources 当前事务的基础设施资源句柄
 * @param conversation 会话身份及固定 owner
 * @param expectedRevision CAS 期望版本
 * @param committedRevision 提交后的版本
 * @param occurredAt 事实发生时间
 */
public record ConversationTransactionContext(TransactionResource resources,
                                             AgentStore.ConversationKey conversation,
                                             long expectedRevision,
                                             long committedRevision,
                                             Instant occurredAt) {
    /**
     * 固定会话版本与 owner，避免参与者绕过 Store 的 owner 和 CAS 约束。
     */
    public ConversationTransactionContext {
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(conversation, "conversation");
        if (expectedRevision < -1 || committedRevision < 0
                || (expectedRevision >= 0 && committedRevision != expectedRevision + 1)
                || (expectedRevision == -1 && committedRevision != 0)) {
            throw new IllegalArgumentException("invalid conversation transaction versions");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
