// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;

import java.util.Base64;

/**
 * 将 Provider wire 的文本传输成本与原生图片的多模态 Token 成本分开计量。
 */
final class ProviderInputTokenEstimator {
    private static final long OPENAI_PATCH_EDGE = 32L;
    private static final long OPENAI_MAX_PATCHES = 30_000L;
    private static final long OPENAI_MAX_MULTIPLIER_HUNDREDTHS = 246L;
    private static final long OPENAI_TILE_EDGE = 512L;
    private static final long OPENAI_TILE_BASE_TOKENS = 2_833L;
    private static final long OPENAI_TILE_TOKENS = 5_667L;
    private static final long OPENAI_TILE_MAX_DIMENSION = 2_048L;
    private static final long OPENAI_TILE_SHORT_SIDE = 768L;
    private static final long ANTHROPIC_PIXELS_PER_TOKEN = 750L;
    private static final long MAX_IMAGE_DIMENSION = 65_535L;

    /** 禁止实例化纯计量策略，避免状态或 Provider IO 混入请求准入。 */
    private ProviderInputTokenEstimator() {
    }

    /**
     * 只清除当前 Codec 明确生成的原生图片字段；正文、Tool 参数和普通 data URL 原样计入文本上界。
     */
    static long estimate(ObjectNode request, ModelPort.Api api) {
        long imageTokens = switch (api) {
            case OPENAI_RESPONSES -> stripOpenAiResponsesImages(request);
            case ANTHROPIC_MESSAGES -> stripAnthropicImages(request);
            case OPENAI_CHAT_COMPLETIONS -> 0L;
        };
        long textTokens = AbstractStreamingModelAdapter.serializeRequest(request).length;
        return saturatedAdd(textTokens, imageTokens);
    }

    /**
     * Responses 只在 message.content 的 input_image 块中承认图片，避免误删 Tool JSON 内同名字段。
     */
    private static long stripOpenAiResponsesImages(ObjectNode request) {
        JsonNode input = request.get("input");
        if (!(input instanceof ArrayNode items)) return 0L;
        long tokens = 0L;
        for (JsonNode item : items) {
            if (!"user".equals(item.path("role").textValue())
                || !(item.get("content") instanceof ArrayNode content)) continue;
            for (JsonNode block : content) {
                if (!"input_image".equals(block.path("type").textValue())
                    || !(block instanceof ObjectNode image)) continue;
                String dataUrl = requiredText(image, "image_url");
                int separator = dataUrl.indexOf(',');
                String base64Marker = ";base64";
                if (!dataUrl.startsWith("data:image/")
                    || separator <= "data:".length() + base64Marker.length()
                    || !dataUrl.substring(0, separator).endsWith(base64Marker)) {
                    throw invalidImage();
                }
                ImageDimensions dimensions = dimensions(
                        dataUrl.substring(separator + 1),
                        dataUrl.substring("data:".length(), separator - base64Marker.length()));
                tokens = saturatedAdd(tokens, openAiImageTokens(dimensions, requiredText(request, "model")));
                image.put("image_url", dataUrl.substring(0, separator + 1));
            }
        }
        return tokens;
    }

    /**
     * Anthropic 只在 image + base64 source 闭集内剥离 data，document/PDF 继续按原始 JSON 字节计量。
     */
    private static long stripAnthropicImages(ObjectNode request) {
        JsonNode messages = request.get("messages");
        if (!(messages instanceof ArrayNode items)) return 0L;
        long tokens = 0L;
        for (JsonNode item : items) {
            if (!(item.get("content") instanceof ArrayNode content)) continue;
            for (JsonNode block : content) {
                if (!"image".equals(block.path("type").textValue())
                    || !(block.path("source") instanceof ObjectNode source)
                    || !"base64".equals(source.path("type").textValue())) continue;
                ImageDimensions dimensions = dimensions(
                        requiredText(source, "data"), requiredText(source, "media_type"));
                tokens = saturatedAdd(tokens, divideCeiling(
                        saturatedMultiply(dimensions.width(), dimensions.height()), ANTHROPIC_PIXELS_PER_TOKEN));
                source.put("data", "");
            }
        }
        return tokens;
    }

