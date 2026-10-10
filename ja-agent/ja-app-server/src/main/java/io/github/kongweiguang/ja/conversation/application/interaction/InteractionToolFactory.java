// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.interaction;


import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionOption;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestion;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestionType;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonBoolean;
import io.github.kongweiguang.ja.agent.domain.json.JsonNull;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool.Invocation;
import io.github.kongweiguang.ja.conversation.application.policy.PlanToolPolicy;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionRequest;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.api.InteractionUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.application.gateway.JaToolRequestContext;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/**
 * 向 Agent 目录注册公共 request_user_input Tool；等待由 Core 持久化，执行阶段只读取其答案。
 */
public final class InteractionToolFactory {
    private final InteractionUseCase interactions;

    /**
     * 工厂只依赖问答用例端口，请求准入策略由 resolver 一次性冻结。
     */
    public InteractionToolFactory(InteractionUseCase interactions) {
        this.interactions = Objects.requireNonNull(interactions, "interactions");
    }

    /**
     * Plan-owned 执行与只读规划是业务必需的决策补齐边界，不能被普通模式的关闭偏好或注入策略误关；
     * 只有其它来源才使用请求开始时冻结的用户级设置。
     */
    private static boolean clarificationEnabled(JaToolRequestContext request) {
        return request.turn().origin() == TurnOrigin.PLAN_EXECUTION
               || PlanToolPolicy.isReadOnlyPlanning(request.turn().origin(), request.preferences().collaborationMode())
               || request.clarificationEnabled();
    }

    /**
     * 构造结构化问题 Schema，服务端据此约束题型、选项身份和推荐标记。
     */
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

    /**
     * 复用有界文本 Schema，避免题面字段遗漏统一描述。
     */
    private static JsonObject textSchema(String description) {
        return JsonObjects.builder().putText("type", "string").putText("description", description).build();
    }

    /**
     * 将固定字符串集合转换为内部 JSON 数组，保持协议枚举闭集。
     */
    private static JsonArray strings(String... values) {
        return new JsonArray(java.util.Arrays.stream(values).map(JsonText::new).map(JsonValue.class::cast).toList());
    }

    /**
     * 统一解析用户提问输入，使 Core 等待题目与恢复后的业务校验使用同一领域快照。
     */
    public static List<InteractionQuestion> parseQuestions(JsonObject arguments) {
        Objects.requireNonNull(arguments, "arguments");
        JsonValue raw = arguments.get("questions");
        if (!(raw instanceof JsonArray(List<JsonValue> values1)) || values1.size() < 1 || values1.size() > 3) {
            throw new IllegalArgumentException("questions must contain one to three items");
        }
        List<InteractionQuestion> result = new ArrayList<>();
        for (JsonValue item : values1) {
            if (!(item instanceof JsonObject object)) {
                throw new IllegalArgumentException("invalid question");
            }
            String questionId = text(object, "questionId");
            String prompt = text(object, "prompt");
            InteractionQuestionType type = InteractionQuestionType.valueOf(
                    text(object, "type").toUpperCase(java.util.Locale.ROOT));
            boolean required = bool(object, "required", true);
            /* 选择题保留“其他答案”通道；旧 false 仅用于验证输入类型，不关闭用户表达出口。 */
            if (type != InteractionQuestionType.TEXT && object.get("allowFreeText") != null) {
                bool(object, "allowFreeText", true);
            }
            List<InteractionOption> options = new ArrayList<>();
            JsonValue optionValue = object.get("options");
            if (optionValue != null) {
                if (!(optionValue instanceof JsonArray(List<JsonValue> values)))
                    {
                        throw new IllegalArgumentException("invalid options");
                    }
                for (JsonValue option : values) {
                    if (!(option instanceof JsonObject optionObject))
                        {
                            throw new IllegalArgumentException("invalid option");
                        }
                    options.add(new InteractionOption(text(optionObject, "optionId"), text(optionObject, "label"),
                            text(optionObject, "description"), bool(optionObject, "recommended", false)));
                }
            }
            result.add(new InteractionQuestion(questionId, prompt, type, options, required, true));
        }
        return List.copyOf(result);
    }

    /**
     * 从 JSON 对象读取非空文本，题目领域对象再执行长度与字符边界校验。
     */
    private static String text(JsonObject object, String name) {
        JsonValue value = object.get(name);
        if (!(value instanceof JsonText(String value1)) || value1.isBlank()) {
            throw new IllegalArgumentException("invalid " + name);
        }
        return value1;
    }

