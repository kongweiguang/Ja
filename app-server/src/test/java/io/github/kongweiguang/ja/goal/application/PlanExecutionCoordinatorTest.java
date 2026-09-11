// @author kongweiguang
// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.in.PlanExecutionEventSink;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;
import io.github.kongweiguang.ja.goal.port.out.PlanEvaluatorPort;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 standalone Plan run 的连接级事件出口不会再借用 Goal observation。 */
final class PlanExecutionCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-09-06T02:00:00Z");

    /**
     * coordinator 必须把发起连接的出口原样交给 Turn adapter；正常 Turn 收口不能把仍可
     * 等待下一轮或 Interaction 恢复的 Plan 误结算为 APPROVED。
     */
    @Test
    void carriesInitiatingConnectionEventsIntoStandaloneTurn() {
        AtomicReference<PlanExecutionEventSink> observedEvents = new AtomicReference<>();
        AtomicReference<GoalRepository.SettlePlanExecution> settlement = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();
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
                    case "beginPlanVerification" -> executing;
                    case "claimPlanTurn" -> {
                        GoalRepository.ClaimPlanTurn command = (GoalRepository.ClaimPlanTurn) arguments[0];
                        yield claimed.compareAndSet(false, true)
                                ? Optional.of(new GoalRepository.PlanTurnClaim(command.planId(), command.runId(),
                                command.planRevisionId(), command.turnId(), 1, 31, 31, 127,
                                1_800_000L)) : Optional.empty();
                    }
                    case "settlePlanExecution" -> {
                        settlement.set((GoalRepository.SettlePlanExecution) arguments[0]);
                        yield executing;
                    }
                    default -> throw new AssertionError("unexpected repository call: " + method.getName());
                });
        PlanExecutionCoordinator.PlanExecutionTurnPort turns = new PlanExecutionCoordinator.PlanExecutionTurnPort() {
            /** 测试端口显式固定 admission 行为，避免默认实现掩盖事件或预算丢失。 */
            @Override public CompletableFuture<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                             PlanExecutionEventSink events) {
                observedEvents.set(events);
                assertEquals("plan_one", request.planId());
                assertEquals("run_one", request.runId());
                return CompletableFuture.completedFuture(null);
            }

            /** 测试端口显式固定 admission 行为，避免默认实现掩盖事件或预算丢失。 */
            @Override public CompletableFuture<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                             GoalRepository.PlanTurnClaim claim,
                                                             PlanExecutionEventSink events) {
                return start(request, events);
            }

            /** 本场景不授权暂停，意外调用必须让测试失败而不是空成功。 */
            @Override public CompletionStage<Void> pause(PlanExecutionCoordinator.ExecutionRequest request) {
                throw new AssertionError("unexpected pause");
            }

            /** 本场景不授权停止，拒绝意外生命周期分支掩盖执行状态。 */
            @Override public CompletionStage<Void> stop(PlanExecutionCoordinator.ExecutionRequest request) {
                throw new AssertionError("unexpected stop");
            }

            /** 本场景不允许隐式续跑，恢复必须由单独用例验证。 */
            @Override public CompletionStage<Void> resume(PlanExecutionCoordinator.ExecutionRequest request,
                                                           PlanExecutionEventSink events) {
                throw new AssertionError("unexpected resume");
            }
        };
        PlanExecutionCoordinator coordinator = new PlanExecutionCoordinator(repository, turns,
                Clock.fixed(NOW, ZoneOffset.UTC));
        PlanExecutionEventSink events = PlanExecutionEventSink.noop();

        coordinator.start(executing, events).toCompletableFuture().join();

        assertSame(events, observedEvents.get());
        assertEquals(null, settlement.get());
    }

    /** 只有 Turn admission/执行失败才允许回退 run，等待交互不应走这条错误收口。 */
    @Test
    void settlesOnlyWhenTurnFails() {
        AtomicReference<GoalRepository.SettlePlanExecution> settlement = new AtomicReference<>();
        GoalModels.Plan executing = new GoalModels.Plan("plan_two", "thr_two", "执行计划",
                GoalModels.PlanStatus.EXECUTING, 4, "planrev_two", "run_two", NOW, NOW);
        RuntimeException failure = new RuntimeException("turn failed");
        java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();
        GoalModels.PlanDefinition definition = new GoalModels.PlanDefinition("执行计划", List.of(), List.of(),
                List.of(), List.of(), List.of(new GoalModels.PlanStep("step_two", "执行", "执行", true, List.of())),
                List.of(new GoalModels.AcceptanceCriterion("criterion_two", "通过", true)), List.of(), List.of("检查"));
        GoalModels.PlanRevision revision = new GoalModels.PlanRevision("planrev_two", "plan_two", 1,
                definition, "{}", "b".repeat(64), "USER_UI", NOW);

        // 失败回调需要重读当前快照；fake 明确返回执行态以检验同一 run fencing。
        GoalRepository failingRepository = (GoalRepository) Proxy.newProxyInstance(
                PlanExecutionCoordinatorTest.class.getClassLoader(), new Class<?>[]{GoalRepository.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "readPlanSnapshot" -> new GoalModels.PlanSnapshot(executing, null, revision, null, List.of(), 1);
                    case "beginPlanVerification" -> executing;
                    case "claimPlanTurn" -> {
                        GoalRepository.ClaimPlanTurn command = (GoalRepository.ClaimPlanTurn) arguments[0];
                        yield claimed.compareAndSet(false, true)
                                ? Optional.of(new GoalRepository.PlanTurnClaim(command.planId(), command.runId(),
                                command.planRevisionId(), command.turnId(), 1, 31, 31, 127,
                                1_800_000L)) : Optional.empty();
                    }
                    case "settlePlanExecution" -> {
                        settlement.set((GoalRepository.SettlePlanExecution) arguments[0]);
                        yield executing;
                    }
                    default -> throw new AssertionError("unexpected repository call: " + method.getName());
                });
        PlanExecutionCoordinator.PlanExecutionTurnPort failingTurns = new PlanExecutionCoordinator.PlanExecutionTurnPort() {
            /** 测试端口显式固定 admission 行为，避免默认实现掩盖事件或预算丢失。 */
            @Override public CompletableFuture<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                             PlanExecutionEventSink events) {
                return CompletableFuture.failedFuture(failure);
            }

            /** 测试端口显式固定 admission 行为，避免默认实现掩盖事件或预算丢失。 */
            @Override public CompletableFuture<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                             GoalRepository.PlanTurnClaim claim,
                                                             PlanExecutionEventSink events) {
                return start(request, events);
            }

            /** 本场景不授权暂停，意外调用必须让测试失败而不是空成功。 */
            @Override public CompletionStage<Void> pause(PlanExecutionCoordinator.ExecutionRequest request) {
                throw new AssertionError("unexpected pause");
            }

            /** 本场景不授权停止，拒绝意外生命周期分支掩盖执行状态。 */
            @Override public CompletionStage<Void> stop(PlanExecutionCoordinator.ExecutionRequest request) {
                throw new AssertionError("unexpected stop");
            }

            /** 本场景不允许隐式续跑，恢复必须由单独用例验证。 */
            @Override public CompletionStage<Void> resume(PlanExecutionCoordinator.ExecutionRequest request,
                                                           PlanExecutionEventSink events) {
                throw new AssertionError("unexpected resume");
            }
        };
        PlanExecutionCoordinator coordinator = new PlanExecutionCoordinator(failingRepository, failingTurns,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> coordinator.start(executing, PlanExecutionEventSink.noop()).toCompletableFuture().join());
        assertEquals("plan_two", settlement.get().planId());
        assertEquals("run_two", settlement.get().runId());
    }

    /** coordinator 必须把 SQLite 计算出的真实剩余预算传入 Turn admission，不能回退到单 Turn 默认值。 */
    @Test
    void forwardsRemainingRunBudgetToTurnAdmission() {
        AtomicReference<GoalRepository.PlanTurnClaim> observedClaim = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();
        GoalModels.Plan executing = new GoalModels.Plan("plan_ceiling", "thr_ceiling", "执行计划",
                GoalModels.PlanStatus.EXECUTING, 4, "planrev_ceiling", "run_ceiling", NOW, NOW);
        GoalModels.PlanDefinition definition = new GoalModels.PlanDefinition("执行计划", List.of(), List.of(),
                List.of(), List.of(), List.of(new GoalModels.PlanStep("step_ceiling", "执行", "执行", true, List.of())),
                List.of(new GoalModels.AcceptanceCriterion("criterion_ceiling", "通过", true)), List.of(), List.of("检查"));
        GoalModels.PlanRevision revision = new GoalModels.PlanRevision("planrev_ceiling", "plan_ceiling", 1,
                definition, "{}", "c".repeat(64), "USER_UI", NOW);
        GoalRepository repository = (GoalRepository) Proxy.newProxyInstance(
                PlanExecutionCoordinatorTest.class.getClassLoader(), new Class<?>[]{GoalRepository.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "readPlanSnapshot" -> new GoalModels.PlanSnapshot(
                            executing, null, revision, null, List.of(), 1);
                    case "beginPlanVerification" -> executing;
                    case "claimPlanTurn" -> {
                        GoalRepository.ClaimPlanTurn command = (GoalRepository.ClaimPlanTurn) arguments[0];
                        yield claimed.compareAndSet(false, true)
                                ? Optional.of(new GoalRepository.PlanTurnClaim(command.planId(), command.runId(),
                                command.planRevisionId(), command.turnId(), 2, 7, 5, 11, 42_000L))
                                : Optional.empty();
                    }
                    default -> throw new AssertionError("unexpected repository call: " + method.getName());
                });
        PlanExecutionCoordinator.PlanExecutionTurnPort turns = new PlanExecutionCoordinator.PlanExecutionTurnPort() {
            /** 测试端口显式固定 admission 行为，避免默认实现掩盖事件或预算丢失。 */
            @Override public CompletableFuture<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                             PlanExecutionEventSink events) {
                return CompletableFuture.completedFuture(null);
            }

            /** 测试端口显式固定 admission 行为，避免默认实现掩盖事件或预算丢失。 */
            @Override public CompletableFuture<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                             GoalRepository.PlanTurnClaim claim,
                                                             PlanExecutionEventSink events) {
                observedClaim.set(claim);
                return CompletableFuture.completedFuture(null);
            }

            /** 本场景不授权暂停，意外调用必须让测试失败而不是空成功。 */
            @Override public CompletionStage<Void> pause(PlanExecutionCoordinator.ExecutionRequest request) {
                throw new AssertionError("unexpected pause");
            }

            /** 本场景不授权停止，拒绝意外生命周期分支掩盖执行状态。 */
            @Override public CompletionStage<Void> stop(PlanExecutionCoordinator.ExecutionRequest request) {
                throw new AssertionError("unexpected stop");
            }

            /** 本场景不允许隐式续跑，恢复必须由单独用例验证。 */
            @Override public CompletionStage<Void> resume(PlanExecutionCoordinator.ExecutionRequest request,
                                                           PlanExecutionEventSink events) {
                throw new AssertionError("unexpected resume");
            }
        };
        PlanExecutionCoordinator coordinator = new PlanExecutionCoordinator(repository, turns,
                Clock.fixed(NOW, ZoneOffset.UTC));

        coordinator.start(executing, PlanExecutionEventSink.noop()).toCompletableFuture().join();

        GoalRepository.PlanTurnClaim claim = observedClaim.get();
        assertEquals(2, claim.ordinal());
        assertEquals(7, claim.remainingTurnBudget());
        assertEquals(5, claim.remainingModelRounds());
        assertEquals(11, claim.remainingToolCalls());
        assertEquals(42_000L, claim.remainingWallBudgetMillis());
    }

    /**
     * 同一 standalone Run 的并发重试必须由持久 claim 单飞；第二次 admission 即使来自丢响应重试，
     * 也不能再次进入 Turn adapter。测试故意让第一次 Turn 未完成，以覆盖真实执行中的竞争窗口。
     */
    @Test
    void doesNotStartTurnTwiceWhenAdmissionIsRetriedConcurrently() {
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger claims = new AtomicInteger();
        CompletableFuture<Void> firstTurn = new CompletableFuture<>();
        GoalModels.Plan executing = new GoalModels.Plan("plan_singleflight", "thr_singleflight", "执行计划",
                GoalModels.PlanStatus.EXECUTING, 4, "planrev_singleflight", "run_singleflight", NOW, NOW);
        GoalModels.PlanDefinition definition = new GoalModels.PlanDefinition("执行计划", List.of(), List.of(),
                List.of(), List.of(), List.of(new GoalModels.PlanStep("step_singleflight", "执行", "执行", true, List.of())),
                List.of(new GoalModels.AcceptanceCriterion("criterion_singleflight", "通过", true)), List.of(), List.of("检查"));
        GoalModels.PlanRevision revision = new GoalModels.PlanRevision("planrev_singleflight", "plan_singleflight", 1,
                definition, "{}", "d".repeat(64), "USER_UI", NOW);
        GoalRepository repository = (GoalRepository) Proxy.newProxyInstance(
                PlanExecutionCoordinatorTest.class.getClassLoader(), new Class<?>[]{GoalRepository.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "readPlanSnapshot" -> new GoalModels.PlanSnapshot(executing, null, revision, null, List.of(), 1);
                    case "beginPlanVerification" -> executing;
                    case "claimPlanTurn" -> {
                        GoalRepository.ClaimPlanTurn command = (GoalRepository.ClaimPlanTurn) arguments[0];
                        yield claims.getAndIncrement() == 0
                                ? Optional.of(new GoalRepository.PlanTurnClaim(command.planId(), command.runId(),
                                command.planRevisionId(), command.turnId(), 1, 31, 31, 127, 1_800_000L))
                                : Optional.empty();
                    }
                    default -> throw new AssertionError("unexpected repository call: " + method.getName());
                });
        PlanExecutionCoordinator.PlanExecutionTurnPort turns = new PlanExecutionCoordinator.PlanExecutionTurnPort() {
            /** 记录真实 adapter admission 次数，并阻塞首个 Turn 以制造重试竞争。 */
            @Override public CompletionStage<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                          GoalRepository.PlanTurnClaim claim,
                                                          PlanExecutionEventSink events) {
                starts.incrementAndGet();
                return firstTurn;
            }

            /** 普通无 claim 入口在本测试中不应被 coordinator 使用。 */
            @Override public CompletionStage<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                          PlanExecutionEventSink events) {
                throw new AssertionError("unclaimed Plan Turn start");
            }

            /** 本场景不授权暂停，意外调用必须显式失败。 */
            @Override public CompletionStage<Void> pause(PlanExecutionCoordinator.ExecutionRequest request) {
                throw new AssertionError("unexpected pause");
            }

            /** 本场景不授权停止，意外调用必须显式失败。 */
            @Override public CompletionStage<Void> stop(PlanExecutionCoordinator.ExecutionRequest request) {
                throw new AssertionError("unexpected stop");
            }

            /** 本场景不触发恢复，意外调用必须显式失败。 */
            @Override public CompletionStage<Void> resume(PlanExecutionCoordinator.ExecutionRequest request,
                                                           PlanExecutionEventSink events) {
                throw new AssertionError("unexpected resume");
            }
        };
        PlanExecutionCoordinator coordinator = new PlanExecutionCoordinator(repository, turns,
                Clock.fixed(NOW, ZoneOffset.UTC));

        CompletionStage<Void> first = coordinator.start(executing, PlanExecutionEventSink.noop());
        CompletionStage<Void> retry = coordinator.start(executing, PlanExecutionEventSink.noop());

        retry.toCompletableFuture().join();
        assertEquals(1, starts.get());
        assertEquals(2, claims.get());
        firstTurn.complete(null);
        first.toCompletableFuture().join();
        assertEquals(1, starts.get());
    }

    /**
     * 显式 Resume 与自动回答共用同一 raw completion；coordinator 只能由 continuation 续跑一次，
     * 否则同一 Run 会重复进入验收边界并可能再次领取预算。
     */
    @Test
    void resumesExistingTurnThroughOneContinuation() {
        AtomicInteger verificationChecks = new AtomicInteger();
        AtomicReference<java.util.function.Consumer<CompletionStage<?>>> continuation = new AtomicReference<>();
        GoalModels.Plan paused = plan("plan_resume_once", GoalModels.PlanStatus.PAUSED, 4);
        GoalModels.Plan executing = plan("plan_resume_once", GoalModels.PlanStatus.EXECUTING, 5);
        GoalModels.PlanDefinition definition = new GoalModels.PlanDefinition("执行计划", List.of(), List.of(),
                List.of(), List.of(), List.of(new GoalModels.PlanStep("step_resume_once", "执行", "执行", true, List.of())),
                List.of(new GoalModels.AcceptanceCriterion("criterion_resume_once", "通过", true)), List.of(), List.of("检查"));
        GoalModels.PlanRevision revision = new GoalModels.PlanRevision("planrev_resume_once", "plan_resume_once", 1,
                definition, "{}", "e".repeat(64), "USER_UI", NOW);
        GoalRepository repository = (GoalRepository) Proxy.newProxyInstance(
                PlanExecutionCoordinatorTest.class.getClassLoader(), new Class<?>[]{GoalRepository.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "readPlanSnapshot" -> new GoalModels.PlanSnapshot(executing, null, revision, null, List.of(), 1);
                    case "findPlanTurnBinding" -> Optional.of(new GoalRepository.InternalTurnBinding(
                            "turn_resume_once", "PLAN_EXECUTION", null, "plan_resume_once", "run_resume_once",
                            null, "planrev_resume_once", "e".repeat(64), null));
                    case "beginPlanVerification" -> {
                        verificationChecks.incrementAndGet();
                        yield paused;
                    }
                    case "claimPlanTurn" -> {
                        GoalRepository.ClaimPlanTurn command = (GoalRepository.ClaimPlanTurn) arguments[0];
                        yield Optional.of(new GoalRepository.PlanTurnClaim(command.planId(), command.runId(),
                                command.planRevisionId(), command.turnId(), 1, 31, 31, 127, 1_800_000L));
                    }
                    default -> throw new AssertionError("unexpected repository call: " + method.getName());
                });
        CompletableFuture<Void> initialSuspension = CompletableFuture.failedFuture(
                new io.github.kongweiguang.ja.conversation.application.service.TurnService.PlanSuspendedException());
        PlanExecutionCoordinator.PlanExecutionTurnPort turns = new PlanExecutionCoordinator.PlanExecutionTurnPort() {
            /** 首轮只建立一个可恢复 Turn，避免测试把暂停误当作普通失败。 */
            @Override public CompletionStage<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                          GoalRepository.PlanTurnClaim claim,
                                                          PlanExecutionEventSink events) {
                return initialSuspension;
            }

            /** 恢复回调由 TurnService 的唯一消费点触发，并把同一个 stage 返回给显式调用方。 */
            @Override public CompletionStage<Void> resume(PlanExecutionCoordinator.ExecutionRequest request,
                                                           PlanExecutionEventSink events) {
                CompletableFuture<Void> resumed = new CompletableFuture<>();
                continuation.get().accept(resumed);
                return resumed;
            }

            /** 测试端口只验证 continuation 绑定，不允许无 claim 的新启动绕过 admission。 */
            @Override public CompletionStage<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                          PlanExecutionEventSink events) {
                throw new AssertionError("unclaimed Plan Turn start");
            }

            /** 控制入口不属于本场景。 */
            @Override public CompletionStage<Void> pause(PlanExecutionCoordinator.ExecutionRequest request) {
                throw new AssertionError("unexpected pause");
            }

            /** 控制入口不属于本场景。 */
            @Override public CompletionStage<Void> stop(PlanExecutionCoordinator.ExecutionRequest request) {
                throw new AssertionError("unexpected stop");
            }

            /** 保存唯一回调；第二次注册表示下一次问题，不能产生第二条续跑链。 */
            @Override public void registerResumeContinuation(PlanExecutionCoordinator.ExecutionRequest request,
                                                              PlanExecutionEventSink events,
                                                              java.util.function.Consumer<CompletionStage<?>> next) {
                continuation.set(next);
            }

            /** fake adapter 明确声明 continuation 已拥有恢复 completion，验证 coordinator 不再重复推进。 */
            @Override public boolean resumeCompletionIsTrackedByContinuation() {
                return true;
            }
        };
        PlanExecutionCoordinator coordinator = new PlanExecutionCoordinator(repository, turns,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(java.util.concurrent.CompletionException.class,
                () -> coordinator.start(executing, PlanExecutionEventSink.noop()).toCompletableFuture().join());
        CompletionStage<Void> resumed = coordinator.resume(paused, PlanExecutionEventSink.noop());
        resumed.toCompletableFuture().complete(null);
        resumed.toCompletableFuture().join();

        assertEquals(1, verificationChecks.get());
    }

    /** 已声明 continuation tracking 的恢复 admission 失败时也必须清理旧回调，避免迟到回答复活旧 Run。 */
    @Test
    void clearsContinuationWhenTrackedResumeAdmissionFails() {
        AtomicInteger cleared = new AtomicInteger();
        GoalModels.Plan paused = plan("plan_resume_failure", GoalModels.PlanStatus.PAUSED, 4);
        GoalModels.PlanRevision revision = new GoalModels.PlanRevision("planrev_resume_failure", "plan_resume_failure", 1,
                new GoalModels.PlanDefinition("执行计划", List.of(), List.of(), List.of(), List.of(),
                        List.of(new GoalModels.PlanStep("step_resume_failure", "执行", "执行", true, List.of())),
                        List.of(new GoalModels.AcceptanceCriterion("criterion_resume_failure", "通过", true)),
                        List.of(), List.of("检查")), "{}", "f".repeat(64), "USER_UI", NOW);
        GoalRepository repository = (GoalRepository) Proxy.newProxyInstance(
                PlanExecutionCoordinatorTest.class.getClassLoader(), new Class<?>[]{GoalRepository.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "readPlanSnapshot" -> new GoalModels.PlanSnapshot(paused, null, revision, null, List.of(), 1);
                    case "findPlanTurnBinding" -> Optional.of(new GoalRepository.InternalTurnBinding(
                            "turn_resume_failure", "PLAN_EXECUTION", null, "plan_resume_failure", "run_resume_failure",
                            null, "planrev_resume_failure", "f".repeat(64), null));
                    default -> throw new AssertionError("unexpected repository call: " + method.getName());
                });
        RuntimeException failure = new RuntimeException("resume admission failed");
        PlanExecutionCoordinator.PlanExecutionTurnPort turns = new PlanExecutionCoordinator.PlanExecutionTurnPort() {
            /** 该端口声明恢复 completion 已由 continuation 追踪，专门覆盖失败 stage 的清理分支。 */
            @Override public CompletionStage<Void> resume(PlanExecutionCoordinator.ExecutionRequest request,
                                                           PlanExecutionEventSink events) {
                return CompletableFuture.failedFuture(failure);
            }

            /** 记录 coordinator 对迟到恢复回调的撤销动作。 */
            @Override public void clearResumeContinuation(PlanExecutionCoordinator.ExecutionRequest request) {
                cleared.incrementAndGet();
            }

            /** 该场景不允许启动新 Turn，避免把恢复失败误判为继续执行。 */
            @Override public CompletionStage<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                          PlanExecutionEventSink events) {
                throw new AssertionError("unexpected unclaimed start");
            }

            /** 该场景不允许有 claim 的新 Turn。 */
            @Override public CompletionStage<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                          GoalRepository.PlanTurnClaim claim,
                                                          PlanExecutionEventSink events) {
                throw new AssertionError("unexpected claimed start");
            }

            /** 该场景只验证恢复 admission，不涉及暂停控制。 */
            @Override public CompletionStage<Void> pause(PlanExecutionCoordinator.ExecutionRequest request) {
                throw new AssertionError("unexpected pause");
            }

            /** 该场景只验证恢复 admission，不涉及停止控制。 */
            @Override public CompletionStage<Void> stop(PlanExecutionCoordinator.ExecutionRequest request) {
                throw new AssertionError("unexpected stop");
            }

            /** 明确声明成功路径由 continuation 追踪，确保 coordinator 不重复推进。 */
            @Override public boolean resumeCompletionIsTrackedByContinuation() {
                return true;
            }
        };
        PlanExecutionCoordinator coordinator = new PlanExecutionCoordinator(repository, turns,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(java.util.concurrent.CompletionException.class,
                () -> coordinator.resume(paused, PlanExecutionEventSink.noop()).toCompletableFuture().join());

        assertEquals(1, cleared.get());
    }

    /** 旧 binding 不存在时恢复必须走同一 Run 的 claim admission，再由唯一 completion 继续执行。 */
    @Test
    void resumeWithoutBindingClaimsBeforeStarting() {
        AtomicInteger claims = new AtomicInteger();
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger verificationChecks = new AtomicInteger();
        GoalModels.Plan paused = plan("plan_resume_claim", GoalModels.PlanStatus.PAUSED, 4);
        GoalModels.Plan executing = plan("plan_resume_claim", GoalModels.PlanStatus.EXECUTING, 5);
        GoalModels.PlanDefinition definition = new GoalModels.PlanDefinition("执行计划", List.of(), List.of(),
                List.of(), List.of(), List.of(new GoalModels.PlanStep("step_resume_claim", "执行", "执行", true, List.of())),
                List.of(new GoalModels.AcceptanceCriterion("criterion_resume_claim", "通过", true)), List.of(), List.of("检查"));
        GoalModels.PlanRevision revision = new GoalModels.PlanRevision("planrev_resume_claim", "plan_resume_claim", 1,
                definition, "{}", "f".repeat(64), "USER_UI", NOW);
        GoalRepository repository = (GoalRepository) Proxy.newProxyInstance(
                PlanExecutionCoordinatorTest.class.getClassLoader(), new Class<?>[]{GoalRepository.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "readPlanSnapshot" -> new GoalModels.PlanSnapshot(executing, null, revision, null, List.of(), 1);
                    case "findPlanTurnBinding" -> Optional.empty();
                    case "claimPlanTurn" -> {
                        claims.incrementAndGet();
                        GoalRepository.ClaimPlanTurn command = (GoalRepository.ClaimPlanTurn) arguments[0];
                        yield Optional.of(new GoalRepository.PlanTurnClaim(command.planId(), command.runId(),
                                command.planRevisionId(), command.turnId(), 1, 31, 31, 127, 1_800_000L));
                    }
                    case "beginPlanVerification" -> {
                        verificationChecks.incrementAndGet();
                        yield paused;
                    }
                    default -> throw new AssertionError("unexpected repository call: " + method.getName());
                });
        PlanExecutionCoordinator.PlanExecutionTurnPort turns = new PlanExecutionCoordinator.PlanExecutionTurnPort() {
            /** 新 Turn 只能通过 coordinator 传入 claim 的入口启动。 */
            @Override public CompletionStage<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                          GoalRepository.PlanTurnClaim claim,
                                                          PlanExecutionEventSink events) {
                starts.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }

            /** 无 binding 的恢复应走 start(request, claim)，不能调用该无 claim 入口。 */
            @Override public CompletionStage<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                          PlanExecutionEventSink events) {
                throw new AssertionError("unclaimed Plan Turn start");
            }

            /** 该测试验证显式恢复 admission，不涉及暂停控制。 */
            @Override public CompletionStage<Void> pause(PlanExecutionCoordinator.ExecutionRequest request) {
                throw new AssertionError("unexpected pause");
            }

            /** 该测试验证显式恢复 admission，不涉及停止控制。 */
            @Override public CompletionStage<Void> stop(PlanExecutionCoordinator.ExecutionRequest request) {
                throw new AssertionError("unexpected stop");
            }

            /** adapter 的无 binding 分支必须在 resume 内先领取 claim，再进入有 claim 的启动入口。 */
            @Override public CompletionStage<Void> resume(PlanExecutionCoordinator.ExecutionRequest request,
                                                           PlanExecutionEventSink events) {
                claims.incrementAndGet();
                return start(request, new GoalRepository.PlanTurnClaim(request.planId(), request.runId(),
                        request.planRevisionId(), request.turnId(), 1, 31, 31, 127, 1_800_000L), events);
            }
        };
        PlanExecutionCoordinator coordinator = new PlanExecutionCoordinator(repository, turns,
                Clock.fixed(NOW, ZoneOffset.UTC));

        coordinator.resume(paused, PlanExecutionEventSink.noop()).toCompletableFuture().join();

        assertEquals(1, claims.get());
        assertEquals(1, starts.get());
        assertEquals(1, verificationChecks.get());
    }

    /**
     * 侧聊关闭必须先取消真实 evaluator 与隐藏 Turn，再提交 Plan STOPPED；观察者失败不能
     * 让已经提交的终态回到可重试的伪失败路径。
     */
    @Test
    void stopsEvaluatorAndTurnBeforePersistingPlanStopped() {
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        GoalModels.Plan verifying = new GoalModels.Plan("plan_owner_stop", "thr_side_stop", "执行计划",
                GoalModels.PlanStatus.VERIFYING, 4, "planrev_owner_stop", "run_owner_stop", NOW, NOW);
        GoalModels.Plan stopped = new GoalModels.Plan("plan_owner_stop", "thr_side_stop", "执行计划",
                GoalModels.PlanStatus.STOPPED, 5, "planrev_owner_stop", "run_owner_stop", NOW, NOW);
        GoalModels.PlanRevision revision = new GoalModels.PlanRevision("planrev_owner_stop", "plan_owner_stop", 1,
                new GoalModels.PlanDefinition("执行计划", List.of(), List.of(), List.of(), List.of(),
                        List.of(new GoalModels.PlanStep("step_owner_stop", "执行", "执行", true, List.of())),
                        List.of(new GoalModels.AcceptanceCriterion("criterion_owner_stop", "通过", true)),
                        List.of(), List.of("检查")), "{}", "a".repeat(64), "USER_UI", NOW);
        AtomicReference<CompletableFuture<PlanEvaluatorPort.Evaluation>> evaluation = new AtomicReference<>();
        GoalRepository repository = (GoalRepository) Proxy.newProxyInstance(
                PlanExecutionCoordinatorTest.class.getClassLoader(), new Class<?>[]{GoalRepository.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "findActivePlanByOwner" -> Optional.of(verifying);
                    case "readPlanSnapshot" -> new GoalModels.PlanSnapshot(verifying, null, revision, null,
                            List.of(), 1);
                    case "listPlanEvidence" -> List.of();
                    case "readPlanRunBudget" -> Optional.of(new GoalRepository.PlanRunBudget(
                            verifying.planId(), verifying.activeRunId(), 8, 8, 60_000L));
                    case "findPlanTurnBinding" -> Optional.of(new GoalRepository.InternalTurnBinding(
                            "turn_owner_stop", "PLAN_EXECUTION", null, verifying.planId(),
                            verifying.activeRunId(), null, verifying.activePlanRevisionId(), "a".repeat(64), null));
                    case "stopPlan" -> {
                        order.add("plan-stop");
                        yield stopped;
                    }
                    default -> throw new AssertionError("unexpected repository call: " + method.getName());
                });
        PlanEvaluatorPort evaluator = (snapshot, evidence, context) -> {
            CompletableFuture<PlanEvaluatorPort.Evaluation> pending = new CompletableFuture<>();
            evaluation.set(pending);
            context.cancellation().onCancellation(() -> {
                order.add("evaluator-cancel");
                pending.completeExceptionally(new CancellationException("closed"));
            });
            return pending;
        };
        PlanExecutionCoordinator.PlanExecutionTurnPort turns = new PlanExecutionCoordinator.PlanExecutionTurnPort() {
            /** 该测试只覆盖 owner stop，意外启动新 Turn 必须立即暴露调用漂移。 */
            @Override public CompletionStage<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                          PlanExecutionEventSink events) {
                throw new AssertionError("unexpected start");
            }

            /** 该测试只覆盖 owner stop，意外领取新 Turn 必须立即暴露调用漂移。 */
            @Override public CompletionStage<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                          GoalRepository.PlanTurnClaim claim,
                                                          PlanExecutionEventSink events) {
                throw new AssertionError("unexpected claimed start");
            }

            /** Plan owner 关闭不允许暂停代替停止。 */
            @Override public CompletionStage<Void> pause(PlanExecutionCoordinator.ExecutionRequest request) {
                throw new AssertionError("unexpected pause");
            }

            /** 隐藏 Turn 必须完成真实停止控制后才能写 Plan STOPPED。 */
            @Override public CompletionStage<Void> stop(PlanExecutionCoordinator.ExecutionRequest request) {
                order.add("turn-stop");
                return CompletableFuture.completedFuture(null);
            }

            /** Plan owner 关闭不允许恢复旧 Turn。 */
            @Override public CompletionStage<Void> resume(PlanExecutionCoordinator.ExecutionRequest request,
                                                           PlanExecutionEventSink events) {
                throw new AssertionError("unexpected resume");
            }
        };
        PlanExecutionCoordinator coordinator = new PlanExecutionCoordinator(repository, turns,
                Clock.fixed(NOW, ZoneOffset.UTC), evaluator);
        coordinator.bindPlanCommitObserver(planId -> {
            order.add("observer");
            throw new IllegalStateException("observer is closed");
        });

        coordinator.verify(verifying);
        assertDoesNotThrow(() -> coordinator.stopOwners(Set.of("thr_side_stop")));

        assertEquals(List.of("evaluator-cancel", "turn-stop", "plan-stop", "observer"), order);
        assertEquals(true, evaluation.get().isCompletedExceptionally());
    }

    /** 真实长度 identity 的验收键不能超过持久化上限，且相同事实必须产生稳定幂等键。 */
    @Test void verificationKeyFitsPersistedIdentityBudget() {
        String planId = "plan_" + "a".repeat(32);
        var verifying = plan(planId, GoalModels.PlanStatus.VERIFYING, 6);
        var definition = new GoalModels.PlanDefinition("验证", List.of(), List.of(), List.of(), List.of(),
                List.of(new GoalModels.PlanStep("step_one", "实施", "验证", true, List.of())),
                List.of(new GoalModels.AcceptanceCriterion("criterion_one", "通过", true)), List.of(), List.of("测试"));
        var revision = new GoalModels.PlanRevision(verifying.activePlanRevisionId(), planId, 1,
                definition, "{}", "b".repeat(64), "USER_UI", NOW);
        List<String> keys = new java.util.ArrayList<>();
        GoalRepository repository = (GoalRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{GoalRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "readPlanSnapshot" -> new GoalModels.PlanSnapshot(verifying, null, revision, null, List.of(), 1);
                    case "listPlanEvidence" -> List.of();
                    case "readPlanRunBudget" -> Optional.of(new GoalRepository.PlanRunBudget(planId, verifying.activeRunId(), 2, 1, 1000));
                    case "completePlanVerification" -> {
                        var command = (GoalRepository.CompletePlanVerification) args[0];
                        keys.add(command.idempotencyKey());
                        assertEquals(true, command.idempotencyKey().length() <= 128);
                        yield plan(planId, GoalModels.PlanStatus.COMPLETED, 7);
                    }
                    default -> throw new AssertionError(method.getName());
                });
        var turns = (PlanExecutionCoordinator.PlanExecutionTurnPort) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{PlanExecutionCoordinator.PlanExecutionTurnPort.class},
                (proxy, method, args) -> { throw new AssertionError("verification cannot start a Tool Turn"); });
        var coordinator = new PlanExecutionCoordinator(repository, turns, Clock.fixed(NOW, ZoneOffset.UTC),
                (snapshot, evidence, context) -> CompletableFuture.completedFuture(
                        new io.github.kongweiguang.ja.goal.port.out.PlanEvaluatorPort.Evaluation(GoalModels.EvaluationVerdict.MET, "通过")));
        coordinator.verify(verifying).toCompletableFuture().join();
        coordinator.verify(verifying).toCompletableFuture().join();
        assertEquals(keys.getFirst(), keys.getLast());
    }

    /** 测试使用最小合法 Plan projection，避免断言依赖实现层 JSON 或运行时字段。 */
    private static GoalModels.Plan plan(String id, GoalModels.PlanStatus status, long revision) {
        return new GoalModels.Plan(id, "thr_" + id, "执行计划", status, revision,
                "planrev_" + id.substring("plan_".length()), "run_" + id.substring("plan_".length()), NOW, NOW);
    }
}
