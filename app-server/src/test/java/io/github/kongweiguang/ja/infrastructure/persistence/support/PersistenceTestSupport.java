// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.support;

import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.database.DatabaseConfig;
import io.github.kongweiguang.ja.infrastructure.persistence.database.JaDatabase;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.AgentMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.AttachmentMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.CheckpointMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.HistoryMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.InstructionScopeMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.RecoveryMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SchemaMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SubagentPolicyMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.InteractionMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PlanEvaluationMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.ThreadDiscoveryMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SideChatMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SideChatPurgeMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.recovery.StartupRecoveryService;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisCheckpointStore;
import io.github.kongweiguang.ja.attachment.adapter.out.persistence.MybatisAttachmentRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisAutomaticTitleUsageRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisConversationRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisHistoryService;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisInstructionScopeRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import io.github.kongweiguang.ja.conversation.domain.SubagentPolicy;
import io.github.kongweiguang.ja.conversation.port.out.SubagentPolicySource;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.io.TempDir;

/** focused test 的真实临时 SQLite fixture；不发现、不读取或删除用户 Ja 数据。 */
public abstract class PersistenceTestSupport {
    protected static final Instant START = Instant.parse("2026-08-25T12:00:00Z");
    protected static final Clock CLOCK = Clock.fixed(START, ZoneOffset.UTC);
    private static final MybatisUnitOfWork.SessionOwner TEST_TRANSACTIONS =
            new MybatisUnitOfWork.SessionOwner() {
                /** 测试 owner 对 JdbcTransactionFactory 显式提交/回滚，生产源码不含此 fallback。 */
                @Override
                public <T> T execute(SqlSessionFactory sessions, MybatisUnitOfWork.Work<T> work) {
                    try (org.apache.ibatis.session.SqlSession session = sessions.openSession()) {
                        try {
                            T result = work.apply(PersistenceMappers.open(session));
                            session.commit();
                            return result;
                        } catch (Throwable failure) {
                            session.rollback();
                            if (failure instanceof StorageException persistence) throw persistence;
                            if (failure instanceof io.github.kongweiguang.ja.conversation.port.out
                                    .ConversationRepository.InputQueueException queueFailure) {
                                // 与生产事务桥一致保留稳定队列失败，避免测试只验证被包装后的假契约。
                                throw queueFailure;
                            }
                            throw new StorageException(StorageException.Code.TRANSACTION,
                                    "test transaction failed", failure);
                        }
                    }
                }
            };

    @TempDir protected Path temp;

    /** 生产不会调用此工厂；它仅让 focused test 对同一 XML/schema 执行真实 commit/rollback。 */
    protected TestDatabase database(String name) throws Exception {
        return database(name, DatabaseConfig.DEFAULT_BUSY_TIMEOUT);
    }

    /** busy 测试可缩短锁等待，但仍使用与生产相同的 datasource pragma。 */
    protected TestDatabase database(String name, Duration busyTimeout) throws Exception {
        JaDatabase database = JaDatabase.open(new DatabaseConfig(temp.resolve(name + ".sqlite3"), busyTimeout));
        Configuration configuration = new Configuration(new Environment("test",
                new JdbcTransactionFactory(), database.dataSource()));
        configuration.setMapUnderscoreToCamelCase(false);
        configuration.addMapper(SchemaMapper.class);
        configuration.addMapper(HistoryMapper.class);
        configuration.addMapper(AgentMapper.class);
        configuration.addMapper(AttachmentMapper.class);
        configuration.addMapper(CheckpointMapper.class);
        configuration.addMapper(RecoveryMapper.class);
        configuration.addMapper(InstructionScopeMapper.class);
        configuration.addMapper(TaskMapper.class);
        configuration.addMapper(SubagentPolicyMapper.class);
        configuration.addMapper(InteractionMapper.class);
        configuration.addMapper(PlanEvaluationMapper.class);
        configuration.addMapper(ThreadDiscoveryMapper.class);
        configuration.addMapper(SideChatMapper.class);
        configuration.addMapper(SideChatPurgeMapper.class);
        SqlSessionFactory sessions = new SqlSessionFactoryBuilder().build(configuration);
        database.bindWalCheckpoint(sessions);
        return new TestDatabase(database, sessions, new ObjectMapper());
    }

    /** fixture 按 composition 相反顺序释放 store 使用者和数据库 lease。 */
    protected record TestDatabase(JaDatabase database, SqlSessionFactory sessions, ObjectMapper mapper)
            implements AutoCloseable {
        /** 交互取消与 Conversation 共享真实 SQLite 事务，覆盖下一轮模型上下文而非仅状态列。 */
        public io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisInteractionRepository interactions() {
            return new io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisInteractionRepository(
                    sessions, mapper, TEST_TRANSACTIONS);
        }
        /** 使用显式测试事务 owner 创建 ConversationRepository，确保提交和回滚真实发生。 */
        public MybatisConversationRepository agentStore() {
            return new MybatisConversationRepository(sessions, mapper, TEST_TRANSACTIONS,
                    SubagentPolicy::defaultPolicy);
        }

        /** 使用可变测试源模拟全局设置变化，验证已创建 Thread 不会被回写。 */
        public MybatisConversationRepository agentStore(SubagentPolicySource policySource) {
            return new MybatisConversationRepository(sessions, mapper, TEST_TRANSACTIONS, policySource);
        }

        /** 保留容量边界测试的调用形状，但 V1 存储不接受旧容量参数。 */
        public MybatisConversationRepository agentStore(long ignoredTurnBytes, long ignoredDatabaseBytes) {
            return agentStore();
        }

        /** 创建共享同一临时数据库的 checkpoint 适配器。 */
        public MybatisCheckpointStore checkpoints() { return new MybatisCheckpointStore(sessions, mapper, TEST_TRANSACTIONS); }

        /** 创建共享同一事务 owner 的受管附件关系适配器。 */
        public MybatisAttachmentRepository attachments() {
            return new MybatisAttachmentRepository(sessions, TEST_TRANSACTIONS);
        }

        /** 创建共享同一事务 owner 的自动标题 usage ledger。 */
        public MybatisAutomaticTitleUsageRepository automaticTitleUsage() {
            return new MybatisAutomaticTitleUsageRepository(sessions, TEST_TRANSACTIONS);
        }

        /** 创建共享同一最终基线数据库的指令 scope 仓储，验证持久化与重启语义。 */
        public MybatisInstructionScopeRepository instructionScopes() {
            return new MybatisInstructionScopeRepository(sessions, TEST_TRANSACTIONS);
        }

        /** 创建使用固定时钟的启动恢复服务，保证断言不受本机时间影响。 */
        public StartupRecoveryService recovery() { return new StartupRecoveryService(sessions, CLOCK, TEST_TRANSACTIONS); }

        /** 创建共享同一事务与数据库的 Workspace/Thread 历史适配器。 */
        public MybatisHistoryService history(MybatisConversationRepository store) {
            return new MybatisHistoryService(sessions, store, mapper, CLOCK, TEST_TRANSACTIONS);
        }

        /** 暴露临时数据库路径，仅供 lease 和 WAL 边界测试使用。 */
        public Path path() { return database.databasePath(); }

        /** 关闭数据库并释放 lease；各适配器本身不拥有 datasource。 */
        @Override public void close() { database.close(); }
    }
}
