// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskRecords;

import java.time.Instant;
import java.util.Objects;

/** Conversation 提交 USER facts 时复用的 Task Mailbox 同事务扩展。 */
public final class TaskMailboxPersistence {
    /**
     * 只把当前 Turn 已持有的 BOUND 行推进到 CONSUMED；调用方必须先在同一 SqlSession 写入
     * 由这些消息构造的 USER facts，事务失败时两侧一起回滚，避免消息丢失或重复注入。
     */
    public static int consumeBoundForTurn(PersistenceMappers mapper, String turnId, Instant occurredAt) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        return mapper.tasks().consumeBoundMailboxForTurn(
                new TaskRecords.MailboxConsume(turnId, occurredAt.toString()));
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

    /** 纯事务扩展禁止实例化。 */
    private TaskMailboxPersistence() { }
}
