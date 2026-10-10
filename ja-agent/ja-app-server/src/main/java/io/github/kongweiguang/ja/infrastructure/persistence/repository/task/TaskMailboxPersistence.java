// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskRecords;

import java.time.Instant;
import java.util.Objects;

/**
 * @author kongweiguang
 * 仅保留 Follow-up 终态释放；MESSAGE 的绑定与消费由 AgentStore participant 同事务完成。
 */
public final class TaskMailboxPersistence {
    /**
     * 不持有事务状态，仅提供宿主 Task 终态扩展。
     */
    private TaskMailboxPersistence() {
    }

    /**
     * 终态 winner 只释放尚未提交 USER facts 的 BOUND 行；CONSUMED 行保持不可逆，避免已进入模型
     * 历史的消息在后续 Turn 再次投递。
     */
    public static int releaseBoundForTurn(PersistenceMappers mapper, String turnId, Instant occurredAt) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        return mapper.tasks().releaseBoundMailboxForTurn(
                new TaskRecords.MailboxRelease(turnId, occurredAt.toString()));
    }
}
