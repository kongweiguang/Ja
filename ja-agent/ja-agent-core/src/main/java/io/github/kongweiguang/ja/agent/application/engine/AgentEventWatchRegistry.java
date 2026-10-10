// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.engine;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentEventWatch;
import io.github.kongweiguang.ja.agent.application.gateway.AgentEvent;
import io.github.kongweiguang.ja.agent.application.gateway.AgentEventListener;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Store 持久事件日志的有界内存唤醒注册表。
 */
public final class AgentEventWatchRegistry implements AgentEventListener, AutoCloseable {
    private final AgentStore store;
    private final int maximumWatches;
    private final Set<Watch> watches = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 只缓存通知，事件正文和顺序仍仅由 Store 决定，并用调用方容量限制等待者数量。
     */
    public AgentEventWatchRegistry(AgentStore store, int maximumWatches) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        if (maximumWatches < 1) {
            throw new IllegalArgumentException("maximumWatches must be positive");
        }
        this.maximumWatches = maximumWatches;
    }

    /**
     * 饱和处理超长时长，避免调用方的大超时溢出单调等待期限。
     */
    private static long timeoutNanos(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * 使用固定监听器身份，防止多个 Engine 悄悄争用同一唤醒职责。
     */
    @Override
    public String id() {
        return "ja.agent.engine.event-watch";
    }

    /**
     * 先登记再读取水位，允许首个零游标订阅发生在持久准入之前；只有该场景可暂缺会话，正游标仍须由 Store 校验。
     * Watch 同时由返回句柄和注册表持有，二者任一关闭都能唤醒等待者；Store 抛出异常或 Error 时原样传播并释放注册槽。
     *
     * @author kongweiguang
     */
    @SuppressWarnings("PMD.CloseResource")
    public AgentEventWatch watch(AgentIdentity identity, long afterSequence) {
        java.util.Objects.requireNonNull(identity, "identity");
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must be non-negative");
        }
        Watch watch = new Watch(identity, afterSequence);
        synchronized (this) {
            if (closed.get()) {
                throw new IllegalStateException("event watch registry is closed");
            }
            if (watches.size() >= maximumWatches) {
                throw new IllegalStateException("event watch capacity is exhausted");
            }
            watches.add(watch);
        }
        try {
            AgentStore.ConversationKey key = new AgentStore.ConversationKey(
                    identity.tenantId(), identity.threadId(), identity.userId());
            java.util.Optional<AgentStore.ConversationSnapshot> snapshot = store.loadConversation(key);
            if (snapshot.isEmpty()) {
                if (afterSequence > 0) {
                    throw new IllegalArgumentException("conversation does not exist");
                }
            } else {
                long eventSequence = snapshot.orElseThrow().eventSequence();
                if (afterSequence > eventSequence) {
                    throw new IllegalArgumentException("afterSequence exceeds the conversation event watermark");
                }
                watch.signal(eventSequence);
            }
            if (closed.get()) {
                watch.close();
            }
            return watch;
        } catch (RuntimeException | Error failure) {
            watch.close();
            throw failure;
        }
    }

    /**
     * 仅在提交后发布水位；Watch 归注册表持有，直到订阅者关闭或注册表关闭。
     */
    @SuppressWarnings("PMD.CloseResource")
    @Override
    public void onEvent(AgentEvent event) {
        java.util.Objects.requireNonNull(event, "event");
        if (closed.get()) {
            return;
        }
        AgentStore.PersistedEvent persisted = event.persistedEvent();
        for (Watch watch : watches) {
            if (watch.identity.tenantId().equals(event.identity().tenantId())
                && watch.identity.threadId().equals(event.identity().threadId())) {
                watch.signal(persisted.sequence());
            }
        }
    }

    /**
     * 关闭全部 Watch，避免关闭流程遗留阻塞中的消费者。
     */
    @Override
    public void close() {
        Set<Watch> closing;
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            closing = Set.copyOf(watches);
        }
        closing.forEach(Watch::close);
    }

    /**
     * 每个订阅单独使用监视器，避免模型提交通知争用全局锁。
     */
    private final class Watch implements AgentEventWatch {
        private final AgentIdentity identity;
        private final Object monitor = new Object();
        private long watermark;
        private boolean watchClosed;

        /**
         * 将订阅绑定到稳定游标，读取 Store 时不持有注册表全局锁。
         */
        private Watch(AgentIdentity identity, long afterSequence) {
            this.identity = identity;
            this.watermark = afterSequence;
        }

        /**
         * 使用单调时钟计算等待期限，避免系统时钟调整延长客户端等待。
         */
        @Override
        public OptionalLong awaitNext(long afterSequence, Duration timeout) throws InterruptedException {
            if (afterSequence < 0) {
                throw new IllegalArgumentException("afterSequence must be non-negative");
            }
            java.util.Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must not be negative");
            }
            long timeoutNanos = timeoutNanos(timeout);
            long startedAt = System.nanoTime();
            synchronized (monitor) {
                while (!watchClosed && watermark <= afterSequence) {
                    long remaining = timeoutNanos - (System.nanoTime() - startedAt);
                    if (remaining <= 0) {
                        return OptionalLong.empty();
                    }
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                }
                if (watchClosed) {
                    throw new CancellationException("event watch is closed");
                }
                return OptionalLong.of(watermark);
            }
        }

        /**
         * 与阻塞等待者共用同一监视器读取关闭状态。
         */
        @Override
        public boolean isClosed() {
            synchronized (monitor) {
                return watchClosed;
            }
        }

        /**
         * 先移除 Watch 再唤醒等待者，避免后续提交继续持有已关闭订阅。
         */
        @Override
        public void close() {
            synchronized (monitor) {
                if (watchClosed) {
                    return;
                }
                watchClosed = true;
                monitor.notifyAll();
            }
            watches.remove(this);
        }

        /**
         * 合并唤醒到最新提交水位，且不在此处调用订阅者代码。
         */
        private void signal(long sequence) {
            synchronized (monitor) {
                if (!watchClosed && sequence > watermark) {
                    watermark = sequence;
                    monitor.notifyAll();
                }
            }
        }
    }
}
