// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.skills.pg.infrastructure;

import io.github.kongweiguang.ja.agent.skills.pg.domain.SkillCatalogChangedException;
import io.github.kongweiguang.ja.agent.skills.pg.domain.SkillVersionConflictException;

import io.github.kongweiguang.ja.agent.domain.skill.SkillDescriptor;
import io.github.kongweiguang.ja.agent.domain.skill.SkillPage;
import io.github.kongweiguang.ja.agent.domain.skill.SkillReadRequest;
import io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 在独立真实 PostgreSQL 实例上验证 Skill 扩展的隔离、版本、分页和事务回滚契约。 */
final class PostgresSkillSourceIntegrationTest {
    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;
    private static PostgresSkillSource source;

    /**
     * 为整个测试类启动一次性数据库，避免连接开发者已有的 PostgreSQL 实例或触碰其数据。
     */
    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        PostgresSkillSchema.install(dataSource);
        source = new PostgresSkillSource(dataSource, "pg", 10);
    }

    /**
     * 所有数据库契约测试完成后关闭本测试创建的 PostgreSQL 进程。
     */
    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    /** 数据库冲突原因仅用于诊断，稳定错误标识不得暴露底层 SQL 文本。 */
    @Test
    void retainsDatabaseCauseWithoutExposingSqlDetails() {
        SQLException sqlFailure = new SQLException("duplicate key value", "23505");
        SkillVersionConflictException conflict = new SkillVersionConflictException(sqlFailure);

        assertEquals("skill_version_conflict", conflict.getMessage());
        assertSame(sqlFailure, conflict.getCause());
    }

    /**
     * 端到端校验版本化新增、更新、实时读取、旧版本拒绝及租户之间的不可见性。
     */
    @Test
    void storesVersionedPackagesAndIsolatesTenants() {
        SkillRequestContext tenantA = tenant();
        SkillRequestContext tenantB = tenant();
        PostgresSkillSource.SkillPackage initial = skill("review", "Review policy", "first body",
                Map.of("notes/checklist.txt", bytes("first resource")));

        SkillDescriptor first = source.write(tenantA, initial, 0);
        SkillPage visible = source.discover(tenantA, null, 20);
        assertEquals(List.of("review"), visible.skills().stream().map(SkillDescriptor::name).toList());
        assertEquals("first body", read(tenantA, first, "SKILL.md"));
        assertEquals("first resource", read(tenantA, first, "notes/checklist.txt"));
        assertTrue(source.discover(tenantB, null, 20).skills().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> read(tenantB, first, "SKILL.md"));

        SkillDescriptor second = source.write(tenantA,
                skill("review", "Updated review policy", "second body",
                        Map.of("notes/checklist.txt", bytes("second resource"))), first.version());
        assertEquals(2, second.version());
        assertThrows(IllegalStateException.class, () -> read(tenantA, first, "SKILL.md"));
        assertEquals("second body", read(tenantA, second, "SKILL.md"));
        assertEquals("second resource", read(tenantA, second, "notes/checklist.txt"));
        assertThrows(SkillVersionConflictException.class, () -> source.write(tenantA, initial, first.version()));

        source.delete(tenantA, "review", second.version());
        assertTrue(source.discover(tenantA, null, 20).skills().isEmpty());

        SkillDescriptor recreated = source.write(tenantA, initial, 0);
        assertTrue(recreated.version() > second.version());
        assertThrows(IllegalStateException.class, () -> read(tenantA, second, "SKILL.md"));
    }

    /**
     * 验证文档更新沿用包版本 CAS，但不会替换附件；冲突路径不得推进目录或留下部分正文。
     */
    @Test
    void updatesSkillDocumentsWithoutReplacingResources() {
        SkillRequestContext tenant = tenant();
        SkillDescriptor created = source.writeDocument(tenant, "draft", "Draft", "first body", 0);
        assertEquals("first body", read(tenant, created, "SKILL.md"));
        assertThrows(IllegalArgumentException.class, () -> read(tenant, created, "notes/checklist.txt"));

        SkillDescriptor first = source.write(tenant, skill("review", "Review policy", "first body",
                Map.of("notes/checklist.txt", bytes("keep this resource"))), 0);
        SkillDescriptor updated = source.writeDocument(tenant, "review", "Updated policy", "second body",
                first.version());
        assertEquals(first.version() + 1, updated.version());
        assertEquals("second body", read(tenant, updated, "SKILL.md"));
        assertEquals("keep this resource", read(tenant, updated, "notes/checklist.txt"));

        assertThrows(SkillVersionConflictException.class,
                () -> source.writeDocument(tenant, "review", "Stale policy", "stale body", first.version()));
        SkillDescriptor current = source.discover(tenant, null, 20).skills().stream()
                .filter(skill -> skill.name().equals("review")).findFirst().orElseThrow();
        assertEquals(updated.version(), current.version());
        assertEquals("Updated policy", current.description());
        assertEquals("second body", read(tenant, current, "SKILL.md"));
        assertEquals("keep this resource", read(tenant, current, "notes/checklist.txt"));
    }

    /**
     * 验证 PG 名称边界与 Core 一致：65/256 字符均能写入、发现和读取，257 字符拒绝且不进入目录。
     */
    @Test
    void acceptsCoreSkillNameLimitAndRejectsLongerNames() {
        SkillRequestContext tenant = tenant();
        for (String name : List.of("s".repeat(65), "s".repeat(256))) {
            String body = "body-" + name.length();
            SkillDescriptor written = source.write(tenant,
                    skill(name, "Boundary skill", body, Map.of()), 0);
            SkillDescriptor discovered = source.discover(tenant, null, 20).skills().stream()
                    .filter(candidate -> candidate.name().equals(name))
                    .findFirst().orElseThrow();

            assertEquals(name, written.name());
            assertEquals(body, read(tenant, discovered, "SKILL.md"));
        }

        String rejectedName = "s".repeat(257);
        assertThrows(IllegalArgumentException.class,
                () -> source.write(tenant, skill(rejectedName, "Too long", "not stored", Map.of()), 0));
        assertFalse(source.discover(tenant, null, 20).skills().stream()
                .anyMatch(candidate -> candidate.name().equals(rejectedName)));
    }

    /**
     * 验证目录修订嵌入游标，分页期间有并发写入时拒绝将两代目录拼成同一结果。
     */
    @Test
    void providesStableKeysetPagesAndRejectsChangedCatalogs() {
        SkillRequestContext tenant = tenant();
        source.write(tenant, skill("alpha", "Alpha.", "A", Map.of()), 0);
        source.write(tenant, skill("bravo", "Bravo.", "B", Map.of()), 0);
        source.write(tenant, skill("charlie", "Charlie.", "C", Map.of()), 0);

        SkillPage first = source.discover(tenant, null, 1);
        assertEquals("alpha", first.skills().getFirst().name());
        assertTrue(first.nextCursor() != null);
        SkillPage second = source.discover(tenant, first.nextCursor(), 1);
        assertEquals("bravo", second.skills().getFirst().name());
        SkillPage third = source.discover(tenant, second.nextCursor(), 1);
        assertEquals("charlie", third.skills().getFirst().name());
        assertNull(third.nextCursor());

        source.write(tenant, skill("delta", "Delta.", "D", Map.of()), 0);
        assertThrows(SkillCatalogChangedException.class,
                () -> source.discover(tenant, first.nextCursor(), 1));
    }

    /**
     * 让两个写入方基于同一版本竞争更新，确认只有一个提交成功，另一个收到版本冲突。
     */
    @Test
    void allowsOnlyOneConcurrentUpdateForTheSameVersion() throws Exception {
        SkillRequestContext tenant = tenant();
        SkillDescriptor initial = source.write(tenant, skill("race", "Initial.", "initial", Map.of()), 0);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService writers = Executors.newFixedThreadPool(2);
        try {
            Future<SkillDescriptor> left = writers.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("concurrent_skill_test_gate_timeout");
                }
                return source.write(tenant,
                        skill("race", "Writer A.", "body A", Map.of("writer-a.txt", bytes("A"))),
                        initial.version());
            });
            Future<SkillDescriptor> right = writers.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("concurrent_skill_test_gate_timeout");
                }
                return source.write(tenant,
                        skill("race", "Writer B.", "body B", Map.of("writer-b.txt", bytes("B"))),
                        initial.version());
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<SkillDescriptor> winners = new java.util.ArrayList<>(1);
            int conflicts = 0;
            for (Future<SkillDescriptor> update : List.of(left, right)) {
                try {
                    winners.add(update.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException failure) {
                    if (failure.getCause() instanceof SkillVersionConflictException) {
                        conflicts++;
                    } else {
                        throw failure;
                    }
                }
            }
            assertEquals(1, winners.size());
            assertEquals(1, conflicts);
            SkillDescriptor winner = winners.getFirst();
            boolean writerA = winner.description().equals("Writer A.");
            assertTrue(writerA || winner.description().equals("Writer B."));
            assertEquals(writerA ? "body A" : "body B", read(tenant, winner, "SKILL.md"));
            assertEquals(writerA ? "A" : "B", read(tenant, winner, writerA ? "writer-a.txt" : "writer-b.txt"));
            assertThrows(IllegalArgumentException.class,
                    () -> read(tenant, winner, writerA ? "writer-b.txt" : "writer-a.txt"));
        } finally {
            writers.shutdownNow();
        }
    }

    /**
     * 在数据库访问前拒绝路径穿越和保留正文路径，确保非法资源包无法进入持久层。
     */
    @Test
    void rejectsUnsafeResourcePathsBeforePersistence() {
        assertThrows(IllegalArgumentException.class,
                () -> new SkillReadRequest("safe", "notes/../../secret.txt", 100));
        assertThrows(IllegalArgumentException.class,
                () -> skill("safe", "Safe.", "body", Map.of("C:/secret.txt", bytes("secret"))));
        assertThrows(IllegalArgumentException.class,
                () -> skill("safe", "Safe.", "body", Map.of("SKILL.md", bytes("shadow body"))));
    }

    /**
     * 验证一个租户的写入不会改变其他租户的目录修订或使其分页游标失效。
     */
    @Test
    void keepsPaginationRevisionsTenantScoped() {
        SkillRequestContext tenantA = tenant();
        SkillRequestContext tenantB = tenant();
        source.write(tenantA, skill("alpha", "Alpha.", "A", Map.of()), 0);
        source.write(tenantA, skill("bravo", "Bravo.", "B", Map.of()), 0);
        source.write(tenantB, skill("other", "Other.", "O", Map.of()), 0);

        SkillPage first = source.discover(tenantA, null, 1);
        source.write(tenantB, skill("other-two", "Other two.", "O2", Map.of()), 0);

        SkillPage second = source.discover(tenantA, first.nextCursor(), 1);
        assertEquals("bravo", second.skills().getFirst().name());
    }

    /**
     * 在包头更新后注入资源插入失败，验证同一事务会恢复旧正文和旧资源集合。
     */
    @Test
    void rollsBackHeaderAndResourceReplacementTogether() throws Exception {
        SkillRequestContext tenant = tenant();
        SkillDescriptor old = source.write(tenant,
                skill("atomic", "Before.", "before body", Map.of("old.txt", bytes("old resource"))), 0);
        installFailingResourceTrigger();
        try {
            assertThrows(IllegalStateException.class, () -> source.write(tenant,
                    skill("atomic", "After.", "after body", Map.of("fail.txt", bytes("fail"))), old.version()));
        } finally {
            dropFailingResourceTrigger();
        }

        SkillDescriptor current = source.discover(tenant, null, 20).skills().stream()
                .filter(skill -> skill.name().equals("atomic")).findFirst().orElseThrow();
        assertEquals(old.version(), current.version());
        assertEquals("before body", read(tenant, current, "SKILL.md"));
        assertEquals("old resource", read(tenant, current, "old.txt"));
        assertThrows(IllegalArgumentException.class, () -> read(tenant, current, "fail.txt"));
    }

    /**
     * 回滚失败时保留原 SQL 错误并跳过 autocommit 恢复，避免连接状态切换隐式提交半成品。
     */
    @Test
    void doesNotResetAutoCommitWhenOwnedTransactionRollbackFails() throws Exception {
        SkillRequestContext tenant = tenant();
        SkillDescriptor old = source.write(tenant,
                skill("rollback-failure", "Before.", "before body", Map.of("old.txt", bytes("old resource"))), 0);
        installFailingResourceTrigger();
        try (Connection connection = dataSource.getConnection()) {
            ConnectionProbe probe = new ConnectionProbe(true, false, true);
            PostgresSkillSource failingSource = new PostgresSkillSource(probe.dataSource(connection), "pg-probe", 10);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> failingSource.write(tenant,
                            skill("rollback-failure", "After.", "after body", Map.of("fail.txt", bytes("fail"))),
                            old.version()));

            assertTrue(failure.getCause() instanceof SQLException);
            assertSame(probe.rollbackFailure, failure.getCause().getSuppressed()[0]);
            assertEquals(1, probe.fullRollbackCalls);
            assertEquals(0, probe.savepointRollbackCalls);
            assertEquals(0, probe.autoCommitTrueCalls);
        } finally {
            dropFailingResourceTrigger();
        }

        SkillDescriptor current = source.discover(tenant, null, 20).skills().stream()
                .filter(skill -> skill.name().equals("rollback-failure")).findFirst().orElseThrow();
        assertEquals(old.version(), current.version());
        assertEquals("before body", read(tenant, current, "SKILL.md"));
        assertEquals("old resource", read(tenant, current, "old.txt"));
    }

    /**
     * 回滚成功后若 autocommit 恢复失败，保留数据库错误为主因并将连接状态错误附加诊断。
     */
    @Test
    void preservesDatabaseFailureWhenAutoCommitRestoreFails() throws Exception {
        SkillRequestContext tenant = tenant();
        SkillDescriptor old = source.write(tenant,
                skill("restore-failure", "Before.", "before body", Map.of("old.txt", bytes("old resource"))), 0);
        installFailingResourceTrigger();
        try (Connection connection = dataSource.getConnection()) {
            ConnectionProbe probe = new ConnectionProbe(false, true, true);
            PostgresSkillSource failingSource = new PostgresSkillSource(probe.dataSource(connection), "pg-probe", 10);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> failingSource.write(tenant,
                            skill("restore-failure", "After.", "after body", Map.of("fail.txt", bytes("fail"))),
                            old.version()));

            assertTrue(failure.getCause() instanceof SQLException);
            assertSame(probe.restoreFailure, failure.getCause().getSuppressed()[0]);
            assertEquals(1, probe.fullRollbackCalls);
            assertEquals(0, probe.savepointRollbackCalls);
            assertEquals(1, probe.autoCommitTrueCalls);
        } finally {
            dropFailingResourceTrigger();
        }

        SkillDescriptor current = source.discover(tenant, null, 20).skills().stream()
                .filter(skill -> skill.name().equals("restore-failure")).findFirst().orElseThrow();
        assertEquals(old.version(), current.version());
        assertEquals("old resource", read(tenant, current, "old.txt"));
    }

    /**
     * 提交成功后恢复安装器开启的连接状态，数据库提交事实仍可由独立连接读取。
     */
    @Test
    void restoresAutoCommitAfterOwnedCommit() throws Exception {
        SkillRequestContext tenant = tenant();
        try (Connection connection = dataSource.getConnection()) {
            ConnectionProbe probe = new ConnectionProbe(false, false, true);
            PostgresSkillSource probedSource = new PostgresSkillSource(probe.dataSource(connection), "pg", 10);

            SkillDescriptor stored = probedSource.write(tenant,
                    skill("committed", "Committed.", "body", Map.of()), 0);

            assertEquals(1, probe.autoCommitTrueCalls);
            assertEquals("body", read(tenant, stored, "SKILL.md"));
        }
    }

    /**
     * 加入外层事务失败时只尝试保存点回滚，不做整笔回滚或 autocommit 切换，事务最终由调用方结束。
     */
    @Test
    void preservesCallerTransactionWhenSavepointRollbackFails() throws Exception {
        SkillRequestContext tenant = tenant();
        SkillDescriptor old = source.write(tenant,
                skill("outer-rollback-failure", "Before.", "before body", Map.of("old.txt", bytes("old resource"))), 0);
        installFailingResourceTrigger();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            ConnectionProbe probe = new ConnectionProbe(true, false, true);
            PostgresSkillSource failingSource = new PostgresSkillSource(probe.dataSource(connection), "pg-probe", 10);
            try {
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> failingSource.write(tenant,
                                skill("outer-rollback-failure", "After.", "after body",
                                        Map.of("fail.txt", bytes("fail"))), old.version()));

                assertTrue(failure.getCause() instanceof SQLException);
                assertSame(probe.rollbackFailure, failure.getCause().getSuppressed()[0]);
                assertEquals(0, probe.fullRollbackCalls);
                assertEquals(1, probe.savepointRollbackCalls);
                assertEquals(0, probe.autoCommitTrueCalls);
                assertFalse(connection.getAutoCommit());
            } finally {
                connection.rollback();
            }
        } finally {
            dropFailingResourceTrigger();
        }

        SkillDescriptor current = source.discover(tenant, null, 20).skills().stream()
                .filter(skill -> skill.name().equals("outer-rollback-failure")).findFirst().orElseThrow();
        assertEquals(old.version(), current.version());
        assertEquals("old resource", read(tenant, current, "old.txt"));
    }

    /**
     * Schema DDL 遇到 Error 时按保存点恢复，并保持外层事务可继续使用且仍由调用方控制。
     */
    @Test
    void schemaInstallerRollsBackErrorsToCallerSavepoint() throws Exception {
        DataSource isolated = isolatedSchemaDataSource();
        try (Connection connection = isolated.getConnection()) {
            connection.setAutoCommit(false);
            ConnectionProbe probe = new ConnectionProbe(false, false, true);
            probe.failStatementExecutionAt = 2;
            assertSame(probe.schemaFailure,
                    assertThrows(AssertionError.class,
                            () -> PostgresSkillSchema.install(probe.dataSource(connection))));

            assertEquals(0, probe.fullRollbackCalls);
            assertEquals(1, probe.savepointRollbackCalls);
            assertEquals(0, probe.autoCommitTrueCalls);
            assertFalse(connection.getAutoCommit());
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT 1")) {
                assertTrue(rows.next());
            }
            connection.rollback();
        }
    }

    /**
     * 确认新基线的单数表名和显式主键名可重复安装，数据库操作只落在一次性嵌入实例。
     */
    @Test
    void schemaInstallerIsIdempotent() throws Exception {
        PostgresSkillSchema.install(dataSource);
        PostgresSkillSchema.install(dataSource);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT to_regclass('agent_skill_package') IS NOT NULL
                         AND to_regclass('agent_skill_module_version') IS NOT NULL
                         AND to_regclass('agent_skill_resource') IS NOT NULL
                         AND to_regclass('agent_skill_catalog_revision') IS NOT NULL
                         AND (SELECT schema_version FROM agent_skill_module_version
                              WHERE module_name = 'ja-agent-skills-pg') = 1
                         AND EXISTS (SELECT 1 FROM pg_constraint
                             WHERE conrelid = 'agent_skill_package'::regclass
                                 AND contype = 'p' AND conname = 'pk_agent_skill_package')
                         AND EXISTS (SELECT 1 FROM pg_constraint
                             WHERE conrelid = 'agent_skill_resource'::regclass
                                 AND contype = 'p' AND conname = 'pk_agent_skill_resource')
                         AND EXISTS (SELECT 1 FROM pg_constraint
                             WHERE conrelid = 'agent_skill_catalog_revision'::regclass
                                 AND contype = 'p' AND conname = 'pk_agent_skill_catalog_revision')
                     """)) {
            assertTrue(rows.next() && rows.getBoolean(1));
        }
    }

    /**
     * 来源构造不隐式创建 schema；空库要求显式安装，部分旧结构则拒绝且不补齐缺失对象。
     */
    @Test
    void sourceRequiresExplicitCompleteSchema() throws Exception {
        DataSource empty = isolatedSchemaDataSource();
        IllegalStateException uninitialized = assertThrows(IllegalStateException.class,
                () -> new PostgresSkillSource(empty, "pg-empty", 10));
        assertEquals("postgres_skill_schema_not_initialized", uninitialized.getMessage());

        DataSource partial = isolatedSchemaDataSource();
        try (Connection connection = partial.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE agent_skill_package (tenant_id text)");
        }
        IllegalStateException mismatch = assertThrows(IllegalStateException.class,
                () -> PostgresSkillSchema.install(partial));
        assertEquals("postgres_skill_schema_mismatch", mismatch.getMessage());
        assertEquals("postgres_skill_schema_mismatch", assertThrows(IllegalStateException.class,
                () -> new PostgresSkillSource(partial, "pg-partial", 10)).getMessage());
        try (Connection connection = partial.getConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT to_regclass('agent_skill_resource') IS NULL")) {
            assertTrue(rows.next() && rows.getBoolean(1));
        }
    }

    /**
     * 前缀属于本模块命名空间；未知/遗留对象既阻止初始化，也必须原样保留供调用方自行处置。
     */
    @Test
    void installerRejectsAndPreservesUnknownModuleRelations() throws Exception {
        DataSource isolated = isolatedSchemaDataSource();
        execute(isolated, "CREATE TABLE agent_skill_legacy (legacy_id integer NOT NULL)");

        assertSchemaMismatch(isolated);
        try (Connection connection = isolated.getConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT to_regclass('agent_skill_legacy') IS NOT NULL
                        AND to_regclass('agent_skill_package') IS NULL
                     """)) {
            assertTrue(rows.next() && rows.getBoolean(1));
        }
    }

    /**
     * 列、约束、索引及版本任何一项漂移都 fail-closed；安装器不能借幂等重跑暗中修补已有结构。
     */
    @Test
    void installerRejectsSchemaDriftWithoutRepair() throws Exception {
        DataSource columnDrift = isolatedSchemaDataSource();
        PostgresSkillSchema.install(columnDrift);
        execute(columnDrift, "ALTER TABLE agent_skill_package ALTER COLUMN skill_name TYPE varchar(255)");
        assertSchemaMismatch(columnDrift);
        try (Connection connection = columnDrift.getConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT character_maximum_length FROM information_schema.columns
                     WHERE table_schema = current_schema() AND table_name = 'agent_skill_package'
                       AND column_name = 'skill_name'
                     """)) {
            assertTrue(rows.next());
            assertEquals(255, rows.getInt(1));
        }

        DataSource domainDrift = isolatedSchemaDataSource();
        PostgresSkillSchema.install(domainDrift);
        execute(domainDrift, "CREATE DOMAIN skill_tenant_id AS text");
        execute(domainDrift,
                "ALTER TABLE agent_skill_catalog_revision ALTER COLUMN tenant_id TYPE skill_tenant_id");
        assertSchemaMismatch(domainDrift);

        DataSource collationDrift = isolatedSchemaDataSource();
        PostgresSkillSchema.install(collationDrift);
        execute(collationDrift,
                "ALTER TABLE agent_skill_catalog_revision ALTER COLUMN tenant_id TYPE text COLLATE \"C\"");
        assertSchemaMismatch(collationDrift);

        DataSource constraintDrift = isolatedSchemaDataSource();
        PostgresSkillSchema.install(constraintDrift);
        execute(constraintDrift,
                "ALTER TABLE agent_skill_package DROP CONSTRAINT ck_agent_skill_package_version_positive");
        assertSchemaMismatch(constraintDrift);

        DataSource indexDrift = isolatedSchemaDataSource();
        PostgresSkillSchema.install(indexDrift);
        execute(indexDrift, "CREATE INDEX ix_unmanaged_skill_description ON agent_skill_package(description)");
        assertSchemaMismatch(indexDrift);

        DataSource updateActionDrift = isolatedSchemaDataSource();
        PostgresSkillSchema.install(updateActionDrift);
        execute(updateActionDrift, "ALTER TABLE agent_skill_resource "
                + "DROP CONSTRAINT fk_agent_skill_resource_package");
        execute(updateActionDrift, "ALTER TABLE agent_skill_resource "
                + "ADD CONSTRAINT fk_agent_skill_resource_package FOREIGN KEY (tenant_id, skill_name) "
                + "REFERENCES agent_skill_package (tenant_id, skill_name) "
                + "ON UPDATE CASCADE ON DELETE CASCADE");
        assertSchemaMismatch(updateActionDrift);

        DataSource matchTypeDrift = isolatedSchemaDataSource();
        PostgresSkillSchema.install(matchTypeDrift);
        execute(matchTypeDrift, "ALTER TABLE agent_skill_resource "
                + "DROP CONSTRAINT fk_agent_skill_resource_package");
        execute(matchTypeDrift, "ALTER TABLE agent_skill_resource "
                + "ADD CONSTRAINT fk_agent_skill_resource_package FOREIGN KEY (tenant_id, skill_name) "
                + "REFERENCES agent_skill_package (tenant_id, skill_name) MATCH FULL "
                + "ON DELETE CASCADE");
        assertSchemaMismatch(matchTypeDrift);

        DataSource versionDrift = isolatedSchemaDataSource();
        PostgresSkillSchema.install(versionDrift);
        execute(versionDrift,
                "UPDATE agent_skill_module_version SET schema_version = 2 WHERE module_name = 'ja-agent-skills-pg'");
        IllegalStateException unsupported = assertThrows(IllegalStateException.class,
                () -> PostgresSkillSchema.install(versionDrift));
        assertEquals("postgres_skill_schema_version_unsupported", unsupported.getMessage());
    }

    /**
     * 两个并发空库启动由事务级 advisory lock 排队，最终仅留下一个完整且可验证的 schema。
     */
    @Test
    void concurrentInstallersInitializeOnce() throws Exception {
        DataSource isolated = isolatedSchemaDataSource();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> {
                start.await();
                PostgresSkillSchema.install(isolated);
                return null;
            });
            Future<?> second = executor.submit(() -> {
                start.await();
                PostgresSkillSchema.install(isolated);
                return null;
            });
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        }
        new PostgresSkillSource(isolated, "pg-concurrent", 10);
    }

    /** 为隔离测试 schema 执行一条 DDL/DML，确保每种漂移只影响临时嵌入数据库。 */
    private static void execute(DataSource isolated, String sql) throws SQLException {
        try (Connection connection = isolated.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** 断言结构错误保持稳定错误码，避免把某个检测查询的驱动细节变成模块公共契约。 */
    private static void assertSchemaMismatch(DataSource isolated) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PostgresSkillSchema.install(isolated));
        assertEquals("postgres_skill_schema_mismatch", failure.getMessage());
    }

    /**
     * 为 schema 契约测试创建随机命名空间并固定每条借用连接的 search_path，不触碰开发者数据库。
     */
    private static DataSource isolatedSchemaDataSource() throws SQLException {
        String schema = "ja_skill_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + schema + "\"");
        }
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getConnection") && (arguments == null || arguments.length == 0)) {
                        Connection connection = dataSource.getConnection();
                        try (Statement statement = connection.createStatement()) {
                            statement.execute("SET search_path TO \"" + schema + "\"");
                            return connection;
                        } catch (SQLException | RuntimeException | Error failure) {
                            try {
                                connection.close();
                            } catch (SQLException closeFailure) {
                                failure.addSuppressed(closeFailure);
                            }
                            throw failure;
                        }
                    }
                    try {
                        return method.invoke(dataSource, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    /**
     * 为每个测试生成独立租户，避免测试并行或残留包影响彼此断言。
     */
    private static SkillRequestContext tenant() {
        return new SkillRequestContext("tenant-" + UUID.randomUUID());
    }

    /**
     * 构造不可变包夹具，并分开设置目录元数据、模型正文与二进制资源。
     */
    private static PostgresSkillSource.SkillPackage skill(String name, String description, String body,
                                                          Map<String, byte[]> resources) {
        return new PostgresSkillSource.SkillPackage(name, description, body, resources);
    }

    /**
     * 使用已发现的包版本读取资源，验证数据读取不能只凭租户内名称绕过版本绑定。
     */
    private static String read(SkillRequestContext tenant, SkillDescriptor descriptor, String path) {
        return source.read(tenant, descriptor, new SkillReadRequest(descriptor.name(), path, 10_000)).content();
    }

    /**
     * 在包校验前将夹具文本固定编码成 UTF-8 字节，避免平台默认编码影响结果。
     */
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 安装一次性失败触发器，在包头已变更后迫使资源批次报错以检验回滚闭环。
     */
    private static void installFailingResourceTrigger() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE OR REPLACE FUNCTION reject_test_skill_resource() RETURNS trigger \
                LANGUAGE plpgsql AS $$ BEGIN IF NEW.resource_path = 'fail.txt' THEN \
                RAISE EXCEPTION 'injected skill resource failure'; END IF; RETURN NEW; END $$\
                """);
            statement.execute("""
                CREATE TRIGGER reject_test_skill_resource BEFORE INSERT ON agent_skill_resource \
                FOR EACH ROW EXECUTE FUNCTION reject_test_skill_resource()\
                """);
        }
    }

    /**
     * 无论原子性断言是否通过都清除故障触发器，避免污染同一个临时数据库中的后续用例。
     */
    private static void dropFailingResourceTrigger() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS reject_test_skill_resource ON agent_skill_resource");
            statement.execute("DROP FUNCTION IF EXISTS reject_test_skill_resource()");
        }
    }

    /**
     * 仅向一次测试调用注入 JDBC 故障，真实连接仍负责事务与 SQL 语义，不伪造 PostgreSQL 结果。
     */
    private static final class ConnectionProbe {
        private final boolean failRollback;
        private final boolean failAutoCommitRestore;
        private final boolean keepConnectionOpen;
        private final SQLException rollbackFailure = new SQLException("injected rollback failure", "XX000");
        private final SQLException restoreFailure = new SQLException("injected autocommit restore failure", "08006");
        private final AssertionError schemaFailure = new AssertionError("injected schema failure");
        private int fullRollbackCalls;
        private int savepointRollbackCalls;
        private int autoCommitTrueCalls;
        private int statementExecuteCalls;
        private int failStatementExecutionAt = -1;

        /**
         * 固定一次测试的故障点，避免共享开关让并行用例互相影响。
         */
        private ConnectionProbe(boolean failRollback, boolean failAutoCommitRestore, boolean keepConnectionOpen) {
            this.failRollback = failRollback;
            this.failAutoCommitRestore = failAutoCommitRestore;
            this.keepConnectionOpen = keepConnectionOpen;
        }

        /**
         * 只替换 DataSource 的 getConnection 结果，其余事务操作都落到给定真实连接。
         */
        private DataSource dataSource(Connection connection) {
            Connection wrapped = connection(connection);
            return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                    new Class<?>[]{DataSource.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("getConnection")) {
                            return wrapped;
                        }
                        throw new UnsupportedOperationException("unexpected DataSource method: " + method.getName());
                    });
        }

        /**
         * 只拦截事务回滚、状态恢复和关闭；其他 Connection 行为由 PostgreSQL 驱动执行。
         */
        private Connection connection(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                        String name = method.getName();
                        if (name.equals("rollback")) {
                            if (arguments == null) {
                                fullRollbackCalls++;
                            } else {
                                savepointRollbackCalls++;
                            }
                            if (failRollback) {
                                throw rollbackFailure;
                            }
                        }
                        if (name.equals("setAutoCommit") && Boolean.TRUE.equals(arguments[0])) {
                            autoCommitTrueCalls++;
                            if (failAutoCommitRestore) {
                                throw restoreFailure;
                            }
                        }
                        if (name.equals("createStatement")) {
                            Statement statement = (Statement) invoke(method, connection, arguments);
                            return statement(statement);
                        }
                        if (name.equals("close") && keepConnectionOpen) {
                            return null;
                        }
                        return invoke(method, connection, arguments);
                    });
        }

        /**
         * 在指定 DDL 调用前注入 Error，以验证 Schema 安装器仍会回滚保存点并保留原错误。
         */
        private Statement statement(Statement statement) {
            return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(),
                    new Class<?>[]{Statement.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("execute")) {
                            statementExecuteCalls++;
                            if (statementExecuteCalls == failStatementExecutionAt) {
                                throw schemaFailure;
                            }
                        }
                        return invoke(method, statement, arguments);
                    });
        }

        /**
         * 解除反射包装，使代理继续抛出 JDBC 的原始 SQLException、RuntimeException 或 Error。
         */
        private static Object invoke(Method method, Object target, Object[] arguments) throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }
}
