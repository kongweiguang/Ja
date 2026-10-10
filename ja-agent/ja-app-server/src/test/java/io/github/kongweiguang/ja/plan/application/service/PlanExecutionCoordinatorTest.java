// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.service;

import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.plan.application.api.PlanExecutionEventSink;
import io.github.kongweiguang.ja.plan.application.gateway.PlanEvaluatorPort;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.domain.PlanModels.EvaluationVerdict;
import io.github.kongweiguang.ja.plan.domain.PlanModels.Plan;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanDefinition;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanRevision;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanSnapshot;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanStatus;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanStep;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证真实 Turn 终态与客观验收结果共同控制同一 Plan Run 的续跑边界。 */
final class PlanExecutionCoordinatorTest {
    private static final String PLAN_ID = "plan_coordinator_test";
    private static final String THREAD_ID = "thr_coordinator_test";
    private static final String REVISION_ID = "planrev_coordinator_test";
    private static final String RUN_ID = "run_coordinator_test";
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    /** 实际 FAILED/CANCELLED 结果必须走现有异常暂停收口，不能领取同 Run 的下一 Turn。 */
    @Test
    void failedAndCancelledTurnDoNotClaimAnotherTurn() {
        for (TurnState terminalState : List.of(TurnState.FAILED, TurnState.CANCELLED)) {
            Fixture fixture = new Fixture(List.of(terminalState, TurnState.COMPLETED),
                    List.of(EvaluationVerdict.NOT_MET, EvaluationVerdict.MET));

            assertThrows(RuntimeException.class, () -> fixture.start().toCompletableFuture().join());

            assertEquals(1, fixture.repository.turnClaims.size());
            assertEquals(1, fixture.turnStarts);
            assertEquals(1, fixture.repository.incompleteSettlements);
            assertEquals(0, fixture.evaluationCount);
        }
    }

    /** 正常 COMPLETED 后 NOT_MET 必须在原 Run 创建下一 Turn，直到 MET 才停止推进。 */
    @Test
    void completedNotMetContinuesSameRunUntilMet() {
        Fixture fixture = new Fixture(List.of(TurnState.COMPLETED, TurnState.COMPLETED),
                List.of(EvaluationVerdict.NOT_MET, EvaluationVerdict.MET));

        fixture.start().toCompletableFuture().join();

        assertEquals(2, fixture.repository.turnClaims.size());
        assertEquals(List.of(RUN_ID, RUN_ID), fixture.repository.turnClaims.stream()
                .map(PlanRepository.PlanTurnClaim::runId).toList());
        assertEquals(2, fixture.turnStarts);
        assertEquals(2, fixture.evaluationCount);
        assertEquals(PlanStatus.COMPLETED, fixture.repository.current.status());
    }

    /** INCONCLUSIVE 是验收暂停事实，不能被 Turn 成功完成误判为可自动续跑。 */
    @Test
    void inconclusiveVerificationKeepsRunPaused() {
        Fixture fixture = new Fixture(List.of(TurnState.COMPLETED),
                List.of(EvaluationVerdict.INCONCLUSIVE));

        fixture.start().toCompletableFuture().join();

        assertEquals(1, fixture.repository.turnClaims.size());
        assertEquals(PlanStatus.PAUSED, fixture.repository.current.status());
    }

    /** 每个场景独立装配持久 Plan、ACL 与 Coordinator，避免测试次序改变 Run 事实。 */
    private static final class Fixture {
        private final FakePlanRepository repository = new FakePlanRepository();
        private final ArrayDeque<TurnState> turnStates;
        private final ArrayDeque<EvaluationVerdict> verdicts;
        private int turnStarts;
        private int evaluationCount;
        private final PlanExecutionCoordinator coordinator;

        /** 冻结执行结果与验收顺序，构造一次性、可重复的 Run 测试场景。 */
        private Fixture(List<TurnState> turnStates, List<EvaluationVerdict> evaluations) {
            this.turnStates = new ArrayDeque<>(turnStates);
            verdicts = new ArrayDeque<>(evaluations);
            PlanExecutionCoordinator.PlanExecutionTurnPort turns = new PlanExecutionCoordinator.PlanExecutionTurnPort() {
                /** Coordinator 单测以最小终态 stage 驱动续跑，不重复覆盖 ACL 的结果映射契约。 */
                @Override
                public CompletionStage<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                   PlanExecutionEventSink events) {
                    return nextTurnCompletion();
                }

                /** 带 claim 的入口与普通入口共用终态 stage，claim 本身由 fake repository 记录。 */
                @Override
                public CompletionStage<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                   PlanRepository.PlanTurnClaim claim,
                                                   PlanExecutionEventSink events) {
                    assertEquals(request.turnId(), claim.turnId());
                    return nextTurnCompletion();
                }

                /** 本组合用例不请求暂停控制。 */
                @Override
                public CompletionStage<Void> pause(PlanExecutionCoordinator.ExecutionRequest request) {
                    return CompletableFuture.completedFuture(null);
                }

                /** 本组合用例不请求停止控制。 */
                @Override
                public CompletionStage<Void> stop(PlanExecutionCoordinator.ExecutionRequest request) {
                    return CompletableFuture.completedFuture(null);
                }

