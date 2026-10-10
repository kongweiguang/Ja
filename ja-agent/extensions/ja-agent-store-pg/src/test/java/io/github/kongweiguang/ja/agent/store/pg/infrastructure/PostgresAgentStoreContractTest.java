// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.store.pg.infrastructure;

import io.github.kongweiguang.ja.agent.application.gateway.store.contract.AgentStoreContractAssertions;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

/**
 * 在临时真实 PostgreSQL 服务执行同一契约，覆盖真实锁和事务语义而非模拟方言。
 */
final class PostgresAgentStoreContractTest {
    private static EmbeddedPostgres postgres;

    /**
     * 每个契约类启动独立临时 PostgreSQL 实例，测试完成后丢弃，不触碰业务数据库。
     */
    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    /**
     * 只关闭本测试类启动的临时数据库，避免影响宿主环境中的其他服务。
     */
    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    /**
     * 临时 schema 运行共享状态机合同，并验证 participant Error 会撤销会话锚点。
     */
    @Test
    void fulfills_the_transaction_and_recovery_contract() throws Exception {
        DataSource database = newDatabase();
        PostgresAgentStore store = new PostgresAgentStore(database);
        String schema;
        try (Connection connection = database.getConnection()) {
            schema = connection.getSchema();
        }
        DataSource peerDatabase = postgres.getDatabase("postgres", "postgres", java.util.Map.of("currentSchema", schema));
        PostgresAgentStore peerStore = new PostgresAgentStore(peerDatabase);
        try {
            AgentStoreContractAssertions.verify(database, store, peerStore);
            assertParticipantErrorRollsBackConversationCreation(store);
        } finally {
            peerStore.close();
            store.close();
        }
        try (Connection connection = database.getConnection()) {
            org.junit.jupiter.api.Assertions.assertFalse(connection.isClosed(),
                    "closing a store must leave an injected DataSource usable");
        }
    }

    /** 独立 schema 运行 SQL 闭集、身份约束和分页探针，便于单独核验真实 PostgreSQL DDL。 */
    @Test
    void enforcesSqlConstraintsAndPagingContract() throws Exception {
        DataSource database = newDatabase();
        PostgresAgentStore store = new PostgresAgentStore(database);
        try {
            AgentStoreSqlContractAssertions.verify(database, store);
        } finally {
            store.close();
        }
    }

    /** 构造器应拒绝固定 Store schema 中列和约束形状的漂移，不能把可查询误认为契约兼容。 */
    @Test
    void rejectsColumnAndConstraintShapeDrift() throws Exception {
        assertSchemaDriftRejected("ALTER TABLE agent_conversation ADD COLUMN schema_drift INTEGER");
        assertSchemaDriftRejected("ALTER TABLE agent_conversation DROP CONSTRAINT pk_agent_conversation");
        assertSchemaDriftRejected("ALTER TABLE agent_record DROP CONSTRAINT uk_agent_record_product_reference");
        assertSchemaDriftRejected("ALTER TABLE agent_event DROP CONSTRAINT fk_agent_event_execution");
        assertSchemaDriftRejected("ALTER TABLE agent_execution DROP CONSTRAINT ck_agent_execution_state");
    }

    /** 独立扩展可以先安装且跨启动保留；未知Core表仍拒绝，不能通过放宽全部agent_表掩盖结构漂移。 */
    @Test
    void bootstrapsAndReopensAlongsideSkillExtensionNamespace() throws Exception {
        DataSource database = newEmptyDatabase();
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE agent_skill_package (tenant_id text NOT NULL)");
            statement.execute("CREATE TABLE agent_skill_resource (tenant_id text NOT NULL)");
            statement.execute("CREATE TABLE agent_skill_catalog_revision (tenant_id text NOT NULL)");
            statement.execute("INSERT INTO agent_skill_package VALUES ('extension_owned')");
        }
        PostgresAgentStore.bootstrap(database);
        try (PostgresAgentStore store = new PostgresAgentStore(database)) {
            PostgresAgentStore.bootstrap(database);
        }
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT tenant_id FROM agent_skill_package")) {
                Assertions.assertTrue(rows.next());
                Assertions.assertEquals("extension_owned", rows.getString(1));
                Assertions.assertFalse(rows.next());
            }
            statement.execute("CREATE TABLE agent_unexpected (id integer)");
        }
        Assertions.assertThrows(AgentStore.StoreIntegrityException.class,
                () -> new PostgresAgentStore(database));
        Assertions.assertThrows(AgentStore.StoreIntegrityException.class,
                () -> PostgresAgentStore.bootstrap(database));
    }

    /** 每个用例在独立schema显式引导Core，不依赖其它测试创建的表。 */
    private DataSource newDatabase() throws Exception {
        DataSource database = newEmptyDatabase();
        PostgresAgentStore.bootstrap(database);
        return database;
    }

    /** 临时schema由本类统一持有；不自动bootstrap，允许验证独立扩展先安装的真实启动顺序。 */
    private DataSource newEmptyDatabase() throws Exception {
        String schema = "agent_store_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = postgres.getPostgresDatabase().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        DataSource database = postgres.getDatabase("postgres", "postgres", java.util.Map.of("currentSchema", schema));
        return database;
    }

    /** 每种变异使用全新随机 schema，验证真实数据库形状改变后构造器稳定拒绝。 */
    private void assertSchemaDriftRejected(String mutation) throws Exception {
        DataSource database = newDatabase();
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(mutation);
        }
        Assertions.assertThrows(AgentStore.StoreIntegrityException.class,
                () -> new PostgresAgentStore(database), "schema mutation must be rejected: " + mutation);
    }

    /**
     * 会话锚点先写后调用宿主回调；断言 Error 原样传播且锚点不存在，证明事务仍由 Store 回滚。
     */
    private static void assertParticipantErrorRollsBackConversationCreation(PostgresAgentStore store) {
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
