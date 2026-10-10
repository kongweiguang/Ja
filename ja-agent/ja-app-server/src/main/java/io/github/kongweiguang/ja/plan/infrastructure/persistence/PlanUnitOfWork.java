// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepositoryException;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.AgentStoreConnectionScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.noear.solon.data.annotation.Transaction;
import org.noear.solon.data.tran.TranIsolation;
import org.noear.solon.data.tran.TranPolicy;
import org.noear.solon.data.tran.TranUtils;

import java.lang.annotation.Annotation;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plan 适配器必须使用的事务 owner；所有 Mapper 共用一个 MyBatis session。
 */
final class PlanUnitOfWork {
    private static final Transaction REQUIRED = new RequiredTransaction();
    private final SqlSessionFactory sessions;
    private final SessionOwner owner;

    /**
     * 生产使用 SolonManagedTransactionFactory 和现有 datasource transaction manager。
     */
    PlanUnitOfWork(SqlSessionFactory sessions) {
        this(sessions, PlanUnitOfWork::solonTransaction);
    }

    /**
     * 测试可替换 commit owner，但只能在唯一 session 上调用 Mapper。
     */
    PlanUnitOfWork(SqlSessionFactory sessions, SessionOwner owner) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    /**
     * 生产事务复用 Solon required 上下文，不在 Repository 自行 commit。
     */
    private static <T> T solonTransaction(SqlSessionFactory sessions, Work<T> work) {
        AtomicReference<T> result = new AtomicReference<>();
        try {
            TranUtils.execute(REQUIRED, () -> {
                try (org.apache.ibatis.session.SqlSession session = sessions.openSession()) {
                    result.set(work.apply(mappers(session)));
                }
            });
            return result.get();
        } catch (Throwable failure) {
            throw transactionFailure(failure);
        }
    }

    /**
     * Plan 的 CAS、状态与批准冲突属于公开领域结果；事务桥只负责回滚，只有未知基础设施异常
     * 才能降级成存储故障，否则 JA-RPC 无法给用户提供正确恢复动作。
     */
    static RuntimeException transactionFailure(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof StorageException storage) {
            return storage;
        }
        if (failure instanceof PlanRepositoryException plan) {
            return plan;
        }
        return new StorageException(StorageException.Code.TRANSACTION,
                "Plan transaction failed", failure);
    }

    /**
     * 仅在事务回调内创建 Mapper，避免调用方意外持有跨事务代理。
     */
    private static Mappers mappers(org.apache.ibatis.session.SqlSession session) {
        return new Mappers(session.getMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.PlanMapper.class));
    }

    /**
     * 每个聚合 mutation 的所有 SQL 必须在此 callback 内完成。
     */
    <T> T required(Work<T> work) {
        Work<T> operation = Objects.requireNonNull(work, "work");
        if (AgentStoreConnectionScope.current().isEmpty()) {
            return owner.execute(sessions, operation);
        }
        try {
            try (org.apache.ibatis.session.SqlSession session = AgentStoreConnectionScope.openSession(sessions)) {
                return operation.apply(mappers(session));
            }
        } catch (Throwable failure) {
            throw transactionFailure(failure);
        }
    }

    /**
     * transaction 内工作只持有当前 MyBatis session 的 Plan Mapper，禁止自行提交。
     */
    @FunctionalInterface
    interface Work<T> {
        /**
         * callback 返回前必须完成全部同步 Mapper 调用，不得保存 session 或异步越过事务边界。
         */
        T apply(Mappers mappers) throws SQLException, JsonProcessingException;
    }

    /**
     * 聚焦 SQLite 测试控制 commit/rollback，生产不替换默认 owner。
     */
    @FunctionalInterface
    interface SessionOwner {
        /**
         * owner 保证异常回滚整个 Plan 聚合 mutation，并在工作结束后关闭 session。
         */
        <T> T execute(SqlSessionFactory sessions, Work<T> work);
    }

    /**
     * 从一个 SqlSession 创建事务作用域 Mapper，保持 Plan 与 Core 同事务。
     *
     * @param plan Plan的数据库映射器
     */
    record Mappers(io.github.kongweiguang.ja.infrastructure.persistence.mapper.PlanMapper plan) {
        /**
         * 拒绝不完整的事务边界，避免后续空指针掩盖组合根装配错误。
         */
        Mappers {
            Objects.requireNonNull(plan, "plan");
        }
    }

    /**
     * required 元数据不依赖代理，保证 composition 直接构造时仍开启事务。
     */
    private static final class RequiredTransaction implements Transaction {
        /**
         * Plan mutation 必须加入既有事务。
         */
        @Override
        public TranPolicy policy() {
            return TranPolicy.required;
        }

        /**
         * 使用 datasource 的 SQLite isolation。
         */
        @Override
        public TranIsolation isolation() {
            return TranIsolation.unspecified;
        }

        /**
         * Plan 聚合包含写入。
         */
        @Override
        public boolean readOnly() {
            return false;
        }

        /**
         * 诊断只暴露边界名。
         */
        @Override
        public String message() {
            return "ja-plan-persistence";
        }

        /**
         * 满足运行时 annotation contract。
         */
        @Override
        public Class<? extends Annotation> annotationType() {
            return Transaction.class;
        }
    }
}

