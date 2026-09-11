// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SideChatMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import io.github.kongweiguang.ja.task.port.out.TaskRepository;
import io.github.kongweiguang.ja.task.port.out.TaskRepositoryException;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.List;
import java.util.Objects;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/** 临时侧聊状态的持久化适配器；标记和闸门不与普通 Task Mailbox SQL 混用。 */
public final class SideChatPersistence implements AutoCloseable {
    private final MybatisUnitOfWork transactions;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 生产路径复用官方 MyBatis-Solon transaction owner，不能在此自行提交 datasource。 */
    public SideChatPersistence(SqlSessionFactory sessions) {
        this.transactions = new MybatisUnitOfWork(sessions);
    }

    /** 聚焦 SQLite 测试替换 transaction owner，但仍执行和生产相同的 Mapper SQL。 */
    public SideChatPersistence(SqlSessionFactory sessions, MybatisUnitOfWork.SessionOwner owner) {
        this.transactions = new MybatisUnitOfWork(sessions, owner);
    }

    /** 返回所有临时标记快照；调用方据此协调取消，不由本适配器执行外部副作用。 */
    public List<TaskRepository.TemporarySideChat> listTemporarySideChats() {
        ensureOpen();
        return transactions.required(SideChatPersistence::listMarkers);
    }

    /** 复用调用方事务读取临时标记，避免 Task Repository 另开 session 破坏关闭准入原子性。 */
    public static List<TaskRepository.TemporarySideChat> listMarkers(PersistenceMappers mappers) {
        return mapper(mappers).selectMarkers().stream().map(SideChatPersistence::temporarySideChat).toList();
    }

    /**
     * 原子切换目标及其已存在后代的关闭闸门，并返回冻结的 Thread identity 列表；
     * CLOSING 重试只回读同一棵树，防止协调器重复创建取消工作。
     */
    public List<String> beginSideChatClose(String threadId) {
        ensureOpen();
        return transactions.required(mappers -> beginClose(mappers, threadId));
    }

    /** 身份验证和关闭标记必须在同一 writer transaction 内完成，禁止先取消再抢准入。 */
    public static List<String> beginClose(PersistenceMappers mappers, String threadId) {
        String id = requireThreadId(threadId);
            SideChatMapper mapper = mapper(mappers);
            TaskRecords.ParentRow owner = mappers.tasks().selectParent(id);
            TaskRecords.SideChatMarkerRow marker = mapper.selectMarker(id);
            if (owner != null && owner.depth() == null) {
                throw new TaskRepositoryException(TaskRepositoryException.Code.RELATION_INVALID,
                        "only independent side chats can be closed");
            }
            if (owner == null && marker != null) {
                throw new StorageException(StorageException.Code.INVALID_STATE,
                        "temporary side chat marker has no visible Thread");
            }
            if (marker == null) {
                if (owner != null) throw new TaskRepositoryException(TaskRepositoryException.Code.RELATION_INVALID,
                        "persistent tasks must use their explicit deletion workflow");
                return List.of();
            }
            requireIndependentSideChat(mappers, id);
            List<String> subtree = List.copyOf(mapper.selectSubtreeThreadIds(id));
            if (subtree.isEmpty()) {
                throw new StorageException(StorageException.Code.INVALID_STATE,
                        "temporary side chat marker has no Thread");
            }
            mapper.markSubtreeClosing(id);
            return subtree;
    }

    /** 新进程在普通恢复之前销毁明确标记的临时工作，不重放旧 Provider、工具或续跑副作用。 */
    public void purgeRecoveredSideChats() {
        ensureOpen();
        transactions.required(mappers -> {
            List<TaskRepository.TemporarySideChat> markers = listMarkers(mappers);
            for (TaskRepository.TemporarySideChat marker : markers) {
                if (mapper(mappers).selectMarker(marker.threadId()) == null) continue;
                beginClose(mappers, marker.threadId());
                SideChatPurger.purge(mappers, marker.threadId(), false);
            }
            return null;
        });
    }

    /** 启动恢复前仅移除标记孤儿；旧侧聊没有标记时绝不能被批量猜测或删除。 */
    public int deleteOrphanTemporarySideChatMarkers() {
        ensureOpen();
        return transactions.required(mapper -> mapper(mapper).deleteOrphanMarkers());
    }

