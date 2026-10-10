// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

/**
 * Plan 固定 SQL 的强类型投影；每种记录只描述稳定的列集合。
 */
public final class PlanRecords {
    /**
     * 结果记录仅表达数据库投影，不承载聚合规则或动态列容器。
     */
    private PlanRecords() {
    }





    /**
     * Tool attempt 完整事实；Plan owner 与副作用结算字段不可用哨兵值替代。
     *
     * @param toolAttemptId 工具执行尝试标识
     * @param planId Plan 标识
     * @param runId 运行标识
     * @param planRevisionId Plan 修订版本标识
     * @param stepId 步骤标识
     * @param attempt 尝试次数
     * @param turnId 本次可恢复执行的稳定标识
     * @param callId 模型生成的工具调用标识
     * @param processGeneration App Server 进程代际标识
     * @param sideEffect 工具副作用的持久化标记
     * @param state 当前状态
     * @param requestDigest 请求内容摘要
     * @param resultDigest 结果内容摘要
     * @param preparedAt 已准备时间
     * @param startedAt 开始时间
     * @param completedAt 完成时间
     */
    public record PlanToolAttemptRow(String toolAttemptId,
                                     String planId,
                                     String runId,
                                     String planRevisionId,
                                     String stepId,
                                     int attempt,
                                     String turnId,
                                     String callId,
                                     long processGeneration,
                                     int sideEffect,
                                     String state,
                                     String requestDigest,
                                     String resultDigest,
                                     String preparedAt,
                                     String startedAt,
                                     String completedAt) {
    }



    /**
     * 验收证据行保留来源身份及精确 Plan revision 绑定。
     *
     * @param evidenceId 证据标识
     * @param planId Plan 标识
     * @param runId 运行标识
     * @param planRevisionId Plan 修订版本标识
     * @param criterionId 验收条件标识
     * @param stepId 步骤标识
     * @param sourceType 来源类型
     * @param sourceId 来源标识
     * @param summary 摘要
     * @param digest 摘要
     * @param observedAt 观察时间
     * @param createdAt 创建时间
     */
    public record AcceptanceEvidenceRow(String evidenceId,
                                        String planId,
                                        String runId,
                                        String planRevisionId,
                                        String criterionId,
                                        String stepId,
                                        String sourceType,
                                        String sourceId,
                                        String summary,
                                        String digest,
                                        String observedAt,
                                        String createdAt) {
    }



    /**
     * Thread owner 与可选 side-chat lineage 的组合，用于创建 Plan 时复核准入。
     *
     * @param revision 当前修订版本
     * @param taskKind 任务类型
     * @param lifecycle 生命周期
     */
    public record ThreadOwnerRow(long revision,
                                 String taskKind,
                                 String lifecycle) {
    }

    /**
     * Plan 主表行；草稿阶段的 active revision/run 允许为 SQL NULL。
     *
     * @param planId Plan 标识
     * @param ownerThreadId 所属对话标识
     * @param objective 目标描述
     * @param createOperationKey 创建幂等键
     * @param status 当前状态分类
     * @param revision 当前修订版本
     * @param activePlanRevisionId 当前活动的 Plan 修订版本标识
     * @param activeRunId 当前活动的运行标识
     * @param createdAt 创建时间
     * @param updatedAt 最后更新时间
     */
    public record PlanRow(String planId,
                          String ownerThreadId,
                          String objective,
                          String createOperationKey,
                          String status,
                          long revision,
                          String activePlanRevisionId,
                          String activeRunId,
                          String createdAt,
                          String updatedAt) {
    }

    /**
     * Plan 持久事件列表及最新事件共用相同列序。
     *
     * @param eventSequence 事件序号
     * @param eventId 事件标识
     * @param planId Plan 标识
     * @param planRevision Plan 修订版本
     * @param activity 活动
     * @param createdAt 创建时间
     */
    public record PlanEventRow(long eventSequence,
                               String eventId,
                               String planId,
                               long planRevision,
                               String activity,
                               String createdAt) {
    }

    /**
     * Plan 草稿行；基准 revision 在初次起草时为空。
     *
     * @param planDraftId Plan 草稿标识
     * @param planId Plan 标识
     * @param draftRevision 草稿修订版本
     * @param definitionJson 定义 JSON 表示
     * @param basedOnPlanRevisionId 当前内容所依据的 Plan 修订版本标识
     * @param updatedAt 最后更新时间
     */
    public record PlanDraftRow(String planDraftId,
                               String planId,
                               long draftRevision,
                               String definitionJson,
                               String basedOnPlanRevisionId,
                               String updatedAt) {
    }

    /**
     * Plan 执行 claim 的复合查询结果。
     *
     * @param ordinal 当前项的顺序位置
     * @param state 当前状态
     */
    public record PlanTurnClaimRow(int ordinal,
                                   String state) {
    }

