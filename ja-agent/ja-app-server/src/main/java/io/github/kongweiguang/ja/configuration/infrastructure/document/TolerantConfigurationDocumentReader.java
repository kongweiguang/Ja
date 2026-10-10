// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.infrastructure.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationData;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationScope;
import io.github.kongweiguang.ja.configuration.domain.model.SkillReference;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将已通过 TOML 语法解析的用户文档收敛为可安全执行的运行时投影。
 *
 * <p>严格校验仍是写入和显式替换的唯一门槛。这里刻意只用于读取：无法证明安全的条目被隔离，
 * 普通字段退回保守默认值，原始文档不修改，因此一次 MCP 拼写错误不会把无关 Provider、历史或
 * 设置入口一并阻断。</p>
 */
final class TolerantConfigurationDocumentReader {
    private static final int CURRENT_SCHEMA_VERSION = ConfigurationDocumentFactory.CURRENT_SCHEMA_VERSION;
    private static final long DEFAULT_CONNECT_TIMEOUT_MILLIS =
            ConfigurationDocumentFactory.DEFAULT_CONNECT_TIMEOUT_MILLIS;
    private static final long DEFAULT_REQUEST_TIMEOUT_MILLIS =
            ConfigurationDocumentFactory.DEFAULT_REQUEST_TIMEOUT_MILLIS;
    private static final long DEFAULT_CONTEXT_WINDOW_TOKENS =
            ConfigurationDocumentFactory.DEFAULT_CONTEXT_WINDOW_TOKENS;
    private static final long DEFAULT_MAX_OUTPUT_TOKENS =
            ConfigurationDocumentFactory.DEFAULT_MAX_OUTPUT_TOKENS;
    private static final Set<String> REASONING_LEVELS = Set.of(
            "off", "minimal", "low", "medium", "high", "xhigh", "max");
    private static final Set<String> INTERACTION_KEYS = Set.of("clarification_enabled");
    private static final Set<String> SUBAGENT_KEYS = Set.of(
            "enabled", "provider_id", "model_id", "reasoning_level");
    private static final Set<String> NETWORK_TIMEOUT_KEYS = Set.of(
            "connect_timeout_ms", "request_timeout_ms");
    private static final Set<String> AGENT_DEFAULT_KEYS = Set.of("context");
    private static final Set<String> CONTEXT_KEYS = Set.of("auto_compact");
    private static final Set<String> PROVIDER_KEYS = Set.of(
            "provider_id", "name", "api", "base_url", "credential_id", "network_timeouts",
            "agent_defaults", "models");
    private static final Set<String> MODEL_KEYS = Set.of(
            "model_id", "name", "model", "capabilities", "reasoning_level_map",
            "default_reasoning_level");
    private static final Set<String> MCP_KEYS = Set.of(
            "mcp_id", "name", "transport", "endpoint", "args", "env", "headers", "auth", "enabled");

    /**
     * 这个读取器只生成瞬时投影，防止配置文件 owner 被隐式复制。
     */
    private TolerantConfigurationDocumentReader() {
    }

    /**
     * 按作用域生成严格可消费的副本和可展示问题；调用者保留 source 作为唯一可编辑原文。
     */
    static Result normalize(ObjectNode source, ConfigurationScope scope) {
        List<ConfigurationData.Issue> issues = new ArrayList<>();
        ObjectNode normalized = scope == ConfigurationScope.USER
                ? normalizeUser(source, issues) : normalizeProject(source, issues);
        ConfigurationPolicy.validateDocument(normalized, scope);
        return new Result(normalized, List.copyOf(issues));
    }

    /**
     * 用户层保留每个安全 Provider/Model，默认选择只在其引用仍完整时保留，避免静默切换到首个服务商。
     */
    private static ObjectNode normalizeUser(ObjectNode source, List<ConfigurationData.Issue> issues) {
        ObjectNode result = source.objectNode();
        result.put("schema_version", CURRENT_SCHEMA_VERSION);
        result.put("config_revision", positiveRevision(source.get("config_revision"), issues, "user", "config_revision"));
        copyUnknownRootFields(source, ConfigurationPolicy.USER_ROOT_KEYS, "user", issues);

        JsonNode rawAccessMode = source.get("default_access_mode");
        String accessMode = rawAccessMode == null
                ? ConfigurationDocumentFactory.DEFAULT_ACCESS_MODE : text(rawAccessMode);
        if (!"approval_required".equals(accessMode) && !"full_access".equals(accessMode)) {
            accessMode = "approval_required";
            issue(issues, "user", "default_access_mode", null, "INVALID_FIELD", "default_in_use", "edit");
        }
        result.put("default_access_mode", accessMode);
        result.set("interaction", normalizeInteraction(source.get("interaction"), issues));
        result.set("subagents", normalizeSubagents());
        result.set("disabled_skills", normalizeSkillReferences(source.get("disabled_skills"), ConfigurationScope.USER, issues));

        ArrayNode providers = result.putArray("providers");
        Set<String> providerIds = new HashSet<>();
        JsonNode sourceProviders = source.get("providers");
        if (sourceProviders instanceof ArrayNode entries) {
            for (int index = 0; index < entries.size(); index++) {
                if (!(entries.get(index) instanceof ObjectNode entry)) {
                    issue(issues, "user", "providers", null, "INVALID_ENTRY", "entry_skipped", "edit");
                    continue;
                }
                ObjectNode provider = normalizeProvider(entry, providerIds, issues);
                if (provider != null) {
                    providers.add(provider);
                }
            }
        } else if (sourceProviders != null) {
            issue(issues, "user", "providers", null, "INVALID_FIELD", "default_in_use", "edit");
        }

        ArrayNode servers = result.putArray("mcp_servers");
        Set<String> mcpIds = new HashSet<>();
        JsonNode sourceServers = source.get("mcp_servers");
        if (sourceServers instanceof ArrayNode entries) {
            for (int index = 0; index < entries.size(); index++) {
                if (!(entries.get(index) instanceof ObjectNode entry)) {
                    issue(issues, "user", "mcp_servers", null, "INVALID_ENTRY", "entry_skipped", "edit");
                    continue;
                }
                ObjectNode server = normalizeMcpServer(entry, mcpIds, issues, "user");
                if (server != null) {
                    servers.add(server);
                }
            }
        } else if (sourceServers != null) {
            issue(issues, "user", "mcp_servers", null, "INVALID_FIELD", "default_in_use", "edit");
        }

        normalizeDefaultSelection(result, source, issues);
        normalizeSubagentSelection(result, source.get("subagents"), issues);
        return result;
    }

