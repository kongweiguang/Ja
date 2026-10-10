// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.context;

import io.github.kongweiguang.ja.agent.application.context.compaction.ContextCompactionService;
import io.github.kongweiguang.ja.agent.application.context.compaction.OverflowRecovery;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;

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
     * 将内部压缩结果投影为窄入站类型，避免调用方依赖压缩服务实现细节。
     */
    private static PreparedPrompt toPreparedPrompt(ContextCompactionService.PromptContext prompt) {
        return new PreparedPrompt(prompt.messages(), prompt.summary(), prompt.estimatedTokens(),
                prompt.continuation(), prompt.localCompaction());
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

    /**
     * 完成预算规划、必要压缩和至多一次溢出恢复，并返回与实际提示绑定的执行证据。
     */
    public <T> Execution<T> execute(Request request, CheckpointReceiptSink receiptSink,
                                    PromptSender<T> sender) {
        return execute(request, receiptSink, sender, ignored -> {
        });
    }

    /**
     * 把精确计量后的压缩起点交给当前持久化执行者，由其与游标在同一事务提交。
     */
    public <T> Execution<T> execute(Request request, CheckpointReceiptSink receiptSink,
                                    PromptSender<T> sender,
                                    ContextCompactionService.CompactionStartObserver compactionStarted) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(receiptSink, "receiptSink");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(compactionStarted, "compactionStarted");
        if (!boundThreadId.equals(request.threadId())) {
            throw new IllegalArgumentException("context request does not match its Turn binding");
        }
        OverflowRecovery.Execution<T> outcome = recovery.execute(request.toCompactionRequest(),
                receiptSink::publish, prompt -> sender.send(toPreparedPrompt(prompt)), compactionStarted);
        return new Execution<>(toPreparedPrompt(outcome.context().prompt()), outcome.context().checkpoint(),
                outcome.context().compacted(), outcome.result(), outcome.overflowRetried(),
                outcome.committedReceipt());
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
     * 冻结一次 Provider 调用的源 revision、预算、续传状态和 Tool 投影上限。
     *
     * @param threadId 所属对话的稳定标识
     * @param sourceRevision 来源修订版本
     * @param messages 消息列表
     * @param budget 预算
     * @param forceCompaction 是否强制压缩上下文
     * @param continuation 续跑（可选）
     * @param outputLimits 工具结果投影的容量约束
     * @param projectionBinding 投影绑定
     * @param meter 计量器
     * @param cancellation 取消控制
     * @param baseline 基线（可选）
     */
    public record Request(String threadId,
                          long sourceRevision,
                          List<ContextMessage> messages,
                          ContextBudget budget,
                          boolean forceCompaction,
                          Optional<ModelContinuation> continuation,
                          ToolProjectionLimits outputLimits,
                          CheckpointStore.ProjectionBinding projectionBinding,
                          ContextTokenMeter meter,
                          CancellationToken cancellation,
                          Optional<ContextBaseline> baseline) {
        /**
         * 复制源消息并校验全部策略输入，保证重试复用同一不可变请求快照。
         */
        public Request {
            ContextRequestValidation.validate(threadId, sourceRevision, messages, budget, continuation,
                    outputLimits, projectionBinding, meter, cancellation, baseline);
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        }

        /**
         * 在应用层内部转换请求，保持公开编排 API 不暴露压缩服务嵌套类型。
         */
        private ContextCompactionService.CompactionRequest toCompactionRequest() {
            return new ContextCompactionService.CompactionRequest(threadId, sourceRevision, messages, budget,
                    forceCompaction, continuation, outputLimits, projectionBinding, meter, cancellation, baseline);
        }
    }

    /**
     * Provider 实际接收的提示快照，显式说明 continuation 是否因本地压缩失效。
     *
     * @param messages 消息列表
     * @param summary 摘要
     * @param estimatedTokens 估算的 Token 数
     * @param continuation 续跑（可选）
     * @param localCompaction 是否使用本地上下文压缩
     */
    public record PreparedPrompt(List<ContextMessage> messages,
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
     *
     * @param prompt 提示词
     * @param checkpoint 检查点（可选）
     * @param compacted 是否已压缩上下文
     * @param result 处理结果
     * @param overflowRetried 上下文溢出后是否已重试
     * @param committedReceipt 已提交回执（可选）
     */
    public record Execution<T>(PreparedPrompt prompt,
                               Optional<CheckpointStore.ContextCheckpoint> checkpoint,
                               boolean compacted,
                               T result,
                               boolean overflowRetried,
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
}
