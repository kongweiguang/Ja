// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

/**
 * TaskMapper 的显式行与参数闭集，避免领域端口依赖 MyBatis 构造规则。
 */
public final class TaskRecords {
    /**
     * 纯 SQL 形状容器禁止实例化。
     */
    private TaskRecords() {
    }

    /**
     * 父 Thread 与可选父 Task lineage 的单快照准入行。
     *
     * @param threadId 所属对话的稳定标识
     * @param ownerUserId 创建后不可更换的 Thread owner
     * @param workspaceId 工作区稳定标识
     * @param accessMode 访问模式
     * @param revision 当前修订版本
     * @param rootThreadId 任务树根对话标识
     * @param depth 深度
     */
    public record ParentRow(String threadId,
                            String ownerUserId,
                            String workspaceId,
                            String accessMode,
                            long revision,
                            String rootThreadId,
                            Integer depth) {
    }

    /**
     * Turn 的所属、因果边及 admission 时间在同一快照校验，避免 Task 重复创建 Core 已准入的行。
     *
     * @param threadId 所属对话的稳定标识
     * @param parentTurnId 父执行标识
     * @param rootTurnId 根任务执行标识
     * @param state 当前状态
     * @param cancelRequestedAt 取消请求时间
     * @param requestedAt 请求时间
     */
    public record TurnCausalityRow(String threadId,
                                   String parentTurnId,
                                   String rootTurnId,
                                   String state,
                                   String cancelRequestedAt,
                                   String requestedAt) {
    }

    /**
     * 通信路由同时冻结发送方标题，并携带目标临时侧聊的关闭闸门状态。
     *
     * @param threadId 所属对话的稳定标识
     * @param rootThreadId 任务树根对话标识
     * @param depth 深度
     * @param title 显示标题
     * @param closing 是否正在关闭
     */
    public record TaskRouteRow(String threadId,
                               String rootThreadId,
                               Integer depth,
                               String title,
                               boolean closing) {
    }

    /**
     * 临时侧聊 marker 的最小 SQL 行形状；状态闭集在 repository 端口边界再次校验。
     *
     * @param threadId 所属对话的稳定标识
     * @param state 当前状态
     */
    public record SideChatMarkerRow(String threadId,
                                    String state) {
    }

    /**
     * 成功清理后的稳定身份回执；boxed 数值匹配 MyBatis 显式构造映射，也允许解码前拒绝 null。
     *
     * @param rootThreadId 已关闭的临时侧聊根
     * @param workspaceId 回执的持久业务 owner
     * @param ownerRootThreadId 回执重放时用于通知路由的顶层会话
     * @param closedThreadCount 完整物理清理范围内的 Thread 数量
     * @param closedAtMs 关闭提交时间
     */
    public record SideChatCloseReceiptRow(String rootThreadId,
                                          String workspaceId,
                                          String ownerRootThreadId,
                                          Integer closedThreadCount,
                                          Long closedAtMs) {
    }

    /**
     * 回执范围成员保留稳定序号；boxed ordinal 与 MyBatis 构造参数一致，解码时校验非空。
     *
     * @param memberOrdinal 回执中的稳定位置
     * @param threadId 已物理清理的 Thread 标识
     */
    public record SideChatCloseMemberRow(Integer memberOrdinal,
                                         String threadId) {
    }

    /**
     * 关闭回执主行的最小 SQL 参数；时间统一以 UTC epoch milliseconds 存储。
     *
     * @param rootThreadId 关闭操作的自然身份
     * @param workspaceId 持久回执 owner
     * @param ownerRootThreadId 通知路由所需的顶层会话身份
     * @param closedThreadCount 已清理 Thread 数量
     * @param closedAtMs 关闭提交时间
     */
    public record SideChatCloseReceiptInsert(String rootThreadId,
                                             String workspaceId,
                                             String ownerRootThreadId,
                                             int closedThreadCount,
                                             long closedAtMs) {
    }

