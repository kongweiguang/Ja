// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.runtime;

import io.github.kongweiguang.ja.catalog.adapter.out.mcp.generation.GenerationCatalog;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.generation.GenerationTurnMcpSessionFactory;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.session.TurnMcpSessionFactory;
import io.github.kongweiguang.ja.catalog.adapter.out.skills.JaSkillSources;
import io.github.kongweiguang.ja.catalog.port.out.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.conversation.adapter.out.tools.BuiltInTools;
import io.github.kongweiguang.ja.conversation.adapter.out.tools.ShellCapability;
import io.github.kongweiguang.ja.conversation.application.loop.McpAgentTool;
import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
import io.github.kongweiguang.ja.conversation.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnLimits;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSession;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSessionFactory;
import io.github.kongweiguang.ja.conversation.port.out.ManagedAttachmentReader;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.conversation.port.out.RuntimeLease;
import io.github.kongweiguang.ja.conversation.port.out.SkillCatalog;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeRequest;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver;
import io.github.kongweiguang.ja.conversation.port.out.TurnToolSessionFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.Comparator;

/**
 * 从同一配置代际冻结 Provider、Skill、MCP 与预算，供 TurnService 原子接管。
 */
public final class ConfigurationTurnRuntimeResolver implements TurnRuntimeResolver {
    private final ConfigurationGenerationPort configurations;
    private final JaSkillSources skills;
    private final Path agentsSkillRoot;
    private final Path jaSkillRoot;
    private final Path jaHome;
    private final ShellCapability shellCapability;
    private final GenerationCatalog generationCatalog;
    private final GenerationTurnMcpSessionFactory mcpSessions;
    private final AgentPromptSessionFactory promptSessions;
    private final ManagedAttachmentReader attachments;

    /**
     * 注入窄端口和无状态适配器，Resolver 本身不读取配置文件或持有 secret。
     */
    public ConfigurationTurnRuntimeResolver(
            ConfigurationGenerationPort configurations,
            JaSkillSources skills,
            Path agentsSkillRoot,
            Path jaSkillRoot,
            ShellCapability shellCapability,
            GenerationCatalog generationCatalog,
            GenerationTurnMcpSessionFactory mcpSessions,
            AgentPromptSessionFactory promptSessions,
            ManagedAttachmentReader attachments) {
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.skills = Objects.requireNonNull(skills, "skills");
        this.agentsSkillRoot = Objects.requireNonNull(agentsSkillRoot, "agentsSkillRoot")
                .toAbsolutePath().normalize();
        this.jaSkillRoot = Objects.requireNonNull(jaSkillRoot, "jaSkillRoot")
                .toAbsolutePath().normalize();
        this.jaHome = Objects.requireNonNull(this.jaSkillRoot.getParent(), "jaHome");
        this.shellCapability = Objects.requireNonNull(shellCapability, "shellCapability");
        this.generationCatalog = Objects.requireNonNull(generationCatalog, "generationCatalog");
        this.mcpSessions = Objects.requireNonNull(mcpSessions, "mcpSessions");
        this.promptSessions = Objects.requireNonNull(promptSessions, "promptSessions");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
    }

