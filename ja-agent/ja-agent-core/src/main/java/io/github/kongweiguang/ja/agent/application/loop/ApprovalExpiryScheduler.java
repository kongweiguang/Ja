// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * 只拥有审批到期回调的有界登记与线程生命周期，等待裁决及持久事务仍由执行服务负责。
 */
final class ApprovalExpiryScheduler implements AutoCloseable {
    private final Object lock = new Object();
    private final Map<WaitKey, ScheduledFuture<?>> tasks = new HashMap<>();
    private final ScheduledThreadPoolExecutor scheduler;
    private final Clock clock;
    private final long closeWaitMillis;
    private final int capacity;
    private final BiConsumer<String, Throwable> diagnostics;
    private volatile boolean closed;

    /**
     * 容量保护定时资源，关闭期限沿用执行租约；超容量仍由回答或恢复入口结算持久等待。
     */
    ApprovalExpiryScheduler(Clock clock, Duration leaseDuration, int capacity,
                            BiConsumer<String, Throwable> diagnostics) {
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isNegative() || leaseDuration.isZero() || capacity < 1) {
            throw new IllegalArgumentException("invalid approval expiry scheduler limits");
        }
        this.capacity = capacity;
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        closeWaitMillis = leaseDuration.compareTo(Duration.ofSeconds(30)) >= 0
                ? 30_000 : Math.max(1_000, leaseDuration.toMillis());
        scheduler = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon().name("ja-agent-wait-expiry-", 0).factory());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    /**
     * 正延迟向上取整到毫秒，确保回调不会因截断提前结束；极端时间按过去或未来饱和处理。
     */
    static long delayUntil(Instant deadline, Instant now) {
        try {
            Duration remaining = Duration.between(now, deadline);
            if (!remaining.isPositive()) {
                return 0;
            }
            long millis = remaining.toMillis();
            return remaining.getNano() % 1_000_000 == 0 ? millis : Math.addExact(millis, 1);
        } catch (ArithmeticException overflow) {
            return deadline.isAfter(now) ? Long.MAX_VALUE : 0;
        }
    }

    /**
     * 同一个等待版本只登记一次；回调只结算 Store，不允许在定时线程重放外部工具。
     */
    void schedule(AgentIdentity identity, String interactionId, long revision, Instant expiresAt, Runnable settlement) {
        WaitKey key = new WaitKey(identity, interactionId, revision);
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(settlement, "settlement");
        synchronized (lock) {
            if (closed || tasks.containsKey(key)) {
                return;
            }
            if (tasks.size() >= capacity) {
                diagnostics.accept("approval expiry registry is full; control/resume will still settle the durable wait",
                        new IllegalStateException("tracked approval limit reached"));
                return;
            }
            tasks.put(key, scheduler.schedule(() -> run(key, settlement),
                    delayUntil(expiresAt, clock.instant()), TimeUnit.MILLISECONDS));
        }
    }

    /**
     * 存储瞬断只重试原等待版本；关闭、损坏事实及业务冲突不得变成无限后台重试。
     */
    private void run(WaitKey key, Runnable settlement) {
        boolean retry = false;
        try {
            if (!closed) {
                settlement.run();
            }
        } catch (AgentStore.StoreUnavailableException |
                 AgentLoopPersistence.UncertainPersistenceException unavailable) {
            retry = !closed;
            diagnostics.accept("approval expiry could not reach the Store; retrying the same wait revision", unavailable);
        } catch (RuntimeException failure) {
            diagnostics.accept("approval expiry settlement failed", failure);
        } finally {
            synchronized (lock) {
                tasks.remove(key);
                if (retry && !closed) {
                    tasks.put(key, scheduler.schedule(() -> run(key, settlement), 1, TimeUnit.SECONDS));
                }
            }
        }
    }

    /**
     * 取消未执行回调并等待已进入的结算退出，保证关闭返回后没有该 owner 的后台 Store 写入。
     */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            tasks.values().forEach(task -> task.cancel(true));
            tasks.clear();
        }
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(closeWaitMillis, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("approval expiry worker did not stop");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while stopping approval expiry worker", interrupted);
        }
    }

    /**
     * 等待身份包含租户执行与版本，旧登记不能关闭另一会话或后继等待。
     *
     * @param identity 包含租户、对话、执行与用户的完整身份
     * @param interactionId 交互标识
     * @param revision 当前修订版本
     */
    private record WaitKey(AgentIdentity identity,
                           String interactionId,
                           long revision) {
        /**
         * 不完整等待键不能成为持久 CAS 的定时入口。
         */
        private WaitKey {
            Objects.requireNonNull(identity, "identity");
            if (interactionId == null || interactionId.isBlank() || revision < 0) {
                throw new IllegalArgumentException("invalid wait expiry key");
            }
        }
    }
}
