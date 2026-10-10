// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.runtime;

import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntimeResolver;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRuntimeRequest;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPortResolver;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry.ModelProvider;
import io.github.kongweiguang.ja.agent.application.gateway.model.SummaryModel;
import io.github.kongweiguang.ja.agent.application.gateway.skill.SkillSource;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.prompt.DefaultAgentPromptSession;
import io.github.kongweiguang.ja.agent.application.skill.SkillCatalog;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按显式模型、冻结扩展、Skill 来源和已声明窗口创建一次请求租约。
 */
public final class StandardAgentRequestRuntimeResolver implements AgentRequestRuntimeResolver {
    private final ModelPort.ModelConfiguration defaultModel;
    private final ModelPortResolver modelPorts;
    private final ContextBudget contextBudget;
    private final SummaryModel.Factory summaryModels;
    private final ManagedAttachmentReader attachments;
    private final ToolProjectionLimits outputLimits;
    private final java.util.function.Function<AgentRuntimeRequest, String> hostPrompt;

    /**
     * 要求 Provider、上下文预算、附件读取和 Tool 输出投影均由宿主明确提供，运行时不会补造本地能力。
     */
    public StandardAgentRequestRuntimeResolver(ModelPort.ModelConfiguration defaultModel,
                                               ModelPortResolver modelPorts,
                                               ContextBudget contextBudget,
                                               SummaryModel.Factory summaryModels,
                                               ManagedAttachmentReader attachments,
                                               ToolProjectionLimits outputLimits) {
        this(defaultModel, modelPorts, contextBudget, summaryModels, attachments, outputLimits, null);
    }

    /**
     * 宿主必须显式提供完整 Persona；未提供时传入空 Prompt，不由 Core 猜测 Agent 身份或行为。
     */
    public StandardAgentRequestRuntimeResolver(ModelPort.ModelConfiguration defaultModel,
                                               ModelPortResolver modelPorts,
                                               ContextBudget contextBudget,
                                               SummaryModel.Factory summaryModels,
                                               ManagedAttachmentReader attachments,
                                               ToolProjectionLimits outputLimits,
                                               java.util.function.Function<AgentRuntimeRequest, String> hostPrompt) {
        this.defaultModel = Objects.requireNonNull(defaultModel, "defaultModel");
        this.modelPorts = Objects.requireNonNull(modelPorts, "modelPorts");
        this.contextBudget = Objects.requireNonNull(contextBudget, "contextBudget");
        this.summaryModels = Objects.requireNonNull(summaryModels, "summaryModels");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.outputLimits = Objects.requireNonNull(outputLimits, "outputLimits");
        this.hostPrompt = hostPrompt;
    }

    /**
     * 恢复游标中的租户、会话或执行身份与本次请求不一致时立即拒绝。
     */
    private static void validateRecoveredIdentity(
        AgentIdentity identity, Optional<AgentStore.ExecutionSnapshot> recoveredExecution) {
        Objects.requireNonNull(identity, "identity");
        recoveredExecution.ifPresent(snapshot -> {
            AgentStore.ExecutionKey key = snapshot.key();
            if (!identity.tenantId().equals(key.conversation().tenantId())
                || !identity.threadId().equals(key.conversation().threadId())
                || !identity.turnId().equals(key.turnId())) {
                throw new IllegalArgumentException("recovered_turn_identity_mismatch");
            }
        });
    }

