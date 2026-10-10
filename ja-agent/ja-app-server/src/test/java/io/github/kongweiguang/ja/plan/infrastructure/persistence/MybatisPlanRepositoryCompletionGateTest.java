// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.plan.infrastructure.persistence.serialization.CanonicalPlanJson;
import io.github.kongweiguang.ja.plan.application.gateway.PlanCanonicalizer;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.domain.PlanModels.AcceptanceCriterion;
import io.github.kongweiguang.ja.plan.domain.PlanModels.EvidenceSource;
import io.github.kongweiguang.ja.plan.domain.PlanModels.Plan;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanDefinition;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanRevision;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanStatus;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanStep;
import io.github.kongweiguang.ja.plan.domain.PlanModels.StepStatus;
import io.github.kongweiguang.ja.plan.domain.PlanModels.ToolAttempt;
import io.github.kongweiguang.ja.plan.domain.PlanModels.ToolAttemptState;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepositoryException;
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
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实 SQLite 锁定 Plan 完成门的证据、未知副作用和幂等语义。 */
final class MybatisPlanRepositoryCompletionGateTest {
    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");

    @TempDir
    Path temp;

    /** 必要步骤虽已成功但缺少当前验收证据时，不得进入独立 Plan 验收阶段。 */
    @Test
    void planStaysExecutingWhenRequiredStepSucceedsWithoutEvidence() throws Exception {
        try (Fixture fixture = fixture("plan-missing-evidence")) {
            Plan executing = createRunningPlan(fixture.repository, "plan_missing", "run_missing", "missing");
            Plan running = movePlanStep(fixture.repository, executing, StepStatus.READY, StepStatus.RUNNING,
                    List.of());
            Plan succeeded = movePlanStep(fixture.repository, running, StepStatus.RUNNING, StepStatus.SUCCEEDED,
                    List.of());

            Plan afterAttempt = fixture.repository.beginPlanVerification(new PlanRepository.BeginPlanVerification(
                    succeeded.planId(), succeeded.revision(), succeeded.activeRunId(), "event_missing_verification",
                    "plan:missing:verification", NOW.plusSeconds(2)));

            assertEquals(PlanStatus.EXECUTING, afterAttempt.status());
            assertEquals(succeeded.revision(), afterAttempt.revision());
        }
    }

    /** 即使步骤和当前证据齐全，UNKNOWN Tool 也必须阻止 MET 完成，避免未知副作用被误报成功。 */
    @Test
    void planCompletionRejectsUnknownToolEvenWhenCurrentEvidenceExists() throws Exception {
        try (Fixture fixture = fixture("plan-unknown-tool")) {
            Plan executing = createRunningPlan(fixture.repository, "plan_unknown", "run_unknown", "unknown");
            Plan succeeded = settlePlanStepWithEvidence(fixture, executing, "unknown");
            Plan verifying = fixture.repository.beginPlanVerification(new PlanRepository.BeginPlanVerification(
                    succeeded.planId(), succeeded.revision(), succeeded.activeRunId(), "event_unknown_verification",
                    "plan:unknown:verification", NOW.plusSeconds(3)));
            assertEquals(PlanStatus.VERIFYING, verifying.status());

            settleUnknownTool(fixture, verifying, "turn_plan_unknown", "unknown");

            PlanRepositoryException failure = assertThrows(PlanRepositoryException.class, () ->
                    fixture.repository.completePlanVerification(new PlanRepository.CompletePlanVerification(
                            verifying.planId(), verifying.revision(), verifying.activeRunId(),
                            PlanModels.EvaluationVerdict.MET, "验收通过", "event_unknown_complete",
                            "plan:unknown:complete", NOW.plusSeconds(4))));
            assertEquals(PlanRepositoryException.Code.PLAN_EVIDENCE_INCOMPLETE, failure.code());
            assertEquals(PlanStatus.VERIFYING, fixture.repository.readPlanSnapshot(verifying.planId()).plan().status());
        }
    }

