// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AgentLoop 代际共享的 delta 延迟调度 Owner；每个 Round 只持有可独立关闭的轻量句柄。
 */
final class DeltaTimerScheduler implements AutoCloseable {
    private static final Duration DEFAULT_CLOSE_BUDGET = Duration.ofSeconds(2);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("ja-delta-flush", 0).factory());
    private final Set<TimerHandle> handles = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 为一个模型 Round 创建隔离句柄；Turn 身份只用于输入校验，不能进入线程名或日志。
     */
    StreamingDeltaBatcher.Timer openTimer(String turnId, int round) {
        if (turnId == null || turnId.isBlank() || round < 1) {
            throw new IllegalArgumentException("invalid delta timer identity");
        }
        if (closed.get()) {
            throw new RejectedExecutionException("delta scheduler is closed");
        }
        TimerHandle handle = new TimerHandle();
        handles.add(handle);
        if (closed.get()) {
            handle.close();
            throw new RejectedExecutionException("delta scheduler is closed");
        }
        return handle;
    }

    /**
     * 使用默认预算关闭独立测试或直接 owner；嵌套 AgentLoop 关闭必须改用 closeAt。
     */
    @Override
    public void close() {
        long budget = DEFAULT_CLOSE_BUDGET.toNanos();
        long now = System.nanoTime();
        closeAt(now >= Long.MAX_VALUE - budget ? Long.MAX_VALUE : now + budget);
    }

    /**
     * 先使全部句柄失效再停止平台 Scheduler，并且只消费上游绝对期限的剩余时间。
     */
    void closeAt(long shutdownDeadlineNanos) {
        if (closed.compareAndSet(false, true)) {
            List.copyOf(handles).forEach(TimerHandle::close);
            scheduler.shutdownNow();
        }
        long remaining = shutdownDeadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            if (!scheduler.isTerminated()) {
                throw new IllegalStateException("delta scheduler close deadline expired");
            }
            return;
        }
        try {
            if (!scheduler.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("delta scheduler did not terminate");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("delta scheduler close interrupted", interrupted);
        }
    }

    /**
     * Round 句柄只取消自己的延迟任务；迟到 Runnable 还会检查句柄和 Scheduler 双重关闭状态。
     */
    private final class TimerHandle implements StreamingDeltaBatcher.Timer {
        private final Set<ScheduledTask> tasks = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final AtomicBoolean handleClosed = new AtomicBoolean();

        /**
         * 将短回调放入共享平台 Scheduler，句柄关闭竞争通过任务对象的 started/cancelled 状态收口。
         */
        @Override
        public Task schedule(Runnable callback, Duration delay) {
            Objects.requireNonNull(callback, "callback");
            Duration requiredDelay = Objects.requireNonNull(delay, "delay");
            if (requiredDelay.isNegative() || handleClosed.get() || closed.get()) {
                throw new RejectedExecutionException("delta timer is closed");
            }
            ScheduledTask task = new ScheduledTask(callback);
            tasks.add(task);
            try {
                task.schedule(requiredDelay);
            } catch (RuntimeException rejected) {
                tasks.remove(task);
                task.cancel();
                throw rejected;
            }
            if (handleClosed.get() || closed.get()) {
                task.cancel();
            }
            return task::cancel;
        }

        /**
         * 幂等撤销本 Round 尚未执行的任务并解除 Owner 引用，不影响其它并发 Round。
         */
        @Override
        public void close() {
            if (!handleClosed.compareAndSet(false, true)) {
                return;
            }
            List.copyOf(tasks).forEach(ScheduledTask::cancel);
            handles.remove(this);
        }

        /**
         * 包装单个 callback，使取消、开始和集合移除保持一个原子生命周期。
         */
        private final class ScheduledTask implements Runnable {
            private final Runnable callback;
            private final AtomicBoolean cancelled = new AtomicBoolean();
            private volatile ScheduledFuture<?> future;

            /**
             * 固定回调引用，实际执行前仍需复核两层关闭状态。
             */
            private ScheduledTask(Runnable callback) {
                this.callback = callback;
            }

            /**
             * 保存 Future 后再次检查取消，覆盖 close 与 schedule 返回之间的竞争窗口。
             */
            private void schedule(Duration delay) {
                future = scheduler.schedule(this, delay.toNanos(), TimeUnit.NANOSECONDS);
                if (cancelled.get()) {
                    future.cancel(false);
                }
            }

            /**
             * 任务出队后先解除集合引用；只有代际和句柄均开放时才允许进入 batcher generation 检查。
             */
            @Override
            public void run() {
                tasks.remove(this);
                if (!cancelled.get() && !handleClosed.get() && !closed.get()) {
                    callback.run();
                }
            }

            /**
             * 取消不打断已经运行的短状态机回调，迟到执行由关闭标记和 batch generation 丢弃。
             */
            private void cancel() {
                cancelled.set(true);
                tasks.remove(this);
                ScheduledFuture<?> scheduled = future;
                if (scheduled != null) {
                    scheduled.cancel(false);
                }
            }
        }
    }
}
