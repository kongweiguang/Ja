// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 为阻塞型短任务提供“每任务一个虚拟线程”，同时把资源并发和总准入作为两个独立上限。
 */
public final class BoundedVirtualExecutor extends AbstractExecutorService {
    private final ExecutorService delegate;
    private final Semaphore admittedPermits;
    private final Semaphore activePermits;
    private final AtomicInteger admittedTasks = new AtomicInteger();
    private final AtomicInteger activeTasks = new AtomicInteger();
    private final Set<TrackedTask> tasks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Object lifecycle = new Object();
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final AtomicBoolean stoppingNow = new AtomicBoolean();

    /**
     * 创建公平双门执行器；admitted 包含运行中与等待 active permit 的全部任务，必须覆盖 active。
     */
    public BoundedVirtualExecutor(String threadNamePrefix, int maxActiveTasks, int maxAdmittedTasks) {
        if (threadNamePrefix == null || threadNamePrefix.isBlank()) {
            throw new IllegalArgumentException("threadNamePrefix is required");
        }
        if (maxActiveTasks < 1 || maxAdmittedTasks < maxActiveTasks) {
            throw new IllegalArgumentException("executor bounds are invalid");
        }
        admittedPermits = new Semaphore(maxAdmittedTasks, true);
        activePermits = new Semaphore(maxActiveTasks, true);
        delegate = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(threadNamePrefix, 0).factory());
    }

    /**
     * 非阻塞占用准入许可后才创建虚拟线程，避免容量耗尽时继续扩张任务对象和线程。
     */
    @Override
    public void execute(Runnable command) {
        Runnable required = Objects.requireNonNull(command, "command");
        if (!admittedPermits.tryAcquire()) {
            throw new RejectedExecutionException("executor capacity is exhausted");
        }
        TrackedTask tracked = new TrackedTask(required);
        boolean accepted = false;
        synchronized (lifecycle) {
            if (!shutdown.get()) {
                tasks.add(tracked);
                admittedTasks.incrementAndGet();
                accepted = true;
            }
        }
        if (!accepted) {
            admittedPermits.release();
            throw new RejectedExecutionException("executor is shutting down");
        }
        try {
            delegate.execute(tracked);
        } catch (RuntimeException | Error rejected) {
            settle(tracked);
            throw rejected;
        }
    }

    /**
     * 使用可绑定包装线程的 Future，使尚在等待 active permit 的提交也能被 cancel(true) 立即唤醒。
     */
    @Override
    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new InterruptibleFutureTask<>(Objects.requireNonNull(callable, "callable"));
    }

    /**
     * 与 Callable 路径共享可中断等待语义，避免 Runnable Future 在关闭时滞留许可。
     */
    @Override
    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new InterruptibleFutureTask<>(Objects.requireNonNull(runnable, "runnable"), value);
    }

    /**
     * 停止新准入但允许所有已接纳任务自然获取 active permit 并完成。
     */
    @Override
    public void shutdown() {
        boolean owner;
        synchronized (lifecycle) {
            owner = shutdown.compareAndSet(false, true);
        }
        if (owner) {
            delegate.shutdown();
        }
    }

    /**
     * 进入强制关闭后同时中断运行任务和等待许可的虚拟线程，并返回尚未进入用户代码的任务。
     */
    @Override
    public List<Runnable> shutdownNow() {
        List<TrackedTask> snapshot;
        synchronized (lifecycle) {
            shutdown.set(true);
            stoppingNow.set(true);
            snapshot = List.copyOf(tasks);
        }
        List<Runnable> notCommenced = delegate.shutdownNow();
        for (Runnable task : notCommenced) {
            if (task instanceof BoundedVirtualExecutor.TrackedTask tracked) {
                settle(tracked);
            }
        }
        List<Runnable> neverStarted = new ArrayList<>();
        for (TrackedTask task : snapshot) {
            if (!task.userTaskStarted.get()) {
                neverStarted.add(task.command);
            }
            task.interrupt();
        }
        return List.copyOf(neverStarted);
    }

    /**
     * 返回是否已经封闭准入；该值在第一次 shutdown 后单调为真。
     */
    @Override
    public boolean isShutdown() {
        return shutdown.get();
    }

    /**
     * 只有底层所有虚拟线程结束且许可登记集合为空时才报告终止。
     */
    @Override
    public boolean isTerminated() {
        return delegate.isTerminated() && tasks.isEmpty();
    }

    /**
     * 消费调用方提供的等待预算；本方法不创建新的关闭期限，也不隐式升级为强制关闭。
     */
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        return delegate.awaitTermination(timeout, unit) && tasks.isEmpty();
    }

    /**
     * 暴露已接纳任务数用于生命周期诊断和测试，不能据此改变 Wire 或业务限额。
     */
    public int admittedTaskCount() {
        return admittedTasks.get();
    }

    /**
     * 暴露正在执行用户代码的任务数，用于证明资源并发上限没有被虚拟线程数量绕过。
     */
    public int activeTaskCount() {
        return activeTasks.get();
    }

    /**
     * 在虚拟线程内可中断地获取资源许可；无论用户代码如何结束都只释放一次两级许可。
     */
    private void run(TrackedTask tracked) {
        boolean active = false;
        tracked.bind(Thread.currentThread());
        try {
            if (tracked.cancelled() || stoppingNow.get()) {
                tracked.cancelFuture();
                return;
            }
            activePermits.acquire();
            active = true;
            activeTasks.incrementAndGet();
            if (tracked.cancelled() || stoppingNow.get()) {
                tracked.cancelFuture();
                return;
            }
            tracked.userTaskStarted.set(true);
            tracked.command.run();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            tracked.cancelFuture();
        } finally {
            tracked.unbind();
            if (active) {
                activeTasks.decrementAndGet();
                activePermits.release();
            }
            settle(tracked);
        }
    }

    /**
     * 从所有索引移除任务并释放准入许可；CAS 防止提交拒绝与线程 finally 竞争时双重释放。
     */
    private void settle(TrackedTask tracked) {
        if (!tracked.settled.compareAndSet(false, true)) {
            return;
        }
        tasks.remove(tracked);
        admittedTasks.decrementAndGet();
        admittedPermits.release();
    }

    /**
     * 保存底层命令和包装线程，强制关闭只中断执行边界，不在生命周期锁内调用用户代码。
     */
    private final class TrackedTask implements Runnable {
        private final Runnable command;
        private final AtomicBoolean settled = new AtomicBoolean();
        private final AtomicBoolean userTaskStarted = new AtomicBoolean();
        private volatile Thread runner;

        /**
         * 固定提交时的用户命令，防止关闭快照观察到可变代理。
         */
        private TrackedTask(Runnable command) {
            this.command = command;
        }

        /**
         * 委托统一运行模板，确保任何出口都回收 active 和 admitted 许可。
         */
        @Override
        public void run() {
            BoundedVirtualExecutor.this.run(this);
        }

        /**
         * 在获取 active permit 前绑定包装线程，使 Future 取消能够中断 Semaphore 等待。
         */
        private void bind(Thread value) {
            runner = value;
            if (cancelled()) {
                value.interrupt();
            }
            if (command instanceof InterruptibleFutureTask<?> future) {
                future.bind(value);
            }
        }

        /**
         * 清除包装线程引用，避免已完成虚拟线程被 Future 或执行器集合保留。
         */
        private void unbind() {
            if (command instanceof InterruptibleFutureTask<?> future) {
                future.unbind();
            }
            runner = null;
        }

        /**
         * 强制关闭同时取消 Future 与中断包装线程；普通 Runnable 依赖线程中断协作退出。
         */
        private void interrupt() {
            cancelFuture();
            Thread value = runner;
            if (value != null) {
                value.interrupt();
            }
        }

        /**
         * 只取消 ExecutorService.submit 生成的 Future，execute 提交的普通 Runnable 不伪造完成信号。
         */
        private void cancelFuture() {
            if (command instanceof Future<?> future) {
                future.cancel(true);
            }
        }

        /**
         * Future 在真正运行前取消时阻止其继续占用 active permit。
         */
        private boolean cancelled() {
            return command instanceof Future<?> future && future.isCancelled();
        }
    }

    /**
     * 把 Future 的 cancel(true) 传播给等待资源许可的包装虚拟线程，而不仅是尚未调用的 FutureTask。
     */
    private static final class InterruptibleFutureTask<T> extends FutureTask<T> {
        private volatile Thread wrapper;

        /**
         * 创建 Callable Future，结果和异常仍由 JDK FutureTask 负责兑现。
         */
        private InterruptibleFutureTask(Callable<T> callable) {
            super(callable);
        }

        /**
         * 创建 Runnable Future，保持 AbstractExecutorService 的标准 submit 返回语义。
         */
        private InterruptibleFutureTask(Runnable runnable, T value) {
            super(runnable, value);
        }

        /**
         * 登记等待 active permit 的包装线程；已取消 Future 会立即中断迟到绑定者。
         */
        private void bind(Thread value) {
            wrapper = value;
            if (isCancelled()) {
                value.interrupt();
            }
        }

        /**
         * Future 完成后解除线程引用，避免生命周期外持有虚拟线程对象。
         */
        private void unbind() {
            wrapper = null;
        }

        /**
         * 在保持 FutureTask 标准状态机的同时唤醒尚未进入 FutureTask.run 的包装线程。
         */
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled && mayInterruptIfRunning) {
                Thread value = wrapper;
                if (value != null) {
                    value.interrupt();
                }
            }
            return cancelled;
        }
    }
}
