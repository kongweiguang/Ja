// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Turn 队列并发回归集，锁定容量、Thread FIFO、公平调度、取消与关闭竞态。 */
final class TurnQueueTest {
    /**
     * 生产构造器只把已取得 running 槽的 Turn 交给虚拟线程，不额外创建平台 Worker 池。
     */
    @Test
    void productionWorkerRunsOnVirtualThread() throws Exception {
        TurnQueue queue = new TurnQueue(64, 8, 8);
        try {
            java.util.concurrent.atomic.AtomicBoolean virtual = new java.util.concurrent.atomic.AtomicBoolean();
            queue.submit("thr_virtual", "turn_virtual",
                    () -> virtual.set(Thread.currentThread().isVirtual()))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertTrue(virtual.get());
        } finally {
            queue.close();
            assertTrue(queue.awaitQuiescence(Duration.ofSeconds(2)));
        }
    }

    /** 锁定全局容量上限，防止多 Thread 合计准入数绕过队列保护。 */
    @Test
    void enforcesTotalCapacity() throws Exception {
        try (TurnQueue queue = new TurnQueue(2, 2, 1)) {
            CountDownLatch release = new CountDownLatch(1);
            queue.submit("thr_a", "turn_a", () -> await(release));
            queue.submit("thr_b", "turn_b", () -> await(release));
            assertThrows(RejectedExecutionException.class,
                    () -> queue.reserve("thr_c", "turn_c"));
            release.countDown();
            assertTrue(queue.awaitQuiescence(Duration.ofSeconds(2)));
        }
    }

    /** 锁定单 Thread 容量上限，避免热点会话独占全局队列。 */
    @Test
    void enforcesPerThreadCapacity() throws Exception {
        try (TurnQueue queue = new TurnQueue(4, 2, 2)) {
            CountDownLatch release = new CountDownLatch(1);
            queue.submit("thr_a", "turn_a1", () -> await(release));
            queue.submit("thr_a", "turn_a2", () -> await(release));
            assertThrows(RejectedExecutionException.class,
                    () -> queue.reserve("thr_a", "turn_a3"));
            TurnQueue.Reservation other = queue.reserve("thr_b", "turn_b1");
            other.submit(() -> { });
            release.countDown();
            assertTrue(queue.awaitQuiescence(Duration.ofSeconds(2)));
        }
    }

    /** 锁定同一 Thread 严格 FIFO，防止后提交 Turn 越过前序执行。 */
    @Test
    void preservesThreadFifo() throws Exception {
        try (TurnQueue queue = new TurnQueue(8, 4, 2)) {
            List<String> order = new ArrayList<>();
            queue.submit("thr_fifo", "turn_1", () -> order.add("1"));
            queue.submit("thr_fifo", "turn_2", () -> order.add("2"));
            queue.submit("thr_fifo", "turn_3", () -> order.add("3"));
            assertTrue(queue.awaitQuiescence(Duration.ofSeconds(2)));
            assertEquals(List.of("1", "2", "3"), order);
        }
    }

    /** 锁定就绪 lane 轮询调度，避免持续活跃 Thread 饿死其他会话。 */
    @Test
    void schedulesReadyLanesRoundRobin() throws Exception {
        try (TurnQueue queue = new TurnQueue(8, 8, 1)) {
            List<String> order = new ArrayList<>();
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            AtomicInteger hotDispatch = new AtomicInteger();
            Runnable[] hotTask = new Runnable[1];
            hotTask[0] = () -> {
                int dispatch = hotDispatch.incrementAndGet();
                order.add("a" + dispatch);
                if (dispatch == 1) {
                    firstStarted.countDown();
                    await(releaseFirst);
                }
                if (dispatch < 4) {
                    queue.submit("thr_a", "turn_a" + (dispatch + 1), hotTask[0]);
                }
            };
            queue.submit("thr_a", "turn_a1", hotTask[0]);
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            queue.submit("thr_b", "turn_b1", () -> order.add("b1"));
            releaseFirst.countDown();
            assertTrue(queue.awaitQuiescence(Duration.ofSeconds(2)));
            assertEquals(List.of("a1", "b1", "a2", "a3", "a4"), order);
        }
    }

