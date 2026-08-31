// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Workspace/Thread 元数据、分页与 lifecycle CAS Mapper。
 */
@Mapper
public interface HistoryMapper {
    /**
     * 按稳定 Workspace 身份读取权威注册记录。
     */
    PersistenceRecords.WorkspaceRow selectWorkspace(@Param("workspaceId") String workspaceId);

    /**
     * 按 canonical root 读取注册记录，用于强制根目录唯一绑定。
     */
    PersistenceRecords.WorkspaceRow selectWorkspaceByRoot(@Param("rootPath") String rootPath);

    /**
     * 插入新的 Workspace 身份绑定，数据库唯一约束作为最终竞争门。
     */
    int insertWorkspace(PersistenceRecords.WorkspaceInsert values);

    /**
     * 按更新时间和身份键执行稳定 Workspace keyset 分页。
     */
    List<PersistenceRecords.WorkspaceRow> selectWorkspacePage(PersistenceRecords.WorkspacePage values);

    /**
     * 以 revision 比较并更新 Workspace trust，竞争失败返回零行。
     */
    int compareAndSetWorkspaceTrust(PersistenceRecords.WorkspaceTrustCas values);

    /**
     * 仅在 revision 匹配且无 Thread 引用时删除 Workspace 注册。
     */
    int deleteWorkspace(PersistenceRecords.WorkspaceDelete values);

    /**
     * 按稳定 Thread 身份读取元数据与当前 revision。
     */
    PersistenceRecords.ThreadRow selectThread(@Param("threadId") String threadId);

    /**
     * 插入初始 Thread，外键保证其 Workspace 已存在。
     */
    int insertThread(PersistenceRecords.ThreadInsert values);

    /**
     * 以 revision 比较并推进面向外部请求的 Thread 版本。
     */
    int compareAndSetThread(PersistenceRecords.ThreadRevisionCas values);

    /**
     * 原子推进并返回 Thread revision，供内部事实提交分配可观察版本。
     */
    Long allocateThreadRevision(PersistenceRecords.ThreadRevision values);

    /**
     * 无 CAS 地推进已持有写锁的 Thread 事实版本，仅限受控事务内部使用。
     */
    int advanceThreadFact(PersistenceRecords.ThreadRevision values);

    /**
     * 按更新时间和身份键执行稳定 Thread keyset 分页。
     */
    List<PersistenceRecords.ThreadRow> selectThreadPage(PersistenceRecords.ThreadPage values);

    /** 在一个 Workspace 内执行 bounded normalized contains 搜索，排序与普通列表一致。 */
    List<PersistenceRecords.ThreadRow> searchThreadPage(PersistenceRecords.ThreadSearch values);

    /**
     * 人工标题使用精确 revision CAS；自动标题只要求 revision 未回退且来源仍为 placeholder，
     * 避免后续 Turn 的正常 revision 推进永久饿死首次标题，同时保留人工标题的唯一所有权门。
     */
    int compareAndSetThreadTitle(PersistenceRecords.ThreadTitleCas values);

    /** 以 revision 原子替换下一轮偏好，不触碰已接纳 Turn。 */
    int compareAndSetThreadPreferences(PersistenceRecords.ThreadPreferencesCas values);

    /** admission 以单次 CAS 推进 revision，并在首次占有 PLACEHOLDER 时原子写入临时标题。 */
    int compareAndSetThreadAdmission(PersistenceRecords.ThreadAdmissionCas values);

    /**
     * 以 revision 更新归档或删除标记，竞争失败返回零行。
     */
    int updateThreadLifecycle(PersistenceRecords.ThreadLifecycle values);

    /**
     * 统计非终态 Turn，作为 Thread 生命周期变更的关闭门。
     */
    int countActiveTurns(@Param("threadId") String threadId);

    /**
     * 按提交时间与条目身份混合读取消息、Tool 和审批快照。
     */
    List<PersistenceRecords.SnapshotItemRow> selectSnapshotItems(PersistenceRecords.SnapshotPage values);

    /** 读取 Thread 最近一次已提交 Provider Usage，供重启后恢复上下文指示器。 */
    PersistenceRecords.ContextUsageRow selectLatestContextUsage(@Param("threadId") String threadId);

    /** 原子插入唯一 Turn change set 及可选 diff artifact。 */
    int insertChangeSet(PersistenceRecords.ChangeSetInsert values);

    /** 在 change set 事务内保存已经校验的 UTF-8 diff。 */
    int insertChangeSetArtifact(PersistenceRecords.ChangeSetInsert values);

    /** 通过 thread+turn+artifact 三元身份读取冻结 diff。 */
    PersistenceRecords.ChangeSetArtifactRow selectChangeSetArtifact(PersistenceRecords.ChangeSetArtifactKey values);
}
