// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.adapter.in.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.port.out.AgentCapability;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JacksonJsonValues;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.foundation.json.JsonValue;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.in.GoalUseCase;
import io.github.kongweiguang.ja.goal.port.out.GoalRepositoryException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 将 Plan/Goal 用例适配为请求级 Agent 能力；领域状态机、CAS 与持久化仍完全由 GoalUseCase 拥有。
 */
public final class PlanGoalAgentCapability implements AgentCapability {
    private static final String INSTRUCTIONS = "Plan revision requires explicit user approval. "
            + "Use structured Goal tools; never self-approve or self-achieve.";
    private final GoalUseCase goals;
    private final ObjectMapper json;
    private final Clock clock;
    private final List<ToolSpec> toolSpecs;

    /** 构造时只冻结静态 Tool 契约；请求相关 Goal/Plan 身份延迟到 prepare 安全点读取。 */
    public PlanGoalAgentCapability(GoalUseCase goals, ObjectMapper json, Clock clock) {
        this.goals = Objects.requireNonNull(goals, "goals");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.toolSpecs = List.of(
                tool("plan_propose", "Freeze a structured revision for an existing standalone Plan.",
                        schema("planId,expectedPlanRevision,definition,idempotencyKey")),
                tool("plan_step_update", "Update one stable step execution with optimistic state checks.",
                        stepUpdateSchema()),
                tool("goal_request_input", "Pause continuation until the user supplies required input.",
                        schema("goalId,expectedGoalRevision,runId,prompt,expiresAt,idempotencyKey")),
                tool("goal_request_evaluation", "Persist an independent no-tool evaluation request.",
                        evaluationSchema()));
    }

    /** 内建稳定 ID 不含实现类名，重构包路径不会制造目录漂移。 */
    @Override
    public String id() {
        return "builtin.plan_goal";
    }

    /** Plan/Goal 说明先于 Task 协作说明，确保模型先理解领域终态约束。 */
    @Override
    public int order() {
        return 100;
    }

    /**
     * 一次性冻结当前 Turn 可达的 Plan/Goal 身份；默认对话直接返回空贡献，不读取无关聚合。
     */
    @Override
    public Prepared prepare(Request request) {
        Objects.requireNonNull(request, "request");
        if (request.turnId() == null || (request.preferences().collaborationMode() != CollaborationMode.PLAN
                && !request.origin().internal())) {
            return Prepared.empty();
        }
        Binding binding = bind(request);
        List<ToolContribution> contributions = new ArrayList<>();
        for (ToolSpec spec : toolSpecs) {
            if (!binding.allows(spec.name())) continue;
            AgentTool.ToolBindingDescriptor descriptor = AgentTool.builtinBindingDescriptor(
                    spec, ToolSideEffect.EXTERNAL, AgentTool.WorkspaceMutationMode.UNOBSERVABLE);
            contributions.add(new ToolContribution(spec, ToolSideEffect.EXTERNAL,
                    AgentTool.WorkspaceMutationMode.UNOBSERVABLE, descriptor,
                    ignored -> new GoalAgentTool(spec, binding)));
        }
        return new Prepared(promptFragment(binding), contributions);
    }

    /** 查询顺序由持久 origin 决定，不能用 collaboration mode 猜测内部 continuation 身份。 */
    private Binding bind(Request request) {
        GoalUseCase.GoalTurnContext goal = request.origin() == TurnOrigin.GOAL_CONTINUATION
                ? goals.goalContinuationContext(request.threadId(), request.turnId()).orElse(null) : null;
        GoalUseCase.PlanTurnContext plan = request.origin() == TurnOrigin.PLAN_EXECUTION
                ? goals.planExecutionContext(request.threadId(), request.turnId()).orElse(null)
                : request.preferences().collaborationMode() == CollaborationMode.PLAN
                        ? goals.currentPlanContext(request.threadId()).orElse(null) : null;
        return new Binding(request.threadId(), request.turnId(), request.preferences().accessMode(),
                request.preferences().collaborationMode(), request.origin(), request.configGeneration(),
                request.deadline(), goal, plan);
    }

