// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationError;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 配置 v1 Provider/Model 与项目 overlay 收紧策略测试。 */
final class ConfigurationPolicyV1Test {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 合法项目 overlay 可以选择用户模型并收紧 Token、模态和思考档位。 */
    @Test
    void projectOverlayMayReferenceAndTightenProviderModel() {
        ObjectNode user = userDocument();
        ObjectNode project = projectOverlay();

        ConfigurationPolicy.enforceNoEscalation(user, project);
        ObjectNode effective = ConfigurationPolicy.mergeDocuments(user, project);
        ConfigurationPolicy.validateDocument(effective, ConfigurationScope.USER);

        ObjectNode model = (ObjectNode) effective.withArray("providers").get(0)
                .withArray("models").get(0);
        assertEquals(64_000, model.path("capabilities").path("context_window_tokens").longValue());
        assertEquals(2, model.path("reasoning_level_map").size());
    }

    /** 项目层不得改写 Provider API、base URL 或 credential 路由。 */
    @Test
    void projectOverlayCannotChangeProviderRoute() {
        ObjectNode project = projectOverlay();
        ((ObjectNode) project.withArray("providers").get(0))
                .put("base_url", "https://proxy.example.com/v1");

        ConfigurationError failure = assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.enforceNoEscalation(userDocument(), project));

