// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

/**
 * 集中声明 MyBatis 的行形状与具名参数对象；这些 record 只描述 SQL 边界，不承载业务行为。
 */
public final class PersistenceRecords {
    /**
     * 纯类型容器禁止实例化，避免被依赖注入框架当作服务注册。
     */
    private PersistenceRecords() {
    }

    /**
     * Interaction request 以 JSON 快照保存题目/答案，状态与 interactionRevision 仍由列做 CAS。
     *
     * @param interactionId 交互标识
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param toolCallId 工具调用标识
     * @param planRevisionId Plan 修订版本标识
     * @param runId 运行标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param questionsJson 问题列表 JSON 表示
     * @param status 当前状态分类
     * @param answersJson 答案集合 JSON 表示
     * @param interactionRevision 交互修订版本
     * @param createdAt 创建时间
     * @param updatedAt 最后更新时间
     */
    public record InteractionRow(String interactionId,
                                 String threadId,
                                 String turnId,
                                 String toolCallId,
                                 String planRevisionId,
                                 String runId,
                                 String operationKey,
                                 String questionsJson,
                                 String status,
                                 String answersJson,
                                 long interactionRevision,
                                 String createdAt,
                                 String updatedAt) {
    }

    /**
     * Interaction 草稿只保存 UI 选择，不改变请求答案状态。
     *
     * @param threadId 所属对话的稳定标识
     * @param interactionId 交互标识
     * @param answersJson 答案集合 JSON 表示
     * @param page 分页结果
     * @param collapsed 是否处于折叠状态
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param draftRevision 草稿修订版本
     * @param updatedAt 最后更新时间
     */
    public record InteractionDraftRow(String threadId,
                                      String interactionId,
                                      String answersJson,
                                      int page,
                                      boolean collapsed,
                                      String operationKey,
                                      long draftRevision,
                                      String updatedAt) {
    }

    /**
     * Interaction 事件序列由 SQLite 分配，客户端据此发现事件缺口后重新 read。
     *
     * @param eventSequence 事件序号
     * @param threadId 所属对话的稳定标识
     * @param interactionId 交互标识
     * @param interactionRevision 交互修订版本
     * @param kind 类型分类
     * @param occurredAt 事件发生时间
     */
    public record InteractionEventRow(long eventSequence,
                                      String threadId,
                                      String interactionId,
                                      long interactionRevision,
                                      String kind,
                                      String occurredAt) {
    }

    /**
     * Interaction 事件写入参数，事件序列由数据库事务分配。
     *
     * @param threadId 所属对话的稳定标识
     * @param interactionId 交互标识
     * @param interactionRevision 交互修订版本
     * @param kind 类型分类
     * @param occurredAt 事件发生时间
     */
    public record InteractionEventInsert(String threadId,
                                         String interactionId,
                                         long interactionRevision,
                                         String kind,
                                         String occurredAt) {
    }

    /**
     * SQL 查询使用的 Thread/request 复合身份，防止跨 Thread 读取。
     *
     * @param threadId 所属对话的稳定标识
     * @param interactionId 交互标识
     */
    public record InteractionKey(String threadId,
                                 String interactionId) {
    }

    /**
     * 待回答请求的结构化持久载荷，答案初始为空且状态必须为 PENDING。
     *
     * @param interactionId 交互标识
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param toolCallId 工具调用标识
     * @param planRevisionId Plan 修订版本标识
     * @param runId 运行标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param questionsJson 问题列表 JSON 表示
     * @param status 当前状态分类
     * @param answersJson 答案集合 JSON 表示
     * @param interactionRevision 交互修订版本
     * @param createdAt 创建时间
     * @param updatedAt 最后更新时间
     */
    public record InteractionInsert(String interactionId,
                                    String threadId,
                                    String turnId,
                                    String toolCallId,
                                    String planRevisionId,
                                    String runId,
                                    String operationKey,
                                    String questionsJson,
                                    String status,
                                    String answersJson,
                                    long interactionRevision,
                                    String createdAt,
                                    String updatedAt) {
    }

    /**
     * 答案更新的 CAS 参数，所有题目在一笔事务内结算。
     *
     * @param threadId 所属对话的稳定标识
     * @param interactionId 交互标识
     * @param expectedInteractionRevision 预期交互修订版本
     * @param answersJson 答案集合 JSON 表示
     * @param status 当前状态分类
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param occurredAt 事件发生时间
     */
    public record InteractionAnswerCas(String threadId,
                                       String interactionId,
                                       long expectedInteractionRevision,
                                       String answersJson,
                                       String status,
                                       String operationKey,
                                       String occurredAt) {
    }

    /**
     * 取消或替代请求的 CAS 参数，迟到答案不能越过该状态。
     *
     * @param threadId 所属对话的稳定标识
     * @param interactionId 交互标识
     * @param expectedInteractionRevision 预期交互修订版本
     * @param status 当前状态分类
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param occurredAt 事件发生时间
     */
    public record InteractionCloseCas(String threadId,
                                      String interactionId,
                                      long expectedInteractionRevision,
                                      String status,
                                      String operationKey,
                                      String occurredAt) {
    }

