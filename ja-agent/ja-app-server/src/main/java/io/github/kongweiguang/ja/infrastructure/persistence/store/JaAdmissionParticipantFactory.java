// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.store;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.JdbcTransactionResources;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.conversation.domain.model.JaMessageIds;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionParticipantFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Ja 执行准入行适配器；它只在 AgentStore admission 的同一 JDBC 事务内创建投影身份。
 */
public final class JaAdmissionParticipantFactory implements AdmissionParticipantFactory {
    private static final String TENANT_ID = "ja.local";

    /**
     * 先校验事实与 Store 分配的完整键/版本，再写 Thread seed、Turn 与可选 RPC 回执，不能留下部分准入。
     * connection 借自 AgentStore 当前事务；只能在回调内同步使用，提交、回滚和关闭均由 AgentStore 负责。
     */
    @SuppressWarnings("PMD.CloseResource")
    private static void apply(AgentStoreTransactionContext transaction, AdmissionFacts facts) throws SQLException {
        Objects.requireNonNull(transaction, "transaction");
        if (transaction.kind() != AgentStore.TransactionKind.ADMISSION) {
            throw new SQLException("Ja admission participant ran outside admission");
        }
        AgentStore.ExecutionKey execution = transaction.execution();
        AgentIdentity identity = facts.identity();
        if (!TENANT_ID.equals(identity.tenantId())
            || !"user_local".equals(identity.userId())
            || !identity.tenantId().equals(execution.conversation().tenantId())
            || !identity.threadId().equals(execution.conversation().threadId())
            || !identity.turnId().equals(execution.turnId())
            || facts.expectedThreadRevision() != transaction.expectedThreadRevision()
            || !facts.acceptedAt().equals(transaction.occurredAt())) {
            throw new SQLException("Ja admission identity or revision does not match AgentStore");
        }

        Connection connection = JdbcTransactionResources.connection(transaction);
        if (facts.newThread().isPresent()) {
            if (facts.expectedThreadRevision() != 0 || transaction.expectedThreadRevision() != 0) {
                throw new SQLException("new Ja Thread admission must start at revision zero");
            }
            insertThread(connection, facts, facts.newThread().orElseThrow());
        } else {
            verifyExistingThread(connection, facts);
        }
        if ("turn/reask".equals(facts.method())) {
            cutCurrentPathForReask(connection, facts);
        }
        JaTurnAdmissionRows.insert(connection, transaction, facts.sourceMessageId(),
                facts.parentTurnId(), facts.rootTurnId());
        insertClientOperation(connection, transaction, facts);
    }

