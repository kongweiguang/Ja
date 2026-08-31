// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import io.github.kongweiguang.ja.foundation.error.StorageException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.resource.LoadableResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collection;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 SQLite schema 升级的备份、检查点、回读、崩溃恢复与失败关闭。 */
final class DatabaseMigrationRecoveryTest {
    @TempDir Path temp;

    /** V2 升级成功后保留源 schema 备份，并且 APPLIED marker 与磁盘 V6 回读一致。 */
    @Test
    void upgradesWithRecoverableBackupAndAppliedCheckpoint() throws Exception {
        Path database = temp.resolve("success").resolve("ja.db");
        SQLiteDataSource source = source(database);
        flywayThrough(source, "V1__kernel.sql", "V2__thread_runtime_preferences.sql").migrate();
        insertWorkspace(source, "ws_backup");

        recovery(database, source).migrate(flyway(source));

        assertEquals("6", schemaVersion(source));
        Path marker = markerPath(database);
        String checkpoint = Files.readString(marker, StandardCharsets.UTF_8);
        assertTrue(checkpoint.contains("state=applied\n"));
        assertTrue(checkpoint.contains("source_version=2\n"));
        assertTrue(checkpoint.contains("target_version=6\n"));
        Path backup = backupPath(database, "2", "6");
        assertTrue(Files.isRegularFile(backup));
        assertEquals("2", schemaVersion(source(backup)));
        assertEquals(1, rowCount(source(backup), "workspaces"));

        recovery(database, source).migrate(flyway(source));
        assertEquals(checkpoint, Files.readString(marker, StandardCharsets.UTF_8));
    }

    /**
     * 目标回读失败必须恢复 V2 并留下 PREPARED；下一次启动先恢复备份，因此人为模拟的
     * 半迁移表不会进入随后成功的 V6。
     */
    @Test
    void restoresPreparedCheckpointBeforeRetryingMigration() throws Exception {
        Path database = temp.resolve("retry").resolve("ja.db");
        SQLiteDataSource source = source(database);
        Flyway v2Only = flywayThrough(source,
                "V1__kernel.sql", "V2__thread_runtime_preferences.sql");
        v2Only.migrate();
        DatabaseMigrationRecovery recovery = recovery(database, source);

        StorageException failedReadback = assertThrows(StorageException.class,
                () -> recovery.migrate(v2Only));
        assertEquals(StorageException.Code.TRANSACTION, failedReadback.code());
        assertEquals("2", schemaVersion(source));
        assertTrue(Files.readString(markerPath(database), StandardCharsets.UTF_8)
                .contains("state=prepared\n"));

        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE interrupted_migration(value TEXT)");
        }
        assertTrue(tableExists(source, "interrupted_migration"));

        recovery.migrate(flyway(source));

