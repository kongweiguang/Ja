// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SideChatPurgeMapper;
import io.github.kongweiguang.ja.task.port.out.TaskRepositoryException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 在调用方事务中清理一个 CLOSING 临时侧聊的完整数据库事实图。
 *
 * <p>清理前一次性固定 Thread/Turn/Goal/Plan/Run/Seed/Revision 身份，后续 SQL 不再递归
 * lineage。这样删除过程即使移除了 lineage，也不会因查询目标漂移而误删其它会话；整个图
 * 仍由调用方的单个 writer transaction 原子提交或回滚。</p>
 */
public final class SideChatPurger {
    /** 清理必须使用调用方事务，不允许实例持有另一份数据库或资源 owner。 */
    private SideChatPurger() {
    }

    /**
     * 清理以 CLOSING marker 为根的临时侧聊子树。
     *
     * @param mappers 调用方当前事务的完整 Mapper 集合
     * @param rootThreadId 临时侧聊根 Thread
     * @param requireTerminal 正常关闭时要求所有 Turn 已进入终态；启动恢复可跳过
     * @return 被物理删除的 Thread 数
     *
     * <p>附件 identity 在任何关系删除前冻结；后续只释放本次目标且没有其它持久引用的附件，
     * blob 行和文件继续交给既有 Attachment GC。</p>
     */
    public static int purge(PersistenceMappers mappers, String rootThreadId, boolean requireTerminal) {
        Objects.requireNonNull(mappers, "mappers");
        String root = requireThreadId(rootThreadId);
        SideChatPurgeMapper purge = Objects.requireNonNull(mappers.sideChatPurges(), "sideChatPurges");
        if (purge.countClosingRoot(root) != 1) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.RELATION_INVALID,
                    "temporary side chat is not a closing independent root");
        }

        List<String> threadIds = immutableIds(purge.selectPurgeThreadIds(root));
        if (threadIds.isEmpty() || !threadIds.contains(root)) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "temporary side chat purge has no stable Thread set");
        }
        if (requireTerminal && purge.countNonTerminalTurns(threadIds) != 0) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.TREE_DELETE_REQUIRED,
                    "temporary side chat contains non-terminal Turns");
        }

        List<String> turnIds = immutableIds(purge.selectPurgeTurnIds(threadIds));
        List<String> goalIds = immutableIds(purge.selectPurgeGoalIds(threadIds));
        List<String> planIds = immutableIds(purge.selectPurgePlanIds(threadIds));
        List<String> runIds = immutableIds(purge.selectPurgeRunIds(goalIds, planIds));
        List<String> seedIds = immutableIds(purge.selectPurgeSeedIds(threadIds));
        List<String> planRevisionIds = immutableIds(purge.selectPurgePlanRevisionIds(planIds));
        List<String> attachmentIds = immutableIds(purge.selectPurgeAttachmentIds(threadIds, turnIds));

        if (purge.markPurgeThreadsClosing(threadIds) != threadIds.size()) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.CAS_CONFLICT,
                    "temporary side chat closing markers changed concurrently");
        }
        // 由应用冻结统一的 ISO 审计时间，避免 SQLite CURRENT_TIMESTAMP 的文本格式漂移。
        String discardedAt = Instant.now().toString();
        deleteTaskFacts(purge, threadIds, turnIds, seedIds);
        deleteInteractionAndEvaluationLeaves(purge, threadIds, turnIds, goalIds, planIds,
                planRevisionIds, runIds);
        deleteGoalPlanGraph(purge, goalIds, planIds, planRevisionIds, runIds);
        deleteConversationFacts(purge, threadIds, turnIds, attachmentIds, discardedAt);
        int count = deleteMarkersAndThreads(purge, threadIds);
        if (count < 1) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "temporary side chat purge removed no Thread");
        }
        return count;
    }

    /** 删除 Interaction、evaluator 和运行叶子，先解除对 Goal/Plan/Run 的外键引用。 */
    private static void deleteInteractionAndEvaluationLeaves(SideChatPurgeMapper purge,
                                                              List<String> threadIds,
                                                              List<String> turnIds,
                                                              List<String> goalIds,
                                                              List<String> planIds,
                                                              List<String> planRevisionIds,
                                                              List<String> runIds) {
        purge.deleteInteractionDrafts(threadIds, turnIds, planRevisionIds, runIds, goalIds);
        purge.deleteInteractionEvents(threadIds, turnIds, planRevisionIds, runIds, goalIds);
        purge.deleteInteractionRequests(threadIds, turnIds, planRevisionIds, runIds, goalIds);
        purge.deletePlanEvaluationRequests(threadIds, planIds, planRevisionIds, runIds);
        if (hasValues(goalIds, runIds, turnIds)) {
            purge.deleteGoalToolAttempts(goalIds, runIds, turnIds);
        }
        if (hasValues(goalIds, runIds)) {
            purge.deleteGoalEvaluations(goalIds, runIds);
        }
        if (hasValues(goalIds, planIds, planRevisionIds, runIds)) {
            purge.deleteAcceptanceEvidence(goalIds, planIds, planRevisionIds, runIds);
        }
        if (hasValues(runIds, planRevisionIds)) {
            purge.deletePlanStepExecutions(runIds, planRevisionIds);
        }
        if (!runIds.isEmpty()) {
            purge.deletePlanTurnClaims(runIds);
        }
        if (!goalIds.isEmpty()) {
            purge.deleteGoalContinuationLeases(goalIds);
            purge.deleteGoalEvents(goalIds);
        }
        if (!planIds.isEmpty()) {
            purge.deletePlanEvents(planIds);
        }
        if (hasValues(goalIds, planIds, planRevisionIds)) {
            purge.deleteGoalPlanLinks(goalIds, planIds, planRevisionIds);
        }
    }

    /** 依照反向 active_run 约束先删 Run，再删 Goal/Plan owner 图。 */
    private static void deleteGoalPlanGraph(SideChatPurgeMapper purge,
                                            List<String> goalIds,
                                            List<String> planIds,
                                            List<String> planRevisionIds,
                                            List<String> runIds) {
        if (hasValues(planIds, planRevisionIds)) {
            purge.deletePlanApprovals(planIds, planRevisionIds);
        }
        if (!runIds.isEmpty()) {
            purge.deleteExecutionRuns(runIds);
        }
        if (!goalIds.isEmpty()) {
            purge.deleteGoalAcceptanceCriteria(goalIds);
            purge.deleteGoalDefinitionRevisions(goalIds);
            purge.deleteGoals(goalIds);
        }
        if (!planRevisionIds.isEmpty()) {
            purge.deletePlanSteps(planRevisionIds);
            purge.deleteAcceptanceCriteria(planRevisionIds);
        }
        if (!planIds.isEmpty()) {
            purge.deletePlanDrafts(planIds);
            if (!planRevisionIds.isEmpty()) {
                purge.deletePlanRevisions(planRevisionIds);
            }
            purge.deletePlans(planIds);
        }
    }

    /** 在 marker 仍保持 CLOSING 时先断开附件关系并释放安全目标，再删除 immutable facts 和 Turn 子事实。 */
    private static void deleteConversationFacts(SideChatPurgeMapper purge,
                                                List<String> threadIds,
                                                List<String> turnIds,
                                                List<String> attachmentIds,
                                                String discardedAt) {
        purge.deleteMessageAttachments(threadIds);
        purge.deletePendingInputAttachments(threadIds, turnIds);
        if (!attachmentIds.isEmpty()) {
            purge.discardPurgeAttachments(attachmentIds, discardedAt);
        }
        purge.deleteMessages(threadIds);
        purge.deleteContextCheckpoints(threadIds);
        purge.deleteUsage(threadIds, turnIds);
        if (!turnIds.isEmpty()) {
            purge.deleteToolBindings(turnIds);
        }
        purge.deleteApprovals(threadIds, turnIds);
        purge.deleteToolArtifacts(threadIds, turnIds);
        purge.deleteTurnChangeSets(threadIds, turnIds);
        purge.deleteChangeSetArtifacts(threadIds, turnIds);
        purge.deleteTimelineMessages(threadIds, turnIds);
        purge.deleteThreadTitleGenerations(threadIds, turnIds);
        if (!turnIds.isEmpty()) {
            purge.deleteTurnExecution(turnIds);
            purge.deleteTurnInternalContext(turnIds);
        }
        purge.deleteTools(threadIds, turnIds);
        purge.deletePendingInputs(threadIds, turnIds);
        deleteTurnsBottomUp(purge, turnIds);
    }

    /** 删除 Turn 自引用树的叶子，重复执行直到固定集合不再包含可删除父 Turn。 */
    private static void deleteTurnsBottomUp(SideChatPurgeMapper purge, List<String> turnIds) {
        if (turnIds.isEmpty()) return;
        int deleted;
        do {
            deleted = purge.deleteLeafTurns(turnIds);
        } while (deleted > 0);
    }

    /** 删除 Task 投影、活动、策略、lineage 和冻结 seed，保持外发 mailbox 的发送方事实。 */
    private static void deleteTaskFacts(SideChatPurgeMapper purge,
                                        List<String> threadIds,
                                        List<String> turnIds,
                                        List<String> seedIds) {
        purge.deleteWorkspaceWriteClaims(threadIds, turnIds);
        purge.deleteInboundMailbox(threadIds);
        purge.deleteTaskProjections(threadIds);
        purge.deleteTaskActivities(threadIds, turnIds);
        purge.deleteThreadInstructionScopes(threadIds);
        purge.deleteSubagentPolicies(threadIds);
        purge.deleteLineage(threadIds);
        if (!seedIds.isEmpty()) {
            purge.deleteContextSeeds(seedIds);
        }
    }

    /** 所有受保护事实删除后才移除 marker，并物理删除 Thread；整个事务提交点不存在半残图。 */
    private static int deleteMarkersAndThreads(SideChatPurgeMapper purge, List<String> threadIds) {
        purge.deleteMarkers(threadIds);
        return purge.deleteThreads(threadIds);
    }

    /** 复制并校验 SQL 结果，避免 mapper 返回可变列表成为删除期间的隐式目标输入。 */
    private static List<String> immutableIds(List<String> ids) {
        if (ids == null || ids.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "temporary side chat purge returned invalid identity");
        }
        return List.copyOf(ids);
    }

    /** 判断可选身份集合是否至少有一项，防止 MyBatis 生成空 IN 或无条件 DELETE。 */
    @SafeVarargs
    private static boolean hasValues(List<String>... values) {
        for (List<String> value : values) {
            if (value != null && !value.isEmpty()) return true;
        }
        return false;
    }

    /** Thread identity 由领域端口复用的窄格式，避免 SQL 递归入口接受空或控制字符。 */
    private static String requireThreadId(String value) {
        if (value == null || !value.startsWith("thr_") || value.length() > 128
                || !value.substring("thr_".length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid side chat thread id");
        }
        return value;
    }
}
