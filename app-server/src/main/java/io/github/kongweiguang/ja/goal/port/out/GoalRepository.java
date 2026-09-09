// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.port.out;

import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.domain.GoalModels.Evidence;
import io.github.kongweiguang.ja.goal.domain.GoalModels.EvaluationVerdict;
import io.github.kongweiguang.ja.goal.domain.GoalModels.Goal;
import io.github.kongweiguang.ja.goal.domain.GoalModels.GoalDefinition;
import io.github.kongweiguang.ja.goal.domain.GoalModels.GoalSnapshot;
import io.github.kongweiguang.ja.goal.domain.GoalModels.GoalPhase;
import io.github.kongweiguang.ja.goal.domain.GoalModels.GoalStatus;
import io.github.kongweiguang.ja.goal.domain.GoalModels.OwnerKind;
import io.github.kongweiguang.ja.goal.domain.GoalModels.AcceptanceCriterion;
import io.github.kongweiguang.ja.goal.domain.GoalModels.Plan;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanDraft;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanRevision;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanSnapshot;
import io.github.kongweiguang.ja.goal.domain.GoalModels.StepStatus;
import io.github.kongweiguang.ja.goal.domain.GoalModels.ToolAttempt;
import io.github.kongweiguang.ja.goal.domain.GoalModels.ToolAttemptState;
import io.github.kongweiguang.ja.goal.domain.GoalModels.TerminalActivity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 独立 Plan 与 Goal 聚合的事务端口；每个 mutation 都携带所属 aggregate CAS 与幂等 identity。 */
public interface GoalRepository {
    /** 创建 owner 唯一的非终态 Goal，并写首个 changed 事件。 */
    Goal create(CreateGoal command);

    /** 创建 thread-owned Plan，并写入独立 Plan event 命名空间。 */
    Plan createPlan(CreatePlan command);

    /** 按稳定 identity 读取当前投影。 */
    Optional<Goal> findGoal(String goalId);

    /** 读取 Goal 自身冻结定义；Plan criteria 永远不能替代该定义。 */
    GoalDefinition readGoalDefinition(String goalId, long goalDefinitionRevision);

    /** 在单个读事务中返回 Goal、Plan 与事件边界，禁止 RPC 跨 revision 拼接。 */
    GoalSnapshot readSnapshot(String goalId);

    /** 在单个读事务中返回 Plan 自己的 draft/revision/approval/run 投影。 */
    PlanSnapshot readPlanSnapshot(String planId);

    /** 读取持久草稿供应用层冻结；缺失草稿不回退到最新 revision。 */
    Optional<PlanDraft> findDraft(String planId);

    /** 保存结构化 draft，draftRevision 与 goalRevision 双 CAS。 */
    Plan saveDraft(SaveDraft command);

    /** 显式丢弃未冻结草稿，并与 Goal revision、事件追加原子提交。 */
    Plan discardDraft(DiscardDraft command);

    /** 冻结不可变 revision；同一幂等键重放必须返回原事实。 */
    PlanRevision propose(ProposePlan command);

    /** 仅 USER_UI 可批准精确 revision/hash；批准不产生执行副作用。 */
    Plan approve(ApprovePlan command);

    /** 仅 APPROVED Plan 可显式创建自己的 run/steps。 */
    Plan executePlan(ExecutePlan command);

    /** 单次 hidden Turn 未完成计划时关闭旧 run，并恢复为可显式再次执行的 APPROVED。 */
    Plan settlePlanExecution(SettlePlanExecution command);

    /** 旧批准或显式拒绝都不能启动 run。 */
    Plan reject(RejectPlan command);

    /** attach 只接受同 owner Plan 的当前已批准 revision/hash。 */
    Goal attachPlan(AttachPlan command);

    /** detach 以 Goal CAS 原子切换为新的 Goal-only run，linkRevision 只记录历史。 */
    Goal detachPlan(DetachPlan command);