    /**
     * 草稿保存的 CAS 参数，draftRevision 与请求状态互不混淆。
     *
     * @param threadId 所属对话的稳定标识
     * @param interactionId 交互标识
     * @param expectedDraftRevision 预期草稿修订版本
     * @param answersJson 答案集合 JSON 表示
     * @param page 分页结果
     * @param collapsed 是否处于折叠状态
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param occurredAt 事件发生时间
     */
    public record InteractionDraftCas(String threadId,
                                      String interactionId,
                                      long expectedDraftRevision,
                                      String answersJson,
                                      int page,
                                      boolean collapsed,
                                      String operationKey,
                                      String occurredAt) {
    }

    /**
     * Plan evaluator intent 只保存非敏感 Profile 和冻结 identity，Provider 凭据永不落库。
     *
     * @param evaluationId 验收评估标识
     * @param modelRequestId 一次真实模型请求的标识
     * @param planId Plan 标识
     * @param planRevisionId Plan 修订版本标识
     * @param runId 运行标识
     * @param ownerThreadId 所属对话标识
     * @param inputDigest 输入内容摘要
     * @param attemptOrdinal 本次尝试的顺序位置
     * @param profileJson 配置档案 JSON 表示
     * @param startedAt 开始时间
     */
    public record PlanEvaluationIntentInsert(String evaluationId,
                                             String modelRequestId,
                                             String planId,
                                             String planRevisionId,
                                             String runId,
                                             String ownerThreadId,
                                             String inputDigest,
                                             int attemptOrdinal,
                                             String profileJson,
                                             String startedAt) {
    }

    /**
     * 冻结 Plan 输入的审计查询键，避免字符串拼接绕过模型身份边界。
     *
     * @param planId Plan 标识
     * @param planRevisionId Plan 修订版本标识
     * @param runId 运行标识
     * @param inputDigest 输入内容摘要
     */
    public record PlanEvaluationLatestLookup(String planId,
                                             String planRevisionId,
                                             String runId,
                                             String inputDigest) {
    }

    /**
     * 崩溃恢复只改变旧请求终态，不猜测其用量或重写输入身份。
     *
     * @param evaluationId 验收评估标识
     * @param completedAt 完成时间
     */
    public record PlanEvaluationInterruptedUpdate(String evaluationId,
                                                  String completedAt) {
    }

    /**
     * Plan evaluator usage 终态使用 identity/CAS 更新，UNKNOWN 不伪造 token 数值。
     *
     * @param evaluationId 验收评估标识
     * @param modelRequestId 一次真实模型请求的标识
     * @param planId Plan 标识
     * @param planRevisionId Plan 修订版本标识
     * @param runId 运行标识
     * @param outcome 执行结果
     * @param certainty 确定性
     * @param inputTokens 输入 Token 数
     * @param outputTokens 输出 Token 数
     * @param totalTokens 输入与输出的 Token 总数
     * @param verdict 验收结论
     * @param criteriaJson 验收条件 JSON 表示
     * @param summary 摘要
     * @param completedAt 完成时间
     */
    public record PlanEvaluationUsageUpdate(String evaluationId,
                                            String modelRequestId,
                                            String planId,
                                            String planRevisionId,
                                            String runId,
                                            String outcome,
                                            String certainty,
                                            Long inputTokens,
                                            Long outputTokens,
                                            Long totalTokens,
                                            String verdict,
                                            String criteriaJson,
                                            String summary,
                                            String completedAt) {
    }

    /**
     * 重试门只需读取 request identity 与当前终态。
     *
     * @param evaluationId 验收评估标识
     * @param modelRequestId 一次真实模型请求的标识
     * @param attemptOrdinal 本次尝试的顺序位置
     * @param outcome 执行结果
     * @param verdict 验收结论
     * @param criteriaJson 验收条件 JSON 表示
     * @param summary 摘要
     */
    public record PlanEvaluationPriorRow(String evaluationId,
                                         String modelRequestId,
                                         Integer attemptOrdinal,
                                         String outcome,
                                         String verdict,
                                         String criteriaJson,
                                         String summary) {
    }

    /**
     * Thread 创建时冻结的子智能体策略；空 provider/model 表示跟随父 Turn。
     *
     * @param threadId 所属对话的稳定标识
     * @param enabled 是否启用
     * @param providerId 模型供应商标识
     * @param modelId 模型标识
     * @param reasoningLevel 模型推理等级
     * @param createdAt 创建时间
     */
    public record SubagentPolicyRow(String threadId,
                                    boolean enabled,
                                    String providerId,
                                    String modelId,
                                    String reasoningLevel,
                                    String createdAt) {
    }

    /**
     * 子智能体策略插入参数，禁止更新既有 Thread 的历史快照。
     *
     * @param threadId 所属对话的稳定标识
     * @param enabled 是否启用
     * @param providerId 模型供应商标识
     * @param modelId 模型标识
     * @param reasoningLevel 模型推理等级
     * @param createdAt 创建时间
     */
    public record SubagentPolicyInsert(String threadId,
                                       boolean enabled,
                                       String providerId,
                                       String modelId,
                                       String reasoningLevel,
                                       String createdAt) {
    }

