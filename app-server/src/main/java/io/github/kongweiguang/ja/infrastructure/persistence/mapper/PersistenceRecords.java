// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

/**
 * 集中声明 MyBatis 的行形状与具名参数对象；这些 record 只描述 SQL 边界，不承载业务行为。
 */
public final class PersistenceRecords {
    /** Workspace 查询的固定列集合。 */
    public record WorkspaceRow(String workspaceId, String rootPath, String displayName, String trust,
                        long revision, String updatedAt) { }

    /** Thread 查询的固定列集合；模型偏好与标题来源均由 V2 schema 强制完整。 */
    public record ThreadRow(String threadId, String workspaceId, String title, String providerId,
                     String modelId, String reasoningLevel, String accessMode, String titleSource,
                     long revision, String createdAt, String updatedAt, String archivedAt, String deletedAt) { }

    /** Turn 查询的完整持久化形状；全局 ID 查询额外填充可空 threadRevision。 */
    public record TurnRow(String turnId, String threadId, String state, String providerId, String modelId,
                   String provider, String api, String upstreamModel, String reasoningLevel, String accessMode,
                   String configGeneration, long mutationVersion, String requestedAt, String updatedAt, String completedAt,
                   String terminalSummary, String errorCode, String errorMessage, String cancelRequestedAt,
                   String cancelReason, Long cancelExpectedThreadRevision, Long cancelThreadRevision,
                   Long cancelTurnMutationVersion, Long threadRevision, String changeSetJson) { }

    /** Message 查询行保留数据库分配的稳定 ordinal。 */
    public record MessageRow(String messageId, String turnId, long ordinal, String role,
                      String blocksJson, String createdAt) { }

    /** Tool 状态门所需的最小查询行。 */
    public record ToolRow(String callId, String state, long revision, int ordinal,
                   String toolName, String sideEffect) { }

    /** Approval 状态门所需的最小查询行，decision 在待决时允许 null。 */
    public record ApprovalRow(String approvalId, String decision, String expiresAt) { }

    /** Pending input 的 FIFO 查询行。 */
    public record PendingInputRow(String inputId, String threadId, String turnId, String kind,
                           String text, String createdAt) { }

    /** Thread 快照 UNION 的统一行形状；不同 item kind 的非适用列保持 null。 */
    public record SnapshotItemRow(String itemId, String itemKind, String createdAt, String turnId,
                           String callId, String toolName, Long toolOrdinal, String toolState,
                           String messageKind, String publicText, Long modelRound, String presentationJson,
                           String approvalId, String decision,
                           String expiresAt, String attachmentId, String displayName,
                           Long sizeBytes, String mediaKind, String mediaType, String attachmentState) { }

    /** Thread 最近一次 Provider Usage 的恢复行；不把模型凭据或请求正文带入历史快照。 */
    public record ContextUsageRow(String turnId, long modelRound, long inputTokens, long outputTokens,
                                  long totalTokens, String occurredAt) { }

    /** Append-only checkpoint 的完整读取形状。 */
    public record CheckpointRow(String threadId, String checkpointId, long sourceRevision, long throughOrdinal,
                         long retainedFromOrdinal, String retainedSplitJson, String summaryJson,
                         int estimatedTokens, String envelopeFingerprint, String strategyVersion,
                         String usageJson, String createdAt) { }

    /** SQLite WAL checkpoint 的三列状态，列名与 PRAGMA 输出保持一一对应。 */
    public record WalCheckpointRow(int busy, int log, int checkpointed) { }