    /**
     * 重答只接受当前路径末尾失败问题，并在原 Thread revision CAS 下原子剪切其后尝试；缺少状态时失败关闭，
     * 避免不完整持久事实被解释为可重答的失败尝试。
     */
    private static void cutCurrentPathForReask(Connection connection, AdmissionFacts facts) throws SQLException {
        String sourceMessageId = JaMessageIds.toItemId(
                Objects.requireNonNull(facts.sourceMessageId(), "reask sourceMessageId"));
        String sql = """
                SELECT owner.turn_sequence,
                    (SELECT latest_attempt.state FROM ja_conversation_turn latest_attempt
                     WHERE latest_attempt.thread_id=source.thread_id AND latest_attempt.is_current_path=1
                       AND (latest_attempt.turn_id=owner.turn_id
                         OR latest_attempt.source_message_id=source.message_id)
                     ORDER BY latest_attempt.turn_sequence DESC LIMIT 1)
                FROM ja_conversation_message source
                JOIN ja_conversation_timeline_entry source_item ON source_item.item_id=source.message_id
                  AND source_item.thread_id=source.thread_id AND source_item.turn_id=source.turn_id
                  AND source_item.kind='USER_INPUT' AND source_item.is_current_path=1
                JOIN ja_conversation_turn owner ON owner.turn_id=source.turn_id AND owner.thread_id=source.thread_id
                  AND owner.is_current_path=1
                WHERE source.thread_id=? AND source.message_id=? AND source.role='USER'
                  AND source.message_id=(
                    SELECT latest.item_id FROM ja_conversation_timeline_entry latest
                    JOIN ja_conversation_message latest_message ON latest_message.message_id=latest.message_id
                      AND latest_message.thread_id=latest.thread_id AND latest_message.turn_id=latest.turn_id
                    JOIN ja_conversation_turn latest_turn ON latest_turn.turn_id=latest.turn_id
                      AND latest_turn.thread_id=latest.thread_id AND latest_turn.is_current_path=1
                    WHERE latest.thread_id=source.thread_id AND latest.kind='USER_INPUT'
                      AND latest.is_current_path=1
                      AND latest_message.role='USER'
                    ORDER BY latest_message.ordinal DESC LIMIT 1)
                  AND NOT EXISTS(
                    SELECT 1 FROM ja_conversation_timeline_entry earlier
                    JOIN ja_conversation_message earlier_message ON earlier_message.message_id=earlier.message_id
                    WHERE earlier.thread_id=source.thread_id AND earlier.turn_id=owner.turn_id
                      AND earlier.kind='USER_INPUT' AND earlier.is_current_path=1
                      AND earlier_message.role='USER'
                      AND earlier_message.ordinal<source.ordinal)
                  AND NOT EXISTS(
                    SELECT 1 FROM ja_conversation_turn successful
                    WHERE successful.thread_id=source.thread_id AND successful.is_current_path=1
                      AND successful.state='COMPLETED'
                      AND (successful.turn_id=owner.turn_id
                        OR successful.source_message_id=source.message_id))
                """;
        long ownerSequence;
        String latestState;
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, facts.identity().threadId());
            query.setString(2, sourceMessageId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Ja reask source is not the current path question");
                }
                ownerSequence = rows.getLong(1);
                latestState = rows.getString(2);
                if (rows.next()) {
                    throw new SQLException("Ja reask source identity is ambiguous");
                }
            }
        }
        if (latestState == null || !java.util.Set.of("FAILED", "CANCELLED").contains(latestState)) {
            throw new SQLException("Ja reask source has no failed current-path attempt");
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE ja_conversation_turn SET is_current_path=0 WHERE thread_id=? AND is_current_path=1 AND turn_sequence>=?")) {
            update.setString(1, facts.identity().threadId());
            update.setLong(2, ownerSequence);
            if (update.executeUpdate() < 1) {
                throw new SQLException("Ja reask path changed during admission");
            }
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE ja_conversation_timeline_entry SET is_current_path=0 WHERE thread_id=? "
                + "AND turn_id IN (SELECT turn_id FROM ja_conversation_turn WHERE thread_id=? AND is_current_path=0 "
                + "AND turn_sequence>=?) AND is_current_path=1")) {
            update.setString(1, facts.identity().threadId());
            update.setString(2, facts.identity().threadId());
            update.setLong(3, ownerSequence);
            update.executeUpdate();
        }
    }

    /**
     * 新 Child Thread 的 Workspace 与明确 preferences 一并写入；AgentStore 随后同事务镜像 revision=1。
     */
    private static void insertThread(Connection connection, AdmissionFacts facts,
                                     AdmissionFacts.ThreadInitialization seed) throws SQLException {
        if (!seed.workspaceId().equals(facts.workspaceId())) {
            throw new SQLException("Ja Thread workspace differs from admission workspace");
        }
        ThreadPreferences preferences = seed.preferences();
        String sql = """
            INSERT INTO ja_conversation_thread(thread_id,owner_user_id,workspace_id,title,provider_id,model_id,reasoning_level,\
            access_mode,collaboration_mode,title_source,revision,created_at_ms,updated_at_ms) \
            VALUES(?,'user_local',?,?,?,?,?,?,?,?,0,?,?)\
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, facts.identity().threadId());
            statement.setString(2, seed.workspaceId());
            statement.setString(3, seed.title());
            statement.setString(4, preferences.providerId());
            statement.setString(5, preferences.modelId());
            nullable(statement, 6, preferences.reasoningLevel());
            statement.setString(7, preferences.accessMode().name());
            statement.setString(8, preferences.collaborationMode().name());
            statement.setString(9, preferences.titleSource().name());
            statement.setLong(10, seed.createdAt().toEpochMilli());
            statement.setLong(11, seed.createdAt().toEpochMilli());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Ja Thread was not created");
            }
        }
    }

    /**
     * 已存在 Thread 必须与本次 CAS anchor 和 Workspace 完全一致；参与者不自行推进 revision。
     */
    private static void verifyExistingThread(Connection connection, AdmissionFacts facts) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT workspace_id,revision FROM ja_conversation_thread WHERE thread_id=? AND owner_user_id='user_local'")) {
            statement.setString(1, facts.identity().threadId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !facts.workspaceId().equals(rows.getString(1))
                    || facts.expectedThreadRevision() != rows.getLong(2) || rows.next()) {
                    throw new SQLException("Ja Thread admission revision or owner changed");
                }
            }
        }
    }

    /**
     * 只镜像旧 schema 明确支持的外部 Turn 操作；其他业务动作由其自身幂等事实负责，不伪造 RPC receipt。
     */
    private static void insertClientOperation(Connection connection, AgentStoreTransactionContext transaction,
                                              AdmissionFacts facts) throws SQLException {
        if (facts.operationKey() == null) {
            return;
        }
        if (!java.util.Set.of("turn/start", "turn/continue", "turn/reask").contains(facts.method())) {
            throw new SQLException("Ja client operation method has no projection schema");
        }
        String sql = """
            INSERT INTO ja_client_operation(operation_key,method,request_fingerprint,thread_id,turn_id,\
            thread_revision,is_queued,approval_id,decision,created_at_ms) VALUES(?,?,?,?,?,?,1,NULL,NULL,?)\
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, facts.operationKey());
            statement.setString(2, facts.method());
            statement.setString(3, facts.requestFingerprint());
            statement.setString(4, facts.identity().threadId());
            statement.setString(5, facts.identity().turnId());
            statement.setLong(6, transaction.committedConversationRevision());
            statement.setLong(7, facts.acceptedAt().toEpochMilli());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Ja operation receipt was not created");
            }
        }
    }

    /**
     * SQLite JDBC 的可空列显式绑定 VARCHAR null，避免 overload 推断差异改变 Thread preferences。
     */
    private static void nullable(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        }
        else {
            statement.setString(index, value);
        }
    }

    /**
     * 冻结同事务准入回调；Core SPI 不携带 JDBC 异常，SQL 故障在回调边界转为中立参与者错误。
     */
    @Override
    public AgentStoreTransactionParticipant create(AdmissionFacts facts) {
        Objects.requireNonNull(facts, "facts");
        return transaction -> {
            try {
                apply(transaction, facts);
            } catch (SQLException failure) {
                throw new AgentStore.StoreParticipantException("Ja admission participant failed", failure);
            }
        };
    }
}
