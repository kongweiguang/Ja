// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.infrastructure.persistence;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.conversation.ContextInstruction;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.application.gateway.JaAdmissionTransactionParticipant;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PlanMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepositoryException;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import io.github.kongweiguang.ja.plan.domain.PlanModels.AcceptanceCriterion;
import io.github.kongweiguang.ja.plan.domain.PlanModels.Plan;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanDefinition;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanRevision;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanStep;
import io.github.kongweiguang.ja.plan.application.gateway.PlanCanonicalizer;
import io.github.kongweiguang.ja.plan.infrastructure.persistence.serialization.CanonicalPlanJson;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通过真实 JaAgentStore admission 验证 Plan binding 与 Turn 的同事务持久化边界。 */
final class PlanExecutionAdmissionPersistenceTest extends PersistenceTestSupport {
    private static final Instant ACCEPTED_AT = Instant.parse("2026-10-06T00:00:00Z");
    private static final String WORKSPACE_ID = "ws_plan_admission";
    private static final String THREAD_ID = "thr_plan_admission";
    private static final ThreadPreferences PREFERENCES = new ThreadPreferences("provider_test", "model_test",
            "medium", AccessMode.FULL_ACCESS, CollaborationMode.PLAN,
            ThreadPreferences.TitleSource.PLACEHOLDER);

    /** 真实 admission 同时落下内部 Plan identity 和 Conversation Turn，两者缺一都会破坏暂停恢复。 */
    @Test
    void persistsPlanBindingWithRealStoreAdmission() throws Exception {
        try (TestDatabase database = database("plan-admission-commit")) {
            AdmissionCase admission = prepare(database, "commit", temp);
            try (AgentStoreProjectionFixture fixture = admit(database, admission, admission.participant())) {
                assertEquals(admission.turnId(), fixture.execution().turnId());
                assertPersistedRows(database, admission.turnId(), 1, 1);
                PlanRepository.InternalTurnBinding binding = admission.plans().findPlanTurnBinding(
                        admission.plan().planId(), admission.plan().activeRunId()).orElseThrow();
                assertEquals(admission.turnId(), binding.turnId());
                assertEquals(admission.revision().planRevisionId(), binding.planRevisionId());
                assertEquals(admission.revision().planHash(), binding.planHash());
            }
        }
    }

    /** Store 在 binding 已写入后失败时必须回滚 Turn、binding 与 Core admission，不能留下半个执行。 */
    @Test
    void rollsBackTurnAndBindingWhenAdmissionFailsAfterBindingWrite() throws Exception {
        try (TestDatabase database = database("plan-admission-rollback")) {
            AdmissionCase admission = prepare(database, "rollback", temp);
            AtomicBoolean bindingWriteCompleted = new AtomicBoolean();
            JaAdmissionTransactionParticipant failAfterPlanBinding = new JaAdmissionTransactionParticipant() {
                /** 保留生产 Plan participant 的 fence 校验，失败只发生在 binding INSERT 之后。 */
                @Override
                public void beforeProjection(AgentStoreTransactionContext transaction) throws SQLException {
                    admission.participant().beforeProjection(transaction);
                }

                /** 注入提交后置失败，以数据库事实证明 Store 回滚覆盖同一 transaction 的全部行。 */
                @Override
                public void afterProjection(AgentStoreTransactionContext transaction) throws SQLException {
                    admission.participant().afterProjection(transaction);
                    bindingWriteCompleted.set(true);
                    throw new SQLException("test failure after Plan binding write");
                }
            };

            assertThrows(AgentStore.StoreParticipantException.class,
                    () -> admit(database, admission, failAfterPlanBinding));

            assertTrue(bindingWriteCompleted.get());
            assertPersistedRows(database, admission.turnId(), 0, 0);
            assertEquals("CLAIMED", claimState(database, admission.plan().activeRunId(), admission.turnId()));
            assertEquals(Optional.empty(), admission.plans().findPlanTurnBinding(
                    admission.plan().planId(), admission.plan().activeRunId()));
        }
    }

