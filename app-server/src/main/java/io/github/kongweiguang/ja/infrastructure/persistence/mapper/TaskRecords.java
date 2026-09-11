// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

/** TaskMapper 的显式行与参数闭集，避免领域端口依赖 MyBatis 构造规则。 */
public final class TaskRecords {
    /** 父 Thread 与可选父 Task lineage 的单快照准入行。 */
    public record ParentRow(String threadId, String workspaceId, String accessMode, long revision,
                            String rootThreadId, Integer depth) { }

    /** origin Turn 的所属 Thread 与既有根 Turn，用于阻止跨 Thread 伪造因果链。 */
    /** 父 Turn 状态随因果身份同快照读取，ATTACHED admission 不得越过已提交取消。 */
    public record TurnCausalityRow(String threadId, String rootTurnId, String state,
                                   String cancelRequestedAt) { }

    /** 通信路由同时冻结发送方标题，并携带目标临时侧聊的关闭闸门状态。 */
    public record TaskRouteRow(String threadId, String rootThreadId, Integer depth,
                               String title, boolean closing) { }

    /** 临时侧聊 marker 的最小 SQL 行形状；状态闭集在 repository 端口边界再次校验。 */
    public record SideChatMarkerRow(String threadId, String state) { }

    /** 父取消事实与仍存活 ATTACHED 直接子 Turn 联结后的去重恢复行。 */
    public record CancellationPropagationRow(String parentThreadId, String parentTurnId) { }

    /** 列表与详情共享的 lineage + projection 固定列集合。 */
    public record TaskSummaryRow(String taskThreadId, String parentThreadId, String rootThreadId,
                                 String originTurnId, String taskName, int depth, String taskKind,
                                 String lifecycle, String contextSeedId, String createdAt,
                                 long revision, String state, long latestActivitySequence,
                                 Long lastSeenActivitySequence, int unreadCount, int descendantCount,
                                 int runningDescendantCount, int needsAttentionCount,
                                 String latestSafeSummary, String startedAt, String completedAt,
                                 String updatedAt) { }

    /** 上下文种子的完整存储形状；JSON 必须由 adapter 严格解码。 */
    public record ContextSeedRow(String contextSeedId, String parentThreadId, String parentTurnId,
                                 long parentRevision, String inheritanceMode, String taskBriefJson,
                                 String effectiveContextJson, String referencesJson,
                                 String permissionCeilingJson, String fingerprint,
                                 String createdAt) { }

    /** Activity 的 SQLite sequence 与安全 JSON 摘要。 */
    public record ActivityRow(long activitySequence, String activityId, String rootThreadId,
                              String taskThreadId, String actorThreadId, String causalTurnId,
                              String kind, String summaryJson, String createdAt) { }

    /** 根 Timeline 单查询保留各表独立身份，adapter 可识别 LEFT JOIN 缺失而不是静默丢活动。 */
    public record RootActivityProjectionRow(
            long activitySequence, String activityId, String activityRootThreadId,
            String activityTaskThreadId, String actorThreadId, String causalTurnId,
            String activityKind, String activitySummaryJson, String activityCreatedAt,
            String lineageTaskThreadId, String parentThreadId, String lineageRootThreadId,
            String originTurnId, String taskName, Integer depth, String taskKind,
            String lifecycle, String contextSeedId, String lineageCreatedAt,
            String projectionTaskThreadId, String projectionRootThreadId, Long projectionRevision,
            String projectionState, Long latestActivitySequence, Long lastSeenActivitySequence,
            Integer unreadCount, Integer descendantCount, Integer runningDescendantCount,
            Integer needsAttentionCount, String latestSafeSummary, String startedAt,
            String completedAt, String projectionUpdatedAt, String persistedThreadId,
            String threadDeletedAt) { }

    /** Mailbox 的完整恢复行；senderTitle 是独立快照，sender/causal id 不依赖发送方 FK。 */
    public record MailboxRow(long mailboxSequence, String messageId, String rootThreadId,
                             String senderThreadId, String senderTitle, String targetThreadId,
                             String causalTurnId, String kind, String contentJson,
                             String idempotencyKey, String state, String boundTurnId,
                             String createdAt, String updatedAt, String consumedAt) { }

    /** 单目标 Mailbox 当前容量统计，UTF-8 bytes 由 SQLite BLOB 长度计算。 */
    public record MailboxStats(int pendingCount, long pendingBytes) { }

    /** Child Turn insert 只扩展 Operation 因果列；请求级 Provider 环境不得固化到 Turn。 */
    public record ChildTurnInsert(String turnId, String threadId, String parentTurnId,
                                  String rootTurnId, String occurredAt) { }

