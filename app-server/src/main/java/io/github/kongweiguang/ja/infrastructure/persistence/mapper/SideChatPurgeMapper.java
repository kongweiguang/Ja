// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 临时侧聊的物理清理 SQL 边界。
 *
 * <p>清理必须和关闭闸门共享同一个 SqlSession。先用根 marker 固定完整身份列表，后续
 * DELETE 只接收这些不可变列表，避免 lineage 删除或并发写入改变清理目标。</p>
 */
@Mapper
public interface SideChatPurgeMapper {
    /** CLOSING marker 必须仍指向独立 SIDE_TASK 根，防止伪造 rootThreadId 越权清理。 */
    int countClosingRoot(@Param("rootThreadId") String rootThreadId);

    /** 读取 marker 和 lineage 的固定 Thread 集合；调用方不得在删除阶段重新递归 lineage。 */
    List<String> selectPurgeThreadIds(@Param("rootThreadId") String rootThreadId);

    /** 为固定子树补齐 CLOSING marker，使四个 immutable-delete trigger 只放行本次冻结图。 */
    int markPurgeThreadsClosing(@Param("threadIds") List<String> threadIds);

    /** 正常关闭必须先证明固定 Thread 集合没有任何非终态 Turn；启动恢复可跳过此门。 */
    int countNonTerminalTurns(@Param("threadIds") List<String> threadIds);

    /** 固定会话身份对应的所有 Turn，包含根 Turn 与 SUBAGENT 后代 Turn。 */
    List<String> selectPurgeTurnIds(@Param("threadIds") List<String> threadIds);

    /** 固定会话 owner 对应的 Goal 集合，后续 Goal SQL 只使用此列表。 */
    List<String> selectPurgeGoalIds(@Param("threadIds") List<String> threadIds);

    /** 固定会话 owner 对应的 Plan 集合，后续 Plan SQL 只使用此列表。 */
    List<String> selectPurgePlanIds(@Param("threadIds") List<String> threadIds);

    /** 固定 Goal/Plan owner 对应的 Run 集合，覆盖 Goal 与独立 Plan 两种运行图。 */
    List<String> selectPurgeRunIds(@Param("goalIds") List<String> goalIds,
                                   @Param("planIds") List<String> planIds);

    /** 固定 lineage 对应的 context seed 集合，必须在线删除 lineage 后再删除 seed。 */
    List<String> selectPurgeSeedIds(@Param("threadIds") List<String> threadIds);

    /** 固定 Plan owner 对应的全部 revision 集合，覆盖历史 revision 与当前 revision。 */
    List<String> selectPurgePlanRevisionIds(@Param("planIds") List<String> planIds);

    /** 删除 Message 附件关系，确保 immutable Message 删除不再受关系表阻挡。 */
    int deleteMessageAttachments(@Param("threadIds") List<String> threadIds);

    /** 删除 pending input 附件关系，确保 input 删除不再受关系表阻挡。 */
    int deletePendingInputAttachments(@Param("threadIds") List<String> threadIds,
                                      @Param("turnIds") List<String> turnIds);

    /** 冻结本次子树实际关联的附件 identity；删除关系前必须复制，避免关系删除后目标集合漂移。 */
    List<String> selectPurgeAttachmentIds(@Param("threadIds") List<String> threadIds,
                                          @Param("turnIds") List<String> turnIds);

    /** 仅释放已无全局关系且未被其它 context seed 继承的冻结附件，并沿用应用的 ISO 审计时间。 */
    int discardPurgeAttachments(@Param("attachmentIds") List<String> attachmentIds,
                                @Param("discardedAt") String discardedAt);

    /** 删除交互草稿，request 主行稍后按固定 Thread/Turn 依赖清理。 */
    int deleteInteractionDrafts(@Param("threadIds") List<String> threadIds,
                                @Param("turnIds") List<String> turnIds,
                                @Param("planRevisionIds") List<String> planRevisionIds,
                                @Param("runIds") List<String> runIds,
                                @Param("goalIds") List<String> goalIds);

    /** 删除交互事件，保留临时子树之外的其它 Thread 事件。 */
    int deleteInteractionEvents(@Param("threadIds") List<String> threadIds,
                                @Param("turnIds") List<String> turnIds,
                                @Param("planRevisionIds") List<String> planRevisionIds,
                                @Param("runIds") List<String> runIds,
                                @Param("goalIds") List<String> goalIds);

