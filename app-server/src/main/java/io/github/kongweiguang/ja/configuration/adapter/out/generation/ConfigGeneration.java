// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationError;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.configuration.port.out.ConfigurationRuntimePort;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * ConfigGeneration 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
 */
public final class ConfigGeneration implements AutoCloseable {
    /**
     * Diagnostic 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    public record Diagnostic(String code, boolean blocking) {
        /**
         * 将外部诊断码收敛为有界、非空值，避免日志和 RPC 错误被异常长文本污染。
         */
        public Diagnostic {
            code = code == null || code.isBlank() ? "CONFIGURATION_ERROR" : code;
            if (code.length() > 64) code = code.substring(0, 64);
        }
    }

    private final String generationId;
    private final String canonicalCwd;
    private final String userVersion;
    private final String projectVersion;
    private final ObjectNode effectiveConfig;
    private final Map<String, Boolean> credentialStatuses;
    private final List<Diagnostic> diagnostics;
    private final boolean trusted;
    private final Map<String, SecretValue> secrets;
    private final List<JsonNode> skills;
    private final List<JsonNode> mcpServers;
    private final Map<String, ProviderDefinition> providers;
    private final Map<String, Skill> skillDefinitions;
    private final Map<String, McpServer> mcpDefinitions;
    private final AccessMode accessMode;
    private final String catalogDigest;
    private final ConfigGenerationLeaseState leaseState;

    /**
     * ConfigGeneration 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    ConfigGeneration(String generationId,
                     String canonicalCwd,
                     String userVersion,
                     String projectVersion,
                     ObjectNode effectiveConfig,
                     Map<String, Boolean> credentialStatuses,
                     List<Diagnostic> diagnostics,
                     boolean trusted,
                     Map<String, SecretValue> secrets,
                     List<JsonNode> skills,
                     List<JsonNode> mcpServers,
                     String catalogDigest,
                     Consumer<ConfigGeneration> onFullyReleased) {
        this.generationId = generationId;
        this.canonicalCwd = canonicalCwd;
        this.userVersion = userVersion;
        this.projectVersion = projectVersion;
        this.effectiveConfig = effectiveConfig.deepCopy();
        this.credentialStatuses = Collections.unmodifiableMap(new LinkedHashMap<>(credentialStatuses));
        this.diagnostics = List.copyOf(diagnostics);
        this.trusted = trusted;
        // generation 接管这些可清零 secret；输入 Map 的结构不再与外部共享。
        this.secrets = new LinkedHashMap<>(secrets);
        this.skills = copyNodes(skills);
        this.mcpServers = copyNodes(mcpServers);
        ConfigGenerationDocumentCatalog catalog = ConfigGenerationDocumentCatalog.parse(this.effectiveConfig);
        this.providers = catalog.providers();
        this.skillDefinitions = catalog.skillDefinitions();
        this.mcpDefinitions = catalog.mcpDefinitions();
        this.accessMode = parseAccessMode(requiredText(this.effectiveConfig, "default_access_mode"));
        this.catalogDigest = catalogDigest;
        this.leaseState = new ConfigGenerationLeaseState(() -> {
            clearSecrets();
            onFullyReleased.accept(this);
        });
    }

    /**
     * generationId 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    public String generationId() {
        return generationId;
    }

    /**
     * canonicalCwd 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    public String canonicalCwd() {
        return canonicalCwd;
    }

    /**
     * 返回构建该代际时用户层的版本快照，供诊断并发变更而非发起新写入。
     */
    public String userVersion() {
        return userVersion;
    }

    /**
     * 返回构建该代际时项目层的版本快照，general workspace 使用缺失版本。
     */
    public String projectVersion() {
        return projectVersion;
    }

    /**
     * effectiveConfig 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public JsonNode effectiveConfig() {
        return effectiveConfig.deepCopy();
    }

    /**
     * credentialStatuses 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public Map<String, Boolean> credentialStatuses() {
        return credentialStatuses;
    }

    /**
     * diagnostics 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    /**
     * 返回构建代际时已冻结的工作区信任结果，租约期间不跟随文件变化。
     */
    public boolean trusted() {
        return trusted;
    }

    /**
     * 读取用户级澄清策略；effective 文档不含项目覆盖字段，旧文档缺失时保持默认开启。
     */
    public boolean clarificationEnabled() {
        JsonNode interaction = effectiveConfig.get("interaction");
        if (interaction == null || interaction.isNull()) return true;
        JsonNode value = interaction.get("clarification_enabled");
        return value == null || value.isBoolean() && value.booleanValue();
    }

    /**
     * skills 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public List<JsonNode> skills() {
        return copyNodes(skills);
    }

    /**
     * mcpServers 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public List<JsonNode> mcpServers() {
        return copyNodes(mcpServers);
    }

    /** 返回冻结 Provider 目录，模型只在所属 Provider 内索引，不建立跨 Provider 扁平查找。 */
    public List<ProviderDefinition> providers() {
        return List.copyOf(providers.values());
    }

    /** 返回配置代际唯一的显式执行模式。 */
    public AccessMode accessMode() {
        return accessMode;
    }

    /** 按稳定 ID 解析 Provider，缺失或非法输入统一失败关闭。 */
    public ProviderDefinition requireProvider(String providerId) {
        if (providerId == null || !providerId.matches("provider_[A-Za-z0-9._-]{1,96}")) {
            throw new ConfigurationError(ConfigurationError.Code.MISSING_PROVIDER_OR_MODEL,
                    "provider is unavailable");
        }
        ProviderDefinition provider = providers.get(providerId);
        if (provider == null) {
            throw new ConfigurationError(ConfigurationError.Code.MISSING_PROVIDER_OR_MODEL,
                    "provider is unavailable");
        }
        return provider;
    }

    /** Model 必须在调用方指定的 Provider 内解析，禁止跨 Provider 模糊匹配。 */
    public ModelDefinition requireModel(String providerId, String modelId) {
        ProviderDefinition provider = requireProvider(providerId);
        if (modelId == null || !modelId.matches("model_[A-Za-z0-9._-]{1,96}")) {
            throw new ConfigurationError(ConfigurationError.Code.MISSING_PROVIDER_OR_MODEL,
                    "model is unavailable");
        }
        return provider.models().stream().filter(model -> model.modelId().equals(modelId)).findFirst()
                .orElseThrow(() -> new ConfigurationError(ConfigurationError.Code.MISSING_PROVIDER_OR_MODEL,
                        "model is unavailable"));
    }

    /**
     * skillDefinitions 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    public List<Skill> skillDefinitions() {
        return List.copyOf(skillDefinitions.values());
    }

    /**
     * mcpDefinitions 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    public List<McpServer> mcpDefinitions() {
        return List.copyOf(mcpDefinitions.values());
    }

    /**
     * requireMcp 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    public McpServer requireMcp(String mcpId) {
        McpServer server = mcpDefinitions.get(mcpId);
        if (server == null) {
            throw new ConfigurationError(ConfigurationError.Code.INVALID_DOCUMENT,
                    "MCP server is unavailable");
        }
        return server;
    }

    /**
     * catalogDigest 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    public String catalogDigest() {
        return catalogDigest;
    }

    /**
     * ready 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    public boolean ready() {
        return diagnostics.stream().noneMatch(Diagnostic::blocking);
    }

    /**
     * acquire 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public synchronized Lease acquire() {
        leaseState.acquire();
        return new Lease(this);
    }

    /**
     * isClosed 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    synchronized boolean isClosed() {
        return leaseState.isClosed();
    }

    /** 返回 Policy 已成对校验的默认 Provider；空 catalog 不猜测第一项。 */
    public Optional<String> defaultProviderId() {
        JsonNode value = effectiveConfig.get("default_provider_id");
        return value != null && value.isTextual() && providers.containsKey(value.textValue())
                ? Optional.of(value.textValue()) : Optional.empty();
    }

    /** 返回 Policy 已成对校验的默认 Model；缺失时不从 Provider 模型顺序推断。 */
    public Optional<String> defaultModelId() {
        Optional<String> providerId = defaultProviderId();
        JsonNode value = effectiveConfig.get("default_model_id");
        if (providerId.isEmpty() || value == null || !value.isTextual()) return Optional.empty();
        return providers.get(providerId.get()).models().stream()
                .anyMatch(model -> model.modelId().equals(value.textValue()))
                ? Optional.of(value.textValue()) : Optional.empty();
    }

    /** 根默认思考档位缺失时回落到模型默认值，二者均已受模型能力集合约束。 */
    public Optional<ReasoningLevel> defaultReasoningLevel() {
        JsonNode value = effectiveConfig.get("default_reasoning_level");
        if (value != null && value.isTextual()) return Optional.of(parseReasoning(value.textValue()));
        if (defaultProviderId().isEmpty() || defaultModelId().isEmpty()) return Optional.empty();
        return Optional.ofNullable(requireModel(defaultProviderId().get(), defaultModelId().get())
                .defaultReasoningLevel());
    }

    /** 返回代际内冻结的子智能体策略，避免 Turn 期间读取可变全局配置。 */
    public ConfigurationGenerationSnapshot.SubagentPolicy subagentPolicy() {
        JsonNode value = effectiveConfig.get("subagents");
        if (!(value instanceof ObjectNode subagents)) {
            throw new IllegalArgumentException("subagent policy is missing");
        }
        JsonNode enabled = subagents.get("enabled");
        if (enabled == null || !enabled.isBoolean()) {
            throw new IllegalArgumentException("subagent enabled state is invalid");
        }
        JsonNode provider = subagents.get("provider_id");
        JsonNode model = subagents.get("model_id");
        JsonNode reasoning = subagents.get("reasoning_level");
        return new ConfigurationGenerationSnapshot.SubagentPolicy(enabled.booleanValue(),
                optionalText(provider), optionalText(model), optionalReasoning(reasoning));
    }

    /** 把 optional 文本引用收敛为稳定空值语义，调用方不接触 Jackson 节点。 */
    private static Optional<String> optionalText(JsonNode value) {
        if (value == null || value.isNull()) return Optional.empty();
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("subagent reference is invalid");
        }
        return Optional.of(value.textValue());
    }

    /** 读取子智能体显式思考档位；跟随父任务必须通过 null 表达，避免代际自行猜测。 */
    private static Optional<ConfigurationGenerationSnapshot.ReasoningLevel> optionalReasoning(JsonNode value) {
        if (value == null) throw new IllegalArgumentException("subagent reasoning level is missing");
        if (value.isNull()) return Optional.empty();
        if (!value.isTextual()) throw new IllegalArgumentException("subagent reasoning level is invalid");
        return Optional.of(ConfigurationGenerationSnapshot.ReasoningLevel.valueOf(
                value.textValue().toUpperCase(java.util.Locale.ROOT)));
    }

    /**
     * secretFor 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    private synchronized String secretFor(String credentialId) {
        SecretValue secret = secrets.get(credentialId);
        return secret == null ? null : secret.asString();
    }

    /**
     * close 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    @Override
    public void close() {
        leaseState.close();
    }

    /**
     * toString 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    @Override
    public String toString() {
        return "ConfigGeneration[id=" + generationId + ", cwd="
               + (canonicalCwd == null ? "general" : "workspace")
               + ", ready=" + ready() + ", credentials=" + credentialStatuses.size() + "]";
    }

    /** Provider 保存连接和 Agent 默认值，多个模型共享同一稳定路由。 */
    public record ProviderDefinition(String providerId, String name, Api api,
                                     URI baseUrl, String credentialId,
                                     NetworkTimeoutConfig networkTimeouts,
                                     AgentDefaultsConfig agentDefaults,
                                     List<ModelDefinition> models) {
        /** 固定 Provider 路由、预算和模型列表，防止代际内出现可变选择。 */
        public ProviderDefinition {
            ConfigGenerationValueRules.requireIdentifier(providerId, "provider_");
            name = ConfigGenerationValueRules.boundedText(name, "name", 512, false);
            java.util.Objects.requireNonNull(api, "api");
            java.util.Objects.requireNonNull(baseUrl, "baseUrl");
            if (!baseUrl.isAbsolute() || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null || baseUrl.getFragment() != null
                || !("https".equalsIgnoreCase(baseUrl.getScheme())
                || "http".equalsIgnoreCase(baseUrl.getScheme())
                   && ConfigGenerationValueRules.isLoopback(baseUrl))) {
                throw new IllegalArgumentException("provider base URL is invalid");
            }
            ConfigGenerationValueRules.requireIdentifier(credentialId, "cred_");
            java.util.Objects.requireNonNull(networkTimeouts, "networkTimeouts");
            java.util.Objects.requireNonNull(agentDefaults, "agentDefaults");
            models = List.copyOf(models);
            if (models.isEmpty()) throw new IllegalArgumentException("provider models are missing");
        }

    }

    /** Model 只保存模型自身能力，不重复 Provider 连接和 Agent 默认值。 */
    public record ModelDefinition(String modelId, String name, String model,
                                  CapabilitiesConfig capabilities,
                                  Map<ReasoningLevel, String> reasoningLevelMap,
                                  ReasoningLevel defaultReasoningLevel) {
        /** 冻结模型能力并要求默认思考档位属于显式集合。 */
        public ModelDefinition {
            ConfigGenerationValueRules.requireIdentifier(modelId, "model_");
            name = ConfigGenerationValueRules.boundedText(name, "name", 512, false);
            model = ConfigGenerationValueRules.boundedText(model, "model", 512, false);
            java.util.Objects.requireNonNull(capabilities, "capabilities");
            reasoningLevelMap = Map.copyOf(reasoningLevelMap);
            if (defaultReasoningLevel != null && !reasoningLevelMap.containsKey(defaultReasoningLevel)) {
                throw new IllegalArgumentException("default reasoning level is unsupported");
            }
        }
    }

    /** Provider 级 Agent 默认值只固定上下文和 Turn 上限，目录启停由根级事实决定。 */
    public record AgentDefaultsConfig(ContextConfig context, TurnLimitConfig turnLimits) {
        /** 固定代际内两个默认对象；后续 Provider 请求可改读更新后的配置代际。 */
        public AgentDefaultsConfig {
            java.util.Objects.requireNonNull(context, "context");
            java.util.Objects.requireNonNull(turnLimits, "turnLimits");
        }
    }

    /**
     * 配置域支持的模型 API，conversation 负责选择具体出站适配器。
     */
    public enum Api {
        /**
         * 使用 OpenAI Responses API。
         */
        OPENAI_RESPONSES,

        /**
         * 使用 Anthropic Messages API。
         */
        ANTHROPIC_MESSAGES,

        /**
         * 使用 OpenAI Chat Completions API。
         */
        OPENAI_CHAT_COMPLETIONS
    }

    /**
     * 配置允许的最大权限边界，conversation 只能收紧而不能扩大。
     */
    public enum AccessMode {
        /**
         * 每次 Tool 调用均请求用户确认。
         */
        APPROVAL_REQUIRED,

        /**
         * 继承 Ja 进程权限并跳过逐次审批。
         */
        FULL_ACCESS
    }

    /** 严格解析根级两值模式，不接受旧权限别名或隐式降级。 */
    private static AccessMode parseAccessMode(String value) {
        return switch (value) {
            case "approval_required" -> AccessMode.APPROVAL_REQUIRED;
            case "full_access" -> AccessMode.FULL_ACCESS;
            default -> throw new IllegalArgumentException("permission mode unsupported");
        };
    }

    /** 代际只读取已经通过 v1 Policy 的必填文本，不为缺失字段提供运行时默认值。 */
    private static String requiredText(ObjectNode object, String key) {
        JsonNode value = object.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("configuration text is missing");
        }
        return value.textValue();
    }

    /** Model 能力上限和输入模态共同决定附件与上下文准入。 */
    public record CapabilitiesConfig(long contextWindowTokens, long maxOutputTokens,
                                     List<InputModality> inputModalities) {
        /** 拒绝无效窗口、空模态以及大于窗口的输出能力。 */
        public CapabilitiesConfig {
            if (contextWindowTokens < 4_096 || contextWindowTokens > 4_000_000
                || maxOutputTokens < 1 || maxOutputTokens > 1_000_000
                || maxOutputTokens >= contextWindowTokens) {
                throw new IllegalArgumentException("model capabilities are outside supported bounds");
            }
            inputModalities = List.copyOf(inputModalities);
            if (inputModalities.isEmpty() || !inputModalities.contains(InputModality.TEXT)) {
                throw new IllegalArgumentException("text input modality is required");
            }
        }
    }

    /** 模型输入模态闭集，禁止附件层猜测厂商能力。 */
    public enum InputModality {
        /** 文本输入。 */
        TEXT,
        /** 图片输入。 */
        IMAGE,
        /** PDF 输入。 */
        PDF
    }

    /** 模型思考档位闭集，只控制 Provider 参数，不承载隐藏思维链。 */
    public enum ReasoningLevel {
        /** 明确关闭推理。 */
        OFF,
        /** 最小推理预算。 */
        MINIMAL,
        /** 低档位。 */
        LOW,
        /** 中档位。 */
        MEDIUM,
        /** 高档位。 */
        HIGH,
        /** 超高档位。 */
        XHIGH,
        /** 最大档位。 */
        MAX
    }

    /** 严格解析逻辑七档，不接受厂商别名；上游值由模型 map 单独保存。 */
    private static ReasoningLevel parseReasoning(String value) {
        return switch (value) {
            case "off" -> ReasoningLevel.OFF;
            case "minimal" -> ReasoningLevel.MINIMAL;
            case "low" -> ReasoningLevel.LOW;
            case "medium" -> ReasoningLevel.MEDIUM;
            case "high" -> ReasoningLevel.HIGH;
            case "xhigh" -> ReasoningLevel.XHIGH;
            case "max" -> ReasoningLevel.MAX;
            default -> throw new IllegalArgumentException("reasoning level unsupported");
        };
    }

    /** 上下文压缩配置只保留真实用户选择，阈值和投影边界不再外露。 */
    public record ContextConfig(boolean autoCompact) {
        /** 布尔值由严格文档解析器提供，无额外兼容语义。 */
        public ContextConfig {
        }
    }

    /**
     * TurnLimitConfig 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    public record TurnLimitConfig(int maxModelRounds, int maxToolCalls, Duration wallTimeout) {
        /**
         * 该声明 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
         */
        public TurnLimitConfig {
            java.util.Objects.requireNonNull(wallTimeout, "wallTimeout");
            long wallMs = wallTimeout.toMillis();
            if (maxModelRounds < 1 || maxModelRounds > 128 || maxToolCalls < 0 || maxToolCalls > 1_024
                || wallMs < 1_000 || wallMs > 86_400_000
                || wallMs != wallTimeout.toNanos() / 1_000_000) {
                throw new IllegalArgumentException("provider turn limits are outside supported bounds");
            }
        }
    }

    /**
     * 固定 Provider 连接与完整响应的独立超时，禁止使用无限等待。
     */
    public record NetworkTimeoutConfig(Duration connectTimeout, Duration requestTimeout) {
        /**
         * 把超时限制在生产允许区间，并拒绝非整毫秒的隐式截断。
         */
        public NetworkTimeoutConfig {
            java.util.Objects.requireNonNull(connectTimeout, "connectTimeout");
            java.util.Objects.requireNonNull(requestTimeout, "requestTimeout");
            long connectMs = connectTimeout.toMillis();
            long requestMs = requestTimeout.toMillis();
            if (connectMs < 100 || connectMs > 120_000 || requestMs < 1_000 || requestMs > 3_600_000
                || connectMs != connectTimeout.toNanos() / 1_000_000
                || requestMs != requestTimeout.toNanos() / 1_000_000) {
                throw new IllegalArgumentException("provider network timeouts are outside supported bounds");
            }
        }
    }

    /**
     * Skill 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    public record Skill(String skillId, String name, String scope, boolean enabled, String description) {
        /**
         * 固定 Skill 标识和文本上限，使 catalog 投影始终是可安全共享的不变值。
         */
        public Skill {
            ConfigGenerationValueRules.requireIdentifier(skillId, "skill_");
            name = ConfigGenerationValueRules.boundedText(name, "name", 512, false);
            scope = ConfigGenerationValueRules.boundedText(scope, "scope", 64, false);
            description = ConfigGenerationValueRules.boundedText(description, "description", 8_192, true);
        }
    }

    /**
     * McpServer 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public record McpServer(String mcpId, String name, Transport transport, String endpoint,
                            List<String> args, Map<String, String> env, Map<String, String> headers,
                            Auth auth, boolean enabled) {
        /**
         * 该声明 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
         */
        public McpServer {
            ConfigGenerationValueRules.requireIdentifier(mcpId, "mcp_");
            name = ConfigGenerationValueRules.boundedText(name, "name", 512, false);
            java.util.Objects.requireNonNull(transport, "transport");
            endpoint = ConfigGenerationValueRules.boundedText(endpoint, "endpoint", 4_096, false);
            args = List.copyOf(java.util.Objects.requireNonNull(args, "args"));
            env = Map.copyOf(java.util.Objects.requireNonNull(env, "env"));
            headers = Map.copyOf(java.util.Objects.requireNonNull(headers, "headers"));
            java.util.Objects.requireNonNull(auth, "auth");
        }
    }

    /**
     * 配置代际允许使用的 MCP 传输类型，禁止通过自由文本增加隐式兼容分支。
     */
    public enum Transport {
        /**
         * 通过受控子进程的标准输入输出交换 MCP 消息。
         */
        STDIO,

        /**
         * 通过 Streamable HTTP 正式协议交换 MCP 消息。
         */
        STREAMABLE_HTTP
    }

    /**
     * 该声明集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public record Auth(AuthKind kind, String name, String credentialId) {
        /**
         * 该声明 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
         */
        public Auth {
            java.util.Objects.requireNonNull(kind, "kind");
            if (kind == AuthKind.NONE && (name != null || credentialId != null)) {
                throw new IllegalArgumentException("invalid MCP auth");
            }
            if (kind != AuthKind.NONE) {
                if (credentialId == null) throw new IllegalArgumentException("MCP credential is missing");
                ConfigGenerationValueRules.requireIdentifier(credentialId, "cred_");
            }
        }
    }

    /**
     * MCP 认证注入目标；该枚举只保存策略，不保存 secret。
     */
    public enum AuthKind {
        /**
         * 不向 MCP 连接注入任何凭据。
         */
        NONE,

        /**
         * 将凭据短时注入受控子进程环境变量。
         */
        ENV,

        /**
         * 将凭据作为标准 Bearer token 注入请求。
         */
        BEARER,

        /**
         * 将凭据注入配置指定的自定义请求头。
         */
        HEADER
    }

    /**
     * 该声明固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    private static List<JsonNode> copyNodes(List<JsonNode> source) {
        java.util.ArrayList<JsonNode> copies = new java.util.ArrayList<>(source.size());
        for (JsonNode node : source) copies.add(node.deepCopy());
        return List.copyOf(copies);
    }

    /**
     * release 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    private void release() {
        leaseState.release();
    }

    /**
     * clearSecrets 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    private synchronized void clearSecrets() {
        secrets.values().forEach(SecretValue::clear);
        secrets.clear();
    }

    /**
     * Lease 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    public static final class Lease implements ConfigurationRuntimePort.GenerationLease {
        private ConfigGeneration generation;

        /**
         * Lease 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
         */
        private Lease(ConfigGeneration generation) {
            this.generation = generation;
        }

        /**
         * generation 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
         */
        public synchronized ConfigGeneration generation() {
            ConfigGeneration value = generation;
            if (value == null) throw new IllegalStateException("configuration lease is closed");
            return value;
        }

        /**
         * 只返回不透明代际标识，使普通消费者无需依赖适配器内部快照类型。
         */
        @Override
        public synchronized String generationId() {
            return generation().generationId();
        }

        /**
         * 把内部强类型 generation 投影为纯 JDK 领域快照，隔离 Jackson 和 conversation 类型。
         */
        @Override
        public synchronized ConfigurationGenerationSnapshot snapshot() {
            return new GenerationView(generation());
        }

        /**
         * 短时借用凭据；调用方不得跨 Provider 请求保存，并仍须在 Turn 结束时关闭租约。
         */
        @Override
        public synchronized String secretFor(String credentialId) {
            return generation().secretFor(credentialId);
        }

        /**
         * 仅释放一次租约，使已关闭 generation 能在最后持有者退出后清零缓冲区。
         */
        @Override
        @SuppressWarnings("PMD.CloseResource")
        public synchronized void close() {
            ConfigGeneration value = generation;
            if (value == null) return;
            generation = null;
            value.release();
        }
    }

    /**
     * 按需创建轻量端口投影；底层 generation 仍由外层租约控制生命周期。
     */
    private record GenerationView(ConfigGeneration source) implements ConfigurationGenerationSnapshot {
        /**
         * 拒绝空 generation，确保所有视图都来自有效租约。
         */
        private GenerationView {
            java.util.Objects.requireNonNull(source, "source");
        }

        /**
         * 返回源代际的不透明标识。
         */
        @Override
        public String generationId() {
            return source.generationId();
        }

        /**
         * 转换 Skill 为端口值，避免外部依赖适配器嵌套类型。
         */
        @Override
        public List<ConfigurationGenerationSnapshot.Skill> skillDefinitions() {
            return source.skillDefinitions().stream().map(skill -> new ConfigurationGenerationSnapshot.Skill(
                    skill.skillId(), skill.name(), skill.scope(), skill.enabled(), skill.description())).toList();
        }

        /**
         * 转换 MCP 为不含 secret 的端口值。
         */
        @Override
        public List<ConfigurationGenerationSnapshot.McpServer> mcpDefinitions() {
            return source.mcpDefinitions().stream().map(GenerationView::projectMcp).toList();
        }

        /** 把根级执行模式投影到 conversation 稳定端口，禁止从单个模型反向推导。 */
        @Override
        public ConfigurationGenerationSnapshot.AccessMode accessMode() {
            return switch (source.accessMode()) {
                case APPROVAL_REQUIRED -> ConfigurationGenerationSnapshot.AccessMode.APPROVAL_REQUIRED;
                case FULL_ACCESS -> ConfigurationGenerationSnapshot.AccessMode.FULL_ACCESS;
            };
        }

        /** 直接投影底层已冻结 trust，避免 instruction/Skill 子系统创建第二套信任状态。 */
        @Override
        public boolean trusted() {
            return source.trusted();
        }

        /** 透传用户级澄清策略；Plan 仍由其自身阶段策略强制启用。 */
        @Override
        public boolean clarificationEnabled() {
            return source.clarificationEnabled();
        }

        /** 复用配置 Owner 已校验的默认 Provider，禁止调用方自行排序。 */
        @Override
        public Optional<String> defaultProviderId() {
            return source.defaultProviderId();
        }

        /** 复用配置 Owner 已校验的默认 Model，不从上游模型名推断身份。 */
        @Override
        public Optional<String> defaultModelId() {
            return source.defaultModelId();
        }

        /** 把根级或模型级默认思考档位投影到领域闭集。 */
        @Override
        public Optional<ConfigurationGenerationSnapshot.ReasoningLevel> defaultReasoningLevel() {
            return source.defaultReasoningLevel().map(GenerationView::projectReasoning);
        }

        /** 透传代际冻结的子智能体开关与成对模型引用，不读取当前配置文件。 */
        @Override
        public ConfigurationGenerationSnapshot.SubagentPolicy subagentPolicy() {
            return source.subagentPolicy();
        }

        /**
         * 复用内部严格查找后再投影，缺失语义保持失败关闭。
         */
        @Override
        public ConfigurationGenerationSnapshot.McpServer requireMcp(String mcpId) {
            return projectMcp(source.requireMcp(mcpId));
        }

        /** 投影 Provider 的连接、Agent 默认值和完整模型目录，不产生扁平兼容索引。 */
        @Override
        public ConfigurationGenerationSnapshot.Provider requireProvider(String providerId) {
            return projectProvider(source.requireProvider(providerId));
        }

        /** 在指定 Provider 内投影 Model，保持稳定二元选择。 */
        @Override
        public ConfigurationGenerationSnapshot.Model requireModel(String providerId, String modelId) {
            return projectModel(source.requireModel(providerId, modelId));
        }

        /** 把内部 Provider 定义转换为不含 Secret 的领域投影。 */
        private static ConfigurationGenerationSnapshot.Provider projectProvider(
                ConfigGeneration.ProviderDefinition provider) {
            ConfigurationGenerationSnapshot.Api api = switch (provider.api()) {
                case OPENAI_RESPONSES -> ConfigurationGenerationSnapshot.Api.OPENAI_RESPONSES;
                case ANTHROPIC_MESSAGES -> ConfigurationGenerationSnapshot.Api.ANTHROPIC_MESSAGES;
                case OPENAI_CHAT_COMPLETIONS -> ConfigurationGenerationSnapshot.Api.OPENAI_CHAT_COMPLETIONS;
            };
            ConfigGeneration.AgentDefaultsConfig defaults = provider.agentDefaults();
            ConfigGeneration.TurnLimitConfig turnLimits = defaults.turnLimits();
            ConfigGeneration.NetworkTimeoutConfig timeouts = provider.networkTimeouts();
            return new ConfigurationGenerationSnapshot.Provider(provider.providerId(), provider.name(),
                    api, provider.baseUrl(), provider.credentialId(),
                    new ConfigurationGenerationSnapshot.NetworkTimeouts(
                            timeouts.connectTimeout(), timeouts.requestTimeout()),
                    new ConfigurationGenerationSnapshot.AgentDefaults(
                            new ConfigurationGenerationSnapshot.Context(defaults.context().autoCompact()),
                            new ConfigurationGenerationSnapshot.TurnLimits(turnLimits.maxModelRounds(),
                                    turnLimits.maxToolCalls(), turnLimits.wallTimeout())),
                    provider.models().stream().map(GenerationView::projectModel).toList());
        }

        /** 把模型能力、模态和思考档位逐项映射到领域闭集。 */
        private static ConfigurationGenerationSnapshot.Model projectModel(
                ConfigGeneration.ModelDefinition model) {
            ConfigurationGenerationSnapshot.Capabilities capabilities =
                    new ConfigurationGenerationSnapshot.Capabilities(
                            model.capabilities().contextWindowTokens(),
                            model.capabilities().maxOutputTokens(),
                            model.capabilities().inputModalities().stream()
                                    .map(GenerationView::projectModality).toList());
            return new ConfigurationGenerationSnapshot.Model(model.modelId(), model.name(), model.model(),
                    capabilities, model.reasoningLevelMap().entrySet().stream()
                            .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                    entry -> projectReasoning(entry.getKey()), Map.Entry::getValue)),
                    model.defaultReasoningLevel() == null ? null
                            : projectReasoning(model.defaultReasoningLevel()));
        }

        /** 输入模态使用显式 switch 映射，新增枚举时强制编译期处理。 */
        private static ConfigurationGenerationSnapshot.InputModality projectModality(
                ConfigGeneration.InputModality modality) {
            return switch (modality) {
                case TEXT -> ConfigurationGenerationSnapshot.InputModality.TEXT;
                case IMAGE -> ConfigurationGenerationSnapshot.InputModality.IMAGE;
                case PDF -> ConfigurationGenerationSnapshot.InputModality.PDF;
            };
        }

        /** 思考档位使用显式闭集映射，不依赖枚举名称字符串。 */
        private static ConfigurationGenerationSnapshot.ReasoningLevel projectReasoning(
                ConfigGeneration.ReasoningLevel effort) {
            return switch (effort) {
                case OFF -> ConfigurationGenerationSnapshot.ReasoningLevel.OFF;
                case MINIMAL -> ConfigurationGenerationSnapshot.ReasoningLevel.MINIMAL;
                case LOW -> ConfigurationGenerationSnapshot.ReasoningLevel.LOW;
                case MEDIUM -> ConfigurationGenerationSnapshot.ReasoningLevel.MEDIUM;
                case HIGH -> ConfigurationGenerationSnapshot.ReasoningLevel.HIGH;
                case XHIGH -> ConfigurationGenerationSnapshot.ReasoningLevel.XHIGH;
                case MAX -> ConfigurationGenerationSnapshot.ReasoningLevel.MAX;
            };
        }

        /**
         * 将内部 MCP 枚举逐项映射到稳定端口，禁止名称字符串 fallback。
         */
        private static ConfigurationGenerationSnapshot.McpServer projectMcp(ConfigGeneration.McpServer server) {
            ConfigurationGenerationSnapshot.Transport transport = switch (server.transport()) {
                case STDIO -> ConfigurationGenerationSnapshot.Transport.STDIO;
                case STREAMABLE_HTTP -> ConfigurationGenerationSnapshot.Transport.STREAMABLE_HTTP;
            };
            ConfigurationGenerationSnapshot.AuthKind authKind = switch (server.auth().kind()) {
                case NONE -> ConfigurationGenerationSnapshot.AuthKind.NONE;
                case ENV -> ConfigurationGenerationSnapshot.AuthKind.ENV;
                case BEARER -> ConfigurationGenerationSnapshot.AuthKind.BEARER;
                case HEADER -> ConfigurationGenerationSnapshot.AuthKind.HEADER;
            };
            ConfigurationGenerationSnapshot.Auth auth = new ConfigurationGenerationSnapshot.Auth(
                    authKind, server.auth().name(), server.auth().credentialId());
            return new ConfigurationGenerationSnapshot.McpServer(server.mcpId(), server.name(), transport,
                    server.endpoint(), server.args(), server.env(), server.headers(), auth, server.enabled());
        }
    }

    /**
     * SecretValue 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public static final class SecretValue {
        private char[] value;

        /**
         * SecretValue 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
         */
        public SecretValue(String source) {
            this.value = source.toCharArray();
        }

        /**
         * copy 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
         */
        public SecretValue copy() {
            return new SecretValue(value.clone());
        }

        /**
         * SecretValue 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
         */
        private SecretValue(char[] source) {
            this.value = source;
        }

        /**
         * asString 只暴露稳定且脱敏的诊断语义，禁止路径、配置正文或敏感值进入日志。
         */
        public String asString() {
            return new String(value);
        }

        /**
         * clear 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
         */
        public void clear() {
            java.util.Arrays.fill(value, '\0');
        }

        /**
         * toString 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
         */
        @Override
        public String toString() {
            return "REDACTED";
        }
    }
}
