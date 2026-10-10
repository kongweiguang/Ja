// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.foundation.error.FailureDiagnostics;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** 持久化 journal 将候选库准备和原子安装拆成可独立恢复的阶段。 */
public final class StorageRecovery {
    private static final String BASELINE = "ja-0.2";
    private static final Set<String> REASONS = Set.of("SCHEMA_VALIDATION_FAILED", "INITIALIZATION_FAILED",
            "CORRUPT_DATABASE", "DISK_FULL", "PERMISSION_DENIED", "INSTANCE_LOCKED", "IO_ERROR");
    /** 恢复状态完全由静态操作管理，避免调用方创建无状态实例。 */
    private StorageRecovery() { }

    /**
     * 只有稳定诊断信息和由服务端管理的备份标识会跨越客户端边界。
     *
     * @param id 当前实体的稳定标识
     * @param phase 阶段
     * @param reason 真实故障触发原因；仅已就绪状态允许无故障原因
     * @param historyAvailable 是否可读取历史
     * @param backupAvailable 是否有可用备份
     * @param rebuilt 是否已重建
     */
    public record Result(String id,
                         String phase,
                         String reason,
                         boolean historyAvailable,
                         boolean backupAvailable,
                         boolean rebuilt) {
        /** 恢复结果使用阶段与原因闭集，损坏 journal 不能被包装成可用历史或重建成功。 */
        public Result {
            if (id == null || id.isBlank() || phase == null
                    || !java.util.Set.of("rebuilding", "ready", "blocked").contains(phase)
                    || (reason == null ? !"ready".equals(phase) : !REASONS.contains(reason))
                    || historyAvailable != "ready".equals(phase) || rebuilt && !"ready".equals(phase)) {
                throw new IllegalArgumentException("invalid storage recovery result");
            }
        }
    }

