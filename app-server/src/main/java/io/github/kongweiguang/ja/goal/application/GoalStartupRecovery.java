// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 新进程按 generation 对账 Goal Tool 与 continuation lease，永不盲目重放副作用。 */
public final class GoalStartupRecovery {
    private static final int PAGE_SIZE = 256;
    private final GoalRepository goals;
    private final Clock clock;
    private final long processGeneration;

    /** 恢复器只持有持久端口和当前 generation，不依赖进程内 Turn/UI 状态。 */
    public GoalStartupRecovery(GoalRepository goals, Clock clock, long processGeneration) {
        this.goals = Objects.requireNonNull(goals, "goals");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (processGeneration < 1) throw new IllegalArgumentException("invalid process generation");
        this.processGeneration = processGeneration;
    }

    /** 有界循环直至旧事实清空；每个状态变化均先提交再处理下一条。 */
    public void recover() {
        recoverLeases();
        recoverEvaluations();
        while (true) {
            List<GoalModels.ToolAttempt> attempts = goals.listUnsettledToolAttempts(
                    processGeneration, PAGE_SIZE);
            if (attempts.isEmpty()) return;
            for (GoalModels.ToolAttempt attempt : attempts) recover(attempt);
        }
    }

    /** 旧 evaluator 无法证明 Provider 是否返回，统一失败关闭并按对应 Run 恢复为 attention。 */
    private void recoverEvaluations() {
        while (true) {
            List<GoalRepository.EvaluationIntent> evaluations = goals.listUnsettledEvaluations(
                    processGeneration, PAGE_SIZE);
            if (evaluations.isEmpty()) return;
            for (GoalRepository.EvaluationIntent evaluation : evaluations) {
                goals.recoverEvaluation(evaluation, processGeneration, clock.instant());
            }
        }
    }

    /** 旧 lease 只废弃，不推断旧 Turn 是否成功，也不自动创建新 continuation。 */
    private void recoverLeases() {
        while (true) {
            List<GoalRepository.ContinuationLease> leases = goals.listHeldLeases(
                    processGeneration, PAGE_SIZE);
            if (leases.isEmpty()) return;
            for (GoalRepository.ContinuationLease lease : leases) {
                goals.releaseLease(lease.goalId(), lease.leaseId(), lease.fencingToken(), true, clock.instant());
            }
        }
    }

    /** PREPARED 从未越过执行边界；STARTED 副作用只能 UNKNOWN 并要求人工恢复。 */
    private void recover(GoalModels.ToolAttempt attempt) {
        if (attempt.state() == GoalModels.ToolAttemptState.PREPARED) {
            goals.startToolAttempt(attempt.toolAttemptId(), clock.instant());
            settleFailed(attempt, "not-executed-before-restart");
            recoverPlan(attempt, false);
            return;
        }
        if (attempt.state() != GoalModels.ToolAttemptState.STARTED) return;
        if (!attempt.sideEffect()) {
            settleFailed(attempt, "read-result-lost-after-restart");
            recoverPlan(attempt, false);
            return;
        }
        goals.settleToolAttempt(new GoalRepository.SettleToolAttempt(attempt.toolAttemptId(),
                GoalModels.ToolAttemptState.UNKNOWN, null, null, clock.instant()));
        if (attempt.goalId() == null) {
            recoverPlan(attempt, true);
            return;
        }
        GoalModels.Goal goal = goals.findGoal(attempt.goalId()).orElse(null);
        if (goal == null || goal.status() == GoalModels.GoalStatus.ACHIEVED
                || goal.status() == GoalModels.GoalStatus.STOPPED) return;
        goals.transition(new GoalRepository.Transition(goal.goalId(), goal.revision(),
                GoalModels.GoalStatus.PAUSED, GoalModels.GoalPhase.NEEDS_ATTENTION, true,
                id("evt_"), "recovery:" + attempt.toolAttemptId(), clock.instant()));
    }

    /** Plan-only attempt 以 run identity 结算；无 Plan owner 时保持 Goal 恢复路径不变。 */
    private void recoverPlan(GoalModels.ToolAttempt attempt, boolean unsafe) {
        if (attempt.planId() == null || attempt.goalId() != null) return;
        GoalModels.Plan plan = goals.readPlanSnapshot(attempt.planId()).plan();
        goals.recoverPlanExecution(new GoalRepository.RecoverPlanExecution(attempt.planId(), plan.revision(),
                attempt.runId(), unsafe, id("evt_"), "recovery:" + attempt.toolAttemptId(), clock.instant()));
    }

    /** 安全失败摘要使用固定原因生成 digest，不伪造 Tool 返回内容。 */
    private void settleFailed(GoalModels.ToolAttempt attempt, String reason) {
        goals.settleToolAttempt(new GoalRepository.SettleToolAttempt(attempt.toolAttemptId(),
                GoalModels.ToolAttemptState.FAILED, GoalDigest.sha256(reason), null, clock.instant()));
    }

    /** 恢复事件使用 opaque identity，幂等性由 attempt identity 固定。 */
    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
