// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.store;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.conversation.domain.model.JaMessageIds;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;

/**
 * Ja Turn admission 的最小 SQL 入口，确保业务 participant 只在 Store 已验证的事务中建原始行。
 */
public final class JaTurnAdmissionRows {
    private static final String TENANT_ID = "ja.local";

    /**
     * 静态 SQL 辅助类没有实例状态，私有构造避免创建无效对象。
     */
    private JaTurnAdmissionRows() {
    }

    /**
     * 在 AgentStore admission participant 中插入尚未投影版本的 QUEUED Turn；mandatory Ja participant
     * 随后会在同一事务把状态与 mutationVersion 镜像到 core 准入回执。
     */
    public static void insert(Connection connection, AgentStoreTransactionContext transaction,
                              String sourceMessageId, String parentTurnId, String rootTurnId) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(transaction, "transaction");
        AgentStore.ExecutionKey execution = transaction.execution();
        if (transaction.kind() != AgentStore.TransactionKind.ADMISSION
            || !TENANT_ID.equals(execution.conversation().tenantId())) {
            throw new IllegalArgumentException("Ja turn admission identity is invalid");
        }
        insert(connection, execution.conversation().threadId(), execution.turnId(),
                sourceMessageId == null ? null : JaMessageIds.toItemId(sourceMessageId), parentTurnId, rootTurnId,
                transaction.occurredAt());
    }

    /**
     * 使用既有业务父子关系和 Store 分配时间创建版本 0 的 QUEUED 行；线程、执行身份不从路径或工作区推断。
     */
    private static void insert(Connection connection, String threadId, String turnId, String sourceMessageId,
                               String parentTurnId, String rootTurnId, Instant occurredAt) throws SQLException {
        String sql = """
            INSERT INTO ja_conversation_turn(turn_id,thread_id,state,mutation_version,requested_at_ms,updated_at_ms,\
            source_message_id,parent_turn_id,root_turn_id) VALUES(?,?,'QUEUED',0,?,?,?,?,?)\
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, turnId);
            statement.setString(2, threadId);
            statement.setLong(3, occurredAt.toEpochMilli());
            statement.setLong(4, occurredAt.toEpochMilli());
            statement.setString(5, sourceMessageId);
            statement.setString(6, parentTurnId);
            statement.setString(7, rootTurnId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Ja turn admission row was not created");
            }
        }
    }
}
