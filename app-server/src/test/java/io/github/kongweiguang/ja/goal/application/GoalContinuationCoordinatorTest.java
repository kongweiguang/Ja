// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.domain.GoalModels.Goal;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 continuation 资格判断、隐藏请求与 lease 单飞释放。 */
final class GoalContinuationCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");

    /** active working Goal 只启动一次隐藏 continuation，并在终态释放 fencing lease。 */
    @Test
    void startsOneHiddenContinuationForEligibleGoal() {
        FakeRepository repository = new FakeRepository();
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger notifications = new AtomicInteger();
        GoalContinuationCoordinator.ContinuationTurnPort turns = new GoalContinuationCoordinator.ContinuationTurnPort() {
            /** fixture 始终模拟 owner idle。 */
            @Override public boolean ownerIdle(String threadId) { return true; }
            /** 请求没有 user content，只验证稳定 identity 与 fencing。 */
            @Override public CompletionStage<Void> start(GoalContinuationCoordinator.ContinuationRequest request) {
                starts.incrementAndGet();
                assertEquals("run_one", request.runId());
                assertEquals(7, request.fencingToken());
                return CompletableFuture.completedFuture(null);
            }
        };
        GoalContinuationCoordinator coordinator = new GoalContinuationCoordinator(repository, turns,
                Clock.fixed(NOW, ZoneOffset.UTC), 3, ignored -> notifications.incrementAndGet());

        Optional<CompletionStage<Void>> first = coordinator.continueIfEligible("goal_one");
        Optional<CompletionStage<Void>> second = coordinator.continueIfEligible("goal_one");
        first.orElseThrow().toCompletableFuture().join();

        assertTrue(first.isPresent());
        assertTrue(second.isEmpty());
        assertEquals(1, starts.get());
        assertEquals(1, repository.releases.get());
        assertEquals(1, notifications.get());
        coordinator.close();
    }

    /** Turn barrier 未释放时 lease 保持 HELD，终态后必须先释放 lease 再开放 gate。 */
    @Test
    void settlesGateOnlyAfterTurnAndLeaseAreTerminal() {
        FakeRepository repository = new FakeRepository();
        CompletableFuture<Void> turnTerminal = new CompletableFuture<>();
        AtomicBoolean settled = new AtomicBoolean();
        GoalContinuationCoordinator.ContinuationTurnPort turns = new GoalContinuationCoordinator.ContinuationTurnPort() {
            /** fixture 始终模拟 owner idle。 */
            @Override public boolean ownerIdle(String threadId) { return true; }
            /** 未完成 future 精确控制 WAITING_APPROVAL 到 Turn 终态的竞态窗口。 */
            @Override public CompletionStage<Void> start(GoalContinuationCoordinator.ContinuationRequest request) {
                return turnTerminal;
            }
            /** settled 回调必须观察到持久 lease 已经释放。 */
            @Override public void settled(GoalContinuationCoordinator.ContinuationRequest request) {
                assertEquals(1, repository.releases.get());
                settled.set(true);
            }
        };
        GoalContinuationCoordinator coordinator = new GoalContinuationCoordinator(repository, turns,
                Clock.fixed(NOW, ZoneOffset.UTC), 3);

        CompletionStage<Void> completion = coordinator.continueIfEligible("goal_one").orElseThrow();
        assertEquals(0, repository.releases.get());
        assertFalse(settled.get());

        turnTerminal.complete(null);
        completion.toCompletableFuture().join();

        assertEquals(1, repository.releases.get());
        assertTrue(settled.get());
        coordinator.close();
    }

    /** 幂等释放已由别的收口路径完成时，旧 gate 仍必须按同一 fencing identity 清除。 */
    @Test
    void settlesGateWhenLeaseReleaseIsAlreadyTerminal() {
        FakeRepository repository = new FakeRepository();
        repository.releaseReturnsEmpty = true;
        AtomicBoolean settled = new AtomicBoolean();
        GoalContinuationCoordinator.ContinuationTurnPort turns = new GoalContinuationCoordinator.ContinuationTurnPort() {
            /** fixture 始终模拟 owner idle。 */
            @Override public boolean ownerIdle(String threadId) { return true; }
            /** 已完成 Turn 让测试只聚焦 lease 幂等收口。 */
            @Override public CompletionStage<Void> start(GoalContinuationCoordinator.ContinuationRequest request) {
                return CompletableFuture.completedFuture(null);
            }
            /** Optional.empty 不能把进程内 gate 永久留在 active。 */
            @Override public void settled(GoalContinuationCoordinator.ContinuationRequest request) {
                settled.set(true);
            }
        };
        GoalContinuationCoordinator coordinator = new GoalContinuationCoordinator(repository, turns,
                Clock.fixed(NOW, ZoneOffset.UTC), 3);

        coordinator.continueIfEligible("goal_one").orElseThrow().toCompletableFuture().join();

        assertEquals(1, repository.releases.get());
        assertTrue(settled.get());
        coordinator.close();
    }

    /**
     * 同步启动失败必须先把已获得的 lease 标为 abandoned 并清理 gate，再通过稳定内部异常上抛；
     * 否则后续恢复会被一个不存在的 Turn 永久阻塞。
     */
    @Test
    void synchronousStartFailureReleasesLeaseBeforePropagating() {
        FakeRepository repository = new FakeRepository();
        RuntimeException startFailure = new IllegalStateException("fixture start failure");
        AtomicBoolean settled = new AtomicBoolean();
        GoalContinuationCoordinator.ContinuationTurnPort turns = new GoalContinuationCoordinator.ContinuationTurnPort() {
            /** fixture 始终模拟 owner idle，使调度进入已取得 lease 的启动边界。 */
            @Override public boolean ownerIdle(String threadId) { return true; }
            /** 同步抛错模拟 Turn admission 前的配置或上下文故障。 */
            @Override public CompletionStage<Void> start(GoalContinuationCoordinator.ContinuationRequest request) {
                throw startFailure;
            }
            /** settled 必须发生在异常交还调用方之前，并且只能观察到已释放 lease。 */
            @Override public void settled(GoalContinuationCoordinator.ContinuationRequest request) {
                assertEquals(1, repository.releases.get());
                settled.set(true);
            }
        };
        GoalContinuationCoordinator coordinator = new GoalContinuationCoordinator(repository, turns,
                Clock.fixed(NOW, ZoneOffset.UTC), 3);

        GoalContinuationCoordinator.ContinuationStartFailure observed = assertThrows(
                GoalContinuationCoordinator.ContinuationStartFailure.class,
                () -> coordinator.continueIfEligible("goal_one"));

        assertSame(startFailure, observed.getCause());
        assertEquals("Goal continuation could not be started", observed.getMessage());
        assertEquals(1, repository.releases.get());
        assertTrue(settled.get());
        coordinator.close();
    }

    /** 最小 fake 只实现 coordinator 使用的读取与 lease 方法。 */
    private static final class FakeRepository implements GoalRepository {
        /** continuation 单测没有 Thread 时间线读取，显式空结果避免把新查询混入调度断言。 */
        @Override public List<GoalModels.TerminalActivity> listTerminalActivities(String ownerThreadId, int limit) {
            return List.of();
        }
        private boolean acquired;
        private boolean releaseReturnsEmpty;
        private final AtomicInteger releases = new AtomicInteger();
        /** 返回一条可续跑 Goal。 */
        @Override public Optional<Goal> findGoal(String goalId) {
            return Optional.of(new Goal("goal_one", "thr_one", GoalModels.OwnerKind.ROOT_THREAD, "目标",
                    1, GoalModels.GoalStatus.ACTIVE, GoalModels.GoalPhase.WORKING, 2,
                    "run_one", 0, 0, null, false, NOW, NOW));
        }
        /** owner 发现未用于本测试。 */
        @Override public Optional<Goal> findActiveGoalByOwner(String ownerThreadId) { return Optional.empty(); }
        /** 第一次申请获得 fencing token 7，之后模拟并发 owner 已持有。 */
        @Override public Optional<ContinuationLease> tryAcquireLease(AcquireLease command) {
            if (acquired) return Optional.empty();
            acquired = true;
            return Optional.of(new ContinuationLease(command.goalId(), command.leaseId(), 3, 7,
                    "HELD", NOW, NOW, null));
        }
        /** 释放只计数，不实现数据库细节。 */
        @Override public Optional<ContinuationLease> releaseLease(String goalId, String leaseId,
                                                                   long fencingToken, boolean abandoned, Instant at) {
            releases.incrementAndGet();
            if (releaseReturnsEmpty) return Optional.empty();
            return Optional.of(new ContinuationLease(goalId, leaseId, 3, fencingToken,
                    "RELEASED", NOW, NOW, at));
        }
        /** 即时完成 fixture 没有状态进展，只返回已记录的 fake Goal。 */
        @Override public Goal recordContinuationNoProgress(String goalId, long expectedGoalRevision,
                String eventId, String idempotencyKey, Instant at) {
            return findGoal(goalId).orElseThrow();
        }
        /** 未使用的 create 明确失败，避免 fake 误入其它用例。 */
        @Override public Goal create(CreateGoal command) { throw unsupported(); }
        /** 未使用的 Plan create 明确失败。 */
        @Override public GoalModels.Plan createPlan(CreatePlan command) { throw unsupported(); }
        /** 未使用的 draft 明确失败。 */
        @Override public GoalModels.Plan saveDraft(SaveDraft command) { throw unsupported(); }
        /** 未使用的 draft discard 明确失败。 */
        @Override public GoalModels.Plan discardDraft(DiscardDraft command) { throw unsupported(); }
        /** 未使用的 propose 明确失败。 */
        @Override public GoalModels.PlanRevision propose(ProposePlan command) { throw unsupported(); }
        /** 未使用的 approve 明确失败。 */
        @Override public GoalModels.Plan approve(ApprovePlan command) { throw unsupported(); }
        /** 未使用的 standalone execute 明确失败。 */
        @Override public GoalModels.Plan executePlan(ExecutePlan command) { throw unsupported(); }
        /** 未使用的 standalone settle 明确失败。 */
        @Override public GoalModels.Plan settlePlanExecution(SettlePlanExecution command) { throw unsupported(); }
        /** 未使用的 reject 明确失败。 */
        @Override public GoalModels.Plan reject(RejectPlan command) { throw unsupported(); }
        /** 未使用的 Goal link 明确失败。 */
        @Override public Goal attachPlan(AttachPlan command) { throw unsupported(); }
        /** 未使用的 Goal unlink 明确失败。 */
        @Override public Goal detachPlan(DetachPlan command) { throw unsupported(); }
        /** 未使用的 transition 明确失败。 */
        @Override public Goal transition(Transition command) { throw unsupported(); }
        /** 未使用的 step 明确失败。 */
        @Override public Goal updateStep(UpdateStep command) { throw unsupported(); }
        /** 未使用的 Plan step 明确失败。 */
        @Override public GoalModels.Plan updatePlanStep(UpdatePlanStep command) { throw unsupported(); }
        /** 未使用的 evidence 明确失败。 */
        @Override public GoalModels.Evidence appendEvidence(AppendEvidence command) { throw unsupported(); }
        /** 未使用的 evaluator intent 明确失败。 */
        @Override public Goal requestEvaluation(RequestEvaluation command) { throw unsupported(); }
        /** 未使用的 evaluator settlement 明确失败。 */
        @Override public Goal completeEvaluation(CompleteEvaluation command) { throw unsupported(); }
        /** 未使用的 input request 明确失败。 */
        @Override public Goal requestInput(RequestInput command) { throw unsupported(); }
        /** 未使用的 input response 明确失败。 */
        @Override public Goal respondInput(RespondInput command) { throw unsupported(); }
        /** 未使用的 Tool prepare 明确失败。 */
        @Override public GoalModels.ToolAttempt prepareToolAttempt(PrepareToolAttempt command) { throw unsupported(); }
        /** 未使用的 Tool start 明确失败。 */
        @Override public GoalModels.ToolAttempt startToolAttempt(String toolAttemptId, Instant at) { throw unsupported(); }
        /** 未使用的 Tool settle 明确失败。 */
        @Override public GoalModels.ToolAttempt settleToolAttempt(SettleToolAttempt command) { throw unsupported(); }
        /** 未使用的恢复列表为空。 */
        @Override public List<GoalModels.ToolAttempt> listUnsettledToolAttempts(long generation, int limit) { return List.of(); }
        /** 未使用的 events 返回空页。 */
        @Override public ReadPage<Event> listEvents(String goalId, long afterSequence, int limit) {
            return new ReadPage<>(0, 0, List.of());
        }
        /** coordinator 只需要可选 Plan link；fake 返回同一 Goal revision 的无 Plan 快照。 */
        @Override public GoalModels.GoalSnapshot readSnapshot(String goalId) {
            Goal goal = findGoal(goalId).orElseThrow();
            GoalModels.GoalDefinition definition = new GoalModels.GoalDefinition(
                    goal.goalId(), 1, goal.objective(), List.of(), NOW);
            return new GoalModels.GoalSnapshot(goal, definition, null, null,
                    0, 0, null, null, null, null, null, 1);
        }
        /** 未使用的 Goal definition 明确失败。 */
        @Override public GoalModels.GoalDefinition readGoalDefinition(String goalId, long revision) { throw unsupported(); }
        /** 未使用的 Plan snapshot 明确失败。 */
        @Override public GoalModels.PlanSnapshot readPlanSnapshot(String planId) { throw unsupported(); }
        /** 未使用的草稿读取为空。 */
        @Override public Optional<GoalModels.PlanDraft> findDraft(String goalId) { return Optional.empty(); }
        /** 未使用的 revision 页返回空。 */
        @Override public ReadPage<GoalModels.PlanRevision> listPlanRevisions(
                String goalId, long afterRevisionNumber, int limit) {
            return new ReadPage<>(0, 0, List.of());
        }
        /** 未使用的 UI evidence 页返回空。 */
        @Override public ReadPage<GoalModels.Evidence> listEvidencePage(
                String goalId, long goalDefinitionRevision, String planRevisionId,
                String afterCreatedAt, String afterEvidenceId, int limit) {
            return new ReadPage<>(0, 0, List.of());
        }
        /** 未使用的 evidence 返回空页。 */
        @Override public List<GoalModels.Evidence> listEvidence(String goalId, String runId, int limit) { return List.of(); }
        /** 未使用的 standalone Plan lookup 返回空。 */
        @Override public Optional<GoalModels.Plan> findExecutingPlanByOwner(String ownerThreadId) { return Optional.empty(); }
        /** 未使用的动态 Plan lookup 返回空。 */
        @Override public Optional<GoalModels.Plan> findActivePlanByOwner(String ownerThreadId) { return Optional.empty(); }
        /** 未使用的 heartbeat 返回空。 */
        @Override public Optional<ContinuationLease> heartbeatLease(String goalId, String leaseId,
                                                                     long fencingToken, Instant at) { return Optional.empty(); }
        /** fake 的统一防误用异常。 */
        private static UnsupportedOperationException unsupported() { return new UnsupportedOperationException(); }
    }
}
