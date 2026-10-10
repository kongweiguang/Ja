// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.runtime;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.application.gateway.AgentExtension;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntimeResolver;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRuntimeRequest;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.model.SummaryModel;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.conversation.infrastructure.tools.JaAgentToolAdapter;
import io.github.kongweiguang.ja.conversation.infrastructure.tools.JaToolBindingCodec;
import io.github.kongweiguang.ja.conversation.application.loop.WorkspaceWriteLeaseCoordinator;
import io.github.kongweiguang.ja.conversation.application.permission.JaApprovalPolicy;
import io.github.kongweiguang.ja.conversation.application.gateway.JaRequestBindingSource;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.application.gateway.JaToolFactContributor;
import io.github.kongweiguang.ja.conversation.application.gateway.RuntimeLease;
import io.github.kongweiguang.ja.conversation.application.gateway.ToolPresentationSerialization;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnChangeSetSerialization;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeMiddlewareFactory;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeRequest;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeResolver;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnToolSessionFactory;
import io.github.kongweiguang.ja.foundation.hash.DigestFields;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把 Ja 每请求配置租约桥接为 Core 的冻结运行时，不把应用路径和持久业务身份塞进 Core。
 */
public final class JaAgentRuntimeResolver implements AgentRequestRuntimeResolver {
    private final TurnRuntimeResolver turnRuntimes;
    private final TurnRuntimeMiddlewareFactory runtimeMiddlewareFactory;
    private final ModelPort models;
    private final JaRequestBindingSource bindings;
    private final JaToolBindingCodec bindingCodec;
    private final WorkspaceWriteLeaseCoordinator writeLeases;
    private final ToolPresentationSerialization presentationCodec;
    private final TurnChangeSetSerialization changeSets;
    private final SummaryModel.Factory summaryModels;
    private final List<JaToolFactContributor> factContributors;
    private final Clock clock;

