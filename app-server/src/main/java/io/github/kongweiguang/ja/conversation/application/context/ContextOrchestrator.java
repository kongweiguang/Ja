// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context;

import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointStore;
import io.github.kongweiguang.ja.conversation.application.context.compaction.ContextCompactionService;
import io.github.kongweiguang.ja.conversation.application.context.compaction.OverflowRecovery;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryDocument;
import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
import io.github.kongweiguang.ja.conversation.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 绑定单个 Thread 的上下文编排入口，确保 Checkpoint 回执先于 Provider 调用发布。
 */
public final class ContextOrchestrator {
    private final OverflowRecovery recovery;
    private final String boundThreadId;

    /**
     * 将恢复状态机和 Thread 身份固定在 Turn 生命周期内，防止跨 Thread 误用压缩服务。
     */
    ContextOrchestrator(ContextCompactionService compaction, String boundThreadId) {
        this.recovery = new OverflowRecovery(Objects.requireNonNull(compaction, "compaction"));
        this.boundThreadId = requiredThread(boundThreadId);
    }

    /**
     * 完成预算规划、必要压缩和至多一次溢出恢复，并返回与实际提示绑定的执行证据。
     */
    public <T> Execution<T> execute(Request request, CheckpointReceiptSink receiptSink,
                                    PromptSender<T> sender) {
        return execute(request, receiptSink, sender, null, ContextCompactionEvent.Trigger.AUTOMATIC);
    }