    /**
     * 读取布尔字段并提供兼容既有 Schema 的显式默认值。
     */
    private static boolean bool(JsonObject object, String name, boolean fallback) {
        JsonValue value = object.get(name);
        if (value == null) {
            return fallback;
        }
        if (value instanceof JsonBoolean booleanValue) {
            return booleanValue.value();
        }
        return throwInvalid(name);
    }

    /**
     * 将错误类型统一转换为参数异常，阻止非法 JSON 继续进入 Interaction 聚合。
     */
    private static boolean throwInvalid(String name) {
        throw new IllegalArgumentException("invalid " + name);
    }

    /**
     * 按冻结策略暴露提问工具；动态提示只补充交互协议，自主判断原则由核心提示统一维护。
     */
    public JaTool.Selection toolsFor(JaToolRequestContext request) {
        Objects.requireNonNull(request, "request");
        /* SUBAGENT 必须把缺失信息汇总回父任务；公共卡片只能由 Root/Side Task 或 Plan 所属 Thread 发出。 */
        if (request.turn().turnId() == null) {
            return JaTool.Selection.empty();
        }
        ToolSpec spec = new ToolSpec("request_user_input",
                "Ask the user one to three structured questions and wait for durable answers.", schema());
        JaTool.Definition definition = toolDefinition(spec, request);
        boolean exposed = request.taskKind().orElse(null)
                          != io.github.kongweiguang.ja.conversation.application.gateway.TaskCapabilityCeilingPort.Kind.SUBAGENT
                          && clarificationEnabled(request);
        return new JaTool.Selection(List.of(definition), exposed ? Set.of(spec.name()) : Set.of(),
                exposed ? """
                    When essential clarification is needed, use request_user_input to ask \
                    the minimum unanswered questions together, recommend an option when appropriate, \
                    and wait for the structured response. Do not use clarification to duplicate tool approval.\
                    """ : "");
    }

    /**
     * 为隐藏与暴露状态复用同一交互安全元数据；请求 deadline 在恢复或执行时从当前上下文读取。
     *
     * @author kongweiguang
     */
    private JaTool.Definition toolDefinition(ToolSpec spec, JaToolRequestContext request) {
        RequestBinding binding = RequestBinding.from(request);
        return new JaTool.Definition(
                spec, ToolSideEffect.READ_ONLY, JaTool.WorkspaceMutationMode.NONE,
                JaTool.builtinBindingDescriptor(spec, ToolSideEffect.READ_ONLY, JaTool.WorkspaceMutationMode.NONE),
                JaTool.PlanAccess.DISALLOWED, JaTool.ApprovalRequirement.TRUSTED_INTERNAL,
                identity -> new RequestUserInputTool(spec, interactions, binding));
    }

