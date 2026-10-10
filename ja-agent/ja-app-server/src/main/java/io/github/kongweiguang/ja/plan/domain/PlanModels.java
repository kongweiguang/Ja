// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Plan 领域类型闭集；wire 和数据库只能显式映射这些值，禁止字符串降级。
 */
public final class PlanModels {
    /**
     * 工具类不进入依赖注入图。
     */
    private PlanModels() {
    }

    /**
     * ID 仅接受有界可打印身份，数据库键不承载 prompt 或路径。
     */
    public static String requireId(String value, String label) {
        String result = Objects.requireNonNull(value, label).strip();
        if (result.isEmpty() || result.length() > 160 || !result.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return result;
    }

    /**
     * 文本约束集中处理 Unicode code unit 上限，防止各 RPC 入口产生不同容量。
     */
    private static String requireText(String value, String label, int minimum, int maximum) {
        String result = Objects.requireNonNull(value, label).strip();
        if (result.length() < minimum || result.length() > maximum) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return result;
    }

    /**
     * 文本列表冻结且逐项验证，避免 null 和超长项绕过整体 JSON 大小约束。
     */
    private static List<String> copyText(List<String> values, String label, int maximumItems) {
        List<String> result = List.copyOf(Objects.requireNonNull(values, label));
        if (result.size() > maximumItems) {
            throw new IllegalArgumentException(label + " is too large");
        }
        return result.stream().map(value -> requireText(value, label, 1, 2000)).toList();
    }

    /**
     * 依赖 identity 使用和实体 ID 相同的严格闭集。
     */
    private static List<String> copyIds(List<String> values, String label, int maximumItems) {
        List<String> result = List.copyOf(Objects.requireNonNull(values, label));
        if (result.size() > maximumItems) {
            throw new IllegalArgumentException(label + " is too large");
        }
        return result.stream().map(value -> requireId(value, label)).toList();
    }



    /**
     * Plan 状态覆盖草稿、审批、执行与验收阶段，具体状态由此闭集表达。
     */
    public enum PlanStatus {
        /**
         * 可编辑草稿。
         */
        DRAFT,
        /**
         * 冻结 revision 等待用户批准。
         */
        AWAITING_APPROVAL,
        /**
         * 用户已批准精确 revision/hash，但尚未选择独立执行或挂接 Plan。
         */
        APPROVED,
        /**
         * 已批准 revision 的独立 run 正在执行。
         */
        EXECUTING,
        /**
         * 步骤已收口，正在进行无 Tool 的独立验收。
         */
        VERIFYING,
        /**
         * 用户暂停或验收证据不足；保留当前 run 供显式恢复。
         */
        PAUSED,
        /**
         * 计划自己的完成门已通过。
         */
        COMPLETED,
        /**
         * 用户明确停止计划。
         */
        STOPPED
    }


    /**
     * 步骤执行状态闭集。
     */
    public enum StepStatus {
        /**
         * 依赖未满足。
         */
        PENDING,
        /**
         * 可领取执行。
         */
        READY,
        /**
         * attempt 正在执行。
         */
        RUNNING,
        /**
         * 等待问题处理。
         */
        BLOCKED,
        /**
         * 成功终态。
         */
        SUCCEEDED,
        /**
         * 可重试失败。
         */
        FAILED,
        /**
         * 非必要步骤显式跳过。
         */
        SKIPPED
    }

    /**
     * 单次批准版本的执行状态闭集。
     */
    public enum RunStatus {
        /**
         * identity 已提交。
         */
        PREPARED,
        /**
         * 正在工作。
         */
        RUNNING,
        /**
         * 正在验收。
         */
        VERIFYING,
        /**
         * 随 Plan 暂停。
         */
        PAUSED,
        /**
         * 已完成。
         */
        COMPLETED,
        /**
         * 已停止。
         */
        STOPPED
    }

    /**
     * 可形成客观验收证据的真实来源闭集。
     */
    public enum EvidenceSource {
        /**
         * Tool result ledger，必须能关联持久调用 identity。
         */
        TOOL_RESULT,
        /**
         * 测试 runner 报告。
         */
        TEST_REPORT,
        /**
         * 构建产物。
         */
        BUILD_ARTIFACT,
        /**
         * 仓库状态。
         */
        REPOSITORY_STATE,
        /**
         * UI 自动化断言。
         */
        UI_ASSERTION,
        /**
         * 用户明确验收。
         */
        USER_ACCEPTANCE
    }

    /**
     * evaluator 调用生命周期。
     */
    public enum EvaluationStatus {
        /**
         * intent 已提交。
         */
        REQUESTED,
        /**
         * 请求正在进行。
         */
        RUNNING,
        /**
         * verdict 已提交。
         */
        COMPLETED,
        /**
         * 调用或结构解析失败。
         */
        FAILED
    }

    /**
     * evaluator 的结构化结论。
     */
    public enum EvaluationVerdict {
        /**
         * 全部条件满足。
         */
        MET,
        /**
         * 至少一项不满足。
         */
        NOT_MET,
        /**
         * 证据不足。
         */
        INCONCLUSIVE
    }

    /**
     * Tool attempt 的恢复状态闭集。
     */
    public enum ToolAttemptState {
        /**
         * tool-call identity 已提交。
         */
        PREPARED,
        /**
         * 外部执行已经开始。
         */
        STARTED,
        /**
         * 真实成功结果已结算。
         */
        SUCCEEDED,
        /**
         * 真实失败结果已结算。
         */
        FAILED,
        /**
         * 工具结果明确为取消，不能伪装成失败或未知副作用。
         */
        CANCELLED,
        /**
         * Core 证明工具在 Started 前失败，保留 startedAt=null。
         */
        FAILED_BEFORE_START,
        /**
         * Core 证明工具在 Started 前取消，保留 startedAt=null。
         */
        CANCELLED_BEFORE_START,
        /**
         * 旧进程副作用结果未知。
         */
        UNKNOWN
    }



    /**
     * Plan 聚合持有 owner Thread、revision 与活动 run identity，状态更新由仓储按 revision CAS 保护。
     *
     * @param planId Plan 标识
     * @param ownerThreadId 所属对话标识
     * @param objective 目标描述
     * @param status 当前状态分类
     * @param revision 当前修订版本
     * @param activePlanRevisionId 当前活动的 Plan 修订版本标识
     * @param activeRunId 当前活动的运行标识
     * @param createdAt 创建时间
     * @param updatedAt 最后更新时间
     */
    public record Plan(String planId,
                       String ownerThreadId,
                       String objective,
                       PlanStatus status,
                       long revision,
                       String activePlanRevisionId,
                       String activeRunId,
                       Instant createdAt,
                       Instant updatedAt) {
        /**
         * 运行态必须同时绑定冻结 revision 与 run，草稿/待批准态不得伪造 active identity。
         */
        public Plan {
            requireId(planId, "planId");
            requireId(ownerThreadId, "ownerThreadId");
            objective = requireText(objective, "objective", 1, 32768);
            Objects.requireNonNull(status, "status");
            if (revision < 0) {
                throw new IllegalArgumentException("invalid Plan revision");
            }
            boolean runningIdentity = activePlanRevisionId != null && activeRunId != null;
            if ((status == PlanStatus.EXECUTING || status == PlanStatus.VERIFYING
                 || status == PlanStatus.PAUSED || status == PlanStatus.COMPLETED) && !runningIdentity) {
                throw new IllegalArgumentException("invalid Plan active identity");
            }
            if (status == PlanStatus.APPROVED
                && (activePlanRevisionId == null || activeRunId != null)) {
                throw new IllegalArgumentException("invalid approved Plan identity");
            }
            if ((status == PlanStatus.DRAFT || status == PlanStatus.AWAITING_APPROVAL)
                && (activePlanRevisionId != null || activeRunId != null)) {
                throw new IllegalArgumentException("invalid Plan active identity");
            }
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }


    /**
     * 冻结版本同时保存 canonical JSON 与摘要字段，权威 hash 只覆盖 canonical JSON。
     *
     * @param planRevisionId Plan 修订版本标识
     * @param planId Plan 标识
     * @param revisionNumber 修订版本编号
     * @param definition 定义
     * @param canonicalJson 规范化后的 JSON 文本
     * @param planHash Plan 内容摘要
     * @param createdBy 创建者标识
     * @param createdAt 创建时间
     */
    public record PlanRevision(String planRevisionId,
                               String planId,
                               int revisionNumber,
                               PlanDefinition definition,
                               String canonicalJson,
                               String planHash,
                               String createdBy,
                               Instant createdAt) {
        /**
         * 冻结版本必须自包含可复算 hash 的权威 JSON，不能只依赖拆表投影重建。
         */
        public PlanRevision {
            requireId(planRevisionId, "planRevisionId");
            requireId(planId, "planId");
            Objects.requireNonNull(definition, "definition");
            if (revisionNumber < 1 || canonicalJson == null || canonicalJson.isBlank()
                || planHash == null || !planHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid Plan revision");
            }
            if (!"AGENT".equals(createdBy) && !"USER_UI".equals(createdBy)) {
                throw new IllegalArgumentException("invalid Plan revision author");
            }
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /**
     * 可变草稿的 revision 与冻结 PlanRevision 分离，UI 保存失败时可精确保留本地编辑。
     *
     * @param planDraftId Plan 草稿标识
     * @param planId Plan 标识
     * @param draftRevision 草稿修订版本
     * @param definition 定义
     * @param basePlanRevisionId 作为修改基准的 Plan 修订版本标识
     * @param updatedAt 最后更新时间
     */
    public record PlanDraft(String planDraftId,
                            String planId,
                            long draftRevision,
                            PlanDefinition definition,
                            String basePlanRevisionId,
                            Instant updatedAt) {
    }

    /**
     * 只投影已批准事实；拒绝决定保留在事件流，不冒充当前 approval。
     *
     * @param approvalId 审批标识
     * @param planId Plan 标识
     * @param planRevisionId Plan 修订版本标识
     * @param planHash Plan 内容摘要
     * @param approvedAt 批准时间
     */
    public record PlanApproval(String approvalId,
                               String planId,
                               String planRevisionId,
                               String planHash,
                               Instant approvedAt) {
    }

    /**
     * 当前 run 的步骤投影保持 attempt 与终态时间，摘要缺失时显式为 null。
     *
     * @param stepId 步骤标识
     * @param runId 运行标识
     * @param status 当前状态分类
     * @param attempt 尝试次数
     * @param failureSignature 失败签名
     * @param summary 摘要
     * @param startedAt 开始时间
     * @param completedAt 完成时间
     */
    public record StepExecution(String stepId,
                                String runId,
                                StepStatus status,
                                int attempt,
                                String failureSignature,
                                String summary,
                                Instant startedAt,
                                Instant completedAt) {
    }


    /**
     * 仓储在一个读事务内组装 Plan、当前 revision、审批、步骤执行与事件水位的一致视图。
     *
     * @param plan 执行计划
     * @param draft 草稿
     * @param currentRevision 当前修订版本
     * @param approval 审批
     * @param stepExecutions 步骤执行列表
     * @param eventSequence 事件序号
     */
    public record PlanSnapshot(Plan plan,
                               PlanDraft draft,
                               PlanRevision currentRevision,
                               PlanApproval approval,
                               List<StepExecution> stepExecutions,
                               long eventSequence) {
        /**
         * 冻结列表并校验 active identity；批准后的 snapshot 若跟随 latest revision，可能让用户看到
         * 未批准定义或让执行器使用错误步骤，因此在领域边界直接失败关闭。
         */
        public PlanSnapshot {
            Objects.requireNonNull(plan, "plan");
            stepExecutions = List.copyOf(Objects.requireNonNull(stepExecutions, "stepExecutions"));
            if (eventSequence < 0) {
                throw new IllegalArgumentException("invalid Plan event sequence");
            }
            if (currentRevision != null && !currentRevision.planId().equals(plan.planId())) {
                throw new IllegalArgumentException("Plan revision does not belong to Plan");
            }
            if (plan.activePlanRevisionId() != null
                && (currentRevision == null
                    || !plan.activePlanRevisionId().equals(currentRevision.planRevisionId()))) {
                throw new IllegalArgumentException("Plan active revision does not match snapshot");
            }
            if (approval != null && (currentRevision == null
                                     || !approval.planId().equals(plan.planId())
                                     || !approval.planRevisionId().equals(currentRevision.planRevisionId())
                                     || !approval.planHash().equals(currentRevision.planHash()))) {
                throw new IllegalArgumentException("Plan approval does not match snapshot");
            }
        }
    }

    /**
     * Plan 实时观察只携带步骤计数与当前动作，不物化正文、证据或内部运行 identity。
     *
     * @param currentStepId 当前步骤标识
     * @param currentStepTitle 当前步骤标题
     * @param completedRequiredSteps 已完成的必需步骤数
     * @param totalRequiredSteps 必需步骤总数
     */
    public record PlanProgress(String currentStepId,
                               String currentStepTitle,
                               int completedRequiredSteps,
                               int totalRequiredSteps) {
        /**
         * 当前步骤允许为空，计数必须保持有界且完成数不能超过总数。
         */
        public PlanProgress {
            if (currentStepId != null) {
                requireId(currentStepId, "currentStepId");
            }
            if (currentStepTitle != null) {
                requireText(currentStepTitle, "currentStepTitle", 1, 240);
            }
            if (completedRequiredSteps < 0 || totalRequiredSteps < 0
                || completedRequiredSteps > totalRequiredSteps) {
                throw new IllegalArgumentException("invalid Plan progress");
            }
        }
    }


    /**
     * append-only Plan event 的公开词汇由 application 层从持久 activity 严格映射。
     *
     * @param eventSequence 事件序号
     * @param planRevision Plan 的修订版本
     * @param kind 类型分类
     * @param summary 摘要
     * @param occurredAt 事件发生时间
     */
    public record PublicEvent(long eventSequence,
                              long planRevision,
                              String kind,
                              String summary,
                              Instant occurredAt) {
    }


    /**
     * 查询分页携带读取时 Plan revision 与 eventSequence，opaque cursor 只由 application 层解释。
     *
     * @param aggregateId 聚合标识
     * @param aggregateRevision 聚合修订版本
     * @param eventSequence 事件序号
     * @param items 条目列表
     * @param nextCursor 读取下一页时使用的游标
     */
    public record Page<T>(String aggregateId,
                          long aggregateRevision,
                          long eventSequence,
                          List<T> items,
                          String nextCursor) {
        /**
         * 分页结果在离开仓储事务后保持不可变。
         */
        public Page {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }

    /**
     * 计划定义是结构化权威源，不允许 Markdown 清单或文本完成标记参与状态判断。
     *
     * @param objective 目标描述
     * @param scope 适用范围
     * @param nonGoals 明确排除的目标范围
     * @param constraints 约束集合
     * @param dependencies 依赖集合
     * @param steps 步骤列表
     * @param acceptanceCriteria 目标验收条件
     * @param risks 风险列表
     * @param verificationStrategy 核验策略
     */
    public record PlanDefinition(String objective,
                                 List<String> scope,
                                 List<String> nonGoals,
                                 List<String> constraints,
                                 List<String> dependencies,
                                 List<PlanStep> steps,
                                 List<AcceptanceCriterion> acceptanceCriteria,
                                 List<String> risks,
                                 List<String> verificationStrategy) {
        /**
         * 防御性复制保证批准后无法通过调用方持有的可变 List 篡改定义。
         */
        public PlanDefinition {
            objective = requireText(objective, "objective", 1, 32768);
            scope = copyText(scope, "scope", 128);
            nonGoals = copyText(nonGoals, "nonGoals", 128);
            constraints = copyText(constraints, "constraints", 128);
            dependencies = copyText(dependencies, "dependencies", 128);
            steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
            acceptanceCriteria = List.copyOf(Objects.requireNonNull(acceptanceCriteria, "acceptanceCriteria"));
            risks = copyText(risks, "risks", 128);
            verificationStrategy = copyText(verificationStrategy, "verificationStrategy", 128);
            if (steps.isEmpty() || steps.size() > 256 || acceptanceCriteria.isEmpty()
                || acceptanceCriteria.size() > 256 || verificationStrategy.isEmpty()) {
                throw new IllegalArgumentException("plan steps and criteria must be bounded and non-empty");
            }
        }

        /**
         * 返回独立快照，避免批准后的权威定义被调用方通过集合引用间接修改。
         */
        @Override
        public List<String> scope() {
            return new java.util.ArrayList<>(scope);
        }

        /**
         * non-goals 与 scope 使用相同隔离边界，调用方排序也不能影响 plan hash。
         */
        @Override
        public List<String> nonGoals() {
            return new java.util.ArrayList<>(nonGoals);
        }

        /**
         * 约束快照允许调用方本地加工，但冻结 revision 始终保持原值。
         */
        @Override
        public List<String> constraints() {
            return new java.util.ArrayList<>(constraints);
        }

        /**
         * 依赖描述是冻结输入的一部分，对外只提供副本。
         */
        @Override
        public List<String> dependencies() {
            return new java.util.ArrayList<>(dependencies);
        }

        /**
         * 风险条目不共享内部集合，避免 evaluator 输入在运行期间漂移。
         */
        @Override
        public List<String> risks() {
            return new java.util.ArrayList<>(risks);
        }

        /**
         * 验证策略副本保持 revision 不可变，同时不要求调用方了解集合实现。
         */
        @Override
        public List<String> verificationStrategy() {
            return new java.util.ArrayList<>(verificationStrategy);
        }
    }

    /**
     * stepId 在所有状态变化中保持稳定；依赖仅引用同一 revision 的 stepId。
     *
     * @param stepId 步骤标识
     * @param title 显示标题
     * @param description 说明
     * @param required 是否必需
     * @param dependsOn 依赖的步骤标识
     */
    public record PlanStep(String stepId,
                           String title,
                           String description,
                           boolean required,
                           List<String> dependsOn) {
        /**
         * 限制可见文本与依赖数量，防止计划被用作无界隐藏上下文。
         */
        public PlanStep {
            requireId(stepId, "stepId");
            title = requireText(title, "title", 1, 240);
            description = requireText(description, "description", 0, 4000);
            dependsOn = copyIds(dependsOn, "dependsOn", 64);
        }

        /**
         * DAG 依赖以副本暴露，防止步骤关系在批准后被外部引用改写。
         */
        @Override
        public List<String> dependsOn() {
            return new java.util.ArrayList<>(dependsOn);
        }
    }

    /**
     * criterionId 是证据挂接身份，描述修改必须创建新 PlanRevision。
     *
     * @param criterionId 验收条件标识
     * @param description 说明
     * @param required 是否必需
     */
    public record AcceptanceCriterion(String criterionId,
                                      String description,
                                      boolean required) {
        /**
         * 必要验收至少有一项由 PlanDefinition 的整体校验进一步保证。
         */
        public AcceptanceCriterion {
            requireId(criterionId, "criterionId");
            description = requireText(description, "description", 1, 2000);
        }
    }

    /**
     * Evidence 只能引用真实来源 identity 和 digest，不承载未经证明的模型正文。
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
    public record Evidence(String evidenceId,
                           String planId,
                           String runId,
                           String planRevisionId,
                           String criterionId,
                           String stepId,
                           EvidenceSource sourceType,
                           String sourceId,
                           String summary,
                           String digest,
                           Instant observedAt,
                           Instant createdAt) {
        /**
         * Plan 证据必须绑定自身聚合和冻结执行版本，避免跨 Run 冒用。
         */
        public Evidence {
            requireId(planId, "planId");
            requireId(runId, "runId");
            requireId(planRevisionId, "planRevisionId");
        }
    }

    /**
     * Tool attempt 把 Plan step 与既有持久 tool call 绑定，恢复不得生成新 call identity。
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
     * @param sideEffect 是否存在副作用
     * @param state 当前状态
     * @param requestDigest 请求内容摘要
     * @param resultDigest 结果内容摘要
     * @param preparedAt 已准备时间
     * @param startedAt 开始时间
     * @param completedAt 完成时间
     */
    public record ToolAttempt(String toolAttemptId,
                              String planId,
                              String runId,
                              String planRevisionId,
                              String stepId,
                              int attempt,
                              String turnId,
                              String callId,
                              long processGeneration,
                              boolean sideEffect,
                              ToolAttemptState state,
                              String requestDigest,
                              String resultDigest,
                              Instant preparedAt,
                              Instant startedAt,
                              Instant completedAt) {
    }

    /**
     * 独立 evaluator 的逐条件结果保持结构化，非法 verdict 不会映射成成功。
     *
     * @param criterionId 验收条件标识
     * @param verdict 验收结论
     * @param reason 原因说明
     */
    public record CriterionEvaluation(String criterionId,
                                      EvaluationVerdict verdict,
                                      String reason) {
    }

    /**
     * 完成门只接受当前 run/revision 的聚合快照。
     *
     * @param steps 步骤列表
     * @param criteria 验收条件
     * @param stepStatuses 步骤状态集合
     * @param evidencedCriteria 已有证据支持的验收条件
     * @param unresolvedInput 是否仍有未结算输入
     * @param unresolvedApproval 是否仍有未结算审批
     * @param unresolvedTool 是否仍有未结算工具调用
     * @param unresolvedTask 是否仍有未结算任务
     * @param evaluatorVerdict 验收评估器给出的结论
     */
    public record CompletionSnapshot(List<PlanStep> steps,
                                     List<AcceptanceCriterion> criteria,
                                     List<StepStatus> stepStatuses,
                                     List<String> evidencedCriteria,
                                     boolean unresolvedInput,
                                     boolean unresolvedApproval,
                                     boolean unresolvedTool,
                                     boolean unresolvedTask,
                                     EvaluationVerdict evaluatorVerdict) {
        /**
         * 防御性复制避免 evaluator 与状态机检查期间集合并发变化。
         */
        public CompletionSnapshot {
            steps = List.copyOf(steps);
            criteria = List.copyOf(criteria);
            stepStatuses = List.copyOf(stepStatuses);
            evidencedCriteria = List.copyOf(evidencedCriteria);
        }
    }
}
