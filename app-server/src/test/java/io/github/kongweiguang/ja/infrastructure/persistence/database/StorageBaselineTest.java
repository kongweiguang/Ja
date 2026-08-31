// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SchemaMapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证全新存储代际标记的准入、原子发布与旧目录拒绝规则。 */
final class StorageBaselineTest {
    @TempDir Path temp;

    /** 空数据目录完成 V1 后必须生成严格 marker，并允许同版本重新打开。 */
    @Test
    void publishesMarkerAfterFreshMigrationAndReopensMatchingGeneration() throws Exception {
        Path databasePath = temp.resolve("data").resolve("ja.db");
        try (JaDatabase database = openForTest(databasePath)) {
            Path marker = databasePath.getParent().resolve(StorageBaseline.MARKER_FILE);
            assertTrue(Files.isRegularFile(marker));
            byte[] markerBytes = Files.readAllBytes(marker);
            assertEquals('\n', markerBytes[markerBytes.length - 1]);
            assertFalse(new String(markerBytes, StandardCharsets.UTF_8).contains("\r"));
            assertArrayEquals(packagedMarker(), markerBytes);
            try (java.util.stream.Stream<Path> files = Files.list(databasePath.getParent())) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString()
                        .startsWith(".storage-baseline-")));
            }
        }

        try (JaDatabase ignored = openForTest(databasePath)) {
            assertTrue(Files.isRegularFile(databasePath));
        }
    }

    /** 非空目录缺失 marker 时必须在创建 SQLite 文件前拒绝，不读取旧内容。 */
    @Test
    void rejectsNonEmptyDirectoryWithoutMarkerBeforeDatabaseCreation() throws Exception {
        Path directory = temp.resolve("unmarked");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("legacy.bin"), "legacy", StandardCharsets.UTF_8);
        Path databasePath = directory.resolve("ja.db");

        StorageException failure = assertThrows(StorageException.class,
                () -> JaDatabase.open(DatabaseConfig.of(databasePath)));

        assertEquals(StorageException.Code.FRESH_SCHEMA_REQUIRED, failure.code());
        assertFalse(Files.exists(databasePath));
        assertFalse(Files.exists(databasePath.resolveSibling("ja.db.lock")));
    }

    /** marker 字节或 V1 摘要不匹配时拒绝启动，原数据库保持不变。 */
    @Test
    void rejectsMismatchedMarkerWithoutTouchingDatabase() throws Exception {
        Path databasePath = temp.resolve("mismatch").resolve("ja.db");
        try (JaDatabase ignored = openForTest(databasePath)) {
            // 首次打开负责创建有效 V1 和 marker。
        }
        long databaseSize = Files.size(databasePath);
        Path marker = databasePath.getParent().resolve(StorageBaseline.MARKER_FILE);
        Files.writeString(marker, "{\"generation\":2}\n", StandardCharsets.UTF_8);

        StorageException failure = assertThrows(StorageException.class,
                () -> JaDatabase.open(DatabaseConfig.of(databasePath)));

        assertEquals(StorageException.Code.STORAGE_CONFLICT, failure.code());
        assertEquals(databaseSize, Files.size(databasePath));
    }

    /** 初始化失败且未完成准入时不发布 marker，残缺目录下次启动必须继续被拒绝。 */
    @Test
    void doesNotPublishMarkerWhenInitializationStopsBeforeCompletion() throws Exception {
        Path directory = temp.resolve("failed");
        Files.createDirectories(directory);
        Path databasePath = directory.resolve("ja.db");
        StorageBaseline.Admission baseline = StorageBaseline.prepare(databasePath);
        Files.writeString(databasePath, "not-a-sqlite-database", StandardCharsets.UTF_8);

        assertThrows(Exception.class, () -> {
            try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
                 java.sql.Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE impossible (value TEXT)");
                baseline.complete();
            }
        });
        assertFalse(Files.exists(directory.resolve(StorageBaseline.MARKER_FILE)));
        StorageException retry = assertThrows(StorageException.class,
                () -> JaDatabase.open(DatabaseConfig.of(databasePath)));
        assertEquals(StorageException.Code.FRESH_SCHEMA_REQUIRED, retry.code());
    }

    /** 与生产准入读取同一打包 marker，避免测试复制随唯一 V1 内容变化的派生摘要。 */
    private static byte[] packagedMarker() throws Exception {
        try (java.io.InputStream input = StorageBaselineTest.class.getClassLoader()
                .getResourceAsStream("db/storage-baseline.json")) {
            if (input == null) throw new AssertionError("packaged storage baseline is missing");
            return input.readAllBytes();
        }
    }

    /** 测试 close 仍走生产 WAL checkpoint 边界，不引入第二个数据库打开实现。 */
    private static JaDatabase openForTest(Path databasePath) {
        JaDatabase database = JaDatabase.open(DatabaseConfig.of(databasePath));
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration(
                new org.apache.ibatis.mapping.Environment(
                "baseline-test", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),
                database.dataSource()));
        configuration.addMapper(SchemaMapper.class);
        database.bindWalCheckpoint(new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration));
        return database;
    }
}
