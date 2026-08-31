// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.cancellation;

import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 以单一监视器协调取消发布、回调认领和清理完成屏障，保证每个回调至多执行一次。
 */
final class CancellationCleanupState implements CancellationCoordinator.CancellationScope {
    private final Object monitor = new Object();
    private final ExecutorService cleanupExecutor;
    private final AtomicLong registrationIds = new AtomicLong();
    private final LinkedHashMap<Long, CallbackRegistration> callbacks = new LinkedHashMap<>();
    private final CompletableFuture<Void> cleanup = new CompletableFuture<>();
    private final List<Throwable> cleanupFailures = new ArrayList<>();
    private boolean cancelled;
    private boolean closed;
    private boolean initialDispatchFinished;
    private int activeCallbacks;
    private String reason;

    /**
     * 固定清理执行器所有权，取消调用线程只发布状态而不直接运行外部回调。
     */
    CancellationCleanupState(ExecutorService cleanupExecutor) {
        this.cleanupExecutor = Objects.requireNonNull(cleanupExecutor, "cleanupExecutor");
    }

    /**
     * 在监视器下读取权威取消位，保证回调登记能观察一致的发布顺序。
     */
    @Override
    public boolean isCancellationRequested() {
        synchronized (monitor) {
            return cancelled;
        }
    }

    /**
     * 返回首次取消原因；重复取消不得覆盖诊断所依赖的原始事实。
     */
    @Override
    public Optional<String> reason() {
        synchronized (monitor) {
            return Optional.ofNullable(reason);
        }
    }

    /**
     * 取消前登记可撤销回调，取消后把迟到回调计入同一屏障并异步执行。
     */
    @Override
    public Registration onCancellation(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        CallbackRegistration registration;
        synchronized (monitor) {
            if (closed) {
                return Registration.noop();
            }
            if (!cancelled) {
                long id = registrationIds.incrementAndGet();
                registration = new CallbackRegistration(this, id, callback);
                callbacks.put(id, registration);
                return registration;
            }
            activeCallbacks++;
        }
        try {
            cleanupExecutor.execute(() -> runImmediate(callback));
        } catch (RejectedExecutionException rejected) {
            synchronized (monitor) {
                cleanupFailures.add(rejected);
                activeCallbacks--;
                completeCleanupIfReady();
            }
        }
        return Registration.noop();
    }

    /**
     * 原子认领首次取消并异步分派快照；执行器拒绝时仍保留已发布取消事实。
     */
    @Override
    public boolean requestCancellation(String cancellationReason) {
        CancellationClaim claim = claimCancellation(cancellationReason);
        if (!claim.won()) return false;
        try {
            cleanupExecutor.execute(() -> runClaim(claim));
        } catch (RejectedExecutionException rejected) {
            // 取消位是权威事实；执行器丢失时直接完成屏障，避免在调用线程同步执行用户清理。
            failClaim(claim, rejected);
        }
        return true;
    }

    /**
     * 在监视器内冻结首次原因并认领全部回调，避免注销与分派重复执行同一回调。
     */
    @SuppressWarnings("PMD.CloseResource")
    CancellationClaim claimCancellation(String cancellationReason) {
        String boundedReason = normalizeReason(cancellationReason);
        List<CallbackRegistration> claimed = new ArrayList<>();
        synchronized (monitor) {
            if (closed || cancelled) {
                return new CancellationClaim(false, List.of());
            }
            cancelled = true;
            reason = boundedReason;
            for (CallbackRegistration registration : callbacks.values()) {
                if (registration.claim()) {
                    claimed.add(registration);
                }
            }
            callbacks.clear();
            activeCallbacks += claimed.size();
        }
        return new CancellationClaim(true, List.copyOf(claimed));
    }

    /**
     * 逐个执行已认领回调，完成初始分派后再判断共享清理屏障是否可终结。
     */
    @SuppressWarnings("PMD.CloseResource")
    void runClaim(CancellationClaim claim) {
        if (!claim.won()) return;
        for (CallbackRegistration registration : claim.callbacks()) {
            runImmediate(registration.callback);
        }
        synchronized (monitor) {
            initialDispatchFinished = true;
            completeCleanupIfReady();
        }
    }

