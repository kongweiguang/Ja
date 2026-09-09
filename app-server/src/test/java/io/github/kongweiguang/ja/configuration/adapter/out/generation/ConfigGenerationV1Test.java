// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Provider/Model v1 代际投影测试。 */
final class ConfigGenerationV1Test {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** reasoning 映射与 Agent 默认值从具体 v1 层级投影，未知模型保持 text-only。 */
    @Test
    void providerModelAndReasoningProjectionRemainNested() {
        ConfigGeneration generation = new ConfigGeneration("generation_fixture", null,
                "cfg_user", "cfg_missing", document(), Map.of(), List.of(), false,
                new java.util.LinkedHashMap<>(), List.of(), List.of(), "catalog_fixture", ignored -> { });

        try (ConfigGeneration.Lease lease = generation.acquire()) {
            ConfigurationGenerationSnapshot snapshot = lease.snapshot();
            assertEquals("provider_fixture", snapshot.defaultProviderId().orElseThrow());
            assertEquals("model_fixture", snapshot.defaultModelId().orElseThrow());
            assertEquals(ConfigurationGenerationSnapshot.ReasoningLevel.MEDIUM,
                    snapshot.defaultReasoningLevel().orElseThrow());
            ConfigurationGenerationSnapshot.Provider provider =
                    snapshot.requireProvider("provider_fixture");
            assertEquals(2, provider.models().size());
            ConfigurationGenerationSnapshot.Model model =
                    snapshot.requireModel("provider_fixture", "model_fixture");
            assertEquals(List.of(ConfigurationGenerationSnapshot.InputModality.TEXT),
                    model.capabilities().inputModalities());
            assertEquals(Map.of(ConfigurationGenerationSnapshot.ReasoningLevel.LOW, "low",
                    ConfigurationGenerationSnapshot.ReasoningLevel.MEDIUM, "medium"),
                    model.reasoningLevelMap());
            assertEquals(32, provider.agentDefaults().turnLimits().maxModelRounds());
        } finally {
            generation.close();
        }
    }

    /** 任意自定义供应商选择的三种显式 API 均原样进入代际投影，不按名称猜测协议。 */
    @Test
    void deepSeekProtocolsRemainExplicitInGenerationProjection() {
        Map<String, ConfigurationGenerationSnapshot.Api> routes = Map.of(
                "anthropic_messages", ConfigurationGenerationSnapshot.Api.ANTHROPIC_MESSAGES,
                "openai_responses", ConfigurationGenerationSnapshot.Api.OPENAI_RESPONSES,
                "openai_chat_completions", ConfigurationGenerationSnapshot.Api.OPENAI_CHAT_COMPLETIONS);

        routes.forEach((wireApi, expectedApi) -> {
            ObjectNode source = document();
            ObjectNode providerNode = (ObjectNode) source.withArray("providers").get(0);
            providerNode.put("name", "DeepSeek");
            providerNode.put("api", wireApi);
            ConfigGeneration generation = new ConfigGeneration("generation_" + wireApi, null,
                    "cfg_user", "cfg_missing", source, Map.of(), List.of(), false,
                    new java.util.LinkedHashMap<>(), List.of(), List.of(), "catalog_fixture", ignored -> { });
            try (ConfigGeneration.Lease lease = generation.acquire()) {
                ConfigurationGenerationSnapshot.Provider provider =
                        lease.snapshot().requireProvider("provider_fixture");
                assertEquals(expectedApi, provider.api());
            } finally {
                generation.close();
            }
        });
    }

    /** 构造含两个模型的完整 effective v1 文档，验证同 Provider 多模型目录。 */
    private static ObjectNode document() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema_version", 1);
        root.put("config_revision", 1);
        root.put("default_access_mode", "approval_required");
        root.put("default_provider_id", "provider_fixture");
        root.put("default_model_id", "model_fixture");
        root.put("default_reasoning_level", "medium");
        root.putArray("mcp_servers");
        root.putArray("skills");
        ObjectNode provider = root.putArray("providers").addObject();
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
        addModel(provider, "model_fixture", "Fixture", true);
        addModel(provider, "model_second", "Second", false);
        return root;
    }

    /** 向同一 Provider 增加一个模型，证明模型能力不复制 Provider 连接字段。 */
    private static void addModel(ObjectNode provider, String id, String name, boolean defaultReasoning) {
        ObjectNode model = provider.withArray("models").addObject();
        model.put("model_id", id);
        model.put("name", name);
        model.put("model", "upstream-" + id);
        model.putObject("capabilities").put("context_window_tokens", 128_000)
                .put("max_output_tokens", 8_192);
        model.putObject("reasoning_level_map").put("low", "low").put("medium", "medium");
        if (defaultReasoning) model.put("default_reasoning_level", "medium");
        else model.putNull("default_reasoning_level");
    }
}
