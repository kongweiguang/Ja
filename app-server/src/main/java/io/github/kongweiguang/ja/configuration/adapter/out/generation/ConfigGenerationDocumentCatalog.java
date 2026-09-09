// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把严格 v1 文档投影为冻结 Provider/Model、Skill 与 MCP catalog。 */
final class ConfigGenerationDocumentCatalog {
    private final Map<String, ConfigGeneration.ProviderDefinition> providers;
    private final Map<String, ConfigGeneration.Skill> skillDefinitions;
    private final Map<String, ConfigGeneration.McpServer> mcpDefinitions;

    /** 构造时冻结全部索引，代际后续不再依赖可变 Jackson 节点。 */
    private ConfigGenerationDocumentCatalog(
            Map<String, ConfigGeneration.ProviderDefinition> providers,
            Map<String, ConfigGeneration.Skill> skillDefinitions,
            Map<String, ConfigGeneration.McpServer> mcpDefinitions) {
        this.providers = Map.copyOf(providers);
        this.skillDefinitions = Map.copyOf(skillDefinitions);
        this.mcpDefinitions = Map.copyOf(mcpDefinitions);
    }

    /** 从已通过 v1 Policy 的 effective 文档建立强类型 catalog。 */
    static ConfigGenerationDocumentCatalog parse(ObjectNode root) {
        return new ConfigGenerationDocumentCatalog(parseProviders(root), parseSkills(root),
                parseMcpServers(root));
    }

    /** 返回稳定 Provider 索引，调用方不得从 Model 反向推导 Provider。 */
    Map<String, ConfigGeneration.ProviderDefinition> providers() {
        return providers;
    }

    /** 返回冻结 Skill 索引。 */
    Map<String, ConfigGeneration.Skill> skillDefinitions() {
        return skillDefinitions;
    }

    /** 返回冻结 MCP 索引。 */
    Map<String, ConfigGeneration.McpServer> mcpDefinitions() {
        return mcpDefinitions;
    }

    /** 逐个解析 Provider，并在同一 Provider 内保持模型配置顺序。 */
    private static Map<String, ConfigGeneration.ProviderDefinition> parseProviders(ObjectNode root) {
        Map<String, ConfigGeneration.ProviderDefinition> values = new LinkedHashMap<>();
        ArrayNode array = requireArray(root, "providers");
        for (JsonNode value : array) {
            ConfigGeneration.ProviderDefinition provider = parseProvider((ObjectNode) value);
            if (values.putIfAbsent(provider.providerId(), provider) != null) {
                throw new IllegalArgumentException("duplicate provider identity");
            }
        }
        return Map.copyOf(values);
    }

    /** 把连接与 Agent 默认值保持在 Provider 层，Model 不复制 credential 或 URL。 */
    private static ConfigGeneration.ProviderDefinition parseProvider(ObjectNode object) {
        ConfigGeneration.Api api = switch (requiredText(object, "api")) {
            case "openai_responses" -> ConfigGeneration.Api.OPENAI_RESPONSES;
            case "anthropic_messages" -> ConfigGeneration.Api.ANTHROPIC_MESSAGES;
            case "openai_chat_completions" -> ConfigGeneration.Api.OPENAI_CHAT_COMPLETIONS;
            default -> throw new IllegalArgumentException("API unsupported");
        };
        ObjectNode network = requireObject(object, "network_timeouts");
        ObjectNode defaults = requireObject(object, "agent_defaults");
        ObjectNode context = requireObject(defaults, "context");
        ObjectNode limits = requireObject(defaults, "turn_limits");
        return new ConfigGeneration.ProviderDefinition(
                requiredText(object, "provider_id"), requiredText(object, "name"), api,
                URI.create(requiredText(object, "base_url")), requiredText(object, "credential_id"),
                new ConfigGeneration.NetworkTimeoutConfig(
                        Duration.ofMillis(number(network, "connect_timeout_ms")),
                        Duration.ofMillis(number(network, "request_timeout_ms"))),
                new ConfigGeneration.AgentDefaultsConfig(
                        new ConfigGeneration.ContextConfig(bool(context, "auto_compact")),
                        new ConfigGeneration.TurnLimitConfig(integer(limits, "max_model_rounds"),
                                integer(limits, "max_tool_calls"),
                                Duration.ofMillis(number(limits, "wall_timeout_ms")))),
                parseModels(object));
    }

