// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.ProviderRequestEnvelope;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.NativeAttachmentContent;
import io.github.kongweiguang.ja.conversation.domain.model.ReasoningContent;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定 Responses 原生图片/PDF 的官方 wire 形态与 Codec 能力闭集。 */
final class OpenAiResponsesCodecTest {
    /** 两种 OpenAI 协议的探测都只能发送 hi，不能注入空 system 消息或 instructions。 */
    @Test
    void omitsSystemPromptForUserOnlyProbe() {
        ModelPort.ModelRequest base = request(ModelRole.USER, List.of(new TextContent("hi")));
        ModelPort.ModelRequest probe = new ModelPort.ModelRequest(base.configuration(),
                new ModelPort.PromptPayload("", "prompt_probe"), base.messages(), List.of(), null, 1);
        JsonNode responses = OpenAiResponsesCodec.encodeRequest(probe);
        assertFalse(responses.has("instructions"));
        assertFalse(responses.path("store").booleanValue());
        assertEquals("reasoning.encrypted_content", responses.path("include").path(0).textValue());
        assertEquals(1, responses.path("input").size());
        assertEquals("hi", responses.path("input").path(0).path("content").path(0).path("text").asText());
        JsonNode chat = OpenAiChatCompletionsCodec.encodeRequest(probe);
        assertEquals(1, chat.path("messages").size());
        assertEquals("user", chat.path("messages").path(0).path("role").asText());
        assertEquals("hi", chat.path("messages").path(0).path("content").asText());
    }

    /** Responses 下一轮只回放同身份原生 reasoning item，并在跨来源时丢弃 opaque 块。 */
    @Test
    void replaysMatchingReasoningItemInAssistantHistoryAndSkipsOtherOrigins() {
        ModelPort.ModelRequest base = request(ModelRole.USER, List.of(new TextContent("hi")));
        ModelPort.ModelConfiguration configuration = base.configuration();
        String endpoint = ReasoningContent.endpointFingerprint(configuration.baseUri());
        ReasoningContent matching = new ReasoningContent(
                configuration.providerId(), configuration.modelId(), "openai_responses", configuration.model(),
                endpoint, "reasoning",
                "{\"type\":\"reasoning\",\"id\":\"reason_1\","
                        + "\"summary\":[],\"encrypted_content\":\"opaque\"}");
        ReasoningContent mismatched = new ReasoningContent(
                "provider_other", configuration.modelId(), "openai_responses", configuration.model(),
                endpoint, "reasoning",
                "{\"type\":\"reasoning\",\"id\":\"wrong\",\"encrypted_content\":\"bad\"}");
        ModelPort.ModelRequest withHistory = new ModelPort.ModelRequest(
                configuration, base.prompt(), List.of(base.messages().getFirst(),
                new ModelMessage(ModelRole.ASSISTANT, List.of(
                        new TextContent("before"), matching, mismatched, new TextContent("after")))),
                List.of(), null, 2);

        JsonNode input = OpenAiResponsesCodec.encodeRequest(withHistory).path("input");
        assertEquals(4, input.size());
        assertEquals("assistant", input.path(1).path("role").asText());
        assertEquals("before", input.path(1).path("content").asText());
        assertEquals("reasoning", input.path(2).path("type").asText());
        assertEquals("opaque", input.path(2).path("encrypted_content").asText());
        assertEquals("after", input.path(3).path("content").asText());
        assertFalse(input.toString().contains("wrong"));
    }

    /** 用户附件必须编码为带媒体类型的 data URL，PDF 还必须保留安全显示名。 */
    @Test
    void encodesNativeImageAndPdfAsResponsesInputBlocks() {
        ModelPort.ModelRequest request = request(ModelRole.USER, List.of(
                new TextContent("inspect both"),
                attachment(NativeAttachmentContent.Kind.IMAGE, "image.png", "image/png", 1, "AQ=="),
                attachment(NativeAttachmentContent.Kind.PDF, "guide.pdf", "application/pdf", 2, "AgM=")));

        JsonNode content = OpenAiResponsesCodec.encodeRequest(request).path("input").path(0).path("content");
        assertEquals("input_text", content.path(0).path("type").asText());
        assertEquals("inspect both", content.path(0).path("text").asText());
        assertEquals("input_image", content.path(1).path("type").asText());
        assertEquals("data:image/png;base64,AQ==", content.path(1).path("image_url").asText());
        assertEquals("auto", content.path(1).path("detail").asText());
        assertEquals("input_file", content.path(2).path("type").asText());
        assertEquals("guide.pdf", content.path(2).path("filename").asText());
        assertEquals("data:application/pdf;base64,AgM=", content.path(2).path("file_data").asText());
    }