    /** 动态说明只追加冻结 identity/CAS，不携带 objective、正文、证据或数据库结构。 */
    private static String promptFragment(Binding binding) {
        StringBuilder result = new StringBuilder(INSTRUCTIONS);
        if (binding.goalContext() != null) {
            GoalUseCase.GoalTurnContext goal = binding.goalContext();
            result.append("\nCurrent Goal binding: goalId=").append(goal.goalId())
                    .append(", expectedGoalRevision=").append(goal.goalRevision())
                    .append(", runId=").append(goal.runId())
                    .append(", status=").append(goal.status().name())
                    .append(", phase=").append(goal.phase().name());
            if (goal.planId() != null) result.append(", planId=").append(goal.planId())
                    .append(", planRevisionId=").append(goal.planRevisionId());
        }
        if (binding.planContext() != null) {
            GoalUseCase.PlanTurnContext plan = binding.planContext();
            result.append("\nCurrent Plan binding: planId=").append(plan.planId())
                    .append(", expectedPlanRevision=").append(plan.planRevision())
                    .append(", status=").append(plan.status().name());
            if (plan.planRevisionId() != null) result.append(", planRevisionId=")
                    .append(plan.planRevisionId());
            if (plan.runId() != null) result.append(", runId=").append(plan.runId());
        }
        return result.toString();
    }

    /** 每个 Tool 固定同一次 prepare 的领域身份，最终 catalog identity 不重新读取状态。 */
    private final class GoalAgentTool implements AgentTool {
        private final ToolSpec spec;
        private final Binding binding;

        /** 保存已经校验过的静态描述和请求绑定，执行阶段不再解析目录。 */
        private GoalAgentTool(ToolSpec spec, Binding binding) {
            this.spec = spec;
            this.binding = binding;
        }

        /** 返回 prepare 暴露的同一不可变规格。 */
        @Override
        public ToolSpec spec() {
            return spec;
        }

        /**
         * Tool runner 仍负责审批与持久结算；适配器只复核因果身份并调用受限 GoalUseCase。
         */
        @Override
        public CompletionStage<ToolResult> execute(Invocation invocation, ExecutionContext context,
                                                   CancellationToken cancellationToken) {
            try {
                validateContext(context);
                cancellationToken.throwIfCancellationRequested();
                JsonNode arguments = JacksonJsonValues.toNode(json, invocation.arguments());
                validateAuthority(spec.name(), arguments);
                Object result = executeGoalTool(spec.name(), arguments);
                JsonValue structured = structuredResult(result);
                JsonNode resultNode = JacksonJsonValues.toNode(json, structured);
                return CompletableFuture.completedFuture(new ToolResult(ToolOutcome.SUCCEEDED,
                        json.writeValueAsString(resultNode), Optional.of(structured), null));
            } catch (GoalRepositoryException failure) {
                return CompletableFuture.completedFuture(failed(failure.code().name()));
            } catch (java.util.concurrent.CancellationException failure) {
                return CompletableFuture.completedFuture(new ToolResult(
                        ToolOutcome.CANCELLED, "", Optional.empty(), "CANCELLED"));
            } catch (JsonProcessingException | IllegalArgumentException
                     | java.time.format.DateTimeParseException failure) {
                return CompletableFuture.completedFuture(failed("TOOL_ARGUMENTS_INVALID"));
            } catch (RuntimeException failure) {
                return CompletableFuture.completedFuture(failed("GOAL_INVALID_STATE"));
            }
        }

