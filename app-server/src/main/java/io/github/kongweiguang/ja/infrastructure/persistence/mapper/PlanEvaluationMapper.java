// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;

/** Plan evaluator intent/usage 的事务 Mapper；请求状态由 SQLite CAS 保护。 */
@Mapper
public interface PlanEvaluationMapper {
    /** 读取同一 request identity，供 UNKNOWN/RUNNING 重试门阻止重复 Provider 调用。 */
    PersistenceRecords.PlanEvaluationPriorRow selectPrior(String requestId);

    /** 同一输入的最新请求 ordinal 决定下一次新身份，历史失败不可原地回放。 */
    PersistenceRecords.PlanEvaluationPriorRow selectLatest(PersistenceRecords.PlanEvaluationLatestLookup values);

    /** 只结算仍为 RUNNING 的旧请求，重复恢复不得二次推进。 */
    int markInterrupted(PersistenceRecords.PlanEvaluationInterruptedUpdate values);

    /** Provider 发起前插入唯一 intent；返回 0 表示并发请求已抢到相同 identity。 */
    int insertIntent(PersistenceRecords.PlanEvaluationIntentInsert values);

    /** 在插入 intent 前以同一事务记一次模型请求，只用状态与暂停 fence 判定准入。 */
    int recordModelRequest(PersistenceRecords.PlanEvaluationIntentInsert values);

    /** 仅为仍处于 RUNNING 的请求结算活动时间，不以累计时长停止验收。 */
    int settleActiveMillis(PersistenceRecords.PlanEvaluationUsageUpdate values);

    /** Provider 终态只允许把 RUNNING 行结算一次。 */
    int settleUsage(PersistenceRecords.PlanEvaluationUsageUpdate values);
}