    /**
     * 先取得唯一配置租约，再完成全部派生；任一校验或适配失败都会在返回前释放 secret，
     * 只有完整 RuntimeLease 才把释放权转移给 TurnService。
     */
    @Override
    @SuppressWarnings("PMD.CloseResource")
    public RuntimeLease resolve(TurnRuntimeRequest request) {
        Objects.requireNonNull(request, "request");
        ConfigurationGenerationPort.Lease lease = configurations.acquire(request.workspaceRoot());
        boolean transferred = false;
        try {
            ConfigurationGenerationSnapshot.Provider provider =
                    lease.snapshot().requireProvider(request.providerId());
            ConfigurationGenerationSnapshot.Model selectedModel =
                    lease.snapshot().requireModel(request.providerId(), request.modelId());
            validateReasoning(request.reasoningLevel(), selectedModel);
            TurnLimits limits = limits(request, provider, selectedModel);
            ModelPort.ModelConfiguration model = model(
                    provider, selectedModel, request.reasoningLevel(), lease, limits.maxOutputTokens());
            SkillCatalog.SkillSnapshot skillSnapshot = skillSnapshot(
                    request.workspaceRoot(), lease);
            ContextBudget contextBudget = contextBudget(
                    selectedModel.capabilities(), provider.agentDefaults().context());
            AgentPromptSession promptSession = promptSessions.open(new AgentPromptSessionFactory.SessionRequest(
                    request.threadId(), request.workspaceRoot(), jaHome, lease.snapshot().trusted(),
                    shellCapability.executionEnvironment(request.workspaceRoot()), contextBudget, skills, skillSnapshot));
            List<AgentTool> builtInTools = BuiltInTools.create(
                    request.workspaceRoot(), skills, skillSnapshot, shellCapability, promptSession,
                    attachments).snapshot();
            TurnToolSessionFactory toolSessions = toolSessions(
                    new TurnMcpSessionFactory.Context(provider.providerId(), selectedModel.modelId(),
                            request.workspaceRoot(),
                            request.requestedAt().plus(limits.wallTimeout())), lease);
            ToolProjectionLimits outputLimits = new ToolProjectionLimits(20_000, 20_000);
            RuntimeLease runtimeLease = new RuntimeLease(lease.generationId(), model,
                    accessMode(request.accessMode(), lease.snapshot().accessMode()), limits,
                    builtInTools, toolSessions, outputLimits, promptSession, attachments,
                    presentationSecrets(provider, lease.snapshot(), lease, model.apiKey()), lease);
            transferred = true;
            return runtimeLease;
        } finally {
            if (!transferred) {
                lease.close();
            }
        }
    }

