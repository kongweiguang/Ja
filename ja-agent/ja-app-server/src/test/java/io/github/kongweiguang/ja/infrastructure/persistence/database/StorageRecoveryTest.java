// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 SQLite 迁移、恢复与进程租约边界，所有文件都限制在 JUnit 临时目录。
 */
class StorageRecoveryTest {
    @TempDir Path temp;

    /**
     * 损坏文件必须保留原始字节并进入阻塞态，防止启动时创建空库覆盖待恢复数据。
     */
    @Test
    void corruptDatabaseBlocksWithoutChangingOriginalBytes() throws Exception {
        Path database = temp.resolve("ja.db");
        byte[] damaged = "not a database".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Files.write(database, damaged);
        StorageException failure = assertThrows(StorageException.class, () -> JaDatabase.open(config(database)));
        assertEquals(StorageException.Code.STORAGE_CONFLICT, failure.code());
        StorageRecovery.Result blocked = StorageRecovery.latest(config(database));
        assertNotNull(blocked);
        assertEquals("blocked", blocked.phase());
        assertFalse(blocked.rebuilt());
        assertFalse(blocked.historyAvailable());
        assertFalse(blocked.backupAvailable());
        assertArrayEquals(damaged, Files.readAllBytes(database));
        assertThrows(StorageException.class, () -> JaDatabase.open(config(database)));
        assertEquals(blocked.id(), StorageRecovery.latest(config(database)).id());
        assertArrayEquals(damaged, Files.readAllBytes(database));
        assertEquals(1, attempts(database));
    }

    /**
     * 真实路径 I/O 故障必须保留 cause 与首次原因；清理环境后重试沿用原恢复身份。
     */
    @Test
    void databasePathDirectoryBlocksAndCanRetryAfterUserMovesObstruction() throws Exception {
        Path database = temp.resolve("ja.db");
        Files.createDirectory(database);
        byte[] obstruction = "keep this directory untouched".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Path obstructionFile = database.resolve("keep.txt");
        Files.write(obstructionFile, obstruction);

        StorageException failure = assertThrows(StorageException.class, () -> JaDatabase.open(config(database)));
        assertEquals(StorageException.Code.STORAGE_CONFLICT, failure.code());
        assertInstanceOf(IOException.class, failure.getCause());
        StorageRecovery.Result blocked = StorageRecovery.latest(config(database));
        assertNotNull(blocked);
        assertEquals("blocked", blocked.phase());
        assertEquals("IO_ERROR", blocked.reason());
        assertFalse(blocked.historyAvailable());
        assertFalse(blocked.backupAvailable());
        Properties failureJournal = new Properties();
        try (InputStream input = Files.newInputStream(temp.resolve("ja.db.recovery.properties"))) {
            failureJournal.load(input);
        }
        assertEquals(IOException.class.getName(), failureJournal.getProperty("failureType"));
        assertTrue(failureJournal.getProperty("failureOrigin").startsWith("io.github.kongweiguang.ja."));
        assertTrue(Files.isDirectory(database));
        assertArrayEquals(obstruction, Files.readAllBytes(obstructionFile));

        assertThrows(StorageException.class, () -> JaDatabase.open(config(database)));
        StorageRecovery.retry(config(database), blocked.id());
        assertThrows(StorageException.class, () -> JaDatabase.open(config(database)));
        StorageRecovery.Result stillBlocked = StorageRecovery.latest(config(database));
        assertEquals(blocked.id(), stillBlocked.id());
        assertEquals("blocked", stillBlocked.phase());
        assertTrue(Files.isDirectory(database));
        assertArrayEquals(obstruction, Files.readAllBytes(obstructionFile));

        Path movedObstruction = temp.resolve("ja.db.obstruction");
        Files.move(database, movedObstruction, StandardCopyOption.ATOMIC_MOVE);

        execute(database, "CREATE TABLE unexpected(value TEXT)");
        byte[] unknownDatabase = Files.readAllBytes(database);
        StorageRecovery.retry(config(database), blocked.id());
        assertThrows(StorageException.class, () -> JaDatabase.open(config(database)));
        StorageRecovery.Result unknownBlocked = StorageRecovery.latest(config(database));
        assertEquals(blocked.id(), unknownBlocked.id());
        assertEquals("blocked", unknownBlocked.phase());
        assertEquals("IO_ERROR", unknownBlocked.reason());
        assertArrayEquals(unknownDatabase, Files.readAllBytes(database));

        Files.delete(database);
        for (String suffix : List.of("-wal", "-shm", "-journal")) {
            Path sidecar = temp.resolve("ja.db" + suffix);
            byte[] sidecarContents = ("preserve " + suffix).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            Files.write(sidecar, sidecarContents);
            StorageRecovery.retry(config(database), blocked.id());
            assertThrows(StorageException.class, () -> JaDatabase.open(config(database)));
            StorageRecovery.Result sidecarBlocked = StorageRecovery.latest(config(database));
            assertEquals(blocked.id(), sidecarBlocked.id());
            assertEquals("blocked", sidecarBlocked.phase());
            assertEquals("IO_ERROR", sidecarBlocked.reason());
            assertArrayEquals(sidecarContents, Files.readAllBytes(sidecar));
            assertFalse(Files.exists(database));
            Files.delete(sidecar);
        }

        StorageRecovery.retry(config(database), blocked.id());
        try (JaDatabase opened = JaDatabase.open(config(database))) {
            assertEquals(blocked.id(), opened.recovery().id());
            assertEquals("ready", opened.recovery().phase());
            assertEquals("IO_ERROR", opened.recovery().reason());
            assertTrue(opened.recovery().historyAvailable());
        }
        assertTrue(Files.isRegularFile(database));
        assertArrayEquals(obstruction, Files.readAllBytes(movedObstruction.resolve("keep.txt")));
        StorageRecovery.Result ready = StorageRecovery.latest(config(database));
        assertEquals("ready", ready.phase());
        assertEquals("IO_ERROR", ready.reason());
        try (JaDatabase reopened = JaDatabase.open(config(database))) {
            assertEquals(blocked.id(), reopened.recovery().id());
            assertEquals("IO_ERROR", reopened.recovery().reason());
        }
        assertEquals(1, attempts(database));
    }

