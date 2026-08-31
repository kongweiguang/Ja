// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import io.github.kongweiguang.ja.foundation.error.StorageException;
import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 在唯一数据库 lease 内为 Flyway 增加进程级恢复检查点。
 *
 * <p>Flyway 的 SQLite 事务可以回滚 SQL 失败，但无法表达进程在 migration 提交、schema
 * 回读和应用准入之间退出的状态。本边界在升级前创建 SQLite 一致性备份，并用相邻原子 marker
 * 记录 PREPARED/APPLIED；任何 PREPARED 重启都先恢复备份再重放，绝不让半迁移数据库进入 MyBatis。</p>
 */
final class DatabaseMigrationRecovery {
    static final String MARKER_SUFFIX = ".migration-checkpoint";
    private static final String FORMAT = "ja-database-migration-v1";
    private static final String PREPARED = "prepared";
    private static final String APPLIED = "applied";
    private static final Set<String> STATES = Set.of(PREPARED, APPLIED);

    private final Path database;
    private final SQLiteDataSource source;
    private final String targetVersion;

    /**
     * 恢复器只接受已经取得 lease 的生产 datasource；路径和目标版本被冻结，防止迁移过程中
     * 备份目标或资源闭集被调用方替换。
     */
    DatabaseMigrationRecovery(Path database, SQLiteDataSource source, String targetVersion) {
        this.database = Objects.requireNonNull(database, "database").toAbsolutePath().normalize();
        this.source = Objects.requireNonNull(source, "source");
        this.targetVersion = requireVersion(targetVersion);
    }

    /**
     * 先收敛遗留检查点，再决定 fresh/no-op/upgrade；升级成功必须通过 Flyway、integrity 和 FK
     * 三重回读后才发布 APPLIED，任一失败都恢复升级前备份并保持启动失败。
     */
    void migrate(Flyway flyway) {
        Objects.requireNonNull(flyway, "flyway");
        Marker marker = readMarker();
        if (marker != null && PREPARED.equals(marker.state())) {
            requireCurrentTarget(marker);
            restore(marker);
        }

        String currentVersion = readSchemaVersion(source);
        rejectNewerVersion(currentVersion);
        if (targetVersion.equals(currentVersion)) {
            validateTarget(flyway);
            requireAppliedConsistency(marker, currentVersion);
            return;
        }
        if ("0".equals(currentVersion)) {
            migrateFresh(flyway);
            return;
        }

        Marker prepared = prepare(currentVersion);
        try {
            flyway.migrate();
            validateTarget(flyway);
            writeMarker(prepared.withState(APPLIED));
        } catch (RuntimeException failure) {
            restoreAfterFailure(prepared, failure);
        }
    }

    /**
     * 全新数据库没有可恢复用户数据，因此不制造空备份；仍要求迁移后回读完整，失败时由
     * StorageBaseline 保持首次代际 marker 未发布，从而阻止残缺目录再次准入。
     */
    private void migrateFresh(Flyway flyway) {
        try {
            flyway.migrate();
            validateTarget(flyway);
        } catch (RuntimeException failure) {
            throw storage(StorageException.Code.TRANSACTION,
                    "cannot initialize Ja database schema", failure);
        }
    }

    /**
     * 备份先写临时文件并验证 schema/integrity，再原子发布；marker 最后写入，因此进程退出
     * 最多留下可重新验证的孤立备份，不会把未完成备份声明成可恢复状态。
     */
    private Marker prepare(String sourceVersion) {
        Path backup = backupPath(sourceVersion);
        createBackup(backup);
        validateBackup(backup, sourceVersion);
        Path backupFile = backup.getFileName();
        if (backupFile == null) {
            throw storage(StorageException.Code.INVALID_CONFIGURATION,
                    "database migration backup filename is unavailable", null);
        }
        Marker marker = new Marker(PREPARED, sourceVersion, targetVersion,
                backupFile.toString(), digest(backup));
        writeMarker(marker);
        return marker;
    }