    /** Turn/Tool/Approval 的复合身份参数。 */
    public record TurnKey(String threadId, String turnId) { }
    /** 新 Turn 的不可变插入参数。 */
    public record TurnInsert(String turnId, String threadId, String providerId, String modelId,
                      String provider, String api, String upstreamModel, String reasoningLevel,
                      String accessMode, String configGeneration, String occurredAt) { }
    /** 取消声明同时冻结 Thread/Turn 两级 CAS 事实。 */
    public record CancellationClaim(String threadId, String turnId, long expectedTurnMutationVersion,
                             String occurredAt, String reason, long expectedThreadRevision,
                             long cancelThreadRevision, long cancelTurnMutationVersion) { }
    /** Turn 状态迁移的完整 CAS 参数，终态字段按目标状态允许 null。 */
    public record TurnCas(String threadId, String turnId, String state, long expectedTurnMutationVersion,
                   String occurredAt, String completedAt, String summary, String errorCode,
                   String errorMessage) { }
    /** 保持当前状态时仅推进 Turn mutation version。 */
    public record TurnAdvance(String threadId, String turnId, long expectedTurnMutationVersion,
                       String state, String occurredAt) { }
    /** Message 插入的完整持久化参数。 */
    public record MessageInsert(String messageId, String threadId, String turnId, long ordinal,
                         String role, String blocksJson, String occurredAt) { }
    /** Tool 的 Turn 内复合身份。 */
    public record ToolKey(String turnId, String callId) { }
    /** PREPARED Tool 插入参数。 */
    public record ToolInsert(String callId, String threadId, String turnId, int ordinal, String toolName,
                      String sideEffect, String presentationJson, String occurredAt) { }
    /** Tool 启动状态门参数。 */
    public record ToolStart(String turnId, String callId, String occurredAt) { }
    /** Tool 完成状态门参数。 */
    public record ToolFinish(String turnId, String callId, String state, String presentationJson,
                      String artifactId, String occurredAt) { }
    /** 审批状态切换只更新已经安全投影的展示 JSON，不改变 Tool 执行状态机。 */
    public record ToolPresentationUpdate(String turnId, String callId, String presentationJson,
                                         String occurredAt) { }
    /** Turn 终态统一结算未完成 Tool，内部状态与公开展示状态分别使用各自闭集。 */
    public record ToolSettlement(String turnId, String state, String presentationStatus,
                                 String outputPreview, String occurredAt) { }
    /** 面向历史 UI 的分阶段文本，与模型上下文 messages 表物理隔离。 */
    public record TimelineMessageInsert(String itemId, String threadId, String turnId, String messageKind,
                                        String publicText, Integer modelRound, String occurredAt) { }
    /** 已脱敏 Tool artifact 只通过四元身份读取。 */
    public record ToolArtifactInsert(String artifactId, String threadId, String turnId, String callId,
                                     String content, long characterLength, String occurredAt) { }
    /** Tool artifact 读取行不暴露数据库主键以外字段。 */
    public record ToolArtifactRow(String artifactId, String content, long characterLength) { }
    /** Tool artifact 严格四元查询。 */
    public record ToolArtifactKey(String threadId, String turnId, String callId, String artifactId) { }
    /** Turn change set 与可选 diff artifact 的原子插入参数。 */
    public record ChangeSetInsert(String threadId, String turnId, String workspaceId, String changeSetJson,
                                  String artifactId, String sha256, Long byteLength, String unifiedDiff,
                                  String occurredAt) { }
    /** 三元身份读取冻结 diff。 */
    public record ChangeSetArtifactKey(String threadId, String turnId, String artifactId) { }
    /** 冻结 diff 读取行。 */
    public record ChangeSetArtifactRow(String artifactId, String content, long byteLength) { }
    /** Approval 的 Turn 内复合身份。 */
    public record ApprovalKey(String turnId, String approvalId) { }
    /** 待决 Approval 插入参数。 */
    public record ApprovalInsert(String approvalId, String threadId, String turnId, String callId,
                          String occurredAt, String expiresAt) { }
    /** Approval 决议的复合状态门参数。 */
    public record ApprovalResolve(String turnId, String approvalId, String callId, String decision,
                           String occurredAt) { }
    /** 单个模型轮次的精确 usage 参数。 */
    public record UsageInsert(String usageId, String threadId, String turnId, int modelRound,
                       long inputTokens, long outputTokens, long totalTokens, String occurredAt) { }
    /** Pending input 插入参数。 */
    public record PendingInputInsert(String inputId, String threadId, String turnId, String kind,
                              String text, String occurredAt) { }
    /** Pending input FIFO 查询参数。 */
    public record PendingInputQuery(String turnId, String kind) { }
    /** 单条 Pending input 消费参数。 */
    public record PendingInputConsume(String inputId, String occurredAt) { }
    /** Turn 下全部 Pending input 取消参数。 */
    public record PendingInputCancel(String turnId, String occurredAt) { }
    /** 输入消费后的 Turn mutation version 推进参数。 */
    public record InputAdvance(String threadId, String turnId, long expectedTurnMutationVersion,
                        String occurredAt) { }

