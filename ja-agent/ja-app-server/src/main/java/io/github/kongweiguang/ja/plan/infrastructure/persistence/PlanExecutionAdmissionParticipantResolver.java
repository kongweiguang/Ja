// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionParticipantResolver;
import io.github.kongweiguang.ja.conversation.application.gateway.JaAdmissionTransactionParticipant;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.JdbcTransactionResources;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.domain.PlanModels.Plan;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanRevision;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanSnapshot;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanStatus;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 在 Turn admission 事务内持久化不可变 Plan/run/revision/hash 绑定，并保留既有 Task 投影。
 */
public final class PlanExecutionAdmissionParticipantResolver implements AdmissionParticipantResolver {
    private final AdmissionParticipantResolver delegate;
    private final PlanRepository plans;
    private final ObjectMapper json;

    /**
     * 装饰现有业务 resolver，使 Plan binding 与 Task 投影共享 JaAgentStore 的 staged admission 事务。
     */
    public PlanExecutionAdmissionParticipantResolver(AdmissionParticipantResolver delegate,
                                                     PlanRepository plans, ObjectMapper json) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 只有新 Plan Turn admission 才新建冻结 binding；普通来源仍完全交由原业务 resolver 处理。
     */
    @Override
    public Optional<JaAdmissionTransactionParticipant> resolve(AdmissionFacts facts) {
        AdmissionFacts admission = Objects.requireNonNull(facts, "facts");
        Optional<JaAdmissionTransactionParticipant> existing =
                Objects.requireNonNull(delegate.resolve(admission), "delegated admission participant");
        if (admission.origin() != TurnOrigin.PLAN_EXECUTION) {
            return existing;
        }
        PlanBinding binding = freezeBinding(admission);
        JaAdmissionTransactionParticipant plan = new PlanContextParticipant(admission, binding);
        return Optional.of(existing.map(participant -> combine(participant, plan)).orElse(plan));
    }

    /**
     * 从唯一活动 Plan 冻结完整身份，拒绝让过期 Run 或 revision 进入 Turn admission。
     */
    private PlanBinding freezeBinding(AdmissionFacts facts) {
        Plan active = plans.findExecutingPlanByOwner(facts.identity().threadId())
                .orElseThrow(() -> new IllegalStateException("Plan execution admission has no active Plan"));
        PlanSnapshot snapshot = plans.readPlanSnapshot(active.planId());
        Plan plan = snapshot.plan();
        PlanRevision revision = snapshot.currentRevision();
        if (!plan.ownerThreadId().equals(facts.identity().threadId())
            || plan.status() != PlanStatus.EXECUTING
            || !Objects.equals(plan.activeRunId(), facts.runId())
            || !Objects.equals(plan.activePlanRevisionId(), facts.planRevisionId())
            || revision == null || !Objects.equals(revision.planRevisionId(), facts.planRevisionId())) {
            throw new IllegalStateException("Plan execution admission identity is stale");
        }
        try {
            String context = json.writeValueAsString(Map.of(
                    "kind", TurnOrigin.PLAN_EXECUTION.name(),
                    "planId", plan.planId(),
                    "runId", facts.runId(),
                    "planRevisionId", facts.planRevisionId(),
                    "planHash", revision.planHash()));
            return new PlanBinding(plan.planId(), facts.runId(), facts.planRevisionId(),
                    revision.planHash(), context);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Plan execution binding cannot be encoded", failure);
        }
    }

    /**
     * 两个业务参与者按既有 Ja staged 顺序运行，任一阶段失败都由外层 Store 回滚。
     */
    private static JaAdmissionTransactionParticipant combine(JaAdmissionTransactionParticipant first,
                                                             JaAdmissionTransactionParticipant second) {
        return new JaAdmissionTransactionParticipant() {
            /** 保留 Task 的准入校验，并在同一事务投影前核验 Plan claim 与暂停 fence。 */
            @Override
            public void beforeProjection(AgentStoreTransactionContext transaction) throws SQLException {
                first.beforeProjection(transaction);
                second.beforeProjection(transaction);
            }

            /** 先提交已有 Task 事实，再在 Turn 外键可用后写入不可变 Plan context。 */
            @Override
            public void afterProjection(AgentStoreTransactionContext transaction) throws SQLException {
                first.afterProjection(transaction);
                second.afterProjection(transaction);
            }
        };
    }

    /**
     * 只在 Store 当前连接中校验与写入，不开启嵌套事务或改变 connection owner。
     */
    private final class PlanContextParticipant implements JaAdmissionTransactionParticipant {
        private final AdmissionFacts facts;
        private final PlanBinding binding;

