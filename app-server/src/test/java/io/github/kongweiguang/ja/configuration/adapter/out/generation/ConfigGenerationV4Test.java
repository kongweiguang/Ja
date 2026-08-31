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

/** Provider/Model v4 代际投影测试。 */
final class ConfigGenerationV4Test {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** reasoning 映射与 Agent 默认值从具体 v4 层级投影，未知模型保持 text-only。 */
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

    /** 构造含两个模型的完整 effective v4 文档，验证同 Provider 多模型目录。 */
    private static ObjectNode document() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema_version", 4);
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
    }
}
