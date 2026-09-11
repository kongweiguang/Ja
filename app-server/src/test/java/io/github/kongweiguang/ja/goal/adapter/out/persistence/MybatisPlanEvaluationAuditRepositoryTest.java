// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestUsage;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.goal.application.PlanEvaluationAuditPort;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.infrastructure.persistence.database.DatabaseConfig;
import io.github.kongweiguang.ja.infrastructure.persistence.database.JaDatabase;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 使用真实 Ja SQLite 验证 Plan evaluator 审计的幂等、计量和未知结果持久化。 */
final class MybatisPlanEvaluationAuditRepositoryTest {
    private static final Instant START = Instant.parse("2026-09-10T08:00:00Z");

    @TempDir
    Path temp;

    /** 已知计量只能结算一次；相同输入重放不得重复付费，输入变化可以产生新 intent。 */
    @Test
    void persistsKnownUsageAndSeparatesChangedInputIdentity() throws Exception {
        try (Fixture fixture = fixture("known-usage")) {
            MybatisPlanEvaluationAuditRepository repository = fixture.repository();
            ProviderRequestProfile profile = profile();
            PlanEvaluationAuditPort.Intent first = intent("request_plan_eval_a", "a".repeat(64), profile);

            repository.recordIntent(first);
            assertEquals(Optional.of(new PlanEvaluationAuditPort.Prior(
                    first.requestId(), PlanEvaluationAuditPort.Outcome.RUNNING)), repository.find(first.requestId()));

            ProviderRequestUsage known = new ProviderRequestUsage(first.requestId(), 1, 1,
                    ProviderRequestUsage.Purpose.ASSISTANT, ProviderRequestUsage.Certainty.KNOWN,
                    profile, new ModelUsage(11, 7, 18));
            repository.recordUsage(new PlanEvaluationAuditPort.Usage(first.requestId(), first.planId(),
                    first.planRevisionId(), first.runId(), known, PlanEvaluationAuditPort.Outcome.SUCCEEDED,
                    START.plusSeconds(1), new PlanEvaluationAuditPort.EvaluationResult(
                            GoalModels.EvaluationVerdict.MET, java.util.List.of(
                            new GoalModels.CriterionEvaluation("criterion_test",
                                    GoalModels.EvaluationVerdict.MET, "通过")), "通过")));
            PlanEvaluationAuditPort.Prior persisted = repository.find(first.requestId()).orElseThrow();
            assertEquals(PlanEvaluationAuditPort.Outcome.SUCCEEDED, persisted.outcome());
            assertEquals(GoalModels.EvaluationVerdict.MET, persisted.evaluation().verdict());
            assertEquals("通过", persisted.evaluation().summary());
            assertEquals(1, persisted.evaluation().criteria().size());
            assertEquals("1/1000", fixture.runUsage(),
                    "evaluator intent reserves one model round and settlement counts active time");

            // SQLite 把唯一键冲突作为 MyBatis PersistenceException，仍属于不可重试的运行时失败。
            assertThrows(RuntimeException.class, () -> repository.recordIntent(first));
            assertThrows(IllegalStateException.class, () -> repository.recordUsage(
                    new PlanEvaluationAuditPort.Usage(first.requestId(), first.planId(), first.planRevisionId(),
                            first.runId(), known, PlanEvaluationAuditPort.Outcome.SUCCEEDED, START.plusSeconds(2),
                            new PlanEvaluationAuditPort.EvaluationResult(GoalModels.EvaluationVerdict.MET,
                                    java.util.List.of(new GoalModels.CriterionEvaluation("criterion_test",
                                            GoalModels.EvaluationVerdict.MET, "通过")), "通过"))));
            assertEquals("1/1000", fixture.runUsage(), "duplicate settlement must not double count budget");

            // requestId/inputDigest 同时改变，表示新增 evidence 或其它冻结事实后的新 intent。
            PlanEvaluationAuditPort.Intent changed = intent("request_plan_eval_b", "b".repeat(64), profile);
            repository.recordIntent(changed);
            assertEquals(PlanEvaluationAuditPort.Outcome.RUNNING,
                    repository.find(changed.requestId()).orElseThrow().outcome());
        }
    }

    /** Provider 计量未知时仍必须保存 UNKNOWN，不能把缺失 token 转成零或可完成事实。 */
    @Test
    void persistsUnknownUsageWithoutTokenFacts() throws Exception {
        try (Fixture fixture = fixture("unknown-usage")) {
            MybatisPlanEvaluationAuditRepository repository = fixture.repository();
            ProviderRequestProfile profile = profile();
            PlanEvaluationAuditPort.Intent intent = intent("request_plan_eval_unknown", "c".repeat(64), profile);
            repository.recordIntent(intent);

            ProviderRequestUsage unknown = new ProviderRequestUsage(intent.requestId(), 1, 1,
                    ProviderRequestUsage.Purpose.ASSISTANT, ProviderRequestUsage.Certainty.UNKNOWN,
                    profile, null);
            repository.recordUsage(new PlanEvaluationAuditPort.Usage(intent.requestId(), intent.planId(),
                    intent.planRevisionId(), intent.runId(), unknown, PlanEvaluationAuditPort.Outcome.UNKNOWN,
                    START.plusSeconds(1)));

            assertEquals(Optional.of(new PlanEvaluationAuditPort.Prior(
                    intent.requestId(), PlanEvaluationAuditPort.Outcome.UNKNOWN)), repository.find(intent.requestId()));
            assertEquals("1/1000", fixture.runUsage(), "UNKNOWN still consumes the reserved round and active time");
        }
    }

