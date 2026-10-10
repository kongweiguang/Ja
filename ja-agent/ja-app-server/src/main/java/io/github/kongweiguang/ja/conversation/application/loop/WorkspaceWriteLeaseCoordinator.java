// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.agent.application.concurrent.CancellationSource;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationWorkspaceWriteClaimPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/**
 * 把 SQLite FIFO claim 转成单次外部 Tool 调用可持有的取消响应租约。
 */
public final class WorkspaceWriteLeaseCoordinator implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceWriteLeaseCoordinator.class);
    private static final Duration RETRY_INTERVAL = Duration.ofMillis(25);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(5);
    private final ConversationWorkspaceWriteClaimPort claims;
    private final Clock clock;
    private final long processGeneration;
    private final Duration heartbeatInterval;
    private final ScheduledExecutorService heartbeats;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleMonitor = new Object();
    private final Map<String, ActiveLease> activeLeases = new HashMap<>();
    private int activeAcquisitions;
    private boolean cleanupComplete;
    private Thread cleanupOwner;

    /**
     * 生产路径绑定持久 claims 与进程代际，heartbeat 只维护已取得的 HELD owner。
     */
    public WorkspaceWriteLeaseCoordinator(ConversationWorkspaceWriteClaimPort claims, Clock clock, long processGeneration) {
        this(claims, clock, processGeneration, HEARTBEAT_INTERVAL, heartbeatExecutor());
    }

    /**
     * 测试可注入更短续租周期，生产装配必须使用三参构造保持五秒租期。
     */
    WorkspaceWriteLeaseCoordinator(ConversationWorkspaceWriteClaimPort claims, Clock clock, long processGeneration,
                                   Duration heartbeatInterval) {
        this(claims, clock, processGeneration, heartbeatInterval, heartbeatExecutor());
    }

    /**
     * 注入拒绝或延迟调度器，确定性验证关闭竞态时 HELD claim 仍被释放。
     */
    WorkspaceWriteLeaseCoordinator(ConversationWorkspaceWriteClaimPort claims, Clock clock, long processGeneration,
                                   Duration heartbeatInterval, ScheduledExecutorService heartbeats) {
        if (processGeneration < 1) {
            throw new IllegalArgumentException("invalid process generation");
        }
        if (heartbeatInterval == null || heartbeatInterval.isZero() || heartbeatInterval.isNegative()) {
            throw new IllegalArgumentException("invalid heartbeat interval");
        }
        this.claims = Objects.requireNonNull(claims, "claims");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.processGeneration = processGeneration;
        this.heartbeatInterval = heartbeatInterval;
        this.heartbeats = Objects.requireNonNull(heartbeats, "heartbeats");
    }

    /**
     * 生产 heartbeat 使用命名守护线程，关闭时只在没有任何 HELD owner 后停止。
     */
    private static ScheduledExecutorService heartbeatExecutor() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("ja-workspace-write-heartbeat-", 0).factory());
    }

    /**
     * 保留关闭回调的原始 RuntimeException/Error 类型，不以清理封装覆盖失败信息。
     */
    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error errorFailure) {
            throw errorFailure;
        }
        throw new IllegalStateException("workspace write lease coordinator close failed", failure);
    }

    /**
     * Heartbeat 首次失败即取消派生 Tool token；重复失败不重复输出告警或扩大日志基数。
     */
    private static void loseOwnership(AtomicBoolean ownershipLost, CancellationSource leaseCancellation) {
        if (ownershipLost.compareAndSet(false, true)) {
            leaseCancellation.cancel("write_lease_lost");
            LOGGER.warn("event=workspace_write_lease_lost ownership_lost=1");
        }
    }

    /**
     * 指标使用单调时钟，只输出等待毫秒数且不携带 Workspace、Thread 或路径。
     */
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

    /**
     * 先追加 WAITING，再短暂等待 FIFO 队首；取消、关闭与 Deadline 都会 ABANDON，避免留下阻塞头。
     */
    public Lease acquire(JaTool.ExecutionContext context, CancellationToken cancellation) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(cancellation, "cancellation");
        beginAcquire();
        ConversationWorkspaceWriteClaimPort.WriteClaim waiting = null;
        boolean acquired = false;
        try {
            waiting = claims.enqueue("claim_" + UUID.randomUUID(), context.workspaceId(),
                    context.threadId(), context.turnId(), processGeneration, clock.instant());
            Thread waitingThread = Thread.currentThread();
            long startedAt = System.nanoTime();
            try (CancellationToken.Registration ignored = cancellation.onCancellation(
                    () -> LockSupport.unpark(waitingThread))) {
                while (true) {
                    cancellation.throwIfCancellationRequested();
                    if (closed.get()) {
                        throw new CancellationException("workspace write lease manager is closing");
                    }
                    Instant now = clock.instant();
                    if (!now.isBefore(context.deadline())) {
                        long waitMillis = elapsedMillis(startedAt);
                        LOGGER.warn("event=workspace_write_lease_timeout wait_ms={}", waitMillis);
                        throw new LeaseFailure(LeaseFailure.Code.TIMEOUT);
                    }
                    Optional<ConversationWorkspaceWriteClaimPort.WriteClaim> candidate = claims.tryAcquire(
                            waiting.claimId(), waiting.fencingToken(), now);
                    if (candidate.isPresent()) {
                        Lease lease = held(candidate.orElseThrow(), cancellation);
                        acquired = true;
                        LOGGER.info("event=workspace_write_lease_wait wait_ms={}", elapsedMillis(startedAt));
                        return lease;
                    }
                    awaitRetry(context.deadline(), now);
                }
            }
        } finally {
            try {
                if (!acquired && waiting != null) {
                    claims.abandon(waiting.claimId(), waiting.fencingToken(), clock.instant());
                }
            } finally {
                finishAcquire();
            }
        }
    }

    /**
     * HELD claim 周期续租；parent registration 的关闭权随返回 Lease 一并转移。
     * PMD 无法沿返回对象保存的 release 闭包识别该所有权，Lease.close 会先停 heartbeat 再关闭 registration。
     */
    @SuppressWarnings("PMD.CloseResource")
    private Lease held(ConversationWorkspaceWriteClaimPort.WriteClaim claim, CancellationToken parentCancellation) {
        AtomicBoolean ownershipLost = new AtomicBoolean();
        CancellationSource leaseCancellation = new CancellationSource();
        CancellationToken.Registration parentRegistration = parentCancellation.onCancellation(() ->
                leaseCancellation.cancel("parent_cancelled"));
        boolean registered = false;
        ScheduledFuture<?> heartbeat = null;
        try {
            synchronized (lifecycleMonitor) {
                if (closed.get()) {
                    throw new CancellationException("workspace write lease manager is closing");
                }
                activeLeases.put(claim.claimId(), new ActiveLease(leaseCancellation, Thread.currentThread()));
                registered = true;
            }
            heartbeat = heartbeats.scheduleWithFixedDelay(() -> {
                try {
                    if (claims.heartbeat(claim.claimId(), claim.fencingToken(), clock.instant()).isEmpty()) {
                        loseOwnership(ownershipLost, leaseCancellation);
                    }
                } catch (RuntimeException failure) {
                    loseOwnership(ownershipLost, leaseCancellation);
                }
            }, heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
            ScheduledFuture<?> scheduledHeartbeat = heartbeat;
            return new Lease(leaseCancellation, ownershipLost,
                    () -> releaseLease(claim, parentRegistration, scheduledHeartbeat,
                            leaseCancellation, ownershipLost));
        } catch (RuntimeException | Error failure) {
            if (heartbeat != null) {
                try {
                    heartbeat.cancel(false);
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            cleanupFailedLeaseStart(claim, parentRegistration, leaseCancellation, registered, failure);
            throw failure;
        }
    }

    /**
     * 在入队前登记 acquisition，使 close 能等待所有 WAITING claim 完成 ABANDON。
     */
    private void beginAcquire() {
        synchronized (lifecycleMonitor) {
            if (closed.get()) {
                throw new IllegalStateException("workspace write lease coordinator is closed");
            }
            activeAcquisitions++;
        }
    }

    /**
     * 通知 close 当前同步 acquisition 已完成，调度失败时也不会留下待清理 claim。
     */
    private void finishAcquire() {
        synchronized (lifecycleMonitor) {
            activeAcquisitions--;
            completeShutdownIfIdle();
            lifecycleMonitor.notifyAll();
        }
    }

    /**
     * 调度启动失败或关闭抢先时，释放刚取得的 fencing owner 并清理回调登记。
     */
    private void cleanupFailedLeaseStart(ConversationWorkspaceWriteClaimPort.WriteClaim claim,
                                         CancellationToken.Registration parentRegistration, CancellationSource leaseCancellation,
                                         boolean registered, Throwable failure) {
        try {
            parentRegistration.close();
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        try {
            Optional<ConversationWorkspaceWriteClaimPort.WriteClaim> released = claims.release(
                    claim.claimId(), claim.fencingToken(), clock.instant());
            if (released.isEmpty()) {
                failure.addSuppressed(new LeaseFailure(LeaseFailure.Code.OWNERSHIP_LOST));
            }
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        } finally {
            if (registered) {
                removeActiveLease(claim.claimId(), leaseCancellation);
            }
        }
    }

    /**
     * 从活跃集合移除已经释放的 lease，并唤醒等待关停的线程。
     */
    private void removeActiveLease(String claimId, CancellationSource leaseCancellation) {
        synchronized (lifecycleMonitor) {
            ActiveLease active = activeLeases.get(claimId);
            if (active != null && active.cancellation() == leaseCancellation) {
                activeLeases.remove(claimId);
            }
            completeShutdownIfIdle();
            lifecycleMonitor.notifyAll();
        }
    }

    /**
     * 关闭完成前保留 heartbeat；只在 admission 与全部 lease 都收口后停止后台线程。
     */
    private void completeShutdownIfIdle() {
        if (closed.get() && activeAcquisitions == 0 && activeLeases.isEmpty() && !cleanupComplete) {
            heartbeats.shutdown();
            cleanupComplete = true;
            lifecycleMonitor.notifyAll();
        }
    }

    /**
     * 关闭取消登记后仍必须尝试释放 DB claim，不能让注册回调异常遗留跨进程写锁。
     */
    private void releaseLease(ConversationWorkspaceWriteClaimPort.WriteClaim claim,
                              CancellationToken.Registration parentRegistration, ScheduledFuture<?> heartbeat,
                              CancellationSource leaseCancellation, AtomicBoolean ownershipLost) {
        Throwable failure = null;
        try {
            heartbeat.cancel(false);
        } catch (RuntimeException | Error heartbeatFailure) {
            failure = heartbeatFailure;
        }
        try {
            parentRegistration.close();
        } catch (RuntimeException | Error registrationFailure) {
            if (failure == null) {
                failure = registrationFailure;
            }
            else {
                failure.addSuppressed(registrationFailure);
            }
        }
        try {
            Optional<ConversationWorkspaceWriteClaimPort.WriteClaim> released = claims.release(
                    claim.claimId(), claim.fencingToken(), clock.instant());
            if (ownershipLost.get() || released.isEmpty()) {
                throw new LeaseFailure(LeaseFailure.Code.OWNERSHIP_LOST);
            }
        } catch (RuntimeException | Error releaseFailure) {
            if (failure == null) {
                failure = releaseFailure;
            }
            else {
                failure.addSuppressed(releaseFailure);
            }
        } finally {
            removeActiveLease(claim.claimId(), leaseCancellation);
        }
        if (failure != null) {
            rethrow(failure);
        }
    }

    /**
     * 先取消全部活跃 Tool，再等待 acquisition 放弃 WAITING claim、Lease 释放 HELD claim；不能提前停 heartbeat。
     */
    @Override
    public void close() {
        List<CancellationSource> toCancel;
        synchronized (lifecycleMonitor) {
            if (!closed.compareAndSet(false, true)) {
                if (cleanupOwner == Thread.currentThread()) {
                    return;
                }
                awaitCloseCompletion();
                return;
            }
            cleanupOwner = Thread.currentThread();
            toCancel = activeLeases.values().stream().map(ActiveLease::cancellation).toList();
        }
        Throwable cancellationFailure = null;
        for (CancellationSource cancellation : toCancel) {
            try {
                cancellation.cancel("workspace_write_lease_coordinator_closed");
            } catch (RuntimeException | Error failure) {
                if (cancellationFailure == null) {
                    cancellationFailure = failure;
                }
                else {
                    cancellationFailure.addSuppressed(failure);
                }
            }
        }
        boolean interrupted = false;
        synchronized (lifecycleMonitor) {
            while (activeAcquisitions > 0 || activeLeases.values().stream()
                    .anyMatch(active -> active.acquisitionThread() != Thread.currentThread())) {
                try {
                    lifecycleMonitor.wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            completeShutdownIfIdle();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (cancellationFailure != null) {
            rethrow(cancellationFailure);
        }
    }

    /**
     * 并发 close 调用等待唯一清理者完成，避免返回时仍有后台 heartbeat 或持久 claim。
     */
    private void awaitCloseCompletion() {
        boolean interrupted = false;
        while (!cleanupComplete) {
            try {
                lifecycleMonitor.wait();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 记录 acquisition 线程，close 在持有本 lease 的工具线程内调用时不能等自己退出。
     *
     * @param cancellation 取消控制
     * @param acquisitionThread 获取资源时所在的 Java 线程
     */
    private record ActiveLease(CancellationSource cancellation,
                               Thread acquisitionThread) {
    }

    /**
     * HELD owner 的单次关闭句柄，防止异常清理重入导致重复释放。
     */
    public static final class Lease implements AutoCloseable {
        private final CancellationToken cancellation;
        private final AtomicBoolean ownershipLost;
        private final Runnable release;
        private final AtomicBoolean closed = new AtomicBoolean();

        /**
         * 绑定唯一持久释放动作。
         */
        private Lease(CancellationToken cancellation, AtomicBoolean ownershipLost, Runnable release) {
            this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
            this.ownershipLost = Objects.requireNonNull(ownershipLost, "ownershipLost");
            this.release = Objects.requireNonNull(release, "release");
        }

        /**
         * Tool 只接收与租约存活绑定的派生 token，Heartbeat 丢失可立即请求停止外部写。
         */
        public CancellationToken cancellation() {
            return cancellation;
        }

        /**
         * Tool 返回与异常路径都复核 fencing 所有权，使丢租约不能被误报为普通取消或成功。
         */
        public void verifyOwnership() {
            if (ownershipLost.get()) {
                throw new LeaseFailure(LeaseFailure.Code.OWNERSHIP_LOST);
            }
        }

        /**
         * 只执行一次 release，保留 fencing owner 的 exactly-once 终态。
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release.run();
            }
        }
    }

    /**
     * 写租约失败闭集供 Tool runner 映射稳定结果，不向模型泄露 SQLite 或路径细节。
     */
    public static final class LeaseFailure extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final Code code;

        /**
         * 失败只保存稳定分类并关闭堆栈，避免高频租约竞争制造诊断开销。
         */
        private LeaseFailure(Code code) {
            super("workspace write lease failed", null, false, false);
            this.code = Objects.requireNonNull(code, "code");
        }

        /**
         * Runner 仅据闭集映射 Tool error code。
         */
        public Code code() {
            return code;
        }

        /**
         * 超时与运行中失去 owner 的恢复语义不同，必须保持可穷举。
         */
        public enum Code {
            /**
             * 等待 FIFO 写入资格超过本次 Tool IO Deadline，后续调用使用自己的新窗口。
             */
            TIMEOUT,
            /**
             * 已取得的 fencing owner 无法续租或释放，当前 Tool 结果必须失败关闭。
             */
            OWNERSHIP_LOST
        }
    }
}