    /** 删除交互请求及其固定 Plan/Goal/Run 引用。 */
    int deleteInteractionRequests(@Param("threadIds") List<String> threadIds,
                                  @Param("turnIds") List<String> turnIds,
                                  @Param("planRevisionIds") List<String> planRevisionIds,
                                  @Param("runIds") List<String> runIds,
                                  @Param("goalIds") List<String> goalIds);

    /** 删除 Plan evaluator 请求，避免其 Run/Revision 外键阻止 owner 图收敛。 */
    int deletePlanEvaluationRequests(@Param("threadIds") List<String> threadIds,
                                     @Param("planIds") List<String> planIds,
                                     @Param("planRevisionIds") List<String> planRevisionIds,
                                     @Param("runIds") List<String> runIds);

    /** 删除 Goal 工具尝试，必须早于 Tools 与 Execution Runs。 */
    int deleteGoalToolAttempts(@Param("goalIds") List<String> goalIds,
                               @Param("runIds") List<String> runIds,
                               @Param("turnIds") List<String> turnIds);

    /** 删除 Goal evaluation 运行叶子，必须早于 Goal definition 与 Execution Runs。 */
    int deleteGoalEvaluations(@Param("goalIds") List<String> goalIds,
                              @Param("runIds") List<String> runIds);

    /** 删除 acceptance evidence，覆盖 Goal/Plan 两条运行图。 */
    int deleteAcceptanceEvidence(@Param("goalIds") List<String> goalIds,
                                 @Param("planIds") List<String> planIds,
                                 @Param("planRevisionIds") List<String> planRevisionIds,
                                 @Param("runIds") List<String> runIds);

    /** 删除 Plan step execution 运行叶子，必须早于 Plan Steps 与 Execution Runs。 */
    int deletePlanStepExecutions(@Param("runIds") List<String> runIds,
                                 @Param("planRevisionIds") List<String> planRevisionIds);

    /** 删除 Run 对 Turn 的 admission claims。 */
    int deletePlanTurnClaims(@Param("runIds") List<String> runIds);

    /** 删除 Goal continuation lease，必须早于 Goal owner。 */
    int deleteGoalContinuationLeases(@Param("goalIds") List<String> goalIds);

    /** 删除 Goal event append-only projection，物理清理仅限 CLOSING 临时子树。 */
    int deleteGoalEvents(@Param("goalIds") List<String> goalIds);

    /** 删除 Plan event append-only projection，物理清理仅限 CLOSING 临时子树。 */
    int deletePlanEvents(@Param("planIds") List<String> planIds);

    /** 删除 Goal/Plan link，必须早于两侧 owner 与 Plan revisions。 */
    int deleteGoalPlanLinks(@Param("goalIds") List<String> goalIds,
                            @Param("planIds") List<String> planIds,
                            @Param("planRevisionIds") List<String> planRevisionIds);

    /** 删除 Plan approval，必须早于 Plan revisions。 */
    int deletePlanApprovals(@Param("planIds") List<String> planIds,
                            @Param("planRevisionIds") List<String> planRevisionIds);

    /** 删除 Execution Runs；Goal/Plan 的反向 active_run FK 在同一事务内延迟校验。 */
    int deleteExecutionRuns(@Param("runIds") List<String> runIds);

    /** 删除 Goal acceptance criteria，必须早于 Goal definition revisions。 */
    int deleteGoalAcceptanceCriteria(@Param("goalIds") List<String> goalIds);

    /** 删除 Goal definition revisions，必须早于 Goal owner。 */
    int deleteGoalDefinitionRevisions(@Param("goalIds") List<String> goalIds);

    /** 删除 Goal owner。 */
    int deleteGoals(@Param("goalIds") List<String> goalIds);

    /** 删除 Plan steps 与 acceptance criteria，必须早于 Plan revisions。 */
    int deletePlanSteps(@Param("planRevisionIds") List<String> planRevisionIds);

    /** 删除 Plan acceptance criteria，必须早于 Plan revisions。 */
    int deleteAcceptanceCriteria(@Param("planRevisionIds") List<String> planRevisionIds);

    /** 删除 Plan drafts，必须早于 Plan owner。 */
    int deletePlanDrafts(@Param("planIds") List<String> planIds);

    /** 删除 Plan revisions，必须早于 Plan owner。 */
    int deletePlanRevisions(@Param("planRevisionIds") List<String> planRevisionIds);

    /** 删除 Plan owner。 */
    int deletePlans(@Param("planIds") List<String> planIds);

