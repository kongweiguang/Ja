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
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
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
     * 打开唯一的新代际数据库。目录标记先于 SQLite 校验，并且首次 marker 只在 V1 成功后发布；
     * 生产路径不会探测、复制或重写旧数据。
     */
    public static JaDatabase open(DatabaseConfig config) {
        Objects.requireNonNull(config, "config");
        AotSideEffectGuard.requireRuntimeIo();
        DatabaseLease lease = null;
        try {
            StorageBaseline.Admission baseline = StorageBaseline.prepare(config.databasePath());
            lease = DatabaseLease.acquire(config.databasePath());
            baseline.verifyAfterLease();
            SQLiteDataSource source = dataSource(config);
            Flyway flyway = Flyway.configure().dataSource(source).locations(new String[0])
                    .resourceProvider(JaFlywayResources.provider()).baselineOnMigrate(false)
                    .validateMigrationNaming(true).load();
            new DatabaseMigrationRecovery(config.databasePath(), source,
                    JaFlywayResources.latestVersion()).migrate(flyway);
            baseline.complete();
            return new JaDatabase(config.databasePath(), source, lease);
        } catch (StorageException failure) {
            if (lease != null) lease.close();
            throw failure;
        } catch (Exception failure) {
            if (lease != null) lease.close();
            throw new StorageException(StorageException.Code.IO, "cannot open Ja database", failure);
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
