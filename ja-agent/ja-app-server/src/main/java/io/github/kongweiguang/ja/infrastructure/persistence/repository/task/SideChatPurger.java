// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepositoryException;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SideChatPurgeMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskRecords;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * 在调用方事务中清理一个 CLOSING 临时侧聊的完整数据库事实图。
 *
 * <p>清理前一次性固定 Thread/Turn/Plan/Run/Seed/Revision 身份，后续 SQL 不再递归
 * lineage。身份参数按数据库变量上限分批绑定；所有批次继续由调用方同一个 writer transaction
 * 提交或回滚。</p>
 */
public final class SideChatPurger {
    /** Three repeated SQL expansions or three bound columns remain below SQLite's 999-variable floor. */
    private static final int SQL_ID_BATCH_SIZE = 300;

    /**
     * 清理必须使用调用方事务，不允许实例持有另一份数据库或资源 owner。
     */
    private SideChatPurger() {
    }

    /**
     * 清理以 CLOSING Thread 为根的临时侧聊子树。
     *
     * @param mappers         调用方当前事务的完整 Mapper 集合
     * @param rootThreadId    临时侧聊根 Thread
     * @param expectedOwnerRootThreadId 关闭通知的冻结路由根
     * @param expectedThreadIds 关闭准入时冻结的完整 Thread 集合
     * @param requireTerminal 正常关闭时要求所有 Turn 已进入终态；启动恢复可跳过
     * @return 与物理清理同事务提交的稳定范围回执
     *
     * <p>附件 identity 在任何关系删除前冻结；后续只释放本次目标且没有其它持久引用的附件，
     * blob 行和文件继续交给既有 Attachment GC。</p>
     */
    public static TaskModels.SideChatCloseReceipt purge(PersistenceMappers mappers,
                                                        String rootThreadId,
                                                        String expectedOwnerRootThreadId,
                                                        List<String> expectedThreadIds,
                                                        boolean requireTerminal) {
        Objects.requireNonNull(mappers, "mappers");
        String root = requireThreadId(rootThreadId);
        String expectedOwner = requireThreadId(expectedOwnerRootThreadId);
        SideChatPurgeMapper purge = Objects.requireNonNull(mappers.sideChatPurges(), "sideChatPurges");
        TaskModels.SideChatCloseState frozen = new TaskModels.SideChatCloseState(root, expectedOwner,
                TaskModels.SideChatClosePhase.CLOSING, expectedThreadIds);
        Optional<TaskModels.SideChatCloseReceipt> previous = readCloseReceipt(purge, root);
        if (previous.isPresent()) {
            TaskModels.SideChatCloseReceipt receipt = previous.orElseThrow();
            if (!receipt.ownerRootThreadId().equals(expectedOwner)
                || !receipt.closedThreadIds().equals(frozen.threadIds())) {
                throw new TaskRepositoryException(TaskRepositoryException.Code.CAS_CONFLICT,
                        "side chat close retry changed its frozen owner or Thread set");
            }
            return receipt;
        }
        if (purge.countClosingRoot(root) != 1) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.RELATION_INVALID,
                    "temporary side chat is not a closing independent root");
        }

        List<String> threadIds = canonicalThreadIds(purge.selectPurgeThreadIds(root));
        if (threadIds.isEmpty() || !threadIds.contains(root)) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "temporary side chat purge has no stable Thread set");
        }
        if (!threadIds.equals(frozen.threadIds())) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.CAS_CONFLICT,
                    "temporary side chat Thread set changed after close admission");
        }
        TaskRecords.ParentRow lineageOwner = mappers.tasks().selectParent(root);
        if (lineageOwner == null || !expectedOwner.equals(lineageOwner.rootThreadId())) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.CAS_CONFLICT,
                    "side chat owner changed after close admission");
        }
        String workspaceId = purge.selectPurgeWorkspaceId(root);
        if (workspaceId == null || workspaceId.isBlank()
                || purge.countPurgeThreadsOutsideWorkspace(root, workspaceId) != 0) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.RELATION_INVALID,
                    "temporary side chat spans an invalid Workspace boundary");
        }
        if (requireTerminal && applyInBatches(threadIds, purge::countNonTerminalTurns) != 0) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.TREE_DELETE_REQUIRED,
                    "temporary side chat contains non-terminal Turns");
        }

        List<String> turnIds = selectInBatches(threadIds, purge::selectPurgeTurnIds);
        List<String> planIds = selectInBatches(threadIds, purge::selectPurgePlanIds);
        List<String> runIds = selectInBatches(planIds, purge::selectPurgeRunIds);
        List<String> seedIds = selectInBatches(threadIds, purge::selectPurgeSeedIds);
        List<String> planRevisionIds = selectInBatches(planIds, purge::selectPurgePlanRevisionIds);
        List<String> attachmentIds = selectAcrossBatches(List.of(threadIds, turnIds), ids ->
                purge.selectPurgeAttachmentIds(ids.get(0), ids.get(1)));

        if (applyInBatches(threadIds, purge::markPurgeThreadsClosing) != threadIds.size()) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.CAS_CONFLICT,
                    "temporary side chat closing markers changed concurrently");
        }
        long closedAtMs = Instant.now().toEpochMilli();
        TaskRecords.SideChatCloseReceiptInsert receipt = new TaskRecords.SideChatCloseReceiptInsert(
                root, workspaceId, expectedOwner, threadIds.size(), closedAtMs);
        if (purge.insertCloseReceipt(receipt) != 1) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.CAS_CONFLICT,
                    "temporary side chat close receipt insert lost");
        }
        int insertedMembers = 0;
        for (int start = 0; start < threadIds.size(); start += SQL_ID_BATCH_SIZE) {
            int end = Math.min(start + SQL_ID_BATCH_SIZE, threadIds.size());
            List<TaskRecords.SideChatCloseMemberInsert> receiptMembers = new ArrayList<>(end - start);
            for (int index = start; index < end; index++) {
                receiptMembers.add(new TaskRecords.SideChatCloseMemberInsert(root, index, threadIds.get(index)));
            }
            insertedMembers += purge.insertCloseReceiptMembers(receiptMembers);
        }
        if (insertedMembers != threadIds.size()) {
            throw new StorageException(StorageException.Code.TRANSACTION,
                    "temporary side chat close receipt members were not fully stored");
        }
        // 由应用冻结统一的 审计时间，避免 SQLite CURRENT_TIMESTAMP 的文本格式漂移。
        String discardedAt = Instant.now().toString();
        deleteAcrossBatches(List.of(threadIds, turnIds), ids ->
                purge.deleteTimelineMessages(ids.get(0), ids.get(1)));
        deleteTaskFacts(purge, threadIds, turnIds, seedIds);
        deleteInteractionAndEvaluationLeaves(purge, threadIds, turnIds, planIds,
                planRevisionIds, runIds);
        deletePlanGraph(purge, planIds, planRevisionIds, runIds);
        deleteConversationFacts(purge, threadIds, turnIds, attachmentIds, discardedAt);
        int count = deleteMarkersAndThreads(purge, threadIds);
        if (count != threadIds.size()) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "temporary side chat purge did not remove its complete frozen Thread set");
        }
        return new TaskModels.SideChatCloseReceipt(root, expectedOwner, threadIds);
    }

    /**
     * 从持久主行和有序成员回放已完成回执；损坏或范围不完整时失败关闭，不重建已删除的 lineage。
     */
    public static Optional<TaskModels.SideChatCloseReceipt> readCloseReceipt(PersistenceMappers mappers,
                                                                             String rootThreadId) {
        Objects.requireNonNull(mappers, "mappers");
        return readCloseReceipt(mappers.sideChatPurges(), requireThreadId(rootThreadId));
    }

    /**
     * 只在主行存在时读取成员，并校验计数、序号及 canonical 顺序后才向调用方暴露回执。
     */
    private static Optional<TaskModels.SideChatCloseReceipt> readCloseReceipt(SideChatPurgeMapper purge,
                                                                              String rootThreadId) {
        TaskRecords.SideChatCloseReceiptRow row = purge.selectCloseReceipt(rootThreadId);
        if (row == null) {
            return Optional.empty();
        }
        List<TaskRecords.SideChatCloseMemberRow> members = purge.selectCloseReceiptMembers(rootThreadId);
        if (row.closedThreadCount() == null || row.closedAtMs() == null
            || row.closedThreadCount() < 1 || members.size() != row.closedThreadCount()) {
            throw invalidReceipt();
        }
        List<String> threadIds = new ArrayList<>(members.size());
        for (int index = 0; index < members.size(); index++) {
            TaskRecords.SideChatCloseMemberRow member = members.get(index);
            if (member == null || member.memberOrdinal() == null || member.memberOrdinal() != index
                || member.threadId() == null) {
                throw invalidReceipt();
            }
            threadIds.add(member.threadId());
        }
        try {
            if (!rootThreadId.equals(row.rootThreadId())) {
                throw invalidReceipt();
            }
            TaskModels.SideChatCloseReceipt receipt = new TaskModels.SideChatCloseReceipt(
                    row.rootThreadId(), row.ownerRootThreadId(), threadIds);
            if (!receipt.closedThreadIds().equals(threadIds)) {
                throw invalidReceipt();
            }
            return Optional.of(receipt);
        } catch (IllegalArgumentException failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "persisted side chat close receipt is invalid", failure);
        }
    }

    /**
     * 回执结构违反 schema 之外的跨行不变量时保持存储 fail closed。
     */
    private static StorageException invalidReceipt() {
        return new StorageException(StorageException.Code.INVALID_STATE,
                "persisted side chat close receipt is incomplete");
    }

    /**
     * Thread 清理范围以稳定字典序保存；拒绝重复 ID，避免 SQL 遍历顺序影响重试响应。
     */
    private static List<String> canonicalThreadIds(List<String> threadIds) {
        List<String> values = immutableIds(threadIds);
        List<String> canonical = values.stream().sorted().toList();
        if (canonical.stream().distinct().count() != canonical.size()) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "temporary side chat purge returned duplicate Thread identity");
        }
        return canonical;
    }

    /**
     * 删除 Interaction、evaluator 和运行叶子，先解除对 Plan/Run 的外键引用。
     */
    private static void deleteInteractionAndEvaluationLeaves(SideChatPurgeMapper purge,
                                                             List<String> threadIds,
                                                             List<String> turnIds,
                                                             List<String> planIds,
                                                             List<String> planRevisionIds,
                                                             List<String> runIds) {
        deleteAcrossBatches(List.of(threadIds, turnIds, planRevisionIds, runIds), ids ->
                purge.deleteInteractionDrafts(ids.get(0), ids.get(1), ids.get(2), ids.get(3)));
        deleteAcrossBatches(List.of(threadIds, turnIds, planRevisionIds, runIds), ids ->
                purge.deleteInteractionEvents(ids.get(0), ids.get(1), ids.get(2), ids.get(3)));
        deleteAcrossBatches(List.of(threadIds, turnIds, planRevisionIds, runIds), ids ->
                purge.deleteInteractionRequests(ids.get(0), ids.get(1), ids.get(2), ids.get(3)));
        deleteAcrossBatches(List.of(threadIds, planIds, planRevisionIds, runIds), ids ->
                purge.deletePlanEvaluationRequests(ids.get(0), ids.get(1), ids.get(2), ids.get(3)));
        deleteAcrossBatches(List.of(runIds, turnIds), ids ->
                purge.deletePlanToolAttempts(ids.get(0), ids.get(1)));
        deleteAcrossBatches(List.of(planIds, planRevisionIds, runIds), ids ->
                purge.deleteAcceptanceEvidence(ids.get(0), ids.get(1), ids.get(2)));
        deleteAcrossBatches(List.of(runIds, planRevisionIds), ids ->
                purge.deletePlanStepExecutions(ids.get(0), ids.get(1)));
        applyInBatches(runIds, purge::deletePlanTurnClaims);
        applyInBatches(planIds, purge::deletePlanEvents);
    }

    /**
     * 依照反向 active_run 约束先删 Run，再删 Plan owner 图。
     */
    private static void deletePlanGraph(SideChatPurgeMapper purge,
                                            List<String> planIds,
                                            List<String> planRevisionIds,
                                            List<String> runIds) {
        applyInBatches(planIds, purge::clearPlanHeads);
        deleteAcrossBatches(List.of(planIds, planRevisionIds), ids ->
                purge.deletePlanApprovals(ids.get(0), ids.get(1)));
        applyInBatches(runIds, purge::deleteExecutionRuns);
        applyInBatches(planRevisionIds, purge::deletePlanSteps);
        applyInBatches(planRevisionIds, purge::deleteAcceptanceCriteria);
        applyInBatches(planIds, purge::deletePlanDrafts);
        applyInBatches(planRevisionIds, purge::deletePlanRevisions);
        applyInBatches(planIds, purge::deletePlans);
    }

    /**
     * 在 marker 仍保持 CLOSING 时解除消息、ToolResult 附件 owner 和 Core call-preview 外键，再删除 immutable facts。
     */
    private static void deleteConversationFacts(SideChatPurgeMapper purge,
                                                List<String> threadIds,
                                                List<String> turnIds,
                                                List<String> attachmentIds,
                                                String discardedAt) {
        applyInBatches(threadIds, purge::deleteMessageAttachments);
        deleteAcrossBatches(List.of(threadIds, turnIds), ids ->
                purge.deletePendingInputAttachments(ids.get(0), ids.get(1)));
        deleteAcrossBatches(List.of(threadIds, turnIds), ids ->
                purge.deleteToolResultAttachments(ids.get(0), ids.get(1)));
        applyInBatches(attachmentIds, ids -> purge.discardPurgeAttachments(ids, discardedAt));
        applyInBatches(threadIds, purge::deleteMessages);
        applyInBatches(threadIds, purge::deleteContextCheckpoints);
        deleteAcrossBatches(List.of(threadIds, turnIds), ids -> purge.deleteUsage(ids.get(0), ids.get(1)));
        applyInBatches(turnIds, purge::deleteToolBindings);
        deleteAcrossBatches(List.of(threadIds, turnIds), ids -> purge.deleteApprovals(ids.get(0), ids.get(1)));
        applyInBatches(threadIds, purge::deleteToolRecoveryAttempts);
        applyInBatches(threadIds, purge::deleteToolRecoveries);
        applyInBatches(threadIds, purge::deleteClientOperations);
        applyInBatches(threadIds, purge::deleteInputOperations);
        deleteAcrossBatches(List.of(threadIds, turnIds), ids ->
                purge.deleteToolArtifacts(ids.get(0), ids.get(1)));
        deleteAcrossBatches(List.of(threadIds, turnIds), ids ->
                purge.deleteTurnChangeSets(ids.get(0), ids.get(1)));
        deleteAcrossBatches(List.of(threadIds, turnIds), ids ->
                purge.deleteChangeSetArtifacts(ids.get(0), ids.get(1)));
        deleteAcrossBatches(List.of(threadIds, turnIds), ids ->
                purge.deleteThreadTitleGenerations(ids.get(0), ids.get(1)));
        applyInBatches(threadIds, purge::deleteAgentEvents);
        applyInBatches(threadIds, purge::deleteAgentOperationReceipts);
        applyInBatches(threadIds, purge::deleteAgentCommitReceipts);
        applyInBatches(threadIds, purge::deleteAgentRecords);
        applyInBatches(threadIds, purge::deleteAgentLeases);
        applyInBatches(threadIds, purge::deleteAgentExecutions);
        applyInBatches(threadIds, purge::deleteAgentConversations);
        applyInBatches(turnIds, purge::deleteTurnInternalContext);
        applyInBatches(turnIds, purge::deleteToolCallPreviews);
        deleteAcrossBatches(List.of(threadIds, turnIds), ids -> purge.deleteTools(ids.get(0), ids.get(1)));
        deleteAcrossBatches(List.of(threadIds, turnIds), ids -> purge.deletePendingInputs(ids.get(0), ids.get(1)));
        deleteTurnsBottomUp(purge, turnIds);
    }

    /**
     * 删除 Turn 自引用树的叶子，重复执行直到固定集合不再包含可删除父 Turn。
     */
    private static void deleteTurnsBottomUp(SideChatPurgeMapper purge, List<String> turnIds) {
        if (turnIds.isEmpty()) {
            return;
        }
        int deleted;
        do {
            deleted = applyInBatches(turnIds, purge::deleteLeafTurns);
        } while (deleted > 0);
    }

    /**
     * 删除 Task 投影、活动、策略、lineage 和冻结 seed，保持外发 mailbox 的发送方事实。
     */
    private static void deleteTaskFacts(SideChatPurgeMapper purge,
                                        List<String> threadIds,
                                        List<String> turnIds,
                                        List<String> seedIds) {
        deleteAcrossBatches(List.of(threadIds, turnIds), ids ->
                purge.deleteWorkspaceWriteClaims(ids.get(0), ids.get(1)));
        applyInBatches(threadIds, purge::deleteInboundMailbox);
        applyInBatches(threadIds, purge::deleteTaskProjections);
        deleteAcrossBatches(List.of(threadIds, turnIds), ids ->
                purge.deleteTaskActivities(ids.get(0), ids.get(1)));
        applyInBatches(threadIds, purge::deleteThreadInstructionScopes);
        applyInBatches(threadIds, purge::deleteSubagentPolicies);
        applyInBatches(threadIds, purge::deleteLineage);
        applyInBatches(seedIds, purge::deleteContextSeeds);
    }

    /**
     * 所有受保护事实删除后才移除 marker，并物理删除 Thread；整个事务提交点不存在半残图。
     */
    private static int deleteMarkersAndThreads(SideChatPurgeMapper purge, List<String> threadIds) {
        return applyInBatches(threadIds, purge::deleteThreads);
    }

    /**
     * 限定每条动态 IN 语句的身份参数数量；多个集合条件分开执行，调用方事务保证整组删除原子性。
     */
    private static int applyInBatches(List<String> ids, ToIntFunction<List<String>> statement) {
        List<String> values = immutableIds(ids);
        int affected = 0;
        for (int start = 0; start < values.size(); start += SQL_ID_BATCH_SIZE) {
            int end = Math.min(start + SQL_ID_BATCH_SIZE, values.size());
            affected = Math.addExact(affected, statement.applyAsInt(values.subList(start, end)));
        }
        return affected;
    }

    /**
     * 分批读取完整 ID 集合，避免查询参数随单个 Thread 的历史事实数量增长到 SQLite 上限。
     */
    private static List<String> selectInBatches(List<String> ids,
                                                Function<List<String>, List<String>> statement) {
        List<String> values = immutableIds(ids);
        List<String> selected = new ArrayList<>();
        for (int start = 0; start < values.size(); start += SQL_ID_BATCH_SIZE) {
            int end = Math.min(start + SQL_ID_BATCH_SIZE, values.size());
            selected.addAll(immutableIds(statement.apply(values.subList(start, end))));
        }
        return List.copyOf(selected);
    }

    /**
     * 执行含多个 OR 身份维度的 SQL 时逐维度、逐批处理，避免组合参数总量突破数据库变量上限。
     */
    private static int deleteAcrossBatches(List<List<String>> idGroups,
                                           ToIntFunction<List<List<String>>> statement) {
        int affected = 0;
        for (int groupIndex = 0; groupIndex < idGroups.size(); groupIndex++) {
            List<String> values = immutableIds(idGroups.get(groupIndex));
            for (int start = 0; start < values.size(); start += SQL_ID_BATCH_SIZE) {
                int end = Math.min(start + SQL_ID_BATCH_SIZE, values.size());
                List<List<String>> arguments = emptyArguments(idGroups.size());
                arguments.set(groupIndex, values.subList(start, end));
                affected = Math.addExact(affected, statement.applyAsInt(List.copyOf(arguments)));
            }
        }
        return affected;
    }

    /**
     * 查询多个身份维度的并集，并在 Java 中去重以保留原 SQL UNION 的结果语义。
     */
    private static List<String> selectAcrossBatches(List<List<String>> idGroups,
                                                     Function<List<List<String>>, List<String>> statement) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (int groupIndex = 0; groupIndex < idGroups.size(); groupIndex++) {
            List<String> values = immutableIds(idGroups.get(groupIndex));
            for (int start = 0; start < values.size(); start += SQL_ID_BATCH_SIZE) {
                int end = Math.min(start + SQL_ID_BATCH_SIZE, values.size());
                List<List<String>> arguments = emptyArguments(idGroups.size());
                arguments.set(groupIndex, values.subList(start, end));
                selected.addAll(immutableIds(statement.apply(List.copyOf(arguments))));
            }
        }
        return List.copyOf(selected);
    }

    /**
     * 构造指定参数位数的空筛选列表，确保每次 OR 条件调用只携带一个身份维度。
     */
    private static List<List<String>> emptyArguments(int count) {
        List<List<String>> arguments = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            arguments.add(List.of());
        }
        return arguments;
    }

    /**
     * 复制并校验 SQL 结果，避免 mapper 返回可变列表成为删除期间的隐式目标输入。
     */
    private static List<String> immutableIds(List<String> ids) {
        if (ids == null || ids.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "temporary side chat purge returned invalid identity");
        }
        return List.copyOf(ids);
    }

    /**
     * Thread identity 由领域端口复用的窄格式，避免 SQL 递归入口接受空或控制字符。
     */
    private static String requireThreadId(String value) {
        if (value == null || !value.startsWith("thr_") || value.length() > 128
            || !value.substring("thr_".length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid side chat thread id");
        }
        return value;
    }
}