    /**
     * 首次启动若只有 SQLite sidecar，不推断它可以丢弃；清理后仍通过同一 recovery identity 初始化。
     */
    @Test
    void orphanSqliteSidecarsBlockFirstInitializationUntilExplicitRetry() throws Exception {
        Path database = temp.resolve("ja.db");
        String recoveryId = null;
        for (String suffix : List.of("-wal", "-shm", "-journal")) {
            Path sidecar = temp.resolve("ja.db" + suffix);
            byte[] contents = ("preserve " + suffix).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            Files.write(sidecar, contents);

            if (recoveryId != null) {
                StorageRecovery.retry(config(database), recoveryId);
            }
            assertThrows(StorageException.class, () -> JaDatabase.open(config(database)));

            StorageRecovery.Result blocked = StorageRecovery.latest(config(database));
            assertNotNull(blocked);
            assertEquals("blocked", blocked.phase());
            assertEquals("IO_ERROR", blocked.reason());
            if (recoveryId == null) {
                recoveryId = blocked.id();
            } else {
                assertEquals(recoveryId, blocked.id());
            }
            assertFalse(Files.exists(database));
            assertArrayEquals(contents, Files.readAllBytes(sidecar));
            Files.delete(sidecar);
        }

        StorageRecovery.retry(config(database), recoveryId);
        try (JaDatabase opened = JaDatabase.open(config(database))) {
            assertEquals(recoveryId, opened.recovery().id());
            assertEquals("ready", opened.recovery().phase());
        }
    }

    /** 正常建库不是恢复事件；首次打开和重开都不能虚构 INITIALIZATION_FAILED 原因。 */
    @Test
    void normalInitializationDoesNotPublishRecoveryState() throws Exception {
        Path database = temp.resolve("normal.db");
        try (JaDatabase opened = JaDatabase.open(config(database))) {
            assertNull(opened.recovery());
        }
        assertNull(StorageRecovery.latest(config(database)));
        try (JaDatabase reopened = JaDatabase.open(config(database))) {
            assertNull(reopened.recovery());
        }
        assertNull(StorageRecovery.latest(config(database)));
        assertEquals(0, attempts(database));
    }