    /**
     * Workspace 查询的固定列集合。
     *
     * @param workspaceId 工作区稳定标识
     * @param rootPath 根路径
     * @param displayName 面向用户的显示名称
     * @param trust 信任状态
     * @param revision 当前修订版本
     * @param updatedAt 最后更新时间
     * @param kind 类型分类
     */
    public record WorkspaceRow(String workspaceId,
                               String rootPath,
                               String displayName,
                               String trust,
                               long revision,
                               String updatedAt,
                               String kind) {
    }

    /**
     * 不物化正文，仅投影 Workspace 重绑忙碌摘要中的会话标题、事实类型、状态和有界计数。
     *
     * @param threadId 被阻塞会话的稳定标识
     * @param threadTitle 用于告知用户的会话标题
     * @param blockerKind 持久化阻塞事实类别
     * @param blockerState 阻塞事实当前状态
     * @param occurrences 该会话与事实类别组合的出现次数
     * @param totalBlockers 查询范围内的阻塞事实总量
     * @author kongweiguang
     */
    public record WorkspaceBusyBlockerRow(String threadId,
                                          String threadTitle,
                                          String blockerKind,
                                          String blockerState,
                                          long occurrences,
                                          long totalBlockers) {
    }

    /**
     * Thread 查询的固定列集合；首版 schema 强制模型偏好与标题来源完整。
     *
     * @param threadId 所属对话的稳定标识
     * @param ownerUserId 创建 Thread 时冻结的权威用户标识
     * @param workspaceId 工作区稳定标识
     * @param title 显示标题
     * @param workspaceKind 工作区类型
     * @param providerId 模型供应商标识
     * @param modelId 模型标识
     * @param reasoningLevel 模型推理等级
     * @param accessMode 访问模式
     * @param collaborationMode 协作模式
     * @param titleSource 标题来源
     * @param revision 当前修订版本
     * @param createdAt 创建时间
     * @param updatedAt 最后更新时间
     * @param pinnedAt 固定时间
     * @param archivedAt 归档时间
     * @param deletedAt 删除时间
     * @param latestTurnStatus 最近执行轮次状态
     * @param latestTurnSeen 是否已读最近一次执行
     * @param latestTurnId 最近执行轮次标识
     */
    public record ThreadRow(String threadId,
                            String ownerUserId,
                            String workspaceId,
                            String title,
                            String workspaceKind,
                            String providerId,
                            String modelId,
                            String reasoningLevel,
                            String accessMode,
                            String collaborationMode,
                            String titleSource,
                            long revision,
                            String createdAt,
                            String updatedAt,
                            String pinnedAt,
                            String archivedAt,
                            String deletedAt,
                            String latestTurnStatus,
                            boolean latestTurnSeen,
                            String latestTurnId) {
    }

    /**
     * 全局 Thread 发现只返回展示字段、最新执行身份和 keyset 时间，不物化正文或偏好。
     *
     * @param threadId 所属对话的稳定标识
     * @param title 显示标题
     * @param kind 类型分类
     * @param workspaceId 工作区稳定标识
     * @param status 当前状态分类
     * @param updatedAt 最后更新时间
     * @param latestTurnId 最近执行轮次标识
     */
    public record ThreadDiscoveryRow(String threadId,
                                     String title,
                                     String kind,
                                     String workspaceId,
                                     String status,
                                     String updatedAt,
                                     String latestTurnId) {
    }

    /**
     * Turn 查询只承载 Operation 状态；请求环境由 usage/profile 独立追踪。
     *
     * @param turnId 本次可恢复执行的稳定标识
     * @param threadId 所属对话的稳定标识
     * @param state 当前状态
     * @param mutationVersion 状态变更版本，用于并发提交校验
     * @param requestedAt 请求时间
     * @param updatedAt 最后更新时间
     * @param completedAt 完成时间
     * @param terminalSummary 终态摘要
     * @param errorCode 错误代码
     * @param errorMessage 错误消息
     * @param cancelRequestedAt 取消请求时间
     * @param cancelReason 取消原因
     * @param cancelThreadRevision 取消结算时的对话修订版本
     * @param cancelTurnMutationVersion 取消结算时的执行状态变更版本
     * @param inputQueueRevision 输入队列修订版本
     * @param acceptingInputs 是否仍接纳输入
     * @param threadRevision 对话修订版本
     * @param changeSetJson 变更集合 JSON 表示
     * @param currentPath 是否为当前路径
     * @param sourceMessageId 来源消息标识
     * @param terminalMessageId 终态消息标识
     */
    public record TurnRow(String turnId,
                          String threadId,
                          String state,
                          long mutationVersion,
                          String requestedAt,
                          String updatedAt,
                          String completedAt,
                          String terminalSummary,
                          String errorCode,
                          String errorMessage,
                          String cancelRequestedAt,
                          String cancelReason,
                          Long cancelThreadRevision,
                          Long cancelTurnMutationVersion,
                          long inputQueueRevision,
                          boolean acceptingInputs,
                          Long threadRevision,
                          String changeSetJson,
                          boolean currentPath,
                          String sourceMessageId,
                          String terminalMessageId) {
    }

