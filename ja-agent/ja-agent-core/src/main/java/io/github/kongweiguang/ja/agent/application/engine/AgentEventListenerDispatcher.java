// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.engine;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.gateway.AgentEvent;
import io.github.kongweiguang.ja.agent.application.gateway.AgentEventListener;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 每个只读监听器拥有独立有界队列与有序 worker，慢回调不会占住 Loop 或其他监听器。
 */
public final class AgentEventListenerDispatcher implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(AgentEventListenerDispatcher.class.getName());
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(2);
    private static final AtomicLong THREAD_IDS = new AtomicLong();

    private final int queueCapacity;
    private final ConcurrentHashMap<ListenerKey, ListenerWorker> workers = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 将每个 listener 的积压上限显式固定，避免慢扩展消耗 Loop 提交线程或无界内存。
     */
    public AgentEventListenerDispatcher(int queueCapacity) {
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.queueCapacity = queueCapacity;
    }

    /**
     * 按快照注册顺序向各自队列投递，调用线程只执行有界队列的非等待 offer。
     */
    public void dispatch(List<AgentEventListener> listeners, AgentEvent event) {
        Objects.requireNonNull(listeners, "listeners");
        Objects.requireNonNull(event, "event");
        if (closed.get()) {
            return;
        }
        for (AgentEventListener listener : listeners) {
            ListenerWorker worker = worker(Objects.requireNonNull(listener, "listener"));
            worker.offer(event);
        }
    }

    /**
     * 执行结果已完成后释放该执行的游标，保留其他并发执行的重同步状态。
     */
    public void finishExecution(AgentIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        workers.values().forEach(worker -> worker.finish(identity));
    }

    /**
     * 先中断所有观察 worker，再限时等待；不配合中断的扩展会使关闭明确失败。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<ListenerWorker> active = List.copyOf(workers.values());
        active.forEach(ListenerWorker::stop);
        long deadline = System.nanoTime() + CLOSE_WAIT.toNanos();
        List<String> stuck = new ArrayList<>();
        for (ListenerWorker worker : active) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                worker.awaitTermination(remaining);
            }
            if (worker.isAlive()) {
                stuck.add(worker.label());
            }
        }
        workers.clear();
        if (!stuck.isEmpty()) {
            IllegalStateException failure = new IllegalStateException(
                    "event listeners ignored Engine shutdown interruption: " + String.join(", ", stuck));
            LOGGER.log(System.Logger.Level.ERROR, failure.getMessage());
            throw failure;
        }
    }

    /**
     * 返回按 listener 对象身份隔离的串行投递器，不执行扩展提供的 id()。
     */
    private ListenerWorker worker(AgentEventListener listener) {
        ListenerKey key = new ListenerKey(listener);
        return workers.compute(key, (ignored, current) -> {
            if (closed.get()) {
                throw new IllegalStateException("listener dispatcher is closed");
            }
            if (current != null && !current.isStopped()) {
                return current;
            }
            return new ListenerWorker(key, listener, queueCapacity);
        });
    }

    /**
     * 每个队列元素固定事件、游标对象和执行身份，避免持有已释放的 Engine Runtime。
     *
     * @param event 事件
     * @param execution 执行
     * @param cursor 游标
     */
    private record Delivery(AgentEvent event,
                            ExecutionKey execution,
                            Cursor cursor) {
    }

    /**
     * 完整身份用于隔离水位，conversation 级事件 sequence 仍可安全用于回读区间。
     *
     * @param tenantId 租户标识，用于隔离不同租户的数据与执行
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     */
    private record ExecutionKey(String tenantId,
                                String threadId,
                                String turnId) {
        /**
         * 从已校验执行身份生成临时键，不把 user 身份混入 Thread 事件游标。
         */
        private static ExecutionKey from(AgentIdentity identity) {
            return new ExecutionKey(identity.tenantId(), identity.threadId(), identity.turnId());
        }
    }

    /**
         * 只以对象身份复用 listener mailbox，业务 equals 不可将不同回调错误合并。
     *
     * @param listener 监听器
     */
        private record ListenerKey(AgentEventListener listener) {
        /**
         * mailbox 映射用 listener 对象身份，支持相同逻辑实现的不同租户实例隔离。
         */
        private ListenerKey {
        }

            /**
             * 同一 callback 对象在多个快照间共享有序队列。
             */
            @Override
            public boolean equals(Object other) {
                return other instanceof ListenerKey key && listener == key.listener;
            }

            /**
             * 对象 identity 哈希与 equals 规则一致，避免回调自定义 hashCode 执行在 Loop 线程。
             */
            @Override
            public int hashCode() {
                return System.identityHashCode(listener);
            }
        }

    /**
     * 并发 overflow 只记录首次缺口及最大水位，后续回调不会改变它的重放起点。
     */
    private static final class Cursor {
        private long lastDelivered;
        private boolean hasDelivered;
        private long replayThrough;
        private String replayReason;

        /**
         * 若前一事件通知失败，则扩大回读上界并抑制当前增量事件。
         */
        private synchronized Gap beforeDelivery(long sequence) {
            if (replayReason != null) {
                replayThrough = Math.max(replayThrough, sequence);
                return snapshot();
            }
            if (hasDelivered && sequence <= lastDelivered) {
                replayReason = "out_of_order";
                replayThrough = lastDelivered;
                return snapshot(Math.min(sequence - 1, lastDelivered));
            }
            return null;
        }

        /**
         * 保存第一个无法确认连续送达的位置；相同缺口只扩大上界。
         */
        private synchronized Gap requireReplay(long throughSequence, String reasonCode) {
            long after = hasDelivered ? lastDelivered : 0;
            if (replayReason == null) {
                replayReason = reasonCode;
                replayThrough = Math.max(throughSequence, after + 1);
            } else {
                replayThrough = Math.max(replayThrough, throughSequence);
            }
            return snapshot();
        }

        /**
         * 只有当前重放水位未继续增长时才推进 cursor，避免吞掉回调执行期间的新 overflow。
         */
        private synchronized void replayed(Gap replayed) {
            if (replayReason != null && replayThrough <= replayed.throughSequence()) {
                lastDelivered = replayed.throughSequence();
                hasDelivered = true;
                replayThrough = 0;
                replayReason = null;
            }
        }

        /**
         * 成功通知才推进游标；并发发现的缺口优先保留以便之后明确补读。
         */
        private synchronized void delivered(long sequence) {
            if (replayReason == null && (!hasDelivered || sequence > lastDelivered)) {
                lastDelivered = sequence;
                hasDelivered = true;
            } else if (replayReason != null) {
                replayThrough = Math.max(replayThrough, sequence);
            }
        }

        /**
         * 返回当前重放边界；out-of-order 会把回读起点退到较早事件之前。
         */
        private Gap snapshot() {
            return snapshot(hasDelivered ? lastDelivered : 0);
        }

        /**
         * 显式重放起点适用于晚到事件，重复读取仍由 conversation sequence 去重。
         */
        private Gap snapshot(long afterSequence) {
            long through = Math.max(replayThrough, afterSequence + 1);
            return new Gap(afterSequence, through, replayReason == null ? "event_gap" : replayReason);
        }
    }

    /**
     * 重放范围使用 Store 会话水位而非本地队列长度或 wall-clock 时间。
     *
     * @param afterSequence 从该序号之后开始读取
     * @param throughSequence 当前快照覆盖到的顺序号
     * @param reasonCode 原因代码
     */
    private record Gap(long afterSequence,
                       long throughSequence,
                       String reasonCode) {
    }

    /**
     * 一个 listener 的独立 mailbox；执行游标只存在于活跃执行期，结束后按屏障释放。
     */
    private final class ListenerWorker {
        private final ListenerKey key;
        private final AgentEventListener listener;
        private final ArrayBlockingQueue<Delivery> queue;
        private final ConcurrentHashMap<ExecutionKey, Cursor> cursors = new ConcurrentHashMap<>();
        private final java.util.Set<ExecutionKey> activeExecutions = ConcurrentHashMap.newKeySet();
        private final Object lifecycle = new Object();
        private final Thread thread;
        private boolean stopped;
        private boolean inFlight;

        /**
         * 独立虚拟 worker 负责回调；共享 Engine 生命周期，不共享任一业务事务或运行时。
         */
        private ListenerWorker(ListenerKey key, AgentEventListener listener, int capacity) {
            this.key = key;
            this.listener = listener;
            queue = new ArrayBlockingQueue<>(capacity);
            thread = Thread.ofVirtual().name("ja-agent-listener-" + THREAD_IDS.incrementAndGet()).start(this::run);
        }

        /**
         * 非等待入队并登记活跃执行；饱和时记录精确缺口，之后只由 worker 通知重同步。
         */
        private void offer(AgentEvent event) {
            ExecutionKey execution = ExecutionKey.from(event.identity());
            synchronized (lifecycle) {
                if (stopped) {
                    return;
                }
                activeExecutions.add(execution);
                Cursor cursor = cursors.computeIfAbsent(execution, ignored -> new Cursor());
                if (!queue.offer(new Delivery(event, execution, cursor))) {
                    Gap gap = cursor.requireReplay(event.persistedEvent().sequence(), "queue_full");
                    reportGap("listener mailbox is full", event, gap.afterSequence(), gap.throughSequence());
                }
            }
        }

        /**
         * 按每个 listener 的 FIFO 次序运行事件；一个异常回调仅使本执行进入持久重放流程。
         */
        private void run() {
            while (true) {
                Delivery delivery;
                try {
                    delivery = queue.take();
                } catch (InterruptedException interrupted) {
                    if (isStopped()) {
                        return;
                    }
                    continue;
                }
                synchronized (lifecycle) {
                    inFlight = true;
                }
                try {
                    deliver(delivery);
                } finally {
                    synchronized (lifecycle) {
                        inFlight = false;
                        stopIfIdle();
                    }
                }
            }
        }

        /**
         * 已知缺口先通知监听器补读，再恢复增量事件；异常日志仅保留类名，避免回调消息泄漏事件正文。
         */
        private void deliver(Delivery delivery) {
            long sequence = delivery.event.persistedEvent().sequence();
            Gap required = delivery.cursor.beforeDelivery(sequence);
            if (required != null) {
                replay(delivery, required);
                return;
            }
            try {
                listener.onEvent(delivery.event);
                delivery.cursor.delivered(sequence);
            } catch (RuntimeException | Error failure) {
                Gap gap = delivery.cursor.requireReplay(sequence, "listener_failed");
                reportGap("listener callback failed (" + failure.getClass().getName() + ")", delivery.event,
                        gap.afterSequence(), gap.throughSequence());
                replay(delivery, gap);
            }
        }

        /**
         * 在独立 worker 上执行只读恢复回调；异常日志仅保留类名，避免回调消息泄漏事件正文。
         */
        private void replay(Delivery delivery, Gap gap) {
            AgentEventListener.ReplayRequired request = new AgentEventListener.ReplayRequired(
                    delivery.event.identity(), gap.afterSequence(), gap.throughSequence(), gap.reasonCode());
            try {
                listener.onReplayRequired(request);
                delivery.cursor.replayed(gap);
            } catch (RuntimeException | Error failure) {
                Gap retained = delivery.cursor.requireReplay(gap.throughSequence(), "replay_failed");
                reportGap("listener replay handler failed (" + failure.getClass().getName() + ")", delivery.event,
                        retained.afterSequence(), retained.throughSequence());
            }
        }

        /**
         * 执行完毕后移除旧游标；已经排队的 Delivery 仍持有游标直到 FIFO 队列排空。
         */
        private void finish(AgentIdentity identity) {
            ExecutionKey execution = ExecutionKey.from(identity);
            synchronized (lifecycle) {
                activeExecutions.remove(execution);
                cursors.remove(execution);
                stopIfIdle();
            }
        }

        /**
         * 中断用户回调并显式报告丢弃的已提交事件，不将关闭解释为取消业务执行。
         */
        private void stop() {
            List<Delivery> abandoned = new ArrayList<>();
            synchronized (lifecycle) {
                if (stopped) {
                    return;
                }
                stopped = true;
                queue.drainTo(abandoned);
            }
            for (Delivery delivery : abandoned) {
                Gap gap = delivery.cursor.requireReplay(delivery.event.persistedEvent().sequence(), "dispatcher_closed");
                reportGap("listener dispatcher closed before delivery", delivery.event,
                        gap.afterSequence(), gap.throughSequence());
            }
            thread.interrupt();
        }

        /**
         * 等待关闭过程只占用共享总期限，避免 listener 数量放大 Engine 的停机时长。
         */
        private void awaitTermination(long remainingNanos) {
            try {
                long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
                int nanos = (int) (remainingNanos - TimeUnit.MILLISECONDS.toNanos(millis));
                thread.join(millis, nanos);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while closing event listener workers", interrupted);
            }
        }

        /**
         * 报告单个 listener 的重放游标；消费者需按租户、Thread 与 Turn 事件流补读到给定上界。
         */
        private void reportGap(String reason, AgentEvent event, long afterSequence, long throughSequence) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Agent event listener {0} requires Store replay tenant={1}, thread={2}, turn={3}, after={4}, through={5}: {6}",
                    label(), event.identity().tenantId(), event.identity().threadId(), event.identity().turnId(),
                    afterSequence, throughSequence, reason);
        }

        /**
         * 队列与回调都空闲后退出，回收只为已结束 execution 创建的虚拟线程。
         */
        private void stopIfIdle() {
            if (!stopped && activeExecutions.isEmpty() && queue.isEmpty() && !inFlight) {
                stopped = true;
                workers.remove(key, this);
                thread.interrupt();
            }
        }

        /**
         * 只读 worker 的关闭状态，供新的事件在映射中替换已退出执行器。
         */
        private boolean isStopped() {
            synchronized (lifecycle) {
                return stopped;
            }
        }

        /**
         * 虚拟线程可能忽略中断，调用方关闭阶段按统一期限检查其是否真正退出。
         */
        private boolean isAlive() {
            return thread.isAlive();
        }

        /**
         * 诊断只用对象身份，不调用扩展的 id()，避免非可信代码进入事件提交路径。
         */
        private String label() {
            return listener.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(listener));
        }
    }
}