                /** 本组合用例不请求恢复控制。 */
                @Override
                public CompletionStage<Void> resume(PlanExecutionCoordinator.ExecutionRequest request,
                                                    PlanExecutionEventSink events) {
                    return CompletableFuture.completedFuture(null);
                }
            };
            PlanEvaluatorPort evaluator = (snapshot, evidence, context) -> {
                evaluationCount++;
                return CompletableFuture.completedFuture(new PlanEvaluatorPort.Evaluation(
                        verdicts.removeFirst(), "objective verdict"));
            };
            coordinator = new PlanExecutionCoordinator(repository.port, turns,
                    Clock.fixed(NOW, ZoneOffset.UTC), evaluator);
        }

        /** 通过公开 Coordinator 入口启动唯一的测试 Run。 */
        private CompletionStage<Void> start() {
            return coordinator.start(repository.current, PlanExecutionEventSink.noop());
        }

        /** Coordinator 仅在 adapter 已确认 COMPLETED 时才应看到正常 stage。 */
        private CompletionStage<Void> nextTurnCompletion() {
            turnStarts++;
            return switch (turnStates.removeFirst()) {
                case COMPLETED -> CompletableFuture.completedFuture(null);
                case FAILED -> CompletableFuture.failedFuture(new IllegalStateException("Turn failed"));
                case CANCELLED -> CompletableFuture.failedFuture(new CancellationException("Turn cancelled"));
                default -> throw new IllegalArgumentException("terminal TurnState required");
            };
        }
    }

    /** 仅模拟 Coordinator 读写的 Plan CAS，保留 revision/run 身份并计数真实 Turn claim。 */
    private static final class FakePlanRepository {
        private final PlanRevision revision = revision();
        private final List<PlanRepository.PlanTurnClaim> turnClaims = new ArrayList<>();
        private Plan current = new Plan(PLAN_ID, THREAD_ID, "objective", PlanStatus.EXECUTING, 1,
                REVISION_ID, RUN_ID, NOW, NOW);
        private int incompleteSettlements;
        private final PlanRepository port = (PlanRepository) Proxy.newProxyInstance(
                PlanRepository.class.getClassLoader(), new Class<?>[]{PlanRepository.class}, this::invoke);

        /** 只实现本组合路径实际使用的仓储事务，未预期访问立即失败。 */
        private Object invoke(Object proxy, java.lang.reflect.Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "readPlanSnapshot" -> snapshot();
                case "claimPlanTurn" -> claim((PlanRepository.ClaimPlanTurn) arguments[0]);
                case "beginPlanVerification" -> beginVerification(
                        (PlanRepository.BeginPlanVerification) arguments[0]);
                case "listPlanEvidence" -> List.of();
                case "completePlanVerification" -> completeVerification(
                        (PlanRepository.CompletePlanVerification) arguments[0]);
                case "settlePlanExecution" -> settleIncomplete();
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }

        /** 返回单次事务快照，保证 Coordinator 每次续跑都看到当前 revision。 */
        private PlanSnapshot snapshot() {
            return new PlanSnapshot(current, null, revision, null, List.of(), 0);
        }

        /** 记录真实 claim 参数，便于断言相邻 Turn 沿用同一个 Run identity。 */
        private Optional<PlanRepository.PlanTurnClaim> claim(PlanRepository.ClaimPlanTurn command) {
            PlanRepository.PlanTurnClaim claim = new PlanRepository.PlanTurnClaim(command.planId(),
                    command.runId(), command.planRevisionId(), command.turnId(), turnClaims.size() + 1);
            turnClaims.add(claim);
            return Optional.of(claim);
        }

        /** 进入验收只推进 Plan revision，不变更已批准的 Run 与冻结版本。 */
        private Plan beginVerification(PlanRepository.BeginPlanVerification command) {
            current = withStatus(PlanStatus.VERIFYING);
            return current;
        }

        /** 验收结论按权威状态映射；NOT_MET 留在原 Run，INCONCLUSIVE 保留暂停状态。 */
        private Plan completeVerification(PlanRepository.CompletePlanVerification command) {
            PlanStatus status = switch (command.verdict()) {
                case MET -> PlanStatus.COMPLETED;
                case NOT_MET -> PlanStatus.EXECUTING;
                case INCONCLUSIVE -> PlanStatus.PAUSED;
            };
            current = withStatus(status);
            return current;
        }

        /** 真实 Turn 失败沿用 Coordinator 的不完整 Run 收口，不创建新执行身份。 */
        private Plan settleIncomplete() {
            incompleteSettlements++;
            current = withStatus(PlanStatus.PAUSED);
            return current;
        }

        /** 只改变测试状态和 revision，Run 与 approved revision 始终保持固定。 */
        private Plan withStatus(PlanStatus status) {
            current = new Plan(current.planId(), current.ownerThreadId(), current.objective(), status,
                    current.revision() + 1, current.activePlanRevisionId(), current.activeRunId(), NOW, NOW);
            return current;
        }
    }

    /** 构造带有效步骤与验收条件的冻结 Plan revision，保持 PlanSnapshot 的真实不变量。 */
    private static PlanRevision revision() {
        PlanDefinition definition = new PlanDefinition("objective", List.of("scope"), List.of(), List.of(),
                List.of(), List.of(new PlanStep("step_test", "inspect", "inspect", true, List.of())),
                List.of(new PlanModels.AcceptanceCriterion("criterion_test", "verified", true)),
                List.of(), List.of("verify committed evidence"));
        return new PlanRevision(REVISION_ID, PLAN_ID, 1, definition, "{}", "a".repeat(64), "USER_UI", NOW);
    }

}
