// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.service;

import io.github.kongweiguang.ja.conversation.application.loop.TurnQueue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 以单一截止线编排停止准入、取消活动 Turn、等待 Lane 静默及关闭自有执行器。
 */
final class TurnShutdown {
    private static final Duration SHUTDOWN_BUDGET = Duration.ofSeconds(10);
    private static final long UNSET_CLOSE_DEADLINE = Long.MIN_VALUE;
    private final TurnQueue queue;
    private final ScheduledExecutorService deadlines;
    private final ExecutorService terminalExecutor;
    private final Map<TurnService.Key, TurnOwnership> active;
    private final Runnable stopAccepting;
    private final TurnCancellationLifecycle cancellation;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong closeDeadlineNanos = new AtomicLong(UNSET_CLOSE_DEADLINE);
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();

    /**
     * 注入全部关闭 owner，确保关闭快照与运行期所持队列、定时器和终态执行器一致。
     */
    TurnShutdown(TurnQueue queue, ScheduledExecutorService deadlines, ExecutorService terminalExecutor,
                 Map<TurnService.Key, TurnOwnership> active, Runnable stopAccepting,
                 TurnCancellationLifecycle cancellation) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        this.terminalExecutor = Objects.requireNonNull(terminalExecutor, "terminalExecutor");
        this.active = Objects.requireNonNull(active, "active");
        this.stopAccepting = Objects.requireNonNull(stopAccepting, "stopAccepting");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }

    /**
     * 返回关闭 CAS 是否已发布，用于准入锁内阻止新的 Turn 越过关闭边界。
     */
    boolean isClosed() {
        return closed.get();
    }

    /**
     * 使用默认十秒总预算执行幂等关闭。
     */
    void close() {
        closeAt(deadlineAfter(SHUTDOWN_BUDGET.toNanos()));
    }

    /**
     * 由唯一 owner 停止准入并收敛活动 Lane；并发调用者等待相同结果且不能延长截止线。
     */
    void closeAt(long shutdownDeadlineNanos) {
        long effectiveDeadline = registerCloseDeadline(shutdownDeadlineNanos);
        if (!closed.compareAndSet(false, true)) {
            awaitCloseCompletion(effectiveDeadline);
            return;
        }
        RuntimeException failure = null;
        try {
            stopAccepting.run();
            for (Map.Entry<TurnService.Key, TurnOwnership> entry : List.copyOf(active.entrySet())) {
                cancellation.requestCancellation(entry.getKey(), entry.getValue(), "runtime closing");
            }
            Duration remaining = remaining(effectiveDeadline);
            if (!queue.awaitQuiescence(remaining)) {
                failure = new IllegalStateException("turn queue did not quiesce before shutdown deadline");
            }
            if (failure == null) {
                awaitTurnCompletions(List.copyOf(active.values()), effectiveDeadline);
            }
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        } finally {
            deadlines.shutdownNow();
            RuntimeException deadlineFailure = awaitOwnedExecutor(
                    deadlines, effectiveDeadline, "turn deadline executor");
            terminalExecutor.shutdown();
            RuntimeException executorFailure = awaitOwnedExecutor(
                    terminalExecutor, effectiveDeadline, "turn terminal executor");
            if (failure == null) failure = deadlineFailure;
            else if (deadlineFailure != null) failure.addSuppressed(deadlineFailure);
            if (failure == null) failure = executorFailure;
            else if (executorFailure != null) failure.addSuppressed(executorFailure);
            if (failure == null && !active.isEmpty()) {
                failure = new IllegalStateException("active Turns remained after shutdown deadline");
            }
            if (failure == null) closeCompletion.complete(null);
            else closeCompletion.completeExceptionally(failure);
        }
        if (failure != null) throw failure;
    }

    /**
     * 以 CAS 固定首次关闭截止线，后续调用只能采用更早预算。
     */
    private long registerCloseDeadline(long requestedDeadline) {
        if (requestedDeadline == UNSET_CLOSE_DEADLINE) {
            throw new IllegalArgumentException("invalid close deadline");
        }
        while (true) {
            long existing = closeDeadlineNanos.get();
            if (existing != UNSET_CLOSE_DEADLINE) return Math.min(existing, requestedDeadline);
            if (closeDeadlineNanos.compareAndSet(UNSET_CLOSE_DEADLINE, requestedDeadline)) {
                return requestedDeadline;
            }
        }
    }

    /**
     * 在总预算内等待关闭快照中的全部 Turn Future，任一异常都使关闭明确失败。
     */
    private static void awaitTurnCompletions(List<TurnOwnership> turns, long shutdownDeadline) {
        if (turns.isEmpty()) return;
        CompletableFuture<?>[] completions = turns.stream()
                .map(turn -> turn.completion)
                .toArray(CompletableFuture<?>[]::new);
        try {
            CompletableFuture.allOf(completions).get(remaining(shutdownDeadline).toNanos(),
                    TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("turn shutdown was interrupted", interrupted);
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("active Turns did not finish before shutdown deadline", timeout);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("turn shutdown completion failed", failure.getCause());
        }
    }

    /**
     * 在剩余预算内等待自有执行器终止，超时或中断时强制停止并返回关闭欠账。
     */
    private static RuntimeException awaitOwnedExecutor(
            ExecutorService executor, long shutdownDeadline, String name) {
        try {
            long remaining = remainingNanos(shutdownDeadline);
            if (!executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                executor.shutdownNow();
                return new IllegalStateException(name + " did not terminate before shutdown deadline");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            return new IllegalStateException(name + " shutdown was interrupted", interrupted);
        }
        return null;
    }

    /**
     * 将单调截止线转换为非负 Duration，供队列静默等待复用。
     */
    private static Duration remaining(long shutdownDeadline) {
        return Duration.ofNanos(remainingNanos(shutdownDeadline));
    }

    /**
     * 计算非负剩余纳秒，并对差值溢出使用饱和值。
     */
    private static long remainingNanos(long shutdownDeadline) {
        long now = System.nanoTime();
        if (shutdownDeadline <= now) return 0L;
        long remaining = shutdownDeadline - now;
        return remaining > 0L ? remaining : Long.MAX_VALUE;
    }

    /**
     * 基于单调时钟生成截止线，加法溢出时饱和到最大值。
     */
    private static long deadlineAfter(long durationNanos) {
        long now = System.nanoTime();
        long deadline = now + durationNanos;
        return deadline >= now ? deadline : Long.MAX_VALUE;
    }

    /**
     * 并发关闭者复用 owner 的完成 Future，并在共同截止线内传播同一失败。
     */
    private void awaitCloseCompletion(long shutdownDeadline) {
        try {
            closeCompletion.get(remainingNanos(shutdownDeadline), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("turn close was interrupted", interrupted);
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("turn close exceeded shutdown deadline", timeout);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new CompletionException(cause);
        }
    }
}
