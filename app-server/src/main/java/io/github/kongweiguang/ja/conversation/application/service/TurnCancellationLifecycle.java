// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.service;

import io.github.kongweiguang.ja.conversation.application.cancellation.CancellationCoordinator;
import io.github.kongweiguang.ja.conversation.application.loop.TurnQueue;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.foundation.error.StorageException;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * 协调取消 CAS、Token 发布、Lane 移除和清理屏障，确保终态只观察已提交的取消事实。
 */
final class TurnCancellationLifecycle {
    private final ConversationRepository store;
    private final CancellationCoordinator cancellations;
    private final Clock clock;
    private final TurnQueue queue;
    private final Map<TurnService.Key, TurnOwnership> active;
    private final Map<TurnService.Key, CompletableFuture<CancellationCoordinator.CancelOutcome>> barriers;
    private final TurnQueueCancellationSettlement queuedSettlement;

    /**
     * 注入存储、队列与活动表的同一运行期实例，使取消路径不会产生第二套所有权。
     */
    TurnCancellationLifecycle(ConversationRepository store, CancellationCoordinator cancellations, Clock clock,
                              TurnQueue queue, Map<TurnService.Key, TurnOwnership> active,
                              Map<TurnService.Key,
                                      CompletableFuture<CancellationCoordinator.CancelOutcome>> barriers,
                              TurnQueueCancellationSettlement queuedSettlement) {
        this.store = Objects.requireNonNull(store, "store");
        this.cancellations = Objects.requireNonNull(cancellations, "cancellations");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.active = Objects.requireNonNull(active, "active");
        this.barriers = Objects.requireNonNull(barriers, "barriers");
        this.queuedSettlement = Objects.requireNonNull(queuedSettlement, "queuedSettlement");
    }

    /**
     * 以调用方 revision 认领持久取消 CAS，再发布进程内 Token；重复调用复用首次 claim。
     */
    TurnUseCase.CancelResult cancel(String turnId, long expectedThreadRevision) {
        return cancel(turnId, expectedThreadRevision, "user cancelled");
    }

    /**
     * 受控生命周期动作复用取消 CAS，但保留调用方原因，使 Plan pause 能在取消收口时
     * 进入可恢复 SUSPENDED，而普通用户取消仍走不可逆 CANCELLED。
     */
    TurnUseCase.CancelResult cancel(String turnId, long expectedThreadRevision, String reason) {
        String normalized = requireTurnId(turnId);
        ActiveEntry entry = findActive(normalized);
        if (entry == null) {
            throw TurnUseCase.TurnCancellationException.of(TurnUseCase.CancelFailure.TURN_NOT_FOUND);
        }
        TurnOwnership turn = entry.turn();
        ConversationRepository.CancellationClaim claim = turn.cancellationClaim.get();
        if (claim == null || turn.cancellationExpectedThreadRevision != expectedThreadRevision) {
            claim = claimCancellation(entry.key(), expectedThreadRevision, reason);
            rememberCancellation(turn, expectedThreadRevision, claim);
        }
        dispatchCancellation(entry.key(), turn, reason);
        return new TurnUseCase.CancelResult(claim.accepted(), normalized, claim.status(),
                claim.threadRevision());
    }

    /**
     * 为 Deadline 或关闭发起尽力取消，只有存储 CAS 接受后才允许发布本地 Token。
     */
    void requestCancellation(TurnService.Key key, TurnOwnership turn, String reason) {
        if (turn.cancelRequested.get()) return;
        ConversationRepository.TurnSnapshot snapshot;
        try {
            snapshot = store.findTurn(key.threadId(), key.turnId()).orElse(null);
        } catch (RuntimeException failure) {
            return;
        }
        if (snapshot == null || snapshot.state().terminal()) return;
        try {
            ConversationRepository.CancellationClaim claim = turn.cancellationClaim.get();
            if (claim == null) {
                claim = claimCancellation(key, snapshot.threadRevision(), reason);
                rememberCancellation(turn, snapshot.threadRevision(), claim);
            }
            if (claim.accepted()) dispatchCancellation(key, turn, reason);
        } catch (RuntimeException failure) {
            Objects.requireNonNull(failure, "failure");
            // 终态与 CAS 竞争时以 SQLite 为权威，绝不发布未提交 Token。
        }
    }