    /**
     * 汇总非秘密模型、来源注册、工具路由与投影预算；可变目录内容留给 Prompt revision，避免恢复锁死更新。
     */
    private static String bindingFingerprint(AgentIdentity identity, ModelPort.ModelConfiguration model,
                                             String modelRoute, ExtensionRegistry.Snapshot extensions,
                                             ContextBudget contextBudget, ToolProjectionLimits outputLimits) {
        Fingerprint fingerprint = new Fingerprint();
        fingerprint.field("tenant", identity.tenantId());
        fingerprint.field("thread", identity.threadId());
        fingerprint.field("turn", identity.turnId());
        fingerprint.field("user", identity.userId());
        fingerprint.field("model.route", modelRoute);
        fingerprint.field("model.provider", model.providerId());
        fingerprint.field("model.id", model.modelId());
        fingerprint.field("model.generation", model.configGeneration());
        fingerprint.field("model.api", model.api().name());
        fingerprint.field("model.name", model.model());
        fingerprint.field("model.endpoint", model.baseUri().toASCIIString());
        fingerprint.field("model.connectTimeoutNanos", Long.toString(model.connectTimeout().toNanos()));
        fingerprint.field("model.requestTimeoutNanos", Long.toString(model.requestTimeout().toNanos()));
        fingerprint.field("model.temperature", optional(model.generation().temperature()));
        fingerprint.field("model.topP", optional(model.generation().topP()));
        fingerprint.field("model.maxOutputTokens", optional(model.generation().maxOutputTokens()));
        fingerprint.field("model.reasoningLevel", optional(model.generation().reasoningLevel()));
        model.inputModalities().stream().map(Enum::name).sorted()
            .forEach(value -> fingerprint.field("model.modality", value));
        fingerprint.field("context.contextWindowTokens", Long.toString(contextBudget.contextWindowTokens()));
        fingerprint.field("context.maxOutputTokens", Long.toString(contextBudget.maxOutputTokens()));
        fingerprint.field("context.autoCompact", Boolean.toString(contextBudget.autoCompact()));
        fingerprint.field("toolOutput.headCharacters", Integer.toString(outputLimits.headCharacters()));
        fingerprint.field("toolOutput.tailCharacters", Integer.toString(outputLimits.tailCharacters()));
        fingerprint.field("toolOutput.artifactOnly", Boolean.toString(outputLimits.artifactOnly()));

        fingerprint.field("extension.count", Integer.toString(extensions.extensionIds().size()));
        extensions.extensionIds().stream().sorted().forEach(value -> fingerprint.field("extension.id", value));
        fingerprint.field("provider.count", Integer.toString(extensions.modelProviders().size()));
        extensions.modelProviders().stream().sorted(Comparator.comparing(ExtensionRegistry.ModelProvider::id))
            .forEach(provider -> fingerprint.field("provider.id", provider.id()));
        fingerprint.field("skillSource.count", Integer.toString(extensions.skillSources().size()));
        for (SkillSource source : extensions.skillSources()) {
            fingerprint.field("skillSource.id", source.id());
            fingerprint.field("skillSource.priority", Integer.toString(source.priority()));
        }

        fingerprint.field("tool.count", Integer.toString(extensions.tools().size()));
        for (AgentTool tool : extensions.tools()) {
            ToolSpec spec = Objects.requireNonNull(tool.spec(), "tool spec");
            fingerprint.field("tool.name", spec.name());
            fingerprint.field("tool.description", spec.description());
            fingerprint.field("tool.schema", AgentTool.canonicalSchema(spec.inputSchema()));
            fingerprint.field("tool.sideEffect", Objects.requireNonNull(tool.sideEffect(), "tool side effect").name());
            fingerprint.field("tool.approval", Objects.requireNonNull(
                tool.approvalRequirement(), "tool approval requirement").name());
            fingerprint.field("tool.resolvedArguments",
                Boolean.toString(tool.validatesResolvedArguments()));
        }
        fingerprint.field("middleware.count", Integer.toString(extensions.middleware().size()));
        extensions.middleware().forEach(value -> {
            fingerprint.field("middleware.id", value.namespace());
            fingerprint.field("middleware.stateSchema", Integer.toString(value.stateSchemaVersion()));
        });
        fingerprint.field("listener.count", Integer.toString(extensions.listeners().size()));
        extensions.listeners().forEach(value -> fingerprint.field("listener.id", value.id()));
        return fingerprint.finish();
    }

    /**
     * 区分未设置的生成参数和字面字符串，同时不暴露凭据。
     */
    private static String optional(Object value) {
        return value == null ? "<provider-default>" : value.toString();
    }

    /**
     * 拒绝公开快照构造器可能带入的 provider、tool 与 listener 身份冲突，middleware namespace 已在快照构造期校验。
     */
    private static void validateExtensionSnapshot(ExtensionRegistry.Snapshot extensions) {
        requireUnique(extensions.modelProviders().stream().map(ExtensionRegistry.ModelProvider::id).toList(),
            "model provider");
        requireUnique(extensions.tools().stream()
            .map(tool -> Objects.requireNonNull(tool, "tool").spec().name()).toList(), "tool");
        requireUnique(extensions.listeners().stream()
            .map(value -> Objects.requireNonNull(value, "listener").id()).toList(), "event listener");
    }

    /**
     * 在执行前拒绝重复身份，使每个已冻结能力都只有一条可审计路由。
     */
    private static void requireUnique(List<String> identities, String kind) {
        if (new HashSet<>(identities).size() != identities.size()) {
            throw new IllegalArgumentException("duplicate " + kind + " identity");
        }
    }

