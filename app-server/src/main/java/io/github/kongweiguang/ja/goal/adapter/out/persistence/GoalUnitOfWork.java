// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.goal.port.out.GoalRepositoryException;
import org.apache.ibatis.session.SqlSessionFactory;
import org.noear.solon.data.annotation.Transaction;
import org.noear.solon.data.tran.TranIsolation;
import org.noear.solon.data.tran.TranPolicy;
import org.noear.solon.data.tran.TranUtils;

import java.lang.annotation.Annotation;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Goal JDBC 聚合的 required transaction owner；不进入共享 Mapper 双轨即可保持单事务。 */
public final class GoalUnitOfWork {
    private static final Transaction REQUIRED = new RequiredTransaction();
    private final SqlSessionFactory sessions;
    private final SessionOwner owner;

    /** 生产使用 SolonManagedTransactionFactory 和现有 datasource transaction manager。 */
    public GoalUnitOfWork(SqlSessionFactory sessions) {
        this(sessions, GoalUnitOfWork::solonTransaction);
    }

    /** 测试可替换 commit owner，但仍只能接收同一 session 的 JDBC connection。 */
    public GoalUnitOfWork(SqlSessionFactory sessions, SessionOwner owner) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    /** 每个聚合 mutation 的所有 SQL 必须在此 callback 内完成。 */
    public <T> T required(Work<T> work) {
        return owner.execute(sessions, Objects.requireNonNull(work, "work"));
    }

    /** 生产事务复用 Solon required 上下文，不在 Repository 自行 commit。 */
    private static <T> T solonTransaction(SqlSessionFactory sessions, Work<T> work) {
        AtomicReference<T> result = new AtomicReference<>();
        try {
            TranUtils.execute(REQUIRED, () -> {
                try (org.apache.ibatis.session.SqlSession session = sessions.openSession()) {
                    result.set(work.apply(session.getConnection()));
                }
            });
            return result.get();
        } catch (Throwable failure) {
            throw transactionFailure(failure);
        }
    }

    /**
     * Goal 的 CAS、状态与批准冲突属于公开领域结果；事务桥只负责回滚，只有未知基础设施异常
     * 才能降级成存储故障，否则 JA-RPC 无法给用户提供正确恢复动作。
     */
    static RuntimeException transactionFailure(Throwable failure) {
        if (failure instanceof StorageException storage) return storage;
        if (failure instanceof GoalRepositoryException goal) return goal;
        return new StorageException(StorageException.Code.TRANSACTION,
                "Goal transaction failed", failure);
    }

    /** transaction 内工作只持有当前 MyBatis session 的 JDBC connection，禁止自行提交。 */
    @FunctionalInterface
    public interface Work<T> {
        /** callback 返回前必须复制所有 JDBC 结果，且不得保存 connection 引用。 */
        T apply(Connection connection) throws SQLException, JsonProcessingException;
    }

    /** 聚焦 SQLite 测试控制 commit/rollback，生产不替换默认 owner。 */
    @FunctionalInterface
    public interface SessionOwner {
        /** owner 保证异常回滚整个 Goal 聚合 mutation。 */
        <T> T execute(SqlSessionFactory sessions, Work<T> work);
    }

    /** required 元数据不依赖代理，保证 composition 直接构造时仍开启事务。 */
    private static final class RequiredTransaction implements Transaction {
        /** Goal mutation 必须加入既有事务。 */
        @Override public TranPolicy policy() { return TranPolicy.required; }
        /** 使用 datasource 的 SQLite isolation。 */
        @Override public TranIsolation isolation() { return TranIsolation.unspecified; }
        /** Goal 聚合包含写入。 */
        @Override public boolean readOnly() { return false; }
        /** 诊断只暴露边界名。 */
        @Override public String message() { return "ja-goal-persistence"; }
        /** 满足运行时 annotation contract。 */
        @Override public Class<? extends Annotation> annotationType() { return Transaction.class; }
    }
}
