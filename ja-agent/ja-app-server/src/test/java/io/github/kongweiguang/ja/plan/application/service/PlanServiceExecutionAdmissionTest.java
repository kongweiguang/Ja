// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.plan.infrastructure.persistence.serialization.CanonicalPlanJson;
import io.github.kongweiguang.ja.plan.application.api.PlanExecutionEventSink;
import io.github.kongweiguang.ja.plan.application.api.PlanUseCase.PlanTurnContext;
import io.github.kongweiguang.ja.plan.application.api.PlanUseCase.ExecutePlan;
import io.github.kongweiguang.ja.plan.application.api.PlanUseCase.PlanControl;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepositoryException;
import io.github.kongweiguang.ja.plan.domain.PlanModels.AcceptanceCriterion;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定 Plan 执行前置依赖失败时不产生无人接管的持久 run。 */
final class PlanServiceExecutionAdmissionTest {
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    /** 首次执行与相同 operation key 的快照重放只准许首次 Run 进入 Turn admission。 */
    @Test
    void replayOfExecutingPlanDoesNotAdmitAnotherTurn() {
        ExecuteReplayFixture fixture = new ExecuteReplayFixture();
        PlanService service = service(fixture.repository, fixture.coordinator);
        PlanExecutionEventSink sink = PlanExecutionEventSink.noop();

        Plan first = service.executePlan(execute(), sink);
        Plan replayed = service.executePlan(execute(), sink);

        assertEquals(first.activeRunId(), replayed.activeRunId());
        assertEquals(1, fixture.turnAdmissions.get());
        assertEquals(1, fixture.claims.get());
        assertSame(sink, fixture.receivedSink.get());
    }

    /** 迟到的同键回执可能已暂停或完成；读取快照不能重新 start 一个不可执行的旧 Run。 */
    @Test
    void replayAfterPauseOrCompletionReturnsSnapshotWithoutStarting() {
        for (PlanStatus status : List.of(PlanStatus.PAUSED, PlanStatus.COMPLETED)) {
            ExecuteReplayFixture fixture = new ExecuteReplayFixture();
            PlanService service = service(fixture.repository, fixture.coordinator);
            PlanExecutionEventSink sink = PlanExecutionEventSink.noop();
            Plan first = service.executePlan(execute(), sink);
            fixture.setStatus(status);

            Plan replayed = assertDoesNotThrow(() -> service.executePlan(execute(), sink));

            assertEquals(status, replayed.status());
            assertEquals(first.activeRunId(), replayed.activeRunId());
            assertEquals(1, fixture.turnAdmissions.get());
            assertEquals(1, fixture.claims.get());
        }
    }

