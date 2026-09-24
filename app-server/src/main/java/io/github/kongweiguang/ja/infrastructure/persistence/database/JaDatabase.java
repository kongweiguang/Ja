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
            backupBeforeV7UpgradeIfNeeded(flyway, source, config.databasePath());
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
                || !"7".equals(current.getVersion().getVersion())
                || flyway.info().pending().length != 0) {
                throw new StorageException(StorageException.Code.STORAGE_CONFLICT,
                        "database schema is not the current Ja V7");
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
     * V7 会重属旧会话历史，因此迁移前必须在独占 lease 内保留 SQLite 一致快照。
     *
     * V7 reassigns existing session history, so retain a SQLite-consistent snapshot under the exclusive lease
     * before Flyway starts; VACUUM INTO includes committed WAL pages, unlike copying the main database file.
     */
    private static void backupBeforeV7UpgradeIfNeeded(Flyway flyway, SQLiteDataSource source, Path databasePath)
            throws SQLException, java.io.IOException {
        MigrationInfo current = flyway.info().current();
        boolean upgradingExistingSchemaToV7 = current != null && current.getVersion() != null
                && current.getVersion().getVersion() != null
                && current.getVersion().compareTo(MigrationVersion.fromVersion("7")) < 0
                && java.util.Arrays.stream(flyway.info().pending())
                .anyMatch(migration -> migration.getVersion() != null
                        && "7".equals(migration.getVersion().getVersion()));
        if (!upgradingExistingSchemaToV7) return;
        Path parent = databasePath.getParent();
        Path databaseName = databasePath.getFileName();
        if (parent == null || databaseName == null) {
            throw new SQLException("database backup location is unavailable");
        }
        String backupName = databaseName + ".pre-v7-" + java.util.UUID.randomUUID() + ".bak";
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
