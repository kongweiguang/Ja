// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.concurrent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定双门虚拟执行器的容量、取消和关闭不变量。
 */
final class BoundedVirtualExecutorTest {
    /**
     * active permit 必须限制真正进入用户代码的数量，而 admitted 仍能容纳等待任务。
     */
    @Test
    void boundsActiveAndAdmittedVirtualTasks() throws Exception {
        BoundedVirtualExecutor executor = new BoundedVirtualExecutor("test-bounded-", 2, 4);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            futures.add(executor.submit(() -> {
                int current = active.incrementAndGet();
                peak.accumulateAndGet(current, Math::max);
                entered.countDown();
                try {
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                    return Thread.currentThread().isVirtual();
                } finally {
                    active.decrementAndGet();
                }
            }));
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertEquals(4, executor.admittedTaskCount());
        assertEquals(2, executor.activeTaskCount());
        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
        release.countDown();
        for (Future<Boolean> future : futures) {
            assertTrue(future.get(2, TimeUnit.SECONDS));
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        assertEquals(2, peak.get());
        assertEquals(0, executor.admittedTaskCount());
        assertEquals(0, executor.activeTaskCount());
    }

    /**
     * 等待 active permit 的 Future 被取消后必须立即释放 admitted，而不是等前序任务自然结束。
     */
    @Test
    void cancellationInterruptsPermitWaiter() throws Exception {
        BoundedVirtualExecutor executor = new BoundedVirtualExecutor("test-cancel-", 1, 2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> running = executor.submit(() -> {
            entered.countDown();
            await(release);
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        AtomicBoolean secondRan = new AtomicBoolean();
        Future<?> waiting = executor.submit(() -> secondRan.set(true));
        assertTrue(waiting.cancel(true));
        awaitCount(executor, 1);
        assertFalse(secondRan.get());
        release.countDown();
        running.get(2, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        assertEquals(0, executor.admittedTaskCount());
    }

    /**
     * shutdown 只封闭准入并排空任务，不能像 shutdownNow 一样取消已经接纳的工作。
     */
    @Test
    void gracefulShutdownDrainsAdmittedTasks() throws Exception {
        BoundedVirtualExecutor executor = new BoundedVirtualExecutor("test-graceful-", 1, 2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        executor.execute(() -> {
            await(release);
            completed.incrementAndGet();
        });
        executor.execute(completed::incrementAndGet);
        executor.shutdown();
        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
        release.countDown();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        assertEquals(2, completed.get());
        assertTrue(executor.isTerminated());
    }

    /**
     * shutdownNow 必须同时打断运行者和许可等待者，并最终把两级计数归零。
     */
    @Test
    void forceShutdownInterruptsRunningAndWaitingTasks() throws Exception {
        BoundedVirtualExecutor executor = new BoundedVirtualExecutor("test-force-", 1, 3);
        CountDownLatch active = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        executor.execute(() -> {
            active.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(10));
            } catch (InterruptedException expected) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(active.await(2, TimeUnit.SECONDS));
        Future<?> waitingOne = executor.submit(() -> { });
        Future<?> waitingTwo = executor.submit(() -> { });
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        assertTrue(interrupted.get());
        assertTrue(waitingOne.isCancelled());
        assertTrue(waitingTwo.isCancelled());
        assertEquals(0, executor.admittedTaskCount());
        assertEquals(0, executor.activeTaskCount());
    }

    /**
     * 用户任务异常仍必须释放许可，使后续任务能够被正常接纳并执行。
     */
    @Test
    void failureReleasesBothPermits() throws Exception {
        BoundedVirtualExecutor executor = new BoundedVirtualExecutor("test-failure-", 1, 1);
        Future<?> failed = executor.submit(() -> {
            throw new IllegalStateException("expected");
        });
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> failed.get(2, TimeUnit.SECONDS));
        awaitCount(executor, 0);
        assertEquals(7, executor.submit(() -> 7).get(2, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    /**
     * 测试任务使用有界等待，防止失败时遗留执行器线程阻止套件结束。
     */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 等待异步 finally 更新计数，避免用固定 sleep 掩盖许可回收竞争。
     */
    private static void awaitCount(BoundedVirtualExecutor executor, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (executor.admittedTaskCount() != expected && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(expected, executor.admittedTaskCount());
    }
}
