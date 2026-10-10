// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.aot.AotSideEffectGuard;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SchemaMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 数据库 lease 覆盖迁移、可恢复切换与全部业务消费者，确保 sidecar 生命周期唯一。
 */
public final class JaDatabase implements AutoCloseable {
    private static final Set<String> NON_JA_TABLES = Set.of(
            "agent_commit_receipt",
            "agent_conversation",
            "agent_event",
            "agent_execution",
            "agent_lease",
            "agent_operation_receipt",
            "agent_record",
            "agent_schema_version",
            "flyway_schema_history");

    private final Path databasePath;
    private final SQLiteDataSource dataSource;
    private final DatabaseLease lease;
    private final StorageRecovery.Result recovery;
    private final AtomicReference<SqlSessionFactory> checkpointSessions = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 仅发布完成恢复的正式数据源，候选文件不能进入服务依赖图。 */
    private JaDatabase(DatabaseConfig config, DatabaseLease lease, StorageRecovery.Result recovery) {
        this.databasePath = config.databasePath();
        this.dataSource = dataSource(config);
        this.lease = lease;
        this.recovery = recovery;
    }

    /**
     * 在组装运行时前完成恢复；构造失败或虚拟机错误时也释放已经取得的唯一 lease。
     */
    public static JaDatabase open(DatabaseConfig config) {
        Objects.requireNonNull(config, "config");
        AotSideEffectGuard.requireRuntimeIo();
        DatabaseLease lease = DatabaseLease.acquire(config.databasePath(), config.busyTimeout());
        try {
            return new JaDatabase(config, lease, StorageRecovery.prepare(config));
        } catch (RuntimeException | Error failure) {
            lease.close();
            throw failure;
        }
    }

    /** 显式资源清单让 JVM 与 Native Image 使用完全相同的迁移链。 */
    static Flyway flyway(SQLiteDataSource source) {
        return Flyway.configure().dataSource(source).locations(new String[0])
                .resourceProvider(JaFlywayResources.provider())
                .baselineOnMigrate(false)
                .validateMigrationNaming(true).load();
    }

    /** 新库仅执行当前 V1；验证和物理完整性全部完成后才允许恢复 owner 发布数据源。 */
    static void migrateAndVerify(SQLiteDataSource source, DatabaseConfig config) throws SQLException {
        Objects.requireNonNull(config, "config");
        flyway(source).migrate();
        assertCurrentBaseline(source);
        verifySqliteIntegrity(source);
        checkpoint(source);
    }

    /**
     * Ja 表名由 V1 DDL 解析器提供；这里只补充由 Agent Store 与 Flyway 各自拥有的固定表。
     */
    static Set<String> expectedTableNames() {
        Set<String> expected = new HashSet<>(JaSchemaContract.expectedTableNames());
        expected.addAll(NON_JA_TABLES);
        return Set.copyOf(expected);
    }

    /**
     * 已存在的库只验证唯一 V1/checksum 与 Core、产品实际结构，不安装 pending migration 或猜测旧布局。
     * Store 构造只读验证，禁止此准入检查暗中补建缺表；成功 ToolResult 图片的独立归属表也属于固定基线。
     * 临时 Store 仅用于构造校验并自动关闭，不调用业务读写接口。
     */
    static void assertCurrentBaseline(SQLiteDataSource source) throws SQLException {
        Flyway current = flyway(source);
        org.flywaydb.core.api.MigrationInfo[] applied = current.info().applied();
        if (!current.validateWithResult().validationSuccessful || current.info().pending().length != 0
                || applied.length != 1 || applied[0].getVersion() == null
                || !"1".equals(applied[0].getVersion().getVersion()) || !applied[0].getState().isApplied()) {
            throw new StorageException(StorageException.Code.STORAGE_CONFLICT, "SCHEMA_VALIDATION_FAILED");
        }
        try (Connection connection = source.getConnection();
             PreparedStatement tables = connection.prepareStatement(
                     "SELECT name FROM sqlite_schema WHERE type='table' AND name NOT LIKE 'sqlite_%'");
             ResultSet rows = tables.executeQuery()) {
            Set<String> names = new HashSet<>();
            while (rows.next()) {
                names.add(rows.getString(1));
            }
            if (!names.equals(expectedTableNames())) {
                throw new StorageException(StorageException.Code.STORAGE_CONFLICT, "SCHEMA_VALIDATION_FAILED");
            }
        }
        try (io.github.kongweiguang.ja.agent.store.local.infrastructure.SQLiteAgentStore ignored =
                     new io.github.kongweiguang.ja.agent.store.local.infrastructure.SQLiteAgentStore(source)) {
            // Store 的验证包含 marker、字段与复合键，不读取业务正文。
        }
        try (Connection connection = source.getConnection()) {
            JaSchemaContract.verify(connection);
        }
    }