    /**
     * 解析单一不可变请求视图；精确注册的模型 ID 优先，未匹配时才交给注入的模型解析器。
     */
    @Override
    public AgentRequestRuntime resolve(AgentRuntimeRequest request, ExtensionRegistry.Snapshot baseExtensions,
                                       Optional<AgentStore.ExecutionSnapshot> recoveredExecution) {
        AgentRuntimeRequest runtimeRequest = Objects.requireNonNull(request, "request");
        ExtensionRegistry.Snapshot extensions = Objects.requireNonNull(baseExtensions, "baseExtensions");
        Optional<AgentStore.ExecutionSnapshot> recovered =
            Objects.requireNonNull(recoveredExecution, "recoveredExecution");
        validateExtensionSnapshot(extensions);
        ModelPort.ModelConfiguration configuration = runtimeRequest.modelOverride().orElse(defaultModel);
        runtimeRequest.requiredModelSelection().ifPresent(selection -> {
            if (!selection.matches(configuration)) {
                throw new IllegalStateException("recovered_model_selection_mismatch");
            }
        });
        validateRecoveredIdentity(runtimeRequest.identity(), recovered);
        ResolvedModel resolvedModel = resolveModel(configuration, extensions);

        SkillCatalog skillCatalog = new SkillCatalog(extensions.skillSources());
        SkillRequestContext skillContext = new SkillRequestContext(
            runtimeRequest.identity().tenantId(), runtimeRequest.contextAttributes());
        SkillCatalog.Catalog discoveredSkills = extensions.skillSources().isEmpty()
            ? skillCatalog.emptyCatalog(skillContext)
            : skillCatalog.discover(skillContext, Set.of());
        String fingerprint = bindingFingerprint(runtimeRequest.identity(), configuration, resolvedModel.route(),
            extensions, contextBudget, outputLimits);
        // Core 允许空 system prompt，但绝不替宿主补造 Persona 或行为规则。
        String configuredPrompt = hostPrompt == null ? "" : hostPrompt.apply(runtimeRequest);
        DefaultAgentPromptSession promptSession = new DefaultAgentPromptSession(
                skillCatalog, discoveredSkills, skillContext, contextBudget, fingerprint,
                extensions.tools().stream().map(AgentTool::spec).toList(), configuredPrompt);
        return new AgentRequestRuntime(configuration, resolvedModel.port(), resolvedModel.summaries(), extensions, promptSession,
            fingerprint, io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant.NONE, attachments, outputLimits,
            Map.of(),
            new RuntimeRelease(promptSession));
    }

    /**
     * 优先选择唯一精确匹配的注册供应商；注册端口和注入端口均由宿主负责关闭。
     */
    private ResolvedModel resolveModel(ModelPort.ModelConfiguration configuration,
                                       ExtensionRegistry.Snapshot extensions) {
        List<ExtensionRegistry.ModelProvider> matches = extensions.modelProviders().stream()
            .filter(provider -> provider.id().equals(configuration.providerId()))
            .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException("duplicate_model_provider_route:" + configuration.providerId());
        }
        if (!matches.isEmpty()) {
            ModelProvider registered = matches.getFirst();
            SummaryModel.Factory summaries = registered.summaries().orElse(binding -> {
                throw new IllegalStateException("registered_model_summary_not_supported:" + registered.id());
            });
            return new ResolvedModel(registered.port(), "registered:" + configuration.providerId()
                                                        + (registered.summaries().isPresent() ? ":summary" : ":no-summary"), summaries);
        }
        ModelPort port = ModelPortResolver.requireResolved(modelPorts, configuration);
        return new ResolvedModel(port, "resolver-api:" + configuration.api().name(), summaryModels);
    }

    /**
     * 固定已选端口和路由，避免运行派发与持久指纹采用不同模型适配器。
     *
     * @param port      模型调用端口
     * @param route     路由
     * @param summaries 摘要集合
     */
    private record ResolvedModel(ModelPort port,
                                 String route,
                                 SummaryModel.Factory summaries) {
        /**
         * 端口和路由必须成对存在，不能发布半绑定的请求环境。
         */
        private ResolvedModel {
            Objects.requireNonNull(port, "port");
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(summaries, "summaries");
        }
    }

    /**
     * 只释放本请求拥有的 Prompt 状态，并使竞争关闭路径幂等。
     */
    private static final class RuntimeRelease implements AutoCloseable {
        private final DefaultAgentPromptSession promptSession;
        private final AtomicBoolean released = new AtomicBoolean();

        /**
         * 只接管解析器创建的资源，模型供应商和存储仍属于注入方。
         */
        private RuntimeRelease(DefaultAgentPromptSession promptSession) {
            this.promptSession = Objects.requireNonNull(promptSession, "promptSession");
        }

        /**
         * 即使取消与 Engine 关闭同时发生，也只清理一次请求提示状态。
         */
        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                promptSession.close();
            }
        }
    }

    /**
     * 将带长度的 UTF-8 字段直接送入 SHA-256，排除凭据并避免字段边界碰撞。
     */
    private static final class Fingerprint {
        private final MessageDigest digest;

        /**
         * 在发布任何运行时能力前创建 Java 必须提供的 SHA-256 摘要器。
         */
        private Fingerprint() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
            }
        }

        /**
         * 同时写入字段名和字段值长度，避免任意扩展身份相邻时发生歧义。
         */
        private void field(String name, String value) {
            update(name);
            update(Objects.requireNonNull(value, "fingerprint value"));
        }

        /**
         * 先写长度再写 UTF-8 内容，避免分隔符歧义和构造无界中间字符串。
         */
        private void update(String value) {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            digest.update((byte) (encoded.length >>> 24));
            digest.update((byte) (encoded.length >>> 16));
            digest.update((byte) (encoded.length >>> 8));
            digest.update((byte) encoded.length);
            digest.update(encoded);
        }

        /**
         * 返回符合 AgentRequestRuntime 绑定契约的小写完整摘要。
         */
        private String finish() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