    /** Run 的模型轮次在 Provider 前预留；预算耗尽时第二个 evaluator 不得发起调用。 */
    @Test
    void rejectsIntentWhenModelRoundBudgetIsExhausted() throws Exception {
        try (Fixture fixture = fixture("round-budget", 1, 10_000)) {
            MybatisPlanEvaluationAuditRepository repository = fixture.repository();
            ProviderRequestProfile profile = profile();
            repository.recordIntent(intent("request_plan_eval_budget_a", "a".repeat(64), profile));
            assertThrows(IllegalStateException.class,
                    () -> repository.recordIntent(intent("request_plan_eval_budget_b", "b".repeat(64), profile)));
            assertEquals("1/0", fixture.runUsage(), "failed reservation must roll back without partial accounting");
        }
    }

    /** 未冻结预算不能被解释为无限预算，必须在 Provider 前拒绝 evaluator intent。 */
    @Test
    void rejectsIntentWhenBudgetIsMissing() throws Exception {
        try (Fixture fixture = fixture("missing-budget", (Integer) null, (Long) null, "VERIFYING", 0)) {
            assertThrows(IllegalStateException.class,
                    () -> fixture.repository().recordIntent(intent("request_plan_eval_missing", "d".repeat(64), profile())));
            assertEquals("0/0", fixture.runUsage());
        }
    }

    /** pause fence 一旦落库，迟到 evaluator 不得重新取得 Provider 调用资格。 */
    @Test
    void rejectsIntentAfterPauseFence() throws Exception {
        try (Fixture fixture = fixture("pause-fence", 4, 10_000, "VERIFYING", 1)) {
            assertThrows(IllegalStateException.class,
                    () -> fixture.repository().recordIntent(intent("request_plan_eval_paused", "e".repeat(64), profile())));
            assertEquals("0/0", fixture.runUsage());
        }
    }

    /** intent 的 revision 必须仍是 Plan 当前 active revision，迟到旧 revision 不得占用预算。 */
    @Test
    void rejectsIntentForStaleRevision() throws Exception {
        try (Fixture fixture = fixture("stale-revision", 4, 10_000, "VERIFYING", 0)) {
            PlanEvaluationAuditPort.Intent stale = new PlanEvaluationAuditPort.Intent(
                    "request_plan_eval_stale", "plan_eval", "planrev_stale", "run_eval", "thr_eval",
                    profile(), "f".repeat(64), START);
            assertThrows(IllegalStateException.class, () -> fixture.repository().recordIntent(stale));
            assertEquals("0/0", fixture.runUsage());
        }
    }

    /** 生成与真实 runtime 合同一致的非敏感 Provider profile，避免测试绕过 profile 校验。 */
    private static ProviderRequestProfile profile() {
        return new ProviderRequestProfile("provider_test", "model_test", "openai_responses", "test-model",
                "medium", "medium", AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT,
                "cfg_test", "prompt_test", "a".repeat(64), 100_000, 8_192);
    }

    /** 构造绑定同一 Plan/revision/run 的审计 intent，只有 input identity 允许变化。 */
    private static PlanEvaluationAuditPort.Intent intent(String requestId, String inputDigest,
                                                          ProviderRequestProfile profile) {
        return new PlanEvaluationAuditPort.Intent(requestId, "plan_eval", "planrev_eval", "run_eval",
                "thr_eval", profile, inputDigest, START);
    }

    /** 打开真实 SQLite、建最小 owner 事实并注册 Plan evaluation Mapper。 */
    private Fixture fixture(String name) throws Exception {
        return fixture(name, 4, 10_000, "VERIFYING", 0);
    }

    /** 允许预算边界测试显式冻结 Run 上限，仍通过真实 schema 与 SQLite 事务执行。 */
    private Fixture fixture(String name, int maxModelRounds, long wallBudgetMillis) throws Exception {
        return fixture(name, Integer.valueOf(maxModelRounds), Long.valueOf(wallBudgetMillis), "VERIFYING", 0);
    }

    /** 用可空预算和 pause fence 复现执行前边界，确保 SQL 不提供无限预算兜底。 */
    private Fixture fixture(String name, Integer maxModelRounds, Integer wallBudgetMillis,
                            String runStatus, int pauseRequested) throws Exception {
        return fixture(name, maxModelRounds, wallBudgetMillis == null ? null : Long.valueOf(wallBudgetMillis),
                runStatus, pauseRequested);
    }

