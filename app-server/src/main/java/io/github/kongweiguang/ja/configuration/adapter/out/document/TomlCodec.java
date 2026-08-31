// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationError;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * TomlCodec 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
 */
final class TomlCodec {
    private static final String NULL_SENTINEL_KEY = "__ja_null";
    private final ObjectMapper mapper;

    /**
     * 复用应用统一 ObjectMapper 完成 TOML 中间 JSON 转换，避免两套数值语义。
     */
    TomlCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 将 TOML 严格解析为根对象，任何 TOML 或 JSON 中间转换错误均归为损坏配置。
     */
    ObjectNode parse(String text) {
        if (text == null) throw corrupt();
        final TomlParseResult parsed;
        try {
            parsed = Toml.parse(text);
        } catch (RuntimeException failure) {
            throw new ConfigurationError(ConfigurationError.Code.CORRUPT_CONFIG,
                    "configuration is corrupt", failure);
        }
        if (parsed.hasErrors()) throw corrupt();
        try {
            JsonNode json = mapper.readTree(parsed.toJson());
            if (!(json instanceof ObjectNode object)) throw corrupt();
            restoreNullSentinels(object);
            return object;
        } catch (JsonProcessingException failure) {
            throw new ConfigurationError(ConfigurationError.Code.CORRUPT_CONFIG,
                    "configuration is corrupt", failure);
        }
    }

    /**
     * 按根标量、对象数组和根表的固定顺序编码，只产生新配置基线可读的 TOML。
     */
    String write(ObjectNode root) {
        if (root == null) throw new ConfigurationError(ConfigurationError.Code.INVALID_DOCUMENT,
                "configuration document is invalid");
        StringBuilder output = new StringBuilder(1024);
        writeRootScalars(root, output);
        writeArrayTables(root, output);
        writeRootTables(root, output);
        return output.toString();
    }

