// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.support;

import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import org.apache.ibatis.exceptions.PersistenceException;
import io.github.kongweiguang.ja.infrastructure.persistence.database.DatabaseConfig;
import io.github.kongweiguang.ja.infrastructure.persistence.database.JaDatabase;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.agent.store.local.infrastructure.SQLiteAgentStore;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.AgentMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.AttachmentMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.HistoryMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.InstructionScopeMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SchemaMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SubagentPolicyMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.InteractionMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PlanEvaluationMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PlanMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.ThreadDiscoveryMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.ThreadForkMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SideChatMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SideChatPurgeMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.ToolCallPreviewMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisAttachmentRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisAutomaticTitleUsageRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisConversationProjectionRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisHistoryService;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisInstructionScopeRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.task.MybatisTaskRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import io.github.kongweiguang.ja.conversation.domain.model.SubagentPolicy;
import io.github.kongweiguang.ja.conversation.application.gateway.SubagentPolicySource;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaAgentStore;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaConversationParticipant;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaTransactionParticipant;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
                /** 模拟生产事务：领域运行异常原样传播，持久化异常稳定映射；回滚错误不能遮蔽首因。 */
                @Override
                public <T> T execute(SqlSessionFactory sessions, MybatisUnitOfWork.Work<T> work) {
                    try (org.apache.ibatis.session.SqlSession session = sessions.openSession()) {
                        try {
                            T result = work.apply(PersistenceMappers.open(session));
                            session.commit();
                            return result;
                        } catch (Throwable failure) {
                            try {
                                session.rollback();
                            } catch (Throwable rollbackFailure) {
                                if (failure != rollbackFailure) {
                                    failure.addSuppressed(rollbackFailure);
                                }
                            }
                            if (failure instanceof StorageException persistence) {
                                throw persistence;
                            }
                            if (failure instanceof PersistenceException persistence) {
                                throw new StorageException(StorageException.Code.TRANSACTION,
                                        "Ja participant transaction failed", persistence);
                            }
                            if (failure instanceof RuntimeException runtime) {
                                throw runtime;
                            }
                            if (failure instanceof Error error) {
                                throw error;
                            }
                            throw new StorageException(StorageException.Code.TRANSACTION,
                                    "test transaction failed", failure);
                        }
                    }
                }
            };

    @TempDir protected Path temp;

    /** 生产不会调用此工厂；注册 PersistenceMappers 的完整映射集合，避免测试事务遗漏真实 participant 依赖。 */
    protected TestDatabase database(String name) throws Exception {
        return database(name, DatabaseConfig.DEFAULT_BUSY_TIMEOUT);
    }

    /** busy 测试可缩短锁等待，但仍使用与生产相同的 datasource pragma。 */
    protected TestDatabase database(String name, Duration busyTimeout) throws Exception {
        JaDatabase database = JaDatabase.open(new DatabaseConfig(
                temp.resolve(name + ".sqlite3"), busyTimeout, temp.resolve("ja-home")));
        Configuration configuration = new Configuration(new Environment("test",
                new JdbcTransactionFactory(), database.dataSource()));
        configuration.setMapUnderscoreToCamelCase(false);
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
        SqlSessionFactory sessions = new SqlSessionFactoryBuilder().build(configuration);
        database.bindWalCheckpoint(sessions);
        return new TestDatabase(database, sessions, new ObjectMapper());
    }

    /**
     * fixture 按 composition 相反顺序释放 store 使用者和数据库 lease。
     *
     * @param database 数据库
     * @param sessions 会话集合
     * @param mapper JSON 对象映射器
     */
    protected record TestDatabase(JaDatabase database,
                                  SqlSessionFactory sessions,
                                  ObjectMapper mapper)
            implements AutoCloseable {
        /** 交互取消与 Conversation 共享真实 SQLite 事务，覆盖下一轮模型上下文而非仅状态列。 */
        public io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisInteractionRepository interactions() {
            return new io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisInteractionRepository(
                    sessions, mapper, TEST_TRANSACTIONS);
        }
        /** 使用相同 SQLite 数据源创建中立 Store；关闭 Store 不会关闭 fixture 持有的数据库。 */
        public SQLiteAgentStore agentStore() {
            return new SQLiteAgentStore(database.dataSource());
        }

        /** 复用测试数据库连接和 raw Store，验证 Task 的冻结上下文只从 Core 事实读取。 */
        public MybatisTaskRepository taskRepository() {
            return taskRepository(agentStore());
        }

        /** Task 与 JaAgentStore 共用同一 raw Store 实例，但 repository 不获得其关闭权。 */
        private MybatisTaskRepository taskRepository(AgentStore rawStore) {
            return new MybatisTaskRepository(sessions, mapper, TEST_TRANSACTIONS, rawStore,
                    new StorePayloadCodec(mapper), new JacksonJsonValueCodec(mapper), CLOCK);
        }

        /** 为 Ja 投影测试装配同库参与者，Store facts、Thread 展示与附件 owner 共用一次事务。 */
        public AgentStore jaAgentStore() {
            AgentStore rawStore = agentStore();
            return new JaAgentStore(rawStore, new JaTransactionParticipant(mapper, List.of(),
                    taskRepository(rawStore), attachments()),
                    new JaConversationParticipant());
        }

        /** History 与测试 JaAgentStore 共用数据库，使 Thread 创建和元数据修订走真实事务参与者。 */
        public MybatisHistoryService history() {
            return history(SubagentPolicy::defaultPolicy);
        }

        /** 使用显式策略源检验 Thread 创建时冻结的 Ja 元数据。 */
        public MybatisHistoryService history(SubagentPolicySource policySource) {
            return new MybatisHistoryService(sessions, jaAgentStore(), policySource, mapper,
                    CLOCK, TEST_TRANSACTIONS);
        }

        /** 投影读取器复用测试数据源，但只读 Ja 侧展示快照，不提供执行恢复写入口。 */
        public MybatisConversationProjectionRepository conversationProjections() {
            return new MybatisConversationProjectionRepository(sessions, TEST_TRANSACTIONS);
        }

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

        /** 暴露临时数据库路径，仅供 lease 和 WAL 边界测试使用。 */
        public Path path() { return database.databasePath(); }

        /** 关闭数据库并释放 lease；各适配器本身不拥有 datasource。 */
        @Override public void close() { database.close(); }
    }
}
