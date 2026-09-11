// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Plan/Goal 领域闭集；wire 和数据库只能显式映射这些值，禁止字符串降级。 */
public final class GoalModels {
    /** Goal 可持久状态闭集。 */
    public enum GoalStatus {
        /** 已创建并允许独立续跑。 */ ACTIVE,
        /** 用户暂停或安全熔断。 */ PAUSED,
        /** 完成门已通过。 */ ACHIEVED,
        /** 用户明确停止。 */ STOPPED
    }
    /** 服务端从持久事实推导的细粒度阶段。 */
    public enum GoalPhase {
        /** 当前可执行。 */ WORKING,
        /** 等待 Tool 审批。 */ WAITING_APPROVAL,
        /** 等待用户输入。 */ WAITING_INPUT,
        /** 正在独立验收。 */ VERIFYING,
        /** 风险或失败需要处理。 */ NEEDS_ATTENTION,
        /** 用户主动暂停。 */ PAUSED,
        /** 客观达成。 */ ACHIEVED,
        /** 永久停止。 */ STOPPED
    }
    /** Plan 是独立 thread-owned aggregate，不借用 Goal 生命周期表达编辑或执行状态。 */
    public enum PlanStatus {
        /** 可编辑草稿。 */ DRAFT,
        /** 冻结 revision 等待用户批准。 */ AWAITING_APPROVAL,
        /** 用户已批准精确 revision/hash，但尚未选择独立执行或挂接 Goal。 */ APPROVED,
        /** 已批准 revision 的独立 run 正在执行。 */ EXECUTING,
        /** 步骤已收口，正在进行无 Tool 的独立验收。 */ VERIFYING,
        /** 用户暂停或验收证据不足；保留当前 run 供显式恢复。 */ PAUSED,
        /** 计划自己的完成门已通过。 */ COMPLETED,
        /** 用户明确停止计划。 */ STOPPED
    }
    /** Goal owner 只允许主 Thread 或独立 Side Task。 */
    public enum OwnerKind {
        /** 主 Thread。 */ ROOT_THREAD,
        /** 独立 Side Task。 */ INDEPENDENT_TASK
    }
    /** 步骤执行状态闭集。 */
    public enum StepStatus {
        /** 依赖未满足。 */ PENDING,
        /** 可领取执行。 */ READY,
        /** attempt 正在执行。 */ RUNNING,
        /** 等待问题处理。 */ BLOCKED,
        /** 成功终态。 */ SUCCEEDED,
        /** 可重试失败。 */ FAILED,
        /** 非必要步骤显式跳过。 */ SKIPPED
    }
    /** 单次批准版本的执行状态闭集。 */
    public enum RunStatus {
        /** identity 已提交。 */ PREPARED,
        /** 正在工作。 */ RUNNING,
        /** 正在验收。 */ VERIFYING,
        /** 随 Goal 暂停。 */ PAUSED,
        /** 已完成。 */ COMPLETED,
        /** 已停止。 */ STOPPED
    }
    /** 可形成客观验收证据的真实来源闭集。 */
    public enum EvidenceSource {
        /** Tool result ledger，必须能关联持久调用 identity。 */
        TOOL_RESULT,
        /** 测试 runner 报告。 */ TEST_REPORT,
        /** 构建产物。 */ BUILD_ARTIFACT,
        /** 仓库状态。 */ REPOSITORY_STATE,
        /** UI 自动化断言。 */ UI_ASSERTION,
        /** 用户明确验收。 */ USER_ACCEPTANCE
    }
    /** evaluator 调用生命周期。 */
    public enum EvaluationStatus {
        /** intent 已提交。 */ REQUESTED,
        /** 请求正在进行。 */ RUNNING,
        /** verdict 已提交。 */ COMPLETED,
        /** 调用或结构解析失败。 */ FAILED
    }
    /** evaluator 的结构化结论。 */
    public enum EvaluationVerdict {
        /** 全部条件满足。 */ MET,
        /** 至少一项不满足。 */ NOT_MET,
        /** 证据不足。 */ INCONCLUSIVE
    }
    /** Tool attempt 的恢复状态闭集。 */
    public enum ToolAttemptState {
        /** tool-call identity 已提交。 */ PREPARED,
        /** 外部执行已经开始。 */ STARTED,
        /** 真实成功结果已结算。 */ SUCCEEDED,
        /** 真实失败结果已结算。 */ FAILED,
        /** 旧进程副作用结果未知。 */ UNKNOWN
    }

