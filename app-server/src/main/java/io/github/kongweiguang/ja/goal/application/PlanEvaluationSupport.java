// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.goal.domain.GoalModels;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Plan evaluator 的结构化输入、严格输出解析和本地一致性门；不执行 IO 或模型调用。 */
final class PlanEvaluationSupport {
    private static final int MAX_OUTPUT_TEXT = 4_000;
    private static final String SYSTEM_PROMPT = "You are an independent acceptance evaluator. "
            + "Use only the supplied frozen plan and evidence. Never request or call tools. "
            + "Return exactly one JSON object with verdict, summary, and criteria. "
            + "A required criterion without matching evidence is inconclusive.";

    /** 冻结输入供 adapter 计算 prompt revision，并把缺证据状态显式交给模型。 */
    record Prepared(String json, String digest, boolean evidenceComplete, boolean stepsComplete) { }

    /** 已严格验证的模型结论；criteria 必须覆盖 revision 中的全部验收条件。 */
    record Parsed(GoalModels.EvaluationVerdict verdict,
                  List<GoalModels.CriterionEvaluation> criteria, String summary) {
        /** 防御性冻结结论，避免异步回调改变完成门依据。 */
        Parsed {
            criteria = List.copyOf(criteria);
            if (summary == null || summary.isBlank() || summary.length() > MAX_OUTPUT_TEXT) {
                throw new IllegalArgumentException("invalid evaluator summary");
            }
        }
    }

