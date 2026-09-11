// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.conversation.domain.SubagentPolicy;
import io.github.kongweiguang.ja.conversation.port.out.SubagentPolicyRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.Objects;
import java.util.Optional;

/** SQLite 读取适配器；运行时只借用已冻结行，不重新解释全局设置。 */
public final class MybatisSubagentPolicyRepository implements SubagentPolicyRepository {
    private final MybatisUnitOfWork transactions;

    /** 生产读取复用唯一 MyBatis-Solon transaction owner。 */
    public MybatisSubagentPolicyRepository(SqlSessionFactory sessions) {
        transactions = new MybatisUnitOfWork(sessions);
    }

    /** 聚焦测试使用真实 SQLite session owner。 */
    public MybatisSubagentPolicyRepository(SqlSessionFactory sessions, MybatisUnitOfWork.SessionOwner owner) {
        transactions = new MybatisUnitOfWork(sessions, owner);
    }

    /** 缺失策略明确返回 empty，由调用方映射为 INVALID_STATE，而不是兼容回退。 */
    @Override
    public Optional<SubagentPolicy> find(String threadId) {
        Objects.requireNonNull(threadId, "threadId");
        return transactions.required(mapper -> {
            PersistenceRecords.SubagentPolicyRow row = mapper.subagentPolicies().select(threadId);
            if (row == null) return Optional.empty();
            return Optional.of(new SubagentPolicy(row.enabled(), row.providerId(), row.modelId(),
                    row.reasoningLevel()));
        });
    }
}
