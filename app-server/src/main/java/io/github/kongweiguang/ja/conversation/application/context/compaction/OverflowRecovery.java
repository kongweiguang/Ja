// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context.compaction;

import io.github.kongweiguang.ja.conversation.application.context.ContextException;
import io.github.kongweiguang.ja.conversation.application.context.ContextCompactionLifecycle;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointStore;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent;

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
     */
    public <T> Execution<T> execute(ContextCompactionService.CompactionRequest request,
                                    CheckpointReceiptSink receiptSink, PromptSender<T> sender) {
        return execute(request, receiptSink, sender, null, ContextCompactionEvent.Trigger.AUTOMATIC);
    }

    /**
     * 让自动、手动与 overflow recovery 共用真实规划/提交边界；null lifecycle 仅保留给纯状态机单测，
     * 生产组合必须传入发布器。
     */
    public <T> Execution<T> execute(ContextCompactionService.CompactionRequest request,
                                    CheckpointReceiptSink receiptSink, PromptSender<T> sender,
                                    ContextCompactionLifecycle lifecycle,
                                    ContextCompactionEvent.Trigger initialTrigger) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(receiptSink, "receiptSink");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(initialTrigger, "initialTrigger");
        ContextCompactionService.CompactionResult initial = compactAttempt(request, lifecycle, initialTrigger);
        publishReceipt(initial, receiptSink);
        completeLifecycle(initial, lifecycle);
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
                // 自动摘要失败可能仍引用旧 checkpoint，但新尾部尚未归入摘要，不能按本次已压缩重投影。
                boolean automaticFallback = attempt.context().automaticSummaryFailed();
                ContextCompactionService.CompactionResult retry = attempt.context().compacted() && !automaticFallback
                        ? compaction.reprojectCommittedForOverflow(request, attempt.context())
                        : compactAttempt(request.shrinkForOverflow(), lifecycle,
                                ContextCompactionEvent.Trigger.OVERFLOW_RECOVERY);
                publishReceipt(retry, receiptSink);
                completeLifecycle(retry, lifecycle);
                Optional<CheckpointStore.CommittedCheckpoint> receipt = attempt.committedReceipt()
                        .or(retry::committedReceipt);
                attempt = new Attempt(AttemptPhase.RECOVERY, retry, true, receipt);
            }
        }
    }

    /** 在 started 前后区分失败，使 failed 事件的 before Token nullable 语义保持真实。 */
    private ContextCompactionService.CompactionResult compactAttempt(
            ContextCompactionService.CompactionRequest request, ContextCompactionLifecycle lifecycle,
            ContextCompactionEvent.Trigger trigger) {
        try {
            return compaction.compact(request,
                    before -> { if (lifecycle != null) lifecycle.started(trigger, before); });
        } catch (ContextException failure) {
            if (lifecycle != null) {
                if (lifecycle.active()) lifecycle.failed(failure.code());
                else lifecycle.failBeforeStart(trigger, failure.code());
            }
            throw failure;
        }
    }

    /** 只有持久回执存在时结束 started；无压缩或复用同源 Checkpoint 不伪造完成事件。 */
    private static void completeLifecycle(ContextCompactionService.CompactionResult result,
                                           ContextCompactionLifecycle lifecycle) {
        if (lifecycle == null || !lifecycle.active()) return;
        if (result.automaticSummaryFailed()) {
            lifecycle.failedForAutomaticFallback(ContextException.Code.SUMMARY_FAILURE);
            return;
        }
        CheckpointStore.CommittedCheckpoint receipt = result.committedReceipt()
                .orElseThrow(() -> new ContextException(ContextException.Code.INVALID_STATE,
                        "started compaction did not produce a committed checkpoint"));
        lifecycle.compacted(receipt, result.prompt().estimatedTokens());
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
     * 冻结一次 Provider 尝试及其持久化回执，避免重试时错配提示和证据。
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
     */
    public record Execution<T>(ContextCompactionService.CompactionResult context,
                               T result, boolean overflowRetried,
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
}
