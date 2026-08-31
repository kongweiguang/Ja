// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.concurrent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * 在整条逆序关闭链中共享的单调绝对期限。
 */
public final class ShutdownDeadline {
    /**
     * 生产关闭预算由最外层 owner 统一创建，内部资源只能消费剩余时间。
     */
    public static final Duration DEFAULT_BUDGET = Duration.ofSeconds(10);

    private final long deadlineNanos;

    /**
     * 只接收已经计算好的绝对值，避免任意层级把剩余时间重置为完整预算。
     */
    private ShutdownDeadline(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    /**
     * 使用默认生产预算启动一次关闭期限。
     */
    public static ShutdownDeadline start() {
        return start(DEFAULT_BUDGET);
    }

    /**
     * 使用单调时钟创建有限正预算，避免系统时间调整延长关闭过程。
     */
    public static ShutdownDeadline start(Duration budget) {
        Duration value = Objects.requireNonNull(budget, "budget");
        long nanos = value.toNanos();
        if (nanos <= 0 || nanos >= Long.MAX_VALUE / 2) {
            throw new IllegalArgumentException("shutdown budget must be positive and finite");
        }
        return new ShutdownDeadline(Math.addExact(System.nanoTime(), nanos));
    }

    /**
     * 从上游已经计算的绝对单调期限恢复值对象，禁止下游重新分配关闭预算。
     */
    public static ShutdownDeadline at(long deadlineNanos) {
        if (deadlineNanos <= 0) throw new IllegalArgumentException("invalid shutdown deadline");
        return new ShutdownDeadline(deadlineNanos);
    }

    /**
     * 返回剩余的单调纳秒数，过期后稳定返回零。
     */
    public long remainingNanos() {
        long remaining = deadlineNanos - System.nanoTime();
        return remaining > 0 ? remaining : 0;
    }

    /**
     * 暴露同一个不可变绝对期限，供资源适配器继续向下传递。
     */
    public long deadlineNanos() {
        return deadlineNanos;
    }

    /**
     * 向只接受毫秒的线程 API 提供向上取整的非零剩余值。
     */
    public long remainingMillis() {
        long nanos = remainingNanos();
        if (nanos == 0) return 0;
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos - 1) + 1);
    }

    /**
     * 判断共享绝对期限是否已经耗尽。
     */
    public boolean expired() {
        return remainingNanos() == 0;
    }

    /**
     * 在同一剩余预算内等待异步 owner，禁止嵌套无界等待。
     */
    public <T> T await(CompletionStage<T> stage, String owner) {
        Objects.requireNonNull(stage, "stage");
        String name = requireOwner(owner);
        CompletableFuture<T> future = stage.toCompletableFuture();
        if (future.isDone()) {
            try {
                return future.join();
            } catch (CompletionException failure) {
                throw rethrow(name, failure.getCause());
            }
        }
        long remaining = remainingNanos();
        if (remaining == 0) throw forced(name, null);
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw forced(name + " interrupted", interrupted);
        } catch (TimeoutException timeout) {
            throw forced(name + " timed out", timeout);
        } catch (ExecutionException failure) {
            throw rethrow(name, failure.getCause());
        }
    }

    /**
     * 去掉异步包装异常，同时保留本地根因供诊断。
     */
    public static RuntimeException rethrow(String owner, Throwable failure) {
        Throwable value = failure;
        while ((value instanceof CompletionException || value instanceof ExecutionException)
               && value.getCause() != null) {
            value = value.getCause();
        }
        if (value instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException(requireOwner(owner), value);
    }

    /**
     * 创建必须由进程级 owner 处理的强制终止标记。
     */
    public static ForcedTerminationException forced(String owner, Throwable cause) {
        return new ForcedTerminationException(requireOwner(owner), cause);
    }

    /**
     * 区分不安全关闭与普通请求、存储失败的稳定异常类型。
     */
    public static final class ForcedTerminationException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        /**
         * 只保留稳定 owner 描述和本地根因，不增加新的关闭预算。
         */
        public ForcedTerminationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 拒绝空 owner 标签，确保强制终止错误始终可定位。
     */
    private static String requireOwner(String owner) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("owner is required");
        return owner;
    }
}
