// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.cursor.Cursor;

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

    /** 排除被删除和临时侧聊，公开用户输入按时间与身份稳定倒序分页。 */
    List<PersistenceRecords.InputHistoryRow> selectInputHistoryPage(PersistenceRecords.InputHistoryPage query);

    /** 只允许当前路径的 Assistant 正文或公开思考摘要进入按页读取，拒绝其它角色与旧尝试。 */
    PersistenceRecords.PublicContentPageRow selectPublicContentPage(
            @Param("threadId") String threadId,
            @Param("messageId") String messageId,
            @Param("offsetCharacters") int offsetCharacters,
            @Param("limitCharacters") int limitCharacters);

    /** 仅流式遍历当前路径公开文本身份，供哈希 ItemId 解析，不读取任意正文。 */
    Cursor<String> streamPublicContentIds(@Param("threadId") String threadId);

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

    /** 按 Workspace kind 聚合主会话列表；SQL 仍执行完整摘要映射与稳定 keyset。 */
    List<PersistenceRecords.ThreadRow> selectSessionThreadPage(PersistenceRecords.SessionThreadPage values);

    /** 在一个 Workspace 内执行 bounded normalized contains 搜索，排序与普通列表一致。 */
    List<PersistenceRecords.ThreadRow> searchThreadPage(PersistenceRecords.ThreadSearch values);

    /** 只在 SESSION Workspace 上执行标题 contains 搜索。 */
    List<PersistenceRecords.ThreadRow> searchSessionThreadPage(PersistenceRecords.SessionThreadSearch values);

    /**
     * 人工标题使用精确 revision CAS；自动标题只要求 revision 未回退且来源仍为 placeholder，
     * 避免后续 Turn 的正常 revision 推进永久饿死首次标题，同时保留人工标题的唯一所有权门。
     */
    int compareAndSetThreadTitle(PersistenceRecords.ThreadTitleCas values);

    /** 以 revision 原子替换下一轮偏好，不触碰已接纳 Turn。 */
    int compareAndSetThreadPreferences(PersistenceRecords.ThreadPreferencesCas values);

    /** admission 以单次 CAS 推进 revision，并在首次占有 PLACEHOLDER 时原子写入临时标题。 */
    int compareAndSetThreadAdmission(PersistenceRecords.ThreadAdmissionCas values);

    /** 读取当前路径最后一个直接 USER 输入，续答准入仍在事务中重验失败终态。 */
    String selectLastCurrentPathQuestionMessageId(@Param("threadId") String threadId);

    /** 识别旧按钮连续创建的 USER“继续”尾链；仅返回候选，完整迁移门仍由同一 admission 事务验证。 */
    String selectLegacyContinueCandidateSource(@Param("threadId") String threadId,
                                               @Param("candidateMessageId") String candidateMessageId);

    /** 对完整四 Turn 只读 Tool 旧链执行 strict gate，其他相似文本或含副作用事实一律不匹配。 */
    boolean isStrictLegacyContinueChain(@Param("threadId") String threadId,
                                       @Param("candidateMessageId") String candidateMessageId,
                                       @Param("sourceMessageId") String sourceMessageId);

    /** 在准入事务内把三条旧 USER 尝试原子移出路径并绑定原问题，返回实际变更行数。 */
    int normalizeLegacyContinuePath(@Param("threadId") String threadId,
                                    @Param("candidateMessageId") String candidateMessageId,
                                    @Param("sourceMessageId") String sourceMessageId);

    /** 仅接受 current path 最后一个 USER 输入，且其最近一次关联尝试失败/取消、没有成功答复。 */
    boolean isReaskableQuestion(@Param("threadId") String threadId,
                                @Param("sourceMessageId") String sourceMessageId);

    /** reask 以 source 所属 Turn 为切点，将该 Turn 与全部后缀原子移出当前路径。 */
    int cutCurrentPathFromQuestion(@Param("threadId") String threadId,
                                   @Param("sourceMessageId") String sourceMessageId);

    /** 以 revision CAS 更新 active Thread 的 pinned_at。 */
    int compareAndSetThreadPin(PersistenceRecords.ThreadPinCas values);

    /**
     * 以 revision CAS 仅确认当前最新 COMPLETED/FAILED Turn，竞争或无可确认事实均返回零行。
     */
    int compareAndSetThreadSeen(PersistenceRecords.ThreadSeenCas values);

    /**
     * 以 revision 更新归档或删除标记，竞争失败返回零行。
     */
    int updateThreadLifecycle(PersistenceRecords.ThreadLifecycle values);

    /** 以 revision CAS 恢复归档 Thread，并强制保持未置顶。 */
    int restoreThread(PersistenceRecords.ThreadRestore values);

    /**
     * 统计非终态 Turn，作为 Thread 生命周期变更的关闭门。
     */
    int countActiveTurns(@Param("threadId") String threadId);

    /**
     * 按提交时间与条目身份混合读取消息、Tool 和审批快照。
     */
    List<PersistenceRecords.SnapshotItemRow> selectSnapshotItems(PersistenceRecords.SnapshotPage values);

    /** 以同一复合排序键反向读取最近的有界页面，供长会话直接从末端恢复。 */
    List<PersistenceRecords.SnapshotItemRow> selectSnapshotItemsLatest(PersistenceRecords.SnapshotPage values);

    /** 只读取被隐藏续答取代的 Turn 身份，避免快照页物化整个 Thread 的消息正文。 */
    List<String> selectSupersededErrorTurnIds(@Param("threadId") String threadId);

    /** 读取 Thread 最近一次已提交 Provider Usage，供重启后恢复上下文指示器。 */
    PersistenceRecords.ContextUsageRow selectLatestContextUsage(@Param("threadId") String threadId);

    /** 聚合当前 Thread 的助手与摘要请求，不扫描 Timeline 或跨 Thread 读取。 */
    PersistenceRecords.ThreadUsageSummaryRow selectThreadUsageSummary(@Param("threadId") String threadId);

    /** 原子插入唯一 Turn change set 及可选 diff artifact。 */
    int insertChangeSet(PersistenceRecords.ChangeSetInsert values);

    /** 在 change set 事务内保存已经校验的 UTF-8 diff。 */
    int insertChangeSetArtifact(PersistenceRecords.ChangeSetInsert values);

    /** 每次通过 thread+turn+artifact 三元身份读取冻结 diff 正文，不复用应用层正文缓存。 */
    PersistenceRecords.ChangeSetArtifactRow selectChangeSetArtifact(PersistenceRecords.ChangeSetArtifactKey values);
}
