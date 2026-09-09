// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证启动恢复对旧代际租约、evaluator 和 Tool 副作用采用失败关闭策略。 */
final class GoalStartupRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-09-06T02:00:00Z");

    /** PREPARED/只读结果可安全停止，已 STARTED 外部副作用必须标记 UNKNOWN 并要求人工恢复。 */
    @Test
    void reconcilesOldGenerationWithoutReplayingSideEffects() {
        Map<String, GoalModels.ToolAttempt> pending = new LinkedHashMap<>();
        pending.put("attempt_prepared", attempt("attempt_prepared", null, "plan_prepared", "run_prepared",
                false, GoalModels.ToolAttemptState.PREPARED));
        pending.put("attempt_read", attempt("attempt_read", null, "plan_read", "run_read",
                false, GoalModels.ToolAttemptState.STARTED));
        pending.put("attempt_plan_side_effect", attempt("attempt_plan_side_effect", null, "plan_unsafe",
                "run_unsafe", true, GoalModels.ToolAttemptState.STARTED));
        pending.put("attempt_goal_side_effect", attempt("attempt_goal_side_effect", "goal_one", null,
                "run_goal", true, GoalModels.ToolAttemptState.STARTED));
        List<String> started = new ArrayList<>();
        Map<String, GoalModels.ToolAttemptState> settled = new LinkedHashMap<>();
        Map<String, Boolean> planRecovery = new LinkedHashMap<>();
        AtomicReference<GoalRepository.Transition> goalTransition = new AtomicReference<>();
        AtomicBoolean evaluationRecovered = new AtomicBoolean();
        AtomicBoolean leaseReleased = new AtomicBoolean();
        GoalModels.Goal goal = new GoalModels.Goal("goal_one", "thread_one",
                GoalModels.OwnerKind.ROOT_THREAD, "持续目标", 1, GoalModels.GoalStatus.ACTIVE,
                GoalModels.GoalPhase.WORKING, 5, "run_goal", 0, 0, null, false, NOW, NOW);
        GoalRepository.EvaluationIntent evaluation = new GoalRepository.EvaluationIntent(
                "evaluation_old", "goal_one", 1, "run_goal", null, 3,
                "model", "provider", NOW);
        GoalRepository.ContinuationLease lease = new GoalRepository.ContinuationLease(
                "goal_one", "lease_old", 3, 1, "HELD", NOW, NOW, null);

        GoalRepository repository = repository((proxy, method, args) -> switch (method.getName()) {
            case "listHeldLeases" -> leaseReleased.get() ? List.of() : List.of(lease);
            case "releaseLease" -> {
                leaseReleased.set(true);
                yield Optional.of(lease);
            }
            case "listUnsettledEvaluations" -> evaluationRecovered.get() ? List.of() : List.of(evaluation);
            case "recoverEvaluation" -> {
                evaluationRecovered.set(true);
                yield Optional.of(goal);
            }
            case "listUnsettledToolAttempts" -> List.copyOf(pending.values());
            case "startToolAttempt" -> {
                started.add((String) args[0]);
                yield pending.get(args[0]);
            }
            case "settleToolAttempt" -> {
                GoalRepository.SettleToolAttempt command = (GoalRepository.SettleToolAttempt) args[0];
                settled.put(command.toolAttemptId(), command.state());
                yield pending.remove(command.toolAttemptId());
            }
            case "readPlanSnapshot" -> planSnapshot((String) args[0]);
            case "recoverPlanExecution" -> {
                GoalRepository.RecoverPlanExecution command = (GoalRepository.RecoverPlanExecution) args[0];
                planRecovery.put(command.planId(), command.unsafe());
                yield planSnapshot(command.planId()).plan();
            }
            case "findGoal" -> Optional.of(goal);
            case "transition" -> {
                GoalRepository.Transition command = (GoalRepository.Transition) args[0];
                goalTransition.set(command);
                yield new GoalModels.Goal(goal.goalId(), goal.ownerThreadId(), goal.ownerKind(), goal.objective(),
                        goal.goalDefinitionRevision(), command.status(), command.phase(), goal.revision() + 1,
                        goal.activeRunId(), 0, 0, null, command.recoveryRequired(), NOW, command.at());
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });

        new GoalStartupRecovery(repository, Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC), 9).recover();

        assertEquals(List.of("attempt_prepared"), started);
        assertEquals(GoalModels.ToolAttemptState.FAILED, settled.get("attempt_prepared"));
        assertEquals(GoalModels.ToolAttemptState.FAILED, settled.get("attempt_read"));
        assertEquals(GoalModels.ToolAttemptState.UNKNOWN, settled.get("attempt_plan_side_effect"));
        assertEquals(GoalModels.ToolAttemptState.UNKNOWN, settled.get("attempt_goal_side_effect"));
        assertFalse(planRecovery.get("plan_prepared"));
        assertFalse(planRecovery.get("plan_read"));
        assertTrue(planRecovery.get("plan_unsafe"));
        assertTrue(evaluationRecovered.get());
        assertTrue(leaseReleased.get());
        assertEquals(GoalModels.GoalStatus.PAUSED, goalTransition.get().status());
        assertEquals(GoalModels.GoalPhase.NEEDS_ATTENTION, goalTransition.get().phase());
        assertTrue(goalTransition.get().recoveryRequired());
    }

    /** Tool attempt fixture 显式冻结旧 generation，避免测试以当前 owner 误判恢复资格。 */
    private static GoalModels.ToolAttempt attempt(String id, String goalId, String planId, String runId,
                                                   boolean sideEffect, GoalModels.ToolAttemptState state) {
        Instant startedAt = state == GoalModels.ToolAttemptState.PREPARED ? null : NOW.plusSeconds(1);
        return new GoalModels.ToolAttempt(id, goalId, planId, goalId == null ? null : 1L,
                runId, planId == null ? null : "revision_" + planId, "step_one", 1,
                "turn_one", "call_" + id, 3, sideEffect, state, "a".repeat(64), null,
                NOW, startedAt, null);
    }

    /** recovery 只读取 Plan revision/run identity，最小 snapshot 保持领域构造约束真实。 */
    private static GoalModels.PlanSnapshot planSnapshot(String planId) {
        String revisionId = "revision_" + planId;
        String runId = switch (planId) {
            case "plan_prepared" -> "run_prepared";
            case "plan_read" -> "run_read";
            default -> "run_unsafe";
        };
        GoalModels.Plan plan = new GoalModels.Plan(planId, "thread_one", "恢复计划",
                GoalModels.PlanStatus.EXECUTING, 2, revisionId, runId, NOW, NOW);
        GoalModels.PlanDefinition definition = new GoalModels.PlanDefinition("恢复计划", List.of(), List.of(),
                List.of(), List.of(), List.of(new GoalModels.PlanStep("step_one", "实施", "执行", true,
                List.of())), List.of(new GoalModels.AcceptanceCriterion("criterion_one", "验收", true)),
                List.of(), List.of("运行恢复回归"));
        GoalModels.PlanRevision revision = new GoalModels.PlanRevision(revisionId, planId, 1,
                definition, "{}", "a".repeat(64), "USER_UI", NOW);
        GoalModels.PlanApproval approval = new GoalModels.PlanApproval("approval_" + planId,
                planId, revisionId, "a".repeat(64), NOW);
        return new GoalModels.PlanSnapshot(plan, null, revision, approval,
                List.of(new GoalModels.StepExecution("step_one", runId, GoalModels.StepStatus.RUNNING,
                        1, null, null, NOW, null)), 2);
    }

    /** 动态代理把恢复器的真实端口调用变成可断言事实，并拒绝未声明的依赖。 */
    private static GoalRepository repository(java.lang.reflect.InvocationHandler handler) {
        return (GoalRepository) Proxy.newProxyInstance(GoalRepository.class.getClassLoader(),
                new Class<?>[]{GoalRepository.class}, handler);
    }
}
