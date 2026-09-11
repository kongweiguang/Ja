// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.goal.domain.CanonicalPlanJson;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.domain.GoalModels.AcceptanceCriterion;
import io.github.kongweiguang.ja.goal.domain.GoalModels.Evidence;
import io.github.kongweiguang.ja.goal.domain.GoalModels.EvidenceSource;
import io.github.kongweiguang.ja.goal.domain.GoalModels.Goal;
import io.github.kongweiguang.ja.goal.domain.GoalModels.Plan;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanDefinition;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanRevision;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanStatus;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanStep;
import io.github.kongweiguang.ja.goal.domain.GoalModels.StepStatus;
import io.github.kongweiguang.ja.goal.domain.GoalModels.ToolAttempt;
import io.github.kongweiguang.ja.goal.domain.GoalModels.ToolAttemptState;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;
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
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实 V1 SQLite 锁定 Plan/Goal 完成门的证据、未知副作用和幂等语义。 */
final class MybatisGoalRepositoryCompletionGateTest {
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

            Plan afterAttempt = fixture.repository.beginPlanVerification(new GoalRepository.BeginPlanVerification(
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
            Plan verifying = fixture.repository.beginPlanVerification(new GoalRepository.BeginPlanVerification(
                    succeeded.planId(), succeeded.revision(), succeeded.activeRunId(), "event_unknown_verification",
                    "plan:unknown:verification", NOW.plusSeconds(3)));
            assertEquals(PlanStatus.VERIFYING, verifying.status());

            settleUnknownTool(fixture, null, verifying.planId(), null, verifying.activeRunId(),
                    verifying.activePlanRevisionId(), "unknown");

            GoalRepositoryException failure = assertThrows(GoalRepositoryException.class, () ->
                    fixture.repository.completePlanVerification(new GoalRepository.CompletePlanVerification(
                            verifying.planId(), verifying.revision(), verifying.activeRunId(),
                            GoalModels.EvaluationVerdict.MET, "验收通过", "event_unknown_complete",
                            "plan:unknown:complete", NOW.plusSeconds(4))));
            assertEquals(GoalRepositoryException.Code.GOAL_EVIDENCE_INCOMPLETE, failure.code());
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
            fixture.insertPlanEvidence("evidence_old_revision", succeeded.activeRunId(),
                    plan.firstRevision().planRevisionId(), "criterion_old", "step_work");

            Plan afterAttempt = fixture.repository.beginPlanVerification(new GoalRepository.BeginPlanVerification(
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
            Plan verifying = fixture.repository.beginPlanVerification(new GoalRepository.BeginPlanVerification(
                    succeeded.planId(), succeeded.revision(), succeeded.activeRunId(), "event_complete_verification",
                    "plan:complete:verification", NOW.plusSeconds(3)));
            int eventsBeforeCompletion = fixture.planEventCount(verifying.planId());
            GoalRepository.CompletePlanVerification command = new GoalRepository.CompletePlanVerification(
                    verifying.planId(), verifying.revision(), verifying.activeRunId(),
                    GoalModels.EvaluationVerdict.MET, "验收通过", "event_complete", "plan:complete", NOW.plusSeconds(4));

            Plan completed = fixture.repository.completePlanVerification(command);
            Plan replayed = fixture.repository.completePlanVerification(command);

            assertEquals(PlanStatus.COMPLETED, completed.status());
            assertEquals(completed, replayed);
            assertEquals(eventsBeforeCompletion + 1, fixture.planEventCount(completed.planId()));
            assertEquals("COMPLETED", fixture.runStatus(completed.activeRunId()));
        }
    }

    /** Goal-only 完成门同样拒绝 UNKNOWN Tool，确保独立 Goal 不因没有 Plan link 而放宽安全边界。 */
    @Test
    void goalCompletionRejectsUnknownTool() throws Exception {
        try (Fixture fixture = fixture("goal-unknown-tool")) {
            Goal created = createGoal(fixture.repository, "goal_unknown", "run_goal_unknown", "unknown");
            fixture.repository.appendEvidence(new GoalRepository.AppendEvidence(
                    created.goalId(), created.revision(), goalEvidence("unknown", created.activeRunId()),
                    "event_goal_unknown_evidence", "goal:unknown:evidence", NOW));
            Goal evidenced = fixture.repository.findGoal(created.goalId()).orElseThrow();
            Goal verifying = fixture.repository.requestEvaluation(new GoalRepository.RequestEvaluation(
                    evidenced.goalId(), evidenced.revision(), "evaluation_goal_unknown", evidenced.activeRunId(), null,
                    1, List.of(), "event_goal_unknown_request", "goal:unknown:request", NOW.plusSeconds(1)));
            Goal evaluated = fixture.repository.completeEvaluation(new GoalRepository.CompleteEvaluation(
                    verifying.goalId(), verifying.revision(), "evaluation_goal_unknown",
                    GoalModels.EvaluationVerdict.MET,
                    "[{\"criterionId\":\"criterion_goal_unknown\",\"verdict\":\"MET\",\"reason\":\"证据已存在\"}]",
                    "Goal evaluator 通过", null, "event_goal_unknown_complete", "goal:unknown:complete",
                    NOW.plusSeconds(2)));
            settleUnknownTool(fixture, evaluated.goalId(), null, 1L, evaluated.activeRunId(), null, "goal");

            GoalRepositoryException failure = assertThrows(GoalRepositoryException.class, () ->
                    fixture.repository.transition(new GoalRepository.Transition(evaluated.goalId(), evaluated.revision(),
                            GoalModels.GoalStatus.ACHIEVED, GoalModels.GoalPhase.ACHIEVED, false,
                            "event_goal_unknown_achieved", "goal:unknown:achieved", NOW.plusSeconds(3))));
            assertEquals(GoalRepositoryException.Code.GOAL_EVIDENCE_INCOMPLETE, failure.code());
            assertEquals(GoalModels.GoalStatus.ACTIVE, fixture.repository.findGoal(evaluated.goalId()).orElseThrow().status());
        }
    }

    /** Goal definition revision 发生变化后，旧 revision evidence 不得替代当前定义的必要证据。 */
    @Test
    void goalCompletionIgnoresEvidenceFromPreviousDefinitionRevision() throws Exception {
        try (Fixture fixture = fixture("goal-old-revision-evidence")) {
            Goal created = createGoal(fixture.repository, "goal_old", "run_goal_old", "old");
            fixture.repository.appendEvidence(new GoalRepository.AppendEvidence(
                    created.goalId(), created.revision(), goalEvidence("old", created.activeRunId()),
                    "event_goal_old_evidence", "goal:old:evidence", NOW));
            Goal evidenced = fixture.repository.findGoal(created.goalId()).orElseThrow();
            Goal verifying = fixture.repository.requestEvaluation(new GoalRepository.RequestEvaluation(
                    evidenced.goalId(), evidenced.revision(), "evaluation_goal_old_v1", evidenced.activeRunId(), null,
                    1, List.of(), "event_goal_old_request_v1", "goal:old:request:v1", NOW.plusSeconds(1)));
            Goal evaluated = fixture.repository.completeEvaluation(new GoalRepository.CompleteEvaluation(
                    verifying.goalId(), verifying.revision(), "evaluation_goal_old_v1",
                    GoalModels.EvaluationVerdict.MET,
                    "[{\"criterionId\":\"criterion_goal_old\",\"verdict\":\"MET\",\"reason\":\"旧版本证据\"}]",
                    "旧版本 evaluator 通过", null, "event_goal_old_complete_v1", "goal:old:complete:v1",
                    NOW.plusSeconds(2)));
            fixture.replaceGoalDefinitionWithCurrentRevision(evaluated.goalId(), 2L, "criterion_goal_current");
            Goal currentVerifying = fixture.repository.requestEvaluation(new GoalRepository.RequestEvaluation(
                    evaluated.goalId(), evaluated.revision(), "evaluation_goal_old_v2", evaluated.activeRunId(), null,
                    1, List.of(), "event_goal_old_request_v2", "goal:old:request:v2", NOW.plusSeconds(3)));
            Goal currentEvaluated = fixture.repository.completeEvaluation(new GoalRepository.CompleteEvaluation(
                    currentVerifying.goalId(), currentVerifying.revision(), "evaluation_goal_old_v2",
                    GoalModels.EvaluationVerdict.MET,
                    "[{\"criterionId\":\"criterion_goal_current\",\"verdict\":\"MET\",\"reason\":\"当前判断\"}]",
                    "当前 evaluator 通过", null, "event_goal_old_complete_v2", "goal:old:complete:v2",
                    NOW.plusSeconds(4)));

            GoalRepositoryException failure = assertThrows(GoalRepositoryException.class, () ->
                    fixture.repository.transition(new GoalRepository.Transition(currentEvaluated.goalId(),
                            currentEvaluated.revision(), GoalModels.GoalStatus.ACHIEVED,
                            GoalModels.GoalPhase.ACHIEVED, false, "event_goal_old_achieved",
                            "goal:old:achieved", NOW.plusSeconds(5))));
            assertEquals(GoalRepositoryException.Code.GOAL_EVIDENCE_INCOMPLETE, failure.code());
        }
    }

    /** 创建真实 Plan revision、Run 与步骤执行投影，保证完成门测试经过公开 repository mutation。 */
    private static Plan createRunningPlan(MybatisGoalRepository repository, String planId, String runId,
                                          String suffix) {
        Plan created = repository.createPlan(new GoalRepository.CreatePlan(
                planId, "thr_completion_gate", "完成门测试", 0, "create:" + planId, NOW));
        PlanDefinition definition = planDefinition(suffix);
        CanonicalPlanJson.Encoded encoded = new CanonicalPlanJson(new ObjectMapper()).encode(definition);
        PlanRevision proposed = repository.propose(new GoalRepository.ProposePlan(
                planId, created.revision(), new PlanRevision("revision_" + suffix, planId, 1,
                definition, encoded.json(), encoded.sha256(), "AGENT", NOW),
                "event_propose_" + suffix, "propose:" + suffix, NOW));
        return repository.executePlan(new GoalRepository.ExecutePlan(planId, 1,
                proposed.planRevisionId(), proposed.planHash(), "approval:" + suffix, runId, 1,
                "event_execute_" + suffix, "execute:" + suffix, NOW, 20, 20, 120_000L, 8));
    }

    /** 创建两个不可变 Plan revision 并执行第二版，用旧版证据验证 revision 精确匹配。 */
    private static RevisionPlan createTwoRevisionPlan(MybatisGoalRepository repository, String planId, String runId) {
        Plan created = repository.createPlan(new GoalRepository.CreatePlan(
                planId, "thr_completion_gate", "多版本完成门测试", 0, "create:" + planId, NOW));
        ObjectMapper mapper = new ObjectMapper();
        PlanDefinition firstDefinition = planDefinition("old");
        CanonicalPlanJson.Encoded firstEncoded = new CanonicalPlanJson(mapper).encode(firstDefinition);
        PlanRevision first = repository.propose(new GoalRepository.ProposePlan(planId, created.revision(),
                new PlanRevision("revision_old", planId, 1, firstDefinition, firstEncoded.json(),
                        firstEncoded.sha256(), "AGENT", NOW), "event_propose_old", "propose:old", NOW));
        Plan rejected = repository.reject(new GoalRepository.RejectPlan(planId, 1, first.planRevisionId(),
                first.planHash(), "approval_old", "旧版测试", "event_reject_old", "reject:old", NOW.plusSeconds(1)));
        PlanDefinition currentDefinition = planDefinition("current");
        CanonicalPlanJson.Encoded currentEncoded = new CanonicalPlanJson(mapper).encode(currentDefinition);
        PlanRevision current = repository.propose(new GoalRepository.ProposePlan(planId, rejected.revision(),
                new PlanRevision("revision_current", planId, 2, currentDefinition, currentEncoded.json(),
                        currentEncoded.sha256(), "AGENT", NOW.plusSeconds(2)), "event_propose_current",
                "propose:current", NOW.plusSeconds(2)));
        Plan running = repository.executePlan(new GoalRepository.ExecutePlan(planId, rejected.revision() + 1,
                current.planRevisionId(), current.planHash(), "approval_current", runId, 1,
                "event_execute_current", "execute:current", NOW.plusSeconds(3), 20, 20, 120_000L, 8));
        return new RevisionPlan(first, current, running);
    }

    /** 仅推进步骤状态；证据 claim 是否存在由调用方显式控制，便于隔离完成门条件。 */
    private static Plan movePlanStep(MybatisGoalRepository repository, Plan plan, StepStatus expected,
                                     StepStatus target, List<GoalRepository.ToolEvidenceClaim> claims) {
        return repository.updatePlanStep(new GoalRepository.UpdatePlanStep(plan.planId(), plan.revision(),
                plan.activeRunId(), "step_work", expected, target, null, claims,
                "event_step_" + plan.planId() + ":" + target, "step:" + plan.planId() + ":" + target,
                NOW.plusSeconds(1)));
    }

    /** 先真实结算 Tool，再用当前 Plan revision 的 claim 写入 evidence，避免测试直接伪造成功状态。 */
    private static Plan settlePlanStepWithEvidence(Fixture fixture, Plan executing, String suffix) throws Exception {
        String turnId = "turn_plan_" + suffix;
        String callId = "call_plan_" + suffix;
        fixture.insertToolCall(turnId, callId);
        ToolAttempt prepared = fixture.repository.prepareToolAttempt(new GoalRepository.PrepareToolAttempt(
                new ToolAttempt("attempt_plan_" + suffix, null, executing.planId(), null,
                        executing.activeRunId(), executing.activePlanRevisionId(), "step_work", 1, turnId, callId,
                        1, false, ToolAttemptState.PREPARED, "b".repeat(64), null, NOW, null, null)));
        fixture.repository.startToolAttempt(prepared.toolAttemptId(), NOW.plusSeconds(1));
        fixture.repository.settleToolAttempt(new GoalRepository.SettleToolAttempt(prepared.toolAttemptId(),
                ToolAttemptState.SUCCEEDED, "c".repeat(64), null, NOW.plusSeconds(2)));
        Plan running = movePlanStep(fixture.repository, executing, StepStatus.READY, StepStatus.RUNNING, List.of());
        return movePlanStep(fixture.repository, running, StepStatus.RUNNING, StepStatus.SUCCEEDED,
                List.of(new GoalRepository.ToolEvidenceClaim("evidence_plan_" + suffix,
                        "criterion_" + suffix, callId, "当前 Tool 结果", NOW.plusSeconds(2))));
    }

    /** 通过真实 prepare/start/settle 路径留下 UNKNOWN Tool，完成门必须把它作为不可恢复阻断。 */
    private static void settleUnknownTool(Fixture fixture, String goalId, String planId,
                                          Long definitionRevision, String runId, String planRevisionId,
                                          String suffix) throws Exception {
        String turnId = "turn_unknown_" + suffix;
        String callId = "call_unknown_" + suffix;
        fixture.insertToolCall(turnId, callId);
        ToolAttempt prepared = fixture.repository.prepareToolAttempt(new GoalRepository.PrepareToolAttempt(
                new ToolAttempt("attempt_unknown_" + suffix, goalId, planId, definitionRevision, runId,
                        planRevisionId, null, 1, turnId, callId, 1, true, ToolAttemptState.PREPARED,
                        "d".repeat(64), null, NOW, null, null)));
        fixture.repository.startToolAttempt(prepared.toolAttemptId(), NOW.plusSeconds(1));
        fixture.repository.settleToolAttempt(new GoalRepository.SettleToolAttempt(prepared.toolAttemptId(),
                ToolAttemptState.UNKNOWN, null, null, NOW.plusSeconds(2)));
    }

    /** 创建 Goal-only run 并冻结其初始定义，供 Goal 完成门测试复用。 */
    private static Goal createGoal(MybatisGoalRepository repository, String goalId, String runId, String suffix) {
        return repository.create(new GoalRepository.CreateGoal(goalId, "thr_completion_gate",
                GoalModels.OwnerKind.ROOT_THREAD, "Goal 完成门测试",
                List.of(new AcceptanceCriterion("criterion_goal_" + suffix, "Goal 当前验收", true)), runId,
                1, 0, "create:" + goalId, NOW));
    }

    /** Goal evidence 绑定初始 definition/run；后续测试会把 definition revision 替换为新版本。 */
    private static Evidence goalEvidence(String suffix, String runId) {
        return new Evidence("evidence_goal_" + suffix, "goal_" + suffix, null, 1L, runId, null,
                "criterion_goal_" + suffix, null, EvidenceSource.TEST_REPORT, "report_" + suffix,
                "旧版本验收报告", "a".repeat(64), NOW, NOW);
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
                DatabaseConfig.DEFAULT_BUSY_TIMEOUT));
        Configuration configuration = new Configuration(new Environment("completion-gate-test",
                new JdbcTransactionFactory(), database.dataSource()));
        configuration.setMapUnderscoreToCamelCase(false);
        configuration.addMapper(SchemaMapper.class);
        SqlSessionFactory sessions = new SqlSessionFactoryBuilder().build(configuration);
        database.bindWalCheckpoint(sessions);
        try (SqlSession session = sessions.openSession(); Statement sql = session.getConnection().createStatement()) {
            sql.executeUpdate("INSERT INTO workspaces(workspace_id,root_path,display_name,trust,revision,created_at,updated_at) "
                    + "VALUES('ws_completion_gate','C:/completion-gate','Completion gate','TRUSTED',0,"
                    + "'2026-09-04T10:00:00Z','2026-09-04T10:00:00Z')");
            sql.executeUpdate("INSERT INTO threads(thread_id,workspace_id,title,revision,created_at,updated_at,"
                    + "provider_id,model_id,access_mode,title_source,collaboration_mode) VALUES("
                    + "'thr_completion_gate','ws_completion_gate','Completion gate',0,"
                    + "'2026-09-04T10:00:00Z','2026-09-04T10:00:00Z','provider_test','model_test',"
                    + "'APPROVAL_REQUIRED','MANUAL','PLAN')");
            session.commit();
        }
        GoalUnitOfWork.SessionOwner owner = new GoalUnitOfWork.SessionOwner() {
            /** 测试 owner 保持 repository 的真实事务边界，仅替换生产 Solon 提交桥。 */
            @Override
            public <T> T execute(SqlSessionFactory factory, GoalUnitOfWork.Work<T> work) {
                try (SqlSession session = factory.openSession()) {
                    try {
                        T value = work.apply(session.getConnection());
                        session.commit();
                        return value;
                    } catch (Throwable failure) {
                        session.rollback();
                        if (failure instanceof RuntimeException runtime) throw runtime;
                        throw new IllegalStateException("completion gate transaction failed", failure);
                    }
                }
            }
        };
        return new Fixture(database, sessions, new MybatisGoalRepository(sessions, new ObjectMapper(), owner));
    }