    /** Workspace 注册插入参数。 */
    public record WorkspaceInsert(String workspaceId, String rootPath, String displayName, String trust,
                           String occurredAt) { }
    /** Workspace keyset 分页参数，首屏 cursor 字段允许 null。 */
    public record WorkspacePage(String cursorTime, String cursorId, int limit) { }
    /** Workspace trust CAS 参数。 */
    public record WorkspaceTrustCas(String workspaceId, String trust, long expectedRevision,
                             String occurredAt) { }
    /** Workspace 删除 CAS 参数。 */
    public record WorkspaceDelete(String workspaceId, long expectedRevision) { }
    /** Thread 插入参数使用当前稳定身份，不保存已退役的 Profile selector。 */
    public record ThreadInsert(String threadId, String workspaceId, String title, String providerId,
                        String modelId, String reasoningLevel, String accessMode, String titleSource,
                        String createdAt) { }
    /** 已持锁 Thread 的无 CAS revision 推进参数。 */
    public record ThreadRevision(String threadId, String occurredAt) { }
    /** 面向外部请求的 Thread revision CAS 参数。 */
    public record ThreadRevisionCas(String threadId, long expectedRevision, String occurredAt) { }
    /** Thread keyset 分页参数固定 Workspace，首屏 cursor 字段允许 null。 */
    public record ThreadPage(String workspaceId, String cursorTime, String cursorId, int limit) { }
    /** Thread 标题搜索沿用更新时间 keyset，并把查询词固定为服务层归一化小写。 */
    public record ThreadSearch(String workspaceId, String normalizedQuery,
                               String cursorTime, String cursorId, int limit) { }
    /**
     * 用户重命名与自动标题共享 SQL 形状；placeholderOnly 为自动路径启用来源所有权 CAS，
     * expectedRevision 在该路径作为首次成功 Turn 的 revision 下界，而不是精确相等条件。
     */
    public record ThreadTitleCas(String threadId, String title, String titleSource,
                                 long expectedRevision, String occurredAt, boolean placeholderOnly) { }
    /** 下一轮模型与权限偏好以单次 revision CAS 原子替换。 */
    public record ThreadPreferencesCas(String threadId, String providerId, String modelId,
                                       String reasoningLevel, String accessMode, long expectedRevision,
                                       String occurredAt) { }
    /** admission 在唯一 revision CAS 中同时写入冻结偏好与可选首次标题。 */
    public record ThreadAdmissionCas(String threadId, String providerId, String modelId,
                                     String reasoningLevel, String accessMode, String provisionalTitle,
                                     long expectedRevision, String occurredAt) { }
    /** Thread 归档或删除的单一 lifecycle CAS 参数。 */
    public record ThreadLifecycle(String threadId, long expectedRevision, String occurredAt, boolean delete) { }
    /** 混合 Thread 快照的 keyset 分页参数。 */
    public record SnapshotPage(String threadId, String cursorTime, String cursorId, int limit) { }

    /** Checkpoint source revision 复合身份。 */
    public record CheckpointKey(String threadId, long sourceRevision) { }
    /** Append-only checkpoint 插入参数。 */
    public record CheckpointInsert(String checkpointId, String threadId, long sourceRevision, long throughOrdinal,
                            long retainedFromOrdinal, String retainedSplitJson, String summaryJson,
                            int estimatedTokens, String envelopeFingerprint, String strategyVersion,
                            String usageJson, String createdAt) { }

    /** Thread 指令 scope 复合身份。 */
    public record ScopeKey(String threadId, String relativeDirectory) { }
    /** 指令 scope 原子限额插入参数。 */
    public record ScopeInsert(String threadId, String relativeDirectory, String discoveredAt) { }
    /** 启动恢复同一 Thread 的共享时间参数。 */
    public record RecoveryCommand(String threadId, String occurredAt) { }

    /** 纯类型容器禁止实例化，避免被依赖注入框架当作服务注册。 */
    private PersistenceRecords() { }
}
