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
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionStatus;

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

    /** 观察只读取 Plan 行、最新事件和步骤摘要，不物化 canonical JSON/draft/evidence。 */
    default PlanObservation readPlanObservation(String planId) {
        throw new UnsupportedOperationException("Plan observation is unavailable");
    }

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

    /** 原子领取 Plan 执行 Turn；预算耗尽时由仓储暂停 run，避免进程内计数绕过上限。 */
    Optional<PlanTurnClaim> claimPlanTurn(ClaimPlanTurn command);

    /** 首次执行冻结实际 RuntimeLease 上限，后续 Turn 不得因配置刷新扩大该 Run。 */
    default void initializePlanBudget(InitializePlanBudget command) {
        throw new UnsupportedOperationException("Plan budget initialization is unavailable");
    }

    /** 读取当前 Run 的冻结剩余预算，验收 Provider 也必须受同一累计墙钟上限约束。 */
    Optional<PlanRunBudget> readPlanRunBudget(String planId, String runId);

    /** Turn 终态结算真实 usage，并在同一事务内累计 Run 预算；重复回调必须幂等。 */
    default void settlePlanTurn(SettlePlanTurn command) {
        throw new UnsupportedOperationException("Plan turn settlement is unavailable");
    }

    /**
     * 交互或人工暂停结束一个活动片段时只结算墙钟；模型和 Tool 用量留到 Turn 最终终态统一结算，
     * 避免同一 Turn 在多次恢复间重复计数。幂等键仍由 plan_events 约束。
     */
    default void settlePlanTurnActivity(SettlePlanTurnActivity command) {
        throw new UnsupportedOperationException("Plan turn activity settlement is unavailable");
    }

    /** 先设置 run pause fence，再等待真实 Turn 结算，Plan 状态随后由 pausePlan CAS 提交。 */
    default Plan requestPlanPause(RequestPlanPause command) {
        throw new UnsupportedOperationException("Plan pause request is unavailable");
    }

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

    /** 最后一项 Tool 结算后重新检查步骤、证据和 pending 资源，再冻结 VERIFYING。 */
    Plan beginPlanVerification(BeginPlanVerification command);

    /** 无 Tool evaluator 的结论与 Run/Plan 状态必须在同一事务中结算。 */
    default Plan completePlanVerification(CompletePlanVerification command) {
        throw new UnsupportedOperationException("Plan verification is unavailable");
    }

    /** 用户暂停保留 run/revision identity，恢复时仍需精确命中同一 run。 */
    default Plan pausePlan(PausePlan command) {
        throw new UnsupportedOperationException("Plan pause is unavailable");
    }

    /** 只有 PAUSED Plan 可以恢复，避免把 APPROVED 误当作可继续的旧 run。 */
    default Plan resumePlan(ResumePlan command) {
        throw new UnsupportedOperationException("Plan resume is unavailable");
    }

    /** 停止是不可逆的 run 结算，但保留冻结 revision 与执行审计。 */
    default Plan stopPlan(StopPlan command) {
        throw new UnsupportedOperationException("Plan stop is unavailable");
    }

    /** 证据只追加并绑定当前 run/revision 的真实来源。 */
    Evidence appendEvidence(AppendEvidence command);

    /** evaluator 请求先持久化，Provider 调用不得早于该提交。 */
    Goal requestEvaluation(RequestEvaluation command);

    /** evaluator 终态推进 VERIFYING；只有完成门另行证明后才可 ACHIEVED。 */
    Goal completeEvaluation(CompleteEvaluation command);

    /** 单个 dispatcher 以 REQUESTED -> RUNNING CAS 领取 evaluator intent。 */
    Optional<EvaluationIntent> claimRequestedEvaluation(String goalId, String runId);

    /** 外部执行前绑定既有 Tool call、run、step 与请求 digest。 */
    ToolAttempt prepareToolAttempt(PrepareToolAttempt command);

    /** 仅 PREPARED attempt 可标记 STARTED，提交后才允许真正执行 Tool。 */
    ToolAttempt startToolAttempt(String toolAttemptId, Instant at);

    /** Tool 终态与可选验收证据在同一事务挂接。 */
    ToolAttempt settleToolAttempt(SettleToolAttempt command);

    /** 启动恢复只读取旧 generation 未结算 attempts。 */
    List<ToolAttempt> listUnsettledToolAttempts(long currentProcessGeneration, int limit);

    /** 启动恢复只读取旧 generation 未结算 evaluator intent。 */
    List<EvaluationIntent> listUnsettledEvaluations(long currentProcessGeneration, int limit);

    /** 旧 evaluator 明确失败；仅仍验证同一 run 的 Goal 才进入可恢复 attention。 */
    Optional<Goal> recoverEvaluation(EvaluationIntent intent, long currentProcessGeneration, Instant at);

    /** Plan-only 旧 run 安全失败回到 APPROVED，未知副作用则永久 STOPPED 等待人工核对。 */
    default Plan recoverPlanExecution(RecoverPlanExecution command) {
        throw new UnsupportedOperationException("Plan execution recovery is unavailable");
    }

    /** 启动恢复有界读取旧 generation 的 EXECUTING/VERIFYING Plan run，不读取正文或 evidence。 */
    default List<PlanExecutionRecovery> listUnsettledPlanExecutions(long currentProcessGeneration, int limit) {
        return List.of();
    }

    /** 启动恢复关闭没有对应 Turn 的 CLAIMED admission intent，避免它永久占用同一 run。 */
    default void settleOrphanedPlanTurnClaims(String runId, Instant at) {
        // 旧数据库/测试 fake 没有 claim ledger 时保持无副作用；生产实现必须做 CAS 收口。
    }

    /** 启动恢复有界读取旧 generation 仍持有的 continuation lease。 */
    List<ContinuationLease> listHeldLeases(long currentProcessGeneration, int limit);

    /** owner Thread 重启发现只查询唯一非终态 Goal。 */
    Optional<Goal> findActiveGoalByOwner(String ownerThreadId);

    /** Tool ledger 只读取 owner 当前唯一 EXECUTING standalone Plan。 */
    Optional<Plan> findExecutingPlanByOwner(String ownerThreadId);

    /** 内部 Turn 的不可变上下文必须精确绑定 run/revision；Goal continuation 还需持有原 fencing lease。 */
    Optional<InternalTurnBinding> findInternalTurnBinding(String turnId);

    /**
     * 启动恢复只接受同一 Goal/run 下的 SUSPENDED 内部 Turn 和 PENDING/ANSWERED Interaction；
     * 该查询同时排除未知副作用，避免应用层用多个快照拼出错误恢复资格。
     */
    default Optional<ContinuationRecoveryCandidate> findContinuationRecovery(String goalId, String runId) {
        return Optional.empty();
    }

    /** 通过 Plan run identity 找到当前可控的内部 Turn，控制动作不能从 owner 最新状态猜测 Turn。 */
    Optional<InternalTurnBinding> findPlanTurnBinding(String planId, String runId);

    /** Extension prompt 只读取 owner 唯一非终态 Plan，不跟随终态历史。 */
    Optional<Plan> findActivePlanByOwner(String ownerThreadId);

    /** 当前工作面恢复只取该 Thread 最近计划，包含可审阅的终态。 */
    Optional<PlanSnapshot> findCurrentPlan(String ownerThreadId);

    /** 按 owner 有界读取不可逆终态，供 thread/read 在重启后恢复 Goal 时间线。 */
    List<TerminalActivity> listTerminalActivities(String ownerThreadId, int limit);

    /** 有界增量读取事件，隐藏 Workbench 不调用该入口。 */
    ReadPage<Event> listEvents(String goalId, long afterSequence, int limit);

    /** Plan 拥有独立事件序列，观察不得借用 Goal event namespace。 */
    ReadPage<PlanEvent> listPlanEvents(String planId, long afterSequence, int limit);

    /** 按不可变 revision number 分页，避免 offset 在追加版本时漂移。 */
    ReadPage<PlanRevision> listPlanRevisions(String planId, long afterRevisionNumber, int limit);

    /** 只读取当前 revision/run 的证据，旧版本证据不进入 evaluator。 */
    List<Evidence> listEvidence(String goalId, String runId, int limit);

    /** 独立 Plan evaluator 只读取当前 run/revision 的证据，不借用 Goal definition 查询。 */
    List<Evidence> listPlanEvidence(String planId, String runId, String planRevisionId, int limit);

    /** Plan evidence 使用 createdAt/evidenceId keyset，迟到证据不会重排上一页。 */
    /** 默认分页只适用于不需要 cursor 的内部读取；UI 分页由仓储实现真实 keyset。 */
    default ReadPage<Evidence> listPlanEvidencePage(String planId, String runId, String planRevisionId,
                                                     String afterCreatedAt, String afterEvidenceId, int limit) {
        return new ReadPage<>(0, 0, listPlanEvidence(planId, runId, planRevisionId, limit));
    }

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
    /** 执行命令在同一事务中记录用户授权、复核 revision/hash，并分配 standalone run。 */
    record ExecutePlan(String planId, long expectedPlanRevision, String planRevisionId, String planHash,
                       String approvalId, String runId, long processGeneration,
                       String eventId, String idempotencyKey, Instant at,
                       Integer maxModelRounds, Integer maxToolCalls, Long wallBudgetMillis,
                       Integer turnBudget) {
        /** 执行事务必须携带完整冻结预算，缺失预算不能伪装为无限或旧格式执行。 */
        public ExecutePlan {
            Objects.requireNonNull(approvalId, "approvalId");
            Objects.requireNonNull(maxModelRounds, "maxModelRounds");
            Objects.requireNonNull(maxToolCalls, "maxToolCalls");
            Objects.requireNonNull(wallBudgetMillis, "wallBudgetMillis");
            Objects.requireNonNull(turnBudget, "turnBudget");
            if (maxModelRounds < 1 || maxModelRounds > 1_000_000
                    || maxToolCalls < 0 || maxToolCalls > 10_000_000
                    || wallBudgetMillis <= 0 || wallBudgetMillis > 86_400_000L * 30
                    || turnBudget < 1 || turnBudget > 256) {
                throw new IllegalArgumentException("invalid Plan execution budget");
            }
        }
    }
    /** completion callback 精确绑定本次 run，迟到 callback 不能停止后续执行。 */
    record SettlePlanExecution(String planId, long expectedPlanRevision, String runId,
                               String eventId, String idempotencyKey, Instant at) { }
    /** Turn 领取 identity；同一 run/turn 重试返回原 ordinal，不重复消耗预算。 */
    record ClaimPlanTurn(String planId, long expectedPlanRevision, String runId, String planRevisionId,
                         String turnId, String eventId, String idempotencyKey, Instant at) { }
    /** effective runtime limits 只允许初始化一次，防止跨 Turn 读取漂移配置。 */
    record InitializePlanBudget(String planId, String runId, int maxModelRounds, int maxToolCalls,
                                long wallBudgetMillis, int antiLoopTurnBudget, Instant at) { }
    /** Turn 完成后只携带不可变身份和活动墙钟增量，模型/Tool 用量由仓储从权威账本读取。 */
    record SettlePlanTurn(String planId, String runId, String turnId, long activeMillis,
                          String eventId, String idempotencyKey, Instant at) {
        /** 活动时长是单调测量值，禁止负数进入 SQLite 累计器。 */
        public SettlePlanTurn {
            if (activeMillis < 0) throw new IllegalArgumentException("activeMillis must be non-negative");
        }
    }
    /** 单个可恢复执行片段的墙钟结算，不读取或累计模型/Tool usage。 */
    record SettlePlanTurnActivity(String planId, String runId, String turnId, long activeMillis,
                                  String eventId, String idempotencyKey, Instant at) {
        /** 活动时长必须为单调非负值，避免暂停重试污染 Run 账本。 */
        public SettlePlanTurnActivity {
            if (activeMillis < 0) throw new IllegalArgumentException("activeMillis must be non-negative");
        }
    }
    /** Run budget 的只读投影；所有 remaining 值均由 SQLite 已用账本计算。 */
    record PlanRunBudget(String planId, String runId, int remainingModelRounds,
                         int remainingToolCalls, long remainingWallBudgetMillis) {
        /** 验收调用允许零剩余预算，但绝不允许负值。 */
        public PlanRunBudget {
            if (remainingModelRounds < 0 || remainingToolCalls < 0 || remainingWallBudgetMillis < 0) {
                throw new IllegalArgumentException("invalid Plan run budget");
            }
        }
    }
    /** pause fence 不增加 Plan revision，允许随后使用同一 expectedPlanRevision 完成状态 CAS。 */
    record RequestPlanPause(String planId, long expectedPlanRevision, String runId,
                            String eventId, String idempotencyKey, Instant at) { }
    /** 已持久化的 Turn ordinal 与本次剩余预算，供调度器决定是否继续。 */
    record PlanTurnClaim(String planId, String runId, String planRevisionId,
                         String turnId, int ordinal, int remainingTurnBudget,
                         int remainingModelRounds, int remainingToolCalls,
                         long remainingWallBudgetMillis) {
        /** 剩余预算允许为零，但不能出现负数或溢出值。 */
        public PlanTurnClaim {
            if (ordinal < 1 || remainingTurnBudget < 0 || remainingModelRounds < 0
                    || remainingToolCalls < 0 || remainingWallBudgetMillis < 0) {
                throw new IllegalArgumentException("invalid remaining Plan budget");
            }
        }
    }
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
    /** VERIFYING mutation 使用 Plan CAS 与 run identity 双重 fencing。 */
    record BeginPlanVerification(String planId, long expectedPlanRevision, String runId,
                                 String eventId, String idempotencyKey, Instant at) { }
    /** evaluator 只提交结构化 verdict；仓储重新检查步骤、证据和 run 状态。 */
    record CompletePlanVerification(String planId, long expectedPlanRevision, String runId,
                                    GoalModels.EvaluationVerdict verdict, String summary,
                                    String eventId, String idempotencyKey, Instant at) { }
    /** 用户 pause 不清除 active run，防止恢复时丢失 Tool ledger 所属关系。 */
    record PausePlan(String planId, long expectedPlanRevision, String runId,
                     String eventId, String idempotencyKey, Instant at) { }
    /** resume 只恢复同一个已暂停 run，不创建第二条执行事实。 */
    record ResumePlan(String planId, long expectedPlanRevision, String runId,
                      String eventId, String idempotencyKey, Instant at) { }
    /** stop 结束当前 run，保留 active identity 供审计和未知副作用核对。 */
    record StopPlan(String planId, long expectedPlanRevision, String runId,
                    String eventId, String idempotencyKey, Instant at) { }
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
    /** 旧 Plan run 的最小恢复事实；unsafe 只表示持久 ledger 无法证明副作用已结算。 */
    record PlanExecutionRecovery(String planId, long expectedPlanRevision, String runId,
                                 String planRevisionId, boolean unsafe) {
        /** 恢复 identity 必须完整，避免扫描结果被错误绑定到后来追加的 revision。 */
        public PlanExecutionRecovery {
            GoalModels.requireId(planId, "planId");
            GoalModels.requireId(runId, "runId");
            GoalModels.requireId(planRevisionId, "planRevisionId");
            if (expectedPlanRevision < 0) throw new IllegalArgumentException("invalid Plan revision");
        }
    }
    /** 增量事件保留 SQLite sequence 和对应 Goal revision。 */
    record Event(long sequence, String eventId, String goalId, long goalRevision,
                 String kind, String payloadJson, Instant createdAt) { }
    /** Plan event 只公开稳定 activity 和 revision 水位，payload 仍由 service 严格映射。 */
    record PlanEvent(long sequence, String eventId, String planId, long planRevision,
                     String activity, Instant createdAt) { }
    /** Plan 事件与同一读事务的轻量步骤进度投影，事件水位不可跨事务拼接。 */
    record PlanObservation(Plan plan, PlanEvent latestEvent, GoalModels.PlanProgress progress) { }
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

    /**
     * 重启后恢复所需的最小事实；turnMutationVersion 与 threadRevision 仅用于显式 Resume CAS，
     * interaction 状态决定是否可以等待用户回答或进入可恢复占位，绝不授权自动调用模型。
     */
    record ContinuationRecoveryCandidate(String goalId, String ownerThreadId, String turnId, String runId,
                                         long threadRevision, long turnMutationVersion,
                                         String interactionRequestId, InteractionStatus interactionStatus,
                                         InternalTurnBinding binding) {
        /** 所有 identity 必须来自同一持久查询，防止跨 Goal、run 或 Turn 串线。 */
        public ContinuationRecoveryCandidate {
            GoalModels.requireId(goalId, "goalId");
            GoalModels.requireId(ownerThreadId, "threadId");
            GoalModels.requireId(turnId, "turnId");
            GoalModels.requireId(runId, "runId");
            GoalModels.requireId(interactionRequestId, "interactionRequestId");
            if (threadRevision < 0 || turnMutationVersion < 0) {
                throw new IllegalArgumentException("invalid continuation recovery revision");
            }
            interactionStatus = Objects.requireNonNull(interactionStatus, "interactionStatus");
            if (interactionStatus != InteractionStatus.PENDING && interactionStatus != InteractionStatus.ANSWERED) {
                throw new IllegalArgumentException("interaction is not recoverable");
            }
            binding = Objects.requireNonNull(binding, "binding");
            if (!goalId.equals(binding.goalId()) || !runId.equals(binding.runId())
                    || !turnId.equals(binding.turnId())) {
                throw new IllegalArgumentException("continuation binding identity mismatch");
            }
        }
    }
}
