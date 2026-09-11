// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.port.in;

import io.github.kongweiguang.ja.goal.domain.GoalModels.Goal;
import io.github.kongweiguang.ja.goal.domain.GoalModels.AcceptanceCriterion;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanDefinition;
import io.github.kongweiguang.ja.goal.domain.GoalModels.Plan;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanStatus;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanRevision;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanSnapshot;
import io.github.kongweiguang.ja.goal.domain.GoalModels.StepStatus;
import io.github.kongweiguang.ja.goal.domain.GoalModels.Evidence;
import io.github.kongweiguang.ja.goal.domain.GoalModels.GoalSnapshot;
import io.github.kongweiguang.ja.goal.domain.GoalModels.Page;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PublicEvent;
import io.github.kongweiguang.ja.goal.domain.GoalModels.TerminalActivity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** UI 与内建 Extension 共享的受限 Goal 用例端口；不暴露 Mapper 或 SqlSession。 */
public interface GoalUseCase {
    /** 注册唯一运行连接的事件出口；关闭句柄只取消进程内订阅。 */
    AutoCloseable subscribe(GoalEventSink sink);

    /** Plan observation 与 Goal observation 分离，连接关闭只撤销自身计划订阅。 */
    AutoCloseable subscribePlan(PlanEventSink sink);

    /** 返回同一事务边界内的 Goal、可选 link 与 Goal 验收投影。 */
    GoalSnapshot read(String goalId);

    /** 返回独立 Plan aggregate 的草稿、冻结版本、批准与 run 投影。 */
    PlanSnapshot readPlan(String planId);

    /** 重启后按 owner 恢复最近计划，不要求前端提前知道 Plan identity 或存在 Goal。 */
    Optional<PlanSnapshot> findCurrentPlan(String ownerThreadId);

    /** 为 Extension 冻结 owner 当前 Goal identity；缺失时返回空而不暴露 Repository。 */
    default Optional<GoalTurnContext> currentGoalContext(String ownerThreadId) { return Optional.empty(); }

    /** 内部 Goal Turn 优先按持久 turnId 解析；pre-admission 时回退到 owner 唯一活动 Goal。 */
    default Optional<GoalTurnContext> goalContinuationContext(String ownerThreadId, String turnId) {
        return currentGoalContext(ownerThreadId);
    }

    /** 为 Plan mode/内部执行冻结 owner 当前非终态 Plan identity。 */
    default Optional<PlanTurnContext> currentPlanContext(String ownerThreadId) { return Optional.empty(); }

    /** 内部执行在 admission 前读取 owner 唯一 EXECUTING Plan；admission 后由持久 Turn identity 复核。 */
    default Optional<PlanTurnContext> planExecutionContext(String ownerThreadId, String turnId) {
        return Optional.empty();
    }

    /** 按 opaque cursor 分页读取用户可见历史。 */
    Page<PublicEvent> readEvents(String goalId, String cursor, int limit);

    /** 独立 Plan 事件查询使用自己的水位，供 Workbench 增量对账。 */
    Page<PublicEvent> readPlanEvents(String planId, String cursor, int limit);

    /** 为 owner Thread 恢复有界 Goal 终态时间线，不读取计划正文或运行期事件。 */
    List<TerminalActivity> listTerminalActivities(String ownerThreadId, int limit);

    /** 按 revision number 分页读取独立 Plan 的不可变版本。 */
    Page<PlanRevision> listRevisions(String planId, String cursor, int limit);

    /** 按 Goal definition revision 分页读取验收证据；Plan revision 可为空。 */
    Page<Evidence> listEvidence(String goalId, long goalDefinitionRevision,
                                String planRevisionId, String cursor, int limit);

    /** Plan evidence 必须精确绑定当前冻结 revision 与 run。 */
    Page<Evidence> listPlanEvidence(String planId, String planRevisionId, String runId,
                                    String cursor, int limit);

    /** 创建 ACTIVE/WORKING Goal，并冻结初始 definition 与 Goal-only run。 */
    Goal create(Create command);

    /** 创建 thread-owned DRAFT Plan；Plan 不要求先存在 Goal。 */
    Plan createPlan(CreatePlan command);