        assertEquals(ConfigurationError.Code.LIMIT_ESCALATION, failure.code());
    }

    /** 项目模型不能增加用户模型未声明的 reasoning 档位。 */
    @Test
    void projectOverlayCannotExpandModelCapabilities() {
        ObjectNode project = projectOverlay();
        ObjectNode model = (ObjectNode) project.withArray("providers").get(0)
                .withArray("models").get(0);
        model.withObject("reasoning_level_map").put("high", "high");

        ConfigurationError failure = assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.enforceNoEscalation(userDocument(), project));

        assertEquals(ConfigurationError.Code.LIMIT_ESCALATION, failure.code());
    }

    /** 根默认 reasoning 必须属于选中模型能力集合。 */
    @Test
    void rootReasoningMustBelongToSelectedModel() {
        ObjectNode document = userDocument();
        document.put("default_reasoning_level", "high");

        ConfigurationError failure = assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.validateDocument(document, ConfigurationScope.USER));

        assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, failure.code());
    }

    /** 用户自定义名称不参与路由，三种当前 API 对每个 Provider 都平级可选。 */
    @Test
    void currentApisAreAcceptedForCustomProviders() {
        for (String api : java.util.List.of(
                "anthropic_messages", "openai_responses", "openai_chat_completions")) {
            ObjectNode document = userDocument();
            ObjectNode provider = (ObjectNode) document.withArray("providers").get(0);
            provider.put("name", "DeepSeek");
            provider.put("api", api);
            ConfigurationPolicy.validateDocument(document, ConfigurationScope.USER);
        }
    }

    /** Provider 对象拒绝闭集外字段，路由只能来自当前 API 规范。 */
    @Test
    void providerRejectsUnknownField() {
        ObjectNode document = userDocument();
        ObjectNode provider = (ObjectNode) document.withArray("providers").get(0);
        provider.put("unexpected", true);

        ConfigurationError failure = assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.validateDocument(document, ConfigurationScope.USER));

        assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, failure.code());
    }

    /** 配置版本只接受当前 v1，其它版本不能进入字段级兼容或转换路径。 */
    @Test
    void unsupportedSchemaVersionIsRejected() {
        ObjectNode document = userDocument();
        document.put("schema_version", 2);

        ConfigurationError failure = assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.validateDocument(document, ConfigurationScope.USER));

        assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, failure.code());
    }

    /** 完整 user 文档缺失当前必填字段时必须失败，不能由 Policy 或 generation 自动补齐。 */
    @Test
    void userDocumentRejectsMissingCurrentField() {
        ObjectNode document = userDocument();
        document.remove("default_reasoning_level");

        ConfigurationError failure = assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.validateDocument(document, ConfigurationScope.USER));

        assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, failure.code());
    }

    /** Provider 的独立凭据引用是当前结构必填项，缺失时不得退化成共享或匿名凭据。 */
    @Test
    void providerRejectsMissingCredentialIdentity() {
        ObjectNode document = userDocument();
        ((ObjectNode) document.withArray("providers").get(0)).remove("credential_id");

        ConfigurationError failure = assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.validateDocument(document, ConfigurationScope.USER));

        assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, failure.code());
    }

    /** 两个 Provider 不得共享同一个 credential ID，避免保存或删除 Secret 时跨供应商串线。 */
    @Test
    void providersRejectSharedCredentialIdentity() {
        ObjectNode document = userDocument();
        ObjectNode duplicate = ((ObjectNode) document.withArray("providers").get(0)).deepCopy();
        duplicate.put("provider_id", "provider_second");
        ((ObjectNode) duplicate.withArray("models").get(0)).put("model_id", "model_second");
        document.withArray("providers").add(duplicate);

        ConfigurationError failure = assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.validateDocument(document, ConfigurationScope.USER));

        assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, failure.code());
    }

    /** Skill 配置只接受与真实发现链一致的四类来源，不保留 workspace 旧别名。 */
    @Test
    void skillScopeUsesCurrentDiscoverySources() {
        ObjectNode document = userDocument();
        ObjectNode skill = document.withArray("skills").addObject();
        skill.put("skill_id", "skill_fixture");
        skill.put("name", "fixture");
        skill.put("scope", "ja");
        skill.put("enabled", false);
        skill.put("description", "Fixture Skill");
        ConfigurationPolicy.validateDocument(document, ConfigurationScope.USER);

        skill.put("scope", "workspace");
        ConfigurationError failure = assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.validateDocument(document, ConfigurationScope.USER));
        assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, failure.code());
    }

    /** 创建完整用户层文档，使各测试只改动一个 overlay 约束。 */
    private static ObjectNode userDocument() {
        ObjectNode root = baseRoot();
        root.put("default_provider_id", "provider_fixture");
        root.put("default_model_id", "model_fixture");
        root.put("default_reasoning_level", "medium");
        ObjectNode provider = root.withArray("providers").addObject();
        provider.put("provider_id", "provider_fixture");
        provider.put("name", "Fixture");
        provider.put("api", "openai_responses");
        provider.put("base_url", "https://api.openai.com/v1");
        provider.put("credential_id", "cred_fixture");
        provider.putObject("network_timeouts")
                .put("connect_timeout_ms", 10_000).put("request_timeout_ms", 120_000);
        ObjectNode defaults = provider.putObject("agent_defaults");
        defaults.putObject("context").put("auto_compact", true);
        defaults.putObject("turn_limits").put("max_model_rounds", 32)
                .put("max_tool_calls", 128).put("wall_timeout_ms", 600_000);
        ObjectNode model = provider.putArray("models").addObject();
        model.put("model_id", "model_fixture");
        model.put("name", "Fixture Model");
        model.put("model", "gpt-fixture");
        ObjectNode capabilities = model.putObject("capabilities");
        capabilities.put("context_window_tokens", 128_000);
        capabilities.put("max_output_tokens", 8_192);
        model.putObject("reasoning_level_map").put("low", "low").put("medium", "medium");
        model.put("default_reasoning_level", "medium");
        return root;
    }

    /** 创建稀疏项目 overlay，只引用用户 Provider/Model 并降低能力。 */
    private static ObjectNode projectOverlay() {
        ObjectNode root = baseRoot();
        root.put("default_provider_id", "provider_fixture");
        root.put("default_model_id", "model_fixture");
        root.put("default_reasoning_level", "medium");
        ObjectNode provider = root.withArray("providers").addObject();
        provider.put("provider_id", "provider_fixture");
        ObjectNode model = provider.putArray("models").addObject();
        model.put("model_id", "model_fixture");
        ObjectNode capabilities = model.putObject("capabilities");
        capabilities.put("context_window_tokens", 64_000);
        capabilities.put("max_output_tokens", 4_096);
        model.putObject("reasoning_level_map").put("low", "low").put("medium", "medium");
        model.put("default_reasoning_level", "medium");
        return root;
    }

    /** 创建两个作用域都必须包含的 v1 根字段与空 catalog。 */
    private static ObjectNode baseRoot() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema_version", 1);
        root.put("config_revision", 1);
        root.put("default_access_mode", "approval_required");
        root.set("providers", MAPPER.createArrayNode());
        root.set("mcp_servers", MAPPER.createArrayNode());
        root.set("skills", MAPPER.createArrayNode());
        return root;
    }
}