    /** 真实 Codec 生成的 300 KB PNG 保留完整发送载荷，但本地预算不得按 Base64 文本膨胀。 */
    @Test
    void codecGeneratedLargePngUsesImageBudgetInsteadOfBase64Length() {
        byte[] png = png(1_920, 1_080, 300 * 1024);
        ModelPort.ModelRequest request = request(ModelRole.USER, List.of(
                new TextContent("describe"), attachment(NativeAttachmentContent.Kind.IMAGE,
                        "screen.png", "image/png", png.length,
                        Base64.getEncoder().encodeToString(png))));

        ProviderRequestEnvelope envelope = ProviderRequestEnvelope.freeze(
                OpenAiResponsesCodec.encodeRequest(request), ModelPort.Api.OPENAI_RESPONSES);

        assertTrue(envelope.sendBody().length > 400_000);
        assertTrue(envelope.inputTokenEstimate() < 128_000);
    }

    /** Assistant 历史不得携带用户上传字节，避免生成不被 Responses 接受的 EasyInput。 */
    @Test
    void rejectsNativeAttachmentOutsideUserMessages() {
        ProviderProtocolException failure = assertThrows(ProviderProtocolException.class,
                () -> OpenAiResponsesCodec.encodeRequest(request(ModelRole.ASSISTANT, List.of(
                        attachment(NativeAttachmentContent.Kind.IMAGE,
                                "image.png", "image/png", 1, "AQ==")))));

        assertEquals("REQUEST_ENCODING", failure.code());
    }

    /** Codec 只发布已实现媒体与 50 MB 请求总量，SVG 和越界文件必须回退 Tool。 */
    @Test
    void publishesExactNativeAttachmentCapability() {
        ModelPort.NativeAttachmentSupport support = OpenAiResponsesCodec.nativeAttachmentSupport();

        assertEquals(50_000_000L, support.maxTotalBytes());
        assertTrue(support.maxBytes(NativeAttachmentContent.Kind.IMAGE, "image/jpeg", 50_000_000L).isPresent());
        assertTrue(support.maxBytes(NativeAttachmentContent.Kind.PDF, "application/pdf", 50_000_000L).isPresent());
        assertFalse(support.maxBytes(NativeAttachmentContent.Kind.IMAGE, "image/svg+xml", 1).isPresent());
        assertFalse(support.maxBytes(NativeAttachmentContent.Kind.PDF, "application/pdf", 50_000_001L).isPresent());
    }

    /** 构造请求期附件，测试不经过文件系统或凭据边界。 */
    private static NativeAttachmentContent attachment(
            NativeAttachmentContent.Kind kind,
            String displayName,
            String mediaType,
            long sizeBytes,
            String base64Data) {
        return new NativeAttachmentContent(
                "att_12345678", kind, displayName, mediaType, sizeBytes, base64Data);
    }

    /** 使用 loopback 配置生成最小冻结请求，保证测试只观察 Codec JSON。 */
    private static ModelPort.ModelRequest request(
            ModelRole role,
            List<io.github.kongweiguang.ja.conversation.domain.model.ModelContent> content) {
        ModelPort.ModelConfiguration configuration = new ModelPort.ModelConfiguration(
                "provider_test", "model_test", "cfg_test", ModelPort.Api.OPENAI_RESPONSES, "test-model", URI.create("http://127.0.0.1:60842"), "fixture-only-api-key",
                Duration.ofSeconds(1), Duration.ofSeconds(1),
                Set.of(ModelPort.InputModality.TEXT, ModelPort.InputModality.IMAGE, ModelPort.InputModality.PDF),
                ModelPort.GenerationOptions.defaults());
        return new ModelPort.ModelRequest(configuration,
                new ModelPort.PromptPayload("system", "prompt_test"),
                List.of(new ModelMessage(role, content)), List.of(), null, 1);
    }

    /** 生成带真实 PNG signature/IHDR 尺寸字段的有界测试载荷，尾部模拟压缩数据。 */
    private static byte[] png(int width, int height, int length) {
        byte[] value = new byte[Math.max(24, length)];
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(signature, 0, value, 0, signature.length);
        value[11] = 13;
        value[12] = 0x49;
        value[13] = 0x48;
        value[14] = 0x44;
        value[15] = 0x52;
        putBigEndian(value, 16, width);
        putBigEndian(value, 20, height);
        return value;
    }

    /** 写入 PNG 使用的网络字节序 32 位尺寸。 */
    private static void putBigEndian(byte[] value, int offset, int number) {
        value[offset] = (byte) (number >>> 24);
        value[offset + 1] = (byte) (number >>> 16);
        value[offset + 2] = (byte) (number >>> 8);
        value[offset + 3] = (byte) number;
    }
}