    /**
     * 项目文档保持稀疏，只输出显式且可验证的字段；这样下层用户设置仍能参与继承，
     * 一个坏覆盖项也不会让同一项目的其它有效覆盖失效。
     */
    private static ObjectNode normalizeProject(ObjectNode source, List<ConfigurationData.Issue> issues) {
        ObjectNode result = source.objectNode();
        result.put("schema_version", CURRENT_SCHEMA_VERSION);
        result.put("config_revision", positiveRevision(source.get("config_revision"), issues, "project", "config_revision"));
        copyUnknownRootFields(source, ConfigurationPolicy.PROJECT_ROOT_KEYS, "project", issues);
        normalizeProjectAccessMode(source, result, issues);
        normalizeProjectSelection(source, result, issues);
        normalizeProjectInteraction(source, result, issues);
        normalizeProjectSubagents(source, result, issues);
        normalizeProjectProviders(source, result, issues);
        if (source.has("disabled_skills")) {
            result.set("disabled_skills",
                    normalizeSkillReferences(source.get("disabled_skills"), ConfigurationScope.PROJECT, issues));
        }
        normalizeMcpCatalog(source, result, "project", issues);
        return result;
    }

    /**
     * 项目权限只覆盖明确给出的合法枚举；非法值不遮蔽用户层和内置默认。
     */
    private static void normalizeProjectAccessMode(ObjectNode source, ObjectNode result,
                                                   List<ConfigurationData.Issue> issues) {
        if (!source.has("default_access_mode")) {
            return;
        }
        String value = text(source.get("default_access_mode"));
        if ("approval_required".equals(value) || "full_access".equals(value)) {
            result.put("default_access_mode", value);
        } else {
            issue(issues, "project", "default_access_mode", null,
                    "INVALID_FIELD", "lower_layer_in_use", "edit");
        }
    }

    /**
     * 项目可选择用户已定义的 Provider/Model；引用成对校验，不能由项目创建或路由新凭据。
     */
    private static void normalizeProjectSelection(ObjectNode source, ObjectNode result,
                                                  List<ConfigurationData.Issue> issues) {
        boolean hasProvider = source.has("default_provider_id");
        boolean hasModel = source.has("default_model_id");
        if (!hasProvider && !hasModel) {
            return;
        }
        String provider = text(source.get("default_provider_id"));
        String model = text(source.get("default_model_id"));
        if (hasProvider) {
            if (provider == null && optionalNull(source.get("default_provider_id"))) {
                result.putNull("default_provider_id");
            } else if (typedId(source.get("default_provider_id"), "provider_") != null) {
                result.put("default_provider_id", provider);
            } else {
                issue(issues, "project", "default_provider_id", null,
                        "INVALID_FIELD", "lower_layer_in_use", "edit");
            }
        }
        if (hasModel) {
            if (model == null && optionalNull(source.get("default_model_id"))) {
                result.putNull("default_model_id");
            } else if (typedId(source.get("default_model_id"), "model_") != null) {
                result.put("default_model_id", model);
            } else {
                issue(issues, "project", "default_model_id", null,
                        "INVALID_FIELD", "lower_layer_in_use", "edit");
            }
        }
        if (source.has("default_reasoning_level")) {
            String reasoning = text(source.get("default_reasoning_level"));
            if (optionalNull(source.get("default_reasoning_level"))
                || reasoning != null && REASONING_LEVELS.contains(reasoning)) {
                if (reasoning == null) {
                    result.putNull("default_reasoning_level");
                }
                else {
                    result.put("default_reasoning_level", reasoning);
                }
            } else {
                result.remove("default_provider_id");
                result.remove("default_model_id");
                issue(issues, "project", "default_reasoning_level", null,
                        "INVALID_FIELD", "lower_layer_in_use", "edit");
            }
        }
    }

    /**
     * 澄清策略遵循稀疏继承；错误对象或错误类型只丢弃该字段。
     */
    private static void normalizeProjectInteraction(ObjectNode source, ObjectNode result,
                                                    List<ConfigurationData.Issue> issues) {
        JsonNode value = source.get("interaction");
        if (value == null) {
            return;
        }
        if (!(value instanceof ObjectNode object)) {
            issue(issues, "project", "interaction", null,
                    "INVALID_FIELD", "lower_layer_in_use", "edit");
            return;
        }
        copyUnknownFields(object, INTERACTION_KEYS, "project", null, issues);
        JsonNode enabled = object.get("clarification_enabled");
        if (enabled == null) {
            return;
        }
        if (enabled.isBoolean()) {
            result.putObject("interaction").set("clarification_enabled", enabled.deepCopy());
        }
        else {
            issue(issues, "project", "interaction", null,
                "INVALID_FIELD", "lower_layer_in_use", "edit");
        }
    }

    /**
     * 项目子任务策略只覆盖明示且自洽的策略字段，不把全局密钥或 Provider 连接移入项目层。
     */
    private static void normalizeProjectSubagents(ObjectNode source, ObjectNode result,
                                                  List<ConfigurationData.Issue> issues) {
        JsonNode value = source.get("subagents");
        if (value == null) {
            return;
        }
        if (!(value instanceof ObjectNode object)) {
            issue(issues, "project", "subagents", null,
                    "INVALID_FIELD", "lower_layer_in_use", "edit");
            return;
        }
        copyUnknownFields(object, SUBAGENT_KEYS, "project", null, issues);
        ObjectNode patch = object.objectNode();
        JsonNode enabled = object.get("enabled");
        if (enabled != null) {
            if (enabled.isBoolean()) {
                patch.set("enabled", enabled.deepCopy());
            }
            else {
                issue(issues, "project", "subagents.enabled", null,
                    "INVALID_FIELD", "lower_layer_in_use", "edit");
            }
        }
        String provider = text(object.get("provider_id"));
        String model = text(object.get("model_id"));
        normalizeProjectSubagentReference(object, patch, "provider_id", provider, "provider_", issues);
        normalizeProjectSubagentReference(object, patch, "model_id", model, "model_", issues);
        JsonNode reasoning = object.get("reasoning_level");
        if (reasoning != null && (optionalNull(reasoning)
                                  || reasoning.isTextual() && REASONING_LEVELS.contains(reasoning.textValue()))) {
            patch.set("reasoning_level", reasoning.deepCopy());
        } else if (reasoning != null) {
            issue(issues, "project", "subagents.reasoning_level", null,
                    "INVALID_FIELD", "lower_layer_in_use", "edit");
        }
        if (!patch.isEmpty()) {
            result.set("subagents", patch);
        }
    }

