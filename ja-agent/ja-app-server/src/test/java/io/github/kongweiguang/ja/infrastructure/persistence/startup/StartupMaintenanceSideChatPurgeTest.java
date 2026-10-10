// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.startup;

import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.store.local.infrastructure.SQLiteAgentStore;
import io.github.kongweiguang.ja.infrastructure.persistence.database.DatabaseConfig;
import io.github.kongweiguang.ja.infrastructure.persistence.database.JaDatabase;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.AgentMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.AttachmentMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.HistoryMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.InstructionScopeMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.InteractionMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PlanEvaluationMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PlanMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SchemaMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SideChatMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SideChatPurgeMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SubagentPolicyMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.ThreadDiscoveryMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.ThreadForkMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.ToolCallPreviewMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.task.SideChatPersistence;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用当前 SQLite schema 验证启动维护会销毁旧侧聊格式并清理其完整 AgentStore 事实。 */
final class StartupMaintenanceSideChatPurgeTest {
    private static final long AT = Instant.parse("2026-09-26T12:00:00Z").toEpochMilli();
    private static final String MAIN_THREAD = "thr_startup_main";
    private static final String SIDE_THREAD = "thr_startup_side";
    private static final List<String> STORE_TABLES = List.of("agent_conversation", "agent_execution", "agent_record",
            "agent_event", "agent_operation_receipt", "agent_commit_receipt", "agent_lease");
    private static final MybatisUnitOfWork.SessionOwner TEST_TRANSACTION_OWNER =
            new MybatisUnitOfWork.SessionOwner() {
                /** 测试只替换 Solon owner，仍提交或回滚启动 purge 的完整 Mapper callback。 */
                @Override
                public <T> T execute(SqlSessionFactory sessions, MybatisUnitOfWork.Work<T> work) {
                    try (SqlSession session = sessions.openSession()) {
                        try {
                            T result = work.apply(PersistenceMappers.open(session));
                            session.commit();
                            return result;
                        } catch (Throwable failure) {
                            session.rollback();
                            if (failure instanceof StorageException persistence) {
                                throw persistence;
                            }
                            throw new StorageException(StorageException.Code.TRANSACTION,
                                    "startup purge test transaction failed", failure);
                        }
                    }
                }
            };

    @TempDir
    Path temp;

    /**
     * 初始化要清除旧 SIDE_TASK 并持久化通知路由回执；过期 seed 正文不按新 codec 解码。
     */
    @Test
    void initializationPurgesLegacySideTaskAndAllStoreFactsButKeepsMainThread() throws Exception {
        JaDatabase database = JaDatabase.open(new DatabaseConfig(temp.resolve("startup-side-chat.sqlite3"),
                Duration.ofSeconds(5), temp.resolve("ja-home")));
        SqlSessionFactory sessions = sessionFactory(database);
        boolean initializeAttempted = false;
        try {
            seedLegacySideChat(sessions);
            try (SQLiteAgentStore store = new SQLiteAgentStore(database.dataSource())) {
                seedStoreConversation(store, "ja.local", SIDE_THREAD);
                seedStoreConversation(store, "ja.local", MAIN_THREAD);
            }

            initializeAttempted = true;
            StartupMaintenanceService maintenance = StartupMaintenanceService.initialize(database, sessions,
                    new SideChatPersistence(sessions, TEST_TRANSACTION_OWNER));
            maintenance.requireInitialized();

            assertEquals(0, count(sessions, "ja_conversation_thread", "thread_id='" + SIDE_THREAD + "'"));
            assertEquals(0, count(sessions, "ja_conversation_lineage", "child_thread_id='" + SIDE_THREAD + "'"));
            assertEquals(0, count(sessions, "ja_task_context_seed", "context_seed_id='seed_startup_legacy'"));
            assertEquals(0, count(sessions, "ja_task_projection", "task_thread_id='" + SIDE_THREAD + "'"));
            assertEquals(0, count(sessions, "ja_task_activity", "task_thread_id='" + SIDE_THREAD + "'"));
            assertEquals(0, count(sessions, "ja_conversation_thread", "thread_id='" + SIDE_THREAD
                    + "' AND temporary_lifecycle_state IS NOT NULL"));
            assertEquals(1, count(sessions, "ja_conversation_thread", "thread_id='" + MAIN_THREAD + "'"));
            try (SqlSession session = sessions.openSession()) {
                SideChatPurgeMapper purges = PersistenceMappers.open(session).sideChatPurges();
                TaskRecords.SideChatCloseReceiptRow receipt = purges.selectCloseReceipt(SIDE_THREAD);
                assertNotNull(receipt);
                assertEquals(MAIN_THREAD, receipt.ownerRootThreadId());
                assertEquals(List.of(SIDE_THREAD), purges.selectCloseReceiptMembers(SIDE_THREAD).stream()
                        .map(TaskRecords.SideChatCloseMemberRow::threadId).toList());
            }
            for (String table : STORE_TABLES) {
                assertEquals(0, storeCount(database, table, SIDE_THREAD), table);
                assertTrue(storeCount(database, table, MAIN_THREAD) > 0, table);
            }
        } finally {
            if (!initializeAttempted) {
                database.bindWalCheckpoint(sessions);
            }
            database.close();
        }
    }