        /** Tool 不得跨 Turn、权限或配置代际复用，拒绝发生在领域 mutation 之前。 */
        private void validateContext(ExecutionContext context) {
            Objects.requireNonNull(context, "context");
            if (!binding.threadId().equals(context.threadId())
                    || !binding.turnId().equals(context.turnId())
                    || binding.accessMode() != context.accessMode()
                    || !binding.configGeneration().equals(context.configGeneration())
                    || !binding.deadline().equals(context.deadline())) {
                throw new IllegalStateException("Plan/Goal Tool execution context changed");
            }
        }

        /** 模型参数只能重复冻结 identity/CAS，不能把 Tool 重定向到同进程其它 Goal/Plan。 */
        private void validateAuthority(String toolName, JsonNode arguments) {
            if ("plan_propose".equals(toolName)) {
                requirePlan(arguments, "planId", "expectedPlanRevision");
            } else if ("plan_step_update".equals(toolName)) {
                if (binding.origin() == TurnOrigin.GOAL_CONTINUATION) {
                    GoalUseCase.GoalTurnContext goal = binding.goalContext();
                    if (!"goal".equals(arguments.path("target").asText())
                            || !goal.goalId().equals(arguments.path("goalId").asText())
                            || goal.goalRevision() != arguments.path("expectedGoalRevision")
                                    .asLong(Long.MIN_VALUE)
                            || !goal.runId().equals(arguments.path("runId").asText())) {
                        throw new IllegalArgumentException("Goal Tool binding is stale");
                    }
                } else {
                    requirePlan(arguments, "planId", "expectedPlanRevision");
                }
            } else if (toolName.startsWith("goal_")) {
                GoalUseCase.GoalTurnContext goal = binding.goalContext();
                if (goal == null || !goal.goalId().equals(arguments.path("goalId").asText())
                        || goal.goalRevision() != arguments.path("expectedGoalRevision").asLong(Long.MIN_VALUE)
                        || !goal.runId().equals(arguments.path("runId").asText())) {
                    throw new IllegalArgumentException("Goal Tool binding is stale");
                }
            }
        }

        /** Plan identity 与 CAS 必须同时匹配，防止旧 prompt 越过同 Plan 的新 revision。 */
        private void requirePlan(JsonNode arguments, String idField, String revisionField) {
            GoalUseCase.PlanTurnContext plan = binding.planContext();
            if (plan == null || !plan.planId().equals(arguments.path(idField).asText())
                    || plan.planRevision() != arguments.path(revisionField).asLong(Long.MIN_VALUE)
                    || (binding.origin() == TurnOrigin.PLAN_EXECUTION
                    && !plan.runId().equals(arguments.path("runId").asText()))) {
                throw new IllegalArgumentException("Plan Tool binding is stale");
            }
        }
    }

    /** 请求级冻结身份同时限定 origin 可见 Tool 闭集。 */
    private record Binding(String threadId, String turnId,
                           io.github.kongweiguang.ja.conversation.domain.permission.AccessMode accessMode,
                           CollaborationMode collaborationMode, TurnOrigin origin,
                           String configGeneration, Instant deadline,
                           GoalUseCase.GoalTurnContext goalContext,
                           GoalUseCase.PlanTurnContext planContext) {
        /** 内部 continuation 缺少对应持久身份时必须失败关闭，不能退化为普通模式。 */
        private Binding {
            if (origin == TurnOrigin.GOAL_CONTINUATION && goalContext == null) {
                throw new IllegalArgumentException("Goal continuation binding is unavailable");
            }
            if (origin == TurnOrigin.PLAN_EXECUTION
                    && (planContext == null || (planContext.status() != GoalModels.PlanStatus.EXECUTING
                    && planContext.status() != GoalModels.PlanStatus.COMPLETED))) {
                throw new IllegalArgumentException("Plan execution binding is unavailable");
            }
        }

        /** Tool 闭集由持久 origin 决定；普通 Default Turn 永远不获得内部 Goal Tool。 */
        private boolean allows(String toolName) {
            return switch (origin) {
                case USER, CHILD_TASK -> collaborationMode == CollaborationMode.PLAN
                        && "plan_propose".equals(toolName);
                case GOAL_CONTINUATION -> goalContext.status() == GoalModels.GoalStatus.ACTIVE
                        && goalContext.phase() == GoalModels.GoalPhase.WORKING
                        && ("goal_request_input".equals(toolName)
                        || "goal_request_evaluation".equals(toolName)
                        || (goalContext.planId() != null && "plan_step_update".equals(toolName)));
                case PLAN_EXECUTION -> planContext.status() == GoalModels.PlanStatus.EXECUTING
                        && "plan_step_update".equals(toolName);
            };
        }
    }

