// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证现有 V1 的实际表、索引和触发器结构，拒绝漂移时保留原数据库文件。 */
final class JaDatabaseBaselineShapeTest {
    @TempDir Path temp;

    /** 当前 V1 的 60/46/28 对象基线含独立工具媒体 owner；显式计数防止迁移与校验集合同时漏项。 */
    @Test
    void reopensCurrentBaselineWithoutRecovery() throws Exception {
        Path databasePath = temp.resolve("valid-baseline").resolve("ja.db");
        Files.createDirectories(databasePath.getParent());
        DatabaseConfig config = DatabaseConfig.of(databasePath, databasePath.getParent());

        try (JaDatabase initial = openDatabase(databasePath)) {
            assertNull(initial.recovery());
        }
        assertNull(StorageRecovery.latest(config));
        assertNormalInitializationJournal(databasePath);

        try (JaDatabase reopened = openDatabase(databasePath)) {
            assertNull(reopened.recovery());
        }
        assertNull(StorageRecovery.latest(config));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement statement = connection.createStatement();
             ResultSet shape = statement.executeQuery("""
                     SELECT
                       (SELECT COUNT(*) FROM sqlite_schema WHERE type='table' AND name GLOB 'ja_*') AS tables,
                       (SELECT COUNT(*) FROM sqlite_schema WHERE type='index' AND sql IS NOT NULL
                         AND tbl_name GLOB 'ja_*') AS indexes,
                       (SELECT COUNT(*) FROM sqlite_schema WHERE type='trigger' AND tbl_name GLOB 'ja_*') AS triggers
                     """)) {
            assertTrue(shape.next());
            assertEquals(60, shape.getInt("tables"));
            assertEquals(46, shape.getInt("indexes"));
            assertEquals(28, shape.getInt("triggers"));
            assertFalse(shape.next());
        }
    }

    /** 额外列属于实际产品 schema 漂移，拒绝准入前后原库摘要必须一致。 */
    @Test
    void rejectsTableShapeDriftWithoutChangingOriginalBytes() throws Exception {
        rejectsDrift("table-drift", "ALTER TABLE ja_conversation_thread ADD COLUMN drift TEXT");
    }

    /** 删除真实产品索引不能被视为合法 V1，也不能由打开流程自动重建。 */
    @Test
    void rejectsMissingProductIndexWithoutChangingOriginalBytes() throws Exception {
        rejectsDrift("index-drift", "DROP INDEX idx_ja_conversation_thread_threads_workspace_updated");
    }

    /** 关闭回执 owner 查询索引属于实际 V1，不能因历史库缺失而被打开流程暗中重建。 */
    @Test
    void rejectsMissingSideChatCloseReceiptIndexWithoutChangingOriginalBytes() throws Exception {
        rejectsDrift("side-chat-close-index-drift",
                "DROP INDEX idx_ja_task_side_chat_close_receipt_workspace");
    }

    /** V1 必须包含回执表及有序成员表，删除任一表都应在使用前被拒绝。 */
    @Test
    void rejectsMissingSideChatCloseMemberTableWithoutChangingOriginalBytes() throws Exception {
        rejectsDrift("side-chat-close-member-drift", "DROP TABLE ja_task_side_chat_close_member");
    }

    /** 删除真实产品触发器不能被视为合法 V1，也不能由打开流程自动重建。 */
    @Test
    void rejectsMissingProductTriggerWithoutChangingOriginalBytes() throws Exception {
        rejectsDrift("trigger-drift", "DROP TRIGGER plan_events_immutable_update");
    }

    /** 每个场景都先生成完整基线，避免变异结果或恢复状态在测试间共享。 */
    private Path initializedDatabase(String name) throws Exception {
        Path databasePath = temp.resolve(name).resolve("ja.db");
        Files.createDirectories(databasePath.getParent());
        try (JaDatabase ignored = openDatabase(databasePath)) {
            // 初始化必须通过产品唯一 V1 准入路径。
        }
        return databasePath;
    }

    /** 统一使用生产配置，使初始化与重开共用同一存储身份和恢复边界。 */
    private static JaDatabase openDatabase(Path databasePath) {
        return JaDatabase.open(DatabaseConfig.of(databasePath, databasePath.getParent()));
    }

    /** 对真实 V1 执行单项 DDL 变异，再核对稳定错误、阻断状态及拒绝前后的文件摘要。 */
    private void rejectsDrift(String name, String mutationSql) throws Exception {
        Path databasePath = initializedDatabase(name);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(mutationSql);
            checkpoint(statement);
        }

        byte[] originalDigest = sha256(databasePath);
        StorageException failure = assertThrows(StorageException.class, () -> {
            try (JaDatabase ignored = openDatabase(databasePath)) {
                // 若漂移被接受，也要关闭 owner，避免断言失败时遗留 lease。
            }
        });
        assertEquals(StorageException.Code.STORAGE_CONFLICT, failure.code());
        assertEquals("SCHEMA_VALIDATION_FAILED", failure.getMessage());

        StorageRecovery.Result recovery = StorageRecovery.latest(
                DatabaseConfig.of(databasePath, databasePath.getParent()));
        assertNotNull(recovery);
        assertEquals("blocked", recovery.phase());
        assertEquals("SCHEMA_VALIDATION_FAILED", recovery.reason());
        assertArrayEquals(originalDigest, sha256(databasePath));
    }

    /** 关闭 SQLite WAL 后再测源文件，排除变异仍留在旁路日志中的假阴性。 */
    private static void checkpoint(Statement statement) throws Exception {
        try (ResultSet rows = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
            assertFalse(rows.next());
        }
    }

    /** 比较拒绝前后的持久文件摘要，覆盖 SQLite 重写而非只比较可见 schema 行。 */
    private static byte[] sha256(Path databasePath) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(databasePath));
    }

    /** 正常 V1 初始化需保留候选基线与摘要供崩溃判别，但不能伪造故障原因。 */
    private static void assertNormalInitializationJournal(Path databasePath) throws Exception {
        Path journalPath = databasePath.resolveSibling(databasePath.getFileName() + ".recovery.properties");
        Properties journal = new Properties();
        try (InputStream input = Files.newInputStream(journalPath)) {
            journal.load(input);
        }

        assertEquals("ja-0.2", journal.getProperty("baseline"));
        assertEquals("COMPLETE", journal.getProperty("stage"));
        assertEquals("INITIALIZE", journal.getProperty("retryStage"));
        assertEquals("false", journal.getProperty("rebuilt"));
        assertFalse(journal.containsKey("reason"));
        assertTrue(journal.getProperty("id").matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"));
        assertTrue(journal.getProperty("digest").matches("[0-9a-f]{64}"));
    }
}
