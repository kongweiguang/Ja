// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证共享 delta Scheduler 的平台线程归属、Round 隔离和关闭丢弃语义。
 */
final class DeltaTimerSchedulerTest {
    /**
     * 关闭一个 Round 句柄不得影响其它 Round，所有回调必须复用同一个平台 Owner。
     */
    @Test
    void isolatesRoundHandlesOnOnePlatformOwner() throws Exception {
        DeltaTimerScheduler scheduler = new DeltaTimerScheduler();
        StreamingDeltaBatcher.Timer cancelled = scheduler.openTimer("turn_one", 1);
        StreamingDeltaBatcher.Timer active = scheduler.openTimer("turn_two", 1);
        AtomicBoolean cancelledRan = new AtomicBoolean();
        AtomicReference<String> ownerName = new AtomicReference<>();
        AtomicBoolean virtual = new AtomicBoolean(true);
        CountDownLatch fired = new CountDownLatch(1);
        cancelled.schedule(() -> cancelledRan.set(true), Duration.ofMillis(40));
        active.schedule(() -> {
            ownerName.set(Thread.currentThread().getName());
            virtual.set(Thread.currentThread().isVirtual());
            fired.countDown();
        }, Duration.ofMillis(5));
        cancelled.close();
        assertTrue(fired.await(2, TimeUnit.SECONDS));
        Thread.sleep(60);
        active.close();
        scheduler.close();
        assertFalse(cancelledRan.get());
        assertFalse(virtual.get());
        assertEquals("ja-delta-flush0", ownerName.get());
    }

    /**
     * Scheduler 关闭后所有句柄和迟到任务都失效，不能越过 AgentLoop 终态边界。
     */
    @Test
    void closeDropsLateCallbacksAndRejectsNewHandles() throws Exception {
        DeltaTimerScheduler scheduler = new DeltaTimerScheduler();
        StreamingDeltaBatcher.Timer timer = scheduler.openTimer("turn_late", 1);
        AtomicBoolean ran = new AtomicBoolean();
        timer.schedule(() -> ran.set(true), Duration.ofMillis(40));
        scheduler.closeAt(System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
        Thread.sleep(60);
        assertFalse(ran.get());
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.RejectedExecutionException.class,
                () -> scheduler.openTimer("turn_late", 2)) != null);
    }
}
