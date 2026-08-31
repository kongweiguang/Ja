// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.port.out.JsonValueCodec;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonBoolean;
import io.github.kongweiguang.ja.foundation.json.JsonNull;
import io.github.kongweiguang.ja.foundation.json.JsonNumber;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.foundation.json.JsonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 为跨层测试提供与生产相同形状的 JSON 值往返，避免每个用例复制弱类型 Map Codec。
 */
public final class TestJsonValueCodec implements JsonValueCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 显式编码 sealed 值，使测试不会意外验证 Jackson record 默认形状。
     */
    @Override
    public String encode(JsonValue value) {
        try {
            return mapper.writeValueAsString(toNode(value));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException(failure);
        }
    }

    /**
     * 深复制 JSON 树并保持数值精度，模拟生产组合根的严格解码行为。
     */
    @Override
    public JsonValue decode(String encodedValue) {
        try {
            return fromNode(mapper.readTree(encodedValue));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException(failure);
        }
    }

    /**
     * 将测试领域值穷举映射为 Jackson 节点，确保所有 sealed 分支均有覆盖入口。
     */
    private JsonNode toNode(JsonValue value) {
        return switch (value) {
            case JsonObject object -> {
                ObjectNode node = mapper.createObjectNode();
                object.members().forEach((name, member) -> node.set(name, toNode(member)));
                yield node;
            }
            case JsonArray array -> {
                ArrayNode node = mapper.createArrayNode();
                array.values().forEach(member -> node.add(toNode(member)));
                yield node;
            }
            case JsonText text -> mapper.getNodeFactory().textNode(text.value());
            case JsonNumber number -> mapper.getNodeFactory().numberNode(number.value());
            case JsonBoolean bool -> mapper.getNodeFactory().booleanNode(bool.value());
            case JsonNull ignored -> mapper.getNodeFactory().nullNode();
        };
    }

    /**
     * 从 Jackson 节点恢复不可变测试值，拒绝所有非标准 JSON 节点。
     */
    private JsonValue fromNode(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonValue> members = new LinkedHashMap<>();
            node.properties().forEach(entry -> members.put(entry.getKey(), fromNode(entry.getValue())));
            return new JsonObject(members);
        }
        if (node.isArray()) {
            List<JsonValue> values = new ArrayList<>(node.size());
            node.forEach(member -> values.add(fromNode(member)));
            return new JsonArray(values);
        }
        if (node.isTextual()) return new JsonText(node.textValue());
        if (node.isNumber()) return new JsonNumber(node.decimalValue());
        if (node.isBoolean()) return new JsonBoolean(node.booleanValue());
        if (node.isNull()) return JsonNull.INSTANCE;
        throw new IllegalArgumentException("unsupported JSON test value");
    }
}