    /** UI 保存结构化草稿，不产生批准。 */
    Plan saveDraft(SaveDraft command);

    /** UI 显式丢弃当前可变草稿。 */
    Plan discardDraft(DiscardDraft command);

    /** UI 从已保存草稿冻结当前 Plan revision。 */
    PlanRevision proposeDraft(ProposeDraft command);

    /** Agent 或 UI 将草稿冻结为可批准 revision。 */
    PlanRevision propose(Propose command);

    /** USER_UI 对精确 revision/hash 批准；批准本身不创建执行 run。 */
    Plan approve(Approve command);

    /**
     * 对已批准 blueprint 显式创建 standalone run；事件出口只绑定发起连接，Plan 不借用 Goal
     * observation，也不向未发起执行的窗口广播内部 Turn。
     */
    Plan executePlan(ExecutePlan command, PlanExecutionEventSink events);

    /** Plan 执行控制与 Goal 控制分离；每个动作只作用于精确的 run identity。 */
    Plan pausePlan(PlanControl command);

    /** 从 PAUSED 恢复原 run，不重新批准或创建第二个 run。 */
    Plan resumePlan(PlanControl command);

    /**
     * 由发起恢复动作的连接提供 Plan Turn 事件出口；默认实现仅兼容无连接的内部调用。
     */
    default Plan resumePlan(PlanControl command, PlanExecutionEventSink events) {
        return resumePlan(command);
    }

    /** 停止 Plan run 并保留审计事实，不自动回滚工作区。 */
    Plan stopPlan(PlanControl command);

    /** UI 拒绝当前待批准 revision 并回到 planning。 */
    Plan reject(Reject command);

    /** 将同 owner 的 Goal 绑定到已批准精确 Plan revision/hash。 */
    Goal attachPlan(AttachPlan command);

    /** 解除精确 link revision，并让 Goal 切回新的 Goal-only run。 */
    Goal detachPlan(DetachPlan command);

    /** UI 状态控制入口。 */
    Goal control(Control command);

    /** Agent 只能推进当前 run 的稳定步骤。 */
    Goal updateStep(StepUpdate command);

    /** Agent 推进独立 Plan run；Goal run 与 Plan run 的 CAS 不得混用。 */
    Plan updatePlanStep(PlanStepUpdate command);

    /** Agent 请求独立 evaluator intent。 */
    Goal requestEvaluation(EvaluationRequest command);