    /**
     * Turn 释放所有权后移除清理屏障引用，避免活动索引长期保留已完成 Future。
     */
    void clearBarrier(TurnService.Key key) {
        barriers.remove(key);
    }

    /**
     * 终态提交前等待本 Turn 的清理屏障，并把失败记录为取消欠账而不丢失终态机会。
     */
    void awaitBarrier(TurnService.Key key, TurnOwnership turn) {
        CompletableFuture<CancellationCoordinator.CancelOutcome> barrier = barriers.get(key);
        if (barrier == null) return;
        try {
            barrier.join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            turn.cancellationDebt.compareAndSet(null, cause);
        }
    }

    /**
     * 缓存首次 revision 与持久 claim，后续重试不得用不同 revision 覆盖已确认事实。
     */
    private static void rememberCancellation(TurnOwnership turn, long expectedThreadRevision,
                                             ConversationRepository.CancellationClaim claim) {
        turn.cancellationExpectedThreadRevision = expectedThreadRevision;
        turn.cancellationClaim.compareAndSet(null, claim);
    }

    /**
     * 由原子位选出唯一分派者，先移除排队任务，再发布 Scope 取消并连接共享屏障。
     */
    private void dispatchCancellation(TurnService.Key key, TurnOwnership turn, String reason) {
        if (!turn.cancelRequested.compareAndSet(false, true)) return;
        CompletableFuture<CancellationCoordinator.CancelOutcome> barrier = new CompletableFuture<>();
        barriers.put(key, barrier);
        boolean queued;
        try {
            queued = queue.cancelQueued(key.threadId(), key.turnId(), reason);
        } catch (RuntimeException failure) {
            turn.cancellationDebt.compareAndSet(null, failure);
            queued = false;
        }
        if (turn.deadline != null) turn.deadline.cancel(false);
        CompletionStage<CancellationCoordinator.CancelOutcome> cancellation;
        try {
            cancellation = Objects.requireNonNull(
                    cancellations.cancel(key.threadId(), key.turnId(), reason), "cancellation stage");
        } catch (Throwable failure) {
            // Turn 所有的 Scope 是唯一安全的本地降级边界；Completion 暴露分派欠账，
            // 而不是允许终态持久化与清理发生竞争。
            turn.cancellationDebt.compareAndSet(null, failure);
            CompletableFuture<CancellationCoordinator.CancelOutcome> fallback = new CompletableFuture<>();
            try {
                turn.cancellation.requestCancellation(reason);
                turn.cancellation.cleanupCompletion().whenComplete((ignored, cleanupFailure) -> {
                    if (cleanupFailure == null) {
                        fallback.complete(CancellationCoordinator.CancelOutcome.REQUESTED);
                    } else {
                        fallback.completeExceptionally(cleanupFailure);
                    }
                });
            } catch (Throwable fallbackFailure) {
                if (fallbackFailure != failure) failure.addSuppressed(fallbackFailure);
                fallback.completeExceptionally(fallbackFailure);
            }
            turn.cancelRequested.set(false);
            cancellation = fallback;
        }
        boolean queuedForSettlement = queued;
        cancellation.whenComplete((outcome, failure) -> {
            Throwable barrierFailure = cancellationFailure(outcome, failure);
            if (barrierFailure != null) turn.cancellationDebt.compareAndSet(null, barrierFailure);
            if (outcome == CancellationCoordinator.CancelOutcome.NOT_FOUND) {
                publishLocalCancellationAfterCoordinatorLoss(turn, reason).whenComplete(
                        (ignored, localFailure) -> {
                            Throwable effectiveFailure = localFailure == null
                                    ? barrierFailure : localFailure;
                            if (effectiveFailure != null) {
                                turn.cancellationDebt.compareAndSet(null, effectiveFailure);
                            }
                            finishBarrier(key, turn, barrier, outcome, effectiveFailure,
                                    queuedForSettlement);
                        });
            } else {
                finishBarrier(key, turn, barrier, outcome, barrierFailure, queuedForSettlement);
            }
        });
    }

