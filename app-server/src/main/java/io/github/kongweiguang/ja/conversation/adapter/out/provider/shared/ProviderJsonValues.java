// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.foundation.json.JacksonJsonValues;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonValue;

import java.util.Objects;

/**
 * 在 Provider Adapter 内转换 Jackson 与严格 JSON 值，防止 JsonNode 泄漏到 conversation 端口。
 */
public final class ProviderJsonValues {
    /**
     * 将 sealed 值穷举映射为 Jackson 树，新增值类型时由编译器强制补齐 Wire 适配。
     */
    public static JsonNode toNode(JsonValue value) {
        return JacksonJsonValues.toNode(AbstractStreamingModelAdapter.JSON, value);
    }

    /**
     * 将 Provider Tool arguments 收紧为对象并深复制，数组或标量不得进入 Agent Loop。
     */
    public static JsonObject toObject(JsonNode node) {
        JsonValue value = fromNode(node);
        if (!(value instanceof JsonObject object)) {
            throw new IllegalArgumentException("Provider Tool arguments must be an object");
        }
        return object;
    }

    /**
     * 从 Jackson 树恢复精确 JSON 值，数字统一使用 BigDecimal 且 null 显式建模。
     */
    public static JsonValue fromNode(JsonNode node) {
        Objects.requireNonNull(node, "node");
        try {
            return JacksonJsonValues.fromNode(node);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Provider JSON value has an unsupported shape", failure);
        }
    }

    /**
     * 序列化严格值时不暴露正文到异常，保持 Provider 请求错误可安全记录。
     */
    public static String write(JsonValue value) {
        try {
            return AbstractStreamingModelAdapter.JSON.writeValueAsString(toNode(value));
        } catch (Exception failure) {
            throw new ProviderProtocolException(
                    "REQUEST_ENCODING", "JSON value could not be encoded", false);
        }
    }

    /**
     * 纯静态 Adapter 禁止实例化，避免产生无意义的运行时状态。
     */
    private ProviderJsonValues() {
    }
}
