// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.gateway;

import io.github.kongweiguang.ja.plan.domain.PlanModels.Evidence;
import io.github.kongweiguang.ja.plan.domain.PlanModels.Plan;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanDraft;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanRevision;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanSnapshot;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.domain.PlanModels.StepStatus;
import io.github.kongweiguang.ja.plan.domain.PlanModels.ToolAttempt;
import io.github.kongweiguang.ja.plan.domain.PlanModels.ToolAttemptState;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 独立 Plan 聚合的事务端口；每个 mutation 都携带所属 aggregate CAS 与幂等 identity。
 */
public interface PlanRepository {
    /**
     * 创建 thread-owned Plan，并写入独立 Plan event 命名空间。
     */
    Plan createPlan(CreatePlan command);




    /**
     * 在单个读事务中返回 Plan 自己的 draft/revision/approval/run 投影。
     */
    PlanSnapshot readPlanSnapshot(String planId);

    /**
     * 观察只读取 Plan 行、最新事件和步骤摘要，不物化 canonical JSON/draft/evidence。
     */
    default PlanObservation readPlanObservation(String planId) {
        throw new UnsupportedOperationException("Plan observation is unavailable");
    }

    /**
     * 读取持久草稿供应用层冻结；缺失草稿不回退到最新 revision。
     */
    Optional<PlanDraft> findDraft(String planId);

    /**
     * 保存结构化 draft，draftRevision 与 Plan revision 双 CAS。
     */
    Plan saveDraft(SaveDraft command);

    /**
     * 显式丢弃未冻结草稿，并与 Plan revision、事件追加原子提交。
     */
    Plan discardDraft(DiscardDraft command);

    /**
     * 冻结不可变 revision；同一幂等键重放必须返回原事实。
     */
    PlanRevision propose(ProposePlan command);

    /**
     * 仅 USER_UI 可批准精确 revision/hash；批准不产生执行副作用。
     */
    Plan approve(ApprovePlan command);

    /**
     * 仅 APPROVED Plan 可显式创建自己的 run/steps。
     */
    Plan executePlan(ExecutePlan command);

    /**
     * 单次 hidden Turn 未完成计划时关闭旧 run，并恢复为可显式再次执行的 APPROVED。
     */
    Plan settlePlanExecution(SettlePlanExecution command);

    /**
     * 按 Run/Turn 身份原子领取或复用 claim；实际 Turn 启动仍须通过准入 fence，重放不会重复占用序号。
     */
    Optional<PlanTurnClaim> claimPlanTurn(ClaimPlanTurn command);

    /**
     * Turn 终态以持久模型、工具 usage 与 claim 结算为准，并在同一事务内幂等记账。
     * activeMillis 仅为当前进程观测的审计值；进程重启后可能低估，不参与执行控制。
     */
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

    /**
     * 先设置 run pause fence，再等待真实 Turn 结算，Plan 状态随后由 pausePlan CAS 提交。
     */
    default Plan requestPlanPause(RequestPlanPause command) {
        throw new UnsupportedOperationException("Plan pause request is unavailable");
    }

    /**
     * 为用户 pause/stop 在同一事务中识别同键重放，再建立控制 fence；owner 关闭沿用独立的 pause 请求。
     */
    PlanControlFenceResult requestPlanControlFence(RequestPlanPause command, PlanControlAction action);

    /**
     * 旧批准或显式拒绝都不能启动 run。
     */
    Plan reject(RejectPlan command);





    /**
     * 独立 Plan step 更新只推进 Plan aggregate，并在完成门满足后进入 COMPLETED。
     */
    Plan updatePlanStep(UpdatePlanStep command);

    /**
     * 最后一项 Tool 结算后重新检查步骤、证据和 pending 资源，再冻结 VERIFYING。
     */
    Plan beginPlanVerification(BeginPlanVerification command);

    /**
     * 无 Tool evaluator 的结论与 Run/Plan 状态必须在同一事务中结算。
     */
    default Plan completePlanVerification(CompletePlanVerification command) {
        throw new UnsupportedOperationException("Plan verification is unavailable");
    }