        assertEquals("6", schemaVersion(source));
        assertFalse(tableExists(source, "interrupted_migration"));
        assertTrue(Files.readString(markerPath(database), StandardCharsets.UTF_8)
                .contains("state=applied\n"));
    }

    /** 损坏 checkpoint 时不得触碰原数据库或用 Flyway 猜测继续。 */
    @Test
    void rejectsCorruptedCheckpointWithoutMigratingDatabase() throws Exception {
        Path database = temp.resolve("corrupt-marker").resolve("ja.db");
        SQLiteDataSource source = source(database);
        flywayThrough(source, "V1__kernel.sql", "V2__thread_runtime_preferences.sql").migrate();
        Files.writeString(markerPath(database), "state=prepared\n", StandardCharsets.UTF_8);

        StorageException failure = assertThrows(StorageException.class,
                () -> recovery(database, source).migrate(flyway(source)));

        assertEquals(StorageException.Code.STORAGE_CONFLICT, failure.code());
        assertEquals("2", schemaVersion(source));
        assertFalse(tableExists(source, "attachment_blobs"));
    }

    /** 测试恢复器与生产固定目标版本同源，避免 fixture 写死另一个迁移代际。 */
    private static DatabaseMigrationRecovery recovery(Path database, SQLiteDataSource source) {
        return new DatabaseMigrationRecovery(database, source, JaFlywayResources.latestVersion());
    }

    /** 使用生产完整资源闭集创建正常 Flyway runner。 */
    private static Flyway flyway(SQLiteDataSource source) {
        return Flyway.configure().dataSource(source).locations(new String[0])
                .resourceProvider(JaFlywayResources.provider()).baselineOnMigrate(false)
                .validateMigrationNaming(true).load();
    }

    /**
     * 只选择正式 migration 的前缀来构造真实旧 schema；不手写历史 DDL，也不向生产资源集
     * 增加测试 migration。
     */
    private static Flyway flywayThrough(SQLiteDataSource source, String... filenames) {
        ResourceProvider all = JaFlywayResources.provider();
        Set<String> allowed = Set.of(filenames);
        ResourceProvider selected = new ResourceProvider() {
            /** 精确转发选中资源，保持 Flyway 单资源解析行为。 */
            @Override
            public LoadableResource getResource(String name) {
                LoadableResource resource = all.getResource(name);
                return resource != null && allowed.contains(resource.getFilename()) ? resource : null;
            }

            /** scanner 只看到测试声明的生产 migration 前缀。 */
            @Override
            public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
                return all.getResources(prefix, suffixes).stream()
                        .filter(resource -> allowed.contains(resource.getFilename())).toList();
            }
        };
        return Flyway.configure().dataSource(source).locations(new String[0])
                .resourceProvider(selected).baselineOnMigrate(false)
                .validateMigrationNaming(true).load();
    }

    /** datasource 使用与生产一致的 WAL/FULL durability，确保 backup 覆盖未 checkpoint 的页面。 */
    private static SQLiteDataSource source(Path database) throws Exception {
        Files.createDirectories(database.getParent());
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + database);
        return source;
    }

    /** 从 Flyway history 回读实际最后版本，不复用恢复器私有判断。 */
    private static String schemaVersion(SQLiteDataSource source) throws Exception {
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT version FROM flyway_schema_history WHERE success=1 "
                             + "ORDER BY installed_rank DESC LIMIT 1")) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    /** sqlite_master 用参数绑定读取表存在性，避免测试断言依赖异常文本。 */
    private static boolean tableExists(SQLiteDataSource source, String table) throws Exception {
        try (Connection connection = source.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /** 升级前写入一条真实历史事实，证明 backup 不只是复制空 schema。 */
    private static void insertWorkspace(SQLiteDataSource source, String workspaceId) throws Exception {
        try (Connection connection = source.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO workspaces(workspace_id,root_path,display_name,trust,revision,"
                             + "created_at,updated_at) VALUES (?,?,?,?,?,?,?)")) {
            statement.setString(1, workspaceId);
            statement.setString(2, "C:/migration-fixture");
            statement.setString(3, "Migration fixture");
            statement.setString(4, "TRUSTED");
            statement.setLong(5, 0);
            statement.setString(6, "2026-08-30T00:00:00Z");
            statement.setString(7, "2026-08-30T00:00:00Z");
            assertEquals(1, statement.executeUpdate());
        }
    }

    /** 使用受控表名读取恢复副本的事实数量；调用点只传固定 schema 名。 */
    private static int rowCount(SQLiteDataSource source, String table) throws Exception {
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    /** marker 文件名只由生产公开 suffix 派生，测试不读取绝对路径内容。 */
    private static Path markerPath(Path database) {
        return database.resolveSibling(database.getFileName() + DatabaseMigrationRecovery.MARKER_SUFFIX);
    }

    /** 版本化 backup 文件名与生产恢复身份一致。 */
    private static Path backupPath(Path database, String sourceVersion, String targetVersion) {
        return database.resolveSibling(database.getFileName() + ".migration-v" + sourceVersion
                + "-to-v" + targetVersion + ".backup");
    }
}