    /** Goal 投影携带独立 definition revision 与 mutation CAS revision，二者禁止互相替代。 */
    public record Goal(String goalId, String ownerThreadId, OwnerKind ownerKind, String objective,
                       long goalDefinitionRevision, GoalStatus status,
                       GoalPhase phase, long revision, String activeRunId,
                       int turnsWithoutProgress, int repeatedFailureCount, String lastFailureSignature,
                       boolean recoveryRequired, Instant createdAt, Instant updatedAt) {
        /** 构造时验证跨字段不变量，让损坏持久行在 adapter 边界失败关闭。 */
        public Goal {
            requireId(goalId, "goalId");
            requireId(ownerThreadId, "ownerThreadId");
            Objects.requireNonNull(ownerKind, "ownerKind");
            objective = requireText(objective, "objective", 1, 32768);
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (goalDefinitionRevision < 1 || revision < 0 || turnsWithoutProgress < 0 || turnsWithoutProgress > 3
                    || repeatedFailureCount < 0 || repeatedFailureCount > 3) {
                throw new IllegalArgumentException("invalid Goal counters");
            }
        }
    }

    /** Goal definition 冻结 objective 与验收条件；本阶段只有初始 revision，但证据仍必须精确绑定。 */
    public record GoalDefinition(String goalId, long revisionNumber, String objective,
                                 List<AcceptanceCriterion> acceptanceCriteria, Instant createdAt) {
        /** 空 criteria 合法，evaluator 仍需按 objective 给出总体 verdict。 */
        public GoalDefinition {
            requireId(goalId, "goalId");
            objective = requireText(objective, "objective", 1, 32768);
            acceptanceCriteria = List.copyOf(Objects.requireNonNull(acceptanceCriteria, "acceptanceCriteria"));
            if (revisionNumber < 1 || acceptanceCriteria.size() > 256) {
                throw new IllegalArgumentException("invalid Goal definition revision");
            }
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /** Plan 自己持有 thread identity、CAS revision 和 run，不通过 Goal 投影编辑状态。 */
    public record Plan(String planId, String ownerThreadId, String objective, PlanStatus status,
                       long revision, String activePlanRevisionId, String activeRunId,
                       Instant createdAt, Instant updatedAt) {
        /** 运行态必须同时绑定冻结 revision 与 run，草稿/待批准态不得伪造 active identity。 */
        public Plan {
            requireId(planId, "planId");
            requireId(ownerThreadId, "ownerThreadId");
            objective = requireText(objective, "objective", 1, 32768);
            Objects.requireNonNull(status, "status");
            if (revision < 0) throw new IllegalArgumentException("invalid Plan revision");
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

    /** Goal 与 Plan 的活动关系只冻结一个已批准 revision/hash，linkRevision 用于 detach CAS。 */
    public record GoalPlanLink(String goalId, String planId, String planRevisionId, String planHash,
                               long linkRevision, Instant attachedAt) {
        /** link 不接受“跟随最新版本”，避免 Plan 后续编辑静默改变 Goal 执行定义。 */
        public GoalPlanLink {
            requireId(goalId, "goalId");
            requireId(planId, "planId");
            requireId(planRevisionId, "planRevisionId");
            if (planHash == null || !planHash.matches("[0-9a-f]{64}") || linkRevision < 1) {
                throw new IllegalArgumentException("invalid Goal Plan link");
            }
            Objects.requireNonNull(attachedAt, "attachedAt");
        }
    }

    /** 冻结版本同时保存 canonical JSON 与摘要字段，权威 hash 只覆盖 canonical JSON。 */
    public record PlanRevision(String planRevisionId, String planId, int revisionNumber,
                               PlanDefinition definition, String canonicalJson, String planHash,
                               String createdBy, Instant createdAt) {
        /** 冻结版本必须自包含可复算 hash 的权威 JSON，不能只依赖拆表投影重建。 */
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

    /** 可变草稿的 revision 与冻结 PlanRevision 分离，UI 保存失败时可精确保留本地编辑。 */
    public record PlanDraft(String planDraftId, String planId, long draftRevision,
                            PlanDefinition definition, String basePlanRevisionId, Instant updatedAt) { }

    /** 只投影已批准事实；拒绝决定保留在事件流，不冒充当前 approval。 */
    public record PlanApproval(String approvalId, String planId, String planRevisionId,
                               String planHash, Instant approvedAt) { }

    /** 当前 run 的步骤投影保持 attempt 与终态时间，摘要缺失时显式为 null。 */
    public record StepExecution(String stepId, String runId, StepStatus status, int attempt,
                                String failureSignature, String summary,
                                Instant startedAt, Instant completedAt) { }

    /** 已完成 evaluator 的公开投影只包含结构化 verdict 与逐条件结论。 */
    public record GoalEvaluation(String evaluationId, String goalId, long goalDefinitionRevision,
                                 String planRevisionId, String runId,
                                 EvaluationVerdict verdict, List<CriterionEvaluation> criteria,
                                 String summary, Instant completedAt) {
        /** 防御性复制 evaluator 输出，避免 RPC 映射期间被调用方修改。 */
        public GoalEvaluation {
            criteria = List.copyOf(Objects.requireNonNull(criteria, "criteria"));
        }
    }

    /** Plan 读模型由仓储在一个读事务内组装，Goal snapshot 不再嵌入这份独立 aggregate。 */
    public record PlanSnapshot(Plan plan, PlanDraft draft, PlanRevision currentRevision, PlanApproval approval,
                               List<StepExecution> stepExecutions, long eventSequence) {
        /**
         * 冻结列表并校验 active identity；批准后的 snapshot 若跟随 latest revision，可能让用户看到
         * 未批准定义或让执行器使用错误步骤，因此在领域边界直接失败关闭。
         */
        public PlanSnapshot {
            Objects.requireNonNull(plan, "plan");
            stepExecutions = List.copyOf(Objects.requireNonNull(stepExecutions, "stepExecutions"));
            if (eventSequence < 0) throw new IllegalArgumentException("invalid Plan event sequence");
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

    /** Plan 实时观察只携带步骤计数与当前动作，不物化正文、证据或内部运行 identity。 */
    public record PlanProgress(String currentStepId, String currentStepTitle,
                               int completedRequiredSteps, int totalRequiredSteps) {
        /** 当前步骤允许为空，计数必须保持有界且完成数不能超过总数。 */
        public PlanProgress {
            if (currentStepId != null) requireId(currentStepId, "currentStepId");
            if (currentStepTitle != null) requireText(currentStepTitle, "currentStepTitle", 1, 240);
            if (completedRequiredSteps < 0 || totalRequiredSteps < 0
                    || completedRequiredSteps > totalRequiredSteps) {
                throw new IllegalArgumentException("invalid Plan progress");
            }
        }
    }

    /** 公开 Goal 快照补齐进度与验收事实；公共 Interaction 聚合独立承载未决输入。 */
    public record GoalSnapshot(Goal goal, GoalDefinition definition, GoalPlanLink planLink, String currentStepId,
                               int completedRequiredSteps, int totalRequiredSteps,
                               String attentionReason,
                               Instant achievedAt, Instant stoppedAt,
                               GoalEvaluation latestEvaluation, long eventSequence) {
        /** 进度与序号在 Repository 事务内冻结，防止返回自相矛盾的计数。 */
        public GoalSnapshot {
            Objects.requireNonNull(goal, "goal");
            Objects.requireNonNull(definition, "definition");
            if (!definition.goalId().equals(goal.goalId())
                    || definition.revisionNumber() != goal.goalDefinitionRevision()) {
                throw new IllegalArgumentException("Goal definition does not match Goal projection");
            }
            if (completedRequiredSteps < 0 || totalRequiredSteps < completedRequiredSteps
                    || eventSequence < 0) {
                throw new IllegalArgumentException("invalid Goal snapshot counters");
            }
        }
    }

    /** append-only Goal event 的公开词汇由 application 层从持久 activity 严格映射。 */
    public record PublicEvent(long eventSequence, long goalRevision, String kind,
                              String summary, Instant occurredAt) { }

    /**
     * 终态时间线只保留 Goal identity、用户目标与完成事实；计划正文和运行期事件继续由 Goal 查询承载，
     * 避免 thread/read 随目标历史增长而无界物化。
     */
    public record TerminalActivity(String goalId, String ownerThreadId, String objective, GoalStatus status,
                                   long goalRevision, long eventSequence, Instant occurredAt) {
        /** 只有不可逆终态能进入 Conversation 时间线，防止暂停态被误呈现为历史结论。 */
        public TerminalActivity {
            requireId(goalId, "goalId");
            requireId(ownerThreadId, "ownerThreadId");
            objective = requireText(objective, "objective", 1, 32768);
            if (status != GoalStatus.ACHIEVED && status != GoalStatus.STOPPED) {
                throw new IllegalArgumentException("terminal Goal status is required");
            }
            if (goalRevision < 1 || eventSequence < 1) {
                throw new IllegalArgumentException("invalid terminal Goal sequence");
            }
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /** 查询分页携带读取时 Goal revision 与 eventSequence，opaque cursor 只由 application 层解释。 */
    public record Page<T>(String aggregateId, long aggregateRevision, long eventSequence,
                          List<T> items, String nextCursor) {
        /** 分页结果在离开仓储事务后保持不可变。 */
        public Page {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }

    /** 计划定义是结构化权威源，不允许 Markdown 清单或文本完成标记参与状态判断。 */
    public record PlanDefinition(String objective, List<String> scope, List<String> nonGoals,
                                 List<String> constraints, List<String> dependencies, List<PlanStep> steps,
                                 List<AcceptanceCriterion> acceptanceCriteria,
                                 List<String> risks, List<String> verificationStrategy) {
        /** 防御性复制保证批准后无法通过调用方持有的可变 List 篡改定义。 */
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

        /** 返回独立快照，避免批准后的权威定义被调用方通过集合引用间接修改。 */
        @Override public List<String> scope() { return new java.util.ArrayList<>(scope); }
        /** non-goals 与 scope 使用相同隔离边界，调用方排序也不能影响 plan hash。 */
        @Override public List<String> nonGoals() { return new java.util.ArrayList<>(nonGoals); }
        /** 约束快照允许调用方本地加工，但冻结 revision 始终保持原值。 */
        @Override public List<String> constraints() { return new java.util.ArrayList<>(constraints); }
        /** 依赖描述是冻结输入的一部分，对外只提供副本。 */
        @Override public List<String> dependencies() { return new java.util.ArrayList<>(dependencies); }
        /** 风险条目不共享内部集合，避免 evaluator 输入在运行期间漂移。 */
        @Override public List<String> risks() { return new java.util.ArrayList<>(risks); }
        /** 验证策略副本保持 revision 不可变，同时不要求调用方了解集合实现。 */
        @Override public List<String> verificationStrategy() {
            return new java.util.ArrayList<>(verificationStrategy);
        }
    }

    /** stepId 在所有状态变化中保持稳定；依赖仅引用同一 revision 的 stepId。 */
    public record PlanStep(String stepId, String title, String description,
                           boolean required, List<String> dependsOn) {
        /** 限制可见文本与依赖数量，防止计划被用作无界隐藏上下文。 */
        public PlanStep {
            requireId(stepId, "stepId");
            title = requireText(title, "title", 1, 240);
            description = requireText(description, "description", 0, 4000);
            dependsOn = copyIds(dependsOn, "dependsOn", 64);
        }

        /** DAG 依赖以副本暴露，防止步骤关系在批准后被外部引用改写。 */
        @Override public List<String> dependsOn() { return new java.util.ArrayList<>(dependsOn); }
    }

    /** criterionId 是证据挂接身份，描述修改必须创建新 PlanRevision。 */
    public record AcceptanceCriterion(String criterionId, String description, boolean required) {
        /** 必要验收至少有一项由 PlanDefinition 的整体校验进一步保证。 */
        public AcceptanceCriterion {
            requireId(criterionId, "criterionId");
            description = requireText(description, "description", 1, 2000);
        }
    }

    /** Evidence 只能引用真实来源 identity 和 digest，不承载未经证明的模型正文。 */
    public record Evidence(String evidenceId, String goalId, String planId, Long goalDefinitionRevision,
                           String runId, String planRevisionId,
                           String criterionId, String stepId, EvidenceSource sourceType,
                           String sourceId, String summary, String digest,
                           Instant observedAt, Instant createdAt) {
        /** Goal 与独立 Plan 至少拥有一个 owner；Goal-owned 证据必须冻结 definition revision。 */
        public Evidence {
            if (goalId == null && planId == null) throw new IllegalArgumentException("evidence owner is required");
            if (goalId != null) {
                requireId(goalId, "goalId");
                if (goalDefinitionRevision == null || goalDefinitionRevision < 1) {
                    throw new IllegalArgumentException("Goal evidence definition revision is required");
                }
            } else if (goalDefinitionRevision != null) {
                throw new IllegalArgumentException("Plan-only evidence cannot bind a Goal definition");
            }
            if (planId != null) requireId(planId, "planId");
        }
    }

    /** Tool attempt 把 Goal step 与既有持久 tool call 绑定，恢复不得生成新 call identity。 */
    public record ToolAttempt(String toolAttemptId, String goalId, String planId, Long goalDefinitionRevision,
                              String runId, String planRevisionId, String stepId,
                              int attempt, String turnId, String callId,
                              long processGeneration, boolean sideEffect, ToolAttemptState state,
                              String requestDigest, String resultDigest, Instant preparedAt,
                              Instant startedAt, Instant completedAt) { }

    /** 独立 evaluator 的逐条件结果保持结构化，非法 verdict 不会映射成成功。 */
    public record CriterionEvaluation(String criterionId, EvaluationVerdict verdict, String reason) { }

    /** 完成门只接受当前 run/revision 的聚合快照。 */
    public record CompletionSnapshot(List<PlanStep> steps, List<AcceptanceCriterion> criteria,
                                     List<StepStatus> stepStatuses, List<String> evidencedCriteria,
                                     boolean unresolvedInput, boolean unresolvedApproval,
                                     boolean unresolvedTool, boolean unresolvedTask,
                                     EvaluationVerdict evaluatorVerdict) {
        /** 防御性复制避免 evaluator 与状态机检查期间集合并发变化。 */
        public CompletionSnapshot {
            steps = List.copyOf(steps);
            criteria = List.copyOf(criteria);
            stepStatuses = List.copyOf(stepStatuses);
            evidencedCriteria = List.copyOf(evidencedCriteria);
        }
    }

    /** ID 仅接受有界可打印身份，数据库键不承载 prompt 或路径。 */
    public static String requireId(String value, String label) {
        String result = Objects.requireNonNull(value, label).strip();
        if (result.isEmpty() || result.length() > 160 || !result.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return result;
    }

    /** 文本约束集中处理 Unicode code unit 上限，防止各 RPC 入口产生不同容量。 */
    private static String requireText(String value, String label, int minimum, int maximum) {
        String result = Objects.requireNonNull(value, label).strip();
        if (result.length() < minimum || result.length() > maximum) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return result;
    }

    /** 文本列表冻结且逐项验证，避免 null 和超长项绕过整体 JSON 大小约束。 */
    private static List<String> copyText(List<String> values, String label, int maximumItems) {
        List<String> result = List.copyOf(Objects.requireNonNull(values, label));
        if (result.size() > maximumItems) throw new IllegalArgumentException(label + " is too large");
        return result.stream().map(value -> requireText(value, label, 1, 2000)).toList();
    }

    /** 依赖 identity 使用和实体 ID 相同的严格闭集。 */
    private static List<String> copyIds(List<String> values, String label, int maximumItems) {
        List<String> result = List.copyOf(Objects.requireNonNull(values, label));
        if (result.size() > maximumItems) throw new IllegalArgumentException(label + " is too large");
        return result.stream().map(value -> requireId(value, label)).toList();
    }

    /** 工具类不进入依赖注入图。 */
    private GoalModels() { }
}