    /** 用户 pause/resume/stop 与内部 phase 推进共享同一 CAS 门。 */
    Goal transition(Transition command);

    /** 步骤更新保持 stepId，attempt 与失败签名作为恢复事实持久化。 */
    Goal updateStep(UpdateStep command);

    /** 独立 Plan step 更新只推进 Plan aggregate，并在完成门满足后进入 COMPLETED。 */
    Plan updatePlanStep(UpdatePlanStep command);

    /** 证据只追加并绑定当前 run/revision 的真实来源。 */
    Evidence appendEvidence(AppendEvidence command);

    /** evaluator 请求先持久化，Provider 调用不得早于该提交。 */
    Goal requestEvaluation(RequestEvaluation command);

    /** evaluator 终态推进 VERIFYING；只有完成门另行证明后才可 ACHIEVED。 */
    Goal completeEvaluation(CompleteEvaluation command);

    /** 单个 dispatcher 以 REQUESTED -> RUNNING CAS 领取 evaluator intent。 */
    default Optional<EvaluationIntent> claimRequestedEvaluation(String goalId, String runId) {
        return Optional.empty();
    }

    /** 输入请求持久化后 Goal 才投影 WAITING_INPUT。 */
    Goal requestInput(RequestInput command);

    /** 用户响应按 request identity 与 Goal CAS 提交，过期请求失败关闭。 */
    Goal respondInput(RespondInput command);

    /** 外部执行前绑定既有 Tool call、run、step 与请求 digest。 */
    ToolAttempt prepareToolAttempt(PrepareToolAttempt command);

    /** 仅 PREPARED attempt 可标记 STARTED，提交后才允许真正执行 Tool。 */
    ToolAttempt startToolAttempt(String toolAttemptId, Instant at);

    /** Tool 终态与可选验收证据在同一事务挂接。 */
    ToolAttempt settleToolAttempt(SettleToolAttempt command);

    /** 启动恢复只读取旧 generation 未结算 attempts。 */
    List<ToolAttempt> listUnsettledToolAttempts(long currentProcessGeneration, int limit);

    /** 启动恢复只读取旧 generation 未结算 evaluator intent。 */
    default List<EvaluationIntent> listUnsettledEvaluations(long currentProcessGeneration, int limit) {
        return List.of();
    }

    /** 旧 evaluator 明确失败；仅仍验证同一 run 的 Goal 才进入可恢复 attention。 */
    default Optional<Goal> recoverEvaluation(EvaluationIntent intent, long currentProcessGeneration, Instant at) {
        return Optional.empty();
    }

    /** Plan-only 旧 run 安全失败回到 APPROVED，未知副作用则永久 STOPPED 等待人工核对。 */
    default Plan recoverPlanExecution(RecoverPlanExecution command) {
        throw new UnsupportedOperationException("Plan execution recovery is unavailable");
    }

    /** 启动恢复有界读取旧 generation 仍持有的 continuation lease。 */
    default List<ContinuationLease> listHeldLeases(long currentProcessGeneration, int limit) { return List.of(); }

    /** owner Thread 重启发现只查询唯一非终态 Goal。 */
    Optional<Goal> findActiveGoalByOwner(String ownerThreadId);

    /** Tool ledger 只读取 owner 当前唯一 EXECUTING standalone Plan。 */
    Optional<Plan> findExecutingPlanByOwner(String ownerThreadId);

    /** 内部 Turn 的不可变上下文必须精确绑定 run/revision；Goal continuation 还需持有原 fencing lease。 */
    default Optional<InternalTurnBinding> findInternalTurnBinding(String turnId) { return Optional.empty(); }

    /** Extension prompt 只读取 owner 唯一非终态 Plan，不跟随终态历史。 */
    Optional<Plan> findActivePlanByOwner(String ownerThreadId);

    /** 按 owner 有界读取不可逆终态，供 thread/read 在重启后恢复 Goal 时间线。 */
    List<TerminalActivity> listTerminalActivities(String ownerThreadId, int limit);

