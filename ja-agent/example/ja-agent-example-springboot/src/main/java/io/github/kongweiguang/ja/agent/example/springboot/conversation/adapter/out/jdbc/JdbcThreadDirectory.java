// @author kongweiguang
package io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.out.jdbc;

import io.github.kongweiguang.ja.agent.example.springboot.conversation.domain.ThreadMetadata;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.port.out.ThreadDirectory;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActor;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.ConversationTransactionContext;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/** PostgreSQL 会话目录适配器；Core Store 仍持有执行事实和事务所有权。 */
@Repository
public class JdbcThreadDirectory implements ThreadDirectory {
    private static final String COLUMNS = "thread_id, agent_id, title, is_archived, version, created_at_ms, updated_at_ms";
    private final JdbcTemplate jdbc;

    /** 只保存目录数据源；DDL 在 Spring 初始化回调中执行。 */
    public JdbcThreadDirectory(DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
    }

    /** 新建单数目录表并显式命名约束；0.2 schema 不读取或迁移旧演示表。 */
    @PostConstruct
    void initializeSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ja_agent_demo_thread (
                    tenant_id text NOT NULL,
                    user_id text NOT NULL,
                    thread_id text NOT NULL,
                    operation_key text NOT NULL,
                    agent_id text NOT NULL DEFAULT 'general',
                    title text NOT NULL,
                    is_title_initialized boolean NOT NULL DEFAULT false,
                    is_title_customized boolean NOT NULL DEFAULT false,
                    is_archived boolean NOT NULL DEFAULT false,
                    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
                    created_at_ms bigint NOT NULL,
                    updated_at_ms bigint NOT NULL,
                    CONSTRAINT pk_ja_agent_demo_thread PRIMARY KEY (tenant_id, thread_id),
                    CONSTRAINT uk_ja_agent_demo_thread_tenant_user_operation_key UNIQUE (tenant_id, user_id, operation_key)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_ja_agent_demo_thread_owner_archive_updated
                    ON ja_agent_demo_thread (tenant_id, user_id, is_archived, updated_at_ms DESC, thread_id DESC)
                """);
    }

    /** 当前 owner 由 HTTP 边界提供，SQL 始终同时约束租户、用户和 Thread。 */
    @Override
    public ThreadMetadata find(CurrentActor actor, String threadId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ja_agent_demo_thread WHERE tenant_id=? AND user_id=? AND thread_id=?",
                (row, ignored) -> metadata(row), actor.tenantId(), actor.userId(), threadId).stream().findFirst().orElse(null);
    }

    /** 以持久 operationKey 回读原 Thread，使创建响应丢失后的重试保持幂等。 */
    @Override
    public ThreadMetadata findByCreateKey(CurrentActor actor, String operationKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ja_agent_demo_thread WHERE tenant_id=? AND user_id=? AND operation_key=?",
                (row, ignored) -> metadata(row), actor.tenantId(), actor.userId(), operationKey).stream().findFirst().orElse(null);
    }

    /** 用显式转义字符按字面匹配用户搜索词，并绑定参数和有界分页。 */
    @Override
    public List<ThreadMetadata> list(CurrentActor actor, boolean archived, String search, int limit, int offset) {
        String term = search == null ? "" : search;
        String pattern = "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return jdbc.query("SELECT " + COLUMNS + " FROM ja_agent_demo_thread WHERE tenant_id=? AND user_id=? AND is_archived=? AND lower(title) LIKE ? ESCAPE '!' ORDER BY updated_at_ms DESC, thread_id DESC LIMIT ? OFFSET ?",
                (row, ignored) -> metadata(row), actor.tenantId(), actor.userId(), archived,
                pattern, limit, offset);
    }

    /** 使用 Core 借出的连接；checked SQL 故障在 Store 回调边界转为运行时异常以触发同事务回滚。 */
    @Override
    public void insert(ConversationTransactionContext context, CurrentActor actor, String threadId, String operationKey,
                       String agentId) {
        try {
            try (PreparedStatement statement = context.resources().unwrap(java.sql.Connection.class).prepareStatement("""
                    INSERT INTO ja_agent_demo_thread
                        (tenant_id, user_id, thread_id, operation_key, agent_id, title, is_archived, version,
                         created_at_ms, updated_at_ms)
                    VALUES (?, ?, ?, ?, ?, '新对话', false, 0, ?, ?)
                    """)) {
                statement.setString(1, actor.tenantId());
                statement.setString(2, actor.userId());
                statement.setString(3, threadId);
                statement.setString(4, operationKey);
                statement.setString(5, agentId);
                long occurredAtMs = context.occurredAt().toEpochMilli();
                statement.setLong(6, occurredAtMs);
                statement.setLong(7, occurredAtMs);
                statement.executeUpdate();
            }
        } catch (SQLException failure) {
            throw new AgentStore.StoreParticipantException("thread directory insert failed", failure);
        }
    }

    /** CAS 冲突保持原运行时语义；SQL 故障保留 cause 并以运行时参与者失败触发 Store 回滚。 */
    @Override
    public void update(ConversationTransactionContext context, CurrentActor actor, String threadId, long expectedVersion,
                       ThreadMetadata.Change change) {
        try {
            try (PreparedStatement statement = context.resources().unwrap(java.sql.Connection.class).prepareStatement("""
                    UPDATE ja_agent_demo_thread
                    SET title = ?, is_title_customized = is_title_customized OR ?, is_archived = ?,
                        version = version + 1, updated_at_ms = ?
                    WHERE tenant_id = ? AND user_id = ? AND thread_id = ? AND version = ?
                    """)) {
                statement.setString(1, change.title());
                statement.setBoolean(2, change.customized());
                statement.setBoolean(3, change.archived());
                statement.setLong(4, context.occurredAt().toEpochMilli());
                statement.setString(5, actor.tenantId());
                statement.setString(6, actor.userId());
                statement.setString(7, threadId);
                statement.setLong(8, expectedVersion);
                if (statement.executeUpdate() != 1) {
                    throw new AgentStore.StoreConflictException("thread metadata version changed");
                }
            }
        } catch (SQLException failure) {
            throw new AgentStore.StoreParticipantException("thread directory update failed", failure);
        }
    }

    /** 事实和列表顺序共用 Core 事务；只把 checked SQL 故障包装为 Store 认可的运行时回滚信号。 */
    @Override
    public void onExecutionCommit(AgentStoreTransactionContext context, String title) {
        try {
            try (PreparedStatement statement = context.resources().unwrap(java.sql.Connection.class).prepareStatement("""
                    UPDATE ja_agent_demo_thread
                    SET title = CASE WHEN is_title_customized = false AND is_title_initialized = false
                                     AND ? IS NOT NULL THEN ? ELSE title END,
                        is_title_initialized = is_title_initialized OR ? IS NOT NULL,
                        updated_at_ms = ?
                    WHERE tenant_id = ? AND user_id = ? AND thread_id = ?
                    """)) {
                statement.setString(1, title);
                statement.setString(2, title);
                statement.setString(3, title);
                statement.setLong(4, context.occurredAt().toEpochMilli());
                statement.setString(5, context.execution().conversation().tenantId());
                statement.setString(6, context.execution().conversation().userId());
                statement.setString(7, context.execution().conversation().threadId());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("thread metadata missing");
                }
            }
        } catch (SQLException failure) {
            throw new AgentStore.StoreParticipantException("thread execution commit participant failed", failure);
        }
    }

    /** 数据库存储 UTC epoch 毫秒；只在目录边界恢复为 Java Instant。 */
    private static ThreadMetadata metadata(ResultSet row) throws SQLException {
        return new ThreadMetadata(row.getString(1), row.getString(2), row.getString(3), row.getBoolean(4),
                row.getLong(5), Instant.ofEpochMilli(row.getLong(6)), Instant.ofEpochMilli(row.getLong(7)));
    }
}