    /** Approved Tool 名称使用闭集 dispatch，不允许参数选择任意 GoalUseCase 方法。 */
    private Object executeGoalTool(String toolName, JsonNode arguments) {
        Objects.requireNonNull(arguments, "arguments");
        if (!arguments.isObject()) throw new IllegalArgumentException("Goal Tool arguments must be an object");
        Instant now = clock.instant();
        return switch (toolName) {
            case "plan_propose" -> goals.propose(new GoalUseCase.Propose(
                    text(arguments, "planId"), integer(arguments, "expectedPlanRevision"),
                    definition(arguments.required("definition")), false,
                    text(arguments, "idempotencyKey"), now));
            case "plan_step_update" -> updateStep(arguments, now);
            case "goal_request_input" -> goals.requestInput(new GoalUseCase.InputRequest(
                    text(arguments, "goalId"), integer(arguments, "expectedGoalRevision"),
                    text(arguments, "runId"), text(arguments, "prompt"),
                    Instant.parse(text(arguments, "expiresAt")), text(arguments, "idempotencyKey"), now));
            case "goal_request_evaluation" -> goals.requestEvaluation(new GoalUseCase.EvaluationRequest(
                    text(arguments, "goalId"), integer(arguments, "expectedGoalRevision"),
                    text(arguments, "runId"), optionalText(arguments, "planRevisionId"),
                    evidenceClaims(arguments), text(arguments, "idempotencyKey"), now));
            default -> throw new IllegalArgumentException("unknown Plan/Goal Agent Tool");
        };
    }

    /** 构造 ToolSpec 时立即解析 schema，非法内建契约在组合阶段失败。 */
    private ToolSpec tool(String name, String description, String encodedSchema) {
        try {
            JsonValue value = JacksonJsonValues.fromNode(json.readTree(encodedSchema));
            if (value instanceof JsonObject object) return new ToolSpec(name, description, object);
            throw new IllegalArgumentException("Plan/Goal Tool schema is not an object");
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Plan/Goal Tool schema is invalid", failure);
        }
    }

