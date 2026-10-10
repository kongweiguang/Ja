// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.engine;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证队列上限、同 Thread 顺序执行及不同 Thread 间的并行进展。 */
final class ExecutionSchedulerTest {
    /** 用单工作线程的 FIFO 屏障核实取消后续准入不会重派队首，而非依赖时间等待碰撞竞态。 */
    @Test
    void releasingLaterReservationDoesNotRedispatchRunningHead() throws Exception {
        try (ExecutionScheduler scheduler = new ExecutionScheduler(1, 4, 4)) {
            CompletableFuture<Void> running = new CompletableFuture<>();
            CountDownLatch started = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            ExecutionScheduler.ThreadKey thread = new ExecutionScheduler.ThreadKey("tenant_a", "thread_a");
            ExecutionScheduler.Reservation first = scheduler.reserve(thread);
            CompletionStage<Void> firstSlot = first.start(() -> {
                calls.incrementAndGet();
                started.countDown();
                return running;
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            ExecutionScheduler.Reservation abandoned = scheduler.reserve(thread);
            abandoned.close();
            ExecutionScheduler.Reservation barrier = scheduler.reserve(
                    new ExecutionScheduler.ThreadKey("tenant_a", "thread_barrier"));
            barrier.start(() -> CompletableFuture.completedFuture(null))
                    .toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(1, calls.get(), "releasing a later reservation must not execute the running head again");
            running.complete(null);
            firstSlot.toCompletableFuture().get(1, TimeUnit.SECONDS);
        }
    }

    /** 同一 Thread 的首个执行未真正结束前，后续任务必须继续等待。 */
    @Test
    void serializesOneThreadUntilItsExecutionStageCompletes() throws Exception {
        try (ExecutionScheduler scheduler = new ExecutionScheduler(2, 4, 4)) {
            CompletableFuture<Void> firstStage = new CompletableFuture<Void>();
            CompletableFuture<Void> secondStage = new CompletableFuture<Void>();
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch secondStarted = new CountDownLatch(1);
            ExecutionScheduler.ThreadKey thread = new ExecutionScheduler.ThreadKey("tenant_a", "thread_a");

            ExecutionScheduler.Reservation first = scheduler.reserve(thread);
            CompletionStage<Void> firstSlot = first.start(() -> {
                firstStarted.countDown();
                return firstStage;
            });
            ExecutionScheduler.Reservation second = scheduler.reserve(thread);
            CompletionStage<Void> secondSlot = second.start(() -> {
                secondStarted.countDown();
                return secondStage;
            });

            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            assertFalse(secondStarted.await(25, TimeUnit.MILLISECONDS));
            firstStage.complete(null);
            assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
            secondStage.complete(null);
            firstSlot.toCompletableFuture().get(1, TimeUnit.SECONDS);
            secondSlot.toCompletableFuture().get(1, TimeUnit.SECONDS);
        }
    }

    /** 不同 Thread 可以并行启动，慢执行不会占用全局 Thread 闸门。 */
    @Test
    void dispatchesDifferentThreadsInParallel() throws Exception {
        try (ExecutionScheduler scheduler = new ExecutionScheduler(2, 4, 4)) {
            CountDownLatch bothStarted = new CountDownLatch(2);
            CompletableFuture<Void> release = new CompletableFuture<Void>();
            ExecutionScheduler.Reservation first = scheduler.reserve(new ExecutionScheduler.ThreadKey("tenant_a", "thread_a"));
            ExecutionScheduler.Reservation second = scheduler.reserve(new ExecutionScheduler.ThreadKey("tenant_a", "thread_b"));
            CompletionStage<Void> firstStage = first.start(() -> {
                bothStarted.countDown();
                return release;
            });
            CompletionStage<Void> secondStage = second.start(() -> {
                bothStarted.countDown();
                return release;
            });

            assertTrue(bothStarted.await(1, TimeUnit.SECONDS));
            release.complete(null);
            firstStage.toCompletableFuture().get(1, TimeUnit.SECONDS);
            secondStage.toCompletableFuture().get(1, TimeUnit.SECONDS);
        }
    }

    /** 全局或单 Thread 待处理数达到上限时，必须在持久准入前拒绝。 */
    @Test
    void boundsGlobalAndPerThreadOutstandingReservations() {
        try (ExecutionScheduler scheduler = new ExecutionScheduler(1, 2, 2)) {
            ExecutionScheduler.Reservation first = scheduler.reserve(new ExecutionScheduler.ThreadKey("tenant_a", "thread_a"));
            ExecutionScheduler.Reservation second = scheduler.reserve(new ExecutionScheduler.ThreadKey("tenant_a", "thread_a"));
            assertThrows(RejectedExecutionException.class,
                    () -> scheduler.reserve(new ExecutionScheduler.ThreadKey("tenant_a", "thread_a")));
            assertThrows(RejectedExecutionException.class,
                    () -> scheduler.reserve(new ExecutionScheduler.ThreadKey("tenant_b", "thread_b")));
            first.close();
            second.close();

            ExecutionScheduler.Reservation released = scheduler.reserve(new ExecutionScheduler.ThreadKey("tenant_b", "thread_b"));
            released.close();
        }
    }
}