    /**
     * 只为当前准入核验物化唯一 Core USER record；正文仍由 Core codec 解码，不成为产品持久副本。
     *
     * @param messageId 当前准入唯一用户消息的 Core 标识
     * @param role 消息的权威角色，准入时必须为 USER
     * @param recordId 拥有该消息的 Core 记录标识
     * @param recordType Core 记录类型，用于选择对应事实解码器
     * @param payloadSchemaVersion Core 事实载荷的版本
     * @param recordSequence Core 会话内的持久记录序号
     * @param payloadJson 待由 Core codec 解码的原始事实载荷
     * @param occurredAtMs Core 记录发生时间，单位为 UTC 毫秒
     */
    public record AdmissionUserRecordRow(String messageId,
                                         String role,
                                         String recordId,
                                         String recordType,
                                         int payloadSchemaVersion,
                                         long recordSequence,
                                         String payloadJson,
                                         long occurredAtMs) {
    }

    /**
     * Pending input 的权威队列查询行；排序字段只留在 SQL，不扩散到领域。
     *
     * @param inputId 输入标识
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param kind 类型分类
     * @param contentJson 内容 JSON 表示
     * @param validationStatus 校验状态
     * @param issueErrorCode 问题错误代码
     * @param issueMessage 问题消息
     * @param issueRetryable 当前问题是否允许重试
     * @param inputRevision 输入修订版本
     * @param createdAt 创建时间
     * @param attachmentsJson 附件列表 JSON 表示
     */
    public record PendingInputRow(String inputId,
                                  String threadId,
                                  String turnId,
                                  String kind,
                                  String contentJson,
                                  String validationStatus,
                                  String issueErrorCode,
                                  String issueMessage,
                                  Boolean issueRetryable,
                                  long inputRevision,
                                  String createdAt,
                                  String attachmentsJson) {
    }

    /**
     * Thread 快照 UNION 的统一行形状；不同 item kind 的非适用列保持 null。
     *
     * @param itemId 条目标识
     * @param itemKind 条目类型
     * @param createdAt 创建时间
     * @param turnId 本次可恢复执行的稳定标识
     * @param callId 模型生成的工具调用标识
     * @param toolName 工具名称
     * @param toolOrdinal 工具在调用批次中的顺序位置
     * @param toolState 工具状态
     * @param messageKind 消息类型
     * @param previewText 有界公开预览
     * @param totalCharacters 原始公开文本的 Unicode 字符数
     * @param modelRound 模型调用轮次
     * @param presentationJson 展示信息 JSON 表示
     * @param approvalId 审批标识
     * @param decision 决策
     * @param expiresAt 过期时间
     * @param attachmentsJson 附件列表 JSON 表示
     * @param contextReferencesJson 有序引用元数据，不含正文
     * @param sourceThreadId 来源对话标识
     * @param sourceTitle 来源标题
     * @param contextMessageId 对应的中性模型消息身份
     * @param contextMessageOrdinal 对应消息在模型上下文中的顺序号
     * @param timelineSequence 持久化时间线排序序号
     * @param turnSequence 所属 Turn 的因果顺序序号
     * @param semanticOrder 同一上下文消息内的语义子项顺序
     * @param ordinalOrder 同一消息与语义位置内的稳定序号
     * @author kongweiguang
     */
    public record SnapshotItemRow(String itemId,
                                  String itemKind,
                                  String createdAt,
                                  String turnId,
                                  String callId,
                                  String toolName,
                                  Long toolOrdinal,
                                  String toolState,
                                  String messageKind,
                                  String previewText,
                                  Integer totalCharacters,
                                  Long modelRound,
                                  String presentationJson,
                                  String approvalId,
                                  String decision,
                                  String expiresAt,
                                  String attachmentsJson,
                                  String contextReferencesJson,
                                  String sourceThreadId,
                                  String sourceTitle,
                                  String contextMessageId,
                                  Long contextMessageOrdinal,
                                  Long timelineSequence,
                                  Long turnSequence,
                                  Integer semanticOrder,
                                  Long ordinalOrder) {
    }

    /**
     * Thread 最近一次 Provider Usage 的恢复行；不把模型凭据或请求正文带入历史快照。
     *
     * @param turnId 本次可恢复执行的稳定标识
     * @param modelRequestId 一次真实模型请求的标识
     * @param modelRound 模型调用轮次
     * @param modelRequestOrdinal 模型请求顺序位置
     * @param purpose 用途
     * @param certainty 确定性
     * @param profileJson 配置档案 JSON 表示
     * @param inputTokens 输入 Token 数
     * @param outputTokens 输出 Token 数
     * @param totalTokens 输入与输出的 Token 总数
     * @param cacheReadTokens 命中缓存的输入 Token 数
     * @param cacheWriteTokens 写入缓存的 Token 数
     * @param newInputTokens 新增输入 Token 数
     * @param inputAccounting 输入用量的计量方式
     * @param estimatedContextTokens 估算的上下文 Token 数
     * @param occurredAt 事件发生时间
     */
    public record ContextUsageRow(String turnId,
                                  String modelRequestId,
                                  long modelRound,
                                  int modelRequestOrdinal,
                                  String purpose,
                                  String certainty,
                                  String profileJson,
                                  Long inputTokens,
                                  Long outputTokens,
                                  Long totalTokens,
                                  Long cacheReadTokens,
                                  Long cacheWriteTokens,
                                  Long newInputTokens,
                                  String inputAccounting,
                                  Long estimatedContextTokens,
                                  String occurredAt) {
    }

