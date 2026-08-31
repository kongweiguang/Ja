// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.transaction;

import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.Objects;

/**
 * 每个持久化动作的显式 Unit of Work；生产模式由 Solon transaction owner 提交或回滚。
 */
public final class MybatisUnitOfWork {
    private final SqlSessionFactory sessions;
    private final SessionOwner owner;

    /**
     * 生产 factory 必须来自官方 MyBatis-Solon adapter，禁止 Repository 自行提交事务。
     */
    public MybatisUnitOfWork(SqlSessionFactory sessions) {
        this(sessions, MybatisUnitOfWork::solonTransaction);
    }

    /**
     * package seam 只允许聚焦测试替换 transaction owner，生产 composition 不暴露第二实现。
     */
    public MybatisUnitOfWork(SqlSessionFactory sessions, SessionOwner owner) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    /**
     * 在同一 SqlSession 内执行全部 Mapper 调用，异常路径保证整个语义边界回滚。
     */
    public <T> T required(Work<T> work) {
        Objects.requireNonNull(work, "work");
        return owner.execute(sessions, work);
    }

    /**
     * Solon transaction owner 是生产唯一 commit/rollback 边界。
     */
    private static <T> T solonTransaction(SqlSessionFactory sessions, Work<T> work) {
        return PersistenceTransactions.required(() -> {
            try (SqlSession session = sessions.openSession()) {
                return work.apply(PersistenceMappers.open(session));
            }
        });
    }

    /**
     * Unit of Work 内的 Mapper 操作，返回结果必须在 session 关闭前完成复制。
     */
    @FunctionalInterface
    public interface Work<T> {
        /**
         * 回调不得泄露 Mapper 或 SqlSession，事务结束后所有结果必须已复制。
         */
        T apply(PersistenceMappers mappers) throws Exception;
    }

    /**
     * 可替换的事务 owner 仅用于用真实 SQLite 验证提交和回滚。
     */
    @FunctionalInterface
    public interface SessionOwner {
        /**
         * owner 决定事务提交/回滚，业务 work 只能看到同 session 的 Mapper。
         */
        <T> T execute(SqlSessionFactory sessions, Work<T> work);
    }
}
