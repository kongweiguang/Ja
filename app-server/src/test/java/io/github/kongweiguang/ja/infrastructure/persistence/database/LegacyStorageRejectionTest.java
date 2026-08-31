// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import io.github.kongweiguang.ja.foundation.error.StorageException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 证明只接受新代际的 runtime 会原样保留并拒绝不兼容旧数据库。 */
final class LegacyStorageRejectionTest {
    @TempDir Path temp;

    /** 旧文件保持不变并阻断启动，不会被复制、备份或重写。 */
    @Test
    void rejectsLegacyDatabaseWithoutCreatingFreshV1() throws Exception {
        Path database = temp.resolve("ja.db");
        createLegacyDatabase(database);
        Path backupRoot = temp.resolve("home").resolve(".ja").resolve("backups");

        assertThrows(StorageException.class, () -> JaDatabase.open(DatabaseConfig.of(database)));
        assertTrue(Files.notExists(backupRoot));
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            assertTrue(columnNames(connection, "threads").contains("profile_revision"));
            assertFalse(columnNames(connection, "threads").contains("profile_id"));
        }
    }

    /** 创建基线前结构，用于证明 runtime 不读取或重写旧数据。 */
    private static void createLegacyDatabase(Path database) throws Exception {
        Files.createDirectories(database.getParent());
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE workspaces (workspace_id TEXT PRIMARY KEY NOT NULL, "
                    + "root_path TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL, "
                    + "trust TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE threads (thread_id TEXT PRIMARY KEY NOT NULL, "
                    + "workspace_id TEXT NOT NULL, title TEXT NOT NULL, profile_revision TEXT NOT NULL, "
                    + "created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
        }
    }

    /** 读取 SQLite 权威列清单，避免依赖 Mapper 对旧 schema 的解释。 */
    private static Set<String> columnNames(java.sql.Connection connection, String table) throws Exception {
        Set<String> columns = new java.util.HashSet<>();
        try (java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                columns.add(rows.getString("name"));
            }
        }
        return columns;
    }
}
