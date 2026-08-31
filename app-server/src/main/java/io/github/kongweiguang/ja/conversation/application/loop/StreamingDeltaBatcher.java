// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.loop;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/**
 * 将 Provider 高频 delta 归约为有界、同类型且严格串行发布的批次，并拥有排空与丢弃状态机。
 */
final class StreamingDeltaBatcher {
    static final Duration FLUSH_DELAY = Duration.ofMillis(24);
    static final int MAX_BATCH_UTF8_BYTES = 8 * 1024;
    private static final int MAX_INPUT_CHARS = 1_000_000;
    private static final int MAX_PENDING_UTF8_BYTES = 4_000_000;

    private final Timer timer;
    private final Emitter emitter;
    private final BooleanSupplier discardRequested;
    private final StringBuilder pending = new StringBuilder();
    private Kind pendingKind;
    private int pendingUtf8Bytes;
    private Timer.Task timeout;
    private long timeoutGeneration;
    private CompletableFuture<Void> drain;
    private boolean accepting = true;
    private boolean emitting;
    private boolean forceDrain;
    private boolean closing;
    private boolean discarded;
    private Throwable failure;

    /**
     * 绑定 Timer、Emitter 与取消判定；三者共同决定批次何时可见及何时必须丢弃。
     */
    StreamingDeltaBatcher(Timer timer, Emitter emitter, BooleanSupplier discardRequested) {
        this.timer = Objects.requireNonNull(timer, "timer");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.discardRequested = Objects.requireNonNull(discardRequested, "discardRequested");
    }

    /**
     * 接纳一个 delta 并推进 Reducer；类型边界、字节上限或背压违约会触发立即排空或失败。
     */
    synchronized CompletionStage<Void> append(Kind kind, String value) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        if (!accepting || discarded || discardRequested.getAsBoolean()) {
            discardLocked();
            return CompletableFuture.completedFuture(null);
        }
        if (failure != null) {
            return CompletableFuture.failedFuture(failure);
        }
        if (value.isEmpty() || value.length() > MAX_INPUT_CHARS) {
            return failLocked(new IllegalArgumentException("stream delta is empty or exceeds its bound"));
        }

        boolean kindBoundary = pendingKind != null && pendingKind != kind;
        if (kindBoundary && emitting) {
            return failLocked(
                    new IllegalStateException("provider ignored delta sink backpressure at a kind boundary"));
        }
        if (kindBoundary) {
            forceDrain = true;
            ensureDrainLocked();
            startEmissionLocked();
            if (failure != null) {
                return CompletableFuture.failedFuture(failure);
            }
            if (discarded || !accepting) {
                return CompletableFuture.completedFuture(null);
            }
        }
        if (emitting && pendingKind != null && pendingKind != kind) {
            return failLocked(
                    new IllegalStateException("provider ignored delta sink backpressure while publishing"));
        }

        int bytes = utf8Length(value);
        if (bytes > MAX_PENDING_UTF8_BYTES - pendingUtf8Bytes) {
            return failLocked(
                    new IllegalStateException("provider ignored delta sink backpressure buffer bound"));
        }
        if (pendingKind == null) {
            pendingKind = kind;
        }
        pending.append(value);
        pendingUtf8Bytes += bytes;

        if (emitting || forceDrain || pendingUtf8Bytes >= MAX_BATCH_UTF8_BYTES) {
            forceDrain = true;
            CompletableFuture<Void> barrier = ensureDrainLocked();
            startEmissionLocked();
            return barrier;
        }
        scheduleTimeoutLocked();
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 建立共享排空屏障并强制启动发布，使 Tool、Usage 与终态不能越过此前草稿。
     */
    synchronized CompletionStage<Void> flush() {
        if (discarded || discardRequested.getAsBoolean()) {
            return discard();
        }
        if (failure != null) {
            if (emitting && drain != null) {
                return drain;
            }
            return CompletableFuture.failedFuture(failure);
        }
        if (pending.isEmpty() && !emitting) {
            return CompletableFuture.completedFuture(null);
        }
        forceDrain = true;
        CompletableFuture<Void> barrier = ensureDrainLocked();
        startEmissionLocked();
        return barrier;
    }

    /**
     * 停止接纳新 delta 后正常排空，Timer 仅在无在途发布时释放。
     */
    synchronized CompletionStage<Void> closeNormally() {
        accepting = false;
        closing = true;
        CompletionStage<Void> completion = flush();
        closeTimerIfIdleLocked();
        return completion;
    }