    /**
     * 一条已清理 Thread 的回执成员参数；root 与成员都只是保留的身份，不保留正文或资源。
     *
     * @param rootThreadId 关闭操作的自然身份
     * @param memberOrdinal 稳定回放顺序
     * @param threadId 已清理 Thread 标识
     */
    public record SideChatCloseMemberInsert(String rootThreadId,
                                            int memberOrdinal,
                                            String threadId) {
    }

    /**
     * 父取消事实与仍存活 ATTACHED 直接子 Turn 联结后的去重恢复行。
     *
     * @param parentThreadId 父对话标识
     * @param parentTurnId 父执行标识
     */
    public record CancellationPropagationRow(String parentThreadId,
                                             String parentTurnId) {
    }

    /**
     * 列表与详情共享的 lineage + projection 固定列集合。
     *
     * @param taskThreadId 任务对话标识
     * @param parentThreadId 父对话标识
     * @param rootThreadId 任务树根对话标识
     * @param originTurnId 原始执行标识
     * @param taskName 任务名称
     * @param depth 深度
     * @param taskKind 任务类型
     * @param lifecycle 生命周期
     * @param contextSeedId 初始上下文快照标识
     * @param createdAt 创建时间
     * @param revision 当前修订版本
     * @param state 当前状态
     * @param latestActivitySequence 最近活动序号
     * @param lastSeenActivitySequence 最近已读活动的序号
     * @param unreadCount 未读数量
     * @param descendantCount 后代任务数量
     * @param runningDescendantCount 正在运行的后代任务数
     * @param needsAttentionCount 需要处理的任务数
     * @param latestSafeSummary 最近确认可用于恢复的摘要
     * @param startedAt 开始时间
     * @param completedAt 完成时间
     * @param updatedAt 最后更新时间
     */
    public record TaskSummaryRow(String taskThreadId,
                                 String parentThreadId,
                                 String rootThreadId,
                                 String originTurnId,
                                 String taskName,
                                 int depth,
                                 String taskKind,
                                 String lifecycle,
                                 String contextSeedId,
                                 String createdAt,
                                 long revision,
                                 String state,
                                 long latestActivitySequence,
                                 Long lastSeenActivitySequence,
                                 int unreadCount,
                                 int descendantCount,
                                 int runningDescendantCount,
                                 int needsAttentionCount,
                                 String latestSafeSummary,
                                 String startedAt,
                                 String completedAt,
                                 String updatedAt) {
    }

    /**
     * 上下文种子的完整存储形状；JSON 必须由 adapter 严格解码。
     *
     * @param contextSeedId 初始上下文快照标识
     * @param parentThreadId 父对话标识
     * @param parentTurnId 父执行标识
     * @param parentRevision 父任务修订版本
     * @param inheritanceMode 继承模式
     * @param taskBriefJson 任务简述 JSON 表示
     * @param effectiveContextJson 生效上下文 JSON 表示
     * @param referencesJson 引用列表 JSON 表示
     * @param permissionCeilingJson 权限上限 JSON 表示
     * @param fingerprint 当前内容或配置的校验指纹
     * @param createdAt 创建时间
     */
    public record ContextSeedRow(String contextSeedId,
                                 String parentThreadId,
                                 String parentTurnId,
                                 long parentRevision,
                                 String inheritanceMode,
                                 String taskBriefJson,
                                 String effectiveContextJson,
                                 String referencesJson,
                                 String permissionCeilingJson,
                                 String fingerprint,
                                 String createdAt) {
    }

    /**
     * Activity 的 SQLite sequence 与安全 JSON 摘要。
     *
     * @param activitySequence 活动序号
     * @param activityId 活动标识
     * @param rootThreadId 任务树根对话标识
     * @param taskThreadId 任务对话标识
     * @param actorThreadId 发起操作的对话标识
     * @param causalTurnId 触发当前事实的执行标识
     * @param kind 类型分类
     * @param summaryJson 摘要 JSON 表示
     * @param createdAt 创建时间
     */
    public record ActivityRow(long activitySequence,
                              String activityId,
                              String rootThreadId,
                              String taskThreadId,
                              String actorThreadId,
                              String causalTurnId,
                              String kind,
                              String summaryJson,
                              String createdAt) {
    }