    /**
     * Provider 路由按协议、地址、凭据引用成组覆盖，防止新地址误用下层密钥。
     */
    private static void normalizeProjectProviders(ObjectNode source, ObjectNode result,
                                                  List<ConfigurationData.Issue> issues) {
        JsonNode value = source.get("providers");
        if (value == null) {
            return;
        }
        if (!(value instanceof ArrayNode entries) || entries.size() > 512) {
            issue(issues, "project", "providers", null,
                    "INVALID_FIELD", "lower_layer_in_use", "edit");
            return;
        }
        ArrayNode providers = result.putArray("providers");
        Set<String> identities = new HashSet<>();
        for (JsonNode entry : entries) {
            if (!(entry instanceof ObjectNode object)) {
                issue(issues, "project", "providers", null, "INVALID_ENTRY", "entry_skipped", "edit");
                continue;
            }
            String id = typedId(object.get("provider_id"), "provider_");
            if (id == null || !identities.add(id)) {
                issue(issues, "project", "providers", id, "INVALID_PROVIDER", "entry_skipped", "edit");
                continue;
            }
            copyUnknownFields(object, ConfigurationPolicy.PROJECT_PROVIDER_KEYS, "project", id, issues);
            ObjectNode provider = object.objectNode().put("provider_id", id);
            normalizeProjectProviderRoute(object, provider, id, issues);
            if (object.has("network_timeouts")) {
                provider.set("network_timeouts", normalizeProjectNetworkTimeouts(
                        object.get("network_timeouts"), id, issues));
            }
            if (object.has("agent_defaults")) {
                JsonNode defaults = object.get("agent_defaults");
                if (defaults instanceof ObjectNode agent) {
                    ObjectNode normalized = agent.objectNode();
                    JsonNode context = agent.get("context");
                    if (context instanceof ObjectNode values) {
                        JsonNode compact = values.get("auto_compact");
                        if (compact != null && compact.isBoolean()) {
                            normalized.putObject("context").set("auto_compact", compact.deepCopy());
                        } else if (compact != null) {
                            issue(issues, "project", "agent_defaults.context.auto_compact", id,
                                    "INVALID_FIELD", "lower_layer_in_use", "edit");
                        }
                        copyUnknownFields(values, CONTEXT_KEYS, "project", id, issues);
                    } else if (context != null) {
                        issue(issues, "project", "agent_defaults.context", id,
                                "INVALID_FIELD", "lower_layer_in_use", "edit");
                    }
                    copyUnknownFields(agent, AGENT_DEFAULT_KEYS, "project", id, issues);
                    if (!normalized.isEmpty()) {
                        provider.set("agent_defaults", normalized);
                    }
                } else {
                    issue(issues, "project", "agent_defaults", id,
                            "INVALID_FIELD", "lower_layer_in_use", "edit");
                }
            }
            if (object.has("models")) {
                normalizeProjectModels(object.get("models"), provider, id, issues);
            }
            if (object.has("removed_model_ids")) {
                normalizeRemovedProjectModels(object.get("removed_model_ids"), provider, id, issues);
            }
            removeConflictingProjectModelIds(provider, id, issues);
            if (provider.size() > 1) {
                providers.add(provider);
            }
        }
    }

    /**
     * 同一项目条目不能同时覆盖和移除同一模型；冲突时保留覆盖并丢弃删除标记。
     */
    private static void removeConflictingProjectModelIds(ObjectNode provider, String providerId,
                                                         List<ConfigurationData.Issue> issues) {
        if (!(provider.get("models") instanceof ArrayNode models)
            || !(provider.get("removed_model_ids") instanceof ArrayNode removed)) {
                return;
            }
        Set<String> modelIds = new HashSet<>();
        models.forEach(model -> modelIds.add(model.path("model_id").asText()));
        ArrayNode accepted = removed.arrayNode();
        for (JsonNode value : removed) {
            String modelId = value.asText();
            if (modelIds.contains(modelId)) {
                issue(issues, "project", "providers.removed_model_ids", providerId,
                        "MODEL_OVERRIDE_CONFLICT", "project_model_in_use", "edit");
            } else {
                accepted.add(value.deepCopy());
            }
        }
        provider.set("removed_model_ids", accepted);
    }

    /**
     * 项目模型覆盖按 model_id 成组校验；坏项不遮蔽下层模型，显式空表保留为空表。
     */
    private static void normalizeProjectModels(JsonNode value, ObjectNode provider, String providerId,
                                               List<ConfigurationData.Issue> issues) {
        if (!(value instanceof ArrayNode entries) || entries.size() > 512) {
            issue(issues, "project", "providers.models", providerId,
                    "INVALID_FIELD", "lower_layer_in_use", "edit");
            return;
        }
        ArrayNode models = provider.putArray("models");
        Set<String> modelIds = new HashSet<>();
        for (JsonNode entry : entries) {
            if (!(entry instanceof ObjectNode object)
                || !object.has("model_id") || !object.has("name") || !object.has("model")
                || !object.has("capabilities") || !object.has("reasoning_level_map")
                || !object.has("default_reasoning_level")
                || !(object.get("capabilities") instanceof ObjectNode capabilities)
                || !capabilities.has("context_window_tokens") || !capabilities.has("max_output_tokens")
                || !(object.get("reasoning_level_map") instanceof ObjectNode)) {
                issue(issues, "project", "providers.models", providerId,
                        "INVALID_MODEL", "entry_skipped", "edit");
                continue;
            }
            List<ConfigurationData.Issue> modelIssues = new ArrayList<>();
            ObjectNode model = normalizeModel(object, providerId, modelIds, modelIssues, "project");
            if (model == null || !modelIssues.isEmpty()) {
                if (modelIssues.isEmpty()) {
                    issue(issues, "project", "providers.models", providerId,
                            "INVALID_MODEL", "entry_skipped", "edit");
                } else {
                    issues.addAll(modelIssues);
                }
                continue;
            }
            models.add(model);
        }
    }