    /**
     * Thread 级累计 Usage 的原始聚合行；覆盖计数让 presenter 区分零值与未报告。
     *
     * @param requestCount 请求数量
     * @param measuredRequestCount 具有实测用量的请求数
     * @param newInputRequestCount 报告新增输入用量的请求数
     * @param newInputTokens 新增输入 Token 数
     * @param outputRequestCount 报告输出用量的请求数
     * @param outputTokens 输出 Token 数
     * @param totalRequestCount 模型请求总数
     * @param totalTokens 输入与输出的 Token 总数
     * @param cacheReadRequestCount 报告缓存读取用量的请求数
     * @param cacheReadTokens 命中缓存的输入 Token 数
     * @param cacheWriteRequestCount 报告缓存写入用量的请求数
     * @param cacheWriteTokens 写入缓存的 Token 数
     * @param cacheCompleteRequestCount 具有完整缓存统计的请求数
     * @param cacheCompleteInputTokens 具有完整缓存统计的输入 Token 数
     * @param cacheCompleteReadTokens 具有完整缓存统计的缓存读取 Token 数
     */
    public record ThreadUsageSummaryRow(long requestCount,
                                        long measuredRequestCount,
                                        long newInputRequestCount,
                                        long newInputTokens,
                                        long outputRequestCount,
                                        long outputTokens,
                                        long totalRequestCount,
                                        long totalTokens,
                                        long cacheReadRequestCount,
                                        long cacheReadTokens,
                                        long cacheWriteRequestCount,
                                        long cacheWriteTokens,
                                        long cacheCompleteRequestCount,
                                        long cacheCompleteInputTokens,
                                        long cacheCompleteReadTokens) {
    }

    /**
     * Append-only checkpoint 的完整读取形状。
     *
     * @param threadId 所属对话的稳定标识
     * @param checkpointId 检查点标识
     * @param sourceRevision 来源修订版本
     * @param throughOrdinal 覆盖到的顺序位置
     * @param retainedFromOrdinal 保留区间的起始顺序位置
     * @param retainedSplitJson 保留消息拆分边界的 JSON
     * @param summaryJson 摘要 JSON 表示
     * @param estimatedTokens 估算的 Token 数
     * @param envelopeFingerprint 协议封装指纹
     * @param strategyVersion 策略版本
     * @param usageJson 用量 JSON 表示
     * @param createdAt 创建时间
     */
    public record CheckpointRow(String threadId,
                                String checkpointId,
                                long sourceRevision,
                                long throughOrdinal,
                                long retainedFromOrdinal,
                                String retainedSplitJson,
                                String summaryJson,
                                int estimatedTokens,
                                String envelopeFingerprint,
                                String strategyVersion,
                                String usageJson,
                                String createdAt) {
    }

    /**
     * SQLite WAL checkpoint 的三列状态，列名与 PRAGMA 输出保持一一对应。
     *
     * @param busy SQLite 检查点操作的繁忙标记
     * @param log 日志
     * @param checkpointed 已写入检查点的 WAL 页数
     */
    public record WalCheckpointRow(int busy,
                                   int log,
                                   int checkpointed) {
    }

    /**
     * 正文读取只返回当前条目的精确来源；只有 Mailbox 保存其自有正文事实。
     *
     * @param itemId 用户可见时间线条目标识
     * @param kind 条目类型，用于选择 Core 事实或 Mailbox 正文来源
     * @param ownerUserId Thread 创建时冻结的权威用户标识
     * @param coreTenantId Core 记录所属租户，Mailbox 来源允许为空
     * @param coreThreadId Core 记录所属会话，Mailbox 来源允许为空
     * @param coreTurnId Core 记录所属执行，Mailbox 来源允许为空
     * @param coreRecordId 正文精确引用的 Core 记录标识
     * @param mailboxContentJson Mailbox 自有正文，Core 来源不保存正文副本
     * @param totalCharacters 可公开正文的字符总数，用于分页边界
     */
    public record ItemContentReferenceRow(String itemId,
                                          String kind,
                                          String ownerUserId,
                                          String coreTenantId,
                                          String coreThreadId,
                                          String coreTurnId,
                                          String coreRecordId,
                                          String mailboxContentJson,
                                          int totalCharacters) {
    }

    /**
     * Tool 的 Turn 内复合身份。
     *
     * @param turnId 本次可恢复执行的稳定标识
     * @param callId 模型生成的工具调用标识
     */
    public record ToolKey(String turnId,
                          String callId) {
    }

    /**
     * Tool artifact 读取行不暴露数据库主键以外字段。
     *
     * @param artifactId 产物标识
     * @param content 内容
     * @param characterLength 文本字符数
     */
    public record ToolArtifactRow(String artifactId,
                                  String content,
                                  long characterLength) {
    }

    /**
     * Tool artifact 严格四元查询。
     *
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param callId 模型生成的工具调用标识
     * @param artifactId 产物标识
     */
    public record ToolArtifactKey(String threadId,
                                  String turnId,
                                  String callId,
                                  String artifactId) {
    }