    /** fresh fixture 持有真实数据库 lease，关闭后不泄漏 WAL 句柄到其它用例。 */
    private record Fixture(JaDatabase database, SqlSessionFactory sessions,
                           MybatisGoalRepository repository) implements AutoCloseable {
        /** 插入已存在的 Turn/Tool identity，repository 随后负责真实 attempt 状态迁移。 */
        private void insertToolCall(String turnId, String callId) throws Exception {
            try (SqlSession session = sessions.openSession(); Statement sql = session.getConnection().createStatement()) {
                sql.executeUpdate("INSERT INTO turns(turn_id,thread_id,state,requested_at,updated_at,completed_at,"
                        + "terminal_summary,parent_turn_id,root_turn_id) VALUES('" + turnId
                        + "','thr_completion_gate','COMPLETED','" + NOW + "','" + NOW + "','" + NOW
                        + "','completed',NULL,NULL)");
                sql.executeUpdate("INSERT INTO tools(call_id,thread_id,turn_id,ordinal,tool_name,side_effect,"
                        + "presentation_json,state,revision,created_at,updated_at) VALUES('" + callId
                        + "','thr_completion_gate','" + turnId + "',0,'shell','EXTERNAL','{}','PREPARED',0,'"
                        + NOW + "','" + NOW + "')");
                session.commit();
            }
        }

        /** 写入仅属于旧 revision 的 evidence，验证 blocker 查询不会按 criterion 文案或 run 猜测复用。 */
        private void insertPlanEvidence(String evidenceId, String runId, String revisionId,
                                         String criterionId, String stepId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement sql = session.getConnection().prepareStatement(
                         "INSERT INTO acceptance_evidence(evidence_id,goal_id,plan_id,goal_definition_revision,"
                                 + "run_id,plan_revision_id,criterion_id,step_id,source_type,source_id,summary,digest,"
                                 + "observed_at,created_at) VALUES(?,NULL,?,NULL,?,?,?,?,?,?,?,?,?,?)")) {
                sql.setString(1, evidenceId);
                sql.setString(2, "plan_old");
                sql.setString(3, runId);
                sql.setString(4, revisionId);
                sql.setString(5, criterionId);
                sql.setString(6, stepId);
                sql.setString(7, EvidenceSource.TEST_REPORT.name());
                sql.setString(8, "old-report");
                sql.setString(9, "旧 revision 报告");
                sql.setString(10, "e".repeat(64));
                sql.setString(11, NOW.toString());
                sql.setString(12, NOW.toString());
                sql.executeUpdate();
                session.commit();
            }
        }