    /**
     * 根 Timeline 单查询保留各表独立身份，adapter 可识别 LEFT JOIN 缺失而不是静默丢活动。
     *
     * @param activitySequence 活动序号
     * @param activityId 活动标识
     * @param activityRootThreadId 活动根对话标识
     * @param activityTaskThreadId 活动任务对话标识
     * @param actorThreadId 发起操作的对话标识
     * @param causalTurnId 触发当前事实的执行标识
     * @param activityKind 活动类型
     * @param activitySummaryJson 活动摘要 JSON 表示
     * @param activityCreatedAt 活动创建时间
     * @param lineageTaskThreadId 任务来源任务对话标识
     * @param parentThreadId 父对话标识
     * @param lineageRootThreadId 任务来源根对话标识
     * @param originTurnId 原始执行标识
     * @param taskName 任务名称
     * @param depth 深度
     * @param taskKind 任务类型
     * @param lifecycle 生命周期
     * @param contextSeedId 初始上下文快照标识
     * @param lineageCreatedAt 任务来源创建时间
     * @param projectionTaskThreadId 投影任务对话标识
     * @param projectionRootThreadId 投影根对话标识
     * @param projectionRevision 投影修订版本
     * @param projectionState 投影状态
     * @param latestActivitySequence 最近活动序号
     * @param lastSeenActivitySequence 最近已读活动的序号
     * @param unreadCount 未读数量
     * @param descendantCount 后代任务数量
     * @param runningDescendantCount 正在运行的后代任务数
     * @param needsAttentionCount 需要处理的任务数
     * @param latestSafeSummary 最近确认可用于恢复的摘要
     * @param startedAt 开始时间
     * @param completedAt 完成时间
     * @param projectionUpdatedAt 投影更新时间
     * @param persistedThreadId 持久化对话标识
     * @param threadDeletedAt 对话删除时间
     */
    public record RootActivityProjectionRow(long activitySequence,
                                            String activityId,
                                            String activityRootThreadId,
                                            String activityTaskThreadId,
                                            String actorThreadId,
                                            String causalTurnId,
                                            String activityKind,
                                            String activitySummaryJson,
                                            String activityCreatedAt,
                                            String lineageTaskThreadId,
                                            String parentThreadId,
                                            String lineageRootThreadId,
                                            String originTurnId,
                                            String taskName,
                                            Integer depth,
                                            String taskKind,
                                            String lifecycle,
                                            String contextSeedId,
                                            String lineageCreatedAt,
                                            String projectionTaskThreadId,
                                            String projectionRootThreadId,
                                            Long projectionRevision,
                                            String projectionState,
                                            Long latestActivitySequence,
                                            Long lastSeenActivitySequence,
                                            Integer unreadCount,
                                            Integer descendantCount,
                                            Integer runningDescendantCount,
                                            Integer needsAttentionCount,
                                            String latestSafeSummary,
                                            String startedAt,
                                            String completedAt,
                                            String projectionUpdatedAt,
                                            String persistedThreadId,
                                            String threadDeletedAt) {
    }

    /**
     * Mailbox 的完整恢复行；senderTitle 是独立快照，sender/causal id 不依赖发送方 FK。
     *
     * @param mailboxSequence 收件箱序号
     * @param messageId 消息标识
     * @param rootThreadId 任务树根对话标识
     * @param senderThreadId 投递消息的来源对话标识
     * @param senderTitle 发送方标题
     * @param targetThreadId 投递消息的目标对话标识
     * @param causalTurnId 触发当前事实的执行标识
     * @param kind 类型分类
     * @param contentJson 内容 JSON 表示
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param state 当前状态
     * @param boundTurnId 绑定的执行标识
     * @param createdAt 创建时间
     * @param updatedAt 最后更新时间
     * @param consumedAt 已消费时间
     */
    public record MailboxRow(long mailboxSequence,
                             String messageId,
                             String rootThreadId,
                             String senderThreadId,
                             String senderTitle,
                             String targetThreadId,
                             String causalTurnId,
                             String kind,
                             String contentJson,
                             String operationKey,
                             String state,
                             String boundTurnId,
                             String createdAt,
                             String updatedAt,
                             String consumedAt) {
    }

