// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationError;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationScope;

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 配置 v1 的唯一严格策略，集中维护 Provider/Model 结构、项目层收紧规则与 Secret 边界。 */
public final class ConfigurationPolicy {
    private static final int MAX_CREDENTIAL_ID_LENGTH = 100;
    private static final Pattern CREDENTIAL_ID_PATTERN =
            Pattern.compile("cred_[A-Za-z0-9][A-Za-z0-9._-]{0,95}");
    private static final Set<String> ROOT_KEYS = Set.of(
            "schema_version", "config_revision", "default_access_mode", "default_provider_id",
            "default_model_id", "default_reasoning_level", "providers", "mcp_servers", "skills");
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
    private static final Set<String> SKILL_KEYS = Set.of(
            "skill_id", "name", "scope", "enabled", "description");
    private static final Set<String> SKILL_SCOPES = Set.of("builtin", "user", "ja", "project");
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

    /** 根据持久化作用域校验完整用户文档或只引用并收紧的项目 overlay。 */
    public static void validateDocument(ObjectNode document, ConfigurationScope scope) {
        if (document == null || scope == null) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration document is invalid");
        }
        rejectUnknown(document, ROOT_KEYS);
        requireCurrentSchema(document);
        if (scope == ConfigurationScope.USER) requireKeys(document, ROOT_KEYS);
        else requireKeys(document, Set.of("schema_version", "config_revision"));
        validateRevision(document.get("config_revision"));
        validateAccessMode(document.get("default_access_mode"));
        validateArray(document.get("providers"), PROVIDER_KEYS, "provider_id");
        validateArray(document.get("mcp_servers"), MCP_KEYS, "mcp_id");
        validateArray(document.get("skills"), SKILL_KEYS, "skill_id");
        if (scope == ConfigurationScope.USER) {
            validateUserProviders(document);
            validateUniqueProviderCredentials(document.get("providers"));
            validateUserMcpServers(document.get("mcp_servers"));
            validateUserSkills(document.get("skills"));
        } else {
            validateProjectProviders(document);
        }
        validateCatalogEnabled(document.get("mcp_servers"), scope);
        validateCatalogEnabled(document.get("skills"), scope);
        validateSkillScopes(document.get("skills"), scope);
        validateDefaultSelection(document, scope == ConfigurationScope.USER);
        scanForLiteralSecrets(document);
    }

    /** 只接受当前 schema v1；其它版本一律失败关闭，不执行迁移或兼容读取。 */
    private static void requireCurrentSchema(ObjectNode document) {
        JsonNode schema = document.get("schema_version");
        if (schema == null || !schema.isIntegralNumber()
            || schema.longValue() != ConfigurationDocumentFactory.CURRENT_SCHEMA_VERSION) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration schema is unsupported");
        }
    }

    /** revision 是 v1 文档的必填可读顺序，禁止通过缺失字段进入旧的隐式初始状态。 */
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

    /** 项目层允许稀疏 Provider/Model overlay，但稳定身份和出现的字段仍须严格合法。 */
    private static void validateProjectProviders(ObjectNode document) {
        JsonNode value = document.get("providers");
        if (!(value instanceof ArrayNode providers)) return;
        for (JsonNode entry : providers) {
            ObjectNode provider = (ObjectNode) entry;
            validateProviderRoute(provider, false);
            if (provider.get("network_timeouts") instanceof ObjectNode network) {
                validateNetworkTimeouts(network, false);
            }
            if (provider.get("agent_defaults") instanceof ObjectNode defaults) {
                validateAgentDefaults(defaults, false);
            }
            if (provider.has("models")) {
                validateArray(provider.get("models"), MODEL_KEYS, "model_id");
                for (JsonNode model : provider.withArray("models")) validateModel((ObjectNode) model, false);
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

    /** 连接地址只允许 HTTPS 或明确 loopback HTTP，拒绝用户信息、查询和片段。 */
    private static void validateBaseUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                               || "::1".equals(host);
            if (!uri.isAbsolute() || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || !("https".equalsIgnoreCase(uri.getScheme())
                || ("http".equalsIgnoreCase(uri.getScheme()) && loopback))) {
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

    /** 用户 Skill 定义必须显式保存全部当前字段，空描述合法但缺失描述不合法。 */
    private static void validateUserSkills(JsonNode value) {
        if (!(value instanceof ArrayNode skills)) return;
        for (JsonNode entry : skills) {
            ObjectNode skill = (ObjectNode) entry;
            requireKeys(skill, SKILL_KEYS);
            requireText(skill, "name", 512, false);
            requireText(skill, "scope", 64, false);
            requireText(skill, "description", 8_192, true);
            requireBoolean(skill, "enabled");
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

    /** 用户 Skill 定义只保存当前四类真实来源；项目稀疏覆盖不重复来源事实。 */
    private static void validateSkillScopes(JsonNode values, ConfigurationScope scope) {
        if (scope != ConfigurationScope.USER || !(values instanceof ArrayNode array)) return;
        for (JsonNode value : array) {
            JsonNode skillScope = value.get("scope");
            if (skillScope == null || !skillScope.isTextual()
                || !SKILL_SCOPES.contains(skillScope.textValue())) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "skill scope is invalid");
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

    /** 确保项目层 Provider/Model 只引用用户层稳定身份并收紧权限、能力与预算。 */
    static void enforceNoEscalation(ObjectNode user, ObjectNode project) {
        if (user == null) return;
        validateDocument(user, ConfigurationScope.USER);
        validateDocument(project, ConfigurationScope.PROJECT);
        compareRootDefaults(user, project);
        compareAccess(user.get("default_access_mode"), project.get("default_access_mode"));
        compareDisabledCatalog(user.get("mcp_servers"), project.get("mcp_servers"), "mcp_id");
        compareDisabledCatalog(user.get("skills"), project.get("skills"), "skill_id");
        Map<String, ObjectNode> providers = index(user.get("providers"), "provider_id");
        JsonNode overlays = project.get("providers");
        if (!(overlays instanceof ArrayNode array)) return;
        for (JsonNode value : array) {
            ObjectNode overlay = (ObjectNode) value;
            ObjectNode base = providers.get(overlay.path("provider_id").asText());
            if (base == null) throw escalation("project provider is unavailable");
            compareProvider(base, overlay);
        }
    }

    /** 项目默认值只能选择用户层已存在的 Provider/Model，不能通过 overlay 新建路由。 */
    private static void compareRootDefaults(ObjectNode user, ObjectNode project) {
        JsonNode provider = project.get("default_provider_id");
        JsonNode model = project.get("default_model_id");
        if (provider == null && model == null) return;
        if (provider == null || model == null || !provider.isTextual() || !model.isTextual()
            || findModel(user, provider.textValue(), model.textValue()) == null) {
            throw escalation("project default model is unavailable");
        }
        JsonNode effort = project.get("default_reasoning_level");
        if (effort != null && !effort.isNull()) {
            ObjectNode selected = findModel(user, provider.textValue(), model.textValue());
            if (!effort.isTextual() || !(selected.get("reasoning_level_map") instanceof ObjectNode levels)
                || !levels.has(effort.textValue())) {
                throw escalation("project default reasoning is unavailable");
            }
        }
    }

    /** Provider overlay 不得改写 API、地址或凭据，只能收紧预算和模型能力。 */
    private static void compareProvider(ObjectNode base, ObjectNode overlay) {
        for (String key : List.of("api", "base_url", "credential_id")) {
            if (overlay.has(key) && !java.util.Objects.equals(base.get(key), overlay.get(key))) {
                throw escalation("project provider route differs from user configuration");
            }
        }
        compareObjectLimits(base.get("network_timeouts"), overlay.get("network_timeouts"));
        compareAgentDefaults(base.get("agent_defaults"), overlay.get("agent_defaults"));
        Map<String, ObjectNode> models = index(base.get("models"), "model_id");
        JsonNode overlays = overlay.get("models");
        if (!(overlays instanceof ArrayNode array)) return;
        for (JsonNode value : array) {
            ObjectNode model = (ObjectNode) value;
            ObjectNode baseModel = models.get(model.path("model_id").asText());
            if (baseModel == null) throw escalation("project model is unavailable");
            compareModel(baseModel, model);
        }
    }

    /** Agent 默认值中的数值只能变小，catalog 启停不再由 Provider 默认值承载。 */
    private static void compareAgentDefaults(JsonNode base, JsonNode overlay) {
        if (!(base instanceof ObjectNode user) || !(overlay instanceof ObjectNode project)) return;
        compareObjectLimits(user.get("turn_limits"), project.get("turn_limits"));
    }

    /** 模型上游标识不可改写，能力预算和思考档位只能收紧。 */
    private static void compareModel(ObjectNode base, ObjectNode overlay) {
        if (overlay.has("model") && !java.util.Objects.equals(base.get("model"), overlay.get("model"))) {
            throw escalation("project upstream model differs from user configuration");
        }
        JsonNode baseCapabilities = base.get("capabilities");
        JsonNode overlayCapabilities = overlay.get("capabilities");
        compareObjectLimits(baseCapabilities, overlayCapabilities);
        compareReasoningMap(base.get("reasoning_level_map"), overlay.get("reasoning_level_map"));
        JsonNode defaultReasoning = overlay.get("default_reasoning_level");
        JsonNode allowed = overlay.has("reasoning_level_map")
                ? overlay.get("reasoning_level_map") : base.get("reasoning_level_map");
        if (defaultReasoning != null && !defaultReasoning.isNull()
            && (!defaultReasoning.isTextual() || !(allowed instanceof ObjectNode levels)
                || !levels.has(defaultReasoning.textValue()))) {
            throw escalation("project model reasoning is unavailable");
        }
    }

    /** 项目 reasoning map 只能保留用户已有键且不得改写其上游映射。 */
    private static void compareReasoningMap(JsonNode base, JsonNode overlay) {
        if (overlay == null) return;
        if (!(base instanceof ObjectNode baseMap) || !(overlay instanceof ObjectNode overlayMap)) {
            throw escalation("project reasoning map exceeds user configuration");
        }
        overlayMap.properties().forEach(entry -> {
            if (!java.util.Objects.equals(baseMap.get(entry.getKey()), entry.getValue())) {
                throw escalation("project reasoning map exceeds user configuration");
            }
        });
    }

    /** 项目目录项必须引用用户已启用对象并显式关闭，禁止新建或重新启用。 */
    private static void compareDisabledCatalog(JsonNode base, JsonNode overlay, String idKey) {
        if (overlay == null) return;
        Map<String, ObjectNode> available = index(base, idKey);
        if (!(overlay instanceof ArrayNode entries)) {
            throw escalation("project catalog is invalid");
        }
        for (JsonNode value : entries) {
            ObjectNode projectEntry = (ObjectNode) value;
            ObjectNode userEntry = available.get(projectEntry.path(idKey).asText());
            if (userEntry == null || !userEntry.path("enabled").asBoolean(false)
                || projectEntry.path("enabled").asBoolean(true)) {
                throw escalation("project catalog exceeds user configuration");
            }
        }
    }

    /** 根权限值只能保持或降低，不能把审批要求扩大为完整访问。 */
    private static void compareAccess(JsonNode base, JsonNode overlay) {
        if (base != null && overlay != null && base.isTextual() && overlay.isTextual()
            && accessRank(overlay.textValue()) > accessRank(base.textValue())) {
            throw escalation("project access is broader than user access");
        }
    }

    /** 递归比较同名数值预算，项目层任何增大都失败关闭。 */
    private static void compareObjectLimits(JsonNode user, JsonNode project) {
        if (!(user instanceof ObjectNode userObject) || !(project instanceof ObjectNode projectObject)) return;
        projectObject.properties().forEach(entry -> {
            JsonNode userValue = userObject.get(entry.getKey());
            JsonNode projectValue = entry.getValue();
            if (userValue != null && userValue.isNumber() && projectValue.isNumber()
                && isLimitKey(entry.getKey()) && projectValue.doubleValue() > userValue.doubleValue()) {
                throw escalation("project limits exceed user limits");
            }
            compareObjectLimits(userValue, projectValue);
        });
    }

    /** 将对象数组按稳定 ID 建索引，重复身份已在严格校验阶段拒绝。 */
    private static Map<String, ObjectNode> index(JsonNode values, String idKey) {
        Map<String, ObjectNode> indexed = new LinkedHashMap<>();
        if (!(values instanceof ArrayNode array)) return indexed;
        for (JsonNode value : array) indexed.put(value.path(idKey).asText(), (ObjectNode) value);
        return indexed;
    }

    /** 权限等级只用于判断项目层是否扩大，不承担运行时默认值。 */
    private static int accessRank(String value) {
        return switch (value) {
            case "approval_required" -> 0;
            case "full_access" -> 1;
            default -> 99;
        };
    }

    /** 只把显式预算字段纳入单调收紧比较，普通数字元数据不做安全推断。 */
    private static boolean isLimitKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("limit") || normalized.contains("timeout")
               || normalized.contains("tokens") || normalized.contains("budget")
               || normalized.startsWith("max_") || normalized.startsWith("window_");
    }

    /** 按 RFC 7396 应用对象 Merge Patch，结果仍必须经过 v1 严格策略。 */
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

    /** 深度合并用户文档与已验证项目 overlay，稳定身份数组按 ID 合并。 */
    static ObjectNode mergeDocuments(ObjectNode base, ObjectNode overlay) {
        ObjectNode result = base.deepCopy();
        overlay.properties().forEach(entry -> mergeValue(result, entry.getKey(), entry.getValue()));
        return result;
    }

    /** 对象递归、身份数组逐项合并，其余字段由项目层整体覆盖。 */
    private static void mergeValue(ObjectNode target, String key, JsonNode overlay) {
        JsonNode current = target.get(key);
        if (current instanceof ObjectNode currentObject && overlay instanceof ObjectNode overlayObject) {
            target.set(key, mergeDocuments(currentObject, overlayObject));
        } else if (current instanceof ArrayNode currentArray && overlay instanceof ArrayNode overlayArray
                   && isIdentityArray(currentArray) && isIdentityArray(overlayArray)) {
            target.set(key, mergeIdentityArrays(currentArray, overlayArray));
        } else {
            target.set(key, overlay.deepCopy());
        }
    }

    /** 按同类型稳定 ID 更新数组，保持用户层顺序并禁止位置语义。 */
    private static ArrayNode mergeIdentityArrays(ArrayNode base, ArrayNode overlay) {
        ArrayNode result = base.deepCopy();
        for (JsonNode candidate : overlay) {
            ObjectNode object = (ObjectNode) candidate;
            String id = identityOf(object);
            int existing = -1;
            for (int index = 0; index < result.size(); index++) {
                if (id.equals(identityOf(result.get(index)))) {
                    existing = index;
                    break;
                }
            }
            if (existing < 0) result.add(object.deepCopy());
            else result.set(existing, mergeDocuments((ObjectNode) result.get(existing), object));
        }
        return result;
    }

    /** 只有所有元素都具备 v1 稳定身份时才启用身份数组合并。 */
    private static boolean isIdentityArray(ArrayNode array) {
        for (JsonNode value : array) if (!(value instanceof ObjectNode) || identityOf(value) == null) return false;
        return true;
    }

    /** 只识别 v1 的稳定 ID，闭集外身份字段直接失败关闭。 */
    private static String identityOf(JsonNode value) {
        if (!(value instanceof ObjectNode object)) return null;
        for (String key : List.of("provider_id", "model_id", "mcp_id", "skill_id")) {
            if (object.path(key).isTextual()) return object.path(key).textValue();
        }
        return null;
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