    /** 持久 Turn 缺少不可变 binding 时向调用方返回不可重试恢复错误，不猜身份或启动替代 Turn。 */
    @Test
    void reportsRecoveryRequiredWhenPersistedTurnLosesItsBinding() throws Exception {
        try (TestDatabase database = database("plan-admission-missing-binding")) {
            AdmissionCase admission = prepare(database, "missing", temp);
            try (AgentStoreProjectionFixture ignored = admit(database, admission, admission.participant())) {
                deleteBinding(database, admission.turnId());

                PlanRepositoryException failure = assertThrows(PlanRepositoryException.class,
                        () -> admission.plans().findPlanTurnBinding(admission.plan().planId(),
                                admission.plan().activeRunId()));

                assertEquals(PlanRepositoryException.Code.PLAN_RECOVERY_REQUIRED, failure.code());
                assertPersistedRows(database, admission.turnId(), 1, 0);
                assertEquals("CLAIMED", claimState(database, admission.plan().activeRunId(), admission.turnId()));
            }
        }
    }

    /**
     * 用生产 Plan repository 和隔离 Thread 建立真实执行 claim，再冻结 admission 所需的完整身份。
     * 测试间共享此准备路径，workspace 根显式传入以避免跨测试实例共享 JUnit 临时目录。
     *
     * @param database 当前隔离 SQLite 数据库
     * @param suffix 用于派生稳定测试身份的后缀
     * @param workspaceRoot 当前测试独占的临时工作区
     * @return 完成真实 claim 但尚未提交 Turn admission 的执行输入
     * @author kongweiguang
     */
    static AdmissionCase prepare(TestDatabase database, String suffix, Path workspaceRoot) throws Exception {
        database.history().register(new Workspace.Registration(WORKSPACE_ID, workspaceRoot.toAbsolutePath(),
                "Plan admission", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, ACCEPTED_AT));
        database.history().createThread(new ThreadSummary.Creation(THREAD_ID, WORKSPACE_ID,
                "Plan admission", PREFERENCES, ACCEPTED_AT));
        MybatisPlanRepository plans = planRepository(database);
        String planId = "plan_admission_" + suffix;
        String runId = "run_admission_" + suffix;
        String turnId = "turn_admission_" + suffix;
        Plan created = plans.createPlan(new PlanRepository.CreatePlan(planId, THREAD_ID,
                "验证执行 admission", 0, "create:" + suffix, ACCEPTED_AT));
        PlanDefinition definition = new PlanDefinition("验证目标", List.of("执行一个步骤"), List.of(), List.of(),
                List.of(), List.of(new PlanStep("step_work", "实施", "完成 admission 验证", true, List.of())),
                List.of(new AcceptanceCriterion("criterion_store", "Store admission 持久化", true)),
                List.of(), List.of("运行持久化测试"));
        CanonicalPlanJson canonicalJson = new CanonicalPlanJson(database.mapper());
        PlanCanonicalizer.Encoded canonical = canonicalJson.encode(definition);
        String revisionId = "planrev_admission_" + suffix;
        plans.propose(new PlanRepository.ProposePlan(planId, created.revision(), new PlanRevision(revisionId,
                planId, 1, definition, canonical.json(), canonical.sha256(), "AGENT", ACCEPTED_AT),
                "event_propose_" + suffix, "propose:" + suffix, ACCEPTED_AT));
        PlanRevision revision = plans.readPlanSnapshot(planId).currentRevision();
        Plan executing = plans.executePlan(new PlanRepository.ExecutePlan(planId,
                plans.readPlanSnapshot(planId).plan().revision(), revision.planRevisionId(), revision.planHash(),
                "approval_" + suffix, runId, 1, "event_execute_" + suffix, "execute:" + suffix,
                ACCEPTED_AT.plusSeconds(1)));
        plans.claimPlanTurn(new PlanRepository.ClaimPlanTurn(executing.planId(), executing.revision(), runId,
                revision.planRevisionId(), turnId, "event_claim_" + suffix, "claim:" + suffix,
                ACCEPTED_AT.plusSeconds(2))).orElseThrow();

        AgentIdentity identity = new AgentIdentity("ja.local", THREAD_ID, turnId, "user_local");
        Instant acceptedAt = ACCEPTED_AT.plusSeconds(3);
        AdmissionFacts facts = new AdmissionFacts(identity, WORKSPACE_ID, TurnOrigin.PLAN_EXECUTION,
                "plan/execute", acceptedAt, null, null, null, revision.planRevisionId(), runId,
                null, null, 0, Optional.empty());
        AgentExecutionRequest request = new AgentExecutionRequest(identity,
                AgentExecutionRequest.AdmissionKind.INTERNAL, Optional.empty(), null,
                Optional.of(new ContextInstruction("instruction_" + suffix, "继续执行已批准的计划步骤")),
                new AgentStore.OperationIdentity("operation_admission_" + suffix, "plan/execute",
                        "a".repeat(64), JsonObject.empty()), Map.of(),
                AgentExecutionRequest.HistorySelection.currentConversation(), acceptedAt,
                List.of(), Optional.empty(), Optional.empty());
        JaAdmissionTransactionParticipant participant = new PlanExecutionAdmissionParticipantResolver(
                ignored -> Optional.empty(), plans, database.mapper()).resolve(facts).orElseThrow();
        return new AdmissionCase(plans, executing, revision, turnId, request, facts, participant);
    }

