// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Plan 聚合固定 SQL 边界；实例由当前事务 SqlSession 创建。
 */
@Mapper
public interface PlanMapper {
    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    PlanRecords.ThreadOwnerRow findThreadsByThreadIdDeletedAt(@Param("threadId") String threadId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    PlanRecords.PlanRow findPlansByOwnerThreadIdCreateOperationKey(@Param("ownerThreadId") String ownerThreadId, @Param("createOperationKey") String createOperationKey);

    /**
     * 读取未删除 Thread 的 revision，Plan 写入用它验证创建时的 owner fence。
     */
    Long findActiveThreadRevision(@Param("threadId") String threadId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    PlanRecords.PlanRow findPlansByOwnerThreadId(@Param("ownerThreadId") String ownerThreadId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    PlanRecords.PlanEventRow findPlanEventsByPlanId(@Param("planId") String planId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    PlanRecords.PlanDraftRow findPlanDraftsByPlanId(@Param("planId") String planId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    String findPlanApprovalsByPlanIdPlanRevisionIdDecision(@Param("planId") String planId, @Param("planRevisionId") String planRevisionId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    String findPlanEventsByPlanIdOperationKey(@Param("planId") String planId, @Param("operationKey") String operationKey);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    PlanRecords.PlanTurnClaimRow findPlanTurnClaimsByRunIdTurnId(@Param("runId") String runId, @Param("turnId") String turnId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    PlanRecords.ExecutionRunStateRow findExecutionRunsByRunId(@Param("runId") String runId);

    /**
     * 检查指定 Run 是否存在仍被持久 Turn 占用的 CLAIMED claim。
     */
    Integer findPlanTurnClaimsByRunIdState(@Param("runId") String runId);

    /**
     * 仅识别终态 Turn 遗留的未结算 claim，避免阻止可恢复的挂起 Turn。
     */
    Integer findTerminalUnsettledPlanTurnClaimByRunId(@Param("runId") String runId);

    /**
     * 判断指定 Plan run 与 Turn 是否已有 claim，避免重复计入执行预算。
     */
    Integer findPlanTurnClaim(@Param("runId") String runId, @Param("turnId") String turnId);

    /**
     * 读取持久 Turn 状态，Plan claim 只能在同事务确认 Turn 已终态后结算。
     */
    String findConversationTurnStateByTurnId(@Param("turnId") String turnId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    PlanRecords.AcceptanceEvidenceRow findAcceptanceEvidenceByEvidenceId(@Param("evidenceId") String evidenceId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    PlanRecords.ThreadModelRow findThreadsByThreadId(@Param("threadId") String threadId);

    /**
     * 读取 run 所属 Plan 的 revision 绑定，用于复核调用方身份与聚合归属。
     */
    PlanRecords.PlanRunBindingRow findPlanRunBinding(@Param("runId") String runId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    PlanRecords.PlanRow findPlansByOwnerThreadIdStatus(@Param("ownerThreadId") String ownerThreadId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    PlanRecords.InternalTurnContextRow findTurnInternalContextByTurnId(@Param("turnId") String turnId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    PlanRecords.TurnIdRow findTurnInternalContextByOriginState(@Param("planId") String planId, @Param("runId") String runId);

    /**
     * 识别已有 Plan Turn 仍持有 CLAIMED claim、但没有匹配不可变 context 的损坏 admission。
     */
    Integer findClaimedPlanTurnMissingInternalContext(@Param("planId") String planId,
                                                      @Param("runId") String runId);

    /**
     * 读取 owner 的非终态 Plan；多行异常由唯一约束暴露，不在应用层任意择一。
     */
    PlanRecords.PlanRow findNonTerminalPlanByOwner(@Param("ownerThreadId") String ownerThreadId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    PlanRecords.PlanStepIdRow findPlanStepExecutionsByRunIdStatus(@Param("runId") String runId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    PlanRecords.PlanRevisionRow findPlanRevisionsByPlanId(@Param("planId") String planId);

    /**
     * 只读取当前 revision 的已批准记录，草稿或旧批准不可作为执行授权。
     */
    PlanRecords.PlanApprovalRow findLatestApprovedPlanApproval(@Param("planId") String planId, @Param("planRevisionId") String planRevisionId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    PlanRecords.PlanOwnerRow findPlansByPlanId(@Param("planId") String planId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    Integer findExecutionRunsByOwnerThreadIdStatus(@Param("ownerThreadId") String ownerThreadId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    Integer findExecutionRunsByPlanIdStatus(@Param("planId") String planId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    PlanRecords.TemporarySideChatRow findTemporarySideChatsByState(@Param("threadId") String threadId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    Integer findPlanApprovalsByPlanIdPlanRevisionIdPlanHash(@Param("planId") String planId, @Param("planRevisionId") String planRevisionId, @Param("planHash") String planHash);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    PlanRecords.PlanRevisionRow findPlanRevisionsByPlanIdPlanRevisionId(@Param("planId") String planId, @Param("planRevisionId") String planRevisionId);

    /**
     * 读取完整 Plan 聚合状态，缺失时由仓储按 Plan 边界返回领域错误。
     */
    PlanRecords.PlanRow findPlanById(@Param("planId") String planId);

    /**
     * 幂等重放只按同一 Plan 的业务键匹配，防止跨聚合复用事件回执。
     */
    Integer findPlanEventByOperationKey(@Param("planId") String planId, @Param("operationKey") String operationKey);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    List<PlanRecords.PlanStepProgressRow> listPlanStepsByPlanRevisionId(@Param("runId") String runId, @Param("planRevisionId") String planRevisionId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    List<PlanRecords.PlanEventRow> listPlanEventsByPlanIdEventSequence(@Param("planId") String planId, @Param("eventSequence") long eventSequence, @Param("limit") int limit);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    List<PlanRecords.PlanRevisionRow> listPlanRevisionsByPlanIdRevisionNumber(@Param("planId") String planId, @Param("revisionNumber") long revisionNumber, @Param("limit") int limit);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    List<PlanRecords.AcceptanceEvidenceRow> listPlanEvidencePageStart(@Param("planId") String planId, @Param("runId") String runId, @Param("planRevisionId") String planRevisionId, @Param("limit") int limit);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    List<PlanRecords.PlanStepExecutionRow> listPlanStepExecutionsByRunId(@Param("runId") String runId);

    /**
     * 进程恢复只读取未 settle 的 Plan run，并带回未知副作用判定。
     */
    List<PlanRecords.PlanExecutionRecoveryRow> listPlanExecutionRecoveryCandidates(@Param("processGeneration") long processGeneration, @Param("limit") int limit);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    int insertPlansWithPlanId(@Param("planId") String planId, @Param("ownerThreadId") String ownerThreadId, @Param("objective") String objective, @Param("createOperationKey") String createOperationKey, @Param("createdAt") String createdAt, @Param("updatedAt") String updatedAt);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    int insertPlanDraftsWithPlanDraftId(@Param("planDraftId") String planDraftId, @Param("planId") String planId, @Param("definitionJson") String definitionJson, @Param("basedOnPlanRevisionId") String basedOnPlanRevisionId, @Param("updatedAt") String updatedAt);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    int deletePlanDrafts(@Param("planId") String planId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    int insertPlanRevisionsWithPlanRevisionId(@Param("planRevisionId") String planRevisionId, @Param("planId") String planId, @Param("revisionNumber") long revisionNumber, @Param("definitionJson") String definitionJson, @Param("planHash") String planHash, @Param("createdBy") String createdBy, @Param("createdAt") String createdAt);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    int insertPlanStepsWithPlanRevisionId(@Param("planRevisionId") String planRevisionId, @Param("stepId") String stepId, @Param("ordinal") int ordinal, @Param("title") String title, @Param("description") String description, @Param("required") int required, @Param("dependencyIdsJson") String dependencyIdsJson);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    int insertAcceptanceCriteriaWithPlanRevisionId(@Param("planRevisionId") String planRevisionId, @Param("criterionId") String criterionId, @Param("ordinal") int ordinal, @Param("description") String description, @Param("required") int required);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    int insertPlanApprovalsWithApprovalId(@Param("approvalId") String approvalId, @Param("planId") String planId, @Param("planRevisionId") String planRevisionId, @Param("planHash") String planHash, @Param("createdAt") String createdAt);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    int updateExecutionRunsSettingUpdatedAtByRunId(@Param("updatedAt") String updatedAt, @Param("runId") String runId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    int insertPlanTurnClaimsWithRunId(@Param("runId") String runId, @Param("turnId") String turnId, @Param("ordinal") int ordinal, @Param("claimedAt") String claimedAt);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    int updateExecutionRunsSettingUsedModelRoundsByRunId(@Param("usedModelRounds") long usedModelRounds, @Param("usedToolCalls") long usedToolCalls, @Param("usedActiveMillis") long usedActiveMillis, @Param("updatedAt") String updatedAt, @Param("runId") String runId);

    /**
     * 只在对应 Turn 结束时结算该 run 的 claim，挂起恢复仍保留原 claim。
     */
    int settlePlanTurnClaim(@Param("runId") String runId, @Param("turnId") String turnId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    int updateExecutionRunsSettingUsedActiveMillisByRunId(@Param("usedActiveMillis") long usedActiveMillis, @Param("updatedAt") String updatedAt, @Param("runId") String runId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    int updateExecutionRunsSettingPauseRequestedByRunId(@Param("updatedAt") String updatedAt, @Param("runId") String runId, @Param("planId") String planId);

    /**
     * 将拒绝决定作为审计事实写入，不覆盖此前针对同 revision 的批准记录。
     */
    int insertRejectedPlanApproval(@Param("approvalId") String approvalId, @Param("planId") String planId, @Param("planRevisionId") String planRevisionId, @Param("planHash") String planHash, @Param("createdAt") String createdAt);

    /**
     * Resume 或 NOT_MET 结算清除暂停请求，使原 run 可以继续。
     */
    int clearPlanRunPauseRequest(@Param("updatedAt") String updatedAt, @Param("runId") String runId);

    /**
     * 仅将没有对应持久 Turn 的 CLAIMED intent 标记为 ABANDONED。
     */
    int abandonUnadmittedPlanTurnClaims(@Param("runId") String runId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    int updateExecutionRunsSettingStatusByRunId(@Param("status") String status, @Param("completedStatus") String completedStatus, @Param("completedAt") String completedAt, @Param("updatedAt") String updatedAt, @Param("runId") String runId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    int updatePlanStepExecutionsSettingStatusByRunId(@Param("status") String status, @Param("runningStatus") String runningStatus, @Param("failureSignature") String failureSignature, @Param("startedStatus") String startedStatus, @Param("startedAt") String startedAt, @Param("completedAt") String completedAt, @Param("updatedAt") String updatedAt, @Param("runId") String runId, @Param("stepId") String stepId, @Param("expectedStatus") String expectedStatus);

    /**
     * 仅将所有依赖均成功的 PENDING 步骤推进到 READY。
     */
    int activateReadyPlanSteps(@Param("updatedAt") String updatedAt, @Param("runId") String runId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    int insertExecutionRunsWithRunId(@Param("runId") String runId, @Param("planId") String planId, @Param("planRevisionId") String planRevisionId, @Param("planHash") String planHash, @Param("status") String status, @Param("processGeneration") long processGeneration, @Param("startedAt") String startedAt, @Param("createdAt") String createdAt, @Param("updatedAt") String updatedAt);

    /** Plan Tool 已开始的恢复查询必须由原 process generation 和持久状态共同约束。 */
    List<PlanRecords.PlanToolAttemptRow> listStartedPlanToolAttemptsByProcessGeneration(
            @Param("processGeneration") long processGeneration, @Param("limit") int limit);

    /** 同一 Turn/call 的历史尝试按稳定顺序读取，避免误认旧的 PREPARED。 */
    List<PlanRecords.PlanToolAttemptRow> listPlanToolAttemptsByTurnIdCallId(
            @Param("turnId") String turnId, @Param("callId") String callId);

    /** 证据只接受当前 Run 中已成功结算的 Tool 来源。 */
    PlanRecords.PlanToolAttemptRow findPlanToolAttemptsByRunIdCallIdState(
            @Param("runId") String runId, @Param("callId") String callId);

    /** 检查精确 Run 是否仍有未结算的 Tool attempt，供恢复边界避免重放未知副作用。 */
    Integer findUnsettledPlanToolAttemptByRunId(@Param("runId") String runId);

    /**
     * 仅为显式 resume 读取本 Run 的完整未结算集合；必须与 Core 游标证明联合判断，不能按单行状态猜测安全。
     *
     * @author kongweiguang
     */
    List<PlanRecords.PlanToolAttemptRow> listUnsettledPlanToolAttemptsByRunId(@Param("runId") String runId);

    /**
     * 在同一 Plan transaction 中读取 App/Core Turn、CLAIMED Run 的冻结 Plan/hash 与严格恢复所需 typed cursor 快照。
     *
     * @author kongweiguang
     */
    String findPlanResumeCoreExecutionSnapshot(@Param("threadId") String threadId,
                                               @Param("turnId") String turnId,
                                               @Param("runId") String runId);

    /**
     * 按 exact execution identity 和 cursor watermark 读取当前助手消息及 Tool 生命周期记录。
     *
     * @param callId cursor 当前等待恢复的 Tool 调用身份
     * @author kongweiguang
     */
    List<String> listPlanResumeCoreToolRecords(@Param("threadId") String threadId,
                                               @Param("turnId") String turnId,
                                               @Param("userId") String userId,
                                               @Param("assistantMessageId") String assistantMessageId,
                                               @Param("callId") String callId,
                                               @Param("lastRecordSequence") long lastRecordSequence);

    /**
     * 按同一 Turn、当前助手记录及 Tool 调用读取模型完成和派发/结算事件，排除未知副作用。
     *
     * @param assistantRecordId cursor 当前助手消息的持久记录身份
     * @param callId cursor 当前等待恢复的 Tool 调用身份
     * @author kongweiguang
     */
    List<String> listPlanResumeCoreToolEvents(@Param("threadId") String threadId,
                                              @Param("turnId") String turnId,
                                              @Param("userId") String userId,
                                              @Param("assistantRecordId") String assistantRecordId,
                                              @Param("callId") String callId,
                                              @Param("mutationVersion") long mutationVersion,
                                              @Param("lastRecordSequence") long lastRecordSequence);

    /** 精确读取 Tool attempt，避免通过当前 owner 状态推测历史调用。 */
    PlanRecords.PlanToolAttemptRow findPlanToolAttemptsByToolAttemptId(@Param("toolAttemptId") String toolAttemptId);

    /** Tool prepared 与 Plan attempt 身份在同一事务提交。 */
    int insertPlanToolAttemptsWithToolAttemptId(@Param("toolAttemptId") String toolAttemptId, @Param("planId") String planId, @Param("runId") String runId, @Param("planRevisionId") String planRevisionId, @Param("stepId") String stepId, @Param("attempt") int attempt, @Param("turnId") String turnId, @Param("callId") String callId, @Param("processGeneration") long processGeneration, @Param("sideEffect") int sideEffect, @Param("state") String state, @Param("requestDigest") String requestDigest, @Param("resultDigest") String resultDigest, @Param("preparedAt") String preparedAt, @Param("startedAt") String startedAt, @Param("completedAt") String completedAt);

    /** Started fence 只能从已准备的同一 attempt 原子推进。 */
    int updatePlanToolAttemptsSettingStateByToolAttemptId(@Param("startedAt") String startedAt, @Param("toolAttemptId") String toolAttemptId);

    /** 已开始调用按真实结果结算，禁止回退到未派发状态。 */
    int settleStartedPlanToolAttempt(@Param("state") String state, @Param("resultDigest") String resultDigest,
                                     @Param("completedAt") String completedAt, @Param("toolAttemptId") String toolAttemptId);

    /** 未开始调用只在 Core 同事务终态证明下关闭。 */
    int settlePreparedPlanToolAttempt(@Param("state") String state, @Param("resultDigest") String resultDigest,
                                      @Param("completedAt") String completedAt, @Param("toolAttemptId") String toolAttemptId);

    /** Plan 证据与结果提交共享事务，重复来源使用唯一约束结算。 */
    int insertIfAbsentAcceptanceEvidenceWithEvidenceId(@Param("evidenceId") String evidenceId, @Param("planId") String planId,
            @Param("runId") String runId, @Param("planRevisionId") String planRevisionId, @Param("criterionId") String criterionId,
            @Param("stepId") String stepId, @Param("sourceType") String sourceType, @Param("sourceId") String sourceId,
            @Param("summary") String summary, @Param("digest") String digest, @Param("observedAt") String observedAt,
            @Param("createdAt") String createdAt);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    int insertPlanStepExecutionsWithRunId(@Param("runId") String runId, @Param("planRevisionId") String planRevisionId, @Param("stepId") String stepId, @Param("status") String status, @Param("updatedAt") String updatedAt);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    int updatePlansSettingStatusByPlanId(@Param("status") String status, @Param("activePlanRevisionId") String activePlanRevisionId, @Param("activeRunId") String activeRunId, @Param("updatedAt") String updatedAt, @Param("planId") String planId, @Param("revision") long revision);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    int insertPlanEventsWithEventId(@Param("eventId") String eventId, @Param("planId") String planId, @Param("planRevision") long planRevision, @Param("activity") String activity, @Param("operationKey") String operationKey, @Param("createdAt") String createdAt);

    /**
     * 计数仅排除明确终态，保护尚可继续执行或恢复的 Plan。
     */
    Long countOpenPlansByOwner(@Param("ownerThreadId") String ownerThreadId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    Long findTurnsByState(@Param("runId") String runId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    Long findInteractionRequestsByThreadIdStatus(@Param("threadId") String threadId);

    /**
     * 在当前写事务中计算下一个 revision number，避免对调用方暴露 SQL 细节。
     */
    Long nextPlanRevisionNumber(@Param("planId") String planId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    Long findPlanTurnClaimsByRunId(@Param("runId") String runId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    Long findUsageByTurnIdPurpose(@Param("turnId") String turnId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    Long findToolsByTurnId(@Param("turnId") String turnId);

    /**
     * 返回 Plan 自己的持久事件水位，供分页读取和重连补读使用。
     */
    Long latestPlanEventSequence(@Param("planId") String planId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    Long findPlanStepsByPlanRevisionIdRequiredRunId(@Param("runId") String runId, @Param("planRevisionId") String planRevisionId, @Param("planRevisionId2") String planRevisionId2, @Param("runId2") String runId2, @Param("runId3") String runId3, @Param("runId4") String runId4);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    Long findPlanStepsByPlanRevisionIdRequired(@Param("planRevisionId") String planRevisionId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    Long findPlanStepExecutionsByRunIdRequiredStatus(@Param("runId") String runId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    Long findAcceptanceCriteriaByPlanRevisionIdCriterionId(@Param("planRevisionId") String planRevisionId, @Param("criterionId") String criterionId);

    /**
     * 参数按持久列语义具名绑定，避免位置数组与结果列错位。
     */
    List<PlanRecords.AcceptanceEvidenceRow> listPlanEvidencePageAfter(@Param("planId") String planId, @Param("runId") String runId, @Param("planRevisionId") String planRevisionId, @Param("createdAt") String createdAt, @Param("createdAt2") String createdAt2, @Param("evidenceId") String evidenceId, @Param("limit") int limit);

}