    /**
     * 旧库、未知格式与 checksum 漂移只阻断原件；正常初始化不发布故障，只有实际中断或失败才记录恢复原因。
     */
    static Result prepare(DatabaseConfig config) {
        Path database = config.databasePath();
        Journal journal = null;
        try {
            journal = Journal.read(database);
            if (journal != null && !"COMPLETE".equals(journal.stage())) {
                if (journal.properties.getProperty("reason") == null) {
                    // 此次启动发现上次初始化未完成，才具备记录中断故障的真实依据。
                    journal.properties.setProperty("reason", "INITIALIZATION_FAILED");
                    journal.save(journal.stage());
                }
                return resume(config, journal);
            }
            if (Files.exists(database, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(database)) {
                return blockOriginal(database, "IO_ERROR", "INITIALIZE",
                        new IOException("database path is not a regular file"));
            }
            boolean existing = Files.exists(database) && Files.size(database) > 0;
            if (!existing) {
                if (hasSqliteSidecars(database)) {
                    return blockOriginal(database, "IO_ERROR", "INITIALIZE",
                            new IOException("SQLite sidecars exist without an initialized database"));
                }
                // 仅移除真正零字节的空占位，非空库永远不会进入此分支。
                Files.deleteIfExists(database);
                journal = Journal.create(database, null);
                return resume(config, journal);
            }
            try {
                JaDatabase.assertCurrentBaseline(JaDatabase.inspectionDataSource(config));
                return journal == null ? null : journal.result();
            } catch (SQLException | RuntimeException invalid) {
                if (operational(invalid)) {
                    throw storageFailure(invalid);
                }
                if (corrupt(invalid) && journal != null
                        && BASELINE.equals(journal.properties.getProperty("baseline"))) {
                    journal = Journal.create(database, "CORRUPT_DATABASE");
                    journal.properties.setProperty("retryStage", "REBUILD");
                    journal.save("REBUILD");
                    return resume(config, journal);
                }
                return blockOriginal(database, corrupt(invalid) ? "CORRUPT_DATABASE" : "SCHEMA_VALIDATION_FAILED",
                        "VALIDATE", invalid);
            }
        } catch (SQLException | IOException | RuntimeException failure) {
            throw storageFailure(failure);
        }
    }

    /**
     * 持久阻断不可安全判定的原库，不复制或覆盖；调用方仅能修复环境后重验原格式或重试新库初始化。
     */
    private static Result blockOriginal(Path database, String reason,
                                        String retryStage, Throwable failure) throws IOException {
        return block(Journal.create(database, reason), reason, retryStage, failure);
    }

    /** 首次真实失败才赋予正常初始化故障原因；已有恢复身份与触发原因在重试中保持稳定。 */
    private static Result block(Journal journal, String reason, String retryStage,
                                Throwable failure) throws IOException {
        if (journal.properties.getProperty("reason") == null) {
            journal.properties.setProperty("reason", reason);
        }
        journal.properties.setProperty("failure", reason);
        journal.properties.setProperty("retryStage", retryStage);
        if (failure != null) {
            journal.properties.setProperty("failureType", failure.getClass().getName());
            FailureDiagnostics.Summary diagnostic = FailureDiagnostics.summarize(failure);
            journal.properties.setProperty("failureOrigin", diagnostic.origin());
        }
        journal.save("BLOCKED");
        org.slf4j.LoggerFactory.getLogger(StorageRecovery.class).warn(
                "Ja existing database blocked reason={} type={}", reason,
                failure == null ? "none" : failure.getClass().getSimpleName());
        throw new StorageException(StorageException.Code.STORAGE_CONFLICT, reason, failure);
    }

    /**
     * 沿用原恢复 identity 推进候选库；初始化失败时保留原库并等待显式重试。
     */
    private static Result resume(DatabaseConfig config, Journal journal) throws SQLException, IOException {
        Path database = config.databasePath();
        Path candidate = journal.directory().resolve("candidate.db");
        boolean initialize = "INITIALIZE".equals(journal.properties.getProperty("retryStage"));
        if (journal.stage().equals("BLOCKED")) {
            String failure = journal.properties.getProperty("failure", "IO_ERROR");
            throw new StorageException(StorageException.Code.STORAGE_CONFLICT, failure);
        }
        if (journal.stage().equals("INITIALIZE")) {
            if (hasDatabaseFileOrSidecar(database)) {
                return block(journal, "IO_ERROR", "INITIALIZE",
                        new IOException("database path or SQLite sidecar became occupied before initialization"));
            }
            try {
                removeCandidate(candidate);
                DatabaseConfig candidateConfig = new DatabaseConfig(candidate,
                        config.busyTimeout(), config.homeDirectory());
                SQLiteDataSource source = JaDatabase.dataSource(candidateConfig);
                JaDatabase.migrateAndVerify(source, candidateConfig);
                journal.properties.setProperty("digest", digest(candidate));
                journal.save("VALIDATED");
            } catch (SQLException | RuntimeException failure) {
                return block(journal, reason(failure), "INITIALIZE", failure);
            }
        }
        if (journal.stage().equals("REBUILD")) {
            try {
                removeCandidate(candidate);
                DatabaseConfig candidateConfig = new DatabaseConfig(candidate, config.busyTimeout(), config.homeDirectory());
                JaDatabase.migrateAndVerify(JaDatabase.dataSource(candidateConfig), candidateConfig);
                journal.properties.setProperty("digest", digest(candidate));
                journal.properties.setProperty("rebuilt", "true");
                journal.save("VALIDATED");
            } catch (SQLException | RuntimeException failure) {
                return block(journal, reason(failure), "REBUILD", failure);
            }
        }
        if (journal.stage().equals("VALIDATED")) {
            if (!digest(candidate).equals(journal.properties.getProperty("digest"))) {
                throw new IOException("candidate changed before installation");
            }
            journal.save(initialize ? "INSTALL" : "SAVE");
        }
        if (journal.stage().equals("SAVE")) {
            for (String suffix : List.of("", "-wal", "-shm", "-journal")) {
                Path original = database.resolveSibling(database.getFileName() + suffix);
                Path backup = journal.directory().resolve("original.db" + suffix);
                if (Files.exists(original)) {
                    if (Files.exists(backup)) {
                        throw new IOException("recovery original and backup both exist");
                    }
                    Files.move(original, backup, StandardCopyOption.ATOMIC_MOVE);
                }
            }
            journal.save("INSTALL");
        }
        if (journal.stage().equals("INSTALL")) {
            if (Files.exists(candidate)) {
                if (hasDatabaseFileOrSidecar(database)) {
                    if (initialize) {
                        return block(journal, "IO_ERROR", "INITIALIZE",
                                new IOException("database path or SQLite sidecar became occupied during installation"));
                    }
                    throw new IOException("recovery installation target already exists");
                }
                Files.move(candidate, database, StandardCopyOption.ATOMIC_MOVE);
            }
            if (initialize && (hasSqliteSidecars(database)
                    || !Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS))) {
                return block(journal, "IO_ERROR", "INITIALIZE",
                        new IOException("database file or SQLite sidecar changed during installation"));
            }
            if (!Files.exists(database) || !digest(database).equals(journal.properties.getProperty("digest"))) {
                throw new IOException("recovery installation did not settle");
            }
            journal.save("COMPLETE");
        }
        return journal.result();
    }

