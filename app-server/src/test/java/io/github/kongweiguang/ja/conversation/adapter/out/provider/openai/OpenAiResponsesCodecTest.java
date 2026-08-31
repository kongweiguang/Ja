// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
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

/** 锁定 Responses 原生图片/PDF 的官方 wire 形态与 Codec 能力闭集。 */
final class OpenAiResponsesCodecTest {
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
                "provider_test", "model_test", "cfg_test", ModelPort.Provider.OPENAI,
                ModelPort.Api.OPENAI_RESPONSES, "test-model", URI.create("http://127.0.0.1:60842"), "",
                Duration.ofSeconds(1), Duration.ofSeconds(1),
                Set.of(ModelPort.InputModality.TEXT, ModelPort.InputModality.IMAGE, ModelPort.InputModality.PDF),
                ModelPort.GenerationOptions.defaults());
        return new ModelPort.ModelRequest(configuration,
                new ModelPort.PromptPayload("system", "prompt_test"),
                List.of(new ModelMessage(role, content)), List.of(), null, 1);
    }
}