    /**
     * OpenAI-compatible 端点无法预知真实模型算法，因此取官方公开 patch 与 tile 两族估算的较大值。
     * 该策略防止 Base64 膨胀，同时不把兼容端点的结果描述为第三方 tokenizer 的严格上界。
     */
    private static long openAiImageTokens(ImageDimensions dimensions, String model) {
        OpenAiTokenization tokenization = OpenAiTokenization.forModel(model);
        if (tokenization != null && tokenization.tile()) {
            return openAiTileTokens(dimensions, tokenization.baseTokens(), tokenization.unitTokens());
        }
        if (tokenization != null) {
            return openAiPatchTokens(dimensions, tokenization.unitTokens(), tokenization.patchBudget());
        }
        return Math.max(openAiPatchTokens(
                        dimensions, OPENAI_MAX_MULTIPLIER_HUNDREDTHS, OPENAI_MAX_PATCHES),
                openAiTileTokens(dimensions, OPENAI_TILE_BASE_TOKENS, OPENAI_TILE_TOKENS));
    }

    /**
     * 已知 patch family 使用官方 multiplier 与当前 detail 的公开 patch budget；像素边长缩放只会
     * 进一步降低 Token，因此保留原尺寸后再截断 budget 是更简单且不会低估的本地准入策略。
     */
    private static long openAiPatchTokens(
            ImageDimensions dimensions, long multiplierHundredths, long patchBudget) {
        long patches = saturatedMultiply(
                divideCeiling(dimensions.width(), OPENAI_PATCH_EDGE),
                divideCeiling(dimensions.height(), OPENAI_PATCH_EDGE));
        return divideCeiling(saturatedMultiply(
                Math.min(patches, patchBudget), multiplierHundredths), 100L);
    }

    /** 已知 tile family 使用官方 base/tile 成本并共享公开缩放规则。 */
    private static long openAiTileTokens(ImageDimensions dimensions, long baseTokens, long tileTokens) {
        ImageDimensions tiled = fitForOpenAiTiles(dimensions);
        long tiles = saturatedMultiply(
                divideCeiling(tiled.width(), OPENAI_TILE_EDGE),
                divideCeiling(tiled.height(), OPENAI_TILE_EDGE));
        return saturatedAdd(baseTokens, saturatedMultiply(tiles, tileTokens));
    }

    /** 按 OpenAI tile 规则依次约束 2048 方框和 768 短边，不放大小图。 */
    private static ImageDimensions fitForOpenAiTiles(ImageDimensions source) {
        long width = source.width();
        long height = source.height();
        long longest = Math.max(width, height);
        if (longest > OPENAI_TILE_MAX_DIMENSION) {
            width = scaleFloor(width, OPENAI_TILE_MAX_DIMENSION, longest);
            height = scaleFloor(height, OPENAI_TILE_MAX_DIMENSION, longest);
        }
        long shortest = Math.min(width, height);
        if (shortest > OPENAI_TILE_SHORT_SIDE) {
            width = scaleFloor(width, OPENAI_TILE_SHORT_SIDE, shortest);
            height = scaleFloor(height, OPENAI_TILE_SHORT_SIDE, shortest);
        }
        return new ImageDimensions(Math.max(1L, width), Math.max(1L, height));
    }

    /** 通过整数商保持向下取整，尺寸上限保证乘法不会溢出。 */
    private static long scaleFloor(long value, long target, long source) {
        return saturatedMultiply(value, target) / source;
    }