    /**
     * 丢弃尚未发布内容并等待在途 Emitter 收口，保留已经发生的发布失败。
     */
    synchronized CompletionStage<Void> discard() {
        discardLocked();
        if (drain != null) {
            return drain;
        }
        if (failure != null) {
            return CompletableFuture.failedFuture(failure);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 在锁内驱动单发布者状态机；异步 Emitter 未完成时立即让出，完成回调再继续归约。
     */
    private void startEmissionLocked() {
        while (!emitting && !pending.isEmpty() && !discarded && failure == null) {
            if (discardRequested.getAsBoolean()) {
                discardLocked();
                return;
            }
            if (!forceDrain && pendingUtf8Bytes < MAX_BATCH_UTF8_BYTES) {
                scheduleTimeoutLocked();
                return;
            }

            cancelTimeoutLocked();
            Batch batch = takeBatchLocked();
            emitting = true;
            CompletableFuture<Void> published;
            try {
                published =
                        Objects.requireNonNull(emitter.emit(batch.kind(), batch.text()), "publish stage")
                                .toCompletableFuture();
            } catch (RuntimeException publishFailure) {
                emissionFinishedLocked(publishFailure);
                return;
            }
            if (!published.isDone()) {
                published.whenComplete(
                        (ignored, publishFailure) -> {
                            synchronized (StreamingDeltaBatcher.this) {
                                emissionFinishedLocked(unwrap(publishFailure));
                            }
                        });
                return;
            }
            try {
                published.join();
            } catch (RuntimeException publishFailure) {
                emissionFinishedLocked(unwrap(publishFailure));
                return;
            }
            if (!settleSuccessfulEmissionLocked()) {
                return;
            }
        }
    }

    /**
     * 归约异步发布完成事件，优先处理丢弃和失败，再决定继续下一批或完成排空屏障。
     */
    private void emissionFinishedLocked(Throwable publishFailure) {
        emitting = false;
        if (discarded) {
            if (publishFailure != null && failure == null) {
                failure = unwrap(publishFailure);
            }
            completeDrainLocked(failure);
            closeTimerIfIdleLocked();
            return;
        }
        if (publishFailure != null) {
            failLocked(publishFailure);
            return;
        }
        if (discardRequested.getAsBoolean()) {
            discardLocked();
            return;
        }
        if (failure != null) {
            completeDrainLocked(failure);
            closeTimerIfIdleLocked();
            return;
        }
        if (settleSuccessfulEmissionLocked()) {
            startEmissionLocked();
        }
    }

    /**
     * 完成一次成功发布；仅当缓冲为空时解除 forceDrain 并兑现共享排空屏障。
     */
    private boolean settleSuccessfulEmissionLocked() {
        emitting = false;
        if (!pending.isEmpty()) {
            return true;
        }
        forceDrain = false;
        CompletableFuture<Void> completed = drain;
        drain = null;
        if (completed != null) {
            completed.complete(null);
        }
        closeTimerIfIdleLocked();
        return false;
    }

    /**
     * 按 Unicode code point 截取不超过 UTF-8 上限的前缀，禁止在代理对或多字节字符中间切分。
     */
    private Batch takeBatchLocked() {
        int end = 0;
        int bytes = 0;
        while (end < pending.length()) {
            int codePoint = Character.codePointAt(pending, end);
            int width = utf8Width(codePoint);
            if (bytes > 0 && bytes + width > MAX_BATCH_UTF8_BYTES) {
                break;
            }
            bytes += width;
            end += Character.charCount(codePoint);
            if (bytes >= MAX_BATCH_UTF8_BYTES) {
                break;
            }
        }
        String text = pending.substring(0, end);
        Kind kind = pendingKind;
        pending.delete(0, end);
        pendingUtf8Bytes -= bytes;
        if (pending.isEmpty()) {
            pendingKind = null;
            pendingUtf8Bytes = 0;
        }
        return new Batch(kind, text);
    }

    /**
     * 仅为空闲非关闭缓冲安排一次延迟刷新，并用 generation 防止旧任务重新激活。
     */
    private void scheduleTimeoutLocked() {
        if (timeout != null || pending.isEmpty() || closing || discarded) {
            return;
        }
        long generation = ++timeoutGeneration;
        timeout = timer.schedule(() -> timeoutElapsed(generation), FLUSH_DELAY);
    }

    /**
     * 在 generation 仍有效时把延迟事件归约为强制排空；取消请求始终优先于发布。
     */
    private void timeoutElapsed(long generation) {
        synchronized (this) {
            if (generation != timeoutGeneration || timeout == null || discarded || failure != null) {
                return;
            }
            timeout = null;
            if (discardRequested.getAsBoolean()) {
                discardLocked();
                return;
            }
            forceDrain = true;
            ensureDrainLocked();
            startEmissionLocked();
        }
    }

    /**
     * 递增 generation 并取消当前任务，使已经排队的迟到回调成为无效事件。
     */
    private void cancelTimeoutLocked() {
        timeoutGeneration++;
        if (timeout != null) {
            timeout.cancel();
            timeout = null;
        }
    }

    /**
     * 为同一段在途内容复用一个排空 Future，避免多个 flush 观察到不同完成边界。
     */
    private CompletableFuture<Void> ensureDrainLocked() {
        if (drain == null || drain.isDone()) {
            drain = new CompletableFuture<>();
        }
        return drain;
    }

    /**
     * 锁定首次失败、停止接纳并清空未发布内容；在途发布完成后再兑现失败屏障。
     */
    private CompletionStage<Void> failLocked(Throwable publishFailure) {
        Throwable normalized = unwrap(Objects.requireNonNull(publishFailure, "publishFailure"));
        if (failure == null) {
            failure = normalized;
        }
        accepting = false;
        closing = true;
        pending.setLength(0);
        pendingKind = null;
        pendingUtf8Bytes = 0;
        forceDrain = false;
        cancelTimeoutLocked();
        if (!emitting) {
            completeDrainLocked(failure);
        }
        timer.close();
        return CompletableFuture.failedFuture(failure);
    }

    /**
     * 进入不可逆丢弃态并释放缓冲与 Timer，同时允许在途 Emitter 完成资源收口。
     */
    private void discardLocked() {
        if (discarded) {
            return;
        }
        discarded = true;
        accepting = false;
        closing = true;
        pending.setLength(0);
        pendingKind = null;
        pendingUtf8Bytes = 0;
        forceDrain = false;
        cancelTimeoutLocked();
        if (!emitting) {
            completeDrainLocked(failure);
        }
        timer.close();
    }

    /**
     * 只兑现并清除当前共享排空屏障，确保成功或失败完成恰好一次。
     */
    private void completeDrainLocked(Throwable completionFailure) {
        CompletableFuture<Void> completed = drain;
        drain = null;
        if (completed == null) {
            return;
        }
        if (completionFailure == null) {
            completed.complete(null);
        } else {
            completed.completeExceptionally(unwrap(completionFailure));
        }
    }

    /**
     * 仅在关闭态且没有缓冲或在途发布时释放 Timer，避免截断仍需调度的批次。
     */
    private void closeTimerIfIdleLocked() {
        if (closing && !emitting && pending.isEmpty()) {
            timer.close();
        }
    }

    /**
     * 按 code point 计算真实 UTF-8 字节数，批次上限不能用 UTF-16 字符数近似。
     */
    private static int utf8Length(String value) {
        int bytes = 0;
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            bytes = Math.addExact(bytes, utf8Width(codePoint));
            index += Character.charCount(codePoint);
        }
        return bytes;
    }

    /**
     * 返回单个 Unicode code point 的 UTF-8 编码宽度，供计量与切批共享同一规则。
     */
    private static int utf8Width(int codePoint) {
        if (codePoint <= 0x7f) {
            return 1;
        }
        if (codePoint <= 0x7ff) {
            return 2;
        }
        if (codePoint <= 0xffff) {
            return 3;
        }
        return 4;
    }

    /**
     * 剥离异步容器异常，确保状态机记录真实 Sink 失败而非包装层。
     */
    private static Throwable unwrap(Throwable value) {
        Throwable current = value;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
               && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 批处理器允许发布的两种流式草稿类别。
     */
    enum Kind {
        /**
         * 用户可见的助手文本增量。
         */
        TEXT,
        /**
         * 可公开的推理摘要增量，不包含私有推理内容。
         */
        REASONING_SUMMARY
    }

    /**
     * 定义单批异步发布端口；实现必须在返回 Stage 完成前持续拥有输入内容。
     */
    @FunctionalInterface
    interface Emitter {
        /**
         * 发布一个同类型批次，完成信号决定后续批次是否可以开始。
         */
        CompletionStage<Void> emit(Kind kind, String text);
    }

    /**
     * 抽象延迟刷新资源，使状态机可确定性测试并明确 Timer 的关闭所有权。
     */
    interface Timer extends AutoCloseable {
        /**
         * 安排一次延迟回调并返回可撤销句柄。
         */
        Task schedule(Runnable callback, Duration delay);

        /**
         * 取消尚未执行的回调并释放 Timer 资源；允许重复调用。
         */
        @Override
        void close();

        /**
         * 表示一个尚可撤销的延迟刷新任务。
         */
        @FunctionalInterface
        interface Task {
            /**
             * 阻止任务开始；已运行的回调由 generation 校验兜底。
             */
            void cancel();
        }
    }

    /**
     * 冻结一次发布的 delta 类型与文本，二者在异步完成前不可变化。
     */
    private record Batch(Kind kind, String text) {
    }

}
