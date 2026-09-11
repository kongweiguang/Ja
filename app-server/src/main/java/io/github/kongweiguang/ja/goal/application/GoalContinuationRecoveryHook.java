// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * 重启后为仍可证明的 Goal Interaction 重新取得 fencing lease；恢复只登记显式回答回调，
 * 不在启动线程自动恢复模型或重放任何 Tool。
 */
public final class GoalContinuationRecoveryHook implements GoalStartupRecovery.ContinuationRecoveryHook {
    private final GoalRepository goals;
    private final GoalContinuationCoordinator.ContinuationTurnPort turns;
    private final Clock clock;
    private final long processGeneration;
    private final java.util.function.BiConsumer<GoalContinuationCoordinator.ContinuationRequest, Boolean> phase;

    /** 组合根注入真实 Goal repository、Turn adapter 和当前 process generation。 */
    public GoalContinuationRecoveryHook(GoalRepository goals, GoalContinuationCoordinator.ContinuationTurnPort turns,
                                       Clock clock, long processGeneration,
                                       java.util.function.BiConsumer<GoalContinuationCoordinator.ContinuationRequest, Boolean> phase) {
        this.goals = Objects.requireNonNull(goals, "goals");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (processGeneration < 1) throw new IllegalArgumentException("invalid process generation");
        this.processGeneration = processGeneration;
        this.phase = Objects.requireNonNull(phase, "phase");
    }

    /**
     * 旧 lease 已提交 ABANDONED 后重新读取完整候选并 CAS 申请新 fence；竞争失败表示其他进程
     * 已经取得恢复权，不创建第二条 lease，也不修改不可变 Turn context。
     */
    @Override
    public void recover(GoalRepository.ContinuationLease abandonedLease) {
        Objects.requireNonNull(abandonedLease, "abandonedLease");
        if (!"ABANDONED".equals(abandonedLease.state())) return;
        GoalRepository.ContinuationRecoveryCandidate candidate = goals
                .findContinuationRecovery(abandonedLease.goalId(), activeRun(abandonedLease.goalId()))
                .orElse(null);
        if (candidate == null) return;
        Optional<GoalRepository.ContinuationLease> acquired = goals.tryAcquireLease(
                new GoalRepository.AcquireLease(candidate.goalId(), id("goallease_recovery_"),
                        processGeneration, clock.instant()));
        if (acquired.isEmpty()) return;
        GoalRepository.ContinuationLease lease = acquired.orElseThrow();
        GoalContinuationCoordinator.ContinuationRequest request = new GoalContinuationCoordinator.ContinuationRequest(
                candidate.goalId(), candidate.ownerThreadId(), candidate.binding().planId(),
                candidate.binding().planRevisionId(), candidate.runId(), candidate.turnId(), lease.fencingToken());
        if (candidate.interactionStatus() == InteractionStatus.PENDING
                || candidate.interactionStatus() == InteractionStatus.ANSWERED) {
            try {
                turns.registerResumeContinuation(request, completion -> observeResume(request, lease, completion));
            } catch (RuntimeException failure) {
                goals.releaseLease(request.goalId(), lease.leaseId(), lease.fencingToken(), true, clock.instant());
                throw failure;
            }
        }
        // 两种状态都只登记回调；答案或用户的继续操作才触发模型，不在启动时隐式执行。
    }

    /** 通过 Goal 快照取得当前 active run，避免用旧 lease 的历史 generation 作为 run identity。 */
    private String activeRun(String goalId) {
        return goals.findGoal(goalId).map(GoalModels.Goal::activeRunId)
                .orElseThrow(() -> new IllegalStateException("Goal recovery owner is unavailable"));
    }

    /** Turn 每次恢复后都重新观察；再次 SUSPENDED 表示下一批问题，不能提前释放同一 lease。 */
    private void observeResume(GoalContinuationCoordinator.ContinuationRequest request,
                               GoalRepository.ContinuationLease lease,
                               CompletionStage<?> completion) {
        phase.accept(request, false);
        completion.whenComplete((ignored, failure) -> {
            if (GoalContinuationCoordinator.isSuspended(failure)) {
                phase.accept(request, true);
                try {
                    turns.registerResumeContinuation(request, next -> observeResume(request, lease, next));
                } catch (RuntimeException registrationFailure) {
                    goals.releaseLease(request.goalId(), lease.leaseId(), lease.fencingToken(), true,
                            clock.instant());
                    turns.settled(request);
                }
                return;
            }
            goals.releaseLease(request.goalId(), lease.leaseId(), lease.fencingToken(), failure != null,
                    clock.instant());
            turns.settled(request);
        });
    }

    /** 恢复 lease identity 使用随机值，单调 fencing 仍由 SQLite 分配。 */
    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
