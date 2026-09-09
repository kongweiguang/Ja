// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 配置 TOML 编解码的精确 round-trip 回归。 */
final class TomlCodecTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * v1 根级和模型级默认思考档位允许显式 null；持久化必须保留字段存在性，不能因 TOML
     * 缺少原生 null 而把一次合法 replace 误报为损坏，也不能静默删除完整文档字段。
     */
    @Test
    void roundTripsNullableReasoningDefaultsWithoutDroppingFields() {
        ObjectNode document = MAPPER.createObjectNode();
        document.put("schema_version", 1);
        document.putNull("default_reasoning_level");
        ObjectNode provider = document.putArray("providers").addObject();
        provider.put("provider_id", "provider_native_smoke");
        ObjectNode model = provider.putArray("models").addObject();
        model.put("model_id", "model_native_smoke");
        model.putNull("default_reasoning_level");
        TomlCodec codec = new TomlCodec(MAPPER);

        String encoded = codec.write(document);
        ObjectNode decoded = codec.parse(encoded);

        assertEquals(2, encoded.split("default_reasoning_level = \\{ __ja_null = true }", -1).length - 1);
        assertTrue(decoded.has("default_reasoning_level"));
        assertTrue(decoded.get("default_reasoning_level").isNull());
        ObjectNode decodedModel = (ObjectNode) decoded.withArray("providers").get(0)
                .withArray("models").get(0);
        assertTrue(decodedModel.has("default_reasoning_level"));
        assertTrue(decodedModel.get("default_reasoning_level").isNull());
    }

    /** 自定义 DeepSeek 名称、显式 Chat 路由和独立凭据引用必须原样往返。 */
    @Test
    void roundTripsDeepSeekChatRouteAndCredentialIdentity() {
        ObjectNode document = MAPPER.createObjectNode();
        document.put("schema_version", 1);
        ObjectNode provider = document.putArray("providers").addObject();
        provider.put("provider_id", "provider_deepseek");
        provider.put("name", "DeepSeek");
        provider.put("api", "openai_chat_completions");
        provider.put("credential_id", "cred_deepseek");
        TomlCodec codec = new TomlCodec(MAPPER);

        ObjectNode decoded = codec.parse(codec.write(document));
        ObjectNode decodedProvider = (ObjectNode) decoded.withArray("providers").get(0);

        assertEquals("DeepSeek", decodedProvider.path("name").textValue());
        assertFalse(decodedProvider.has("provider"));
        assertEquals("openai_chat_completions", decodedProvider.path("api").textValue());
        assertEquals("cred_deepseek", decodedProvider.path("credential_id").textValue());
    }
}
