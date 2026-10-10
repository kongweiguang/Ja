// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.shared;


import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 以单调时钟表达一次操作的绝对截止时间，避免嵌套调用重置剩余预算。
 */
public final class DeadlineBudget {
    private final long deadlineNanos;

    /**
     * 只接收已计算的单调绝对值，避免构造阶段重新读取系统时间扩大预算。
     *
     * @param deadlineNanos 单调时钟绝对纳秒值
     */
    private DeadlineBudget(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    /**
     * 从有限正预算创建单调截止时间；溢出时拒绝而不静默延长等待。
     *
     * @param budget 剩余预算
     * @return 不可变截止时间
     */
    public static DeadlineBudget after(Duration budget) {
        Duration value = Objects.requireNonNull(budget, "budget");
        long nanos;
        try {
            nanos = value.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("deadline budget must be positive and finite", overflow);
        }
        long now = System.nanoTime();
        if (nanos <= 0) {
            throw new IllegalArgumentException("deadline budget must be positive and finite");
        }
        try {
            return new DeadlineBudget(Math.addExact(now, nanos));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("deadline budget exceeds monotonic clock range", overflow);
        }
    }

    /**
     * 包装上游已计算的绝对期限，保持调用链共享同一时间边界。
     *
     * @param deadlineNanos 单调时钟绝对纳秒值
     * @return 不可变截止时间
     */
    public static DeadlineBudget at(long deadlineNanos) {
        if (deadlineNanos <= 0) {
            throw new IllegalArgumentException("deadlineNanos must be positive");
        }
        return new DeadlineBudget(deadlineNanos);
    }

    /**
     * 返回尚未耗尽的纳秒预算，过期后稳定返回零。
     *
     * @return 剩余纳秒数
     */
    public long remainingNanos() {
        long remaining = deadlineNanos - System.nanoTime();
        return remaining > 0 ? remaining : 0;
    }

    /**
     * 向只接收毫秒的 API 提供非零预算的向上取整值。
     *
     * @return 剩余毫秒数
     */
    public long remainingMillis() {
        long nanos = remainingNanos();
        return nanos == 0 ? 0 : Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos - 1) + 1);
    }

    /**
     * 判断截止时间是否已耗尽。
     *
     * @return 是否过期
     */
    public boolean expired() {
        return remainingNanos() == 0;
    }

    /**
     * 暴露绝对值供下游继续传递，禁止下游根据剩余值重建新的预算。
     *
     * @return 单调时钟绝对纳秒值
     */
    public long deadlineNanos() {
        return deadlineNanos;
    }

    /**
     * 在共享截止时间内等待清理阶段，禁止每个资源重新创建独立超时预算。
     *
     * @param stage 待等待的清理阶段
     * @param owner 资源 owner
     * @param <T> 阶段结果类型
     * @return 阶段结果
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    public <T> T await(CompletionStage<T> stage, String owner) {
        Objects.requireNonNull(stage, "stage");
        String scope = Objects.requireNonNull(owner, "owner");
        if (scope.isBlank()) {
            throw new IllegalArgumentException("owner is required");
        }
        CompletableFuture<T> future = stage.toCompletableFuture();
        if (future.isDone()) {
            try {
                return future.join();
            } catch (CompletionException failure) {
                throw CompletionFailures.propagate(scope, failure);
            }
        }
        long remaining = remainingNanos();
        if (remaining == 0) {
            throw new DeadlineExceededException(scope, null);
        }
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DeadlineExceededException(scope + " interrupted", interrupted);
        } catch (TimeoutException timeout) {
            throw new DeadlineExceededException(scope + " timed out", timeout);
        } catch (ExecutionException failure) {
            throw CompletionFailures.propagate(scope, failure.getCause());
        }
    }

    /**
     * 标记清理阶段无法在 owner 共享的绝对期限内完成，调用方不得刷新期限继续等待。
     */
    public static final class DeadlineExceededException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        /**
         * 保存 owner 和本地根因，供进程级关闭协调器聚合诊断。
         *
         * @param message owner 及阶段状态
         * @param cause 超时或中断根因
         */
        public DeadlineExceededException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
