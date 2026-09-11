// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.domain.model.ModelContent;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.NativeAttachmentContent;
import io.github.kongweiguang.ja.conversation.domain.model.ReasoningContent;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定 Anthropic Messages 原生图片/PDF 的官方 wire 形态与 Codec 能力闭集。 */
final class AnthropicMessagesCodecTest {
    /** 纯用户探测省略 system，确保 Provider 收到的唯一对话内容为 hi。 */
    @Test
    void omitsSystemPromptForUserOnlyProbe() {
        ModelPort.ModelRequest base = request(ModelRole.USER, List.of(new TextContent("hi")));
        ModelPort.ModelRequest probe = new ModelPort.ModelRequest(base.configuration(),
                new ModelPort.PromptPayload("", "prompt_probe"), base.messages(), List.of(), null, 1);
        JsonNode encoded = AnthropicMessagesCodec.encodeRequest(probe);
        assertFalse(encoded.has("system"));
        assertEquals(1, encoded.path("messages").size());
        assertEquals("user", encoded.path("messages").path(0).path("role").asText());
        assertEquals("hi", encoded.path("messages").path(0).path("content").path(0).path("text").asText());
    }

    /** 思考档位必须同时开启 Anthropic thinking；仅发送 effort 不会产生可回传的 thinking block。 */
    @Test
    void enablesAdaptiveThinkingAndDisablesItExplicitly() {
        ModelPort.ModelRequest base = request(ModelRole.USER, List.of(new TextContent("hi")));
        ModelPort.ModelConfiguration enabled = withReasoning(base.configuration(), "high");
        JsonNode enabledRequest = AnthropicMessagesCodec.encodeRequest(new ModelPort.ModelRequest(
                enabled, base.prompt(), base.messages(), List.of(), null, 1));

        assertEquals("adaptive", enabledRequest.path("thinking").path("type").asText());
        assertEquals("summarized", enabledRequest.path("thinking").path("display").asText());
        assertEquals("high", enabledRequest.path("output_config").path("effort").asText());

        ModelPort.ModelConfiguration disabled = withReasoning(base.configuration(), "off");
        JsonNode disabledRequest = AnthropicMessagesCodec.encodeRequest(new ModelPort.ModelRequest(
                disabled, base.prompt(), base.messages(), List.of(), null, 1));

        assertEquals("disabled", disabledRequest.path("thinking").path("type").asText());
        assertFalse(disabledRequest.has("output_config"));
    }

    /** 旧版 Anthropic 模型使用 budget_tokens；预算不能与 adaptive effort 混发。 */
    @Test
    void enablesBudgetThinkingForNumericUpstreamLevel() {
        ModelPort.ModelRequest base = request(ModelRole.USER, List.of(new TextContent("hi")));
        ModelPort.ModelConfiguration configuration = withReasoning(base.configuration(), "4096");
        JsonNode encoded = AnthropicMessagesCodec.encodeRequest(new ModelPort.ModelRequest(
                configuration, base.prompt(), base.messages(), List.of(), null, 1));

        assertEquals("enabled", encoded.path("thinking").path("type").asText());
        assertEquals(4096, encoded.path("thinking").path("budget_tokens").asInt());
        assertEquals("summarized", encoded.path("thinking").path("display").asText());
        assertFalse(encoded.has("output_config"));
    }

    /** 同身份原生块按 assistant 原始顺序回放，跨 Provider opaque 块既不回放也不降级成文本。 */
    @Test
    void replaysMatchingReasoningInOrderAndSkipsMismatchedOrigin() {
        ModelPort.ModelRequest base = request(ModelRole.USER, List.of(new TextContent("hi")));
        ModelPort.ModelConfiguration configuration = base.configuration();
        ReasoningContent matching = reasoning(configuration, "thinking",
                "{\"type\":\"thinking\",\"thinking\":\"matched chain\",\"signature\":\"sig\"}");
        ReasoningContent mismatched = new ReasoningContent(
                "provider_other", configuration.modelId(), "anthropic_messages", configuration.model(),
                ReasoningContent.endpointFingerprint(configuration.baseUri()), "thinking",
                "{\"type\":\"thinking\",\"thinking\":\"wrong origin\",\"signature\":\"bad\"}");
        ModelPort.ModelRequest withHistory = new ModelPort.ModelRequest(
                configuration, base.prompt(), List.of(
                        base.messages().getFirst(),
                        new ModelMessage(ModelRole.ASSISTANT, List.of(
                                new TextContent("before"), matching, mismatched,
                                new ToolCallContent("call_1", "read_file",
                                        JsonObjects.builder().putText("path", "README.md").build())))),
                List.of(), null, 1);

        JsonNode content = AnthropicMessagesCodec.encodeRequest(withHistory)
                .path("messages").path(1).path("content");
        assertEquals(3, content.size());
        assertEquals("text", content.path(0).path("type").asText());
        assertEquals("thinking", content.path(1).path("type").asText());
        assertEquals("matched chain", content.path(1).path("thinking").asText());
        assertEquals("tool_use", content.path(2).path("type").asText());
        assertTrue(!content.toString().contains("wrong origin"));
    }

