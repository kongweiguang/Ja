// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.WorkspaceWriteClaimPort;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationSource;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** 把 SQLite FIFO claim 转成单次外部 Tool 调用可持有的取消响应租约。 */
final class WorkspaceWriteLeaseCoordinator implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceWriteLeaseCoordinator.class);
    private static final Duration RETRY_INTERVAL = Duration.ofMillis(25);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(5);
    private final WorkspaceWriteClaimPort claims;
    private final Clock clock;
    private final long processGeneration;
    private final Duration heartbeatInterval;
    private final ScheduledExecutorService heartbeats;
    private final boolean enabled;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 生产路径绑定持久 claims 与进程代际，heartbeat 只维护已取得的 HELD owner。 */
    WorkspaceWriteLeaseCoordinator(WorkspaceWriteClaimPort claims, Clock clock, long processGeneration) {
        this(claims, clock, processGeneration, HEARTBEAT_INTERVAL, true);
    }

    /** 聚焦测试可缩短 Heartbeat 周期，生产调用仍固定五秒并保持同一状态机。 */
    WorkspaceWriteLeaseCoordinator(WorkspaceWriteClaimPort claims, Clock clock, long processGeneration,
                                   Duration heartbeatInterval) {
        this(claims, clock, processGeneration, heartbeatInterval, true);
    }

    /** 旧构造测试保持既有行为；生产组合根发布 AgentLoop 前必须替换此实例。 */
    static WorkspaceWriteLeaseCoordinator disabled(Clock clock) {
        return new WorkspaceWriteLeaseCoordinator(null, clock, 1, HEARTBEAT_INTERVAL, false);
    }

    /** 测试兼容仅限 package 内，不能从 Tool 或 RPC 选择关闭租约。 */
    private WorkspaceWriteLeaseCoordinator(WorkspaceWriteClaimPort claims, Clock clock,
                                           long processGeneration, Duration heartbeatInterval, boolean enabled) {
        if (enabled && processGeneration < 1) throw new IllegalArgumentException("invalid process generation");
        if (heartbeatInterval.isZero() || heartbeatInterval.isNegative()) {
            throw new IllegalArgumentException("invalid heartbeat interval");
        }
        this.claims = enabled ? Objects.requireNonNull(claims, "claims") : null;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.processGeneration = processGeneration;
        this.heartbeatInterval = heartbeatInterval;
        this.enabled = enabled;
        this.heartbeats = enabled ? Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("ja-workspace-write-heartbeat-", 0).factory()) : null;
    }

    /**
     * 先追加 WAITING，再短暂等待 FIFO 队首；取消、关闭与 Deadline 都会 ABANDON，避免留下阻塞头。
     */
    Lease acquire(AgentTool.ExecutionContext context, CancellationToken cancellation) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(cancellation, "cancellation");
        if (!enabled) return Lease.noop(cancellation);
        if (closed.get()) throw new IllegalStateException("workspace write lease coordinator is closed");
        WorkspaceWriteClaimPort.WriteClaim waiting = claims.enqueue(
                "claim_" + UUID.randomUUID(), context.workspaceId(),
                context.threadId(), context.turnId(), processGeneration, clock.instant());
        Thread waitingThread = Thread.currentThread();
        boolean acquired = false;
        long startedAt = System.nanoTime();
        try (CancellationToken.Registration ignored = cancellation.onCancellation(
                () -> LockSupport.unpark(waitingThread))) {
            while (true) {
                cancellation.throwIfCancellationRequested();
                if (closed.get()) throw new CancellationException("workspace write lease manager is closing");
                Instant now = clock.instant();
                if (!now.isBefore(context.deadline())) {
                    long waitMillis = elapsedMillis(startedAt);
                    LOGGER.warn("event=workspace_write_lease_timeout wait_ms={}", waitMillis);
                    throw new LeaseFailure(LeaseFailure.Code.TIMEOUT);
                }
                Optional<WorkspaceWriteClaimPort.WriteClaim> candidate = claims.tryAcquire(
                        waiting.claimId(), waiting.fencingToken(), now);
                if (candidate.isPresent()) {
                    acquired = true;
                    LOGGER.info("event=workspace_write_lease_wait wait_ms={}", elapsedMillis(startedAt));
                    return held(candidate.orElseThrow(), cancellation);
                }
                awaitRetry(context.deadline(), now);
            }
        } finally {
            if (!acquired) claims.abandon(waiting.claimId(), waiting.fencingToken(), clock.instant());
        }
    }

    /**
     * HELD claim 周期续租；parent registration 的关闭权随返回 Lease 一并转移。
     * PMD 无法沿返回对象保存的 release 闭包识别该所有权，Lease.close 会先停 heartbeat 再关闭 registration。
     */
    @SuppressWarnings("PMD.CloseResource")
    private Lease held(WorkspaceWriteClaimPort.WriteClaim claim, CancellationToken parentCancellation) {
        AtomicBoolean ownershipLost = new AtomicBoolean();
        CancellationSource leaseCancellation = new CancellationSource();
        CancellationToken.Registration parentRegistration = parentCancellation.onCancellation(() ->
                leaseCancellation.cancel("parent_cancelled"));
        ScheduledFuture<?> heartbeat = heartbeats.scheduleWithFixedDelay(() -> {
            try {
                if (claims.heartbeat(claim.claimId(), claim.fencingToken(), clock.instant()).isEmpty()) {
                    loseOwnership(ownershipLost, leaseCancellation);
                }
            } catch (RuntimeException failure) {
                loseOwnership(ownershipLost, leaseCancellation);
            }
        }, heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
        return new Lease(leaseCancellation, ownershipLost, () -> {
            heartbeat.cancel(false);
            parentRegistration.close();
            Optional<WorkspaceWriteClaimPort.WriteClaim> released = claims.release(
                    claim.claimId(), claim.fencingToken(), clock.instant());
            if (ownershipLost.get() || released.isEmpty()) {
                throw new LeaseFailure(LeaseFailure.Code.OWNERSHIP_LOST);
            }
        });
    }

    /** Heartbeat 首次失败即取消派生 Tool token；重复失败不重复输出告警或扩大日志基数。 */
    private static void loseOwnership(AtomicBoolean ownershipLost, CancellationSource leaseCancellation) {
        if (ownershipLost.compareAndSet(false, true)) {
            leaseCancellation.cancel("write_lease_lost");
            LOGGER.warn("event=workspace_write_lease_lost ownership_lost=1");
        }
    }

    /** 指标使用单调时钟，只输出等待毫秒数且不携带 Workspace、Thread 或路径。 */
    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }

    /**
     * 每轮等待受 25ms 与绝对 Deadline 双重限制；park permit 不依赖公开 monitor，
     * 取消先于 park 到达也不会丢失唤醒，外层循环仍负责重新检查全部权威条件。
     */
    private static void awaitRetry(Instant deadline, Instant now) {
        long waitNanos = Math.min(RETRY_INTERVAL.toNanos(),
                Math.max(1L, Duration.between(now, deadline).toNanos()));
        LockSupport.parkNanos(waitNanos);
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            throw new CancellationException("workspace write lease wait interrupted");
        }
    }

    /** 关闭停止新 acquire 与 heartbeat；运行中 Tool 的 finally 仍同步 release。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true) || heartbeats == null) return;
        heartbeats.shutdownNow();
    }

    /** HELD owner 的单次关闭句柄，防止异常清理重入导致重复释放。 */
    static final class Lease implements AutoCloseable {
        private final CancellationToken cancellation;
        private final AtomicBoolean ownershipLost;
        private final Runnable release;
        private final AtomicBoolean closed = new AtomicBoolean();

        /** 绑定唯一持久释放动作。 */
        private Lease(CancellationToken cancellation, AtomicBoolean ownershipLost, Runnable release) {
            this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
            this.ownershipLost = Objects.requireNonNull(ownershipLost, "ownershipLost");
            this.release = Objects.requireNonNull(release, "release");
        }

        /** 未绑定生产 claims 的 package 测试使用无状态句柄。 */
        private static Lease noop(CancellationToken cancellation) {
            return new Lease(cancellation, new AtomicBoolean(), () -> { });
        }

        /** Tool 只接收与租约存活绑定的派生 token，Heartbeat 丢失可立即请求停止外部写。 */
        CancellationToken cancellation() {
            return cancellation;
        }

        /** Tool 返回与异常路径都复核 fencing 所有权，使丢租约不能被误报为普通取消或成功。 */
        void verifyOwnership() {
            if (ownershipLost.get()) throw new LeaseFailure(LeaseFailure.Code.OWNERSHIP_LOST);
        }

        /** 只执行一次 release，保留 fencing owner 的 exactly-once 终态。 */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) release.run();
        }
    }

    /** 写租约失败闭集供 Tool runner 映射稳定结果，不向模型泄露 SQLite 或路径细节。 */
    static final class LeaseFailure extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        private final Code code;

        /** 失败只保存稳定分类并关闭堆栈，避免高频租约竞争制造诊断开销。 */
        private LeaseFailure(Code code) {
            super("workspace write lease failed", null, false, false);
            this.code = Objects.requireNonNull(code, "code");
        }

        /** Runner 仅据闭集映射 Tool error code。 */
        Code code() {
            return code;
        }

        /** 超时与运行中失去 owner 的恢复语义不同，必须保持可穷举。 */
        enum Code {
            /** 等待 FIFO 写入资格超过 Turn 的原始绝对 Deadline，调用方可在新 Turn 重试。 */
            TIMEOUT,
            /** 已取得的 fencing owner 无法续租或释放，当前 Tool 结果必须失败关闭。 */
            OWNERSHIP_LOST
        }
    }
}