        /**
         * 冻结本次 admission 的事实与 JSON，防止事务回调时读取已经变化的 Plan revision。
         */
        private PlanContextParticipant(AdmissionFacts facts, PlanBinding binding) {
            this.facts = facts;
            this.binding = binding;
        }

        /**
         * 校验同一 Store transaction 仍持有活动 claim，且 pause fence 尚未关闭准入。
         */
        @Override
        public void beforeProjection(AgentStoreTransactionContext transaction) throws SQLException {
            if (transaction.kind() != AgentStore.TransactionKind.ADMISSION) {
                return;
            }
            validateIdentity(transaction);
            String query = """
                    SELECT 1
                    FROM ja_plan p
                    JOIN ja_plan_execution e ON e.run_id=p.active_run_id
                    JOIN ja_plan_turn_claim c ON c.run_id=e.run_id
                    WHERE p.plan_id=? AND p.owner_thread_id=? AND p.status='EXECUTING'
                      AND p.active_plan_revision_id=? AND p.active_run_id=?
                      AND e.status='RUNNING' AND e.plan_revision_id=? AND e.plan_hash=?
                      AND e.is_pause_requested=0 AND c.turn_id=? AND c.state='CLAIMED'
                    LIMIT 1
                    """;
            try (PreparedStatement statement = JdbcTransactionResources.connection(transaction)
                    .prepareStatement(query)) {
                statement.setString(1, binding.planId());
                statement.setString(2, facts.identity().threadId());
                statement.setString(3, binding.planRevisionId());
                statement.setString(4, binding.runId());
                statement.setString(5, binding.planRevisionId());
                statement.setString(6, binding.planHash());
                statement.setString(7, facts.identity().turnId());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new SQLException("Plan execution admission fence is closed");
                    }
                }
            }
        }

        /**
         * Turn 投影成功后再满足 context 外键，保证 Turn 与恢复 binding 原子提交或共同回滚。
         */
        @Override
        public void afterProjection(AgentStoreTransactionContext transaction) throws SQLException {
            if (transaction.kind() != AgentStore.TransactionKind.ADMISSION) {
                return;
            }
            validateIdentity(transaction);
            try (PreparedStatement statement = JdbcTransactionResources.connection(transaction)
                    .prepareStatement("""
                            INSERT INTO ja_turn_internal_context(turn_id,origin,context_json,created_at_ms)
                            VALUES(?,'PLAN_EXECUTION',?,?)
                            """)) {
                statement.setString(1, facts.identity().turnId());
                statement.setString(2, binding.contextJson());
                statement.setLong(3, facts.acceptedAt().toEpochMilli());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Plan execution binding was not persisted");
                }
            }
        }

        /**
         * 冻结 Plan 身份、Core execution key 和交易类型必须完全一致，避免 context 跨 Turn 写入。
         */
        private void validateIdentity(AgentStoreTransactionContext transaction) throws SQLException {
            AgentStore.ExecutionKey key = transaction.execution();
            AgentStore.ConversationKey conversation = key.conversation();
            AgentIdentity identity = facts.identity();
            if (!identity.tenantId().equals(conversation.tenantId())
                || !identity.threadId().equals(conversation.threadId())
                || !identity.userId().equals(conversation.userId())
                || !identity.turnId().equals(key.turnId())) {
                throw new SQLException("Plan execution binding does not match its Store transaction");
            }
        }
    }

    /**
     * Plan context 持久载荷只包含后续恢复、权限与审查所需的冻结身份。
     * @param planId 绑定的 Plan 标识
     * @param runId 本次计划执行的 Run 标识
     * @param planRevisionId 执行所依据的冻结修订标识
     * @param planHash 冻结修订的规范摘要
     * @param contextJson 持久化的不可变恢复上下文
     */
    private record PlanBinding(String planId,
                               String runId,
                               String planRevisionId,
                               String planHash,
                               String contextJson) {
        /** 校验冻结身份完整，避免持久 JSON 包含可空占位字段。 */
        private PlanBinding {
            PlanModels.requireId(planId, "planId");
            PlanModels.requireId(runId, "runId");
            PlanModels.requireId(planRevisionId, "planRevisionId");
            if (planHash == null || !planHash.matches("[0-9a-f]{64}")
                || contextJson == null || contextJson.isBlank()) {
                throw new IllegalArgumentException("invalid frozen Plan binding");
            }
        }
    }
}
