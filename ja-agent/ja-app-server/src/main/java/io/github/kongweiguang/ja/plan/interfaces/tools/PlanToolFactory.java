// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.interfaces.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonNull;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool.ExtensionFact;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool.Invocation;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValues;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.application.gateway.JaToolRequestContext;
import io.github.kongweiguang.ja.plan.application.service.PlanToolCommandCodec;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.application.api.PlanUseCase;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 将 Plan 用例适配为请求级工具定义；领域状态机、CAS 与持久化仍完全由 PlanUseCase 拥有。
 */
public final class PlanToolFactory {
    private final PlanUseCase plans;
    private final ObjectMapper json;
    private final Clock clock;
    private final PlanToolCommandCodec commands;
    private final List<ToolSpec> toolSpecs;

    /**
     * 构造时只冻结静态 Tool 契约；请求相关 Plan 身份延迟到每次请求安全点读取。
     */
    public PlanToolFactory(PlanUseCase plans, ObjectMapper json, Clock clock) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.commands = new PlanToolCommandCodec();
        this.toolSpecs = List.of(
                tool("plan_propose", "Freeze a structured revision for an existing standalone Plan.",
                        proposalSchema()),
                tool("plan_draft_update", "Save a structured Plan draft with optimistic revision checks.",
                        draftUpdateSchema()),
                tool("plan_step_update", "Update one stable Plan step execution with optimistic state checks.",
                        stepUpdateSchema()));
    }

    /**
     * 只提示当前 Plan 的授权与验收边界，普通对话不注入独立任务状态。
     */
    private static String promptFragment(Binding binding) {
        StringBuilder result = new StringBuilder();
        if (binding.planContext() != null) {
            result.append("""
                Use the exposed Plan tools to maintain the bound Plan. Only the user's Execute action \
                authorizes a proposed revision; never self-approve. During execution, update steps with \
                observed evidence; step completion is not final acceptance.\
                """);
            PlanUseCase.PlanTurnContext plan = binding.planContext();
            result.append("\nCurrent Plan binding: planId=").append(plan.planId())
                    .append(", expectedPlanRevision=").append(plan.planRevision())
                    .append(", status=").append(plan.status().name());
            if (plan.planRevisionId() != null) {
                result.append(", planRevisionId=")
                    .append(plan.planRevisionId());
            }
            if (plan.runId() != null) {
                result.append(", runId=").append(plan.runId());
            }
        }
        return result.toString();
    }

    /**
     * 提案直接暴露冻结版本的完整结构，避免模型只能看到不透明 object 而反复猜测领域字段。
     */
    private static String proposalSchema() {
        return """
            {"type":"object","additionalProperties":false,"properties":{\
            "planId":{"type":"string"},"expectedPlanRevision":{"type":"integer"},\
            "operationKey":{"type":"string"},"definition":\
            """ + definitionSchema() + """
            },"required":["planId","expectedPlanRevision","definition","operationKey"]}\
            """;
    }

    /** 提案与草稿复用同一闭集结构；依赖图、唯一 ID 和状态仍由领域校验，Schema 不代替业务约束。 */
    private static String definitionSchema() {
        return """
            {"type":"object","additionalProperties":false,"properties":{\
            "objective":{"type":"string"},\
            "scope":{"type":"array","maxItems":128,"items":{"type":"string"}},\
            "nonGoals":{"type":"array","maxItems":128,"items":{"type":"string"}},\
            "constraints":{"type":"array","maxItems":128,"items":{"type":"string"}},\
            "dependencies":{"type":"array","maxItems":128,"items":{"type":"string"}},\
            "risks":{"type":"array","maxItems":128,"items":{"type":"string"}},\
            "verificationStrategy":{"type":"array","maxItems":128,"items":{"type":"string"}},\
            "steps":{"type":"array","minItems":1,"maxItems":256,"items":{\
            "type":"object","additionalProperties":false,"properties":{\
            "stepId":{"type":"string"},"title":{"type":"string"},"description":{"type":"string"},\
            "required":{"type":"boolean"},\
            "dependsOn":{"type":"array","maxItems":64,"items":{"type":"string"}}},\
            "required":["stepId","title","description","required","dependsOn"]}},\
            "acceptanceCriteria":{"type":"array","minItems":1,"maxItems":256,"items":{\
            "type":"object","additionalProperties":false,"properties":{\
            "criterionId":{"type":"string"},"description":{"type":"string"},"required":{"type":"boolean"}},\
            "required":["criterionId","description","required"]}}},\
            "required":["objective","scope","nonGoals","constraints","dependencies",\
            "steps","acceptanceCriteria","risks","verificationStrategy"]}\
            """;
    }

    /**
     * 草稿更新保留可为空但必须明确传入的批准基线；使用标准 required 保持模型目录与执行校验一致。
     */
    private static String draftUpdateSchema() {
        return """
            {"type":"object","additionalProperties":false,"properties":{\
            "planId":{"type":"string"},"expectedPlanRevision":{"type":"integer"},\
            "expectedDraftRevision":{"type":"integer"},"definition":\
            """ + definitionSchema() + """
            ,\
            "basedOnPlanRevisionId":{"type":["string","null"]},\
            "operationKey":{"type":"string"}},"required":["planId",\
            "expectedPlanRevision","expectedDraftRevision","definition",\
            "basedOnPlanRevisionId","operationKey"]}\
            """;
    }

    /**
     * 规划期唯一允许的内部持久化 Tool；其副作用仅影响计划草稿或冻结版本。
     */
    private static boolean isPlanMutationTool(String toolName) {
        return "plan_propose".equals(toolName) || "plan_draft_update".equals(toolName);
    }

    /**
     * 步骤工具只接受当前 Plan 身份，避免模型在两个聚合间选择写入目标。
     */
    private static String stepUpdateSchema() {
        String properties = """
            "planId":{"type":"string"},"expectedPlanRevision":{"type":"integer"},\
            "runId":{"type":"string"},"stepId":{"type":"string"},\
            "expectedStatus":{"type":"string"},"status":{"type":"string"},\
            "failureSignature":{"type":["string","null"]},\
            """
                            + evidenceClaimsProperty() + ",\"operationKey\":{\"type\":\"string\"}";
        String required = """
            "planId","expectedPlanRevision","runId","stepId","expectedStatus","status",\
            "failureSignature","evidenceClaims","operationKey"\
            """;
        return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
               + properties + "},\"required\":[" + required + "]}";
    }

    /**
     * 证据声明必须同时包含准则、工具调用和说明，缺失项在 dispatch 前由标准 Schema 拒绝。
     */
    private static String evidenceClaimsProperty() {
        return """
            "evidenceClaims":{"type":"array","maxItems":64,"items":{\
            "type":"object","additionalProperties":false,"properties":{\
            "criterionId":{"type":"string"},"callId":{"type":"string"},\
            "summary":{"type":"string"}},\
            "required":["criterionId","callId","summary"]}}\
            """;
    }

    /**
     * 可空 Plan binding 身份显式编码 JSON null，区分缺失来源与未执行状态。
     */
    private static JsonValue nullable(String value) {
        return value == null ? JsonNull.INSTANCE : new JsonText(value);
    }

    /**
     * 版本号可能为空，不能把 null 伪装为零 revision。
     */
    private static JsonValue nullableNumber(Long value) {
        return value == null ? JsonNull.INSTANCE : new io.github.kongweiguang.ja.agent.domain.json.JsonNumber(value);
    }

    /**
     * 失败结果只公开稳定错误码，不携带 SQLite、路径或计划正文。
     */
    private static JaTool.ToolResult failed(String code) {
        return new JaTool.ToolResult(ToolOutcome.FAILED, "Plan operation failed",
                Optional.empty(), code, List.of(), Optional.empty(), Optional.empty());
    }

    /**
     * 一次性冻结当前 Turn 可达的 Plan 身份；完整 Tool 定义始终进入安全目录，实际可见集合仍
     * 严格由当前 origin、协作模式与可达 Plan 上下文决定，避免隐藏入口造成 Child ceiling 摘要漂移。
     */
    public JaTool.Selection toolsFor(JaToolRequestContext request) {
        Objects.requireNonNull(request, "request");
        if (request.turn().turnId() == null) {
            return JaTool.Selection.empty();
        }
        Binding binding = bind(request);
        List<JaTool.Definition> definitions = toolSpecs.stream()
                .map(spec -> toolDefinition(spec, binding)).toList();
        Set<String> visibleNames = definitions.stream().map(JaTool.Definition::spec)
                .map(ToolSpec::name).filter(binding::allows).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new JaTool.Selection(definitions, visibleNames,
                visibleNames.isEmpty() ? "" : promptFragment(binding));
    }

    /**
     * 为完整安全定义复用同一绑定快照，隐藏入口仍计入稳定目录摘要。
     */
    private JaTool.Definition toolDefinition(ToolSpec spec, Binding binding) {
        JaTool.ToolBindingDescriptor descriptor = JaTool.builtinBindingDescriptor(
                spec, ToolSideEffect.EXTERNAL, JaTool.WorkspaceMutationMode.UNOBSERVABLE);
        return new JaTool.Definition(spec, ToolSideEffect.EXTERNAL,
                JaTool.WorkspaceMutationMode.UNOBSERVABLE, descriptor,
                isPlanMutationTool(spec.name())
                        ? JaTool.PlanAccess.INTERNAL_MUTATION
                        : JaTool.PlanAccess.DISALLOWED,
                isPlanMutationTool(spec.name())
                        ? JaTool.ApprovalRequirement.TRUSTED_INTERNAL
                        : JaTool.ApprovalRequirement.USER_REQUIRED,
                ignored -> new PlanAgentTool(spec, binding));
    }

    /**
     * 查询顺序由持久 origin 决定，不能用 collaboration mode 猜测内部 continuation 身份；IO deadline 在每次执行刷新。
     *
     * @author kongweiguang
     */
    private Binding bind(JaToolRequestContext request) {
        io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeRequest turn = request.turn();
        PlanUseCase.PlanTurnContext plan = turn.origin() == TurnOrigin.PLAN_EXECUTION
                ? plans.planExecutionContext(turn.threadId(), turn.turnId()).orElse(null)
                : request.preferences().collaborationMode() == CollaborationMode.PLAN
                  ? plans.currentPlanContext(turn.threadId()).orElse(null) : null;
        return new Binding(turn.threadId(), turn.turnId(), request.preferences().accessMode(),
                request.preferences().collaborationMode(), turn.origin(), request.configGeneration(),
                plan);
    }

    /**
     * 构造 ToolSpec 时立即解析 schema，非法内建契约在组合阶段失败。
     */
    private ToolSpec tool(String name, String description, String encodedSchema) {
        try {
            JsonValue value = JacksonJsonValues.fromNode(json.readTree(encodedSchema));
            if (value instanceof JsonObject object) {
                return new ToolSpec(name, description, object);
            }
            throw new IllegalArgumentException("Plan Tool schema is not an object");
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Plan Tool schema is invalid", failure);
        }
    }

    /**
     * 请求级冻结身份同时限定 origin 可见 Tool 闭集。
     *
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param accessMode 访问模式
     * @param collaborationMode 协作模式
     * @param origin 来源分类
     * @param configGeneration 冻结的配置代际标识
     * @param planContext Plan 上下文
     * @author kongweiguang
     */
    private record Binding(String threadId,
                           String turnId,
                           io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode accessMode,
                           CollaborationMode collaborationMode,
                           TurnOrigin origin,
                           String configGeneration,
                           PlanUseCase.PlanTurnContext planContext) {
        /**
         * 内部 Plan 执行缺少对应持久身份时必须失败关闭，不能退化为普通模式。
         */
        private Binding {
            if (origin == TurnOrigin.PLAN_EXECUTION
                && (planContext == null || (planContext.status() != PlanModels.PlanStatus.EXECUTING
                                            && planContext.status() != PlanModels.PlanStatus.COMPLETED))) {
                throw new IllegalArgumentException("Plan execution binding is unavailable");
            }
        }

        /**
         * 将模型声明阶段与 PlanService/MybatisPlanRepository 的 Plan 编辑门保持一致；终态和运行态均收紧，
         * 即使上游 projection 暂时返回了一个非空但不可编辑的 Plan，也不会把它伪装成可用 mutation。
         */
        private static boolean allowsPlanMutation(PlanUseCase.PlanTurnContext plan, String toolName) {
            if (plan == null || !isPlanMutationTool(toolName)) {
                return false;
            }
            return switch (toolName) {
                case "plan_propose" -> plan.status() == PlanModels.PlanStatus.DRAFT;
                case "plan_draft_update" -> switch (plan.status()) {
                    case DRAFT, AWAITING_APPROVAL, APPROVED, PAUSED -> true;
                    case EXECUTING, VERIFYING, COMPLETED, STOPPED -> false;
                };
                default -> false;
            };
        }

        /**
         * Tool 闭集由持久 origin 与 Plan 状态共同决定；用户续答保留原 PLAN 资格。
         * 提案只能从 DRAFT 冻结，草稿编辑则复用仓储允许的非执行状态，避免把一个“能调用但必然失败”的
         * 内部 Tool 送入模型目录，也避免旧 prompt 在执行中或终态重新获得编辑入口。
         */
        private boolean allows(String toolName) {
            return switch (origin) {
                case USER, USER_CONTINUATION, CHILD_TASK -> collaborationMode == CollaborationMode.PLAN
                                                            && allowsPlanMutation(planContext, toolName);
                case PLAN_EXECUTION -> planContext.status() == PlanModels.PlanStatus.EXECUTING
                                       && "plan_step_update".equals(toolName);
            };
        }
    }

    /**
     * 每个 Tool 固定同一次 prepare 的领域身份，最终 catalog identity 不重新读取状态。
     */
    private final class PlanAgentTool implements JaTool {
        private final ToolSpec spec;
        private final Binding binding;

        /**
         * 保存已经校验过的静态描述和请求绑定，执行阶段不再解析目录。
         */
        private PlanAgentTool(ToolSpec spec, Binding binding) {
            this.spec = spec;
            this.binding = binding;
        }

        /**
         * 返回 prepare 暴露的同一不可变规格。
         */
        @Override
        public ToolSpec spec() {
            return spec;
        }

        /**
         * 固定工具的安全声明同时用于目录准入和实际调用，避免两个阶段读取不同的默认权限。
         */
        @Override
        public ToolSideEffect sideEffect() {
            return ToolSideEffect.EXTERNAL;
        }

        /**
         * Plan 领域写入没有工作区文件收据，目录和调用都必须保留不可观察的恢复边界。
         */
        @Override
        public WorkspaceMutationMode workspaceMutationMode() {
            return WorkspaceMutationMode.UNOBSERVABLE;
        }

        /**
         * 草稿和提案的内部写入资格必须在目录筛选时已可见；执行步骤仍禁止进入只读规划目录。
         */
        @Override
        public PlanAccess planAccess() {
            return isPlanMutationTool(spec.name())
                    ? PlanAccess.INTERNAL_MUTATION : PlanAccess.DISALLOWED;
        }

        /**
         * 内部草稿写入沿用 Plan 身份/CAS，目录与调用共用免外部审批声明；执行步骤保留用户审批边界。
         */
        @Override
        public ApprovalRequirement approvalRequirement() {
            return isPlanMutationTool(spec.name())
                    ? ApprovalRequirement.TRUSTED_INTERNAL : ApprovalRequirement.USER_REQUIRED;
        }

        /**
         * 只生成待同事务应用的命令事实；业务写入由 AgentStore participant 原子结算。
         */
        @Override
        public CompletionStage<ToolResult> execute(Invocation invocation, JaTool.ExecutionContext context,
                                                   CancellationToken cancellationToken) {
            try {
                validateContext(invocation, context);
                cancellationToken.throwIfCancellationRequested();
                JsonNode arguments = JacksonJsonValues.toNode(json, invocation.arguments());
                validateAuthority(spec.name(), arguments);
                PlanToolCommandCodec.Command command = commands.decode(spec.name(), invocation.arguments(),
                        clock.instant());
                JsonObject payload = commandPayload(invocation, context, command);
                JsonValue structured = JsonObjects.builder().putText("aggregateId", command.aggregateId())
                        .putText("callId", invocation.callId()).putText("toolName", spec.name())
                        .putText("status", "committed").build();
                JsonNode resultNode = JacksonJsonValues.toNode(json, structured);
                return CompletableFuture.completedFuture(new ToolResult(ToolOutcome.SUCCEEDED,
                        json.writeValueAsString(resultNode), Optional.of(structured), null,
                        List.of(new ExtensionFact("ja.plan", "tool-command", 1, payload)),
                        Optional.empty(), Optional.empty()));
            } catch (java.util.concurrent.CancellationException failure) {
                return CompletableFuture.completedFuture(new ToolResult(
                        ToolOutcome.CANCELLED, "", Optional.empty(), "CANCELLED",
                        List.of(), Optional.empty(), Optional.empty()));
            } catch (JsonProcessingException | IllegalArgumentException failure) {
                return CompletableFuture.completedFuture(failed("TOOL_ARGUMENTS_INVALID"));
            } catch (RuntimeException failure) {
                return CompletableFuture.completedFuture(failed("PLAN_INVALID_STATE"));
            }
        }

        /**
         * Tool 不得跨 Turn、权限或配置代际复用；短时 IO deadline 每次执行都会刷新，不属于授权身份。
         *
         * @author kongweiguang
         */
        private void validateContext(Invocation invocation, JaTool.ExecutionContext context) {
            Objects.requireNonNull(context, "context");
            if (!invocation.callId().equals(context.callId())
                || !binding.threadId().equals(context.threadId())
                || !binding.turnId().equals(context.turnId())
                || binding.accessMode() != context.accessMode()
                || !binding.configGeneration().equals(context.configGeneration())) {
                throw new IllegalStateException("Plan Tool execution context changed");
            }
        }

        /**
         * 记录 actor、请求代际与冻结 aggregate binding，供事务 applier 再核验授权 CAS。
         */
        private JsonObject commandPayload(Invocation invocation, JaTool.ExecutionContext context,
                                          PlanToolCommandCodec.Command command) {
            JsonObject actor = JsonObjects.builder().putText("tenantId", context.identity().tenantId())
                    .putText("threadId", context.identity().threadId())
                    .putText("turnId", context.identity().turnId())
                    .putText("userId", context.identity().userId()).build();
            JsonObject bindingPayload = JsonObjects.builder().putText("threadId", binding.threadId())
                    .putText("turnId", binding.turnId()).putText("origin", binding.origin().name())
                    .putText("accessMode", binding.accessMode().name())
                    .putText("collaborationMode", binding.collaborationMode().name())
                    .putText("configGeneration", binding.configGeneration())
                    .putText("workspaceId", context.workspaceId())
                    .put("planId", nullable(binding.planContext() == null ? null : binding.planContext().planId()))
                    .put("planRevision", nullableNumber(binding.planContext() == null
                            ? null : binding.planContext().planRevision()))
                    .put("planRunId", nullable(binding.planContext() == null ? null : binding.planContext().runId()))
                    .build();
            return JsonObjects.builder().put("actor", actor).put("binding", bindingPayload)
                    .putText("callId", invocation.callId()).putText("callKey", context.callKey())
                    .putText("toolName", command.toolName()).put("arguments", command.arguments())
                    .putText("acceptedAt", command.acceptedAt().toString()).build();
        }

        /**
         * 模型参数只能重复冻结 identity/CAS，不能把 Tool 重定向到同进程其它 Plan。
         */
        private void validateAuthority(String toolName, JsonNode arguments) {
            if ("plan_propose".equals(toolName) || "plan_draft_update".equals(toolName)) {
                requirePlan(arguments, "planId", "expectedPlanRevision");
            } else if ("plan_step_update".equals(toolName)) {
                requirePlan(arguments, "planId", "expectedPlanRevision");
            }
        }

        /**
         * Plan identity 与 CAS 必须同时匹配，防止旧 prompt 越过同 Plan 的新 revision。
         */
        private void requirePlan(JsonNode arguments, String idField, String revisionField) {
            PlanUseCase.PlanTurnContext plan = binding.planContext();
            if (plan == null || !plan.planId().equals(arguments.path(idField).asText())
                || plan.planRevision() != arguments.path(revisionField).asLong(Long.MIN_VALUE)
                || (binding.origin() == TurnOrigin.PLAN_EXECUTION
                    && !plan.runId().equals(arguments.path("runId").asText()))) {
                throw new IllegalArgumentException("Plan Tool binding is stale");
            }
        }
    }
}


