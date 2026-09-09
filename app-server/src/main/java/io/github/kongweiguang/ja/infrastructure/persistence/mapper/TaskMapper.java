// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Child Thread、Mailbox、Activity projection 与 Workspace write claim 的 SQL 边界。 */
@Mapper
public interface TaskMapper {
    /** admission 在同一快照读取父 Thread 与可选父 Task 层级。 */
    TaskRecords.ParentRow selectParent(@Param("threadId") String threadId);

    /** origin Turn 必须属于父 Thread，根 Turn 只能从既有因果列继承。 */
    TaskRecords.TurnCausalityRow selectTurnCausality(@Param("turnId") String turnId);

    /** 通信发送方与目标通过同一查询解析根树，不接受跨根 Mailbox。 */
    TaskRecords.TaskRouteRow selectTaskRoute(@Param("threadId") String threadId);

    /** 有效上下文只读取 checkpoint retained 边界之后的消息，并用 limit+1 暴露超限。 */
    List<PersistenceRecords.MessageRow> selectEffectiveMessages(@Param("threadId") String threadId,
                                                                @Param("retainedFromOrdinal") long retainedFromOrdinal,
                                                                @Param("limit") int limit);

    /** 根树容量按 lineage 权威事实计算，不相信可修复 projection 计数。 */
    int countRootDescendants(@Param("rootThreadId") String rootThreadId);

    /** 插入不可变上下文种子。 */
    int insertContextSeed(TaskRecords.ContextSeedInsert values);

    /** 插入带父/根因果列的 QUEUED Child Turn。 */
    int insertChildTurn(TaskRecords.ChildTurnInsert values);

    /** 插入不可变 Thread lineage。 */
    int insertLineage(TaskRecords.LineageInsert values);

    /** 追加 Activity 并返回数据库分配的单调 sequence。 */
    Long insertActivity(TaskRecords.ActivityInsert values);

    /** 创建 Task 当前投影。 */
    int insertProjection(TaskRecords.ProjectionInsert values);

    /** 新后代只更新其 Task 祖先计数，根主 Thread 没有 projection。 */
    int incrementAncestorDescendants(@Param("parentThreadId") String parentThreadId);

    /** 任一后代状态变化后从权威 lineage/projection 重算全部 Task 祖先计数。 */
    int recomputeAncestorCounts(@Param("taskThreadId") String taskThreadId,
                                @Param("occurredAt") String occurredAt);

    /** Child Thread 标题提交时推进 Task revision，使旧摘要不能覆盖新的展示名称。 */
    int advanceTitleProjection(@Param("taskThreadId") String taskThreadId,
                               @Param("occurredAt") String occurredAt);

    /** 按 Child Thread 读取一个不可分割的 summary 行。 */
    TaskRecords.TaskSummaryRow selectTaskSummary(@Param("taskThreadId") String taskThreadId);

    /** 按根和 lineage 顺序读取整棵有界任务树。 */
    List<TaskRecords.TaskSummaryRow> selectTaskTree(@Param("rootThreadId") String rootThreadId);

    /** 先按根索引截取最近活动，再联结当前 Task 摘要；LEFT JOIN 让损坏关系可被 adapter 识别。 */
    List<TaskRecords.RootActivityProjectionRow> selectRootActivityProjections(
            @Param("rootThreadId") String rootThreadId, @Param("limit") int limit);

    /** 读取 Task 的不可变上下文种子。 */
    TaskRecords.ContextSeedRow selectContextSeed(@Param("taskThreadId") String taskThreadId);

    /** 按 sequence 增量读取 Task 活动；零表示首屏。 */
    List<TaskRecords.ActivityRow> selectActivities(@Param("taskThreadId") String taskThreadId,
                                                   @Param("afterSequence") long afterSequence,
                                                   @Param("limit") int limit);

    /** 详情只读取发送方或目标为该 Task 的 Mailbox 行。 */
    List<TaskRecords.MailboxRow> selectMailbox(@Param("taskThreadId") String taskThreadId,
                                               @Param("afterSequence") long afterSequence,
                                               @Param("limit") int limit);

    /** 幂等重试先按发送方命名空间读取首个权威事实。 */
    TaskRecords.MailboxRow selectMailboxByIdempotency(@Param("senderThreadId") String senderThreadId,
                                                      @Param("idempotencyKey") String idempotencyKey);

    /** 容量门只统计尚未消费的目标消息和 UTF-8 bytes。 */
    TaskRecords.MailboxStats selectMailboxStats(@Param("targetThreadId") String targetThreadId);

    /** 插入 Mailbox 并返回 sequence；唯一键竞争由 adapter 回读验证语义。 */
    Long insertMailbox(TaskRecords.MailboxInsert values);

    /** Follow-up 把 PENDING 消息绑定到同事务创建的目标 Turn。 */
    int bindMailbox(TaskRecords.MailboxBind values);

    /** 崩溃重入先读取当前 Turn 已经持有的 BOUND 消息，禁止重新分配身份。 */
    List<TaskRecords.MailboxRow> selectBoundMailboxForTurn(@Param("turnId") String turnId,
                                                           @Param("limit") int limit);

    /** 按 sequence 将有限 PENDING 消息绑定到当前 Turn。 */
    int claimPendingMailbox(TaskRecords.MailboxClaim values);

