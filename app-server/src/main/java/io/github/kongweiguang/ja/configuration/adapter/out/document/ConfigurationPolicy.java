// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationError;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationScope;
import io.github.kongweiguang.ja.configuration.domain.SkillReference;

import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 配置 v2 的唯一严格策略，集中维护 Provider/Model 结构、项目层收紧规则与 Secret 边界。 */
public final class ConfigurationPolicy {
    private static final int MAX_CREDENTIAL_ID_LENGTH = 100;
    private static final Pattern CREDENTIAL_ID_PATTERN =
            Pattern.compile("cred_[A-Za-z0-9][A-Za-z0-9._-]{0,95}");
    private static final Set<String> USER_ROOT_KEYS = Set.of(
            "schema_version", "config_revision", "default_access_mode", "default_provider_id",
            "default_model_id", "default_reasoning_level", "interaction", "subagents",
            "providers", "mcp_servers", "skills");
    private static final Set<String> PROJECT_ROOT_KEYS = Set.of(
            "schema_version", "config_revision", "skills", "disabled_skills");
    private static final Set<String> USER_REQUIRED_ROOT_KEYS = Set.of(
            "schema_version", "config_revision", "default_access_mode", "default_provider_id",
            "default_model_id", "default_reasoning_level", "subagents", "providers", "mcp_servers", "skills");
    private static final Set<String> SUBAGENT_KEYS = Set.of("enabled", "provider_id", "model_id",
            "reasoning_level");
    private static final Set<String> INTERACTION_KEYS = Set.of("clarification_enabled");
    private static final Set<String> PROVIDER_KEYS = Set.of(
            "provider_id", "name", "api", "base_url", "credential_id",
            "network_timeouts", "agent_defaults", "models");
    private static final Set<String> MODEL_KEYS = Set.of(
            "model_id", "name", "model", "capabilities", "reasoning_level_map",
            "default_reasoning_level");
    private static final Set<String> CAPABILITY_KEYS = Set.of(
            "context_window_tokens", "max_output_tokens");
    private static final Set<String> AGENT_DEFAULT_KEYS = Set.of(
            "context", "turn_limits");
    private static final Set<String> CONTEXT_KEYS = Set.of("auto_compact");
    private static final Set<String> TURN_LIMIT_KEYS = Set.of(
            "max_model_rounds", "max_tool_calls", "wall_timeout_ms");
    private static final Set<String> NETWORK_TIMEOUT_KEYS = Set.of(
            "connect_timeout_ms", "request_timeout_ms");
    private static final Set<String> MCP_KEYS = Set.of(
            "mcp_id", "name", "transport", "endpoint", "args", "env", "headers", "auth", "enabled");
    private static final Set<String> REASONING_LEVELS = Set.of(
            "off", "minimal", "low", "medium", "high", "xhigh", "max");
    private static final Set<String> SECRET_KEY_PARTS = Set.of(
            "secret", "token", "password", "apikey", "api_key", "authorization", "private_key");
    private static final Set<String> NON_SECRET_BUDGET_KEYS = Set.of(
            "context_window_tokens", "max_output_tokens");
    private static final int MAX_ARRAY_ITEMS = 512;
    private static final int MAX_CREDENTIALS = 512;

    /** 规则集合无状态且只允许静态调用，避免出现第二份配置生命周期。 */
    private ConfigurationPolicy() {
    }

    /** 按用户层完整文档规则校验 v1，供没有作用域概念的代际构造路径复用。 */
    public static void validateDocument(ObjectNode document) {
        validateDocument(document, ConfigurationScope.USER);
    }