    /**
     * SQLite 在线 backup API 把可能仍在 WAL 的已提交页折叠为单文件快照；相比复制 main/wal/shm
     * 三件套，它在 Windows 崩溃恢复和 Native Image 中都只依赖 sqlite-jdbc 的正式 ABI。
     */
    private void createBackup(Path backup) {
        Path staging = temporarySibling(backup, ".backup-");
        try {
            Files.deleteIfExists(staging);
            try (Connection connection = source.getConnection()) {
                int result = connection.unwrap(SQLiteConnection.class).getDatabase()
                        .backup("main", staging.toString(), null);
                if (result != 0) {
                    throw new SQLException("SQLite backup returned a non-zero status");
                }
            }
            forceFile(staging);
            moveAtomic(staging, backup, true);
        } catch (IOException | SQLException failure) {
            throw storage(StorageException.Code.IO, "cannot create database migration backup", failure);
        } finally {
            deleteQuietly(staging);
        }
    }

    /**
     * 失败恢复始终把 marker 退回 PREPARED；即使恢复本身失败，下一次启动也只会再次尝试恢复，
     * 不会把可能已提交的目标 schema 当成正常数据库开放。
     */
    private void restoreAfterFailure(Marker prepared, RuntimeException migrationFailure) {
        try {
            restore(prepared);
            writeMarker(prepared);
        } catch (RuntimeException recoveryFailure) {
            migrationFailure.addSuppressed(recoveryFailure);
            throw storage(StorageException.Code.IO,
                    "database migration failed and recovery was not confirmed", migrationFailure);
        }
        throw storage(StorageException.Code.TRANSACTION,
                "database migration failed and the previous schema was restored", migrationFailure);
    }

    /**
     * 恢复前同时校验 marker 派生路径、备份摘要和源 schema；随后原子替换 main 文件并清除
     * 旧 WAL/SHM，避免 SQLite 在恢复副本上重放目标 schema 的残留页。
     */
    private void restore(Marker marker) {
        validateMarker(marker);
        Path backup = database.resolveSibling(marker.backupFile()).normalize();
        if (!Objects.equals(database.getParent(), backup.getParent())
            || !backup.equals(backupPath(marker.sourceVersion()))) {
            throw storage(StorageException.Code.STORAGE_CONFLICT,
                    "database migration marker names an invalid backup", null);
        }
        if (!MessageDigest.isEqual(marker.backupSha256().getBytes(StandardCharsets.US_ASCII),
                digest(backup).getBytes(StandardCharsets.US_ASCII))) {
            throw storage(StorageException.Code.STORAGE_CONFLICT,
                    "database migration backup does not match its checkpoint", null);
        }
        validateBackup(backup, marker.sourceVersion());
        Path staging = temporarySibling(database, ".restore-");
        try {
            Files.copy(backup, staging, StandardCopyOption.REPLACE_EXISTING);
            forceFile(staging);
            Files.deleteIfExists(walPath());
            Files.deleteIfExists(shmPath());
            moveAtomic(staging, database, true);
            Files.deleteIfExists(walPath());
            Files.deleteIfExists(shmPath());
        } catch (IOException failure) {
            throw storage(StorageException.Code.IO, "cannot restore database migration backup", failure);
        } finally {
            deleteQuietly(staging);
        }
        validateDatabase(source, marker.sourceVersion());
    }

    /**
     * 目标回读要求没有 pending migration、Flyway history 到达精确目标，并对新连接执行
     * integrity_check/foreign_key_check；单凭 migrate() 返回不能证明磁盘上的 schema 可重开。
     */
    private void validateTarget(Flyway flyway) {
        try {
            flyway.validate();
            if (flyway.info().pending().length != 0) {
                throw new SQLException("Flyway still reports pending migrations");
            }
            validateDatabase(source, targetVersion);
        } catch (RuntimeException | SQLException failure) {
            throw storage(StorageException.Code.TRANSACTION,
                    "database migration readback validation failed", failure);
        }
    }