    /**
     * 执行器拒绝认领任务时释放回调计数并把拒绝原因计入清理失败聚合。
     */
    void failClaim(CancellationClaim claim, Throwable failure) {
        if (!claim.won()) return;
        synchronized (monitor) {
            cleanupFailures.add(Objects.requireNonNull(failure, "failure"));
            activeCallbacks -= claim.callbacks().size();
            initialDispatchFinished = true;
            completeCleanupIfReady();
        }
    }

    /**
     * 返回所有初始及迟到取消回调共享的完成屏障，重复调用不得创建新阶段。
     */
    @Override
    public CompletionStage<Void> cleanupCompletion() {
        return cleanup.minimalCompletionStage();
    }

    /**
     * 禁止新登记并解除未认领回调引用；已发布取消仍必须等待在途清理。
     */
    @Override
    public void close() {
        synchronized (monitor) {
            if (closed) return;
            closed = true;
            callbacks.values().forEach(CallbackRegistration::claim);
            callbacks.clear();
            if (!cancelled) {
                cleanup.complete(null);
            } else {
                completeCleanupIfReady();
            }
        }
    }

    /**
     * 隔离单个回调异常并配对递减活动计数，确保其余清理仍可执行。
     */
    private void runImmediate(Runnable callback) {
        try {
            callback.run();
        } catch (Throwable failure) {
            synchronized (monitor) {
                cleanupFailures.add(failure);
            }
        } finally {
            synchronized (monitor) {
                activeCallbacks--;
                completeCleanupIfReady();
            }
        }
    }

    /**
     * 仅在初始分派结束且活动回调归零时一次性完成屏障，并聚合全部清理失败。
     */
    private void completeCleanupIfReady() {
        if (!cancelled || !initialDispatchFinished || activeCallbacks != 0 || cleanup.isDone()) {
            return;
        }
        if (cleanupFailures.isEmpty()) {
            cleanup.complete(null);
        } else {
            cleanup.completeExceptionally(DefaultCancellationCoordinator.CleanupFailure.aggregate(
                    "turn cancellation cleanup failed", List.copyOf(cleanupFailures)));
        }
    }

    /**
     * 对首次取消原因实施长度和 NUL 约束，防止无界诊断文本进入共享状态。
     */
    private static String normalizeReason(String value) {
        Objects.requireNonNull(value, "reason");
        if (value.isBlank() || value.length() > 4_096 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("reason must be non-blank, NUL-free, and at most 4096 characters");
        }
        return value;
    }

    /**
     * 冻结一次取消竞争的胜负与回调快照，使发布和执行阶段可以安全分离。
     */
    static final class CancellationClaim {
        private final boolean won;
        private final List<CallbackRegistration> callbacks;

        /**
         * 复制回调快照，防止监视器释放后集合被注销路径修改。
         */
        private CancellationClaim(boolean won, List<CallbackRegistration> callbacks) {
            this.won = won;
            this.callbacks = List.copyOf(callbacks);
        }

        /**
         * 指示本次调用是否拥有首次取消分派权。
         */
        boolean won() {
            return won;
        }

        /**
         * 返回首次取消时已原子认领的不可变回调序列。
         */
        List<CallbackRegistration> callbacks() {
            return callbacks;
        }
    }

    /**
     * 以原子认领位协调注销与取消分派，确保回调最多由一条路径取得。
     */
    static final class CallbackRegistration implements CancellationToken.Registration {
        private final CancellationCleanupState owner;
        private final long id;
        private final Runnable callback;
        private final AtomicBoolean claimed = new AtomicBoolean();

        /**
         * 绑定所属状态、登记序号和回调，序号只用于精确移除本次登记。
         */
        private CallbackRegistration(CancellationCleanupState owner, long id, Runnable callback) {
            this.owner = owner;
            this.id = id;
            this.callback = callback;
        }

        /**
         * 仅由注销竞争赢家从待分派集合移除登记，已认领回调不再干预。
         */
        @Override
        public void close() {
            if (!claim()) return;
            synchronized (owner.monitor) {
                owner.callbacks.remove(id, this);
            }
        }

        /**
         * 原子争夺回调所有权，统一约束注销和取消分派的 exactly-once 语义。
         */
        private boolean claim() {
            return claimed.compareAndSet(false, true);
        }
    }
}