    /** 创建命令保留 owner Thread revision。 */
    record Create(String ownerThreadId, boolean independentTask, String objective,
                  List<AcceptanceCriterion> acceptanceCriteria, long expectedThreadRevision,
                  String idempotencyKey, Instant at) {
        /** criteria 作为 initial definition 冻结，空集合仍需 evaluator 总 verdict。 */
        public Create { acceptanceCriteria = List.copyOf(acceptanceCriteria); }
    }
    /** Plan create 只冻结 owner 与 objective，编辑内容进入独立 draft。 */
    record CreatePlan(String ownerThreadId, String objective, long expectedThreadRevision,
                      String idempotencyKey, Instant at) { }
    /** 草稿命令携带 Plan 与 draft 两级 revision CAS。 */
    record SaveDraft(String planId, long expectedPlanRevision, long expectedDraftRevision,
                     PlanDefinition definition, String basedOnPlanRevisionId,
                     String idempotencyKey, Instant at) { }
    /** 丢弃草稿仍携带 Plan CAS，防止旧页面删除新编辑。 */
    record DiscardDraft(String planId, long expectedPlanRevision,
                        String idempotencyKey, Instant at) { }
    /** 提案命令选择 agent 或 user_ui 来源。 */
    record Propose(String planId, long expectedPlanRevision, PlanDefinition definition,
                   boolean userAuthored, String idempotencyKey, Instant at) { }
    /** 持久草稿提案只指定作者来源，定义由服务端权威读取。 */
    record ProposeDraft(String planId, long expectedPlanRevision,
                        boolean userAuthored, String idempotencyKey, Instant at) { }
    /** 批准命令必须来自显式 UI action。 */
    record Approve(String planId, long expectedPlanRevision, String planRevisionId,
                   String planHash, String idempotencyKey, Instant at) { }
    /** standalone 执行再次绑定批准 identity，避免批准后换版竞态。 */
    record ExecutePlan(String planId, long expectedPlanRevision, String planRevisionId,
                       String planHash, long processGeneration, String idempotencyKey, Instant at) { }
    /** 拒绝原因进入事件摘要边界，revision/hash 由服务端从当前快照锁定。 */
    record Reject(String planId, long expectedPlanRevision, String reason,
                       String idempotencyKey, Instant at) { }
    /** Plan 控制使用 active run 作为第二道 fencing，防止旧窗口误操作新执行。 */
    record PlanControl(String planId, long expectedPlanRevision, String runId,
                       String idempotencyKey, Instant at) { }
    /** attach 不跟随最新版本，精确批准 identity 是 Goal 执行资格的一部分。 */
    record AttachPlan(String goalId, long expectedGoalRevision, String planId,
                      String planRevisionId, String planHash,
                      String idempotencyKey, Instant at) { }
    /** Goal revision 已覆盖并发重挂载，linkRevision 仅作为投影和内部审计事实。 */
    record DetachPlan(String goalId, long expectedGoalRevision, String idempotencyKey, Instant at) { }
    /** 控制动作闭集避免 handler 传任意目标状态。 */
    record Control(String goalId, long expectedGoalRevision, Action action,
                   String idempotencyKey, Instant at) { }
    /** step 更新保持 expected status CAS。 */
    record StepUpdate(String goalId, long expectedGoalRevision, String runId, String stepId,
                      StepStatus expectedStatus, StepStatus status, String failureSignature,
                      java.util.List<ToolEvidenceClaim> evidenceClaims,
                      String idempotencyKey, Instant at) {
        /** evidence claims 冻结后才能跨 Extension 边界进入同一事务。 */
        public StepUpdate { evidenceClaims = java.util.List.copyOf(evidenceClaims); }
    }
    /** 独立 Plan step 更新使用 Plan CAS，证据 claim 仍只引用真实已结算 Tool call。 */
    record PlanStepUpdate(String planId, long expectedPlanRevision, String runId, String stepId,
                          StepStatus expectedStatus, StepStatus status, String failureSignature,
                          java.util.List<ToolEvidenceClaim> evidenceClaims,
                          String idempotencyKey, Instant at) {
        /** 跨 Extension 边界前冻结 claim，避免执行期间被调用方改写。 */
        public PlanStepUpdate { evidenceClaims = java.util.List.copyOf(evidenceClaims); }
    }
    /** Agent 只能引用当前步骤已成功 Tool call，并显式说明它证明的 criterion。 */
    record ToolEvidenceClaim(String criterionId, String callId, String summary) { }
    /** evaluator 请求冻结当前 thread 模型。 */
    record EvaluationRequest(String goalId, long expectedGoalRevision, String runId,
                             String planRevisionId, List<ToolEvidenceClaim> evidenceClaims,
                             String idempotencyKey, Instant at) {
        /** evaluator intent 与证据 claim 共用同一 mutation，避免请求提交后再补证据的竞态。 */
        public EvaluationRequest { evidenceClaims = List.copyOf(evidenceClaims); }
    }
    /** Goal continuation 只暴露 Tool 所需 identity，不泄漏 objective、证据或数据库行。 */
    record GoalTurnContext(String goalId, long goalRevision, String runId,
                           io.github.kongweiguang.ja.goal.domain.GoalModels.GoalStatus status,
                           io.github.kongweiguang.ja.goal.domain.GoalModels.GoalPhase phase,
                           String planId, String planRevisionId) { }
    /** Plan mode 只暴露稳定 CAS/run identity，动态 prompt 不携带计划正文。 */
    record PlanTurnContext(String planId, long planRevision, PlanStatus status,
                           String planRevisionId, String runId) { }
    /** 用户可见 Goal 控制动作。 */
    enum Action {
        /** 暂停当前 Goal。 */ PAUSE,
        /** 从普通暂停恢复。 */ RESUME,
        /** 永久停止。 */ STOP
    }
}