    /** USER facts 同事务提交后只消费当前 Turn 持有的 BOUND 消息。 */
    int consumeBoundMailboxForTurn(TaskRecords.MailboxConsume values);

    /** Turn 终态只释放尚未消费的 BOUND 行，不改写已经提交的 CONSUMED 事实。 */
    int releaseBoundMailboxForTurn(TaskRecords.MailboxRelease values);

    /** Activity 与状态投影共享 expected revision CAS。 */
    int compareAndSetProjection(TaskRecords.ProjectionCas values);

    /** QueueOnly 通信只推进 Activity/未读，不改变运行或终态。 */
    int compareAndSetProjectionActivity(TaskRecords.ProjectionActivityCas values);

    /** 显式 Follow-up 允许把已完成 Task 重新置为 QUEUED。 */
    int compareAndSetFollowUpProjection(TaskRecords.FollowUpProjectionCas values);

    /** 已读 CAS 通过子查询重新计算未读，而非用全局 sequence 差值。 */
    int compareAndSetSeen(TaskRecords.SeenCas values);

    /** 取消传播只选择 ATTACHED 后代并保持父优先顺序。 */
    List<TaskRecords.TaskSummaryRow> selectAttachedDescendants(@Param("parentThreadId") String parentThreadId);

    /** 父取消事实仍有非终态 ATTACHED 直接子 Turn 时返回一次恢复传播身份。 */
    List<TaskRecords.CancellationPropagationRow> selectPendingCancellationPropagations();

    /** normal terminal commit 通过 Turn 身份定位 Child Task 扩展。 */
    TaskRecords.TerminalTaskRow selectTaskByTurn(@Param("turnId") String turnId);

    /** 终态提交读取同一 Child 的下一条非终态 Turn，Task 投影不得越过已排队工作。 */
    String selectNextNonTerminalTaskTurnState(@Param("taskThreadId") String taskThreadId,
                                              @Param("settledTurnId") String settledTurnId);

    /** 删除前必须证明目标 projection revision 未漂移。 */
    int countDeleteTarget(TaskRecords.TreeDelete values);

    /** 删除拒绝任何非终态后代 Turn，避免静默取消运行工作。 */
    int countNonTerminalTurnsInTree(@Param("taskThreadId") String taskThreadId);

    /** 返回即将删除的 Task 数供 RPC 回执。 */
    int countTaskSubtree(@Param("taskThreadId") String taskThreadId);

    /** 显式删除先把 Child Threads 标为 deleted，防止 lineage 移除后污染全局 Thread 列表。 */
    int softDeleteTaskThreads(TaskRecords.TreeDelete values);

    /** 移除子树关联的 Workspace claims。 */
    int deleteTreeWriteClaims(@Param("taskThreadId") String taskThreadId);

    /** 移除任一端属于子树的 Mailbox。 */
    int deleteTreeMailbox(@Param("taskThreadId") String taskThreadId);

    /** 移除子树 Task 活动。 */
    int deleteTreeActivities(@Param("taskThreadId") String taskThreadId);

    /** 移除子树当前投影。 */
    int deleteTreeProjections(@Param("taskThreadId") String taskThreadId);

    /** 删除 lineage 前先返回其 seed 身份供后续清理。 */
    List<String> selectTreeSeedIds(@Param("taskThreadId") String taskThreadId);

    /** 移除子树 lineage，使已 soft-delete Thread 不再作为 Child 出现。 */
    int deleteTreeLineage(@Param("taskThreadId") String taskThreadId);

    /** 移除不再被 lineage 引用的冻结 seed。 */
    int deleteContextSeeds(@Param("seedIds") List<String> seedIds);

    /** 物理删除后重算仍存在 Task 的全部后代计数。 */
    int recomputeDescendantCounts(@Param("rootThreadId") String rootThreadId,
                                  @Param("occurredAt") String occurredAt);

    /** Workspace 内按当前最大 token 分配下一 fencing token。 */
    Long selectNextFencingToken(@Param("workspaceId") String workspaceId);

    /** 单行 ledger 在 SQLite writer transaction 内分配严格递增的 App Server 代际。 */
    Long allocateProcessGeneration();

    /** 追加 WAITING write claim。 */
    Long insertWriteClaim(TaskRecords.WriteClaimInsert values);

    /** 按身份读取 write claim。 */
    TaskRecords.WriteClaimRow selectWriteClaim(@Param("claimId") String claimId);

    /** FIFO 队首且无 HELD owner 时才可变为 HELD。 */
    int acquireWriteClaim(TaskRecords.WriteClaimCas values);

    /** 只有当前 fencing owner 可续 heartbeat。 */
    int heartbeatWriteClaim(TaskRecords.WriteClaimCas values);

    /** 只有当前 fencing owner 可正常释放。 */
    int releaseWriteClaim(TaskRecords.WriteClaimCas values);

    /** WAITING 或 HELD 声明可因取消/恢复被显式放弃。 */
    int abandonWriteClaim(TaskRecords.WriteClaimCas values);

    /** 已确认旧进程死亡且当前进程尚未准入 claim 时，废弃全部遗留活动声明。 */
    int abandonActiveWriteClaims(@Param("occurredAt") String occurredAt);
}