    /**
     * Turn change set 与可选 diff artifact 的原子插入参数。
     *
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param workspaceId 工作区稳定标识
     * @param changeSetJson 变更集合 JSON 表示
     * @param artifactId 产物标识
     * @param sha256 内容的 SHA-256 摘要
     * @param byteLength 数据长度，单位为字节
     * @param unifiedDiff 统一格式的差异文本
     * @param occurredAt 事件发生时间
     */
    public record ChangeSetInsert(String threadId,
                                  String turnId,
                                  String workspaceId,
                                  String changeSetJson,
                                  String artifactId,
                                  String sha256,
                                  Long byteLength,
                                  String unifiedDiff,
                                  String occurredAt) {
    }

    /**
     * 三元身份读取冻结 diff。
     *
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param artifactId 产物标识
     */
    public record ChangeSetArtifactKey(String threadId,
                                       String turnId,
                                       String artifactId) {
    }

    /**
     * 冻结 diff 读取行。
     *
     * @param artifactId 产物标识
     * @param sha256 内容的 SHA-256 摘要
     * @param content 内容
     * @param byteLength 数据长度，单位为字节
     */
    public record ChangeSetArtifactRow(String artifactId,
                                       String sha256,
                                       String content,
                                       long byteLength) {
    }

    /**
     * Approval 的 Turn 内复合身份。
     *
     * @param turnId 本次可恢复执行的稳定标识
     * @param approvalId 审批标识
     */
    public record ApprovalKey(String turnId,
                              String approvalId) {
    }

    /**
     * Workspace 注册插入参数。
     *
     * @param workspaceId 工作区稳定标识
     * @param rootPath 根路径
     * @param displayName 面向用户的显示名称
     * @param trust 信任状态
     * @param kind 类型分类
     * @param occurredAt 事件发生时间
     */
    public record WorkspaceInsert(String workspaceId,
                                  String rootPath,
                                  String displayName,
                                  String trust,
                                  String kind,
                                  String occurredAt) {
    }

    /**
     * Workspace keyset 分页参数，首屏 cursor 字段允许 null。
     *
     * @param cursorTime 游标记录的时间
     * @param cursorId 游标标识
     * @param limit 返回条目数上限
     * @param kind 类型分类
     */
    public record WorkspacePage(String cursorTime,
                                String cursorId,
                                int limit,
                                String kind) {
    }

    /**
     * Workspace trust CAS 参数。
     *
     * @param workspaceId 工作区稳定标识
     * @param trust 信任状态
     * @param expectedRevision 提交时预期的修订版本
     * @param occurredAt 事件发生时间
     */
    public record WorkspaceTrustCas(String workspaceId,
                                    String trust,
                                    long expectedRevision,
                                    String occurredAt) {
    }

    /**
     * Workspace metadata CAS 参数；保留 ID 并仅在真实根变更时把信任重置为未信任。
     *
     * @param workspaceId 稳定工作区标识
     * @param rootPath 目标物理根
     * @param displayName 展示名称
     * @param resetTrust 根变化时是否清除信任
     * @param expectedRevision 更新前的权威版本
     * @param occurredAt 事件发生时间
     */
    public record WorkspaceUpdateCas(String workspaceId,
                                     String rootPath,
                                     String displayName,
                                     boolean resetTrust,
                                     long expectedRevision,
                                     String occurredAt) {
    }

    /**
     * Workspace 删除 CAS 参数。
     *
     * @param workspaceId 工作区稳定标识
     * @param expectedRevision 提交时预期的修订版本
     */
    public record WorkspaceDelete(String workspaceId,
                                  long expectedRevision) {
    }

    /**
     * Thread 插入参数使用当前稳定身份，不保存已退役的 Profile selector。
     *
     * @param threadId 所属对话的稳定标识
     * @param ownerUserId 创建时冻结并与 Core conversation 绑定的用户标识
     * @param workspaceId 工作区稳定标识
     * @param title 显示标题
     * @param providerId 模型供应商标识
     * @param modelId 模型标识
     * @param reasoningLevel 模型推理等级
     * @param accessMode 访问模式
     * @param collaborationMode 协作模式
     * @param titleSource 标题来源
     * @param createdAt 创建时间
     */
    public record ThreadInsert(String threadId,
                               String ownerUserId,
                               String workspaceId,
                               String title,
                               String providerId,
                               String modelId,
                               String reasoningLevel,
                               String accessMode,
                               String collaborationMode,
                               String titleSource,
                               String createdAt) {
    }

    /**
     * Thread keyset 分页参数固定 Workspace，首屏 cursor 字段允许 null。
     *
     * @param workspaceId 工作区稳定标识
     * @param cursorPinned 游标对应条目的固定状态
     * @param cursorSortTime 分页游标使用的排序时间
     * @param cursorUpdatedAt 游标对应条目的更新时间
     * @param cursorId 游标标识
     * @param limit 返回条目数上限
     */
    public record ThreadPage(String workspaceId,
                             Integer cursorPinned,
                             String cursorSortTime,
                             String cursorUpdatedAt,
                             String cursorId,
                             int limit) {
    }