    /** thinking 关闭后即使本轮未启用 reasoning，也按 native 规则回放 redacted 块。 */
    @Test
    void replaysMatchingRedactedReasoningWithoutThinkingOption() {
        ModelPort.ModelRequest base = request(ModelRole.USER, List.of(new TextContent("hi")));
        ModelPort.ModelConfiguration configuration = base.configuration();
        ReasoningContent redacted = reasoning(configuration, "redacted_thinking",
                "{\"type\":\"redacted_thinking\",\"data\":\"opaque\"}");
        ModelPort.ModelRequest withHistory = new ModelPort.ModelRequest(
                configuration, base.prompt(), List.of(
                        base.messages().getFirst(), new ModelMessage(ModelRole.ASSISTANT, List.of(redacted))),
                List.of(), null, 1);

        JsonNode messages = AnthropicMessagesCodec.encodeRequest(withHistory).path("messages");
        assertEquals(2, messages.size());
        assertEquals("redacted_thinking", messages.path(1).path("content").path(0).path("type").asText());
        assertEquals("opaque", messages.path(1).path("content").path(0).path("data").asText());
    }

    /** 匹配 origin 的 reasoning 缺 signature 时拒绝 wire，避免把截断块伪装成可续传历史。 */
    @Test
    void rejectsIncompleteMatchingReasoning() {
        ModelPort.ModelRequest base = request(ModelRole.USER, List.of(new TextContent("hi")));
        ReasoningContent incomplete = reasoning(base.configuration(), "thinking",
                "{\"type\":\"thinking\",\"thinking\":\"truncated\",\"signature\":\"\"}");
        ModelPort.ModelRequest withHistory = new ModelPort.ModelRequest(
                base.configuration(), base.prompt(), List.of(
                        base.messages().getFirst(), new ModelMessage(ModelRole.ASSISTANT, List.of(incomplete))),
                List.of(), null, 1);

        ProviderProtocolException failure = assertThrows(ProviderProtocolException.class,
                () -> AnthropicMessagesCodec.encodeRequest(withHistory));
        assertEquals("REQUEST_ENCODING", failure.code());
    }

    /** 用户图片和 PDF 必须使用明确类型的 Base64 source，不能伪装为普通文本。 */
    @Test
    void encodesNativeImageAndPdfAsMessagesContentBlocks() {
        ModelPort.ModelRequest request = request(ModelRole.USER, List.of(
                new TextContent("inspect both"),
                attachment(NativeAttachmentContent.Kind.IMAGE, "image.png", "image/png", 1, "AQ=="),
                attachment(NativeAttachmentContent.Kind.PDF, "guide.pdf", "application/pdf", 2, "AgM=")));

        JsonNode content = AnthropicMessagesCodec.encodeRequest(request).path("messages").path(0).path("content");
        assertEquals("text", content.path(0).path("type").asText());
        assertEquals("inspect both", content.path(0).path("text").asText());
        assertEquals("image", content.path(1).path("type").asText());
        assertEquals("base64", content.path(1).path("source").path("type").asText());
        assertEquals("image/png", content.path(1).path("source").path("media_type").asText());
        assertEquals("AQ==", content.path(1).path("source").path("data").asText());
        assertEquals("document", content.path(2).path("type").asText());
        assertEquals("base64", content.path(2).path("source").path("type").asText());
        assertEquals("application/pdf", content.path(2).path("source").path("media_type").asText());
        assertEquals("AgM=", content.path(2).path("source").path("data").asText());
    }