    /** 有界增量读取事件，隐藏 Workbench 不调用该入口。 */
    ReadPage<Event> listEvents(String goalId, long afterSequence, int limit);

    /** 按不可变 revision number 分页，避免 offset 在追加版本时漂移。 */
    ReadPage<PlanRevision> listPlanRevisions(String planId, long afterRevisionNumber, int limit);

    /** 只读取当前 revision/run 的证据，旧版本证据不进入 evaluator。 */
    List<Evidence> listEvidence(String goalId, String runId, int limit);

    /** UI 按冻结 Plan revision 分页读取证据，不依赖当前 active run。 */
    ReadPage<Evidence> listEvidencePage(String goalId, long goalDefinitionRevision, String planRevisionId,
                                        String afterCreatedAt, String afterEvidenceId, int limit);

    /** SQLite 唯一 lease 保证同一 Goal 自动续跑单飞。 */
    Optional<ContinuationLease> tryAcquireLease(AcquireLease command);

    /** 仅 fencing owner 可 heartbeat。 */
    Optional<ContinuationLease> heartbeatLease(String goalId, String leaseId, long fencingToken, Instant at);

    /** 正常或恢复放弃都持久终态，旧 lease 永不复用。 */
    Optional<ContinuationLease> releaseLease(String goalId, String leaseId, long fencingToken,
                                             boolean abandoned, Instant at);

    /** continuation Turn 无任何 Goal revision 进展时递增熔断计数，第三次原子暂停。 */
    default Goal recordContinuationNoProgress(String goalId, long expectedGoalRevision,
                                              String eventId, String idempotencyKey, Instant at) {
        throw new UnsupportedOperationException("Goal continuation progress is unavailable");
    }

    /** continuation 的 Tool 审批边界推进服务端 phase，不复用用户 pause/resume 控制语义。 */
    default Goal projectContinuationPhase(ProjectContinuationPhase command) {
        throw new UnsupportedOperationException("Goal continuation phase projection is unavailable");
    }

