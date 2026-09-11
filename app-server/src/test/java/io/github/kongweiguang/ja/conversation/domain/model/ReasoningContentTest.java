// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证原生 reasoning 块的身份边界、模型名兼容性和脱敏字符串表示。 */
final class ReasoningContentTest {
    /** 上游模型名沿用模型配置文本域，必须接受 OpenRouter 风格的 slash。 */
    @Test
    void acceptsSlashModelAndCanonicalWireField() {
        ReasoningContent content = content("openai_responses", "openrouter/vendor/model", "reasoning_content");

        assertTrue(content.matches("provider_test", "model_test", "openai_responses",
                "openrouter/vendor/model", content.endpointFingerprint()));
        assertTrue(content.toString().contains("wireField=reasoning_content"));
    }

    /** 私有 reasoning、signature 和 encrypted_content 不得从日志字符串中泄漏。 */
    @Test
    void redactsNativeJsonFromToString() {
        ReasoningContent content = content("anthropic_messages", "claude-3-7-sonnet", "redacted_thinking");

        assertFalse(content.toString().contains("private chain"));
        assertFalse(content.toString().contains("signature-secret"));
        assertFalse(content.toString().contains("encrypted-secret"));
    }

    /** 端点身份变化时禁止复用原生块，避免把签名材料发送到不同网关。 */
    @Test
    void endpointFingerprintSeparatesGateways() {
        ReasoningContent content = content("openai_responses", "gpt-5", "reasoning");
        String otherEndpoint = ReasoningContent.endpointFingerprint(URI.create("https://gateway.example/v2"));

        assertFalse(content.matches("provider_test", "model_test", "openai_responses", "gpt-5", otherEndpoint));
    }

    /** 构造含完整原生字段的测试块，统一覆盖 opaque 状态和 slash 模型域。 */
    private static ReasoningContent content(String api, String upstreamModel, String wireField) {
        return new ReasoningContent(
                "provider_test", "model_test", api, upstreamModel,
                ReasoningContent.endpointFingerprint(URI.create("https://api.example/v1")), wireField,
                "{\"thinking\":\"private chain\",\"signature\":\"signature-secret\","
                        + "\"encrypted_content\":\"encrypted-secret\"}");
    }
}