    /**
     * 删除引用使用独立稳定 ID 列表表达；缺失列表继承，空列表清除项目删除标记。
     */
    private static void normalizeRemovedProjectModels(JsonNode value, ObjectNode provider, String providerId,
                                                      List<ConfigurationData.Issue> issues) {
        if (!(value instanceof ArrayNode entries) || entries.size() > 512) {
            issue(issues, "project", "providers.removed_model_ids", providerId,
                    "INVALID_FIELD", "lower_layer_in_use", "edit");
            return;
        }
        ArrayNode ids = provider.putArray("removed_model_ids");
        Set<String> identities = new HashSet<>();
        for (JsonNode entry : entries) {
            String id = typedId(entry, "model_");
            if (id == null || !identities.add(id)) {
                issue(issues, "project", "providers.removed_model_ids", providerId,
                        "INVALID_ENTRY", "entry_skipped", "edit");
            } else {
                ids.add(id);
            }
        }
    }

    /**
     * 缺少或损坏任一关联路由字段时整体继承下层，绝不组合新旧目标与凭据。
     */
    private static void normalizeProjectProviderRoute(ObjectNode source, ObjectNode target, String providerId,
                                                      List<ConfigurationData.Issue> issues) {
        boolean anyRouteField = source.has("api") || source.has("base_url") || source.has("credential_id");
        if (!anyRouteField) {
            return;
        }
        String api = text(source.get("api"));
        String baseUrl = text(source.get("base_url"));
        String credential = credentialId(source.get("credential_id"));
        if (("openai_responses".equals(api) || "anthropic_messages".equals(api)
             || "openai_chat_completions".equals(api)) && isSafeHttpUrl(baseUrl) && credential != null) {
            target.put("api", api);
            target.put("base_url", baseUrl);
            target.put("credential_id", credential);
            return;
        }
        issue(issues, "project", "providers.connection", providerId,
                "INVALID_PROVIDER_CONNECTION", "lower_layer_in_use", "edit");
    }

    /**
     * 子任务引用分别保留显式合法覆盖；最终成对引用由有效配置合并阶段校验。
     */
    private static void normalizeProjectSubagentReference(ObjectNode source, ObjectNode patch,
                                                          String field, String value, String prefix,
                                                          List<ConfigurationData.Issue> issues) {
        if (!source.has(field)) {
            return;
        }
        JsonNode raw = source.get(field);
        if (optionalNull(raw)) {
            patch.putNull(field);
        } else if (typedId(raw, prefix) != null) {
            patch.put(field, value);
        } else {
            issue(issues, "project", "subagents." + field, null,
                    "INVALID_FIELD", "lower_layer_in_use", "edit");
        }
    }

    /**
     * Project 网络预算维持稀疏形状并逐字段限界，未提供值继续继承用户层。
     */
    private static ObjectNode normalizeProjectNetworkTimeouts(JsonNode value, String entityId,
                                                              List<ConfigurationData.Issue> issues) {
        ObjectNode result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        if (!(value instanceof ObjectNode object)) {
            issue(issues, "project", "network_timeouts", entityId,
                    "INVALID_FIELD", "lower_layer_in_use", "edit");
            return result;
        }
        copyProjectTimeout(object, result, "connect_timeout_ms", 100, 120_000, entityId, issues);
        copyProjectTimeout(object, result, "request_timeout_ms", 1_000, 3_600_000, entityId, issues);
        copyUnknownFields(object, NETWORK_TIMEOUT_KEYS, "project", entityId, issues);
        return result;
    }

    /**
     * 只接受协议范围内的正整数 timeout，防止无效覆盖落入启动参数。
     */
    private static void copyProjectTimeout(ObjectNode source, ObjectNode target, String field,
                                           long minimum, long maximum, String entityId,
                                           List<ConfigurationData.Issue> issues) {
        JsonNode value = source.get(field);
        if (value == null) {
            return;
        }
        if (value.isIntegralNumber() && value.longValue() >= minimum && value.longValue() <= maximum) {
            target.set(field, value.deepCopy());
        } else {
            issue(issues, "project", "network_timeouts." + field, entityId,
                    "INVALID_FIELD", "lower_layer_in_use", "edit");
        }
    }

    /**
     * 用户与项目 MCP 都按稳定 mcp_id 投影；项目重复 ID 会在合并时替换全局服务。
     */
    private static void normalizeMcpCatalog(ObjectNode source, ObjectNode result, String scope,
                                            List<ConfigurationData.Issue> issues) {
        JsonNode sourceServers = source.get("mcp_servers");
        if (sourceServers == null) {
            return;
        }
        ArrayNode servers = result.putArray("mcp_servers");
        Set<String> mcpIds = new HashSet<>();
        if (sourceServers instanceof ArrayNode entries) {
            for (JsonNode entry : entries) {
                if (entry instanceof ObjectNode object) {
                    ObjectNode server = normalizeMcpServer(object, mcpIds, issues, scope);
                    if (server != null) {
                        servers.add(server);
                    }
                } else {
                    issue(issues, scope, "mcp_servers", null, "INVALID_ENTRY", "entry_skipped", "edit");
                }
            }
        } else {
            issue(issues, scope, "mcp_servers", null, "INVALID_FIELD", "lower_layer_in_use", "edit");
        }
    }