    /** 旧 Plan revision 的 evidence 即使挂在同一 run，也不能满足当前 revision 的必要验收条件。 */
    @Test
    void planCompletionIgnoresEvidenceFromPreviousRevision() throws Exception {
        try (Fixture fixture = fixture("plan-old-revision-evidence")) {
            RevisionPlan plan = createTwoRevisionPlan(fixture.repository, "plan_old", "run_old");
            Plan running = movePlanStep(fixture.repository, plan.currentPlan(), StepStatus.READY,
                    StepStatus.RUNNING, List.of());
            Plan succeeded = movePlanStep(fixture.repository, running, StepStatus.RUNNING,
                    StepStatus.SUCCEEDED, List.of());
            fixture.insertPlanEvidence("evidence_old_revision", plan.currentPlan().planId(),
                    succeeded.activeRunId(), plan.firstRevision().planRevisionId(),
                    "criterion_old", "step_work");

            Plan afterAttempt = fixture.repository.beginPlanVerification(new PlanRepository.BeginPlanVerification(
                    succeeded.planId(), succeeded.revision(), succeeded.activeRunId(), "event_old_verification",
                    "plan:old:verification", NOW.plusSeconds(3)));

            assertEquals(PlanStatus.EXECUTING, afterAttempt.status());
            assertEquals(succeeded.revision(), afterAttempt.revision());
        }
    }

    /** 当前 revision 的真实 Tool evidence 与 MET 结论只产生一次完成事实，重放返回同一终态。 */
    @Test
    void planCompletionWithCurrentEvidenceAndMetIsIdempotent() throws Exception {
        try (Fixture fixture = fixture("plan-completion-idempotency")) {
            Plan executing = createRunningPlan(fixture.repository, "plan_complete", "run_complete", "complete");
            Plan succeeded = settlePlanStepWithEvidence(fixture, executing, "complete");
            Plan verifying = fixture.repository.beginPlanVerification(new PlanRepository.BeginPlanVerification(
                    succeeded.planId(), succeeded.revision(), succeeded.activeRunId(), "event_complete_verification",
                    "plan:complete:verification", NOW.plusSeconds(3)));
            int eventsBeforeCompletion = fixture.planEventCount(verifying.planId());
            PlanRepository.CompletePlanVerification command = new PlanRepository.CompletePlanVerification(
                    verifying.planId(), verifying.revision(), verifying.activeRunId(),
                    PlanModels.EvaluationVerdict.MET, "验收通过", "event_complete", "plan:complete", NOW.plusSeconds(4));

            Plan completed = fixture.repository.completePlanVerification(command);
            Plan replayed = fixture.repository.completePlanVerification(command);

            assertEquals(PlanStatus.COMPLETED, completed.status());
            assertEquals(completed, replayed);
            assertEquals(eventsBeforeCompletion + 1, fixture.planEventCount(completed.planId()));
            assertEquals("COMPLETED", fixture.runStatus(completed.activeRunId()));
        }
    }

    /** 已提交的暂停 fence 必须阻止 MET 完成，也不能被 NOT_MET 清除后恢复执行。 */
    @Test
    void pauseFenceWinsOverMetAndNotMetVerificationResults() throws Exception {
        assertPauseFenceWins(PlanModels.EvaluationVerdict.MET, "pause-fence-met");
        assertPauseFenceWins(PlanModels.EvaluationVerdict.NOT_MET, "pause-fence-not-met");
    }

    /** 每个 verdict 使用独立 SQLite 数据库，验证暂停结果最终保留在 Plan 与 Run 持久状态中。 */
    private void assertPauseFenceWins(PlanModels.EvaluationVerdict verdict, String suffix) throws Exception {
        try (Fixture fixture = fixture(suffix)) {
            Plan executing = createRunningPlan(fixture.repository, "plan_" + suffix, "run_" + suffix, suffix);
            Plan succeeded = settlePlanStepWithEvidence(fixture, executing, suffix);
            Plan verifying = fixture.repository.beginPlanVerification(new PlanRepository.BeginPlanVerification(
                    succeeded.planId(), succeeded.revision(), succeeded.activeRunId(), "event_verify_" + suffix,
                    "plan:" + suffix + ":verify", NOW.plusSeconds(3)));
            int eventsBeforePause = fixture.planEventCount(verifying.planId());

            fixture.repository.requestPlanPause(new PlanRepository.RequestPlanPause(verifying.planId(),
                    verifying.revision(), verifying.activeRunId(), "event_pause_fence_" + suffix,
                    "plan:" + suffix + ":pause-fence", NOW.plusSeconds(4)));
            Plan afterVerdict = fixture.repository.completePlanVerification(new PlanRepository.CompletePlanVerification(
                    verifying.planId(), verifying.revision(), verifying.activeRunId(), verdict, "验收结果",
                    "event_verdict_" + suffix, "plan:" + suffix + ":verdict", NOW.plusSeconds(5)));

            assertEquals(verifying, afterVerdict);
            assertEquals(eventsBeforePause, fixture.planEventCount(verifying.planId()));
            assertEquals("VERIFYING", fixture.runStatus(verifying.activeRunId()));
            assertEquals(1, fixture.pauseRequested(verifying.activeRunId()));

            Plan paused = fixture.repository.pausePlan(new PlanRepository.PausePlan(verifying.planId(),
                    verifying.revision(), verifying.activeRunId(), "event_paused_" + suffix,
                    "plan:" + suffix + ":pause", NOW.plusSeconds(6)));
            assertEquals(PlanStatus.PAUSED, paused.status());
            assertEquals("PAUSED", fixture.runStatus(paused.activeRunId()));
            assertEquals(1, fixture.pauseRequested(paused.activeRunId()));
        }
    }