    /**
     * Execution run 的状态与预算用量快照。
     *
     * @param status 当前状态分类
     * @param planId Plan 标识
     * @param planRevisionId Plan 修订版本标识
     * @param pauseRequested 暂停请求
     * @param usedModelRounds 已发生的模型调用轮数
     * @param usedToolCalls 已发生的工具调用次数
     * @param usedActiveMillis 已消耗的活动时长，单位为毫秒
     */
    public record ExecutionRunStateRow(String status,
                                       String planId,
                                       String planRevisionId,
                                       int pauseRequested,
                                       long usedModelRounds,
                                       long usedToolCalls,
                                       long usedActiveMillis) {
    }

    /**
     * 创建 evaluator 时使用的 Thread provider/model 冻结身份。
     *
     * @param providerId 模型供应商标识
     * @param modelId 模型标识
     */
    public record ThreadModelRow(String providerId,
                                 String modelId) {
    }

    /**
     * Run owner 的 Plan 绑定。
     *
     * @param planId Plan 标识
     * @param planRevisionId Plan 修订版本标识
     */
    public record PlanRunBindingRow(String planId,
                                    String planRevisionId) {
    }

    /**
     * 内部 Turn 的稳定来源和规范 JSON context。
     *
     * @param origin 来源分类
     * @param contextJson 上下文 JSON 表示
     */
    public record InternalTurnContextRow(String origin,
                                         String contextJson) {
    }


    /**
     * Plan revision 完整定义行；JSON 在应用边界严格解码和校验。
     *
     * @param planRevisionId Plan 修订版本标识
     * @param planId Plan 标识
     * @param revisionNumber 修订版本编号
     * @param definitionJson 定义 JSON 表示
     * @param planHash Plan 内容摘要
     * @param createdBy 创建者标识
     * @param createdAt 创建时间
     */
    public record PlanRevisionRow(String planRevisionId,
                                  String planId,
                                  int revisionNumber,
                                  String definitionJson,
                                  String planHash,
                                  String createdBy,
                                  String createdAt) {
    }

    /**
     * Plan approval 行绑定 hash，不能把旧 revision 的批准挪到新定义。
     *
     * @param approvalId 审批标识
     * @param planId Plan 标识
     * @param planRevisionId Plan 修订版本标识
     * @param planHash Plan 内容摘要
     * @param decision 决策
     * @param createdAt 创建时间
     */
    public record PlanApprovalRow(String approvalId,
                                  String planId,
                                  String planRevisionId,
                                  String planHash,
                                  String decision,
                                  String createdAt) {
    }

    /**
     * UI 步骤进度投影不携带完整 Plan definition。
     *
     * @param stepId 步骤标识
     * @param title 显示标题
     * @param required 必需
     * @param status 当前状态分类
     */
    public record PlanStepProgressRow(String stepId,
                                      String title,
                                      int required,
                                      String status) {
    }

    /**
     * 持久步骤执行状态行；未开始或未结束时间按 SQL NULL 保留。
     *
     * @param runId 运行标识
     * @param planRevisionId Plan 修订版本标识
     * @param stepId 步骤标识
     * @param status 当前状态分类
     * @param attemptCount 累计尝试次数
     * @param failureSignature 失败签名
     * @param startedAt 开始时间
     * @param completedAt 完成时间
     * @param updatedAt 最后更新时间
     */
    public record PlanStepExecutionRow(String runId,
                                       String planRevisionId,
                                       String stepId,
                                       String status,
                                       int attemptCount,
                                       String failureSignature,
                                       String startedAt,
                                       String completedAt,
                                       String updatedAt) {
    }

    /**
     * 进程恢复扫描投影明确标记未知副作用是否阻止自动续跑。
     *
     * @param planId Plan 标识
     * @param revision 当前修订版本
     * @param activeRunId 当前活动的运行标识
     * @param activePlanRevisionId 当前活动的 Plan 修订版本标识
     * @param unsafe 不安全
     */
    public record PlanExecutionRecoveryRow(String planId,
                                           long revision,
                                           String activeRunId,
                                           String activePlanRevisionId,
                                           int unsafe) {
    }

    /**
     * 临时侧聊关闭门只返回需要继续等待关闭的 owner id。
     *
     * @param threadId 所属对话的稳定标识
     */
    public record TemporarySideChatRow(String threadId) {
    }

    /**
     * Plan owner 查询不读取或暴露 Plan 的其余列。
     *
     * @param ownerThreadId 所属对话标识
     */
    public record PlanOwnerRow(String ownerThreadId) {
    }

    /**
     * Plan 当前执行步骤的稳定步骤标识。
     *
     * @param stepId 步骤标识
     */
    public record PlanStepIdRow(String stepId) {
    }

    /**
     * 活动 Plan Turn 的身份投影，与步骤标识保持独立类型。
     *
     * @param turnId 本次可恢复执行的稳定标识
     */
    public record TurnIdRow(String turnId) {
    }
}