    /** 初始化前将数据库及其旁路日志视为同一 SQLite 状态，避免把旧 sidecar 应用到新库。 */
    private static boolean hasDatabaseFileOrSidecar(Path database) {
        return Files.exists(database, LinkOption.NOFOLLOW_LINKS) || hasSqliteSidecars(database);
    }

    /** sidecar 残留表示路径仍有 SQLite 状态；不读取或删除其内容，只等待用户显式清理。 */
    private static boolean hasSqliteSidecars(Path database) {
        for (String suffix : List.of("-wal", "-shm", "-journal")) {
            Path sidecar = database.resolveSibling(database.getFileName() + suffix);
            if (Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
        }
        return false;
    }

    /** 先确认恢复身份，再验证明确的重试阶段；无效 journal 不得改写持久事实。 */
    public static void retry(DatabaseConfig config, String recoveryId) {
        try (DatabaseLease ignored = DatabaseLease.acquire(config.databasePath())) {
            Journal journal = Journal.read(config.databasePath());
            if (journal == null || !journal.properties.getProperty("id").equals(recoveryId)
                || !journal.stage().equals("BLOCKED")) {
                throw new StorageException(StorageException.Code.INVALID_STATE, "recovery retry is no longer applicable");
            }
            String retryStage = journal.properties.getProperty("retryStage");
            if (retryStage == null || !Set.of("VALIDATE", "REBUILD", "INITIALIZE").contains(retryStage)) {
                throw new StorageException(StorageException.Code.INVALID_STATE, "recovery retry is no longer applicable");
            }
            if ("VALIDATE".equals(retryStage)) {
                try {
                    JaDatabase.assertCurrentBaseline(JaDatabase.inspectionDataSource(config));
                } catch (SQLException invalid) {
                    throw storageFailure(invalid);
                }
                journal.properties.remove("failure");
                journal.save("COMPLETE");
            } else {
                journal.properties.remove("failure");
                journal.save(retryStage);
            }
        } catch (IOException failure) {
            throw storageFailure(failure);
        }
    }

    /** 权限、锁和系统 I/O 需要修复环境；复制或重建数据不能绕过这些失败。 */
    static boolean operational(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException || cause instanceof SecurityException) {
                return true;
            }
            if (cause instanceof StorageException storage && Set.of(StorageException.Code.IO,
                    StorageException.Code.INSTANCE_LOCKED).contains(storage.code())) {
                        return true;
                    }
            if (cause instanceof SQLException sql && Set.of(3, 5, 6, 7, 8, 10, 13, 14, 23)
                    .contains(sql.getErrorCode() & 255)) {
                        return true;
                    }
        }
        return false;
    }

    /** 只按 SQLite 的损坏错误码分类，避免把锁等待或翻译后的消息误判为损坏。 */
    private static boolean corrupt(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && Set.of(11, 26).contains(sql.getErrorCode() & 255)) {
                return true;
            }
        }
        return false;
    }

    /** 将底层失败归为稳定原因，供客户端展示动作且不泄漏 SQL 或路径。 */
    public static String reason(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof StorageException storage && storage.getMessage() != null && Set.of("DISK_FULL", "PERMISSION_DENIED",
                    "INSTANCE_LOCKED", "IO_ERROR", "CORRUPT_DATABASE", "SCHEMA_VALIDATION_FAILED", "INITIALIZATION_FAILED")
                    .contains(storage.getMessage())) {
                        return storage.getMessage();
                    }
            if (cause instanceof AccessDeniedException || cause instanceof SecurityException) {
                return "PERMISSION_DENIED";
            }
            if (cause instanceof StorageException storage && storage.code() == StorageException.Code.INSTANCE_LOCKED)
                {
                    return "INSTANCE_LOCKED";
                }
            if (cause instanceof SQLException sql) {
                switch (sql.getErrorCode() & 255) {
                    case 3, 8, 23: return "PERMISSION_DENIED";
                    case 5, 6: return "INSTANCE_LOCKED";
                    case 13: return "DISK_FULL";
                    case 11, 26: return "CORRUPT_DATABASE";
                    default: break;
                }
            }
        }
        return operational(failure) ? "IO_ERROR" : "INITIALIZATION_FAILED";
    }

    /** Bootstrap 只读取真实恢复结论；已正常完成初始化的私有准入 journal 不构成客户端故障。 */
    public static Result latest(DatabaseConfig config) {
        try {
            Journal journal = Journal.read(config.databasePath());
            return journal == null ? null : journal.result();
        } catch (IOException failure) {
            return null;
        }
    }

    /** 保留本地原始 cause，同时向调用方发布稳定的存储错误分类。 */
    private static StorageException storageFailure(Throwable failure) {
        if (failure instanceof StorageException storage) {
            return storage;
        }
        return new StorageException(operational(failure) ? StorageException.Code.IO : StorageException.Code.STORAGE_CONFLICT,
                reason(failure), failure);
    }

    /** 仅清理 journal 管理的临时候选文件，不触碰原始数据库与备份。 */
    private static void removeCandidate(Path path) throws IOException {
        for (String suffix : List.of("", "-wal", "-shm", "-journal"))
            {
                Files.deleteIfExists(path.resolveSibling(path.getFileName() + suffix));
            }
    }

    /** checkpoint 后计算候选摘要，供安装恢复核对且不扫描正常运行中的数据库。 */
    private static String digest(Path path) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    hash.update(buffer, 0, length);
                }
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    /** journal 以 Properties 避免 Native Image 反射，并从已校验 identity 推导备份路径。 */
    private static final class Journal {
        private final Path path;
        private final Properties properties;

        /** 每次尝试复用同一 journal 路径，保证崩溃恢复不会换身份。 */
        private Journal(Path path, Properties properties) {
            this.path = path;
            this.properties = properties;
        }

        /** journal 放在数据库旁边，避免与待替换的 SQLite 文件共用路径。 */
        private static Path path(Path database) {
            return database.resolveSibling(database.getFileName() + ".recovery.properties");
        }

        /** 只接受当前恢复契约；旧基线、未知阶段和不完整候选身份必须保留原件并阻断。 */
        private static Journal read(Path database) throws IOException {
            Path path = path(database);
            if (!Files.exists(path)) {
                return null;
            }
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(path)) { properties.load(input); }
            if (!properties.getProperty("id", "").matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                || !Set.of("REBUILD", "INITIALIZE", "VALIDATED", "SAVE", "INSTALL",
                    "COMPLETE", "BLOCKED")
                    .contains(properties.getProperty("stage", ""))
                || !BASELINE.equals(properties.getProperty("baseline"))
                || !Set.of("false", "true").contains(properties.getProperty("rebuilt", ""))) {
                        throw new IOException("invalid recovery journal");
                    }
            String retryStage = properties.getProperty("retryStage", "");
            if (!Set.of("VALIDATE", "REBUILD", "INITIALIZE").contains(retryStage)
                    || "BLOCKED".equals(properties.getProperty("stage"))
                    && !REASONS.contains(properties.getProperty("failure", ""))) {
                throw new IOException("invalid recovery journal decision");
            }
            validateReason(properties, retryStage);
            String digest = properties.getProperty("digest");
            boolean installedCandidate = "COMPLETE".equals(properties.getProperty("stage"))
                    && Set.of("INITIALIZE", "REBUILD").contains(retryStage);
            if (digest != null && !digest.matches("[0-9a-f]{64}")
                    || (installedCandidate || Set.of("VALIDATED", "SAVE", "INSTALL").contains(properties.getProperty("stage")))
                    && digest == null) {
                throw new IOException("invalid recovery candidate identity");
            }
            return new Journal(path, properties);
        }

        /** 无故障原因仅允许完整的当前正常初始化决策，不能把旧 journal 缺字段当作正常启动。 */
        private static void validateReason(Properties properties, String retryStage) throws IOException {
            String reason = properties.getProperty("reason");
            if (reason == null) {
                if (!"INITIALIZE".equals(retryStage) || !"false".equals(properties.getProperty("rebuilt"))
                        || !Set.of("INITIALIZE", "VALIDATED", "INSTALL", "COMPLETE").contains(properties.getProperty("stage"))
                        || properties.containsKey("failure")) {
                    throw new IOException("invalid initialization journal reason");
                }
            } else if (!REASONS.contains(reason)) {
                throw new IOException("invalid recovery journal reason");
            }
        }

        /** 先持久化 identity 再操作候选库；正常初始化尚未失败，因此不预填虚假的故障原因。 */
        private static Journal create(Path database, String reason) throws IOException {
            Properties properties = new Properties();
            properties.setProperty("id", UUID.randomUUID().toString());
            if (reason != null) {
                properties.setProperty("reason", reason);
            }
            properties.setProperty("baseline", BASELINE);
            properties.setProperty("rebuilt", "false");
            properties.setProperty("retryStage", "INITIALIZE");
            Journal journal = new Journal(path(database), properties);
            Files.createDirectories(journal.directory());
            journal.save("INITIALIZE");
            return journal;
        }

        /** 正常初始化完成不发布恢复状态；真实恢复只在安装完成后暴露就绪，并保留其实际触发原因。 */
        private Result result() {
            boolean ready = stage().equals("COMPLETE");
            if (ready && properties.getProperty("reason") == null) {
                return null;
            }
            boolean rebuilt = ready && Boolean.parseBoolean(properties.getProperty("rebuilt"));
            String phase = ready ? "ready" : stage().equals("BLOCKED") ? "blocked" : "rebuilding";
            return new Result(properties.getProperty("id"), phase,
                    stage().equals("BLOCKED") ? properties.getProperty("failure", "IO_ERROR")
                            : properties.getProperty("reason", "INITIALIZATION_FAILED"),
                    ready, Files.exists(directory().resolve("original.db")), rebuilt);
        }

        /** 绝对 journal 路径来自 DatabaseConfig；使用同级解析保持恢复产物在数据库目录内。 */
        private Path directory() { return path.resolveSibling("db-backups").resolve(properties.getProperty("id")); }

        /** 每次不可逆文件移动前先持久化阶段。 */
        private String stage() { return properties.getProperty("stage"); }

        /** 先刷盘完整 journal，再原子替换，部分写入的决策永远不具权威性。 */
        private void save(String stage) throws IOException {
            properties.setProperty("stage", stage);
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) { properties.store(output, "Ja storage recovery"); }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