    /** 并发相同键请求由仓储折叠为同一 Run，Service 仅让创建者启动一次 Turn。 */
    @Test
    void concurrentExecuteReplayAdmitsOnlyTheCreatedRun() throws Exception {
        ExecuteReplayFixture fixture = new ExecuteReplayFixture();
        PlanService service = service(fixture.repository, fixture.coordinator);
        PlanExecutionEventSink sink = PlanExecutionEventSink.noop();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<Plan> first = callers.submit(() -> executeConcurrently(service, sink, ready, start));
            Future<Plan> second = callers.submit(() -> executeConcurrently(service, sink, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS), "both execute requests must reach the gate");
            start.countDown();

            Plan firstResult = first.get(5, TimeUnit.SECONDS);
            Plan secondResult = second.get(5, TimeUnit.SECONDS);

            assertEquals(firstResult.activeRunId(), secondResult.activeRunId());
            assertEquals(1, fixture.turnAdmissions.get());
            assertEquals(1, fixture.claims.get());
        } finally {
            start.countDown();
            callers.shutdownNow();
        }
    }

    /** 同一恢复操作的持久重放只返回当前快照，不得再次唤醒已恢复或已终结的 Run。 */
    @Test
    void resumeReplayReturnsCurrentSnapshotWithoutAdmittingAnotherResume() {
        for (PlanStatus laterStatus : List.of(PlanStatus.EXECUTING, PlanStatus.PAUSED,
                PlanStatus.COMPLETED, PlanStatus.STOPPED)) {
            ResumeReplayFixture fixture = new ResumeReplayFixture();
            PlanService service = service(fixture.repository, fixture.coordinator);
            PlanControl command = control();

            Plan resumed = service.resumePlan(command, PlanExecutionEventSink.noop());
            fixture.setStatus(laterStatus);
            Plan replayed = assertDoesNotThrow(
                    () -> service.resumePlan(command, PlanExecutionEventSink.noop()));

            assertEquals(PlanStatus.EXECUTING, resumed.status());
            assertEquals(laterStatus, replayed.status());
            assertEquals(1, fixture.resumeCalls.get());
        }
    }

    /** 已暂停 Run 恢复后重放旧 pause key 必须在触碰 coordinator 前识别为持久重放。 */
    @Test
    void pauseReplayAfterResumeDoesNotControlRunAgain() {
        PlanControlReplayFixture fixture = new PlanControlReplayFixture();
        PlanService service = service(fixture.repository, fixture.coordinator);

        Plan paused = service.pausePlan(fixture.control("pause:replay"));
        Plan resumed = service.resumePlan(fixture.control("resume:after-pause"), PlanExecutionEventSink.noop());
        Plan replayedPause = service.pausePlan(fixture.control(resumed, "pause:replay"));

        assertEquals(PlanStatus.PAUSED, paused.status());
        assertEquals(PlanStatus.EXECUTING, resumed.status());
        assertEquals(PlanStatus.EXECUTING, replayedPause.status());
        assertEquals(1, fixture.pauseCalls.get());
    }

    /** stop 的跨活动 key 复用在建立 fence 前拒绝，终态同键重放也不重复控制。 */
    @Test
    void controlReplayAndCrossActivityDoNotReachCoordinator() {
        PlanControlReplayFixture fixture = new PlanControlReplayFixture();
        PlanService service = service(fixture.repository, fixture.coordinator);

        Plan paused = service.pausePlan(fixture.control("control:shared-key"));
        PlanRepositoryException crossActivity = assertThrows(PlanRepositoryException.class,
                () -> service.stopPlan(fixture.control(paused, "control:shared-key")));
        assertEquals(PlanRepositoryException.Code.PLAN_INVALID_STATE, crossActivity.code());
        assertEquals(0, fixture.stopCalls.get());

        Plan stopped = service.stopPlan(fixture.control(paused, "stop:terminal-replay"));
        Plan replayedStop = service.stopPlan(fixture.control(stopped, "stop:terminal-replay"));

        assertEquals(PlanStatus.STOPPED, stopped.status());
        assertEquals(stopped, replayedStop);
        assertEquals(1, fixture.stopCalls.get());
    }

    /** 新 stop key 在 PAUSED 状态仍必须等待真实控制，而不是把 fence 重放规则误用于合法停止。 */
    @Test
    void newStopOperationStillControlsPausedRun() {
        PlanControlReplayFixture fixture = new PlanControlReplayFixture();
        PlanService service = service(fixture.repository, fixture.coordinator);
        Plan paused = service.pausePlan(fixture.control("pause:before-stop"));

        Plan stopped = service.stopPlan(fixture.control(paused, "stop:new-on-paused"));

        assertEquals(PlanStatus.STOPPED, stopped.status());
        assertEquals(1, fixture.stopCalls.get());
    }

    /** 并发 fixture 在同一屏障后提交完全相同的用户操作身份。 */
    private static Plan executeConcurrently(PlanService service, PlanExecutionEventSink sink,
                                            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("execute race gate timed out");
        }
        return service.executePlan(execute(), sink);
    }

    /** 真实协调器配合精简事务回执仓储，验证 Service 重放不会跨越 Turn admission 边界。 */
    private static final class ExecuteReplayFixture {
        private final AtomicReference<Plan> current = new AtomicReference<>();
        private final AtomicInteger claims = new AtomicInteger();
        private final AtomicInteger turnAdmissions = new AtomicInteger();
        private final AtomicReference<PlanExecutionEventSink> receivedSink = new AtomicReference<>();
        private final PlanRepository repository = (PlanRepository) Proxy.newProxyInstance(
                PlanRepository.class.getClassLoader(), new Class<?>[]{PlanRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "executePlan" -> execute((PlanRepository.ExecutePlan) args[0]);
                    case "claimPlanTurn" -> claim((PlanRepository.ClaimPlanTurn) args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        private final PlanExecutionCoordinator coordinator = new PlanExecutionCoordinator(repository,
                new PlanExecutionCoordinator.PlanExecutionTurnPort() {
                    /** Admission 保持挂起，避免测试运行到后续验收或下一 Turn。 */
                    @Override
                    public CompletableFuture<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                         PlanRepository.PlanTurnClaim claim,
                                                         PlanExecutionEventSink events) {
                        turnAdmissions.incrementAndGet();
                        receivedSink.set(events);
                        return new CompletableFuture<>();
                    }

                    /** Service 回归只覆盖按持久 claim 的真实 coordinator 路径。 */
                    @Override
                    public CompletableFuture<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                         PlanExecutionEventSink events) {
                        throw new AssertionError("Plan coordinator must start with a persisted claim");
                    }

                    /** 当前回归不触发暂停控制。 */
                    @Override
                    public CompletableFuture<Void> pause(PlanExecutionCoordinator.ExecutionRequest request) {
                        return CompletableFuture.completedFuture(null);
                    }

                    /** 当前回归不触发停止控制。 */
                    @Override
                    public CompletableFuture<Void> stop(PlanExecutionCoordinator.ExecutionRequest request) {
                        return CompletableFuture.completedFuture(null);
                    }

                    /** 当前回归不触发恢复控制。 */
                    @Override
                    public CompletableFuture<Void> resume(PlanExecutionCoordinator.ExecutionRequest request,
                                                          PlanExecutionEventSink events) {
                        return CompletableFuture.completedFuture(null);
                    }
                }, Clock.fixed(NOW, ZoneOffset.UTC));

        /** 同一操作键的仓储重放始终返回当前权威 Plan，而非重新创建 Run。 */
        private synchronized Plan execute(PlanRepository.ExecutePlan command) {
            Plan result = current.get();
            if (result == null) {
                result = plan(command.runId(), PlanStatus.EXECUTING, 3);
                current.set(result);
            }
            return result;
        }

        /** 只有当前执行 Run 才能领取；重放后的暂停或终态快照不给旧 Run 新建 claim。 */
        private Optional<PlanRepository.PlanTurnClaim> claim(PlanRepository.ClaimPlanTurn command) {
            claims.incrementAndGet();
            Plan result = current.get();
            if (result.status() != PlanStatus.EXECUTING
                || !result.activeRunId().equals(command.runId())) {
                return Optional.empty();
            }
            return Optional.of(new PlanRepository.PlanTurnClaim(command.planId(), command.runId(),
                    command.planRevisionId(), command.turnId(), 1));
        }

        /** 测试只推进权威快照状态，不伪造新的 Run 或 Plan revision identity。 */
        private void setStatus(PlanStatus status) {
            Plan before = current.get();
            current.set(plan(before.activeRunId(), status, before.revision() + 1));
        }

        /** 构造仓储已经提交的活动或终态快照，保留同一个冻结 revision 与 Run。 */
        private static Plan plan(String runId, PlanStatus status, long revision) {
            return new Plan("plan_test", "thr_owner", "交付目标", status, revision,
                    "planrev_test", runId, NOW, NOW);
        }
    }

    /** 仓储返回同键的权威当前快照，测试 Service 不把回放误当成新的 resume admission。 */
    private static final class ResumeReplayFixture {
        private final AtomicReference<Plan> current = new AtomicReference<>(
                ExecuteReplayFixture.plan("run_test", PlanStatus.PAUSED, 2));
        private final AtomicInteger resumeCalls = new AtomicInteger();
        private final AtomicBoolean committed = new AtomicBoolean();
        private final PlanRepository repository = (PlanRepository) Proxy.newProxyInstance(
                PlanRepository.class.getClassLoader(), new Class<?>[]{PlanRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "resumePlan" -> {
                        if (committed.compareAndSet(false, true)) {
                            Plan before = current.get();
                            Plan resumed = ExecuteReplayFixture.plan(before.activeRunId(),
                                    PlanStatus.EXECUTING, before.revision() + 1);
                            current.set(resumed);
                            yield new PlanRepository.ResumeResult(resumed, true);
                        }
                        yield new PlanRepository.ResumeResult(current.get(), false);
                    }
                    case "findPlanTurnBinding" -> Optional.empty();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        private final PlanExecutionCoordinator coordinator = new PlanExecutionCoordinator(repository,
                new PlanExecutionCoordinator.PlanExecutionTurnPort() {
                    /** 本回归只观察恢复 admission，不推进 Turn 后续工作。 */
                    @Override
                    public CompletableFuture<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                         PlanExecutionEventSink events) {
                        throw new AssertionError("resume replay must not start a fresh Turn");
                    }

                    /** 本回归不执行首次 Plan Turn。 */
                    @Override
                    public CompletableFuture<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                         PlanRepository.PlanTurnClaim claim,
                                                         PlanExecutionEventSink events) {
                        throw new AssertionError("resume replay must not claim a fresh Turn");
                    }

                    /** 本回归不执行 pause。 */
                    @Override
                    public CompletableFuture<Void> pause(PlanExecutionCoordinator.ExecutionRequest request) {
                        throw new AssertionError("resume replay must not pause the Run");
                    }

                    /** 本回归不执行 stop。 */
                    @Override
                    public CompletableFuture<Void> stop(PlanExecutionCoordinator.ExecutionRequest request) {
                        throw new AssertionError("resume replay must not stop the Run");
                    }

                    /** 恢复 admission 保持挂起，避免引入并发终态推进。 */
                    @Override
                    public CompletableFuture<Void> resume(PlanExecutionCoordinator.ExecutionRequest request,
                                                          PlanExecutionEventSink events) {
                        resumeCalls.incrementAndGet();
                        return new CompletableFuture<>();
                    }
                }, Clock.fixed(NOW, ZoneOffset.UTC));

        /** 后续权威状态可前进，但不会更换最初操作键对应的已提交恢复事实。 */
        private void setStatus(PlanStatus status) {
            Plan before = current.get();
            current.set(ExecuteReplayFixture.plan(before.activeRunId(), status, before.revision() + 1));
        }
    }

    /** Service fake 只替代持久事务；操作 key/activity 与 coordinator 边界仍显式可断言。 */
    private static final class PlanControlReplayFixture {
        private final AtomicReference<Plan> current = new AtomicReference<>(
                ExecuteReplayFixture.plan("run_test", PlanStatus.EXECUTING, 2));
        private final java.util.Map<String, String> activities = new java.util.HashMap<>();
        private final AtomicInteger pauseCalls = new AtomicInteger();
        private final AtomicInteger stopCalls = new AtomicInteger();
        private final AtomicInteger resumeCalls = new AtomicInteger();
        private final PlanRepository repository = (PlanRepository) Proxy.newProxyInstance(
                PlanRepository.class.getClassLoader(), new Class<?>[]{PlanRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "readPlanSnapshot" -> snapshot();
                    case "findPlanTurnBinding" -> Optional.of(binding());
                    case "requestPlanControlFence" -> requestControlFence(
                            (PlanRepository.RequestPlanPause) args[0], (PlanRepository.PlanControlAction) args[1]);
                    case "pausePlan" -> pause((PlanRepository.PausePlan) args[0]);
                    case "stopPlan" -> stop((PlanRepository.StopPlan) args[0]);
                    case "resumePlan" -> resume((PlanRepository.ResumePlan) args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        private final PlanExecutionCoordinator coordinator = new PlanExecutionCoordinator(repository,
                new PlanExecutionCoordinator.PlanExecutionTurnPort() {
                    /** 本夹具不创建新 Turn。 */
                    @Override
                    public CompletableFuture<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                         PlanExecutionEventSink events) {
                        throw new AssertionError("control replay must not start a Turn");
                    }

                    /** 本夹具不创建带 claim 的新 Turn。 */
                    @Override
                    public CompletableFuture<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                         PlanRepository.PlanTurnClaim claim,
                                                         PlanExecutionEventSink events) {
                        throw new AssertionError("control replay must not claim a Turn");
                    }

                    /** 控制测试以调用计数观察真实 Coordinator 到 Turn adapter 的边界。 */
                    @Override
                    public CompletableFuture<Void> pause(PlanExecutionCoordinator.ExecutionRequest request) {
                        pauseCalls.incrementAndGet();
                        return CompletableFuture.completedFuture(null);
                    }

                    /** 控制测试以调用计数观察真实 Coordinator 到 Turn adapter 的边界。 */
                    @Override
                    public CompletableFuture<Void> stop(PlanExecutionCoordinator.ExecutionRequest request) {
                        stopCalls.incrementAndGet();
                        return CompletableFuture.completedFuture(null);
                    }

                    /** 保持恢复 admission 未完成，避免该夹具进入终态推进。 */
                    @Override
                    public CompletableFuture<Void> resume(PlanExecutionCoordinator.ExecutionRequest request,
                                                          PlanExecutionEventSink events) {
                        resumeCalls.incrementAndGet();
                        return new CompletableFuture<>();
                    }
                }, Clock.fixed(NOW, ZoneOffset.UTC));

        /** 首次 fence 在写入前校验持久活动，匹配重放不要求同一个历史 revision。 */
        private PlanRepository.PlanControlFenceResult requestControlFence(
                PlanRepository.RequestPlanPause command, PlanRepository.PlanControlAction action) {
            String existing = activities.get(command.operationKey());
            if (existing != null) {
                if (!existing.equals(action.activity())) {
                    throw new PlanRepositoryException(PlanRepositoryException.Code.PLAN_INVALID_STATE,
                            "Plan idempotency key was reused");
                }
                return new PlanRepository.PlanControlFenceResult(current.get(), false);
            }
            Plan plan = current.get();
            if (plan.revision() != command.expectedPlanRevision()
                || !plan.activeRunId().equals(command.runId())) {
                throw new PlanRepositoryException(PlanRepositoryException.Code.PLAN_REVISION_CONFLICT,
                        "Plan revision conflict");
            }
            boolean pausable = plan.status() == PlanStatus.EXECUTING || plan.status() == PlanStatus.VERIFYING;
            if ((action == PlanRepository.PlanControlAction.PAUSE && !pausable)
                || (action == PlanRepository.PlanControlAction.STOP
                    && !pausable && plan.status() != PlanStatus.PAUSED)) {
                throw new PlanRepositoryException(PlanRepositoryException.Code.PLAN_INVALID_STATE,
                        "Plan control is not allowed");
            }
            return new PlanRepository.PlanControlFenceResult(plan, true);
        }

        /** pause 终态事件记入操作 key，之后同键请求只回当前快照。 */
        private Plan pause(PlanRepository.PausePlan command) {
            String existing = activities.get(command.operationKey());
            if (existing != null) {
                if (!"plan_paused".equals(existing)) {
                    throw new PlanRepositoryException(PlanRepositoryException.Code.PLAN_INVALID_STATE,
                            "Plan idempotency key was reused");
                }
                return current.get();
            }
            Plan before = current.get();
            Plan paused = ExecuteReplayFixture.plan(before.activeRunId(), PlanStatus.PAUSED, before.revision() + 1);
            activities.put(command.operationKey(), "plan_paused");
            current.set(paused);
            return paused;
        }

        /** stop 终态事件记入操作 key，重放不再改变 Run 或调用 coordinator。 */
        private Plan stop(PlanRepository.StopPlan command) {
            String existing = activities.get(command.operationKey());
            if (existing != null) {
                if (!"plan_stopped".equals(existing)) {
                    throw new PlanRepositoryException(PlanRepositoryException.Code.PLAN_INVALID_STATE,
                            "Plan idempotency key was reused");
                }
                return current.get();
            }
            Plan before = current.get();
            Plan stopped = ExecuteReplayFixture.plan(before.activeRunId(), PlanStatus.STOPPED, before.revision() + 1);
            activities.put(command.operationKey(), "plan_stopped");
            current.set(stopped);
            return stopped;
        }

        /** 已提交恢复 replay 返回当前事实，首次恢复仅从 PAUSED 推进 EXECUTING。 */
        private PlanRepository.ResumeResult resume(PlanRepository.ResumePlan command) {
            String existing = activities.get(command.operationKey());
            if (existing != null) {
                if (!"plan_resumed".equals(existing)) {
                    throw new PlanRepositoryException(PlanRepositoryException.Code.PLAN_INVALID_STATE,
                            "Plan idempotency key was reused");
                }
                return new PlanRepository.ResumeResult(current.get(), false);
            }
            Plan before = current.get();
            Plan resumed = ExecuteReplayFixture.plan(before.activeRunId(), PlanStatus.EXECUTING, before.revision() + 1);
            activities.put(command.operationKey(), "plan_resumed");
            current.set(resumed);
            return new PlanRepository.ResumeResult(resumed, true);
        }

        /** 生成当前状态完整身份快照，Coordinator 只读取已持久绑定的 execution identity。 */
        private PlanSnapshot snapshot() {
            Plan plan = current.get();
            PlanDefinition definition = new PlanDefinition("交付目标", List.of("当前范围"), List.of(), List.of(),
                    List.of(), List.of(new PlanStep("step_snapshot", "实施", "完成实现", true, List.of())),
                    List.of(new AcceptanceCriterion("criterion_snapshot", "验证通过", true)), List.of(),
                    List.of("运行测试"));
            PlanRevision revision = new PlanRevision(plan.activePlanRevisionId(), plan.planId(), 1, definition,
                    "{}", "a".repeat(64), "AGENT", NOW);
            return new PlanSnapshot(plan, null, revision, null, List.of(), plan.revision());
        }

        /** 将 Run 当前唯一绑定成可控制 Turn，确保计数来自 coordinator 的持久身份分支。 */
        private PlanRepository.InternalTurnBinding binding() {
            Plan plan = current.get();
            return new PlanRepository.InternalTurnBinding("turn_test", "PLAN_EXECUTION", plan.planId(),
                    plan.activeRunId(), plan.activePlanRevisionId(), "a".repeat(64));
        }

        /** 构造使用当前权威 revision 的控制命令，不通过旧 revision 恰好跳过控制。 */
        private PlanControl control(String operationKey) {
            return control(current.get(), operationKey);
        }

        /** 控制重放可明确携带已推进的最新 Plan revision。 */
        private PlanControl control(Plan plan, String operationKey) {
            return new PlanControl(plan.planId(), plan.revision(), plan.activeRunId(), operationKey, NOW);
        }
    }

    /** 缺少 coordinator 时 execute/resume 必须在调用仓储前失败。 */
    @Test
    void requiresCoordinatorBeforePersistingRun() {
        AtomicInteger repositoryCalls = new AtomicInteger();
        PlanRepository repository = repository(repositoryCalls);
        PlanService service = service(repository, null);

        assertThrows(IllegalStateException.class,
                () -> service.executePlan(execute(), PlanExecutionEventSink.noop()));
        assertThrows(IllegalStateException.class,
                () -> service.resumePlan(control(), PlanExecutionEventSink.noop()));

        assertEquals(0, repositoryCalls.get());
    }

    /** 缺失事件出口必须在 execute/resume 的数据库 mutation 之前被拒绝。 */
    @Test
    void requiresEventSinkBeforePersistingRun() {
        AtomicInteger repositoryCalls = new AtomicInteger();
        PlanRepository repository = repository(repositoryCalls);
        PlanExecutionCoordinator executions = new PlanExecutionCoordinator(repository,
                new PlanExecutionCoordinator.PlanExecutionTurnPort() {
                    /** 前置条件应在 Turn 准入前拒绝，测试端口不产生执行副作用。 */
                    @Override
                    public CompletableFuture<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                         PlanExecutionEventSink events) {
                        return CompletableFuture.completedFuture(null);
                    }

                    /** 前置条件测试不会启动 Turn。 */
                    @Override
                    public CompletableFuture<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                         PlanRepository.PlanTurnClaim claim,
                                                         PlanExecutionEventSink events) {
                        return CompletableFuture.completedFuture(null);
                    }

                    /** 前置条件测试不会暂停 Turn。 */
                    @Override
                    public CompletableFuture<Void> pause(PlanExecutionCoordinator.ExecutionRequest request) {
                        return CompletableFuture.completedFuture(null);
                    }

                    /** 前置条件测试不会停止 Turn。 */
                    @Override
                    public CompletableFuture<Void> stop(PlanExecutionCoordinator.ExecutionRequest request) {
                        return CompletableFuture.completedFuture(null);
                    }

                    /** 前置条件测试不会恢复 Turn。 */
                    @Override
                    public CompletableFuture<Void> resume(PlanExecutionCoordinator.ExecutionRequest request,
                                                          PlanExecutionEventSink events) {
                        return CompletableFuture.completedFuture(null);
                    }
                },
                Clock.fixed(NOW, ZoneOffset.UTC));
        PlanService service = service(repository, executions);

        assertThrows(NullPointerException.class, () -> service.executePlan(execute(), null));
        assertThrows(NullPointerException.class, () -> service.resumePlan(control(), null));

        assertEquals(0, repositoryCalls.get());
    }

    /** Turn 控制只接受 binding 与持久 Plan/run/revision/hash 全匹配的冻结身份。 */
    @Test
    void planTurnControlContextRejectsPersistedIdentityMismatch() {
        PlanSnapshot snapshot = planSnapshot();
        String matchingHash = snapshot.currentRevision().planHash();
        List<PlanRepository.InternalTurnBinding> mismatches = List.of(
                new PlanRepository.InternalTurnBinding("turn_other", "PLAN_EXECUTION",
                        "plan_snapshot", "run_snapshot", "planrev_snapshot", matchingHash),
                binding("plan_other", "run_snapshot", "planrev_snapshot", matchingHash),
                binding("plan_snapshot", "run_other", "planrev_snapshot", matchingHash),
                binding("plan_snapshot", "run_snapshot", "planrev_other", matchingHash),
                binding("plan_snapshot", "run_snapshot", "planrev_snapshot", "b".repeat(64)));

        for (PlanRepository.InternalTurnBinding mismatch : mismatches) {
            PlanService service = service(bindingRepository(mismatch, snapshot), null);
            PlanRepositoryException failure = assertThrows(PlanRepositoryException.class,
                    () -> service.planTurnControlContext("thr_owner", "turn_binding"));
            assertEquals(PlanRepositoryException.Code.PLAN_RECOVERY_REQUIRED, failure.code());
        }

        PlanService ownerMismatch = service(bindingRepository(
                binding("plan_snapshot", "run_snapshot", "planrev_snapshot", matchingHash), snapshot), null);
        PlanRepositoryException ownerFailure = assertThrows(PlanRepositoryException.class,
                () -> ownerMismatch.planTurnControlContext("thr_other", "turn_binding"));
        assertEquals(PlanRepositoryException.Code.PLAN_RECOVERY_REQUIRED, ownerFailure.code());
    }

    /** Repository 解码失败属于不可核实的持久 binding，统一暴露恢复错误且不尝试普通 Turn 回退。 */
    @Test
    void planTurnControlContextMapsMalformedBindingToRecoveryRequired() {
        PlanRepository repository = (PlanRepository) Proxy.newProxyInstance(
                PlanRepository.class.getClassLoader(), new Class<?>[]{PlanRepository.class},
                (proxy, method, args) -> {
                    if ("findInternalTurnBinding".equals(method.getName())) {
                        throw new PlanRepositoryException(PlanRepositoryException.Code.PLAN_INVALID_STATE,
                                "fixture binding decode failure");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        PlanService service = service(repository, null);

        PlanRepositoryException failure = assertThrows(PlanRepositoryException.class,
                () -> service.planTurnControlContext("thr_owner", "turn_binding"));

        assertEquals(PlanRepositoryException.Code.PLAN_RECOVERY_REQUIRED, failure.code());
    }

    /** 精确 binding 映射回同一个控制上下文，防止失败关闭检查误拒绝合法 Plan Turn。 */
    @Test
    void planTurnControlContextReturnsMatchingPersistedIdentity() {
        PlanSnapshot snapshot = planSnapshot();
        PlanService service = service(bindingRepository(binding("plan_snapshot", "run_snapshot",
                "planrev_snapshot", snapshot.currentRevision().planHash()), snapshot), null);

        assertEquals(new PlanTurnContext("plan_snapshot", 3, PlanStatus.EXECUTING,
                        "planrev_snapshot", "run_snapshot"),
                service.planTurnControlContext("thr_owner", "turn_binding").orElseThrow());
    }

    /** Repository proxy 在任何访问时计数并失败，避免测试误把返回值当作执行已受理。 */
    private static PlanRepository repository(AtomicInteger calls) {
        return (PlanRepository) Proxy.newProxyInstance(PlanRepository.class.getClassLoader(),
                new Class<?>[]{PlanRepository.class}, (proxy, method, args) -> {
                    calls.incrementAndGet();
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** 测试仅装配 PlanService 的真实前置校验，不创建任何数据库或执行线程。 */
    private static PlanService service(PlanRepository repository, PlanExecutionCoordinator executions) {
        PlanEventRegistry events = new PlanEventRegistry();
        return new PlanService(repository, new CanonicalPlanJson(new ObjectMapper()), Clock.fixed(NOW, ZoneOffset.UTC),
                1, executions, events, new PlanCommittedPublisher(repository, events));
    }

    /** 最小只读仓储只暴露 binding 查询与其对应的一致 Plan snapshot。 */
    private static PlanRepository bindingRepository(PlanRepository.InternalTurnBinding binding,
                                                    PlanSnapshot snapshot) {
        return (PlanRepository) Proxy.newProxyInstance(PlanRepository.class.getClassLoader(),
                new Class<?>[]{PlanRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findInternalTurnBinding" -> Optional.of(binding);
                    case "readPlanSnapshot" -> snapshot;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** 构造身份可独立变化的持久 binding，覆盖每个跨表关联字段。 */
    private static PlanRepository.InternalTurnBinding binding(String planId, String runId,
                                                               String revisionId, String hash) {
        return new PlanRepository.InternalTurnBinding("turn_binding", "PLAN_EXECUTION",
                planId, runId, revisionId, hash);
    }

    /** 构造满足领域不变量的活动 Plan 快照，故障仅来自 binding 与快照的关联差异。 */
    private static PlanSnapshot planSnapshot() {
        String hash = "a".repeat(64);
        PlanDefinition definition = new PlanDefinition("交付目标", List.of("当前范围"), List.of(), List.of(), List.of(),
                List.of(new PlanStep("step_snapshot", "实施", "完成实现", true, List.of())),
                List.of(new AcceptanceCriterion("criterion_snapshot", "验证通过", true)),
                List.of(), List.of("运行测试"));
        PlanRevision revision = new PlanRevision("planrev_snapshot", "plan_snapshot", 1,
                definition, "{}", hash, "AGENT", NOW);
        Plan plan = new Plan("plan_snapshot", "thr_owner", "交付目标", PlanStatus.EXECUTING,
                3, "planrev_snapshot", "run_snapshot", NOW, NOW);
        return new PlanSnapshot(plan, null, revision, null, List.of(), 0);
    }

    /** 命令使用合法标识，确保失败来自执行前置条件而非参数解析。 */
    private static ExecutePlan execute() {
        return new ExecutePlan("plan_test", 2, "planrev_test", "a".repeat(64),
                1, "operation_execute", NOW);
    }

    /** 恢复命令携带冻结 run identity，测试不依赖活动 Plan 的伪造状态。 */
    private static PlanControl control() {
        return new PlanControl("plan_test", 2, "run_test", "operation_resume", NOW);
    }
}


