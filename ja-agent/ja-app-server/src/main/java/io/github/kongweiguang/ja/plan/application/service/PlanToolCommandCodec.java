// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.service;

import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonBoolean;
import io.github.kongweiguang.ja.agent.domain.json.JsonNull;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.plan.application.gateway.PlanCanonicalizer;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.application.api.PlanUseCase;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 将模型调用解码为闭集、不可变的 Plan 命令，供准入校验与事务 applier 共用。
 */
public final class PlanToolCommandCodec {
    /**
     * 解码仅依赖 Core 的不可变 JSON 值，避免应用服务绑定 Jackson 等基础设施实现。
     */
    public PlanToolCommandCodec() {
    }

    /**
     * 为同一稳定工具调用生成幂等领域身份，重放/回执核验不会插入第二组事件。
     */
    private static String stableId(String prefix, String callKey, String purpose) {
        return prefix + PlanDigest.sha256(callKey + '\0' + purpose);
    }

    /**
     * 把 Agent 提交的证据声明固定到调用身份与接受时间，不以工具正文直接授予验收资格。
     */
    private static List<PlanRepository.ToolEvidenceClaim> evidenceClaims(
            List<PlanUseCase.ToolEvidenceClaim> claims, Instant at, String callKey) {
        List<PlanUseCase.ToolEvidenceClaim> source = List.copyOf(Objects.requireNonNull(claims, "claims"));
        List<PlanRepository.ToolEvidenceClaim> result = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            PlanUseCase.ToolEvidenceClaim claim = source.get(index);
            result.add(new PlanRepository.ToolEvidenceClaim(stableId("evidence_", callKey, "claim:" + index),
                    claim.criterionId(), claim.callId(), claim.summary(), at));
        }
        return List.copyOf(result);
    }

    /**
     * 直接读取 Core 的闭集 JSON 值，避免应用服务绑定某一种 JSON 基础设施。
     */
    private static PlanModels.PlanDefinition definition(JsonValue value) {
        JsonObject node = object(value);
        exact(node, "objective", "scope", "nonGoals", "constraints", "dependencies", "steps",
                "acceptanceCriteria", "risks", "verificationStrategy");
        List<PlanModels.PlanStep> steps = array(required(node, "steps"), 256).stream()
                .map(PlanToolCommandCodec::planStep).toList();
        List<PlanModels.AcceptanceCriterion> criteria = array(required(node, "acceptanceCriteria"),
                256).stream().map(PlanToolCommandCodec::criterion).toList();
        try {
            return new PlanModels.PlanDefinition(text(node, "objective"),
                    textArray(node, "scope"), textArray(node, "nonGoals"), textArray(node, "constraints"),
                    textArray(node, "dependencies"), steps, criteria, textArray(node, "risks"),
                    textArray(node, "verificationStrategy"));
        } catch (IllegalArgumentException failure) {
            throw invalid(failure);
        }
    }

    /**
     * Plan step 的必需字段精确匹配定义，防止未知字段改变批准后的隐含执行含义。
     */
    private static PlanModels.PlanStep planStep(JsonValue value) {
        JsonObject node = object(value);
        exact(node, "stepId", "title", "description", "required", "dependsOn");
        return new PlanModels.PlanStep(text(node, "stepId"), text(node, "title"), textAllowEmpty(node, "description"),
                bool(node, "required"), textArray(node, "dependsOn", 64));
    }

    /**
     * 验收 criterion 的结构与布尔类型显式解码，不接受 Jackson 风格的类型 coercion。
     */
    private static PlanModels.AcceptanceCriterion criterion(JsonValue value) {
        JsonObject node = object(value);
        exact(node, "criterionId", "description", "required");
        return new PlanModels.AcceptanceCriterion(text(node, "criterionId"), text(node, "description"),
                bool(node, "required"));
    }

    /**
     * claim 只接受闭合对象、非空摘要及唯一 criterion/call 组合。
     */
    private static List<PlanUseCase.ToolEvidenceClaim> evidenceClaims(JsonObject node) {
        List<JsonValue> values = array(required(node, "evidenceClaims"), 64);
        Set<String> identities = new HashSet<>();
        List<PlanUseCase.ToolEvidenceClaim> result = new ArrayList<>(values.size());
        for (JsonValue value : values) {
            JsonObject claim = object(value);
            exact(claim, "criterionId", "callId", "summary");
            String criterion = text(claim, "criterionId");
            String call = text(claim, "callId");
            String summary = text(claim, "summary");
            if (!identities.add(criterion + '\n' + call)) {
                throw invalid();
            }
            result.add(new PlanUseCase.ToolEvidenceClaim(criterion, call, summary));
        }
        return List.copyOf(result);
    }

    /**
     * 结构字段精确闭合，避免绕过 JSON Schema 直调时夹带权限或 aggregate 选择字段。
     */
    private static void exact(JsonObject node, String... fields) {
        exact(node, Set.of(fields));
    }

    /**
     * 集合比较同时要求所有字段存在，显式 JSON null 仍保留为合法字段值。
     */
    private static void exact(JsonObject node, Set<String> expected) {
        if (!node.members().keySet().equals(expected)) {
            throw invalid();
        }
    }

    /**
     * 必需成员必须存在；缺失与显式 JsonNull 保持不同错误路径。
     */
    private static JsonValue required(JsonObject node, String field) {
        JsonValue value = node.get(field);
        if (value == null) {
            throw invalid();
        }
        return value;
    }

    /**
     * 必需文本拒绝数字、对象、null 和空白，避免弱类型转换越过领域边界。
     */
    private static String text(JsonObject node, String field) {
        JsonValue value = required(node, field);
        if (!(value instanceof JsonText(String value1)) || value1.isBlank()) {
            throw invalid();
        }
        return value1;
    }

    /**
     * 计划步骤描述允许空串但仍须保持字符串类型，其他身份与说明字段要求非空。
     */
    private static String textAllowEmpty(JsonObject node, String field) {
        JsonValue value = required(node, field);
        if (!(value instanceof JsonText(String value1))) {
            throw invalid();
        }
        return value1;
    }

    /**
     * 可空身份字段只接受字符串或显式 JSON null。
     */
    private static String optionalText(JsonObject node, String field) {
        JsonValue value = required(node, field);
        if (value == JsonNull.INSTANCE) {
            return null;
        }
        if (!(value instanceof JsonText(String value1))) {
            throw invalid();
        }
        return value1;
    }

    /**
     * CAS revision 必须是无小数位且可无损表示为非负 long 的 JSON 数字。
     */
    private static long integer(JsonObject node, String field) {
        JsonValue value = required(node, field);
        if (!(value instanceof JsonNumber(java.math.BigDecimal value1)) || value1.scale() > 0) {
            throw invalid();
        }
        try {
            long result = value1.longValueExact();
            if (result < 0) {
                throw invalid();
            }
            return result;
        } catch (ArithmeticException failure) {
            throw invalid(failure);
        }
    }

    /**
     * 必需布尔拒绝字符串或数字形式的真值，保持 Tool Schema 与领域事实一致。
     */
    private static boolean bool(JsonObject node, String field) {
        JsonValue value = required(node, field);
        if (!(value instanceof JsonBoolean booleanValue)) {
            throw invalid();
        }
        return booleanValue.value();
    }

    /**
     * 列表成员类型和长度在进入 PlanDefinition 前就验证，避免不完整状态进入持久层。
     */
    private static List<String> textArray(JsonObject node, String field) {
        return textArray(node, field, 128);
    }

    /**
     * ID 列表使用更小的约束上限，避免步骤依赖形成不可控的关系矩阵。
     */
    private static List<String> textArray(JsonObject node, String field, int maximum) {
        return array(required(node, field), maximum).stream().map(PlanToolCommandCodec::textValue).toList();
    }

    /**
     * 读取有界 JSON 数组并返回不可变元素快照。
     */
    private static List<JsonValue> array(JsonValue value, int maximum) {
        if (!(value instanceof JsonArray(List<JsonValue> values)) || values.size() > maximum) {
            throw invalid();
        }
        return values;
    }

    /**
     * 列表字符串必须有实际内容，避免空依赖或空准则描述进入冻结版本。
     */
    private static String textValue(JsonValue value) {
        if (!(value instanceof JsonText(String value1)) || value1.isBlank()) {
            throw invalid();
        }
        return value1;
    }

    /**
     * 嵌套定义只允许 JSON object，不把数组或标量替换成空定义。
     */
    private static JsonObject object(JsonValue value) {
        if (!(value instanceof JsonObject object)) {
            throw invalid();
        }
        return object;
    }

    /**
     * 小写 wire 状态显式映射到领域枚举，不容忍未知状态回退。
     */
    private static PlanModels.StepStatus status(JsonObject node, String field) {
        try {
            return PlanModels.StepStatus.valueOf(text(node, field).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw invalid(failure);
        }
    }

    /**
     * 参数解析错误统一为可回给模型纠正的输入异常，不暴露 JSON 实现细节。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid Plan Tool command");
    }

    /**
     * 保留数值或枚举解析原因供 capability 转成稳定错误码，不暴露内部异常正文。
     */
    private static IllegalArgumentException invalid(Throwable cause) {
        return new IllegalArgumentException("invalid Plan Tool command", cause);
    }

    /**
     * 在工具调用阶段校验完整命令；返回的 closure 只在 Store participant 内执行。
     */
    public Command decode(String toolName, JsonObject arguments, Instant acceptedAt) {
        String name = Objects.requireNonNull(toolName, "toolName");
        JsonObject frozenArguments = Objects.requireNonNull(arguments, "arguments");
        Instant at = Objects.requireNonNull(acceptedAt, "acceptedAt");
        try {
            return switch (name) {
                case "plan_propose" -> planPropose(frozenArguments, at);
                case "plan_draft_update" -> planDraftUpdate(frozenArguments, at);
                case "plan_step_update" -> planStepUpdate(frozenArguments, at);
                default -> throw invalid();
            };
        } catch (IllegalArgumentException failure) {
            throw invalid(failure);
        }
    }

    /**
     * 计划提案仍以模型作者来源写入，最终授权与 revision CAS 由 repository 再次核验。
     */
    private Command planPropose(JsonObject node, Instant at) {
        exact(node, "planId", "expectedPlanRevision", "definition", "operationKey");
        PlanUseCase.Propose request = new PlanUseCase.Propose(text(node, "planId"),
                integer(node, "expectedPlanRevision"), definition(required(node, "definition")), false,
                text(node, "operationKey"), at);
        return new Command("plan_propose", request.planId(), node, at, (plans, canonical, generation, callKey) -> {
            PlanCanonicalizer.Encoded encoded = canonical.encode(request.definition());
            PlanModels.PlanRevision revision = new PlanModels.PlanRevision(stableId("planrev_", callKey, "revision"),
                    request.planId(), 1, request.definition(), encoded.json(), encoded.sha256(), "AGENT", request.at());
            plans.propose(new PlanRepository.ProposePlan(request.planId(), request.expectedPlanRevision(),
                    revision, stableId("evt_", callKey, "event"), request.operationKey(), request.at()));
        });
    }

    /**
     * 草稿更新复核 Plan 与 draft 两级 CAS，并保留批准基线是否为空的原始语义。
     */
    private Command planDraftUpdate(JsonObject node, Instant at) {
        exact(node, "planId", "expectedPlanRevision", "expectedDraftRevision", "definition",
                "basedOnPlanRevisionId", "operationKey");
        PlanUseCase.SaveDraft request = new PlanUseCase.SaveDraft(text(node, "planId"),
                integer(node, "expectedPlanRevision"), integer(node, "expectedDraftRevision"),
                definition(required(node, "definition")), optionalText(node, "basedOnPlanRevisionId"),
                text(node, "operationKey"), at);
        return new Command("plan_draft_update", request.planId(), node, at,
                (plans, canonical, generation, callKey) -> {
                    PlanCanonicalizer.Encoded encoded = canonical.encode(request.definition());
                    plans.saveDraft(new PlanRepository.SaveDraft(request.planId(),
                            request.expectedPlanRevision(), request.expectedDraftRevision(), encoded.json(),
                            request.basedOnPlanRevisionId(), stableId("evt_", callKey, "event"),
                            request.operationKey(), request.at()));
                });
    }

    /**
     * 步骤更新仅以 Plan CAS 入账；封闭字段集拒绝旧 target 和 Plan 身份。
     */
    private Command planStepUpdate(JsonObject node, Instant at) {
        exact(node, "planId", "expectedPlanRevision", "runId", "stepId", "expectedStatus",
                "status", "failureSignature", "evidenceClaims", "operationKey");
        String runId = text(node, "runId");
        String stepId = text(node, "stepId");
        PlanModels.StepStatus expected = status(node, "expectedStatus");
        PlanModels.StepStatus updated = status(node, "status");
        String failure = optionalText(node, "failureSignature");
        List<PlanUseCase.ToolEvidenceClaim> claims = evidenceClaims(node);
        String operationKey = text(node, "operationKey");
        String planId = text(node, "planId");
        PlanUseCase.PlanStepUpdate request = new PlanUseCase.PlanStepUpdate(planId,
                integer(node, "expectedPlanRevision"), runId, stepId, expected, updated, failure,
                claims, operationKey, at);
        return new Command("plan_step_update", planId, node, at, (plans, canonical, generation, callKey) ->
                plans.updatePlanStep(new PlanRepository.UpdatePlanStep(request.planId(), request.expectedPlanRevision(),
                        request.runId(), request.stepId(), request.expectedStatus(), request.status(),
                        request.failureSignature(), evidenceClaims(request.evidenceClaims(), request.at(), callKey),
                        stableId("evt_", callKey, "event"), request.operationKey(), request.at())));
    }

    /**
     * 执行能力是 codec 生成的闭集仓储命令，不接受任意 SQL 或反射目标。
     */
    @FunctionalInterface
    public interface Mutation {
        /**
         * 用冻结 CAS 与幂等 identity 执行 Repository 写入；调用方通过 Store 回执判断事务结果。
         */
        void apply(PlanRepository plans, PlanCanonicalizer canonical,
                   long processGeneration, String callKey);
    }

    /**
     * 冻结通过闭集校验的命令值；持久参与者以同一事务连接调用 Repository。
     *
     * @param toolName 工具名称
     * @param aggregateId 聚合标识
     * @param arguments 工具调用参数
     * @param acceptedAt 受理时间
     * @param mutation 状态变更
     */
    public record Command(String toolName,
                          String aggregateId,
                          JsonObject arguments,
                          Instant acceptedAt,
                          Mutation mutation) {
        /**
         * 持久化前捕获不可变命令输入，避免在核心 Store 事务中调用会发布事件的应用服务。
         */
        public Command {
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(aggregateId, "aggregateId");
            Objects.requireNonNull(arguments, "arguments");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            Objects.requireNonNull(mutation, "mutation");
        }

        /**
         * 仅由已验证同事务工具结果的 Ja applier 调用，Repository 会沿用活动 JDBC 连接；
         * 此命令不返回领域对象，避免把事务内部投影误当成对外工具结果。
         */
        public void apply(PlanRepository plans, PlanCanonicalizer canonical,
                          long processGeneration, String callKey) {
            mutation.apply(Objects.requireNonNull(plans, "plans"),
                    Objects.requireNonNull(canonical, "canonical"), processGeneration,
                    Objects.requireNonNull(callKey, "callKey"));
        }
    }
}



