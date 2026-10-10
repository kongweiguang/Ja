// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.infrastructure.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationError;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 配置 v2 的来源限定 Skill 停用与可信项目 MCP 策略测试。 */
final class ConfigurationPolicyV2Test {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 用户层停用名单只接受 user/ja，未列出的发现项由运行时默认开启。 */
    @Test
    void userSkillsRequireGlobalSourceReferences() {
        ObjectNode user = userDocument("user:review", "ja:coding");

        ConfigurationPolicy.validateDocument(user, ConfigurationScope.USER);

        user.withArray("disabled_skills").add("project:review");
        ConfigurationError failure = assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.validateDocument(user, ConfigurationScope.USER));
        assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, failure.code());
    }

    /** 可信项目可覆盖普通权限策略，但连接三元组必须完整。 */
    @Test
    void projectDocumentCanOwnOrdinaryDefaults() {
        ObjectNode project = projectDocument("project:review");

        ConfigurationPolicy.validateDocument(project, ConfigurationScope.PROJECT);

        project.put("default_access_mode", "full_access");
        ConfigurationPolicy.validateDocument(project, ConfigurationScope.PROJECT);
        project.putArray("providers").addObject().put("provider_id", "provider_one")
                .put("base_url", "https://example.test").put("credential_id", "cred_one");
        ConfigurationError failure = assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.validateDocument(project, ConfigurationScope.PROJECT));
        assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, failure.code());
    }

    /** 项目 Provider 路由整体覆盖同 ID 全局连接，避免协议、地址与凭据引用错位混搭。 */
    @Test
    void projectProviderConnectionOverridesAsOneStableIdentityGroup() {
        ObjectNode user = userDocument();
        ObjectNode provider = user.withArray("providers").addObject();
        provider.put("provider_id", "provider_one").put("name", "Global")
                .put("api", "openai_responses").put("base_url", "https://global.example/v1")
                .put("credential_id", "cred_global");
        provider.putObject("network_timeouts").put("connect_timeout_ms", 10_000)
                .put("request_timeout_ms", 120_000);
        provider.putObject("agent_defaults").putObject("context").put("auto_compact", true);
        ObjectNode model = provider.putArray("models").addObject();
        model.put("model_id", "model_one").put("name", "Model").put("model", "global-model");
        model.putObject("capabilities").put("context_window_tokens", 128_000)
                .put("max_output_tokens", 8_192);
        model.putObject("reasoning_level_map").put("medium", "medium");
        model.put("default_reasoning_level", "medium");
        user.put("default_provider_id", "provider_one");
        user.put("default_model_id", "model_one");
        user.put("default_reasoning_level", "medium");

        ObjectNode project = projectDocument();
        project.withArray("providers").addObject().put("provider_id", "provider_one")
                .put("api", "anthropic_messages").put("base_url", "https://project.example/v1")
                .put("credential_id", "cred_project");

        ConfigurationPolicy.validateDocument(user, ConfigurationScope.USER);
        ConfigurationPolicy.validateDocument(project, ConfigurationScope.PROJECT);
        ObjectNode effective = ConfigurationPolicy.mergeDocuments(user, project);
        ObjectNode providerView = (ObjectNode) effective.withArray("providers").get(0);
        assertEquals("anthropic_messages", providerView.path("api").textValue());
        assertEquals("https://project.example/v1", providerView.path("base_url").textValue());
        assertEquals("cred_project", providerView.path("credential_id").textValue());

        ObjectNode incomplete = projectDocument();
        incomplete.withArray("providers").addObject().put("provider_id", "provider_one")
                .put("base_url", "https://project.example/v1");
        assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.validateDocument(incomplete, ConfigurationScope.PROJECT));
    }

    /** 项目模型目录按 Provider/Model 稳定 ID 更新与追加，并用显式删除 ID 屏蔽单个全局模型。 */
    @Test
    void projectModelsMergeByStableIdentity() {
        ObjectNode user = userDocument();
        ObjectNode provider = user.withArray("providers").addObject();
        provider.put("provider_id", "provider_one").put("name", "Global")
                .put("api", "openai_responses").put("base_url", "https://global.example/v1")
                .put("credential_id", "cred_global");
        provider.putObject("network_timeouts").put("connect_timeout_ms", 10_000)
                .put("request_timeout_ms", 120_000);
        provider.putObject("agent_defaults").putObject("context").put("auto_compact", true);
        ArrayNode baseModels = provider.putArray("models");
        model(baseModels, "model_one", "global-one");
        model(baseModels, "model_removed", "global-removed");
        user.put("default_provider_id", "provider_one");
        user.put("default_model_id", "model_one");
        user.putNull("default_reasoning_level");

        ObjectNode project = projectDocument();
        ObjectNode providerOverride = project.withArray("providers").addObject()
                .put("provider_id", "provider_one");
        ArrayNode modelOverrides = providerOverride.putArray("models");
        model(modelOverrides, "model_one", "project-one");
        model(modelOverrides, "model_project", "project-only");
        providerOverride.putArray("removed_model_ids").add("model_removed");

        ConfigurationPolicy.validateDocument(user, ConfigurationScope.USER);
        ConfigurationPolicy.validateDocument(project, ConfigurationScope.PROJECT);
        ObjectNode effective = ConfigurationPolicy.mergeDocuments(user, project);
        ArrayNode models = (ArrayNode) effective.withArray("providers").get(0).get("models");
        assertEquals(2, models.size());
        assertEquals("project-one", models.get(0).path("model").textValue());
        assertEquals("model_project", models.get(1).path("model_id").textValue());
    }

    /** Project MCP 同 ID 替换全局定义，不同 ID 追加，均由稳定身份匹配。 */
    @Test
    void projectMcpMergesByIdentityAndAllowsExplicitReplacement() {
        ObjectNode user = userDocument();
        ObjectNode project = projectDocument("project:review");
        mcp(user, "mcp_global", "Kerminal");
        mcp(project, "mcp_project", "Kerminal");

        ConfigurationPolicy.validateDocument(project, ConfigurationScope.PROJECT);
        ConfigurationPolicy.enforceNoEscalation(user, project);
        ObjectNode effective = ConfigurationPolicy.mergeDocuments(user, project);
        assertEquals(2, effective.withArray("mcp_servers").size());
        assertEquals("mcp_project", effective.withArray("mcp_servers").get(1).path("mcp_id").textValue());

        ObjectNode replacement = projectDocument("project:review");
        mcp(replacement, "mcp_global", "Project Kerminal");
        ObjectNode replaced = ConfigurationPolicy.mergeDocuments(user, replacement);
        assertEquals(1, replaced.withArray("mcp_servers").size());
        assertEquals("Project Kerminal", replaced.withArray("mcp_servers").get(0).path("name").textValue());
    }

    /** 合法省略 enabled 采用默认开启，非法类型只让对应服务从执行投影消失。 */
    @Test
    void mcpEnabledDefaultsTrueButInvalidTypeIsIsolated() {
        ObjectNode user = userDocument();
        mcp(user, "mcp_good", "Good");
        mcp(user, "mcp_bad", "Bad");
        ((ObjectNode) user.withArray("mcp_servers").get(0)).remove("enabled");
        ((ObjectNode) user.withArray("mcp_servers").get(1)).put("enabled", "yes");

        TolerantConfigurationDocumentReader.Result normalized =
                TolerantConfigurationDocumentReader.normalize(user, ConfigurationScope.USER);
        assertEquals(1, normalized.document().withArray("mcp_servers").size());
        assertEquals(true, normalized.document().withArray("mcp_servers").get(0).path("enabled").booleanValue());
    }

    /** 严格配置拒绝错误 Schema 能力类型，宽容读取报告问题并关闭该能力。 */
    @Test
    void malformedOutputSchemaCapabilityIsRejectedOrDisabledByReadMode() {
        ObjectNode user = userDocument();
        ObjectNode model = providerWithModel(user, "model_schema");
        model.with("capabilities").put("supports_output_schema", "true");

        ConfigurationError failure = assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.validateDocument(user, ConfigurationScope.USER));
        assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, failure.code());

        TolerantConfigurationDocumentReader.Result normalized =
                TolerantConfigurationDocumentReader.normalize(user, ConfigurationScope.USER);
        ObjectNode normalizedModel = (ObjectNode) normalized.document().withArray("providers")
                .get(0).withArray("models").get(0);
        assertFalse(normalizedModel.path("capabilities").path("supports_output_schema").booleanValue());
        assertTrue(normalized.issues().stream().anyMatch(issue -> "capabilities".equals(issue.field())
                && "model_schema".equals(issue.entityId()) && "INVALID_FIELD".equals(issue.reason())));
    }

    /** 先在读取侧隔离坏 MCP map，避免严格完整性门槛连带屏蔽同层的有效连接。 */
    @Test
    void invalidMcpStringMapsAreIsolatedWithoutLosingUsableEntries() {
        ObjectNode user = userDocument();
        ObjectNode provider = user.withArray("providers").addObject();
        provider.put("provider_id", "provider_valid").put("name", "Valid")
                .put("api", "openai_responses").put("base_url", "https://example.test")
                .put("credential_id", "cred_valid");
        provider.putObject("network_timeouts").put("connect_timeout_ms", 10_000)
                .put("request_timeout_ms", 120_000);
        provider.putObject("agent_defaults").putObject("context").put("auto_compact", true);
        model(provider.putArray("models"), "model_valid", "upstream-model");
        user.put("default_provider_id", "provider_valid");
        user.put("default_model_id", "model_valid");
        user.put("default_reasoning_level", "medium");

        mcp(user, "mcp_valid", "Valid MCP");
        mcp(user, "mcp_too_many", "Too Many Entries");
        mcp(user, "mcp_empty_key", "Empty Key");
        mcp(user, "mcp_long_key", "Long Key");
        ArrayNode servers = user.withArray("mcp_servers");
        ObjectNode tooMany = (ObjectNode) servers.get(1);
        for (int index = 0; index < 65; index++) {
            tooMany.with("env").put("ENV_" + index, "value");
        }
        ((ObjectNode) servers.get(2).get("env")).put("", "value");
        ((ObjectNode) servers.get(3).get("headers")).put("x".repeat(129), "value");

        TolerantConfigurationDocumentReader.Result normalized =
                TolerantConfigurationDocumentReader.normalize(user, ConfigurationScope.USER);
        ArrayNode normalizedServers = normalized.document().withArray("mcp_servers");

        assertEquals(1, normalized.document().withArray("providers").size());
        assertEquals(4, normalizedServers.size());
        assertTrue(normalizedServers.get(0).path("enabled").booleanValue());
        assertFalse(normalizedServers.get(1).path("enabled").booleanValue());
        assertFalse(normalizedServers.get(2).path("enabled").booleanValue());
        assertFalse(normalizedServers.get(3).path("enabled").booleanValue());
        assertEquals(0, normalizedServers.get(1).path("env").size());
        assertEquals(0, normalizedServers.get(2).path("env").size());
        assertEquals(0, normalizedServers.get(3).path("headers").size());
        assertEquals(3, normalized.issues().stream()
                .filter(issue -> "env".equals(issue.field()) || "headers".equals(issue.field()))
                .count());
    }

    /** 测试服务使用规范完整定义，避免策略测试靠缺字段误报冲突。 */
    private static void mcp(ObjectNode document, String id, String name) {
        ObjectNode server = document.withArray("mcp_servers").addObject();
        server.put("mcp_id", id).put("name", name).put("transport", "stdio")
                .put("endpoint", "node").put("enabled", true);
        server.putArray("args");
        server.putObject("env");
        server.putObject("headers");
        server.putObject("auth").put("kind", "none");
    }

    /** 完整模型 fixture 使用固定能力值，使断言只针对稳定 ID 合并而非默认归一化。 */
    private static ObjectNode model(ArrayNode models, String id, String upstream) {
        ObjectNode model = models.addObject();
        model.put("model_id", id).put("name", id).put("model", upstream);
        model.putObject("capabilities").put("context_window_tokens", 128_000)
                .put("max_output_tokens", 8_192);
        model.putObject("reasoning_level_map").put("medium", "medium");
        model.put("default_reasoning_level", "medium");
        return model;
    }

    /** 构造严格有效的 Provider 与模型，令测试只改变指定的能力字段。 */
    private static ObjectNode providerWithModel(ObjectNode document, String modelId) {
        ObjectNode provider = document.withArray("providers").addObject();
        provider.put("provider_id", "provider_fixture").put("name", "Fixture")
                .put("api", "openai_responses").put("base_url", "https://example.test")
                .put("credential_id", "cred_fixture");
        provider.putObject("network_timeouts").put("connect_timeout_ms", 10_000)
                .put("request_timeout_ms", 120_000);
        provider.putObject("agent_defaults").putObject("context").put("auto_compact", true);
        ObjectNode model = model(provider.putArray("models"), modelId, "gpt-5-test");
        document.put("default_provider_id", "provider_fixture");
        document.put("default_model_id", modelId);
        document.put("default_reasoning_level", "medium");
        return model;
    }

    /** 项目停用清单可显式引用任何 Skill 来源，供项目策略完整覆盖用户停用清单。 */
    @Test
    void projectDisabledSkillsCanTargetGlobalSources() {
        ObjectNode project = projectDocument("project:review");
        project.withArray("disabled_skills").add("user:global");
        project.withArray("disabled_skills").add("ja:missing");
        ConfigurationPolicy.validateDocument(project, ConfigurationScope.PROJECT);
    }

    /** 配置名称使用跨端固定边界；项目继承全局来源，但用户配置不能指向项目 Skill。 */
    @Test
    void disabledSkillReferencesUseCanonicalNamesAndScopeSources() {
        String maxName = "a".repeat(256);
        ObjectNode user = userDocument("user:" + maxName, "ja:" + maxName);
        ObjectNode project = projectDocument("user:" + maxName, "ja:" + maxName, "project:" + maxName);
        ConfigurationPolicy.validateDocument(user, ConfigurationScope.USER);
        ConfigurationPolicy.validateDocument(project, ConfigurationScope.PROJECT);

        for (String invalidReference : List.of(
                "user:天气", "user:Upper", "user:runtime_refresh", "user:" + "a".repeat(257))) {
            assertThrows(ConfigurationError.class, () -> ConfigurationPolicy.validateDocument(
                    userDocument(invalidReference), ConfigurationScope.USER), invalidReference);
            assertThrows(ConfigurationError.class, () -> ConfigurationPolicy.validateDocument(
                    projectDocument(invalidReference), ConfigurationScope.PROJECT), invalidReference);
        }
        assertThrows(ConfigurationError.class, () -> ConfigurationPolicy.validateDocument(
                userDocument("project:local"), ConfigurationScope.USER));
    }

    /** 项目显式 Skill 列表替换全局列表，空列表也能清除继承的停用项。 */
    @Test
    void effectiveDisabledSkillsUseExplicitProjectList() {
        ObjectNode effective = ConfigurationPolicy.mergeDocuments(
                userDocument("user:review", "ja:coding"),
                projectDocument("project:review"));

        assertEquals(java.util.List.of("project:review"), skillReferences(effective));
        ObjectNode cleared = ConfigurationPolicy.mergeDocuments(
                userDocument("user:review", "ja:coding"), projectDocument());
        assertEquals(java.util.List.of(), skillReferences(cleared));
    }

    /** 旧对象数组和旧 schema 没有兼容路径，必须进入配置恢复而不是被静默改写。 */
    @Test
    void legacySkillConfigurationIsRejected() {
        ObjectNode user = userDocument();
        user.put("schema_version", 1);
        user.withArray("skills").addObject().put("skill_id", "skill_review");

        ConfigurationError failure = assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.validateDocument(user, ConfigurationScope.USER));
        assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, failure.code());
    }

    /** 宽容读取旧启用名单只报告问题，绝不能把旧名单反向解释成停用授权。 */
    @Test
    void legacyEnabledListDoesNotBecomeDisabledList() {
        ObjectNode user = userDocument();
        user.putArray("skills").add("user:review");

        TolerantConfigurationDocumentReader.Result read =
                TolerantConfigurationDocumentReader.normalize(user, ConfigurationScope.USER);
        assertEquals(0, read.document().withArray("disabled_skills").size());
        assertEquals(true, read.issues().stream().anyMatch(issue -> "skills".equals(issue.field())));
    }

    /** 最小用户文档保留 Provider/MCP 的现有严格 owner，而测试只暴露关联的 Skill 字段。 */
    private static ObjectNode userDocument(String... disabled) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema_version", 2);
        root.put("config_revision", 1);
        root.put("default_access_mode", "approval_required");
        root.putNull("default_provider_id");
        root.putNull("default_model_id");
        root.putNull("default_reasoning_level");
        root.putObject("subagents").put("enabled", true).putNull("provider_id").putNull("model_id")
                .putNull("reasoning_level");
        root.putArray("providers");
        root.putArray("mcp_servers");
        ArrayNode values = root.putArray("disabled_skills");
        for (String skill : disabled) {
            values.add(skill);
        }
        return root;
    }

    /** 项目文档只记录本来源的停用引用。 */
    private static ObjectNode projectDocument(String... disabled) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema_version", 2);
        root.put("config_revision", 1);
        ArrayNode values = root.putArray("disabled_skills");
        for (String skill : disabled) {
            values.add(skill);
        }
        return root;
    }

    /** 只读取引用字符串，使断言不依赖 TOML 格式或可能变化的发现元数据。 */
    private static java.util.List<String> skillReferences(ObjectNode document) {
        return document.withArray("disabled_skills").valueStream().map(node -> node.textValue()).toList();
    }
}