    /**
     * APPLIED 是不可逆准入断言：它必须指向当前 target。旧 APPLIED marker 可以被下一版升级覆盖，
     * 但同版本 marker 与数据库不一致时必须失败关闭而不能猜测恢复。
     */
    private void requireAppliedConsistency(Marker marker, String currentVersion) {
        if (marker == null) return;
        validateMarker(marker);
        if (APPLIED.equals(marker.state()) && marker.targetVersion().equals(currentVersion)) return;
        if (APPLIED.equals(marker.state()) && versionNumber(marker.targetVersion()) < versionNumber(currentVersion)) {
            return;
        }
        throw storage(StorageException.Code.STORAGE_CONFLICT,
                "database migration checkpoint is inconsistent with the schema", null);
    }

    /** 遗留 PREPARED 只能由包含该 target 的运行时恢复，禁止旧/未知运行时改写数据库。 */
    private void requireCurrentTarget(Marker marker) {
        validateMarker(marker);
        if (!targetVersion.equals(marker.targetVersion())) {
            throw storage(StorageException.Code.STORAGE_CONFLICT,
                    "database migration checkpoint targets another runtime", null);
        }
    }

    /** 当前数据库高于打包资源时拒绝降级启动，避免 Flyway 异常被包装成可重试升级。 */
    private void rejectNewerVersion(String currentVersion) {
        if ("0".equals(currentVersion)) return;
        if (versionNumber(currentVersion) > versionNumber(targetVersion)) {
            throw storage(StorageException.Code.STORAGE_CONFLICT,
                    "database schema is newer than this runtime", null);
        }
    }

    /**
     * 独立连接校验 schema history、文件完整性和外键；backup 与恢复后的 main 共用同一门禁，
     * 避免仅比较文件摘要却接受逻辑损坏的恢复点。
     */
    private static void validateDatabase(SQLiteDataSource dataSource, String expectedVersion) {
        try (Connection connection = dataSource.getConnection()) {
            String actual = readSchemaVersion(connection);
            if (!expectedVersion.equals(actual)) {
                throw new SQLException("database schema version does not match the checkpoint");
            }
            try (Statement statement = connection.createStatement();
                 ResultSet integrity = statement.executeQuery("PRAGMA integrity_check")) {
                if (!integrity.next() || !"ok".equalsIgnoreCase(integrity.getString(1)) || integrity.next()) {
                    throw new SQLException("database integrity check failed");
                }
            }
            try (Statement statement = connection.createStatement();
                 ResultSet foreignKeys = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (foreignKeys.next()) throw new SQLException("database foreign key check failed");
            }
        } catch (SQLException failure) {
            throw storage(StorageException.Code.STORAGE_CONFLICT,
                    "database migration checkpoint validation failed", failure);
        }
    }