    /**
         * Tool 只在 Core 已持久化等待并收到答案后读取事实，不创建第二套等待生命周期。
     *
     * @param spec 配置规格
     * @param interactions 交互列表
     * @param binding 绑定
     */
        private record RequestUserInputTool(ToolSpec spec,
                                            InteractionUseCase interactions,
                                            RequestBinding binding) implements JaTool {
        /**
         * 注入问答读取端口和请求级绑定，避免复用时将答案交给另一 Turn。
         */
        private RequestUserInputTool {
        }

            /**
             * 同时核对等待来源与冻结题目，拒绝借用同 Thread 的其它调用答案。
             */
            private static boolean matchesAnsweredRequest(InteractionRequest request, Invocation invocation,
                                                          ExecutionContext context,
                                                          List<InteractionQuestion> questions) {
                return request != null && request.status() == InteractionStatus.ANSWERED
                       && request.threadId().equals(context.threadId())
                       && request.turnId().equals(context.turnId())
                       && request.toolCallId().equals(invocation.callId())
                       && Objects.equals(request.planRevisionId(), context.planRevisionId())
                       && Objects.equals(request.runId(), context.runId())
                       && request.questions().equals(questions);
            }

            /**
             * 两条执行路径共享完全相同的可持久化模型结果，避免恢复改变答案投影。
             */
            private static ToolResult result(List<InteractionQuestion> questions, List<InteractionAnswer> answers) {
                return new ToolResult(ToolOutcome.SUCCEEDED,
                        answerContent(questions, answers), Optional.of(answerPayload(answers)), null);
            }

            /**
             * 将确定缺少的核验事实转换为 Core 可持久化的稳定 Unknown 代码。
             */
            private static CompletionStage<RecoveryResolution> completedUnknown(String reasonCode) {
                return CompletableFuture.completedFuture(new RecoveryResolution.Unknown(reasonCode));
            }

            /**
             * 指纹绑定调用、owner、冻结问题和已提交答案；只存摘要，不泄露用户答复。
             */
            private static String verificationFingerprint(Invocation invocation, ExecutionContext context,
                                                          InteractionRequest request,
                                                          List<InteractionQuestion> questions,
                                                          List<InteractionAnswer> answers) {
                MessageDigest digest;
                try {
                    digest = MessageDigest.getInstance("SHA-256");
                } catch (NoSuchAlgorithmException unavailable) {
                    throw new IllegalStateException("SHA-256 is unavailable", unavailable);
                }
                AgentIdentity identity = context.identity();
                digestPart(digest, identity.tenantId());
                digestPart(digest, identity.threadId());
                digestPart(digest, identity.turnId());
                digestPart(digest, identity.userId());
                digestPart(digest, invocation.callId());
                digestPart(digest, invocation.toolName());
                digestPart(digest, context.threadId());
                digestPart(digest, context.turnId());
                digestPart(digest, context.planRevisionId());
                digestPart(digest, context.runId());
                digestPart(digest, request.interactionId());
                digestPart(digest, request.operationKey());
                digestPart(digest, Long.toString(request.interactionRevision()));
                digestPart(digest, request.status().name());
                digestPart(digest, request.updatedAt().toString());
                for (InteractionQuestion question : questions) {
                    digestPart(digest, question.questionId());
                    digestPart(digest, question.prompt());
                    digestPart(digest, question.type().name());
                    digestPart(digest, Boolean.toString(question.required()));
                    digestPart(digest, Boolean.toString(question.allowFreeText()));
                    for (InteractionOption option : question.options()) {
                        digestPart(digest, option.optionId());
                        digestPart(digest, option.label());
                        digestPart(digest, option.description());
                        digestPart(digest, Boolean.toString(option.recommended()));
                    }
                    digestPart(digest, "question-end");
                }
                for (InteractionAnswer answer : answers) {
                    digestPart(digest, answer.questionId());
                    answer.optionIds().forEach(optionId -> digestPart(digest, optionId));
                    digestPart(digest, "options-end");
                    digestPart(digest, answer.freeText());
                    digestPart(digest, Boolean.toString(answer.skipped()));
                }
                return HexFormat.of().formatHex(digest.digest());
            }

            /**
             * 使用带长度的字段编码，避免不同问题/答案边界产生相同摘要输入。
             */
            private static void digestPart(MessageDigest digest, String value) {
                if (value == null) {
                    digest.update((byte) 0);
                    return;
                }
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) 1);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }

            /**
             * 按冻结题目排序答案，避免客户端提交顺序改变模型看到的问答次序。
             */
            private static List<InteractionAnswer> orderedAnswers(InteractionRequest request) {
                Map<String, InteractionAnswer> byQuestion = new HashMap<>();
                for (InteractionAnswer answer : request.answers()) {
                    byQuestion.put(answer.questionId(), answer);
                }
                return request.questions().stream().map(question -> Objects.requireNonNull(
                        byQuestion.get(question.questionId()), "validated interaction answer")).toList();
            }

            /**
             * 模型结果同时保留稳定 question/option 身份和显式跳过、自由文本字段。
             */
            private static JsonObject answerPayload(List<InteractionAnswer> answers) {
                List<JsonValue> values = answers.stream().map(answer -> (JsonValue) JsonObjects.builder()
                        .putText("questionId", answer.questionId())
                        .put("optionIds", new JsonArray(answer.optionIds().stream()
                                .map(JsonText::new).map(JsonValue.class::cast).toList()))
                        .put("freeText", answer.freeText() == null ? JsonNull.INSTANCE : new JsonText(answer.freeText()))
                        .putBoolean("skipped", answer.skipped()).build()).toList();
                return JsonObjects.builder().put("answers", new JsonArray(values)).build();
            }

            /**
             * Tool 文本让模型直接读取用户答复，稳定身份仍由结构化结果携带。
             */
            private static String answerContent(List<InteractionQuestion> questions, List<InteractionAnswer> answers) {
                Map<String, InteractionQuestion> byQuestion = new HashMap<>();
                for (InteractionQuestion question : questions) {
                    byQuestion.put(question.questionId(), question);
                }
                StringBuilder content = new StringBuilder("用户已回答澄清问题：");
                for (InteractionAnswer answer : answers) {
                    InteractionQuestion question = Objects.requireNonNull(byQuestion.get(answer.questionId()),
                            "validated interaction question");
                    content.append("\n[").append(question.questionId()).append("] ").append(question.prompt())
                            .append("\n回答：");
                    if (answer.skipped()) {
                        content.append("已跳过");
                    } else if (answer.optionIds().isEmpty()) {
                        content.append(Objects.requireNonNull(answer.freeText(), "validated free text"));
                    } else {
                        Map<String, String> labels = new HashMap<>();
                        question.options().forEach(option -> labels.put(option.optionId(), option.label()));
                        content.append(answer.optionIds().stream().map(labels::get)
                                .map(value -> Objects.requireNonNull(value, "validated option label"))
                                .collect(Collectors.joining("、")));
                        if (answer.freeText() != null) {
                            content.append("；补充：").append(answer.freeText());
                        }
                    }
                }
                return content.toString();
            }

            /**
             * 返回原始 Tool spec，调用方据此生成稳定工具目录。
             */
            @Override
            public ToolSpec spec() {
                return spec;
            }

            /**
             * 问题创建只影响 Interaction 状态，不触及 Workspace 或外部系统。
             */
            @Override
            public ToolSideEffect sideEffect() {
                return ToolSideEffect.READ_ONLY;
            }

            /**
             * 明确声明无副作用，Plan 只读策略可据此允许目录暴露。
             */
            @Override
            public ToolSideEffect sideEffect(Invocation invocation) {
                Objects.requireNonNull(invocation, "invocation");
                return sideEffect();
            }

            /**
             * 交互 Tool 不获取工作区写入租约。
             */
            @Override
            public WorkspaceMutationMode workspaceMutationMode() {
                return WorkspaceMutationMode.NONE;
            }

            /**
             * 交互入口的每次调用都沿用固定的非工作区写入声明。
             */
            @Override
            public WorkspaceMutationMode workspaceMutationMode(Invocation invocation) {
                Objects.requireNonNull(invocation, "invocation");
                return workspaceMutationMode();
            }

            /**
             * 提问只写入 Java/SQLite 的 Interaction 状态并暂停当前 Turn，不触及 Workspace 或外部系统；
             * 权限审批与用户回答保持独立，避免同一次提问先显示无关的外部操作审批卡片。
             */
            @Override
            public ApprovalRequirement approvalRequirement() {
                return ApprovalRequirement.TRUSTED_INTERNAL;
            }

            /**
             * 提问需要用户回答，但不应再次请求外部动作权限审批。
             */
            @Override
            public ApprovalRequirement approvalRequirement(Invocation invocation) {
                Objects.requireNonNull(invocation, "invocation");
                return approvalRequirement();
            }

            /**
             * 只接受同一持久等待的完整答案；等待本身已由 Core 原子保存，不在执行端重复创建。
             */
            @Override
            public CompletionStage<ToolResult> execute(Invocation invocation, ExecutionContext context,
                                                       CancellationToken cancellationToken) {
                validateContext(context);
                cancellationToken.throwIfCancellationRequested();
                List<InteractionQuestion> questions = parseQuestions(invocation.arguments());
                InteractionRequest request = answeredRequest(invocation, context, questions);
                return CompletableFuture.completedFuture(result(questions, orderedAnswers(request)));
            }

            /**
             * Started 后崩溃时只核验同一份持久答案并重建原结果，不重复创建等待或答案。
             */
            @Override
            public CompletionStage<RecoveryResolution> reconcile(
                    Invocation invocation, ExecutionContext context,
                    Optional<AgentTool.RecoveryEvidence> evidence, CancellationToken cancellationToken) {
                Objects.requireNonNull(invocation, "invocation");
                Objects.requireNonNull(context, "context");
                Objects.requireNonNull(evidence, "evidence");
                Objects.requireNonNull(cancellationToken, "cancellationToken");
                cancellationToken.throwIfCancellationRequested();
                if (!spec.name().equals(invocation.toolName()) || !context.callId().equals(invocation.callId())
                    || !contextMatches(context)) {
                    return completedUnknown("CLARIFICATION_BINDING_UNAVAILABLE");
                }
                List<InteractionQuestion> questions;
                try {
                    questions = parseQuestions(invocation.arguments());
                } catch (IllegalArgumentException invalidInvocation) {
                    return completedUnknown("CLARIFICATION_QUESTIONS_UNAVAILABLE");
                }

                /* Repository 故障继续向上传播；只有确证缺失或身份不匹配才可返回 Unknown。 */
                InteractionSnapshot snapshot = interactions.read(context.threadId(), null).orElse(null);
                if (snapshot == null || !snapshot.threadId().equals(context.threadId())) {
                    return completedUnknown("CLARIFICATION_ANSWER_UNAVAILABLE");
                }
                InteractionRequest request = snapshot.request().orElse(null);
                if (!matchesAnsweredRequest(request, invocation, context, questions)) {
                    return completedUnknown("CLARIFICATION_ANSWER_UNAVAILABLE");
                }
                try {
                    InteractionQuestion.validateCompleteAnswers(request.questions(), request.answers());
                } catch (IllegalArgumentException invalidAnswers) {
                    return completedUnknown("CLARIFICATION_ANSWER_UNAVAILABLE");
                }
                List<InteractionAnswer> answers = orderedAnswers(request);
                ToolResult recovered = result(questions, answers);
                return CompletableFuture.completedFuture(new RecoveryResolution.Applied(
                        recovered, verificationFingerprint(invocation, context, request, questions, answers)));
            }

            /**
             * 从持久快照核对 Turn、Tool call、业务 owner 和问题定义，拒绝任何跨请求答案复用。
             */
            private InteractionRequest answeredRequest(Invocation invocation, ExecutionContext context,
                                                       List<InteractionQuestion> questions) {
                InteractionSnapshot snapshot = interactions.read(context.threadId(), null)
                        .orElseThrow(() -> new IllegalStateException("clarification answer is unavailable"));
                if (!snapshot.threadId().equals(context.threadId())) {
                    throw new IllegalStateException("clarification thread identity changed");
                }
                InteractionRequest request = snapshot.request()
                        .orElseThrow(() -> new IllegalStateException("clarification request is unavailable"));
                if (!matchesAnsweredRequest(request, invocation, context, questions)) {
                    throw new IllegalStateException("clarification answer does not match the frozen Tool call");
                }
                InteractionQuestion.validateCompleteAnswers(request.questions(), request.answers());
                return request;
            }

            /**
             * 拒绝跨 Turn、Workspace、配置代际、Plan 所属 Thread 或原生来源复用冻结的提问 Tool；
             * 短时 IO deadline 可刷新，因此不参与授权身份比较。
             *
             * @author kongweiguang
             */
            private void validateContext(ExecutionContext context) {
                Objects.requireNonNull(context, "context");
                if (!contextMatches(context)) {
                    throw new IllegalStateException("Interaction Tool execution context changed");
                }
            }

            /**
             * 恢复时逐项比较请求冻结值，不能借助当前可变配置重算旧授权身份；deadline 是每次执行的 IO 窗口。
             *
             * @author kongweiguang
             */
            private boolean contextMatches(ExecutionContext context) {
                return binding.threadId().equals(context.threadId())
                       && binding.turnId().equals(context.turnId())
                       && binding.workspaceRoot().equals(context.workspaceRoot())
                       && binding.workspaceId().equals(context.workspaceId())
                       && binding.accessMode() == context.accessMode()
                       && binding.configGeneration().equals(context.configGeneration())
                       && binding.origin() == context.origin()
                       && Objects.equals(binding.planRevisionId(), context.planRevisionId())
                       && Objects.equals(binding.runId(), context.runId());
            }

        }

    /**
     * 只保存提问创建真正使用的请求身份，避免工具对象持有完整偏好或全局运行时。
     *
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param workspaceRoot 工作区根目录
     * @param workspaceId 工作区稳定标识
     * @param accessMode 访问模式
     * @param configGeneration 冻结的配置代际标识
     * @param origin 来源分类
     * @param planRevisionId Plan 修订版本标识
     * @param runId 运行标识
     * @author kongweiguang
     */
    private record RequestBinding(String threadId,
                                  String turnId,
                                  Path workspaceRoot,
                                  String workspaceId,
                                  AccessMode accessMode,
                                  String configGeneration,
                                  TurnOrigin origin,
                                  String planRevisionId,
                                  String runId) {
        /**
         * 从已校验请求冻结稳定执行与授权身份，短时 IO deadline 则由每次执行上下文提供。
         *
         * @author kongweiguang
         */
        private static RequestBinding from(JaToolRequestContext request) {
            io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeRequest turn = request.turn();
            return new RequestBinding(turn.threadId(), turn.turnId(), turn.workspaceRoot(),
                    turn.workspaceId(), request.preferences().accessMode(), request.configGeneration(),
                    turn.origin(), turn.planRevisionId(), turn.runId());
        }
    }
}