        /** 把已完成 Plan 的 event 水位作为幂等断言，不依赖内存事件或回调次数。 */
        private int planEventCount(String planId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement sql = session.getConnection().prepareStatement(
                         "SELECT COUNT(*) FROM plan_events WHERE plan_id=?")) {
                sql.setString(1, planId);
                try (var rows = sql.executeQuery()) {
                    if (!rows.next()) throw new AssertionError("missing plan event count");
                    return rows.getInt(1);
                }
            }
        }

        /** 直接读取 execution_runs 的终态，证明 Plan 与 Run 在同一 completion transaction 收口。 */
        private String runStatus(String runId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement sql = session.getConnection().prepareStatement(
                         "SELECT status FROM execution_runs WHERE run_id=?")) {
                sql.setString(1, runId);
                try (var rows = sql.executeQuery()) {
                    if (!rows.next()) throw new AssertionError("missing execution run");
                    return rows.getString(1);
                }
            }
        }

        /** 构造第二个 Goal definition revision，并只保留旧 revision evidence 供完成门拒绝。 */
        private void replaceGoalDefinitionWithCurrentRevision(String goalId, long revision,
                                                               String criterionId) throws Exception {
            try (SqlSession session = sessions.openSession()) {
                try (PreparedStatement definition = session.getConnection().prepareStatement(
                        "INSERT INTO goal_definition_revisions(goal_id,revision_number,objective,created_at) "
                                + "VALUES(?,?,?,?)")) {
                    definition.setString(1, goalId);
                    definition.setLong(2, revision);
                    definition.setString(3, "当前 Goal 定义");
                    definition.setString(4, NOW.plusSeconds(3).toString());
                    definition.executeUpdate();
                }
                try (PreparedStatement criterion = session.getConnection().prepareStatement(
                        "INSERT INTO goal_acceptance_criteria(goal_id,goal_definition_revision,criterion_id,"
                                + "ordinal,description,required) VALUES(?,?,?,?,?,1)")) {
                    criterion.setString(1, goalId);
                    criterion.setLong(2, revision);
                    criterion.setString(3, criterionId);
                    criterion.setInt(4, 0);
                    criterion.setString(5, "当前 Goal 验收");
                    criterion.executeUpdate();
                }
                try (PreparedStatement goal = session.getConnection().prepareStatement(
                        "UPDATE goals SET goal_definition_revision=? WHERE goal_id=?")) {
                    goal.setLong(1, revision);
                    goal.setString(2, goalId);
                    goal.executeUpdate();
                }
                session.commit();
            }
        }

        /** 关闭 fresh 数据库并让 JaDatabase 执行自身的 WAL 清理。 */
        @Override
        public void close() {
            database.close();
        }
    }

    /** 两个冻结 revision 与当前执行投影的最小测试载体。 */
    private record RevisionPlan(PlanRevision firstRevision, PlanRevision currentRevision, Plan currentPlan) {
        /** 构造体保证测试不能误把旧 revision 当作当前执行版本。 */
        private RevisionPlan {
            assertTrue(firstRevision.revisionNumber() < currentRevision.revisionNumber());
            assertEquals(currentRevision.planRevisionId(), currentPlan.activePlanRevisionId());
        }
    }
}