    /**
     * 解码受请求总大小约束的 Base64，并仅解析支持媒体的文件头；无法证明尺寸时拒绝本地准入。
     */
    private static ImageDimensions dimensions(String base64, String mediaType) {
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException invalid) {
            throw invalidImage();
        }
        ImageDimensions dimensions = switch (mediaType) {
            case "image/png" -> png(bytes);
            case "image/jpeg" -> jpeg(bytes);
            case "image/gif" -> gif(bytes);
            case "image/webp" -> webp(bytes);
            default -> throw invalidImage();
        };
        if (dimensions.width() < 1 || dimensions.height() < 1
            || dimensions.width() > MAX_IMAGE_DIMENSION || dimensions.height() > MAX_IMAGE_DIMENSION) {
            throw invalidImage();
        }
        return dimensions;
    }

    /** 从 PNG IHDR 读取无符号 32 位尺寸，并校验固定签名与首块类型。 */
    private static ImageDimensions png(byte[] bytes) {
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (bytes.length < 24 || !matches(bytes, 0, signature)
            || !matches(bytes, 12, new byte[] {0x49, 0x48, 0x44, 0x52})) throw invalidImage();
        return new ImageDimensions(unsignedInt(bytes, 16), unsignedInt(bytes, 20));
    }

    /** 从 GIF logical screen descriptor 读取 little-endian 尺寸。 */
    private static ImageDimensions gif(byte[] bytes) {
        boolean signature = matches(bytes, 0, "GIF87a".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                || matches(bytes, 0, "GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        if (bytes.length < 10 || !signature) throw invalidImage();
        return new ImageDimensions(unsignedShortLittleEndian(bytes, 6), unsignedShortLittleEndian(bytes, 8));
    }

    /** 扫描 JPEG marker，直到首个 SOF 段给出尺寸；压缩数据开始后不再猜测。 */
    private static ImageDimensions jpeg(byte[] bytes) {
        if (bytes.length < 4 || unsigned(bytes[0]) != 0xff || unsigned(bytes[1]) != 0xd8) throw invalidImage();
        int offset = 2;
        while (offset < bytes.length) {
            while (offset < bytes.length && unsigned(bytes[offset]) != 0xff) offset++;
            while (offset < bytes.length && unsigned(bytes[offset]) == 0xff) offset++;
            if (offset >= bytes.length) break;
            int marker = unsigned(bytes[offset++]);
            if (marker == 0xd9 || marker == 0xda) break;
            if (marker == 0x01 || marker >= 0xd0 && marker <= 0xd7) continue;
            if (offset + 2 > bytes.length) throw invalidImage();
            int length = unsignedShortBigEndian(bytes, offset);
            if (length < 2 || offset + length > bytes.length) throw invalidImage();
            if (isStartOfFrame(marker)) {
                if (length < 7) throw invalidImage();
                return new ImageDimensions(
                        unsignedShortBigEndian(bytes, offset + 5), unsignedShortBigEndian(bytes, offset + 3));
            }
            offset += length;
        }
        throw invalidImage();
    }

    /** 从 WebP VP8X、VP8 或 VP8L 首图块读取尺寸，拒绝未知或截断容器。 */
    private static ImageDimensions webp(byte[] bytes) {
        if (bytes.length < 12 || !matches(bytes, 0, new byte[] {0x52, 0x49, 0x46, 0x46})
            || !matches(bytes, 8, new byte[] {0x57, 0x45, 0x42, 0x50})) throw invalidImage();
        if (bytes.length >= 30 && matches(bytes, 12, new byte[] {0x56, 0x50, 0x38, 0x58})) {
            return new ImageDimensions(1L + unsigned24LittleEndian(bytes, 24),
                    1L + unsigned24LittleEndian(bytes, 27));
        }
        if (bytes.length >= 30 && matches(bytes, 12, new byte[] {0x56, 0x50, 0x38, 0x20})
            && matches(bytes, 23, new byte[] {(byte) 0x9d, 0x01, 0x2a})) {
            return new ImageDimensions(unsignedShortLittleEndian(bytes, 26) & 0x3fffL,
                    unsignedShortLittleEndian(bytes, 28) & 0x3fffL);
        }
        if (bytes.length >= 25 && matches(bytes, 12, new byte[] {0x56, 0x50, 0x38, 0x4c})
            && unsigned(bytes[20]) == 0x2f) {
            long bits = unsigned(bytes[21]) | (long) unsigned(bytes[22]) << 8
                    | (long) unsigned(bytes[23]) << 16 | (long) unsigned(bytes[24]) << 24;
            return new ImageDimensions(1L + (bits & 0x3fffL), 1L + (bits >>> 14 & 0x3fffL));
        }
        throw invalidImage();
    }

    /** SOF marker 闭集排除 Huffman、算术表与扫描段。 */
    private static boolean isStartOfFrame(int marker) {
        return marker >= 0xc0 && marker <= 0xc3 || marker >= 0xc5 && marker <= 0xc7
                || marker >= 0xc9 && marker <= 0xcb || marker >= 0xcd && marker <= 0xcf;
    }

    /** 读取必须存在的文本字段，并禁止空载荷静默降级成零图片 Token。 */
    private static String requiredText(ObjectNode node, String field) {
        String value = node.path(field).textValue();
        if (value == null || value.isEmpty()) throw invalidImage();
        return value;
    }

    /** 固定错误不携带 Base64、媒体正文或上游端点。 */
    private static ProviderProtocolException invalidImage() {
        return new ProviderProtocolException(
                "REQUEST_ENCODING", "provider image dimensions could not be measured", false);
    }

    /** 数组匹配先校验范围，避免畸形头触发越界异常或隐式接受前缀。 */
    private static boolean matches(byte[] value, int offset, byte[] expected) {
        if (offset < 0 || offset > value.length - expected.length) return false;
        for (int index = 0; index < expected.length; index++) {
            if (value[offset + index] != expected[index]) return false;
        }
        return true;
    }

    /** 将有符号 byte 投影为无符号整数。 */
    private static int unsigned(byte value) {
        return value & 0xff;
    }

    /** 读取网络字节序的无符号 16 位整数。 */
    private static int unsignedShortBigEndian(byte[] value, int offset) {
        return unsigned(value[offset]) << 8 | unsigned(value[offset + 1]);
    }

    /** 读取 little-endian 的无符号 16 位整数。 */
    private static int unsignedShortLittleEndian(byte[] value, int offset) {
        return unsigned(value[offset]) | unsigned(value[offset + 1]) << 8;
    }

    /** 读取 PNG 使用的网络字节序无符号 32 位整数。 */
    private static long unsignedInt(byte[] value, int offset) {
        return (long) unsigned(value[offset]) << 24 | (long) unsigned(value[offset + 1]) << 16
                | (long) unsigned(value[offset + 2]) << 8 | unsigned(value[offset + 3]);
    }

    /** 读取 WebP 使用的 little-endian 无符号 24 位整数。 */
    private static long unsigned24LittleEndian(byte[] value, int offset) {
        return unsigned(value[offset]) | (long) unsigned(value[offset + 1]) << 8
                | (long) unsigned(value[offset + 2]) << 16;
    }

    /** 正数向上整除，调用方均在格式校验后传入。 */
    private static long divideCeiling(long value, long divisor) {
        return value / divisor + (value % divisor == 0 ? 0 : 1);
    }

    /** 预算溢出时饱和为 long 上限，让上层稳定判定超窗而不是绕回负数。 */
    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    /** 预算溢出时饱和为 long 上限，让畸形维度不能绕过上下文门禁。 */
    private static long saturatedMultiply(long left, long right) {
        return left != 0 && right > Long.MAX_VALUE / left ? Long.MAX_VALUE : left * right;
    }

    /** 只承载已经校验为正且有界的像素尺寸。 */
    private record ImageDimensions(long width, long height) {
    }

    /**
     * 只登记 OpenAI 官方当前公开的模型族；自定义名称显式落入跨族保守估计，绝不伪装成精确值。
     */
    private record OpenAiTokenization(boolean tile, long baseTokens, long unitTokens, long patchBudget) {
        /** 依据冻结上游模型标识选择公开算法，日期快照沿用其基础模型规则。 */
        private static OpenAiTokenization forModel(String model) {
            String normalized = model.toLowerCase(java.util.Locale.ROOT);
            if (modelFamily(normalized, "gpt-4o-mini")) return tile(2_833L, 5_667L);
            if (modelFamily(normalized, "gpt-4o") || modelFamily(normalized, "gpt-4.1")) {
                return tile(85L, 170L);
            }
            if (modelFamily(normalized, "gpt-5.1") || modelFamily(normalized, "gpt-5")) {
                return tile(70L, 140L);
            }
            if (modelFamily(normalized, "o1") || modelFamily(normalized, "o1-pro")
                || modelFamily(normalized, "o3")) return tile(75L, 150L);
            if (modelFamily(normalized, "gpt-4.1-mini")) return patch(162L, 6_144L);
            if (modelFamily(normalized, "gpt-4.1-nano")) return patch(246L, OPENAI_MAX_PATCHES);
            if (modelFamily(normalized, "o4-mini")) return patch(172L, OPENAI_MAX_PATCHES);
            if (modelFamily(normalized, "gpt-5-nano")) return patch(150L, OPENAI_MAX_PATCHES);
            if (modelFamily(normalized, "gpt-5.6-sol") || modelFamily(normalized, "gpt-5.6-terra")
                || modelFamily(normalized, "gpt-5.6-luna")) return patch(120L, 30_000L);
            if (modelFamily(normalized, "gpt-5.5")) return patch(120L, 10_000L);
            if (modelFamily(normalized, "gpt-5.4") || modelFamily(normalized, "gpt-5.4-mini")
                || modelFamily(normalized, "gpt-5.4-nano")) return patch(120L, 2_500L);
            if (modelFamily(normalized, "gpt-5.2")) return patch(120L, 6_144L);
            if (modelFamily(normalized, "gpt-5-mini")) return patch(120L, OPENAI_MAX_PATCHES);
            return null;
        }

        /** 只接受精确模型名或官方日期快照后缀，防止自定义前后缀误命中已知算法。 */
        private static boolean modelFamily(String model, String family) {
            String datedSnapshot = java.util.regex.Pattern.quote(family) + "-\\d{4}-\\d{2}-\\d{2}";
            return model.equals(family) || model.matches(datedSnapshot);
        }

        /** 构造 tile family 计量元数据。 */
        private static OpenAiTokenization tile(long baseTokens, long tileTokens) {
            return new OpenAiTokenization(true, baseTokens, tileTokens, 0L);
        }

        /** 构造 patch family 计量元数据，unit 使用百分之一 multiplier。 */
        private static OpenAiTokenization patch(long multiplierHundredths, long patchBudget) {
            return new OpenAiTokenization(false, 0L, multiplierHundredths, patchBudget);
        }
    }
}