    /**
     * 单目标 Mailbox 当前容量统计，UTF-8 bytes 由 SQLite BLOB 长度计算。
     *
     * @param pendingCount 待处理数量
     * @param pendingBytes 待处理字节数
     */
    public record MailboxStats(int pendingCount,
                               long pendingBytes) {
    }

    /**
     * Seed insert 保存 canonical JSON 和 adapter 计算的 fingerprint。
     *
     * @param contextSeedId 初始上下文快照标识
     * @param parentThreadId 父对话标识
     * @param parentTurnId 父执行标识
     * @param parentRevision 父任务修订版本
     * @param inheritanceMode 继承模式
     * @param taskBriefJson 任务简述 JSON 表示
     * @param effectiveContextJson 生效上下文 JSON 表示
     * @param referencesJson 引用列表 JSON 表示
     * @param permissionCeilingJson 权限上限 JSON 表示
     * @param fingerprint 当前内容或配置的校验指纹
     * @param createdAt 创建时间
     */
    public record ContextSeedInsert(String contextSeedId,
                                    String parentThreadId,
                                    String parentTurnId,
                                    long parentRevision,
                                    String inheritanceMode,
                                    String taskBriefJson,
                                    String effectiveContextJson,
                                    String referencesJson,
                                    String permissionCeilingJson,
                                    String fingerprint,
                                    String createdAt) {
    }

    /**
     * 创建后不可更新的 Thread lineage 参数。
     *
     * @param childThreadId 侧边或委派对话标识
     * @param parentThreadId 父对话标识
     * @param rootThreadId 任务树根对话标识
     * @param originTurnId 原始执行标识
     * @param taskName 任务名称
     * @param depth 深度
     * @param taskKind 任务类型
     * @param lifecycle 生命周期
     * @param contextSeedId 初始上下文快照标识
     * @param createdAt 创建时间
     */
    public record LineageInsert(String childThreadId,
                                String parentThreadId,
                                String rootThreadId,
                                String originTurnId,
                                String taskName,
                                int depth,
                                String taskKind,
                                String lifecycle,
                                String contextSeedId,
                                String createdAt) {
    }

    /**
     * Activity insert 由 SQLite 分配全局 sequence 并 RETURNING。
     *
     * @param activityId 活动标识
     * @param rootThreadId 任务树根对话标识
     * @param taskThreadId 任务对话标识
     * @param actorThreadId 发起操作的对话标识
     * @param causalTurnId 触发当前事实的执行标识
     * @param kind 类型分类
     * @param summaryJson 摘要 JSON 表示
     * @param createdAt 创建时间
     */
    public record ActivityInsert(String activityId,
                                 String rootThreadId,
                                 String taskThreadId,
                                 String actorThreadId,
                                 String causalTurnId,
                                 String kind,
                                 String summaryJson,
                                 String createdAt) {
    }

    /**
     * 初始 projection 使用已提交 DISPATCHED sequence，不允许临时零引用。
     *
     * @param taskThreadId 任务对话标识
     * @param rootThreadId 任务树根对话标识
     * @param state 当前状态
     * @param latestActivitySequence 最近活动序号
     * @param latestSafeSummary 最近确认可用于恢复的摘要
     * @param startedAt 开始时间
     * @param updatedAt 最后更新时间
     */
    public record ProjectionInsert(String taskThreadId,
                                   String rootThreadId,
                                   String state,
                                   long latestActivitySequence,
                                   String latestSafeSummary,
                                   String startedAt,
                                   String updatedAt) {
    }