    /** SQLite 在候选库发布前检查物理页、外键关系和真实读写能力。 */
    static void verifySqliteIntegrity(SQLiteDataSource source) throws SQLException {
        try (Connection connection = source.getConnection(); Statement query = connection.createStatement()) {
            try (ResultSet rows = query.executeQuery("PRAGMA integrity_check")) {
                if (!rows.next() || !"ok".equalsIgnoreCase(rows.getString(1)) || rows.next()) {
                    throw new SQLException("database integrity check failed");
                }
            }
            try (ResultSet rows = query.executeQuery("PRAGMA foreign_key_check")) {
                if (rows.next()) {
                    throw new SQLException("database foreign key check failed");
                }
            }
            query.execute("CREATE TEMP TABLE ja_storage_probe(value INTEGER)");
            connection.setAutoCommit(false);
            query.executeUpdate("INSERT INTO ja_storage_probe VALUES(1)");
            try (ResultSet rows = query.executeQuery("SELECT value FROM ja_storage_probe")) {
                if (!rows.next() || rows.getInt(1) != 1) {
                    throw new SQLException("database read/write probe failed");
                }
            }
            connection.rollback();
            connection.setAutoCommit(true);
            query.execute("DROP TABLE ja_storage_probe");
        }
    }

    /** 完成 WAL checkpoint 后，候选库才能按单一主文件原子安装。 */
    static void checkpoint(SQLiteDataSource source) throws SQLException {
        try (Connection connection = source.getConnection(); Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            if (!rows.next() || rows.getInt(1) != 0) {
                throw new SQLException("database checkpoint is busy");
            }
        }
    }

    /** 每条连接都固定启用外键、完整同步持久性和有界锁等待。 */
    static SQLiteDataSource dataSource(DatabaseConfig config) {
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

    /** 启动前只读检查旧库，避免拒绝不兼容 schema 时切换其 journal mode 或改写原文件。 */
    static SQLiteDataSource inspectionDataSource(DatabaseConfig config) {
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.setReadOnly(true);
        sqlite.setBusyTimeout(Math.toIntExact(config.busyTimeout().toMillis()));
        SQLiteDataSource source = new SQLiteDataSource(sqlite);
        source.setUrl("jdbc:sqlite:" + config.databasePath());
        return source;
    }

    /** 返回诊断身份路径；调用方不能借此选择恢复目标。 */
    public Path databasePath() { return databasePath; }

    /** MyBatis 只能消费恢复已完成后的唯一正式数据源。 */
    public SQLiteDataSource dataSource() { return dataSource; }

    /** 返回可跨提示关闭和进程重启读取的最近恢复结果。 */
    public StorageRecovery.Result recovery() { return recovery; }

    /** 仅在 MyBatis 组装后绑定一次；部分启动仍可通过直接 JDBC 关闭。 */
    public void bindWalCheckpoint(SqlSessionFactory sessions) {
        if (!checkpointSessions.compareAndSet(null, Objects.requireNonNull(sessions, "sessions"))) {
            throw new StorageException(StorageException.Code.INVALID_STATE, "WAL checkpoint factory is already bound");
        }
    }

    /**
     * 调用方先停止数据库消费者；checkpoint 失败仍释放 lease，避免关闭错误永久占住数据库 owner。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            SqlSessionFactory sessions = checkpointSessions.get();
            if (sessions == null) {
                checkpoint(dataSource);
            }
            else {
                try (SqlSession session = sessions.openSession(true)) {
                PersistenceRecords.WalCheckpointRow result = session.getMapper(SchemaMapper.class).checkpointWal();
                if (result.busy() != 0) {
                    throw new SQLException("database checkpoint is busy");
                }
            }
            }
        } catch (SQLException | RuntimeException failure) {
            throw new StorageException(StorageException.Code.TRANSACTION, "WAL checkpoint failed", failure);
        } finally {
            lease.close();
        }
    }
}