    /** 模型能力、输入模态与思考档位均从具体模型读取，不继承 Provider 全局开关。 */
    private static List<ConfigGeneration.ModelDefinition> parseModels(ObjectNode provider) {
        List<ConfigGeneration.ModelDefinition> values = new ArrayList<>();
        ArrayNode array = requireArray(provider, "models");
        for (JsonNode value : array) {
            ObjectNode object = (ObjectNode) value;
            ObjectNode capabilities = requireObject(object, "capabilities");
            ConfigGeneration.ModelDefinition model = new ConfigGeneration.ModelDefinition(
                    requiredText(object, "model_id"), requiredText(object, "name"),
                    requiredText(object, "model"),
                    new ConfigGeneration.CapabilitiesConfig(
                            number(capabilities, "context_window_tokens"),
                            number(capabilities, "max_output_tokens"),
                            nativeModalities(provider, requiredText(object, "model"))),
                    reasoningLevelMap(object.get("reasoning_level_map")),
                    nullableReasoning(object, "default_reasoning_level"));
            if (values.stream().anyMatch(existing -> existing.modelId().equals(model.modelId()))) {
                throw new IllegalArgumentException("duplicate model identity");
            }
            values.add(model);
        }
        return List.copyOf(values);
    }

    /**
     * 只有 App Server 已知模型且所选原生 Codec 支持时开放附件直传；未知自定义模型保守为 text-only。
     */
    private static List<ConfigGeneration.InputModality> nativeModalities(
            ObjectNode provider, String model) {
        String api = requiredText(provider, "api");
        boolean openAiNative = "openai_responses".equals(api)
                               && (model.startsWith("gpt-4o") || model.startsWith("gpt-4.1")
                                   || model.startsWith("gpt-5"));
        boolean anthropicNative = "anthropic_messages".equals(api) && model.startsWith("claude-3");
        return openAiNative || anthropicNative
                ? List.of(ConfigGeneration.InputModality.TEXT, ConfigGeneration.InputModality.IMAGE,
                        ConfigGeneration.InputModality.PDF)
                : List.of(ConfigGeneration.InputModality.TEXT);
    }

    /** 把逻辑档位与上游值一起冻结，Turn admission 不再依赖枚举名称猜测 Provider 参数。 */
    private static Map<ConfigGeneration.ReasoningLevel, String> reasoningLevelMap(JsonNode value) {
        if (!(value instanceof ObjectNode object)) throw new IllegalArgumentException("reasoning map missing");
        Map<ConfigGeneration.ReasoningLevel, String> result = new LinkedHashMap<>();
        object.properties().forEach(entry -> {
            ConfigGeneration.ReasoningLevel level = reasoning(entry.getKey());
            if (result.putIfAbsent(level, entry.getValue().textValue()) != null) {
                throw new IllegalArgumentException("duplicate reasoning level");
            }
        });
        return Map.copyOf(result);
    }

    /** 必填字段可显式为 null；缺失字段不能再被解释为未设置。 */
    private static ConfigGeneration.ReasoningLevel nullableReasoning(ObjectNode object, String key) {
        JsonNode value = object.get(key);
        if (value == null) throw new IllegalArgumentException("reasoning level is missing");
        return value.isNull() ? null : reasoning(requiredText(object, key));
    }

    /** 只映射逻辑七档，不接受厂商私有别名。 */
    private static ConfigGeneration.ReasoningLevel reasoning(String value) {
        return switch (value) {
            case "off" -> ConfigGeneration.ReasoningLevel.OFF;
            case "minimal" -> ConfigGeneration.ReasoningLevel.MINIMAL;
            case "low" -> ConfigGeneration.ReasoningLevel.LOW;
            case "medium" -> ConfigGeneration.ReasoningLevel.MEDIUM;
            case "high" -> ConfigGeneration.ReasoningLevel.HIGH;
            case "xhigh" -> ConfigGeneration.ReasoningLevel.XHIGH;
            case "max" -> ConfigGeneration.ReasoningLevel.MAX;
            default -> throw new IllegalArgumentException("reasoning level unsupported");
        };
    }

    /** 解析 Skill catalog，严格文档保证所有字段类型已闭集校验。 */
    private static Map<String, ConfigGeneration.Skill> parseSkills(ObjectNode root) {
        Map<String, ConfigGeneration.Skill> values = new LinkedHashMap<>();
        ArrayNode array = requireArray(root, "skills");
        for (JsonNode value : array) {
            ObjectNode object = (ObjectNode) value;
            ConfigGeneration.Skill skill = new ConfigGeneration.Skill(
                    requiredText(object, "skill_id"), requiredText(object, "name"),
                    requiredText(object, "scope"), requiredBoolean(object, "enabled"),
                    requiredString(object, "description"));
            values.put(skill.skillId(), skill);
        }
        return Map.copyOf(values);
    }

