// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.cancellation;

import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 按 Turn 身份管理唯一取消 Scope，并以共享清理屏障协调请求、完成与有界关闭。
 */
public final class DefaultCancellationCoordinator implements CancellationCoordinator, DeadlineCloseable {
    private static final long SHUTDOWN_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long UNSET_CLOSE_DEADLINE = Long.MIN_VALUE;
    private final ConcurrentHashMap<TurnKey, CancellationCleanupState> scopes = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong closeDeadlineNanos = new AtomicLong(UNSET_CLOSE_DEADLINE);
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();
    private final ExecutorService cancellationExecutor;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();

    /**
     * 为每个取消任务使用独立虚拟线程，避免某个阻塞清理拖住其他 Turn。
     */
    public DefaultCancellationCoordinator() {
        this(Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("ja-cancellation-", 0).factory()));
    }

    /**
     * 注入协调器独占的执行器，使拒绝提交和关闭时限可以确定性验证。
     */
    DefaultCancellationCoordinator(ExecutorService cancellationExecutor) {
        this.cancellationExecutor = Objects.requireNonNull(cancellationExecutor, "cancellationExecutor");
    }

    /**
     * 在生命周期读锁内为全局 Turn 键创建唯一 Scope，重复身份直接拒绝。
     */
    @Override
    @SuppressWarnings("PMD.CloseResource")
    public CancellationScope open(String threadId, String turnId) {
        lifecycle.readLock().lock();
        try {
            ensureOpen();
            TurnKey key = TurnKey.create(threadId, turnId);
            CancellationCleanupState candidate = new CancellationCleanupState(cancellationExecutor);
            CancellationCleanupState existing = scopes.putIfAbsent(key, candidate);
            if (existing != null) {
                throw new IllegalStateException("cancellation scope already exists for turn");
            }
            return candidate;
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /**
     * 同步发布首次取消位，再异步等待清理；重复请求复用 Scope 的同一完成屏障。
     */
    @Override
    @SuppressWarnings("PMD.CloseResource")
    public CompletionStage<CancelOutcome> cancel(String threadId, String turnId, String reason) {
        lifecycle.readLock().lock();
        try {
            TurnKey key = TurnKey.create(threadId, turnId);
            CancellationCleanupState scope = scopes.get(key);
            if (scope == null) {
                return CompletableFuture.completedFuture(CancelOutcome.NOT_FOUND);
            }
            // 方法返回前必须发布 Token；清理刻意作为独立阶段，防止阻塞的执行器让调用方看到过期取消状态。
            CancellationCleanupState.CancellationClaim claim = scope.claimCancellation(reason);
            CancelOutcome outcome = claim.won()
                    ? CancelOutcome.REQUESTED : CancelOutcome.ALREADY_REQUESTED;
            CompletableFuture<CancelOutcome> result = new CompletableFuture<>();
            try {
                cancellationExecutor.execute(() -> dispatchCancellation(scope, claim, outcome, result));
            } catch (RejectedExecutionException rejected) {
                scope.failClaim(claim, rejected);
                completeAfterCleanup(scope, outcome, result);
            }
            if (!claim.won()) completeAfterCleanup(scope, outcome, result);
            return result;
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /**
     * 按复合 Turn 键查询只读令牌，不把取消发布权暴露给协调器外部。
     */
    @Override
    public Optional<CancellationToken> find(String threadId, String turnId) {
        return Optional.ofNullable(scopes.get(TurnKey.create(threadId, turnId)));
    }

    /**
     * 关闭完成的 Scope，并延迟到清理屏障结束后再移除以容纳迟到观察者。
     */
    @Override
    @SuppressWarnings("PMD.CloseResource")
    public void complete(String threadId, String turnId) {
        lifecycle.readLock().lock();
        try {
            TurnKey key = TurnKey.create(threadId, turnId);
            CancellationCleanupState scope = scopes.get(key);
            if (scope != null) {
                // 取消屏障完成前保持 Scope 可查找，否则迟到的关闭可能丢失在途清理的唯一 Owner 引用。
                scope.close();
                scope.cleanupCompletion().whenComplete((ignored, failure) -> scopes.remove(key, scope));
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /**
     * 使用默认十秒总预算收敛全部 Scope 和自有执行器。
     */
    @Override
    public void close() {
        closeAt(deadlineAfter(SHUTDOWN_BUDGET_NANOS));
    }

    /**
     * 由唯一关闭 owner 快照并取消全部 Scope；并发关闭者等待同一结果和更早截止线。
     */
    @Override
    @SuppressWarnings("PMD.CloseResource")
    public void closeAt(long shutdownDeadlineNanos) {
        long effectiveDeadline = registerCloseDeadline(shutdownDeadlineNanos);
        List<CancellationCleanupState> snapshot;
        boolean owner;
        lifecycle.writeLock().lock();
        try {
            owner = closed.compareAndSet(false, true);
            snapshot = owner ? List.copyOf(scopes.values()) : List.of();
            if (owner) scopes.clear();
        } finally {
            lifecycle.writeLock().unlock();
        }
        if (!owner) {
            awaitCloseCompletion(effectiveDeadline);
            return;
        }
        Throwable failure = null;
        try {
            List<Throwable> failures = new ArrayList<>();
            List<CompletableFuture<CancelOutcome>> cancellations = new ArrayList<>(snapshot.size());
            for (CancellationCleanupState scope : snapshot) {
                CompletableFuture<CancelOutcome> cancellation = new CompletableFuture<>();
                cancellations.add(cancellation);
                CancellationCleanupState.CancellationClaim claim =
                        scope.claimCancellation("runtime shutdown");
                try {
                    cancellationExecutor.execute(() -> dispatchCancellation(
                            scope, claim, claim.won() ? CancelOutcome.REQUESTED : CancelOutcome.ALREADY_REQUESTED,
                            cancellation));
                } catch (RejectedExecutionException rejected) {
                    scope.failClaim(claim, rejected);
                    completeAfterCleanup(scope,
                            claim.won() ? CancelOutcome.REQUESTED : CancelOutcome.ALREADY_REQUESTED,
                            cancellation);
                }
                if (!claim.won()) {
                    completeAfterCleanup(scope, CancelOutcome.ALREADY_REQUESTED, cancellation);
                }
            }
            // 先分派所有有界 Turn 回调，不能因 Scope 按 Map 顺序等待而让慢 Adapter 消耗其他 Turn 的关闭时间片。
            for (CompletableFuture<CancelOutcome> cancellation : cancellations) {
                awaitCancellation(cancellation, effectiveDeadline, failures);
            }
            snapshot.forEach(CancellationCleanupState::close);
            stopExecutor(failures, effectiveDeadline);
            if (!failures.isEmpty()) {
                failure = CleanupFailure.aggregate("runtime shutdown cleanup failed", failures);
            }
        } catch (Throwable closeFailure) {
            failure = closeFailure;
        } finally {
            if (failure == null) closeCompletion.complete(null);
            else closeCompletion.completeExceptionally(failure);
        }
        throwIfCloseFailed(failure);
    }

    /**
     * 以 CAS 固定首个关闭截止线，并让后续调用只能收紧而不能延长已承诺预算。
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
     * 基于单调时钟计算截止线，纳秒加法溢出时饱和到最大值。
     */
    private static long deadlineAfter(long durationNanos) {
        long now = System.nanoTime();
        long deadline = now + durationNanos;
        return deadline >= now ? deadline : Long.MAX_VALUE;
    }

    /**
     * 计算非负剩余预算，并对单调时钟差值溢出采用饱和值。
     */
    private static long remainingNanos(long shutdownDeadline) {
        long now = System.nanoTime();
        if (shutdownDeadline <= now) return 0L;
        long remaining = shutdownDeadline - now;
        return remaining > 0L ? remaining : Long.MAX_VALUE;
    }

    /**
     * 并发关闭者在共同截止线内复用 owner 的完成结果，并保持线程中断语义。
     */
    private void awaitCloseCompletion(long shutdownDeadline) {
        try {
            closeCompletion.get(remainingNanos(shutdownDeadline), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cancellation close was interrupted", interrupted);
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("cancellation close exceeded shutdown deadline", timeout);
        } catch (ExecutionException failure) {
            throwIfCloseFailed(failure.getCause() == null ? failure : failure.getCause());
        }
    }

    /**
     * 保留运行时异常和 Error 的原始类型，其余受检失败统一包装为完成异常。
     */
    private static void throwIfCloseFailed(Throwable failure) {
        if (failure == null) return;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new CompletionException(failure);
    }

    /**
     * 在协调器执行器上运行已认领回调，再把共享清理屏障映射到请求结果。
     */
    private static void dispatchCancellation(
            CancellationCleanupState scope, CancellationCleanupState.CancellationClaim claim, CancelOutcome outcome,
            CompletableFuture<CancelOutcome> result) {
        try {
            scope.runClaim(claim);
            completeAfterCleanup(scope, outcome, result);
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
    }

    /**
     * 只有 Scope 的全部清理完成后才确认取消结果，任何清理欠账都异常传播。
     */
    private static void completeAfterCleanup(
            CancellationCleanupState scope, CancelOutcome outcome, CompletableFuture<CancelOutcome> result) {
        scope.cleanupCompletion().whenComplete((ignored, failure) -> {
            if (failure == null) result.complete(outcome);
            else result.completeExceptionally(failure);
        });
    }

    /**
     * 在全局关闭预算内等待单个 Scope，并收集失败而不中断其他 Scope 的收口。
     */
    private static void awaitCancellation(
            CompletableFuture<CancelOutcome> cancellation, long shutdownDeadline, List<Throwable> failures) {
        try {
            long remaining = remainingNanos(shutdownDeadline);
            cancellation.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failures.add(interrupted);
        } catch (java.util.concurrent.TimeoutException timeout) {
            failures.add(new IllegalStateException("cancellation cleanup exceeded shutdown deadline", timeout));
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            failures.add(cause);
        }
    }

    /**
     * 在剩余预算内关闭自有执行器，超时或中断时强制停止并记录关闭欠账。
     */
    private void stopExecutor(List<Throwable> failures, long shutdownDeadline) {
        cancellationExecutor.shutdown();
        try {
            long remaining = remainingNanos(shutdownDeadline);
            if (!cancellationExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                cancellationExecutor.shutdownNow();
                failures.add(new IllegalStateException("cancellation executor did not terminate"));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancellationExecutor.shutdownNow();
            failures.add(exception);
        }
    }

    /**
     * 拒绝关闭快照后的新 Scope，防止关闭者遗漏活动 Turn。
     */
    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("cancellation coordinator is closed");
        }
    }

    /**
     * 约束复合键标识的字符集和长度，避免畸形身份进入活动 Scope 索引。
     */
    private static String requireIdentifier(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256
            || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException(name + " must be a safe non-blank identifier");
        }
        return value;
    }

    /**
     * 以 Thread 与 Turn 双维度标识取消 Scope，避免不同会话间发生串扰。
     */
    private record TurnKey(String threadId, String turnId) {
        /**
         * 校验两个身份分量后创建不可变索引键。
         */
        private static TurnKey create(String threadId, String turnId) {
            return new TurnKey(requireIdentifier(threadId, "threadId"), requireIdentifier(turnId, "turnId"));
        }
    }

    /**
     * 聚合多个取消回调或关闭阶段失败，保留每个原因为 suppressed 异常。
     */
    public static final class CleanupFailure extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        /**
         * 创建不携带外部资源细节的聚合异常外壳。
         */
        private CleanupFailure(String message) {
            super(message);
        }

        /**
         * 把所有独立清理失败附加到一个异常，避免首个失败掩盖后续欠账。
         */
        static CleanupFailure aggregate(String message, List<Throwable> failures) {
            CleanupFailure aggregate = new CleanupFailure(message);
            failures.forEach(aggregate::addSuppressed);
            return aggregate;
        }
    }
}
