// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.TransactionResource;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.application.gateway.JaAdmissionTransactionParticipant;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEvent;
import io.github.kongweiguang.ja.conversation.domain.model.TurnResult;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.infrastructure.persistence.database.DatabaseConfig;
import io.github.kongweiguang.ja.infrastructure.persistence.database.JaDatabase;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PlanMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SchemaMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.AgentStoreConnectionScope;
import io.github.kongweiguang.ja.plan.application.api.PlanExecutionEventSink;
import io.github.kongweiguang.ja.plan.application.api.PlanUseCase.PlanControl;
import io.github.kongweiguang.ja.plan.application.gateway.PlanConversationPort;
import io.github.kongweiguang.ja.plan.application.gateway.PlanEvaluatorPort;
import io.github.kongweiguang.ja.plan.application.service.PlanStartupRecovery;
import io.github.kongweiguang.ja.plan.application.gateway.PlanTurnPort;
import io.github.kongweiguang.ja.plan.application.gateway.PlanWorkspacePort;
import io.github.kongweiguang.ja.plan.application.service.PlanCommittedPublisher;
import io.github.kongweiguang.ja.plan.application.service.PlanEventRegistry;
import io.github.kongweiguang.ja.plan.application.service.PlanExecutionCoordinator;
import io.github.kongweiguang.ja.plan.application.service.PlanExecutionTurnAdapter;
import io.github.kongweiguang.ja.plan.application.service.PlanService;
import io.github.kongweiguang.ja.plan.infrastructure.persistence.serialization.CanonicalPlanJson;
import io.github.kongweiguang.ja.plan.application.gateway.PlanCanonicalizer;
import io.github.kongweiguang.ja.plan.domain.PlanModels.AcceptanceCriterion;
import io.github.kongweiguang.ja.plan.domain.PlanModels.Plan;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanDefinition;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanRevision;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanSnapshot;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanStatus;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanStep;
import io.github.kongweiguang.ja.plan.domain.PlanModels.StepStatus;
import io.github.kongweiguang.ja.plan.domain.PlanModels.ToolAttempt;
import io.github.kongweiguang.ja.plan.domain.PlanModels.ToolAttemptState;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepositoryException;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实 SQLite 验证 Plan 授权、执行幂等、恢复、owner 隔离与持久事件分页。 */
final class MybatisPlanRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");

    @TempDir
    Path temp;

    /**
     * 分别保存可独立对账的模型与工具计量，避免把不同副作用来源合并成无法核验的总数。
     *
     * @param modelRounds 已结算模型轮数
     * @param toolCalls 已结算工具调用数
     */
    private record RunUsage(long modelRounds,
                            long toolCalls) { }

    /** Store 借用连接时 Plan 仓储不得自行提交或关闭，外层回滚后读模型也必须为空。 */
    /**
     * 从借用连接读取未提交写入，证明提交与回滚仍由调用方事务控制且不会触发 SQLite 锁冲突。
     */
    @Test
    void joinsAgentStoreConnectionWithoutCommittingOrClosingIt() throws Exception {
        try (Fixture fixture = fixture("borrowed-store-connection")) {
            MybatisPlanRepository plans = fixture.repository();
            try (SqlSession store = fixture.sessions().openSession()) {
                AgentStoreConnectionScope.call(store.getConnection(), () -> {
                    plans.createPlan(new PlanRepository.CreatePlan(
                            "plan_joined", "thr_one", "同事务计划", 0, "plan:create:join", NOW));
                    return null;
                });

                assertFalse(store.getConnection().isClosed());
                assertFalse(store.getConnection().getAutoCommit());
                try (PreparedStatement query = store.getConnection().prepareStatement(
                        "SELECT COUNT(*) FROM ja_plan WHERE plan_id=?")) {
                    query.setString(1, "plan_joined");
                    try (ResultSet rows = query.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals(1, rows.getInt(1));
                    }
                }
                store.getConnection().rollback();
            }

            assertTrue(plans.findCurrentPlan("thr_one").isEmpty());
        }
    }

    /** 批准只能引用服务端冻结的最新 revision/hash，单独批准不会提前登记执行副作用。 */
    @Test
    void approvesOnlyTheExactRevisionWithoutCreatingRun() throws Exception {
        try (Fixture fixture = fixture("exact-approval")) {
            MybatisPlanRepository plans = fixture.repository();
            Plan awaiting = createAwaitingPlan(plans, "plan_approval", "thr_one", "revision_approval");
            PlanRevision revision = plans.readPlanSnapshot(awaiting.planId()).currentRevision();

            PlanRepositoryException staleHash = assertThrows(PlanRepositoryException.class, () ->
                    plans.approve(new PlanRepository.ApprovePlan(awaiting.planId(), awaiting.revision(),
                            revision.planRevisionId(), "0".repeat(64), "approval_stale", "event_stale",
                            "approve:exact", NOW)));
            assertEquals(PlanRepositoryException.Code.PLAN_APPROVAL_STALE, staleHash.code());
            assertEquals(PlanStatus.AWAITING_APPROVAL,
                    plans.readPlanSnapshot(awaiting.planId()).plan().status());
            assertEquals(0, fixture.runCount(awaiting.planId()));

            Plan approved = plans.approve(new PlanRepository.ApprovePlan(awaiting.planId(), awaiting.revision(),
                    revision.planRevisionId(), revision.planHash(), "approval_exact", "event_approved",
                    "approve:exact", NOW.plusSeconds(1)));
            PlanSnapshot snapshot = plans.readPlanSnapshot(approved.planId());

            assertEquals(PlanStatus.APPROVED, approved.status());
            assertNull(approved.activeRunId());
            assertEquals(revision.planRevisionId(), snapshot.approval().planRevisionId());
            assertEquals(revision.planHash(), snapshot.approval().planHash());
            assertEquals(1, fixture.approvalCount(approved.planId(), revision));
            assertEquals(0, fixture.runCount(approved.planId()));
        }
    }

    /** 数据库定义被改而 hash 未变时，批准必须失败且不能落批准、Run 或事件事实。 */
    @Test
    void rejectsApprovalWhenPersistedRevisionDefinitionDoesNotMatchHash() throws Exception {
        try (Fixture fixture = fixture("tampered-approval")) {
            MybatisPlanRepository plans = fixture.repository();
            Plan awaiting = createAwaitingPlan(plans, "plan_tampered_approval", "thr_one", "revision_tampered_approval");
            PlanRevision revision = plans.readPlanSnapshot(awaiting.planId()).currentRevision();
            int eventsBeforeApproval = fixture.planEventCount(awaiting.planId());
            fixture.replaceRevisionDefinition(revision.planRevisionId(), definition("篡改后的工作范围"));

            PlanRepositoryException failure = assertThrows(PlanRepositoryException.class, () ->
                    plans.approve(new PlanRepository.ApprovePlan(awaiting.planId(), awaiting.revision(),
                            revision.planRevisionId(), revision.planHash(), "approval_tampered",
                            "event_tampered_approval", "approve:tampered", NOW.plusSeconds(1))));

            assertEquals(PlanRepositoryException.Code.PLAN_INVALID_STATE, failure.code());
            assertEquals("AWAITING_APPROVAL", fixture.planStatus(awaiting.planId()));
            assertEquals(eventsBeforeApproval, fixture.planEventCount(awaiting.planId()));
            assertEquals(0, fixture.approvalCount(awaiting.planId(), revision));
            assertEquals(0, fixture.runCount(awaiting.planId()));
        }
    }

    /** 执行授权、批准回执、Run 和事件在单一提交中形成；丢响应后的原命令重放不重复建账。 */
    @Test
    void executesAndReplaysOneAuthorizedRun() throws Exception {
        try (Fixture fixture = fixture("execute-idempotency")) {
            MybatisPlanRepository plans = fixture.repository();
            Plan awaiting = createAwaitingPlan(plans, "plan_execute", "thr_one", "revision_execute");
            PlanRevision revision = plans.readPlanSnapshot(awaiting.planId()).currentRevision();
            PlanRepository.ExecutePlan command = executeCommand(
                    awaiting, revision, "run_execute", "execute:once", NOW.plusSeconds(1));

            Plan executing = plans.executePlan(command);
            Plan replayed = plans.executePlan(command);
            PlanSnapshot snapshot = plans.readPlanSnapshot(executing.planId());
            List<PlanRepository.PlanEvent> events = plans.listPlanEvents(executing.planId(), 0, 20).items();

            assertEquals(PlanStatus.EXECUTING, executing.status());
            assertEquals("run_execute", executing.activeRunId());
            assertEquals(executing, replayed);
            assertEquals(revision.planRevisionId(), snapshot.approval().planRevisionId());
            assertEquals(revision.planHash(), snapshot.approval().planHash());
            assertEquals(1, fixture.approvalCount(executing.planId(), revision));
            assertEquals(1, fixture.runCount(executing.planId()));
            assertEquals(1, events.stream().filter(event ->
                    "plan_execution_started".equals(event.activity())).count());
        }
    }

    /** 新版本提出后，旧版本身份即使带正确旧 hash 也不得写入批准、Run 或执行事件。 */
    @Test
    void rejectsExecutionAgainstSupersededRevision() throws Exception {
        try (Fixture fixture = fixture("superseded-revision")) {
            MybatisPlanRepository plans = fixture.repository();
            Plan firstAwaiting = createAwaitingPlan(plans, "plan_revision", "thr_one", "revision_first");
            PlanRevision first = plans.readPlanSnapshot(firstAwaiting.planId()).currentRevision();
            Plan rejected = plans.reject(new PlanRepository.RejectPlan(firstAwaiting.planId(),
                    firstAwaiting.revision(), first.planRevisionId(), first.planHash(), "rejection_first",
                    "重新规划", "event_rejected", "reject:first", NOW.plusSeconds(1)));
            PlanDefinition nextDefinition = definition("第二版范围");
            PlanCanonicalizer.Encoded nextCanonical = new CanonicalPlanJson(new ObjectMapper()).encode(nextDefinition);
            PlanRevision second = plans.propose(new PlanRepository.ProposePlan(firstAwaiting.planId(),
                    rejected.revision(), new PlanRevision("revision_second", firstAwaiting.planId(), 2,
                            nextDefinition, nextCanonical.json(), nextCanonical.sha256(), "USER_UI",
                            NOW.plusSeconds(2)), "event_proposed_second", "propose:second", NOW.plusSeconds(2)));
            Plan latestAwaiting = plans.readPlanSnapshot(firstAwaiting.planId()).plan();

            PlanRepositoryException stale = assertThrows(PlanRepositoryException.class, () ->
                    plans.executePlan(executeCommand(latestAwaiting, first, "run_stale", "execute:stale",
                            NOW.plusSeconds(3))));

            assertEquals(PlanRepositoryException.Code.PLAN_APPROVAL_STALE, stale.code());
            assertEquals(PlanStatus.AWAITING_APPROVAL,
                    plans.readPlanSnapshot(firstAwaiting.planId()).plan().status());
            assertEquals(0, fixture.runCount(firstAwaiting.planId()));
            assertEquals(0, fixture.approvalCount(firstAwaiting.planId(), first));

            Plan executing = plans.executePlan(executeCommand(latestAwaiting, second,
                    "run_current", "execute:current", NOW.plusSeconds(4)));
            assertEquals(PlanStatus.EXECUTING, executing.status());
            assertEquals("run_current", executing.activeRunId());
            assertEquals(1, fixture.approvalCount(executing.planId(), second));
            assertEquals(1, fixture.runCount(executing.planId()));
        }
    }

    /** 暂停和显式恢复只切换同一 Run 状态，不得因重复恢复创建新的执行账本。 */
    /**
     * 暂停 Run 的持久状态须在同一身份恢复前读取，因为恢复会原位更新该 execution_runs 行。
     */
    @Test
    void pausesAndResumesTheSameRunIdempotently() throws Exception {
        try (Fixture fixture = fixture("pause-resume")) {
            MybatisPlanRepository plans = fixture.repository();
            Plan awaiting = createAwaitingPlan(plans, "plan_resume", "thr_one", "revision_resume");
            PlanRevision revision = plans.readPlanSnapshot(awaiting.planId()).currentRevision();
            Plan executing = plans.executePlan(executeCommand(
                    awaiting, revision, "run_resume", "execute:resume", NOW.plusSeconds(1)));

            Plan paused = plans.pausePlan(new PlanRepository.PausePlan(executing.planId(),
                    executing.revision(), executing.activeRunId(), "event_paused", "pause:resume",
                    NOW.plusSeconds(2)));
            String persistedPausedRunStatus = fixture.runStatus(paused.activeRunId());
            PlanRepository.ResumePlan resume = new PlanRepository.ResumePlan(paused.planId(), paused.revision(),
                    paused.activeRunId(), "event_resumed", "resume:resume", NOW.plusSeconds(3));
            PlanRepository.ResumeResult firstResume = plans.resumePlan(resume);
            PlanRepository.ResumeResult replayResume = plans.resumePlan(resume);
            Plan resumed = firstResume.plan();
            Plan replayed = replayResume.plan();

            assertEquals(PlanStatus.PAUSED, paused.status());
            assertEquals("PAUSED", persistedPausedRunStatus);
            assertTrue(firstResume.applied());
            assertFalse(replayResume.applied());
            assertEquals(PlanStatus.EXECUTING, resumed.status());
            assertEquals("RUNNING", fixture.runStatus(resumed.activeRunId()));
            assertEquals("run_resume", resumed.activeRunId());
            assertEquals(resumed, replayed);
            assertEquals(1, fixture.runCount(resumed.planId()));
        }
    }

    /** 恢复前重验批准版本完整性，损坏定义不得清 fence、恢复 Run 或新增事件。 */
    @Test
    void rejectsResumeWhenPersistedRevisionDefinitionDoesNotMatchHash() throws Exception {
        try (Fixture fixture = fixture("tampered-resume")) {
            MybatisPlanRepository plans = fixture.repository();
            Plan awaiting = createAwaitingPlan(plans, "plan_tampered_resume", "thr_one", "revision_tampered_resume");
            PlanRevision revision = plans.readPlanSnapshot(awaiting.planId()).currentRevision();
            Plan executing = plans.executePlan(executeCommand(
                    awaiting, revision, "run_tampered_resume", "execute:tampered-resume", NOW.plusSeconds(1)));
            Plan paused = plans.pausePlan(new PlanRepository.PausePlan(executing.planId(), executing.revision(),
                    executing.activeRunId(), "event_pause_tampered", "pause:tampered-resume", NOW.plusSeconds(2)));
            int eventsBeforeResume = fixture.planEventCount(paused.planId());
            fixture.replaceRevisionDefinition(revision.planRevisionId(), definition("恢复前被篡改的工作范围"));

            PlanRepositoryException failure = assertThrows(PlanRepositoryException.class, () ->
                    plans.resumePlan(new PlanRepository.ResumePlan(paused.planId(), paused.revision(),
                            paused.activeRunId(), "event_tampered_resume", "resume:tampered", NOW.plusSeconds(3))));

            assertEquals(PlanRepositoryException.Code.PLAN_INVALID_STATE, failure.code());
            assertEquals("PAUSED", fixture.planStatus(paused.planId()));
            assertEquals("PAUSED", fixture.runStatus(paused.activeRunId()));
            assertEquals(eventsBeforeResume, fixture.planEventCount(paused.planId()));
            assertEquals(1, fixture.runCount(paused.planId()));
        }
    }

    /** 重启依据持久副作用与 Turn claim 判定恢复；无 claim 的未知 evaluator 结果不能继续执行。 */
    @Test
    void recoversSafeAndUnknownRunsWithDifferentOutcomes() throws Exception {
        try (Fixture safeFixture = fixture("safe-recovery")) {
            MybatisPlanRepository plans = safeFixture.repository();
            Plan awaiting = createAwaitingPlan(plans, "plan_safe", "thr_one", "revision_safe");
            PlanRevision revision = plans.readPlanSnapshot(awaiting.planId()).currentRevision();
            Plan executing = plans.executePlan(executeCommand(
                    awaiting, revision, "run_safe", "execute:safe", NOW.plusSeconds(1)));
            PlanRepository.PlanExecutionRecovery recovery =
                    plans.listUnsettledPlanExecutions(2, 20).getFirst();

            assertFalse(recovery.unsafe());
            Plan recovered = plans.recoverPlanExecution(new PlanRepository.RecoverPlanExecution(
                    recovery.planId(), recovery.expectedPlanRevision(), recovery.runId(), false,
                    "event_recovered", "recover:safe", NOW.plusSeconds(2)));
            assertEquals(PlanStatus.PAUSED, recovered.status());
            assertEquals(executing.activeRunId(), recovered.activeRunId());
            assertEquals("PAUSED", safeFixture.runStatus(executing.activeRunId()));
        }

        try (Fixture unsafeFixture = fixture("unknown-recovery")) {
            MybatisPlanRepository plans = unsafeFixture.repository();
            Plan awaiting = createAwaitingPlan(plans, "plan_unknown", "thr_one", "revision_unknown");
            PlanRevision revision = plans.readPlanSnapshot(awaiting.planId()).currentRevision();
            Plan executing = plans.executePlan(executeCommand(
                    awaiting, revision, "run_unknown", "execute:unknown", NOW.plusSeconds(1)));
            unsafeFixture.insertClaimedToolCall(executing, "turn_unknown", "call_unknown");
            ToolAttempt prepared = plans.prepareToolAttempt(new PlanRepository.PrepareToolAttempt(
                    new ToolAttempt("attempt_unknown", executing.planId(), executing.activeRunId(),
                            executing.activePlanRevisionId(), "step_work", 1, "turn_unknown", "call_unknown",
                            1, true, ToolAttemptState.PREPARED, "d".repeat(64), null, NOW, null, null)));
            plans.startToolAttempt(prepared.toolAttemptId(), NOW.plusSeconds(2));
            PlanRepository.PlanExecutionRecovery recovery =
                    plans.listUnsettledPlanExecutions(2, 20).getFirst();

            assertTrue(recovery.unsafe());
            Plan recovered = plans.recoverPlanExecution(new PlanRepository.RecoverPlanExecution(
                    recovery.planId(), recovery.expectedPlanRevision(), recovery.runId(), false,
                    "event_recovery_required", "recover:unknown", NOW.plusSeconds(3)));
            assertEquals(PlanStatus.PAUSED, recovered.status());
            assertEquals("PAUSED", unsafeFixture.runStatus(executing.activeRunId()));
            assertEquals("CLAIMED", unsafeFixture.planTurnClaimState(executing.activeRunId(), "turn_unknown"));
            assertTrue(plans.listPlanEvents(executing.planId(), 0, 20).items().stream()
                    .anyMatch(event -> "plan_recovery_required".equals(event.activity())));
        }

        try (Fixture unsafeWithoutTurnFixture = fixture("unknown-recovery-without-turn")) {
            MybatisPlanRepository plans = unsafeWithoutTurnFixture.repository();
            Plan awaiting = createAwaitingPlan(plans, "plan_unknown_without_turn", "thr_one",
                    "revision_unknown_without_turn");
            PlanRevision revision = plans.readPlanSnapshot(awaiting.planId()).currentRevision();
            Plan executing = plans.executePlan(executeCommand(awaiting, revision, "run_unknown_without_turn",
                    "execute:unknown-without-turn", NOW.plusSeconds(1)));
            unsafeWithoutTurnFixture.insertUnknownEvaluationAttempt(executing, "evaluation_unknown_without_turn");
            PlanRepository.PlanExecutionRecovery recovery = plans.listUnsettledPlanExecutions(2, 20).getFirst();

            assertTrue(recovery.unsafe());
            assertEquals(0, unsafeWithoutTurnFixture.planTurnClaimCount(executing.activeRunId()));
            Plan recovered = plans.recoverPlanExecution(new PlanRepository.RecoverPlanExecution(
                    recovery.planId(), recovery.expectedPlanRevision(), recovery.runId(), recovery.unsafe(),
                    "event_recovery_required_without_turn", "recover:unknown-without-turn", NOW.plusSeconds(2)));

            assertEquals(PlanStatus.STOPPED, recovered.status());
            assertEquals("STOPPED", unsafeWithoutTurnFixture.runStatus(executing.activeRunId()));
        }
    }

    /** unsafe UNKNOWN 保留 Turn claim 防止整轮副作用重放；安全 attempt 可结算失败，但重复启动不能解除阻塞。 */
    @Test
    void startupRecoveryKeepsMixedRunBlockedRegardlessOfAttemptOrder() throws Exception {
        for (String scenario : List.of("safe-first", "unsafe-first", "already-unknown")) {
            try (Fixture fixture = fixture("mixed-recovery-" + scenario)) {
                MybatisPlanRepository plans = fixture.repository();
                Plan awaiting = createAwaitingPlan(plans, "plan_mixed", "thr_one", "revision_mixed");
                PlanRevision revision = plans.readPlanSnapshot(awaiting.planId()).currentRevision();
                Plan executing = plans.executePlan(executeCommand(
                        awaiting, revision, "run_mixed", "execute:mixed", NOW.plusSeconds(1)));
                fixture.claimTurn(executing, "turn_recovery_mixed");
                fixture.insertTurn("turn_recovery_mixed");
                fixture.insertToolCall("turn_recovery_mixed", "call_safe_mixed", 0);
                fixture.insertToolCall("turn_recovery_mixed", "call_unsafe_mixed", 1);
                boolean unsafeFirst = scenario.equals("unsafe-first");
                ToolAttempt safe = plans.prepareToolAttempt(new PlanRepository.PrepareToolAttempt(new ToolAttempt(
                        "attempt_safe", executing.planId(), executing.activeRunId(), executing.activePlanRevisionId(),
                        "step_work", 1, "turn_recovery_mixed", "call_safe_mixed", 1, false, ToolAttemptState.PREPARED,
                        "d".repeat(64), null, NOW.plusSeconds(unsafeFirst ? 4 : 3), null, null)));
                ToolAttempt unsafe = plans.prepareToolAttempt(new PlanRepository.PrepareToolAttempt(new ToolAttempt(
                        "attempt_unsafe", executing.planId(), executing.activeRunId(), executing.activePlanRevisionId(),
                        "step_work", 2, "turn_recovery_mixed", "call_unsafe_mixed", 1, true, ToolAttemptState.PREPARED,
                        "e".repeat(64), null, NOW.plusSeconds(unsafeFirst ? 3 : 4), null, null)));
                plans.startToolAttempt(safe.toolAttemptId(), NOW.plusSeconds(5));
                plans.startToolAttempt(unsafe.toolAttemptId(), NOW.plusSeconds(5));
                if (scenario.equals("already-unknown")) {
                    plans.settleToolAttempt(new PlanRepository.SettleToolAttempt(unsafe.toolAttemptId(),
                            ToolAttemptState.UNKNOWN, null, null, NOW.plusSeconds(6)));
                }

                PlanStartupRecovery recovery = new PlanStartupRecovery(plans,
                        Clock.fixed(NOW.plusSeconds(7), ZoneOffset.UTC), 2);
                recovery.recover();

                assertEquals(PlanStatus.PAUSED, plans.readPlanSnapshot(executing.planId()).plan().status());
                assertEquals("PAUSED", fixture.runStatus(executing.activeRunId()));
                // UNKNOWN 外部副作用继续阻止 Turn claim 结算，避免重放包含未知副作用的整个 Turn。
                assertEquals("CLAIMED", fixture.planTurnClaimState(
                        executing.activeRunId(), "turn_recovery_mixed"));
                assertEquals(ToolAttemptState.FAILED, plans.findToolAttemptsByTurnIdCallId(
                        "turn_recovery_mixed", "call_safe_mixed").getFirst().state());
                assertEquals(ToolAttemptState.UNKNOWN, plans.findToolAttemptsByTurnIdCallId(
                        "turn_recovery_mixed", "call_unsafe_mixed").getFirst().state());
                assertEquals(1, plans.listPlanEvents(executing.planId(), 0, 100).items().stream()
                        .filter(event -> event.activity().equals("plan_recovery_required")).count());
                int events = fixture.planEventCount(executing.planId());
                recovery.recover();
                assertEquals(events, fixture.planEventCount(executing.planId()));
                assertEquals("PAUSED", fixture.runStatus(executing.activeRunId()));
            }
        }
    }

    /** 暂停不是外部结果的证明；旧坏恢复状态及未结算调用都必须在恢复写入前拒绝。 */
    @Test
    void rejectsResumeWithUnsettledToolResultsWithoutChangingRun() throws Exception {
        for (ToolAttemptState state : List.of(ToolAttemptState.PREPARED,
                ToolAttemptState.STARTED, ToolAttemptState.UNKNOWN)) {
            try (Fixture fixture = fixture("resume-unsettled-" + state)) {
                MybatisPlanRepository plans = fixture.repository();
                Plan awaiting = createAwaitingPlan(plans, "plan_unsettled", "thr_one", "revision_unsettled");
                PlanRevision revision = plans.readPlanSnapshot(awaiting.planId()).currentRevision();
                Plan executing = plans.executePlan(executeCommand(
                        awaiting, revision, "run_unsettled", "execute:unsettled", NOW.plusSeconds(1)));
                fixture.insertClaimedToolCall(executing, "turn_unsettled", "call_unsettled");
                ToolAttempt attempt = plans.prepareToolAttempt(new PlanRepository.PrepareToolAttempt(new ToolAttempt(
                        "attempt_unsettled", executing.planId(), executing.activeRunId(),
                        executing.activePlanRevisionId(), "step_work", 1, "turn_unsettled", "call_unsettled",
                        1, true, ToolAttemptState.PREPARED, "d".repeat(64), null, NOW.plusSeconds(2), null, null)));
                if (state != ToolAttemptState.PREPARED) {
                    plans.startToolAttempt(attempt.toolAttemptId(), NOW.plusSeconds(3));
                }
                if (state == ToolAttemptState.UNKNOWN) {
                    plans.settleToolAttempt(new PlanRepository.SettleToolAttempt(attempt.toolAttemptId(),
                            state, null, null, NOW.plusSeconds(4)));
                }
                Plan paused = plans.pausePlan(new PlanRepository.PausePlan(executing.planId(), executing.revision(),
                        executing.activeRunId(), "event_pause_unsettled", "pause:unsettled", NOW.plusSeconds(5)));
                int events = fixture.planEventCount(paused.planId());

                PlanRepositoryException failure = assertThrows(PlanRepositoryException.class, () ->
                        plans.resumePlan(new PlanRepository.ResumePlan(paused.planId(), paused.revision(),
                                paused.activeRunId(), "event_resume_unsettled", "resume:unsettled",
                                NOW.plusSeconds(6))));

                assertEquals(PlanRepositoryException.Code.PLAN_INVALID_STATE, failure.code());
                assertEquals(paused, plans.readPlanSnapshot(paused.planId()).plan());
                assertEquals("PAUSED", fixture.runStatus(paused.activeRunId()));
                assertEquals(events, fixture.planEventCount(paused.planId()));
                assertEquals(state, plans.findToolAttemptsByTurnIdCallId(
                        "turn_unsettled", "call_unsettled").getFirst().state());
            }
        }
    }

    /** SQLite 同键并发恢复由同一事务串行化，只有首次提交返回 applied=true 并追加恢复事件。 */
    @Test
    void concurrentResumeReplayReportsSingleAppliedMutation() throws Exception {
        try (Fixture fixture = fixture("concurrent-resume-replay")) {
            MybatisPlanRepository plans = fixture.repository();
            Plan awaiting = createAwaitingPlan(plans, "plan_concurrent_resume", "thr_one",
                    "revision_concurrent_resume");
            PlanRevision revision = plans.readPlanSnapshot(awaiting.planId()).currentRevision();
            Plan executing = plans.executePlan(executeCommand(awaiting, revision,
                    "run_concurrent_resume", "execute:concurrent-resume", NOW.plusSeconds(1)));
            Plan paused = plans.pausePlan(new PlanRepository.PausePlan(executing.planId(), executing.revision(),
                    executing.activeRunId(), "event_pause_concurrent_resume", "pause:concurrent-resume",
                    NOW.plusSeconds(2)));
            PlanRepository.ResumePlan command = new PlanRepository.ResumePlan(paused.planId(), paused.revision(),
                    paused.activeRunId(), "event_resume_concurrent_resume", "resume:concurrent", NOW.plusSeconds(3));
            int eventsBeforeResume = fixture.planEventCount(paused.planId());
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService callers = Executors.newFixedThreadPool(2);
            try {
                Future<PlanRepository.ResumeResult> first = callers.submit(
                        () -> resumeConcurrently(plans, command, ready, start));
                Future<PlanRepository.ResumeResult> second = callers.submit(
                        () -> resumeConcurrently(plans, command, ready, start));
                assertTrue(ready.await(5, TimeUnit.SECONDS), "both resume requests must reach the gate");
                start.countDown();

                PlanRepository.ResumeResult firstResult = first.get(5, TimeUnit.SECONDS);
                PlanRepository.ResumeResult secondResult = second.get(5, TimeUnit.SECONDS);

                assertEquals(1, (firstResult.applied() ? 1 : 0) + (secondResult.applied() ? 1 : 0));
                assertEquals(firstResult.plan(), secondResult.plan());
                assertEquals(PlanStatus.EXECUTING, plans.readPlanSnapshot(paused.planId()).plan().status());
                assertEquals(eventsBeforeResume + 1, fixture.planEventCount(paused.planId()));
                assertEquals(1, fixture.runCount(paused.planId()));
            } finally {
                start.countDown();
                callers.shutdownNow();
            }
        }
    }

    /** 两个并发调用共享 barrier，确保返回 applied 标记来自仓储事务而非线程先后假设。 */
    private static PlanRepository.ResumeResult resumeConcurrently(MybatisPlanRepository plans,
                                                                   PlanRepository.ResumePlan command,
                                                                   CountDownLatch ready,
                                                                   CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("resume race gate timed out");
        }
        return plans.resumePlan(command);
    }

    /** pause/stop fence 按持久活动类型原子去重，PAUSED 仍允许新的 stop 操作安全结算。 */
    @Test
    void controlFenceReplaysOnlyMatchingActivityAndAllowsStopFromPaused() throws Exception {
        try (Fixture fixture = fixture("control-fence-replay")) {
            MybatisPlanRepository plans = fixture.repository();
            Plan awaiting = createAwaitingPlan(plans, "plan_control_fence", "thr_one", "revision_control_fence");
            PlanRevision revision = plans.readPlanSnapshot(awaiting.planId()).currentRevision();
            Plan executing = plans.executePlan(executeCommand(awaiting, revision,
                    "run_control_fence", "execute:control-fence", NOW.plusSeconds(1)));
            PlanRepository.RequestPlanPause pauseFence = new PlanRepository.RequestPlanPause(
                    executing.planId(), executing.revision(), executing.activeRunId(),
                    "event_pause_fence", "pause:control-fence", NOW.plusSeconds(2));

            PlanRepository.PlanControlFenceResult firstPause = plans.requestPlanControlFence(pauseFence,
                    PlanRepository.PlanControlAction.PAUSE);
            Plan paused = plans.pausePlan(new PlanRepository.PausePlan(executing.planId(), executing.revision(),
                    executing.activeRunId(), "event_pause_control_fence", pauseFence.operationKey(), NOW.plusSeconds(3)));
            PlanRepository.ResumeResult resumedResult = plans.resumePlan(new PlanRepository.ResumePlan(
                    paused.planId(), paused.revision(), paused.activeRunId(), "event_resume_control_fence",
                    "resume:control-fence", NOW.plusSeconds(4)));
            Plan resumed = resumedResult.plan();
            int eventsBeforePauseReplay = fixture.planEventCount(resumed.planId());
            PlanRepository.PlanControlFenceResult pauseReplay = plans.requestPlanControlFence(
                    new PlanRepository.RequestPlanPause(resumed.planId(), resumed.revision(), resumed.activeRunId(),
                            "event_pause_fence_replay", pauseFence.operationKey(), NOW.plusSeconds(5)),
                    PlanRepository.PlanControlAction.PAUSE);
            int eventsBeforeCrossActivity = fixture.planEventCount(resumed.planId());

            PlanRepositoryException crossActivity = assertThrows(PlanRepositoryException.class,
                    () -> plans.requestPlanControlFence(new PlanRepository.RequestPlanPause(
                                    resumed.planId(), resumed.revision(), resumed.activeRunId(),
                                    "event_stop_cross_activity", pauseFence.operationKey(), NOW.plusSeconds(6)),
                            PlanRepository.PlanControlAction.STOP));

            assertEquals(PlanRepositoryException.Code.PLAN_INVALID_STATE, crossActivity.code());
            assertTrue(firstPause.shouldControl());
            assertFalse(pauseReplay.shouldControl());
            assertEquals(resumed, pauseReplay.plan());
            assertFalse(fixture.runPauseRequested(resumed.activeRunId()));
            assertEquals(eventsBeforePauseReplay, fixture.planEventCount(resumed.planId()));
            assertEquals(eventsBeforeCrossActivity, fixture.planEventCount(resumed.planId()));

            Plan pausedAgain = plans.pausePlan(new PlanRepository.PausePlan(resumed.planId(), resumed.revision(),
                    resumed.activeRunId(), "event_pause_again_control_fence", "pause:control-fence:again",
                    NOW.plusSeconds(7)));
            PlanRepository.RequestPlanPause stopFence = new PlanRepository.RequestPlanPause(
                    pausedAgain.planId(), pausedAgain.revision(), pausedAgain.activeRunId(),
                    "event_stop_fence", "stop:control-fence", NOW.plusSeconds(8));
            PlanRepository.PlanControlFenceResult firstStop = plans.requestPlanControlFence(stopFence,
                    PlanRepository.PlanControlAction.STOP);
            Plan stopped = plans.stopPlan(new PlanRepository.StopPlan(pausedAgain.planId(), pausedAgain.revision(),
                    pausedAgain.activeRunId(), "event_stop_control_fence", stopFence.operationKey(), NOW.plusSeconds(9)));
            int eventsAfterStop = fixture.planEventCount(stopped.planId());
            PlanRepository.PlanControlFenceResult stopReplay = plans.requestPlanControlFence(
                    new PlanRepository.RequestPlanPause(stopped.planId(), stopped.revision(), stopped.activeRunId(),
                            "event_stop_fence_replay", stopFence.operationKey(), NOW.plusSeconds(10)),
                    PlanRepository.PlanControlAction.STOP);

            assertTrue(firstStop.shouldControl());
            assertFalse(stopReplay.shouldControl());
            assertEquals(PlanStatus.STOPPED, stopReplay.plan().status());
            assertEquals(stopped, stopReplay.plan());
            assertEquals(eventsAfterStop, fixture.planEventCount(stopped.planId()));
        }
    }

    /** Turn admission 必须在同一 Store 事务写入 Plan binding，供暂停与进程恢复定位真实执行。 */
    @Test
    void persistsPlanBindingWithTurnAdmissionTransaction() throws Exception {
        try (Fixture fixture = fixture("plan-context-admission")) {
            MybatisPlanRepository plans = fixture.repository();
            Plan awaiting = createAwaitingPlan(plans, "plan_context_admission", "thr_one", "revision_context");
            PlanRevision revision = plans.readPlanSnapshot(awaiting.planId()).currentRevision();
            Plan executing = plans.executePlan(executeCommand(
                    awaiting, revision, "run_context_admission", "execute:context-admission", NOW.plusSeconds(1)));
            String turnId = "turn_context_admission";
            fixture.claimTurn(executing, turnId);
            Instant acceptedAt = NOW.plusSeconds(2);
            AdmissionFacts facts = new AdmissionFacts(new AgentIdentity("ja.local", "thr_one", turnId, "user_local"),
                    "ws_one", TurnOrigin.PLAN_EXECUTION, "plan/execute", acceptedAt, null, null, null,
                    revision.planRevisionId(), executing.activeRunId(), null, null, 0, Optional.empty());
            JaAdmissionTransactionParticipant participant = new PlanExecutionAdmissionParticipantResolver(
                    ignored -> Optional.empty(), plans, new ObjectMapper()).resolve(facts).orElseThrow();

            try (SqlSession session = fixture.sessions().openSession()) {
                java.sql.Connection connection = session.getConnection();
                AgentStoreTransactionContext transaction = new AgentStoreTransactionContext(
                        TransactionResource.of(connection), new AgentStore.ExecutionKey(
                                new AgentStore.ConversationKey("ja.local", "thr_one", "user_local"), turnId),
                        0, 0, -1, 1, 1, 0, 0, AgentStore.TransactionKind.ADMISSION, "RUNNING", 1,
                        JsonObject.empty(), List.of(), List.of(), Optional.empty(), null, null, acceptedAt);
                participant.beforeProjection(transaction);
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO ja_conversation_turn(turn_id,thread_id,state,requested_at_ms,updated_at_ms,
                        completed_at_ms,terminal_summary,parent_turn_id,root_turn_id)
                        VALUES(?,'thr_one','RUNNING',?,?,NULL,NULL,NULL,NULL)
                        """)) {
                    insert.setString(1, turnId);
                    insert.setLong(2, acceptedAt.toEpochMilli());
                    insert.setLong(3, acceptedAt.toEpochMilli());
                    assertEquals(1, insert.executeUpdate());
                }
                participant.afterProjection(transaction);
                session.commit();
            }

            PlanRepository.InternalTurnBinding binding = plans.findPlanTurnBinding(
                    executing.planId(), executing.activeRunId()).orElseThrow();
            assertEquals(turnId, binding.turnId());
            assertEquals(revision.planRevisionId(), binding.planRevisionId());
            assertEquals(revision.planHash(), binding.planHash());
        }
    }

    /** 无 Turn 的 claim 是 admission intent；Turn 已存在却无匹配 binding 必须阻止控制动作假成功。 */
    @Test
    void failsClosedForClaimedTurnWithoutInternalBinding() throws Exception {
        try (Fixture fixture = fixture("plan-context-missing")) {
            MybatisPlanRepository plans = fixture.repository();
            Plan awaiting = createAwaitingPlan(plans, "plan_context_missing", "thr_one", "revision_missing");
            PlanRevision revision = plans.readPlanSnapshot(awaiting.planId()).currentRevision();
            Plan executing = plans.executePlan(executeCommand(
                    awaiting, revision, "run_context_missing", "execute:context-missing", NOW.plusSeconds(1)));
            String turnId = "turn_context_missing";
            fixture.claimTurn(executing, turnId);

            assertTrue(plans.findPlanTurnBinding(executing.planId(), executing.activeRunId()).isEmpty());
            fixture.insertTurn(turnId);

            PlanRepositoryException failure = assertThrows(PlanRepositoryException.class,
                    () -> plans.findPlanTurnBinding(executing.planId(), executing.activeRunId()));
            assertEquals(PlanRepositoryException.Code.PLAN_RECOVERY_REQUIRED, failure.code());
        }
    }

    /** 重启保留 terminal Turn 的未结算 claim；显式 Stop 回放权威完成后再提交 usage 与 Run 终态。 */
    @Test
    void recoveredTerminalClaimSettlesUsageBeforeExplicitStop() throws Exception {
        try (Fixture fixture = fixture("recovered-terminal-claim-stop")) {
            MybatisPlanRepository plans = fixture.repository();
            Plan awaiting = createAwaitingPlan(plans, "plan_terminal_stop", "thr_one", "revision_terminal_stop");
            PlanRevision revision = plans.readPlanSnapshot(awaiting.planId()).currentRevision();
            Plan executing = plans.executePlan(executeCommand(
                    awaiting, revision, "run_terminal_stop", "execute:terminal-stop", NOW.plusSeconds(1)));
            String turnId = "turn_terminal_stop";
            fixture.claimTurn(executing, turnId);
            fixture.insertTurn(turnId);
            fixture.insertToolCall(turnId, "call_terminal_stop", 0);
            fixture.insertTurnUsage(turnId, "usage_terminal_stop", "request_terminal_stop");
            fixture.insertPlanTurnBinding(executing, revision, turnId);
            ToolAttempt attempt = plans.prepareToolAttempt(new PlanRepository.PrepareToolAttempt(new ToolAttempt(
                    "attempt_terminal_stop", executing.planId(), executing.activeRunId(),
                    executing.activePlanRevisionId(), "step_work", 1, turnId, "call_terminal_stop",
                    1, true, ToolAttemptState.PREPARED, "f".repeat(64), null, NOW.plusSeconds(2), null, null)));
            plans.startToolAttempt(attempt.toolAttemptId(), NOW.plusSeconds(3));
            fixture.updateTurnState(turnId, "CANCELLED");

            new PlanStartupRecovery(plans, Clock.fixed(NOW.plusSeconds(4), ZoneOffset.UTC), 2).recover();

            Plan paused = plans.readPlanSnapshot(executing.planId()).plan();
            assertEquals(PlanStatus.PAUSED, paused.status());
            assertEquals(executing.activeRunId(), paused.activeRunId());
            assertEquals("PAUSED", fixture.runStatus(paused.activeRunId()));
            assertEquals("CLAIMED", fixture.planTurnClaimState(paused.activeRunId(), turnId));
            assertEquals(ToolAttemptState.UNKNOWN, plans.findToolAttemptsByTurnIdCallId(
                    turnId, "call_terminal_stop").getFirst().state());
            assertEquals(turnId, plans.findPlanTurnBinding(paused.planId(), paused.activeRunId())
                    .orElseThrow().turnId());

            int eventsBeforeResume = fixture.planEventCount(paused.planId());
            PlanRepositoryException resumeFailure = assertThrows(PlanRepositoryException.class, () ->
                    plans.resumePlan(new PlanRepository.ResumePlan(paused.planId(), paused.revision(),
                            paused.activeRunId(), "event_resume_terminal_stop", "resume:terminal-stop",
                            NOW.plusSeconds(5))));
            assertEquals(PlanRepositoryException.Code.PLAN_INVALID_STATE, resumeFailure.code());
            assertEquals("CLAIMED", fixture.planTurnClaimState(paused.activeRunId(), turnId));
            assertEquals(eventsBeforeResume, fixture.planEventCount(paused.planId()));

            CompletableFuture<TurnResult> terminal = new CompletableFuture<>();
            CountDownLatch terminalReplayRequested = new CountDownLatch(1);
            AtomicInteger cancellationCalls = new AtomicInteger();
            AtomicReference<TurnState> turnState = new AtomicReference<>(TurnState.CANCELLED);
            PlanService service = planService(plans, controlledTurnPort(
                    terminal, terminalReplayRequested, cancellationCalls), turnState);
            PlanControl stopCommand = new PlanControl(paused.planId(), paused.revision(), paused.activeRunId(),
                    "stop:terminal-claim", NOW.plusSeconds(6));
            CompletableFuture<Plan> stopping = CompletableFuture.supplyAsync(() -> service.stopPlan(stopCommand));
            try {
                assertTrue(terminalReplayRequested.await(5, TimeUnit.SECONDS));
                assertFalse(stopping.isDone());
                assertEquals(PlanStatus.PAUSED, plans.readPlanSnapshot(paused.planId()).plan().status());
                assertEquals("CLAIMED", fixture.planTurnClaimState(paused.activeRunId(), turnId));

                terminal.complete(cancelledTurnResult(turnId));
                Plan stopped = stopping.get(5, TimeUnit.SECONDS);

                assertEquals(PlanStatus.STOPPED, stopped.status());
                assertEquals("STOPPED", fixture.runStatus(paused.activeRunId()));
                assertEquals("SETTLED", fixture.planTurnClaimState(paused.activeRunId(), turnId));
                assertEquals(new RunUsage(1, 1), fixture.runUsage(paused.activeRunId()));
                assertEquals(1, cancellationCalls.get());
                assertEquals(ToolAttemptState.UNKNOWN, plans.findToolAttemptsByTurnIdCallId(
                        turnId, "call_terminal_stop").getFirst().state());
            } finally {
                if (!terminal.isDone()) {
                    terminal.completeExceptionally(new IllegalStateException("terminal replay test aborted"));
                }
                stopping.handle((ignored, failure) -> null).get(5, TimeUnit.SECONDS);
            }
        }
    }

    /** Turn ledger 只允许已有 CLAIMED 身份与持久终态配对，Future 结果不能替代数据库事实。 */
    @Test
    void turnSettlementRequiresClaimAndPersistentTerminalTurn() throws Exception {
        for (String scenario : List.of("missing_claim", "missing_turn", "RUNNING", "SUSPENDED")) {
            try (Fixture fixture = fixture("settle-terminal-" + scenario.toLowerCase(java.util.Locale.ROOT))) {
                MybatisPlanRepository plans = fixture.repository();
                String suffix = scenario.toLowerCase(java.util.Locale.ROOT);
                Plan executing = executingPlan(plans, "plan_settle_" + suffix,
                        "run_settle_" + suffix, suffix);
                String turnId = "turn_settle_" + suffix;
                if (!scenario.equals("missing_claim")) {
                    fixture.claimTurn(executing, turnId);
                }
                if (!scenario.equals("missing_turn")) {
                    fixture.insertTurn(turnId);
                    fixture.updateTurnState(turnId,
                            scenario.equals("missing_claim") ? "CANCELLED" : scenario);
                }
                int events = fixture.planEventCount(executing.planId());
                PlanRepository.SettlePlanTurn command = new PlanRepository.SettlePlanTurn(
                        executing.planId(), executing.activeRunId(), turnId, 5,
                        "event_settle_" + suffix, "settle:" + suffix, NOW.plusSeconds(2));

                PlanRepositoryException failure = assertThrows(PlanRepositoryException.class,
                        () -> plans.settlePlanTurn(command));

                assertEquals(PlanRepositoryException.Code.PLAN_INVALID_STATE, failure.code());
                assertEquals(PlanStatus.EXECUTING, plans.readPlanSnapshot(executing.planId()).plan().status());
                assertEquals("RUNNING", fixture.runStatus(executing.activeRunId()));
                assertEquals(scenario.equals("missing_claim") ? null : "CLAIMED",
                        fixture.planTurnClaimState(executing.activeRunId(), turnId));
                assertEquals(events, fixture.planEventCount(executing.planId()));
            }
        }
    }

    /** 三种持久终态都可释放 claim，同一结算命令重放不得追加第二条账本事件。 */
    @Test
    void terminalTurnSettlementIsIdempotentForEveryTerminalState() throws Exception {
        for (String state : List.of("COMPLETED", "FAILED", "CANCELLED")) {
            try (Fixture fixture = fixture("settle-terminal-" + state.toLowerCase(java.util.Locale.ROOT))) {
                MybatisPlanRepository plans = fixture.repository();
                String suffix = state.toLowerCase(java.util.Locale.ROOT);
                Plan executing = executingPlan(plans, "plan_settle_" + suffix,
                        "run_settle_" + suffix, suffix);
                String turnId = "turn_settle_" + suffix;
                fixture.claimTurn(executing, turnId);
                fixture.insertTurn(turnId);
                fixture.updateTurnState(turnId, state);
                int events = fixture.planEventCount(executing.planId());
                PlanRepository.SettlePlanTurn command = new PlanRepository.SettlePlanTurn(
                        executing.planId(), executing.activeRunId(), turnId, 5,
                        "event_settle_" + suffix, "settle:" + suffix, NOW.plusSeconds(2));

                plans.settlePlanTurn(command);
                int settledEvents = fixture.planEventCount(executing.planId());
                plans.settlePlanTurn(command);

                assertEquals("SETTLED", fixture.planTurnClaimState(executing.activeRunId(), turnId));
                assertEquals(events + 1, settledEvents);
                assertEquals(settledEvents, fixture.planEventCount(executing.planId()));
            }
        }
    }

    /** 不完整失败收口保留终态未结算 claim，并阻止恢复、停止、编辑及草稿丢弃绕过账本。 */
    @Test
    void incompleteExecutionKeepsTerminalClaimAsRecoverableBlocker() throws Exception {
        try (Fixture fixture = fixture("incomplete-terminal-claim")) {
            MybatisPlanRepository plans = fixture.repository();
            Plan executing = executingPlan(plans, "plan_incomplete_claim", "run_incomplete_claim", "incomplete-claim");
            String turnId = "turn_incomplete_claim";
            fixture.claimTurn(executing, turnId);
            fixture.insertTurn(turnId);
            fixture.updateTurnState(turnId, "CANCELLED");
            int eventsBeforeSettlement = fixture.planEventCount(executing.planId());

            Plan paused = plans.settlePlanExecution(new PlanRepository.SettlePlanExecution(
                    executing.planId(), executing.revision(), executing.activeRunId(),
                    "event_incomplete_claim", "settle:incomplete-claim", NOW.plusSeconds(2)));

            assertEquals(PlanStatus.PAUSED, paused.status());
            assertEquals(executing.activeRunId(), paused.activeRunId());
            assertEquals(executing.activePlanRevisionId(), paused.activePlanRevisionId());
            assertEquals("PAUSED", fixture.runStatus(paused.activeRunId()));
            assertEquals("CLAIMED", fixture.planTurnClaimState(paused.activeRunId(), turnId));
            assertEquals(eventsBeforeSettlement + 1, fixture.planEventCount(paused.planId()));

            String draftJson = new CanonicalPlanJson(new ObjectMapper()).encode(
                    definition("未结算 claim 期间不能替换草稿")).json();
            fixture.insertPlanDraft(paused.planId(), draftJson, paused.activePlanRevisionId());
            int eventsBeforeRejectedMutations = fixture.planEventCount(paused.planId());

            PlanRepositoryException resumeFailure = assertThrows(PlanRepositoryException.class, () ->
                    plans.resumePlan(new PlanRepository.ResumePlan(paused.planId(), paused.revision(),
                            paused.activeRunId(), "event_resume_incomplete_claim", "resume:incomplete-claim",
                            NOW.plusSeconds(3))));
            PlanRepositoryException stopFailure = assertThrows(PlanRepositoryException.class, () ->
                    plans.stopPlan(new PlanRepository.StopPlan(paused.planId(), paused.revision(),
                            paused.activeRunId(), "event_stop_incomplete_claim", "stop:incomplete-claim",
                            NOW.plusSeconds(4))));
            PlanRepositoryException saveFailure = assertThrows(PlanRepositoryException.class, () ->
                    plans.saveDraft(new PlanRepository.SaveDraft(paused.planId(), paused.revision(), 1,
                            draftJson, paused.activePlanRevisionId(), "event_save_incomplete_claim",
                            "draft:incomplete-claim", NOW.plusSeconds(5))));
            PlanRepositoryException discardFailure = assertThrows(PlanRepositoryException.class, () ->
                    plans.discardDraft(new PlanRepository.DiscardDraft(paused.planId(), paused.revision(),
                            "event_discard_incomplete_claim", "discard:incomplete-claim", NOW.plusSeconds(6))));

            assertEquals(PlanRepositoryException.Code.PLAN_INVALID_STATE, resumeFailure.code());
            assertEquals(PlanRepositoryException.Code.PLAN_INVALID_STATE, stopFailure.code());
            assertEquals(PlanRepositoryException.Code.PLAN_INVALID_STATE, saveFailure.code());
            assertEquals(PlanRepositoryException.Code.PLAN_INVALID_STATE, discardFailure.code());
            assertEquals(paused, plans.readPlanSnapshot(paused.planId()).plan());
            assertEquals("PAUSED", fixture.runStatus(paused.activeRunId()));
            assertEquals("CLAIMED", fixture.planTurnClaimState(paused.activeRunId(), turnId));
            assertTrue(plans.findDraft(paused.planId()).isPresent());
            assertEquals(eventsBeforeRejectedMutations, fixture.planEventCount(paused.planId()));
        }
    }

    /** 已提交 pause fence 时普通失败回调不得抢先改 Run、Plan 或放弃尚未 admission 的 claim。 */
    @Test
    void incompleteSettlementLeavesPauseFenceOwnerInControl() throws Exception {
        try (Fixture fixture = fixture("incomplete-pause-fence")) {
            MybatisPlanRepository plans = fixture.repository();
            Plan executing = executingPlan(plans, "plan_pause_fence", "run_pause_fence", "pause-fence");
            String turnId = "turn_pause_fence";
            fixture.claimTurn(executing, turnId);
            plans.requestPlanPause(new PlanRepository.RequestPlanPause(executing.planId(), executing.revision(),
                    executing.activeRunId(), "event_pause_fence", "pause-fence:request", NOW.plusSeconds(2)));
            int eventsAfterFence = fixture.planEventCount(executing.planId());

            Plan unchanged = plans.settlePlanExecution(new PlanRepository.SettlePlanExecution(
                    executing.planId(), executing.revision(), executing.activeRunId(),
                    "event_incomplete_after_fence", "settle:after-fence", NOW.plusSeconds(3)));

            assertEquals(executing, unchanged);
            assertEquals(PlanStatus.EXECUTING, plans.readPlanSnapshot(executing.planId()).plan().status());
            assertEquals("RUNNING", fixture.runStatus(executing.activeRunId()));
            assertTrue(fixture.runPauseRequested(executing.activeRunId()));
            assertEquals("CLAIMED", fixture.planTurnClaimState(executing.activeRunId(), turnId));
            assertEquals(eventsAfterFence, fixture.planEventCount(executing.planId()));
        }
    }

    /** 完整收口后，草稿丢弃恢复准确的批准版本；丢响应重放返回同一提交结果。 */
    @Test
    void settledRunAllowsDraftDiscardAndIdempotentReplay() throws Exception {
        try (Fixture fixture = fixture("settled-draft-discard")) {
            MybatisPlanRepository plans = fixture.repository();
            Plan executing = executingPlan(plans, "plan_discard_settled", "run_discard_settled", "discard-settled");
            PlanRevision approvedRevision = plans.readPlanSnapshot(executing.planId()).currentRevision();
            Plan paused = plans.settlePlanExecution(new PlanRepository.SettlePlanExecution(
                    executing.planId(), executing.revision(), executing.activeRunId(),
                    "event_settle_before_discard", "settle:before-discard", NOW.plusSeconds(2)));
            String draftJson = new CanonicalPlanJson(new ObjectMapper()).encode(
                    definition("替代草稿内容")).json();
            Plan drafted = plans.saveDraft(new PlanRepository.SaveDraft(paused.planId(), paused.revision(), 0,
                    draftJson, approvedRevision.planRevisionId(), "event_save_discard_draft",
                    "draft:discard-settled", NOW.plusSeconds(3)));
            int eventsBeforeDiscard = fixture.planEventCount(drafted.planId());
            PlanRepository.DiscardDraft discard = new PlanRepository.DiscardDraft(drafted.planId(), drafted.revision(),
                    "event_discard_settled_draft", "discard:settled-draft", NOW.plusSeconds(4));

            Plan discarded = plans.discardDraft(discard);
            Plan replayed = plans.discardDraft(discard);

            assertEquals(PlanStatus.APPROVED, discarded.status());
            assertEquals(approvedRevision.planRevisionId(), discarded.activePlanRevisionId());
            assertNull(discarded.activeRunId());
            assertTrue(plans.findDraft(discarded.planId()).isEmpty());
            assertEquals("STOPPED", fixture.runStatus(executing.activeRunId()));
            assertEquals(discarded, replayed);
            assertEquals(eventsBeforeDiscard + 1, fixture.planEventCount(discarded.planId()));
        }
    }

    /** Stop 必须在同一 SQLite 事务内拒绝已有 Turn 但账本仍 CLAIMED 的 Run。 */
    @Test
    void stopRejectsTerminalTurnWithUnsettledClaim() throws Exception {
        try (Fixture fixture = fixture("stop-terminal-unsettled-claim")) {
            MybatisPlanRepository plans = fixture.repository();
            Plan awaiting = createAwaitingPlan(plans, "plan_stop_unsettled", "thr_one", "revision_stop_unsettled");
            PlanRevision revision = plans.readPlanSnapshot(awaiting.planId()).currentRevision();
            Plan executing = plans.executePlan(executeCommand(
                    awaiting, revision, "run_stop_unsettled", "execute:stop-unsettled", NOW.plusSeconds(1)));
            fixture.claimTurn(executing, "turn_stop_unsettled");
            fixture.insertTurn("turn_stop_unsettled");
            fixture.updateTurnState("turn_stop_unsettled", "CANCELLED");
            int events = fixture.planEventCount(executing.planId());

            PlanRepositoryException failure = assertThrows(PlanRepositoryException.class, () ->
                    plans.stopPlan(new PlanRepository.StopPlan(executing.planId(), executing.revision(),
                            executing.activeRunId(), "event_stop_unsettled", "stop:unsettled",
                            NOW.plusSeconds(2))));

            assertEquals(PlanRepositoryException.Code.PLAN_INVALID_STATE, failure.code());
            assertEquals(executing, plans.readPlanSnapshot(executing.planId()).plan());
            assertEquals("RUNNING", fixture.runStatus(executing.activeRunId()));
            assertEquals("CLAIMED", fixture.planTurnClaimState(executing.activeRunId(), "turn_stop_unsettled"));
            assertEquals(events, fixture.planEventCount(executing.planId()));
        }
    }

    /** 持久 PLAN_EXECUTION binding 让重启后的 SUSPENDED Turn 可定位，Stop 等终态和 claim settlement 后完成。 */
    @Test
    void stopWaitsForRestartedSuspendedTurnTerminalAndLedgerSettlement() throws Exception {
        try (Fixture fixture = fixture("stop-restarted-suspended-turn")) {
            MybatisPlanRepository plans = fixture.repository();
            Plan executing = executingPlan(plans, "plan_stop_restart", "run_stop_restart", "turn_stop_restart");
            fixture.claimTurn(executing, "turn_stop_restart");
            fixture.insertTurn("turn_stop_restart");
            fixture.insertPlanTurnBinding(executing,
                    plans.readPlanSnapshot(executing.planId()).currentRevision(), "turn_stop_restart");
            fixture.updateTurnState("turn_stop_restart", "SUSPENDED");
            CompletableFuture<TurnResult> cancellation = new CompletableFuture<>();
            AtomicReference<TurnState> turnState = new AtomicReference<>(TurnState.SUSPENDED);
            CountDownLatch cancellationRequested = new CountDownLatch(1);
            AtomicInteger cancellationCalls = new AtomicInteger();
            PlanTurnPort turns = controlledTurnPort(cancellation,
                    cancellationRequested, cancellationCalls);
            PlanService service = planService(plans, turns, turnState);
            PlanControl command = new PlanControl(executing.planId(), executing.revision(),
                    executing.activeRunId(), "stop:restart-waits-for-settlement", NOW.plusSeconds(3));

            CompletableFuture<Plan> stopped = CompletableFuture.supplyAsync(() -> service.stopPlan(command));

            assertTrue(cancellationRequested.await(5, TimeUnit.SECONDS));
            assertFalse(stopped.isDone());
            assertEquals(PlanStatus.EXECUTING, plans.readPlanSnapshot(executing.planId()).plan().status());
            assertEquals("CLAIMED", fixture.planTurnClaimState(executing.activeRunId(), "turn_stop_restart"));

            fixture.updateTurnState("turn_stop_restart", "CANCELLED");
            turnState.set(TurnState.CANCELLED);
            cancellation.complete(cancelledTurnResult("turn_stop_restart"));
            Plan result = stopped.get(5, TimeUnit.SECONDS);

            assertEquals(PlanStatus.STOPPED, result.status());
            assertEquals("STOPPED", fixture.runStatus(executing.activeRunId()));
            assertEquals("SETTLED", fixture.planTurnClaimState(executing.activeRunId(), "turn_stop_restart"));
            assertEquals(result, service.stopPlan(command));
            assertEquals(1, cancellationCalls.get());
        }
    }

    /**
     * 已接受 Pause fence 后，在途 Tool 仍可提交步骤和证据；最终暂停必须 CAS 最新聚合 revision。
     *
     * @author kongweiguang
     */
    @Test
    void pauseUsesRevisionCommittedDuringAcceptedTurnSettlement() throws Exception {
        assertControlUsesRevisionCommittedDuringSettlement(false);
    }

    /**
     * 已接受 Stop fence 后，在途 Tool 的合法结算仍须先落库，再按最新 revision 停止同一 Run。
     *
     * @author kongweiguang
     */
    @Test
    void stopUsesRevisionCommittedDuringAcceptedTurnSettlement() throws Exception {
        assertControlUsesRevisionCommittedDuringSettlement(true);
    }

    /**
     * 旧 revision 或错误 Run 身份不能进入真实 Turn 控制，也不能靠等待后的刷新绕过首个 CAS。
     *
     * @author kongweiguang
     */
    @Test
    void staleRevisionAndWrongRunDoNotControlTheTurn() throws Exception {
        try (Fixture fixture = fixture("control-stale-identity")) {
            MybatisPlanRepository storage = fixture.repository();
            Plan executing = executingPlan(storage, "plan_control_stale", "run_control_stale", "stale");
            fixture.insertClaimedToolCall(executing, "turn_control_stale", "call_control_stale");
            fixture.insertPlanTurnBinding(executing, storage.readPlanSnapshot(executing.planId()).currentRevision(),
                    "turn_control_stale");
            CompletableFuture<Void> suspension = new CompletableFuture<>();
            CompletableFuture<TurnResult> cancellation = new CompletableFuture<>();
            CountDownLatch controlRequested = new CountDownLatch(1);
            AtomicInteger controlCalls = new AtomicInteger();
            PlanService service = planService(storage, controlledPlanControlPort(suspension, cancellation,
                    controlRequested, controlCalls), new AtomicReference<>(TurnState.RUNNING));

            PlanRepositoryException stale = assertThrows(PlanRepositoryException.class, () ->
                    service.pausePlan(new PlanControl(executing.planId(), executing.revision() - 1,
                            executing.activeRunId(), "pause:stale-identity", NOW.plusSeconds(2))));
            PlanRepositoryException wrongRun = assertThrows(PlanRepositoryException.class, () ->
                    service.stopPlan(new PlanControl(executing.planId(), executing.revision(), "run_other",
                            "stop:wrong-run", NOW.plusSeconds(3))));

            assertEquals(PlanRepositoryException.Code.PLAN_REVISION_CONFLICT, stale.code());
            assertEquals(PlanRepositoryException.Code.PLAN_INVALID_STATE, wrongRun.code());
            assertEquals(0, controlCalls.get());
            assertEquals(1, controlRequested.getCount());
            assertFalse(fixture.runPauseRequested(executing.activeRunId()));
            assertEquals(PlanStatus.EXECUTING, storage.readPlanSnapshot(executing.planId()).plan().status());
        }
    }

    /**
     * Turn 暂停结算失败时仅保留已提交的安全 fence，不得发布 PAUSED 或吞掉原始失败。
     *
     * @author kongweiguang
     */
    @Test
    void pauseSettlementFailureDoesNotCommitPausedState() throws Exception {
        try (Fixture fixture = fixture("pause-turn-settlement-failure")) {
            MybatisPlanRepository storage = fixture.repository();
            Plan executing = executingPlan(storage, "plan_pause_failure", "run_pause_failure", "pause-failure");
            fixture.insertClaimedToolCall(executing, "turn_pause_failure", "call_pause_failure");
            fixture.insertPlanTurnBinding(executing, storage.readPlanSnapshot(executing.planId()).currentRevision(),
                    "turn_pause_failure");
            CompletableFuture<Void> suspension = new CompletableFuture<>();
            CompletableFuture<TurnResult> cancellation = new CompletableFuture<>();
            CountDownLatch controlRequested = new CountDownLatch(1);
            AtomicInteger controlCalls = new AtomicInteger();
            PlanService service = planService(storage, controlledPlanControlPort(suspension, cancellation,
                    controlRequested, controlCalls), new AtomicReference<>(TurnState.RUNNING));
            PlanControl command = new PlanControl(executing.planId(), executing.revision(),
                    executing.activeRunId(), "pause:turn-settlement-failure", NOW.plusSeconds(2));
            CompletableFuture<Plan> pause = CompletableFuture.supplyAsync(() -> service.pausePlan(command));

            try {
                assertTrue(controlRequested.await(5, TimeUnit.SECONDS));
                int eventsAtFence = fixture.planEventCount(executing.planId());
                suspension.completeExceptionally(new IllegalStateException("controlled suspension failure"));

                CompletionException failure = assertThrows(CompletionException.class, pause::join);
                assertTrue(failure.getCause() instanceof IllegalStateException);
                assertEquals(PlanStatus.EXECUTING, storage.readPlanSnapshot(executing.planId()).plan().status());
                assertEquals("RUNNING", fixture.runStatus(executing.activeRunId()));
                assertEquals(eventsAtFence, fixture.planEventCount(executing.planId()));
                assertEquals(1, controlCalls.get());
            } finally {
                suspension.completeExceptionally(new IllegalStateException("test cleanup"));
                pause.handle((ignored, failure) -> null).get(5, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * 安全 Stop 完成后若另一合法控制已终止该 Plan，刷新只提供 CAS 版本，不能把他方终态冒认为本操作成功。
     *
     * @author kongweiguang
     */
    @Test
    void stopDoesNotTreatAnotherCommittedStopAsItsOwnResult() throws Exception {
        try (Fixture fixture = fixture("stop-terminal-control-race")) {
            MybatisPlanRepository storage = fixture.repository();
            Plan executing = executingPlan(storage, "plan_stop_terminal_race", "run_stop_terminal_race", "terminal-race");
            fixture.insertClaimedToolCall(executing, "turn_stop_terminal_race", "call_stop_terminal_race");
            fixture.insertPlanTurnBinding(executing,
                    storage.readPlanSnapshot(executing.planId()).currentRevision(), "turn_stop_terminal_race");
            fixture.updateTurnState("turn_stop_terminal_race", "SUSPENDED");
            AtomicBoolean injectTerminalStop = new AtomicBoolean(false);
            AtomicBoolean competingStopCommitted = new AtomicBoolean(false);
            PlanRepository observed = stopPlanBeforeNextSettledSnapshot(storage, fixture,
                    executing.planId(), executing.activeRunId(), "turn_stop_terminal_race",
                    injectTerminalStop, competingStopCommitted);
            CompletableFuture<Void> suspension = new CompletableFuture<>();
            CompletableFuture<TurnResult> cancellation = new CompletableFuture<>();
            CountDownLatch controlRequested = new CountDownLatch(1);
            AtomicInteger controlCalls = new AtomicInteger();
            PlanService service = planService(observed, controlledPlanControlPort(suspension, cancellation,
                    controlRequested, controlCalls), new AtomicReference<>(TurnState.SUSPENDED));
            PlanControl command = new PlanControl(executing.planId(), executing.revision(),
                    executing.activeRunId(), "stop:first-control", NOW.plusSeconds(3));
            CompletableFuture<Plan> stop = CompletableFuture.supplyAsync(() -> service.stopPlan(command));

            try {
                assertTrue(controlRequested.await(5, TimeUnit.SECONDS));
                int stopsAtFence = fixture.planActivityEventCount(executing.planId(), "plan_stopped");
                fixture.updateTurnState("turn_stop_terminal_race", "CANCELLED");
                injectTerminalStop.set(true);
                cancellation.complete(cancelledTurnResult("turn_stop_terminal_race"));

                CompletionException failure = assertThrows(CompletionException.class, stop::join);
                assertTrue(failure.getCause() instanceof PlanRepositoryException);
                assertEquals(PlanRepositoryException.Code.PLAN_INVALID_STATE,
                        ((PlanRepositoryException) failure.getCause()).code());
                assertTrue(competingStopCommitted.get());
                assertEquals(PlanStatus.STOPPED, storage.readPlanSnapshot(executing.planId()).plan().status());
                assertEquals("STOPPED", fixture.runStatus(executing.activeRunId()));
                assertEquals(stopsAtFence + 1, fixture.planActivityEventCount(executing.planId(), "plan_stopped"));
                assertEquals(1, controlCalls.get());
            } finally {
                cancellation.completeExceptionally(new IllegalStateException("test cleanup"));
                stop.handle((ignored, failure) -> null).get(5, TimeUnit.SECONDS);
            }
        }
    }

    /** Thread 读取尚未创建 durable Turn 时 Stop 先提交 fence，迟到 admission 必须由 SQLite 拒绝。 */
    @Test
    void stopFenceAbandonsClaimBeforeLateTurnAdmission() throws Exception {
        try (Fixture fixture = fixture("stop-admission-barrier")) {
            MybatisPlanRepository storage = fixture.repository();
            Plan executing = executingPlan(storage, "plan_admission", "run_admission", "admission");
            String turnId = "turn_admission";
            PlanRepository.PlanTurnClaim claim = fixture.claimTurn(executing, turnId);
            CountDownLatch threadReadEntered = new CountDownLatch(1);
            CountDownLatch allowThreadRead = new CountDownLatch(1);
            CountDownLatch pauseFenceCommitted = new CountDownLatch(1);
            CountDownLatch lateTurnInsertRejected = new CountDownLatch(1);
            AtomicInteger startCalls = new AtomicInteger();
            AtomicInteger cancellationCalls = new AtomicInteger();
            CompletableFuture<TurnResult> terminal = new CompletableFuture<>();
            PlanRepository plans = observePauseFence(storage, pauseFenceCommitted);
            PlanConversationPort conversations = new PlanConversationPort() {
                /** 挂住真实 Thread 读取点，确定 Stop 发生在 barrier 预留之后、Turn admission 之前。 */
                @Override
                public ThreadBinding readThread(String threadId) {
                    threadReadEntered.countDown();
                    try {
                        if (!allowThreadRead.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test did not release Thread admission");
                        }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Thread admission wait was interrupted", failure);
                    }
                    return new ThreadBinding(threadId, "ws_one", 0, "provider", "model", "LOW",
                            "APPROVAL_REQUIRED", "PLAN");
                }

                /** 当前用例不持久化 binding；若 Stop 读取 Turn，说明 no-binding 分支失效。 */
                @Override
                public TurnBinding readTurn(String threadId, String currentTurnId) {
                    throw new UnsupportedOperationException("no durable Plan Turn binding exists");
                }
            };
            PlanTurnPort turns = new PlanTurnPort() {
                /** 本竞态只启动首次 Turn，不注册恢复 continuation。 */
                @Override
                public void registerResumeContinuation(String currentTurnId,
                        java.util.function.Consumer<CompletionStage<TurnResult>> continuation) {
                    throw new UnsupportedOperationException("admission test does not resume Turns");
                }

                /** 本竞态没有需要清理的待恢复 continuation。 */
                @Override
                public void clearResumeContinuation(String currentTurnId) { }

                /** Stop fence 后尝试真实插入；V1 必须拒绝迟到 Turn，fake 不得伪造 Accepted。 */
                @Override
                public Accepted startContinuation(PlanTurnPort.StartRequest request, String instruction,
                        ConversationTurnEventSink sink,
                        io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot nativeContext) {
                    startCalls.incrementAndGet();
                    try {
                        fixture.insertTurn(request.turnId());
                    } catch (java.sql.SQLException failure) {
                        lateTurnInsertRejected.countDown();
                        throw new IllegalStateException("V1 rejected late Plan Turn admission", failure);
                    } catch (Exception failure) {
                        throw new IllegalStateException("unexpected Plan Turn admission failure", failure);
                    }
                    throw new IllegalStateException("V1 admission fence accepted a late Plan Turn");
                }

                /** 本竞态使用 Stop，不暂停现有 Turn。 */
                @Override
                public CompletionStage<Void> suspendPlanRun(String currentTurnId) {
                    return CompletableFuture.completedFuture(null);
                }

                /** 本竞态不恢复已有 Turn。 */
                @Override
                public Accepted resume(String currentTurnId, long expectedThreadRevision,
                        ConversationTurnEventSink sink,
                        io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot nativeContext) {
                    throw new UnsupportedOperationException("admission test does not resume Turns");
                }

                /** Stop 只能取消已经 admission 的 Turn，返回值仍等待权威 terminal future。 */
                @Override
                public CompletionStage<TurnResult> cancel(String currentTurnId) {
                    cancellationCalls.incrementAndGet();
                    return terminal;
                }

                /** 测试无宿主原生执行上下文。 */
                @Override
                public io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot runContext(
                        String planId, String runId) {
                    return null;
                }
            };
            PlanEventRegistry events = new PlanEventRegistry();
            PlanExecutionTurnAdapter adapter = new PlanExecutionTurnAdapter(turns, conversations,
                    workspaceId -> new PlanWorkspacePort.Binding(workspaceId, temp), plans,
                    new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                    (threadId, downstream, registered) -> ConversationTurnEventSink.noop());
            PlanExecutionCoordinator coordinator = new PlanExecutionCoordinator(plans, adapter,
                    Clock.fixed(NOW, ZoneOffset.UTC), stopMustNotEvaluate());
            PlanService service = new PlanService(plans, new CanonicalPlanJson(new ObjectMapper()),
                    Clock.fixed(NOW, ZoneOffset.UTC), 1, coordinator, events,
                    new PlanCommittedPublisher(plans, events));
            PlanExecutionCoordinator.ExecutionRequest request = new PlanExecutionCoordinator.ExecutionRequest(
                    executing.planId(), "thr_one", executing.activePlanRevisionId(), executing.activeRunId(), turnId);
            CompletableFuture<CompletionStage<Void>> start = null;
            CompletableFuture<Plan> stop = null;

            try {
                start = CompletableFuture.supplyAsync(() ->
                        adapter.start(request, claim, PlanExecutionEventSink.noop()));
                assertTrue(threadReadEntered.await(5, TimeUnit.SECONDS));

                PlanControl control = new PlanControl(executing.planId(), executing.revision(),
                        executing.activeRunId(), "stop:admission-race", NOW.plusSeconds(2));
                stop = CompletableFuture.supplyAsync(() -> service.stopPlan(control));
                assertTrue(pauseFenceCommitted.await(5, TimeUnit.SECONDS));
                Plan stopped = stop.get(5, TimeUnit.SECONDS);
                assertEquals(PlanStatus.STOPPED, stopped.status());
                assertEquals("STOPPED", fixture.runStatus(executing.activeRunId()));
                assertTrue(fixture.runPauseRequested(executing.activeRunId()));
                assertEquals(0, startCalls.get());
                assertEquals(0, cancellationCalls.get());
                assertEquals("ABANDONED", fixture.planTurnClaimState(executing.activeRunId(), turnId));
                assertFalse(fixture.turnExists(turnId));
                assertEquals(new RunUsage(0, 0), fixture.runUsage(executing.activeRunId()));
                assertFalse(start.isDone());

                allowThreadRead.countDown();
                assertTrue(lateTurnInsertRejected.await(5, TimeUnit.SECONDS));
                assertEquals(1, startCalls.get());
                CompletionStage<Void> rejectedAdmission = start.get(5, TimeUnit.SECONDS);
                assertThrows(CompletionException.class, () -> rejectedAdmission.toCompletableFuture().join());
                assertEquals(0, cancellationCalls.get());
                assertEquals("STOPPED", fixture.runStatus(executing.activeRunId()));
                assertEquals("ABANDONED", fixture.planTurnClaimState(executing.activeRunId(), turnId));
                assertFalse(fixture.turnExists(turnId));
                assertEquals(new RunUsage(0, 0), fixture.runUsage(executing.activeRunId()));
            } finally {
                allowThreadRead.countDown();
                if (!terminal.isDone()) {
                    terminal.completeExceptionally(new IllegalStateException("admission race test aborted"));
                }
                if (start != null) {
                    start.handle((ignored, failure) -> null).get(5, TimeUnit.SECONDS);
                }
                if (stop != null) {
                    stop.handle((ignored, failure) -> null).get(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    /** 持久 Turn binding 使 Stop 触达 ledger；settlement 失败后本次与同 operationKey 重试均不得确认。 */
    @Test
    void repeatedStopDoesNotConfirmWhenTurnLedgerSettlementFails() throws Exception {
        try (Fixture fixture = fixture("stop-ledger-settlement-failure")) {
            MybatisPlanRepository storage = fixture.repository();
            Plan executing = executingPlan(storage, "plan_stop_failure", "run_stop_failure", "turn_stop_failure");
            fixture.claimTurn(executing, "turn_stop_failure");
            fixture.insertTurn("turn_stop_failure");
            fixture.insertPlanTurnBinding(executing,
                    storage.readPlanSnapshot(executing.planId()).currentRevision(), "turn_stop_failure");
            fixture.updateTurnState("turn_stop_failure", "SUSPENDED");
            CompletableFuture<TurnResult> cancellation = new CompletableFuture<>();
            AtomicReference<TurnState> turnState = new AtomicReference<>(TurnState.SUSPENDED);
            CountDownLatch cancellationRequested = new CountDownLatch(1);
            AtomicInteger cancellationCalls = new AtomicInteger();
            PlanRepository plans = failTurnSettlement(storage);
            PlanService service = planService(plans, controlledTurnPort(cancellation,
                    cancellationRequested, cancellationCalls), turnState);
            PlanControl command = new PlanControl(executing.planId(), executing.revision(),
                    executing.activeRunId(), "stop:ledger-failure", NOW.plusSeconds(3));

            CompletableFuture<Plan> stopped = CompletableFuture.supplyAsync(() -> service.stopPlan(command));
            assertTrue(cancellationRequested.await(5, TimeUnit.SECONDS));
            fixture.updateTurnState("turn_stop_failure", "CANCELLED");
            turnState.set(TurnState.CANCELLED);
            cancellation.complete(cancelledTurnResult("turn_stop_failure"));

            assertThrows(CompletionException.class, () -> stopped.join());
            assertThrows(RuntimeException.class, () -> service.stopPlan(command));
            assertEquals(PlanStatus.EXECUTING, storage.readPlanSnapshot(executing.planId()).plan().status());
            assertEquals("RUNNING", fixture.runStatus(executing.activeRunId()));
            assertEquals("CLAIMED", fixture.planTurnClaimState(executing.activeRunId(), "turn_stop_failure"));
            assertTrue(cancellationCalls.get() >= 1);
        }
    }

    /** 创建、批准并执行 Plan，为 Stop 组合测试提供真实持久 Run 与活动执行身份。 */
    private static Plan executingPlan(MybatisPlanRepository plans, String planId, String runId, String suffix) {
        Plan awaiting = createAwaitingPlan(plans, planId, "thr_one", "revision_" + suffix);
        PlanRevision revision = plans.readPlanSnapshot(planId).currentRevision();
        return plans.executePlan(executeCommand(awaiting, revision, runId, "execute:" + suffix,
                NOW.plusSeconds(1)));
    }

    /**
     * 将受控 Turn 控制点夹在已持久 fence 与合法 Tool evidence 提交之间，分别验证 Pause/Stop 使用新 CAS 版本。
     *
     * @author kongweiguang
     */
    private void assertControlUsesRevisionCommittedDuringSettlement(boolean stopping) throws Exception {
        String suffix = stopping ? "stop-settlement-revision" : "pause-settlement-revision";
        try (Fixture fixture = fixture(suffix)) {
            MybatisPlanRepository storage = fixture.repository();
            String turnId = "turn_" + suffix;
            String callId = "call_" + suffix;
            Plan executing = executingPlan(storage, "plan_" + suffix, "run_" + suffix, suffix);
            Plan runningStep = prepareInFlightPlanStep(fixture, executing, turnId, callId, suffix);
            CompletableFuture<Void> suspension = new CompletableFuture<>();
            CompletableFuture<TurnResult> cancellation = new CompletableFuture<>();
            CountDownLatch controlRequested = new CountDownLatch(1);
            AtomicInteger controlCalls = new AtomicInteger();
            AtomicReference<TurnState> turnState = new AtomicReference<>(TurnState.RUNNING);
            PlanService service = planService(storage, controlledPlanControlPort(suspension, cancellation,
                    controlRequested, controlCalls), turnState);
            PlanControl command = new PlanControl(executing.planId(), runningStep.revision(),
                    executing.activeRunId(), (stopping ? "stop:" : "pause:") + suffix, NOW.plusSeconds(3));
            CompletableFuture<Plan> controlled = CompletableFuture.supplyAsync(() -> stopping
                    ? service.stopPlan(command) : service.pausePlan(command));

            try {
                assertTrue(controlRequested.await(5, TimeUnit.SECONDS));
                assertFalse(controlled.isDone());
                assertTrue(fixture.runPauseRequested(executing.activeRunId()));

                Plan evidenced = settleInFlightPlanStep(fixture, runningStep, callId, suffix);
                assertTrue(evidenced.revision() > command.expectedPlanRevision());
                if (stopping) {
                    fixture.updateTurnState(turnId, "CANCELLED");
                    turnState.set(TurnState.CANCELLED);
                    cancellation.complete(cancelledTurnResult(turnId));
                } else {
                    fixture.updateTurnState(turnId, "SUSPENDED");
                    turnState.set(TurnState.SUSPENDED);
                    suspension.complete(null);
                }

                Plan result = controlled.get(5, TimeUnit.SECONDS);
                assertEquals(stopping ? PlanStatus.STOPPED : PlanStatus.PAUSED, result.status());
                assertEquals(evidenced.revision() + 1, result.revision());
                assertEquals(stopping ? "STOPPED" : "PAUSED", fixture.runStatus(executing.activeRunId()));
                assertEquals(stopping ? "SETTLED" : "CLAIMED",
                        fixture.planTurnClaimState(executing.activeRunId(), turnId));

                int eventsAfterCommit = fixture.planEventCount(executing.planId());
                Plan replay = stopping ? service.stopPlan(command) : service.pausePlan(command);
                assertEquals(result, replay);
                assertEquals(eventsAfterCommit, fixture.planEventCount(executing.planId()));
                assertEquals(1, controlCalls.get());
            } finally {
                suspension.completeExceptionally(new IllegalStateException("test cleanup"));
                cancellation.completeExceptionally(new IllegalStateException("test cleanup"));
                controlled.handle((ignored, failure) -> null).get(5, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * 用真实仓储建立已启动 Tool 和 RUNNING 步骤；后续 completion 可在控制屏障期间合法写入证据。
     *
     * @author kongweiguang
     */
    private static Plan prepareInFlightPlanStep(Fixture fixture, Plan executing, String turnId,
                                                String callId, String suffix) throws Exception {
        MybatisPlanRepository repository = fixture.repository();
        fixture.insertClaimedToolCall(executing, turnId, callId);
        fixture.insertPlanTurnBinding(executing,
                repository.readPlanSnapshot(executing.planId()).currentRevision(), turnId);
        ToolAttempt attempt = new ToolAttempt("attempt_" + suffix, executing.planId(), executing.activeRunId(),
                executing.activePlanRevisionId(), "step_work", 1, turnId, callId, 1, false,
                ToolAttemptState.PREPARED, "a".repeat(64), null, NOW, null, null);
        repository.prepareToolAttempt(new PlanRepository.PrepareToolAttempt(attempt));
        repository.startToolAttempt(attempt.toolAttemptId(), NOW.plusSeconds(2));
        return repository.updatePlanStep(new PlanRepository.UpdatePlanStep(executing.planId(), executing.revision(),
                executing.activeRunId(), "step_work", StepStatus.READY, StepStatus.RUNNING, null, List.of(),
                "event_step_running_" + suffix, "step:running:" + suffix, NOW.plusSeconds(2)));
    }

    /**
     * 在已接受 fence 后提交 Tool 终态及步骤证据，令最终 pause/stop 必须使用新的聚合 revision。
     *
     * @author kongweiguang
     */
    private static Plan settleInFlightPlanStep(Fixture fixture, Plan runningStep, String callId,
                                               String suffix) {
        MybatisPlanRepository repository = fixture.repository();
        repository.settleToolAttempt(new PlanRepository.SettleToolAttempt("attempt_" + suffix,
                ToolAttemptState.SUCCEEDED, "b".repeat(64), null, NOW.plusSeconds(4)));
        return repository.updatePlanStep(new PlanRepository.UpdatePlanStep(runningStep.planId(),
                runningStep.revision(), runningStep.activeRunId(), "step_work", StepStatus.RUNNING,
                StepStatus.SUCCEEDED, null,
                List.of(new PlanRepository.ToolEvidenceClaim("evidence_" + suffix, "criterion_tests", callId,
                        "在途 Tool 已安全结算", NOW.plusSeconds(4))),
                "event_step_succeeded_" + suffix, "step:succeeded:" + suffix, NOW.plusSeconds(4)));
    }

    /**
     * Pause/Stop 共用一个可控的 Turn 屏障；测试只释放当前动作对应的 future，不模拟生产状态迁移。
     *
     * @author kongweiguang
     */
    private static PlanTurnPort controlledPlanControlPort(CompletableFuture<Void> suspension,
                                                          CompletableFuture<TurnResult> cancellation,
                                                          CountDownLatch controlRequested,
                                                          AtomicInteger controlCalls) {
        return new PlanTurnPort() {
            /** 当前控制竞态不创建新的 Plan Turn。 */
            @Override
            public Accepted startContinuation(StartRequest request, String instruction,
                    ConversationTurnEventSink sink,
                    io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot nativeContext) {
                throw new UnsupportedOperationException("control test must not start a Turn");
            }

            /** 这里只验证同一 Turn 控制屏障，不注册恢复 continuation。 */
            @Override
            public void registerResumeContinuation(String turnId,
                    java.util.function.Consumer<CompletionStage<TurnResult>> continuation) {
                throw new UnsupportedOperationException("control test must not register resume");
            }

            /** 当前用例没有需要清理的恢复 continuation。 */
            @Override
            public void clearResumeContinuation(String turnId) { }

            /** 暂停请求返回受控结算 stage，确保调用方在未来完成前不能提交 PAUSED。 */
            @Override
            public CompletionStage<Void> suspendPlanRun(String turnId) {
                controlCalls.incrementAndGet();
                controlRequested.countDown();
                return suspension;
            }

            /** 当前控制回归不恢复已暂停的 Turn。 */
            @Override
            public Accepted resume(String turnId, long expectedThreadRevision,
                    ConversationTurnEventSink sink,
                    io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot nativeContext) {
                throw new UnsupportedOperationException("control test must not resume a Turn");
            }

            /** Stop completion 由测试显式结算，绑定同一 Turn 的持久 ledger 屏障。 */
            @Override
            public CompletionStage<TurnResult> cancel(String turnId) {
                controlCalls.incrementAndGet();
                controlRequested.countDown();
                return cancellation;
            }

            /** 此测试不需要宿主 Native execution context。 */
            @Override
            public io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot runContext(
                    String planId, String runId) {
                return null;
            }
        };
    }

    /**
     * 在 Stop Turn claim 已真实 settlement 后注入另一合法终态，检查刷新版本仍不能吞掉状态/操作身份冲突。
     *
     * @author kongweiguang
     */
    private static PlanRepository stopPlanBeforeNextSettledSnapshot(MybatisPlanRepository delegate,
            Fixture fixture, String planId, String runId, String turnId,
            AtomicBoolean inject, AtomicBoolean committed) {
        return (PlanRepository) java.lang.reflect.Proxy.newProxyInstance(PlanRepository.class.getClassLoader(),
                new Class<?>[]{PlanRepository.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("readPlanSnapshot") && planId.equals(arguments[0])
                        && inject.get() && "SETTLED".equals(fixture.planTurnClaimState(runId, turnId))
                        && inject.compareAndSet(true, false)) {
                        Plan current = delegate.readPlanSnapshot(planId).plan();
                        delegate.stopPlan(new PlanRepository.StopPlan(planId, current.revision(), runId,
                                "event_competing_stop", "stop:competing-terminal", NOW.plusSeconds(5)));
                        committed.set(true);
                        return delegate.readPlanSnapshot(planId);
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    /** 装配真实 PlanService、Coordinator 和新建 adapter，模拟进程重启后仅持久状态仍可停止。 */
    private static PlanService planService(PlanRepository plans, PlanTurnPort turns,
                                          AtomicReference<TurnState> turnState) {
        PlanConversationPort conversations = new PlanConversationPort() {
            /** Stop 不进行新准入，Thread 读取保持显式未使用。 */
            @Override
            public ThreadBinding readThread(String threadId) {
                throw new UnsupportedOperationException("Stop must not admit a new Turn");
            }

            /** Stop 只观察精确 Turn 的当前权威状态。 */
            @Override
            public TurnBinding readTurn(String threadId, String turnId) {
                return new TurnBinding(turnId, turnState.get());
            }
        };
        PlanExecutionTurnAdapter adapter = new PlanExecutionTurnAdapter(turns, conversations,
                workspaceId -> { throw new UnsupportedOperationException("Stop must not open a Workspace"); },
                plans, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                (threadId, downstream, registered) -> ConversationTurnEventSink.noop());
        PlanEventRegistry events = new PlanEventRegistry();
        PlanExecutionCoordinator coordinator = new PlanExecutionCoordinator(plans, adapter,
                Clock.fixed(NOW, ZoneOffset.UTC), stopMustNotEvaluate());
        return new PlanService(plans, new CanonicalPlanJson(new ObjectMapper()),
                Clock.fixed(NOW, ZoneOffset.UTC), 1, coordinator, events,
                new PlanCommittedPublisher(plans, events));
    }

    /** Stop 测试不得触发 Plan 验收；意外推进时立即失败，避免吞掉额外执行副作用。 */
    private static PlanEvaluatorPort stopMustNotEvaluate() {
        return (snapshot, evidence, context) -> {
            throw new AssertionError("Stop path must not evaluate a Plan");
        };
    }

    /** 提供可手动释放的真实取消 completion，固定异步 Stop 的等待边界。 */
    private static PlanTurnPort controlledTurnPort(CompletableFuture<TurnResult> cancellation,
                                                   CountDownLatch cancellationRequested,
                                                   AtomicInteger cancellationCalls) {
        return new PlanTurnPort() {
            /** 本 Stop 测试不注册恢复回调。 */
            @Override
            public void registerResumeContinuation(String turnId,
                    java.util.function.Consumer<CompletionStage<TurnResult>> continuation) {
                throw new UnsupportedOperationException("Stop must not resume a Turn");
            }

            /** 本 Stop 测试没有待处理的恢复回调。 */
            @Override
            public void clearResumeContinuation(String turnId) { }

            /** 此夹具不接纳新 Turn，避免无关执行改变 STOP 断言。 */
            @Override
            public Accepted startContinuation(StartRequest request, String instruction,
                    ConversationTurnEventSink sink,
                    io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot nativeContext) {
                throw new UnsupportedOperationException("Stop must not start a Turn");
            }

            /** 此夹具不暂停 Turn。 */
            @Override
            public CompletionStage<Void> suspendPlanRun(String turnId) {
                return CompletableFuture.completedFuture(null);
            }

            /** Stop 使用持久 SUSPENDED cursor 的取消入口，不执行恢复准入。 */
            @Override
            public Accepted resume(String turnId, long expectedThreadRevision,
                    ConversationTurnEventSink sink,
                    io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot nativeContext) {
                throw new UnsupportedOperationException("Stop must not resume a Turn");
            }

            /** 取消等待真实终态；该 completion 模拟重启后的 TurnService durable barrier。 */
            @Override
            public CompletionStage<TurnResult> cancel(String turnId) {
                cancellationCalls.incrementAndGet();
                cancellationRequested.countDown();
                return cancellation;
            }

            /** 此 Stop 路径不读取运行时环境快照。 */
            @Override
            public io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot runContext(
                    String planId, String runId) {
                return null;
            }
        };
    }

    /** 让真实 Repository 在唯一 ledger mutation 处失败，验证 Stop 不吞掉 durable settlement error。 */
    private static PlanRepository failTurnSettlement(MybatisPlanRepository delegate) {
        return (PlanRepository) java.lang.reflect.Proxy.newProxyInstance(PlanRepository.class.getClassLoader(),
                new Class<?>[]{PlanRepository.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("settlePlanTurn")) {
                        throw new IllegalStateException("Injected Plan ledger settlement failure");
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    /** 仅在真实 SQLite 控制 fence 提交后唤醒竞态测试，避免依赖线程调度猜测 fence 顺序。 */
    private static PlanRepository observePauseFence(MybatisPlanRepository delegate, CountDownLatch committed) {
        return (PlanRepository) java.lang.reflect.Proxy.newProxyInstance(PlanRepository.class.getClassLoader(),
                new Class<?>[]{PlanRepository.class}, (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if (method.getName().equals("requestPlanControlFence")
                            || method.getName().equals("requestPlanPause")) {
                            committed.countDown();
                        }
                        return result;
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    /** 构造状态和 terminal event 一致的真实领域取消结果，供 TurnService completion barrier 使用。 */
    private static TurnResult cancelledTurnResult(String turnId) {
        TurnEvent.Context context = new TurnEvent.Context("evt_cancel_" + turnId,
                "thr_one", turnId, 1, 0, NOW.plusSeconds(3));
        TurnEvent.Terminal terminal = new TurnEvent.Terminal(context, TurnState.CANCELLED,
                "cancelled", null, null, null, null, null);
        return new TurnResult(TurnState.CANCELLED, terminal.summary(), terminal);
    }

    /** Resume 仅拒绝有终态但未结算的 claim，不能让新的 Turn 覆盖原账本身份。 */
    @Test
    void rejectsResumeWithTerminalUnsettledTurnClaim() throws Exception {
        try (Fixture fixture = fixture("resume-terminal-unsettled-claim")) {
            MybatisPlanRepository plans = fixture.repository();
            Plan awaiting = createAwaitingPlan(plans, "plan_resume_terminal", "thr_one", "revision_resume_terminal");
            PlanRevision revision = plans.readPlanSnapshot(awaiting.planId()).currentRevision();
            Plan executing = plans.executePlan(executeCommand(
                    awaiting, revision, "run_resume_terminal", "execute:resume-terminal", NOW.plusSeconds(1)));
            fixture.claimTurn(executing, "turn_resume_terminal");
            fixture.insertTurn("turn_resume_terminal");
            Plan paused = plans.pausePlan(new PlanRepository.PausePlan(executing.planId(), executing.revision(),
                    executing.activeRunId(), "event_pause_terminal", "pause:terminal", NOW.plusSeconds(2)));
            fixture.updateTurnState("turn_resume_terminal", "CANCELLED");
            int events = fixture.planEventCount(paused.planId());

            PlanRepositoryException failure = assertThrows(PlanRepositoryException.class, () ->
                    plans.resumePlan(new PlanRepository.ResumePlan(paused.planId(), paused.revision(),
                            paused.activeRunId(), "event_resume_terminal", "resume:terminal", NOW.plusSeconds(3))));

            assertEquals(PlanRepositoryException.Code.PLAN_INVALID_STATE, failure.code());
            assertEquals(paused, plans.readPlanSnapshot(paused.planId()).plan());
            assertEquals("PAUSED", fixture.runStatus(paused.activeRunId()));
            assertEquals("CLAIMED", fixture.planTurnClaimState(paused.activeRunId(), "turn_resume_terminal"));
            assertEquals(events, fixture.planEventCount(paused.planId()));
        }
    }

    /** SUSPENDED 与 WAITING_INPUT 都恢复同一 Run 的原 Turn，不创建新 claim 或 ordinal。 */
    @Test
    void resumesSuspendedAndInputWaitingTurnOnSameRun() throws Exception {
        for (String state : List.of("SUSPENDED", "WAITING_INPUT")) {
            try (Fixture fixture = fixture("resume-live-claim-" + state.toLowerCase(java.util.Locale.ROOT))) {
                MybatisPlanRepository plans = fixture.repository();
                String suffix = state.toLowerCase(java.util.Locale.ROOT);
                Plan awaiting = createAwaitingPlan(plans, "plan_resume_live_" + suffix, "thr_one",
                        "revision_resume_live_" + suffix);
                PlanRevision revision = plans.readPlanSnapshot(awaiting.planId()).currentRevision();
                Plan executing = plans.executePlan(executeCommand(awaiting, revision,
                        "run_resume_live_" + suffix, "execute:resume-live:" + suffix, NOW.plusSeconds(1)));
                PlanRepository.PlanTurnClaim original = fixture.claimTurn(executing, "turn_resume_live_" + suffix);
                fixture.insertTurn(original.turnId());
                fixture.updateTurnState(original.turnId(), state);
                Plan paused = plans.pausePlan(new PlanRepository.PausePlan(executing.planId(), executing.revision(),
                        executing.activeRunId(), "event_pause_live_" + suffix,
                        "pause:resume-live:" + suffix, NOW.plusSeconds(2)));

                Plan resumed = plans.resumePlan(new PlanRepository.ResumePlan(paused.planId(), paused.revision(),
                        paused.activeRunId(), "event_resume_live_" + suffix,
                        "resume:live:" + suffix, NOW.plusSeconds(3))).plan();
                PlanRepository.PlanTurnClaim replay = plans.claimPlanTurn(new PlanRepository.ClaimPlanTurn(
                        resumed.planId(), resumed.revision(), resumed.activeRunId(), resumed.activePlanRevisionId(),
                        original.turnId(), "event_claim_replay_" + suffix,
                        "claim:resume-live:" + suffix, NOW.plusSeconds(4))).orElseThrow();

                assertEquals(PlanStatus.EXECUTING, resumed.status());
                assertEquals(original.runId(), resumed.activeRunId());
                assertEquals(original, replay);
                assertEquals("CLAIMED", fixture.planTurnClaimState(resumed.activeRunId(), original.turnId()));
                assertEquals("RUNNING", fixture.runStatus(resumed.activeRunId()));
            }
        }
    }

    /** terminal Turn 的账本未结算时，保存草稿不得停止 Run、清除 claim 或产生半提交事件。 */
    @Test
    void draftCannotReplaceRunWithTerminalUnsettledTurnClaim() throws Exception {
        try (Fixture fixture = fixture("draft-terminal-unsettled-claim")) {
            MybatisPlanRepository plans = fixture.repository();
            Plan awaiting = createAwaitingPlan(plans, "plan_draft_unsettled", "thr_one", "revision_draft_unsettled");
            PlanRevision revision = plans.readPlanSnapshot(awaiting.planId()).currentRevision();
            Plan executing = plans.executePlan(executeCommand(
                    awaiting, revision, "run_draft_unsettled", "execute:draft-unsettled", NOW.plusSeconds(1)));
            fixture.claimTurn(executing, "turn_draft_unsettled");
            fixture.insertTurn("turn_draft_unsettled");
            Plan paused = plans.pausePlan(new PlanRepository.PausePlan(executing.planId(), executing.revision(),
                    executing.activeRunId(), "event_pause_draft_unsettled", "pause:draft-unsettled",
                    NOW.plusSeconds(2)));
            fixture.updateTurnState("turn_draft_unsettled", "CANCELLED");
            int events = fixture.planEventCount(paused.planId());
            String draftJson = new CanonicalPlanJson(new ObjectMapper()).encode(
                    definition("不能替换未结算 Run 的草稿")).json();

            PlanRepositoryException failure = assertThrows(PlanRepositoryException.class, () ->
                    plans.saveDraft(new PlanRepository.SaveDraft(paused.planId(), paused.revision(), 0,
                            draftJson, revision.planRevisionId(), "event_save_unsettled_draft",
                            "draft:unsettled", NOW.plusSeconds(3))));

            assertEquals(PlanRepositoryException.Code.PLAN_INVALID_STATE, failure.code());
            assertEquals(paused, plans.readPlanSnapshot(paused.planId()).plan());
            assertEquals("PAUSED", fixture.runStatus(paused.activeRunId()));
            assertEquals("CLAIMED", fixture.planTurnClaimState(paused.activeRunId(), "turn_draft_unsettled"));
            assertTrue(plans.findDraft(paused.planId()).isEmpty());
            assertEquals(events, fixture.planEventCount(paused.planId()));
        }
    }

    /** Plan Turn claim 重放复用 ordinal；无 Turn 的 admission 被暂停结算后封存，恢复可领取下一个。 */
    @Test
    void abandonsOrphanedTurnClaimBeforeResumingSameRun() throws Exception {
        try (Fixture fixture = fixture("claim-recovery")) {
            MybatisPlanRepository plans = fixture.repository();
            Plan awaiting = createAwaitingPlan(plans, "plan_claim", "thr_one", "revision_claim");
            PlanRevision revision = plans.readPlanSnapshot(awaiting.planId()).currentRevision();
            Plan executing = plans.executePlan(executeCommand(
                    awaiting, revision, "run_claim", "execute:claim", NOW.plusSeconds(1)));
            PlanRepository.ClaimPlanTurn firstCommand = new PlanRepository.ClaimPlanTurn(
                    executing.planId(), executing.revision(), executing.activeRunId(),
                    executing.activePlanRevisionId(), "turn_orphan", "event_claim_first",
                    "claim:first", NOW.plusSeconds(2));
            PlanRepository.PlanTurnClaim first = plans.claimPlanTurn(firstCommand).orElseThrow();
            PlanRepository.PlanTurnClaim replay = plans.claimPlanTurn(firstCommand).orElseThrow();

            assertEquals(first.ordinal(), replay.ordinal());
            Plan paused = plans.settlePlanExecution(new PlanRepository.SettlePlanExecution(
                    executing.planId(), executing.revision(), executing.activeRunId(),
                    "event_settle_orphan", "settle:orphan", NOW.plusSeconds(3)));
            assertEquals("ABANDONED", fixture.planTurnClaimState(executing.activeRunId(), "turn_orphan"));

            Plan resumed = plans.resumePlan(new PlanRepository.ResumePlan(paused.planId(), paused.revision(),
                    paused.activeRunId(), "event_resume_orphan", "resume:orphan", NOW.plusSeconds(4))).plan();
            PlanRepository.PlanTurnClaim next = plans.claimPlanTurn(new PlanRepository.ClaimPlanTurn(
                    resumed.planId(), resumed.revision(), resumed.activeRunId(), resumed.activePlanRevisionId(),
                    "turn_next", "event_claim_next", "claim:next", NOW.plusSeconds(5))).orElseThrow();

            assertEquals(2, next.ordinal());
            assertTrue(plans.claimPlanTurn(new PlanRepository.ClaimPlanTurn(
                    resumed.planId(), resumed.revision(), resumed.activeRunId(), resumed.activePlanRevisionId(),
                    "turn_orphan", "event_claim_retry", "claim:first:retry", NOW.plusSeconds(6))).isEmpty());
            assertEquals("run_claim", resumed.activeRunId());
            assertEquals(1, fixture.runCount(resumed.planId()));
        }
    }

    /** Plan 事件按持久序号分页；侧边 Thread 的计划不得进入主 Thread 的当前计划投影。 */
    @Test
    void isolatesThreadOwnershipAndPagesCommittedEvents() throws Exception {
        try (Fixture fixture = fixture("owner-and-events")) {
            fixture.insertThread("thr_side");
            MybatisPlanRepository plans = fixture.repository();
            Plan awaiting = createAwaitingPlan(plans, "plan_side", "thr_side", "revision_side");
            PlanRevision revision = plans.readPlanSnapshot(awaiting.planId()).currentRevision();
            Plan executing = plans.executePlan(executeCommand(
                    awaiting, revision, "run_side", "execute:side", NOW.plusSeconds(1)));

            assertTrue(plans.findCurrentPlan("thr_one").isEmpty());
            assertEquals("plan_side", plans.findCurrentPlan("thr_side").orElseThrow().plan().planId());
            assertEquals("plan_side", plans.findActivePlanByOwner("thr_side").orElseThrow().planId());

            PlanRepository.ReadPage<PlanRepository.PlanEvent> firstPage =
                    plans.listPlanEvents(executing.planId(), 0, 2);
            long cursor = firstPage.items().getLast().sequence();
            PlanRepository.ReadPage<PlanRepository.PlanEvent> secondPage =
                    plans.listPlanEvents(executing.planId(), cursor, 2);
            List<PlanRepository.PlanEvent> events = new ArrayList<>(firstPage.items());
            events.addAll(secondPage.items());

            assertEquals(2, firstPage.items().size());
            assertEquals(1, secondPage.items().size());
            assertEquals(List.of(1L, 2L, 3L), events.stream().map(PlanRepository.PlanEvent::sequence).toList());
            assertEquals("plan_execution_started", events.getLast().activity());
        }
    }

    /** 仓储异常转换不能抹去稳定领域错误，RPC 才能将旧批准精确映射为授权冲突。 */
    @Test
    void preservesStaleApprovalFailureAtTransactionBoundary() {
        PlanRepositoryException stale = new PlanRepositoryException(
                PlanRepositoryException.Code.PLAN_APPROVAL_STALE, "private detail");
        assertSame(stale, PlanUnitOfWork.transactionFailure(stale));
    }

    /**
     * 在隔离连接绕开 CHECK 后，须确认磁盘保留损坏 TEXT；读取只返回固定脱敏分类且不得修复或写入半成品状态。
     */
    @Test
    void rejectsInvalidPersistedPlanTimestampWithoutLeakingOrWriting() throws Exception {
        try (Fixture fixture = fixture("invalid-plan-timestamp")) {
            MybatisPlanRepository plans = fixture.repository();
            Plan plan = plans.createPlan(new PlanRepository.CreatePlan(
                    "plan_invalid_time", "thr_one", "时间读取", 0, "create:invalid-time", NOW));
            int eventsBefore = fixture.planEventCount(plan.planId());
            String damagedValue = "private-invalid-time-value";
            try (SqlSession session = fixture.sessions().openSession()) {
                // 仅此隔离连接绕过 CHECK，制造真实磁盘损坏以覆盖读取层的失败关闭与脱敏边界。
                try (Statement pragma = session.getConnection().createStatement()) {
                    assertFalse(pragma.execute("PRAGMA ignore_check_constraints=ON"));
                    try (ResultSet enabled = pragma.executeQuery("PRAGMA ignore_check_constraints")) {
                        assertTrue(enabled.next());
                        assertEquals(1, enabled.getInt(1));
                    }
                }
                try (PreparedStatement update = session.getConnection().prepareStatement(
                        "UPDATE ja_plan SET updated_at_ms=? WHERE plan_id=?")) {
                    update.setString(1, damagedValue);
                    update.setString(2, plan.planId());
                    assertEquals(1, update.executeUpdate());
                }
                try (PreparedStatement query = session.getConnection().prepareStatement(
                        "SELECT typeof(updated_at_ms),updated_at_ms FROM ja_plan WHERE plan_id=?")) {
                    query.setString(1, plan.planId());
                    try (ResultSet rows = query.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals("text", rows.getString(1));
                        assertEquals(damagedValue, rows.getString(2));
                    }
                }
                session.commit();
            }

            StorageException failure = assertThrows(StorageException.class,
                    () -> plans.readPlanSnapshot(plan.planId()));

            assertEquals(StorageException.Code.INVALID_STATE, failure.code());
            assertEquals("invalid persisted Plan timestamp", failure.getMessage());
            assertFalse(failure.getMessage().contains(damagedValue));
            assertFalse(failure.getCause().getMessage().contains(damagedValue));
            assertEquals("DRAFT", fixture.planStatus(plan.planId()));
            assertEquals(eventsBefore, fixture.planEventCount(plan.planId()));
            try (SqlSession session = fixture.sessions().openSession();
                 PreparedStatement query = session.getConnection().prepareStatement(
                         "SELECT updated_at_ms FROM ja_plan WHERE plan_id=?")) {
                query.setString(1, plan.planId());
                try (ResultSet rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(damagedValue, rows.getString(1));
                }
            }
        }
    }

    /** 构造唯一必要步骤和验收条件，确保授权测试的 revision hash 可稳定复算。 */
    private static PlanDefinition definition(String scope) {
        return new PlanDefinition("交付目标", List.of(scope), List.of(), List.of(), List.of(),
                List.of(new PlanStep("step_work", "实施", "完成实现", true, List.of())),
                List.of(new AcceptanceCriterion("criterion_tests", "计划测试通过", true)),
                List.of(), List.of("运行聚焦测试"));
    }

    /** 创建一个 owner-bound 草稿并冻结首次 revision，返回仓储当前权威 Plan 投影。 */
    private static Plan createAwaitingPlan(MybatisPlanRepository repository, String planId,
                                           String ownerThreadId, String revisionId) {
        Plan created = repository.createPlan(new PlanRepository.CreatePlan(
                planId, ownerThreadId, "交付目标", 0, "create:" + planId, NOW));
        PlanDefinition definition = definition("当前工作范围");
        PlanCanonicalizer.Encoded canonical = new CanonicalPlanJson(new ObjectMapper()).encode(definition);
        repository.propose(new PlanRepository.ProposePlan(planId, created.revision(),
                new PlanRevision(revisionId, planId, 1, definition, canonical.json(), canonical.sha256(), "AGENT", NOW),
                "event_propose:" + planId, "propose:" + planId, NOW));
        return repository.readPlanSnapshot(planId).plan();
    }

    /** 构造绑定服务端返回 revision/hash 的执行命令，避免测试从用户输入猜测授权身份。 */
    private static PlanRepository.ExecutePlan executeCommand(Plan plan, PlanRevision revision,
                                                              String runId, String operationKey, Instant at) {
        return new PlanRepository.ExecutePlan(plan.planId(), plan.revision(), revision.planRevisionId(),
                revision.planHash(), "approval:" + runId, runId, 1, "event:" + runId,
                operationKey, at);
    }

    /** 初始化 fresh SQLite 与 Plan-only Mapper；测试 owner 保留真实单事务提交/回滚边界。 */
    private Fixture fixture(String name) throws Exception {
        JaDatabase database = JaDatabase.open(new DatabaseConfig(temp.resolve(name + ".sqlite3"),
                DatabaseConfig.DEFAULT_BUSY_TIMEOUT, temp));
        Configuration configuration = new Configuration(new Environment("plan-repository-test",
                new JdbcTransactionFactory(), database.dataSource()));
        configuration.setMapUnderscoreToCamelCase(false);
        configuration.addMapper(SchemaMapper.class);
        configuration.addMapper(PlanMapper.class);
        SqlSessionFactory sessions = new SqlSessionFactoryBuilder().build(configuration);
        database.bindWalCheckpoint(sessions);
        try (SqlSession session = sessions.openSession()) {
            try (PreparedStatement insert = session.getConnection().prepareStatement("""
                    INSERT INTO ja_workspace(workspace_id,root_path,kind,display_name,trust,revision,
                    created_at_ms,updated_at_ms) VALUES('ws_one','C:/plan-test','PROJECT','Plan test',
                    'TRUSTED',0,?,?)
                    """)) {
                insert.setLong(1, NOW.toEpochMilli());
                insert.setLong(2, NOW.toEpochMilli());
                insert.executeUpdate();
            }
            try (Statement sql = session.getConnection().createStatement()) {
                insertThread(sql, "thr_one", "ws_one", "主对话");
            }
            session.commit();
        }
        PlanUnitOfWork.SessionOwner owner = new PlanUnitOfWork.SessionOwner() {
            /** 测试替换 Solon 提交桥，但异常失败路径仍回滚整个 repository mutation。 */
            @Override
            public <T> T execute(SqlSessionFactory factory, PlanUnitOfWork.Work<T> work) {
                try (SqlSession session = factory.openSession()) {
                    try {
                        T value = work.apply(new PlanUnitOfWork.Mappers(session.getMapper(PlanMapper.class)));
                        session.commit();
                        return value;
                    } catch (Throwable failure) {
                        session.rollback();
                        if (failure instanceof RuntimeException runtime) {
                            throw runtime;
                        }
                        throw new IllegalStateException("Plan test transaction failed", failure);
                    }
                }
            }
        };
        return new Fixture(database, sessions, new MybatisPlanRepository(sessions, new ObjectMapper(), owner));
    }

    /** 通过 workspace 的真实外键加入第二个 owner Thread，不伪造 Plan owner 事实。 */
    private static void insertThread(Statement sql, String threadId, String workspaceId, String title)
            throws Exception {
        try (PreparedStatement insert = sql.getConnection().prepareStatement("""
                INSERT INTO ja_conversation_thread(thread_id,owner_user_id,workspace_id,title,revision,created_at_ms,updated_at_ms,\
                provider_id,model_id,access_mode,title_source,collaboration_mode) \
                VALUES(?,'user_local',?,?,0,?,?, 'provider','model',\
                'APPROVAL_REQUIRED','MANUAL','PLAN')\
                """)) {
            insert.setString(1, threadId);
            insert.setString(2, workspaceId);
            insert.setString(3, title);
            insert.setLong(4, NOW.toEpochMilli());
            insert.setLong(5, NOW.toEpochMilli());
            insert.executeUpdate();
        }
    }

    /**
     * 测试资源 owner 在用例退出时关闭真实数据库 lease，防止 SQLite WAL 句柄泄漏。
     *
     * @param database   数据库租约，由 Fixture 在用例退出时关闭
     * @param sessions   MyBatis 会话工厂，用于核实提交后的持久事实
     * @param repository 被测 Plan 仓储实例
     */
    private record Fixture(JaDatabase database,
                           SqlSessionFactory sessions,
                           MybatisPlanRepository repository) implements AutoCloseable {
        /** 查询指定计划的持久 Run 数，区分已授权执行与仅批准投影。 */
        private int runCount(String planId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement query = session.getConnection().prepareStatement(
                         "SELECT COUNT(*) FROM ja_plan_execution WHERE plan_id=?")) {
                query.setString(1, planId);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new AssertionError("missing run count");
                    }
                    return rows.getInt(1);
                }
            }
        }

        /** 精确读取绑定 revision/hash 的批准行，证明重复执行没有额外授权事实。 */
        private int approvalCount(String planId, PlanRevision revision) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement query = session.getConnection().prepareStatement("""
                         SELECT COUNT(*) FROM ja_plan_approval WHERE plan_id=? AND plan_revision_id=?\
                         AND plan_hash=? AND decision='APPROVED'\
                         """)) {
                query.setString(1, planId);
                query.setString(2, revision.planRevisionId());
                query.setString(3, revision.planHash());
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new AssertionError("missing approval count");
                    }
                    return rows.getInt(1);
                }
            }
        }

        /** 仅在隔离 SQLite fixture 移除不可变触发器，再改写合法正文以模拟磁盘损坏。 */
        private void replaceRevisionDefinition(String revisionId, PlanDefinition definition) throws Exception {
            String changedJson = new CanonicalPlanJson(new ObjectMapper()).encode(definition).json();
            try (SqlSession session = sessions.openSession()) {
                try (Statement ddl = session.getConnection().createStatement()) {
                    ddl.executeUpdate("DROP TRIGGER plan_revisions_immutable_update");
                }
                try (PreparedStatement update = session.getConnection().prepareStatement(
                        "UPDATE ja_plan_revision SET definition_json=? WHERE plan_revision_id=?")) {
                    update.setString(1, changedJson);
                    update.setString(2, revisionId);
                    if (update.executeUpdate() != 1) {
                        throw new AssertionError("missing Plan revision");
                    }
                }
                session.commit();
            }
        }

        /** 读取持久 Plan 状态，以便在 revision 解码失败后仍能核实事务无部分提交。 */
        private String planStatus(String planId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement query = session.getConnection().prepareStatement(
                         "SELECT status FROM ja_plan WHERE plan_id=?")) {
                query.setString(1, planId);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new AssertionError("missing Plan");
                    }
                    return rows.getString(1);
                }
            }
        }

        /** 读取已提交事件数量，确认损坏 revision 不会留下批准或恢复的半个事件。 */
        private int planEventCount(String planId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement query = session.getConnection().prepareStatement(
                         "SELECT COUNT(*) FROM ja_plan_event WHERE plan_id=?")) {
                query.setString(1, planId);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new AssertionError("missing Plan event count");
                    }
                    return rows.getInt(1);
                }
            }
        }

        /** 回读事务提交后的 Run 状态，避免只凭 Plan 内存投影断言恢复成功。 */
        private String runStatus(String runId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement query = session.getConnection().prepareStatement(
                         "SELECT status FROM ja_plan_execution WHERE run_id=?")) {
                query.setString(1, runId);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new AssertionError("missing execution run");
                    }
                    return rows.getString(1);
                }
            }
        }

        /** 先提交第二个 Thread 行，保持 owner 隔离测试通过生产数据库约束验证。 */
        private void insertThread(String threadId) throws Exception {
            try (SqlSession session = sessions.openSession(); Statement sql = session.getConnection().createStatement()) {
                MybatisPlanRepositoryTest.insertThread(sql, threadId, "ws_one", "侧边任务");
                session.commit();
            }
        }

        /** 用生产仓储规则领取 Plan Turn，阻止 fixture 绕过 Run 与 owner 校验。 */
        private PlanRepository.PlanTurnClaim claimTurn(Plan plan, String turnId) {
            return repository.claimPlanTurn(new PlanRepository.ClaimPlanTurn(plan.planId(), plan.revision(),
                    plan.activeRunId(), plan.activePlanRevisionId(), turnId, "event_claim_" + turnId,
                    "claim:" + turnId, NOW)).orElseThrow();
        }

        /** 仅改变隔离 fixture 的 Conversation 终态，模拟 Turn 已终止但账本提交失败的恢复窗口。 */
        private void updateTurnState(String turnId, String state) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement update = session.getConnection().prepareStatement("""
                         UPDATE ja_conversation_turn
                         SET state=?,completed_at_ms=CASE WHEN ? IN ('COMPLETED','FAILED','CANCELLED') THEN ? ELSE NULL END,
                             terminal_summary=CASE WHEN ? IN ('COMPLETED','FAILED','CANCELLED') THEN 'test terminal' ELSE NULL END
                         WHERE turn_id=?
                         """)) {
                update.setString(1, state);
                update.setString(2, state);
                update.setLong(3, NOW.plusSeconds(1).toEpochMilli());
                update.setString(4, state);
                update.setString(5, turnId);
                assertEquals(1, update.executeUpdate());
                session.commit();
            }
        }

        /** 回读 durable pause fence，确认 Stop 的新准入屏障已在恢复 Turn 前提交。 */
        private boolean runPauseRequested(String runId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement query = session.getConnection().prepareStatement(
                         "SELECT is_pause_requested FROM ja_plan_execution WHERE run_id=?")) {
                query.setString(1, runId);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new AssertionError("missing execution run");
                    }
                    return rows.getInt(1) == 1;
                }
            }
        }

        /**
         * 只统计指定业务终态事件，排除 Turn settlement 等合法内部事件对并发控制断言的干扰。
         *
         * @author kongweiguang
         */
        private int planActivityEventCount(String planId, String activity) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement query = session.getConnection().prepareStatement(
                         "SELECT COUNT(*) FROM ja_plan_event WHERE plan_id=? AND activity=?")) {
                query.setString(1, planId);
                query.setString(2, activity);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new AssertionError("missing Plan activity event count");
                    }
                    return rows.getInt(1);
                }
            }
        }

        /** 回读同一 Run 已原子累计的模型轮次和工具调用，验证 Turn claim settlement 没有丢 usage。 */
        private RunUsage runUsage(String runId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement query = session.getConnection().prepareStatement(
                         "SELECT used_model_rounds,used_tool_calls FROM ja_plan_execution WHERE run_id=?")) {
                query.setString(1, runId);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new AssertionError("missing execution usage");
                    }
                    return new RunUsage(rows.getLong(1), rows.getLong(2));
                }
            }
        }

        /** 读取真实持久 Turn 状态，验证取消之前 admission 已完成且 Turn 仍在执行。 */
        private String turnState(String turnId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement query = session.getConnection().prepareStatement(
                         "SELECT state FROM ja_conversation_turn WHERE turn_id=?")) {
                query.setString(1, turnId);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new AssertionError("missing Conversation Turn");
                    }
                    return rows.getString(1);
                }
            }
        }

        /** 查询精确 Turn 是否已持久化，区分被 fence 拒绝与仅未进入内存执行器的状态。 */
        private boolean turnExists(String turnId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement query = session.getConnection().prepareStatement(
                         "SELECT 1 FROM ja_conversation_turn WHERE turn_id=?")) {
                query.setString(1, turnId);
                try (ResultSet rows = query.executeQuery()) {
                    return rows.next();
                }
            }
        }

        /** 仅为账本守卫构造 API 不可达的既存草稿行，验证异常恢复状态不能靠丢草稿绕过。 */
        private void insertPlanDraft(String planId, String definitionJson, String baselineRevisionId)
                throws Exception {
            try (SqlSession session = sessions.openSession()) {
                int inserted = session.getMapper(PlanMapper.class).insertPlanDraftsWithPlanDraftId(
                        "plandraft_recovery_" + planId, planId, definitionJson, baselineRevisionId, NOW.toString());
                assertEquals(1, inserted);
                session.commit();
            }
        }

        /** 创建一个有效 claim、Turn 和 ToolCall，供单 Tool attempt 测试复用。 */
        private void insertClaimedToolCall(Plan plan, String turnId, String callId) throws Exception {
            claimTurn(plan, turnId);
            insertTurn(turnId);
            insertToolCall(turnId, callId, 0);
        }

        /** Turn 必须属于 Plan owner Thread，且保持在真实执行中的状态。 */
        private void insertTurn(String turnId) throws Exception {
            try (SqlSession session = sessions.openSession()) {
                try (PreparedStatement turn = session.getConnection().prepareStatement("""
                        INSERT INTO ja_conversation_turn(turn_id,thread_id,state,requested_at_ms,updated_at_ms,completed_at_ms,\
                        terminal_summary,parent_turn_id,root_turn_id) \
                        VALUES(?,'thr_one','RUNNING',?, ?, NULL,NULL,NULL,NULL)\
                        """)) {
                    turn.setString(1, turnId);
                    turn.setLong(2, NOW.toEpochMilli());
                    turn.setLong(3, NOW.toEpochMilli());
                    turn.executeUpdate();
                }
                session.commit();
            }
        }

        /** 写入与真实 Turn 完整匹配的不可变 Plan binding，模拟重启后 adapter 从数据库恢复控制权。 */
        private void insertPlanTurnBinding(Plan plan, PlanRevision revision, String turnId) throws Exception {
            String context = new ObjectMapper().writeValueAsString(Map.of(
                    "kind", "PLAN_EXECUTION", "planId", plan.planId(), "runId", plan.activeRunId(),
                    "planRevisionId", revision.planRevisionId(), "planHash", revision.planHash()));
            try (SqlSession session = sessions.openSession();
                 PreparedStatement insert = session.getConnection().prepareStatement("""
                         INSERT INTO ja_turn_internal_context(turn_id,origin,context_json,created_at_ms)
                         VALUES(?,'PLAN_EXECUTION',?,?)
                         """)) {
                insert.setString(1, turnId);
                insert.setString(2, context);
                insert.setLong(3, NOW.toEpochMilli());
                assertEquals(1, insert.executeUpdate());
                session.commit();
            }
        }

        /** 以符合 V1 所有者约束的 evaluator UNKNOWN 事实覆盖无 Turn claim 的不安全恢复分支。 */
        private void insertUnknownEvaluationAttempt(Plan plan, String evaluationId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement insert = session.getConnection().prepareStatement("""
                         INSERT INTO ja_plan_evaluation_attempt(evaluation_id,model_request_id,plan_id,
                         plan_revision_id,run_id,owner_thread_id,input_digest,attempt_ordinal,profile_json,
                         outcome,certainty,started_at_ms)
                         VALUES(?,?,?,?,?,?,?,1,'{}','RUNNING','UNKNOWN',?)
                         """)) {
                insert.setString(1, evaluationId);
                insert.setString(2, "request_" + evaluationId);
                insert.setString(3, plan.planId());
                insert.setString(4, plan.activePlanRevisionId());
                insert.setString(5, plan.activeRunId());
                insert.setString(6, plan.ownerThreadId());
                insert.setString(7, "f".repeat(64));
                insert.setLong(8, NOW.toEpochMilli());
                assertEquals(1, insert.executeUpdate());
                try (PreparedStatement interrupted = session.getConnection().prepareStatement("""
                        UPDATE ja_plan_evaluation_attempt SET outcome='UNKNOWN',completed_at_ms=?
                        WHERE evaluation_id=? AND outcome='RUNNING'
                        """)) {
                    interrupted.setLong(1, NOW.plusSeconds(1).toEpochMilli());
                    interrupted.setString(2, evaluationId);
                    assertEquals(1, interrupted.executeUpdate());
                }
                session.commit();
            }
        }

        /** 回读 Run 的全部 admitted Turn claims，确保无 claim 的恢复场景没有依赖虚构 Turn identity。 */
        private int planTurnClaimCount(String runId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement query = session.getConnection().prepareStatement(
                         "SELECT COUNT(*) FROM ja_plan_turn_claim WHERE run_id=?")) {
                query.setString(1, runId);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new AssertionError("missing claim count");
                    }
                    return rows.getInt(1);
                }
            }
        }

        /** 创建真实 UNKNOWN usage 行；结算按持久 model round 计量，不从内存 TurnResult 推测。 */
        private void insertTurnUsage(String turnId, String usageId, String requestId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement insert = session.getConnection().prepareStatement("""
                         INSERT INTO ja_turn_usage(usage_id,model_request_id,thread_id,turn_id,model_round,
                         model_request_ordinal,purpose,certainty,profile_json,created_at_ms)
                         VALUES(?,?, 'thr_one', ?,1,1,'ASSISTANT','UNKNOWN','{}',?)
                         """)) {
                insert.setString(1, usageId);
                insert.setString(2, requestId);
                insert.setString(3, turnId);
                insert.setLong(4, NOW.toEpochMilli());
                assertEquals(1, insert.executeUpdate());
                session.commit();
            }
        }

        /** ToolCall 只在真实 Turn 下新增，多个工具可以共用一个已经领取的 Plan Turn。 */
        private void insertToolCall(String turnId, String callId, int ordinal) throws Exception {
            try (SqlSession session = sessions.openSession()) {
                try (PreparedStatement tool = session.getConnection().prepareStatement("""
                        INSERT INTO ja_tool_call(call_id,thread_id,turn_id,ordinal,tool_name,side_effect,\
                        presentation_json,state,revision,created_at_ms,updated_at_ms) \
                        VALUES(?,'thr_one',?,?, 'shell','EXTERNAL','{}','PREPARED',0,?,?)\
                        """)) {
                    tool.setString(1, callId);
                    tool.setString(2, turnId);
                    tool.setInt(3, ordinal);
                    tool.setLong(4, NOW.toEpochMilli());
                    tool.setLong(5, NOW.toEpochMilli());
                    tool.executeUpdate();
                }
                session.commit();
            }
        }

        /** 读取已提交 Turn claim 状态，恢复判定不从 coordinator 的进程内集合推测。 */
        private String planTurnClaimState(String runId, String turnId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement query = session.getConnection().prepareStatement(
                         "SELECT state FROM ja_plan_turn_claim WHERE run_id=? AND turn_id=?")) {
                query.setString(1, runId);
                query.setString(2, turnId);
                try (ResultSet rows = query.executeQuery()) {
                    return rows.next() ? rows.getString(1) : null;
                }
            }
        }

        /** 释放真实 SQLite lease 并让 JaDatabase 自己执行 WAL 清理。 */
        @Override
        public void close() {
            database.close();
        }
    }
}