    /** 备份用独立 datasource 回读，验证过程中不运行 migration 或修改主数据库。 */
    private static void validateBackup(Path backup, String expectedVersion) {
        if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
            throw storage(StorageException.Code.STORAGE_CONFLICT,
                    "database migration backup is unavailable", null);
        }
        SQLiteDataSource backupSource = new SQLiteDataSource();
        backupSource.setUrl("jdbc:sqlite:" + backup);
        validateDatabase(backupSource, expectedVersion);
    }

    /**
     * 读取最后一个成功版本并拒绝失败 history；不存在 history 表仅表示真正 fresh 数据库，
     * 任何其它 SQL 错误都作为损坏而不是版本 0 处理。
     */
    private static String readSchemaVersion(SQLiteDataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return readSchemaVersion(connection);
        } catch (SQLException failure) {
            throw storage(StorageException.Code.STORAGE_CONFLICT,
                    "cannot read database schema version", failure);
        }
    }

    /** 连接级版本读取让 backup、main 和测试 fixture 共享完全相同的 history 规则。 */
    private static String readSchemaVersion(Connection connection) throws SQLException {
        if (!historyExists(connection)) return "0";
        try (Statement statement = connection.createStatement();
             ResultSet failed = statement.executeQuery(
                     "SELECT COUNT(*) FROM flyway_schema_history WHERE success<>1")) {
            if (!failed.next() || failed.getLong(1) != 0) {
                throw new SQLException("Flyway history contains a failed migration");
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet current = statement.executeQuery(
                     "SELECT version FROM flyway_schema_history WHERE success=1 "
                             + "ORDER BY installed_rank DESC LIMIT 1")) {
            if (!current.next()) return "0";
            return requireVersion(current.getString(1));
        }
    }

    /** 仅 sqlite_master 中的正式表名可以证明 Flyway history 存在，不把任意查询失败当作 fresh。 */
    private static boolean historyExists(Connection connection) throws SQLException {
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, "flyway_schema_history");
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /** marker 采用固定 UTF-8 行协议，避免为启动检查点创建第二套 JSON mapper 配置。 */
    private void writeMarker(Marker marker) {
        validateMarker(marker);
        byte[] bytes = marker.encode();
        Path path = markerPath();
        Path staging = temporarySibling(path, ".marker-");
        try {
            writeForced(staging, bytes);
            moveAtomic(staging, path, true);
            Marker readback = readMarker();
            if (!marker.equals(readback)) throw new IOException("marker readback mismatch");
        } catch (IOException failure) {
            throw storage(StorageException.Code.IO, "cannot publish database migration checkpoint", failure);
        } finally {
            deleteQuietly(staging);
        }
    }

    /** marker 字段、顺序和值域全部闭集校验，损坏 marker 不允许静默忽略或宽松恢复。 */
    private Marker readMarker() {
        Path path = markerPath();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw storage(StorageException.Code.STORAGE_CONFLICT,
                    "database migration checkpoint is not a regular file", null);
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0 || bytes[bytes.length - 1] != '\n') {
                throw new IllegalArgumentException("checkpoint must end with LF");
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("checkpoint must use canonical LF");
            }
            Marker marker = Marker.decode(List.of(text.split("\\n", -1)));
            validateMarker(marker);
            return marker;
        } catch (IOException | IllegalArgumentException failure) {
            throw storage(StorageException.Code.STORAGE_CONFLICT,
                    "database migration checkpoint is invalid", failure);
        }
    }

    /** marker 必须只引用同目录派生的版本化备份，并使用完整 SHA-256 小写十六进制。 */
    private static void validateMarker(Marker marker) {
        if (!STATES.contains(marker.state())
            || versionNumber(marker.sourceVersion()) >= versionNumber(marker.targetVersion())
            || !marker.backupFile().matches("[^/\\\\]+\\.migration-v[0-9]+-to-v[0-9]+\\.backup")
            || !marker.backupSha256().matches("[0-9a-f]{64}")) {
            throw storage(StorageException.Code.STORAGE_CONFLICT,
                    "database migration checkpoint fields are invalid", null);
        }
    }

    /** Flyway 版本当前只允许正整数，避免比较或路径派生接受旧别名和限定符。 */
    private static String requireVersion(String version) {
        if (version == null || !version.matches("[1-9][0-9]*")) {
            throw storage(StorageException.Code.STORAGE_CONFLICT,
                    "database schema version is invalid", null);
        }
        return version;
    }

    /** 版本比较使用长整型并拒绝溢出，让高版本数据库稳定失败关闭。 */
    private static long versionNumber(String version) {
        try {
            return Long.parseLong(requireVersion(version));
        } catch (NumberFormatException failure) {
            throw storage(StorageException.Code.STORAGE_CONFLICT,
                    "database schema version is outside the supported range", failure);
        }
    }

    /** 版本化备份名不包含绝对路径，方便用户识别并阻止 marker 路径穿越。 */
    private Path backupPath(String sourceVersion) {
        String name = database.getFileName() + ".migration-v" + requireVersion(sourceVersion)
                + "-to-v" + targetVersion + ".backup";
        return database.resolveSibling(name);
    }

    /** migration marker 与数据库相邻，使移动数据目录时恢复点仍保持同一所有权边界。 */
    private Path markerPath() {
        return database.resolveSibling(database.getFileName() + MARKER_SUFFIX);
    }

    /** SQLite WAL 文件名由 JDBC 规范固定派生，不接受外部路径输入。 */
    private Path walPath() {
        return database.resolveSibling(database.getFileName() + "-wal");
    }

    /** SQLite shared-memory 文件与 WAL 同生命周期，恢复时必须一并清除。 */
    private Path shmPath() {
        return database.resolveSibling(database.getFileName() + "-shm");
    }

    /** 临时文件只在目标同目录创建，保证最终 publication 可以要求原子 rename。 */
    private static Path temporarySibling(Path target, String prefix) {
        try {
            Path parent = target.getParent();
            Path filename = target.getFileName();
            if (parent == null || filename == null) {
                throw storage(StorageException.Code.INVALID_CONFIGURATION,
                        "database migration staging path is unavailable", null);
            }
            return Files.createTempFile(parent, "." + filename + prefix, ".tmp");
        } catch (IOException failure) {
            throw storage(StorageException.Code.IO, "cannot create database migration staging file", failure);
        }
    }

    /** 原子替换是恢复准入条件，不支持时直接失败并保留 backup/marker 供人工恢复。 */
    private static void moveAtomic(Path source, Path target, boolean replace) throws IOException {
        try {
            if (replace) {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException failure) {
            throw new IOException("atomic database migration publication is unavailable", failure);
        }
    }

    /** marker 正文在关闭前强制刷盘，避免 rename 只发布了缓存中的部分内容。 */
    private static void writeForced(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    /** SQLite backup API 完成后再强制刷盘，确保 PREPARED 不引用尚未落盘的恢复副本。 */
    private static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    /** SHA-256 只用于恢复点身份，异常不包含路径或数据库内容。 */
    private static String digest(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw storage(StorageException.Code.IO, "cannot verify database migration backup", failure);
        }
    }

    /** 临时文件清理是 best effort；权威 marker 和 backup 从不经此路径删除。 */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A stale staging file is never accepted as a checkpoint on the next startup.
        }
    }

    /** 所有异常只暴露稳定分类和脱敏阶段，不携带数据库路径、SQL 或内容。 */
    private static StorageException storage(StorageException.Code code, String message, Throwable cause) {
        return cause == null ? new StorageException(code, message) : new StorageException(code, message, cause);
    }

    /** marker 只保存恢复所需的最小元数据，不记录绝对路径、时间或用户内容。 */
    private record Marker(String state, String sourceVersion, String targetVersion,
                          String backupFile, String backupSha256) {
        /** 状态推进只改变 state，其余恢复身份必须保持逐字节相同。 */
        Marker withState(String nextState) {
            return new Marker(nextState, sourceVersion, targetVersion, backupFile, backupSha256);
        }

        /** 固定行序列便于 Native/JVM 一致回读，并避免 Properties 的时间戳与转义差异。 */
        byte[] encode() {
            String value = String.join("\n",
                    "format=" + FORMAT,
                    "state=" + state,
                    "source_version=" + sourceVersion,
                    "target_version=" + targetVersion,
                    "backup_file=" + backupFile,
                    "backup_sha256=" + backupSha256,
                    "");
            return value.getBytes(StandardCharsets.UTF_8);
        }

        /** 只接受六个固定字段及末尾 LF，不容忍未知字段、重排或重复键。 */
        static Marker decode(List<String> lines) {
            if (lines.size() != 7 || !lines.get(6).isEmpty()
                || !("format=" + FORMAT).equals(lines.get(0))) {
                throw new IllegalArgumentException("invalid marker structure");
            }
            return new Marker(value(lines.get(1), "state"),
                    value(lines.get(2), "source_version"),
                    value(lines.get(3), "target_version"),
                    value(lines.get(4), "backup_file"),
                    value(lines.get(5), "backup_sha256"));
        }

        /** 每一行只允许一个精确键前缀，值域由外层统一校验。 */
        private static String value(String line, String key) {
            String prefix = key + "=";
            if (!line.startsWith(prefix) || line.length() == prefix.length()) {
                throw new IllegalArgumentException("invalid marker field");
            }
            return line.substring(prefix.length());
        }
    }
}