    /** 创建真实 Plan revision、Run 与步骤执行投影，保证完成门测试经过公开 repository mutation。 */
    private static Plan createRunningPlan(MybatisPlanRepository repository, String planId, String runId,
                                          String suffix) {
        Plan created = repository.createPlan(new PlanRepository.CreatePlan(
                planId, "thr_completion_gate", "完成门测试", 0, "create:" + planId, NOW));
        PlanDefinition definition = planDefinition(suffix);
        PlanCanonicalizer.Encoded encoded = new CanonicalPlanJson(new ObjectMapper()).encode(definition);
        PlanRevision proposed = repository.propose(new PlanRepository.ProposePlan(
                planId, created.revision(), new PlanRevision("revision_" + suffix, planId, 1,
                definition, encoded.json(), encoded.sha256(), "AGENT", NOW),
                "event_propose_" + suffix, "propose:" + suffix, NOW));
        return repository.executePlan(new PlanRepository.ExecutePlan(planId, 1,
                proposed.planRevisionId(), proposed.planHash(), "approval:" + suffix, runId, 1,
                "event_execute_" + suffix, "execute:" + suffix, NOW));
    }

    /** 创建两个不可变 Plan revision 并执行第二版，用旧版证据验证 revision 精确匹配。 */
    private static RevisionPlan createTwoRevisionPlan(MybatisPlanRepository repository, String planId, String runId) {
        Plan created = repository.createPlan(new PlanRepository.CreatePlan(
                planId, "thr_completion_gate", "多版本完成门测试", 0, "create:" + planId, NOW));
        ObjectMapper mapper = new ObjectMapper();
        PlanDefinition firstDefinition = planDefinition("old");
        PlanCanonicalizer.Encoded firstEncoded = new CanonicalPlanJson(mapper).encode(firstDefinition);
        PlanRevision first = repository.propose(new PlanRepository.ProposePlan(planId, created.revision(),
                new PlanRevision("revision_old", planId, 1, firstDefinition, firstEncoded.json(),
                        firstEncoded.sha256(), "AGENT", NOW), "event_propose_old", "propose:old", NOW));
        Plan rejected = repository.reject(new PlanRepository.RejectPlan(planId, 1, first.planRevisionId(),
                first.planHash(), "approval_old", "旧版测试", "event_reject_old", "reject:old", NOW.plusSeconds(1)));
        PlanDefinition currentDefinition = planDefinition("current");
        PlanCanonicalizer.Encoded currentEncoded = new CanonicalPlanJson(mapper).encode(currentDefinition);
        PlanRevision current = repository.propose(new PlanRepository.ProposePlan(planId, rejected.revision(),
                new PlanRevision("revision_current", planId, 2, currentDefinition, currentEncoded.json(),
                        currentEncoded.sha256(), "AGENT", NOW.plusSeconds(2)), "event_propose_current",
                "propose:current", NOW.plusSeconds(2)));
        Plan running = repository.executePlan(new PlanRepository.ExecutePlan(planId, rejected.revision() + 1,
                current.planRevisionId(), current.planHash(), "approval_current", runId, 1,
                "event_execute_current", "execute:current", NOW.plusSeconds(3)));
        return new RevisionPlan(first, current, running);
    }

