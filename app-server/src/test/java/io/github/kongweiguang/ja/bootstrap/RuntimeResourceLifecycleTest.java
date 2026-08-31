// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import io.github.kongweiguang.ja.foundation.concurrent.ShutdownDeadline;

import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 独立于 Solon 启动验证生命周期屏障及其安全失败边界。 */
final class RuntimeResourceLifecycleTest {
    /**
     * 成功的 Turn 屏障必须先于所有依赖关闭，剩余所有者仍须严格按照原构造顺序逆序释放。
     */
    @Test
    void successfulFenceRunsBeforeStrictReverseDependencyClose() {
        RuntimeResourceLifecycle lifecycle = new RuntimeResourceLifecycle();
        List<String> closed = new ArrayList<>();
        lifecycle.own(() -> closed.add("database"));
        lifecycle.own(() -> closed.add("store"));
        lifecycle.ownShutdownFence(() -> closed.add("turn-service"));
        lifecycle.own(() -> closed.add("late-resource"));

        lifecycle.close();

        assertEquals(List.of("turn-service", "late-resource", "store", "database"), closed);
    }

    /** 生命周期必须转发同一个绝对时间预算，而不是调用会重新计时的默认关闭方法。 */
    @Test
    void forwardsOneAbsoluteDeadlineToAwareResources() {
        RuntimeResourceLifecycle lifecycle = new RuntimeResourceLifecycle();
        AtomicReference<Long> observed = new AtomicReference<>();
        DeadlineCloseable aware = new DeadlineCloseable() {
            /** 记录组合根的 Deadline，不留下嵌套超时接缝。 */
            @Override public void closeAt(long shutdownDeadlineNanos) {
                observed.set(shutdownDeadlineNanos);
            }

            /** 检测关闭路径是否回退为拥有独立预算的实现。 */
            @Override public void close() {
                throw new AssertionError("default close must not be used");
            }
        };
        lifecycle.own(aware);
        ShutdownDeadline deadline = ShutdownDeadline.start(Duration.ofSeconds(2));

        lifecycle.close(deadline);

        assertEquals(deadline.deadlineNanos(), observed.get());
    }

    /**
     * Deadline 或静默屏障失败时必须要求强制终止，绝不能关闭仍可能被未完成工作线程使用的依赖；
     * 第二次关闭也必须保持同一失败结论。
     */
    @Test
    void failedFenceLeavesDependenciesOpenAndCannotLookCleanLater() {
        RuntimeResourceLifecycle lifecycle = new RuntimeResourceLifecycle();
        List<String> closed = new ArrayList<>();
        lifecycle.own(() -> closed.add("database"));
        lifecycle.own(() -> closed.add("store"));
        lifecycle.ownShutdownFence(() -> {
            closed.add("turn-service");
            throw new IllegalStateException("quiescence deadline exceeded");
        });

        ShutdownDeadline.ForcedTerminationException failure = assertThrows(
                ShutdownDeadline.ForcedTerminationException.class, lifecycle::close);
        assertTrue(failure.getMessage().contains("forced termination"));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals(List.of("turn-service"), closed);
        assertThrows(ShutdownDeadline.ForcedTerminationException.class, lifecycle::close);
        assertEquals(List.of("turn-service"), closed);
    }

