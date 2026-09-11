// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.interaction;

import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionOption;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionQuestion;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionQuestionType;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionRequest;
import io.github.kongweiguang.ja.conversation.application.policy.PlanToolPolicy;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.port.out.AgentCapability;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonBoolean;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.foundation.json.JsonValue;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** 向 Agent 目录注册公共 request_user_input Tool；只负责解析题目并创建持久请求。 */
public final class InteractionCapability implements AgentCapability {
    private final InteractionService interactions;
    private final ClarificationPolicy policy;

    /** Plan 上下文的强制准入在 prepare 中统一兜底，普通模式才由配置 Owner 的快照控制。 */
    public InteractionCapability(InteractionService interactions) {
        this(interactions, AgentCapability.Request::clarificationEnabled);
    }

    /** 策略只决定 Tool 是否进入本次目录，不被 Tool 实现自行读取配置或持久化。 */
    public InteractionCapability(InteractionService interactions, ClarificationPolicy policy) {
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** 能力目录使用稳定 ID，避免 Provider 文案变化影响策略去重。 */
    @Override
    public String id() { return "conversation.interaction"; }

    /** 交互工具排在只读基础能力之后，保持目录顺序稳定且可审计。 */
    @Override
    public int order() { return 80; }

    /** 按当前请求的 clarification 策略决定是否暴露提问能力。 */
    @Override
    public Prepared prepare(Request request) {
        /* SUBAGENT 必须把缺失信息汇总回父任务；公共卡片只能由 Root/Side Task 或 Plan/Goal owner 发出。 */
        if (request.taskKind().orElse(null)
                == io.github.kongweiguang.ja.conversation.port.out.TaskCapabilityCeilingPort.Kind.SUBAGENT
                || !clarificationEnabled(request)) {
            return Prepared.empty();
        }
        ToolSpec spec = new ToolSpec("request_user_input",
                "Ask the user one to three structured questions and wait for durable answers.", schema());
        AgentCapability.ToolContribution contribution = new AgentCapability.ToolContribution(
                spec, ToolSideEffect.READ_ONLY, AgentTool.WorkspaceMutationMode.NONE,
                AgentTool.builtinBindingDescriptor(spec, ToolSideEffect.READ_ONLY, AgentTool.WorkspaceMutationMode.NONE),
                AgentTool.PlanAccess.DISALLOWED, AgentTool.ApprovalRequirement.TRUSTED_INTERNAL,
                identity -> new RequestUserInputTool(spec, interactions));
        return new Prepared("When a decision materially changes the plan, use request_user_input. "
                + "Do not infer missing answers; wait for the user's structured response.", List.of(contribution));
    }

    /** 配置 Owner 提供的纯策略边界；Plan 请求应返回 true，普通模式受 clarificationEnabled 控制。 */
    @FunctionalInterface
    public interface ClarificationPolicy {
        /** 只消费请求冻结的偏好，不能在模型发出调用后换用别的配置代际。 */
        boolean enabled(Request request);
    }

    /**
     * Plan-owned 执行与只读规划是业务必需的决策补齐边界，不能被普通模式的关闭偏好或注入策略误关；
     * 只有其它来源才使用请求开始时冻结的用户级设置。
     */
    private boolean clarificationEnabled(Request request) {
        return request.origin() == TurnOrigin.PLAN_EXECUTION
                || PlanToolPolicy.isReadOnlyPlanning(request.origin(), request.preferences().collaborationMode())
                || policy.enabled(request);
    }

    /** 构造结构化问题 Schema，服务端据此约束题型、选项身份和推荐标记。 */
    private static JsonObject schema() {
        JsonObject option = JsonObjects.builder().putText("type", "object")
                .put("properties", new JsonObject(java.util.Map.of(
                        "optionId", textSchema("Stable option ID, prefixed option_"),
                        "label", textSchema("Short label"),
                        "description", textSchema("Trade-off explanation"),
                        "recommended", JsonObjects.builder().putText("type", "boolean").build())))
                .put("required", strings("optionId", "label", "description", "recommended"))
                .putBoolean("additionalProperties", false).build();
        JsonObject question = JsonObjects.builder().putText("type", "object")
                .put("properties", new JsonObject(java.util.Map.of(
                        "questionId", textSchema("Stable question ID, prefixed question_"),
                        "prompt", textSchema("Question text"),
                        "type", JsonObjects.builder().putText("type", "string")
                                .put("enum", strings("single", "multiple", "text")).build(),
                        "options", JsonObjects.builder().putText("type", "array")
                                .putNumber("maxItems", 32).put("items", option).build(),
                        "required", JsonObjects.builder().putText("type", "boolean").build(),
                        "allowFreeText", JsonObjects.builder().putText("type", "boolean").build())))
                .put("required", strings("questionId", "prompt", "type", "options", "required", "allowFreeText"))
                .putBoolean("additionalProperties", false).build();
        return JsonObjects.builder().putText("type", "object")
                .put("properties", new JsonObject(java.util.Map.of(
                        "questions", JsonObjects.builder().putText("type", "array")
                                .putNumber("minItems", 1).putNumber("maxItems", 3).put("items", question).build())))
                .put("required", strings("questions"))
                .putBoolean("additionalProperties", false).build();
    }

    /** 复用有界文本 Schema，避免题面字段遗漏统一描述。 */
    private static JsonObject textSchema(String description) {
        return JsonObjects.builder().putText("type", "string").putText("description", description).build();
    }

    /** 将固定字符串集合转换为内部 JSON 数组，保持协议枚举闭集。 */
    private static JsonArray strings(String... values) {
        return new JsonArray(java.util.Arrays.stream(values).map(JsonText::new).map(JsonValue.class::cast).toList());
    }

    /** Tool 只创建待回答事实并抛出挂起信号，不在此处执行任何副作用。 */
    private static final class RequestUserInputTool implements AgentTool {
        private final ToolSpec spec;
        private final InteractionService interactions;

        /** 注入已绑定的 Interaction owner，避免 Tool 自己持有数据库连接。 */
        private RequestUserInputTool(ToolSpec spec, InteractionService interactions) {
            this.spec = spec;
            this.interactions = interactions;
        }

        /** 返回原始 Tool spec，调用方据此生成稳定工具目录。 */
        @Override public ToolSpec spec() { return spec; }
        /** 明确声明无副作用，Plan 只读策略可据此允许目录暴露。 */
        @Override public ToolSideEffect sideEffect() { return ToolSideEffect.READ_ONLY; }
        /** 交互 Tool 不获取工作区写入租约。 */
        @Override public WorkspaceMutationMode workspaceMutationMode() { return WorkspaceMutationMode.NONE; }

        /**
         * 提问只写入 Java/SQLite 的 Interaction 状态并暂停当前 Turn，不触及 Workspace 或外部系统；
         * 权限审批与用户回答保持独立，避免同一次提问先显示无关的外部操作审批卡片。
         */
        @Override public ApprovalRequirement approvalRequirement() {
            return ApprovalRequirement.TRUSTED_INTERNAL;
        }

        /** 解析稳定问题身份并将控制流交给 Loop 的原子挂起边界。 */
        @Override
        public CompletionStage<ToolResult> execute(Invocation invocation, ExecutionContext context,
                                                   CancellationToken cancellationToken) {
            cancellationToken.throwIfCancellationRequested();
            List<InteractionQuestion> questions = parseQuestions(invocation.arguments());
            if (context.origin() == io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin.PLAN_EXECUTION
                    && (context.runId() == null || context.planRevisionId() == null)) {
                throw new IllegalStateException("Plan interaction identity is unavailable");
            }
            if (context.origin() == io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin.GOAL_CONTINUATION
                    && (context.runId() == null || context.goalId() == null)) {
                throw new IllegalStateException("Goal interaction identity is unavailable");
            }
            String requestId = "interaction_" + java.util.UUID.randomUUID().toString().replace("-", "");
            InteractionRequest request = interactions.prepare(new InteractionService.CreateRequest(requestId, context.threadId(),
                    context.turnId(), invocation.callId(), context.planRevisionId(), context.runId(), context.goalId(),
                    invocation.callId(), questions));
            throw new InteractionSuspendedException(request);
        }

        /** 严格解析一至三道题，拒绝模型通过未知字段或错误题型绕过领域校验。 */
        private static List<InteractionQuestion> parseQuestions(JsonObject arguments) {
            JsonValue raw = arguments.get("questions");
            if (!(raw instanceof JsonArray array) || array.values().size() < 1 || array.values().size() > 3) {
                throw new IllegalArgumentException("questions must contain one to three items");
            }
            List<InteractionQuestion> result = new ArrayList<>();
            for (JsonValue item : array.values()) {
                if (!(item instanceof JsonObject object)) throw new IllegalArgumentException("invalid question");
                String questionId = text(object, "questionId");
                String prompt = text(object, "prompt");
                InteractionQuestionType type = InteractionQuestionType.valueOf(text(object, "type").toUpperCase(java.util.Locale.ROOT));
                boolean required = bool(object, "required", true);
                /* 选择题始终保留“其他答案”通道；旧的 false 仅作为输入字段形状校验，不再关闭用户表达出口。 */
                if (type != InteractionQuestionType.TEXT && object.get("allowFreeText") != null) {
                    bool(object, "allowFreeText", true);
                }
                boolean freeText = true;
                List<InteractionOption> options = new ArrayList<>();
                JsonValue optionValue = object.get("options");
                if (optionValue != null) {
                    if (!(optionValue instanceof JsonArray optionArray)) throw new IllegalArgumentException("invalid options");
                    for (JsonValue option : optionArray.values()) {
                        if (!(option instanceof JsonObject optionObject)) throw new IllegalArgumentException("invalid option");
                        options.add(new InteractionOption(text(optionObject, "optionId"), text(optionObject, "label"),
                                text(optionObject, "description"), bool(optionObject, "recommended", false)));
                    }
                }
                result.add(new InteractionQuestion(questionId, prompt, type, options, required, freeText));
            }
            return List.copyOf(result);
        }

        /** 从 JSON 对象读取非空文本，题目领域对象再执行长度与字符边界校验。 */
        private static String text(JsonObject object, String name) {
            JsonValue value = object.get(name);
            if (!(value instanceof JsonText text) || text.value().isBlank()) throw new IllegalArgumentException("invalid " + name);
            return text.value();
        }

        /** 读取布尔字段并提供仅用于旧 payload 形状的显式默认值。 */
        private static boolean bool(JsonObject object, String name, boolean fallback) {
            JsonValue value = object.get(name);
            return value == null ? fallback : value instanceof JsonBoolean bool ? bool.value()
                    : throwInvalid(name);
        }

        /** 将错误类型统一转换为参数异常，阻止非法 JSON 继续进入 Interaction 聚合。 */
        private static boolean throwInvalid(String name) {
            throw new IllegalArgumentException("invalid " + name);
        }
    }
}
