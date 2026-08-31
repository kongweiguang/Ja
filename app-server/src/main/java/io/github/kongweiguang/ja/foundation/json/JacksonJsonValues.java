// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 在 Jackson 边界与 Ja 严格 JSON 闭集之间做唯一递归映射，避免各 Adapter 漂移出不同数字、
 * null 或成员顺序语义。
 */
public final class JacksonJsonValues {
    /**
     * 穷举 sealed 值并使用调用方持有的 ObjectMapper 创建节点；本类型不创建第二套 Mapper 配置。
     */
    public static JsonNode toNode(ObjectMapper mapper, JsonValue value) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(value, "value");
        return switch (value) {
            case JsonObject object -> {
                ObjectNode node = mapper.createObjectNode();
                object.members().forEach((name, member) -> node.set(name, toNode(mapper, member)));
                yield node;
            }
            case JsonArray array -> {
                ArrayNode node = mapper.createArrayNode();
                array.values().forEach(member -> node.add(toNode(mapper, member)));
                yield node;
            }
            case JsonText text -> mapper.getNodeFactory().textNode(text.value());
            case JsonNumber number -> mapper.getNodeFactory().numberNode(number.value());
            case JsonBoolean bool -> mapper.getNodeFactory().booleanNode(bool.value());
            case JsonNull ignored -> mapper.getNodeFactory().nullNode();
        };
    }

    /**
     * 深复制 Jackson 树并保留对象顺序与 BigDecimal 精度；缺失、二进制和其它非 JSON 节点
     * 统一失败，由外层 Adapter 映射为各自稳定错误分类。
     */
    public static JsonValue fromNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isBinary()) {
            throw new IllegalArgumentException("JSON value has an unsupported shape");
        }
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
        throw new IllegalArgumentException("JSON value has an unsupported shape");
    }

    /** 纯静态映射器禁止实例化，ObjectMapper 生命周期仍由组合根或具体 Adapter 管理。 */
    private JacksonJsonValues() {
    }
}
