// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SchemaMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.resource.LoadableResource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 验证已有 V1 Thread 经过 V2 后得到一次性默认子智能体快照，重复迁移不会覆盖。 */
final class SubagentPolicyMigrationTest {
    @TempDir
    Path temp;

    /** V1 已有 Thread 迁移为开启/跟随父任务，重复执行 V2 保持唯一行并通过生产启动校验。 */
    @Test
    void migratesExistingThreadsOnceAndReopensWithTheSameSnapshot() throws Exception {
        Path databasePath = temp.resolve("v1-existing-thread").resolve("ja.db");
        Files.createDirectories(databasePath.getParent());
        SQLiteDataSource source = source(databasePath);
        String v1 = resource("db/migration/V1__kernel.sql");
        String v2 = resource("db/migration/V2__thread_subagent_policies.sql");
        String v3 = resource("db/migration/V3__subagent_reasoning.sql");
        migrate(source, List.of(new StringResource("V1__kernel.sql", v1)));
        try (java.sql.Connection connection = source.getConnection();
             java.sql.PreparedStatement workspace = connection.prepareStatement(
                     "INSERT INTO workspaces(workspace_id,root_path,display_name,trust,created_at,updated_at) "
                             + "VALUES(?,?,?,?,?,?)");
             java.sql.PreparedStatement thread = connection.prepareStatement(
                     "INSERT INTO threads(thread_id,workspace_id,title,created_at,updated_at,provider_id,model_id,"
                             + "access_mode,collaboration_mode,title_source) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            connection.setAutoCommit(false);
            workspace.setString(1, "ws_migration");
            workspace.setString(2, "C:/ja-migration");
            workspace.setString(3, "migration");
            workspace.setString(4, "TRUSTED");
            workspace.setString(5, "2026-09-10T00:00:00Z");
            workspace.setString(6, "2026-09-10T00:00:00Z");
            workspace.executeUpdate();
            thread.setString(1, "thr_existing");
            thread.setString(2, "ws_migration");
            thread.setString(3, "existing");
            thread.setString(4, "2026-09-10T00:00:00Z");
            thread.setString(5, "2026-09-10T00:00:00Z");
            thread.setString(6, "provider_fixture");
            thread.setString(7, "model_fixture");
            thread.setString(8, "APPROVAL_REQUIRED");
            thread.setString(9, "DEFAULT");
            thread.setString(10, "MANUAL");
            thread.executeUpdate();
            connection.commit();
        }

        migrate(source, List.of(new StringResource("V1__kernel.sql", v1),
                new StringResource("V2__thread_subagent_policies.sql", v2),
                new StringResource("V3__subagent_reasoning.sql", v3)));
        migrate(source, List.of(new StringResource("V1__kernel.sql", v1),
                new StringResource("V2__thread_subagent_policies.sql", v2),
                new StringResource("V3__subagent_reasoning.sql", v3)));

        try (java.sql.Connection connection = source.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            assertEquals(1, number(statement, "SELECT COUNT(*) FROM thread_subagent_policies"));
            assertEquals(1, number(statement, "SELECT enabled FROM thread_subagent_policies "
                    + "WHERE thread_id='thr_existing'"));
            assertEquals(0, number(statement, "SELECT COUNT(*) FROM thread_subagent_policies "
                    + "WHERE thread_id='thr_existing' AND (provider_id IS NOT NULL OR model_id IS NOT NULL)"));
        }

        try (JaDatabase database = JaDatabase.open(DatabaseConfig.of(databasePath))) {
            org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration(
                    new Environment("migration-test", new JdbcTransactionFactory(), database.dataSource()));
            configuration.addMapper(SchemaMapper.class);
            database.bindWalCheckpoint(new SqlSessionFactoryBuilder().build(configuration));
            assertNotNull(database.dataSource());
        }

        try (java.sql.Connection connection = source.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            assertEquals(1, number(statement, "SELECT COUNT(*) FROM pragma_table_info('thread_subagent_policies') "
                    + "WHERE name='reasoning_level'"));
            assertEquals(1, number(statement, "SELECT COUNT(*) FROM thread_subagent_policies "
                    + "WHERE thread_id='thr_existing' AND reasoning_level IS NULL"));
        }

        try (java.sql.Connection connection = source.getConnection();
             java.sql.PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM threads WHERE thread_id=?");
             java.sql.PreparedStatement policyCount = connection.prepareStatement(
                     "SELECT COUNT(*) FROM thread_subagent_policies WHERE thread_id=?")) {
            connection.setAutoCommit(false);
            delete.setString(1, "thr_existing");
            assertEquals(1, delete.executeUpdate());
            connection.commit();
            policyCount.setString(1, "thr_existing");
            try (java.sql.ResultSet result = policyCount.executeQuery()) {
                assertEquals(0, result.next() ? result.getLong(1) : -1);
            }
        }
    }

    /** 构造与生产迁移相同的 SQLite 外壳，但测试不接触用户目录。 */
    private static SQLiteDataSource source(Path databasePath) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + databasePath);
        return source;
    }

    /** 用受控单文件资源执行指定 Flyway 版本，真实模拟 V1 已安装后再执行 V2。 */
    private static void migrate(SQLiteDataSource source, List<StringResource> resources) {
        Flyway.configure().dataSource(source).locations(new String[0])
                .resourceProvider(new MigrationResourceProvider(resources))
                .baselineOnMigrate(false).ignoreMigrationPatterns(new String[0])
                .validateMigrationNaming(true).load().migrate();
    }

    /** 读取仓库内迁移资源，测试只验证真实 SQL，不复制或重写 schema。 */
    private static String resource(String path) throws Exception {
        try (InputStream input = SubagentPolicyMigrationTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            if (input == null) throw new AssertionError("migration resource is missing: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 读取单值 SQL 结果，保持断言与 SQLite 类型转换集中。 */
    private static long number(java.sql.Statement statement, String sql) throws Exception {
        try (java.sql.ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new AssertionError("missing result: " + sql);
            return result.getLong(1);
        }
    }

    /** Flyway 只看到测试指定的一个版本，避免迁移前置条件被生产资源列表隐藏。 */
    private record MigrationResourceProvider(List<StringResource> resources) implements ResourceProvider {
        /** 按精确文件名返回资源，阻止测试意外执行其它版本。 */
        @Override
        public LoadableResource getResource(String name) {
            if (name == null) return null;
            String normalized = name.replace('\\', '/');
            return resources.stream().filter(resource -> normalized.endsWith(resource.getFilename()))
                    .findFirst().orElse(null);
        }

        /** 返回完整历史资源集，使 Flyway 能校验 V1 后再执行 V2。 */
        @Override
        public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
            return List.copyOf(resources);
        }
    }

    /** 将真实迁移字节包装为 Flyway 可重复读取的稳定资源身份。 */
    private static final class StringResource extends LoadableResource {
        private final String filename;
        private final String sql;

        /** 固定文件名与内容，使 checksum 和执行阶段读取同一份数据。 */
        private StringResource(String filename, String sql) {
            this.filename = filename;
            this.sql = sql;
        }

        /** 返回受控版本文件名。 */
        @Override public String getFilename() { return filename; }

        /** 返回稳定 classpath 诊断路径。 */
        @Override public String getAbsolutePath() { return "classpath:test/" + filename; }

        /** 内存资源没有磁盘路径，沿用稳定逻辑路径。 */
        @Override public String getAbsolutePathOnDisk() { return getAbsolutePath(); }

        /** 返回 Flyway 需要的相对迁移名称。 */
        @Override public String getRelativePath() { return filename; }

        /** 每次读取都创建新 Reader，支持 checksum 与执行阶段重复读取。 */
        @Override public Reader read() {
            return new InputStreamReader(new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8);
        }
    }
}
