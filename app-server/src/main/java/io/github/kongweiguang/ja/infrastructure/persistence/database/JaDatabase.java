// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.aot.AotSideEffectGuard;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SchemaMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSession;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/**
 * 单 SQLite datasource 与 Flyway 生命周期 owner；MyBatis factory 只由官方 Solon plugin 创建。
 */
public final class JaDatabase implements AutoCloseable {
    private final Path databasePath;
    private final SQLiteDataSource dataSource;
    private final DatabaseLease lease;
    private final java.util.concurrent.atomic.AtomicReference<SqlSessionFactory> checkpointSessions =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * datasource、文件 lease 与 Flyway schema 作为一个不可拆分的生命周期资源。
     */
    private JaDatabase(Path databasePath, SQLiteDataSource dataSource, DatabaseLease lease) {
        this.databasePath = databasePath;
        this.dataSource = dataSource;
        this.lease = lease;
    }

    /**
     * 打开唯一数据库并把 schema 准入完全交给 Flyway history/checksum。
     *
     * <p>空库按固定 Flyway 迁移闭集初始化；升级到 V7 前先在 lease 内做 WAL 一致快照，再执行
     * 会话归属事务。非空未知 schema、版本漂移、checksum 冲突和损坏数据库都失败关闭，不 repair
     * 或删除数据库内容。</p>
     */
    public static JaDatabase open(DatabaseConfig config) {
        Objects.requireNonNull(config, "config");
        AotSideEffectGuard.requireRuntimeIo();
        DatabaseLease lease = null;
        try {
            lease = DatabaseLease.acquire(config.databasePath());
            SQLiteDataSource source = dataSource(config);
            Flyway flyway = Flyway.configure().dataSource(source).locations(new String[0])
                    .resourceProvider(JaFlywayResources.provider()).baselineOnMigrate(false)
                    .ignoreMigrationPatterns(new String[0])
                    .validateMigrationNaming(true).load();
            backupBeforeSchemaRebuildIfNeeded(flyway, source, config.databasePath());
            migrateAndVerify(flyway, source, config);
            return new JaDatabase(config.databasePath(), source, lease);
        } catch (StorageException failure) {
            if (lease != null) lease.close();
            throw failure;
        } catch (FlywayException | SQLException invalidDatabase) {
            if (lease != null) lease.close();
            throw new StorageException(StorageException.Code.STORAGE_CONFLICT,
                    "database schema or integrity check failed", invalidDatabase);
        } catch (Exception failure) {
            if (lease != null) lease.close();
            throw new StorageException(StorageException.Code.IO, "cannot open Ja database", failure);
        }
    }

    /**
     * Flyway 执行和 SQLite 完整性回读都在进程 lease 内完成；任何 schema 漂移、future history、
     * checksum 冲突或损坏都归类为稳定的存储冲突，调用方不能把它当作可 repair 的普通 I/O。
     */
    private static void migrateAndVerify(Flyway flyway, SQLiteDataSource source, DatabaseConfig config) {
        try {
            flyway.migrate();
            MigrationInfo current = flyway.info().current();
            if (current == null || current.getVersion() == null
                || !"15".equals(current.getVersion().getVersion())
                || flyway.info().pending().length != 0) {
                throw new StorageException(StorageException.Code.STORAGE_CONFLICT,
                        "database schema is not the current Ja V15");
            }
            LegacySessionWorkspaceMigration.migrate(source, config);
            verifySqliteIntegrity(source);
        } catch (StorageException failure) {
            throw failure;
        } catch (FlywayException | SQLException failure) {
            throw new StorageException(StorageException.Code.STORAGE_CONFLICT,
                    "database schema or integrity check failed", failure);
        }
    }

