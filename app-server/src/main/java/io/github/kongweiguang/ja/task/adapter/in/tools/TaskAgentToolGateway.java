// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.task.adapter.in.tools;

import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.SubagentPolicy;
import io.github.kongweiguang.ja.conversation.domain.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.AgentCapability;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool.ExecutionContext;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool.Invocation;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool.ToolResult;
import io.github.kongweiguang.ja.conversation.port.out.TaskCapabilityCeilingPort;
import io.github.kongweiguang.ja.conversation.port.out.SubagentPolicyRepository;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.foundation.json.JsonNull;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.foundation.json.JsonValue;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.task.domain.TaskModels;
import io.github.kongweiguang.ja.task.port.in.TaskUseCase;
import io.github.kongweiguang.ja.task.port.out.TaskRepositoryException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 为每次 Provider 请求构造七个原生 Task Tool，并以一次性 late binding 打破 Runtime Resolver 与
 * TaskCoordinator 之间的组合环；Tool 永远直接调用 Java TaskUseCase，不经过 JA-RPC。
 */
public final class TaskAgentToolGateway implements AgentCapability, TaskCapabilityCeilingPort {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(TaskAgentToolGateway.class);
    private static final int MAX_TEXT = 64 * 1024;
    private static final Duration MAX_WAIT = Duration.ofMinutes(10);
    private static final List<ToolSpec> TOOL_SPECS = toolSpecs();
    private final AtomicReference<TaskUseCase> tasks = new AtomicReference<>();
    private final AtomicReference<ThreadUseCase> threads = new AtomicReference<>();
    private final AtomicReference<SubagentPolicyRepository> policies = new AtomicReference<>();

    /** 真实运行时必须在构造期绑定策略 Owner，避免能力目录短暂默认放行。 */
    public TaskAgentToolGateway(SubagentPolicyRepository policies) {
        this.policies.set(Objects.requireNonNull(policies, "policies"));
    }

    /** Task 能力使用稳定 ID，使实现类重命名不会改变注册身份。 */
    @Override
    public String id() {
        return "builtin.task";
    }

    /** Task 协作工具排在 Plan/Goal 领域能力之后，顺序不依赖容器扫描。 */
    @Override
    public int order() {
        return 200;
    }

    /**
     * 在 TaskCoordinator 完整构造后绑定唯一应用端口；重复绑定只允许同一实例，防止热替换让已生成
     * Tool batch 在执行中跳到另一套 Task owner。
     */
    public void bind(TaskUseCase useCase) {
        Objects.requireNonNull(useCase, "useCase");
        if (tasks.get() == useCase) return;
        if (!tasks.compareAndSet(null, useCase)) {
            throw new IllegalStateException("task Tool gateway is already bound");
        }
    }

    /**
     * 在完整 Runtime 组合后绑定唯一 Thread discovery owner；与 Task owner 分开绑定，避免 Tool
     * 适配器复制历史查询或在未完成组合时伪造空目录。
     */
    public void bindThreads(ThreadUseCase useCase) {
        Objects.requireNonNull(useCase, "useCase");
        if (threads.get() == useCase) return;
        if (!threads.compareAndSet(null, useCase)) {
            throw new IllegalStateException("thread Tool gateway is already bound");
        }
    }

    /**
     * 为一次 Provider 请求固定父身份、模型偏好、配置代际与 Deadline；生成 batch 后由持久 binding
     * 保持 schema 和权限不变，下一 Provider 请求仍可读取更新后的环境。
     */
    @Override
    public Prepared prepare(Request request) {
        Objects.requireNonNull(request, "request");
        if (request.turnId() == null) return Prepared.empty();
        SubagentPolicy policy = policy(request.threadId());
        List<ToolContribution> contributions = TOOL_SPECS.stream()
                .filter(spec -> policy.enabled() || !"spawn_agent".equals(spec.name())).map(spec -> {
            ToolSideEffect sideEffect = readOnly(spec.name())
                    ? ToolSideEffect.READ_ONLY : ToolSideEffect.EXTERNAL;
            AgentTool.WorkspaceMutationMode mutationMode = readOnly(spec.name())
                    ? AgentTool.WorkspaceMutationMode.NONE : AgentTool.WorkspaceMutationMode.UNOBSERVABLE;
            AgentTool.ToolBindingDescriptor descriptor = AgentTool.builtinBindingDescriptor(
                    spec, sideEffect, mutationMode);
            return new ToolContribution(spec, sideEffect, mutationMode, descriptor, identity -> {
                ThreadPreferences capabilityPreferences = policy.followsParent() ? request.preferences()
                        : new ThreadPreferences(policy.providerId(), policy.modelId(), policy.reasoningLevel(),
                        request.preferences().accessMode(), request.preferences().collaborationMode(),
                        ThreadPreferences.TitleSource.MANUAL);
                JsonObject ceiling = create(capabilityPreferences, request.configGeneration(), identity);
                Binding binding = new Binding(request.threadId(), request.turnId(), request.preferences(),
                        request.configGeneration(), request.deadline(), ceiling, policy);
                return tool(spec, binding);
            });
        }).toList();
        return new Prepared("", contributions);
    }