    /** 创建 Goal 的 owner revision 防止绑定已变化 Thread。 */
    record CreateGoal(String goalId, String ownerThreadId, OwnerKind ownerKind, String objective,
                      List<AcceptanceCriterion> acceptanceCriteria, String runId, long processGeneration,
                      long expectedThreadRevision, String idempotencyKey, Instant at) {
        /** initial definition 在同一事务冻结，调用方集合之后不可再改写。 */
        public CreateGoal { acceptanceCriteria = List.copyOf(acceptanceCriteria); }
    }
    /** Plan 创建命令拥有自己的 identity 与 owner CAS。 */
    record CreatePlan(String planId, String ownerThreadId, String objective,
                      long expectedThreadRevision, String idempotencyKey, Instant at) { }
    /** 草稿保存同时检查 Plan 与 draft 两级 revision。 */
    record SaveDraft(String planId, long expectedPlanRevision, long expectedDraftRevision,
                     String definitionJson, String basedOnPlanRevisionId,
                     String eventId, String idempotencyKey, Instant at) { }
    /** 草稿丢弃仍是 Plan mutation，避免 reload 后旧草稿复现。 */
    record DiscardDraft(String planId, long expectedPlanRevision,
                        String eventId, String idempotencyKey, Instant at) { }
    /** 提案携带已经 canonical 化的完整不可变版本。 */
    record ProposePlan(String planId, long expectedPlanRevision, PlanRevision revision,
                       String eventId, String idempotencyKey, Instant at) { }
    /** 批准命令绑定精确 revision/hash 并分配 run identity。 */
    record ApprovePlan(String planId, long expectedPlanRevision, String planRevisionId, String planHash,
                       String approvalId,
                       String eventId, String idempotencyKey, Instant at) { }
    /** 执行命令复核批准 revision/hash，并分配 standalone run。 */
    record ExecutePlan(String planId, long expectedPlanRevision, String planRevisionId, String planHash,
                       String runId, long processGeneration,
                       String eventId, String idempotencyKey, Instant at) { }
    /** completion callback 精确绑定本次 run，迟到 callback 不能停止后续执行。 */
    record SettlePlanExecution(String planId, long expectedPlanRevision, String runId,
                               String eventId, String idempotencyKey, Instant at) { }
    /** 拒绝只写用户决定，不创建 run。 */
    record RejectPlan(String planId, long expectedPlanRevision, String planRevisionId, String planHash,
                      String approvalId, String reason, String eventId, String idempotencyKey, Instant at) { }
    /** linkRevision 由仓储分配，attach 只携带被批准的精确 revision/hash。 */
    record AttachPlan(String goalId, long expectedGoalRevision, String planId,
                      String planRevisionId, String planHash,
                      String runId, long processGeneration,
                      String eventId, String idempotencyKey, Instant at) { }
    /** detach 由服务端分配新的 Goal-only run identity，避免复用已停止 run。 */
    record DetachPlan(String goalId, long expectedGoalRevision, String replacementRunId, long processGeneration,
                      String eventId, String idempotencyKey, Instant at) { }
    /** 状态转换显式携带目标 phase 和恢复风险投影。 */
    record Transition(String goalId, long expectedGoalRevision, GoalStatus status, GoalPhase phase,
                      boolean recoveryRequired, String eventId, String idempotencyKey, Instant at) { }
    /** step attempt 更新同时携带旧状态门和失败签名。 */
    record UpdateStep(String goalId, long expectedGoalRevision, String runId, String stepId,
                      StepStatus expectedStatus, StepStatus status, String failureSignature,
                      List<ToolEvidenceClaim> evidenceClaims,
                      String eventId, String idempotencyKey, Instant at) {
        /** claims 与步骤状态共享一次 writer transaction。 */
        public UpdateStep { evidenceClaims = List.copyOf(evidenceClaims); }
    }
    /** Plan step 与 evidence 在同一 writer transaction 结算。 */
    record UpdatePlanStep(String planId, long expectedPlanRevision, String runId, String stepId,
                          StepStatus expectedStatus, StepStatus status, String failureSignature,
                          List<ToolEvidenceClaim> evidenceClaims,
                          String eventId, String idempotencyKey, Instant at) {
        /** evidence claim 在事务开始前冻结，避免 retry 看见不同集合。 */
        public UpdatePlanStep { evidenceClaims = List.copyOf(evidenceClaims); }
    }
    /** evidenceId 由应用层生成，其余字段必须由 repository 对真实 Tool attempt 复核。 */
    record ToolEvidenceClaim(String evidenceId, String criterionId, String callId,
                             String summary, Instant observedAt) { }
    /** 证据写入只接受已构造的真实来源领域值。 */
    record AppendEvidence(String goalId, long expectedGoalRevision, Evidence evidence,
                          String eventId, String idempotencyKey, Instant at) { }
    /** evaluator intent 冻结本次模型与当前 run/revision。 */
    record RequestEvaluation(String goalId, long expectedGoalRevision, String evaluationId,
                             String runId, String planRevisionId, long processGeneration,
                             List<ToolEvidenceClaim> evidenceClaims,
                             String eventId, String idempotencyKey, Instant at) {
        /** 证据与 intent 在同一 writer transaction 冻结。 */
        public RequestEvaluation { evidenceClaims = List.copyOf(evidenceClaims); }
    }
    /** evaluator 结算携带结构化 criteria JSON 或稳定失败码。 */
    record CompleteEvaluation(String goalId, long expectedGoalRevision, String evaluationId,
                               EvaluationVerdict verdict, String criteriaJson, String summary, String errorCode,
                               String eventId, String idempotencyKey, Instant at) { }
    /** 输入请求可选绑定 run 和服务端过期时间。 */
    record RequestInput(String goalId, long expectedGoalRevision, String inputRequestId, String runId,
                        String prompt, Instant expiresAt, String eventId,
                        String idempotencyKey, Instant at) { }
    /** 用户响应只结算一个 pending request。 */
    record RespondInput(String goalId, long expectedGoalRevision, String inputRequestId,
                        String responseJson, String eventId, String idempotencyKey, Instant at) { }
    /** prepare 命令必须引用已持久化的 Turn Tool call。 */
    record PrepareToolAttempt(ToolAttempt attempt) { }
    /** settlement 只允许真实 terminal digest，证据可为空。 */
    record SettleToolAttempt(String toolAttemptId, ToolAttemptState state, String resultDigest,
                             Evidence evidence, Instant at) { }
    /** lease 申请绑定当前 App Server process generation。 */
    record AcquireLease(String goalId, String leaseId, long processGeneration, Instant at) { }
    /** 只允许活动 continuation 在 working 与 waiting approval 之间投影。 */
    record ProjectContinuationPhase(String goalId, long expectedGoalRevision,
                                    GoalPhase expectedPhase, GoalPhase phase,
                                    String eventId, String idempotencyKey, Instant at) { }
    /** unsafe 表示副作用 Tool 已 STARTED 且结果未知，禁止再次执行同一 Plan。 */
    record RecoverPlanExecution(String planId, long expectedPlanRevision, String runId, boolean unsafe,
                                String eventId, String idempotencyKey, Instant at) { }
    /** 增量事件保留 SQLite sequence 和对应 Goal revision。 */
    record Event(long sequence, String eventId, String goalId, long goalRevision,
                 String kind, String payloadJson, Instant createdAt) { }
    /** 读页事实与快照边界在同一事务获取，opaque cursor 由 GoalService 生成。 */
    record ReadPage<T>(long goalRevision, long eventSequence, List<T> items) {
        /** 防御性复制避免事务外调用方修改同一快照页。 */
        public ReadPage {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }
    /** continuation lease 回执携带 fencing token，旧 owner 不能续约。 */
    record ContinuationLease(String goalId, String leaseId, long processGeneration,
                             long fencingToken, String state, Instant acquiredAt,
                             Instant heartbeatAt, Instant releasedAt) { }
    /** evaluator intent 固定请求时模型身份，dispatcher 必须再与当前 Thread 偏好核对。 */
    record EvaluationIntent(String evaluationId, String goalId, long goalDefinitionRevision,
                            String runId, String planRevisionId,
                            long processGeneration, String modelId, String providerId, Instant requestedAt) { }