    /**
     * 将共享生命周期发布器绑定到实际压缩状态机，确保 Handler、UI 或 Provider adapter 不推测事件阶段。
     */
    public <T> Execution<T> execute(Request request, CheckpointReceiptSink receiptSink,
                                    PromptSender<T> sender, ContextCompactionLifecycle lifecycle,
                                    ContextCompactionEvent.Trigger trigger) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(receiptSink, "receiptSink");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(trigger, "trigger");
        if (!boundThreadId.equals(request.threadId())) {
            throw new IllegalArgumentException("context request does not match its Turn binding");
        }
        OverflowRecovery.Execution<T> outcome = recovery.execute(request.toCompactionRequest(),
                receiptSink::publish, prompt -> sender.send(toPreparedPrompt(prompt)), lifecycle, trigger);
        return new Execution<>(toPreparedPrompt(outcome.context().prompt()), outcome.context().checkpoint(),
                outcome.context().compacted(), outcome.result(), outcome.overflowRetried(),
                outcome.committedReceipt());
    }

    /**
     * 将内部压缩结果投影为窄入站类型，避免调用方依赖压缩服务实现细节。
     */
    private static PreparedPrompt toPreparedPrompt(ContextCompactionService.PromptContext prompt) {
        return new PreparedPrompt(prompt.messages(), prompt.summary(), prompt.estimatedTokens(),
                prompt.continuation(), prompt.localCompaction());
    }

    /**
     * 冻结一次 Provider 调用的源 revision、预算、续传状态和 Tool 投影上限。
     */
    public record Request(
            String threadId,
            long sourceRevision,
            List<ContextMessage> messages,
            ContextBudget budget,
            boolean forceCompaction,
            Optional<ModelContinuation> continuation,
            ToolProjectionLimits outputLimits,
            ContextTokenMeter meter,
            CancellationToken cancellation) {
        /**
         * 为不需要取消协调的纯策略调用保留窄构造入口；生产 Turn 必须使用显式令牌构造器。
         */
        public Request(String threadId, long sourceRevision, List<ContextMessage> messages,
                       ContextBudget budget, boolean forceCompaction,
                       Optional<ModelContinuation> continuation, ToolProjectionLimits outputLimits,
                       ContextTokenMeter meter) {
            this(threadId, sourceRevision, messages, budget, forceCompaction, continuation,
                    outputLimits, meter, CancellationToken.none());
        }

        /**
         * 复制源消息并校验全部策略输入，保证重试复用同一不可变请求快照。
         */
        public Request {
            if (threadId == null || threadId.isBlank() || sourceRevision < 0) {
                throw new IllegalArgumentException("invalid context thread");
            }
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            budget = Objects.requireNonNull(budget, "budget");
            continuation = Objects.requireNonNull(continuation, "continuation");
            outputLimits = Objects.requireNonNull(outputLimits, "outputLimits");
            meter = Objects.requireNonNull(meter, "meter");
            cancellation = Objects.requireNonNull(cancellation, "cancellation");
        }

        /**
         * 在应用层内部转换请求，保持公开编排 API 不暴露压缩服务嵌套类型。
         */
        private ContextCompactionService.CompactionRequest toCompactionRequest() {
            return new ContextCompactionService.CompactionRequest(threadId, sourceRevision, messages, budget,
                    forceCompaction, continuation, outputLimits, meter, cancellation);
        }
    }

    /**
     * Provider 实际接收的提示快照，显式说明 continuation 是否因本地压缩失效。
     */
    public record PreparedPrompt(
            List<ContextMessage> messages,
            SummaryDocument summary,
            int estimatedTokens,
            Optional<ModelContinuation> continuation,
            boolean localCompaction) {
        /**
         * 冻结消息与摘要并拒绝负预算结果，防止发送后证据与请求内容漂移。
         */
        public PreparedPrompt {
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            summary = Objects.requireNonNull(summary, "summary");
            if (estimatedTokens < 0) {
                throw new IllegalArgumentException("estimatedTokens must be non-negative");
            }
            continuation = Objects.requireNonNull(continuation, "continuation");
        }
    }

    /**
     * 将模型结果、实际提示、Checkpoint 和溢出重试标志绑定为一个不可变执行事实。
     */
    public record Execution<T>(PreparedPrompt prompt,
                               Optional<CheckpointStore.ContextCheckpoint> checkpoint,
                               boolean compacted, T result, boolean overflowRetried,
                               Optional<CheckpointStore.CommittedCheckpoint> committedReceipt) {
        /**
         * 校验本地压缩、Checkpoint 与提交回执一致，禁止上层观察到半绑定结果。
         */
        public Execution {
            prompt = Objects.requireNonNull(prompt, "prompt");
            checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
            committedReceipt = Objects.requireNonNull(committedReceipt, "committedReceipt");
            if (compacted != prompt.localCompaction() || compacted != checkpoint.isPresent()) {
                throw new IllegalArgumentException("orchestrator execution state mismatch");
            }
            final Optional<CheckpointStore.ContextCheckpoint> committedCheckpoint = checkpoint;
            final Optional<CheckpointStore.CommittedCheckpoint> committedReceiptValue = committedReceipt;
            committedReceiptValue.ifPresent(receipt -> {
                if (committedCheckpoint.isEmpty()
                    || !committedCheckpoint.orElseThrow().equals(receipt.checkpoint())) {
                    throw new IllegalArgumentException("orchestrator receipt checkpoint mismatch");
                }
            });
        }
    }

    /**
     * 同步接收首次提交回执，以强制持久事件发布发生在外部模型 IO 之前。
     */
    @FunctionalInterface
    public interface CheckpointReceiptSink {
        /**
         * 发布新 Checkpoint 的确定性回执；复用记录不会再次进入该端口。
         */
        void publish(CheckpointStore.CommittedCheckpoint receipt);
    }

    /**
     * 隔离 Provider 副作用，使上下文规划和溢出恢复不依赖具体传输实现。
     */
    @FunctionalInterface
    public interface PromptSender<T> {
        /**
         * 发送已冻结提示；上下文溢出必须映射为稳定的 {@link ContextException.Code}。
         */
        T send(PreparedPrompt prompt);
    }

    /**
     * 在创建编排器时拒绝空 Thread 身份，避免延迟到 Provider 调用才暴露绑定错误。
     */
    private static String requiredThread(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("invalid bound context thread");
        }
        return threadId;
    }
}