    /**
     * Mailbox insert 使用发送方 scoped idempotency key 并返回 sequence。
     *
     * @param messageId 消息标识
     * @param rootThreadId 任务树根对话标识
     * @param senderThreadId 投递消息的来源对话标识
     * @param targetThreadId 投递消息的目标对话标识
     * @param causalTurnId 触发当前事实的执行标识
     * @param kind 类型分类
     * @param contentJson 内容 JSON 表示
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param state 当前状态
     * @param boundTurnId 绑定的执行标识
     * @param createdAt 创建时间
     * @param updatedAt 最后更新时间
     */
    public record MailboxInsert(String messageId,
                                String rootThreadId,
                                String senderThreadId,
                                String targetThreadId,
                                String causalTurnId,
                                String kind,
                                String contentJson,
                                String operationKey,
                                String state,
                                String boundTurnId,
                                String createdAt,
                                String updatedAt) {
    }

    /**
     * Follow-up 只允许将精确 PENDING 消息绑定到目标新 Turn。
     *
     * @param messageId 消息标识
     * @param targetThreadId 投递消息的目标对话标识
     * @param boundTurnId 绑定的执行标识
     * @param occurredAt 事件发生时间
     */
    public record MailboxBind(String messageId,
                              String targetThreadId,
                              String boundTurnId,
                              String occurredAt) {
    }

    /**
     * 首轮 Follow-up 在同一准入中立刻消费，回执绑定到真实 USER Turn。
     *
     * @param messageId 消息标识
     * @param targetThreadId 投递消息的目标对话标识
     * @param boundTurnId 绑定的执行标识
     * @param occurredAt 事件发生时间
     */
    public record MailboxConsume(String messageId,
                                 String targetThreadId,
                                 String boundTurnId,
                                 String occurredAt) {
    }

    /**
     * 终态竞争获胜后释放尚未进入 Conversation 的 BOUND 消息，供后续 Turn 重新 claim。
     *
     * @param turnId 本次可恢复执行的稳定标识
     * @param occurredAt 事件发生时间
     */
    public record MailboxRelease(String turnId,
                                 String occurredAt) {
    }

    /**
     * Projection 的通用状态 CAS 与同事务 Activity sequence。
     *
     * @param taskThreadId 任务对话标识
     * @param expectedRevision 提交时预期的修订版本
     * @param state 当前状态
     * @param latestActivitySequence 最近活动序号
     * @param latestSafeSummary 最近确认可用于恢复的摘要
     * @param startedAt 开始时间
     * @param completedAt 完成时间
     * @param occurredAt 事件发生时间
     * @param incrementUnread 是否增加未读计数
     */
    public record ProjectionCas(String taskThreadId,
                                long expectedRevision,
                                String state,
                                long latestActivitySequence,
                                String latestSafeSummary,
                                String startedAt,
                                String completedAt,
                                String occurredAt,
                                boolean incrementUnread) {
    }

    /**
     * 不改变任务状态的消息 Activity 只推进 revision、latest sequence 与未读。
     *
     * @param taskThreadId 任务对话标识
     * @param expectedRevision 提交时预期的修订版本
     * @param latestActivitySequence 最近活动序号
     * @param latestSafeSummary 最近确认可用于恢复的摘要
     * @param occurredAt 事件发生时间
     */
    public record ProjectionActivityCas(String taskThreadId,
                                        long expectedRevision,
                                        long latestActivitySequence,
                                        String latestSafeSummary,
                                        String occurredAt) {
    }

    /**
     * Follow-up 可从终态显式开启新 Turn，并清除上一轮 completed_at。
     *
     * @param taskThreadId 任务对话标识
     * @param expectedRevision 提交时预期的修订版本
     * @param latestActivitySequence 最近活动序号
     * @param latestSafeSummary 最近确认可用于恢复的摘要
     * @param occurredAt 事件发生时间
     */
    public record FollowUpProjectionCas(String taskThreadId,
                                        long expectedRevision,
                                        long latestActivitySequence,
                                        String latestSafeSummary,
                                        String occurredAt) {
    }

