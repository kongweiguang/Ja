// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

/**
 * 集中声明 MyBatis 的行形状与具名参数对象；这些 record 只描述 SQL 边界，不承载业务行为。
 */
public final class PersistenceRecords {
    /** Interaction request 以 JSON 快照保存题目/答案，状态与 revision 仍由列做 CAS。 */
    public record InteractionRow(String requestId, String threadId, String turnId, String toolCallId,
                                 String planRevisionId, String runId, String goalId, String idempotencyKey,
                                 String questionsJson, String status, String answersJson, long revision,
                                 String createdAt, String updatedAt) { }

    /** Interaction 草稿只保存 UI 选择，不改变请求答案状态。 */
    public record InteractionDraftRow(String threadId, String requestId, String answersJson, int page,
                                      boolean collapsed, String idempotencyKey, long revision, String updatedAt) { }

    /** Interaction 事件序列由 SQLite 分配，客户端据此发现事件缺口后重新 read。 */
    public record InteractionEventRow(long eventSequence, String threadId, String requestId,
                                     long requestRevision, String kind, String occurredAt) { }
    /** Interaction 事件写入参数，事件序列由数据库事务分配。 */
    public record InteractionEventInsert(String threadId, String requestId, long requestRevision,
                                         String kind, String occurredAt) { }

    /** SQL 查询使用的 Thread/request 复合身份，防止跨 Thread 读取。 */
    public record InteractionKey(String threadId, String requestId) { }
    /** 待回答请求的结构化持久载荷，答案初始为空且状态必须为 PENDING。 */
    public record InteractionInsert(String requestId, String threadId, String turnId, String toolCallId,
                                    String planRevisionId, String runId, String goalId, String idempotencyKey,
                                    String questionsJson, String status, String answersJson, long revision,
                                    String createdAt, String updatedAt) { }
    /** 答案更新的 CAS 参数，所有题目在一笔事务内结算。 */
    public record InteractionAnswerCas(String threadId, String requestId, long expectedRevision,
                                       String answersJson, String status, String idempotencyKey, String occurredAt) { }
    /** 取消或替代请求的 CAS 参数，迟到答案不能越过该状态。 */
    public record InteractionCloseCas(String threadId, String requestId, long expectedRevision,
                                      String status, String idempotencyKey, String occurredAt) { }
    /** 草稿保存的 CAS 参数，草稿 revision 与请求状态互不混淆。 */
    public record InteractionDraftCas(String threadId, String requestId, long expectedRevision,
                                      String answersJson, int page, boolean collapsed,
                                      String idempotencyKey, String occurredAt) { }
    /** Plan evaluator intent 只保存非敏感 Profile 和冻结 identity，Provider 凭据永不落库。 */
    public record PlanEvaluationIntentInsert(String requestId, String planId, String planRevisionId,
                                             String runId, String ownerThreadId, String inputDigest,
                                             String profileJson, String startedAt) { }
      /** Plan evaluator usage 终态使用 identity/CAS 更新，UNKNOWN 不伪造 token 数值。 */
      public record PlanEvaluationUsageUpdate(String requestId, String planId, String planRevisionId,
                                              String runId, String outcome, String certainty,
                                              Long inputTokens, Long outputTokens, Long totalTokens,
                                              String verdict, String criteriaJson, String summary,
                                              String completedAt) { }
      /** 重试门只需读取 request identity 与当前终态。 */
      public record PlanEvaluationPriorRow(String requestId, String outcome, String verdict,
                                           String criteriaJson, String summary) { }
    /** Thread 创建时冻结的子智能体策略；空 provider/model 表示跟随父 Turn。 */
    public record SubagentPolicyRow(String threadId, boolean enabled, String providerId, String modelId,
                                    String reasoningLevel, String createdAt) { }

