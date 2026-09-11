// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestUsage;
import io.github.kongweiguang.ja.goal.application.PlanEvaluationAuditPort;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Plan evaluator 审计适配器；intent 与 usage 通过 MybatisUnitOfWork 使用同一 SQLite 事务 owner。 */
public final class MybatisPlanEvaluationAuditRepository implements PlanEvaluationAuditPort {
    private final MybatisUnitOfWork transactions;
    private final ObjectMapper json;

    /** 生产构造复用 Solon 管理的 MyBatis session factory，不自行提交事务。 */
    public MybatisPlanEvaluationAuditRepository(SqlSessionFactory sessions, ObjectMapper json) {
        this.transactions = new MybatisUnitOfWork(sessions);
        this.json = Objects.requireNonNull(json, "json");
    }

    /** 聚焦 SQLite 测试注入真实 transaction owner，不在生产路径增加测试分支。 */
    public MybatisPlanEvaluationAuditRepository(SqlSessionFactory sessions, ObjectMapper json,
                                                MybatisUnitOfWork.SessionOwner owner) {
        this.transactions = new MybatisUnitOfWork(sessions, owner);
        this.json = Objects.requireNonNull(json, "json");
    }

    /** 查询 identity 终态；成功请求同时恢复完整结论，避免重启后再次付费调用。 */
    @Override
    public Optional<Prior> find(String requestId) {
        return transactions.required(mappers -> Optional.ofNullable(
                mappers.planEvaluations().selectPrior(requestId)).map(row ->
                        prior(row)));
    }

    /** intent 先原子预留 Run 的模型轮次，再插入审计身份；任一步失败都会回滚预留。 */
    @Override
    public void recordIntent(Intent intent) {
        String profile = profileJson(intent.profile());
        transactions.required(mappers -> {
            PersistenceRecords.PlanEvaluationIntentInsert values = new PersistenceRecords.PlanEvaluationIntentInsert(
                    intent.requestId(), intent.planId(), intent.planRevisionId(), intent.runId(),
                    intent.ownerThreadId(), intent.inputDigest(), profile, intent.startedAt().toString());
            int reserved = mappers.planEvaluations().reserveModelRound(values);
            if (reserved != 1) throw new IllegalStateException("Plan evaluator model budget is exhausted");
            int inserted = mappers.planEvaluations().insertIntent(values);
            if (inserted != 1) throw new IllegalStateException("Plan evaluator intent already exists");
            return null;
        });
    }

    /** usage 先原子结算活动时间再结算请求终态，UNKNOWN 也计入预算且重复提交不重复计量。 */
    @Override
    public void recordUsage(Usage usage) {
        ProviderRequestUsage facts = usage.providerUsage();
        var modelUsage = facts.usage();
        String criteriaJson = usage.evaluation() == null ? null : criteriaJson(usage.evaluation().criteria());
        transactions.required(mappers -> {
            PersistenceRecords.PlanEvaluationUsageUpdate values = new PersistenceRecords.PlanEvaluationUsageUpdate(
                    usage.requestId(), usage.planId(), usage.planRevisionId(), usage.runId(),
                    usage.outcome().name(), facts.certainty().name(),
                    modelUsage == null ? null : modelUsage.inputTokens(),
                    modelUsage == null ? null : modelUsage.outputTokens(),
                    modelUsage == null ? null : modelUsage.totalTokens(),
                    usage.evaluation() == null ? null : usage.evaluation().verdict().name(), criteriaJson,
                    usage.evaluation() == null ? null : usage.evaluation().summary(),
                    usage.completedAt().toString());
            int activeUpdated = mappers.planEvaluations().settleActiveMillis(values);
            if (activeUpdated != 1) throw new IllegalStateException("Plan evaluator active budget settlement is stale");
            int updated = mappers.planEvaluations().settleUsage(values);
            if (updated != 1) throw new IllegalStateException("Plan evaluator usage settlement is stale");
            return null;
        });
    }

