// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.engine;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 同一 Thread 串行、有界排队，不同 Thread 可以独立推进。
 */
public final class ExecutionScheduler implements AutoCloseable {
    private final Object lock = new Object();
    private final Map<ThreadKey, ThreadQueue> threads = new HashMap<>();
    private final ExecutorService executor;
    private final int maxOutstanding;
    private final int maxOutstandingPerThread;
    private int outstanding;
    private boolean closed;

    /**
     * 创建 Engine 私有的有界调度器；容量保护服务资源，不限制 Agent 循环进展。
     */
    public ExecutionScheduler(int parallelism, int maxOutstanding, int maxOutstandingPerThread) {
        if (parallelism < 1 || maxOutstanding < 1 || maxOutstandingPerThread < 1
            || maxOutstandingPerThread > maxOutstanding) {
            throw new IllegalArgumentException("invalid execution scheduler limits");
        }
        this.maxOutstanding = maxOutstanding;
        this.maxOutstandingPerThread = maxOutstandingPerThread;
        ThreadFactory threads = new ThreadFactory() {
            private final AtomicInteger sequence = new AtomicInteger();

            /** 为执行线程设置可诊断名称，并让线程池所有权留在 Engine 内。 */
            @Override
            public Thread newThread(Runnable task) {
                return Thread.ofPlatform().name("ja-agent-engine-" + sequence.incrementAndGet()).daemon(true).unstarted(task);
            }
        };
        this.executor = new ThreadPoolExecutor(parallelism, parallelism, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxOutstanding), threads, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 去掉 CompletionStage 包装，同时保留 Core 抛出的原始异常。
     */
    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 在任何准入事务提交前预留容量和 FIFO Thread 位置。
     */
    public Reservation reserve(ThreadKey key) {
        Objects.requireNonNull(key, "key");
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("agent engine is closed");
            }
            if (outstanding >= maxOutstanding) {
                throw new RejectedExecutionException("agent execution queue is full");
            }
            ThreadQueue queue = threads.computeIfAbsent(key, ignored -> new ThreadQueue());
            if (queue.tasks.size() >= maxOutstandingPerThread) {
                if (queue.tasks.isEmpty() && !queue.running) {
                    threads.remove(key);
                }
                throw new RejectedExecutionException("thread execution queue is full");
            }
            Task task = new Task(key);
            queue.tasks.addLast(task);
            outstanding++;
            return new Reservation(task);
        }
    }

    /**
     * 停止接收新工作，并拒绝未开始的任务；已提交执行仍可正常结算。
     */
    @Override
    public void close() {
        Map<Task, Throwable> rejected = new HashMap<>();
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            CancellationException shutdown = new CancellationException("agent engine closed before execution started");
            for (ThreadQueue queue : threads.values()) {
                for (Task task : queue.tasks) {
                    if (!task.started) {
                        task.cancelled = true;
                        rejected.put(task, shutdown);
                        outstanding--;
                    }
                }
                queue.tasks.removeIf(task -> !task.started);
                queue.running = queue.tasks.stream().anyMatch(task -> task.started);
            }
            threads.entrySet().removeIf(entry -> entry.getValue().tasks.isEmpty());
        }
        rejected.forEach((task, failure) -> task.completion.completeExceptionally(failure));
        executor.shutdown();
    }

    /**
     * 仅选择已就绪的 FIFO 队首，后续任务不得越过仍在准入的前序请求。
     */
    private void dispatchHead(ThreadQueue queue) {
        if (closed || queue.running) {
            return;
        }
        Task head = queue.tasks.peekFirst();
        if (head == null || !head.ready) {
            return;
        }
        queue.running = true;
        try {
            executor.execute(() -> run(head));
        } catch (RejectedExecutionException failure) {
            removeUnstarted(head, failure);
        }
    }

    /**
     * 只调用一次 Core 执行，并持有 Thread 闸门直到异步执行真正完成。
     */
    private void run(Task task) {
        synchronized (lock) {
            if (closed || task.cancelled || task.completion.isDone()) {
                removeUnstarted(task, new CancellationException("agent engine closed before execution started"));
                return;
            }
            task.started = true;
        }
        try {
            CompletionStage<?> result = Objects.requireNonNull(task.work.get(), "execution stage");
            result.whenComplete((ignored, failure) -> finish(task, failure));
        } catch (Throwable failure) {
            finish(task, failure);
        }
    }

    /**
     * Core 执行结算后才释放 Thread，保证同一 Thread 的 Turn 不会重叠。
     */
    private void finish(Task task, Throwable failure) {
        synchronized (lock) {
            if (task.finished) {
                return;
            }
            task.finished = true;
            ThreadQueue queue = threads.get(task.key);
            if (queue != null) {
                if (queue.tasks.peekFirst() == task) {
                    queue.tasks.removeFirst();
                }
                else if (!queue.tasks.remove(task)) {
                    return;
                }
                queue.running = false;
                outstanding--;
                if (queue.tasks.isEmpty()) {
                    threads.remove(task.key);
                }
                else {
                    dispatchHead(queue);
                }
            }
        }
        if (failure == null) {
            task.completion.complete(null);
        }
        else {
            task.completion.completeExceptionally(unwrap(failure));
        }
    }

    /**
     * 只有移除队首才释放 Thread 闸门；后续准入失败不得清除正在执行的队首标记或触发重复派发。
     */
    private void removeUnstarted(Task task, Throwable failure) {
        ThreadQueue queue = threads.get(task.key);
        boolean head = queue != null && queue.tasks.peekFirst() == task;
        if (queue == null || !queue.tasks.remove(task)) {
            return;
        }
        task.cancelled = true;
        outstanding--;
        if (head) {
            queue.running = false;
        }
        if (queue.tasks.isEmpty()) {
            threads.remove(task.key);
        }
        else {
            dispatchHead(queue);
        }
        task.completion.completeExceptionally(failure);
    }

    /**
     * 用租户与 Thread 共同标识调度分区，避免跨租户的同名 Thread 冲突。
     *
     * @param tenantId 租户标识，用于隔离不同租户的数据与执行
     * @param threadId 所属对话的稳定标识
     */
    public record ThreadKey(String tenantId,
                            String threadId) {
        /**
         * 在成为队列分区前校验租户和 Thread 身份。
         */
        public ThreadKey {
            if (tenantId == null || tenantId.isBlank() || threadId == null || threadId.isBlank()) {
                throw new IllegalArgumentException("invalid thread key");
            }
        }
    }

    /**
     * 单 Thread FIFO 保留一个运行头项，后续准入只能在它真正结算后派发。
     */
    private static final class ThreadQueue {
        private final ArrayDeque<Task> tasks = new ArrayDeque<>();
        private boolean running;
    }

    /**
     * 预留、持久准入与执行用同一任务对象，避免失败清理误放行后续会话项。
     */
    private static final class Task {
        private final ThreadKey key;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private Supplier<? extends CompletionStage<?>> work;
        private boolean ready;
        private boolean started;
        private boolean cancelled;
        private boolean finished;

        /**
         * 预留一项尚未取得持久准入回执的任务；未就绪时被误派发必须显式失败。
         */
        private Task(ThreadKey key) {
            this.key = key;
            this.work = () -> {
                throw new IllegalStateException("execution task was dispatched before admission completed");
            };
        }
    }

    /**
     * 当提交阶段结束时自动释放对应调度预留。
     */
    public final class Reservation implements AutoCloseable {
        private final Task task;
        private boolean completed;

        /**
         * 将调度租约绑定到预留任务，不向门面暴露队列内部结构。
         */
        private Reservation(Task task) {
            this.task = task;
        }

        /**
         * 只在取得持久准入回执后派发任务，并保持 Thread 占用至执行阶段结算。
         */
        public CompletionStage<Void> start(Supplier<? extends CompletionStage<?>> work) {
            Objects.requireNonNull(work, "work");
            synchronized (lock) {
                if (completed) {
                    throw new IllegalStateException("execution reservation is already complete");
                }
                if (closed) {
                    throw new IllegalStateException("agent engine is closed");
                }
                if (task.ready) {
                    throw new IllegalStateException("execution reservation has already started");
                }
                task.work = work;
                task.ready = true;
                completed = true;
                ThreadQueue queue = threads.get(task.key);
                if (queue == null) {
                    throw new IllegalStateException("execution reservation is no longer scheduled");
                }
                dispatchHead(queue);
                return task.completion;
            }
        }

        /**
         * 准入失败时释放尚未开始的队列位置，让同 Thread 下一项工作继续。
         */
        @Override
        public void close() {
            synchronized (lock) {
                if (completed) {
                    return;
                }
                completed = true;
                removeUnstarted(task, new CancellationException("execution admission did not complete"));
            }
        }
    }
}
