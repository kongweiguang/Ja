// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepository;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepositoryException;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SideChatMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 临时侧聊状态的持久化适配器；标记和闸门不与普通 Task Mailbox SQL 混用。
 */
public final class SideChatPersistence implements AutoCloseable {
    private final MybatisUnitOfWork transactions;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 生产路径复用官方 MyBatis-Solon transaction owner，不能在此自行提交 datasource。
     */
    public SideChatPersistence(SqlSessionFactory sessions) {
        this.transactions = new MybatisUnitOfWork(sessions);
    }

    /**
     * 聚焦 SQLite 测试替换 transaction owner，但仍执行和生产相同的 Mapper SQL。
     */
    public SideChatPersistence(SqlSessionFactory sessions, MybatisUnitOfWork.SessionOwner owner) {
        this.transactions = new MybatisUnitOfWork(sessions, owner);
    }

    /**
     * 复用调用方事务读取临时标记，避免 Task Repository 另开 session 破坏关闭准入原子性。
     */
    public static List<TaskRepository.TemporarySideChat> listMarkers(PersistenceMappers mappers) {
        return mapper(mappers).selectMarkers().stream().map(SideChatPersistence::temporarySideChat).toList();
    }

    /**
     * 同事务优先回放已清理回执的持久 owner；新关闭从 lineage 冻结 owner，确保通知路由可存活。
     */
    public static TaskModels.SideChatCloseState beginClose(PersistenceMappers mappers, String threadId) {
        String id = requireThreadId(threadId);
        SideChatMapper mapper = mapper(mappers);
        Optional<TaskModels.SideChatCloseReceipt> receipt = SideChatPurger.readCloseReceipt(mappers, id);
        if (receipt.isPresent()) {
            if (mappers.tasks().selectParent(id) != null || mapper.selectMarker(id) != null) {
                throw new StorageException(StorageException.Code.INVALID_STATE,
                        "closed side chat receipt still has a live Thread");
            }
            TaskModels.SideChatCloseReceipt closed = receipt.orElseThrow();
            return new TaskModels.SideChatCloseState(id, closed.ownerRootThreadId(),
                    TaskModels.SideChatClosePhase.CLOSED, closed.closedThreadIds());
        }
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
            if (owner != null) {
                throw new TaskRepositoryException(TaskRepositoryException.Code.RELATION_INVALID,
                    "persistent tasks must use their explicit deletion workflow");
            }
            throw new TaskRepositoryException(TaskRepositoryException.Code.RELATION_INVALID,
                    "side chat does not exist");
        }
        String ownerRootThreadId = requireThreadId(owner.rootThreadId());
        requireIndependentSideChat(mappers, id);
        List<String> subtree = mapper.selectSubtreeThreadIds(id).stream().sorted().toList();
        if (subtree.isEmpty()) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "temporary side chat marker has no Thread");
        }
        mapper.markSubtreeClosing(id);
        return new TaskModels.SideChatCloseState(id, ownerRootThreadId,
                TaskModels.SideChatClosePhase.CLOSING, subtree);
    }

    /**
     * 在已存在的 admission transaction 中登记 OPEN 标记，失败会和 Thread 图一起回滚。
     */
    public static void insertOpen(PersistenceMappers mappers, String threadId) {
        int changed = mapper(mappers).insertOpen(requireThreadId(threadId));
        if (changed != 1) {
            throw new StorageException(StorageException.Code.CAS_CONFLICT,
                    "temporary side chat marker insert lost");
        }
    }

    /**
     * 供 Task admission 选择稳定的领域错误，关闭祖先也会阻断新建后代。
     */
    public static void requireTaskAdmissionOpen(PersistenceMappers mappers, String threadId) {
        if (isClosing(mappers, threadId)) {
            throw new io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepositoryException(
                    io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepositoryException.Code.CAS_CONFLICT,
                    "side chat is closing");
        }
    }

    /**
     * 供 Conversation admission 选择存储端口错误，避免关闭窗口写入新的用户或续跑 Turn。
     */
    public static void requireConversationAdmissionOpen(PersistenceMappers mappers, String threadId) {
        if (isClosing(mappers, threadId)) {
            throw new StorageException(StorageException.Code.INVALID_STATE, "side chat is closing");
        }
    }

    /**
     * 供 server 协调和其它 admission 端口读取同一持久关闭闸门。
     */
    public static boolean isClosing(PersistenceMappers mappers, String threadId) {
        String id = requireThreadId(threadId);
        return mapper(mappers).selectClosingOwner(id) != null;
    }

    /**
     * 关闭根必须仍是独立 SIDE_TASK；marker 可能因关闭子树而落在 SUBAGENT 上，不能据此越权。
     */
    private static void requireIndependentSideChat(PersistenceMappers mappers, String threadId) {
        TaskRecords.TaskSummaryRow task = Objects.requireNonNull(mappers, "mappers").tasks()
                .selectTaskSummary(threadId);
        if (task == null || !"SIDE_TASK".equals(task.taskKind())
            || !"INDEPENDENT".equals(task.lifecycle())) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.RELATION_INVALID,
                    "only independent side chats can be closed");
        }
    }

    /**
     * 允许 marker row 只在此处转换为端口对外的稳定状态闭集。
     */
    private static TaskRepository.TemporarySideChat temporarySideChat(TaskRecords.SideChatMarkerRow row) {
        try {
            return new TaskRepository.TemporarySideChat(row.threadId(),
                    TaskRepository.TemporarySideChatState.valueOf(row.state()));
        } catch (RuntimeException failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "temporary side chat marker is invalid", failure);
        }
    }

    /**
     * Mapper 未注册时失败关闭，避免测试或生产退化成没有临时生命周期的假实现。
     */
    private static SideChatMapper mapper(PersistenceMappers mappers) {
        SideChatMapper mapper = Objects.requireNonNull(mappers, "mappers").sideChats();
        if (mapper == null) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "temporary side chat mapper is unavailable");
        }
        return mapper;
    }

    /**
     * 所有临时侧聊身份都沿用 Thread 的窄格式，防止 SQL 边界接收空或 NUL。
     */
    private static String requireThreadId(String value) {
        if (value == null || !value.startsWith("thr_") || value.length() > 128
            || !value.substring("thr_".length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid side chat thread id");
        }
        return value;
    }

    /**
     * 返回所有临时标记快照；调用方据此协调取消，不由本适配器执行外部副作用。
     */
    public List<TaskRepository.TemporarySideChat> listTemporarySideChats() {
        ensureOpen();
        return transactions.required(SideChatPersistence::listMarkers);
    }

    /**
     * 新进程关闭残留 marker 时沿用 lineage owner 并写入范围回执，使重试继续路由到同一存活根。
     */
    public void purgeRecoveredSideChats() {
        ensureOpen();
        transactions.required(mappers -> {
            List<TaskRepository.TemporarySideChat> markers = listMarkers(mappers);
            for (TaskRepository.TemporarySideChat marker : markers) {
                if (mapper(mappers).selectMarker(marker.threadId()) == null) {
                    continue;
                }
                TaskModels.SideChatCloseState close = beginClose(mappers, marker.threadId());
                if (close.phase() == TaskModels.SideChatClosePhase.CLOSING) {
                    SideChatPurger.purge(mappers, marker.threadId(), close.ownerRootThreadId(),
                            close.threadIds(), false);
                }
            }
            return null;
        });
    }

    /**
     * 关闭仓储只阻止迟到 SQL，不拥有组合根的 datasource 或文件 lease。
     */
    @Override
    public void close() {
        closed.set(true);
    }

    /**
     * 组合关闭后所有调用稳定拒绝，避免外部协调器持有已关闭适配器。
     */
    private void ensureOpen() {
        if (closed.get()) {
            throw new StorageException(StorageException.Code.CLOSED, "side chat persistence is closed");
        }
    }

}
