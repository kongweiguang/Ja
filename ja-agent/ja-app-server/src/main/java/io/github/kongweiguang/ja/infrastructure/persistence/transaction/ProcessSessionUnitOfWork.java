// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.transaction;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * 将进程会话的原生 SQL 限定在 Ja 唯一数据库事务 owner 内。
 */
public final class ProcessSessionUnitOfWork {
    private final SqlSessionFactory sessions;

    /**
     * 复用命名 SqlSessionFactory，避免进程 ledger 自建第二个 SQLite 连接池或提交边界。
     */
    public ProcessSessionUnitOfWork(SqlSessionFactory sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    /**
     * 在 Solon required 事务中执行一组同步 SQL，返回值必须在 SqlSession 关闭前复制完成。
     */
    public <T> T required(SqlWork<T> work) {
        SqlWork<T> operation = Objects.requireNonNull(work, "work");
        return PersistenceTransactions.required(() -> {
            try (SqlSession session = sessions.openSession()) {
                return operation.apply(session.getConnection());
            }
        });
    }

    /**
     * 同步 SQL 回调不得泄露 Connection 或异步越过事务生命周期。
     */
    @FunctionalInterface
    public interface SqlWork<T> {
        /**
         * 完成当前事务内的单个同步读写单元。
         */
        T apply(Connection connection) throws SQLException;
    }
}