    /** 子智能体策略插入参数，禁止更新既有 Thread 的历史快照。 */
    public record SubagentPolicyInsert(String threadId, boolean enabled, String providerId, String modelId,
                                       String reasoningLevel, String createdAt) { }

    /** Workspace 查询的固定列集合。 */
    public record WorkspaceRow(String workspaceId, String rootPath, String displayName, String trust,
                        long revision, String updatedAt) { }

    /** Thread 查询的固定列集合；首版 schema 强制模型偏好与标题来源完整。 */
    public record ThreadRow(String threadId, String workspaceId, String title, String providerId,
                     String modelId, String reasoningLevel, String accessMode, String collaborationMode,
                     String titleSource,
                     long revision, String createdAt, String updatedAt, String pinnedAt, String archivedAt,
                     String deletedAt, String latestTurnStatus, boolean latestTurnSeen, String activeGoalId) { }

    /** 全局 Thread 发现只返回展示字段和 keyset 排序时间，不物化正文或偏好。 */
    public record ThreadDiscoveryRow(String threadId, String title, String kind, String workspaceId,
                                     String status, String updatedAt) { }

    /** Turn 查询只承载 Operation 状态；请求环境由 usage/profile 独立追踪。 */
    public record TurnRow(String turnId, String threadId, String state,
                   long mutationVersion, String requestedAt, String updatedAt, String completedAt,
                   String terminalSummary, String errorCode, String errorMessage, String cancelRequestedAt,
                   String cancelReason, Long cancelExpectedThreadRevision, Long cancelThreadRevision,
                   Long cancelTurnMutationVersion, long inputQueueRevision, boolean acceptingInputs,
                   Long threadRevision, String changeSetJson) { }

    /** Message 查询行保留数据库分配的稳定 ordinal。 */
    public record MessageRow(String messageId, String turnId, long ordinal, String role,
                      String blocksJson, String createdAt) { }

    /** Tool 状态门所需的最小查询行。 */
    public record ToolRow(String callId, String state, long revision, int ordinal,
                   String toolName, String sideEffect) { }

    /** Approval 状态门所需的最小查询行，decision 在待决时允许 null。 */
    public record ApprovalRow(String approvalId, String decision, String expiresAt) { }
    /** Tool 恢复按 callId 读取原审批，不生成新的业务身份。 */
    public record ToolApprovalRow(String approvalId, String decision, String expiresAt) { }
    /** 外部审批响应提交前锁定所属 Turn 状态门与 CAS 版本。 */
    public record ApprovalDecisionRow(String approvalId, String threadId, String turnId, String callId,
                                      String decision, String expiresAt, String turnState,
                                      long turnMutationVersion) {
        /**
         * SQLite JDBC 将 INTEGER 构造参数投影为包装 Long；保留领域侧非空 primitive accessor，
         * 仅在 MyBatis 构造边界显式拆箱，避免把可空版本号扩散到审批 CAS。
         */
        public ApprovalDecisionRow(String approvalId, String threadId, String turnId, String callId,
                                   String decision, String expiresAt, String turnState,
                                   Long turnMutationVersion) {
            this(approvalId, threadId, turnId, callId, decision, expiresAt, turnState,
                    turnMutationVersion.longValue());
        }
    }

    /** Pending input 的权威队列查询行；排序字段只留在 SQL，不扩散到领域。 */
    public record PendingInputRow(String inputId, String threadId, String turnId, String kind,
                           String contentJson, String validationStatus, String issueErrorCode,
                           String issueMessage, Boolean issueRetryable,
                           long inputRevision, String createdAt, String attachmentsJson) { }
    /** 单 Turn 当前待处理数量和 UTF-8 字节数。 */
    public record PendingInputStats(int inputCount, long totalBytes) { }

    /** Thread 快照 UNION 的统一行形状；不同 item kind 的非适用列保持 null。 */
    public record SnapshotItemRow(String itemId, String itemKind, String createdAt, String turnId,
                           String callId, String toolName, Long toolOrdinal, String toolState,
                           String messageKind, String publicText, String blocksJson,
                             Long modelRound, String presentationJson,
                             String approvalId, String decision,
                             String expiresAt, String attachmentsJson,
                             String sourceThreadId, String sourceTitle) { }

