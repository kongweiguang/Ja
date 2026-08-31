// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.foundation.json.JacksonJsonValues;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonValue;

import java.util.Map;
import java.util.Objects;

/**
 * 将 MCP SDK 的弱类型对象图限制在 Adapter 内，并对 Kernel 暴露严格 JSON 值。
 */
public final class McpJsonValues {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    /**
     * 通过 Jackson 树校验 SDK 值并恢复 sealed 模型，拒绝二进制或非 JSON Java 对象。
     */
    public static JsonValue fromSdk(ObjectMapper mapper, Object value) {
        Objects.requireNonNull(mapper, "mapper");
        try {
            return JacksonJsonValues.fromNode(mapper.valueToTree(value));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("MCP value is not strict JSON", failure);
        }
    }

    /**
     * 将 SDK Schema 收紧为对象；非对象输入不能进入冻结 ToolSpec。
     */
    public static JsonObject objectFromSdk(ObjectMapper mapper, Object value) {
        JsonValue converted = fromSdk(mapper, value);
        if (!(converted instanceof JsonObject object)) {
            throw new IllegalArgumentException("MCP JSON value must be an object");
        }
        return object;
    }

    /**
     * 只在 SDK 调用瞬间还原 Map，并确保返回图由 Jackson 新建而非领域集合别名。
     */
    public static Map<String, Object> toSdkArguments(ObjectMapper mapper, JsonObject arguments) {
        return mapper.convertValue(JacksonJsonValues.toNode(mapper, arguments), OBJECT_MAP);
    }

    /**
     * 将强类型值映射为 Jackson 树，供 Schema 校验、大小计算和目录摘要使用。
     */
    public static JsonNode toNode(ObjectMapper mapper, JsonValue value) {
        return JacksonJsonValues.toNode(mapper, value);
    }

    /**
     * 纯静态防腐层禁止实例化，避免创建第二个 MCP JSON 状态所有者。
     */
    private McpJsonValues() {
    }
}