    /**
     * Provider 的身份、协议、地址和至少一个模型缺失时无法安全调用，因此仅隔离该 Provider，
     * 其它 Provider 继续进入目录。
     */
    private static ObjectNode normalizeProvider(ObjectNode source, Set<String> identities,
                                                List<ConfigurationData.Issue> issues) {
        String id = typedId(source.get("provider_id"), "provider_");
        if (id == null || !identities.add(id)) {
            issue(issues, "user", "providers", id, "INVALID_PROVIDER", "entry_skipped", "edit");
            return null;
        }
        String api = text(source.get("api"));
        String baseUrl = text(source.get("base_url"));
        if (!("openai_responses".equals(api) || "anthropic_messages".equals(api)
              || "openai_chat_completions".equals(api)) || !isSafeHttpUrl(baseUrl)) {
            issue(issues, "user", "providers", id, "INVALID_PROVIDER_ROUTE", "provider_unavailable", "edit");
            return null;
        }
        copyUnknownFields(source, PROVIDER_KEYS, "user", id, issues);
        ObjectNode result = source.objectNode();
        result.put("provider_id", id);
        result.put("name", boundedText(source.get("name"), id, 512, issues, "providers", id));
        result.put("api", api);
        result.put("base_url", baseUrl);
        String credential = credentialId(source.get("credential_id"));
        if (credential == null) {
            credential = missingCredentialId(id);
            issue(issues, "user", "credential_id", id, "MISSING_CREDENTIAL", "connection_unavailable", "edit");
        }
        result.put("credential_id", credential);
        result.set("network_timeouts", normalizeNetworkTimeouts(source.get("network_timeouts"), id, issues));
        result.set("agent_defaults", normalizeAgentDefaults(source.get("agent_defaults")));
        ArrayNode models = result.putArray("models");
        Set<String> modelIds = new HashSet<>();
        JsonNode sourceModels = source.get("models");
        if (sourceModels instanceof ArrayNode entries) {
            for (int modelIndex = 0; modelIndex < entries.size(); modelIndex++) {
                if (!(entries.get(modelIndex) instanceof ObjectNode entry)) {
                    issue(issues, "user", "models", id, "INVALID_MODEL", "entry_skipped", "edit");
                    continue;
                }
                ObjectNode model = normalizeModel(entry, id, modelIds, issues, "user");
                if (model != null) {
                    models.add(model);
                }
            }
        }
        if (models.isEmpty()) {
            issue(issues, "user", "providers", id, "NO_USABLE_MODEL", "provider_unavailable", "edit");
            return null;
        }
        return result;
    }

    /**
     * 模型没有安全的上游标识时才跳过；预算、展示名和 reasoning map 均可局部回退。
     */
    private static ObjectNode normalizeModel(ObjectNode source, String providerId, Set<String> identities,
                                             List<ConfigurationData.Issue> issues, String scope) {
        String id = typedId(source.get("model_id"), "model_");
        String upstream = text(source.get("model"));
        if (id == null || !identities.add(id) || upstream == null || upstream.isBlank() || upstream.length() > 512) {
            issue(issues, scope, "models", providerId, "INVALID_MODEL", "entry_skipped", "edit");
            return null;
        }
        copyUnknownFields(source, MODEL_KEYS, scope, id, issues);
        ObjectNode result = source.objectNode();
        result.put("model_id", id);
        result.put("name", boundedText(source.get("name"), id, 512, issues, "models", id, scope));
        result.put("model", upstream);
        result.set("capabilities", normalizeCapabilities(source.get("capabilities"), id, issues, scope));
        ObjectNode reasoning = normalizeReasoningMap(source.get("reasoning_level_map"), id, issues, scope);
        result.set("reasoning_level_map", reasoning);
        JsonNode defaultReasoningValue = source.get("default_reasoning_level");
        String defaultReasoning = text(defaultReasoningValue);
        if (defaultReasoningValue == null || defaultReasoningValue.isNull()) {
            result.putNull("default_reasoning_level");
        } else if (defaultReasoning == null || !reasoning.has(defaultReasoning)) {
            if (reasoning.has("medium")) {
                result.put("default_reasoning_level", "medium");
            }
            else {
                result.putNull("default_reasoning_level");
            }
            issue(issues, scope, "default_reasoning_level", id, "INVALID_FIELD", "default_in_use", "edit");
        } else {
            result.put("default_reasoning_level", defaultReasoning);
        }
        return result;
    }

    /**
     * MCP 参数异常时保留可修复的条目并强制停用，避免宽容读取把不确定参数当成可执行配置。
     */
    private static ObjectNode normalizeMcpServer(ObjectNode source, Set<String> identities,
                                                 List<ConfigurationData.Issue> issues, String scope) {
        String id = typedId(source.get("mcp_id"), "mcp_");
        String transport = text(source.get("transport"));
        String endpoint = text(source.get("endpoint"));
        if (id == null || !identities.add(id) || !("stdio".equals(transport) || "streamable_http".equals(transport))
            || endpoint == null || endpoint.isBlank() || endpoint.length() > 4_096) {
            issue(issues, scope, "mcp_servers", id, "INVALID_MCP", "entry_skipped", "edit");
            return null;
        }
        ObjectNode auth = normalizeMcpAuth(source.get("auth"));
        if (auth == null) {
            issue(issues, scope, "mcp_servers", id, "INVALID_MCP_AUTH", "entry_skipped", "edit");
            return null;
        }
        copyUnknownFields(source, MCP_KEYS, scope, id, issues);
        ObjectNode result = source.objectNode();
        result.put("mcp_id", id);
        result.put("name", boundedText(source.get("name"), id, 512, issues, "mcp_servers", id, scope));
        result.put("transport", transport);
        result.put("endpoint", endpoint);
        JsonNode enabled = source.get("enabled");
        if (enabled != null && !enabled.isBoolean()) {
            issue(issues, scope, "enabled", id, "INVALID_FIELD", "entry_skipped", "edit");
            return null;
        }
        ArrayNode args = safeStrings(source.get("args"), 128, 4_096);
        ObjectNode environment = safeStringMap(source.get("env"), 8_192);
        ObjectNode headers = safeStringMap(source.get("headers"), 8_192);
        boolean parametersValid = true;
        if (args == null) {
            issue(issues, scope, "args", id, "INVALID_FIELD", "disabled_for_safety", "edit");
            args = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            parametersValid = false;
        }
        if (environment == null) {
            issue(issues, scope, "env", id, "INVALID_FIELD", "disabled_for_safety", "edit");
            environment = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            parametersValid = false;
        }
        if (headers == null) {
            issue(issues, scope, "headers", id, "INVALID_FIELD", "disabled_for_safety", "edit");
            headers = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            parametersValid = false;
        }
        result.set("args", args);
        result.set("env", environment);
        result.set("headers", headers);
        result.set("auth", auth);
        result.put("enabled", (enabled == null || enabled.booleanValue()) && parametersValid);
        return result;
    }