    /**
     * 用户暂停保留 run/revision identity，恢复时仍需精确命中同一 run。
     */
    default Plan pausePlan(PausePlan command) {
        throw new UnsupportedOperationException("Plan pause is unavailable");
    }

    /**
     * 只有 PAUSED Plan 可以恢复，避免把 APPROVED 误当作可继续的旧 run。
     */
    default ResumeResult resumePlan(ResumePlan command) {
        throw new UnsupportedOperationException("Plan resume is unavailable");
    }

    /**
     * 停止是不可逆的 run 结算，但保留冻结 revision 与执行审计。
     */
    default Plan stopPlan(StopPlan command) {
        throw new UnsupportedOperationException("Plan stop is unavailable");
    }





    /**
     * 外部执行前绑定既有 Tool call、run、step 与请求 digest。
     */
    ToolAttempt prepareToolAttempt(PrepareToolAttempt command);

    /**
     * 仅 PREPARED attempt 可标记 STARTED，提交后才允许真正执行 Tool。
     */
    ToolAttempt startToolAttempt(String toolAttemptId, Instant at);

    /**
     * Tool 终态与可选验收证据在同一事务挂接。
     */
    ToolAttempt settleToolAttempt(SettleToolAttempt command);

    /**
     * Core 在同一提交证明 ToolCompleted 与失败/取消结果且没有 ToolStarted 时，才允许 PREPARED 直接结算；
     * 实现必须保持 startedAt=null、禁止 evidence，并以 PREPARED CAS 防止重复越过执行边界。
     */
    ToolAttempt settleUnstartedToolAttempt(SettleToolAttempt command);

    /**
     * 按不可变 Turn/call identity 查找全部 attempt，调用方必须显式处理同一调用的重试序号。
     */
    List<ToolAttempt> findToolAttemptsByTurnIdCallId(String turnId, String callId);

    /**
     * 启动恢复只读取旧 generation 未结算 attempts。
     */
    List<ToolAttempt> listUnsettledToolAttempts(long currentProcessGeneration, int limit);



    /**
     * Plan-only 旧 run 安全失败回到 APPROVED，未知副作用则永久 STOPPED 等待人工核对。
     */
    default Plan recoverPlanExecution(RecoverPlanExecution command) {
        throw new UnsupportedOperationException("Plan execution recovery is unavailable");
    }

    /**
     * 启动恢复有界读取旧 generation 的 EXECUTING/VERIFYING Plan run，不读取正文或 evidence。
     */
    default List<PlanExecutionRecovery> listUnsettledPlanExecutions(long currentProcessGeneration, int limit) {
        return List.of();
    }

    /**
     * 仅放弃尚未创建持久 Turn 的 admission intent；有 Turn 的 claim 必须经 usage 结算。
     */
    void abandonUnadmittedPlanTurnClaims(String runId);



    /**
     * Tool ledger 只读取 owner 当前唯一 EXECUTING standalone Plan。
     */
    Optional<Plan> findExecutingPlanByOwner(String ownerThreadId);

    /**
     * 内部 Turn 的不可变上下文必须精确绑定 run/revision；Plan continuation 还需持有原 fencing lease。
     */
    Optional<InternalTurnBinding> findInternalTurnBinding(String turnId);


    /**
     * 通过 Plan run identity 找到当前可控的内部 Turn，控制动作不能从 owner 最新状态猜测 Turn。
     */
    Optional<InternalTurnBinding> findPlanTurnBinding(String planId, String runId);

    /**
     * Extension prompt 只读取 owner 唯一非终态 Plan，不跟随终态历史。
     */
    Optional<Plan> findActivePlanByOwner(String ownerThreadId);

    /**
     * 当前工作面恢复只取该 Thread 最近计划，包含可审阅的终态。
     */
    Optional<PlanSnapshot> findCurrentPlan(String ownerThreadId);



    /**
     * 历史事件按持久化 Plan sequence 分页；进程内通知水位不能替代该游标。
     */
    ReadPage<PlanEvent> listPlanEvents(String planId, long afterSequence, int limit);

    /**
     * 按不可变 revision number 分页，避免 offset 在追加版本时漂移。
     */
    ReadPage<PlanRevision> listPlanRevisions(String planId, long afterRevisionNumber, int limit);