    /**
     * 校验和偏移必须阻止数据库接入；比对整库字节可证明失败路径没有修复或替换原库。
     */
    @Test
    void checksumChangeBlocksWithoutRepairingOriginal() throws Exception {
        Path database = initialized();
        long originalChecksum = scalar(database, "SELECT checksum FROM flyway_schema_history WHERE version='1'");
        execute(database, "UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='1'");
        long changedChecksum = scalar(database, "SELECT checksum FROM flyway_schema_history WHERE version='1'");
        byte[] original = Files.readAllBytes(database);
        StorageException failure = assertThrows(StorageException.class, () -> JaDatabase.open(config(database)));
        assertEquals(StorageException.Code.STORAGE_CONFLICT, failure.code());
        assertEquals("blocked", StorageRecovery.latest(config(database)).phase());
        assertEquals(originalChecksum + 1, changedChecksum);
        assertEquals(changedChecksum, scalar(database,
                "SELECT checksum FROM flyway_schema_history WHERE version='1'"));
        assertArrayEquals(original, Files.readAllBytes(database));
    }

    /**
     * schema 与迁移历史冲突时必须阻止重建，并确认未知表和原始数据库字节均未变化。
     */
    @Test
    void schemaConflictBlocksAndPreservesOriginal() throws Exception {
        Path database = initialized();
        execute(database, "UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='1'");
        execute(database, "CREATE TABLE unexpected(value TEXT)");
        byte[] original = Files.readAllBytes(database);
        StorageException failure = assertThrows(StorageException.class, () -> JaDatabase.open(config(database)));
        assertEquals(StorageException.Code.STORAGE_CONFLICT, failure.code());
        assertEquals("blocked", StorageRecovery.latest(config(database)).phase());
        assertEquals(1, scalar(database, "SELECT count(*) FROM sqlite_schema WHERE name='unexpected'"));
        assertArrayEquals(original, Files.readAllBytes(database));
    }

    /**
     * 先持有真实数据库租约再尝试打开，确认互斥检查早于备份和恢复副作用。
     */
    @Test
    void lockedOwnerNeverCreatesRecoveryFiles() throws Exception {
        Path database = initialized();
        long initialAttempts = attempts(database);
        Set<String> directoriesBefore = backupDirectoryIds(database);
        Path journalPath = database.resolveSibling(database.getFileName() + ".recovery.properties");
        byte[] journalBefore = Files.readAllBytes(journalPath);
        byte[] databaseBefore = Files.readAllBytes(database);
        try (DatabaseLease ignored = DatabaseLease.acquire(database)) {
            StorageException error = assertThrows(StorageException.class, () -> JaDatabase.open(config(database)));
            assertEquals(StorageException.Code.INSTANCE_LOCKED, error.code());
            assertEquals(initialAttempts, attempts(database));
            assertEquals(directoriesBefore, backupDirectoryIds(database));
            assertArrayEquals(journalBefore, Files.readAllBytes(journalPath));
            assertArrayEquals(databaseBefore, Files.readAllBytes(database));
        }
    }

    /**
     * 中断等待必须保留原始原因与线程标记，并始终关闭失败获取过程中打开的 lease channel。
     */
    @Test
    void interruptWhileWaitingForOtherProcessLeaseRemainsAnIoFailure() throws Exception {
        Path database = temp.resolve("lease-interruption.db");
        Path ready = temp.resolve("lease-owner.ready");
        Process owner = startLeaseOwner(database, ready);
        try {
            awaitLeaseOwner(owner, ready);
            AtomicBoolean interruptPreserved = new AtomicBoolean();
            FutureTask<StorageException> acquisition = new FutureTask<>(() -> {
                StorageException failure = assertThrows(StorageException.class,
                        () -> DatabaseLease.acquire(database, Duration.ofSeconds(5)));
                interruptPreserved.set(Thread.currentThread().isInterrupted());
                return failure;
            });
            Thread waiter = Thread.ofPlatform().name("database-lease-interruption-test").unstarted(acquisition);
            waiter.start();
            waiter.interrupt();

            StorageException failure = acquisition.get(3, TimeUnit.SECONDS);
            assertEquals(StorageException.Code.IO, failure.code());
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertTrue(interruptPreserved.get());
        } finally {
            stopLeaseOwner(owner);
        }
    }

