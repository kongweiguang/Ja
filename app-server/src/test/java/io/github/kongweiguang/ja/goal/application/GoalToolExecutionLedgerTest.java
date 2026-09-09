// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.port.out.GoalToolExecutionPort;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 conversation Tool ledger 只按内部 Turn origin 绑定精确 Goal/Plan run。 */
final class GoalToolExecutionLedgerTest {
    private static final Instant NOW = Instant.parse("2026-09-06T01:00:00Z");

    /** 同一 Thread 同时有活动 Goal 与 standalone Plan 时，两个内部 origin 不能互相串账。 */
    @Test
    void isolatesGoalAndPlanAttemptsByTurnOrigin() {
        GoalModels.Goal goal = new GoalModels.Goal("goal_one", "thread_one",
                GoalModels.OwnerKind.ROOT_THREAD, "持续目标", 1, GoalModels.GoalStatus.ACTIVE,
                GoalModels.GoalPhase.WORKING, 4, "run_goal", 0, 0, null, false, NOW, NOW);
        GoalModels.GoalPlanLink link = new GoalModels.GoalPlanLink("goal_one", "plan_goal",
                "revision_goal", "a".repeat(64), 1, NOW);
        GoalModels.Plan goalPlan = plan("plan_goal", GoalModels.PlanStatus.APPROVED,
                "revision_goal", null);
        GoalModels.Plan standalone = plan("plan_standalone", GoalModels.PlanStatus.EXECUTING,
                "revision_standalone", "run_plan");
        GoalModels.PlanSnapshot goalPlanSnapshot = snapshot(goalPlan, "revision_goal", "run_goal");
        GoalModels.PlanSnapshot standaloneSnapshot = snapshot(
                standalone, "revision_standalone", "run_plan");
        GoalModels.GoalSnapshot goalSnapshot = new GoalModels.GoalSnapshot(goal,
                new GoalModels.GoalDefinition("goal_one", 1, "持续目标", List.of(), NOW),
                link, "step_one", 0, 1, null, null, null, null, null, 7);
        List<GoalModels.ToolAttempt> prepared = new ArrayList<>();
        List<GoalModels.ToolAttemptState> settled = new ArrayList<>();
        GoalRepository repository = repository((proxy, method, args) -> switch (method.getName()) {
            case "findInternalTurnBinding" -> switch ((String) args[0]) {
                case "turn_goal" -> Optional.of(new GoalRepository.InternalTurnBinding("turn_goal",
                        "GOAL_CONTINUATION", "goal_one", "plan_goal", "run_goal", 1L,
                        "revision_goal", "a".repeat(64), 7L));
                case "turn_plan" -> Optional.of(new GoalRepository.InternalTurnBinding("turn_plan",
                        "PLAN_EXECUTION", null, "plan_standalone", "run_plan", null,
                        "revision_standalone", "a".repeat(64), null));
                case "turn_stale" -> Optional.of(new GoalRepository.InternalTurnBinding("turn_stale",
                        "GOAL_CONTINUATION", "goal_one", "plan_goal", "run_replaced", 1L,
                        "revision_goal", "a".repeat(64), 6L));
                default -> Optional.empty();
            };
            case "findGoal" -> Optional.of(goal);
            case "readSnapshot" -> goalSnapshot;
            case "readPlanSnapshot" -> args[0].equals("plan_goal")
                    ? goalPlanSnapshot : standaloneSnapshot;
            case "prepareToolAttempt" -> {
                GoalModels.ToolAttempt attempt = ((GoalRepository.PrepareToolAttempt) args[0]).attempt();
                prepared.add(attempt);
                yield attempt;
            }
            case "startToolAttempt" -> prepared.stream()
                    .filter(item -> item.toolAttemptId().equals(args[0])).findFirst().orElseThrow();
            case "settleToolAttempt" -> {
                GoalRepository.SettleToolAttempt command = (GoalRepository.SettleToolAttempt) args[0];
                settled.add(command.state());
                yield prepared.stream().filter(item -> item.toolAttemptId().equals(command.toolAttemptId()))
                        .findFirst().orElseThrow();
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
        GoalToolExecutionLedger ledger = new GoalToolExecutionLedger(repository,
                Clock.fixed(NOW, ZoneOffset.UTC), 9);

        assertTrue(ledger.prepare(request(TurnOrigin.USER, "call_user")).isEmpty());
        assertTrue(ledger.prepare(request(TurnOrigin.GOAL_CONTINUATION, "call_stale")).isEmpty());
        GoalToolExecutionPort.Attempt goalAttempt = ledger.prepare(
                request(TurnOrigin.GOAL_CONTINUATION, "call_goal")).orElseThrow();
        GoalToolExecutionPort.Attempt planAttempt = ledger.prepare(
                request(TurnOrigin.PLAN_EXECUTION, "call_plan")).orElseThrow();
        ledger.start(goalAttempt, NOW.plusSeconds(1));
        ledger.settle(planAttempt, new GoalToolExecutionPort.Settlement(
                ToolOutcome.SUCCEEDED, "ok", null, NOW.plusSeconds(2)));

        assertEquals(2, prepared.size());
        GoalModels.ToolAttempt goalRecord = prepared.get(0);
        assertEquals("goal_one", goalRecord.goalId());
        assertEquals("plan_goal", goalRecord.planId());
        assertEquals("run_goal", goalRecord.runId());
        GoalModels.ToolAttempt planRecord = prepared.get(1);
        assertNull(planRecord.goalId());
        assertEquals("plan_standalone", planRecord.planId());
        assertEquals("run_plan", planRecord.runId());
        assertFalse(goalAttempt.attemptId().equals(planAttempt.attemptId()));
        assertEquals(List.of(GoalModels.ToolAttemptState.SUCCEEDED), settled);
    }

    /** Tool 事务原子完成 Goal 后才唤醒观察者，通知不得早于 SQLite 提交。 */
    @Test
    void publishesGoalIdentityAfterToolSettlementCompletesGoal() {
        GoalModels.Goal achieved = new GoalModels.Goal("goal_one", "thread_one",
                GoalModels.OwnerKind.ROOT_THREAD, "持续目标", 1, GoalModels.GoalStatus.ACHIEVED,
                GoalModels.GoalPhase.ACHIEVED, 5, "run_goal", 0, 0, null, false, NOW, NOW);
        GoalModels.ToolAttempt settled = new GoalModels.ToolAttempt("attempt_one", "goal_one", null, 1L,
                "run_goal", null, null, 1, "turn_goal", "call_goal", 9, false,
                GoalModels.ToolAttemptState.SUCCEEDED, "a".repeat(64), "b".repeat(64),
                NOW, NOW, NOW);
        GoalRepository repository = repository((proxy, method, args) -> switch (method.getName()) {
            case "settleToolAttempt" -> settled;
            case "findGoal" -> Optional.of(achieved);
            default -> throw new UnsupportedOperationException(method.getName());
        });
        List<String> notifications = new ArrayList<>();
        GoalToolExecutionLedger ledger = new GoalToolExecutionLedger(repository,
                Clock.fixed(NOW, ZoneOffset.UTC), 9, notifications::add);

        ledger.settle(new GoalToolExecutionPort.Attempt("attempt_one"),
                new GoalToolExecutionPort.Settlement(ToolOutcome.SUCCEEDED, "ok", null, NOW));

        assertEquals(List.of("goal_one"), notifications);
    }

    /** Tool prepare 请求只携带调用已冻结事实，测试不会依赖 ambient Thread 状态。 */
    private static GoalToolExecutionPort.Prepare request(TurnOrigin origin, String callId) {
        String turnId = "turn_user";
        if (callId.equals("call_stale")) turnId = "turn_stale";
        else if (origin == TurnOrigin.GOAL_CONTINUATION) turnId = "turn_goal";
        else if (origin == TurnOrigin.PLAN_EXECUTION) turnId = "turn_plan";
        return new GoalToolExecutionPort.Prepare("thread_one", turnId, origin, callId,
                "shell", JsonObject.empty(), ToolSideEffect.EXTERNAL, NOW);
    }

    /** 构造符合状态 identity 约束的最小 Plan。 */
    private static GoalModels.Plan plan(String id, GoalModels.PlanStatus status,
                                        String revisionId, String runId) {
        return new GoalModels.Plan(id, "thread_one", "计划", status, 3,
                revisionId, runId, NOW, NOW);
    }

    /** 每个 Plan snapshot 使用自己的 revision/run，防止测试本身掩盖跨账错误。 */
    private static GoalModels.PlanSnapshot snapshot(GoalModels.Plan plan, String revisionId, String runId) {
        GoalModels.PlanDefinition definition = new GoalModels.PlanDefinition("计划", List.of(), List.of(),
                List.of(), List.of(), List.of(new GoalModels.PlanStep("step_one", "实施", "执行", true,
                List.of())), List.of(new GoalModels.AcceptanceCriterion("criterion_one", "验收", true)),
                List.of(), List.of("运行聚焦回归"));
        GoalModels.PlanRevision revision = new GoalModels.PlanRevision(revisionId, plan.planId(), 1,
                definition, "{}", "a".repeat(64), "USER_UI", NOW);
        GoalModels.PlanApproval approval = new GoalModels.PlanApproval("approval_" + plan.planId(),
                plan.planId(), revisionId, "a".repeat(64), NOW);
        return new GoalModels.PlanSnapshot(plan, null, revision, approval,
                List.of(new GoalModels.StepExecution("step_one", runId, GoalModels.StepStatus.READY,
                        1, null, null, null, null)), 3);
    }

    /** 动态代理仅暴露本测试所需窄端口，任何新增调用都会立即失败并暴露依赖扩张。 */
    private static GoalRepository repository(java.lang.reflect.InvocationHandler handler) {
        return (GoalRepository) Proxy.newProxyInstance(GoalRepository.class.getClassLoader(),
                new Class<?>[]{GoalRepository.class}, handler);
    }
}