    /**
     * 验收证据按 run 与 revision 过滤；Plan 定义快照由 evaluator 调用方单独提供。
     */
    List<Evidence> listPlanEvidence(String planId, String runId, String planRevisionId, int limit);

    /** Plan evidence 使用 createdAt/evidenceId keyset，迟到证据不会重排上一页。 */
    /**
     * 默认分页只适用于不需要 cursor 的内部读取；UI 分页由仓储实现真实 keyset。
     */
    default ReadPage<Evidence> listPlanEvidencePage(String planId, String runId, String planRevisionId,
                                                    String afterCreatedAt, String afterEvidenceId, int limit) {
        return new ReadPage<>(0, 0, listPlanEvidence(planId, runId, planRevisionId, limit));
    }








    /**
     * Plan 创建命令拥有自己的 identity 与 owner CAS。
     *
     * @param planId Plan 标识
     * @param ownerThreadId 所属对话标识
     * @param objective 目标描述
     * @param expectedThreadRevision 预期对话修订版本
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record CreatePlan(String planId,
                      String ownerThreadId,
                      String objective,
                      long expectedThreadRevision,
                      String operationKey,
                      Instant at) {
    }

    /**
     * 草稿保存同时检查 Plan 与 draft 两级 revision。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param expectedDraftRevision 预期草稿修订版本
     * @param definitionJson 定义 JSON 表示
     * @param basedOnPlanRevisionId 当前内容所依据的 Plan 修订版本标识
     * @param eventId 事件标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record SaveDraft(String planId,
                     long expectedPlanRevision,
                     long expectedDraftRevision,
                     String definitionJson,
                     String basedOnPlanRevisionId,
                     String eventId,
                     String operationKey,
                     Instant at) {
    }

    /**
     * 草稿丢弃仍是 Plan mutation，避免 reload 后旧草稿复现。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param eventId 事件标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record DiscardDraft(String planId,
                        long expectedPlanRevision,
                        String eventId,
                        String operationKey,
                        Instant at) {
    }

    /**
     * 提案携带已经 canonical 化的完整不可变版本。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param revision 当前修订版本
     * @param eventId 事件标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record ProposePlan(String planId,
                       long expectedPlanRevision,
                       PlanRevision revision,
                       String eventId,
                       String operationKey,
                       Instant at) {
    }

    /**
     * 批准命令绑定精确 revision/hash 并分配 run identity。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param planRevisionId Plan 修订版本标识
     * @param planHash Plan 内容摘要
     * @param approvalId 审批标识
     * @param eventId 事件标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record ApprovePlan(String planId,
                       long expectedPlanRevision,
                       String planRevisionId,
                       String planHash,
                       String approvalId,
                       String eventId,
                       String operationKey,
                       Instant at) {
    }

    /**
     * 执行命令在同一事务中记录用户授权、复核 revision/hash，并分配 standalone run。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param planRevisionId Plan 修订版本标识
     * @param planHash Plan 内容摘要
     * @param approvalId 审批标识
     * @param runId 运行标识
     * @param processGeneration App Server 进程代际标识
     * @param eventId 事件标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record ExecutePlan(String planId,
                       long expectedPlanRevision,
                       String planRevisionId,
                       String planHash,
                       String approvalId,
                       String runId,
                       long processGeneration,
                       String eventId,
                       String operationKey,
                       Instant at) {
        /**
         * 执行只冻结批准身份与 Run；模型/工具次数不参与准入。
         */
        public ExecutePlan {
            Objects.requireNonNull(approvalId, "approvalId");
        }
    }

    /**
     * completion callback 精确绑定本次 run，迟到 callback 不能停止后续执行。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param runId 运行标识
     * @param eventId 事件标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record SettlePlanExecution(String planId,
                               long expectedPlanRevision,
                               String runId,
                               String eventId,
                               String operationKey,
                               Instant at) {
    }

    /**
     * Turn 领取 identity；同一 run/turn 重试返回原 ordinal，不重复消耗预算。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param runId 运行标识
     * @param planRevisionId Plan 修订版本标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param eventId 事件标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record ClaimPlanTurn(String planId,
                         long expectedPlanRevision,
                         String runId,
                         String planRevisionId,
                         String turnId,
                         String eventId,
                         String operationKey,
                         Instant at) {
    }
    /** effective runtime limits 只允许初始化一次，防止跨 Turn 读取漂移配置。 */
    /**
     * Turn 完成后只携带不可变身份和活动墙钟增量，模型/Tool 用量由仓储从权威账本读取。
     *
     * @param planId Plan 标识
     * @param runId 运行标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param activeMillis 当前进程观测的活动时长审计值（毫秒）；重启后可能低估，不参与执行控制
     * @param eventId 事件标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record SettlePlanTurn(String planId,
                          String runId,
                          String turnId,
                          long activeMillis,
                          String eventId,
                          String operationKey,
                          Instant at) {
        /**
         * 活动时长是单调测量值，禁止负数进入 SQLite 累计器。
         */
        public SettlePlanTurn {
            if (activeMillis < 0) {
                throw new IllegalArgumentException("activeMillis must be non-negative");
            }
        }
    }

    /**
     * 单个可恢复执行片段的墙钟结算，不读取或累计模型/Tool usage。
     *
     * @param planId Plan 标识
     * @param runId 运行标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param activeMillis 当前进程观测的活动片段审计值（毫秒）；重启后可能低估，不参与执行控制
     * @param eventId 事件标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record SettlePlanTurnActivity(String planId,
                                  String runId,
                                  String turnId,
                                  long activeMillis,
                                  String eventId,
                                  String operationKey,
                                  Instant at) {
        /**
         * 活动时长必须为单调非负值，避免暂停重试污染 Run 账本。
         */
        public SettlePlanTurnActivity {
            if (activeMillis < 0) {
                throw new IllegalArgumentException("activeMillis must be non-negative");
            }
        }
    }

    /**
     * pause fence 不增加 Plan revision，允许随后使用同一 expectedPlanRevision 完成状态 CAS。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param runId 运行标识
     * @param eventId 事件标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record RequestPlanPause(String planId,
                            long expectedPlanRevision,
                            String runId,
                            String eventId,
                            String operationKey,
                            Instant at) {
    }

    /**
     * 已持久化的 Turn ordinal 与身份，供调度器精确结算和恢复。
     *
     * @param planId Plan 标识
     * @param runId 运行标识
     * @param planRevisionId Plan 修订版本标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param ordinal 当前项的顺序位置
     */
    record PlanTurnClaim(String planId,
                         String runId,
                         String planRevisionId,
                         String turnId,
                         int ordinal) {
        /**
         * 序号从一开始单调前进，不再夹带预算。
         */
        public PlanTurnClaim {
            if (ordinal < 1) {
                throw new IllegalArgumentException("invalid Plan Turn ordinal");
            }
        }
    }

    /**
     * 拒绝只写用户决定，不创建 run。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param planRevisionId Plan 修订版本标识
     * @param planHash Plan 内容摘要
     * @param approvalId 审批标识
     * @param reason 原因说明
     * @param eventId 事件标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record RejectPlan(String planId,
                      long expectedPlanRevision,
                      String planRevisionId,
                      String planHash,
                      String approvalId,
                      String reason,
                      String eventId,
                      String operationKey,
                      Instant at) {
    }





    /**
     * Plan step 与 evidence 在同一 writer transaction 结算。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param runId 运行标识
     * @param stepId 步骤标识
     * @param expectedStatus 预期状态
     * @param status 当前状态分类
     * @param failureSignature 失败签名
     * @param evidenceClaims 工具提供的证据声明
     * @param eventId 事件标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record UpdatePlanStep(String planId,
                          long expectedPlanRevision,
                          String runId,
                          String stepId,
                          StepStatus expectedStatus,
                          StepStatus status,
                          String failureSignature,
                          List<ToolEvidenceClaim> evidenceClaims,
                          String eventId,
                          String operationKey,
                          Instant at) {
        /**
         * evidence claim 在事务开始前冻结，避免 retry 看见不同集合。
         */
        public UpdatePlanStep {
            evidenceClaims = List.copyOf(evidenceClaims);
        }
    }

    /**
     * VERIFYING mutation 使用 Plan CAS 与 run identity 双重 fencing。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param runId 运行标识
     * @param eventId 事件标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record BeginPlanVerification(String planId,
                                 long expectedPlanRevision,
                                 String runId,
                                 String eventId,
                                 String operationKey,
                                 Instant at) {
    }

    /**
     * evaluator 只提交结构化 verdict；仓储重新检查步骤、证据和 run 状态。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param runId 运行标识
     * @param verdict 验收结论
     * @param summary 摘要
     * @param eventId 事件标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record CompletePlanVerification(String planId,
                                    long expectedPlanRevision,
                                    String runId,
                                    PlanModels.EvaluationVerdict verdict,
                                    String summary,
                                    String eventId,
                                    String operationKey,
                                    Instant at) {
    }

    /**
     * 用户 pause 不清除 active run，防止恢复时丢失 Tool ledger 所属关系。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param runId 运行标识
     * @param eventId 事件标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record PausePlan(String planId,
                     long expectedPlanRevision,
                     String runId,
                     String eventId,
                     String operationKey,
                     Instant at) {
    }

    /**
     * resume 只恢复同一个已暂停 run，不创建第二条执行事实。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param runId 运行标识
     * @param eventId 事件标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record ResumePlan(String planId,
                      long expectedPlanRevision,
                      String runId,
                      String eventId,
                      String operationKey,
                      Instant at) {
    }

    /**
     * stop 结束当前 run，保留 active identity 供审计和未知副作用核对。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param runId 运行标识
     * @param eventId 事件标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record StopPlan(String planId,
                    long expectedPlanRevision,
                    String runId,
                    String eventId,
                    String operationKey,
                    Instant at) {
    }

    /**
     * Resume 与控制 fence 的仓储结果显式区分首次准入和持久同键重放，Service 不从快照状态推测副作用。
     *
     * @param plan 当前权威 Plan
     * @param applied 本次事务是否首次提交对应的 Resume mutation
     */
    record ResumeResult(Plan plan,
                        boolean applied) {
        /** 防止回放契约泄漏为空快照，使 Service 的副作用判定始终对应持久 Plan。 */
        public ResumeResult {
            Objects.requireNonNull(plan, "plan");
        }
    }

    /**
     * pause 与 stop 复用同一个安全结算 fence，但幂等活动类型保持精确区分。
     */
    enum PlanControlAction {
        /** 提交 plan_paused 活动后结束当前安全控制片段。 */
        PAUSE("plan_paused"),
        /** 提交 plan_stopped 活动后不可逆结束当前 Run。 */
        STOP("plan_stopped");

        private final String activity;

        /** 枚举只绑定 V1 已持久化的活动名称，不引入额外控制状态或别名。 */
        PlanControlAction(String activity) {
            this.activity = activity;
        }

        /** 返回唯一持久活动类型，避免应用层自行拼写或接受别名。 */
        public String activity() {
            return activity;
        }
    }

    /**
     * 控制 fence 返回权威快照，并明确本次请求是否应驱动真实 Turn/evaluator 收口。
     *
     * @param plan 当前权威 Plan
     * @param shouldControl 首次请求需要执行真实控制；同类型已提交操作重放为 false
     */
    record PlanControlFenceResult(Plan plan,
                                  boolean shouldControl) {
        /** 控制分支必须携带当前权威快照，不能只返回影响调用方重读的布尔状态。 */
        public PlanControlFenceResult {
            Objects.requireNonNull(plan, "plan");
        }
    }

    /**
     * evidenceId 由应用层生成，其余字段必须由 repository 对真实 Tool attempt 复核。
     *
     * @param evidenceId 证据标识
     * @param criterionId 验收条件标识
     * @param callId 模型生成的工具调用标识
     * @param summary 摘要
     * @param observedAt 观察时间
     */
    record ToolEvidenceClaim(String evidenceId,
                             String criterionId,
                             String callId,
                             String summary,
                             Instant observedAt) {
    }




    /**
     * prepare 命令必须引用已持久化的 Turn Tool call。
     *
     * @param attempt 尝试次数
     */
    record PrepareToolAttempt(ToolAttempt attempt) {
    }

    /**
     * settlement 只允许真实 terminal digest，证据可为空。
     *
     * @param toolAttemptId 工具执行尝试标识
     * @param state 当前状态
     * @param resultDigest 结果内容摘要
     * @param evidence 证据
     * @param at 当前事实发生时间
     */
    record SettleToolAttempt(String toolAttemptId,
                             ToolAttemptState state,
                             String resultDigest,
                             Evidence evidence,
                             Instant at) {
    }



    /**
     * unsafe 表示副作用 Tool 已 STARTED 且结果未知，禁止再次执行同一 Plan。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param runId 运行标识
     * @param unsafe 是否存在不安全状态
     * @param eventId 事件标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record RecoverPlanExecution(String planId,
                                long expectedPlanRevision,
                                String runId,
                                boolean unsafe,
                                String eventId,
                                String operationKey,
                                Instant at) {
    }

    /**
     * 旧 Plan run 的最小恢复事实；unsafe 只表示持久 ledger 无法证明副作用已结算。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param runId 运行标识
     * @param planRevisionId Plan 修订版本标识
     * @param unsafe 是否存在不安全状态
     */
    record PlanExecutionRecovery(String planId,
                                 long expectedPlanRevision,
                                 String runId,
                                 String planRevisionId,
                                 boolean unsafe) {
        /**
         * 恢复 identity 必须完整，避免扫描结果被错误绑定到后来追加的 revision。
         */
        public PlanExecutionRecovery {
            PlanModels.requireId(planId, "planId");
            PlanModels.requireId(runId, "runId");
            PlanModels.requireId(planRevisionId, "planRevisionId");
            if (expectedPlanRevision < 0) {
                throw new IllegalArgumentException("invalid Plan revision");
            }
        }
    }


    /**
     * Plan event 只公开稳定 activity 和 revision 水位，payload 仍由 service 严格映射。
     *
     * @param sequence 当前记录的顺序号
     * @param eventId 事件标识
     * @param planId Plan 标识
     * @param planRevision Plan 修订版本
     * @param activity 活动
     * @param createdAt 创建时间
     */
    record PlanEvent(long sequence,
                     String eventId,
                     String planId,
                     long planRevision,
                     String activity,
                     Instant createdAt) {
    }

    /**
     * Plan 事件与同一读事务的轻量步骤进度投影，事件水位不可跨事务拼接。
     *
     * @param plan 执行计划
     * @param latestEvent 最近事件
     * @param progress 进度
     */
    record PlanObservation(Plan plan,
                           PlanEvent latestEvent,
                           PlanModels.PlanProgress progress) {
    }

    /**
     * 读页事实与快照边界在同一事务获取，opaque cursor 由 Plan 服务生成。
     *
     * @param planRevision Plan 的修订版本
     * @param eventSequence 事件序号
     * @param items 条目列表
     */
    record ReadPage<T>(long planRevision,
                       long eventSequence,
                       List<T> items) {
        /**
         * 防御性复制避免事务外调用方修改同一快照页。
         */
        public ReadPage {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }



    /**
     * 内部 Turn binding 来自不可变 SQLite context，不允许 ledger 从 owner 当前状态反推旧 Turn 身份。
     *
     * @param turnId 本次可恢复执行的稳定标识
     * @param origin 来源分类
     * @param planId Plan 标识
     * @param runId 运行标识
     * @param planRevisionId Plan 修订版本标识
     * @param planHash Plan 内容摘要
     */
    record InternalTurnBinding(String turnId,
                               String origin,
                               String planId,
                               String runId,
                               String planRevisionId,
                               String planHash) {
        /**
         * 内部执行只接受完整的冻结 Plan 身份，拒绝空字段和其它来源。
         */
        public InternalTurnBinding {
            PlanModels.requireId(turnId, "turnId");
            PlanModels.requireId(runId, "runId");
            if (planId == null || planRevisionId == null || planHash == null
                || !planHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid internal Plan binding");
            }
            if (!"PLAN_EXECUTION".equals(origin)) {
                throw new IllegalArgumentException("invalid internal Turn origin");
            }
        }
    }

}

