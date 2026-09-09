// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.port.out.GoalToolExecutionPort;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.goal.application.GoalToolExecutionLedger;
import io.github.kongweiguang.ja.goal.application.GoalEvaluator;
import io.github.kongweiguang.ja.goal.domain.CanonicalPlanJson;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.domain.GoalModels.AcceptanceCriterion;
import io.github.kongweiguang.ja.goal.domain.GoalModels.Evidence;
import io.github.kongweiguang.ja.goal.domain.GoalModels.EvidenceSource;
import io.github.kongweiguang.ja.goal.domain.GoalModels.Goal;
import io.github.kongweiguang.ja.goal.domain.GoalModels.Plan;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanDefinition;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanRevision;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanStep;
import io.github.kongweiguang.ja.goal.application.GoalService;
import io.github.kongweiguang.ja.goal.application.GoalContinuationGate;
import io.github.kongweiguang.ja.goal.port.in.GoalEvent;
import io.github.kongweiguang.ja.goal.port.in.GoalUseCase;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;
import io.github.kongweiguang.ja.goal.port.out.GoalEvaluatorPort;
import io.github.kongweiguang.ja.goal.port.out.GoalRepositoryException;
import io.github.kongweiguang.ja.infrastructure.persistence.database.DatabaseConfig;
import io.github.kongweiguang.ja.infrastructure.persistence.database.JaDatabase;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SchemaMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实 V1 SQLite 验证独立 Plan/Goal 的批准绑定、CAS、证据完成门和 continuation lease。 */
final class MybatisGoalRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");
    @TempDir Path temp;

    /** 从创建到 MET 完成的全部事实使用同一 repository transaction，并拒绝旧 hash。 */
    @Test
    void persistsGoalApprovalEvidenceAndCompletion() throws Exception {
        try (Fixture fixture = fixture("goal-flow")) {
            MybatisGoalRepository goals = fixture.repository();
            Goal created = createGoal(goals, "goal_one", "run_goal_initial", "create:key1");
            Goal replayed = goals.create(new GoalRepository.CreateGoal("goal_other", "thr_one",
                    GoalModels.OwnerKind.ROOT_THREAD, "交付目标", goalCriteria(), "run_goal_other", 1,
                    0, "create:key1", NOW));
            assertEquals(created.goalId(), replayed.goalId());

            goals.createPlan(new GoalRepository.CreatePlan(
                    "plan_one", "thr_one", "交付目标", 0, "plan:create:key1", NOW));
            PlanDefinition definition = definition();
            CanonicalPlanJson.Encoded encoded = new CanonicalPlanJson(new ObjectMapper()).encode(definition);
            PlanRevision proposed = goals.propose(new GoalRepository.ProposePlan("plan_one", 0,
                    new PlanRevision("planrev_one", "plan_one", 1, definition, encoded.json(),
                            encoded.sha256(), "AGENT", NOW), "evt_propose", "propose:key1", NOW));
            assertEquals(1, proposed.revisionNumber());
            GoalRepositoryException stale = assertThrows(GoalRepositoryException.class, () -> goals.approve(
                    new GoalRepository.ApprovePlan("plan_one", 1, "planrev_one", "0".repeat(64),
                            "appr_stale", "evt_stale", "approve:bad", NOW)));
            assertEquals(GoalRepositoryException.Code.PLAN_APPROVAL_STALE, stale.code());

            goals.approve(new GoalRepository.ApprovePlan("plan_one", 1, "planrev_one",
                    encoded.sha256(), "appr_one", "evt_approve", "approve:key1", NOW));
            Goal active = goals.attachPlan(new GoalRepository.AttachPlan("goal_one", created.revision(),
                    "plan_one", "planrev_one", encoded.sha256(), "run_one", 1,
                    "evt_attach", "attach:key1", NOW));
            assertEquals(GoalModels.GoalStatus.ACTIVE, active.status());
            fixture.insertToolCall();
            GoalModels.ToolAttempt prepared = goals.prepareToolAttempt(new GoalRepository.PrepareToolAttempt(
                    new GoalModels.ToolAttempt("toolattempt_one", "goal_one", "plan_one", 1L,
                            "run_one", "planrev_one", "step_work", 1, "turn_goal", "call_goal", 1, true,
                            GoalModels.ToolAttemptState.PREPARED, "c".repeat(64), null, NOW, null, null)));
            GoalModels.ToolAttempt started = goals.startToolAttempt(prepared.toolAttemptId(), NOW.plusSeconds(1));
            assertEquals(GoalModels.ToolAttemptState.STARTED, started.state());
            assertEquals(1, goals.listUnsettledToolAttempts(2, 20).size());
            GoalModels.ToolAttempt settled = goals.settleToolAttempt(new GoalRepository.SettleToolAttempt(
                    prepared.toolAttemptId(), GoalModels.ToolAttemptState.SUCCEEDED,
                    "d".repeat(64), null, NOW.plusSeconds(2)));
            assertEquals(GoalModels.ToolAttemptState.SUCCEEDED, settled.state());
            Goal running = goals.updateStep(new GoalRepository.UpdateStep("goal_one", active.revision(), "run_one",
                    "step_work", GoalModels.StepStatus.READY, GoalModels.StepStatus.RUNNING,
                    null, List.of(), "evt_running", "step:running", NOW));
            Goal succeeded = goals.updateStep(new GoalRepository.UpdateStep("goal_one", running.revision(),
                    "run_one", "step_work", GoalModels.StepStatus.RUNNING, GoalModels.StepStatus.SUCCEEDED,
                    null, List.of(new GoalRepository.ToolEvidenceClaim("evidence_tool", "criterion_plan",
                            "call_goal", "Tool 结果已提交", NOW.plusSeconds(2))),
                    "evt_success", "step:success", NOW));
            Evidence evidence = new Evidence("evidence_one", "goal_one", "plan_one", 1L,
                    "run_one", "planrev_one", "criterion_goal", "step_work",
                    EvidenceSource.TEST_REPORT, "surefire_goal",
                    "全部 Goal 聚焦测试通过", "a".repeat(64), NOW, NOW);
            goals.appendEvidence(new GoalRepository.AppendEvidence("goal_one", succeeded.revision(), evidence,
                    "evt_evidence", "evidence:key1", NOW));
            Goal verifying = goals.requestEvaluation(new GoalRepository.RequestEvaluation("goal_one", 4,
                    "evaluation_one", "run_one", "planrev_one", 1, List.of(),
                    "evt_eval_req", "evaluate:req", NOW));
            Goal evaluated = goals.completeEvaluation(new GoalRepository.CompleteEvaluation("goal_one",
                    verifying.revision(), "evaluation_one", GoalModels.EvaluationVerdict.MET,
                    "[{\"criterionId\":\"criterion_goal\",\"verdict\":\"MET\",\"reason\":\"通过\"}]",
                    "全部验收条件已满足", null, "evt_eval_done", "evaluate:done", NOW));
            Goal achieved = goals.transition(new GoalRepository.Transition("goal_one", evaluated.revision(),
                    GoalModels.GoalStatus.ACHIEVED, GoalModels.GoalPhase.ACHIEVED, false,
                    "evt_achieved", "goal:achieved", NOW));

            assertEquals(GoalModels.GoalStatus.ACHIEVED, achieved.status());
            GoalModels.GoalEvaluation latestEvaluation = goals.readSnapshot("goal_one").latestEvaluation();
            assertNotNull(latestEvaluation);
            assertEquals("evaluation_one", latestEvaluation.evaluationId());
            assertEquals("全部验收条件已满足", latestEvaluation.summary());
            assertEquals(GoalModels.EvaluationVerdict.MET, latestEvaluation.verdict());
            assertEquals(List.of("criterion_goal"), latestEvaluation.criteria().stream()
                    .map(GoalModels.CriterionEvaluation::criterionId).toList());
            List<Evidence> persistedEvidence = goals.listEvidence("goal_one", "run_one", 20);
            assertEquals(3, persistedEvidence.size());
            assertTrue(persistedEvidence.stream().anyMatch(item -> item.sourceType() == EvidenceSource.TOOL_RESULT));
            assertTrue(persistedEvidence.stream().anyMatch(item -> item.sourceType() == EvidenceSource.TEST_REPORT));
            assertTrue(goals.listEvents("goal_one", 0, 20).items().size() >= 8);
            List<GoalModels.TerminalActivity> terminal = goals.listTerminalActivities("thr_one", 128);
            assertEquals(1, terminal.size());
            assertEquals("goal_one", terminal.getFirst().goalId());
            assertEquals(GoalModels.GoalStatus.ACHIEVED, terminal.getFirst().status());
            assertEquals(achieved.revision(), terminal.getFirst().goalRevision());
            assertTrue(terminal.getFirst().eventSequence() > 0);
            assertTrue(goals.listTerminalActivities("thr_other", 128).isEmpty());
        }
    }

    /** 默认 Solon transaction owner 的唯一失败分类必须保留 Goal 稳定异常，避免旧批准被误报成存储故障。 */
    @Test
    void productionTransactionPreservesStaleApprovalFailure() {
        GoalRepositoryException stale = new GoalRepositoryException(
                GoalRepositoryException.Code.PLAN_APPROVAL_STALE, "private detail");
        assertSame(stale, GoalUnitOfWork.transactionFailure(stale));
    }

    /** active/working Goal 同时只能有一个 HELD lease，释放后新 token 必须递增。 */
    @Test
    void fencesContinuationLeases() throws Exception {
        try (Fixture fixture = fixture("goal-lease")) {
            MybatisGoalRepository goals = fixture.repository();
            Goal active = approved(goals);
            GoalRepository.ContinuationLease first = goals.tryAcquireLease(
                    new GoalRepository.AcquireLease(active.goalId(), "goallease_one", 3, NOW)).orElseThrow();
            assertTrue(goals.tryAcquireLease(new GoalRepository.AcquireLease(
                    active.goalId(), "goallease_two", 3, NOW)).isEmpty());
            goals.releaseLease(active.goalId(), first.leaseId(), first.fencingToken(), false, NOW).orElseThrow();
            GoalRepository.ContinuationLease second = goals.tryAcquireLease(
                    new GoalRepository.AcquireLease(active.goalId(), "goallease_three", 3, NOW)).orElseThrow();
            assertTrue(second.fencingToken() > first.fencingToken());
        }
    }

    /**
     * Tool ledger 必须同时服从持久 internal context、当前 run 与 HELD fencing lease；旧 Turn 即使仍指向
     * 同一 Goal/Plan 也不能在 replacement 或释放后补写 attempt，JSON 浮点 revision 也必须失败关闭。
     */
    @Test
    void rejectsStaleReleasedAndMalformedInternalTurnBindings() throws Exception {
        try (Fixture fixture = fixture("goal-internal-turn-binding")) {
            MybatisGoalRepository goals = fixture.repository();
            Goal active = approved(goals);
            GoalModels.GoalPlanLink link = goals.readSnapshot(active.goalId()).planLink();
            GoalRepository.ContinuationLease lease = goals.tryAcquireLease(new GoalRepository.AcquireLease(
                    active.goalId(), "goallease_binding", 1, NOW)).orElseThrow();
            GoalToolExecutionLedger ledger = new GoalToolExecutionLedger(goals,
                    Clock.fixed(NOW, ZoneOffset.UTC), 1);

            fixture.insertInternalToolCall("turn_stale", "call_stale", continuationContext(
                    "run_replaced", link, lease.fencingToken(), "1"));
            assertTrue(ledger.prepare(toolPrepare("turn_stale", "call_stale")).isEmpty());
            assertEquals(0, fixture.toolAttemptCount());

            fixture.insertInternalToolCall("turn_current", "call_current", continuationContext(
                    active.activeRunId(), link, lease.fencingToken(), "1"));
            assertTrue(ledger.prepare(toolPrepare("turn_current", "call_current")).isPresent());
            assertEquals(1, fixture.toolAttemptCount());

            goals.releaseLease(active.goalId(), lease.leaseId(), lease.fencingToken(), false,
                    NOW.plusSeconds(1)).orElseThrow();
            fixture.insertInternalToolCall("turn_released", "call_released", continuationContext(
                    active.activeRunId(), link, lease.fencingToken(), "1"));
            assertTrue(goals.findInternalTurnBinding("turn_released").isEmpty());
            assertTrue(ledger.prepare(toolPrepare("turn_released", "call_released")).isEmpty());
            assertEquals(1, fixture.toolAttemptCount());

            fixture.insertInternalToolCall("turn_fractional", "call_fractional", continuationContext(
                    active.activeRunId(), link, lease.fencingToken(), "1.5"));
            GoalRepositoryException malformed = assertThrows(GoalRepositoryException.class,
                    () -> goals.findInternalTurnBinding("turn_fractional"));
            assertEquals(GoalRepositoryException.Code.GOAL_INVALID_STATE, malformed.code());
            assertEquals(1, fixture.toolAttemptCount());
        }
    }

    /**
     * attach/detach 都会替换 Goal-owned run，因此六类未决恢复边界必须采用同一失败关闭策略；每个组合
     * 使用独立数据库，防止前一个 blocker 或回滚结果掩盖后一个条件。
     */
    @Test
    void blocksAttachAndDetachWhileCurrentRunHasUnresolvedWork() throws Exception {
        List<String> blockers = List.of("PENDING_INPUT", "PENDING_EVALUATOR", "TOOL_PREPARED",
                "TOOL_STARTED", "HELD_LEASE", "TOOL_UNKNOWN");
        for (String operation : List.of("attach", "detach")) {
            for (String blocker : blockers) {
                try (Fixture fixture = fixture("goal-replacement-" + operation + "-" + blocker.toLowerCase())) {
                    MybatisGoalRepository goals = fixture.repository();
                    Goal active = approved(goals);
                    GoalModels.GoalPlanLink link = goals.readSnapshot(active.goalId()).planLink();
                    fixture.insertRunReplacementBlocker(blocker);
                    String replacementRunId = "run_replacement_" + operation + "_" + blocker.toLowerCase();

                    GoalRepositoryException failure = assertThrows(GoalRepositoryException.class, () -> {
                        if ("attach".equals(operation)) {
                            goals.attachPlan(new GoalRepository.AttachPlan(active.goalId(), active.revision(),
                                    link.planId(), link.planRevisionId(), link.planHash(), replacementRunId, 2,
                                    "evt_blocked_attach", "blocked:attach:" + blocker, NOW.plusSeconds(1)));
                        } else {
                            goals.detachPlan(new GoalRepository.DetachPlan(active.goalId(), active.revision(),
                                    replacementRunId, 2, "evt_blocked_detach", "blocked:detach:" + blocker,
                                    NOW.plusSeconds(1)));
                        }
                    });

                    assertEquals(GoalRepositoryException.Code.GOAL_INVALID_STATE, failure.code(),
                            operation + ':' + blocker);
                    assertEquals(active.activeRunId(), goals.findGoal(active.goalId()).orElseThrow().activeRunId(),
                            operation + ':' + blocker);
                    assertEquals("RUNNING", fixture.runStatus(active.activeRunId()), operation + ':' + blocker);
                    assertEquals(link, goals.readSnapshot(active.goalId()).planLink(), operation + ':' + blocker);
                }
            }
        }
    }

    /** pause 返回后旧 Turn 未终态时 resume 不得改写 Goal，lease 收口后才能恢复并重新单飞。 */
    @Test
    void rejectsImmediateResumeUntilContinuationAndLeaseSettle() throws Exception {
        try (Fixture fixture = fixture("goal-resume-race")) {
            MybatisGoalRepository goals = fixture.repository();
            Goal active = approved(goals);
            fixture.insertToolCall();
            GoalContinuationGate gate = new GoalContinuationGate();
            GoalService service = new GoalService(goals, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                    1, new io.github.kongweiguang.ja.goal.application.GoalEventRegistry(), gate);
            CompletableFuture<Void> turnTerminal = new CompletableFuture<>();
            AtomicBoolean cancellationRequested = new AtomicBoolean();
            GoalRepository.ContinuationLease oldLease = goals.tryAcquireLease(new GoalRepository.AcquireLease(
                    active.goalId(), "goallease_old", 1, NOW)).orElseThrow();
            gate.activate(active.goalId(), "turn_goal", oldLease.fencingToken(),
                    () -> cancellationRequested.set(true));

            Goal paused = service.control(new GoalUseCase.Control(active.goalId(), active.revision(),
                    GoalUseCase.Action.PAUSE, "goal:pause", NOW.plusSeconds(1)));
            GoalRepositoryException draining = assertThrows(GoalRepositoryException.class, () ->
                    service.control(new GoalUseCase.Control(active.goalId(), paused.revision(),
                            GoalUseCase.Action.RESUME, "goal:resume:early", NOW.plusSeconds(2))));

            assertTrue(cancellationRequested.get());
            assertFalse(turnTerminal.isDone());
            assertEquals(GoalRepositoryException.Code.GOAL_INVALID_STATE, draining.code());
            Goal stillPaused = goals.findGoal(active.goalId()).orElseThrow();
            assertEquals(GoalModels.GoalStatus.PAUSED, stillPaused.status());
            assertEquals(GoalModels.GoalPhase.PAUSED, stillPaused.phase());
            assertTrue(goals.tryAcquireLease(new GoalRepository.AcquireLease(
                    active.goalId(), "goallease_competing", 1, NOW.plusSeconds(2))).isEmpty());

            turnTerminal.complete(null);
            goals.releaseLease(active.goalId(), oldLease.leaseId(), oldLease.fencingToken(), false,
                    NOW.plusSeconds(3)).orElseThrow();
            gate.complete(active.goalId(), "turn_goal", oldLease.fencingToken());
            Goal resumed = service.control(new GoalUseCase.Control(active.goalId(), paused.revision(),
                    GoalUseCase.Action.RESUME, "goal:resume:settled", NOW.plusSeconds(4)));
            GoalRepository.ContinuationLease nextLease = goals.tryAcquireLease(new GoalRepository.AcquireLease(
                    active.goalId(), "goallease_next", 1, NOW.plusSeconds(5))).orElseThrow();

            assertEquals(GoalModels.GoalStatus.ACTIVE, resumed.status());
            assertEquals(GoalModels.GoalPhase.WORKING, resumed.phase());
            assertTrue(nextLease.fencingToken() > oldLease.fencingToken());
            assertTrue(goals.tryAcquireLease(new GoalRepository.AcquireLease(
                    active.goalId(), "goallease_duplicate", 1, NOW.plusSeconds(5))).isEmpty());
        }
    }

    /**
     * attach/detach 先暴露稳定 Goal CAS 结果：陈旧 revision 不得被活动 continuation 遮蔽，正确 revision
     * 仍须等待收口；已提交请求的同 key 重放也不能被进程内 gate 破坏。
     */
    @Test
    void prioritizesGoalRevisionAndIdempotentReplayBeforeContinuationSettlement() throws Exception {
        try (Fixture fixture = fixture("goal-plan-link-cas-priority")) {
            MybatisGoalRepository goals = fixture.repository();
            Goal created = createGoal(goals, "goal_one", "run_goal_initial", "create:key1");
            goals.createPlan(new GoalRepository.CreatePlan(
                    "plan_one", "thr_one", "交付目标", 0, "plan:create:key1", NOW));
            PlanDefinition definition = definition();
            CanonicalPlanJson.Encoded encoded = new CanonicalPlanJson(new ObjectMapper()).encode(definition);
            goals.propose(new GoalRepository.ProposePlan("plan_one", 0,
                    new PlanRevision("planrev_one", "plan_one", 1, definition, encoded.json(),
                            encoded.sha256(), "AGENT", NOW), "evt_propose", "propose:key1", NOW));
            goals.approve(new GoalRepository.ApprovePlan("plan_one", 1, "planrev_one", encoded.sha256(),
                    "appr_one", "evt_approve", "approve:key1", NOW));
            GoalContinuationGate gate = new GoalContinuationGate();
            GoalService service = new GoalService(goals, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                    1, new io.github.kongweiguang.ja.goal.application.GoalEventRegistry(), gate);
            GoalUseCase.AttachPlan attach = new GoalUseCase.AttachPlan(created.goalId(), created.revision(),
                    "plan_one", "planrev_one", encoded.sha256(), "goal:attach", NOW.plusSeconds(1));
            gate.activate(created.goalId(), "turn_attach", 7, () -> { });

            GoalRepositoryException staleAttach = assertThrows(GoalRepositoryException.class, () ->
                    service.attachPlan(new GoalUseCase.AttachPlan(created.goalId(), created.revision() + 100,
                            "plan_one", "planrev_one", encoded.sha256(), "goal:attach:stale",
                            NOW.plusSeconds(1))));
            GoalRepositoryException settlingAttach = assertThrows(GoalRepositoryException.class,
                    () -> service.attachPlan(attach));
            assertEquals(GoalRepositoryException.Code.GOAL_REVISION_CONFLICT, staleAttach.code());
            assertEquals(GoalRepositoryException.Code.GOAL_INVALID_STATE, settlingAttach.code());

            gate.complete(created.goalId(), "turn_attach", 7);
            Goal attached = service.attachPlan(attach);
            gate.activate(created.goalId(), "turn_detach", 8, () -> { });
            Goal replayed = service.attachPlan(attach);
            GoalRepositoryException staleDetach = assertThrows(GoalRepositoryException.class, () ->
                    service.detachPlan(new GoalUseCase.DetachPlan(attached.goalId(), attached.revision() + 100,
                            "goal:detach:stale", NOW.plusSeconds(2))));
            GoalRepositoryException settlingDetach = assertThrows(GoalRepositoryException.class, () ->
                    service.detachPlan(new GoalUseCase.DetachPlan(attached.goalId(), attached.revision(),
                            "goal:detach", NOW.plusSeconds(2))));
            assertEquals(attached, replayed);
            assertEquals(GoalRepositoryException.Code.GOAL_REVISION_CONFLICT, staleDetach.code());
            assertEquals(GoalRepositoryException.Code.GOAL_INVALID_STATE, settlingDetach.code());

            gate.complete(created.goalId(), "turn_detach", 8);
            Goal detached = service.detachPlan(new GoalUseCase.DetachPlan(attached.goalId(), attached.revision(),
                    "goal:detach", NOW.plusSeconds(2)));
            assertNull(goals.readSnapshot(detached.goalId()).planLink());
        }
    }

    /**
     * continuation 的审批 phase 必须是持久 CAS，重复事件保持幂等；用户暂停后迟到的审批
     * resolved 事件必须被拒绝，不能把 Goal 覆盖回 ACTIVE。
     */
    @Test
    void projectsContinuationApprovalPhaseWithoutOverwritingPause() throws Exception {
        try (Fixture fixture = fixture("goal-approval-phase")) {
            MybatisGoalRepository goals = fixture.repository();
            Goal active = approved(goals);
            Goal waiting = goals.projectContinuationPhase(new GoalRepository.ProjectContinuationPhase(
                    active.goalId(), active.revision(), GoalModels.GoalPhase.WORKING,
                    GoalModels.GoalPhase.WAITING_APPROVAL, "evt_waiting", "phase:requested", NOW));
            Goal replayed = goals.projectContinuationPhase(new GoalRepository.ProjectContinuationPhase(
                    active.goalId(), active.revision(), GoalModels.GoalPhase.WORKING,
                    GoalModels.GoalPhase.WAITING_APPROVAL, "evt_ignored", "phase:requested", NOW));
            Goal working = goals.projectContinuationPhase(new GoalRepository.ProjectContinuationPhase(
                    waiting.goalId(), waiting.revision(), GoalModels.GoalPhase.WAITING_APPROVAL,
                    GoalModels.GoalPhase.WORKING, "evt_working", "phase:resolved", NOW.plusSeconds(1)));

            assertEquals(GoalModels.GoalPhase.WAITING_APPROVAL, waiting.phase());
            assertEquals(active.revision(), waiting.revision());
            assertEquals(waiting.revision(), replayed.revision());
            assertEquals(GoalModels.GoalPhase.WORKING, working.phase());
            assertEquals(active.revision(), working.revision());
            Goal paused = goals.transition(new GoalRepository.Transition(working.goalId(), working.revision(),
                    GoalModels.GoalStatus.PAUSED, GoalModels.GoalPhase.PAUSED, false,
                    "evt_paused", "goal:pause", NOW.plusSeconds(2)));

            GoalRepositoryException late = assertThrows(GoalRepositoryException.class, () ->
                    goals.projectContinuationPhase(new GoalRepository.ProjectContinuationPhase(
                            paused.goalId(), paused.revision(), GoalModels.GoalPhase.WAITING_APPROVAL,
                            GoalModels.GoalPhase.WORKING, "evt_late", "phase:late", NOW.plusSeconds(3))));
            assertEquals(GoalRepositoryException.Code.GOAL_INVALID_STATE, late.code());
            assertEquals(GoalModels.GoalStatus.PAUSED,
                    goals.findGoal(paused.goalId()).orElseThrow().status());
        }
    }

    /** 连续三个完全无 revision 进展的 continuation 必须由数据库计数并在第三次暂停。 */
    @Test
    void pausesAfterThreeContinuationTurnsWithoutProgress() throws Exception {
        try (Fixture fixture = fixture("goal-continuation-stall")) {
            MybatisGoalRepository goals = fixture.repository();
            Goal current = approved(goals);
            for (int turn = 1; turn <= 3; turn++) {
                current = goals.recordContinuationNoProgress("goal_one", current.revision(),
                        "evt_no_progress_" + turn, "continuation:no-progress:" + turn,
                        NOW.plusSeconds(turn));
            }

            assertEquals(GoalModels.GoalStatus.PAUSED, current.status());
            assertEquals(GoalModels.GoalPhase.NEEDS_ATTENTION, current.phase());
            assertEquals(3, current.turnsWithoutProgress());
        }
    }

    /** 旧代际 evaluator 被明确失败结算，模型身份来自 owner Thread 且 Goal 进入可恢复 attention。 */
    @Test
    void recoversInterruptedEvaluatorFromOlderProcessGeneration() throws Exception {
        try (Fixture fixture = fixture("goal-evaluator-recovery")) {
            MybatisGoalRepository goals = fixture.repository();
            Goal active = approved(goals);
            Goal verifying = goals.requestEvaluation(new GoalRepository.RequestEvaluation("goal_one",
                    active.revision(), "evaluation_old", "run_one", "planrev_one", 4,
                    List.of(),
                    "evt_evaluation_old", "evaluation:old", NOW));
            GoalRepository.EvaluationIntent claimed = goals.claimRequestedEvaluation(
                    "goal_one", "run_one").orElseThrow();

            assertEquals("model", claimed.modelId());
            assertEquals("provider", claimed.providerId());
            assertEquals(4, claimed.processGeneration());
            assertEquals(1, goals.listUnsettledEvaluations(5, 20).size());
            Goal recovered = goals.recoverEvaluation(claimed, 5, NOW.plusSeconds(1)).orElseThrow();

            assertEquals(GoalModels.GoalStatus.PAUSED, recovered.status());
            assertEquals(GoalModels.GoalPhase.NEEDS_ATTENTION, recovered.phase());
            assertTrue(goals.listUnsettledEvaluations(5, 20).isEmpty());
            assertTrue(recovered.revision() > verifying.revision());
        }
    }

    /** 提案重试必须返回首次提交的 revision identity，并拒绝同 key 的不同请求或跨操作复用。 */
    @Test
    void replaysExactPlanRevisionAndRejectsIdempotencyCollisions() throws Exception {
        try (Fixture fixture = fixture("goal-propose-replay")) {
            MybatisGoalRepository goals = fixture.repository();
            goals.createPlan(new GoalRepository.CreatePlan(
                    "plan_one", "thr_one", "交付目标", 0, "plan:create:key1", NOW));
            PlanDefinition definition = definition();
            CanonicalPlanJson canonical = new CanonicalPlanJson(new ObjectMapper());
            CanonicalPlanJson.Encoded encoded = canonical.encode(definition);
            PlanRevision first = goals.propose(new GoalRepository.ProposePlan("plan_one", 0,
                    new PlanRevision("planrev_one", "plan_one", 1, definition, encoded.json(),
                            encoded.sha256(), "AGENT", NOW),
                    "evt_propose", "propose:key1", NOW));
            PlanRevision replay = goals.propose(new GoalRepository.ProposePlan("plan_one", 0,
                    new PlanRevision("planrev_retry", "plan_one", 1, definition, encoded.json(),
                            encoded.sha256(), "AGENT", NOW.plusSeconds(1)),
                    "evt_retry", "propose:key1", NOW.plusSeconds(1)));

            assertEquals(first.planRevisionId(), replay.planRevisionId());
            assertEquals(first.createdAt(), replay.createdAt());
            assertEquals(1, goals.listPlanRevisions("plan_one", 0, 20).items().size());
            GoalRepositoryException collision = assertThrows(GoalRepositoryException.class, () -> goals.approve(
                    new GoalRepository.ApprovePlan("plan_one", 1, first.planRevisionId(), first.planHash(),
                            "appr_collision", "evt_collision", "propose:key1", NOW)));
            assertEquals(GoalRepositoryException.Code.GOAL_INVALID_STATE, collision.code());
        }
    }

    /** 同一失败签名第三次出现且没有真实新证据时，步骤结算与 Goal 熔断必须原子提交。 */
    @Test
    void pausesAfterThreeRepeatedFailuresWithoutEvidence() throws Exception {
        try (Fixture fixture = fixture("goal-repeated-failure")) {
            MybatisGoalRepository goals = fixture.repository();
            Goal current = approved(goals);
            String signature = "f".repeat(64);
            for (int attempt = 1; attempt <= 3; attempt++) {
                current = goals.updateStep(new GoalRepository.UpdateStep("goal_one", current.revision(), "run_one",
                        "step_work", GoalModels.StepStatus.READY, GoalModels.StepStatus.RUNNING,
                        null, List.of(), "evt_running_" + attempt, "step:running:" + attempt, NOW.plusSeconds(attempt)));
                current = goals.updateStep(new GoalRepository.UpdateStep("goal_one", current.revision(), "run_one",
                        "step_work", GoalModels.StepStatus.RUNNING, GoalModels.StepStatus.FAILED,
                        signature, List.of(), "evt_failed_" + attempt, "step:failed:" + attempt,
                        NOW.plusSeconds(attempt + 10L)));
                if (attempt < 3) {
                    current = goals.updateStep(new GoalRepository.UpdateStep("goal_one", current.revision(),
                            "run_one", "step_work", GoalModels.StepStatus.FAILED, GoalModels.StepStatus.READY,
                            null, List.of(), "evt_retry_" + attempt, "step:retry:" + attempt,
                            NOW.plusSeconds(attempt + 20L)));
                }
            }

            assertEquals(GoalModels.GoalStatus.PAUSED, current.status());
            assertEquals(GoalModels.GoalPhase.NEEDS_ATTENTION, current.phase());
            assertEquals(3, current.repeatedFailureCount());
            assertEquals(signature, current.lastFailureSignature());
        }
    }

    /** 暂停后的编辑可产生新 Run，但旧 revision 永远不能重新取得执行资格。 */
    @Test
    void replacesPausedRunOnlyAfterLatestRevisionApproval() throws Exception {
        try (Fixture fixture = fixture("goal-revision-replacement")) {
            MybatisGoalRepository goals = fixture.repository();
            Goal active = approved(goals);
            Goal paused = goals.transition(new GoalRepository.Transition("goal_one", active.revision(),
                    GoalModels.GoalStatus.PAUSED, GoalModels.GoalPhase.PAUSED, false,
                    "evt_pause", "goal:pause", NOW.plusSeconds(1)));
            Plan approvedPlan = goals.readPlanSnapshot("plan_one").plan();
            Plan editing = goals.saveDraft(new GoalRepository.SaveDraft("plan_one", approvedPlan.revision(), 0,
                    new CanonicalPlanJson(new ObjectMapper()).encode(definition()).json(), "planrev_one",
                    "evt_draft_two", "draft:two", NOW.plusSeconds(2)));
            PlanDefinition revisedDefinition = new PlanDefinition("交付目标", List.of("目标范围"), List.of(),
                    List.of("保持兼容"), List.of(),
                    List.of(new PlanStep("step_work", "实施", "完成第二版实现", true, List.of())),
                    List.of(new AcceptanceCriterion("criterion_plan", "测试通过", true)),
                    List.of(), List.of("运行聚焦测试"));
            CanonicalPlanJson.Encoded revised = new CanonicalPlanJson(new ObjectMapper()).encode(revisedDefinition);
            goals.propose(new GoalRepository.ProposePlan("plan_one", editing.revision(),
                    new PlanRevision("planrev_two", "plan_one", 2, revisedDefinition, revised.json(),
                            revised.sha256(), "USER_UI", NOW.plusSeconds(3)),
                    "evt_propose_two", "propose:two", NOW.plusSeconds(3)));
            Plan awaiting = goals.readPlanSnapshot("plan_one").plan();

            GoalRepositoryException stale = assertThrows(GoalRepositoryException.class, () -> goals.approve(
                    new GoalRepository.ApprovePlan("plan_one", awaiting.revision(), "planrev_one",
                            new CanonicalPlanJson(new ObjectMapper()).encode(definition()).sha256(),
                            "appr_old", "evt_old", "approve:old", NOW.plusSeconds(4))));
            assertEquals(GoalRepositoryException.Code.PLAN_APPROVAL_STALE, stale.code());

            Plan approved = goals.approve(new GoalRepository.ApprovePlan("plan_one", awaiting.revision(),
                    "planrev_two", revised.sha256(), "appr_two",
                    "evt_approve_two", "approve:two", NOW.plusSeconds(5)));
            Goal relinked = goals.attachPlan(new GoalRepository.AttachPlan("goal_one", paused.revision(),
                    "plan_one", "planrev_two", revised.sha256(), "run_two", 1,
                    "evt_attach_two", "attach:two", NOW.plusSeconds(6)));
            assertEquals(GoalModels.PlanStatus.APPROVED, approved.status());
            assertEquals(GoalModels.GoalStatus.PAUSED, relinked.status());
            assertEquals("run_two", relinked.activeRunId());
            assertEquals("STOPPED", fixture.runStatus("run_one"));
            assertEquals("PAUSED", fixture.runStatus("run_two"));
            Goal resumed = goals.transition(new GoalRepository.Transition("goal_one", relinked.revision(),
                    GoalModels.GoalStatus.ACTIVE, GoalModels.GoalPhase.WORKING, false,
                    "evt_resume", "goal:resume", NOW.plusSeconds(7)));
            assertEquals(GoalModels.GoalStatus.ACTIVE, resumed.status());
            assertEquals("RUNNING", fixture.runStatus("run_two"));

            GoalRepository.ReadPage<PlanRevision> firstPage = goals.listPlanRevisions("plan_one", 0, 1);
            GoalRepository.ReadPage<PlanRevision> secondPage = goals.listPlanRevisions(
                    "plan_one", firstPage.items().getFirst().revisionNumber(), 1);
            assertEquals(List.of("planrev_one"), firstPage.items().stream()
                    .map(PlanRevision::planRevisionId).toList());
            assertEquals(List.of("planrev_two"), secondPage.items().stream()
                    .map(PlanRevision::planRevisionId).toList());
        }
    }

    /** 完整快照、输入结算和 evidence/event keyset 页都来自同一权威 SQLite 事实。 */
    @Test
    void readsCompleteSnapshotAndStablePages() throws Exception {
        try (Fixture fixture = fixture("goal-read-model")) {
            MybatisGoalRepository goals = fixture.repository();
            Goal active = approved(goals);
            GoalModels.GoalSnapshot initial = goals.readSnapshot("goal_one");
            GoalModels.PlanSnapshot plan = goals.readPlanSnapshot("plan_one");
            assertEquals(active.revision(), initial.goal().revision());
            assertEquals("plan_one", initial.planLink().planId());
            assertEquals("planrev_one", plan.currentRevision().planRevisionId());
            assertEquals("appr_one", plan.approval().approvalId());
            assertEquals("step_work", initial.currentStepId());
            assertEquals(0, initial.completedRequiredSteps());
            assertEquals(1, initial.totalRequiredSteps());

            Goal waiting = goals.requestInput(new GoalRepository.RequestInput("goal_one", active.revision(),
                    "goalinput_one", "run_one", "请选择策略", NOW.plusSeconds(60), "evt_input",
                    "input:request", NOW));
            assertNotNull(goals.readSnapshot("goal_one").pendingInput());
            Goal resumed = goals.respondInput(new GoalRepository.RespondInput("goal_one", waiting.revision(),
                    "goalinput_one", "\"继续\"", "evt_response", "input:response", NOW.plusSeconds(1)));
            assertNull(goals.readSnapshot("goal_one").pendingInput());

            Evidence firstEvidence = new Evidence("evidence_a", "goal_one", "plan_one", 1L,
                    "run_one", "planrev_one", "criterion_goal", "step_work",
                    EvidenceSource.TEST_REPORT, "report_a", "报告 A",
                    "a".repeat(64), NOW.plusSeconds(2), NOW.plusSeconds(2));
            goals.appendEvidence(new GoalRepository.AppendEvidence("goal_one", resumed.revision(), firstEvidence,
                    "evt_evidence_a", "evidence:a", NOW.plusSeconds(2)));
            Goal afterFirst = goals.findGoal("goal_one").orElseThrow();
            Evidence secondEvidence = new Evidence("evidence_b", "goal_one", "plan_one", 1L,
                    "run_one", "planrev_one", "criterion_goal", "step_work",
                    EvidenceSource.BUILD_ARTIFACT, "artifact_b", "产物 B",
                    "b".repeat(64), NOW.plusSeconds(3), NOW.plusSeconds(3));
            goals.appendEvidence(new GoalRepository.AppendEvidence("goal_one", afterFirst.revision(),
                    secondEvidence, "evt_evidence_b", "evidence:b", NOW.plusSeconds(3)));

            GoalRepository.ReadPage<Evidence> evidencePageOne = goals.listEvidencePage(
                    "goal_one", 1, "planrev_one", null, null, 1);
            Evidence boundary = evidencePageOne.items().getFirst();
            GoalRepository.ReadPage<Evidence> evidencePageTwo = goals.listEvidencePage(
                    "goal_one", 1, "planrev_one", boundary.createdAt().toString(), boundary.evidenceId(), 2);
            assertEquals(List.of("evidence_a"), evidencePageOne.items().stream().map(Evidence::evidenceId).toList());
            assertEquals(List.of("evidence_b"), evidencePageTwo.items().stream().map(Evidence::evidenceId).toList());

            GoalRepository.ReadPage<GoalRepository.Event> eventPageOne = goals.listEvents("goal_one", 0, 2);
            long eventBoundary = eventPageOne.items().getLast().sequence();
            GoalRepository.ReadPage<GoalRepository.Event> eventPageTwo = goals.listEvents(
                    "goal_one", eventBoundary, 100);
            assertNotEquals(eventPageOne.items().getLast().eventId(), eventPageTwo.items().getFirst().eventId());
            assertEquals(goals.listEvents("goal_one", 0, 100).items().size(),
                    eventPageOne.items().size() + eventPageTwo.items().size());
        }
    }

    /** 丢弃换版草稿恢复旧批准暂停态；恢复执行必须由后续显式 resume 完成。 */
    @Test
    void discardsRevisionDraftBackToApprovedPause() throws Exception {
        try (Fixture fixture = fixture("goal-discard-draft")) {
            MybatisGoalRepository goals = fixture.repository();
            Goal active = approved(goals);
            Goal paused = goals.transition(new GoalRepository.Transition("goal_one", active.revision(),
                    GoalModels.GoalStatus.PAUSED, GoalModels.GoalPhase.PAUSED, false,
                    "evt_pause", "goal:pause", NOW));
            Plan approvedPlan = goals.readPlanSnapshot("plan_one").plan();
            Plan editing = goals.saveDraft(new GoalRepository.SaveDraft("plan_one", approvedPlan.revision(), 0,
                    new CanonicalPlanJson(new ObjectMapper()).encode(definition()).json(), "planrev_one",
                    "evt_edit", "draft:edit", NOW.plusSeconds(1)));
            Plan discarded = goals.discardDraft(new GoalRepository.DiscardDraft("plan_one", editing.revision(),
                    "evt_discard", "draft:discard", NOW.plusSeconds(2)));

            assertEquals(GoalModels.GoalStatus.PAUSED, goals.findGoal(paused.goalId()).orElseThrow().status());
            assertEquals(GoalModels.PlanStatus.APPROVED, discarded.status());
            assertEquals("planrev_one", discarded.activePlanRevisionId());
            assertNull(goals.readPlanSnapshot("plan_one").draft());
        }
    }

    /** 最新 revision 可被拒绝；过期 input 不接受迟到响应且保留稳定错误码。 */
    @Test
    void rejectsLatestRevisionAndExpiredInput() throws Exception {
        try (Fixture fixture = fixture("goal-reject-expired")) {
            MybatisGoalRepository goals = fixture.repository();
            Goal created = createGoal(goals, "goal_one", "run_goal_initial", "create:key1");
            goals.createPlan(new GoalRepository.CreatePlan(
                    "plan_one", "thr_one", "交付目标", 0, "plan:create:key1", NOW));
            CanonicalPlanJson.Encoded encoded = new CanonicalPlanJson(new ObjectMapper()).encode(definition());
            goals.propose(new GoalRepository.ProposePlan("plan_one", 0,
                    new PlanRevision("planrev_one", "plan_one", 1, definition(), encoded.json(),
                            encoded.sha256(), "USER_UI", NOW), "evt_propose", "propose:key1", NOW));
            Plan rejected = goals.reject(new GoalRepository.RejectPlan("plan_one", 1, "planrev_one",
                    encoded.sha256(), "appr_reject", "需要调整", "evt_reject", "reject:key1", NOW));
            assertEquals(GoalModels.PlanStatus.DRAFT, rejected.status());

            goals.propose(new GoalRepository.ProposePlan("plan_one", rejected.revision(),
                    new PlanRevision("planrev_two", "plan_one", 2, definition(), encoded.json(),
                            encoded.sha256(), "USER_UI", NOW.plusSeconds(1)),
                    "evt_propose_two", "propose:key2", NOW.plusSeconds(1)));
            Plan awaitingApproval = goals.readPlanSnapshot("plan_one").plan();
            goals.approve(new GoalRepository.ApprovePlan("plan_one", awaitingApproval.revision(),
                    "planrev_two", encoded.sha256(), "appr_two",
                    "evt_approve", "approve:key2", NOW.plusSeconds(2)));
            Goal active = goals.attachPlan(new GoalRepository.AttachPlan("goal_one", created.revision(),
                    "plan_one", "planrev_two", encoded.sha256(), "run_two", 1,
                    "evt_attach", "attach:key2", NOW.plusSeconds(2)));
            Goal waiting = goals.requestInput(new GoalRepository.RequestInput("goal_one", active.revision(),
                    "goalinput_expired", "run_two", "请输入", NOW.plusSeconds(2), "evt_input",
                    "input:expired", NOW.plusSeconds(1)));
            GoalRepositoryException expired = assertThrows(GoalRepositoryException.class, () ->
                    goals.respondInput(new GoalRepository.RespondInput("goal_one", waiting.revision(),
                            "goalinput_expired", "\"迟到\"", "evt_late", "input:late", NOW.plusSeconds(2))));
            assertEquals(GoalRepositoryException.Code.GOAL_INPUT_EXPIRED, expired.code());
        }
    }

    /** Service 在事务提交后统一发布持久事件，关闭订阅句柄后不再读取或发布 UI 投影。 */
    @Test
    void publishesCommittedMutationThroughGoalUseCase() throws Exception {
        try (Fixture fixture = fixture("goal-service-events")) {
            GoalService service = new GoalService(fixture.repository(), new ObjectMapper(),
                    Clock.fixed(NOW, ZoneOffset.UTC));
            List<GoalEvent> published = new ArrayList<>();
            AutoCloseable subscription = service.subscribe(event -> {
                published.add(event);
                return CompletableFuture.completedFuture(null);
            });
            Goal created = service.create(new GoalUseCase.Create("thr_one", false, "交付目标",
                    goalCriteria(), 0, "create:service", NOW));

            assertEquals(1, published.size());
            assertEquals(created.goalId(), published.getFirst().snapshot().goal().goalId());
            assertEquals("created", published.getFirst().activity().kind());
            assertEquals(created.revision(), published.getFirst().activity().goalRevision());
            assertEquals("created", service.readEvents(created.goalId(), null, 20)
                    .items().getFirst().kind());

            Plan plan = service.createPlan(new GoalUseCase.CreatePlan(
                    "thr_one", "交付目标", 0, "plan:create:service", NOW));
            service.saveDraft(new GoalUseCase.SaveDraft(plan.planId(), plan.revision(), 0,
                    definition(), null, "draft:service", NOW.plusSeconds(1)));
            PlanRevision proposed = service.proposeDraft(new GoalUseCase.ProposeDraft(
                    plan.planId(), 1, true, "propose:service", NOW.plusSeconds(2)));
            Plan approved = service.approve(new GoalUseCase.Approve(
                    plan.planId(), 2, proposed.planRevisionId(), proposed.planHash(),
                    "approve:service", NOW.plusSeconds(3)));
            Goal attached = service.attachPlan(new GoalUseCase.AttachPlan(
                    created.goalId(), created.revision(), approved.planId(), proposed.planRevisionId(),
                    proposed.planHash(), "attach:service", NOW.plusSeconds(4)));
            Goal detached = service.detachPlan(new GoalUseCase.DetachPlan(
                    attached.goalId(), attached.revision(), "detach:service", NOW.plusSeconds(5)));

            assertEquals(List.of("created", "plan_attached", "plan_detached"), published.stream()
                    .map(event -> event.activity().kind()).toList());
            assertEquals(List.of("created", "plan_attached", "plan_detached"),
                    service.readEvents(created.goalId(), null, 20).items().stream()
                            .map(GoalModels.PublicEvent::kind).toList());
            assertNull(service.read(detached.goalId()).planLink());
            subscription.close();
            service.control(new GoalUseCase.Control(detached.goalId(), detached.revision(),
                    GoalUseCase.Action.PAUSE, "pause:service", NOW.plusSeconds(6)));
            assertEquals(3, published.size());
        }
    }

    /** Goal-only 不依赖 Plan/step；当前 definition 的真实证据与 MET evaluator 仍必须同时满足完成门。 */
    @Test
    void completesGoalOnlyRunWithCurrentEvidenceAndEvaluatorProjection() throws Exception {
        try (Fixture fixture = fixture("goal-only-completion")) {
            MybatisGoalRepository goals = fixture.repository();
            Goal created = createGoal(goals, "goal_only", "run_goal_only", "create:goal-only");
            fixture.insertToolCall();
            GoalModels.ToolAttempt attempt = goals.prepareToolAttempt(new GoalRepository.PrepareToolAttempt(
                    new GoalModels.ToolAttempt("toolattempt_goal_only", "goal_only", null, 1L,
                            "run_goal_only", null, null, 1, "turn_goal", "call_goal", 1, false,
                            GoalModels.ToolAttemptState.PREPARED, "b".repeat(64), null, NOW, null, null)));
            goals.startToolAttempt(attempt.toolAttemptId(), NOW.plusSeconds(1));
            goals.settleToolAttempt(new GoalRepository.SettleToolAttempt(attempt.toolAttemptId(),
                    GoalModels.ToolAttemptState.SUCCEEDED, "c".repeat(64), null, NOW.plusSeconds(2)));

            Goal verifying = goals.requestEvaluation(new GoalRepository.RequestEvaluation("goal_only",
                    created.revision(), "evaluation_goal_only", "run_goal_only", null, 1,
                    List.of(new GoalRepository.ToolEvidenceClaim("evidence_goal_only", "criterion_goal",
                            "call_goal", "真实 Tool 结果满足 Goal 验收", NOW.plusSeconds(2))),
                    "evt_goal_only_evaluate",
                    "goal-only:evaluate", NOW.plusSeconds(1)));
            Goal evaluated = goals.completeEvaluation(new GoalRepository.CompleteEvaluation("goal_only",
                    verifying.revision(), "evaluation_goal_only", GoalModels.EvaluationVerdict.MET,
                    "[{\"criterionId\":\"criterion_goal\",\"verdict\":\"MET\",\"reason\":\"报告可信\"}]",
                    "Goal-only 验收通过", null, "evt_goal_only_evaluated",
                    "goal-only:evaluated", NOW.plusSeconds(2)));
            Goal achieved = goals.transition(new GoalRepository.Transition("goal_only", evaluated.revision(),
                    GoalModels.GoalStatus.ACHIEVED, GoalModels.GoalPhase.ACHIEVED, false,
                    "evt_goal_only_achieved", "goal-only:achieved", NOW.plusSeconds(3)));

            GoalModels.GoalSnapshot snapshot = goals.readSnapshot("goal_only");
            assertEquals(GoalModels.GoalStatus.ACHIEVED, achieved.status());
            assertNull(snapshot.planLink());
            assertEquals(0, snapshot.totalRequiredSteps());
            assertEquals("Goal-only 验收通过", snapshot.latestEvaluation().summary());
            assertEquals("run_goal_only", snapshot.latestEvaluation().runId());
            assertTrue(goals.listEvidence("goal_only", "run_goal_only", 20).stream()
                    .anyMatch(item -> "criterion_goal".equals(item.criterionId())
                            && item.sourceType() == EvidenceSource.TOOL_RESULT));
        }
    }

    /** evaluator 先返回 MET 时保持 WORKING，最后一个 Tool settlement 必须在同一事务完成 Goal。 */
    @Test
    void completesGoalWhenEvaluatorSettlesBeforeEvaluationTool() throws Exception {
        try (Fixture fixture = fixture("goal-evaluator-before-tool")) {
            MybatisGoalRepository goals = fixture.repository();
            Goal created = createGoal(goals, "goal_race", "run_race", "create:race");
            goals.appendEvidence(new GoalRepository.AppendEvidence("goal_race", created.revision(),
                    goalEvidence("evidence_race", "goal_race", "run_race"),
                    "evt_race_evidence", "race:evidence", NOW));
            fixture.insertToolCall();
            GoalModels.ToolAttempt attempt = startedGoalTool(goals, "goal_race", "run_race",
                    "toolattempt_race");
            Goal evidenced = goals.findGoal("goal_race").orElseThrow();
            Goal verifying = goals.requestEvaluation(new GoalRepository.RequestEvaluation("goal_race",
                    evidenced.revision(), "evaluation_race", "run_race", null, 1, List.of(),
                    "evt_race_requested", "race:requested", NOW.plusSeconds(2)));

            evaluator(goals).evaluateRequested("evaluation_race", verifying.revision(),
                    evaluatorRequest("goal_race", "run_race"), "race:evaluated").toCompletableFuture().join();
            Goal waitingForTool = goals.findGoal("goal_race").orElseThrow();
            assertEquals(GoalModels.GoalStatus.ACTIVE, waitingForTool.status());
            assertEquals(GoalModels.GoalPhase.WORKING, waitingForTool.phase());

            GoalModels.ToolAttempt settled = goals.settleToolAttempt(new GoalRepository.SettleToolAttempt(
                    attempt.toolAttemptId(), GoalModels.ToolAttemptState.SUCCEEDED,
                    "d".repeat(64), null, NOW.plusSeconds(3)));
            Goal achieved = goals.findGoal("goal_race").orElseThrow();
            int events = goals.listEvents("goal_race", 0, 50).items().size();
            GoalModels.ToolAttempt replay = goals.settleToolAttempt(new GoalRepository.SettleToolAttempt(
                    attempt.toolAttemptId(), GoalModels.ToolAttemptState.SUCCEEDED,
                    "d".repeat(64), null, NOW.plusSeconds(4)));

            assertEquals(GoalModels.GoalStatus.ACHIEVED, achieved.status());
            assertEquals(GoalModels.GoalPhase.ACHIEVED, achieved.phase());
            assertEquals(settled, replay);
            assertEquals(events, goals.listEvents("goal_race", 0, 50).items().size());
        }
    }

    /** Tool 先结算时 evaluator MET 仍沿用原完成门，证明双触发收口不依赖固定到达顺序。 */
    @Test
    void completesGoalWhenEvaluationToolSettlesBeforeEvaluator() throws Exception {
        try (Fixture fixture = fixture("goal-tool-before-evaluator")) {
            MybatisGoalRepository goals = fixture.repository();
            Goal created = createGoal(goals, "goal_order", "run_order", "create:order");
            goals.appendEvidence(new GoalRepository.AppendEvidence("goal_order", created.revision(),
                    goalEvidence("evidence_order", "goal_order", "run_order"),
                    "evt_order_evidence", "order:evidence", NOW));
            fixture.insertToolCall();
            GoalModels.ToolAttempt attempt = startedGoalTool(goals, "goal_order", "run_order",
                    "toolattempt_order");
            Goal evidenced = goals.findGoal("goal_order").orElseThrow();
            Goal verifying = goals.requestEvaluation(new GoalRepository.RequestEvaluation("goal_order",
                    evidenced.revision(), "evaluation_order", "run_order", null, 1, List.of(),
                    "evt_order_requested", "order:requested", NOW.plusSeconds(2)));
            goals.settleToolAttempt(new GoalRepository.SettleToolAttempt(attempt.toolAttemptId(),
                    GoalModels.ToolAttemptState.SUCCEEDED, "e".repeat(64), null, NOW.plusSeconds(3)));

            evaluator(goals).evaluateRequested("evaluation_order", verifying.revision(),
                    evaluatorRequest("goal_order", "run_order"), "order:evaluated")
                    .toCompletableFuture().join();

            Goal achieved = goals.findGoal("goal_order").orElseThrow();
            assertEquals(GoalModels.GoalStatus.ACHIEVED, achieved.status());
            assertEquals(GoalModels.GoalPhase.ACHIEVED, achieved.phase());
        }
    }

    /** Tool terminal 只能消除未决调用，缺少 criterion evidence 时不得借 MET 结论越过完成门。 */
    @Test
    void keepsGoalWorkingWhenToolSettlesWithoutRequiredEvidence() throws Exception {
        try (Fixture fixture = fixture("goal-tool-missing-evidence")) {
            MybatisGoalRepository goals = fixture.repository();
            Goal created = createGoal(goals, "goal_missing", "run_missing", "create:missing");
            fixture.insertToolCall();
            GoalModels.ToolAttempt attempt = startedGoalTool(goals, "goal_missing", "run_missing",
                    "toolattempt_missing");
            Goal verifying = goals.requestEvaluation(new GoalRepository.RequestEvaluation("goal_missing",
                    created.revision(), "evaluation_missing", "run_missing", null, 1, List.of(),
                    "evt_missing_requested", "missing:requested", NOW.plusSeconds(2)));
            evaluator(goals).evaluateRequested("evaluation_missing", verifying.revision(),
                    evaluatorRequest("goal_missing", "run_missing"), "missing:evaluated")
                    .toCompletableFuture().join();

            goals.settleToolAttempt(new GoalRepository.SettleToolAttempt(attempt.toolAttemptId(),
                    GoalModels.ToolAttemptState.SUCCEEDED, "f".repeat(64), null, NOW.plusSeconds(3)));

            Goal incomplete = goals.findGoal("goal_missing").orElseThrow();
            assertEquals(GoalModels.GoalStatus.ACTIVE, incomplete.status());
            assertEquals(GoalModels.GoalPhase.WORKING, incomplete.phase());
        }
    }

    /** Goal 幂等 key 只可重放同一操作类型，pause key 不能越权成为 stop 或其它 mutation。 */
    @Test
    void rejectsGoalIdempotencyKeyReusedAcrossOperations() throws Exception {
        try (Fixture fixture = fixture("goal-idempotency-collision")) {
            MybatisGoalRepository goals = fixture.repository();
            Goal created = createGoal(goals, "goal_one", "run_goal_initial", "create:key1");
            Goal paused = goals.transition(new GoalRepository.Transition("goal_one", created.revision(),
                    GoalModels.GoalStatus.PAUSED, GoalModels.GoalPhase.PAUSED, false,
                    "evt_pause", "goal:shared", NOW));
            Goal replay = goals.transition(new GoalRepository.Transition("goal_one", created.revision(),
                    GoalModels.GoalStatus.PAUSED, GoalModels.GoalPhase.PAUSED, false,
                    "evt_pause_retry", "goal:shared", NOW.plusSeconds(1)));
            assertEquals(paused, replay);

            GoalRepositoryException collision = assertThrows(GoalRepositoryException.class, () ->
                    goals.transition(new GoalRepository.Transition("goal_one", paused.revision(),
                            GoalModels.GoalStatus.STOPPED, GoalModels.GoalPhase.STOPPED, false,
                            "evt_stop", "goal:shared", NOW.plusSeconds(2))));
            assertEquals(GoalRepositoryException.Code.GOAL_INVALID_STATE, collision.code());
            assertEquals(GoalModels.GoalStatus.PAUSED, goals.findGoal("goal_one").orElseThrow().status());
        }
    }

    /** fixture 创建 fresh V1 数据库，并让 JDBC 聚合仓储显式 commit/rollback。 */
    private Fixture fixture(String name) throws Exception {
        JaDatabase database = JaDatabase.open(new DatabaseConfig(temp.resolve(name + ".sqlite3"),
                DatabaseConfig.DEFAULT_BUSY_TIMEOUT));
        Configuration configuration = new Configuration(new Environment("goal-test",
                new JdbcTransactionFactory(), database.dataSource()));
        configuration.setMapUnderscoreToCamelCase(false);
        configuration.addMapper(SchemaMapper.class);
        SqlSessionFactory sessions = new SqlSessionFactoryBuilder().build(configuration);
        database.bindWalCheckpoint(sessions);
        try (SqlSession session = sessions.openSession(); Statement sql = session.getConnection().createStatement()) {
            sql.executeUpdate("INSERT INTO workspaces(workspace_id,root_path,display_name,trust,revision,created_at,updated_at) "
                    + "VALUES('ws_one','C:/goal-test','Goal test','TRUSTED',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            sql.executeUpdate("INSERT INTO threads(thread_id,workspace_id,title,revision,created_at,updated_at,"
                    + "provider_id,model_id,access_mode,title_source,collaboration_mode) VALUES("
                    + "'thr_one','ws_one','Goal',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'provider','model',"
                    + "'APPROVAL_REQUIRED','MANUAL','PLAN')");
            session.commit();
        }
        GoalUnitOfWork.SessionOwner owner = new GoalUnitOfWork.SessionOwner() {
            /** 测试事务保留 Goal 稳定错误，其它异常继续暴露原因。 */
            @Override public <T> T execute(SqlSessionFactory factory, GoalUnitOfWork.Work<T> work) {
                try (SqlSession session = factory.openSession()) {
                    try {
                        T value = work.apply(session.getConnection());
                        session.commit();
                        return value;
                    } catch (Throwable failure) {
                        session.rollback();
                        if (failure instanceof RuntimeException runtime) throw runtime;
                        throw new IllegalStateException("Goal test transaction failed", failure);
                    }
                }
            }
        };
        return new Fixture(database, sessions, new MybatisGoalRepository(sessions, new ObjectMapper(), owner));
    }

    /** lease 用例复用最小批准链。 */
    private static Goal approved(MybatisGoalRepository goals) {
        Goal goal = createGoal(goals, "goal_one", "run_goal_initial", "create:key1");
        goals.createPlan(new GoalRepository.CreatePlan(
                "plan_one", "thr_one", "交付目标", 0, "plan:create:key1", NOW));
        PlanDefinition definition = definition();
        CanonicalPlanJson.Encoded encoded = new CanonicalPlanJson(new ObjectMapper()).encode(definition);
        goals.propose(new GoalRepository.ProposePlan("plan_one", 0, new PlanRevision("planrev_one", "plan_one",
                1, definition, encoded.json(), encoded.sha256(), "AGENT", NOW),
                "evt_propose", "propose:key1", NOW));
        goals.approve(new GoalRepository.ApprovePlan("plan_one", 1, "planrev_one", encoded.sha256(),
                "appr_one", "evt_approve", "approve:key1", NOW));
        return goals.attachPlan(new GoalRepository.AttachPlan("goal_one", goal.revision(), "plan_one",
                "planrev_one", encoded.sha256(), "run_one", 1,
                "evt_attach", "attach:key1", NOW));
    }

    /** Goal criteria 独立于 Plan criteria，避免 link 后两个定义共享同一 identity。 */
    private static Goal createGoal(MybatisGoalRepository goals, String goalId, String runId, String key) {
        return goals.create(new GoalRepository.CreateGoal(goalId, "thr_one", GoalModels.OwnerKind.ROOT_THREAD,
                "交付目标", goalCriteria(), runId, 1, 0, key, NOW));
    }

    /** 测试 evaluator 固定返回结构化 MET，完成资格仍完全由 repository 的真实事实决定。 */
    private static GoalEvaluator evaluator(MybatisGoalRepository goals) {
        GoalEvaluatorPort port = request -> CompletableFuture.completedFuture(new GoalEvaluatorPort.Result(
                GoalModels.EvaluationVerdict.MET,
                List.of(new GoalModels.CriterionEvaluation("criterion_goal",
                        GoalModels.EvaluationVerdict.MET, "真实证据满足验收")),
                "独立 evaluator 判定通过"));
        return new GoalEvaluator(goals, port, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** evaluator 请求仅包含冻结 Goal 定义，Goal-only 路径禁止伪造 Plan revision。 */
    private static GoalEvaluatorPort.Request evaluatorRequest(String goalId, String runId) {
        return new GoalEvaluatorPort.Request(goalId, "thr_one", 1, null, runId,
                "provider", "model", "交付目标", null,
                List.of(new GoalEvaluatorPort.Criterion("criterion_goal", "目标验收通过", true)), List.of());
    }

    /** 外部测试报告 evidence 精确绑定当前 Goal definition/run，不依赖 evaluation Tool 自身。 */
    private static Evidence goalEvidence(String evidenceId, String goalId, String runId) {
        return new Evidence(evidenceId, goalId, null, 1L, runId, null, "criterion_goal", null,
                EvidenceSource.TEST_REPORT, "surefire_goal", "聚焦测试通过", "a".repeat(64), NOW, NOW);
    }

    /** 构造 STARTED Goal Tool，使测试精确覆盖 evaluator 与 terminal 回执的竞争窗口。 */
    private static GoalModels.ToolAttempt startedGoalTool(MybatisGoalRepository goals, String goalId,
                                                           String runId, String attemptId) {
        GoalModels.ToolAttempt attempt = goals.prepareToolAttempt(new GoalRepository.PrepareToolAttempt(
                new GoalModels.ToolAttempt(attemptId, goalId, null, 1L, runId, null, null, 1,
                        "turn_goal", "call_goal", 1, false, GoalModels.ToolAttemptState.PREPARED,
                        "b".repeat(64), null, NOW, null, null)));
        return goals.startToolAttempt(attempt.toolAttemptId(), NOW.plusSeconds(1));
    }

    /** Goal 完成门使用自己的冻结验收条件，不从 Plan revision 推断。 */
    private static List<AcceptanceCriterion> goalCriteria() {
        return List.of(new AcceptanceCriterion("criterion_goal", "目标验收通过", true));
    }

    /** Tool 请求只携带冻结 Turn identity，测试不允许 ledger 从 owner ambient 状态反推绑定。 */
    private static GoalToolExecutionPort.Prepare toolPrepare(String turnId, String callId) {
        return new GoalToolExecutionPort.Prepare("thr_one", turnId, TurnOrigin.GOAL_CONTINUATION,
                callId, "shell", JsonObject.empty(), ToolSideEffect.EXTERNAL, NOW);
    }

    /** revisionLiteral 刻意保留 JSON number 语法，用于覆盖合法整数与非法浮点的严格解析边界。 */
    private static String continuationContext(String runId, GoalModels.GoalPlanLink link,
                                              long fencingToken, String revisionLiteral) {
        return "{\"kind\":\"GOAL_CONTINUATION\",\"goalId\":\"goal_one\","
                + "\"planId\":\"" + link.planId() + "\",\"runId\":\"" + runId + "\","
                + "\"goalDefinitionRevision\":" + revisionLiteral + ",\"planRevisionId\":\""
                + link.planRevisionId() + "\",\"planHash\":\"" + link.planHash() + "\","
                + "\"fencingToken\":" + fencingToken + "}";
    }

    /** 聚焦测试使用一个无依赖必要步骤与一个必要验收。 */
    private static PlanDefinition definition() {
        return new PlanDefinition("交付目标", List.of("目标范围"), List.of(), List.of(), List.of(),
                List.of(new PlanStep("step_work", "实施", "完成实现", true, List.of())),
                List.of(new AcceptanceCriterion("criterion_plan", "计划测试通过", true)),
                List.of(), List.of("运行聚焦测试"));
    }

    /** 关闭 fresh 数据库并执行 WAL checkpoint。 */
    private record Fixture(JaDatabase database, SqlSessionFactory sessions,
                           MybatisGoalRepository repository) implements AutoCloseable {
        /** 建立既有 Turn Tool call，证明 Goal attempt 只能绑定真实持久 identity。 */
        private void insertToolCall() throws Exception {
            try (SqlSession session = sessions.openSession(); Statement sql = session.getConnection().createStatement()) {
                sql.executeUpdate("INSERT INTO turns(turn_id,thread_id,state,requested_at,updated_at,parent_turn_id,root_turn_id) "
                        + "VALUES('turn_goal','thr_one','RUNNING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL,NULL)");
                sql.executeUpdate("INSERT INTO tools(call_id,thread_id,turn_id,ordinal,tool_name,side_effect,"
                        + "presentation_json,state,revision,created_at,updated_at) VALUES('call_goal','thr_one',"
                        + "'turn_goal',0,'shell','EXTERNAL','{}','PREPARED',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
                session.commit();
            }
        }
        /** 插入真实 Turn/Tool/context 三元组，使 binding 回归经过 V1 外键与 JSON CHECK。 */
        private void insertInternalToolCall(String turnId, String callId, String contextJson) throws Exception {
            try (SqlSession session = sessions.openSession()) {
                try (java.sql.PreparedStatement turn = session.getConnection().prepareStatement(
                        "INSERT INTO turns(turn_id,thread_id,state,requested_at,updated_at,completed_at,"
                                + "terminal_summary,parent_turn_id,root_turn_id) VALUES(?,'thr_one','COMPLETED',"
                                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'completed',NULL,NULL)")) {
                    turn.setString(1, turnId);
                    turn.executeUpdate();
                }
                try (java.sql.PreparedStatement tool = session.getConnection().prepareStatement(
                        "INSERT INTO tools(call_id,thread_id,turn_id,ordinal,tool_name,side_effect,presentation_json,"
                                + "state,revision,created_at,updated_at) VALUES(?,'thr_one',?,0,'shell','EXTERNAL',"
                                + "'{}','PREPARED',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")) {
                    tool.setString(1, callId);
                    tool.setString(2, turnId);
                    tool.executeUpdate();
                }
                try (java.sql.PreparedStatement context = session.getConnection().prepareStatement(
                        "INSERT INTO turn_internal_context(turn_id,origin,context_json,created_at) "
                                + "VALUES(?,'GOAL_CONTINUATION',?,CURRENT_TIMESTAMP)")) {
                    context.setString(1, turnId);
                    context.setString(2, contextJson);
                    context.executeUpdate();
                }
                session.commit();
            }
        }
        /** attempt 数量用于证明拒绝路径没有在 SQLite 留下半提交 ledger。 */
        private int toolAttemptCount() throws Exception {
            try (SqlSession session = sessions.openSession(); Statement sql = session.getConnection().createStatement();
                 java.sql.ResultSet rows = sql.executeQuery("SELECT COUNT(*) FROM goal_tool_attempts")) {
                return rows.next() ? rows.getInt(1) : -1;
            }
        }
        /**
         * 以原始 V1 行分别制造 run replacement blocker；Tool blocker 使用终态 Turn，避免同时命中
         * 非终态 Turn 条件而失去逐项证明力。
         */
        private void insertRunReplacementBlocker(String blocker) throws Exception {
            try (SqlSession session = sessions.openSession(); Statement sql = session.getConnection().createStatement()) {
                switch (blocker) {
                    case "PENDING_INPUT" -> sql.executeUpdate("INSERT INTO goal_input_requests("
                            + "input_request_id,goal_id,run_id,prompt,state,expires_at,created_at) VALUES("
                            + "'input_blocker','goal_one','run_one','等待输入','PENDING',"
                            + "'2026-09-05T10:00:01Z','2026-09-04T10:00:01Z')");
                    case "PENDING_EVALUATOR" -> sql.executeUpdate("INSERT INTO goal_evaluations("
                            + "evaluation_id,goal_id,goal_definition_revision,run_id,plan_revision_id,status,"
                            + "process_generation,model_id,provider_id,requested_at) VALUES("
                            + "'evaluation_blocker','goal_one',1,'run_one','planrev_one','REQUESTED',1,"
                            + "'model','provider','2026-09-04T10:00:01Z')");
                    case "TOOL_PREPARED" -> insertToolBlocker(sql, "PREPARED", false);
                    case "TOOL_STARTED" -> insertToolBlocker(sql, "STARTED", false);
                    case "TOOL_UNKNOWN" -> insertToolBlocker(sql, "UNKNOWN", true);
                    case "HELD_LEASE" -> sql.executeUpdate("INSERT INTO goal_continuation_leases("
                            + "lease_id,goal_id,process_generation,fencing_token,state,acquired_at,heartbeat_at) "
                            + "VALUES('lease_blocker','goal_one',1,1,'HELD','2026-09-04T10:00:01Z',"
                            + "'2026-09-04T10:00:01Z')");
                    default -> throw new IllegalArgumentException("unknown blocker: " + blocker);
                }
                session.commit();
            }
        }
        /** Tool attempt 通过真实 tools 外键写入，started/completed 时间与三种 ledger 状态保持一致。 */
        private static void insertToolBlocker(Statement sql, String state, boolean sideEffect) throws Exception {
            sql.executeUpdate("INSERT INTO turns(turn_id,thread_id,state,requested_at,updated_at,completed_at,"
                    + "terminal_summary,parent_turn_id,root_turn_id) VALUES('turn_blocker','thr_one','COMPLETED',"
                    + "'2026-09-04T10:00:01Z','2026-09-04T10:00:01Z','2026-09-04T10:00:01Z',"
                    + "'completed',NULL,NULL)");
            sql.executeUpdate("INSERT INTO tools(call_id,thread_id,turn_id,ordinal,tool_name,side_effect,"
                    + "presentation_json,state,revision,created_at,updated_at) VALUES('call_blocker','thr_one',"
                    + "'turn_blocker',0,'shell','EXTERNAL','{}','PREPARED',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            String startedAt = "PREPARED".equals(state) ? "NULL" : "'2026-09-04T10:00:01Z'";
            String completedAt = "UNKNOWN".equals(state) ? "'2026-09-04T10:00:01Z'" : "NULL";
            sql.executeUpdate("INSERT INTO goal_tool_attempts(tool_attempt_id,goal_id,plan_id,"
                    + "goal_definition_revision,run_id,plan_revision_id,step_id,attempt,turn_id,call_id,"
                    + "process_generation,side_effect,state,request_digest,prepared_at,started_at,completed_at) "
                    + "VALUES('attempt_blocker','goal_one','plan_one',1,'run_one','planrev_one','step_work',1,"
                    + "'turn_blocker','call_blocker',1," + (sideEffect ? 1 : 0) + ",'" + state + "','"
                    + "a".repeat(64) + "','2026-09-04T10:00:01Z'," + startedAt + ',' + completedAt + ")");
        }
        /** 直接回读 Run 状态，只用于证明 Goal 与 Run 的同事务生命周期投影。 */
        private String runStatus(String runId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 java.sql.PreparedStatement query = session.getConnection().prepareStatement(
                         "SELECT status FROM execution_runs WHERE run_id=?")) {
                query.setString(1, runId);
                try (java.sql.ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("run is unavailable");
                    return rows.getString(1);
                }
            }
        }
        /** 关闭 fresh 数据库并执行 WAL checkpoint。 */
        @Override public void close() { database.close(); }
    }
}