    /**
     * 依赖均由 Ja 组合根持有；业务中间件通过端口按 Turn 创建，本适配器不直接依赖业务模块实现。
     */
    public JaAgentRuntimeResolver(TurnRuntimeResolver turnRuntimes,
                                  TurnRuntimeMiddlewareFactory runtimeMiddlewareFactory, ModelPort models,
                                  JaRequestBindingSource bindings, JaToolBindingCodec bindingCodec,
                                  WorkspaceWriteLeaseCoordinator writeLeases,
                                  ToolPresentationSerialization presentationCodec,
                                  TurnChangeSetSerialization changeSets,
                                  SummaryModel.Factory summaryModels,
                                  List<JaToolFactContributor> factContributors, Clock clock) {
        this.turnRuntimes = Objects.requireNonNull(turnRuntimes, "turnRuntimes");
        this.runtimeMiddlewareFactory = Objects.requireNonNull(runtimeMiddlewareFactory, "runtimeMiddlewareFactory");
        this.models = Objects.requireNonNull(models, "models");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.bindingCodec = Objects.requireNonNull(bindingCodec, "bindingCodec");
        this.writeLeases = Objects.requireNonNull(writeLeases, "writeLeases");
        this.presentationCodec = Objects.requireNonNull(presentationCodec, "presentationCodec");
        this.changeSets = Objects.requireNonNull(changeSets, "changeSets");
        this.summaryModels = Objects.requireNonNull(summaryModels, "summaryModels");
        this.factContributors = List.copyOf(Objects.requireNonNull(factContributors, "factContributors"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 只冻结本次 Ja runtime 可确定的静态身份；prompt 和可见工具目录必须由 Core 在真实请求边界生成，
     * 避免恢复时把初始租约摘要冒充成已经发送的请求事实。
     */
    private static Map<String, String> modelIdentityAttributes(RuntimeLease runtime) {
        return Map.of(
                "accessMode", runtime.accessMode().name(),
                "collaborationMode", runtime.collaborationMode().name(),
                "configGeneration", runtime.generationId());
    }

    /**
     * 从已适配 Ja 工具中按原始目录计算可见身份，不用工具类别或名称前缀推导权限。
     */
    private static Set<String> toolNames(List<AgentTool> tools) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (AgentTool tool : tools) {
            String name = Objects.requireNonNull(Objects.requireNonNull(tool, "tool").spec(), "tool spec").name();
            if (!names.add(name)) {
                throw new IllegalArgumentException("duplicate runtime tool: " + name);
            }
        }
        return Set.copyOf(names);
    }

    /**
     * 仅使用可核实的 Provider route 标签参与 fingerprint，不输出或摘要凭据值。
     */
    private static String modelRoute(ExtensionRegistry.Snapshot base, ModelPort.ModelConfiguration model) {
        List<String> matches = base.modelProviders().stream().map(ExtensionRegistry.ModelProvider::id)
                .filter(model.providerId()::equals).toList();
        if (matches.size() > 1) {
            throw new IllegalArgumentException("duplicate model provider route");
        }
        return matches.isEmpty() ? "builtin-api:" + model.api().name() : "extension:" + matches.getFirst();
    }

    /**
     * 配置源必须解析出本次 Core 请求指定的模型身份，不能静默忽略模型切换意图。
     */
    private static void validateModelSelection(AgentRuntimeRequest request, TurnRuntimeRequest turn,
                                               ModelPort.ModelConfiguration model) {
        if (!turn.providerId().equals(model.providerId()) || !turn.modelId().equals(model.modelId())) {
            throw new IllegalArgumentException("Ja runtime model selection mismatch");
        }
        request.requiredModelSelection().ifPresent(selection -> {
            if (!selection.matches(model)) {
                throw new IllegalArgumentException("persisted model selection does not match Ja configuration");
            }
        });
        request.modelOverride().ifPresent(override -> {
            if (!override.providerId().equals(model.providerId()) || !override.modelId().equals(model.modelId())) {
                throw new IllegalArgumentException("model override selection does not match Ja configuration");
            }
        });
    }

    /**
     * 恢复执行必须带 Core 已持久化的无密钥模型身份，不能拿当前 Thread 偏好隐式换模型。
     */
    private static void validateRecoveredModelSelection(AgentRuntimeRequest request,
                                                        Optional<AgentStore.ExecutionSnapshot> recovered) {
        if (recovered.isPresent() && request.requiredModelSelection().isEmpty()) {
            throw new IllegalArgumentException("recovered Ja execution is missing its persisted model selection");
        }
    }

    /**
     * 普通请求绑定真实 Turn；压缩请求使用独立 execution identity 且不伪造 Ja Turn。
     */
    private static void validateRequestIdentity(AgentIdentity identity, TurnRuntimeRequest request,
                                                boolean compaction) {
        boolean validExecution = compaction
                ? request.turnId() == null && identity.turnId().matches("compaction_[0-9a-f]{64}")
                : request.turnId() != null && identity.turnId().equals(request.turnId());
        if (!identity.threadId().equals(request.threadId()) || !validExecution) {
            throw new IllegalArgumentException("Ja runtime request identity mismatch");
        }
    }

    /**
     * 恢复时拒绝跨租户、会话或执行复用游标；路径和业务对象不从游标重建。
     */
    private static void validateRecoveredIdentity(
            AgentIdentity identity, Optional<AgentStore.ExecutionSnapshot> recovered) {
        recovered.ifPresent(snapshot -> {
            AgentStore.ExecutionKey key = snapshot.key();
            if (!identity.tenantId().equals(key.conversation().tenantId())
                || !identity.threadId().equals(key.conversation().threadId())
                || !identity.turnId().equals(key.turnId())) {
                throw new IllegalArgumentException("recovered execution identity mismatch");
            }
        });
    }

    /**
     * 关闭本 resolver 实际创建的资源；压缩无 MCP session，但仍需释放配置租约。
     */
    private static AutoCloseable release(RuntimeLease runtime, TurnToolSessionFactory.Session session) {
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Throwable failure = null;
            if (session != null) {
                try {
                    session.close();
                } catch (RuntimeException | Error closeFailure) {
                    failure = closeFailure;
                }
            }
            try {
                runtime.close();
            } catch (RuntimeException | Error closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                }
                else {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        };
    }

    /**
     * 运行时装配中任一步失败时释放已取得资源，并保留原始失败为首要原因。
     */
    private static void closeAfterFailure(TurnToolSessionFactory.Session session, RuntimeLease runtime,
                                          Throwable failure) {
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        if (runtime != null) {
            try {
                runtime.close();
            } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    /**
     * 恢复指纹拒绝路由漂移；独立压缩没有 Ja Turn，沿用可空字段编码而不伪造普通 Turn 身份。
     */
    private static String bindingFingerprint(AgentIdentity identity, AgentRuntimeRequest coreRequest,
                                             TurnRuntimeRequest turn, RuntimeLease runtime,
                                             ExtensionRegistry.Snapshot base, String modelRoute,
                                             AgentRuntimeRequest.Kind requestKind) {
        Fingerprint fingerprint = new Fingerprint();
        fingerprint.field("request.kind", requestKind.name());
        fingerprint.field("identity.tenant", identity.tenantId());
        fingerprint.field("identity.thread", identity.threadId());
        fingerprint.field("identity.turn", identity.turnId());
        fingerprint.field("identity.user", identity.userId());
        coreRequest.contextAttributes().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> {
                    fingerprint.field("context.key", entry.getKey());
                    fingerprint.field("context.value", entry.getValue());
                });
        fingerprint.field("thread", turn.threadId());
        fingerprint.field("turn", optional(turn.turnId()));
        fingerprint.field("workspace.id", turn.workspaceId());
        fingerprint.field("provider.id", turn.providerId());
        fingerprint.field("model.id", turn.modelId());
        fingerprint.field("origin", turn.origin().name());
        fingerprint.field("plan.revision", optional(turn.planRevisionId()));
        fingerprint.field("plan.run", optional(turn.runId()));
        fingerprint.field("generation", runtime.generationId());
        fingerprint.field("model.route", modelRoute);
        appendBaseExtensions(fingerprint, base);
        fingerprint.field("tool.catalog", runtime.toolCatalogDigest());
        fingerprint.field("prompt.revision", runtime.promptRevision());
        fingerprint.field("access.mode", runtime.accessMode().name());
        fingerprint.field("collaboration.mode", runtime.collaborationMode().name());
        fingerprint.field("limits.input", Integer.toString(runtime.limits().maxInputTokens()));
        fingerprint.field("limits.output", Integer.toString(runtime.limits().maxOutputTokens()));
        fingerprint.field("limits.requestNanos", Long.toString(runtime.limits().requestWindow().toNanos()));
        appendModel(fingerprint, runtime.model());
        return fingerprint.finish();
    }

    /**
     * Base 能力虽由 Engine 冻结，仍纳入请求指纹，恢复时拒绝扩展目录发生的安全漂移。
     */
    private static void appendBaseExtensions(Fingerprint fingerprint, ExtensionRegistry.Snapshot base) {
        base.extensionIds().stream().sorted().forEach(value -> fingerprint.field("extension.id", value));
        base.modelProviders().stream().map(ExtensionRegistry.ModelProvider::id).sorted()
                .forEach(value -> fingerprint.field("extension.modelProvider", value));
        for (io.github.kongweiguang.ja.agent.application.gateway.skill.SkillSource source : base.skillSources()) {
            fingerprint.field("extension.skillSource", source.id());
            fingerprint.field("extension.skillPriority", Integer.toString(source.priority()));
        }
        for (AgentTool tool : base.tools()) {
            AgentTool required = Objects.requireNonNull(tool, "base tool");
            io.github.kongweiguang.ja.agent.domain.tool.ToolSpec spec =
                    Objects.requireNonNull(required.spec(), "base tool spec");
            fingerprint.field("extension.tool.name", spec.name());
            fingerprint.field("extension.tool.description", spec.description());
            fingerprint.field("extension.tool.schema", AgentTool.canonicalSchema(spec.inputSchema()));
            fingerprint.field("extension.tool.sideEffect", required.sideEffect().name());
            fingerprint.field("extension.tool.approval", required.approvalRequirement().name());
            fingerprint.field("extension.tool.revalidates", Boolean.toString(required.validatesResolvedArguments()));
        }
        base.middleware().forEach(value -> {
            fingerprint.field("extension.middleware.namespace", value.namespace());
            fingerprint.field("extension.middleware.schema", Integer.toString(value.stateSchemaVersion()));
        });
        base.listeners().forEach(value -> fingerprint.field("extension.listener", value.id()));
    }

    /**
     * 指纹仅含影响路由的非秘密模型字段，API Key 永不进入哈希输入或日志。
     */
    private static void appendModel(Fingerprint fingerprint, ModelPort.ModelConfiguration model) {
        fingerprint.field("model.provider", model.providerId());
        fingerprint.field("model.id", model.modelId());
        fingerprint.field("model.generation", model.configGeneration());
        fingerprint.field("model.api", model.api().name());
        fingerprint.field("model.name", model.model());
        fingerprint.field("model.endpoint", model.baseUri().toASCIIString());
        fingerprint.field("model.connectNanos", Long.toString(model.connectTimeout().toNanos()));
        fingerprint.field("model.requestNanos", Long.toString(model.requestTimeout().toNanos()));
        fingerprint.field("model.temperature", optional(model.generation().temperature()));
        fingerprint.field("model.topP", optional(model.generation().topP()));
        fingerprint.field("model.maxOutput", optional(model.generation().maxOutputTokens()));
        fingerprint.field("model.reasoning", optional(model.generation().reasoningLevel()));
        model.inputModalities().stream().map(Enum::name).sorted()
                .forEach(value -> fingerprint.field("model.modality", value));
    }

    /**
     * 将空值编码为固定标记，使身份字段缺失与文本 "null" 不会混淆。
     */
    private static String optional(Object value) {
        return value == null ? "<absent>" : value.toString();
    }

    /**
     * 成功时把租约与 MCP 会话交给返回 runtime 的幂等 close 回调；失败时本方法关闭已取得资源并保留原异常。
     * 请求级业务中间件由注入端口依据冻结 Turn 请求创建，避免此通用 Resolver 反向依赖业务实现。
     * Core 在执行结束、取消或关闭时释放该 runtime，因此本地不能提前关闭成功交接的资源。
     */
    @SuppressWarnings("PMD.CloseResource")
    @Override
    public AgentRequestRuntime resolve(AgentRuntimeRequest request, ExtensionRegistry.Snapshot baseExtensions,
                                       Optional<AgentStore.ExecutionSnapshot> recoveredExecution) {
        AgentRuntimeRequest coreRequest = Objects.requireNonNull(request, "request");
        ExtensionRegistry.Snapshot base = Objects.requireNonNull(baseExtensions, "baseExtensions");
        Optional<AgentStore.ExecutionSnapshot> recovered =
                Objects.requireNonNull(recoveredExecution, "recoveredExecution");
        validateRecoveredIdentity(coreRequest.identity(), recovered);
        validateRecoveredModelSelection(coreRequest, recovered);
        coreRequest.cancellationToken().throwIfCancellationRequested();

        JaRequestBindingSource.Binding binding = Objects.requireNonNull(
                bindings.bind(coreRequest, recovered), "Ja request binding");
        TurnRuntimeRequest turnRequest = binding.runtimeRequest();
        boolean compaction = coreRequest.kind() == AgentRuntimeRequest.Kind.COMPACTION;
        validateRequestIdentity(coreRequest.identity(), turnRequest, compaction);

        RuntimeLease runtime = Objects.requireNonNull(turnRuntimes.resolve(turnRequest), "Ja runtime lease");
        TurnToolSessionFactory.Session mcpSession = null;
        try {
            coreRequest.cancellationToken().throwIfCancellationRequested();
            ExtensionRegistry.Snapshot extensions;
            if (compaction) {
                extensions = base;
            } else {
                mcpSession = Objects.requireNonNull(
                        runtime.toolSessions().open(coreRequest.cancellationToken()), "MCP session");
                coreRequest.cancellationToken().throwIfCancellationRequested();
                List<JaTool> runtimeTools = runtime.tools();
                List<JaTool> mcpTools = mcpSession.tools();
                Set<String> mcpVisibleNames = Set.copyOf(mcpTools.stream()
                        .map(tool -> tool.spec().name()).toList());
                List<JaTool> jaTools = new ArrayList<>(runtimeTools.size() + mcpTools.size());
                jaTools.addAll(runtimeTools);
                jaTools.addAll(mcpTools);
                List<AgentTool> requestTools = adaptTools(jaTools, coreRequest.identity(), turnRequest, runtime);
                ExtensionRegistry requestRegistry = ExtensionRegistry.copyOf(base);
                requestRegistry.register(new JaRequestToolsExtension(requestTools));
                requestRegistry.registerMiddleware(new JaToolVisibilityMiddleware(toolNames(requestTools),
                        runtime.visibleToolNames(), mcpVisibleNames));
                requestRegistry.registerMiddleware(new JaToolApprovalMiddleware(toolNames(requestTools), clock));
                requestRegistry.registerMiddleware(new JaClarificationWaitMiddleware(turnRequest,
                        runtime.visibleToolNames()));
                requestRegistry.registerMiddleware(new JaTurnChangeSetMiddleware(coreRequest.identity(),
                        turnRequest.workspaceRoot(), turnRequest.workspaceId(), jaTools, base.tools(), changeSets));
                runtimeMiddlewareFactory.create(turnRequest).forEach(requestRegistry::registerMiddleware);
                extensions = requestRegistry.freeze();
            }
            validateModelSelection(coreRequest, turnRequest, runtime.model());
            ModelPort model = resolveModelPort(base, runtime.model());
            AgentPromptSession prompt = Objects.requireNonNull(runtime.promptSession(), "Prompt session");
            String fingerprint = bindingFingerprint(coreRequest.identity(), coreRequest, turnRequest, runtime,
                    base, modelRoute(base, runtime.model()), coreRequest.kind());

            AgentRequestRuntime resolved = new AgentRequestRuntime(runtime.model(), model, summaryModels,
                    extensions, prompt, fingerprint, binding.transactionParticipant(), runtime.attachments(),
                    runtime.outputLimits(), modelIdentityAttributes(runtime), release(runtime, mcpSession));
            return resolved;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(mcpSession, runtime, failure);
            throw failure;
        }
    }

    /**
     * 把 Core 已验证身份和 Ja 冻结上下文绑定到每个工具，不从工具名或 context attributes 推权限。
     * 每次进入工具安全点才开启 IO 窗口；准入时间只属持久身份，不能让模型生成、审批或前序工具耗尽后续能力。
     */
    private List<AgentTool> adaptTools(List<JaTool> tools, AgentIdentity identity,
                                       TurnRuntimeRequest turnRequest, RuntimeLease runtime) {
        List<AgentTool> adapted = new ArrayList<>(tools.size());
        RuntimeLease frozenRuntime = Objects.requireNonNull(runtime, "runtime");
        for (JaTool tool : tools) {
            JaTool required = Objects.requireNonNull(tool, "Ja tool");
            JaTool.ExecutionContextFactory contextFactory = (invocation, coreContext) -> {
                AgentTool.ExecutionContext active = Objects.requireNonNull(coreContext, "Core tool context");
                if (!identity.equals(active.identity())) {
                    throw new IllegalArgumentException("tool execution identity does not match the bound request");
                }
                return new JaTool.ExecutionContext(identity, invocation.callId(), active.callKey(),
                        turnRequest.threadId(), turnRequest.turnId(), turnRequest.workspaceRoot(),
                        frozenRuntime.accessMode(), frozenRuntime.generationId(),
                        clock.instant().plus(frozenRuntime.limits().requestWindow()),
                        turnRequest.workspaceId(), turnRequest.planRevisionId(), turnRequest.runId(),
                        turnRequest.origin());
            };
            adapted.add(new JaAgentToolAdapter(required, new JaAgentToolAdapter.Dependencies(contextFactory,
                    frozenRuntime.accessMode(), bindingCodec, writeLeases, presentationCodec,
                    frozenRuntime.presentationSecrets(), factContributors,
                    turnRequest.origin(), frozenRuntime.collaborationMode())));
        }
        return List.copyOf(adapted);
    }

    /**
     * 配置方持有唯一模型目录；Core 扩展可按显式供应商 ID 覆盖应用内置多协议适配器。
     */
    private ModelPort resolveModelPort(ExtensionRegistry.Snapshot base, ModelPort.ModelConfiguration model) {
        List<ExtensionRegistry.ModelProvider> matches = base.modelProviders().stream()
                .filter(provider -> provider.id().equals(model.providerId())).toList();
        if (matches.size() > 1) {
            throw new IllegalArgumentException("duplicate model provider route");
        }
        return matches.isEmpty() ? models : matches.getFirst().port();
    }

    /**
     * 把一份冻结工具批次显式注册进请求级 ExtensionRegistry，借 Core 的重名校验和顺序语义。
     *
     * @param tools 工具列表
     */
    private record JaRequestToolsExtension(List<AgentTool> tools) implements AgentExtension {
        /**
         * 固定全量工具目录的注册顺序，恢复期不会因外部列表被修改而重排。
         */
        private JaRequestToolsExtension {
            tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        }

        /**
         * 返回 Ja 请求能力组的固定扩展身份。
         */
        @Override
        public String id() {
            return "ja.request-tools";
        }

        /**
         * 仅注册已经由本次 frozen Ja runtime 适配的工具定义，不创建或动态发现能力。
         */
        @Override
        public void register(ExtensionRegistry registry) {
            for (AgentTool tool : tools) {
                registry.registerTool(tool);
            }
        }
    }

    /**
     * 只隐藏本次 Ja runtime 未授权的工具，基准扩展与当前 MCP 会话保持可见。
     *
     * @param jaToolNames Ja 工具名称集合
     * @param runtimeVisibleNames 运行时可见的能力名称
     * @param mcpVisibleNames MCP 可见名称集合
     */
    private record JaToolVisibilityMiddleware(Set<String> jaToolNames,
                                              Set<String> runtimeVisibleNames,
                                              Set<String> mcpVisibleNames) implements AgentMiddleware {
        /**
         * 验证可见集合均属于本次适配目录，不能借请求补丁暴露未注册工具。
         */
        private JaToolVisibilityMiddleware {
            jaToolNames = Set.copyOf(Objects.requireNonNull(jaToolNames, "jaToolNames"));
            runtimeVisibleNames = Set.copyOf(Objects.requireNonNull(runtimeVisibleNames, "runtimeVisibleNames"));
            mcpVisibleNames = Set.copyOf(Objects.requireNonNull(mcpVisibleNames, "mcpVisibleNames"));
            if (!jaToolNames.containsAll(runtimeVisibleNames) || !jaToolNames.containsAll(mcpVisibleNames)) {
                throw new IllegalArgumentException("visible Ja tool names are outside the request catalog");
            }
        }

        /**
         * 返回稳定 namespace，Core 按它隔离该无状态请求策略。
         */
        @Override
        public String id() {
            return "ja.tool-visibility";
        }

        /**
         * 保留基础能力和 MCP，并只将 frozen runtime 声明的 Ja 子集交给本次模型请求。
         */
        @Override
        public ExtensionDecision<RequestPatch> beforeRequest(RequestContext context) {
            Objects.requireNonNull(context, "context");
            LinkedHashSet<String> visible = new LinkedHashSet<>(context.registeredTools().stream()
                    .map(ToolSpec::name).toList());
            visible.removeAll(jaToolNames);
            visible.addAll(runtimeVisibleNames);
            visible.addAll(mcpVisibleNames);
            return ExtensionDecision.unchanged(new RequestPatch("", List.of(), Optional.of(visible)),
                    context.extensionState());
        }
    }

    /**
     * 只为 Ja 适配器生成通用审批 Wait；权限分类已由冻结 accessMode 与最终参数决定。
     *
     * @param jaToolNames Ja 工具名称集合
     * @param clock 时钟
     */
    private record JaToolApprovalMiddleware(Set<String> jaToolNames,
                                            Clock clock) implements AgentMiddleware {
        /**
         * 冻结本次目录并复用组合根时钟，防止请求间共享可变工具集或自建时间源。
         */
        private JaToolApprovalMiddleware {
            jaToolNames = Set.copyOf(Objects.requireNonNull(jaToolNames, "jaToolNames"));
            Objects.requireNonNull(clock, "clock");
        }

        /**
         * 返回固定审批命名空间，供注册冲突和状态隔离检查使用。
         */
        @Override
        public String id() {
            return "ja.tool-approval";
        }

        /**
         * 将需要用户许可的最终 Ja 调用转换为有界、可持久恢复的宿主等待。
         */
        @Override
        public ExtensionDecision<ToolDecision> beforeTool(ToolContext context) {
            Objects.requireNonNull(context, "context");
            if (!jaToolNames.contains(context.invocation().toolName())
                || context.approvalRequirement() == AgentTool.ApprovalRequirement.NOT_REQUIRED) {
                return ExtensionDecision.unchanged(
                        ToolDecision.proceed(context.invocation().arguments()), context.extensionState());
            }
            Instant expiresAt = JaApprovalPolicy.approvalExpiry(clock);
            WaitRequest request = new WaitRequest("approval", "请确认是否允许执行此次工具调用。",
                    JsonObjects.builder().putText("expiresAt", expiresAt.toString()).build());
            return ExtensionDecision.unchanged(ToolDecision.waitFor(request), context.extensionState());
        }
    }

    /**
     * 顺序与字段名、字节长度一同进入摘要，避免拼接歧义和生成大型中间 JSON 字符串。
     */
    private static final class Fingerprint {
        private final MessageDigest digest;

        /**
         * JDK 必须提供 SHA-256；运行时缺失属于平台错误，不能降级到弱摘要。
         */
        private Fingerprint() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException unavailable) {
                throw new IllegalStateException("SHA-256 is unavailable", unavailable);
            }
        }

        /**
         * 以带长度的 UTF-8 写入键值对，禁止字段边界碰撞。
         */
        private void field(String name, String value) {
            update(name);
            update(Objects.requireNonNull(value, "fingerprint value"));
        }

        /**
         * 先写长度再写内容，避免分隔符碰撞且不依赖 JSON serializer。
         */
        private void update(String value) {
            DigestFields.appendUtf8(digest, Objects.requireNonNull(value, "fingerprint value"));
        }

        /**
         * 返回 AgentRequestRuntime 所需的小写完整 SHA-256。
         */
        private String finish() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