    /** 在分派前验证内部工厂输入，避免抽象父构造器抛错留下部分初始化实例。 */
    private AgentTool tool(ToolSpec spec, Binding binding) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(binding, "binding");
        return switch (spec.name()) {
            case "spawn_agent" -> new SpawnAgentTool(spec, binding);
            case "send_message" -> new SendMessageTool(spec, binding);
            case "continue_agent" -> new ContinueAgentTool(spec, binding);
            case "wait_agent" -> new WaitAgentTool(spec, binding);
            case "list_agents" -> new ListAgentsTool(spec, binding);
            case "list_threads" -> new ListThreadsTool(spec, binding);
            case "cancel_agent" -> new CancelAgentTool(spec, binding);
            default -> throw new IllegalStateException("unknown Task Agent Tool");
        };
    }

    /** ToolSpec 由 prepare 与最终实例共享同一对象，避免声明和执行各自构造 schema。 */
    private static List<ToolSpec> toolSpecs() {
        return List.of(
                new ToolSpec("spawn_agent", "Spawn an attached subagent with a focused task brief",
                        objectSchema(Map.of(
                                "taskName", property("string", "Unique sibling task name."),
                                "brief", property("string", "Focused task brief; parent transcript is not copied."),
                                "accessMode", enumProperty("Optional child access ceiling.",
                                        "approval_required", "full_access"),
                                "timeoutMs", property("integer",
                                        "Positive timeout bounded by the parent turn.")),
                                List.of("taskName", "brief"))),
                new ToolSpec("send_message", "Queue a message for another task without waking an idle task",
                        objectSchema(Map.of(
                                "targetThreadId", property("string", "Target child thread identity."),
                                "message", property("string", "Message delivered at the target's next safe point.")),
                                List.of("targetThreadId", "message"))),
                new ToolSpec("continue_agent", "Continue a subagent delegated by the current task",
                        objectSchema(Map.of(
                                "targetThreadId", property("string", "Delegated subagent thread identity."),
                                "message", property("string", "Continuation instruction."),
                                "expectedTaskRevision", property("integer", "Latest observed task revision."),
                                "timeoutMs", property("integer",
                                        "Positive timeout bounded by the parent turn.")),
                                List.of("targetThreadId", "message", "expectedTaskRevision"))),
                new ToolSpec("wait_agent", "Wait for any selected subagent to complete or need attention",
                        objectSchema(Map.of(
                                "taskThreadIds", arrayProperty("Child thread identities to wait for."),
                                "timeoutMs", property("integer", "Wait timeout up to 600000 milliseconds.")),
                                List.of("taskThreadIds"))),
                new ToolSpec("list_agents", "List subagents in the current task tree without reading transcripts",
                        objectSchema(Map.of(), List.of())),
                new ToolSpec("list_threads", "List recent Ja threads without reading transcripts",
                        objectSchema(Map.of(
                                "query", property("string", "Optional title search text."),
                                "cursor", property("string", "Opaque cursor returned by a previous page."),
                                "limit", property("integer", "Page size from 1 to 200."),
                                "workspaceId", property("string", "Optional Workspace identity filter.")),
                                List.of())),
                new ToolSpec("cancel_agent", "Cancel a task and its attached descendants",
                        objectSchema(Map.of(
                                "targetThreadId", property("string", "Target child thread identity."),
                                "expectedTaskRevision", property("integer", "Latest observed task revision.")),
                                List.of("targetThreadId", "expectedTaskRevision"))));
    }

    /** 只有纯投影等待与枚举可安全重试，其余 Task Tool 都可能提交持久事实。 */
    private static boolean readOnly(String toolName) {
        return "wait_agent".equals(toolName) || "list_agents".equals(toolName)
                || "list_threads".equals(toolName);
    }

    /**
     * Tool 调用发生时才解析应用端口，使 Solon 可以先构造 TurnRuntimeResolver；未完成组合时失败关闭，
     * 不使用 no-op TaskUseCase 伪造成功。
     */
    private TaskUseCase requireTasks() {
        TaskUseCase value = tasks.get();
        if (value == null) throw new IllegalStateException("task Tool gateway is not bound");
        return value;
    }

    /** 只读 Thread discovery 必须复用 Java 历史 owner，未完成组合时明确失败关闭。 */
    private ThreadUseCase requireThreads() {
        ThreadUseCase value = threads.get();
        if (value == null) throw new IllegalStateException("thread Tool gateway is not bound");
        return value;
    }

    /** 策略行缺失时失败关闭，避免把数据库损坏误判为默认开启。 */
    private SubagentPolicy policy(String threadId) {
        SubagentPolicyRepository repository = policies.get();
        return repository.find(threadId).orElseThrow(() ->
                new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                        "subagent policy is unavailable"));
    }

    /** 单次请求生成的 Task Tools 共享不可变权限与配置指纹。 */
    private record Binding(String parentThreadId, String parentTurnId, ThreadPreferences preferences,
                           String configGeneration, Instant deadline, JsonObject capabilityCeiling,
                           SubagentPolicy policy) {
        /** 冻结字段只接受生产身份形状，避免 Adapter 宽松接收随后由 TaskCoordinator 猜测。 */
        public Binding {
            if (parentThreadId == null || !parentThreadId.startsWith("thr_")) {
                throw new IllegalArgumentException("invalid parentThreadId");
            }
            if (parentTurnId == null || !parentTurnId.startsWith("turn_")) {
                throw new IllegalArgumentException("invalid parentTurnId");
            }
            Objects.requireNonNull(preferences, "preferences");
            if (configGeneration == null || !configGeneration.startsWith("cfg_")) {
                throw new IllegalArgumentException("invalid configGeneration");
            }
            Objects.requireNonNull(deadline, "deadline");
            Objects.requireNonNull(capabilityCeiling, "capabilityCeiling");
            Objects.requireNonNull(policy, "policy");
        }

        /** 指定模型使用策略引用及其冻结档位；null 表示目标模型默认值，跟随父任务沿用父档位。 */
        ThreadPreferences childPreferences() {
            if (policy.followsParent()) return preferences;
            return new ThreadPreferences(policy.providerId(), policy.modelId(), policy.reasoningLevel(),
                    preferences.accessMode(), preferences.collaborationMode(),
                    ThreadPreferences.TitleSource.MANUAL);
        }
    }

    /** Root Thread 没有 seed；Child 只返回创建时持久化的不可变权限上限，不物化 Transcript。 */
    @Override
    public Optional<JsonObject> read(String threadId) {
        Objects.requireNonNull(threadId, "threadId");
        try {
            TaskModels.Detail detail = requireTasks().read(threadId, 0, 0, 1);
            JsonObject ceiling = detail.contextSeed().permissionCeiling();
            validateCeilingKind(detail.task().lineage().kind(), ceiling);
            return Optional.of(ceiling);
        } catch (TaskRepositoryException failure) {
            if (failure.code() == TaskRepositoryException.Code.NOT_FOUND) return Optional.empty();
            throw failure;
        }
    }

    /**
     * Runtime resolver 只通过该窄端口读取持久 Child 身份；具体 Task owner 仍由 late binding 提供，
     * 因此不会在能力适配器中复制 lineage、标题或进程级任务状态。
     */
    @Override
    public Optional<TaskCapabilityCeilingPort.RuntimeIdentity> readIdentity(String threadId) {
        Objects.requireNonNull(threadId, "threadId");
        return requireTasks().readRuntimeIdentity(threadId).map(TaskAgentToolGateway::runtimeIdentity);
    }

    /** 将 Task 领域的持久投影收窄为 conversation 端口拥有的边界 DTO，阻止领域依赖反向泄漏。 */
    private static TaskCapabilityCeilingPort.RuntimeIdentity runtimeIdentity(TaskModels.RuntimeIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return new TaskCapabilityCeilingPort.RuntimeIdentity(
                identity.taskThreadId(), identity.parentThreadId(), identity.rootThreadId(),
                identity.taskName(), identity.parentTaskName(), identity.rootTaskName(),
                identity.kind() == TaskModels.Kind.SIDE_TASK
                        ? TaskCapabilityCeilingPort.Kind.SIDE_TASK : TaskCapabilityCeilingPort.Kind.SUBAGENT);
    }

    /** Variant 与不可变 lineage 必须一一对应，损坏 seed 不能借较弱 ceiling 绕过 Subagent 约束。 */
    static void validateCeilingKind(TaskModels.Kind kind, JsonObject ceiling) {
        JsonValue value = ceiling.get("version");
        String expected = switch (kind) {
            case SIDE_TASK -> "task_access_v1";
            case SUBAGENT -> "task_capability_v1";
        };
        if (!(value instanceof JsonText version) || !expected.equals(version.value())) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                    "task ceiling does not match lineage kind");
        }
    }

    /** 构造不含 secret 和配置正文的能力上限；摘要身份用于 Child 安全点 fail-closed 校验。 */
    @Override
    public JsonObject create(ThreadPreferences preferences, String configGeneration,
                             AgentCapability.CatalogIdentity catalogIdentity) {
        Objects.requireNonNull(preferences, "preferences");
        Objects.requireNonNull(catalogIdentity, "catalogIdentity");
        List<JsonValue> skills = catalogIdentity.skillIds().stream().sorted().map(JsonText::new)
                .map(JsonValue.class::cast).toList();
        var builder = JsonObjects.builder().putText("version", "task_capability_v1")
                .putText("providerId", preferences.providerId()).putText("modelId", preferences.modelId())
                .putText("accessMode", preferences.accessMode().name().toLowerCase(java.util.Locale.ROOT))
                .putText("collaborationMode",
                        preferences.collaborationMode().name().toLowerCase(java.util.Locale.ROOT))
                .putText("configGeneration", configGeneration)
                .putText("toolCatalogDigest", catalogIdentity.toolCatalogDigest())
                .putText("mcpCatalogRevision", catalogIdentity.mcpCatalogRevision())
                .put("skillIds", new JsonArray(skills));
        if (preferences.reasoningLevel() == null) builder.put("reasoningLevel", JsonNull.INSTANCE);
        else builder.putText("reasoningLevel", preferences.reasoningLevel());
        return builder.build();
    }

    /** 统一 Tool 执行的冻结上下文复核、取消传播与安全错误映射。 */
    private abstract class TaskTool implements AgentTool {
        private final ToolSpec spec;
        final Binding binding;

        /** 只保存私有工厂已经校验的值，抽象构造器不执行可失败操作。 */
        private TaskTool(ToolSpec spec, Binding binding) {
            this.spec = spec;
            this.binding = binding;
        }

        /** 返回当前请求目录中的 Tool 描述。 */
        @Override
        public final ToolSpec spec() {
            return spec;
        }

        /** Task 读操作显式收窄，所有提交事实的工具保持保守外部副作用。 */
        @Override
        public final ToolSideEffect sideEffect() {
            return readOnly(spec.name()) ? ToolSideEffect.READ_ONLY : ToolSideEffect.EXTERNAL;
        }

        /** Task 只读投影不触碰工作区，其余数据库副作用仍不可用 Workspace 收据表达。 */
        @Override
        public final WorkspaceMutationMode workspaceMutationMode() {
            return readOnly(spec.name()) ? WorkspaceMutationMode.NONE : WorkspaceMutationMode.UNOBSERVABLE;
        }

        /**
         * 每次执行复核 AgentLoop 传入的不可变上下文，再把同步或异步应用结果统一收敛为安全 ToolResult。
         */
        @Override
        public final CompletionStage<ToolResult> execute(Invocation invocation, ExecutionContext context,
                                                         CancellationToken cancellationToken) {
            try {
                validateContext(context);
                cancellationToken.throwIfCancellationRequested();
                return executeTask(invocation, context, cancellationToken)
                        .handle((result, failure) -> failure == null ? result : failure(failure));
            } catch (RuntimeException failure) {
                return CompletableFuture.completedFuture(failure(failure));
            }
        }

        /** 具体 Tool 只负责调用 TaskUseCase，不拥有错误日志、RPC 或持久化细节。 */
        abstract CompletionStage<ToolResult> executeTask(Invocation invocation, ExecutionContext context,
                                                         CancellationToken cancellationToken);

        /**
         * 防止 Tool 实例被错误复用到另一请求代际；父 Thread 与 Turn 都来自请求级 Resolver，
         * Tool 只使用复核后的因果身份，不从全局 active registry 反查或接受调用方替换。
         */
        private void validateContext(ExecutionContext context) {
            Objects.requireNonNull(context, "context");
            if (!binding.parentThreadId().equals(context.threadId())
                    || !binding.parentTurnId().equals(context.turnId())
                    || binding.preferences().accessMode() != context.accessMode()
                    || !binding.configGeneration().equals(context.configGeneration())
                    || !binding.deadline().equals(context.deadline())) {
                throw new IllegalStateException("task Tool execution context changed");
            }
        }
    }

    /** 创建 brief-only、ATTACHED 的 Subagent。 */
    private final class SpawnAgentTool extends TaskTool {
        /** Schema 只允许名称、brief、可选收紧权限与超时，不允许模型切换 Provider 或提升能力。 */
        private SpawnAgentTool(ToolSpec spec, Binding binding) {
            super(spec, binding);
        }

        /** 冻结父偏好并只允许权限取父上限与请求值的更严格者。 */
        @Override
        CompletionStage<ToolResult> executeTask(Invocation invocation, ExecutionContext context,
                                                CancellationToken cancellationToken) {
            String taskName = text(invocation, "taskName", 96, false);
            UserContent brief = userText(invocation, "brief");
            AccessMode childMode = childAccess(invocation, binding.preferences().accessMode());
            ThreadPreferences childPreferences = binding.childPreferences();
            ThreadPreferences preferences = new ThreadPreferences(childPreferences.providerId(),
                    childPreferences.modelId(), childPreferences.reasoningLevel(), childMode,
                    binding.preferences().collaborationMode(),
                    ThreadPreferences.TitleSource.MANUAL);
            TaskUseCase.StartResult result = requireTasks().spawnAgent(new TaskUseCase.SpawnCommand(
                    context.threadId(), context.turnId(), taskName, brief,
                    boundedDeadline(invocation, context, "timeoutMs"), preferences,
                    binding.capabilityCeiling()));
            String path = taskPath(result.task());
            JsonObject structured = JsonObjects.builder()
                    .putText("threadId", result.task().lineage().taskThreadId())
                    .putText("turnId", result.turnId()).putText("taskPath", path)
                    .putText("status", wire(result.task().projection().state())).build();
            return completed("Spawned " + path + " (" + result.task().lineage().taskThreadId() + ").", structured);
        }
    }

    /** QueueOnly 发送消息，目标空闲时不会启动 Turn。 */
    private final class SendMessageTool extends TaskTool {
        /** 目标身份与消息正文保持显式，幂等键稳定派生自 Provider callId。 */
        private SendMessageTool(ToolSpec spec, Binding binding) {
            super(spec, binding);
        }

        /** 只写持久 Mailbox，并返回服务端 sequence 供调用者关联。 */
        @Override
        CompletionStage<ToolResult> executeTask(Invocation invocation, ExecutionContext context,
                                                CancellationToken cancellationToken) {
            TaskUseCase.MessageReceipt receipt = requireTasks().sendMessage(message(invocation, context));
            JsonObject structured = JsonObjects.builder().putText("messageId", receipt.messageId())
                    .putNumber("mailboxSequence", receipt.mailboxSequence()).build();
            return completed("Message queued for " + text(invocation, "targetThreadId", 128, false) + ".",
                    structured);
        }
    }

    /** 继续一个已由当前 Agent 委派的 Subagent，并为其原子接纳下一 Turn。 */
    private final class ContinueAgentTool extends TaskTool {
        /** expectedTaskRevision 让模型显式处理并发变化，避免后台消息静默覆盖新状态。 */
        private ContinueAgentTool(ToolSpec spec, Binding binding) {
            super(spec, binding);
        }

        /** TaskUseCase 在 Java 应用边界复核委派链，再由同一事务提交 Mailbox 与 Child Turn。 */
        @Override
        CompletionStage<ToolResult> executeTask(Invocation invocation, ExecutionContext context,
                                                CancellationToken cancellationToken) {
            TaskUseCase.FollowUpResult result = requireTasks().continueAgentFrom(context.threadId(),
                    new TaskUseCase.FollowUpCommand(
                            message(invocation, context), integer(invocation, "expectedTaskRevision", 0,
                                    9_007_199_254_740_991L),
                            boundedDeadline(invocation, context, "timeoutMs")));
            JsonObject structured = JsonObjects.builder()
                    .putText("threadId", result.task().lineage().taskThreadId())
                    .putText("turnId", result.turnId()).putText("messageId", result.messageId())
                    .putNumber("revision", result.task().projection().revision())
                    .putText("status", wire(result.task().projection().state())).build();
            return completed("Continuation queued for " + result.task().lineage().taskName() + ".", structured);
        }
    }

    /** 事件驱动等待目标进入终态或需要处理状态。 */
    private final class WaitAgentTool extends TaskTool {
        /** 最多八个目标与十分钟上限沿用 TaskUseCase 的有界等待合同。 */
        private WaitAgentTool(ToolSpec spec, Binding binding) {
            super(spec, binding);
        }

        /** 父 Turn CancellationToken 直接注册到 Task waiter，不使用轮询或阻塞 sleep。 */
        @Override
        CompletionStage<ToolResult> executeTask(Invocation invocation, ExecutionContext context,
                                                CancellationToken cancellationToken) {
            Set<String> targets = identifiers(invocation, "taskThreadIds", 8);
            Duration timeout = waitDuration(invocation, context);
            return requireTasks().waitAgentsFrom(context.threadId(), targets, timeout, cancellationToken)
                    .thenApply(result -> {
                List<JsonValue> values = result.tasks().stream().map(TaskAgentToolGateway::taskResult)
                        .map(JsonValue.class::cast).toList();
                JsonObject structured = JsonObjects.builder().putBoolean("timedOut", result.timedOut())
                        .put("tasks", new JsonArray(values)).build();
                return success(result.timedOut() ? "No task changed before the timeout."
                        : "A task completed or needs attention.", structured);
            });
        }
    }

    /** 读取当前 Agent 实际委派的 Subagent 子树摘要，不物化 Child Transcript 或独立侧聊。 */
    private final class ListAgentsTool extends TaskTool {
        /** 空参数明确表示从当前 Turn 身份开始沿真实委派边遍历，不允许跨树枚举。 */
        private ListAgentsTool(ToolSpec spec, Binding binding) {
            super(spec, binding);
        }

        /** Child 通过自身有界详情解析 root，再由 parentThreadId 和 Kind 过滤实际 Agent 子树。 */
        @Override
        CompletionStage<ToolResult> executeTask(Invocation invocation, ExecutionContext context,
                                                CancellationToken cancellationToken) {
            String root = rootThread(context.threadId());
            List<TaskModels.Summary> tasks = delegatedAgents(context.threadId(), requireTasks().listTree(root));
            List<JsonValue> values = tasks.stream().map(TaskAgentToolGateway::taskResult)
                    .map(JsonValue.class::cast).toList();
            JsonObject structured = JsonObjects.builder().putText("rootThreadId", root)
                    .put("tasks", new JsonArray(values)).build();
            return completed(tasks.isEmpty() ? "No subagents are registered."
                    : tasks.size() + " subagent task(s) found.", structured);
        }
    }

    /** 只读全局 Thread 目录，不读取任何 Thread snapshot、Task seed 或工作区正文。 */
    private final class ListThreadsTool extends TaskTool {
        /** discovery 仅依赖 spec 与请求冻结上下文，实际 owner 由 bindThreads late binding 提供。 */
        private ListThreadsTool(ToolSpec spec, Binding binding) {
            super(spec, binding);
        }

        /** 将 Tool 参数收窄为固定 all scope，并原样返回最小 discovery page。 */
        @Override
        CompletionStage<ToolResult> executeTask(Invocation invocation, ExecutionContext context,
                                                CancellationToken cancellationToken) {
            String query = optionalText(invocation, "query", 256);
            String cursor = optionalText(invocation, "cursor", 512);
            String workspaceId = optionalIdentifier(invocation, "workspaceId", "ws_", 128);
            int limit = discoveryLimit(invocation);
            CursorPage<ThreadDiscovery> page = requireThreads().discoverThreads(
                    new ThreadDiscovery.Query("all", query, cursor, limit, workspaceId));
            List<JsonValue> values = page.items().stream().map(TaskAgentToolGateway::threadResult)
                    .map(JsonValue.class::cast).toList();
            JsonObject structured = JsonObjects.builder().put("items", new JsonArray(values))
                    .put("nextCursor", page.nextCursor() == null
                            ? JsonNull.INSTANCE : new JsonText(page.nextCursor()))
                    .build();
            return completed(page.items().isEmpty() ? "No threads found."
                    : page.items().size() + " thread(s) found.", structured);
        }
    }

    /** 显式取消目标及其 ATTACHED 后代。 */
    private final class CancelAgentTool extends TaskTool {
        /** CAS revision 使取消与完成竞态由唯一终态 owner 决定。 */
        private CancelAgentTool(ToolSpec spec, Binding binding) {
            super(spec, binding);
        }

        /** TaskCoordinator 负责递归传播，本 Tool 不扫描或逐个猜测活动 Turn。 */
        @Override
        CompletionStage<ToolResult> executeTask(Invocation invocation, ExecutionContext context,
                                                CancellationToken cancellationToken) {
            String target = text(invocation, "targetThreadId", 128, false);
            TaskModels.Summary result = requireTasks().cancelFrom(context.threadId(), target,
                    integer(invocation, "expectedTaskRevision", 0, 9_007_199_254_740_991L));
            return completed("Cancellation requested for " + result.lineage().taskName() + ".",
                    taskResult(result));
        }
    }

    /** 构造当前调用稳定幂等的 Mailbox 命令。 */
    private static TaskUseCase.MessageCommand message(Invocation invocation, ExecutionContext context) {
        return new TaskUseCase.MessageCommand(context.threadId(),
                text(invocation, "targetThreadId", 128, false), userText(invocation, "message"),
                "tool:" + context.turnId() + ":" + invocation.callId(), context.turnId());
    }

    /** Child 权限只能与父上限相同或从 full_access 收紧为 approval_required。 */
    private static AccessMode childAccess(Invocation invocation, AccessMode ceiling) {
        String requested = optionalText(invocation, "accessMode", 32);
        if (requested == null) return ceiling;
        AccessMode selected = switch (requested) {
            case "approval_required" -> AccessMode.APPROVAL_REQUIRED;
            case "full_access" -> AccessMode.FULL_ACCESS;
            default -> throw new IllegalArgumentException("invalid accessMode");
        };
        if (ceiling == AccessMode.APPROVAL_REQUIRED && selected == AccessMode.FULL_ACCESS) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.PERMISSION_DENIED,
                    "child access exceeds parent ceiling");
        }
        return selected;
    }

    /** 取请求超时、父 Turn 剩余时间与 Task 上限中的最小正值。 */
    private static Duration boundedDeadline(Invocation invocation, ExecutionContext context, String field) {
        long remaining = Duration.between(Instant.now(), context.deadline()).toMillis();
        if (remaining < 1_000) throw new CancellationException("parent turn deadline elapsed");
        long requested = optionalInteger(invocation, field, remaining);
        return Duration.ofMillis(Math.max(1_000, Math.min(Math.min(requested, remaining), 86_400_000L)));
    }

    /** Wait 独立限制十分钟，同时绝不超过父 Turn 的剩余 Deadline。 */
    private static Duration waitDuration(Invocation invocation, ExecutionContext context) {
        Duration bounded = boundedDeadline(invocation, context, "timeoutMs");
        return bounded.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : bounded;
    }

    /** Root Thread 没有 Task 详情；Child 则从自身 lineage 读取唯一 root。 */
    private String rootThread(String threadId) {
        try {
            return requireTasks().read(threadId, 0, 0, 1).task().lineage().rootThreadId();
        } catch (TaskRepositoryException failure) {
            if (failure.code() == TaskRepositoryException.Code.NOT_FOUND) return threadId;
            throw failure;
        }
    }

    /**
     * 从当前调用者沿 ATTACHED/SUBAGENT parent 边做有界遍历；SIDE_TASK 边被截断，避免共享 root
     * 或独立侧聊让 Agent 看到不属于其委派范围的任务。
     */
    private static List<TaskModels.Summary> delegatedAgents(String requesterThreadId,
                                                              List<TaskModels.Summary> tree) {
        Objects.requireNonNull(requesterThreadId, "requesterThreadId");
        List<TaskModels.Summary> values = List.copyOf(Objects.requireNonNull(tree, "tree"));
        Map<String, List<TaskModels.Summary>> children = new HashMap<>();
        for (TaskModels.Summary task : values) {
            children.computeIfAbsent(task.lineage().parentThreadId(), ignored -> new ArrayList<>()).add(task);
        }
        Set<String> frontier = new LinkedHashSet<>(Set.of(requesterThreadId));
        Set<String> included = new LinkedHashSet<>();
        while (!frontier.isEmpty()) {
            String parent = frontier.iterator().next();
            frontier.remove(parent);
            for (TaskModels.Summary child : children.getOrDefault(parent, List.of())) {
                if (child.lineage().kind() != TaskModels.Kind.SUBAGENT
                        || child.lineage().lifecycle() != TaskModels.Lifecycle.ATTACHED) {
                    continue;
                }
                if (included.add(child.lineage().taskThreadId())) {
                    frontier.add(child.lineage().taskThreadId());
                }
            }
        }
        return values.stream().filter(task -> included.contains(task.lineage().taskThreadId())).toList();
    }

    /** 由 lineage 向上构造人类可读稳定路径；缺失父摘要时仍保留当前 taskName。 */
    private String taskPath(TaskModels.Summary task) {
        List<TaskModels.Summary> tree = requireTasks().listTree(task.lineage().rootThreadId());
        Map<String, TaskModels.Summary> byId = new HashMap<>();
        tree.forEach(value -> byId.put(value.lineage().taskThreadId(), value));
        List<String> names = new ArrayList<>();
        TaskModels.Summary current = task;
        while (current != null) {
            names.addFirst(current.lineage().taskName());
            current = byId.get(current.lineage().parentThreadId());
        }
        return "/root/" + String.join("/", names);
    }

    /** Tool 结果只投影右栏摘要所需字段，不读取 seed、Mailbox 或 Transcript。 */
    private static JsonObject taskResult(TaskModels.Summary task) {
        return JsonObjects.builder().putText("threadId", task.lineage().taskThreadId())
                .putText("parentThreadId", task.lineage().parentThreadId())
                .putText("taskName", task.lineage().taskName())
                .putText("kind", wire(task.lineage().kind()))
                .putText("status", wire(task.projection().state()))
                .putNumber("revision", task.projection().revision())
                .putNumber("depth", task.lineage().depth())
                .putText("summary", task.projection().latestSafeSummary()).build();
    }

    /** Tool discovery item 复用 RPC 的字段闭集，避免模型看到正文、偏好或时间戳。 */
    private static JsonObject threadResult(ThreadDiscovery thread) {
        return JsonObjects.builder().putText("threadId", thread.threadId())
                .putText("title", thread.title()).putText("kind", wire(thread.kind()))
                .putText("workspaceId", thread.workspaceId()).putText("status", wire(thread.status())).build();
    }

    /** 将文本参数包装为标准结构化用户内容，Subagent 不复制父 Transcript。 */
    private static UserContent userText(Invocation invocation, String field) {
        return new UserContent(List.of(new TextContent(text(invocation, field, MAX_TEXT, false))));
    }

    /** 读取必需文本并执行独立于 Provider schema 校验器的硬限制。 */
    private static String text(Invocation invocation, String field, int maxLength, boolean allowBlank) {
        JsonValue value = invocation.arguments().get(field);
        if (!(value instanceof JsonText text) || text.value().length() > maxLength
                || (!allowBlank && text.value().isBlank())) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return text.value();
    }

    /** 缺失时返回 null，存在时仍拒绝非文本和超限值。 */
    private static String optionalText(Invocation invocation, String field, int maxLength) {
        if (!invocation.arguments().containsKey(field)) return null;
        return text(invocation, field, maxLength, true);
    }

    /** 可选 Workspace identity 仍执行与 RPC 相同的 opaque 前缀和字符集检查。 */
    private static String optionalIdentifier(Invocation invocation, String field, String prefix, int maxLength) {
        String value = optionalText(invocation, field, maxLength);
        if (value == null || (value.startsWith(prefix) && value.length() <= maxLength
                && value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*"))) {
            return value;
        }
        throw new IllegalArgumentException("invalid " + field);
    }

    /** discovery 页面使用 1..200 的独立上限，不复用 timeout/CAS 的整数范围。 */
    private static int discoveryLimit(Invocation invocation) {
        return invocation.arguments().containsKey("limit")
                ? (int) integer(invocation, "limit", 1, 200) : 200;
    }

    /** 读取 JSON 精确非负整数，禁止 double 舍入越过 CAS 或 Deadline 边界。 */
    private static long integer(Invocation invocation, String field, long min, long max) {
        JsonValue value = invocation.arguments().get(field);
        if (!(value instanceof io.github.kongweiguang.ja.foundation.json.JsonNumber number)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        final long parsed;
        try {
            parsed = number.value().longValueExact();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("invalid " + field);
        }
        if (parsed < min || parsed > max) throw new IllegalArgumentException("invalid " + field);
        return parsed;
    }

    /** 可选整数缺失时使用已计算的父边界，不接受显式 null。 */
    private static long optionalInteger(Invocation invocation, String field, long fallback) {
        return invocation.arguments().containsKey(field)
                ? integer(invocation, field, 1_000, 86_400_000L) : fallback;
    }

    /** 读取最多八个唯一 Task 身份；空集合无法形成有意义的事件等待。 */
    private static Set<String> identifiers(Invocation invocation, String field, int maximum) {
        JsonValue raw = invocation.arguments().get(field);
        if (!(raw instanceof JsonArray array) || array.values().isEmpty() || array.values().size() > maximum) {
            throw new IllegalArgumentException("invalid " + field);
        }
        Set<String> result = new LinkedHashSet<>();
        for (JsonValue value : array.values()) {
            if (!(value instanceof JsonText text) || !text.value().startsWith("thr_")
                    || text.value().length() > 128 || !result.add(text.value())) {
                throw new IllegalArgumentException("invalid " + field);
            }
        }
        return Set.copyOf(result);
    }

    /** 统一成功结果形状，structuredContent 与安全文本同时服务模型和 UI。 */
    private static CompletionStage<ToolResult> completed(String content, JsonObject structured) {
        return CompletableFuture.completedFuture(success(content, structured));
    }

    /** 构造已确认提交的 Tool 成功结果。 */
    private static ToolResult success(String content, JsonObject structured) {
        return new ToolResult(ToolOutcome.SUCCEEDED, content, Optional.of(structured), null);
    }

    /** 只公开稳定 Task 分类；未知实现故障不携带异常正文进入模型上下文。 */
    private static ToolResult failure(Throwable source) {
        Throwable failure = source instanceof CompletionException && source.getCause() != null
                ? source.getCause() : source;
        if (failure instanceof CancellationException) {
            return new ToolResult(ToolOutcome.CANCELLED, "Task operation was cancelled.", Optional.empty(),
                    "CANCELLED");
        }
        if (failure instanceof TaskRepositoryException taskFailure) {
            // 保留无正文的拒绝位置，真实运行中可定位准入失败，不把 SQL、参数或会话内容交给模型。
            StackTraceElement[] frames = taskFailure.getStackTrace();
            LOGGER.warn("Task operation rejected code={} origin={}", taskFailure.code(),
                    java.util.Arrays.stream(frames)
                            .filter(frame -> frame.getClassName().startsWith("io.github.kongweiguang.ja."))
                            .limit(3).toList());
            return new ToolResult(ToolOutcome.FAILED, "Task operation failed: " + taskFailure.code().name() + ".",
                    Optional.empty(), taskFailure.code().name());
        }
        if (failure instanceof IllegalArgumentException) {
            return new ToolResult(ToolOutcome.FAILED, "Task Tool arguments are invalid.", Optional.empty(),
                    "TOOL_ARGUMENTS_INVALID");
        }
        return new ToolResult(ToolOutcome.FAILED, "Task operation failed safely.", Optional.empty(),
                "TASK_TOOL_FAILED");
    }

    /** Java 枚举统一投影为稳定小写 wire 风格。 */
    private static String wire(Enum<?> value) {
        return value.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** 构造 additionalProperties=false 的 Tool 参数 schema。 */
    private static JsonObject objectSchema(Map<String, JsonObject> properties, List<String> required) {
        Map<String, JsonValue> ordered = new LinkedHashMap<>(properties);
        List<JsonValue> requiredValues = required.stream().map(JsonText::new).map(JsonValue.class::cast).toList();
        return JsonObjects.builder().putText("type", "object").put("properties", new JsonObject(ordered))
                .put("required", new JsonArray(requiredValues)).putBoolean("additionalProperties", false).build();
    }

    /** 构造普通 JSON schema 属性。 */
    private static JsonObject property(String type, String description) {
        return JsonObjects.builder().putText("type", type).putText("description", description).build();
    }

    /** 构造枚举文本属性，避免权限值由自由文本猜测。 */
    private static JsonObject enumProperty(String description, String... values) {
        List<JsonValue> choices = java.util.Arrays.stream(values).map(JsonText::new)
                .map(JsonValue.class::cast).toList();
        return JsonObjects.builder().putText("type", "string").putText("description", description)
                .put("enum", new JsonArray(choices)).build();
    }

    /** 构造 Task 身份数组属性并固定数量上限。 */
    private static JsonObject arrayProperty(String description) {
        return JsonObjects.builder().putText("type", "array").putText("description", description)
                .putNumber("minItems", 1).putNumber("maxItems", 8)
                .put("items", property("string", "Child thread identity.")).build();
    }
}