    /**
     * Session 主会话跨 Workspace 分页仍使用原 pinned/update keyset。
     *
     * @param cursorPinned 游标对应条目的固定状态
     * @param cursorSortTime 分页游标使用的排序时间
     * @param cursorUpdatedAt 游标对应条目的更新时间
     * @param cursorId 游标标识
     * @param limit 返回条目数上限
     */
    public record SessionThreadPage(Integer cursorPinned,
                                    String cursorSortTime,
                                    String cursorUpdatedAt,
                                    String cursorId,
                                    int limit) {
    }

    /**
     * Thread 标题搜索沿用更新时间 keyset，并把查询词固定为服务层归一化小写。
     *
     * @param workspaceId 工作区稳定标识
     * @param normalizedQuery 归一化查询
     * @param cursorTime 游标记录的时间
     * @param cursorId 游标标识
     * @param limit 返回条目数上限
     */
    public record ThreadSearch(String workspaceId,
                               String normalizedQuery,
                               String cursorTime,
                               String cursorId,
                               int limit) {
    }

    /**
     * Session 聚合搜索独立于单 Workspace 项目查询，保留同一 updatedAt keyset。
     *
     * @param normalizedQuery 归一化查询
     * @param cursorTime 游标记录的时间
     * @param cursorId 游标标识
     * @param limit 返回条目数上限
     */
    public record SessionThreadSearch(String normalizedQuery,
                                      String cursorTime,
                                      String cursorId,
                                      int limit) {
    }

    /**
     * 全局发现使用统一更新时间/身份 keyset，可选 Workspace 与标题 contains 过滤。
     *
     * @param workspaceId 工作区稳定标识
     * @param normalizedQuery 归一化查询
     * @param cursorTime 游标记录的时间
     * @param cursorId 游标标识
     * @param limit 返回条目数上限
     */
    public record ThreadDiscoveryPage(String workspaceId,
                                      String normalizedQuery,
                                      String cursorTime,
                                      String cursorId,
                                      int limit) {
    }

    /**
     * 输入历史从公开 USER timeline 作 keyset 分页，查询不物化整个 Thread。
     *
     * @param normalizedQuery 归一化查询
     * @param cursorTime 游标记录的时间
     * @param cursorId 游标标识
     * @param limit 返回条目数上限
     */
    public record InputHistoryPage(String normalizedQuery,
                                   String cursorTime,
                                   String cursorId,
                                   int limit) {
    }

    /**
     * 查询仅物化用户可见文本和导航身份，不读取 Provider 原始消息。
     *
     * @param itemId 条目标识
     * @param threadId 所属对话的稳定标识
     * @param publicText 公开文本
     * @param createdAt 创建时间
     * @param truncated 结果是否被截断
     */
    public record InputHistoryRow(String itemId,
                                  String threadId,
                                  String publicText,
                                  String createdAt,
                                  boolean truncated) {
    }

    /**
     * 用户重命名与自动标题共享 SQL 形状；placeholderOnly 为自动路径启用来源所有权 CAS，
     * expectedRevision 在该路径作为首次成功 Turn 的 revision 下界，而不是精确相等条件。
     *
     * @param threadId 所属对话的稳定标识
     * @param title 显示标题
     * @param titleSource 标题来源
     * @param expectedRevision 提交时预期的修订版本
     * @param occurredAt 事件发生时间
     * @param placeholderOnly 是否仅为占位配置
     */
    public record ThreadTitleCas(String threadId,
                                 String title,
                                 String titleSource,
                                 long expectedRevision,
                                 String occurredAt,
                                 boolean placeholderOnly) {
    }

    /**
     * 下一轮模型与权限偏好以单次 revision CAS 原子替换。
     *
     * @param threadId 所属对话的稳定标识
     * @param providerId 模型供应商标识
     * @param modelId 模型标识
     * @param reasoningLevel 模型推理等级
     * @param accessMode 访问模式
     * @param collaborationMode 协作模式
     * @param expectedRevision 提交时预期的修订版本
     * @param occurredAt 事件发生时间
     */
    public record ThreadPreferencesCas(String threadId,
                                       String providerId,
                                       String modelId,
                                       String reasoningLevel,
                                       String accessMode,
                                       String collaborationMode,
                                       long expectedRevision,
                                       String occurredAt) {
    }

    /**
     * 置顶只作用于 active Thread；null 时间明确表达取消置顶。
     *
     * @param threadId 所属对话的稳定标识
     * @param pinnedAt 固定时间
     * @param expectedRevision 提交时预期的修订版本
     * @param occurredAt 事件发生时间
     */
    public record ThreadPinCas(String threadId,
                               String pinnedAt,
                               long expectedRevision,
                               String occurredAt) {
    }

    /**
     * 已读写入只接受调用方看到的 Thread revision，真实 Turn 序号由 SQL 在同一事务内选取。
     *
     * @param threadId 所属对话的稳定标识
     * @param expectedRevision 提交时预期的修订版本
     * @param occurredAt 事件发生时间
     */
    public record ThreadSeenCas(String threadId,
                                long expectedRevision,
                                String occurredAt) {
    }

