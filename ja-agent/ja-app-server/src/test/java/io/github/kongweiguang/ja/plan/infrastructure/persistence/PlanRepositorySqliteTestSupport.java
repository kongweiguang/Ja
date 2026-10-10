// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PlanMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.Objects;

/** 让跨 package 持久化集成测试复用 Plan 的真实 unit-of-work 与 SQLite 事务注入点。 */
public final class PlanRepositorySqliteTestSupport {
    private static final PlanUnitOfWork.SessionOwner SQLITE_TRANSACTION_OWNER = new PlanUnitOfWork.SessionOwner() {
        /** 每次调用只持有一个真实 SQLite session，提交失败或业务失败均回滚本次 Plan mutation。 */
        @Override
        public <T> T execute(SqlSessionFactory sessions, PlanUnitOfWork.Work<T> work) {
            try (SqlSession session = sessions.openSession()) {
                try {
                    T result = work.apply(new PlanUnitOfWork.Mappers(session.getMapper(PlanMapper.class)));
                    session.commit();
                    return result;
                } catch (Throwable failure) {
                    session.rollback();
                    if (failure instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    if (failure instanceof Error error) {
                        throw error;
                    }
                    throw new IllegalStateException("Plan test transaction failed", failure);
                }
            }
        }
    };

    /** 测试源码只能通过此入口使用包内事务注入 seam，不扩大生产构造器可见范围。 */
    private PlanRepositorySqliteTestSupport() {
    }

    /** 注入显式 SQLite commit/rollback owner，保持生产 PlanRepository 与 unit-of-work 实现不变。 */
    public static MybatisPlanRepository create(SqlSessionFactory sessions, ObjectMapper mapper) {
        return new MybatisPlanRepository(Objects.requireNonNull(sessions, "sessions"),
                Objects.requireNonNull(mapper, "mapper"), SQLITE_TRANSACTION_OWNER);
    }
}
