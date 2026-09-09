// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.in.PlanExecutionEventSink;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 验证 standalone Plan run 的连接级事件出口不会再借用 Goal observation。 */
final class PlanExecutionCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-09-06T02:00:00Z");

    /**
     * coordinator 必须把发起连接的出口原样交给 Turn adapter；Turn 终态后再以同一 run identity
     * 结算，防止事件路由修复改变 Plan 的一次性执行语义。
     */
    @Test
    void carriesInitiatingConnectionEventsIntoStandaloneTurn() {
        AtomicReference<PlanExecutionEventSink> observedEvents = new AtomicReference<>();
        AtomicReference<GoalRepository.SettlePlanExecution> settlement = new AtomicReference<>();
        GoalModels.Plan executing = new GoalModels.Plan("plan_one", "thr_one", "执行计划",
                GoalModels.PlanStatus.EXECUTING, 4, "planrev_one", "run_one", NOW, NOW);
        GoalModels.PlanDefinition definition = new GoalModels.PlanDefinition("执行计划", List.of("工作区"),
                List.of(), List.of(), List.of(),
                List.of(new GoalModels.PlanStep("step_one", "执行", "执行已批准步骤", true, List.of())),
                List.of(new GoalModels.AcceptanceCriterion("criterion_one", "命令成功", true)),
                List.of(), List.of("检查 Tool result"));
        GoalModels.PlanRevision revision = new GoalModels.PlanRevision("planrev_one", "plan_one", 1,
                definition, "{}", "a".repeat(64), "USER_UI", NOW);
        GoalRepository repository = (GoalRepository) Proxy.newProxyInstance(
                PlanExecutionCoordinatorTest.class.getClassLoader(), new Class<?>[]{GoalRepository.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "readPlanSnapshot" -> new GoalModels.PlanSnapshot(
                            executing, null, revision, null, List.of(), 1);
                    case "settlePlanExecution" -> {
                        settlement.set((GoalRepository.SettlePlanExecution) arguments[0]);
                        yield executing;
                    }
                    default -> throw new AssertionError("unexpected repository call: " + method.getName());
                });
        PlanExecutionCoordinator.PlanExecutionTurnPort turns = (request, events) -> {
            observedEvents.set(events);
            assertEquals("plan_one", request.planId());
            assertEquals("run_one", request.runId());
            return CompletableFuture.completedFuture(null);
        };
        PlanExecutionCoordinator coordinator = new PlanExecutionCoordinator(repository, turns,
                Clock.fixed(NOW, ZoneOffset.UTC));
        PlanExecutionEventSink events = PlanExecutionEventSink.noop();

        coordinator.start(executing, events).toCompletableFuture().join();

        assertSame(events, observedEvents.get());
        assertEquals("plan_one", settlement.get().planId());
        assertEquals("run_one", settlement.get().runId());
    }
}
