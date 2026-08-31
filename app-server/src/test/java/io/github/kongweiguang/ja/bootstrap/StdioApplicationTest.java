// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import io.github.kongweiguang.ja.foundation.concurrent.ShutdownDeadline;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 在不停止进程级测试应用的前提下，验证有界 Solon 停止接缝。 */
final class StdioApplicationTest {
    /** 成功停止必须只执行一次，并原样保留更早的清理结果。 */
    @Test
    void successfulStopRunsOnceWithinCallerDeadline() {
        AtomicInteger invocations = new AtomicInteger();
        AtomicBoolean virtualWorker = new AtomicBoolean();
        AtomicBoolean daemonWorker = new AtomicBoolean();
        RuntimeException prior = new IllegalStateException("earlier cleanup failed");

        RuntimeException result = StdioApplication.stopSolon(true, false,
                ShutdownDeadline.start(Duration.ofSeconds(2)), prior, () -> {
                    invocations.incrementAndGet();
                    virtualWorker.set(Thread.currentThread().isVirtual());
                    daemonWorker.set(Thread.currentThread().isDaemon());
                });

        assertSame(prior, result);
        assertEquals(1, invocations.get());
        assertTrue(virtualWorker.get());
        assertTrue(daemonWorker.get());
        assertEquals(0, prior.getSuppressed().length);
    }

    /** 应用未启动或处于 AOT 时，绝不能调用注入的进程级停止动作。 */
    @Test
    void inactiveApplicationsDoNotStartStopWorker() {
        AtomicInteger invocations = new AtomicInteger();
        ShutdownDeadline deadline = ShutdownDeadline.start(Duration.ofSeconds(2));

        assertSame(null, StdioApplication.stopSolon(false, false, deadline, null,
                invocations::incrementAndGet));
        assertSame(null, StdioApplication.stopSolon(true, true, deadline, null,
                invocations::incrementAndGet));
        assertEquals(0, invocations.get());
    }

    /** 同步 Solon 失败必须原样返回，并且停止动作不得重试。 */
    @Test
    void stopFailureIsReturnedWithoutRetry() {
        AtomicInteger invocations = new AtomicInteger();
        RuntimeException expected = new IllegalStateException("Solon stop failed");

        RuntimeException result = StdioApplication.stopSolon(true, false,
                ShutdownDeadline.start(Duration.ofSeconds(2)), null, () -> {
                    invocations.incrementAndGet();
                    throw expected;
                });

        assertSame(expected, result);
        assertEquals(1, invocations.get());
    }

    /**
     * 不配合的插件不能延长共享预算；工作线程只接收一次中断，并保持守护状态交由
     * Rust Job Object 处理，而不是由 Java 关闭所有者继续 join。
     */
    @Test
    void timedOutStopReturnsForcedWithoutWaitingForUncooperativeWorker() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        long started = System.nanoTime();
        RuntimeException result;
        try {
            result = StdioApplication.stopSolon(true, false,
                    ShutdownDeadline.start(Duration.ofMillis(150)), null, () -> {
                        invocations.incrementAndGet();
                        entered.countDown();
                        boolean restoreInterrupt = false;
                        while (release.getCount() > 0) {
                            try {
                                release.await();
                            } catch (InterruptedException ignored) {
                                restoreInterrupt = true;
                                interrupted.countDown();
                            }
                        }
                        if (restoreInterrupt) Thread.currentThread().interrupt();
                    });
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertInstanceOf(ShutdownDeadline.ForcedTerminationException.class, result);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertEquals(1, invocations.get());
            assertTrue(elapsedMillis < 1_000, "Solon stop exceeded the bounded handoff window");
        } finally {
            release.countDown();
        }
    }

    /** 超时必须保持为主失败类别，同时保留更早的关闭失败证据。 */
    @Test
    void forcedStopRetainsEarlierFailureWithoutLookingClean() throws Exception {
        RuntimeException prior = new IllegalStateException("runtime close failed");
        CountDownLatch release = new CountDownLatch(1);
        RuntimeException result;
        try {
            result = StdioApplication.stopSolon(true, false,
                    ShutdownDeadline.start(Duration.ofMillis(100)), prior, () -> {
                        while (release.getCount() > 0) {
                            try {
                                release.await();
                            } catch (InterruptedException ignored) {
                                // 故意忽略中断，用于模拟卡死且不配合关闭的 Solon 插件。
                            }
                        }
                    });
        } finally {
            release.countDown();
        }

        assertInstanceOf(ShutdownDeadline.ForcedTerminationException.class, result);
        assertEquals(1, result.getSuppressed().length);
        assertSame(prior, result.getSuppressed()[0]);
        assertFalse(result.getMessage().isBlank());
    }

    /** 分离的 Solon 工作线程接收停止信号时，调用方的中断状态必须得到保留。 */
    @Test
    void interruptedCallerReturnsForcedAndPreservesInterrupt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<RuntimeException> result = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        Thread caller = Thread.ofPlatform().start(() -> {
            result.set(StdioApplication.stopSolon(true, false,
                    ShutdownDeadline.start(Duration.ofSeconds(2)), null, () -> {
                        entered.countDown();
                        boolean restoreInterrupt = false;
                        while (release.getCount() > 0) {
                            try {
                                release.await();
                            } catch (InterruptedException ignored) {
                                restoreInterrupt = true;
                                workerInterrupted.countDown();
                            }
                        }
                        if (restoreInterrupt) Thread.currentThread().interrupt();
                    }));
            interruptPreserved.set(Thread.currentThread().isInterrupted());
        });
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(1_000);
            assertFalse(caller.isAlive(), "interrupted close caller remained blocked");
            assertInstanceOf(ShutdownDeadline.ForcedTerminationException.class, result.get());
            assertTrue(interruptPreserved.get());
            assertTrue(workerInterrupted.await(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            caller.join(1_000);
        }
    }
}
