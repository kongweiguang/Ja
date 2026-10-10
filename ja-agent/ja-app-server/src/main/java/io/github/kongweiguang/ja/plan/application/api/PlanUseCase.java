// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.api;

import io.github.kongweiguang.ja.plan.domain.PlanModels.Evidence;
import io.github.kongweiguang.ja.plan.domain.PlanModels.Page;
import io.github.kongweiguang.ja.plan.domain.PlanModels.Plan;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanDefinition;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanRevision;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanSnapshot;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanStatus;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PublicEvent;
import io.github.kongweiguang.ja.plan.domain.PlanModels.StepStatus;

import java.time.Instant;
import java.util.Optional;

/**
 * UI 与内建 Extension 共享的受限 Plan 用例端口；不暴露 Mapper 或 SqlSession。
 */
public interface PlanUseCase {
    /**
     * 订阅已提交的 Plan 状态事件；关闭句柄只撤销当前连接的进程内订阅。
     */
    AutoCloseable subscribePlan(PlanEventSink sink);


    /**
     * 返回独立 Plan aggregate 的草稿、冻结版本、批准与 run 投影。
     */
    PlanSnapshot readPlan(String planId);

    /**
     * 重启后按 owner 查询 Thread 当前 Plan；调用方无需持有 Plan identity。
     */
    Optional<PlanSnapshot> findCurrentPlan(String ownerThreadId);



    /**
     * 为 Plan mode/内部执行冻结 owner 当前非终态 Plan identity。
     */
    default Optional<PlanTurnContext> currentPlanContext(String ownerThreadId) {
        return Optional.empty();
    }

    /**
     * 内部执行在 admission 前读取 owner 唯一 EXECUTING Plan；admission 后由持久 Turn identity 复核。
     */
    default Optional<PlanTurnContext> planExecutionContext(String ownerThreadId, String turnId) {
        return Optional.empty();
    }

    /**
     * RPC Turn 控制仅在存在不可变 Plan binding 时返回对应 run；绑定存在但身份不匹配必须失败关闭。
     */
    default Optional<PlanTurnContext> planTurnControlContext(String ownerThreadId, String turnId) {
        return Optional.empty();
    }


    /**
     * 独立 Plan 事件查询使用自己的水位，供 Workbench 增量对账。
     */
    Page<PublicEvent> readPlanEvents(String planId, String cursor, int limit);


    /**
     * 按 revision number 分页读取独立 Plan 的不可变版本。
     */
    Page<PlanRevision> listRevisions(String planId, String cursor, int limit);


    /**
     * Plan evidence 必须精确绑定当前冻结 revision 与 run。
     */
    Page<Evidence> listPlanEvidence(String planId, String planRevisionId, String runId,
                                    String cursor, int limit);


    /**
     * 在 owner Thread 下创建 DRAFT Plan，编辑内容随后写入可变草稿。
     */
    Plan createPlan(CreatePlan command);

    /**
     * UI 保存结构化草稿，不产生批准。
     */
    Plan saveDraft(SaveDraft command);

    /**
     * UI 显式丢弃当前可变草稿。
     */
    Plan discardDraft(DiscardDraft command);

    /**
     * UI 从已保存草稿冻结当前 Plan revision。
     */
    PlanRevision proposeDraft(ProposeDraft command);

    /**
     * Agent 或 UI 将草稿冻结为可批准 revision。
     */
    PlanRevision propose(Propose command);

    /**
     * USER_UI 对精确 revision/hash 批准；批准本身不创建执行 run。
     */
    Plan approve(Approve command);

    /**
     * 对已批准版本显式创建 standalone run；内部 Turn 事件只发给发起连接，不向其他窗口广播。
     */
    Plan executePlan(ExecutePlan command, PlanExecutionEventSink events);

    /**
     * 暂停、恢复和停止均绑定精确 run identity，避免控制请求作用于同一 Plan 的其他执行。
     */
    Plan pausePlan(PlanControl command);

    /**
     * 从 PAUSED 恢复原 run，不重新批准或创建第二个 run。
     */
    Plan resumePlan(PlanControl command);

    /**
     * 将发起恢复动作的连接事件出口传给原 run；实现必须传播 sink，避免 UI 丢失恢复后的实时投影。
     */
    Plan resumePlan(PlanControl command, PlanExecutionEventSink events);

    /**
     * 停止 Plan run 并保留审计事实，不自动回滚工作区。
     */
    Plan stopPlan(PlanControl command);

    /**
     * UI 拒绝当前待批准 revision 并回到 planning。
     */
    Plan reject(Reject command);





    /**
     * 步骤推进同时校验 Plan revision 与 run identity，防止旧 Turn 的结果写入后续执行。
     */
    Plan updatePlanStep(PlanStepUpdate command);




