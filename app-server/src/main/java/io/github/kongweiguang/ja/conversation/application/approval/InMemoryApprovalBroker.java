// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.approval;

import io.github.kongweiguang.ja.conversation.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 以进程内原子状态实现有界审批登记、超时拒绝及 exactly-once 完成。
 */
public final class InMemoryApprovalBroker implements ApprovalBroker, AutoCloseable {
    private final Clock clock;
    private final int maximumTombstones;
    private final Duration tombstoneRetention;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final Semaphore pendingSlots;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private final ConcurrentHashMap<String, PendingApproval> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> tombstones = new ConcurrentHashMap<>();
    private final Object tombstoneLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicReference<DecisionStore> decisionStore =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** 只允许组合一次持久 owner，防止不同 Repository 竞争同一审批 Future。 */
    @Override
    public void bindDecisionStore(DecisionStore store) {
        Objects.requireNonNull(store, "store");
        DecisionStore current = decisionStore.get();
        if (current == store) return;
        if (!decisionStore.compareAndSet(null, store)) {
            throw new IllegalStateException("approval decision store is already bound");
        }
    }

    /**
     * 创建并独占单线程超时调度器，确保审批到期任务不会占用公共执行器。
     */
    public InMemoryApprovalBroker(Clock clock, int maximumPending, int maximumTombstones,
                                  Duration tombstoneRetention) {
        this(clock, maximumPending, maximumTombstones, tombstoneRetention,
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon().name("ja-approval-expiry").factory()), true);
    }

    /**
     * 使用外部调度器以支持确定性测试，关闭 Broker 时不会越权关闭调用方资源。
     */
    public InMemoryApprovalBroker(Clock clock, int maximumPending, int maximumTombstones,
                                  Duration tombstoneRetention, ScheduledExecutorService scheduler) {
        this(clock, maximumPending, maximumTombstones, tombstoneRetention, scheduler, false);
    }

    /**
     * 固定容量、墓碑保留期和调度器所有权，避免并发运行时改变防重放边界。
     */
    private InMemoryApprovalBroker(Clock clock, int maximumPending, int maximumTombstones,
                                   Duration tombstoneRetention, ScheduledExecutorService scheduler, boolean ownsScheduler) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maximumPending <= 0 || maximumPending > 65_536) {
            throw new IllegalArgumentException("maximumPending must be between 1 and 65536");
        }
        if (maximumTombstones <= 0 || maximumTombstones > 262_144) {
            throw new IllegalArgumentException("maximumTombstones must be between 1 and 262144");
        }
        this.tombstoneRetention = Objects.requireNonNull(tombstoneRetention, "tombstoneRetention");
        if (tombstoneRetention.isNegative() || tombstoneRetention.isZero()) {
            throw new IllegalArgumentException("tombstoneRetention must be positive");
        }
        this.maximumTombstones = maximumTombstones;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
        this.pendingSlots = new Semaphore(maximumPending);
    }

    /**
     * 原子占用待审批槽位并安装取消、超时回调；任一竞争胜出后都共享同一完成结果。
     */
    @Override
    @SuppressWarnings("PMD.CloseResource")
    public CompletionStage<Resolution> request(ApprovalRequest request, CancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        lifecycle.readLock().lock();
        try {
            ensureOpen();
            Instant now = clock.instant();
            purgeTombstones(now);
            if (!request.expiresAt().isAfter(now)) {
                DecisionStore durable = decisionStore.get();
                if (durable != null && durable.persist(
                        request.approvalId(), ApprovalDecision.DENY, request.expiresAt())) {
                    rememberTombstone(request.approvalId(), now);
                    return CompletableFuture.completedFuture(
                            new Resolution(request.approvalId(), ApprovalDecision.DENY, request.expiresAt()));
                }
                return CompletableFuture.failedFuture(
                        new IllegalStateException("expired approval decision was not persisted"));
            }
            if (tombstones.containsKey(request.approvalId())) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("approvalId was already used"));
            }
            if (!pendingSlots.tryAcquire()) {
                return CompletableFuture.failedFuture(new IllegalStateException("approval capacity exhausted"));
            }
            PendingApproval candidate = new PendingApproval(request);
            if (pending.putIfAbsent(request.approvalId(), candidate) != null) {
                pendingSlots.release();
                return CompletableFuture.failedFuture(new IllegalArgumentException("approvalId is already pending"));
            }
            try {
                CancellationToken.Registration registration = cancellationToken.onCancellation(
                        () -> finishDurably(candidate, ApprovalDecision.DENY,
                                boundedResolutionTime(candidate, clock.instant())));
                candidate.installRegistration(registration);
                long delayNanos = Math.max(0L, Duration.between(clock.instant(), request.expiresAt()).toNanos());
                candidate.installExpiry(scheduler.schedule(
                        () -> finishDurably(candidate, ApprovalDecision.DENY, candidate.request.expiresAt()),
                        delayNanos, TimeUnit.NANOSECONDS));
                if (cancellationToken.isCancellationRequested()) {
                    finishDurably(candidate, ApprovalDecision.DENY,
                            boundedResolutionTime(candidate, clock.instant()));
                }
                return candidate.result;
            } catch (RuntimeException exception) {
                if (finishDurably(candidate, ApprovalDecision.DENY,
                        boundedResolutionTime(candidate, clock.instant()))) {
                    return CompletableFuture.failedFuture(exception);
                }
                return candidate.result;
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /**
     * 仅接受当前待审批且未过期的关联响应，迟到或未来时间回执统一按拒绝收口。
     */
    @Override
    public boolean resolve(String approvalId, ApprovalDecision response, Instant resolvedAt) {
        Objects.requireNonNull(approvalId, "approvalId");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(resolvedAt, "resolvedAt");
        PendingApproval approval = pending.get(approvalId);
        if (approval == null) {
            return false;
        }
        Instant now = clock.instant();
        if (!now.isBefore(approval.request.expiresAt())
            || !resolvedAt.isBefore(approval.request.expiresAt())
            || resolvedAt.isAfter(now)) {
            finishDurably(approval, ApprovalDecision.DENY, approval.request.expiresAt());
            return false;
        }
        return finishDurably(approval, response, resolvedAt);
    }

    /**
     * 将指定 Turn 的全部待审批项幂等拒绝，阻断取消后迟到的 Tool 授权。
     */
    @Override
    public void cancelTurn(String threadId, String turnId, String reason) {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(reason, "reason");
        Instant now = clock.instant();
        pending.values().stream()
                .filter(approval -> approval.request.permission().threadId().equals(threadId)
                                    && approval.request.permission().turnId().equals(turnId))
                .forEach(approval -> finishDurably(approval, ApprovalDecision.DENY,
                        boundedResolutionTime(approval, now)));
    }

    /**
     * 返回当前占用容量的审批数，用于验证并发上限与释放是否配对。
     */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * 在写锁内停止新登记并拒绝全部在途审批；仅关闭自身创建的调度器。
     */
    @Override
    public void close() {
        lifecycle.writeLock().lock();
        try {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                Instant now = clock.instant();
                for (PendingApproval approval : pending.values()) {
                    if (!finishDurably(approval, ApprovalDecision.DENY,
                            boundedResolutionTime(approval, now))) {
                        throw new IllegalStateException("approval shutdown decision was not persisted");
                    }
                }
                if (ownsScheduler) {
                    scheduler.shutdownNow();
                }
                tombstones.clear();
            } catch (RuntimeException failure) {
                /*
                 * SQLite 未确认前不能永久关闭内存 owner；恢复 open 位让应用关闭协调器可重试，
                 * 已成功完成的审批仍由墓碑保持 exactly-once。
                 */
                closed.set(false);
                throw failure;
            }
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    /**
     * 通过完成位选出唯一赢家，并按墓碑、索引、槽位、回调、Future 的顺序释放所有权。
     */
    private boolean finish(PendingApproval approval, ApprovalDecision response, Instant resolvedAt) {
        if (!approval.finished.compareAndSet(false, true)) {
            return false;
        }
        rememberTombstone(approval.request.approvalId(), clock.instant());
        pending.remove(approval.request.approvalId(), approval);
        pendingSlots.release();
        approval.disposeRegistrations();
        approval.result.complete(new Resolution(approval.request.approvalId(), response, resolvedAt));
        return true;
    }

    /** 自动拒绝与外部响应共享 persist-before-wake；失败保留 waiter 供恢复或后续重试。 */
    private boolean finishDurably(PendingApproval approval, ApprovalDecision response, Instant resolvedAt) {
        if (!approval.resolving.compareAndSet(false, true)) return false;
        try {
            if (approval.finished.get()) return false;
            DecisionStore durable = decisionStore.get();
            return durable != null
                    && durable.persist(approval.request.approvalId(), response, resolvedAt)
                    && finish(approval, response, resolvedAt);
        } finally {
            approval.resolving.set(false);
        }
    }

    /** 到期后的本地取消使用原 expiry 作为权威时刻，使持久拒绝仍满足审批时间约束。 */
    private static Instant boundedResolutionTime(PendingApproval approval, Instant candidate) {
        return candidate.isAfter(approval.request.expiresAt()) ? approval.request.expiresAt() : candidate;
    }

    /**
     * 保留已消费审批 ID 的有界墓碑，容量满时淘汰最早到期项以阻止短期重放。
     */
    private void rememberTombstone(String approvalId, Instant resolvedAt) {
        synchronized (tombstoneLock) {
            purgeTombstones(clock.instant());
            if (tombstones.size() >= maximumTombstones) {
                tombstones.entrySet().stream()
                        .min(java.util.Map.Entry.comparingByValue())
                        .ifPresent(entry -> tombstones.remove(entry.getKey(), entry.getValue()));
            }
            tombstones.put(approvalId, resolvedAt.plus(tombstoneRetention));
        }
    }

    /**
     * 清除到期墓碑，使防重放窗口受配置保留期和最大容量共同约束。
     */
    private void purgeTombstones(Instant now) {
        synchronized (tombstoneLock) {
            tombstones.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        }
    }

    /**
     * 在生命周期读锁内拒绝关闭后的登记，避免请求越过关闭快照。
     */
    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("approval broker is closed");
        }
    }

    /**
     * 聚合单个审批的完成位、取消登记和超时任务，所有资源由同一完成赢家释放。
     */
    private static final class PendingApproval {
        private final ApprovalRequest request;
        private final CompletableFuture<Resolution> result = new CompletableFuture<>();
        private final AtomicBoolean resolving = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile CancellationToken.Registration cancellationRegistration;
        private volatile ScheduledFuture<?> expiry;

        /**
         * 为冻结的审批请求创建唯一 Future，后续竞争只能完成该实例一次。
         */
        private PendingApproval(ApprovalRequest request) {
            this.request = request;
        }

        /**
         * 安装取消登记；若完成竞争已结束，则立即注销迟到回调以免泄漏引用。
         */
        private void installRegistration(CancellationToken.Registration registration) {
            this.cancellationRegistration = Objects.requireNonNull(registration, "registration");
            if (finished.get()) {
                registration.close();
            }
        }

        /**
         * 安装到期任务；若审批已完成，则立即取消迟到任务以保持 exactly-once。
         */
        private void installExpiry(ScheduledFuture<?> expiry) {
            this.expiry = Objects.requireNonNull(expiry, "expiry");
            if (finished.get()) {
                expiry.cancel(false);
            }
        }

        /**
         * 释放取消回调与超时任务，确保审批完成后不再触发第二条完成路径。
         */
        @SuppressWarnings("PMD.CloseResource")
        private void disposeRegistrations() {
            CancellationToken.Registration registration = cancellationRegistration;
            if (registration != null) {
                registration.close();
            }
            ScheduledFuture<?> scheduled = expiry;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }
    }
}