    /**
     * 逐项回退连接与流空闲上限；Turn 的绝对截止由执行生命周期单独管理。
     */
    private static ObjectNode normalizeNetworkTimeouts(JsonNode value, String entityId,
                                                       List<ConfigurationData.Issue> issues) {
        ObjectNode result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        ObjectNode source = value instanceof ObjectNode object ? object : null;
        result.put("connect_timeout_ms", positive(source == null ? null : source.get("connect_timeout_ms"),
                DEFAULT_CONNECT_TIMEOUT_MILLIS, issues, "network_timeouts", entityId, "user"));
        result.put("request_timeout_ms", positive(source == null ? null : source.get("request_timeout_ms"),
                DEFAULT_REQUEST_TIMEOUT_MILLIS, issues, "network_timeouts", entityId, "user"));
        return result;
    }

    /**
     * Agent 默认值只恢复上下文压缩开关，不从损坏配置提升权限或启用额外目录。
     */
    private static ObjectNode normalizeAgentDefaults(JsonNode value) {
        ObjectNode result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        ObjectNode source = value instanceof ObjectNode object ? object : null;
        ObjectNode context = result.putObject("context");
        JsonNode compact = source == null || !(source.get("context") instanceof ObjectNode sourceContext)
                ? null : sourceContext.get("auto_compact");
        context.put("auto_compact", compact == null || !compact.isBoolean()
                ? ConfigurationDocumentFactory.DEFAULT_AUTO_COMPACT : compact.booleanValue());
        return result;
    }

    /**
     * 同一模型能力字段彼此校验，必要时回退到已验证的默认组合。
     */
    private static ObjectNode normalizeCapabilities(JsonNode value, String entityId,
                                                    List<ConfigurationData.Issue> issues, String scope) {
        ObjectNode result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        ObjectNode source = value instanceof ObjectNode object ? object : null;
        long context = positive(source == null ? null : source.get("context_window_tokens"),
                DEFAULT_CONTEXT_WINDOW_TOKENS, issues, "capabilities", entityId, scope);
        long output = positive(source == null ? null : source.get("max_output_tokens"),
                DEFAULT_MAX_OUTPUT_TOKENS, issues, "capabilities", entityId, scope);
        if (output >= context) {
            context = DEFAULT_CONTEXT_WINDOW_TOKENS;
            output = DEFAULT_MAX_OUTPUT_TOKENS;
            issue(issues, scope, "capabilities", entityId, "INVALID_FIELD", "default_in_use", "edit");
        }
        result.put("context_window_tokens", context);
        result.put("max_output_tokens", output);
        JsonNode outputSchema = source == null ? null : source.get("supports_output_schema");
        if (outputSchema == null) {
            result.put("supports_output_schema", false);
        } else if (outputSchema.isBoolean()) {
            result.put("supports_output_schema", outputSchema.booleanValue());
        } else {
            result.put("supports_output_schema", false);
            issue(issues, scope, "capabilities", entityId, "INVALID_FIELD", "default_in_use", "edit");
        }
        return result;
    }

    /**
     * reasoning map 只保留已知逻辑档位；空或错误 map 回退为 medium，避免下游转换失败。
     */
    private static ObjectNode normalizeReasoningMap(JsonNode value, String entityId,
                                                    List<ConfigurationData.Issue> issues, String scope) {
        ObjectNode result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        result.removeAll();
        if (value instanceof ObjectNode source) {
            source.properties().forEach(entry -> {
                String upstream = text(entry.getValue());
                if (REASONING_LEVELS.contains(entry.getKey()) && upstream != null && !upstream.isBlank()
                    && upstream.length() <= 128) {
                    result.put(entry.getKey(), upstream);
                }
            });
            // 空表是当前配置格式中“模型没有可选推理档位”的合法表达；只有带无效内容的表才
            // 需要回退 medium 并提示用户，不能把正常的非 reasoning 模型渲染成配置问题。
            if (source.isEmpty()) {
                return result;
            }
        }
        if (result.isEmpty()) {
            result.put("medium", "medium");
            issue(issues, scope, "reasoning_level_map", entityId, "INVALID_FIELD", "default_in_use", "edit");
        }
        return result;
    }

    /**
     * 停用名单只接受所属来源身份；旧启用字段作为未知字段报告，不能反向解释为停用。
     */
    private static ArrayNode normalizeSkillReferences(JsonNode value, ConfigurationScope scope,
                                                      List<ConfigurationData.Issue> issues) {
        ArrayNode result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        if (value == null) {
            return result;
        }
        if (!(value instanceof ArrayNode entries)) {
            issue(issues, scope.name().toLowerCase(java.util.Locale.ROOT), "disabled_skills",
                    null, "INVALID_FIELD", "default_in_use", "edit");
            return result;
        }
        Set<String> identities = new HashSet<>();
        for (JsonNode entry : entries) {
            String reference = text(entry);
            try {
                if (reference == null) {
                    throw new IllegalArgumentException("invalid skill reference");
                }
                SkillReference parsed = SkillReference.parse(reference);
                boolean allowed = scope == ConfigurationScope.PROJECT
                                  || parsed.source() == SkillReference.Source.USER || parsed.source() == SkillReference.Source.JA;
                if (!allowed || !identities.add(reference)) {
                    throw new IllegalArgumentException();
                }
                result.add(reference);
            } catch (IllegalArgumentException invalid) {
                issue(issues, scope.name().toLowerCase(java.util.Locale.ROOT),
                        "disabled_skills", null, "INVALID_ENTRY", "entry_skipped", "edit");
            }
        }
        return result;
    }