    /** 内部 Turn binding 来自不可变 SQLite context，不允许 ledger 从 owner 当前状态反推旧 Turn 身份。 */
    record InternalTurnBinding(String turnId, String origin, String goalId, String planId, String runId,
                               Long goalDefinitionRevision, String planRevisionId, String planHash,
                               Long fencingToken) {
        /** 两种内部 origin 使用互斥必填字段，避免 nullable 组合产生第三种含义。 */
        public InternalTurnBinding {
            GoalModels.requireId(turnId, "turnId");
            GoalModels.requireId(runId, "runId");
            boolean planTuple = planId != null || planRevisionId != null || planHash != null;
            if (planTuple && (planId == null || planRevisionId == null || planHash == null
                    || !planHash.matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException("invalid internal Plan binding");
            }
            if ("GOAL_CONTINUATION".equals(origin)) {
                GoalModels.requireId(goalId, "goalId");
                if (goalDefinitionRevision == null || goalDefinitionRevision < 1
                        || fencingToken == null || fencingToken < 1) {
                    throw new IllegalArgumentException("invalid Goal continuation binding");
                }
            } else if ("PLAN_EXECUTION".equals(origin)) {
                if (goalId != null || goalDefinitionRevision != null || fencingToken != null || !planTuple) {
                    throw new IllegalArgumentException("invalid Plan execution binding");
                }
            } else {
                throw new IllegalArgumentException("invalid internal Turn origin");
            }
        }
    }
}