    /** 锁定停止准入后仍排空已保留工作，避免关闭边界丢失已确认 Turn。 */
    @Test
    void stopAcceptingDrainsAlreadyAdmittedReservations() throws Exception {
        try (TurnQueue queue = new TurnQueue(4, 4, 1)) {
            AtomicInteger executions = new AtomicInteger();
            TurnQueue.Reservation admitted = queue.reserve("thr_drain", "turn_admitted");
            queue.stopAccepting();
            assertThrows(RejectedExecutionException.class,
                    () -> queue.reserve("thr_rejected", "turn_rejected"));
            admitted.submit(executions::incrementAndGet);
            assertTrue(queue.awaitQuiescence(Duration.ofSeconds(2)));
            assertEquals(1, executions.get());
        }
    }

    /** 锁定不同 Thread 可并行执行，同时保持各自 lane 的串行语义。 */
    @Test
    void runsDifferentThreadsInParallel() throws Exception {
        try (TurnQueue queue = new TurnQueue(8, 4, 2)) {
            CountDownLatch entered = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger concurrent = new AtomicInteger();
            AtomicInteger maximum = new AtomicInteger();
            Runnable task = () -> {
                int current = concurrent.incrementAndGet();
                maximum.accumulateAndGet(current, Math::max);
                entered.countDown();
                await(release);
                concurrent.decrementAndGet();
            };
            queue.submit("thr_one", "turn_one", task);
            queue.submit("thr_two", "turn_two", task);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(2, maximum.get());
            release.countDown();
            assertTrue(queue.awaitQuiescence(Duration.ofSeconds(2)));
        }
    }

    /** 锁定取消尚未运行的条目会释放容量且不调用任务体。 */
    @Test
    void cancelsQueuedEntry() throws Exception {
        try (TurnQueue queue = new TurnQueue(4, 4, 1)) {
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger executions = new AtomicInteger();
            queue.submit("thr_cancel", "turn_running", () -> await(release));
            queue.submit("thr_cancel", "turn_queued", executions::incrementAndGet);
            assertTrue(queue.cancelQueued("thr_cancel", "turn_queued", "user cancel"));
            assertFalse(queue.cancelQueued("thr_cancel", "turn_queued", "late cancel"));
            release.countDown();
            assertTrue(queue.awaitQuiescence(Duration.ofSeconds(2)));
            assertEquals(0, executions.get());
        }
    }

    /** 锁定取消就绪 lane 后可为同一 Thread 重建 lane，避免会话永久失活。 */
    @Test
    void cancelledReadyLaneCanBeRecreated() throws Exception {
        try (TurnQueue queue = new TurnQueue(4, 2, 1)) {
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger executions = new AtomicInteger();
            queue.submit("thr_blocker", "turn_blocker", () -> await(release));
            queue.submit("thr_reused", "turn_cancelled", () -> executions.addAndGet(100));
            assertTrue(queue.cancelQueued("thr_reused", "turn_cancelled", "cancel before dispatch"));
            queue.submit("thr_reused", "turn_replacement", executions::incrementAndGet);
            release.countDown();
            assertTrue(queue.awaitQuiescence(Duration.ofSeconds(2)));
            assertEquals(1, executions.get());
        }
    }