    /** Assistant 历史不得携带用户上传字节，避免 Provider 接收无归属的原生内容。 */
    @Test
    void rejectsNativeAttachmentOutsideUserMessages() {
        ProviderProtocolException failure = assertThrows(ProviderProtocolException.class,
                () -> AnthropicMessagesCodec.encodeRequest(request(ModelRole.ASSISTANT, List.of(
                        attachment(NativeAttachmentContent.Kind.PDF,
                                "guide.pdf", "application/pdf", 2, "AgM=")))));

        assertEquals("REQUEST_ENCODING", failure.code());
    }

    /** Codec 只发布当前 SDK 媒体闭集和保守 32 MB 总量，SVG 与越界载荷必须回退 Tool。 */
    @Test
    void publishesExactNativeAttachmentCapability() {
        ModelPort.NativeAttachmentSupport support = AnthropicMessagesCodec.nativeAttachmentSupport();

        assertEquals(32_000_000L, support.maxTotalBytes());
        assertTrue(support.maxBytes(NativeAttachmentContent.Kind.IMAGE, "image/webp", 32_000_000L).isPresent());
        assertTrue(support.maxBytes(NativeAttachmentContent.Kind.PDF, "application/pdf", 32_000_000L).isPresent());
        assertFalse(support.maxBytes(NativeAttachmentContent.Kind.IMAGE, "image/svg+xml", 1).isPresent());
        assertFalse(support.maxBytes(NativeAttachmentContent.Kind.PDF, "application/pdf", 32_000_001L).isPresent());
    }

    /** 构造请求期附件，显示名不进入 Anthropic wire，但仍由领域对象验证。 */
    private static NativeAttachmentContent attachment(
            NativeAttachmentContent.Kind kind,
            String displayName,
            String mediaType,
            long sizeBytes,
            String base64Data) {
        return new NativeAttachmentContent(
                "att_12345678", kind, displayName, mediaType, sizeBytes, base64Data);
    }

    /** 创建带当前 Provider origin 的 native reasoning，测试回放身份边界而不复制生产工厂。 */
    private static ReasoningContent reasoning(ModelPort.ModelConfiguration configuration,
                                               String wireField, String nativeJson) {
        return new ReasoningContent(configuration.providerId(), configuration.modelId(),
                "anthropic_messages", configuration.model(),
                ReasoningContent.endpointFingerprint(configuration.baseUri()), wireField, nativeJson);
    }

    /** 使用 loopback 配置生成最小冻结请求，保证测试只观察 Codec JSON。 */
    private static ModelPort.ModelRequest request(ModelRole role, List<ModelContent> content) {
        ModelPort.ModelConfiguration configuration = new ModelPort.ModelConfiguration(
                "provider_test", "model_test", "cfg_test", ModelPort.Api.ANTHROPIC_MESSAGES, "test-model", URI.create("http://127.0.0.1:60842"), "fixture-only-api-key",
                Duration.ofSeconds(1), Duration.ofSeconds(1),
                Set.of(ModelPort.InputModality.TEXT, ModelPort.InputModality.IMAGE, ModelPort.InputModality.PDF),
                ModelPort.GenerationOptions.defaults());
        return new ModelPort.ModelRequest(configuration,
                new ModelPort.PromptPayload("system", "prompt_test"),
                List.of(new ModelMessage(role, content)), List.of(), null, 1);
    }

    /** 复用最小 loopback 配置，仅替换 reasoning 档位以隔离 Codec 映射行为。 */
    private static ModelPort.ModelConfiguration withReasoning(
            ModelPort.ModelConfiguration configuration, String reasoningLevel) {
        return new ModelPort.ModelConfiguration(configuration.providerId(), configuration.modelId(),
                configuration.configGeneration(), configuration.api(), configuration.model(),
                configuration.baseUri(), configuration.apiKey(), configuration.connectTimeout(),
                configuration.requestTimeout(), configuration.inputModalities(),
                new ModelPort.GenerationOptions(null, null,
                        configuration.generation().maxOutputTokens(), reasoningLevel));
    }
}
