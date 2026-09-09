// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.WorkspaceWriteClaimPort;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationSource;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 聚焦验证 Workspace 写租约的 FIFO、取消清理与 fencing owner。 */
final class WorkspaceWriteLeaseCoordinatorTest {
    private static final Clock CLOCK = Clock.systemUTC();

    /** 第二个写调用必须等待同 Workspace 队首释放，且两个 claim 各自只进入一个终态。 */
    @Test
    void serializesWorkspaceWritersInFifoOrder() throws Exception {
        RecordingClaims claims = new RecordingClaims();
        try (WorkspaceWriteLeaseCoordinator leases = new WorkspaceWriteLeaseCoordinator(claims, CLOCK, 7)) {
            WorkspaceWriteLeaseCoordinator.Lease first = leases.acquire(context("turn_first"), CancellationToken.none());
            CompletableFuture<WorkspaceWriteLeaseCoordinator.Lease> second = CompletableFuture.supplyAsync(
                    () -> leases.acquire(context("turn_second"), CancellationToken.none()));

            Thread.sleep(75);
            assertFalse(second.isDone(), "后继 writer 不得越过仍持有的 FIFO 队首");
            first.close();
            try (WorkspaceWriteLeaseCoordinator.Lease ignored = second.get(1, TimeUnit.SECONDS)) {
                assertEquals(List.of("turn_first", "turn_second"), claims.acquiredTurns());
            }
            assertEquals(2, claims.releasedCount());
        }
    }