    /**
     * V7 重属旧历史、V10 重建消息与 Usage、V11/V12 清理旧预算、V13 扩展验收审计、
     * V14 建立公开正文分页索引、V15 建立输入操作回执；升级前备份。
     * VACUUM INTO 包含已提交 WAL 页，直接复制主数据库文件不能保证这个边界。
     */
    private static void backupBeforeSchemaRebuildIfNeeded(Flyway flyway, SQLiteDataSource source, Path databasePath)
            throws SQLException, java.io.IOException {
        MigrationInfo current = flyway.info().current();
        boolean upgradingExistingSchemaToV7 = current != null && current.getVersion() != null
                && current.getVersion().getVersion() != null
                && current.getVersion().compareTo(MigrationVersion.fromVersion("7")) < 0
                && java.util.Arrays.stream(flyway.info().pending())
                .anyMatch(migration -> migration.getVersion() != null
                        && "7".equals(migration.getVersion().getVersion()));
        boolean upgradingExistingSchemaToV10 = current != null && current.getVersion() != null
                && current.getVersion().compareTo(MigrationVersion.fromVersion("10")) < 0
                && java.util.Arrays.stream(flyway.info().pending())
                .anyMatch(migration -> migration.getVersion() != null
                        && "10".equals(migration.getVersion().getVersion()));
        boolean upgradingExistingSchemaToV11 = current != null && current.getVersion() != null
                && current.getVersion().compareTo(MigrationVersion.fromVersion("11")) < 0
                && java.util.Arrays.stream(flyway.info().pending())
                .anyMatch(migration -> migration.getVersion() != null
                        && "11".equals(migration.getVersion().getVersion()));
        boolean upgradingExistingSchemaToV12 = current != null && current.getVersion() != null
                && current.getVersion().compareTo(MigrationVersion.fromVersion("12")) < 0
                && java.util.Arrays.stream(flyway.info().pending())
                .anyMatch(migration -> migration.getVersion() != null
                        && "12".equals(migration.getVersion().getVersion()));
        boolean upgradingExistingSchemaToV13 = current != null && current.getVersion() != null
                && current.getVersion().compareTo(MigrationVersion.fromVersion("13")) < 0
                && java.util.Arrays.stream(flyway.info().pending())
                .anyMatch(migration -> migration.getVersion() != null
                        && "13".equals(migration.getVersion().getVersion()));
        boolean upgradingExistingSchemaToV14 = current != null && current.getVersion() != null
                && current.getVersion().compareTo(MigrationVersion.fromVersion("14")) < 0
                && java.util.Arrays.stream(flyway.info().pending())
                .anyMatch(migration -> migration.getVersion() != null
                        && "14".equals(migration.getVersion().getVersion()));
        boolean upgradingExistingSchemaToV15 = current != null && current.getVersion() != null
                && current.getVersion().compareTo(MigrationVersion.fromVersion("15")) < 0
                && java.util.Arrays.stream(flyway.info().pending())
                .anyMatch(migration -> migration.getVersion() != null
                        && "15".equals(migration.getVersion().getVersion()));
        if (!upgradingExistingSchemaToV7 && !upgradingExistingSchemaToV10
                && !upgradingExistingSchemaToV11 && !upgradingExistingSchemaToV12
                && !upgradingExistingSchemaToV13 && !upgradingExistingSchemaToV14
                && !upgradingExistingSchemaToV15) return;
        Path parent = databasePath.getParent();
        Path databaseName = databasePath.getFileName();
        if (parent == null || databaseName == null) {
            throw new SQLException("database backup location is unavailable");
        }
        String backupName = databaseName + (upgradingExistingSchemaToV7 ? ".pre-v7-"
                : upgradingExistingSchemaToV10 ? ".pre-v10-"
                : upgradingExistingSchemaToV11 ? ".pre-v11-"
                : upgradingExistingSchemaToV12 ? ".pre-v12-"
                : upgradingExistingSchemaToV13 ? ".pre-v13-"
                : upgradingExistingSchemaToV14 ? ".pre-v14-" : ".pre-v15-")
                + java.util.UUID.randomUUID() + ".bak";
        Path backup = parent.resolve(backupName);
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement("VACUUM INTO ?")) {
            statement.setString(1, backup.toString());
            statement.execute();
        }
        if (!java.nio.file.Files.isRegularFile(backup) || java.nio.file.Files.size(backup) == 0) {
            throw new SQLException("database backup was not created");
        }
        SQLiteDataSource snapshot = new SQLiteDataSource();
        snapshot.setUrl("jdbc:sqlite:" + backup);
        verifySqliteIntegrity(snapshot);
    }

    /**
     * SQLite 自身而非 Mapper 投影裁决物理完整性；完整性或外键检查出现任意结果行即拒绝启动，
     * 且不会调用 repair、删除文件或改变 schema history。
     */
    private static void verifySqliteIntegrity(SQLiteDataSource source) throws SQLException {
        try (java.sql.Connection connection = source.getConnection();
             java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet integrity = statement.executeQuery("PRAGMA integrity_check")) {
            if (!integrity.next() || !"ok".equalsIgnoreCase(integrity.getString(1)) || integrity.next()) {
                throw new StorageException(StorageException.Code.STORAGE_CONFLICT,
                        "database integrity check failed");
            }
        }
        try (java.sql.Connection connection = source.getConnection();
             java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet violations = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (violations.next()) {
                throw new StorageException(StorageException.Code.STORAGE_CONFLICT,
                        "database foreign key check failed");
            }
        }
    }

    /**
     * SQLite durability、FK、IMMEDIATE transaction 与 bounded busy timeout 在 datasource 层固定。
     */
    private static SQLiteDataSource dataSource(DatabaseConfig config) {
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true);
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqlite.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        sqlite.setBusyTimeout(Math.toIntExact(config.busyTimeout().toMillis()));
        sqlite.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        SQLiteDataSource source = new SQLiteDataSource(sqlite);
        source.setUrl("jdbc:sqlite:" + config.databasePath());
        return source;
    }

    /**
     * 返回已规范化的唯一 SQLite 文件，仅供 composition 和诊断读取。
     */
    public Path databasePath() {
        return databasePath;
    }

    /**
     * 官方 MyBatis-Solon plugin 直接消费唯一 datasource bean。
     */
    public SQLiteDataSource dataSource() {
        return dataSource;
    }

    /**
     * composition 在 named ja factory 可用后只绑定一次，close 不创建第二套 MyBatis 配置。
     */
    public void bindWalCheckpoint(SqlSessionFactory sessions) {
        Objects.requireNonNull(sessions, "sessions");
        if (!checkpointSessions.compareAndSet(null, sessions)) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "WAL checkpoint factory is already bound");
        }
    }

    /**
     * 无事务地完成 WAL truncate 后才释放 lease；busy 时保留 owner 以阻止不安全重开。
     * 该方法只由 RuntimeResourceLifecycle 调用，不能标注 Solon @Destroy，否则 forced shutdown
     * 会绕过 Turn quiescence fence 直接关闭仍被 worker 使用的数据库。
     */
    @Override
    public void close() {
        if (closed.get()) return;
        SqlSessionFactory sessions = checkpointSessions.get();
        if (sessions == null) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "WAL checkpoint factory was not bound");
        }
        try (SqlSession session = sessions.openSession(true)) {
            PersistenceRecords.WalCheckpointRow result = session.getMapper(SchemaMapper.class).checkpointWal();
            if (result.busy() != 0) {
                throw new StorageException(StorageException.Code.TRANSACTION,
                        "WAL checkpoint is blocked by an active transaction");
            }
        } catch (StorageException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new StorageException(StorageException.Code.TRANSACTION,
                    "WAL checkpoint failed", failure);
        }
        if (closed.compareAndSet(false, true)) lease.close();
    }
}