    /** 仅推进步骤状态；证据 claim 是否存在由调用方显式控制，便于隔离完成门条件。 */
    private static Plan movePlanStep(MybatisPlanRepository repository, Plan plan, StepStatus expected,
                                     StepStatus target, List<PlanRepository.ToolEvidenceClaim> claims) {
        return repository.updatePlanStep(new PlanRepository.UpdatePlanStep(plan.planId(), plan.revision(),
                plan.activeRunId(), "step_work", expected, target, null, claims,
                "event_step_" + plan.planId() + ":" + target, "step:" + plan.planId() + ":" + target,
                NOW.plusSeconds(1)));
    }

    /** 先真实结算 Tool，再用当前 Plan revision 的 claim 写入 evidence，避免测试直接伪造成功状态。 */
    private static Plan settlePlanStepWithEvidence(Fixture fixture, Plan executing, String suffix) throws Exception {
        String turnId = "turn_plan_" + suffix;
        String callId = "call_plan_" + suffix;
        fixture.insertClaimedToolCall(executing, turnId, callId);
        ToolAttempt prepared = fixture.repository.prepareToolAttempt(new PlanRepository.PrepareToolAttempt(
                new ToolAttempt("attempt_plan_" + suffix, executing.planId(),
                        executing.activeRunId(), executing.activePlanRevisionId(), "step_work", 1, turnId, callId,
                        1, false, ToolAttemptState.PREPARED, "b".repeat(64), null, NOW, null, null)));
        fixture.repository.startToolAttempt(prepared.toolAttemptId(), NOW.plusSeconds(1));
        fixture.repository.settleToolAttempt(new PlanRepository.SettleToolAttempt(prepared.toolAttemptId(),
                ToolAttemptState.SUCCEEDED, "c".repeat(64), null, NOW.plusSeconds(2)));
        Plan running = movePlanStep(fixture.repository, executing, StepStatus.READY, StepStatus.RUNNING, List.of());
        return movePlanStep(fixture.repository, running, StepStatus.RUNNING, StepStatus.SUCCEEDED,
                List.of(new PlanRepository.ToolEvidenceClaim("evidence_plan_" + suffix,
                        "criterion_" + suffix, callId, "当前 Tool 结果", NOW.plusSeconds(2))));
    }

    /** 通过真实 prepare/start/settle 路径留下 UNKNOWN Tool，完成门必须把它作为不可恢复阻断。 */
    private static void settleUnknownTool(Fixture fixture, Plan plan, String turnId, String suffix) throws Exception {
        String callId = "call_unknown_" + suffix;
        fixture.insertToolCall(turnId, callId, 1);
        ToolAttempt prepared = fixture.repository.prepareToolAttempt(new PlanRepository.PrepareToolAttempt(
                new ToolAttempt("attempt_unknown_" + suffix, plan.planId(), plan.activeRunId(),
                        plan.activePlanRevisionId(),
                        "step_work", 1, turnId, callId, 1, true, ToolAttemptState.PREPARED,
                        "d".repeat(64), null, NOW, null, null)));
        fixture.repository.startToolAttempt(prepared.toolAttemptId(), NOW.plusSeconds(1));
        fixture.repository.settleToolAttempt(new PlanRepository.SettleToolAttempt(prepared.toolAttemptId(),
                ToolAttemptState.UNKNOWN, null, null, NOW.plusSeconds(2)));
    }

    /** PlanDefinition 保持一个必要步骤和必要 criterion，确保每个完成门 blocker 都能单独观测。 */
    private static PlanDefinition planDefinition(String suffix) {
        return new PlanDefinition("完成门测试 " + suffix, List.of("当前范围"), List.of(), List.of(), List.of(),
                List.of(new PlanStep("step_work", "实施", "完成当前实现", true, List.of())),
                List.of(new AcceptanceCriterion("criterion_" + suffix, "当前计划验收", true)), List.of(),
                List.of("运行真实 SQLite 验证"));
    }

