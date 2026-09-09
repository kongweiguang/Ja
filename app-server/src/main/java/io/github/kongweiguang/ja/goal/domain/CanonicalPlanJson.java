// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.goal.domain.GoalModels.AcceptanceCriterion;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanDefinition;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanStep;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** 为 PlanRevision 生成字段顺序稳定、无空白的 JSON 与 SHA-256。 */
public final class CanonicalPlanJson {
    private final ObjectMapper mapper;

    /** 复用 composition ObjectMapper，但手工构树固定权威字段和顺序。 */
    public CanonicalPlanJson(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** 先验证 DAG 再编码，hash 永远对应可执行的结构化定义。 */
    public Encoded encode(PlanDefinition definition) {
        PlanPolicy.validate(definition);
        try {
            String json = mapper.writeValueAsString(node(definition));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new Encoded(json, HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        } catch (Exception failure) {
            throw new IllegalArgumentException("cannot encode canonical plan", failure);
        }
    }

    /** 显式构树避免 Java record 反射顺序或 ObjectMapper feature 改变 hash。 */
    private ObjectNode node(PlanDefinition definition) {
        ObjectNode root = mapper.createObjectNode();
        root.put("objective", definition.objective());
        strings(root.putArray("scope"), definition.scope());
        strings(root.putArray("nonGoals"), definition.nonGoals());
        strings(root.putArray("constraints"), definition.constraints());
        strings(root.putArray("dependencies"), definition.dependencies());
        ArrayNode steps = root.putArray("steps");
        for (PlanStep step : definition.steps()) {
            ObjectNode value = steps.addObject();
            value.put("stepId", step.stepId());
            value.put("title", step.title());
            value.put("description", step.description());
            value.put("required", step.required());
            strings(value.putArray("dependsOn"), step.dependsOn());
        }
        ArrayNode criteria = root.putArray("acceptanceCriteria");
        for (AcceptanceCriterion criterion : definition.acceptanceCriteria()) {
            ObjectNode value = criteria.addObject();
            value.put("criterionId", criterion.criterionId());
            value.put("description", criterion.description());
            value.put("required", criterion.required());
        }
        strings(root.putArray("risks"), definition.risks());
        strings(root.putArray("verificationStrategy"), definition.verificationStrategy());
        return root;
    }

    /** 数组顺序是计划语义的一部分，不能像对象键一样重排。 */
    private static void strings(ArrayNode target, Iterable<String> values) {
        for (String value : values) target.add(value);
    }

    /** canonical JSON 与 hash 作为不可拆分结果传递，避免调用方对不同正文重复散列。 */
    public record Encoded(String json, String sha256) {
        /** 只接受本编码器产生的完整结果。 */
        public Encoded {
            Objects.requireNonNull(json, "json");
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid plan hash");
            }
        }
    }
}