    /**
     * 冻结当前 Turn 真正可触达的 Provider、MCP 配置与进程环境敏感值；排序后先替换长值，
     * 防止一个短 Secret 提前破坏另一个长 Secret 的完整匹配。
     */
    private static List<String> presentationSecrets(
            ConfigurationGenerationSnapshot.Provider provider,
            ConfigurationGenerationSnapshot snapshot,
            ConfigurationGenerationPort.Lease lease,
            String providerSecret) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        addSecret(values, providerSecret);
        for (ConfigurationGenerationSnapshot.McpServer server : snapshot.mcpDefinitions()) {
            if (!server.enabled()) continue;
            server.env().forEach((key, value) -> addSensitiveSetting(values, key, value));
            server.headers().forEach((key, value) -> addSensitiveSetting(values, key, value));
            if (server.auth().credentialId() != null) {
                addSecret(values, lease.secretFor(server.auth().credentialId()));
            }
        }
        System.getenv().forEach((key, value) -> addSensitiveSetting(values, key, value));
        return values.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    /** 只把敏感名称对应的配置或环境值加入集合，普通变量不会导致大范围误脱敏。 */
    private static void addSensitiveSetting(Set<String> values, String key, String value) {
        String normalized = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT)
                .replace("_", "").replace("-", "").replace(".", "");
        if (normalized.contains("token") || normalized.contains("password")
                || normalized.contains("passwd") || normalized.contains("apikey")
                || normalized.contains("authorization") || normalized.contains("cookie")
                || normalized.contains("secret")) {
            addSecret(values, value);
        }
    }

    /** 忽略空 Secret，避免空串替换在投影文本的每个字符边界产生无意义标记。 */
    private static void addSecret(Set<String> values, String value) {
        if (value != null && !value.isEmpty()) values.add(value);
    }

    /**
     * 预热仅持有一次短租约，并在 Schema 准备完成或失败后立即释放。
     */
    @Override
    public void prepareWorkspace(Path workspaceRoot) {
        Path root = Objects.requireNonNull(workspaceRoot, "workspaceRoot")
                .toAbsolutePath().normalize();
        try (ConfigurationGenerationPort.Lease lease = configurations.acquire(root)) {
            generationCatalog.prepareWorkspace(root, lease);
        }
    }

    /** 短时取得配置租约并返回 Owner 已成对验证的默认模型选择，不借出 snapshot 或 Secret。 */
    @Override
    public Optional<DefaultModelSelection> defaultModelSelection(Path workspaceRoot) {
        Path root = Objects.requireNonNull(workspaceRoot, "workspaceRoot")
                .toAbsolutePath().normalize();
        try (ConfigurationGenerationPort.Lease lease = configurations.acquire(root)) {
            ConfigurationGenerationSnapshot snapshot = lease.snapshot();
            if (snapshot.defaultProviderId().isEmpty() || snapshot.defaultModelId().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new DefaultModelSelection(snapshot.defaultProviderId().get(),
                    snapshot.defaultModelId().get(), snapshot.defaultReasoningLevel()
                            .map(value -> value.name().toLowerCase(java.util.Locale.ROOT)).orElse(null)));
        }
    }

    /**
     * 把 Provider 默认上限映射为领域预算，并以入站 Deadline 继续收紧。
     */
    private static TurnLimits limits(TurnRuntimeRequest request,
                                     ConfigurationGenerationSnapshot.Provider provider,
                                     ConfigurationGenerationSnapshot.Model model) {
        ConfigurationGenerationSnapshot.Capabilities capabilities = model.capabilities();
        int maxInputTokens = boundedTokenCount(
                capabilities.contextWindowTokens() - capabilities.maxOutputTokens(), 4_000_000);
        int maxOutputTokens = boundedTokenCount(capabilities.maxOutputTokens(), 1_000_000);
        ConfigurationGenerationSnapshot.TurnLimits configured = provider.agentDefaults().turnLimits();
        Duration wallTimeout = minimum(request.deadline(), configured.wallTimeout());
        return new TurnLimits(
                configured.maxModelRounds(), configured.maxToolCalls(),
                maxInputTokens, maxOutputTokens, wallTimeout);
    }

    /**
     * 显式穷举配置枚举并冻结模型输入模态，同时短时借用凭据；禁止通过名称或旧 Provider 别名回退。
     */
    private static ModelPort.ModelConfiguration model(
            ConfigurationGenerationSnapshot.Provider provider,
            ConfigurationGenerationSnapshot.Model selectedModel,
            String reasoningLevel,
            ConfigurationGenerationPort.Lease lease,
            int maxOutputTokens) {
        ModelPort.Provider modelProvider = switch (provider.provider()) {
            case OPENAI -> ModelPort.Provider.OPENAI;
            case ANTHROPIC -> ModelPort.Provider.ANTHROPIC;
        };
        ModelPort.Api api = switch (provider.api()) {
            case OPENAI_RESPONSES -> ModelPort.Api.OPENAI_RESPONSES;
            case ANTHROPIC_MESSAGES -> ModelPort.Api.ANTHROPIC_MESSAGES;
        };
        String apiKey = provider.credentialId() == null ? "" : lease.secretFor(provider.credentialId());
        if (provider.credentialId() != null && (apiKey == null || apiKey.isEmpty())) {
            throw new IllegalStateException("Provider credential is unavailable");
        }
        return new ModelPort.ModelConfiguration(provider.providerId(), selectedModel.modelId(),
                lease.generationId(), modelProvider, api, selectedModel.model(), provider.baseUrl(), apiKey,
                provider.networkTimeouts().connectTimeout(), provider.networkTimeouts().requestTimeout(),
                selectedModel.capabilities().inputModalities().stream()
                        .map(modality -> ModelPort.InputModality.valueOf(modality.name()))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                new ModelPort.GenerationOptions(null, null, maxOutputTokens,
                        upstreamReasoning(reasoningLevel, selectedModel)));
    }

    /**
     * 依据冻结 Provider 的 Skill ID 选择固定 revision，缺失或禁用条目直接拒绝 Turn。
     */
    private SkillCatalog.SkillSnapshot skillSnapshot(
            Path workspaceRoot, ConfigurationGenerationPort.Lease lease) {
        SkillCatalog.SnapshotRequest request = new SkillCatalog.SnapshotRequest(
                workspaceRoot, agentsSkillRoot, jaSkillRoot, lease.snapshot().trusted());
        List<ConfigurationGenerationSnapshot.Skill> enabled = lease.snapshot().skillDefinitions().stream()
                .filter(ConfigurationGenerationSnapshot.Skill::enabled).toList();
        if (enabled.isEmpty()) {
            // 未授权任何 Skill 时不扫描无关目录；严格格式错误只能阻断真正选择了 Skill 的 Turn。
            return skills.emptySnapshot();
        }
        Set<String> allowedNames = new HashSet<>();
        for (ConfigurationGenerationSnapshot.Skill skill : enabled) {
            allowedNames.add(skill.name());
        }
        SkillCatalog.SkillSnapshot complete = skills.snapshot(request);
        List<String> revisions = complete.skills().stream()
                .filter(descriptor -> allowedNames.contains(descriptor.name()))
                .map(SkillCatalog.SkillDescriptor::revision)
                .toList();
        if (revisions.size() != allowedNames.size()) {
            throw new IllegalStateException("Turn Skill is unavailable");
        }
        return skills.select(complete, revisions);
    }

    /**
     * 让 MCP 会话延迟到 AgentLoop 使用时打开，同时持续绑定当前配置租约。
     */
    private TurnToolSessionFactory toolSessions(TurnMcpSessionFactory.Context context,
                                                ConfigurationGenerationPort.Lease lease) {
        return cancellation -> {
            TurnMcpSessionFactory.Session opened = mcpSessions.open(context, lease, cancellation);
            try {
                List<AgentTool> adapted = McpAgentTool.adapt(opened.gateway(), opened.snapshot());
                return new TurnToolSessionFactory.Session() {
                    private boolean closed;

                    /** 返回冻结的 MCP Tool；会话关闭后拒绝复用失效连接。 */
                    @Override
                    public List<AgentTool> tools() {
                        if (closed) {
                            throw new IllegalStateException("Turn MCP session is closed");
                        }
                        return adapted;
                    }

                    /** 幂等关闭底层 MCP 会话，使正常、取消和异常路径可以竞争释放。 */
                    @Override
                    public void close() {
                        if (!closed) {
                            closed = true;
                            opened.close();
                        }
                    }
                };
            } catch (RuntimeException failure) {
                opened.close();
                throw failure;
            }
        };
    }

    /**
     * 用配置窗口生成无 Provider 实测值的首次预算，动态计量由 Turn 状态机后续覆盖。
     */
    private static ContextBudget contextBudget(
            ConfigurationGenerationSnapshot.Capabilities capabilities,
            ConfigurationGenerationSnapshot.Context context) {
        return ContextBudget.capabilities(capabilities.contextWindowTokens(),
                capabilities.maxOutputTokens(), context.autoCompact());
    }

    /**
     * 将 long 配置收敛到领域允许的正整数上限，拒绝固定预算已经耗尽的配置。
     */
    private static int boundedTokenCount(long value, int maximum) {
        if (value < 1) {
            throw new IllegalArgumentException("configured token budget is exhausted");
        }
        return (int) Math.min(value, maximum);
    }

    /**
     * 选择两个正 Deadline 中更严格者，Resolver 永远不能扩大客户端意图。
     */
    private static Duration minimum(Duration requested, Duration configured) {
        return requested.compareTo(configured) <= 0 ? requested : configured;
    }

    /** 请求只能继承或收紧根级权限，历史 Thread 不能在配置收紧后继续扩大执行范围。 */
    private static AccessMode accessMode(
            AccessMode requested, ConfigurationGenerationSnapshot.AccessMode configured) {
        return requested == AccessMode.APPROVAL_REQUIRED
                || configured == ConfigurationGenerationSnapshot.AccessMode.APPROVAL_REQUIRED
                ? AccessMode.APPROVAL_REQUIRED : AccessMode.FULL_ACCESS;
    }

    /** reasoning 必须属于所选模型显式能力集合；null 继续使用模型默认。 */
    private static void validateReasoning(
            String requested, ConfigurationGenerationSnapshot.Model model) {
        if (requested == null) return;
        ConfigurationGenerationSnapshot.ReasoningLevel effort =
                ConfigurationGenerationSnapshot.ReasoningLevel.valueOf(
                        requested.toUpperCase(java.util.Locale.ROOT));
        if (!model.reasoningLevelMap().containsKey(effort)) {
            throw new IllegalArgumentException("reasoning level is unsupported");
        }
    }

    /** 在 Turn admission 将逻辑档位冻结为模型声明的上游值；null 保持 Provider 默认。 */
    private static String upstreamReasoning(
            String requested, ConfigurationGenerationSnapshot.Model model) {
        if (requested == null) return null;
        ConfigurationGenerationSnapshot.ReasoningLevel level =
                ConfigurationGenerationSnapshot.ReasoningLevel.valueOf(
                        requested.toUpperCase(java.util.Locale.ROOT));
        String upstream = model.reasoningLevelMap().get(level);
        if (upstream == null) throw new IllegalArgumentException("reasoning level is unsupported");
        return upstream;
    }
}