    /** 通过正式 projection fixture 调用 JaAgentStore.admit，唯一用于提交边界的真实 Store 入口。 */
    private AgentStoreProjectionFixture admit(TestDatabase database, AdmissionCase admission,
                                              JaAdmissionTransactionParticipant participant) {
        return AgentStoreProjectionFixture.open(database.jaAgentStore(), database.mapper(), admission.request(),
                admission.facts(), PREFERENCES, 0, participant);
    }

    /** Plan repository 使用隔离 SQLite owner，保持每次聚合写入具有与生产相同的单 session 回滚边界。 */
    private static MybatisPlanRepository planRepository(TestDatabase database) {
        return new MybatisPlanRepository(database.sessions(), database.mapper(), new PlanUnitOfWork.SessionOwner() {
            /** 测试 owner 仅替换 Solon 提交桥，失败仍按 PlanUnitOfWork 的稳定事务规则回滚。 */
            @Override
            public <T> T execute(SqlSessionFactory sessions, PlanUnitOfWork.Work<T> work) {
                try (SqlSession session = sessions.openSession()) {
                    try {
                        T value = work.apply(new PlanUnitOfWork.Mappers(session.getMapper(PlanMapper.class)));
                        session.commit();
                        return value;
                    } catch (Throwable failure) {
                        try {
                            session.rollback();
                        } catch (Throwable rollbackFailure) {
                            failure.addSuppressed(rollbackFailure);
                        }
                        throw PlanUnitOfWork.transactionFailure(failure);
                    }
                }
            }
        });
    }

    /** 分别核对 Turn 与 binding 行数，提交/回滚需成对变化，损坏场景则保留 Turn 并明确缺失 binding。 */
    private static void assertPersistedRows(TestDatabase database, String turnId,
                                            int expectedTurn, int expectedBinding) throws Exception {
        try (SqlSession session = database.sessions().openSession()) {
            assertEquals(expectedTurn,
                    count(session, "SELECT COUNT(*) FROM ja_conversation_turn WHERE turn_id=?", turnId));
            assertEquals(expectedBinding,
                    count(session, "SELECT COUNT(*) FROM ja_turn_internal_context WHERE turn_id=?", turnId));
        }
    }

    /** 只对测试内固定 SQL 执行单值读取，SQL 本身不从业务输入拼接。 */
    private static int count(SqlSession session, String sql, String turnId) throws Exception {
        try (PreparedStatement query = session.getConnection().prepareStatement(sql)) {
            query.setString(1, turnId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new AssertionError("missing admission count");
                }
                return rows.getInt(1);
            }
        }
    }

    /** 故障注入只删除不可变 binding 行，保留 claimed Turn 以验证恢复错误而不猜测身份。 */
    private static void deleteBinding(TestDatabase database, String turnId) throws Exception {
        try (SqlSession session = database.sessions().openSession();
             PreparedStatement delete = session.getConnection().prepareStatement(
                     "DELETE FROM ja_turn_internal_context WHERE turn_id=?")) {
            delete.setString(1, turnId);
            assertEquals(1, delete.executeUpdate());
            session.commit();
        }
    }

    /** 读取原始 claim 保证恢复错误后没有被静默释放或重新领取。 */
    private static String claimState(TestDatabase database, String runId, String turnId) throws Exception {
        try (SqlSession session = database.sessions().openSession();
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

    /** 持有一次 admission 的冻结输入，测试辅助值不能推断或补写持久 binding。
     * @param plans 负责建立并查询执行身份的 Plan 仓储
     * @param plan 已获准执行的 Plan 快照
     * @param revision 本次执行冻结的 Plan 修订
     * @param turnId admission 使用的稳定 Turn 标识
     * @param request 交给真实 AgentStore 的不可变执行请求
     * @param facts Ja 同事务投影所需的 admission 身份
     * @param participant admission 阶段校验并写入 binding 的参与者
     */
    record AdmissionCase(MybatisPlanRepository plans,
                         Plan plan,
                         PlanRevision revision,
                         String turnId,
                         AgentExecutionRequest request,
                         AdmissionFacts facts,
                         JaAdmissionTransactionParticipant participant) { }
}