    /**
     * Thread 归档或删除的单一 lifecycle CAS 参数。
     *
     * @param threadId 所属对话的稳定标识
     * @param expectedRevision 提交时预期的修订版本
     * @param occurredAt 事件发生时间
     * @param delete 是否执行删除
     */
    public record ThreadLifecycle(String threadId,
                                  long expectedRevision,
                                  String occurredAt,
                                  boolean delete) {
    }

    /**
     * 恢复只接受已归档 Thread，并显式清除可能损坏的置顶事实。
     *
     * @param threadId 所属对话的稳定标识
     * @param expectedRevision 提交时预期的修订版本
     * @param occurredAt 事件发生时间
     */
    public record ThreadRestore(String threadId,
                                long expectedRevision,
                                String occurredAt) {
    }

    /**
     * 混合 Thread 快照的 keyset 分页参数。
     *
     * @param threadId 所属对话的稳定标识
     * @param cursorTime 游标记录的时间
     * @param cursorId 游标标识
     * @param limit 返回条目数上限
     */
    public record SnapshotPage(String threadId,
                               String cursorTime,
                               String cursorId,
                               int limit) {
    }

    /**
     * Fork 专用真实历史 keyset；选中锚点和游标都沿 Core 消息因果序，而不是同毫秒时间或随机 ID。
     *
     * @param threadId 来源 Thread
     * @param boundaryItemId 精确选中的已结算助手 item
     * @param cursorTurnSequence 上一页所属 Thread Turn 序号
     * @param cursorContextMessageOrdinal 上一页 Core conversation message 顺序
     * @param cursorSemanticOrder 同消息内公开摘要、正文和 Tool 的顺序
     * @param cursorOrdinalOrder 同 Tool 批次顺序
     * @param cursorTimelineSequence 同序 mailbox/公开项的稳定来源序号
     * @param cursorItemId 同序稳定 item tie breaker
     * @param limit 页面上限
     */
    public record ForkSnapshotPage(String threadId,
                                   String boundaryItemId,
                                   Long cursorTurnSequence,
                                   Long cursorContextMessageOrdinal,
                                   Integer cursorSemanticOrder,
                                   Long cursorOrdinalOrder,
                                   Long cursorTimelineSequence,
                                   String cursorItemId,
                                   int limit) {
    }

    /**
     * 客户端提交只保存安全回执与请求指纹，业务正文仍在原领域表中。
     *
     * @param operationKey 客户端操作标识
     * @param method 方法
     * @param requestFingerprint 请求指纹
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param threadRevision 对话修订版本
     * @param queued 是否已进入队列
     * @param approvalId 审批标识
     * @param decision 决策
     * @param createdAt 创建时间
     */
    public record ClientOperationRow(String operationKey,
                                     String method,
                                     String requestFingerprint,
                                     String threadId,
                                     String turnId,
                                     long threadRevision,
                                     boolean queued,
                                     String approvalId,
                                     String decision,
                                     String createdAt) {
    }

    /**
     * 入队回执只保留请求指纹和条目身份，不能把用户正文复制进账本。
     *
     * @param operationKey 客户端操作标识
     * @param requestFingerprint 请求指纹
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param inputId 输入标识
     * @param inputKind 输入类型
     * @param createdAt 创建时间
     */
    public record InputOperationRow(String operationKey,
                                    String requestFingerprint,
                                    String threadId,
                                    String turnId,
                                    String inputId,
                                    String inputKind,
                                    String createdAt) {
    }

    /**
     * Checkpoint source revision 复合身份。
     *
     * @param threadId 所属对话的稳定标识
     * @param sourceRevision 来源修订版本
     */
    public record CheckpointKey(String threadId,
                                long sourceRevision) {
    }

    /**
     * Append-only checkpoint 插入参数。
     *
     * @param checkpointId 检查点标识
     * @param threadId 所属对话的稳定标识
     * @param sourceRevision 来源修订版本
     * @param throughOrdinal 覆盖到的顺序位置
     * @param retainedFromOrdinal 保留区间的起始顺序位置
     * @param retainedSplitJson 保留消息拆分边界的 JSON
     * @param summaryJson 摘要 JSON 表示
     * @param estimatedTokens 估算的 Token 数
     * @param envelopeFingerprint 协议封装指纹
     * @param strategyVersion 策略版本
     * @param usageJson 用量 JSON 表示
     * @param createdAt 创建时间
     */
    public record CheckpointInsert(String checkpointId,
                                   String threadId,
                                   long sourceRevision,
                                   long throughOrdinal,
                                   long retainedFromOrdinal,
                                   String retainedSplitJson,
                                   String summaryJson,
                                   int estimatedTokens,
                                   String envelopeFingerprint,
                                   String strategyVersion,
                                   String usageJson,
                                   String createdAt) {
    }

    /**
     * Thread 指令 scope 复合身份。
     *
     * @param threadId 所属对话的稳定标识
     * @param relativeDirectory 相对于工作区的目录
     */
    public record ScopeKey(String threadId,
                           String relativeDirectory) {
    }

    /**
     * 指令 scope 原子限额插入参数。
     *
     * @param threadId 所属对话的稳定标识
     * @param relativeDirectory 相对于工作区的目录
     * @param discoveredAt 发现时间
     */
    public record ScopeInsert(String threadId,
                              String relativeDirectory,
                              String discoveredAt) {
    }
}
