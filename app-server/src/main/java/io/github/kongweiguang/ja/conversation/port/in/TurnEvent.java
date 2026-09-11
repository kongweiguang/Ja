// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.in;

import io.github.kongweiguang.ja.conversation.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.conversation.domain.AttachmentSummary;
import io.github.kongweiguang.ja.conversation.domain.InputQueue;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestUsage;
import io.github.kongweiguang.ja.conversation.domain.TurnChangeSet;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 除一次性流式草稿外，每个持久化事务至多发布一个 Provider 中立的 Turn 事件。
 */
public sealed interface TurnEvent permits TurnEvent.StateChanged, TurnEvent.ModelStepCommitted,
        TurnEvent.TextDelta, TurnEvent.ReasoningSummaryDelta, TurnEvent.ToolStarted,
        TurnEvent.ToolBatchCommitted,
        TurnEvent.ApprovalRequested, TurnEvent.ApprovalResolved, TurnEvent.InputQueueChanged,
        TurnEvent.InputConsumed, TurnEvent.MessagesReceived, TurnEvent.Terminal {
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
            case ModelStepCommitted value -> new ModelStepCommitted(replacement, value.messageId(),
                    value.text(), value.reasoningSummary(), value.modelRound(), value.usage(), value.toolCalls());
            case TextDelta value -> value;
            case ReasoningSummaryDelta value -> value;
            case ToolStarted value -> new ToolStarted(replacement, value.callId(), value.ordinal());
            case ToolBatchCommitted value -> new ToolBatchCommitted(replacement,
                    value.results());
            case ApprovalRequested value -> new ApprovalRequested(replacement, value.approvalId(), value.callId(),
                    value.toolName(), value.reason(), value.expiresAt());
            case ApprovalResolved value -> new ApprovalResolved(replacement, value.approvalId(), value.decision());
            case InputQueueChanged value -> new InputQueueChanged(replacement, value.inputQueue());
            case InputConsumed value -> new InputConsumed(replacement, value.input(), value.userItem(),
                    value.inputQueue(), value.assistantSettlement());
            case MessagesReceived value -> new MessagesReceived(replacement, value.items());
            case Terminal value -> new Terminal(replacement, value.state(), value.summary(), value.errorCode(),
                    value.errorMessage(), value.finalMessage(), value.usage(), value.changeSet());
        };
    }

    /**
     * 已提交事件的稳定身份、Turn 关联、Thread revision 与发生时刻。
     */
    record Context(String eventId, String threadId, String turnId, long threadRevision, Instant occurredAt) {
        /**
         * 固化公开关联链，禁止负 revision 或不符合新基线的标识。
         */
        public Context {
            eventId = identifier(eventId, "eventId", "evt_");
            threadId = identifier(threadId, "threadId", "thr_");
            turnId = identifier(turnId, "turnId", "turn_");
            if (threadRevision < 0) {
                throw new IllegalArgumentException("threadRevision must be non-negative");
            }
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /**
     * 已提交的合法 Turn 状态迁移。
     */
    record StateChanged(Context context, TurnState from, TurnState to) implements TurnEvent {
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

    /** 队列 CRUD 已提交；该事件只按 queue revision 收敛，不推进 Thread revision。 */
    record InputQueueChanged(Context context, InputQueue inputQueue) implements TurnEvent {
        /** 关联必须属于同一 Turn，防止跨 Turn 全量投影被错误覆盖。 */
        public InputQueueChanged {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(inputQueue, "inputQueue");
            if (!context.turnId().equals(inputQueue.turnId())) {
                throw new IllegalArgumentException("input queue turn mismatch");
            }
        }
    }

    /** 一条输入已从队列原子迁移到公开 Timeline，并携带消费后的权威队列。 */
    record InputConsumed(Context context, InputQueue.QueuedInput input, UserItem userItem,
                         InputQueue inputQueue, AssistantSettlement assistantSettlement) implements TurnEvent {
        /** 消费前条目、公开 item 与消费后队列必须保持同一 Turn 关联。 */
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
     */
    record MessagesReceived(Context context, List<ThreadSnapshot.ThreadMessageItem> items)
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

    /** 消费事务写入的公开 USER_INPUT Timeline 事实。 */
    record UserItem(String itemId, Instant createdAt, String turnId, UserContent content,
                    List<AttachmentSummary> attachments) {
        /** Timeline identity、完整内容与附件顺序必须能直接映射到 thread/read 的相同 item。 */
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

    /** STOP 边界消费时携带此前同事务结算的 Assistant，Tool 安全点消费时为空。 */
    record AssistantSettlement(String messageId, String text, int modelRound,
                               ProviderRequestUsage usage, String reasoningSummary) {
        /** 结算投影必须携带本次请求事实，避免客户端把最新 Assistant 关联到旧 Profile。 */
        public AssistantSettlement {
            messageId = identifier(messageId, "messageId", "item_");
            text = boundedText(text, "text", 1_048_576, true);
            if (modelRound < 1 || modelRound > 128) {
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
     */
    record ModelStepCommitted(Context context, String messageId, String text, String reasoningSummary, int modelRound,
                              ProviderRequestUsage usage, List<ToolCall> toolCalls) implements TurnEvent {
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
            if (modelRound < 1 || modelRound > 128) {
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
     * 模型轮次内已完成组装的 Tool 调用及其原始顺序。
     */
    record ToolCall(String callId, String toolName, ToolPresentation presentation, int ordinal) {
        /**
         * 复制结构化参数并限制序号，保证持久化和并发归并确定性。
         */
        public ToolCall {
            callId = identifier(callId, "callId", "call_");
            toolName = boundedText(toolName, "toolName", 512, false);
            Objects.requireNonNull(presentation, "presentation");
            if (ordinal < 0 || ordinal > 1_023) {
                throw new IllegalArgumentException("ordinal is outside the turn bound");
            }
        }
    }

    /**
     * 未提交的 assistant 文本草稿，只按 Turn 和流序号关联。
     */
    record TextDelta(String turnId, long streamSeq, String text) implements TurnEvent {
        /**
         * 要求正向单调序号和非空有界片段，避免无效帧占用通知队列。
         */
        public TextDelta {
            turnId = identifier(turnId, "turnId", "turn_");
            if (streamSeq < 1) {
                throw new IllegalArgumentException("streamSeq must be positive");
            }
            text = boundedText(text, "text", 1_000_000, false);
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
     * 未提交的公开推理摘要草稿，不包含 Provider 隐藏推理。
     */
    record ReasoningSummaryDelta(String turnId, long streamSeq, String text) implements TurnEvent {
        /**
         * 与文本草稿共享流序约束，防止重放或空片段进入投影。
         */
        public ReasoningSummaryDelta {
            turnId = identifier(turnId, "turnId", "turn_");
            if (streamSeq < 1) {
                throw new IllegalArgumentException("streamSeq must be positive");
            }
            text = boundedText(text, "text", 1_000_000, false);
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
     */
    record ToolStarted(Context context, String callId, int ordinal) implements TurnEvent {
        /**
         * callId 与 Turn 全局 ordinal 共同关联 Prepared 行，避免连续 Tool 的 started 通知串线。
         */
        public ToolStarted {
            Objects.requireNonNull(context, "context");
            callId = identifier(callId, "callId", "call_");
            if (ordinal < 0 || ordinal > 1_023) {
                throw new IllegalArgumentException("ordinal is outside the turn bound");
            }
        }
    }

    /**
     * 一批 Tool 结果与工作区脏状态已经原子提交。
     */
    record ToolBatchCommitted(Context context, List<ToolBatchResult> results) implements TurnEvent {
        /**
         * 要求非空结果；文件变化由独立 TurnChangeSet 事实提供，不能从 Tool 名称推断。
         */
        public ToolBatchCommitted {
            Objects.requireNonNull(context, "context");
            results = List.copyOf(Objects.requireNonNull(results, "results"));
            if (results.isEmpty()) throw new IllegalArgumentException("Tool batch must not be empty");
        }
    }

    /**
     * 单个 Tool 的终态、安全输出、顺序与可选错误码。
     */
    record ToolBatchResult(String callId, ToolOutcome outcome, ToolPresentation presentation, int ordinal,
                           String errorCode) {
        /**
         * 限制内容和错误码大小，禁止 Adapter 异常或无界响应越过端口。
         */
        public ToolBatchResult {
            callId = identifier(callId, "callId", "call_");
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
     */
    record ApprovalRequested(Context context, String approvalId, String callId, String toolName, String reason,
                             Instant expiresAt) implements TurnEvent {
        /**
         * 固定审批、调用与过期时间关联，避免响应被投递到另一 Tool。
         */
        public ApprovalRequested {
            Objects.requireNonNull(context, "context");
            approvalId = identifier(approvalId, "approvalId", "appr_");
            callId = identifier(callId, "callId", "call_");
            toolName = boundedText(toolName, "toolName", 512, false);
            reason = boundedText(reason, "reason", 16_384, false);
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /**
     * 用户决定已被 exactly-once 接受并持久提交。
     */
    record ApprovalResolved(Context context, String approvalId, ApprovalDecision decision)
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
     * Turn 唯一终态及其最终消息、错误投影和累计用量。
     */
    record Terminal(Context context, TurnState state, String summary, String errorCode, String errorMessage,
                    FinalMessage finalMessage, ProviderRequestUsage usage, TurnChangeSet changeSet)
            implements TurnEvent {
        /**
         * 只接受与终态类别一致的公开投影：成功和失败必须有最终消息，失败还必须有稳定错误，
         * 取消不得伪装为失败；在领域端口处收紧可避免各 transport 重复猜测条件字段。
         */
        public Terminal {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(changeSet, "changeSet");
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
                    if (finalMessage == null || errorCode != null || errorMessage != null) {
                        throw new IllegalArgumentException("completed terminal has invalid projection");
                    }
                }
                case FAILED -> {
                    if (finalMessage == null || errorCode == null || errorMessage == null) {
                        throw new IllegalArgumentException("failed terminal has invalid projection");
                    }
                }
                case CANCELLED -> {
                    if (finalMessage != null || errorCode != null || errorMessage != null) {
                        throw new IllegalArgumentException("cancelled terminal has invalid projection");
                    }
                }
                case QUEUED, RUNNING, WAITING_APPROVAL -> throw new IllegalArgumentException("terminal state required");
            }
        }

        /** 既有测试构造也必须获得明确 complete 空 ChangeSet，禁止终态继续出现缺失事实。 */
        public Terminal(Context context, TurnState state, String summary, String errorCode, String errorMessage,
                        FinalMessage finalMessage, ProviderRequestUsage usage) {
            this(context, state, summary, errorCode, errorMessage, finalMessage, usage,
                    TurnChangeSet.emptyComplete());
        }
    }

    /**
     * 成功 Turn 最终持久化的 assistant 消息公开投影。
     */
    record FinalMessage(String messageId, String text) {
        /**
         * 绑定消息标识与有界正文，避免终态通知回读数据库才能展示。
         */
        public FinalMessage {
            messageId = identifier(messageId, "messageId", "item_");
            text = boundedText(text, "text", 1_048_576, true);
        }
    }

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

}
