// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.cancellation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kongweiguang.ja.conversation.application.cancellation.CancellationCoordinator;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 取消协调器竞态回归集，锁定 token 发布、清理屏障、幂等完成与关闭语义。 */
final class DefaultCancellationCoordinatorTest {
    /** 锁定先取消后注册仍只调度一次回调，避免时序倒置丢失取消通知。 */
    @Test
    void cancelBeforeRegisterSchedulesCallbackExactlyOnce() throws Exception {
        DefaultCancellationCoordinator coordinator = new DefaultCancellationCoordinator();
        CancellationCoordinator.CancellationScope scope = coordinator.open("thr_1", "turn_1");
        assertEquals(CancellationCoordinator.CancelOutcome.REQUESTED,
                coordinator.cancel("thr_1", "turn_1", "cancel first").toCompletableFuture().join());
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch callbackFinished = new CountDownLatch(1);
        scope.onCancellation(() -> {
            calls.incrementAndGet();
            callbackFinished.countDown();
        });
        assertTrue(callbackFinished.await(5, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
        assertTrue(scope.isCancellationRequested());
        assertEquals("cancel first", scope.reason().orElseThrow());
        coordinator.complete("thr_1", "turn_1");
        coordinator.close();
    }

    /** 锁定注册与取消并发窗口，确保回调既不遗漏也不重复。 */
    @Test
    void registerDuringCancelClosesTheRace() throws Exception {
        DefaultCancellationCoordinator coordinator = new DefaultCancellationCoordinator();
        CancellationCoordinator.CancellationScope scope = coordinator.open("thr_race", "turn_race");
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger racingCalls = new AtomicInteger();
        scope.onCancellation(() -> {
            firstCalls.incrementAndGet();
            firstEntered.countDown();
            await(releaseFirst);
        });
        CompletableFuture<CancellationCoordinator.CancelOutcome> cancellation = CompletableFuture.supplyAsync(
                () -> coordinator.cancel("thr_race", "turn_race", "race").toCompletableFuture().join());
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
        CountDownLatch racingEntered = new CountDownLatch(1);
        scope.onCancellation(() -> {
            racingCalls.incrementAndGet();
            racingEntered.countDown();
        });
        assertTrue(racingEntered.await(5, TimeUnit.SECONDS));
        assertFalse(cancellation.isDone());
        releaseFirst.countDown();
        assertEquals(CancellationCoordinator.CancelOutcome.REQUESTED, cancellation.join());
        assertEquals(1, firstCalls.get());
        assertEquals(1, racingCalls.get());
        coordinator.complete("thr_race", "turn_race");
        coordinator.close();
    }

    /** 锁定执行器受阻时先发布取消 token，释放后清理仍仅执行一次。 */
    @Test
    void blockedExecutorPublishesTokenBeforeCleanupAndRunsOnceAfterRelease() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch blockerEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        executor.submit(() -> {
            blockerEntered.countDown();
            await(releaseBlocker);
        });
        assertTrue(blockerEntered.await(5, TimeUnit.SECONDS));

        DefaultCancellationCoordinator coordinator = new DefaultCancellationCoordinator(executor);
        CancellationCoordinator.CancellationScope scope = coordinator.open("thr_blocked", "turn_blocked");
        AtomicInteger calls = new AtomicInteger();
        scope.onCancellation(calls::incrementAndGet);

        CompletableFuture<CancellationCoordinator.CancelOutcome> cancellation = coordinator.cancel(
                "thr_blocked", "turn_blocked", "blocked").toCompletableFuture();
        assertTrue(scope.isCancellationRequested());
        assertEquals(0, calls.get());
        assertFalse(cancellation.isDone());

        releaseBlocker.countDown();
        assertEquals(CancellationCoordinator.CancelOutcome.REQUESTED,
                cancellation.get(5, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
        coordinator.complete("thr_blocked", "turn_blocked");
        coordinator.close();
    }

    /** 锁定重复关闭复用同一完成结果，同时尊重调用方传入的截止时间。 */
    @Test
    void repeatedCloseUsesOneCompletionAndCallerDeadline() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch blockerEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        executor.submit(() -> {
            blockerEntered.countDown();
            await(releaseBlocker);
        });
        assertTrue(blockerEntered.await(5, TimeUnit.SECONDS));

        DefaultCancellationCoordinator coordinator = new DefaultCancellationCoordinator(executor);
        CancellationCoordinator.CancellationScope scope = coordinator.open("thr_close_deadline", "turn_close_deadline");
        scope.onCancellation(() -> { });
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> coordinator.closeAt(
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
        try {
            long observedUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!scope.isCancellationRequested() && System.nanoTime() < observedUntil) {
                Thread.onSpinWait();
            }
            assertTrue(scope.isCancellationRequested());
            assertThrows(IllegalStateException.class, () -> coordinator.closeAt(
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50)));
        } finally {
            releaseBlocker.countDown();
            first.get(3, TimeUnit.SECONDS);
        }
        coordinator.close();
    }

    /** 锁定取消立即返回可等待阶段，避免同步等待阻塞清理造成调用线程卡死。 */
    @Test
    void cancelReturnsStageBeforeBlockingCleanupFinishes() throws Exception {
        DefaultCancellationCoordinator coordinator = new DefaultCancellationCoordinator();
        CancellationCoordinator.CancellationScope scope = coordinator.open("thr_nonblock", "turn_nonblock");
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        scope.onCancellation(() -> {
            cleanupEntered.countDown();
            await(releaseCleanup);
        });
        CompletableFuture<CancellationCoordinator.CancelOutcome> cancellation = coordinator.cancel(
                "thr_nonblock", "turn_nonblock", "cancel").toCompletableFuture();
        assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
        assertFalse(cancellation.isDone());
        releaseCleanup.countDown();
        assertEquals(CancellationCoordinator.CancelOutcome.REQUESTED, cancellation.get(5, TimeUnit.SECONDS));
        coordinator.complete("thr_nonblock", "turn_nonblock");
        coordinator.close();
    }

    /** 锁定并发取消只有一个单调赢家，防止原因或完成状态发生回退。 */
    @Test
    void concurrentCancelHasOneMonotonicWinner() throws Exception {
        DefaultCancellationCoordinator coordinator = new DefaultCancellationCoordinator();
        coordinator.open("thr_many", "turn_many");
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<CompletableFuture<CancellationCoordinator.CancelOutcome>> attempts =
                    java.util.stream.IntStream.range(0, 32)
                            .mapToObj(index -> CompletableFuture.supplyAsync(() -> coordinator.cancel(
                                    "thr_many", "turn_many", "cancel " + index).toCompletableFuture().join(), executor))
                            .toList();
            CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).join();
            long requested = attempts.stream().map(CompletableFuture::join)
                    .filter(outcome -> outcome == CancellationCoordinator.CancelOutcome.REQUESTED).count();
            long repeated = attempts.stream().map(CompletableFuture::join)
                    .filter(outcome -> outcome == CancellationCoordinator.CancelOutcome.ALREADY_REQUESTED).count();
            assertEquals(1, requested);
            assertEquals(31, repeated);
        }
        coordinator.complete("thr_many", "turn_many");
        coordinator.close();
    }

    /** 锁定多个清理失败被聚合报告，避免首个异常掩盖剩余资源泄漏。 */
    @Test
    void cleanupFailuresAreAggregated() {
        DefaultCancellationCoordinator coordinator = new DefaultCancellationCoordinator();
        CancellationCoordinator.CancellationScope scope = coordinator.open("thr_fail", "turn_fail");
        scope.onCancellation(() -> {
            throw new IllegalStateException("model cleanup failed");
        });
        scope.onCancellation(() -> {
            throw new IllegalArgumentException("process cleanup failed");
        });
        CompletionException failure = assertThrows(CompletionException.class,
                () -> coordinator.cancel("thr_fail", "turn_fail", "cancel").toCompletableFuture().join());
        DefaultCancellationCoordinator.CleanupFailure aggregate = assertInstanceOf(
                DefaultCancellationCoordinator.CleanupFailure.class, failure.getCause());
        assertEquals(2, aggregate.getSuppressed().length);
        coordinator.complete("thr_fail", "turn_fail");
        coordinator.close();
    }

    /** 锁定已关闭注册不会执行回调，防止撤销后的监听器重新参与取消。 */
    @Test
    void closedRegistrationDoesNotRun() {
        DefaultCancellationCoordinator coordinator = new DefaultCancellationCoordinator();
        CancellationCoordinator.CancellationScope scope = coordinator.open("thr_done", "turn_done");
        AtomicInteger calls = new AtomicInteger();
        CancellationToken.Registration registration = scope.onCancellation(calls::incrementAndGet);
        registration.close();
        registration.close();
        assertEquals(CancellationCoordinator.CancelOutcome.REQUESTED,
                coordinator.cancel("thr_done", "turn_done", "cancel").toCompletableFuture().join());
        assertEquals(0, calls.get());
        coordinator.complete("thr_done", "turn_done");
        coordinator.close();
    }

    /** 锁定协调器关闭会取消并释放全部作用域，避免 Turn 资源残留。 */
    @Test
    void closeCancelsAndReleasesAllScopes() {
        DefaultCancellationCoordinator coordinator = new DefaultCancellationCoordinator();
        CancellationCoordinator.CancellationScope first = coordinator.open("thr_close", "turn_1");
        CancellationCoordinator.CancellationScope second = coordinator.open("thr_close", "turn_2");
        AtomicInteger calls = new AtomicInteger();
        first.onCancellation(calls::incrementAndGet);
        second.onCancellation(calls::incrementAndGet);
        coordinator.close();
        assertEquals(2, calls.get());
        assertTrue(first.cleanupCompletion().toCompletableFuture().isDone());
        assertTrue(second.cleanupCompletion().toCompletableFuture().isDone());
        assertTrue(coordinator.find("thr_close", "turn_1").isEmpty());
        assertThrows(IllegalStateException.class, () -> coordinator.open("thr_close", "turn_3"));
        assertEquals(CancellationCoordinator.CancelOutcome.NOT_FOUND,
                coordinator.cancel("thr_close", "turn_1", "late").toCompletableFuture().join());
    }

    /** 将锁存器等待收敛为测试断言，避免竞态夹具无限阻塞。 */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for cancellation race");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("cancellation race was interrupted", exception);
        }
    }
}
