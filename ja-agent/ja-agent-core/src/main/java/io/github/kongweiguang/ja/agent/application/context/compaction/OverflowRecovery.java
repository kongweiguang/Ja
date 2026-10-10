// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.context.compaction;

import io.github.kongweiguang.ja.agent.application.context.ContextException;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;

import java.util.Objects;
import java.util.Optional;

/**
 * 在 Provider 上下文溢出后最多执行一次 Tool 输出收缩和本地压缩重试。
 */
public final class OverflowRecovery {
    private final ContextCompactionService compaction;

    /**
     * 复用组合根持有的压缩服务，防止恢复路径创建第二个持久化 owner。
     */
    public OverflowRecovery(ContextCompactionService compaction) {
        this.compaction = Objects.requireNonNull(compaction, "compaction");
    }

    /**
     * 仅发布插入获胜者；复用的 Checkpoint 保持持久但不重复发事件。
     */
    private static void publishReceipt(ContextCompactionService.CompactionResult result,
                                       CheckpointReceiptSink receiptSink) {
        result.committedReceipt().filter(CheckpointStore.CommittedCheckpoint::newlyCommitted)
                .ifPresent(receiptSink::publish);
    }

    /**
     * 将当前尝试阶段和失败类别归约为唯一后续动作；纯函数使“最多重试一次”不变量不依赖异常嵌套。
     */
    private static RecoveryAction reduce(AttemptPhase phase, ContextException.Code failureCode) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(failureCode, "failureCode");
        if (failureCode != ContextException.Code.CONTEXT_LIMIT) {
            return RecoveryAction.PROPAGATE;
        }
        return phase == AttemptPhase.INITIAL ? RecoveryAction.RETRY : RecoveryAction.EXHAUSTED;
    }

    /**
     * 首次发送溢出时仅重试一次，重试使用收缩后的 Tool 投影和强制本地压缩。
     */
    public <T> T send(ContextCompactionService.CompactionRequest request, PromptSender<T> sender) {
        return execute(request, sender).result();
    }

    /**
     * 执行 Provider 尝试并返回与实际提示严格关联的上下文结果。
     */
    public <T> Execution<T> execute(ContextCompactionService.CompactionRequest request,
                                    PromptSender<T> sender) {
        return execute(request, receipt -> {
        }, sender);
    }

    /**
     * 在每次 Provider 尝试前完成压缩并同步发布新提交回执。同步回调保证外部模型 IO 开始时，
     * Checkpoint、Thread revision 和回执身份已经持久化；重试复用首次回执，不会重复发布上下文事件。
     * 若真实溢出后没有更小的可发送提示，直接收口而不原样重投一次付费请求。
     */
    public <T> Execution<T> execute(ContextCompactionService.CompactionRequest request,
                                    CheckpointReceiptSink receiptSink, PromptSender<T> sender) {
        return execute(request, receiptSink, sender, ignored -> {
        });
    }

    /**
     * 让压缩的精确 before 计量回到唯一执行者持久化；无更小可发送请求时明确失败，不退回原提示。
     */
    public <T> Execution<T> execute(ContextCompactionService.CompactionRequest request,
                                    CheckpointReceiptSink receiptSink, PromptSender<T> sender,
                                    ContextCompactionService.CompactionStartObserver compactionStarted) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(receiptSink, "receiptSink");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(compactionStarted, "compactionStarted");
        ContextCompactionService.CompactionResult initial = compaction.compact(request, compactionStarted);
        publishReceipt(initial, receiptSink);
        Attempt attempt = new Attempt(AttemptPhase.INITIAL, initial, false,
                initial.committedReceipt());
        while (true) {
            try {
                request.cancellation().throwIfCancellationRequested();
                return new Execution<>(attempt.context(), sender.send(attempt.context().prompt()),
                        attempt.overflowRetried(), attempt.committedReceipt());
            } catch (ContextException failure) {
                RecoveryAction action = reduce(attempt.phase(), failure.code());
                if (action == RecoveryAction.PROPAGATE) {
                    throw failure;
                }
                if (action == RecoveryAction.EXHAUSTED) {
                    throw new ContextException(ContextException.Code.CONTEXT_LIMIT,
                            "context overflow remained after the single recovery retry", failure);
                }
                request.cancellation().throwIfCancellationRequested();
                ContextCompactionService.CompactionResult retry = attempt.context().compacted()
                        ? compaction.reprojectCommittedForOverflow(request, attempt.context())
                        : compaction.compact(request.shrinkForOverflow(), compactionStarted);
                if (retry.plan().summaryInput().isEmpty() && retry.plan().fullPromptFits()
                    && retry.plan().compactedPromptTokens() >= retry.plan().fullPromptTokens()) {
                    throw new ContextException(ContextException.Code.CONTEXT_LIMIT,
                            "overflow recovery had no smaller sendable envelope", failure);
                }
                publishReceipt(retry, receiptSink);
                Optional<CheckpointStore.CommittedCheckpoint> receipt = attempt.committedReceipt()
                        .or(retry::committedReceipt);
                attempt = new Attempt(AttemptPhase.RECOVERY, retry, true, receipt);
            }
        }
    }

    /**
     * Provider 尝试阶段只允许从首次尝试单向进入一次恢复尝试。
     */
    private enum AttemptPhase {
        /**
         * 尚未消费溢出恢复额度的首次尝试。
         */
        INITIAL,

        /**
         * 已消费唯一恢复额度的第二次尝试。
         */
        RECOVERY
    }

    /**
     * 纯状态归约器输出的闭集动作，调用方不得通过异常文本决定重试。
     */
    private enum RecoveryAction {
        /**
         * 非上下文溢出错误保持原分类向上传播。
         */
        PROPAGATE,

        /**
         * 首次上下文溢出进入唯一一次恢复。
         */
        RETRY,

        /**
         * 恢复尝试仍溢出时终止，不允许第三次发送。
         */
        EXHAUSTED
    }

    /**
     * 同步发布端口用于强制“回执先于 Provider 调用”的顺序。
     */
    @FunctionalInterface
    public interface CheckpointReceiptSink {
        /**
         * 仅接收新插入的 Checkpoint 回执，复用记录不重复发送。
         */
        void publish(CheckpointStore.CommittedCheckpoint receipt);
    }

    /**
     * 窄发送端口允许应用映射 Provider 溢出，而不与 HTTP 状态码耦合。
     */
    @FunctionalInterface
    public interface PromptSender<T> {
        /**
         * 使用不可变提示上下文执行一次 Provider 请求。
         */
        T send(ContextCompactionService.PromptContext prompt);
    }

    /**
     * 冻结一次 Provider 尝试及其持久化回执，避免重试时错配提示和证据。
     *
     * @param phase 阶段
     * @param context 当前操作的上下文
     * @param overflowRetried 上下文溢出后是否已重试
     * @param committedReceipt 已提交回执（可选）
     */
    private record Attempt(AttemptPhase phase,
                           ContextCompactionService.CompactionResult context,
                           boolean overflowRetried,
                           Optional<CheckpointStore.CommittedCheckpoint> committedReceipt) {
        /**
         * 校验状态快照完整，保证发送前已具备上下文和回执所有权。
         */
        private Attempt {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(committedReceipt, "committedReceipt");
            if (overflowRetried != (phase == AttemptPhase.RECOVERY)) {
                throw new IllegalArgumentException("overflow retry phase mismatch");
            }
        }
    }

    /**
     * 不可变执行结果保证模型输出与实际提示、Checkpoint 一一关联。
     *
     * @param context 当前操作的上下文
     * @param result 处理结果
     * @param overflowRetried 上下文溢出后是否已重试
     * @param committedReceipt 已提交回执（可选）
     */
    public record Execution<T>(ContextCompactionService.CompactionResult context,
                               T result,
                               boolean overflowRetried,
                               Optional<CheckpointStore.CommittedCheckpoint> committedReceipt) {
        /**
         * 为不需要检查回执的调用方保留精简构造入口。
         */
        public Execution(ContextCompactionService.CompactionResult context, T result,
                         boolean overflowRetried) {
            this(context, result, overflowRetried, context.committedReceipt());
        }

        /**
         * 禁止调用方观察到缺少对应上下文证据的模型结果。
         */
        public Execution {
            Objects.requireNonNull(context, "context");
            committedReceipt = Objects.requireNonNull(committedReceipt, "committedReceipt");
        }
    }
}