    /**
     * 用户根默认项必须形成现有 Provider/Model 的成对引用，否则留空并由 Composer 请求选择。
     */
    private static void normalizeDefaultSelection(ObjectNode result, ObjectNode source,
                                                  List<ConfigurationData.Issue> issues) {
        String providerId = text(source.get("default_provider_id"));
        String modelId = text(source.get("default_model_id"));
        ObjectNode provider = providerById(result.withArray("providers"), providerId);
        if (provider == null || modelById(provider.withArray("models"), modelId) == null) {
            result.putNull("default_provider_id");
            result.putNull("default_model_id");
            result.putNull("default_reasoning_level");
            if (providerId != null || modelId != null) {
                issue(issues, "user", "default_model_id", null, "DEFAULT_SELECTION_UNAVAILABLE",
                        "selection_required", "edit");
            }
            return;
        }
        result.put("default_provider_id", providerId);
        result.put("default_model_id", modelId);
        String reasoning = text(source.get("default_reasoning_level"));
        ObjectNode model = modelById(provider.withArray("models"), modelId);
        if (reasoning != null && model.with("reasoning_level_map").has(reasoning)) {
            result.put("default_reasoning_level", reasoning);
        } else {
            result.putNull("default_reasoning_level");
            if (reasoning != null) {
                issue(issues, "user", "default_reasoning_level", modelId, "INVALID_FIELD", "default_in_use", "edit");
            }
        }
    }

    /**
     * 失效的子智能体引用保守关闭，主对话可继续，且不会借默认模型隐式扩大委派能力。
     */
    private static void normalizeSubagentSelection(ObjectNode result, JsonNode source,
                                                   List<ConfigurationData.Issue> issues) {
        ObjectNode normalized = result.with("subagents");
        if (!(source instanceof ObjectNode original)) {
            return;
        }
        JsonNode enabled = original.get("enabled");
        String providerId = text(original.get("provider_id"));
        String modelId = text(original.get("model_id"));
        // null/null 是“跟随父任务”的正常配置，不要求用户写 Provider/Model 引用。TomlCodec 会将
        // 历史的 __ja_null 还原为 NullNode，因此这里同时接受显式 null 和省略字段。
        if (enabled != null && enabled.isBoolean() && enabled.booleanValue()
            && optionalNull(original.get("provider_id")) && optionalNull(original.get("model_id"))
            && optionalNull(original.get("reasoning_level"))) {
            normalized.put("enabled", true);
            normalized.putNull("provider_id");
            normalized.putNull("model_id");
            normalized.putNull("reasoning_level");
            return;
        }
        ObjectNode provider = providerById(result.withArray("providers"), providerId);
        ObjectNode model = provider == null ? null : modelById(provider.withArray("models"), modelId);
        if (enabled != null && enabled.isBoolean() && enabled.booleanValue() && provider != null && model != null) {
            normalized.put("enabled", true);
            normalized.put("provider_id", providerId);
            normalized.put("model_id", modelId);
            String reasoning = text(original.get("reasoning_level"));
            if (reasoning != null && model.with("reasoning_level_map").has(reasoning)) {
                normalized.put("reasoning_level", reasoning);
            }
            return;
        }
        normalized.put("enabled", false);
        normalized.putNull("provider_id");
        normalized.putNull("model_id");
        normalized.putNull("reasoning_level");
        if (enabled != null || providerId != null || modelId != null) {
            issue(issues, "user", "subagents", null, "INVALID_SUBAGENT_SELECTION", "disabled_for_safety", "edit");
        }
    }

    /**
     * MCP auth 只有完整的显式形状才可进入运行时，避免把错误凭据配置解释成匿名连接。
     */
    private static ObjectNode normalizeMcpAuth(JsonNode value) {
        if (!(value instanceof ObjectNode source)) {
            return null;
        }
        String kind = text(source.get("kind"));
        ObjectNode result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        if ("none".equals(kind)) {
            return result.put("kind", "none");
        }
        String credential = credentialId(source.get("credential_id"));
        if ("bearer".equals(kind) && credential != null) {
            return result.put("kind", kind).put("credential_id", credential);
        }
        String name = text(source.get("name"));
        if (("env".equals(kind) || "header".equals(kind)) && credential != null && name != null
            && !name.isBlank() && name.length() <= 128) {
            return result.put("kind", kind).put("name", name).put("credential_id", credential);
        }
        return null;
    }

    /**
     * interaction 缺失或异常保持默认开启，避免解析错误造成不可恢复的对话交互锁死。
     */
    private static ObjectNode normalizeInteraction(JsonNode value, List<ConfigurationData.Issue> issues) {
        ObjectNode result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        JsonNode enabled = value instanceof ObjectNode source ? source.get("clarification_enabled") : null;
        result.removeAll();
        result.put("clarification_enabled", enabled == null || !enabled.isBoolean()
                ? ConfigurationDocumentFactory.DEFAULT_CLARIFICATION_ENABLED : enabled.booleanValue());
        if (enabled != null && !enabled.isBoolean()) {
            issue(issues, "user", "interaction", null, "INVALID_FIELD", "default_in_use", "edit");
        }
        return result;
    }

    /**
     * 子智能体在无法验证完整选择时预设为关闭，随后在 Provider/Model 投影完成后重新解算。
     */
    private static ObjectNode normalizeSubagents() {
        ObjectNode result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        result.removeAll();
        result.put("enabled", ConfigurationDocumentFactory.DEFAULT_SUBAGENTS_ENABLED);
        result.putNull("provider_id");
        result.putNull("model_id");
        result.putNull("reasoning_level");
        return result;
    }

    /**
     * 仅复制合法文本数组；缺失时采用空数组，格式错误时返回 null 供调用方定位并停用对应服务。
     */
    private static ArrayNode safeStrings(JsonNode value, int maxItems, int maxLength) {
        ArrayNode result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        if (value == null) {
            return result;
        }
        if (!(value instanceof ArrayNode source) || source.size() > maxItems) {
            return null;
        }
        for (JsonNode entry : source) {
            String text = text(entry);
            if (text == null || text.length() > maxLength)
                {
                    return null;
                }
            result.add(text);
        }
        return result;
    }

    /**
     * 环境与 header map 缺失时采用空 map；复用严格策略的边界，避免坏 MCP 令最终校验否定整层配置。
     */
    private static ObjectNode safeStringMap(JsonNode value, int maxValueLength) {
        ObjectNode result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        if (value == null) {
            return result;
        }
        if (!(value instanceof ObjectNode source) || source.size() > 64) {
            return null;
        }
        for (Map.Entry<String, JsonNode> entry : source.properties()) {
            String key = entry.getKey();
            String text = text(entry.getValue());
            if (key.isEmpty() || key.length() > 128 || text == null || text.length() > maxValueLength) {
                return null;
            }
            result.put(key, text);
        }
        return result;
    }

