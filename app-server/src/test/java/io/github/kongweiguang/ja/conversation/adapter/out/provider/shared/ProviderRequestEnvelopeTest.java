// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证冻结发送正文与协议感知的本地多模态预算保持独立。 */
class ProviderRequestEnvelopeTest {
    /** 300 KB PNG 的 Base64 必须留在发送正文，但不能再按四十万级文本 Token 计入窗口。 */
    @Test
    void keepsLargeResponsesPngInSendBodyWithoutCountingBase64AsText() throws Exception {
        ObjectNode request = openAiResponses(png(1_920, 1_080, 300 * 1024));

        ProviderRequestEnvelope envelope = ProviderRequestEnvelope.freeze(
                request, ModelPort.Api.OPENAI_RESPONSES);

        byte[] expectedBody = AbstractStreamingModelAdapter.serializeRequest(request);
        assertArrayEquals(expectedBody, envelope.sendBody());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expectedBody)),
                envelope.fingerprint());
        assertTrue(expectedBody.length > 400_000);
        assertTrue(envelope.inputTokenEstimate() < 128_000);
    }

    /** 同尺寸图片的压缩字节差异只改变传输与指纹，不应改变多模态预算。 */
    @Test
    void sameDimensionsWithDifferentCompressionLengthsHaveSameResponsesEstimate() {
        ProviderRequestEnvelope compact = ProviderRequestEnvelope.freeze(
                openAiResponses(png(1_920, 1_080, 64)), ModelPort.Api.OPENAI_RESPONSES);
        ProviderRequestEnvelope large = ProviderRequestEnvelope.freeze(
                openAiResponses(png(1_920, 1_080, 300 * 1024)), ModelPort.Api.OPENAI_RESPONSES);

        assertEquals(compact.inputTokenEstimate(), large.inputTokenEstimate());
        assertNotEquals(compact.sendBody().length, large.sendBody().length);
        assertNotEquals(compact.fingerprint(), large.fingerprint());
    }

    /** 已知 OpenAI patch 模型使用其公开 multiplier，避免无差别套用未知兼容模型回退。 */
    @Test
    void knownOpenAiPatchModelUsesPublishedMultiplier() {
        ObjectNode request = openAiResponses(png(1_920, 1_080, 64));
        request.put("model", "gpt-5.4");
        ObjectNode measured = request.deepCopy();
        ((ObjectNode) measured.path("input").path(0).path("content").path(1))
                .put("image_url", "data:image/png;base64,");

        ProviderRequestEnvelope envelope = ProviderRequestEnvelope.freeze(
                request, ModelPort.Api.OPENAI_RESPONSES);

        long patchTokens = (60L * 34L * 120L + 99L) / 100L;
        assertEquals(ProviderInputTokenEstimator.estimateTextTokens(
                        AbstractStreamingModelAdapter.serializeRequest(measured)) + patchTokens,
                envelope.inputTokenEstimate());
    }

    /** gpt-5.4 auto 使用 high 的 2500 patch budget，大图不得退回全局 30000 patch 上限。 */
    @Test
    void knownOpenAiPatchModelUsesPublishedAutoDetailBudget() {
        ObjectNode request = openAiResponses(png(4_096, 4_096, 64));
        request.put("model", "gpt-5.4");
        ObjectNode measured = request.deepCopy();
        ((ObjectNode) measured.path("input").path(0).path("content").path(1))
                .put("image_url", "data:image/png;base64,");

        ProviderRequestEnvelope envelope = ProviderRequestEnvelope.freeze(
                request, ModelPort.Api.OPENAI_RESPONSES);

        assertEquals(ProviderInputTokenEstimator.estimateTextTokens(
                        AbstractStreamingModelAdapter.serializeRequest(measured)) + 3_000L,
                envelope.inputTokenEstimate());
    }

    /** Anthropic image source 使用像素预算，而完整 Base64 仍原样进入冻结发送正文。 */
    @Test
    void metersAnthropicImageByPixelsAndPreservesTransportBody() {
        ObjectNode request = anthropic(png(1_920, 1_080, 300 * 1024));
        ObjectNode measured = request.deepCopy();
        ((ObjectNode) measured.path("messages").path(0).path("content").path(1).path("source"))
                .put("data", "");

        ProviderRequestEnvelope envelope = ProviderRequestEnvelope.freeze(
                request, ModelPort.Api.ANTHROPIC_MESSAGES);

        long expectedPixels = (1_920L * 1_080L + 749L) / 750L;
        assertEquals(ProviderInputTokenEstimator.estimateTextTokens(
                        AbstractStreamingModelAdapter.serializeRequest(measured)) + expectedPixels,
                envelope.inputTokenEstimate());
        assertTrue(envelope.sendBody().length > 400_000);
    }

    /** Chat 当前无原生附件能力，普通文本请求使用 UTF-8 感知估算而不是把每个字节当 Token。 */
    @Test
    void keepsChatTextEstimateBelowFrozenBodyBytes() {
        ObjectNode request = AbstractStreamingModelAdapter.JSON.createObjectNode();
        request.put("model", "test-model");
        request.putArray("messages").addObject().put("role", "user").put("content",
                "hello, this is a deliberately longer English prompt so the ratio is observable");
        request.put("stream", true);

        ProviderRequestEnvelope envelope = ProviderRequestEnvelope.freeze(
                request, ModelPort.Api.OPENAI_CHAT_COMPLETIONS);

        assertTrue(envelope.inputTokenEstimate() > 0);
        assertTrue(envelope.inputTokenEstimate() < envelope.sendBody().length);
    }

    /** 中文正文按码点而非三倍 UTF-8 字节计量，防止短对话被错误放大到压缩门槛。 */
    @Test
    void countsNonAsciiTextByCodePoint() {
        ObjectNode request = AbstractStreamingModelAdapter.JSON.createObjectNode();
        request.put("model", "test-model");
        request.putArray("messages").addObject().put("role", "user")
                .put("content", "上下文检查 ".repeat(1_000));

        ProviderRequestEnvelope envelope = ProviderRequestEnvelope.freeze(
                request, ModelPort.Api.OPENAI_CHAT_COMPLETIONS);

        assertTrue(envelope.inputTokenEstimate() < envelope.sendBody().length / 2);
    }

    /** 用户正文和 Tool 参数中的 data URL 不属于原生图片块，必须继续按文本估算。 */
    @Test
    void doesNotStripDataUrlsOutsideResponsesImageBlocks() {
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png(12, 10, 64));
        ObjectNode request = AbstractStreamingModelAdapter.JSON.createObjectNode();
        ArrayNode input = request.putArray("input");
        input.addObject().put("type", "message").put("role", "user").putArray("content")
                .addObject().put("type", "input_text").put("text", dataUrl);
        input.addObject().put("type", "function_call").put("name", "echo")
                .put("arguments", "{\"image_url\":\"" + dataUrl + "\"}");

        ProviderRequestEnvelope envelope = ProviderRequestEnvelope.freeze(
                request, ModelPort.Api.OPENAI_RESPONSES);

        ObjectNode withoutData = request.deepCopy();
        ((ObjectNode) withoutData.path("input").path(0).path("content").path(0))
                .put("text", "short");
        ((ObjectNode) withoutData.path("input").path(1))
                .put("arguments", "{\"image_url\":\"short\"}");
        ProviderRequestEnvelope shortEnvelope = ProviderRequestEnvelope.freeze(
                withoutData, ModelPort.Api.OPENAI_RESPONSES);
        assertTrue(envelope.inputTokenEstimate() > shortEnvelope.inputTokenEstimate());
    }

    /** 畸形 Base64 或超出公开输入尺寸边界的 PNG 必须稳定失败，不能以零图片 Token 放行。 */
    @Test
    void rejectsMalformedAndOversizedImageDimensions() {
        ObjectNode malformed = openAiResponses(png(32, 32, 64));
        ((ObjectNode) malformed.path("input").path(0).path("content").path(1))
                .put("image_url", "data:image/png;base64,not-base64!");
        assertThrows(ProviderProtocolException.class, () -> ProviderRequestEnvelope.freeze(
                malformed, ModelPort.Api.OPENAI_RESPONSES));

        assertThrows(ProviderProtocolException.class, () -> ProviderRequestEnvelope.freeze(
                openAiResponses(png(65_536, 10, 64)), ModelPort.Api.OPENAI_RESPONSES));
    }

    /** JPEG、GIF 与 WebP 的受支持头都必须得到尺寸预算，避免非 PNG 图片产生回归。 */
    @Test
    void acceptsSupportedImageHeaders() {
        byte[][] images = {jpeg(640, 480), gif(640, 480),
                webpExtended(640, 480), webpLossless(640, 480)};
        for (byte[] image : images) {
            ProviderRequestEnvelope envelope = ProviderRequestEnvelope.freeze(
                    openAiResponses(image), ModelPort.Api.OPENAI_RESPONSES);
            assertTrue(envelope.inputTokenEstimate() > 0);
            assertTrue(envelope.inputTokenEstimate() < envelope.sendBody().length + 50_000L);
        }
    }

    /** 构造 Codec 同形的 Responses 原生图片块，测试只观察共享冻结边界。 */
    private static ObjectNode openAiResponses(byte[] image) {
        ObjectNode request = AbstractStreamingModelAdapter.JSON.createObjectNode();
        request.put("model", "test-model");
        ArrayNode content = request.putArray("input").addObject()
                .put("role", "user").putArray("content");
        content.addObject().put("type", "input_text").put("text", "describe");
        content.addObject().put("type", "input_image").put("detail", "auto")
                .put("image_url", "data:image/" + mediaSubtype(image) + ";base64,"
                        + Base64.getEncoder().encodeToString(image));
        return request;
    }

    /** 构造 Codec 同形的 Anthropic image source，防止测试依赖领域附件装载。 */
    private static ObjectNode anthropic(byte[] image) {
        ObjectNode request = AbstractStreamingModelAdapter.JSON.createObjectNode();
        request.put("model", "test-model");
        ArrayNode content = request.putArray("messages").addObject()
                .put("role", "user").putArray("content");
        content.addObject().put("type", "text").put("text", "describe");
        content.addObject().put("type", "image").putObject("source")
                .put("type", "base64").put("media_type", "image/png")
                .put("data", Base64.getEncoder().encodeToString(image));
        return request;
    }

    /** 生成只需满足本地 IHDR 读取的 PNG 字节，额外尾部模拟不同压缩率。 */
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

    /** 生成带 SOF0 的最小 JPEG 头，尺寸字段采用网络字节序。 */
    private static byte[] jpeg(int width, int height) {
        return new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xc0,
                0, 7, 8, (byte) (height >>> 8), (byte) height,
                (byte) (width >>> 8), (byte) width, 1};
    }

    /** 生成带 logical screen descriptor 的 GIF89a 头。 */
    private static byte[] gif(int width, int height) {
        byte[] value = new byte[10];
        System.arraycopy("GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, value, 0, 6);
        value[6] = (byte) width;
        value[7] = (byte) (width >>> 8);
        value[8] = (byte) height;
        value[9] = (byte) (height >>> 8);
        return value;
    }

    /** 生成带 VP8X canvas 尺寸的最小 WebP 头。 */
    private static byte[] webpExtended(int width, int height) {
        byte[] value = new byte[30];
        System.arraycopy("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, value, 0, 4);
        System.arraycopy("WEBPVP8X".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, value, 8, 8);
        putLittleEndian24(value, 24, width - 1);
        putLittleEndian24(value, 27, height - 1);
        return value;
    }

    /** 生成刚好覆盖 VP8L 尺寸位的 25 字节 WebP 头，锁定各 chunk 独立最小长度。 */
    private static byte[] webpLossless(int width, int height) {
        byte[] value = new byte[25];
        System.arraycopy("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, value, 0, 4);
        System.arraycopy("WEBPVP8L".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, value, 8, 8);
        value[20] = 0x2f;
        long bits = width - 1L | (height - 1L) << 14;
        value[21] = (byte) bits;
        value[22] = (byte) (bits >>> 8);
        value[23] = (byte) (bits >>> 16);
        value[24] = (byte) (bits >>> 24);
        return value;
    }

    /** 从测试头签名选择与 Codec 一致的媒体子类型。 */
    private static String mediaSubtype(byte[] value) {
        if (value[0] == (byte) 0xff) return "jpeg";
        if (value[0] == 'G') return "gif";
        if (value[0] == 'R') return "webp";
        return "png";
    }

    /** 写入 PNG 使用的网络字节序 32 位值。 */
    private static void putBigEndian(byte[] value, int offset, int number) {
        value[offset] = (byte) (number >>> 24);
        value[offset + 1] = (byte) (number >>> 16);
        value[offset + 2] = (byte) (number >>> 8);
        value[offset + 3] = (byte) number;
    }

    /** 写入 WebP 使用的 little-endian 24 位值。 */
    private static void putLittleEndian24(byte[] value, int offset, int number) {
        value[offset] = (byte) number;
        value[offset + 1] = (byte) (number >>> 8);
        value[offset + 2] = (byte) (number >>> 16);
    }
}
