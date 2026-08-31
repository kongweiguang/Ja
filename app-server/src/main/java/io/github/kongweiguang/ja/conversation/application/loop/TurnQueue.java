// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 以 Thread lane 保证同一会话 Turn 串行，同时在不同 Thread 间执行公平的有界并发调度。
 */
public final class TurnQueue implements AutoCloseable {
    /**
     * 未提交预留永远不得进入执行器；哨兵把内部状态破坏转换为确定性失败。
     */
    private static final Runnable UNSUBMITTED_TASK = () -> {
        throw new IllegalStateException("turn reservation was dispatched before submission");
    };
    private final Object monitor = new Object();
    private final Map<String, Lane> lanes = new HashMap<>();
    private final Map<Key, Entry> entries = new HashMap<>();
    private final ArrayDeque<Lane> readyLanes = new ArrayDeque<>();
    private final ExecutorService workers;
    private final int totalCapacity;
    private final int perThreadCapacity;
    private final int maxConcurrentThreads;
    private int admitted;
    private int running;
    private boolean accepting = true;

    /**
     * 校验领域容量后创建每任务虚拟线程执行器；真正并发仍只由 running 槽控制，不能形成第二套队列。
     */
    public TurnQueue(int totalCapacity, int perThreadCapacity, int maxConcurrentThreads) {
        validateBounds(totalCapacity, perThreadCapacity, maxConcurrentThreads);
        this.totalCapacity = totalCapacity;
        this.perThreadCapacity = perThreadCapacity;
        this.maxConcurrentThreads = maxConcurrentThreads;
        this.workers = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("ja-turn-queue-", 0).factory());
    }

    /**
     * 注入 Executor 以测试拒绝与关闭场景，同时保留与生产构造器相同的容量约束。
     */
    TurnQueue(int totalCapacity, int perThreadCapacity, int maxConcurrentThreads, ExecutorService workers) {
        validateBounds(totalCapacity, perThreadCapacity, maxConcurrentThreads);
        this.totalCapacity = totalCapacity;
        this.perThreadCapacity = perThreadCapacity;
        this.maxConcurrentThreads = maxConcurrentThreads;
        this.workers = Objects.requireNonNull(workers, "workers");
    }

    /**
     * 原子占用全局与 lane 容量但不立即调度，为调用方完成持久化后再提交任务留出边界。
     */
    public Reservation reserve(String threadId, String turnId) {
        String checkedThread = id(threadId, "threadId", "thr_");
        String checkedTurn = id(turnId, "turnId", "turn_");
        Key key = new Key(checkedThread, checkedTurn);
        synchronized (monitor) {
            ensureAccepting();
            if (admitted >= totalCapacity) {
                throw new RejectedExecutionException("TURN_QUEUE_FULL");
            }
            Lane lane = lanes.computeIfAbsent(checkedThread, Lane::new);
            if (lane.size() >= perThreadCapacity) {
                if (lane.size() == 0) lanes.remove(checkedThread, lane);
                throw new RejectedExecutionException("THREAD_QUEUE_FULL");
            }
            if (entries.containsKey(key)) {
                throw new IllegalArgumentException("turn identity is already admitted");
            }
            Entry entry = new Entry(key, lane);
            lane.pending.addLast(entry);
            entries.put(key, entry);
            admitted++;
            return new Reservation(entry);
        }
    }

    /**
     * 以 reserve/submit 两阶段接纳便捷提交；提交失败会回收预留容量并保持完成信号一致。
     */
    public CompletionStage<Void> submit(String threadId, String turnId, Runnable task) {
        Objects.requireNonNull(task, "task");
        Reservation reservation = reserve(threadId, turnId);
        try {
            reservation.submit(task);
            return reservation.completion();
        } catch (RuntimeException failure) {
            reservation.fail(failure);
            throw failure;
        }
    }

    /**
     * 关闭新准入但保留已接纳任务，使优雅关闭可以先等待队列自然静默。
     */
    public void stopAccepting() {
        synchronized (monitor) {
            accepting = false;
            monitor.notifyAll();
        }
    }

    /**
     * 仅取消尚未启动的指定 Turn，并在移除 lane head 后重新计算可调度队首。
     */
    public boolean cancelQueued(String threadId, String turnId, String reason) {
        Key key = new Key(id(threadId, "threadId", "thr_"), id(turnId, "turnId", "turn_"));
        Entry entry;
        ArrayDeque<DispatchFailure> dispatchFailures;
        synchronized (monitor) {
            entry = entries.get(key);
            if (entry == null || entry.started.get()) return false;
            Lane lane = entry.lane;
            boolean removedHead = lane.pending.peekFirst() == entry;
            if (removedHead) removeReady(lane);
            if (!lane.pending.remove(entry)) {
                if (removedHead) enqueueReady(lane);
                return false;
            }
            entries.remove(key, entry);
            admitted--;
            if (lane.pending.isEmpty() && !lane.running) lanes.remove(key.threadId, lane);
            else if (removedHead) enqueueReady(lane);
            dispatchFailures = scheduleHeads();
            monitor.notifyAll();
        }
        // CompletableFuture 回调属于调用方代码，绝不能在持有队列监视器时执行。
        entry.cancel(new CancellationException(reason == null ? "turn cancelled" : reason));
        publishDispatchFailures(dispatchFailures);
        return true;
    }

    /**
     * 在监视器下读取已接纳总数，包含排队和运行中的 Turn。
     */
    public int admittedCount() {
        synchronized (monitor) {
            return admitted;
        }
    }

    /**
     * 在监视器下读取当前占用跨 Thread 并发槽的 Turn 数。
     */
    public int runningCount() {
        synchronized (monitor) {
            return running;
        }
    }

    /**
     * 使用单调时钟有界等待所有已接纳 Turn 离队，中断时恢复中断位并报告未静默。
     */
    public boolean awaitQuiescence(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (monitor) {
            long remaining;
            while (admitted > 0 && (remaining = deadline - System.nanoTime()) > 0) {
                try {
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return admitted == 0;
        }
    }

    /**
     * 停止准入并取消所有未启动项，运行项继续收口；完成 Future 必须在监视器外兑现。
     */
    @Override
    public void close() {
        stopAccepting();
        ArrayDeque<Entry> cancelled = new ArrayDeque<>();
        synchronized (monitor) {
            for (Lane lane : lanes.values()) {
                removeReady(lane);
                for (Entry entry : lane.pending.toArray(Entry[]::new)) {
                    if (!entry.started.get()) {
                        lane.pending.remove(entry);
                        entries.remove(entry.key, entry);
                        admitted--;
                        cancelled.addLast(entry);
                    }
                }
            }
            lanes.entrySet().removeIf(entry -> entry.getValue().pending.isEmpty() && !entry.getValue().running);
            monitor.notifyAll();
        }
        // 在监视器外关闭依赖 Future，防止用户 Continuation 阻塞队列清理。
        cancelled.forEach(entry -> entry.cancel(new CancellationException("queue closed")));
        workers.shutdown();
    }

    /**
     * 轮转 ready lane 并只启动每个 lane 的队首，直到填满跨 Thread 并发槽。
     */
    private ArrayDeque<DispatchFailure> scheduleHeads() {
        ArrayDeque<DispatchFailure> dispatchFailures = null;
        while (running < maxConcurrentThreads && !readyLanes.isEmpty()) {
            Lane lane = readyLanes.removeFirst();
            lane.readyQueued = false;
            Entry candidate = lane.pending.peekFirst();
            if (lane.running || candidate == null || !candidate.ready.get()
                || lanes.get(lane.threadId) != lane) continue;
            candidate.lane.running = true;
            candidate.started.set(true);
            running++;
            try {
                workers.execute(() -> execute(candidate));
            } catch (RejectedExecutionException rejected) {
                candidate.lane.running = false;
                candidate.started.set(false);
                running--;
                removeAfterFailure(candidate);
                if (dispatchFailures == null) dispatchFailures = new ArrayDeque<>();
                dispatchFailures.addLast(new DispatchFailure(candidate, rejected));
            }
        }
        return dispatchFailures;
    }

    /**
     * 在工作线程执行一个队首，并在释放 lane 后调度其它 lane；用户完成回调始终在锁外触发。
     */
    private void execute(Entry entry) {
        Throwable outcome = null;
        try {
            if (!entry.cancelled.get()) entry.task.run();
        } catch (Throwable failure) {
            outcome = failure;
        }
        ArrayDeque<DispatchFailure> dispatchFailures;
        synchronized (monitor) {
            removeAfterRun(entry);
            monitor.notifyAll();
            dispatchFailures = scheduleHeads();
        }
        entry.complete(outcome);
        publishDispatchFailures(dispatchFailures);
    }

    /**
     * 从所有索引一致移除已运行项并释放并发槽，再将同 lane 下一项放回公平队列。
     */
    private void removeAfterRun(Entry entry) {
        if (entries.remove(entry.key, entry)) {
            entry.lane.pending.remove(entry);
            entry.lane.running = false;
            admitted--;
            running--;
            if (entry.lane.pending.isEmpty()) lanes.remove(entry.key.threadId, entry.lane);
            else enqueueReady(entry.lane);
        }
    }

    /**
     * Executor 拒绝调度时回滚准入与 lane 状态，但不重复释放尚未占用的运行槽。
     */
    private void removeAfterFailure(Entry entry) {
        if (entries.remove(entry.key, entry)) {
            entry.lane.pending.remove(entry);
            admitted--;
            if (entry.lane.pending.isEmpty()) lanes.remove(entry.key.threadId, entry.lane);
            else enqueueReady(entry.lane);
        }
    }

    /**
     * 仅把拥有就绪队首且未运行的活跃 lane 入队一次，防止同 lane 并发或重复轮转。
     */
    private void enqueueReady(Lane lane) {
        Entry head = lane.pending.peekFirst();
        if (!lane.running && !lane.readyQueued && head != null && head.ready.get()
            && lanes.get(lane.threadId) == lane) {
            lane.readyQueued = true;
            readyLanes.addLast(lane);
        }
    }

    /**
     * 在 lane head 变化或关闭时撤销公平队列标记，保持队列与标志一致。
     */
    private void removeReady(Lane lane) {
        if (!lane.readyQueued) return;
        readyLanes.removeFirstOccurrence(lane);
        lane.readyQueued = false;
    }

    /**
     * 在监视器外兑现 Executor 拒绝结果，避免调用方 Continuation 重入队列锁。
     */
    private static void publishDispatchFailures(ArrayDeque<DispatchFailure> dispatchFailures) {
        if (dispatchFailures == null) return;
        dispatchFailures.forEach(failure -> failure.entry.fail(failure.cause));
    }

    /**
     * 约束容量为正且层级上限不超过全局容量，避免无法满足的调度配置。
     */
    private static void validateBounds(int totalCapacity, int perThreadCapacity, int maxConcurrentThreads) {
        if (totalCapacity < 1 || totalCapacity > 65_536
            || perThreadCapacity < 1 || perThreadCapacity > totalCapacity
            || maxConcurrentThreads < 1 || maxConcurrentThreads > totalCapacity) {
            throw new IllegalArgumentException("queue bounds are outside the supported range");
        }
    }

    /**
     * 在预留容量前拒绝关闭中的队列，防止 stopAccepting 后产生新 Entry。
     */
    private void ensureAccepting() {
        if (!accepting) throw new RejectedExecutionException("SHUTTING_DOWN");
    }

    /**
     * 校验内部 Thread/Turn ID 的前缀、长度与安全字符集，避免模糊键进入索引。
     */
    private static String id(String value, String field, String prefix) {
        if (value == null || !value.startsWith(prefix) || value.length() > 108
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 以 Thread 与 Turn 组合键保证同一 Turn 在队列中只能出现一次。
     */
    private record Key(String threadId, String turnId) {
    }

    /**
     * 暂存必须在监视器外发布的调度拒绝及其 Entry。
     */
    private record DispatchFailure(Entry entry, RejectedExecutionException cause) {
    }

    /**
     * 保存单个 Thread 的 FIFO 队列和调度标志，是 Thread 内串行性的权威状态。
     */
    private static final class Lane {
        private final String threadId;
        private final ArrayDeque<Entry> pending = new ArrayDeque<>();
        private boolean running;
        private boolean readyQueued;

        /**
         * 将 lane 永久绑定到一个 Thread ID，避免索引复用时改变串行域。
         */
        private Lane(String threadId) {
            this.threadId = threadId;
        }

        /**
         * 返回该 Thread 已接纳的排队及运行 Entry 数，用于单 lane 容量判断。
         */
        private int size() {
            return pending.size();
        }
    }

    /**
     * 表示一个两阶段接纳的 Turn，并以原子标志协调提交、启动与取消竞争。
     */
    private static final class Entry {
        private final Key key;
        private final Lane lane;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean ready = new AtomicBoolean();
        private Runnable task = UNSUBMITTED_TASK;

        /**
         * 固定组合键及所属 lane，Entry 只允许在该串行域中排队和完成。
         */
        private Entry(Key key, Lane lane) {
            this.key = key;
            this.lane = lane;
        }

        /**
         * 以给定原因兑现尚未执行的失败，重复兑现由 CompletableFuture 幂等吸收。
         */
        private void fail(Throwable failure) {
            completion.completeExceptionally(failure);
        }

        /**
         * 将任务正常返回或抛出的失败映射到唯一完成信号。
         */
        private void complete(Throwable failure) {
            if (failure == null) completion.complete(null);
            else completion.completeExceptionally(failure);
        }

        /**
         * 标记取消并以异常完成，工作线程据此跳过尚未开始的任务。
         */
        private void cancel(Throwable failure) {
            cancelled.set(true);
            completion.completeExceptionally(failure);
        }
    }

    /**
     * 暴露一次性两阶段准入句柄，使调用方可在任务可执行前完成其它原子准备。
     */
    public final class Reservation {
        private final Entry entry;
        private final AtomicBoolean submitted = new AtomicBoolean();

        /**
         * 绑定唯一 Entry；句柄不复制队列状态，所有竞争仍由外层监视器裁决。
         */
        private Reservation(Entry entry) {
            this.entry = entry;
        }

        /**
         * 一次性绑定任务并将 lane head 标为就绪，随后触发跨 lane 公平调度。
         */
        public void submit(Runnable task) {
            Objects.requireNonNull(task, "task");
            if (!submitted.compareAndSet(false, true)) throw new IllegalStateException("reservation already submitted");
            ArrayDeque<DispatchFailure> dispatchFailures;
            synchronized (monitor) {
                if (!entries.containsKey(entry.key)) throw new RejectedExecutionException("reservation is closed");
                entry.task = task;
                entry.ready.set(true);
                enqueueReady(entry.lane);
                dispatchFailures = scheduleHeads();
            }
            publishDispatchFailures(dispatchFailures);
        }

        /**
         * 在任务启动前撤销预留并释放容量；已启动 Entry 的结果必须由工作线程决定。
         */
        public void fail(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            boolean removed = false;
            ArrayDeque<DispatchFailure> dispatchFailures = null;
            synchronized (monitor) {
                if (!entry.started.get() && entries.remove(entry.key, entry)) {
                    boolean removedHead = entry.lane.pending.peekFirst() == entry;
                    if (removedHead) removeReady(entry.lane);
                    entry.lane.pending.remove(entry);
                    admitted--;
                    if (entry.lane.pending.isEmpty() && !entry.lane.running)
                        lanes.remove(entry.key.threadId, entry.lane);
                    else if (removedHead) enqueueReady(entry.lane);
                    removed = true;
                    monitor.notifyAll();
                    dispatchFailures = scheduleHeads();
                }
            }
            // 准入失败可能触发调用方 Continuation，因此只能在释放监视器后发布。
            if (removed) entry.fail(failure);
            publishDispatchFailures(dispatchFailures);
        }

        /**
         * 返回 Entry 的唯一完成信号，覆盖执行成功、执行失败、撤销与关闭取消。
         */
        public CompletionStage<Void> completion() {
            return entry.completion;
        }
    }
}
