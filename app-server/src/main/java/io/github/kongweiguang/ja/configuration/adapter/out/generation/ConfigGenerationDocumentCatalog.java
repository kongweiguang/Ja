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

/** 把严格 v4 文档投影为冻结 Provider/Model、Skill 与 MCP catalog。 */
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

    /** 从已通过 v4 Policy 的 effective 文档建立强类型 catalog。 */
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
        JsonNode source = root.get("providers");
        if (!(source instanceof ArrayNode array)) return Map.of();
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
        ConfigGeneration.ProviderType providerType = switch (text(object, "provider", null)) {
            case "openai" -> ConfigGeneration.ProviderType.OPENAI;
            case "anthropic" -> ConfigGeneration.ProviderType.ANTHROPIC;
            default -> throw new IllegalArgumentException("provider unsupported");
        };
        ConfigGeneration.Api api = switch (text(object, "api", null)) {
            case "openai_responses" -> ConfigGeneration.Api.OPENAI_RESPONSES;
            case "anthropic_messages" -> ConfigGeneration.Api.ANTHROPIC_MESSAGES;
            default -> throw new IllegalArgumentException("API unsupported");
        };
        ObjectNode network = requireObject(object, "network_timeouts");
        ObjectNode defaults = requireObject(object, "agent_defaults");
        ObjectNode context = requireObject(defaults, "context");
        ObjectNode limits = requireObject(defaults, "turn_limits");
        return new ConfigGeneration.ProviderDefinition(
                text(object, "provider_id", null), text(object, "name", null), providerType, api,
                URI.create(text(object, "base_url", null)), text(object, "credential_id", null),
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
        JsonNode source = provider.get("models");
        if (!(source instanceof ArrayNode array)) return List.of();
        for (JsonNode value : array) {
            ObjectNode object = (ObjectNode) value;
            ObjectNode capabilities = requireObject(object, "capabilities");
            ConfigGeneration.ModelDefinition model = new ConfigGeneration.ModelDefinition(
                    text(object, "model_id", null), text(object, "name", null),
                    text(object, "model", null),
                    new ConfigGeneration.CapabilitiesConfig(
                            number(capabilities, "context_window_tokens"),
                            number(capabilities, "max_output_tokens"),
                            nativeModalities(provider, text(object, "model", null))),
                    reasoningLevelMap(object.get("reasoning_level_map")),
                    optionalReasoning(object.get("default_reasoning_level")));
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
        String api = text(provider, "api", null);
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

    /** 缺失或显式 null 表示模型不设置默认档位，否则必须属于闭集。 */
    private static ConfigGeneration.ReasoningLevel optionalReasoning(JsonNode value) {
        return value == null || value.isNull() ? null : reasoning(value.textValue());
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
        JsonNode source = root.get("skills");
        if (!(source instanceof ArrayNode array)) return Map.of();
        for (JsonNode value : array) {
            ObjectNode object = (ObjectNode) value;
            ConfigGeneration.Skill skill = new ConfigGeneration.Skill(
                    text(object, "skill_id", null), text(object, "name", null),
                    text(object, "scope", "user"), object.path("enabled").asBoolean(true),
                    text(object, "description", ""));
            values.put(skill.skillId(), skill);
        }
        return Map.copyOf(values);
    }

    /** 解析 MCP catalog，凭据仍只保存 credential ID。 */
    private static Map<String, ConfigGeneration.McpServer> parseMcpServers(ObjectNode root) {
        Map<String, ConfigGeneration.McpServer> values = new LinkedHashMap<>();
        JsonNode source = root.get("mcp_servers");
        if (!(source instanceof ArrayNode array)) return Map.of();
        for (JsonNode value : array) {
            ObjectNode object = (ObjectNode) value;
            ConfigGeneration.Transport transport = "stdio".equals(text(object, "transport", "stdio"))
                    ? ConfigGeneration.Transport.STDIO : ConfigGeneration.Transport.STREAMABLE_HTTP;
            ObjectNode authObject = object.get("auth") instanceof ObjectNode auth ? auth : null;
            ConfigGeneration.AuthKind kind = switch (text(authObject, "kind", "none")) {
                case "none" -> ConfigGeneration.AuthKind.NONE;
                case "env" -> ConfigGeneration.AuthKind.ENV;
                case "bearer" -> ConfigGeneration.AuthKind.BEARER;
                case "header" -> ConfigGeneration.AuthKind.HEADER;
                default -> throw new IllegalArgumentException("MCP auth unsupported");
            };
            ConfigGeneration.McpServer server = new ConfigGeneration.McpServer(
                    text(object, "mcp_id", null), text(object, "name", null), transport,
                    text(object, "endpoint", null), strings(object.get("args")),
                    stringsMap(object.get("env")), stringsMap(object.get("headers")),
                    new ConfigGeneration.Auth(kind, text(authObject, "name", null),
                            text(authObject, "credential_id", null)),
                    object.path("enabled").asBoolean(true));
            values.put(server.mcpId(), server);
        }
        return Map.copyOf(values);
    }

    /** 只接受文本节点，缺失时使用调用点明确提供的 fallback。 */
    private static String text(JsonNode object, String key, String fallback) {
        JsonNode value = object == null ? null : object.get(key);
        return value == null || value.isNull() ? fallback : value.isTextual() ? value.textValue()
                : ConfigGenerationValueRules.throwValue("text");
    }

    /** 要求嵌套对象存在，严格解析不使用空对象降级。 */
    private static ObjectNode requireObject(ObjectNode object, String key) {
        if (object.get(key) instanceof ObjectNode value) return value;
        throw new IllegalArgumentException("object is missing");
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

    /** 读取 MCP 参数字符串数组，缺失时返回空集合。 */
    private static List<String> strings(JsonNode value) {
        if (!(value instanceof ArrayNode array)) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode entry : array) result.add(entry.textValue());
        return List.copyOf(result);
    }

    /** 读取 MCP 文本 Map，拒绝对象外类型。 */
    private static Map<String, String> stringsMap(JsonNode value) {
        if (value == null) return Map.of();
        if (!(value instanceof ObjectNode object)) throw new IllegalArgumentException("map expected");
        Map<String, String> result = new LinkedHashMap<>();
        object.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue().textValue()));
        return Map.copyOf(result);
    }
}