    /** 解析 MCP catalog，凭据仍只保存 credential ID。 */
    private static Map<String, ConfigGeneration.McpServer> parseMcpServers(ObjectNode root) {
        Map<String, ConfigGeneration.McpServer> values = new LinkedHashMap<>();
        ArrayNode array = requireArray(root, "mcp_servers");
        for (JsonNode value : array) {
            ObjectNode object = (ObjectNode) value;
            ConfigGeneration.Transport transport = switch (requiredText(object, "transport")) {
                case "stdio" -> ConfigGeneration.Transport.STDIO;
                case "streamable_http" -> ConfigGeneration.Transport.STREAMABLE_HTTP;
                default -> throw new IllegalArgumentException("MCP transport unsupported");
            };
            ObjectNode authObject = requireObject(object, "auth");
            ConfigGeneration.AuthKind kind = switch (requiredText(authObject, "kind")) {
                case "none" -> ConfigGeneration.AuthKind.NONE;
                case "env" -> ConfigGeneration.AuthKind.ENV;
                case "bearer" -> ConfigGeneration.AuthKind.BEARER;
                case "header" -> ConfigGeneration.AuthKind.HEADER;
                default -> throw new IllegalArgumentException("MCP auth unsupported");
            };
            ConfigGeneration.McpServer server = new ConfigGeneration.McpServer(
                    requiredText(object, "mcp_id"), requiredText(object, "name"), transport,
                    requiredText(object, "endpoint"), requiredStrings(object, "args"),
                    requiredStringsMap(object, "env"), requiredStringsMap(object, "headers"),
                    new ConfigGeneration.Auth(kind,
                            kind == ConfigGeneration.AuthKind.ENV || kind == ConfigGeneration.AuthKind.HEADER
                                    ? requiredText(authObject, "name") : null,
                            kind == ConfigGeneration.AuthKind.NONE
                                    ? null : requiredText(authObject, "credential_id")),
                    requiredBoolean(object, "enabled"));
            values.put(server.mcpId(), server);
        }
        return Map.copyOf(values);
    }

    /** 只接受显式文本字段，generation 不承担旧文档默认值或别名转换。 */
    private static String requiredText(ObjectNode object, String key) {
        String value = requiredString(object, key);
        if (value.isBlank()) throw new IllegalArgumentException("text is blank");
        return value;
    }

    /** description 允许空文本，但字段本身仍必须存在。 */
    private static String requiredString(ObjectNode object, String key) {
        JsonNode value = object == null ? null : object.get(key);
        if (value == null || !value.isTextual()) return ConfigGenerationValueRules.throwValue("text");
        return value.textValue();
    }

    /** 要求嵌套对象存在，严格解析不使用空对象降级。 */
    private static ObjectNode requireObject(ObjectNode object, String key) {
        if (object.get(key) instanceof ObjectNode value) return value;
        throw new IllegalArgumentException("object is missing");
    }

    /** 要求当前 v1 的集合字段显式存在，禁止缺失集合被投影为空目录。 */
    private static ArrayNode requireArray(ObjectNode object, String key) {
        if (object.get(key) instanceof ArrayNode value) return value;
        throw new IllegalArgumentException("array is missing");
    }

    /** 只接受整数节点，避免浮点或文本在时间和配额边界中被截断。 */
    private static long number(JsonNode object, String key) {
        JsonNode value = object.get(key);
        if (value == null || !value.isIntegralNumber()) throw new IllegalArgumentException("number expected");
        return value.longValue();
    }

    /** 把已校验整数收敛到 Java int 范围。 */
    private static int integer(JsonNode object, String key) {
        long value = number(object, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("integer expected");
        }
        return (int) value;
    }

    /** 只接受显式布尔值，新 schema 不为策略选择提供隐式默认。 */
    private static boolean bool(JsonNode object, String key) {
        JsonNode value = object.get(key);
        if (value == null || !value.isBoolean()) throw new IllegalArgumentException("boolean expected");
        return value.booleanValue();
    }

    /** 读取显式 MCP 参数数组，缺失或错误类型直接失败关闭。 */
    private static List<String> requiredStrings(ObjectNode object, String key) {
        JsonNode value = object.get(key);
        if (!(value instanceof ArrayNode array)) throw new IllegalArgumentException("string array is missing");
        List<String> result = new ArrayList<>();
        for (JsonNode entry : array) {
            if (!entry.isTextual()) throw new IllegalArgumentException("string array is invalid");
            result.add(entry.textValue());
        }
        return List.copyOf(result);
    }

    /** 读取显式 MCP 文本 Map，缺失字段不能再被解释为空 Map。 */
    private static Map<String, String> requiredStringsMap(ObjectNode source, String key) {
        JsonNode value = source.get(key);
        if (!(value instanceof ObjectNode object)) throw new IllegalArgumentException("map expected");
        Map<String, String> result = new LinkedHashMap<>();
        object.properties().forEach(entry -> {
            if (!entry.getValue().isTextual()) throw new IllegalArgumentException("map value expected");
            result.put(entry.getKey(), entry.getValue().textValue());
        });
        return Map.copyOf(result);
    }

    /** 布尔字段必须显式存在，避免 JsonNode 默认 false 吞掉损坏配置。 */
    private static boolean requiredBoolean(ObjectNode object, String key) {
        JsonNode value = object.get(key);
        if (value == null || !value.isBoolean()) throw new IllegalArgumentException("boolean expected");
        return value.booleanValue();
    }
}