    /** 创建 fresh SQLite、V1 schema 和显式 commit/rollback owner，不接触用户数据库。 */
    private Fixture fixture(String name) throws Exception {
        JaDatabase database = JaDatabase.open(new DatabaseConfig(temp.resolve(name + ".sqlite3"),
                DatabaseConfig.DEFAULT_BUSY_TIMEOUT, temp));
        Configuration configuration = new Configuration(new Environment("completion-gate-test",
                new JdbcTransactionFactory(), database.dataSource()));
        configuration.setMapUnderscoreToCamelCase(false);
        configuration.addMapper(SchemaMapper.class);
        configuration.addMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.PlanMapper.class);
        SqlSessionFactory sessions = new SqlSessionFactoryBuilder().build(configuration);
        database.bindWalCheckpoint(sessions);
        long now = NOW.toEpochMilli();
        try (SqlSession session = sessions.openSession()) {
            try (PreparedStatement insert = session.getConnection().prepareStatement("""
                    INSERT INTO ja_workspace(workspace_id,root_path,kind,display_name,trust,revision,
                    created_at_ms,updated_at_ms) VALUES('ws_completion_gate','C:/completion-gate','PROJECT',
                    'Completion gate','TRUSTED',0,?,?)
                    """)) {
                insert.setLong(1, now);
                insert.setLong(2, now);
                insert.executeUpdate();
            }
            try (PreparedStatement insert = session.getConnection().prepareStatement("""
                    INSERT INTO ja_conversation_thread(thread_id,owner_user_id,workspace_id,title,revision,
                    created_at_ms,updated_at_ms,provider_id,model_id,access_mode,title_source,collaboration_mode)
                    VALUES('thr_completion_gate','user_local','ws_completion_gate','Completion gate',0,?,?,
                    'provider_test','model_test','APPROVAL_REQUIRED','MANUAL','PLAN')
                    """)) {
                insert.setLong(1, now);
                insert.setLong(2, now);
                insert.executeUpdate();
            }
            session.commit();
        }
        PlanUnitOfWork.SessionOwner owner = new PlanUnitOfWork.SessionOwner() {
            /** 测试 owner 保持 repository 的真实事务边界，仅替换生产 Solon 提交桥。 */
            @Override
            public <T> T execute(SqlSessionFactory factory, PlanUnitOfWork.Work<T> work) {
                try (SqlSession session = factory.openSession()) {
                    try {
                        T value = work.apply(new PlanUnitOfWork.Mappers(
                                session.getMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.PlanMapper.class)));
                        session.commit();
                        return value;
                    } catch (Throwable failure) {
                        session.rollback();
                        if (failure instanceof RuntimeException runtime) {
                            throw runtime;
                        }
                        throw new IllegalStateException("completion gate transaction failed", failure);
                    }
                }
            }
        };
        return new Fixture(database, sessions, new MybatisPlanRepository(sessions, new ObjectMapper(), owner));
    }

    /**
     * fresh fixture 持有真实数据库 lease，关闭后不泄漏 WAL 句柄到其它用例。
     *
     * @param database 数据库
     * @param sessions 会话集合
     * @param repository 仓储
     */
    private record Fixture(JaDatabase database,
                           SqlSessionFactory sessions,
                           MybatisPlanRepository repository) implements AutoCloseable {
        /** 领取真实 Plan Turn 后建立 owner 一致的 Turn 与首个 ToolCall。 */
        private void insertClaimedToolCall(Plan plan, String turnId, String callId) throws Exception {
            repository.claimPlanTurn(new PlanRepository.ClaimPlanTurn(plan.planId(), plan.revision(),
                    plan.activeRunId(), plan.activePlanRevisionId(), turnId, "event_claim_" + turnId,
                    "claim:" + turnId, NOW)).orElseThrow();
            insertTurn(turnId);
            insertToolCall(turnId, callId, 0);
        }

        /** 同一 Plan Turn 可包含多个 Tool 调用；Turn 的 owner 与 claim 先由生产规则绑定。 */
        private void insertTurn(String turnId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement insert = session.getConnection().prepareStatement("""
                         INSERT INTO ja_conversation_turn(turn_id,thread_id,state,requested_at_ms,updated_at_ms,
                         completed_at_ms,terminal_summary,parent_turn_id,root_turn_id)
                         VALUES(?,'thr_completion_gate','RUNNING',?,?,NULL,NULL,NULL,NULL)
                         """)) {
                insert.setString(1, turnId);
                insert.setLong(2, NOW.toEpochMilli());
                insert.setLong(3, NOW.toEpochMilli());
                insert.executeUpdate();
                session.commit();
            }
        }

        /** ToolCall 只引用已存在且属于当前 Plan owner Thread 的 Turn。 */
        private void insertToolCall(String turnId, String callId, int ordinal) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement insert = session.getConnection().prepareStatement("""
                         INSERT INTO ja_tool_call(call_id,thread_id,turn_id,ordinal,tool_name,side_effect,
                         presentation_json,state,revision,created_at_ms,updated_at_ms)
                         VALUES(?,'thr_completion_gate',?,?,'shell','EXTERNAL','{}','PREPARED',0,?,?)
                         """)) {
                insert.setString(1, callId);
                insert.setString(2, turnId);
                insert.setInt(3, ordinal);
                insert.setLong(4, NOW.toEpochMilli());
                insert.setLong(5, NOW.toEpochMilli());
                insert.executeUpdate();
                session.commit();
            }
        }

        /** 写入仅属于旧 revision 的 evidence，验证 blocker 查询不会按 criterion 文案或 run 猜测复用。 */
        private void insertPlanEvidence(String evidenceId, String planId, String runId, String revisionId,
                                         String criterionId, String stepId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement sql = session.getConnection().prepareStatement(
                         """
                             INSERT INTO ja_plan_acceptance_evidence(evidence_id,plan_id,run_id,plan_revision_id,\
                             criterion_id,step_id,source_type,source_id,summary,digest,observed_at_ms,created_at_ms) \
                             VALUES(?,?,?,?,?,?,?,?,?,?,?,?)\
                             """)) {
                sql.setString(1, evidenceId);
                sql.setString(2, planId);
                sql.setString(3, runId);
                sql.setString(4, revisionId);
                sql.setString(5, criterionId);
                sql.setString(6, stepId);
                sql.setString(7, EvidenceSource.TEST_REPORT.name());
                sql.setString(8, "old-report");
                sql.setString(9, "旧 revision 报告");
                sql.setString(10, "e".repeat(64));
                sql.setLong(11, NOW.toEpochMilli());
                sql.setLong(12, NOW.toEpochMilli());
                sql.executeUpdate();
                session.commit();
            }
        }

        /** 把已完成 Plan 的 event 水位作为幂等断言，不依赖内存事件或回调次数。 */
        private int planEventCount(String planId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement sql = session.getConnection().prepareStatement(
                         "SELECT COUNT(*) FROM ja_plan_event WHERE plan_id=?")) {
                sql.setString(1, planId);
                try (java.sql.ResultSet rows = sql.executeQuery()) {
                    if (!rows.next()) {
                        throw new AssertionError("missing plan event count");
                    }
                    return rows.getInt(1);
                }
            }
        }

        /** 直接读取 execution_runs 的终态，证明 Plan 与 Run 在同一 completion transaction 收口。 */
        private String runStatus(String runId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement sql = session.getConnection().prepareStatement(
                         "SELECT status FROM ja_plan_execution WHERE run_id=?")) {
                sql.setString(1, runId);
                try (java.sql.ResultSet rows = sql.executeQuery()) {
                    if (!rows.next()) {
                        throw new AssertionError("missing execution run");
                    }
                    return rows.getString(1);
                }
            }
        }

        /** 从真实 SQLite 回读 pause_requested，确认验收结算没有清除此 fence。 */
        private int pauseRequested(String runId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement sql = session.getConnection().prepareStatement(
                         "SELECT is_pause_requested FROM ja_plan_execution WHERE run_id=?")) {
                sql.setString(1, runId);
                try (java.sql.ResultSet rows = sql.executeQuery()) {
                    if (!rows.next()) {
                        throw new AssertionError("missing execution run");
                    }
                    return rows.getInt(1);
                }
            }
        }

        /** 关闭 fresh 数据库并让 JaDatabase 执行自身的 WAL 清理。 */
        @Override
        public void close() {
            database.close();
        }
    }

    /**
     * 两个冻结 revision 与当前执行投影的最小测试载体。
     *
     * @param firstRevision 首个修订版本
     * @param currentRevision 当前修订版本
     * @param currentPlan 当前 Plan
     */
    private record RevisionPlan(PlanRevision firstRevision,
                                PlanRevision currentRevision,
                                Plan currentPlan) {
        /** 构造体保证测试不能误把旧 revision 当作当前执行版本。 */
        private RevisionPlan {
            assertTrue(firstRevision.revisionNumber() < currentRevision.revisionNumber());
            assertEquals(currentRevision.planRevisionId(), currentPlan.activePlanRevisionId());
        }
    }
}