    /**
     * 完成取消屏障；若 Turn 仍在队列中，则转交串行终态 owner 后再解除屏障索引。
     */
    private void finishBarrier(TurnService.Key key, TurnOwnership turn,
                               CompletableFuture<CancellationCoordinator.CancelOutcome> barrier,
                               CancellationCoordinator.CancelOutcome outcome,
                               Throwable barrierFailure, boolean queued) {
        if (barrierFailure == null) barrier.complete(outcome);
        else barrier.completeExceptionally(barrierFailure);
        if (queued) {
            try {
                queuedSettlement.schedule(key, turn, barrierFailure);
            } finally {
                barriers.remove(key, barrier);
            }
        }
    }

    /**
     * 协调器索引丢失时退回 Turn 自有 Scope，仍把本地回调清理纳入完成屏障。
     */
    private static CompletionStage<Void> publishLocalCancellationAfterCoordinatorLoss(
            TurnOwnership turn, String reason) {
        try {
            turn.cancellation.requestCancellation(reason);
            return Objects.requireNonNull(turn.cancellation.cleanupCompletion(), "cleanup stage");
        } catch (Throwable fallbackFailure) {
            turn.cancellationDebt.compareAndSet(null, fallbackFailure);
            return CompletableFuture.failedFuture(fallbackFailure);
        }
    }

    /**
     * 把异步失败、缺失 Scope 和空结果规范化为明确的取消欠账。
     */
    private static Throwable cancellationFailure(CancellationCoordinator.CancelOutcome outcome,
                                                 Throwable failure) {
        if (failure != null) {
            if (failure instanceof CompletionException completion && completion.getCause() != null) {
                return completion.getCause();
            }
            return failure;
        }
        if (outcome == CancellationCoordinator.CancelOutcome.NOT_FOUND) {
            return new IllegalStateException("cancellation scope unavailable");
        }
        if (outcome == null) return new IllegalStateException("cancellation outcome missing");
        return null;
    }

    /**
     * 把 conversation 端口与生产 Repository 的稳定取消类别映射为入站用例错误；仅 CAS 与缺失
     * 可以越过应用边界，其他存储故障保持原样，避免 transport 把不可用误判为用户竞争。
     */
    private ConversationRepository.CancellationClaim claimCancellation(TurnService.Key key,
                                                                       long expectedThreadRevision,
                                                                       String reason) {
        try {
            return Objects.requireNonNull(store.claimCancellation(key.threadId(), key.turnId(),
                    expectedThreadRevision, reason, clock.instant()), "cancellation claim");
        } catch (ConversationRepository.CancellationClaimException failure) {
            throw switch (failure.failure()) {
                case NOT_FOUND -> TurnUseCase.TurnCancellationException.of(
                        TurnUseCase.CancelFailure.TURN_NOT_FOUND);
                case CONFLICT -> TurnUseCase.TurnCancellationException.of(
                        TurnUseCase.CancelFailure.CONFLICT);
                case UNAVAILABLE -> failure;
            };
        } catch (StorageException failure) {
            if (failure.code() == StorageException.Code.CAS_CONFLICT) {
                throw TurnUseCase.TurnCancellationException.of(TurnUseCase.CancelFailure.CONFLICT);
            }
            if (failure.code() == StorageException.Code.NOT_FOUND) {
                throw TurnUseCase.TurnCancellationException.of(TurnUseCase.CancelFailure.TURN_NOT_FOUND);
            }
            throw failure;
        }
    }

    /**
     * 在活动所有权表中按全局 Turn ID 定位其复合键与资源集合。
     */
    private ActiveEntry findActive(String turnId) {
        for (Map.Entry<TurnService.Key, TurnOwnership> entry : active.entrySet()) {
            if (entry.getKey().turnId().equals(turnId)) {
                return new ActiveEntry(entry.getKey(), entry.getValue());
            }
        }
        return null;
    }

    /**
     * 校验公开取消入口的 Turn ID 形态，避免无界或模糊标识触发全表扫描。
     */
    private static String requireTurnId(String value) {
        if (value == null || !value.startsWith("turn_") || value.length() > 128
            || !value.substring(5).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid turnId");
        }
        return value;
    }

    /**
     * 保持活动表复合键与 Turn 所有权的同一快照，避免二次查询发生竞态。
     */
    private record ActiveEntry(TurnService.Key key, TurnOwnership turn) {
        /**
         * 拒绝缺失的键或所有权，确保取消操作始终针对完整活动快照。
         */
        private ActiveEntry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(turn, "turn");
        }
    }
}
