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

/** 配置 v4 Provider/Model 与项目 overlay 收紧策略测试。 */
final class ConfigurationPolicyV4Test {
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

    /** v4 运行时闭集不接受已淘汰的 Chat Completions 值，也不把它隐式转换为 Responses。 */
    @Test
    void chatCompletionsIsRejectedByStrictV4Policy() {
        ObjectNode document = userDocument();
        ((ObjectNode) document.withArray("providers").get(0))
                .put("api", "openai_chat_completions");

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
        provider.put("provider", "openai");
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

    /** 创建两个作用域都必须包含的 v4 根字段与空 catalog。 */
    private static ObjectNode baseRoot() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema_version", 4);
        root.put("config_revision", 1);
        root.put("default_access_mode", "approval_required");
        root.set("providers", MAPPER.createArrayNode());
        root.set("mcp_servers", MAPPER.createArrayNode());
        root.set("skills", MAPPER.createArrayNode());
        return root;
    }
}