    /**
     * 先写入根级标量和标量数组，避免 TOML 表头改变后续键的归属。
     */
    private static void writeRootScalars(ObjectNode root, StringBuilder output) {
        Iterator<Map.Entry<String, JsonNode>> fields = root.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (value.isObject() || isArrayOfObjects(value)) continue;
            appendAssignment(output, field.getKey(), value);
        }
    }

    /**
     * 将根级对象数组编码为 TOML array-of-tables，保留 catalog 条目顺序。
     */
    private static void writeArrayTables(ObjectNode root, StringBuilder output) {
        Iterator<Map.Entry<String, JsonNode>> fields = root.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!(field.getValue() instanceof ArrayNode array) || !isArrayOfObjects(array)) continue;
            for (JsonNode element : array) {
                output.append("[[").append(escapeKey(field.getKey())).append("]]\n");
                writeObjectBody(output, field.getKey(), (ObjectNode) element);
            }
        }
    }

    /**
     * 在根标量之后写入根对象表，使嵌套路径的 TOML 作用域明确。
     */
    private static void writeRootTables(ObjectNode root, StringBuilder output) {
        Iterator<Map.Entry<String, JsonNode>> fields = root.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!(field.getValue() instanceof ObjectNode object)) continue;
            output.append('[').append(escapeKey(field.getKey())).append("]\n");
            writeObjectBody(output, field.getKey(), object);
        }
    }

    /**
     * 先输出当前表赋值再递归子表，避免子表头导致父字段落入错误作用域。
     */
    private static void writeObjectBody(StringBuilder output, String tablePath, ObjectNode object) {
        Iterator<Map.Entry<String, JsonNode>> fields = object.properties().iterator();
        List<Map.Entry<String, JsonNode>> nested = new ArrayList<>();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getValue().isObject() || isArrayOfObjects(field.getValue())) nested.add(field);
            else appendAssignment(output, field.getKey(), field.getValue());
        }
        for (Map.Entry<String, JsonNode> field : nested) {
            if (field.getValue() instanceof ObjectNode child) {
                String childPath = tablePath + '.' + escapeKey(field.getKey());
                output.append('[').append(childPath).append("]\n");
                writeObjectBody(output, childPath, child);
            } else if (field.getValue() instanceof ArrayNode array && isArrayOfObjects(array)) {
                String childPath = tablePath + '.' + escapeKey(field.getKey());
                for (JsonNode element : array) {
                    output.append("[[").append(childPath).append("]]\n");
                    writeObjectBody(output, childPath, (ObjectNode) element);
                }
            }
        }
    }

    /**
     * 输出 TOML 基线支持的赋值；JSON null 使用私有 inline-table 哨兵保存字段存在性，
     * 因为 TOML 本身没有 null，同时 v3 完整文档必须区分“显式无默认值”和字段缺失。
     */
    private static void appendAssignment(StringBuilder output, String key, JsonNode value) {
        if (value == null || value.isBinary() || value.isMissingNode()
            || (value.isArray() && !isScalarArray(value))) throw corrupt();
        output.append(escapeKey(key)).append(" = ").append(valueToToml(value)).append('\n');
    }

    /**
     * 只编码文本、标量数组、数字、布尔值和可逆 null 哨兵，不为未知 JSON 类型提供字符串降级。
     */
    private static String valueToToml(JsonNode value) {
        if (value.isNull()) return "{ " + NULL_SENTINEL_KEY + " = true }";
        if (value.isTextual()) return quote(value.textValue());
        if (value.isArray()) {
            StringBuilder builder = new StringBuilder("[");
            for (int index = 0; index < value.size(); index++) {
                if (index > 0) builder.append(", ");
                builder.append(valueToToml(value.get(index)));
            }
            return builder.append(']').toString();
        }
        if (value.isNumber() || value.isBoolean()) return value.toString();
        throw corrupt();
    }

    /**
     * 把编码器唯一产生的单键 inline-table 还原为 JSON null；仅接受精确形状可避免普通配置对象
     * 被误判，递归处理则保证 Provider/Model 等任意合法嵌套层级具有相同 round-trip 语义。
     */
    private static void restoreNullSentinels(JsonNode value) {
        if (value instanceof ObjectNode object) {
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            object.properties().forEach(fields::add);
            for (Map.Entry<String, JsonNode> field : fields) {
                JsonNode child = field.getValue();
                if (isNullSentinel(child)) object.putNull(field.getKey());
                else restoreNullSentinels(child);
            }
        } else if (value instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                JsonNode child = array.get(index);
                if (isNullSentinel(child)) array.set(index, array.nullNode());
                else restoreNullSentinels(child);
            }
        }
    }

    /** 只识别编码器保留的精确单键对象，拒绝带额外字段或非 true 值的近似形状。 */
    private static boolean isNullSentinel(JsonNode value) {
        return value instanceof ObjectNode object && object.size() == 1
               && object.path(NULL_SENTINEL_KEY).isBoolean()
               && object.path(NULL_SENTINEL_KEY).booleanValue();
    }

    /**
     * 生成 TOML 基本字符串，显式转义引号、反斜杠和控制字符。
     */
    private static String quote(String value) {
        StringBuilder output = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> output.append("\\\\");
                case '"' -> output.append("\\\"");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (Character.isISOControl(character)) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else output.append(character);
                }
            }
        }
        return output.append('"').toString();
    }

    /**
     * 保留合法 bare key，其他键统一引用，避免点号被误解为表路径。
     */
    private static String escapeKey(String key) {
        if (key != null && key.matches("[A-Za-z0-9_-]+")) return key;
        return quote(key == null ? "" : key);
    }

    /**
     * 仅当数组所有元素均为 TOML 支持的 JSON 标量时才允许内联编码。
     */
    private static boolean isScalarArray(JsonNode value) {
        if (!value.isArray()) return false;
        for (JsonNode element : value) {
            if (!(element.isTextual() || element.isNumber() || element.isBoolean())) return false;
        }
        return true;
    }

    /**
     * 只将非空且元素全为对象的数组识别为 array-of-tables。
     */
    private static boolean isArrayOfObjects(JsonNode value) {
        if (!(value instanceof ArrayNode array) || array.isEmpty()) return false;
        for (JsonNode element : array) if (!(element instanceof ObjectNode)) return false;
        return true;
    }

    /**
     * 统一产生不包含 TOML 正文、路径或解析器细节的损坏配置错误。
     */
    private static ConfigurationError corrupt() {
        return new ConfigurationError(ConfigurationError.Code.CORRUPT_CONFIG,
                "configuration is corrupt");
    }
}
