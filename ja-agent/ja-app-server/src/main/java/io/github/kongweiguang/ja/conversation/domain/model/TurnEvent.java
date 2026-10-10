// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolRecoveryDecision;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 除一次性流式草稿外，每个持久化事务至多发布一个 Provider 中立的 Turn 事件。
 */
public sealed interface TurnEvent permits TurnEvent.StateChanged, TurnEvent.ModelStepCommitted,
        TurnEvent.ContextUsageEstimated, TurnEvent.TextDelta, TurnEvent.ReasoningSummaryDelta,
        TurnEvent.RetryStarted, TurnEvent.ToolStarted,
        TurnEvent.ToolBatchCommitted,
        TurnEvent.ApprovalRequested, TurnEvent.ApprovalResolved, TurnEvent.InputQueueChanged,
        TurnEvent.InputConsumed, TurnEvent.MessagesReceived, TurnEvent.ExtensionEvent,
        TurnEvent.ApprovalClosed, TurnEvent.ToolRecoveryChanged, TurnEvent.Terminal {
    /**
     * 校验新存储基线的类型前缀和稳定标识词汇，不接受旧别名。
     */
    private static String identifier(String value, String field, String prefix) {
        if (value == null || !value.startsWith(prefix) || value.length() > 108
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 在通知入队前限制非可信文本，并额外禁止 Tool 名称包含换行。
     */
    private static String boundedText(String value, String field, int maximum, boolean allowEmpty) {
        if (value == null || (!allowEmpty && value.isEmpty()) || value.length() > maximum
            || value.indexOf('\0') >= 0
            || ("toolName".equals(field) && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0))) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 返回已提交事件的持久化上下文；一次性流式草稿没有事务上下文。
     */
    Context context();

    /**
     * 用提交事务返回的权威 revision 替换上下文；流式草稿保持原样。
     */
    default TurnEvent withContext(Context replacement) {
        Objects.requireNonNull(replacement, "replacement");
        return switch (this) {
            case StateChanged value -> new StateChanged(replacement, value.from(), value.to());
            case ContextUsageEstimated value -> new ContextUsageEstimated(
                    replacement, value.usage(), value.estimatedTokens());
            case ModelStepCommitted value -> new ModelStepCommitted(replacement, value.messageId(),
                    value.text(), value.reasoningSummary(), value.modelRound(), value.usage(), value.toolCalls());
            case TextDelta value -> value;
            case ReasoningSummaryDelta value -> value;
            case RetryStarted value -> new RetryStarted(replacement, value.attempt());
            case ToolStarted value -> new ToolStarted(replacement, value.callId(), value.ordinal());
            case ToolBatchCommitted value -> new ToolBatchCommitted(replacement,
                    value.results());
            case ApprovalRequested value -> new ApprovalRequested(replacement, value.approvalId(), value.callId(),
                    value.toolName(), value.presentation(), value.reason(), value.expiresAt());
            case ApprovalResolved value -> new ApprovalResolved(replacement, value.approvalId(), value.decision());
            case ApprovalClosed value -> new ApprovalClosed(replacement, value.approvalId(), value.reason());
            case ToolRecoveryChanged value -> new ToolRecoveryChanged(replacement, value.recoveryId(), value.callId(),
                    value.revision(), value.status(), value.disposition());
            case InputQueueChanged value -> new InputQueueChanged(replacement, value.inputQueue());
            case InputConsumed value -> new InputConsumed(replacement, value.input(), value.userItem(),
                    value.inputQueue(), value.assistantSettlement());
            case MessagesReceived value -> new MessagesReceived(replacement, value.items());
            case ExtensionEvent value ->
                    new ExtensionEvent(replacement, value.namespace(), value.type(), value.payload());
            case Terminal value -> new Terminal(replacement, value.state(), value.summary(), value.errorCode(),
                    value.errorMessage(), value.finalMessage(), value.usage(), value.changeSet());
        };
    }

    /**
     * 已提交事件的稳定身份、Turn 关联、Thread revision、Turn mutation 水位与发生时刻。
     *
     * <p>mutation 水位只服务 Java 内部的恢复 fence，不进入 JA-RPC wire。Thread revision 可能被
     * metadata 或不可见事务跳号，不能单独证明该 Turn 的正文已经提交；把同一 CAS 回执返回的
     * mutation version 随事件携带，read 才能识别“已持久化但事件尚未送达”的窗口。</p>
     *
     * @param eventId 事件标识
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param threadRevision 对话修订版本
     * @param turnMutationVersion 当前执行的状态变更版本
     * @param occurredAt 事件发生时间
     */
    record Context(String eventId,
                   String threadId,
                   String turnId,
                   long threadRevision,
                   long turnMutationVersion,
                   Instant occurredAt) {
        /**
         * 固化公开关联链，禁止负 revision 或不符合新基线的标识。
         */
        public Context {
            eventId = identifier(eventId, "eventId", "evt_");
            threadId = identifier(threadId, "threadId", "thr_");
            turnId = identifier(turnId, "turnId", "turn_");
            if (threadRevision < 0 || turnMutationVersion < -1) {
                throw new IllegalArgumentException("event revisions are invalid");
            }
            Objects.requireNonNull(occurredAt, "occurredAt");
        }

    }

    /**
     * 已提交的合法 Turn 状态迁移。
     *
     * @param context 当前操作的上下文
     * @param from 起点
     * @param to 目标
     */
    record StateChanged(Context context,
                        TurnState from,
                        TurnState to) implements TurnEvent {
        /**
         * 仅允许领域状态机声明的非自环迁移进入公开事件流。
         */
        public StateChanged {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (from == to || !from.canTransitionTo(to)) {
                throw new IllegalArgumentException("illegal public turn transition");
            }
        }
    }

    /**
     * 队列 CRUD 已提交；该事件只按 queue revision 收敛，不推进 Thread revision。
     *
     * @param context 当前操作的上下文
     * @param inputQueue 输入队列
     */
    record InputQueueChanged(Context context,
                             InputQueue inputQueue) implements TurnEvent {
        /**
         * 关联必须属于同一 Turn，防止跨 Turn 全量投影被错误覆盖。
         */
        public InputQueueChanged {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(inputQueue, "inputQueue");
            if (!context.turnId().equals(inputQueue.turnId())) {
                throw new IllegalArgumentException("input queue turn mismatch");
            }
        }
    }

    /**
     * 一条输入已从队列原子迁移到公开 Timeline，并携带消费后的权威队列。
     *
     * @param context 当前操作的上下文
     * @param input 输入内容
     * @param userItem 用户条目
     * @param inputQueue 输入队列
     * @param assistantSettlement 助手结算
     */
    record InputConsumed(Context context,
                         InputQueue.QueuedInput input,
                         UserItem userItem,
                         InputQueue inputQueue,
                         AssistantSettlement assistantSettlement) implements TurnEvent {
        /**
         * 消费前条目、公开 item 与消费后队列必须保持同一 Turn 关联。
         */
        public InputConsumed {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(userItem, "userItem");
            Objects.requireNonNull(inputQueue, "inputQueue");
            if (!context.turnId().equals(input.turnId()) || !context.turnId().equals(userItem.turnId())
                || !context.turnId().equals(inputQueue.turnId())) {
                throw new IllegalArgumentException("consumed input turn mismatch");
            }
        }
    }

    /**
     * Mailbox 消费事务提交后的真实跨会话消息；不创建 InputConsumed 或 Activity，避免伪造用户动作。
     *
     * @param context 当前操作的上下文
     * @param items 条目列表
     */
    record MessagesReceived(Context context,
                            List<ThreadSnapshot.ThreadMessageItem> items)
            implements TurnEvent {
        /**
         * 批次必须非空且全部属于当前目标 Turn，顺序沿用 Mailbox sequence 的提交顺序。
         */
        public MessagesReceived {
            Objects.requireNonNull(context, "context");
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            if (items.isEmpty() || items.size() > 256) {
                throw new IllegalArgumentException("invalid received message batch");
            }
            if (items.stream().anyMatch(item -> !context.turnId().equals(item.turnId()))) {
                throw new IllegalArgumentException("received message turn mismatch");
            }
        }
    }

    /**
     * 供宿主投影非核心持久事件的命名空间载荷，不把 Ja 邮箱 DTO 带入通用事件类型。
     *
     * @param context 当前操作的上下文
     * @param namespace 命名空间
     * @param type 类型标识
     * @param payload 负载
     */
    record ExtensionEvent(Context context,
                          String namespace,
                          String type,
                          JsonObject payload) implements TurnEvent {
        /**
         * 在边界验证命名空间与 JSON 载荷，防止未校验扩展数据进入事件订阅。
         */
        public ExtensionEvent {
            Objects.requireNonNull(context, "context");
            namespace = ContractChecks.identifier(namespace, "namespace");
            type = ContractChecks.identifier(type, "type");
            Objects.requireNonNull(payload, "payload");
        }
    }

    /**
     * 消费事务写入的公开 USER_INPUT Timeline 事实。
     *
     * @param itemId 条目标识
     * @param createdAt 创建时间
     * @param turnId 本次可恢复执行的稳定标识
     * @param content 内容
     * @param attachments 附件列表
     */
    record UserItem(String itemId,
                    Instant createdAt,
                    String turnId,
                    UserContent content,
                    List<AttachmentSummary> attachments) {
        /**
         * Timeline identity、完整内容与附件顺序必须能直接映射到 thread/read 的相同 item。
         */
        public UserItem {
            itemId = identifier(itemId, "itemId", "item_");
            Objects.requireNonNull(createdAt, "createdAt");
            turnId = identifier(turnId, "turnId", "turn_");
            Objects.requireNonNull(content, "content");
            attachments = List.copyOf(Objects.requireNonNull(attachments, "attachments"));
            if (!content.attachmentIds().equals(attachments.stream()
                    .map(AttachmentSummary::attachmentId).toList())) {
                throw new IllegalArgumentException("user item attachment summaries do not match content");
            }
        }
    }

    /**
     * STOP 边界消费时携带此前同事务结算的 Assistant，Tool 安全点消费时为空。
     *
     * @param messageId 消息标识
     * @param text 文本内容
     * @param modelRound 模型调用轮次
     * @param usage 用量
     * @param reasoningSummary 推理摘要
     */
    record AssistantSettlement(String messageId,
                               String text,
                               int modelRound,
                               ProviderRequestUsage usage,
                               String reasoningSummary) {
        /**
         * 结算投影必须携带本次请求事实，避免客户端把最新 Assistant 关联到旧 Profile。
         */
        public AssistantSettlement {
            messageId = identifier(messageId, "messageId", "item_");
            text = boundedText(text, "text", 1_048_576, true);
            if (modelRound < 1) {
                throw new IllegalArgumentException("modelRound is outside the turn bound");
            }
            Objects.requireNonNull(usage, "usage");
            if (reasoningSummary != null) {
                reasoningSummary = boundedText(reasoningSummary, "reasoningSummary", 1_048_576, false);
            }
        }
    }

    /**
     * 包含 Tool 调用的完整模型轮次已经持久提交。
     *
     * @param context 当前操作的上下文
     * @param messageId 消息标识
     * @param text 文本内容
     * @param reasoningSummary 推理摘要
     * @param modelRound 模型调用轮次
     * @param usage 用量
     * @param toolCalls 工具调用列表
     */
    record ModelStepCommitted(Context context,
                              String messageId,
                              String text,
                              String reasoningSummary,
                              int modelRound,
                              ProviderRequestUsage usage,
                              List<ToolCall> toolCalls) implements TurnEvent {
        /**
         * 要求至少一个完整 Tool 调用，纯文本最终消息由 Terminal 承载。
         */
        public ModelStepCommitted {
            Objects.requireNonNull(context, "context");
            messageId = identifier(messageId, "messageId", "item_");
            text = boundedText(text, "text", 1_048_576, true);
            if (reasoningSummary != null) {
                reasoningSummary = boundedText(reasoningSummary, "reasoningSummary", 1_048_576, false);
            }
            if (modelRound < 1) {
                throw new IllegalArgumentException("modelRound is outside the turn bound");
            }
            Objects.requireNonNull(usage, "usage");
            toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
            if (toolCalls.isEmpty()) {
                throw new IllegalArgumentException("model step requires Tool calls");
            }
        }
    }

    /**
     * Provider dispatch 前发布完整输入 envelope 的本地估算，让 Composer 不沿用上一轮上下文。
     *
     * @param context 当前操作的上下文
     * @param usage 用量
     * @param estimatedTokens 估算的 Token 数
     */
    record ContextUsageEstimated(Context context,
                                 ProviderRequestUsage usage,
                                 long estimatedTokens)
            implements TurnEvent {
        /**
         * 估算必须绑定当前 Assistant UNKNOWN 请求并复用其模型窗口，不能伪装成已计费 Usage。
         */
        public ContextUsageEstimated {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(usage, "usage");
            if (usage.purpose() != ProviderRequestUsage.Purpose.ASSISTANT
                || usage.certainty() != ProviderRequestUsage.Certainty.UNKNOWN
                || estimatedTokens < 0 || estimatedTokens > 9_007_199_254_740_991L) {
                throw new IllegalArgumentException("invalid local context estimate event");
            }
        }
    }

    /**
     * 流失败后清空未提交草稿，并在下一次 Provider dispatch 前展示一次轻量重试状态。
     *
     * @param context 当前操作的上下文
     * @param attempt 尝试次数
     */
    record RetryStarted(Context context,
                        int attempt) implements TurnEvent {
        /**
         * 重试至少从第二次尝试开始；次数不作为停止条件。
         */
        public RetryStarted {
            Objects.requireNonNull(context, "context");
            if (attempt < 2) {
                throw new IllegalArgumentException("invalid retry attempt");
            }
        }
    }

    /**
     * 模型轮次内已完成组装的 Tool 调用及其原始顺序。
     *
     * @param callId 模型生成的工具调用标识
     * @param toolName 工具名称
     * @param presentation 展示信息
     * @param ordinal 当前项的顺序位置
     */
    record ToolCall(String callId,
                    String toolName,
                    ToolPresentation presentation,
                    int ordinal) {
        /**
         * 保留 Provider 原生 callId，复制结构化参数并限制序号，使持久化和并发归并确定。
         */
        public ToolCall {
            callId = JaToolCallId.require(callId);
            toolName = boundedText(toolName, "toolName", 512, false);
            Objects.requireNonNull(presentation, "presentation");
            if (ordinal < 0 || ordinal > 1_023) {
                throw new IllegalArgumentException("ordinal is outside the turn bound");
            }
        }
    }

    /**
     * 未提交的 assistant 文本草稿，保留模型请求身份供 Bridge 对齐持久估算，不进入 RPC。
     *
     * @param turnId 本次可恢复执行的稳定标识
     * @param streamSeq 临时流事件序号
     * @param text 文本内容
     * @param modelRequestId 当前 Provider 请求标识
     */
    record TextDelta(String turnId,
                     long streamSeq,
                     String text,
                     String modelRequestId) implements TurnEvent {
        /**
         * 要求正向单调序号和非空有界片段，避免无效帧占用通知队列。
         */
        public TextDelta {
            turnId = identifier(turnId, "turnId", "turn_");
            if (streamSeq < 1) {
                throw new IllegalArgumentException("streamSeq must be positive");
            }
            text = boundedText(text, "text", 1_000_000, false);
            modelRequestId = ContractChecks.identifier(modelRequestId, "modelRequestId");
        }

        /**
         * 流式草稿尚无持久化 revision，因此显式返回空上下文。
         */
        @Override
        public Context context() {
            return null;
        }
    }

    /**
     * 未提交的公开推理摘要草稿，不包含 Provider 隐藏推理，并保留内部请求关联。
     *
     * @param turnId 本次可恢复执行的稳定标识
     * @param streamSeq 临时流事件序号
     * @param text 文本内容
     * @param modelRequestId 当前 Provider 请求标识
     */
    record ReasoningSummaryDelta(String turnId,
                                 long streamSeq,
                                 String text,
                                 String modelRequestId) implements TurnEvent {
        /**
         * 与文本草稿共享流序约束，防止重放或空片段进入投影。
         */
        public ReasoningSummaryDelta {
            turnId = identifier(turnId, "turnId", "turn_");
            if (streamSeq < 1) {
                throw new IllegalArgumentException("streamSeq must be positive");
            }
            text = boundedText(text, "text", 1_000_000, false);
            modelRequestId = ContractChecks.identifier(modelRequestId, "modelRequestId");
        }

        /**
         * 流式摘要尚无持久化 revision，因此显式返回空上下文。
         */
        @Override
        public Context context() {
            return null;
        }
    }

    /**
     * 单个 Tool 已越过执行边界，并已把内部状态与安全展示投影原子提交为运行中。
     *
     * @param context 当前操作的上下文
     * @param callId 模型生成的工具调用标识
     * @param ordinal 当前项的顺序位置
     */
    record ToolStarted(Context context,
                       String callId,
                       int ordinal) implements TurnEvent {
        /**
         * 按原值校验 Provider callId，并以 Turn 全局 ordinal 关联 Prepared 行，避免调用串线。
         */
        public ToolStarted {
            Objects.requireNonNull(context, "context");
            callId = JaToolCallId.require(callId);
            if (ordinal < 0 || ordinal > 1_023) {
                throw new IllegalArgumentException("ordinal is outside the turn bound");
            }
        }
    }

    /**
     * 一批 Tool 结果与工作区脏状态已经原子提交。
     *
     * @param context 当前操作的上下文
     * @param results 结果列表
     */
    record ToolBatchCommitted(Context context,
                              List<ToolBatchResult> results) implements TurnEvent {
        /**
         * 要求非空结果；文件变化由独立 TurnChangeSet 事实提供，不能从 Tool 名称推断。
         */
        public ToolBatchCommitted {
            Objects.requireNonNull(context, "context");
            results = List.copyOf(Objects.requireNonNull(results, "results"));
            if (results.isEmpty()) {
                throw new IllegalArgumentException("Tool batch must not be empty");
            }
        }
    }

    /**
     * 单个 Tool 的终态、安全输出、顺序与可选错误码。
     *
     * @param callId 模型生成的工具调用标识
     * @param outcome 执行结果
     * @param presentation 展示信息
     * @param ordinal 当前项的顺序位置
     * @param errorCode 错误代码
     */
    record ToolBatchResult(String callId,
                           ToolOutcome outcome,
                           ToolPresentation presentation,
                           int ordinal,
                           String errorCode) {
        /**
         * 按原值校验 Provider callId，并限制内容与错误码大小，禁止无界 Tool 响应越过端口。
         */
        public ToolBatchResult {
            callId = JaToolCallId.require(callId);
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(presentation, "presentation");
            if (ordinal < 0 || ordinal > 1_023) {
                throw new IllegalArgumentException("ordinal is outside the turn bound");
            }
            if (errorCode != null) {
                errorCode = boundedText(errorCode, "errorCode", 128, false);
            }
        }
    }

    /**
     * Tool 执行暂停并等待用户决定的已提交事实。
     *
     * @param context 当前操作的上下文
     * @param approvalId 审批标识
     * @param callId 模型生成的工具调用标识
     * @param toolName 工具名称
     * @param presentation 已校验并脱敏的待执行工具展示事实
     * @param reason 原因说明
     * @param expiresAt 过期时间
     */
    record ApprovalRequested(Context context,
                             String approvalId,
                             String callId,
                             String toolName,
                             ToolPresentation presentation,
                             String reason,
                             Instant expiresAt) implements TurnEvent {
        /**
         * 保留 Provider 原生 callId 和被冻结的安全展示事实，使审批确认展示与实际待执行 Tool 一致。
         */
        public ApprovalRequested {
            Objects.requireNonNull(context, "context");
            approvalId = identifier(approvalId, "approvalId", "appr_");
            callId = JaToolCallId.require(callId);
            toolName = boundedText(toolName, "toolName", 512, false);
            Objects.requireNonNull(presentation, "presentation");
            if (presentation.status() != ToolPresentation.Status.PENDING || !presentation.media().isEmpty()) {
                throw new IllegalArgumentException("approval presentation must be pending and contain no media");
            }
            reason = boundedText(reason, "reason", 16_384, false);
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /**
     * 用户决定已被 exactly-once 接受并持久提交。
     *
     * @param context 当前操作的上下文
     * @param approvalId 审批标识
     * @param decision 决策
     */
    record ApprovalResolved(Context context,
                            String approvalId,
                            ApprovalDecision decision)
            implements TurnEvent {
        /**
         * 要求稳定审批标识和闭集决定，迟到处理由应用协调器负责。
         */
        public ApprovalResolved {
            Objects.requireNonNull(context, "context");
            approvalId = identifier(approvalId, "approvalId", "appr_");
            Objects.requireNonNull(decision, "decision");
        }
    }

    /**
     * 等待被取消、替代或到期时关闭审批卡片，不伪装成用户拒绝。
     *
     * @param context 当前操作的上下文
     * @param approvalId 审批标识
     * @param reason 原因说明
     */
    record ApprovalClosed(Context context,
                          String approvalId,
                          InteractionStatus reason) implements TurnEvent {
        /**
         * 只接受持久等待状态机允许的关闭原因，用户拒绝仍由 ApprovalResolved 表示。
         */
        public ApprovalClosed {
            Objects.requireNonNull(context, "context");
            approvalId = identifier(approvalId, "approvalId", "appr_");
            if (reason != InteractionStatus.CANCELLED && reason != InteractionStatus.SUPERSEDED
                && reason != InteractionStatus.EXPIRED) {
                throw new IllegalArgumentException("approval close reason is invalid");
            }
        }
    }

    /**
     * 工具副作用结果未知时公开可恢复状态；不把未知执行伪装成失败或完成。
     *
     * @param context 当前操作的上下文
     * @param recoveryId 恢复标识
     * @param callId 模型生成的工具调用标识
     * @param revision 当前修订版本
     * @param status 当前状态分类
     * @param disposition 处置方式
     */
    record ToolRecoveryChanged(Context context,
                               String recoveryId,
                               String callId,
                               long revision,
                               ToolRecoveryDecision.Status status,
                               ToolRecoveryDecision.Disposition disposition) implements TurnEvent {
        /**
         * 身份与状态必须匹配 Core 持久裁决，只有未指定 disposition 的 PENDING 才开放用户动作。
         */
        public ToolRecoveryChanged {
            Objects.requireNonNull(context, "context");
            recoveryId = ContractChecks.identifier(recoveryId, "recoveryId");
            callId = JaToolCallId.require(callId);
            if (revision < 1) {
                throw new IllegalArgumentException("invalid recovery revision");
            }
            Objects.requireNonNull(status, "status");
            boolean matches = switch (status) {
                case PENDING -> disposition == null || disposition == ToolRecoveryDecision.Disposition.RETRY;
                case VERIFIED -> disposition == ToolRecoveryDecision.Disposition.VERIFIED;
                case RETRIED -> disposition == ToolRecoveryDecision.Disposition.RETRY;
                case SKIPPED -> disposition == ToolRecoveryDecision.Disposition.SKIP;
            };
            if (!matches) {
                throw new IllegalArgumentException("recovery status and disposition disagree");
            }
        }
    }

    /**
     * Turn 唯一终态及其最终消息、错误投影和累计用量。
     *
     * @param context 当前操作的上下文
     * @param state 当前状态
     * @param summary 摘要
     * @param errorCode 错误代码
     * @param errorMessage 错误消息
     * @param finalMessage 最终消息
     * @param usage 用量
     * @param changeSet 变更集合
     */
    record Terminal(Context context,
                    TurnState state,
                    String summary,
                    String errorCode,
                    String errorMessage,
                    FinalMessage finalMessage,
                    ProviderRequestUsage usage,
                    TurnChangeSet changeSet)
            implements TurnEvent {
        /**
         * 只接受与终态类别一致的公开投影：成功必须带最终消息和已提交 ChangeSet，失败允许缺少最终消息或 ChangeSet
         * 但必须保留稳定错误，取消不得伪装为失败；领域端口收紧条件字段，避免 transport 猜测或合成事实。
         */
        public Terminal {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(state, "state");
            if (!state.terminal()) {
                throw new IllegalArgumentException("terminal state required");
            }
            summary = boundedText(summary == null ? "" : summary, "summary", 1_000_000, true);
            if (errorCode != null) {
                errorCode = boundedText(errorCode, "errorCode", 128, false);
            }
            if (errorMessage != null) {
                errorMessage = boundedText(errorMessage, "errorMessage", 65_536, true);
            }
            switch (state) {
                case COMPLETED -> {
                    if (finalMessage == null || changeSet == null || errorCode != null || errorMessage != null) {
                        throw new IllegalArgumentException("completed terminal has invalid projection");
                    }
                }
                case FAILED -> {
                    if (errorCode == null || errorMessage == null) {
                        throw new IllegalArgumentException("failed terminal has invalid projection");
                    }
                }
                case CANCELLED -> {
                    if (finalMessage != null || errorCode != null || errorMessage != null) {
                        throw new IllegalArgumentException("cancelled terminal has invalid projection");
                    }
                }
                case QUEUED, RUNNING, WAITING_APPROVAL, WAITING_INPUT ->
                        throw new IllegalArgumentException("terminal state required");
            }
        }

    }

    /**
     * 成功 Turn 最终持久化的 assistant 消息公开投影。
     *
     * @param messageId 消息标识
     * @param text 文本内容
     */
    record FinalMessage(String messageId,
                        String text) {
        /**
         * 绑定消息标识与有界正文，避免终态通知回读数据库才能展示。
         */
        public FinalMessage {
            messageId = identifier(messageId, "messageId", "item_");
            text = boundedText(text, "text", 1_048_576, true);
        }
    }

}

