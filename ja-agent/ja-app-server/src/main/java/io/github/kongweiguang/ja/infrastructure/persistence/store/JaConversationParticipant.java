// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.store;

import io.github.kongweiguang.ja.infrastructure.persistence.transaction.JdbcTransactionResources;
import io.github.kongweiguang.ja.agent.application.gateway.store.ConversationTransactionParticipant;
import io.github.kongweiguang.ja.agent.application.gateway.store.ConversationTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * 在 Store 所有的同一事务中将 Ja Thread revision 与通用 conversation revision 保持一致。
 */
public final class JaConversationParticipant implements ConversationTransactionParticipant {
    private static final String TENANT_ID = "ja.local";

    /**
     * Thread 的 Workspace 与偏好仍由 Ja 持有，只有 revision 镜像 Store 的 CAS 结果。
     */
    public JaConversationParticipant() {
    }

    /**
     * 新会话行必须由前置业务参与者创建，避免此适配器猜测 Workspace 或默认偏好。
     */
    private static void verifyCreatedThread(ConversationTransactionContext transaction) throws SQLException {
        if (transaction.committedRevision() != 0) {
            throw new IllegalArgumentException("new conversation revision must start at zero");
        }
        try (PreparedStatement statement = JdbcTransactionResources.connection(transaction).prepareStatement(
                "SELECT revision FROM ja_conversation_thread WHERE thread_id = ?")) {
            statement.setString(1, transaction.conversation().threadId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getLong(1) != 0 || rows.next()) {
                    throw new SQLException("new AgentStore conversation must have one Ja thread at revision zero");
                }
            }
        }
    }

    /**
     * Core 回调保持 JDBC 中立；SQL 故障在此适配边界包装，其余失败原样交由 Store 回滚。
     */
    @Override
    public void apply(ConversationTransactionContext transaction) {
        try {
            applyProjection(transaction);
        } catch (SQLException failure) {
            throw new AgentStore.StoreParticipantException("Ja conversation participant failed", failure);
        }
    }

    /**
     * 使用 Store 提供的连接执行 revision CAS；该回调不拥有事务提交权，也不能独立更新版本。
     */
    private static void applyProjection(ConversationTransactionContext transaction) throws SQLException {
        Objects.requireNonNull(transaction, "transaction");
        if (!TENANT_ID.equals(transaction.conversation().tenantId())) {
            throw new IllegalArgumentException("Ja conversations must use the ja.local tenant");
        }
        if (transaction.expectedRevision() == -1) {
            verifyCreatedThread(transaction);
            return;
        }
        if (transaction.committedRevision() != transaction.expectedRevision() + 1) {
            throw new IllegalArgumentException("conversation revision must advance by one");
        }
        try (PreparedStatement statement = JdbcTransactionResources.connection(transaction).prepareStatement(
                "UPDATE ja_conversation_thread SET revision = ?, updated_at_ms = ? WHERE thread_id = ? AND revision = ?")) {
            statement.setLong(1, transaction.committedRevision());
            statement.setLong(2, transaction.occurredAt().toEpochMilli());
            statement.setString(3, transaction.conversation().threadId());
            statement.setLong(4, transaction.expectedRevision());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Ja thread revision no longer matches the AgentStore revision");
            }
        }
    }
}
