// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.database.DatabaseConfig;
import io.github.kongweiguang.ja.infrastructure.persistence.database.JaDatabase;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PlanMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SchemaMapper;
import io.github.kongweiguang.ja.plan.application.gateway.PlanCanonicalizer;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepositoryException;
import io.github.kongweiguang.ja.plan.domain.PlanModels.AcceptanceCriterion;
import io.github.kongweiguang.ja.plan.domain.PlanModels.Plan;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanDefinition;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanRevision;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanStatus;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanStep;
import io.github.kongweiguang.ja.plan.infrastructure.persistence.serialization.CanonicalPlanJson;
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
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 通过独立稳定入口证明终态 Turn 未结算时不能提交 Plan Stop。 */
final class PlanStopClaimSettlementRegressionTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    @TempDir
    Path temp;

    /** 旧实现会在 CLAIMED claim 尚未结算时提交 STOPPED，本测试固定该事务边界。 */
    @Test
    void stopRejectsTerminalTurnWithClaimStillClaimed() throws Exception {
        try (Fixture fixture = fixture()) {
            Plan awaiting = fixture.repository().createPlan(new PlanRepository.CreatePlan(
                    "plan_stop_claim", "thr_stop_claim", "停止结算测试", 0, "create:stop-claim", NOW));
            PlanDefinition definition = new PlanDefinition("停止结算测试", List.of("执行范围"), List.of(),
                    List.of(), List.of(), List.of(new PlanStep("step_work", "执行", "完成执行", true, List.of())),
                    List.of(new AcceptanceCriterion("criterion_done", "验证完成", true)), List.of(),
                    List.of("运行聚焦测试"));
            PlanCanonicalizer.Encoded canonical = new CanonicalPlanJson(new ObjectMapper()).encode(definition);
            PlanRevision revision = new PlanRevision("revision_stop_claim", awaiting.planId(), 1,
                    definition, canonical.json(), canonical.sha256(), "AGENT", NOW);
            fixture.repository().propose(new PlanRepository.ProposePlan(awaiting.planId(), awaiting.revision(),
                    revision, "event_propose_stop_claim", "propose:stop-claim", NOW));
            Plan proposed = fixture.repository().readPlanSnapshot(awaiting.planId()).plan();
            Plan executing = fixture.repository().executePlan(new PlanRepository.ExecutePlan(
                    proposed.planId(), proposed.revision(), revision.planRevisionId(), revision.planHash(),
                    "approval_stop_claim", "run_stop_claim", 1, "event_execute_stop_claim",
                    "execute:stop-claim", NOW.plusSeconds(1)));
            fixture.repository().claimPlanTurn(new PlanRepository.ClaimPlanTurn(executing.planId(),
                    executing.revision(), executing.activeRunId(), executing.activePlanRevisionId(),
                    "turn_stop_claim", "event_claim_stop_claim", "claim:stop-claim", NOW.plusSeconds(2)));
            fixture.insertTurn("turn_stop_claim");

            PlanRepository.StopPlan stop = new PlanRepository.StopPlan(executing.planId(), executing.revision(),
                    executing.activeRunId(), "event_stop_claim", "stop:claim-still-held", NOW.plusSeconds(3));
            PlanRepositoryException failure = assertThrows(PlanRepositoryException.class,
                    () -> fixture.repository().stopPlan(stop));

            assertEquals(PlanRepositoryException.Code.PLAN_INVALID_STATE, failure.code());
            assertEquals(PlanStatus.EXECUTING,
                    fixture.repository().readPlanSnapshot(executing.planId()).plan().status());
            assertEquals("CLAIMED", fixture.claimState(executing.activeRunId(), "turn_stop_claim"));
        }
    }

    /** 初始化独立 SQLite schema 与最小 owner 行，使红测能原样移植到旧源码快照。 */
    private Fixture fixture() throws Exception {
        JaDatabase database = JaDatabase.open(new DatabaseConfig(temp.resolve("plan-stop-claim.sqlite3"),
                DatabaseConfig.DEFAULT_BUSY_TIMEOUT, temp));
        Configuration configuration = new Configuration(new Environment("plan-stop-claim-regression",
                new JdbcTransactionFactory(), database.dataSource()));
        configuration.setMapUnderscoreToCamelCase(false);
        configuration.addMapper(SchemaMapper.class);
        configuration.addMapper(PlanMapper.class);
        SqlSessionFactory sessions = new SqlSessionFactoryBuilder().build(configuration);
        database.bindWalCheckpoint(sessions);
        try (SqlSession session = sessions.openSession()) {
            try (PreparedStatement workspace = session.getConnection().prepareStatement("""
                    INSERT INTO ja_workspace(workspace_id,root_path,kind,display_name,trust,revision,
                    created_at_ms,updated_at_ms) VALUES('ws_stop_claim','C:/plan-stop-claim','PROJECT',
                    'Plan stop test','TRUSTED',0,?,?)
                    """)) {
                workspace.setLong(1, NOW.toEpochMilli());
                workspace.setLong(2, NOW.toEpochMilli());
                workspace.executeUpdate();
            }
            try (PreparedStatement thread = session.getConnection().prepareStatement("""
                    INSERT INTO ja_conversation_thread(thread_id,owner_user_id,workspace_id,title,revision,
                    created_at_ms,updated_at_ms,provider_id,model_id,access_mode,title_source,collaboration_mode)
                    VALUES('thr_stop_claim','user_local','ws_stop_claim','Plan stop test',0,?,?,'provider_test',
                    'model_test','APPROVAL_REQUIRED','MANUAL','PLAN')
                    """)) {
                thread.setLong(1, NOW.toEpochMilli());
                thread.setLong(2, NOW.toEpochMilli());
                thread.executeUpdate();
            }
            session.commit();
        }
        PlanUnitOfWork.SessionOwner owner = new PlanUnitOfWork.SessionOwner() {
            /** 测试 owner 只替换 Solon 提交桥，仍以真实 session 事务回滚失败 mutation。 */
            @Override
            public <T> T execute(SqlSessionFactory factory, PlanUnitOfWork.Work<T> work) {
                try (SqlSession session = factory.openSession()) {
                    try {
                        T result = work.apply(new PlanUnitOfWork.Mappers(session.getMapper(PlanMapper.class)));
                        session.commit();
                        return result;
                    } catch (Throwable failure) {
                        session.rollback();
                        if (failure instanceof RuntimeException runtime) {
                            throw runtime;
                        }
                        throw new IllegalStateException("Plan stop regression transaction failed", failure);
                    }
                }
            }
        };
        return new Fixture(database, sessions,
                new MybatisPlanRepository(sessions, new ObjectMapper(), owner));
    }

    /**
     * 集中持有数据库、会话工厂与仓储，确保终态事实和 Stop 检查共享同一 SQLite owner。
     *
     * @param database SQLite 数据库及其资源生命周期 owner
     * @param sessions 绑定该数据库数据源的 MyBatis 会话工厂
     * @param repository 复用上述会话工厂事务的 Plan 仓储
     */
    private record Fixture(JaDatabase database,
                           SqlSessionFactory sessions,
                           MybatisPlanRepository repository) implements AutoCloseable {
        /** 持久化终态 Turn，使真实仓储能校验 CLAIMED claim 的结算屏障。 */
        private void insertTurn(String turnId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement turn = session.getConnection().prepareStatement("""
                         INSERT INTO ja_conversation_turn(turn_id,thread_id,state,requested_at_ms,updated_at_ms,
                         completed_at_ms,terminal_summary,parent_turn_id,root_turn_id)
                         VALUES(?,'thr_stop_claim','CANCELLED',?,?,?,'取消完成',NULL,NULL)
                         """)) {
                turn.setString(1, turnId);
                turn.setLong(2, NOW.toEpochMilli());
                turn.setLong(3, NOW.plusSeconds(2).toEpochMilli());
                turn.setLong(4, NOW.plusSeconds(2).toEpochMilli());
                turn.executeUpdate();
                session.commit();
            }
        }

        /** 从 SQLite 回读持久 claim 状态，防止停止投影掩盖未结算事实。 */
        private String claimState(String runId, String turnId) throws Exception {
            try (SqlSession session = sessions.openSession();
                 PreparedStatement query = session.getConnection().prepareStatement(
                         "SELECT state FROM ja_plan_turn_claim WHERE run_id=? AND turn_id=?")) {
                query.setString(1, runId);
                query.setString(2, turnId);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new AssertionError("missing Plan Turn claim");
                    }
                    return rows.getString(1);
                }
            }
        }

        /** 释放隔离 SQLite 数据库资源，不把 WAL 句柄留给相邻测试。 */
        @Override
        public void close() {
            database.close();
        }
    }
}