    /** Seed insert 保存 canonical JSON 和 adapter 计算的 fingerprint。 */
    public record ContextSeedInsert(String contextSeedId, String parentThreadId, String parentTurnId,
                                    long parentRevision, String inheritanceMode, String taskBriefJson,
                                    String effectiveContextJson, String referencesJson,
                                    String permissionCeilingJson, String fingerprint,
                                    String createdAt) { }

    /** 创建后不可更新的 Thread lineage 参数。 */
    public record LineageInsert(String childThreadId, String parentThreadId, String rootThreadId,
                                String originTurnId, String taskName, int depth, String taskKind,
                                String lifecycle, String contextSeedId, String createdAt) { }

    /** Activity insert 由 SQLite 分配全局 sequence 并 RETURNING。 */
    public record ActivityInsert(String activityId, String rootThreadId, String taskThreadId,
                                 String actorThreadId, String causalTurnId, String kind,
                                 String summaryJson, String createdAt) { }

    /** 初始 projection 使用已提交 DISPATCHED sequence，不允许临时零引用。 */
    public record ProjectionInsert(String taskThreadId, String rootThreadId, String state,
                                   long latestActivitySequence, String latestSafeSummary,
                                   String startedAt, String updatedAt) { }

    /** Mailbox insert 使用发送方 scoped idempotency key 并返回 sequence。 */
    public record MailboxInsert(String messageId, String rootThreadId, String senderThreadId,
                                String targetThreadId, String causalTurnId, String kind,
                                String contentJson, String idempotencyKey, String state,
                                String boundTurnId, String createdAt, String updatedAt) { }

    /** Follow-up 只允许将精确 PENDING 消息绑定到目标新 Turn。 */
    public record MailboxBind(String messageId, String targetThreadId, String boundTurnId,
                              String occurredAt) { }

    /** 安全点只把尚未归属任何执行者的 PENDING 消息绑定到当前目标 Turn。 */
    public record MailboxClaim(String targetThreadId, String turnId, int limit,
                               String occurredAt) { }

    /** USER facts 成功写入后只消费同一 Turn 持有的 BOUND 消息。 */
    public record MailboxConsume(String turnId, String occurredAt) { }

    /** 终态竞争获胜后释放尚未进入 Conversation 的 BOUND 消息，供后续 Turn 重新 claim。 */
    public record MailboxRelease(String turnId, String occurredAt) { }

    /** Projection 的通用状态 CAS 与同事务 Activity sequence。 */
    public record ProjectionCas(String taskThreadId, long expectedRevision, String state,
                                long latestActivitySequence, String latestSafeSummary,
                                String startedAt, String completedAt, String occurredAt,
                                boolean incrementUnread) { }

    /** 不改变任务状态的消息 Activity 只推进 revision、latest sequence 与未读。 */
    public record ProjectionActivityCas(String taskThreadId, long expectedRevision,
                                        long latestActivitySequence, String latestSafeSummary,
                                        String occurredAt) { }

    /** Follow-up 可从终态显式开启新 Turn，并清除上一轮 completed_at。 */
    public record FollowUpProjectionCas(String taskThreadId, long expectedRevision,
                                        long latestActivitySequence, String latestSafeSummary,
                                        String occurredAt) { }

    /** 已读边界 CAS 不接受未来 activity sequence。 */
    public record SeenCas(String taskThreadId, long expectedRevision,
                          long throughActivitySequence, String occurredAt) { }

    /** 删除确认必须同时绑定 Task identity 和投影 revision。 */
    public record TreeDelete(String taskThreadId, long expectedRevision,
                             String occurredAt) { }

    /** 终态集成按 Turn 找到 Child lineage 与当前 projection revision。 */
    public record TerminalTaskRow(String taskThreadId, String parentThreadId, String rootThreadId,
                                  String taskKind, long taskRevision, String taskState) { }

    /** Workspace FIFO 声明写入参数；fencing token 在同事务内由 Mapper 分配。 */
    public record WriteClaimInsert(String claimId, String workspaceId, String threadId,
                                   String turnId, long processGeneration, long fencingToken,
                                   String requestedAt) { }

    /** Workspace write claim 的完整恢复行。 */
    public record WriteClaimRow(long claimSequence, String claimId, String workspaceId,
                                String threadId, String turnId, long processGeneration,
                                long fencingToken, String state, String requestedAt,
                                String acquiredAt, String heartbeatAt, String releasedAt) { }

    /** 所有 lease 状态变更同时绑定稳定 claimId 和 fencing token。 */
    public record WriteClaimCas(String claimId, long fencingToken, String occurredAt) { }

    /** 纯 SQL 形状容器禁止实例化。 */
    private TaskRecords() { }
}
