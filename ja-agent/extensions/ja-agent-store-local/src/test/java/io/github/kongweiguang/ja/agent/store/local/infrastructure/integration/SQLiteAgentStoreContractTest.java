// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.store.local.infrastructure.integration;

import io.github.kongweiguang.ja.agent.application.gateway.store.contract.AgentStoreContractAssertions;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.store.local.infrastructure.SQLiteAgentStore;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

/**
 * 在真实 SQLite 文件和每操作新连接上运行共用契约，验证不依赖连接内存状态的持久恢复。
 */
final class SQLiteAgentStoreContractTest {
    @TempDir
    Path temporaryDirectory;

    /** 构造器只验证宿主 schema；空库失败后不应获得 DDL 写入权。 */
    @Test
    void rejectsAnUninitializedSchemaWithoutCreatingTables() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + temporaryDirectory.resolve("uninitialized.sqlite"));

        Assertions.assertThrows(AgentStore.StoreIntegrityException.class,
                () -> new SQLiteAgentStore(dataSource));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet tables = statement.executeQuery(
                     "SELECT COUNT(*) FROM sqlite_schema WHERE type='table' AND name LIKE 'agent_%'")) {
            Assertions.assertTrue(tables.next());
            Assertions.assertEquals(0, tables.getInt(1));
        }
    }

    /** 构造器必须拒绝宿主命名空间内未声明的 Store 表，避免误接入不完整或混合版本数据库。 */
    @Test
    void rejectsAnAdditionalOwnedSchemaTable() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + temporaryDirectory.resolve("unexpected-table.sqlite"));
        SQLiteAgentStore.bootstrap(dataSource);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE agent_unexpected (value INTEGER)");
        }

        Assertions.assertThrows(AgentStore.StoreIntegrityException.class,
                () -> new SQLiteAgentStore(dataSource));
    }

    /** CHECK 字符集中的空格是字面量语义，校验须发现其被改成空字符串后的 schema 漂移。 */
    @Test
    void rejectsWhitespaceLiteralDriftInConstraint() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + temporaryDirectory.resolve("literal-drift.sqlite"));
        SQLiteAgentStore.bootstrap(dataSource);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA writable_schema = ON");
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE sqlite_schema SET sql = replace(sql, ?, ?) WHERE type='table' AND name=?")) {
                update.setString(1, "||' '||");
                update.setString(2, "||''||");
                update.setString(3, "agent_conversation");
                Assertions.assertEquals(1, update.executeUpdate());
            } finally {
                statement.execute("PRAGMA writable_schema = OFF");
            }
        }

        Assertions.assertThrows(AgentStore.StoreIntegrityException.class,
                () -> new SQLiteAgentStore(dataSource));
    }

    /** 重复 bootstrap 与新 Store 实例应接受未漂移 schema，保证进程重启后的正常装配路径。 */
    @Test
    void acceptsCurrentSchemaAfterBootstrapAndReopen() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + temporaryDirectory.resolve("reopen.sqlite"));
        SQLiteAgentStore.bootstrap(dataSource);
        new SQLiteAgentStore(dataSource).close();

        SQLiteAgentStore.bootstrap(dataSource);
        SQLiteAgentStore reopened = new SQLiteAgentStore(dataSource);
        reopened.close();
    }

    /**
     * 先显式 bootstrap 空文件库，再运行共享状态机合同；生产构造器只校验 schema，不取得初始化写权限。
     */
    @Test
    void fulfills_the_transaction_and_recovery_contract() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + temporaryDirectory.resolve("agent-store.sqlite"));
        SQLiteAgentStore.bootstrap(dataSource);
        SQLiteAgentStore store = new SQLiteAgentStore(dataSource);
        SQLiteAgentStore peerStore = new SQLiteAgentStore(dataSource);
        try {
            AgentStoreContractAssertions.verify(dataSource, store, peerStore);
            assertParticipantErrorRollsBackConversationCreation(store);
        } finally {
            peerStore.close();
            store.close();
        }
        try (Connection connection = dataSource.getConnection()) {
            org.junit.jupiter.api.Assertions.assertFalse(connection.isClosed(),
                    "closing a store must leave an injected DataSource usable");
        }
    }

    /** 独立运行 SQL 闭集与分页边界探针，使 SQL 合同不被共享事务合同失败遮蔽。 */
    @Test
    void enforcesSqlConstraintsAndPagingContract() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + temporaryDirectory.resolve("sql-contract.sqlite"));
        SQLiteAgentStore.bootstrap(dataSource);
        SQLiteAgentStore store = new SQLiteAgentStore(dataSource);
        try {
            AgentStoreSqlContractAssertions.verify(dataSource, store);
        } finally {
            store.close();
        }
    }

    /**
     * 会话锚点先写后调用宿主回调；断言 Error 原样传播且锚点不存在，证明事务仍由 Store 回滚。
     */
    private static void assertParticipantErrorRollsBackConversationCreation(SQLiteAgentStore store) {
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(
                "error-tenant-" + UUID.randomUUID(), "error-thread-" + UUID.randomUUID(),
                "error-user-" + UUID.randomUUID());
        AssertionError expected = new AssertionError("abort participant");
        AssertionError actual = Assertions.assertThrows(AssertionError.class,
                () -> store.initializeConversation(
                        new AgentStore.ConversationInitialization(conversation, Instant.now()),
                        transaction -> {
                            throw expected;
                        }));

        Assertions.assertSame(expected, actual);
        Assertions.assertTrue(store.loadConversation(conversation).isEmpty());
    }
}
