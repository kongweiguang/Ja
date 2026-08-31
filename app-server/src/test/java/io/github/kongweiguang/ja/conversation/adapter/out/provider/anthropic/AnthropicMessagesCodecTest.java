// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.domain.model.ModelContent;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.NativeAttachmentContent;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
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

    /** 使用 loopback 配置生成最小冻结请求，保证测试只观察 Codec JSON。 */
    private static ModelPort.ModelRequest request(ModelRole role, List<ModelContent> content) {
        ModelPort.ModelConfiguration configuration = new ModelPort.ModelConfiguration(
                "provider_test", "model_test", "cfg_test", ModelPort.Provider.ANTHROPIC,
                ModelPort.Api.ANTHROPIC_MESSAGES, "test-model", URI.create("http://127.0.0.1:60842"), "",
                Duration.ofSeconds(1), Duration.ofSeconds(1),
                Set.of(ModelPort.InputModality.TEXT, ModelPort.InputModality.IMAGE, ModelPort.InputModality.PDF),
                ModelPort.GenerationOptions.defaults());
        return new ModelPort.ModelRequest(configuration,
                new ModelPort.PromptPayload("system", "prompt_test"),
                List.of(new ModelMessage(role, content)), List.of(), null, 1);
    }
}