    /** Thread 最近一次 Provider Usage 的恢复行；不把模型凭据或请求正文带入历史快照。 */
    public record ContextUsageRow(String turnId, String requestId, long modelRound,
                                  int requestOrdinal, String purpose, String certainty,
                                  String profileJson, Long inputTokens, Long outputTokens, Long totalTokens,
                                  String occurredAt) { }
    /** Turn execution 的版本化 JSON 行；解码只允许经过严格 Codec。 */
    public record TurnExecutionRow(String turnId, int schemaVersion, String stateJson) { }

    /** Append-only checkpoint 的完整读取形状。 */
    public record CheckpointRow(String threadId, String checkpointId, long sourceRevision, long throughOrdinal,
                         long retainedFromOrdinal, String retainedSplitJson, String summaryJson,
                         int estimatedTokens, String envelopeFingerprint, String strategyVersion,
                         String usageJson, String createdAt) { }

    /** SQLite WAL checkpoint 的三列状态，列名与 PRAGMA 输出保持一一对应。 */
    public record WalCheckpointRow(int busy, int log, int checkpointed) { }

    /** Turn/Tool/Approval 的复合身份参数。 */
    public record TurnKey(String threadId, String turnId) { }
    /** 新 Turn 只接纳 Operation 身份与时间，禁止把可热更新环境固化到 Turn。 */
    public record TurnInsert(String turnId, String threadId, String occurredAt) { }
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
    /** 完整替换 Turn execution state 的参数。 */
    public record TurnExecutionWrite(String turnId, int schemaVersion, String stateJson) { }
    /** 内部 Turn 的不可见准入上下文；只允许与 execution 同事务首次写入。 */
    public record InternalTurnContextInsert(String turnId, String origin, String contextJson,
                                            String createdAt) { }
    /** Message 插入的完整持久化参数。 */
    public record MessageInsert(String messageId, String threadId, String turnId, long ordinal,
                         String role, String blocksJson, String occurredAt) { }
    /** Tool 的 Turn 内复合身份。 */
    public record ToolKey(String turnId, String callId) { }
    /** PREPARED Tool 插入参数。 */
    public record ToolInsert(String callId, String threadId, String turnId, int ordinal, String toolName,
                      String sideEffect, String presentationJson, String occurredAt) { }
    /** Provider 返回整个 batch 后与 Tool 行同事务插入的不可变路由绑定。 */
    public record ToolBindingInsert(String turnId, String batchId, String callId, String routeKind,
                                    String localName, String serverId, String remoteName,
                                    String schemaHash, String routeHash, String catalogRevision,
                                    String accessMode, String occurredAt) { }
    /** Tool 执行恢复只读取该精确绑定，禁止从当前同名目录反推旧路由。 */
    public record ToolBindingRow(String turnId, String batchId, String callId, String routeKind,
                                 String localName, String serverId, String remoteName,
                                 String schemaHash, String routeHash, String catalogRevision,
                                 String accessMode) { }
    /** Tool 启动状态门参数。 */
    public record ToolStart(String turnId, String callId, String occurredAt) { }
    /** Tool 完成状态门参数。 */
    public record ToolFinish(String turnId, String callId, String state, String presentationJson,
                      String artifactId, String occurredAt) { }
    /** 审批状态切换只更新已经安全投影的展示 JSON，不改变 Tool 执行状态机。 */
    public record ToolPresentationUpdate(String turnId, String callId, String presentationJson,
                                         String occurredAt) { }
    /** 决策事务只把既有安全投影从等待切到运行，不重建展示正文。 */
    public record ToolApprovalStatusUpdate(String turnId, String callId, String occurredAt) { }
    /** Turn 终态统一结算未完成 Tool，内部状态与公开展示状态分别使用各自闭集。 */
    public record ToolSettlement(String turnId, String state, String presentationStatus,
                                 String outputPreview, String occurredAt) { }
    /** 面向历史 UI 的分阶段文本，与模型上下文 messages 表物理隔离。 */
    public record TimelineMessageInsert(String itemId, String threadId, String turnId, String messageKind,
                                        String publicText, Integer modelRound, String sourceThreadId,
                                        String sourceTitle, String occurredAt) { }
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
    public record ChangeSetArtifactRow(String artifactId, String sha256, String content, long byteLength) { }
    /** Approval 的 Turn 内复合身份。 */
    public record ApprovalKey(String turnId, String approvalId) { }
    /** 待决 Approval 插入参数。 */
    public record ApprovalInsert(String approvalId, String threadId, String turnId, String callId,
                          String occurredAt, String expiresAt) { }
    /** Approval 决议的复合状态门参数。 */
    public record ApprovalResolve(String turnId, String approvalId, String callId, String decision,
                           String occurredAt) { }
    /** 单个模型轮次的精确 usage 参数。 */
    public record UsageInsert(String usageId, String requestId, String threadId, String turnId,
                              int modelRound, int requestOrdinal, String purpose, String certainty,
                              String profileJson, Long inputTokens, Long outputTokens, Long totalTokens,
                              String occurredAt) { }
    /** UNKNOWN 行只允许由同 request/profile 的可靠计量原位升级为 KNOWN。 */
    public record UsageSettlement(String requestId, String turnId, int requestOrdinal,
                                  String purpose, String profileJson,
                                  long inputTokens, long outputTokens, long totalTokens,
                                  String occurredAt) { }
    /** Pending input 插入参数。 */
    public record PendingInputInsert(String inputId, String threadId, String turnId, String kind,
                               String contentJson, String occurredAt) { }
    /** Pending input 安全点查询参数；kind 为空时按完整队列优先级选择。 */
    public record PendingInputQuery(String turnId, String kind) { }
    /** 单项查询同时绑定 Turn，禁止只凭全局 inputId 越过所有权边界。 */
    public record PendingInputKey(String turnId, String inputId) { }
    /** 单条 Pending input 消费参数。 */
    public record PendingInputConsume(String inputId, long expectedInputRevision, String occurredAt) { }
    /** 条目 CAS 变更参数；prioritySequence 仅 prioritize 使用。 */
    public record PendingInputMutation(String turnId, String inputId, long expectedInputRevision,
                                       String contentJson, Long prioritySequence, String occurredAt) { }
    /** 消费期引用失效的稳定修复事实；不保存底层异常或绝对路径。 */
    public record PendingInputAttention(String turnId, String inputId, long expectedInputRevision,
                                        String errorCode, String message, boolean retryable,
                                        String occurredAt) { }
    /** Turn 下全部 Pending input 取消参数。 */
    public record PendingInputCancel(String turnId, String occurredAt) { }
    /** queue revision 与接收门共享同一 CAS，避免队列内容和投影版本分离提交。 */
    public record InputQueueAdvance(String turnId, long expectedQueueRevision, String occurredAt) { }
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
                        String modelId, String reasoningLevel, String accessMode, String collaborationMode,
                        String titleSource,
                        String createdAt) { }
    /** 已持锁 Thread 的无 CAS revision 推进参数。 */
    public record ThreadRevision(String threadId, String occurredAt) { }
    /** 面向外部请求的 Thread revision CAS 参数。 */
    public record ThreadRevisionCas(String threadId, long expectedRevision, String occurredAt) { }
    /** Thread keyset 分页参数固定 Workspace，首屏 cursor 字段允许 null。 */
    public record ThreadPage(String workspaceId, Integer cursorPinned, String cursorSortTime,
                             String cursorUpdatedAt, String cursorId, int limit) { }
    /** Thread 标题搜索沿用更新时间 keyset，并把查询词固定为服务层归一化小写。 */
    public record ThreadSearch(String workspaceId, String normalizedQuery,
                               String cursorTime, String cursorId, int limit) { }
    /** 全局发现使用统一更新时间/身份 keyset，可选 Workspace 与标题 contains 过滤。 */
    public record ThreadDiscoveryPage(String workspaceId, String normalizedQuery,
                                      String cursorTime, String cursorId, int limit) { }
    /**
     * 用户重命名与自动标题共享 SQL 形状；placeholderOnly 为自动路径启用来源所有权 CAS，
     * expectedRevision 在该路径作为首次成功 Turn 的 revision 下界，而不是精确相等条件。
     */
    public record ThreadTitleCas(String threadId, String title, String titleSource,
                                 long expectedRevision, String occurredAt, boolean placeholderOnly) { }
    /** 下一轮模型与权限偏好以单次 revision CAS 原子替换。 */
    public record ThreadPreferencesCas(String threadId, String providerId, String modelId,
                                       String reasoningLevel, String accessMode, String collaborationMode,
                                       long expectedRevision,
                                       String occurredAt) { }
    /** admission 在唯一 revision CAS 中同时写入冻结偏好与可选首次标题。 */
    public record ThreadAdmissionCas(String threadId, String providerId, String modelId,
                                     String reasoningLevel, String accessMode, String collaborationMode,
                                     String provisionalTitle,
                                     long expectedRevision, String occurredAt) { }
    /** 置顶只作用于 active Thread；null 时间明确表达取消置顶。 */
    public record ThreadPinCas(String threadId, String pinnedAt, long expectedRevision, String occurredAt) { }
    /** 已读写入只接受调用方看到的 Thread revision，真实 Turn 序号由 SQL 在同一事务内选取。 */
    public record ThreadSeenCas(String threadId, long expectedRevision, String occurredAt) { }
    /** Thread 归档或删除的单一 lifecycle CAS 参数。 */
    public record ThreadLifecycle(String threadId, long expectedRevision, String occurredAt, boolean delete) { }
    /** 恢复只接受已归档 Thread，并显式清除可能损坏的置顶事实。 */
    public record ThreadRestore(String threadId, long expectedRevision, String occurredAt) { }
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
    /** 启动恢复只读取活动 Turn 与完整 execution JSON，不从消息历史推断位置。 */
    public record RecoveryTurnRow(String turnId, String threadId, String state, long mutationVersion,
                                  String cancelRequestedAt, Integer schemaVersion, String stateJson) { }
    /** SUSPENDED 或取消终态的精确 Turn CAS。 */
    public record RecoveryTurnCas(String turnId, String threadId, long expectedTurnMutationVersion,
                                  String occurredAt) { }
    /** 启动恢复合成唯一绑定不可用 Tool 结果所需的稳定身份与 ordinal。 */
    public record RecoveryToolMessage(String messageId, String threadId, String turnId, String callId,
                                      String content, String occurredAt) { }
    /** Resume 联表只读取 Operation、Workspace 与 execution JSON；环境在下一请求安全点重新解析。 */
    public record ResumeTurnRow(String turnId, String threadId, String workspaceId, String rootPath,
                                long turnMutationVersion, long threadRevision,
                                int schemaVersion, String stateJson, String originalUserBlocksJson,
                                String internalOrigin, String internalContextJson,
                                boolean provisionalTitleEligible) { }
    /** Resume 与 SUSPENDED cancel 共用 Thread/Turn 双 CAS 输入。 */
    public record ResumeTurnCas(String turnId, String threadId, long expectedThreadRevision,
                                long expectedTurnMutationVersion, String occurredAt) { }

    /** 纯类型容器禁止实例化，避免被依赖注入框架当作服务注册。 */
    private PersistenceRecords() { }
}