    /**
     * 有效根 revision 可直接采用；缺失或错误 revision 只影响可读顺序，不否定整层设置。
     */
    private static long positiveRevision(JsonNode value, List<ConfigurationData.Issue> issues, String scope,
                                         String field) {
        if (value != null && value.isIntegralNumber() && value.longValue() >= 0) {
            return value.longValue();
        }
        if (value != null) {
            issue(issues, scope, field, null, "INVALID_FIELD", "default_in_use", "edit");
        }
        return 0L;
    }

    /**
     * 预算必须是正整数，错误值统一使用已验证的有限默认值。
     */
    private static long positive(JsonNode value, long fallback, List<ConfigurationData.Issue> issues,
                                 String field, String entityId, String scope) {
        if (value != null && value.isIntegralNumber() && value.longValue() > 0) {
            return value.longValue();
        }
        if (value != null) {
            issue(issues, scope, field, entityId, "INVALID_FIELD", "default_in_use", "edit");
        }
        return fallback;
    }

    /**
     * Provider/MCP/Model 身份只接受当前 typed-id 形状，避免将坏条目引入强类型 catalog。
     */
    private static String typedId(JsonNode value, String prefix) {
        String id = text(value);
        return id != null && id.matches(prefix + "[A-Za-z0-9][A-Za-z0-9._-]{0,95}") ? id : null;
    }

    /**
     * 缺失 credential 用不可碰撞的占位引用保留模型可见性；连接 admission 仍会报告未配置。
     */
    private static String missingCredentialId(String providerId) {
        return "cred_missing_" + Integer.toUnsignedString(providerId.hashCode(), 36);
    }

    /**
     * 读取时复用与严格路径等价的 HTTP(S) 限制，不接受 URL 内嵌凭据或 query/fragment。
     */
    private static boolean isSafeHttpUrl(String value) {
        if (value == null) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return uri.isAbsolute() && uri.getHost() != null && !uri.getHost().isBlank()
                   && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                   && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /**
     * 文本字段错误回退到同条目的稳定 ID，UI 仍可展示并让用户就地修复。
     */
    private static String boundedText(JsonNode value, String fallback, int maxLength,
                                      List<ConfigurationData.Issue> issues, String field, String entityId) {
        return boundedText(value, fallback, maxLength, issues, field, entityId, "user");
    }

    /**
     * 项目 MCP 的文本修复必须保留项目来源，否则设置页会把问题指向全局服务。
     */
    private static String boundedText(JsonNode value, String fallback, int maxLength,
                                      List<ConfigurationData.Issue> issues, String field, String entityId,
                                      String scope) {
        String text = text(value);
        if (text != null && !text.isBlank() && text.length() <= maxLength) {
            return text;
        }
        if (value != null) {
            issue(issues, scope, field, entityId, "INVALID_FIELD", "default_in_use", "edit");
        }
        return fallback;
    }

    /**
     * credential 仅验证公开引用形状；Secret 永远不从普通配置读取或写入有效投影。
     */
    private static String credentialId(JsonNode value) {
        String id = text(value);
        return id != null && id.matches("cred_[A-Za-z0-9][A-Za-z0-9._-]{0,95}") ? id : null;
    }

    /**
     * 只将 JSON 文本节点转换为字符串，所有其它节点均保持显式无效。
     */
    private static String text(JsonNode value) {
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    /**
     * 可空选择项省略与显式 null 具有相同语义，避免旧 TOML 编码结果被当作不安全引用。
     */
    private static boolean optionalNull(JsonNode value) {
        return value == null || value.isNull();
    }

    /**
     * 默认引用查找只在已保留的配置目录中进行，绝不从源文件中猜测已被隔离条目。
     */
    private static ObjectNode providerById(ArrayNode providers, String id) {
        if (id == null) {
            return null;
        }
        for (JsonNode provider : providers) {
            if (provider instanceof ObjectNode object && id.equals(text(object.get("provider_id")))) {
                return object;
            }
        }
        return null;
    }

    /**
     * Model 查找被 Provider 身份约束，防止同名模型跨服务商意外成为默认项。
     */
    private static ObjectNode modelById(ArrayNode models, String id) {
        if (id == null) {
            return null;
        }
        for (JsonNode model : models) {
            if (model instanceof ObjectNode object && id.equals(text(object.get("model_id")))) {
                return object;
            }
        }
        return null;
    }

    /**
     * 未知字段不参与运行时，但保留源文件；问题只返回字段名和作用域，不返回原文或值。
     */
    private static void copyUnknownRootFields(ObjectNode source, Set<String> known, String scope,
                                              List<ConfigurationData.Issue> issues) {
        copyUnknownFields(source, known, scope, null, issues);
    }

    /**
     * 对实体内部未知字段发出独立问题，避免一个未来字段升级为整个条目或文档失败。
     */
    private static void copyUnknownFields(ObjectNode source, Set<String> known, String scope, String entityId,
                                          List<ConfigurationData.Issue> issues) {
        source.fieldNames().forEachRemaining(field -> {
            if (!known.contains(field)) {
                issue(issues, scope, field, entityId,
                    "UNKNOWN_FIELD", "ignored", "edit");
            }
        });
    }

    /**
     * 将所有问题收敛为固定、脱敏的结构，允许 UI 根据 scope/field/entity 就地给出动作。
     */
    private static void issue(List<ConfigurationData.Issue> issues, String scope, String field, String entityId,
                              String reason, String impact, String action) {
        String identityField = (field == null ? "document" : field)
                .replaceAll("[^A-Za-z0-9_-]", "_");
        if (identityField.length() > 40) {
            identityField = identityField.substring(0, 40);
        }
        String identifier = "cfg_" + scope + '_' + identityField
                            + '_' + issues.size();
        issues.add(new ConfigurationData.Issue(identifier, scope, field, entityId, null, null,
                reason, impact, List.of(action)));
    }

    /**
     * 宽容投影和结构化问题必须作为同一不可变读取结果交付，避免 source 与 diagnostics 脱节。
     *
     * @param document 文档
     * @param issues 问题列表
     */
    record Result(ObjectNode document,
                  List<ConfigurationData.Issue> issues) {
        /**
         * 防御性复制有效文档，确保 generation 缓存无法反向修改读取结果。
         */
        Result {
            document = document.deepCopy();
            issues = List.copyOf(issues);
        }
    }
}