    /**
     * 关闭协调确认整棵树没有活动 Turn 后，原子清理临时 Task 投影和 marker；历史 Thread/消息仍按
     * 既有软删除语义保留，防止关闭侧聊误删用户已经产生的文件修改或通信审计事实。
     */
    public int deleteClosedSideChat(String threadId) {
        ensureOpen();
        String id = requireThreadId(threadId);
        return transactions.required(mappers -> {
            SideChatMapper sideChat = mapper(mappers);
            TaskRecords.SideChatMarkerRow marker = sideChat.selectMarker(id);
            if (marker == null) return 0;
            if (!"CLOSING".equals(marker.state())) {
                throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                        "temporary side chat is not closing");
            }
            requireIndependentSideChat(mappers, id);
            TaskRecords.TaskSummaryRow summary = mappers.tasks().selectTaskSummary(id);
            if (summary == null) {
                throw new StorageException(StorageException.Code.INVALID_STATE,
                        "temporary side chat task projection is unavailable");
            }
            if (mappers.tasks().countNonTerminalTurnsInTree(id) != 0) {
                throw new TaskRepositoryException(TaskRepositoryException.Code.TREE_DELETE_REQUIRED,
                        "temporary side chat still has non-terminal Turns");
            }
            int count = mappers.tasks().countTaskSubtree(id);
            if (count < 1) {
                throw new StorageException(StorageException.Code.INVALID_STATE,
                        "temporary side chat subtree is unavailable");
            }
            List<String> seedIds = mappers.tasks().selectTreeSeedIds(id);
            String occurredAt = Instant.now().toString();
            TaskRecords.TreeDelete deletion = new TaskRecords.TreeDelete(id, summary.revision(), occurredAt);
            if (mappers.tasks().countDeleteTarget(deletion) != 1
                    || mappers.tasks().softDeleteTaskThreads(deletion) < 1) {
                throw new TaskRepositoryException(TaskRepositoryException.Code.CAS_CONFLICT,
                        "temporary side chat changed during close");
            }
            mappers.tasks().deleteTreeWriteClaims(id);
            mappers.tasks().deleteTreeMailbox(id);
            mappers.tasks().deleteTreeProjections(id);
            mappers.tasks().deleteTreeActivities(id);
            mappers.tasks().deleteTreeLineage(id);
            if (!seedIds.isEmpty()) mappers.tasks().deleteContextSeeds(seedIds);
            mappers.tasks().recomputeDescendantCounts(summary.rootThreadId(), occurredAt);
            if (sideChat.deleteClosedMarker(id) != 1) {
                throw new TaskRepositoryException(TaskRepositoryException.Code.CAS_CONFLICT,
                        "temporary side chat marker changed during close");
            }
            return count;
        });
    }

    /** 在已存在的 admission transaction 中登记 OPEN 标记，失败会和 Thread 图一起回滚。 */
    public static void insertOpen(PersistenceMappers mappers, String threadId) {
        int changed = mapper(mappers).insertOpen(requireThreadId(threadId));
        if (changed != 1) {
            throw new StorageException(StorageException.Code.CAS_CONFLICT,
                    "temporary side chat marker insert lost");
        }
    }

    /** 供 Task admission 选择稳定的领域错误，关闭祖先也会阻断新建后代。 */
    public static void requireTaskAdmissionOpen(PersistenceMappers mappers, String threadId) {
        if (isClosing(mappers, threadId)) {
            throw new io.github.kongweiguang.ja.task.port.out.TaskRepositoryException(
                    io.github.kongweiguang.ja.task.port.out.TaskRepositoryException.Code.CAS_CONFLICT,
                    "side chat is closing");
        }
    }

    /** 供 Conversation admission 选择存储端口错误，避免关闭窗口写入新的用户或续跑 Turn。 */
    public static void requireConversationAdmissionOpen(PersistenceMappers mappers, String threadId) {
        if (isClosing(mappers, threadId)) {
            throw new StorageException(StorageException.Code.INVALID_STATE, "side chat is closing");
        }
    }

    /** 供 server 协调和其它 admission 端口读取同一持久关闭闸门。 */
    public static boolean isClosing(PersistenceMappers mappers, String threadId) {
        String id = requireThreadId(threadId);
        return mapper(mappers).selectClosingOwner(id) != null;
    }

    /** 关闭根必须仍是独立 SIDE_TASK；marker 可能因关闭子树而落在 SUBAGENT 上，不能据此越权。 */
    private static void requireIndependentSideChat(PersistenceMappers mappers, String threadId) {
        TaskRecords.TaskSummaryRow task = Objects.requireNonNull(mappers, "mappers").tasks()
                .selectTaskSummary(threadId);
        if (task == null || !"SIDE_TASK".equals(task.taskKind())
                || !"INDEPENDENT".equals(task.lifecycle())) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.RELATION_INVALID,
                    "only independent side chats can be closed");
        }
    }

    /** 关闭仓储只阻止迟到 SQL，不拥有组合根的 datasource 或文件 lease。 */
    @Override
    public void close() {
        closed.set(true);
    }

    /** 允许 marker row 只在此处转换为端口对外的稳定状态闭集。 */
    private static TaskRepository.TemporarySideChat temporarySideChat(TaskRecords.SideChatMarkerRow row) {
        try {
            return new TaskRepository.TemporarySideChat(row.threadId(),
                    TaskRepository.TemporarySideChatState.valueOf(row.state()));
        } catch (RuntimeException failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "temporary side chat marker is invalid", failure);
        }
    }

    /** Mapper 未注册时失败关闭，避免测试或生产退化成没有临时生命周期的假实现。 */
    private static SideChatMapper mapper(PersistenceMappers mappers) {
        SideChatMapper mapper = Objects.requireNonNull(mappers, "mappers").sideChats();
        if (mapper == null) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "temporary side chat mapper is unavailable");
        }
        return mapper;
    }

    /** 所有临时侧聊身份都沿用 Thread 的窄格式，防止 SQL 边界接收空或 NUL。 */
    private static String requireThreadId(String value) {
        if (value == null || !value.startsWith("thr_") || value.length() > 128
                || !value.substring("thr_".length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid side chat thread id");
        }
        return value;
    }

    /** 组合关闭后所有调用稳定拒绝，避免外部协调器持有已关闭适配器。 */
    private void ensureOpen() {
        if (closed.get()) {
            throw new StorageException(StorageException.Code.CLOSED, "side chat persistence is closed");
        }
    }

}