    /** 允许 SQLite fixture 显式写入缺失预算，验证严格执行策略而非测试默认值。 */
    private Fixture fixture(String name, Integer maxModelRounds, Long wallBudgetMillis,
                            String runStatus, int pauseRequested) throws Exception {
        JaDatabase database = JaDatabase.open(DatabaseConfig.of(temp.resolve(name + ".sqlite3")));
        Configuration configuration = new Configuration(new Environment("plan-evaluation-test",
                new JdbcTransactionFactory(), database.dataSource()));
        configuration.setMapUnderscoreToCamelCase(false);
        // PersistenceMappers.open() is transaction-wide, so the focused fixture registers every
        // mapper that the shared production facade resolves, not only the mapper under test.
        configuration.addMappers("io.github.kongweiguang.ja.infrastructure.persistence.mapper");
        SqlSessionFactory sessions = new SqlSessionFactoryBuilder().build(configuration);
        database.bindWalCheckpoint(sessions);
        try (SqlSession session = sessions.openSession(); Statement sql = session.getConnection().createStatement()) {
            sql.executeUpdate("INSERT INTO workspaces(workspace_id,root_path,display_name,trust,revision,created_at,updated_at) "
                    + "VALUES('ws_eval','C:/plan-evaluation-test','Plan evaluation test','TRUSTED',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            sql.executeUpdate("INSERT INTO threads(thread_id,workspace_id,title,revision,created_at,updated_at,provider_id,model_id,access_mode,title_source,collaboration_mode) "
                    + "VALUES('thr_eval','ws_eval','Plan evaluation',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'provider_test','model_test','APPROVAL_REQUIRED','MANUAL','PLAN')");
            sql.executeUpdate("INSERT INTO plans(plan_id,owner_thread_id,objective,create_idempotency_key,status,revision,created_at,updated_at) "
                    + "VALUES('plan_eval','thr_eval','Evaluate plan','plan-evaluation-key','DRAFT',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            sql.executeUpdate("INSERT INTO plan_revisions(plan_revision_id,plan_id,revision_number,definition_json,plan_hash,created_by,created_at) "
                    + "VALUES('planrev_eval','plan_eval',1,'{}','" + "a".repeat(64) + "','AGENT',CURRENT_TIMESTAMP)");
            sql.executeUpdate("INSERT INTO execution_runs(run_id,plan_id,plan_revision_id,plan_hash,status,process_generation,"
                    + "pause_requested,max_model_rounds,wall_budget_millis,created_at,updated_at) VALUES('run_eval','plan_eval',"
                    + "'planrev_eval','" + "a".repeat(64) + "','" + runStatus + "',1," + pauseRequested + ","
                    + sqlLiteral(maxModelRounds) + "," + sqlLiteral(wallBudgetMillis)
                    + ",CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            sql.executeUpdate("UPDATE plans SET status='VERIFYING',revision=1,active_plan_revision_id='planrev_eval',active_run_id='run_eval' WHERE plan_id='plan_eval'");
            session.commit();
        }
        MybatisUnitOfWork.SessionOwner owner = new MybatisUnitOfWork.SessionOwner() {
            /** 测试事务 owner 与生产一样保证 Mapper 失败整体回滚，只替换 Solon 提交桥。 */
            @Override
            public <T> T execute(SqlSessionFactory factory, MybatisUnitOfWork.Work<T> work) {
                try (SqlSession session = factory.openSession()) {
                    try {
                        T value = work.apply(PersistenceMappers.open(session));
                        session.commit();
                        return value;
                    } catch (Throwable failure) {
                        session.rollback();
                        if (failure instanceof RuntimeException runtime) throw runtime;
                        throw new IllegalStateException("Plan evaluation test transaction failed", failure);
                    }
                }
            }
        };
        return new Fixture(database, sessions, new MybatisPlanEvaluationAuditRepository(
                sessions, new ObjectMapper(), owner));
    }

    /** 将可选测试预算编码为 SQL NULL，避免字符串拼接把缺失值伪装成零预算。 */
    private static String sqlLiteral(Number value) {
        return value == null ? "NULL" : value.toString();
    }

    /** Fixture 关闭数据库 lease，避免测试文件和 WAL 句柄泄漏到后续用例。 */
    private record Fixture(JaDatabase database, SqlSessionFactory sessions,
                           MybatisPlanEvaluationAuditRepository repository)
            implements AutoCloseable {
        /** 读取 Run 的两个 evaluator 预算计数，验证审计与执行预算同事务可见。 */
        private String runUsage() throws Exception {
            try (SqlSession session = sessions.openSession();
                 java.sql.PreparedStatement query = session.getConnection().prepareStatement(
                         "SELECT used_model_rounds,used_active_millis FROM execution_runs WHERE run_id='run_eval'")) {
                try (java.sql.ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("run budget row missing");
                    return rows.getInt(1) + "/" + rows.getLong(2);
                }
            }
        }
        /** 关闭 fresh SQLite lease，确保测试不会把 WAL 文件句柄泄漏给后续用例。 */
        @Override
        public void close() {
            database.close();
        }
    }
}