    /** 生成不包含凭据、路径和原始 Tool 正文的 canonical evaluator payload。 */
    static Prepared prepare(ObjectMapper json, GoalModels.PlanSnapshot snapshot,
                            List<GoalModels.Evidence> evidence) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(evidence, "evidence");
        GoalModels.Plan plan = snapshot.plan();
        GoalModels.PlanRevision revision = snapshot.currentRevision();
        if (revision == null || !plan.planId().equals(revision.planId())
                || !Objects.equals(plan.activePlanRevisionId(), revision.planRevisionId())
                || plan.activeRunId() == null || plan.status() != GoalModels.PlanStatus.VERIFYING) {
            throw new IllegalArgumentException("Plan evaluator identity is unavailable");
        }
        Map<String, GoalModels.PlanStep> definitions = new HashMap<>();
        for (GoalModels.PlanStep step : revision.definition().steps()) {
            if (definitions.put(step.stepId(), step) != null) throw new IllegalArgumentException("duplicate step");
        }
        Map<String, GoalModels.AcceptanceCriterion> criteria = new HashMap<>();
        for (GoalModels.AcceptanceCriterion criterion : revision.definition().acceptanceCriteria()) {
            if (criteria.put(criterion.criterionId(), criterion) != null) {
                throw new IllegalArgumentException("duplicate criterion");
            }
        }
        Map<String, GoalModels.StepExecution> executions = new HashMap<>();
        boolean stepsComplete = true;
        for (GoalModels.StepExecution execution : snapshot.stepExecutions()) {
            if (!plan.activeRunId().equals(execution.runId()) || !definitions.containsKey(execution.stepId())
                    || executions.put(execution.stepId(), execution) != null) {
                throw new IllegalArgumentException("step execution identity is invalid");
            }
        }
        for (GoalModels.PlanStep step : revision.definition().steps()) {
            GoalModels.StepExecution execution = executions.get(step.stepId());
            if (execution == null) stepsComplete = false;
            else if (step.required() && execution.status() != GoalModels.StepStatus.SUCCEEDED) stepsComplete = false;
        }
        Set<String> evidenced = new HashSet<>();
        boolean evidenceComplete = true;
        for (GoalModels.Evidence item : evidence) {
            if (!plan.planId().equals(item.planId()) || !plan.activeRunId().equals(item.runId())
                    || !revision.planRevisionId().equals(item.planRevisionId())
                    || (item.criterionId() != null && !criteria.containsKey(item.criterionId()))
                    || item.sourceId() == null || item.sourceId().isBlank()
                    || item.digest() == null || !item.digest().matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("evidence identity is invalid");
            }
            // Tool ledger 自动证据没有 criterion；保留为验收上下文，但只有显式绑定才满足必要条件。
            if (item.criterionId() != null) evidenced.add(item.criterionId());
        }
        for (GoalModels.AcceptanceCriterion criterion : revision.definition().acceptanceCriteria()) {
            if (criterion.required() && !evidenced.contains(criterion.criterionId())) evidenceComplete = false;
        }
        try {
            ObjectNode payload = json.createObjectNode();
            payload.put("planId", plan.planId());
            payload.put("ownerThreadId", plan.ownerThreadId());
            payload.put("runId", plan.activeRunId());
            payload.put("planRevisionId", revision.planRevisionId());
            payload.put("planHash", revision.planHash());
            payload.put("objective", revision.definition().objective());
            payload.put("stepsComplete", stepsComplete);
            payload.put("evidenceComplete", evidenceComplete);
            JsonNode canonicalPlan = json.readTree(revision.canonicalJson());
            if (canonicalPlan == null || !canonicalPlan.isObject()) {
                throw new IllegalArgumentException("Plan canonical JSON is not an object");
            }
            payload.set("plan", canonicalPlan);
            ArrayNode statuses = payload.putArray("stepExecutions");
            for (GoalModels.PlanStep step : revision.definition().steps()) {
                GoalModels.StepExecution execution = executions.get(step.stepId());
                ObjectNode item = statuses.addObject();
                item.put("stepId", step.stepId());
                item.put("required", step.required());
                item.put("status", execution == null ? "MISSING" : execution.status().name().toLowerCase(Locale.ROOT));
            }
            ArrayNode criterionNodes = payload.putArray("criteria");
            for (GoalModels.AcceptanceCriterion criterion : revision.definition().acceptanceCriteria()) {
                ObjectNode item = criterionNodes.addObject();
                item.put("criterionId", criterion.criterionId());
                item.put("description", criterion.description());
                item.put("required", criterion.required());
                item.put("evidencePresent", evidenced.contains(criterion.criterionId()));
            }
            ArrayNode evidenceNodes = payload.putArray("evidence");
            for (GoalModels.Evidence item : evidence) {
                ObjectNode node = evidenceNodes.addObject();
                node.put("criterionId", item.criterionId());
                node.put("sourceType", item.sourceType().name().toLowerCase(Locale.ROOT));
                node.put("sourceId", item.sourceId());
                node.put("summary", boundedSummary(item.summary()));
                node.put("digest", item.digest());
            }
            String encoded = json.writeValueAsString(payload);
            return new Prepared(encoded, digest(SYSTEM_PROMPT + '\n' + encoded), evidenceComplete, stepsComplete);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Plan evaluator input is invalid", failure);
        }
    }

    /** Provider 输出必须是无围栏单 object，且 criteria 与冻结 revision 的集合完全一致。 */
    static Parsed decode(ObjectMapper json, String value,
                         List<GoalModels.AcceptanceCriterion> expected, boolean evidenceComplete,
                         boolean stepsComplete) {
        try {
            JsonNode root = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
            if (root == null || !root.isObject() || root.size() != 3
                    || !root.has("verdict") || !root.has("summary") || !root.has("criteria")
                    || !root.get("verdict").isTextual() || !root.get("summary").isTextual()
                    || !root.get("criteria").isArray()) throw new IllegalArgumentException("invalid evaluator JSON");
            String summary = root.get("summary").textValue();
            if (summary == null || summary.isBlank() || summary.length() > 1_000) {
                throw new IllegalArgumentException("invalid evaluator summary");
            }
            Map<String, GoalModels.AcceptanceCriterion> expectedById = new HashMap<>();
            for (GoalModels.AcceptanceCriterion criterion : expected) {
                expectedById.put(criterion.criterionId(), criterion);
            }
            List<GoalModels.CriterionEvaluation> parsed = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode item : root.get("criteria")) {
                if (!item.isObject() || item.size() != 3 || !item.has("criterionId")
                        || !item.has("verdict") || !item.has("reason")
                        || !item.get("criterionId").isTextual() || !item.get("verdict").isTextual()
                        || !item.get("reason").isTextual()) {
                    throw new IllegalArgumentException("invalid evaluator criterion");
                }
                String criterionId = item.get("criterionId").textValue();
                if (!seen.add(criterionId) || !expectedById.containsKey(criterionId)) {
                    throw new IllegalArgumentException("criterion set does not match revision");
                }
                String reason = item.get("reason").textValue();
                if (reason.isBlank() || reason.length() > 1_000) {
                    throw new IllegalArgumentException("invalid criterion reason");
                }
                parsed.add(new GoalModels.CriterionEvaluation(criterionId,
                        verdict(item.get("verdict").textValue()), reason));
            }
            if (seen.size() != expected.size()) throw new IllegalArgumentException("criterion set is incomplete");
            GoalModels.EvaluationVerdict overall = verdict(root.get("verdict").textValue());
            boolean requiredNotMet = false;
            boolean requiredInconclusive = !evidenceComplete;
            for (GoalModels.CriterionEvaluation item : parsed) {
                if (expectedById.get(item.criterionId()).required()) {
                    requiredNotMet |= item.verdict() == GoalModels.EvaluationVerdict.NOT_MET;
                    requiredInconclusive |= item.verdict() == GoalModels.EvaluationVerdict.INCONCLUSIVE;
                }
            }
            if (requiredInconclusive || !stepsComplete) {
                if (overall == GoalModels.EvaluationVerdict.MET) {
                    throw new IllegalArgumentException("model claimed MET without complete facts");
                }
                if (requiredInconclusive) overall = GoalModels.EvaluationVerdict.INCONCLUSIVE;
            }
            if (overall == GoalModels.EvaluationVerdict.MET && requiredNotMet) {
                throw new IllegalArgumentException("model verdict contradicts required criterion");
            }
            if (overall == GoalModels.EvaluationVerdict.NOT_MET && !requiredNotMet && requiredInconclusive) {
                overall = GoalModels.EvaluationVerdict.INCONCLUSIVE;
            }
            return new Parsed(overall, parsed, formatSummary(summary, parsed));
        } catch (JsonProcessingException | RuntimeException failure) {
            throw new IllegalArgumentException("Plan evaluator output is invalid", failure);
        }
    }

    /** 将逐 criterion verdict 压缩到既有 Evaluation.summary，避免把完成门变成自然语言解析。 */
    private static String formatSummary(String summary, List<GoalModels.CriterionEvaluation> criteria) {
        StringBuilder value = new StringBuilder(summary).append("\nCriteria:");
        for (GoalModels.CriterionEvaluation criterion : criteria) {
            value.append(' ').append(criterion.criterionId()).append('=').append(
                    criterion.verdict().name().toLowerCase(Locale.ROOT)).append(';');
        }
        if (value.length() > 4_000) throw new IllegalArgumentException("evaluator summary is too large");
        return value.toString();
    }

    /** Evidence 摘要只允许有限文本，原始 Tool 正文必须留在专属账本而不是模型验收请求。 */
    private static String boundedSummary(String value) {
        if (value == null || value.isBlank() || value.length() > 2_000
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid evidence summary");
        }
        return value;
    }

    /** wire verdict 是显式小写闭集，未知值不能静默降级为 inconclusive。 */
    private static GoalModels.EvaluationVerdict verdict(String value) {
        return GoalModels.EvaluationVerdict.valueOf(value.toUpperCase(Locale.ROOT));
    }

    /** Prompt revision 覆盖完整 system+payload，防止 evaluator continuation 跨 revision 复用。 */
    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Provider 只需看到固定的 evaluator system contract，调用方不能把普通 Agent prompt 混入。 */
    static String systemPrompt() { return SYSTEM_PROMPT; }

    /** 工具类不持有状态，避免误创建一个看似可配置但没有生命周期的 evaluator helper。 */
    private PlanEvaluationSupport() { }
}