    /** 等待租约时取消必须立即唤醒轮询并放弃 claim，不能给后续 Turn 留下阻塞头。 */
    @Test
    void cancellationAbandonsWaitingClaim() throws Exception {
        RecordingClaims claims = new RecordingClaims();
        try (WorkspaceWriteLeaseCoordinator leases = new WorkspaceWriteLeaseCoordinator(claims, CLOCK, 9);
             WorkspaceWriteLeaseCoordinator.Lease held = leases.acquire(context("turn_owner"), CancellationToken.none())) {
            CancellationSource cancellation = new CancellationSource();
            CompletableFuture<Void> waiting = CompletableFuture.runAsync(
                    () -> leases.acquire(context("turn_waiting"), cancellation));

            assertTrue(claims.awaitEnqueued(2, 1_000));
            cancellation.cancel("parent turn cancelled");
            java.util.concurrent.ExecutionException failure = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> waiting.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof java.util.concurrent.CancellationException);
            assertEquals(WorkspaceWriteClaimPort.State.ABANDONED,
                    claims.stateForTurn("turn_waiting"));
        }
    }

    /** Deadline 先于 FIFO owner 时必须返回稳定 TIMEOUT，并留下可恢复的 ABANDONED claim。 */
    @Test
    void deadlineMapsToStableLeaseTimeout() {
        RecordingClaims claims = new RecordingClaims();
        try (WorkspaceWriteLeaseCoordinator leases = new WorkspaceWriteLeaseCoordinator(claims, CLOCK, 10)) {
            WorkspaceWriteLeaseCoordinator.LeaseFailure failure = assertThrows(
                    WorkspaceWriteLeaseCoordinator.LeaseFailure.class,
                    () -> leases.acquire(context("turn_timeout", Instant.now().minusSeconds(1)),
                            CancellationToken.none()));

            assertEquals(WorkspaceWriteLeaseCoordinator.LeaseFailure.Code.TIMEOUT, failure.code());
            assertEquals(WorkspaceWriteClaimPort.State.ABANDONED, claims.stateForTurn("turn_timeout"));
        }
    }

    /** Heartbeat 丢失必须取消执行中派生 token，并在 release 时以 OWNERSHIP_LOST 失败关闭。 */
    @Test
    void heartbeatLossCancelsExecutionAndFailsClosure() throws Exception {
        RecordingClaims claims = new RecordingClaims();
        ListAppender<ILoggingEvent> logs = captureLogs();
        WorkspaceWriteLeaseCoordinator leases = new WorkspaceWriteLeaseCoordinator(
                claims, CLOCK, 11, Duration.ofMillis(10));
        try {
            WorkspaceWriteLeaseCoordinator.Lease lease = leases.acquire(
                    context("turn_heartbeat"), CancellationToken.none());
            claims.failHeartbeats = true;

            long deadline = System.currentTimeMillis() + 1_000;
            while (!lease.cancellation().isCancellationRequested() && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }

            assertTrue(lease.cancellation().isCancellationRequested());
            WorkspaceWriteLeaseCoordinator.LeaseFailure failure = assertThrows(
                    WorkspaceWriteLeaseCoordinator.LeaseFailure.class, lease::close);
            assertEquals(WorkspaceWriteLeaseCoordinator.LeaseFailure.Code.OWNERSHIP_LOST, failure.code());
            assertTrue(logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .anyMatch("event=workspace_write_lease_lost ownership_lost=1"::equals));
            assertTrue(logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains("turn_heartbeat") || message.contains("ws_test")));
        } finally {
            detachLogs(logs);
            leases.close();
        }
    }

    /** 每个用例使用同一 Workspace 和未来 Deadline，仅改变 Turn identity。 */
    private static AgentTool.ExecutionContext context(String turnId) {
        return context(turnId, Instant.now().plusSeconds(5));
    }

    /** 显式 Deadline 变体覆盖无需等待墙钟推进的超时分支。 */
    private static AgentTool.ExecutionContext context(String turnId, Instant deadline) {
        return new AgentTool.ExecutionContext("thr_test", turnId, Path.of("C:/workspace"),
                AccessMode.FULL_ACCESS, "cfg_test", deadline, "ws_test");
    }

    /** ListAppender 只捕获目标类日志，验证固定 key=value 形状且不依赖全局文件配置。 */
    private static ListAppender<ILoggingEvent> captureLogs() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(WorkspaceWriteLeaseCoordinator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /** 每个测试解除 appender，避免并行测试持有跨用例日志引用。 */
    private static void detachLogs(ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                WorkspaceWriteLeaseCoordinator.class)).detachAppender(appender);
        appender.stop();
    }

    /** 内存 fake 只实现 Repository 已承诺的 FIFO 与 fencing 语义，不复制租约管理器的轮询逻辑。 */
    private static final class RecordingClaims implements WorkspaceWriteClaimPort {
        private final Map<String, WorkspaceWriteClaimPort.WriteClaim> claims = new LinkedHashMap<>();
        private long sequence;
        private volatile boolean failHeartbeats;

        /** 追加 WAITING 并用 sequence 同时作为单调 fencing token。 */
        @Override
        public synchronized WorkspaceWriteClaimPort.WriteClaim enqueue(
                String claimId, String workspaceId, String threadId, String turnId,
                long processGeneration, Instant requestedAt) {
            WorkspaceWriteClaimPort.WriteClaim claim = new WorkspaceWriteClaimPort.WriteClaim(
                    ++sequence, claimId, workspaceId,
                    threadId, turnId, processGeneration, sequence,
                    WorkspaceWriteClaimPort.State.WAITING, requestedAt, null, null, null);
            claims.put(claimId, claim);
            notifyAll();
            return claim;
        }

        /** 仅无 HELD owner且目标是最早 WAITING 时授予租约。 */
        @Override
        public synchronized Optional<WorkspaceWriteClaimPort.WriteClaim> tryAcquire(
                String claimId, long fencingToken, Instant acquiredAt) {
            if (claims.values().stream().anyMatch(claim ->
                    claim.state() == WorkspaceWriteClaimPort.State.HELD)) return Optional.empty();
            WorkspaceWriteClaimPort.WriteClaim first = claims.values().stream().filter(claim ->
                    claim.state() == WorkspaceWriteClaimPort.State.WAITING).findFirst().orElse(null);
            if (first == null || !first.claimId().equals(claimId)
                    || first.fencingToken() != fencingToken) return Optional.empty();
            WorkspaceWriteClaimPort.WriteClaim held = copy(first, WorkspaceWriteClaimPort.State.HELD,
                    acquiredAt, acquiredAt, null);
            claims.put(claimId, held);
            return Optional.of(held);
        }

        /** 测试小于五秒，不应触发 heartbeat；实现仍严格拒绝非 HELD owner。 */
        @Override
        public synchronized Optional<WorkspaceWriteClaimPort.WriteClaim> heartbeat(
                String claimId, long fencingToken, Instant occurredAt) {
            WorkspaceWriteClaimPort.WriteClaim current = claims.get(claimId);
            if (failHeartbeats) return Optional.empty();
            if (!owned(current, fencingToken, WorkspaceWriteClaimPort.State.HELD)) return Optional.empty();
            WorkspaceWriteClaimPort.WriteClaim heartbeat = copy(
                    current, current.state(), current.acquiredAt(), occurredAt, null);
            claims.put(claimId, heartbeat);
            return Optional.of(heartbeat);
        }

        /** 正常释放只接受当前 HELD fencing owner。 */
        @Override
        public synchronized Optional<WorkspaceWriteClaimPort.WriteClaim> release(
                String claimId, long fencingToken, Instant occurredAt) {
            WorkspaceWriteClaimPort.WriteClaim current = claims.get(claimId);
            if (!owned(current, fencingToken, WorkspaceWriteClaimPort.State.HELD)) return Optional.empty();
            WorkspaceWriteClaimPort.WriteClaim released = copy(current, WorkspaceWriteClaimPort.State.RELEASED,
                    current.acquiredAt(), current.heartbeatAt(), occurredAt);
            claims.put(claimId, released);
            notifyAll();
            return Optional.of(released);
        }

        /** 取消等待只接受当前 WAITING fencing owner。 */
        @Override
        public synchronized Optional<WorkspaceWriteClaimPort.WriteClaim> abandon(
                String claimId, long fencingToken, Instant occurredAt) {
            WorkspaceWriteClaimPort.WriteClaim current = claims.get(claimId);
            if (!owned(current, fencingToken, WorkspaceWriteClaimPort.State.WAITING)) return Optional.empty();
            WorkspaceWriteClaimPort.WriteClaim abandoned = copy(
                    current, WorkspaceWriteClaimPort.State.ABANDONED,
                    null, null, occurredAt);
            claims.put(claimId, abandoned);
            notifyAll();
            return Optional.of(abandoned);
        }

        /** 等待指定数量 claim 已持久化，避免并发断言依赖固定调度时机。 */
        private synchronized boolean awaitEnqueued(int expected, long timeoutMillis) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (claims.size() < expected && System.currentTimeMillis() < deadline) {
                wait(Math.max(1L, deadline - System.currentTimeMillis()));
            }
            return claims.size() >= expected;
        }

        /** 返回真实 HELD 顺序，不把 WAITING 插入顺序误当成已取得写权。 */
        private synchronized List<String> acquiredTurns() {
            List<String> result = new ArrayList<>();
            claims.values().stream().filter(claim -> claim.acquiredAt() != null)
                    .forEach(claim -> result.add(claim.turnId()));
            return List.copyOf(result);
        }

        /** 统计正常释放，ABANDONED 不冒充执行过的 writer。 */
        private synchronized long releasedCount() {
            return claims.values().stream().filter(claim ->
                    claim.state() == WorkspaceWriteClaimPort.State.RELEASED).count();
        }

        /** 按 Turn 回读终态，测试 ID 唯一且不依赖随机 claimId。 */
        private synchronized WorkspaceWriteClaimPort.State stateForTurn(String turnId) {
            return claims.values().stream().filter(claim -> claim.turnId().equals(turnId))
                    .findFirst().orElseThrow().state();
        }

        /** fencing、状态与 identity 同时匹配才允许迁移。 */
        private static boolean owned(WorkspaceWriteClaimPort.WriteClaim claim, long fencingToken,
                                     WorkspaceWriteClaimPort.State state) {
            return claim != null && claim.fencingToken() == fencingToken && claim.state() == state;
        }

        /** 状态迁移保持所有不可变 identity 与原请求时间。 */
        private static WorkspaceWriteClaimPort.WriteClaim copy(
                WorkspaceWriteClaimPort.WriteClaim source, WorkspaceWriteClaimPort.State state,
                Instant acquiredAt, Instant heartbeatAt, Instant releasedAt) {
            return new WorkspaceWriteClaimPort.WriteClaim(
                    source.sequence(), source.claimId(), source.workspaceId(),
                    source.threadId(), source.turnId(), source.processGeneration(), source.fencingToken(),
                    state, source.requestedAt(), acquiredAt, heartbeatAt, releasedAt);
        }
    }
}
