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
     * 在既有 Workspace 行内 CAS 修改根、名称和信任，不替换主键或历史外键。
     */
    int compareAndSetWorkspaceUpdate(PersistenceRecords.WorkspaceUpdateCas values);

    /**
     * 返回最多三类 Workspace 持久阻塞事实及总数；查询只读标题与状态，不读取正文。
     */
    List<PersistenceRecords.WorkspaceBusyBlockerRow> selectWorkspaceBusyBlockers(
            @Param("workspaceId") String workspaceId);

    /**
     * 仅在 revision 匹配且无 Thread 引用时删除 Workspace 注册。
     */
    int deleteWorkspace(PersistenceRecords.WorkspaceDelete values);

    /**
     * 按稳定 Thread 身份读取元数据与当前 revision。
     */
    PersistenceRecords.ThreadRow selectThread(@Param("threadId") String threadId);

    /**
     * 排除被删除和临时侧聊，公开用户输入按时间与身份稳定倒序分页。
     */
    List<PersistenceRecords.InputHistoryRow> selectInputHistoryPage(PersistenceRecords.InputHistoryPage query);

    /**
     * 精确命中当前 Thread 的当前路径条目，避免遍历历史反向解析公开身份。
     */
    PersistenceRecords.ItemContentReferenceRow selectItemContentReference(
            @Param("threadId") String threadId,
            @Param("itemId") String itemId);

    /**
     * 插入初始 Thread，外键保证其 Workspace 已存在。
     */
    int insertThread(PersistenceRecords.ThreadInsert values);

    /**
     * 按更新时间和身份键执行稳定 Thread keyset 分页。
     */
    List<PersistenceRecords.ThreadRow> selectThreadPage(PersistenceRecords.ThreadPage values);

    /**
     * 按 Workspace kind 聚合主会话列表；SQL 仍执行完整摘要映射与稳定 keyset。
     */
    List<PersistenceRecords.ThreadRow> selectSessionThreadPage(PersistenceRecords.SessionThreadPage values);

    /**
     * 在一个 Workspace 内执行 bounded normalized contains 搜索，排序与普通列表一致。
     */
    List<PersistenceRecords.ThreadRow> searchThreadPage(PersistenceRecords.ThreadSearch values);

    /**
     * 只在 SESSION Workspace 上执行标题 contains 搜索。
     */
    List<PersistenceRecords.ThreadRow> searchSessionThreadPage(PersistenceRecords.SessionThreadSearch values);

    /**
     * 标题字段按调用方观察版本 CAS；Store 事务参与者统一推进可见 Thread revision，
     * 自动标题仍以 revision 下界和 placeholder 来源作为所有权门。
     */
    int compareAndSetThreadTitle(PersistenceRecords.ThreadTitleCas values);

    /**
     * 以调用方 revision CAS 替换下一轮偏好；revision 由同事务的 AgentStore 参与者推进。
     */
    int compareAndSetThreadPreferences(PersistenceRecords.ThreadPreferencesCas values);

    /**
     * 读取当前路径最后一个直接 USER 输入，续答准入仍在事务中重验失败终态。
     */
    String selectLastCurrentPathQuestionMessageId(@Param("threadId") String threadId);

    /**
     * 仅接受 current path 最后一个 USER 输入，且其最近一次关联尝试失败/取消、没有成功答复。
     */
    boolean isReaskableQuestion(@Param("threadId") String threadId,
                                @Param("sourceMessageId") String sourceMessageId);

    /**
     * reask 以 source 所属 Turn 为切点，将该 Turn 与全部后缀原子移出当前路径。
     */
    int cutCurrentPathFromQuestion(@Param("threadId") String threadId,
                                   @Param("sourceMessageId") String sourceMessageId);

    /**
     * 以调用方 revision CAS 更新 active Thread 的 pinned_at；不在 Ja SQL 内独立推进版本。
     */
    int compareAndSetThreadPin(PersistenceRecords.ThreadPinCas values);

    /**
     * 以调用方 revision CAS 仅确认当前最新 COMPLETED/FAILED Turn；版本由 AgentStore 参与者同步推进。
     */
    int compareAndSetThreadSeen(PersistenceRecords.ThreadSeenCas values);

    /**
     * 以调用方 revision CAS 更新归档或删除标记；竞争失败返回零行，版本由 AgentStore 参与者推进。
     */
    int updateThreadLifecycle(PersistenceRecords.ThreadLifecycle values);

    /**
     * 以调用方 revision CAS 恢复归档 Thread，并强制保持未置顶；版本由 AgentStore 参与者推进。
     */
    int restoreThread(PersistenceRecords.ThreadRestore values);

    /**
     * 统计非终态 Turn，作为 Thread 生命周期变更的关闭门。
     */
    int countActiveTurns(@Param("threadId") String threadId);

    /**
     * 按提交时间与条目身份混合读取消息、Tool 和审批快照。
     */
    List<PersistenceRecords.SnapshotItemRow> selectSnapshotItems(PersistenceRecords.SnapshotPage values);

    /** 使用 Core Turn 与消息 ordinal 分页冻结 Fork 前缀，避免时间戳或 item ID 改变已结算边界。 */
    List<PersistenceRecords.SnapshotItemRow> selectForkSnapshotItems(PersistenceRecords.ForkSnapshotPage values);

    /**
     * 以同一复合排序键反向读取最近的有界页面，供长会话直接从末端恢复。
     */
    List<PersistenceRecords.SnapshotItemRow> selectSnapshotItemsLatest(PersistenceRecords.SnapshotPage values);

    /** 通过已公开 item 身份读取单个当前路径事实，用于在 Thread 忙碌时精确校验可分叉回复。 */
    PersistenceRecords.SnapshotItemRow selectSnapshotItem(@Param("threadId") String threadId,
                                                            @Param("itemId") String itemId);

    /**
     * 只读取被隐藏续答取代的 Turn 身份，避免快照页物化整个 Thread 的消息正文。
     */
    List<String> selectSupersededErrorTurnIds(@Param("threadId") String threadId);

    /**
     * 读取 Thread 最近一次已提交 Provider Usage，供重启后恢复上下文指示器。
     */
    PersistenceRecords.ContextUsageRow selectLatestContextUsage(@Param("threadId") String threadId);

    /**
     * 以完整执行身份和 Ja Turn 投影版本读取同一提交的权威游标，供活动流基线校验。
     */
    String selectExecutionCursor(@Param("tenantId") String tenantId,
                                 @Param("threadId") String threadId,
                                 @Param("turnId") String turnId,
                                 @Param("userId") String userId,
                                 @Param("mutationVersion") long mutationVersion,
                                 @Param("state") String state,
                                 @Param("schemaVersion") int schemaVersion);

    /**
     * 聚合当前 Thread 的助手与摘要请求，不扫描 Timeline 或跨 Thread 读取。
     */
    PersistenceRecords.ThreadUsageSummaryRow selectThreadUsageSummary(@Param("threadId") String threadId);

    /**
     * 原子插入唯一 Turn change set 及可选 diff artifact。
     */
    int insertChangeSet(PersistenceRecords.ChangeSetInsert values);

    /**
     * 在 change set 事务内保存已经校验的 UTF-8 diff。
     */
    int insertChangeSetArtifact(PersistenceRecords.ChangeSetInsert values);

    /**
     * 每次通过 thread+turn+artifact 三元身份读取冻结 diff 正文，不复用应用层正文缓存。
     */
    PersistenceRecords.ChangeSetArtifactRow selectChangeSetArtifact(PersistenceRecords.ChangeSetArtifactKey values);
}
