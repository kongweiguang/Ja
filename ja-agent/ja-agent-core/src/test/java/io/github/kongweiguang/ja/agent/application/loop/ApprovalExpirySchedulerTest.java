// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 核实到期线程的资源边界；持久等待裁决继续由真实 Store/Engine 集成测试覆盖。 */
final class ApprovalExpirySchedulerTest {
    /** 重复登记不占第二个名额，过载保留显式诊断，关闭撤销尚未到期的所有回调。 */
    @Test
    void boundsRegistrationsAndCancelsPendingCallbacksOnClose() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger diagnostics = new AtomicInteger();
        ApprovalExpiryScheduler scheduler = new ApprovalExpiryScheduler(
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), Duration.ofSeconds(1), 1,
                (message, failure) -> diagnostics.incrementAndGet());
        try (scheduler) {
            scheduler.schedule(identity(), "interaction_a", 0, Instant.MAX, calls::incrementAndGet);
            scheduler.schedule(identity(), "interaction_a", 0, Instant.MAX, calls::incrementAndGet);
            scheduler.schedule(identity(), "interaction_b", 0, Instant.MAX, calls::incrementAndGet);
            assertEquals(1, diagnostics.get());
        }
        scheduler.schedule(identity(), "interaction_c", 0, Instant.EPOCH, calls::incrementAndGet);
        assertEquals(0, calls.get());
    }

    /** 存储瞬断只重试同一回调，成功后结束；不会制造新的等待身份或外部动作。 */
    @Test
    void retriesTransientStoreFailureAndStopsAfterSettlement() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger diagnostics = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(1);
        try (ApprovalExpiryScheduler scheduler = new ApprovalExpiryScheduler(
                Clock.systemUTC(), Duration.ofSeconds(1), 1,
                (message, failure) -> diagnostics.incrementAndGet())) {
            scheduler.schedule(identity(), "interaction_a", 0, Instant.EPOCH, () -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new AgentStore.StoreUnavailableException("transient fixture", null);
                }
                completed.countDown();
            });
            assertTrue(completed.await(5, TimeUnit.SECONDS));
        }
        assertEquals(2, attempts.get());
        assertEquals(1, diagnostics.get());
    }

    /** 关闭必须等到已进入的回调退出，而不是只把 Future 标成取消就向宿主返回。 */
    @Test
    void closeInterruptsAndJoinsRunningSettlement() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        CountDownLatch blocker = new CountDownLatch(1);
        try (ApprovalExpiryScheduler scheduler = new ApprovalExpiryScheduler(
                Clock.systemUTC(), Duration.ofSeconds(1), 1, (message, failure) -> { })) {
            scheduler.schedule(identity(), "interaction_a", 0, Instant.EPOCH, () -> {
                started.countDown();
                try {
                    blocker.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            scheduler.close();
            assertEquals(0, finished.getCount());
        }
    }

    /** 极端过去时间的毫秒溢出仍表示已经到期，不能被误排到遥远未来。 */
    @Test
    void overflowedPastDeadlineSettlesImmediately() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        try (ApprovalExpiryScheduler scheduler = new ApprovalExpiryScheduler(
                Clock.fixed(Instant.MAX, ZoneOffset.UTC), Duration.ofSeconds(1), 1,
                (message, failure) -> { })) {
            scheduler.schedule(identity(), "interaction_a", 0, Instant.MIN, completed::countDown);
            assertTrue(completed.await(5, TimeUnit.SECONDS));
        }
    }

    /** 亚毫秒余量必须保留，否则持久结算会因尚未到期而返回并失去后续计时。 */
    @Test
    void futureDeadlinesNeverRoundDown() {
        assertEquals(1, ApprovalExpiryScheduler.delayUntil(Instant.EPOCH.plusNanos(1), Instant.EPOCH));
        assertEquals(1, ApprovalExpiryScheduler.delayUntil(Instant.EPOCH.plusMillis(1), Instant.EPOCH));
        assertEquals(2, ApprovalExpiryScheduler.delayUntil(Instant.EPOCH.plusNanos(1_000_001), Instant.EPOCH));
        assertEquals(0, ApprovalExpiryScheduler.delayUntil(Instant.EPOCH, Instant.EPOCH));
        assertEquals(0, ApprovalExpiryScheduler.delayUntil(Instant.EPOCH.minusNanos(1), Instant.EPOCH));
        assertEquals(Long.MAX_VALUE, ApprovalExpiryScheduler.delayUntil(Instant.MAX, Instant.MIN));
    }

    /** 每项测试使用完整租户与执行身份，避免以简化键漏掉生产去重维度。 */
    private static AgentIdentity identity() {
        return new AgentIdentity("tenant_a", "thread_a", "turn_a", "user_a");
    }
}