    /** 注册与生产相同的映射集合，测试事务 owner 才能在初始化入口提交同一个 purge SQL。 */
    private static SqlSessionFactory sessionFactory(JaDatabase database) {
        Configuration configuration = new Configuration(new Environment("startup-test",
                new JdbcTransactionFactory(), database.dataSource()));
        configuration.addMapper(SchemaMapper.class);
        configuration.addMapper(HistoryMapper.class);
        configuration.addMapper(AgentMapper.class);
        configuration.addMapper(AttachmentMapper.class);
        configuration.addMapper(InstructionScopeMapper.class);
        configuration.addMapper(TaskMapper.class);
        configuration.addMapper(SubagentPolicyMapper.class);
        configuration.addMapper(InteractionMapper.class);
        configuration.addMapper(PlanEvaluationMapper.class);
        configuration.addMapper(PlanMapper.class);
        configuration.addMapper(ThreadDiscoveryMapper.class);
        configuration.addMapper(ThreadForkMapper.class);
        configuration.addMapper(SideChatMapper.class);
        configuration.addMapper(SideChatPurgeMapper.class);
        configuration.addMapper(ToolCallPreviewMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 准备真实 Ja 侧 Thread、旧格式冻结 seed 和孤立 OPEN marker，模拟上次版本留下的临时会话。 */
    private static void seedLegacySideChat(SqlSessionFactory sessions) throws Exception {
        try (SqlSession session = sessions.openSession(); Statement sql = session.getConnection().createStatement()) {
            sql.executeUpdate("""
                INSERT INTO ja_workspace(workspace_id,root_path,kind,display_name,trust,created_at_ms,updated_at_ms) \
                VALUES('ws_startup','C:/ja/startup','PROJECT','Startup test','TRUSTED','\
                """ + AT + "','" + AT + "')");
            sql.executeUpdate("""
                INSERT INTO ja_conversation_thread(thread_id,owner_user_id,temporary_lifecycle_state,workspace_id,title,created_at_ms,updated_at_ms,provider_id,model_id,\
                access_mode,title_source,collaboration_mode) VALUES \
                ('\
                """ + MAIN_THREAD + "','user_local',NULL,'ws_startup','Main','" + AT + "','" + AT
                    + "','provider','model','APPROVAL_REQUIRED','MANUAL','DEFAULT'),"
                    + "('" + SIDE_THREAD + "','user_local','OPEN','ws_startup','Old side','" + AT + "','" + AT
                    + "','provider','model','APPROVAL_REQUIRED','MANUAL','DEFAULT')");
            sql.executeUpdate("""
                INSERT INTO ja_task_context_seed(context_seed_id,parent_thread_id,parent_revision,\
                inheritance_mode,effective_context_json,references_json,permission_ceiling_json,fingerprint,\
                created_at_ms) \
                VALUES('seed_startup_legacy','\
                """ + MAIN_THREAD + "',0,'EFFECTIVE_CONTEXT',"
                    + "'{\"legacyEffectiveContext\":{\"messages\":[]}}','[]','{}','"
                    + "a".repeat(64) + "','" + AT + "')");
            sql.executeUpdate("""
                INSERT INTO ja_conversation_lineage(child_thread_id,parent_thread_id,root_thread_id,task_name,depth,\
                task_kind,lifecycle,context_seed_id,created_at_ms) VALUES('\
                """ + SIDE_THREAD + "','"
                    + MAIN_THREAD + "','" + MAIN_THREAD + "','Old side',1,'SIDE_TASK','INDEPENDENT',"
                    + "'seed_startup_legacy','" + AT + "')");
            sql.executeUpdate("""
                INSERT INTO ja_task_activity(activity_id,root_thread_id,task_thread_id,actor_thread_id,\
                kind,summary_json,created_at_ms) VALUES('activity_startup_legacy','\
                """ + MAIN_THREAD + "','"
                    + SIDE_THREAD + "','" + MAIN_THREAD + "','CREATED','{}','" + AT + "')");
            long sequence;
            try (ResultSet rows = sql.executeQuery("SELECT last_insert_rowid()")) {
                if (!rows.next()) {
                    throw new IllegalStateException("startup activity sequence is missing");
                }
                sequence = rows.getLong(1);
            }
            sql.executeUpdate("""
                INSERT INTO ja_task_projection(task_thread_id,root_thread_id,state,latest_activity_sequence,\
                updated_at_ms) VALUES('\
                """ + SIDE_THREAD + "','" + MAIN_THREAD + "','IDLE'," + sequence + ",'" + AT + "')");
            session.commit();
        }
    }

    /** 使用当前 lease 时间建立每张执行、事件、回执与租约表都非空的真实 Store 事实图。 */
    private static void seedStoreConversation(AgentStore store, String tenantId, String threadId) {
        Instant now = Instant.now();
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(tenantId, threadId, "user_local");
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(conversation, "turn_startup_" + threadId);
        store.initializeConversation(new AgentStore.ConversationInitialization(conversation, now));
        store.admit(new AgentStore.Admission(execution, 0, 1, "RUNNING", new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}"), List.of(new AgentStore.RecordWrite("record_startup_" + threadId, "test.fact", 1, new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}"), now)), List.of(new AgentStore.EventWrite("event_startup_" + threadId, "test.event", 1, new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}"), now)), new AgentStore.OperationIdentity("operation_startup_" + threadId, "test.start", "b".repeat(64), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")), now));
        AgentStore.Lease lease = store.claimLease(new AgentStore.LeaseRequest(conversation,
                "owner_startup_" + threadId, now, now.plusSeconds(300)));
        store.commit(new AgentStore.ExecutionCommit(execution, 1, "RUNNING", 1, lease, 1,
                "COMPLETED", JsonObject.empty(), List.of(), List.of(), new AgentStore.CommitIdentity(execution,
                "commit_startup_" + threadId, "c".repeat(64)), Optional.empty(), now));
    }

    /** 固定 Ja 表名均来自测试内常量；谓词只用本测试 hard-coded identity 检查启动后的物理结果。 */
    private static long count(SqlSessionFactory sessions, String table, String predicate) throws Exception {
        try (SqlSession session = sessions.openSession(); Statement query = session.getConnection().createStatement();
             ResultSet rows = query.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            if (!rows.next()) {
                throw new IllegalStateException("startup purge count is missing");
            }
            return rows.getLong(1);
        }
    }

    /** 每张 Store 表只按租户与会话参数回读，表名由固定白名单防止查询超出验证范围。 */
    private static long storeCount(JaDatabase database, String table, String threadId) throws Exception {
        if (!STORE_TABLES.contains(table)) {
            throw new IllegalArgumentException("unknown AgentStore table");
        }
        try (Connection connection = database.dataSource().getConnection();
             PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE tenant_id=? AND thread_id=?")) {
            query.setString(1, "ja.local");
            query.setString(2, threadId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("AgentStore purge count is missing");
                }
                return rows.getLong(1);
            }
        }
    }
}