    /** 将持久行恢复为严格结构化结论；损坏审计记录必须让恢复路径停在 INCONCLUSIVE。 */
    private Prior prior(PersistenceRecords.PlanEvaluationPriorRow row) {
        Outcome outcome = Outcome.valueOf(row.outcome());
        EvaluationResult evaluation = null;
        if (outcome == Outcome.SUCCEEDED) {
            if (row.verdict() == null || row.criteriaJson() == null || row.summary() == null) {
                throw new IllegalStateException("successful evaluator audit has incomplete result");
            }
            evaluation = new EvaluationResult(verdict(row.verdict()), decodeCriteria(row.criteriaJson()), row.summary());
        } else if (row.verdict() != null || row.criteriaJson() != null || row.summary() != null) {
            throw new IllegalStateException("non-success evaluator audit has a result");
        }
        return new Prior(row.requestId(), outcome, evaluation);
    }

    /** criteria JSON 是仅供审计恢复的固定三字段数组，不接受未知字段或重复 criterion。 */
    private List<GoalModels.CriterionEvaluation> decodeCriteria(String encoded) {
        try {
            JsonNode root = json.readTree(encoded);
            if (root == null || !root.isArray()) throw new IllegalArgumentException("criteria is not an array");
            List<GoalModels.CriterionEvaluation> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode item : root) {
                if (!item.isObject() || item.size() != 3 || !item.has("criterionId")
                        || !item.has("verdict") || !item.has("reason")
                        || !item.get("criterionId").isTextual() || !item.get("verdict").isTextual()
                        || !item.get("reason").isTextual()) {
                    throw new IllegalArgumentException("invalid persisted criterion");
                }
                String criterionId = item.get("criterionId").textValue();
                if (!seen.add(criterionId)) throw new IllegalArgumentException("duplicate persisted criterion");
                result.add(new GoalModels.CriterionEvaluation(criterionId,
                        verdict(item.get("verdict").textValue()), item.get("reason").textValue()));
            }
            return List.copyOf(result);
        } catch (JsonProcessingException | RuntimeException failure) {
            throw new IllegalStateException("persisted evaluator criteria are invalid", failure);
        }
    }

    /** 审计写入使用稳定字段顺序，恢复时不依赖自然语言 summary 反解析。 */
    private String criteriaJson(List<GoalModels.CriterionEvaluation> criteria) {
        try {
            ArrayNode root = json.createArrayNode();
            for (GoalModels.CriterionEvaluation criterion : criteria) {
                ObjectNode item = root.addObject();
                item.put("criterionId", criterion.criterionId());
                item.put("verdict", criterion.verdict().name());
                item.put("reason", criterion.reason());
            }
            return json.writeValueAsString(root);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("evaluator criteria cannot be persisted", failure);
        }
    }

    /** 结论闭集保持与 evaluator wire contract 一致，未知持久值不能静默降级。 */
    private static GoalModels.EvaluationVerdict verdict(String value) {
        return GoalModels.EvaluationVerdict.valueOf(value.toUpperCase(Locale.ROOT));
    }

    /** Profile 只保存非敏感 Provider/Model/权限事实，显式字段映射避免 Native 反射漂移。 */
    private String profileJson(ProviderRequestProfile profile) {
        try {
            ObjectNode value = json.createObjectNode();
            value.put("providerId", profile.providerId());
            value.put("modelId", profile.modelId());
            value.put("api", profile.api());
            value.put("upstreamModel", profile.upstreamModel());
            value.put("requestedReasoning", profile.requestedReasoning());
            value.put("effectiveReasoning", profile.effectiveReasoning());
            value.put("accessMode", profile.accessMode().name());
            value.put("collaborationMode", profile.collaborationMode().name());
            value.put("configGeneration", profile.configGeneration());
            value.put("promptRevision", profile.promptRevision());
            value.put("toolCatalogRevision", profile.toolCatalogRevision());
            value.put("contextWindowTokens", profile.contextWindowTokens());
            value.put("maxOutputTokens", profile.maxOutputTokens());
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Plan evaluator profile cannot be persisted", failure);
        }
    }
}