    /**
     * 子进程持有真实 OS 文件锁，避免同 JVM 的 OverlappingFileLockException 绕过等待分支。
     */
    private Process startLeaseOwner(Path database, Path ready) throws Exception {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        return new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                DatabaseLeaseProbe.class.getName(), "--hold", database.toString(), ready.toString())
                .redirectErrorStream(true).start();
    }

    /**
     * 只在子进程已成功取得租约后才启动等待者，测试结果不依赖启动时序猜测。
     */
    private void awaitLeaseOwner(Process owner, Path ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(ready) && owner.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(Files.exists(ready), "isolated process did not acquire the database lease");
    }

    /**
     * 关闭子进程输入让 fixture 正常释放文件锁，超时则终止残留测试进程。
     */
    private void stopLeaseOwner(Process owner) throws Exception {
        owner.getOutputStream().close();
        if (!owner.waitFor(5, TimeUnit.SECONDS)) {
            owner.destroyForcibly();
            assertTrue(owner.waitFor(5, TimeUnit.SECONDS), "isolated lease owner did not stop");
        }
    }

    /** 环境故障按原始类型分类；真实非环境初始化失败使用稳定通用原因。 */
    @Test
    void operationalErrorsNeverQualifyForReplacement() {
        for (int code : List.of(3, 5, 6, 8, 10, 13, 14, 23)) {
            assertTrue(StorageRecovery.operational(new SQLException("opaque", "", code)));
        }
        assertFalse(StorageRecovery.operational(new SQLException("opaque", "", 11)));
        assertEquals("DISK_FULL", StorageRecovery.reason(new SQLException("opaque", "", 13)));
        assertEquals("DISK_FULL", StorageRecovery.reason(new StorageException(StorageException.Code.IO, "DISK_FULL")));
        assertEquals("PERMISSION_DENIED", StorageRecovery.reason(new java.nio.file.AccessDeniedException("opaque")));
        assertTrue(StorageRecovery.operational(new SecurityException("opaque")));
        assertEquals("PERMISSION_DENIED", StorageRecovery.reason(new SecurityException("opaque")));
        SQLException migrationConflict = new SQLException("opaque migration conflict");
        assertFalse(StorageRecovery.operational(migrationConflict));
        assertEquals("INITIALIZATION_FAILED", StorageRecovery.reason(migrationConflict));
    }

    /**
     * 恢复阶段必须使用当前 journal 契约；无效候选会被重建并沿用已持久化的身份。
     */
    @Test
    void interruptedRebuildRecreatesCandidateWithSameRecoveryIdentity() throws Exception {
        Path database = initialized();
        long initialAttempts = attempts(database);
        execute(database, "UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='1'");
        String id = UUID.randomUUID().toString();
        Properties journal = recoveryFixture(id, "REBUILD", "REBUILD", "SCHEMA_VALIDATION_FAILED");
        Path directory = temp.resolve("db-backups").resolve(id);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("candidate.db"), "incomplete");
        saveJournal(journal);
        byte[] original = Files.readAllBytes(database);
        try (JaDatabase opened = JaDatabase.open(config(database))) {
            assertEquals(id, opened.recovery().id());
            assertEquals("ready", opened.recovery().phase());
            assertEquals("SCHEMA_VALIDATION_FAILED", opened.recovery().reason());
            assertTrue(opened.recovery().rebuilt());
        }
        assertEquals("ready", StorageRecovery.latest(config(database)).phase());
        assertEquals("SCHEMA_VALIDATION_FAILED", StorageRecovery.latest(config(database)).reason());
        assertFalse(java.util.Arrays.equals(original, Files.readAllBytes(database)));
        assertEquals(initialAttempts + 1, attempts(database));
    }

    /**
     * SAVE 阶段可在原库与 sidecar 移动之间中断；重启需保留备份并只完成一次安装。
     */
    @Test
    void interruptedSaveResumesAfterOriginalWasMoved() throws Exception {
        Path database = initialized();
        long initialAttempts = attempts(database);
        Properties journal = installationFixture(database, "SAVE");
        Path directory = temp.resolve("db-backups").resolve(journal.getProperty("id"));
        byte[] original = Files.readAllBytes(database);
        Files.move(database, directory.resolve("original.db"));
        byte[] oldJournal = { 1, 2, 3 };
        Files.write(temp.resolve("ja.db-journal"), oldJournal);
        try (JaDatabase opened = JaDatabase.open(config(database))) {
            assertEquals(journal.getProperty("id"), opened.recovery().id());
            assertTrue(opened.recovery().backupAvailable());
        }
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("original.db")));
        assertArrayEquals(oldJournal, Files.readAllBytes(directory.resolve("original.db-journal")));
        assertFalse(Files.exists(temp.resolve("ja.db-journal")));
        assertEquals(initialAttempts + 1, attempts(database));
    }

    /**
     * INSTALL 可在原子移动后、journal 完成前中断；用摘要确定已安装候选库的权威性。
     */
    @Test
    void interruptedInstallRecognizesAlreadyInstalledCandidate() throws Exception {
        Path database = initialized();
        long initialAttempts = attempts(database);
        Properties journal = installationFixture(database, "INSTALL");
        Path directory = temp.resolve("db-backups").resolve(journal.getProperty("id"));
        Files.move(database, directory.resolve("original.db"));
        Files.move(directory.resolve("candidate.db"), database);
        try (JaDatabase opened = JaDatabase.open(config(database))) {
            assertEquals(journal.getProperty("id"), opened.recovery().id());
            assertEquals("ready", opened.recovery().phase());
        }
        try (JaDatabase reopened = JaDatabase.open(config(database))) {
            assertEquals(journal.getProperty("id"), reopened.recovery().id());
        }
        assertEquals(initialAttempts + 1, attempts(database));
    }

    /** Strict journal 拒绝缺失或未知重试阶段，且不改写原件或清理阻断诊断。 */
    @Test
    void invalidRetryStagesLeaveJournalAndDatabaseUntouched() throws Exception {
        Path database = temp.resolve("ja.db");
        Path journalPath = temp.resolve("ja.db.recovery.properties");
        String recoveryId = UUID.randomUUID().toString();
        Properties journal = recoveryFixture(recoveryId, "BLOCKED", "INITIALIZE", "IO_ERROR");
        for (String retryStage : List.of("", "FRESH", "COMPLETE")) {
            if (retryStage.isEmpty()) {
                journal.remove("retryStage");
            } else {
                journal.setProperty("retryStage", retryStage);
            }
            saveJournal(journal);
            byte[] before = Files.readAllBytes(journalPath);
            StorageException failure = assertThrows(StorageException.class,
                    () -> StorageRecovery.retry(config(database), recoveryId));
            assertEquals(StorageException.Code.IO, failure.code());
            assertInstanceOf(IOException.class, failure.getCause());
            assertArrayEquals(before, Files.readAllBytes(journalPath));
            assertFalse(Files.exists(database));
            assertFalse(Files.exists(temp.resolve("db-backups")));
            assertNull(StorageRecovery.latest(config(database)));
        }
    }

    /** 当前 journal 必须声明基线；缺字段时重试只报告损坏，不触碰数据库或持久原件。 */
    @Test
    void journalWithoutBaselineIsRejectedWithoutMutation() throws Exception {
        Path database = temp.resolve("ja.db");
        Path journalPath = temp.resolve("ja.db.recovery.properties");
        String recoveryId = UUID.randomUUID().toString();
        Properties journal = recoveryFixture(recoveryId, "BLOCKED", "INITIALIZE", "IO_ERROR");
        journal.remove("baseline");
        saveJournal(journal);
        byte[] before = Files.readAllBytes(journalPath);

        StorageException failure = assertThrows(StorageException.class,
                () -> StorageRecovery.retry(config(database), recoveryId));

        assertEquals(StorageException.Code.IO, failure.code());
        assertInstanceOf(IOException.class, failure.getCause());
        assertArrayEquals(before, Files.readAllBytes(journalPath));
        assertFalse(Files.exists(database));
        assertFalse(Files.exists(temp.resolve("db-backups")));
        assertNull(StorageRecovery.latest(config(database)));
    }

    /** 用户修复原库后再显式 VALIDATE；不重建候选即可保留原库中的既有历史。 */
    @Test
    void blockedMigrationCanRetryAfterOriginalHistoryIsFixed() throws Exception {
        Path database = initialized();
        long initialAttempts = attempts(database);
        long originalChecksum = scalar(database, "SELECT checksum FROM flyway_schema_history WHERE version='1'");
        execute(database, "UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='1'");
        byte[] unchangedOriginal = Files.readAllBytes(database);
        assertThrows(StorageException.class, () -> JaDatabase.open(config(database)));
        StorageRecovery.Result blocked = StorageRecovery.latest(config(database));
        assertEquals("blocked", blocked.phase());
        assertEquals(initialAttempts + 1, attempts(database));
        assertArrayEquals(unchangedOriginal, Files.readAllBytes(database));

        execute(database, "UPDATE flyway_schema_history SET checksum=" + originalChecksum + " WHERE version='1'");
        StorageRecovery.retry(config(database), blocked.id());
        try (JaDatabase opened = JaDatabase.open(config(database))) {
            assertEquals(blocked.id(), opened.recovery().id());
            assertFalse(opened.recovery().rebuilt());
            assertTrue(opened.recovery().historyAvailable());
            assertFalse(opened.recovery().backupAvailable());
        }
        assertEquals(originalChecksum, scalar(database,
                "SELECT checksum FROM flyway_schema_history WHERE version='1'"));
        assertEquals(initialAttempts + 1, attempts(database));
    }

    /**
     * 仅重建崩溃后可见的磁盘事实，避免为测试在生产恢复逻辑中加入故障注入分支。
     */
    private Properties installationFixture(Path database, String stage) throws Exception {
        Properties journal = recoveryFixture(UUID.randomUUID().toString(), stage, "REBUILD",
                "SCHEMA_VALIDATION_FAILED");
        Path directory = temp.resolve("db-backups").resolve(journal.getProperty("id"));
        Files.createDirectories(directory);
        Path candidate = directory.resolve("candidate.db");
        Files.copy(database, candidate);
        journal.setProperty("digest", java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(candidate))));
        saveJournal(journal);
        return journal;
    }

    /** 仅构造当前版本可接受的恢复身份，避免测试 fixture 依赖已淘汰的 journal 形状。 */
    private Properties recoveryFixture(String id, String stage, String retryStage, String reason) {
        Properties journal = new Properties();
        journal.setProperty("id", id);
        journal.setProperty("stage", stage);
        journal.setProperty("baseline", "ja-0.2");
        journal.setProperty("retryStage", retryStage);
        journal.setProperty("reason", reason);
        journal.setProperty("rebuilt", "false");
        if ("BLOCKED".equals(stage)) {
            journal.setProperty("failure", reason);
        }
        return journal;
    }

    /**
     * 测试使用与生产恢复器相同的完整 journal 格式，保证阶段恢复断言对应真实持久事实。
     */
    private void saveJournal(Properties journal) throws Exception {
        try (OutputStream output = Files.newOutputStream(temp.resolve("ja.db.recovery.properties"))) {
            journal.store(output, "fixture");
        }
    }

    /**
     * 每个恢复场景都复用生产迁移链，并把数据库与备份约束在当前测试的临时目录。
     */
    private Path initialized() throws Exception {
        Path database = temp.resolve("ja.db");
        try (JaDatabase ignored = JaDatabase.open(config(database))) { }
        return database;
    }

    /**
     * 显式指定 home 与数据库路径，防止迁移过程探测真实用户配置或数据目录。
     */
    private DatabaseConfig config(Path database) { return DatabaseConfig.of(database, temp); }

    /**
     * 通过 SQLite 权威状态读取单值，避免使用恢复结果投影替代数据库事实。
     */
    private static long scalar(Path database, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement query = connection.createStatement(); ResultSet rows = query.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    /**
     * 所有 SQL 修改仅作用于一次性测试库，避免辅助方法误改源库或用户数据。
     */
    private static void execute(Path database, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement query = connection.createStatement()) { query.execute(sql); }
    }

    /** 从真实原因或持久候选识别恢复尝试，排除正常初始化留下的空 baseline 目录。 */
    private static long attempts(Path database) throws Exception {
        Path backupRoot = database.getParent().resolve("db-backups");
        Set<String> recoveryIds = new HashSet<>();
        if (Files.isDirectory(backupRoot)) {
            try (Stream<Path> directories = Files.list(backupRoot)) {
                for (Path directory : directories.filter(Files::isDirectory).toList()) {
                    try (Stream<Path> entries = Files.list(directory)) {
                        if (entries.findAny().isPresent()) {
                            recoveryIds.add(directory.getFileName().toString());
                        }
                    }
                }
            }
        }
        Path journalPath = database.resolveSibling(database.getFileName() + ".recovery.properties");
        if (Files.isRegularFile(journalPath)) {
            Properties journal = new Properties();
            try (InputStream input = Files.newInputStream(journalPath)) {
                journal.load(input);
            }
            String id = journal.getProperty("id");
            if (journal.getProperty("reason") != null && id != null) {
                recoveryIds.add(id);
            }
        }
        return recoveryIds.size();
    }

    /** 精确快照恢复目录标识，防止同数目下目录被替换而逃过锁冲突检查。 */
    private static Set<String> backupDirectoryIds(Path database) throws IOException {
        Path backupRoot = database.getParent().resolve("db-backups");
        if (!Files.isDirectory(backupRoot)) {
            return Set.of();
        }
        try (Stream<Path> directories = Files.list(backupRoot)) {
            return directories.filter(Files::isDirectory)
                    .map(directory -> directory.getFileName().toString())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }
}