    /** 屏障成功后若资源关闭超时，不得继续启动更早创建的依赖关闭。 */
    @Test
    void forcedResourceTimeoutStopsBeforePersistenceDependencies() throws Exception {
        RuntimeResourceLifecycle lifecycle = new RuntimeResourceLifecycle();
        List<String> closed = new CopyOnWriteArrayList<>();
        CountDownLatch resourceEntered = new CountDownLatch(1);
        CountDownLatch releaseResource = new CountDownLatch(1);
        CountDownLatch resourceExited = new CountDownLatch(1);
        lifecycle.own(() -> closed.add("database"));
        lifecycle.own(() -> closed.add("store"));
        lifecycle.own(() -> {
            closed.add("provider");
            resourceEntered.countDown();
            boolean interrupted = false;
            while (releaseResource.getCount() > 0) {
                try {
                    releaseResource.await();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            resourceExited.countDown();
        });
        lifecycle.ownShutdownFence(() -> closed.add("turn-service"));

        try {
            assertThrows(ShutdownDeadline.ForcedTerminationException.class,
                    () -> lifecycle.close(ShutdownDeadline.start(Duration.ofMillis(250))));
            assertTrue(resourceEntered.await(1, TimeUnit.SECONDS));
            assertEquals(List.of("turn-service", "provider"), closed);
            assertThrows(ShutdownDeadline.ForcedTerminationException.class, lifecycle::close);
            assertEquals(List.of("turn-service", "provider"), closed);
        } finally {
            releaseResource.countDown();
            assertTrue(resourceExited.await(1, TimeUnit.SECONDS));
        }
    }

    /** 部分构造尚无关闭屏障，因此仍应保持严格逆序清理行为。 */
    @Test
    void partialInitializationWithoutFenceStillClosesInReverse() {
        RuntimeResourceLifecycle lifecycle = new RuntimeResourceLifecycle();
        List<String> closed = new ArrayList<>();
        lifecycle.own(() -> closed.add("database"));
        lifecycle.own(() -> closed.add("provider"));

        lifecycle.close();

        assertEquals(List.of("provider", "database"), closed);
    }

    /** 并发关闭调用方共享一次屏障执行和一次逆序依赖关闭结果。 */
    @Test
    void concurrentCloseWaitsForOneSharedCompletion() throws Exception {
        RuntimeResourceLifecycle lifecycle = new RuntimeResourceLifecycle();
        List<String> closed = new ArrayList<>();
        CountDownLatch fenceEntered = new CountDownLatch(1);
        CountDownLatch releaseFence = new CountDownLatch(1);
        lifecycle.own(() -> closed.add("database"));
        lifecycle.ownShutdownFence(() -> {
            closed.add("turn-service");
            fenceEntered.countDown();
            releaseFence.await();
        });

        CompletableFuture<Void> first = CompletableFuture.runAsync(
                () -> lifecycle.close(ShutdownDeadline.start(Duration.ofSeconds(2))));
        assertTrue(fenceEntered.await(1, TimeUnit.SECONDS));
        CompletableFuture<Void> second = CompletableFuture.runAsync(
                () -> lifecycle.close(ShutdownDeadline.start(Duration.ofSeconds(2))));
        releaseFence.countDown();
        first.get(1, TimeUnit.SECONDS);
        second.get(1, TimeUnit.SECONDS);
        assertEquals(List.of("turn-service", "database"), closed);
    }

    /**
     * 关闭已接纳后到达的强制终止升级必须赢得完成竞争，并阻止 Provider、Store 和数据库所有者
     * 在进行中的静默屏障之后开始关闭。
     */
    @Test
    void forcedUpgradeDuringCloseStopsLaterDependencyClose() throws Exception {
        RuntimeResourceLifecycle lifecycle = new RuntimeResourceLifecycle();
        List<String> closed = new CopyOnWriteArrayList<>();
        CountDownLatch fenceEntered = new CountDownLatch(1);
        CountDownLatch releaseFence = new CountDownLatch(1);
        lifecycle.own(() -> closed.add("database"));
        lifecycle.own(() -> closed.add("store"));
        lifecycle.own(() -> closed.add("provider"));
        lifecycle.ownShutdownFence(() -> {
            closed.add("turn-service");
            fenceEntered.countDown();
            releaseFence.await();
        });

        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        CountDownLatch closeDone = new CountDownLatch(1);
        Thread closeThread = Thread.ofVirtual().start(() -> {
            try {
                lifecycle.close(ShutdownDeadline.start(Duration.ofSeconds(2)));
            } catch (Throwable failure) {
                closeFailure.set(failure);
            } finally {
                closeDone.countDown();
            }
        });
        try {
            assertTrue(fenceEntered.await(1, TimeUnit.SECONDS));
            lifecycle.requireForcedTermination(new IllegalStateException("ingress barrier failed"));
            assertFalse(closeDone.await(100, TimeUnit.MILLISECONDS));
            assertEquals(List.of("turn-service"), closed);
        } finally {
            releaseFence.countDown();
            assertTrue(closeDone.await(1, TimeUnit.SECONDS));
            closeThread.join(1_000);
        }
        assertInstanceOf(ShutdownDeadline.ForcedTerminationException.class, closeFailure.get());
        assertEquals(List.of("turn-service"), closed);
    }
}
