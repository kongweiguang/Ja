// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.in;

import io.github.kongweiguang.ja.conversation.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.conversation.domain.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 除一次性流式草稿外，每个持久化事务至多发布一个 Provider 中立的 Turn 事件。
 */
public sealed interface TurnEvent permits TurnEvent.StateChanged, TurnEvent.ModelStepCommitted,
        TurnEvent.TextDelta, TurnEvent.ReasoningSummaryDelta, TurnEvent.ToolBatchCommitted,
        TurnEvent.ApprovalRequested, TurnEvent.ApprovalResolved, TurnEvent.Terminal {
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
            case ToolBatchCommitted value -> new ToolBatchCommitted(replacement,
                    value.results());
            case ApprovalRequested value -> new ApprovalRequested(replacement, value.approvalId(), value.callId(),
                    value.toolName(), value.reason(), value.expiresAt());
            case ApprovalResolved value -> new ApprovalResolved(replacement, value.approvalId(), value.decision());
            case Terminal value -> new Terminal(replacement, value.state(), value.summary(), value.errorCode(),
                    value.errorMessage(), value.finalMessage(), value.usage());
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

    /**
     * 包含 Tool 调用的完整模型轮次已经持久提交。
     */
    record ModelStepCommitted(Context context, String messageId, String text, String reasoningSummary, int modelRound,
                              ModelUsage usage, List<ToolCall> toolCalls) implements TurnEvent {
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
                    FinalMessage finalMessage, TerminalUsage usage)
            implements TurnEvent {
        /**
         * 只接受与终态类别一致的公开投影：成功必须有最终消息，失败必须有稳定错误，
         * 取消不得伪装为失败；在领域端口处收紧可避免各 transport 重复猜测条件字段。
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
                    if (finalMessage == null || errorCode != null || errorMessage != null) {
                        throw new IllegalArgumentException("completed terminal has invalid projection");
                    }
                }
                case FAILED -> {
                    if (finalMessage != null || errorCode == null || errorMessage == null) {
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
     * Turn 结束时的累计 Provider 用量与最后模型轮次。
     */
    record TerminalUsage(ModelUsage usage, int modelRound) {
        /**
         * 限制轮次并要求权威用量，避免终态统计与持久事实不一致。
         */
        public TerminalUsage {
            Objects.requireNonNull(usage, "usage");
            if (modelRound < 1 || modelRound > 128) {
                throw new IllegalArgumentException("modelRound is outside the turn bound");
            }
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