    /** 删除 Message 正文；V1 immutable trigger 仅对 CLOSING 临时子树开放窄例外。 */
    int deleteMessages(@Param("threadIds") List<String> threadIds);

    /** 删除 context checkpoint；V1 immutable trigger 仅对 CLOSING 临时子树开放窄例外。 */
    int deleteContextCheckpoints(@Param("threadIds") List<String> threadIds);

    /** 删除 usage ledger；V1 immutable trigger 仅对 CLOSING 临时子树开放窄例外。 */
    int deleteUsage(@Param("threadIds") List<String> threadIds,
                    @Param("turnIds") List<String> turnIds);

    /** 删除工具路由绑定；V1 immutable trigger 仅对 CLOSING 临时子树开放窄例外。 */
    int deleteToolBindings(@Param("turnIds") List<String> turnIds);

    /** 删除 Turn 外键叶子 approvals。 */
    int deleteApprovals(@Param("threadIds") List<String> threadIds,
                        @Param("turnIds") List<String> turnIds);

    /** 删除脱敏 Tool artifacts。 */
    int deleteToolArtifacts(@Param("threadIds") List<String> threadIds,
                            @Param("turnIds") List<String> turnIds);

    /** 删除 Turn change set 与 diff artifact。 */
    int deleteTurnChangeSets(@Param("threadIds") List<String> threadIds,
                             @Param("turnIds") List<String> turnIds);

    /** 删除独立 diff artifact。 */
    int deleteChangeSetArtifacts(@Param("threadIds") List<String> threadIds,
                                 @Param("turnIds") List<String> turnIds);

    /** 删除面向 UI 的 timeline projection。 */
    int deleteTimelineMessages(@Param("threadIds") List<String> threadIds,
                               @Param("turnIds") List<String> turnIds);

    /** 删除标题生成状态。 */
    int deleteThreadTitleGenerations(@Param("threadIds") List<String> threadIds,
                                     @Param("turnIds") List<String> turnIds);

    /** 删除 Turn runtime state。 */
    int deleteTurnExecution(@Param("turnIds") List<String> turnIds);

    /** 删除 Goal/Plan Turn internal context。 */
    int deleteTurnInternalContext(@Param("turnIds") List<String> turnIds);

    /** 删除 Tools，必须晚于 Tool bindings、approvals、artifacts 和 Goal tool attempts。 */
    int deleteTools(@Param("threadIds") List<String> threadIds,
                    @Param("turnIds") List<String> turnIds);

    /** 删除 pending input 主行。 */
    int deletePendingInputs(@Param("threadIds") List<String> threadIds,
                            @Param("turnIds") List<String> turnIds);

    /** 只删除没有子 Turn 引用的固定 Turn，调用方反复执行直到列表耗尽。 */
    int deleteLeafTurns(@Param("turnIds") List<String> turnIds);

    /** 删除 Workspace write claims。 */
    int deleteWorkspaceWriteClaims(@Param("threadIds") List<String> threadIds,
                                   @Param("turnIds") List<String> turnIds);

    /** 只删除入站 mailbox；sender 属于临时子树但 target 在外部的外发消息必须保留。 */
    int deleteInboundMailbox(@Param("threadIds") List<String> threadIds);

    /** 删除 Task projection，必须早于 task activities 与 lineage。 */
    int deleteTaskProjections(@Param("threadIds") List<String> threadIds);

    /** 删除 Task activity，覆盖 root/task/actor/causal Turn 四类固定引用。 */
    int deleteTaskActivities(@Param("threadIds") List<String> threadIds,
                             @Param("turnIds") List<String> turnIds);

    /** 删除 Thread instruction scopes；Thread 本身保留为 tombstone。 */
    int deleteThreadInstructionScopes(@Param("threadIds") List<String> threadIds);

    /** 删除 V2 冻结的 subagent policy。 */
    int deleteSubagentPolicies(@Param("threadIds") List<String> threadIds);

    /** 删除 lineage，必须在线删除 projection/activity 且固定 seed 后执行。 */
    int deleteLineage(@Param("threadIds") List<String> threadIds);

    /** 删除 lineage 不再引用的固定 context seed。 */
    int deleteContextSeeds(@Param("seedIds") List<String> seedIds);

    /** 删除固定临时 Thread；外发 mailbox 依靠 sender_title 保留发送方展示快照。 */
    int deleteThreads(@Param("threadIds") List<String> threadIds);

    /** 最后移除冻结 marker；删除正文前 marker 必须保持 CLOSING。 */
    int deleteMarkers(@Param("threadIds") List<String> threadIds);
}
