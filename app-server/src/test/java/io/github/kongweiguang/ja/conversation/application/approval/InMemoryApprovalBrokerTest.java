// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.approval;

import io.github.kongweiguang.ja.conversation.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.permission.PermissionAction;
import io.github.kongweiguang.ja.conversation.domain.permission.PermissionRequest;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定审批决定的 persist-before-wake 边界及失败后的可重试语义。 */
final class InMemoryApprovalBrokerTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** 持久层返回 false 时不得完成 waiter，下一次成功提交仍可解析同一审批。 */
    @Test
    void rejectedPersistenceLeavesWaiterPendingAndRetryable() {
        try (InMemoryApprovalBroker broker = broker()) {
            AtomicInteger attempts = new AtomicInteger();
            broker.bindDecisionStore((approvalId, decision, resolvedAt) -> attempts.incrementAndGet() > 1);
            CompletionStage<ApprovalBroker.Resolution> pending = broker.request(request(), CancellationToken.none());

            assertFalse(broker.resolve("appr_resume", ApprovalDecision.APPROVE, NOW));
            assertFalse(pending.toCompletableFuture().isDone());
            assertTrue(broker.resolve("appr_resume", ApprovalDecision.APPROVE, NOW));
            assertEquals(ApprovalDecision.APPROVE, pending.toCompletableFuture().join().response());
            assertEquals(2, attempts.get());
        }
    }

    /** 持久层异常必须原样返回且保留 waiter，恢复后可用同一 ID 再次提交。 */
    @Test
    void persistenceExceptionLeavesWaiterPendingAndRetryable() {
        try (InMemoryApprovalBroker broker = broker()) {
            AtomicInteger attempts = new AtomicInteger();
            broker.bindDecisionStore((approvalId, decision, resolvedAt) -> {
                if (attempts.getAndIncrement() == 0) throw new IllegalStateException("database unavailable");
                return true;
            });
            CompletionStage<ApprovalBroker.Resolution> pending = broker.request(request(), CancellationToken.none());

            assertThrows(IllegalStateException.class,
                    () -> broker.resolve("appr_resume", ApprovalDecision.APPROVE, NOW));
            assertFalse(pending.toCompletableFuture().isDone());
            assertTrue(broker.resolve("appr_resume", ApprovalDecision.DENY, NOW));
            assertEquals(ApprovalDecision.DENY, pending.toCompletableFuture().join().response());
            assertEquals(2, attempts.get());
        }
    }

    /** APPROVE 持久事务持有 resolving ownership 时，取消不得把同一 waiter 提前完成为 DENY。 */
    @Test
    void concurrentCancellationCannotOvertakePersistingApproval() throws Exception {
        try (InMemoryApprovalBroker broker = broker()) {
            CountDownLatch persistenceEntered = new CountDownLatch(1);
            CountDownLatch persistenceRelease = new CountDownLatch(1);
            AtomicInteger decisions = new AtomicInteger();
            broker.bindDecisionStore((approvalId, decision, resolvedAt) -> {
                decisions.incrementAndGet();
                persistenceEntered.countDown();
                try {
                    assertTrue(persistenceRelease.await(1, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return true;
            });
            CompletionStage<ApprovalBroker.Resolution> pending = broker.request(request(), CancellationToken.none());
            CompletableFuture<Boolean> resolving = CompletableFuture.supplyAsync(
                    () -> broker.resolve("appr_resume", ApprovalDecision.APPROVE, NOW));
            assertTrue(persistenceEntered.await(1, TimeUnit.SECONDS));

            broker.cancelTurn("thr_test", "turn_test", "cancel race");
            assertFalse(pending.toCompletableFuture().isDone());
            persistenceRelease.countDown();

            assertTrue(resolving.get(1, TimeUnit.SECONDS));
            assertEquals(ApprovalDecision.APPROVE, pending.toCompletableFuture().join().response());
            assertEquals(1, decisions.get());
        }
    }

    /** 取消自动 DENY 持久失败时不得唤醒 waiter，同一审批仍可显式重试。 */
    @Test
    void cancellationPersistenceFailureLeavesWaiterRetryable() {
        try (InMemoryApprovalBroker broker = broker()) {
            AtomicInteger attempts = new AtomicInteger();
            broker.bindDecisionStore((approvalId, decision, resolvedAt) -> attempts.incrementAndGet() > 1);
            CancellationSource cancellation = new CancellationSource();
            CompletionStage<ApprovalBroker.Resolution> pending = broker.request(request(), cancellation);

            CancellationSource.CancelResult cancelled = cancellation.cancel("test cancellation");

            assertTrue(cancelled.changed());
            assertTrue(cancelled.callbackFailure().isEmpty());
            assertFalse(pending.toCompletableFuture().isDone());
            assertTrue(broker.resolve("appr_resume", ApprovalDecision.DENY, NOW));
            assertEquals(ApprovalDecision.DENY, pending.toCompletableFuture().join().response());
            assertEquals(2, attempts.get());
        }
    }

    /** 到期任务持久失败时保留 waiter，不得因 scheduler 已触发就把内存结果伪装为已拒绝。 */
    @Test
    void timeoutPersistenceFailureLeavesWaiterRetryable() throws Exception {
        try (InMemoryApprovalBroker broker = broker()) {
            CountDownLatch timeoutAttempted = new CountDownLatch(1);
            AtomicInteger attempts = new AtomicInteger();
            broker.bindDecisionStore((approvalId, decision, resolvedAt) -> {
                int attempt = attempts.incrementAndGet();
                if (attempt == 1) timeoutAttempted.countDown();
                return attempt > 1;
            });
            CompletionStage<ApprovalBroker.Resolution> pending = broker.request(
                    request(Duration.ofMillis(10)), CancellationToken.none());

            assertTrue(timeoutAttempted.await(1, TimeUnit.SECONDS));
            assertFalse(pending.toCompletableFuture().isDone());
            assertTrue(broker.resolve("appr_resume", ApprovalDecision.DENY, NOW));
            assertEquals(ApprovalDecision.DENY, pending.toCompletableFuture().join().response());
            assertEquals(2, attempts.get());
        }
    }

    /** 关闭自动 DENY 未持久时必须失败可见并恢复 open 位，第二次关闭才能完成 waiter。 */
    @Test
    void closePersistenceFailureIsRetryable() {
        InMemoryApprovalBroker broker = broker();
        AtomicInteger attempts = new AtomicInteger();
        broker.bindDecisionStore((approvalId, decision, resolvedAt) -> attempts.incrementAndGet() > 1);
        CompletionStage<ApprovalBroker.Resolution> pending = broker.request(request(), CancellationToken.none());

        assertThrows(IllegalStateException.class, broker::close);
        assertFalse(pending.toCompletableFuture().isDone());
        broker.close();

        assertEquals(ApprovalDecision.DENY, pending.toCompletableFuture().join().response());
        assertEquals(2, attempts.get());
    }

    /** 每个测试使用独立 Broker，避免墓碑与调度任务跨用例共享。 */
    private static InMemoryApprovalBroker broker() {
        return new InMemoryApprovalBroker(CLOCK, 8, 32, Duration.ofMinutes(10));
    }

    /** 构造绑定单一 Turn 的审批请求，测试只改变持久化结果而不改变身份。 */
    private static ApprovalBroker.ApprovalRequest request() {
        return request(Duration.ofMinutes(5));
    }

    /** 允许超时用例缩短真实 scheduler 延迟，同时权威时钟仍固定可断言。 */
    private static ApprovalBroker.ApprovalRequest request(Duration ttl) {
        PermissionRequest permission = new PermissionRequest(
                "thr_test", "turn_test", "cfg_test", AccessMode.APPROVAL_REQUIRED,
                PermissionAction.WRITE, "edit", Path.of("C:/workspace"), List.of(), null);
        return new ApprovalBroker.ApprovalRequest(
                "appr_resume", permission, "Tool requires approval", NOW.plus(ttl));
    }
}
