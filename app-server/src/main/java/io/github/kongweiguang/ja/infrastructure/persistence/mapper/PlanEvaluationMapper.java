// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;

/** Plan evaluator intent/usage 的事务 Mapper；请求状态由 SQLite CAS 保护。 */
@Mapper
public interface PlanEvaluationMapper {
    /** 读取同一 request identity，供 UNKNOWN/RUNNING 重试门阻止重复 Provider 调用。 */
    PersistenceRecords.PlanEvaluationPriorRow selectPrior(String requestId);

    /** Provider 发起前插入唯一 intent；返回 0 表示并发请求已抢到相同 identity。 */
    int insertIntent(PersistenceRecords.PlanEvaluationIntentInsert values);

    /** 在插入 intent 前以同一 SQLite 事务预留一个模型轮次，预算不足时不允许发起 Provider。 */
    int reserveModelRound(PersistenceRecords.PlanEvaluationIntentInsert values);

    /** 仅为仍处于 RUNNING 的请求结算活动时间，并以 Run 的 wall budget 做原子 CAS。 */
    int settleActiveMillis(PersistenceRecords.PlanEvaluationUsageUpdate values);

    /** Provider 终态只允许把 RUNNING 行结算一次。 */
    int settleUsage(PersistenceRecords.PlanEvaluationUsageUpdate values);
}