    /**
     * 新 USER Turn 与首轮 Activity 在 AgentStore 外层事务中共同推进 Task revision。
     *
     * @param taskThreadId 任务对话标识
     * @param expectedRevision 提交时预期的修订版本
     * @param latestActivitySequence 最近活动序号
     * @param latestSafeSummary 最近确认可用于恢复的摘要
     * @param startedAt 开始时间
     * @param occurredAt 事件发生时间
     */
    public record UserAdmissionProjectionCas(String taskThreadId,
                                             long expectedRevision,
                                             long latestActivitySequence,
                                             String latestSafeSummary,
                                             String startedAt,
                                             String occurredAt) {
    }

    /**
     * 已读边界 CAS 不接受未来 activity sequence。
     *
     * @param taskThreadId 任务对话标识
     * @param expectedRevision 提交时预期的修订版本
     * @param throughActivitySequence 当前快照覆盖到的活动序号
     * @param occurredAt 事件发生时间
     */
    public record SeenCas(String taskThreadId,
                          long expectedRevision,
                          long throughActivitySequence,
                          String occurredAt) {
    }

    /**
     * 删除确认必须同时绑定 Task identity 和投影 revision。
     *
     * @param taskThreadId 任务对话标识
     * @param expectedRevision 提交时预期的修订版本
     * @param occurredAt 事件发生时间
     */
    public record TreeDelete(String taskThreadId,
                             long expectedRevision,
                             String occurredAt) {
    }

    /**
     * 终态集成按 Turn 找到 Child lineage 与当前 projection revision。
     *
     * @param taskThreadId 任务对话标识
     * @param parentThreadId 父对话标识
     * @param rootThreadId 任务树根对话标识
     * @param taskKind 任务类型
     * @param taskRevision 任务修订版本
     * @param taskState 任务状态
     */
    public record TerminalTaskRow(String taskThreadId,
                                  String parentThreadId,
                                  String rootThreadId,
                                  String taskKind,
                                  long taskRevision,
                                  String taskState) {
    }

    /**
     * Workspace FIFO 声明写入参数；fencing token 在同事务内由 Mapper 分配。
     *
     * @param claimId 领取标识
     * @param workspaceId 工作区稳定标识
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param processGeneration App Server 进程代际标识
     * @param fencingToken 执行租约的隔离令牌，用于拒绝过期执行者
     * @param requestedAt 请求时间
     */
    public record WriteClaimInsert(String claimId,
                                   String workspaceId,
                                   String threadId,
                                   String turnId,
                                   long processGeneration,
                                   long fencingToken,
                                   String requestedAt) {
    }

    /**
     * Workspace write claim 的完整恢复行。
     *
     * @param claimSequence 领取序号
     * @param claimId 领取标识
     * @param workspaceId 工作区稳定标识
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param processGeneration App Server 进程代际标识
     * @param fencingToken 执行租约的隔离令牌，用于拒绝过期执行者
     * @param state 当前状态
     * @param requestedAt 请求时间
     * @param acquiredAt 获取时间
     * @param heartbeatAt 心跳时间
     * @param releasedAt 释放时间
     */
    public record WriteClaimRow(long claimSequence,
                                String claimId,
                                String workspaceId,
                                String threadId,
                                String turnId,
                                long processGeneration,
                                long fencingToken,
                                String state,
                                String requestedAt,
                                String acquiredAt,
                                String heartbeatAt,
                                String releasedAt) {
    }

    /**
     * 所有 lease 状态变更同时绑定稳定 claimId 和 fencing token。
     *
     * @param claimId 领取标识
     * @param fencingToken 执行租约的隔离令牌，用于拒绝过期执行者
     * @param occurredAt 事件发生时间
     */
    public record WriteClaimCas(String claimId,
                                long fencingToken,
                                String occurredAt) {
    }
}