    /** 锁定关闭取消排队工作但不阻塞运行中头部，避免收口线程死锁。 */
    @Test
    void closeCancelsQueuedWorkWithoutBlockingRunningHead() throws Exception {
        TurnQueue queue = new TurnQueue(4, 4, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            queue.submit("thr_close", "turn_running", () -> {
                started.countDown();
                await(release);
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            java.util.concurrent.CompletionStage<Void> queued =
                    queue.submit("thr_close", "turn_queued", () -> { });
            queue.close();
            assertThrows(CancellationException.class, () -> queued.toCompletableFuture().join());
            assertThrows(RejectedExecutionException.class,
                    () -> queue.reserve("thr_close", "turn_rejected"));
            assertFalse(queue.awaitQuiescence(Duration.ZERO));
            release.countDown();
            assertTrue(queue.awaitQuiescence(Duration.ofSeconds(2)));
        } finally {
            release.countDown();
            queue.close();
        }
    }

    /** 锁定执行器拒绝在队列 monitor 外发布，避免回调重入造成锁反转。 */
    @Test
    void publishesExecutorRejectionOutsideMonitor() throws Exception {
        ExecutorService rejecting = Executors.newSingleThreadExecutor();
        rejecting.shutdown();
        try (TurnQueue queue = new TurnQueue(4, 4, 1, rejecting)) {
            Object monitor = monitorOf(queue);
            AtomicBoolean callbackHeldMonitor = new AtomicBoolean(true);
            AtomicBoolean callbackSawCleanState = new AtomicBoolean();
            AtomicInteger completionCount = new AtomicInteger();
            TurnQueue.Reservation reservation = queue.reserve("thr_reject", "turn_reject");
            java.util.concurrent.CompletionStage<Void> completion = reservation.completion();
            completion.whenComplete((ignored, failure) -> {
                callbackHeldMonitor.set(Thread.holdsLock(monitor));
                callbackSawCleanState.set(failure instanceof RejectedExecutionException
                        && queue.admittedCount() == 0
                        && queue.runningCount() == 0
                        && queue.awaitQuiescence(Duration.ZERO));
                completionCount.incrementAndGet();
            });

            reservation.submit(() -> { });

            CompletionException rejection = assertThrows(
                    CompletionException.class, () -> completion.toCompletableFuture().join());
            assertTrue(rejection.getCause() instanceof RejectedExecutionException);
            assertFalse(callbackHeldMonitor.get());
            assertTrue(callbackSawCleanState.get());
            assertEquals(1, completionCount.get());
            assertEquals(0, readyLaneCount(queue));
        }
    }

    /** 锁定成功完成清理 lane 后允许回调重入提交，避免内部锁阻塞后续工作。 */
    @Test
    void successfulCompletionCanReenterAfterLaneCleanup() throws Exception {
        try (TurnQueue queue = new TurnQueue(4, 4, 1)) {
            Object monitor = monitorOf(queue);
            CountDownLatch continuationFinished = new CountDownLatch(1);
            AtomicBoolean callbackHeldMonitor = new AtomicBoolean(true);
            AtomicBoolean callbackSawQuiescence = new AtomicBoolean();
            AtomicInteger successorRuns = new AtomicInteger();
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            TurnQueue.Reservation first = queue.reserve("thr_reentrant", "turn_first");
            first.completion().whenComplete((ignored, failure) -> {
                callbackHeldMonitor.set(Thread.holdsLock(monitor));
                callbackSawQuiescence.set(queue.awaitQuiescence(Duration.ZERO));
                try {
                    if (failure != null) throw new AssertionError("first task failed", failure);
                    queue.submit("thr_reentrant", "turn_second", successorRuns::incrementAndGet);
                } catch (Throwable callbackError) {
                    callbackFailure.set(callbackError);
                } finally {
                    continuationFinished.countDown();
                }
            });

            first.submit(() -> { });

            assertTrue(continuationFinished.await(2, TimeUnit.SECONDS));
            assertNull(callbackFailure.get());
            assertFalse(callbackHeldMonitor.get());
            assertTrue(callbackSawQuiescence.get());
            assertTrue(queue.awaitQuiescence(Duration.ofSeconds(2)));
            assertEquals(1, successorRuns.get());
        }
    }

    /** 锁定执行器拒绝与关闭竞态只完成一次，防止同一 reservation 双重结算。 */
    @Test
    void executorRejectionRacingCloseDoesNotDoubleComplete() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch rejectionEntered = new CountDownLatch(1);
        CountDownLatch releaseRejection = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>());
        executor.execute(() -> {
            workerStarted.countDown();
            await(releaseWorker);
        });
        assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
        executor.setRejectedExecutionHandler((task, owner) -> {
            rejectionEntered.countDown();
            try {
                if (!releaseRejection.await(2, TimeUnit.SECONDS)) {
                    throw new RejectedExecutionException("rejection gate timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("rejection gate interrupted", interrupted);
            }
            throw new RejectedExecutionException("forced rejection");
        });

        TurnQueue queue = new TurnQueue(4, 4, 1, executor);
        AtomicInteger completionCount = new AtomicInteger();
        AtomicBoolean callbackHeldMonitor = new AtomicBoolean(true);
        AtomicReference<Throwable> submitFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Object monitor = monitorOf(queue);
        TurnQueue.Reservation reservation = queue.reserve("thr_race", "turn_race");
        java.util.concurrent.CompletionStage<Void> completion = reservation.completion();
        completion.whenComplete((ignored, failure) -> {
            callbackHeldMonitor.set(Thread.holdsLock(monitor));
            completionCount.incrementAndGet();
        });
        Thread submitter = Thread.ofPlatform().start(() -> {
            try {
                reservation.submit(() -> { });
            } catch (Throwable failure) {
                submitFailure.set(failure);
            }
        });
        try {
            assertTrue(rejectionEntered.await(2, TimeUnit.SECONDS));
            Thread closer = Thread.ofPlatform().start(() -> {
                closeStarted.countDown();
                try {
                    queue.close();
                } catch (Throwable failure) {
                    closeFailure.set(failure);
                }
            });
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS));
            releaseRejection.countDown();
            join(submitter);
            join(closer);

            assertNull(submitFailure.get());
            assertNull(closeFailure.get());
            CompletionException rejection = assertThrows(
                    CompletionException.class, () -> completion.toCompletableFuture().join());
            assertTrue(rejection.getCause() instanceof RejectedExecutionException);
            assertFalse(callbackHeldMonitor.get());
            assertEquals(1, completionCount.get());
            assertEquals(0, queue.admittedCount());
            assertEquals(0, queue.runningCount());
            assertEquals(0, readyLaneCount(queue));
        } finally {
            releaseRejection.countDown();
            releaseWorker.countDown();
            queue.close();
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }

    /** 锁定 reservation 在显式 submit 前绝不运行，保持准入与执行两阶段边界。 */
    @Test
    void reservationDoesNotRunBeforeSubmit() throws Exception {
        try (TurnQueue queue = new TurnQueue(4, 4, 1)) {
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger executions = new AtomicInteger();
            java.util.concurrent.CompletionStage<Void> running =
                    queue.submit("thr_reserved", "turn_running", () -> await(release));
            TurnQueue.Reservation reservation = queue.reserve("thr_reserved", "turn_reserved");
            release.countDown();
            running.toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(0, executions.get());
            reservation.submit(executions::incrementAndGet);
            assertTrue(queue.awaitQuiescence(Duration.ofSeconds(2)));
            assertEquals(1, executions.get());
        }
    }

    /** 有界等待并将超时转为断言失败，避免并发测试无限挂起。 */
    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test interrupted", interrupted);
        }
    }

    /** 读取队列 monitor 仅用于证明回调发布不持锁，不扩散反射依赖到生产代码。 */
    private static Object monitorOf(TurnQueue queue) throws ReflectiveOperationException {
        Field field = TurnQueue.class.getDeclaredField("monitor");
        field.setAccessible(true);
        return field.get(queue);
    }

    /** 读取就绪 lane 数量以验证取消清理完成，避免通过时序猜测内部状态。 */
    private static int readyLaneCount(TurnQueue queue) throws ReflectiveOperationException {
        Field field = TurnQueue.class.getDeclaredField("readyLanes");
        field.setAccessible(true);
        return ((ArrayDeque<?>) field.get(queue)).size();
    }

    /** 有界等待线程退出并断言不存活，使竞态用例能可靠发现死锁。 */
    private static void join(Thread thread) throws InterruptedException {
        thread.join(2_000);
        assertFalse(thread.isAlive(), "race participant did not finish");
    }
}