    /** 简单 Tool 的 required 与 properties 由同一字段列表生成，避免静态契约内部漂移。 */
    private static String schema(String commaSeparatedRequired) {
        StringBuilder required = new StringBuilder();
        StringBuilder properties = new StringBuilder();
        for (String field : commaSeparatedRequired.split(",")) {
            if (!required.isEmpty()) required.append(',');
            if (!properties.isEmpty()) properties.append(',');
            required.append('"').append(field).append('"');
            String type = field.startsWith("expected") && field.endsWith("Revision")
                    ? "integer" : field.equals("definition") ? "object" : "string";
            properties.append('"').append(field).append("\":{\"type\":\"").append(type).append("\"}");
        }
        return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{" + properties
                + "},\"required\":[" + required + "]}";
    }

    /** 步骤更新以 target 判别两个聚合，细粒度互斥继续由执行端硬校验。 */
    private static String stepUpdateSchema() {
        String properties = "\"target\":{\"type\":\"string\",\"enum\":[\"goal\",\"plan\"]},"
                + "\"goalId\":{\"type\":\"string\"},\"expectedGoalRevision\":{\"type\":\"integer\"},"
                + "\"planId\":{\"type\":\"string\"},\"expectedPlanRevision\":{\"type\":\"integer\"},"
                + "\"runId\":{\"type\":\"string\"},\"stepId\":{\"type\":\"string\"},"
                + "\"expectedStatus\":{\"type\":\"string\"},\"status\":{\"type\":\"string\"},"
                + "\"failureSignature\":{\"type\":[\"string\",\"null\"]},"
                + evidenceClaimsProperty() + ",\"idempotencyKey\":{\"type\":\"string\"}";
        String required = "\"target\",\"runId\",\"stepId\",\"expectedStatus\",\"status\","
                + "\"failureSignature\",\"evidenceClaims\",\"idempotencyKey\"";
        return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                + properties + "},\"required\":[" + required + "]}";
    }

    /** Goal-only evaluation 显式允许 null Plan，并要求真实 Tool evidence。 */
    private static String evaluationSchema() {
        return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                + "\"goalId\":{\"type\":\"string\"},\"expectedGoalRevision\":{\"type\":\"integer\"},"
                + "\"runId\":{\"type\":\"string\"},\"planRevisionId\":{\"type\":[\"string\",\"null\"]},"
                + evidenceClaimsProperty() + ",\"idempotencyKey\":{\"type\":\"string\"}},"
                + "\"required\":[\"goalId\",\"expectedGoalRevision\",\"runId\","
                + "\"planRevisionId\",\"evidenceClaims\",\"idempotencyKey\"]}";
    }

    /** 两个 mutation 共用完全相同的 evidence claim schema。 */
    private static String evidenceClaimsProperty() {
        return "\"evidenceClaims\":{\"type\":\"array\",\"maxItems\":64,\"items\":{"
                + "\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                + "\"criterionId\":{\"type\":\"string\"},\"callId\":{\"type\":\"string\"},"
                + "\"summary\":{\"type\":\"string\"}},"
                + "\"required\":[\"criterionId\",\"callId\",\"summary\"]}}";
    }

    /** target 是两套 aggregate CAS 的显式判别器，字段闭集在直接调用时也必须成立。 */
    private Object updateStep(JsonNode arguments, Instant now) {
        String target = text(arguments, "target");
        validateStepFields(arguments, target);
        String runId = text(arguments, "runId");
        String stepId = text(arguments, "stepId");
        GoalModels.StepStatus expected = status(arguments, "expectedStatus");
        GoalModels.StepStatus updated = status(arguments, "status");
        String failure = optionalText(arguments, "failureSignature");
        List<GoalUseCase.ToolEvidenceClaim> evidence = evidenceClaims(arguments);
        String idempotencyKey = text(arguments, "idempotencyKey");
        return switch (target) {
            case "goal" -> goals.updateStep(new GoalUseCase.StepUpdate(
                    text(arguments, "goalId"), integer(arguments, "expectedGoalRevision"), runId, stepId,
                    expected, updated, failure, evidence, idempotencyKey, now));
            case "plan" -> goals.updatePlanStep(new GoalUseCase.PlanStepUpdate(
                    text(arguments, "planId"), integer(arguments, "expectedPlanRevision"), runId, stepId,
                    expected, updated, failure, evidence, idempotencyKey, now));
            default -> throw new IllegalArgumentException("invalid target");
        };
    }

    /** 混入另一 aggregate 的 identity/CAS 必须失败。 */
    private static void validateStepFields(JsonNode arguments, String target) {
        Set<String> allowed = new HashSet<>(List.of("target", "runId", "stepId", "expectedStatus", "status",
                "failureSignature", "evidenceClaims", "idempotencyKey"));
        switch (target) {
            case "goal" -> allowed.addAll(List.of("goalId", "expectedGoalRevision"));
            case "plan" -> allowed.addAll(List.of("planId", "expectedPlanRevision"));
            default -> throw new IllegalArgumentException("invalid target");
        }
        arguments.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw new IllegalArgumentException("invalid step update field");
        });
    }

    /** claim 列表拒绝未知字段、空摘要和重复 call/criterion 对。 */
    private static List<GoalUseCase.ToolEvidenceClaim> evidenceClaims(JsonNode arguments) {
        JsonNode values = arguments.required("evidenceClaims");
        if (!values.isArray() || values.size() > 64) throw new IllegalArgumentException("invalid evidenceClaims");
        Set<String> identities = new HashSet<>();
        List<GoalUseCase.ToolEvidenceClaim> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isObject() || value.size() != 3) throw new IllegalArgumentException("invalid evidence claim");
            String criterionId = text(value, "criterionId");
            String callId = text(value, "callId");
            String summary = text(value, "summary");
            if (!identities.add(criterionId + '\n' + callId)) {
                throw new IllegalArgumentException("duplicate evidence claim");
            }
            result.add(new GoalUseCase.ToolEvidenceClaim(criterionId, callId, summary));
        }
        return List.copyOf(result);
    }

    /** Jackson 仅将冻结 PlanDefinition record 解码，不接受未知字段。 */
    private GoalModels.PlanDefinition definition(JsonNode node) {
        try {
            return json.treeToValue(node, GoalModels.PlanDefinition.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Plan definition is invalid", failure);
        }
    }

    /** 必需文本不得通过 Jackson coercion 从数值产生。 */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isTextual() || value.textValue().isBlank()) throw new IllegalArgumentException("invalid " + field);
        return value.textValue();
    }

    /** 可选字段只接受文本或 null。 */
    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("invalid " + field);
        return value.textValue();
    }

    /** revision 必须是可精确表示的非负整数。 */
    private static long integer(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isNumber() || value.decimalValue().scale() != 0) {
            throw new IllegalArgumentException("invalid " + field);
        }
        try {
            long result = value.decimalValue().longValueExact();
            if (result < 0) throw new IllegalArgumentException("invalid " + field);
            return result;
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("invalid " + field, failure);
        }
    }

    /** wire 小写步骤状态显式映射到领域枚举。 */
    private static GoalModels.StepStatus status(JsonNode node, String field) {
        return GoalModels.StepStatus.valueOf(text(node, field).toUpperCase(java.util.Locale.ROOT));
    }

    /** Tool 结果只暴露继续推理所需稳定身份与 CAS，不泄漏完整聚合。 */
    private static JsonObject structuredResult(Object result) {
        if (result instanceof GoalModels.Goal goal) {
            return JsonObjects.builder().putText("goalId", goal.goalId())
                    .putNumber("goalRevision", goal.revision()).putText("status", wire(goal.status()))
                    .putText("phase", wire(goal.phase())).build();
        }
        if (result instanceof GoalModels.PlanRevision revision) {
            return JsonObjects.builder().putText("planId", revision.planId())
                    .putText("planRevisionId", revision.planRevisionId())
                    .putNumber("revisionNumber", revision.revisionNumber())
                    .putText("planHash", revision.planHash()).build();
        }
        if (result instanceof GoalModels.Plan plan) {
            var builder = JsonObjects.builder().putText("planId", plan.planId())
                    .putNumber("planRevision", plan.revision()).putText("status", wire(plan.status()));
            if (plan.activePlanRevisionId() != null) {
                builder.putText("activePlanRevisionId", plan.activePlanRevisionId());
            }
            if (plan.activeRunId() != null) builder.putText("activeRunId", plan.activeRunId());
            return builder.build();
        }
        throw new IllegalStateException("unsupported Plan/Goal Tool result");
    }

    /** 领域枚举统一投影为稳定小写值。 */
    private static String wire(Enum<?> value) {
        return value.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** 失败结果只公开稳定错误码，不携带 SQLite、路径或计划正文。 */
    private static AgentTool.ToolResult failed(String code) {
        return new AgentTool.ToolResult(ToolOutcome.FAILED, "Goal operation failed",
                Optional.empty(), code);
    }
}