    /**
     * 根据持久化作用域校验当前 v2 文档。项目层有意只保存 Skill 引用，防止项目文件成为
     * Provider、MCP 或执行策略的第二个 owner，且使首次项目写入可保持最小化。
     */
    public static void validateDocument(ObjectNode document, ConfigurationScope scope) {
        if (document == null || scope == null) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration document is invalid");
        }
        requireCurrentSchema(document);
        if (scope == ConfigurationScope.PROJECT) {
            rejectUnknown(document, PROJECT_ROOT_KEYS);
            requireKeys(document, Set.of("schema_version", "config_revision", "skills"));
            validateRevision(document.get("config_revision"));
            validateSkillReferences(document.get("skills"), scope, false);
            validateSkillReferences(document.get("disabled_skills"), scope, true);
            scanForLiteralSecrets(document);
            return;
        }
        rejectUnknown(document, USER_ROOT_KEYS);
        requireKeys(document, USER_REQUIRED_ROOT_KEYS);
        validateClarification(document, scope);
        validateRevision(document.get("config_revision"));
        validateAccessMode(document.get("default_access_mode"));
        validateArray(document.get("providers"), PROVIDER_KEYS, "provider_id");
        validateArray(document.get("mcp_servers"), MCP_KEYS, "mcp_id");
        validateSkillReferences(document.get("skills"), scope, false);
        validateSkillReferences(document.get("disabled_skills"), scope, true);
        validateUserProviders(document);
        validateUniqueProviderCredentials(document.get("providers"));
        validateUserMcpServers(document.get("mcp_servers"));
        validateCatalogEnabled(document.get("mcp_servers"), scope);
        validateDefaultSelection(document, scope == ConfigurationScope.USER);
        validateSubagents(document, scope);
        scanForLiteralSecrets(document);
    }

    /**
     * 澄清开关只属于用户级 interaction 对象；项目层拒绝整个对象，避免项目配置静默改变
     * 用户对话行为。缺失用户对象按默认开启处理，读取不会把旧根级别名重新带回文档。
     */
    private static void validateClarification(ObjectNode document, ConfigurationScope scope) {
        JsonNode interaction = document.get("interaction");
        if (scope == ConfigurationScope.PROJECT && interaction != null) {
            throw error(ConfigurationError.Code.LIMIT_ESCALATION,
                    "project clarification policy is not allowed");
        }
        if (interaction == null) return;
        if (!(interaction instanceof ObjectNode object)) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "interaction policy is invalid");
        }
        rejectUnknown(object, INTERACTION_KEYS);
        JsonNode value = object.get("clarification_enabled");
        if (value != null && !value.isBoolean()) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT,
                    "clarification enabled state is invalid");
        }
    }

    /** 只接受当前 schema v2；其它版本一律失败关闭，不执行迁移或兼容读取。 */
    private static void requireCurrentSchema(ObjectNode document) {
        JsonNode schema = document.get("schema_version");
        if (schema == null || !schema.isIntegralNumber()
            || schema.longValue() != ConfigurationDocumentFactory.CURRENT_SCHEMA_VERSION) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration schema is unsupported");
        }
    }

    /** revision 是 v2 文档的必填可读顺序，禁止通过缺失字段进入旧的隐式初始状态。 */
    private static void validateRevision(JsonNode revision) {
        if (revision == null || !revision.isIntegralNumber() || revision.longValue() < 0) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration revision is invalid");
        }
    }

    /** 根级默认权限只有审批与完整访问两种，不接受旧字段或隐式别名。 */
    private static void validateAccessMode(JsonNode value) {
        if (value != null && (!value.isTextual() || accessRank(value.textValue()) > 1)) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "default access mode is invalid");
        }
    }

    /** 用户层 Provider 必须完整保存连接、Agent 默认值和至少一个模型。 */
    private static void validateUserProviders(ObjectNode document) {
        JsonNode value = document.get("providers");
        if (!(value instanceof ArrayNode providers)) return;
        for (JsonNode entry : providers) {
            ObjectNode provider = (ObjectNode) entry;
            requireKeys(provider, PROVIDER_KEYS);
            requireText(provider, "name", 512, false);
            validateProviderRoute(provider, true);
            validateNetworkTimeouts(requireObject(provider, "network_timeouts"), true);
            validateAgentDefaults(requireObject(provider, "agent_defaults"), true);
            validateArray(provider.get("models"), MODEL_KEYS, "model_id");
            if (!(provider.get("models") instanceof ArrayNode models) || models.isEmpty()) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "provider models are missing");
            }
            for (JsonNode model : models) validateModel((ObjectNode) model, true);
        }
    }

    /** 每个 Provider 独占一个凭据引用，防止两个供应商通过同一 credential ID 隐式共享 API Key。 */
    private static void validateUniqueProviderCredentials(JsonNode value) {
        if (!(value instanceof ArrayNode providers)) return;
        Set<String> credentials = new HashSet<>();
        for (JsonNode entry : providers) {
            String credentialId = requireText(
                    (ObjectNode) entry, "credential_id", MAX_CREDENTIAL_ID_LENGTH, false);
            if (!credentials.add(credentialId)) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT,
                        "provider credential reference is duplicated");
            }
        }
    }

    /** Provider 路由只由显式 API 规范和地址决定；自定义名称不参与协议或鉴权推断。 */
    private static void validateProviderRoute(ObjectNode provider, boolean required) {
        String api = optionalText(provider, "api", required);
        String baseUrl = optionalText(provider, "base_url", required);
        if (api != null && !("openai_responses".equals(api) || "anthropic_messages".equals(api)
            || "openai_chat_completions".equals(api))) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "provider API is invalid");
        }
        if (baseUrl != null) validateBaseUrl(baseUrl);
        String credential = optionalText(provider, "credential_id", required);
        if (credential != null) validateCredentialId(credential);
    }

    /** 连接地址允许用户指定任意 HTTP(S) 主机，但拒绝 URL 内嵌凭据、查询和片段。 */
    private static void validateBaseUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!uri.isAbsolute() || host == null || host.isBlank() || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equalsIgnoreCase(uri.getScheme())
                || "http".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("invalid base URL");
            }
        } catch (RuntimeException failure) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "provider base URL is invalid");
        }
    }

    /** Provider 网络预算必须是完整正整数；项目 overlay 可只覆盖要收紧的字段。 */
    private static void validateNetworkTimeouts(ObjectNode network, boolean complete) {
        rejectUnknown(network, NETWORK_TIMEOUT_KEYS);
        requirePositiveIntegers(network, NETWORK_TIMEOUT_KEYS, complete);
    }

    /** Agent 默认值只固定上下文和 Turn 上限；Skills/MCP 启用事实归根级目录所有。 */
    private static void validateAgentDefaults(ObjectNode defaults, boolean complete) {
        rejectUnknown(defaults, AGENT_DEFAULT_KEYS);
        if (complete) requireKeys(defaults, AGENT_DEFAULT_KEYS);
        if (defaults.get("context") instanceof ObjectNode context) {
            rejectUnknown(context, CONTEXT_KEYS);
            if (complete) requireKeys(context, CONTEXT_KEYS);
            if (context.has("auto_compact") && !context.path("auto_compact").isBoolean()) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "provider context is invalid");
            }
        } else if (complete) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "provider context is missing");
        }
        if (defaults.get("turn_limits") instanceof ObjectNode limits) {
            rejectUnknown(limits, TURN_LIMIT_KEYS);
            requirePositiveIntegers(limits, TURN_LIMIT_KEYS, complete);
        } else if (complete) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "provider turn limits are missing");
        }
    }

    /** Model 完整文档声明能力，项目 overlay 只允许提交需要收紧的字段。 */
    private static void validateModel(ObjectNode model, boolean complete) {
        if (complete) {
            requireKeys(model, MODEL_KEYS);
            requireText(model, "name", 512, false);
            requireText(model, "model", 512, false);
        } else {
            optionalText(model, "name", false);
            optionalText(model, "model", false);
        }
        if (model.get("capabilities") instanceof ObjectNode capabilities) {
            rejectUnknown(capabilities, CAPABILITY_KEYS);
            if (complete) requireKeys(capabilities, CAPABILITY_KEYS);
            requirePositiveIntegers(capabilities,
                    Set.of("context_window_tokens", "max_output_tokens"), complete);
            if (capabilities.has("context_window_tokens") && capabilities.has("max_output_tokens")
                && capabilities.path("max_output_tokens").longValue()
                   >= capabilities.path("context_window_tokens").longValue()) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "model capabilities are invalid");
            }
        } else if (complete) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "model capabilities are missing");
        }
        JsonNode levels = model.get("reasoning_level_map");
        validateReasoningMap(levels, complete);
        validateReasoningDefault(model.get("default_reasoning_level"), levels);
    }

    /** 用户 MCP 定义必须完整匹配当前 v1 结构，generation 不再为缺失字段填默认值。 */
    private static void validateUserMcpServers(JsonNode value) {
        if (!(value instanceof ArrayNode servers)) return;
        for (JsonNode entry : servers) {
            ObjectNode server = (ObjectNode) entry;
            requireKeys(server, MCP_KEYS);
            requireText(server, "name", 512, false);
            String transport = requireText(server, "transport", 64, false);
            if (!("stdio".equals(transport) || "streamable_http".equals(transport))) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "MCP transport is invalid");
            }
            requireText(server, "endpoint", 4_096, false);
            validateStringArray(server.get("args"), 128, 4_096);
            validateStringMap(server.get("env"), 8_192);
            validateStringMap(server.get("headers"), 8_192);
            validateMcpAuth(requireObject(server, "auth"));
            requireBoolean(server, "enabled");
        }
    }

    /**
     * 校验只携带来源与名称的 Skill 授权，拒绝旧对象数组以避免描述、路径与开关副本继续进入配置。
     * 项目层只可登记本项目 Skill，或以 `disabled_skills` 收紧已经存在的全局引用。
     */
    private static void validateSkillReferences(
            JsonNode value, ConfigurationScope scope, boolean disabled) {
        if (value == null) {
            if (scope == ConfigurationScope.USER && !disabled) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "skill references are missing");
            }
            return;
        }
        if (!(value instanceof ArrayNode references) || references.size() > MAX_ARRAY_ITEMS) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "skill references are invalid");
        }
        if (scope == ConfigurationScope.USER && disabled) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "user disabled skills are unsupported");
        }
        Set<String> unique = new HashSet<>();
        for (JsonNode valueNode : references) {
            if (!valueNode.isTextual()) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "skill reference is invalid");
            }
            SkillReference reference;
            try {
                reference = SkillReference.parse(valueNode.textValue());
            } catch (IllegalArgumentException invalid) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "skill reference is invalid");
            }
            boolean allowed = scope == ConfigurationScope.USER
                    ? reference.source() == SkillReference.Source.USER || reference.source() == SkillReference.Source.JA
                    : disabled
                            ? reference.source() == SkillReference.Source.USER
                                    || reference.source() == SkillReference.Source.JA
                            : reference.source() == SkillReference.Source.PROJECT;
            if (!allowed || !unique.add(reference.identifier())) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "skill reference is invalid");
            }
        }
    }

    /** MCP auth 使用四个精确判别形状，拒绝把缺失字段解释成 none。 */
    private static void validateMcpAuth(ObjectNode auth) {
        String kind = requireText(auth, "kind", 32, false);
        Set<String> keys = switch (kind) {
            case "none" -> Set.of("kind");
            case "bearer" -> Set.of("kind", "credential_id");
            case "env", "header" -> Set.of("kind", "name", "credential_id");
            default -> throw error(ConfigurationError.Code.INVALID_DOCUMENT, "MCP auth is invalid");
        };
        rejectUnknown(auth, keys);
        requireKeys(auth, keys);
        if (!"none".equals(kind)) {
            validateCredentialId(requireText(
                    auth, "credential_id", MAX_CREDENTIAL_ID_LENGTH, false));
        }
        if ("env".equals(kind) || "header".equals(kind)) {
            requireText(auth, "name", 128, false);
        }
    }

    /** 当前数组字段必须显式存在并只包含有界文本，不接受缺失即空数组的读取兜底。 */
    private static void validateStringArray(JsonNode value, int maxItems, int maxLength) {
        if (!(value instanceof ArrayNode array) || array.size() > maxItems) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration string array is invalid");
        }
        for (JsonNode item : array) {
            if (!item.isTextual() || item.textValue().length() > maxLength) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration string array is invalid");
            }
        }
    }

    /** 当前 Map 字段必须显式存在且值为有界文本，generation 不再把其它类型转为空 Map。 */
    private static void validateStringMap(JsonNode value, int maxValueLength) {
        if (!(value instanceof ObjectNode map) || map.size() > 64) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration map is invalid");
        }
        map.properties().forEach(entry -> {
            if (entry.getKey().isEmpty() || entry.getKey().length() > 128
                || !entry.getValue().isTextual()
                || entry.getValue().textValue().length() > maxValueLength) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration map is invalid");
            }
        });
    }

    /** 必填布尔值必须真实存在，禁止使用 JsonNode 的 false 默认值掩盖缺失字段。 */
    private static boolean requireBoolean(ObjectNode object, String key) {
        JsonNode value = object.get(key);
        if (value == null || !value.isBoolean()) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration boolean is invalid");
        }
        return value.booleanValue();
    }

    /** 模型默认思考档位只能为空或属于该模型显式能力集合。 */
    private static void validateReasoningDefault(JsonNode value, JsonNode efforts) {
        if (value == null || value.isNull()) return;
        if (!value.isTextual() || !(efforts instanceof ObjectNode map)
            || !map.has(value.textValue())) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "default reasoning level is invalid");
        }
    }

    /** 根默认 Provider/Model 必须成对存在、真实可解析，默认思考档位必须属于选中模型。 */
    private static void validateDefaultSelection(ObjectNode document, boolean requireForCatalog) {
        JsonNode providerValue = document.get("default_provider_id");
        JsonNode modelValue = document.get("default_model_id");
        boolean hasProvider = providerValue != null && !providerValue.isNull();
        boolean hasModel = modelValue != null && !modelValue.isNull();
        if (hasProvider != hasModel || hasProvider && (!providerValue.isTextual() || !modelValue.isTextual())) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "default provider and model are invalid");
        }
        JsonNode providersValue = document.get("providers");
        boolean catalogPresent = providersValue instanceof ArrayNode providers && !providers.isEmpty();
        if (requireForCatalog && catalogPresent && !hasProvider) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "default provider and model are required");
        }
        ObjectNode selectedModel = hasProvider
                ? findModel(document, providerValue.textValue(), modelValue.textValue()) : null;
        if (requireForCatalog && hasProvider && selectedModel == null) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "default provider or model is unavailable");
        }
        JsonNode rootEffort = document.get("default_reasoning_level");
        if (rootEffort == null || rootEffort.isNull()) return;
        if (!requireForCatalog && rootEffort.isTextual()
            && REASONING_LEVELS.contains(rootEffort.textValue())) return;
        if (!rootEffort.isTextual() || selectedModel == null
            || !(selectedModel.get("reasoning_level_map") instanceof ObjectNode levels)
            || !levels.has(rootEffort.textValue())) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "default reasoning level is invalid");
        }
    }

    /** 子智能体策略只属于用户层全局配置，引用必须成对且解析到当前 Provider/Model catalog。 */
    private static void validateSubagents(ObjectNode document, ConfigurationScope scope) {
        JsonNode value = document.get("subagents");
        if (scope != ConfigurationScope.USER || !(value instanceof ObjectNode subagents)) {
            if (scope == ConfigurationScope.USER) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "subagent policy is missing");
            }
            if (value != null) {
                throw error(ConfigurationError.Code.LIMIT_ESCALATION,
                        "project subagent policy is not allowed");
            }
            return;
        }
        rejectUnknown(subagents, SUBAGENT_KEYS);
        requireKeys(subagents, SUBAGENT_KEYS);
        requireBoolean(subagents, "enabled");
        JsonNode provider = subagents.get("provider_id");
        JsonNode model = subagents.get("model_id");
        JsonNode reasoning = subagents.get("reasoning_level");
        boolean hasProvider = provider != null && !provider.isNull();
        boolean hasModel = model != null && !model.isNull();
        if (hasProvider != hasModel || hasProvider && (!provider.isTextual() || !model.isTextual())) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT,
                    "subagent provider and model are invalid");
        }
        if (hasProvider && findModel(document, provider.textValue(), model.textValue()) == null) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "subagent provider or model is unavailable");
        }
        if (reasoning != null && !reasoning.isNull()) {
            if (!reasoning.isTextual() || !REASONING_LEVELS.contains(reasoning.textValue())) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "subagent reasoning level is invalid");
            }
            if (!hasProvider) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT,
                        "follow-parent subagent policy cannot set reasoning level");
            }
            ObjectNode selected = findModel(document, provider.textValue(), model.textValue());
            JsonNode map = selected == null ? null : selected.get("reasoning_level_map");
            if (!(map instanceof ObjectNode levels) || !levels.has(reasoning.textValue())) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT,
                        "subagent reasoning level is unsupported by model");
            }
        }
    }

    /** 在嵌套 catalog 中按两个稳定 ID 定位模型，不使用显示名或上游模型名推断。 */
    private static ObjectNode findModel(ObjectNode document, String providerId, String modelId) {
        JsonNode providers = document.get("providers");
        if (!(providers instanceof ArrayNode array)) return null;
        for (JsonNode provider : array) {
            if (!providerId.equals(provider.path("provider_id").asText(null))) continue;
            JsonNode models = provider.get("models");
            if (!(models instanceof ArrayNode modelArray)) return null;
            for (JsonNode model : modelArray) {
                if (modelId.equals(model.path("model_id").asText(null))) return (ObjectNode) model;
            }
        }
        return null;
    }

    /**
     * 根级 enabled 是唯一启用事实；用户文档必须显式给出布尔值，项目 overlay 只允许写 false，
     * true 由缺失覆盖表示继承，避免项目层以冗余真值制造扩权歧义。
     */
    private static void validateCatalogEnabled(JsonNode values, ConfigurationScope scope) {
        if (!(values instanceof ArrayNode array)) return;
        for (JsonNode value : array) {
            ObjectNode entry = (ObjectNode) value;
            JsonNode enabled = value.get("enabled");
            if (scope == ConfigurationScope.USER) {
                if (enabled == null || !enabled.isBoolean()) {
                    throw error(ConfigurationError.Code.INVALID_DOCUMENT, "catalog enabled state is invalid");
                }
            } else {
                Set<String> fields = new HashSet<>();
                entry.fieldNames().forEachRemaining(fields::add);
                boolean sparseShape = fields.equals(Set.of("mcp_id", "enabled"))
                                      || fields.equals(Set.of("skill_id", "enabled"));
                if (!sparseShape || enabled == null || !enabled.isBoolean() || enabled.booleanValue()) {
                    throw error(ConfigurationError.Code.INVALID_DOCUMENT,
                            "project catalog may only disable entries");
                }
            }
        }
    }

    /** reasoning map 使用逻辑七档键和有界上游文本值；缺失键就是不支持。 */
    private static void validateReasoningMap(JsonNode value, boolean required) {
        if (value == null) {
            if (required) throw error(ConfigurationError.Code.INVALID_DOCUMENT, "reasoning level map is missing");
            return;
        }
        if (!(value instanceof ObjectNode map) || map.size() > REASONING_LEVELS.size()) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "reasoning level map is invalid");
        }
        map.properties().forEach(entry -> {
            JsonNode upstream = entry.getValue();
            if (!REASONING_LEVELS.contains(entry.getKey()) || !upstream.isTextual()
                || upstream.textValue().isBlank() || upstream.textValue().length() > 128) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "reasoning level map is invalid");
            }
        });
    }

    /** 约束对象数组的数量、字段闭集和稳定 ID，禁止缺失或重复身份。 */
    private static void validateArray(JsonNode value, Set<String> allowed, String idKey) {
        if (value == null) return;
        if (!(value instanceof ArrayNode array) || array.size() > MAX_ARRAY_ITEMS) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration collection is invalid");
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode entry : array) {
            if (!(entry instanceof ObjectNode object)) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration collection is invalid");
            }
            rejectUnknown(object, allowed);
            String id = requireText(object, idKey);
            if (!ids.add(id) || !validTypedId(id, idKey)) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration identity is invalid");
            }
        }
    }

    /** 四类配置 ID 使用明确类型前缀，防止跨层误引用同一个自由文本。 */
    private static boolean validTypedId(String id, String key) {
        String prefix = switch (key) {
            case "provider_id" -> "provider_";
            case "model_id" -> "model_";
            case "mcp_id" -> "mcp_";
            case "skill_id" -> "skill_";
            default -> throw new IllegalArgumentException("configuration identity key is unsupported");
        };
        return id.matches(prefix + "[A-Za-z0-9][A-Za-z0-9._-]{0,95}");
    }

    /** 要求对象字段为有界非空文本，避免空显示名和不受控持久化值。 */
    private static String requireText(ObjectNode object, String key) {
        return optionalText(object, key, true);
    }

    /** 按字段合同校验必填文本长度；仅 description 允许空字符串。 */
    private static String requireText(ObjectNode object, String key, int maxLength, boolean allowBlank) {
        JsonNode value = object.get(key);
        if (value == null || !value.isTextual() || value.textValue().length() > maxLength
            || (!allowBlank && value.textValue().isBlank())) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration text is invalid");
        }
        return value.textValue();
    }

    /** 读取可选文本；出现 null 或错误类型时始终失败，不把 null 当成空字符串。 */
    private static String optionalText(ObjectNode object, String key, boolean required) {
        JsonNode value = object.get(key);
        if (value == null) {
            if (required) throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration text is missing");
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 4_096) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration text is invalid");
        }
        return value.textValue();
    }

    /** 要求嵌套字段为对象，防止缺失对象被后续 path() 默认值掩盖。 */
    private static ObjectNode requireObject(ObjectNode object, String key) {
        if (object.get(key) instanceof ObjectNode value) return value;
        throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration object is missing");
    }

    /** 完整对象必须精确包含要求字段，稀疏项目 overlay 则只校验出现的字段。 */
    private static void requireKeys(ObjectNode object, Set<String> keys) {
        Set<String> present = new HashSet<>();
        object.fieldNames().forEachRemaining(present::add);
        if (!present.containsAll(keys)) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration fields are missing");
        }
    }

    /** 数值预算只接受正整数，避免 TOML 浮点或字符串在运行时被静默截断。 */
    private static void requirePositiveIntegers(ObjectNode object, Set<String> keys, boolean complete) {
        for (String key : keys) {
            JsonNode value = object.get(key);
            if (value == null) {
                if (complete) throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration limit is missing");
                continue;
            }
            if (!value.isIntegralNumber() || value.longValue() <= 0) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration limit is invalid");
            }
        }
    }

    /** 拒绝 schema 闭集外字段，防止拼写错误和旧字段被静默忽略。 */
    private static void rejectUnknown(ObjectNode object, Set<String> allowed) {
        object.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration field is unsupported");
            }
        });
    }

    /** 递归拒绝疑似明文 Secret 字段，credential_id 只允许保存不透明引用。 */
    private static void scanForLiteralSecrets(JsonNode value) {
        if (value instanceof ObjectNode object) {
            object.properties().forEach(entry -> {
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                boolean integralBudget = NON_SECRET_BUDGET_KEYS.contains(key)
                                         && entry.getValue().isIntegralNumber();
                if (!"credential_id".equals(key) && !integralBudget && isSecretKey(key)) {
                    throw error(ConfigurationError.Code.LITERAL_SECRET, "literal credential is not allowed");
                }
                scanForLiteralSecrets(entry.getValue());
            });
        } else if (value instanceof ArrayNode array) {
            for (JsonNode child : array) scanForLiteralSecrets(child);
        }
    }

    /** 以稳定关键字识别 Secret 字段，不扫描值内容以避免误报普通文本。 */
    static boolean isSecretKey(String key) {
        for (String part : SECRET_KEY_PARTS) if (key.contains(part)) return true;
        return false;
    }

    /**
     * 项目层唯一可收紧的全局事实是已经显式启用的用户 Skill。项目 Skill 自身由
     * `project:` 身份隔离，因此无需也不能从用户层继承授权。
     */
    static void enforceNoEscalation(ObjectNode user, ObjectNode project) {
        if (user == null) return;
        validateDocument(user, ConfigurationScope.USER);
        validateDocument(project, ConfigurationScope.PROJECT);
        compareDisabledSkillReferences(user.get("skills"), project.get("disabled_skills"));
    }

    /**
     * 项目禁用引用必须已经在用户层显式启用；否则项目文件会从“收紧”变成可观察的虚假状态。
     */
    private static void compareDisabledSkillReferences(JsonNode user, JsonNode disabled) {
        if (disabled == null) return;
        if (!(user instanceof ArrayNode userReferences) || !(disabled instanceof ArrayNode disabledReferences)) {
            throw escalation("project skill references are invalid");
        }
        Set<String> enabled = new HashSet<>();
        userReferences.forEach(value -> enabled.add(value.textValue()));
        for (JsonNode value : disabledReferences) {
            if (!enabled.contains(value.textValue())) {
                throw escalation("project disabled skill is unavailable");
            }
        }
    }

    /** 权限等级只用于判断项目层是否扩大，不承担运行时默认值。 */
    private static int accessRank(String value) {
        return switch (value) {
            case "approval_required" -> 0;
            case "full_access" -> 1;
            default -> 99;
        };
    }

    /** 按 RFC 7396 应用对象 Merge Patch，结果仍必须经过 v2 严格策略。 */
    static ObjectNode applyMergePatch(ObjectNode source, ObjectNode patch) {
        ObjectNode result = source.deepCopy();
        patch.properties().forEach(entry -> {
            String key = entry.getKey();
            JsonNode patchValue = entry.getValue();
            if (patchValue.isNull()) {
                result.remove(key);
                return;
            }
            JsonNode current = result.get(key);
            if (current instanceof ObjectNode currentObject && patchValue instanceof ObjectNode patchObject) {
                result.set(key, applyMergePatch(currentObject, patchObject));
            } else if (patchValue instanceof ObjectNode patchObject) {
                result.set(key, applyMergePatch(result.objectNode(), patchObject));
            } else {
                result.set(key, patchValue.deepCopy());
            }
        });
        return result;
    }

    /**
     * 深度合并用户文档与已验证项目 overlay；Skill 引用按来源保持独立，项目禁用只从最终授权集移除。
     * `disabled_skills` 是项目层控制事实，不得泄漏进 effective 文档或 generation。
     */
    static ObjectNode mergeDocuments(ObjectNode base, ObjectNode overlay) {
        ObjectNode result = base.deepCopy();
        appendProjectSkillReferences(result, overlay.get("skills"));
        removeDisabledSkillReferences(result, overlay.get("disabled_skills"));
        result.remove("disabled_skills");
        return result;
    }

    /**
     * 将项目显式授权追加到用户引用集，不按名称折叠，从而让同名不同来源仍由发现优先级裁决。
     */
    private static void appendProjectSkillReferences(ObjectNode target, JsonNode projectReferences) {
        if (!(projectReferences instanceof ArrayNode references)) return;
        ArrayNode effective = target.withArray("skills");
        Set<String> existing = new HashSet<>();
        effective.forEach(value -> existing.add(value.textValue()));
        references.forEach(value -> {
            String reference = value.textValue();
            if (existing.add(reference)) effective.add(reference);
        });
    }

    /**
     * 项目停用只能移除已存在的全局授权；项目 Skill 不会因禁用列表而被新增或被其它项目影响。
     */
    private static void removeDisabledSkillReferences(ObjectNode target, JsonNode disabledReferences) {
        if (!(disabledReferences instanceof ArrayNode disabled)) return;
        Set<String> removed = new HashSet<>();
        disabled.forEach(value -> removed.add(value.textValue()));
        ArrayNode effective = target.withArray("skills");
        for (int index = effective.size() - 1; index >= 0; index--) {
            if (removed.contains(effective.get(index).textValue())) effective.remove(index);
        }
    }

    /** 凭据文档只允许有界 credential ID 到文本 Secret 的映射。 */
    public static void validateAuth(ObjectNode auth) {
        if (auth.size() > MAX_CREDENTIALS) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "credential store is too large");
        }
        auth.properties().forEach(entry -> {
            validateCredentialId(entry.getKey());
            JsonNode value = entry.getValue();
            if (!value.isTextual()) {
                throw error(ConfigurationError.Code.CORRUPT_AUTH, "credential store is corrupt");
            }
            validateSecret(value.textValue());
        });
    }

    /** credential ID 与 v1 Wire 共用字符集合和 100 字符上限，避免配置与凭据入口分叉。 */
    static void validateCredentialId(String id) {
        if (id == null || id.length() > MAX_CREDENTIAL_ID_LENGTH
                || !CREDENTIAL_ID_PATTERN.matcher(id).matches()) {
            throw error(ConfigurationError.Code.INVALID_ARGUMENT, "credential id is invalid");
        }
    }

    /** Secret 只允许有界可打印文本，实际值永不进入配置文档或返回 DTO。 */
    static void validateSecret(String secret) {
        if (secret == null || secret.isEmpty() || secret.length() > 1_048_576
            || secret.chars().anyMatch(character -> character == 0 || Character.isISOControl(character))) {
            throw error(ConfigurationError.Code.INVALID_ARGUMENT, "credential value is invalid");
        }
    }

    /** 统一构造不携带配置正文、路径或 Secret 的项目扩权错误。 */
    private static ConfigurationError escalation(String message) {
        return error(ConfigurationError.Code.LIMIT_ESCALATION, message);
    }

    /** 统一构造脱敏配置域错误，调用方只按稳定错误码分支。 */
    private static ConfigurationError error(ConfigurationError.Code code, String message) {
        return new ConfigurationError(code, message);
    }
}