    /**
     * Plan create 只冻结 owner 与 objective，编辑内容进入独立 draft。
     *
     * @param ownerThreadId 所属对话标识
     * @param objective 目标描述
     * @param expectedThreadRevision 预期对话修订版本
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record CreatePlan(String ownerThreadId,
                      String objective,
                      long expectedThreadRevision,
                      String operationKey,
                      Instant at) {
    }

    /**
     * 草稿命令携带 Plan 与 draft 两级 revision CAS。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param expectedDraftRevision 预期草稿修订版本
     * @param definition 定义
     * @param basedOnPlanRevisionId 当前内容所依据的 Plan 修订版本标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record SaveDraft(String planId,
                     long expectedPlanRevision,
                     long expectedDraftRevision,
                     PlanDefinition definition,
                     String basedOnPlanRevisionId,
                     String operationKey,
                     Instant at) {
    }

    /**
     * 丢弃草稿仍携带 Plan CAS，防止旧页面删除新编辑。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record DiscardDraft(String planId,
                        long expectedPlanRevision,
                        String operationKey,
                        Instant at) {
    }

    /**
     * 提案命令选择 agent 或 user_ui 来源。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param definition 定义
     * @param userAuthored 是否由用户编写
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record Propose(String planId,
                   long expectedPlanRevision,
                   PlanDefinition definition,
                   boolean userAuthored,
                   String operationKey,
                   Instant at) {
    }

    /**
     * 持久草稿提案只指定作者来源，定义由服务端权威读取。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param userAuthored 是否由用户编写
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record ProposeDraft(String planId,
                        long expectedPlanRevision,
                        boolean userAuthored,
                        String operationKey,
                        Instant at) {
    }

    /**
     * 批准命令必须来自显式 UI action。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param planRevisionId Plan 修订版本标识
     * @param planHash Plan 内容摘要
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record Approve(String planId,
                   long expectedPlanRevision,
                   String planRevisionId,
                   String planHash,
                   String operationKey,
                   Instant at) {
    }

    /**
     * standalone 执行再次绑定批准 identity，避免批准后换版竞态。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param planRevisionId Plan 修订版本标识
     * @param planHash Plan 内容摘要
     * @param processGeneration App Server 进程代际标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record ExecutePlan(String planId,
                       long expectedPlanRevision,
                       String planRevisionId,
                       String planHash,
                       long processGeneration,
                       String operationKey,
                       Instant at) {
    }

    /**
     * 拒绝原因进入事件摘要边界，revision/hash 由服务端从当前快照锁定。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param reason 原因说明
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record Reject(String planId,
                  long expectedPlanRevision,
                  String reason,
                  String operationKey,
                  Instant at) {
    }

    /**
     * Plan 控制使用 active run 作为第二道 fencing，防止旧窗口误操作新执行。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param runId 运行标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record PlanControl(String planId,
                       long expectedPlanRevision,
                       String runId,
                       String operationKey,
                       Instant at) {
    }





    /**
     * 独立 Plan step 更新使用 Plan CAS，证据 claim 仍只引用真实已结算 Tool call。
     *
     * @param planId Plan 标识
     * @param expectedPlanRevision 提交时预期的 Plan 修订版本
     * @param runId 运行标识
     * @param stepId 步骤标识
     * @param expectedStatus 预期状态
     * @param status 当前状态分类
     * @param failureSignature 失败签名
     * @param evidenceClaims 工具提供的证据声明
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param at 当前事实发生时间
     */
    record PlanStepUpdate(String planId,
                          long expectedPlanRevision,
                          String runId,
                          String stepId,
                          StepStatus expectedStatus,
                          StepStatus status,
                          String failureSignature,
                          java.util.List<ToolEvidenceClaim> evidenceClaims,
                          String operationKey,
                          Instant at) {
        /**
         * 跨 Extension 边界前冻结 claim，避免执行期间被调用方改写。
         */
        public PlanStepUpdate {
            evidenceClaims = java.util.List.copyOf(evidenceClaims);
        }
    }

    /**
     * Agent 只能引用当前步骤已成功 Tool call，并显式说明它证明的 criterion。
     *
     * @param criterionId 验收条件标识
     * @param callId 模型生成的工具调用标识
     * @param summary 摘要
     */
    record ToolEvidenceClaim(String criterionId,
                             String callId,
                             String summary) {
    }



    /**
     * Plan mode 只暴露稳定 CAS/run identity，动态 prompt 不携带计划正文。
     *
     * @param planId Plan 标识
     * @param planRevision Plan 修订版本
     * @param status 当前状态分类
     * @param planRevisionId Plan 修订版本标识
     * @param runId 运行标识
     */
    record PlanTurnContext(String planId,
                           long planRevision,
                           PlanStatus status,
                           String planRevisionId,
                           String runId) {
    }
}

