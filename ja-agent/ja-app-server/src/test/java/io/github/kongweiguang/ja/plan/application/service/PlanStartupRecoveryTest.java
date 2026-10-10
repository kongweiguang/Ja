// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.service;

import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证新进程只结算旧 Plan 事实，不重放外部副作用。 */
final class PlanStartupRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-09-06T02:00:00Z");

    /** PREPARED 缺少 Core 同提交终态证明时保留 blocker；STARTED 按副作用边界恢复。 */
    @Test
    void reconcilesOldGenerationWithoutReplayingSideEffects() {
        Map<String, PlanModels.ToolAttempt> pending = new LinkedHashMap<>();
        pending.put("attempt_prepared", attempt("attempt_prepared", "plan_prepared", "run_prepared",
                false, PlanModels.ToolAttemptState.PREPARED));
        pending.put("attempt_read", attempt("attempt_read", "plan_read", "run_read",
                false, PlanModels.ToolAttemptState.STARTED));
        pending.put("attempt_side_effect", attempt("attempt_side_effect", "plan_unsafe", "run_unsafe",
                true, PlanModels.ToolAttemptState.STARTED));
        Map<String, PlanRepository.PlanExecutionRecovery> executions = new LinkedHashMap<>();
        executions.put("plan_read", new PlanRepository.PlanExecutionRecovery(
                "plan_read", 2, "run_read", "revision_plan_read", false));
        executions.put("plan_unsafe", new PlanRepository.PlanExecutionRecovery(
                "plan_unsafe", 2, "run_unsafe", "revision_plan_unsafe", true));
        List<String> started = new ArrayList<>();
        List<String> abandonedClaimRuns = new ArrayList<>();
        Map<String, PlanModels.ToolAttemptState> settled = new LinkedHashMap<>();
        Map<String, Boolean> recovery = new LinkedHashMap<>();

        PlanRepository repository = repository((proxy, method, args) -> switch (method.getName()) {
            case "listUnsettledToolAttempts" -> pending.values().stream()
                    .filter(value -> value.state() == PlanModels.ToolAttemptState.STARTED).toList();
            case "settleToolAttempt" -> {
                PlanRepository.SettleToolAttempt command = (PlanRepository.SettleToolAttempt) args[0];
                settled.put(command.toolAttemptId(), command.state());
                yield pending.remove(command.toolAttemptId());
            }
            case "readPlanSnapshot" -> planSnapshot((String) args[0]);
            case "abandonUnadmittedPlanTurnClaims" -> {
                abandonedClaimRuns.add((String) args[0]);
                yield null;
            }
            case "recoverPlanExecution" -> {
                PlanRepository.RecoverPlanExecution command = (PlanRepository.RecoverPlanExecution) args[0];
                recovery.put(command.planId(), command.unsafe());
                executions.remove(command.planId());
                yield planSnapshot(command.planId()).plan();
            }
            case "listUnsettledPlanExecutions" -> List.copyOf(executions.values());
            default -> throw new UnsupportedOperationException(method.getName());
        });

        new PlanStartupRecovery(repository, Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC), 9).recover();

        assertTrue(started.isEmpty());
        assertFalse(settled.containsKey("attempt_prepared"));
        assertEquals(PlanModels.ToolAttemptState.PREPARED, pending.get("attempt_prepared").state());
        assertEquals(PlanModels.ToolAttemptState.FAILED, settled.get("attempt_read"));
        assertEquals(PlanModels.ToolAttemptState.UNKNOWN, settled.get("attempt_side_effect"));
        assertFalse(recovery.get("plan_read"));
        assertTrue(recovery.get("plan_unsafe"));
        assertEquals(List.of("run_read", "run_unsafe"), abandonedClaimRuns);
    }

    /** 安全 attempt 排在前面时，也必须等同 Run 的副作用 attempt 结算后再选 unsafe blocker。 */
    @Test
    void safeAttemptListedFirstCannotMaskUnknownSideEffect() {
        assertMixedAttemptRecovery(List.of(
                attempt("attempt_safe", "plan_mixed", "run_mixed", false, PlanModels.ToolAttemptState.STARTED),
                attempt("attempt_unsafe", "plan_mixed", "run_mixed", true, PlanModels.ToolAttemptState.STARTED)), false);
    }

    /** 副作用 attempt 排在前面时仍只做一次 Run 级恢复，并先结算所有旧 attempt。 */
    @Test
    void unknownSideEffectListedFirstRemainsRunBlocker() {
        assertMixedAttemptRecovery(List.of(
                attempt("attempt_unsafe", "plan_mixed", "run_mixed", true, PlanModels.ToolAttemptState.STARTED),
                attempt("attempt_safe", "plan_mixed", "run_mixed", false, PlanModels.ToolAttemptState.STARTED)), false);
    }

    /** 上次启动已落库的 UNKNOWN 加本次安全失败仍由候选查询识别为 blocker。 */
    @Test
    void priorUnknownRemainsBlockingWhenOnlySafeAttemptIsRecovered() {
        assertMixedAttemptRecovery(List.of(
                attempt("attempt_safe", "plan_mixed", "run_mixed", false, PlanModels.ToolAttemptState.STARTED)), true);
    }

    /** 先结算 Tool 事实，再放弃无持久 Turn 的 claim；真实 Turn 的账本结算仍由执行边界负责。 */
    private static void assertMixedAttemptRecovery(List<PlanModels.ToolAttempt> orderedAttempts,
                                                   boolean priorUnknown) {
        Map<String, PlanModels.ToolAttempt> pending = new LinkedHashMap<>();
        orderedAttempts.forEach(value -> pending.put(value.toolAttemptId(), value));
        Map<String, PlanModels.ToolAttemptState> settled = new LinkedHashMap<>();
        if (priorUnknown) {
            settled.put("attempt_prior_unknown", PlanModels.ToolAttemptState.UNKNOWN);
        }
        List<String> lifecycle = new ArrayList<>();
        List<PlanRepository.RecoverPlanExecution> recoveries = new ArrayList<>();
        AtomicReference<PlanModels.PlanStatus> status = new AtomicReference<>(PlanModels.PlanStatus.EXECUTING);
        AtomicInteger revision = new AtomicInteger(2);
        PlanRepository repository = repository((proxy, method, args) -> switch (method.getName()) {
            case "listUnsettledToolAttempts" -> pending.values().stream()
                    .filter(value -> value.state() == PlanModels.ToolAttemptState.STARTED).toList();
            case "settleToolAttempt" -> {
                PlanRepository.SettleToolAttempt command = (PlanRepository.SettleToolAttempt) args[0];
                PlanModels.ToolAttempt attempt = pending.remove(command.toolAttemptId());
                assertTrue(attempt != null, "attempt should settle once");
                settled.put(command.toolAttemptId(), command.state());
                lifecycle.add("attempt:" + command.toolAttemptId());
                yield attempt;
            }
            case "abandonUnadmittedPlanTurnClaims" -> {
                assertTrue(pending.isEmpty(), "all Tool attempts must settle before claim abandonment");
                assertEquals(orderedAttempts.size() + (priorUnknown ? 1 : 0), settled.size());
                assertEquals("run_mixed", args[0]);
                lifecycle.add("abandon-unadmitted-claims");
                yield null;
            }
            case "readPlanSnapshot" -> activePlanSnapshot((String) args[0], "run_mixed", status.get(), revision.get());
            case "recoverPlanExecution" -> {
                PlanRepository.RecoverPlanExecution command = (PlanRepository.RecoverPlanExecution) args[0];
                assertTrue(pending.isEmpty(), "Run recovery must follow every attempt settlement");
                assertEquals(orderedAttempts.size() + (priorUnknown ? 1 : 0), settled.size());
                assertEquals("abandon-unadmitted-claims", lifecycle.getLast(),
                        "unadmitted claims must be abandoned before Plan state");
                recoveries.add(command);
                status.set(command.unsafe() ? PlanModels.PlanStatus.STOPPED : PlanModels.PlanStatus.PAUSED);
                revision.incrementAndGet();
                lifecycle.add("recovery");
                yield activePlanSnapshot(command.planId(), command.runId(), status.get(), revision.get()).plan();
            }
            case "listUnsettledPlanExecutions" -> {
                if (status.get() != PlanModels.PlanStatus.EXECUTING
                    && status.get() != PlanModels.PlanStatus.VERIFYING) {
                    yield List.of();
                }
                assertTrue(pending.isEmpty(), "candidate query must run after Tool attempt settlement");
                boolean unsafe = settled.values().stream()
                        .anyMatch(value -> value == PlanModels.ToolAttemptState.UNKNOWN);
                yield List.of(new PlanRepository.PlanExecutionRecovery("plan_mixed", revision.get(),
                        "run_mixed", "revision_plan_mixed", unsafe));
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });

        new PlanStartupRecovery(repository, Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC), 9).recover();

        List<String> expectedLifecycle = new ArrayList<>();
        orderedAttempts.forEach(value -> expectedLifecycle.add("attempt:" + value.toolAttemptId()));
        expectedLifecycle.add("abandon-unadmitted-claims");
        expectedLifecycle.add("recovery");
        assertEquals(expectedLifecycle, lifecycle);
        assertEquals(PlanModels.ToolAttemptState.FAILED, settled.get("attempt_safe"));
        if (orderedAttempts.stream().anyMatch(PlanModels.ToolAttempt::sideEffect)) {
            assertEquals(PlanModels.ToolAttemptState.UNKNOWN, settled.get("attempt_unsafe"));
        }
        assertEquals(priorUnknown, settled.containsKey("attempt_prior_unknown"));
        assertEquals(1, recoveries.size());
        assertTrue(recoveries.getFirst().unsafe());
        assertEquals("run_mixed", recoveries.getFirst().runId());
        assertEquals(PlanModels.PlanStatus.STOPPED, status.get());
    }

    /** 旧活动 run 先放弃无持久 Turn 的 admission intent；未知副作用保持阻塞。 */
    @Test
    void settlesOldRunsAndPreservesUnknownSideEffectBlocker() {
        Map<String, PlanRepository.PlanExecutionRecovery> pending = new LinkedHashMap<>();
        pending.put("plan_safe", new PlanRepository.PlanExecutionRecovery(
                "plan_safe", 4, "run_safe", "revision_safe", false));
        pending.put("plan_unknown", new PlanRepository.PlanExecutionRecovery(
                "plan_unknown", 7, "run_unknown", "revision_unknown", true));
        List<PlanRepository.RecoverPlanExecution> recovered = new ArrayList<>();
        List<String> abandonedClaimRuns = new ArrayList<>();
        PlanRepository repository = repository((proxy, method, args) -> switch (method.getName()) {
            case "listUnsettledToolAttempts" -> List.of();
            case "listUnsettledPlanExecutions" -> List.copyOf(pending.values());
            case "abandonUnadmittedPlanTurnClaims" -> {
                abandonedClaimRuns.add((String) args[0]);
                yield null;
            }
            case "recoverPlanExecution" -> {
                PlanRepository.RecoverPlanExecution command = (PlanRepository.RecoverPlanExecution) args[0];
                recovered.add(command);
                pending.remove(command.planId());
                yield null;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });

        new PlanStartupRecovery(repository, Clock.fixed(NOW, ZoneOffset.UTC), 9).recover();

        assertEquals(List.of("run_safe", "run_unknown"), abandonedClaimRuns);
        assertEquals(2, recovered.size());
        assertFalse(recovered.get(0).unsafe());
        assertTrue(recovered.get(1).unsafe());
    }

    /** 持久查询重复返回同一页时必须立即失败，避免启动恢复占用线程无限循环。 */
    @Test
    void stopsWhenRecoveryPageDoesNotAdvance() {
        AtomicInteger reads = new AtomicInteger();
        PlanModels.ToolAttempt prepared = attempt("attempt_prepared", "plan_prepared", "run_prepared",
                false, PlanModels.ToolAttemptState.PREPARED);
        PlanRepository repository = repository((proxy, method, args) -> switch (method.getName()) {
            case "listUnsettledToolAttempts" -> {
                reads.incrementAndGet();
                yield List.of(prepared);
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new PlanStartupRecovery(repository, Clock.fixed(NOW, ZoneOffset.UTC), 9).recover());

        assertEquals("startup recovery made no progress: tool attempts", failure.getMessage());
        assertEquals(2, reads.get());
    }

    /** 工具记录冻结旧进程代际，确保恢复资格不会从当前 Plan 状态猜测。 */
    private static PlanModels.ToolAttempt attempt(String id, String planId, String runId,
                                                   boolean sideEffect, PlanModels.ToolAttemptState state) {
        Instant startedAt = state == PlanModels.ToolAttemptState.PREPARED ? null : NOW.plusSeconds(1);
        return new PlanModels.ToolAttempt(id, planId, runId, "revision_" + planId, "step_one", 1,
                "turn_one", "call_" + id, 3, sideEffect, state, "a".repeat(64), null, NOW, startedAt, null);
    }

    /** 恢复只需读取 Plan、revision、run 的冻结 identity，不读取正文或重新生成任何事实。 */
    private static PlanModels.PlanSnapshot planSnapshot(String planId) {
        String revisionId = "revision_" + planId;
        String runId = "plan_read".equals(planId) ? "run_read" : "run_unsafe";
        PlanModels.Plan plan = new PlanModels.Plan(planId, "thread_one", "恢复计划",
                PlanModels.PlanStatus.EXECUTING, 2, revisionId, runId, NOW, NOW);
        PlanModels.PlanDefinition definition = new PlanModels.PlanDefinition("恢复计划", List.of(), List.of(),
                List.of(), List.of(), List.of(new PlanModels.PlanStep("step_one", "实施", "执行", true,
                List.of())), List.of(new PlanModels.AcceptanceCriterion("criterion_one", "验收", true)),
                List.of(), List.of("运行恢复回归"));
        PlanModels.PlanRevision revision = new PlanModels.PlanRevision(revisionId, planId, 1,
                definition, "{}", "a".repeat(64), "USER_UI", NOW);
        PlanModels.PlanApproval approval = new PlanModels.PlanApproval("approval_" + planId,
                planId, revisionId, "a".repeat(64), NOW);
        return new PlanModels.PlanSnapshot(plan, null, revision, approval,
                List.of(new PlanModels.StepExecution("step_one", runId, PlanModels.StepStatus.RUNNING,
                        1, null, null, NOW, null)), 2);
    }

    /** 模拟同一活动 Run 经恢复 CAS 后的状态与 revision，供 mixed-attempt 顺序回归复用。 */
    private static PlanModels.PlanSnapshot activePlanSnapshot(String planId, String runId,
                                                               PlanModels.PlanStatus status, long revisionNumber) {
        String revisionId = "revision_" + planId;
        PlanModels.Plan plan = new PlanModels.Plan(planId, "thread_one", "恢复计划", status, revisionNumber,
                revisionId, runId, NOW, NOW);
        PlanModels.PlanDefinition definition = new PlanModels.PlanDefinition("恢复计划", List.of(), List.of(),
                List.of(), List.of(), List.of(new PlanModels.PlanStep("step_one", "实施", "执行", true,
                List.of())), List.of(new PlanModels.AcceptanceCriterion("criterion_one", "验收", true)),
                List.of(), List.of("运行恢复回归"));
        PlanModels.PlanRevision planRevision = new PlanModels.PlanRevision(revisionId, planId, 1,
                definition, "{}", "a".repeat(64), "USER_UI", NOW);
        PlanModels.PlanApproval approval = new PlanModels.PlanApproval("approval_" + planId,
                planId, revisionId, "a".repeat(64), NOW);
        return new PlanModels.PlanSnapshot(plan, null, planRevision, approval,
                List.of(new PlanModels.StepExecution("step_one", runId, PlanModels.StepStatus.RUNNING,
                        1, null, null, NOW, null)), revisionNumber);
    }

    /** 动态代理只开放恢复器实际使用的持久端口调用。 */
    private static PlanRepository repository(java.lang.reflect.InvocationHandler handler) {
        return (PlanRepository) Proxy.newProxyInstance(PlanRepository.class.getClassLoader(),
                new Class<?>[]{PlanRepository.class}, handler);
    }
}

