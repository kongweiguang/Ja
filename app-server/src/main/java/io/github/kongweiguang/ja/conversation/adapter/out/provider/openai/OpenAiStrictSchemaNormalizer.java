// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 将支持的 Tool JSON Schema 子集转换为 OpenAI strict function-tool 结构。
 *
 * <p>转换器只操作 Jackson 深拷贝，避免修改 Turn 冻结的 Tool 定义。OpenAI 要求对象属性全部进入
 * {@code required}，因此原始可选属性在 wire schema 中改为 nullable，并由独立还原器恢复可选语义。</p>
 */
final class OpenAiStrictSchemaNormalizer {
    private static final Set<String> JSON_TYPES = Set.of(
            "null", "boolean", "object", "array", "number", "integer", "string");

    /**
     * 转换 Policy 无状态，禁止实例化以避免请求之间共享状态或替换策略。
     */
    private OpenAiStrictSchemaNormalizer() {
    }

    /**
     * 校验并转换函数参数根节点，根节点必须是 object schema。
     */
    static ObjectNode normalizeRoot(JsonNode source) {
        JsonNode normalized = normalizeNode(source, "$", true);
        if (!isType(normalized.get("type"), "object")) {
            throw invalid("root schema must have object type");
        }
        return (ObjectNode) normalized;
    }

    /**
     * 递归复制受支持的 schema 节点，并施加 strict object 不变量。
     */
    private static JsonNode normalizeNode(JsonNode source, String path, boolean root) {
        if (source == null || !source.isObject()) {
            throw invalid(path + " must be a schema object");
        }
        ObjectNode result = ((ObjectNode) source).deepCopy();
        JsonNode type = source.get("type");
        validateType(type, path);

        boolean objectType = containsType(type, "object");
        boolean hasObjectKeywords = source.has("properties")
                                    || source.has("required") || source.has("additionalProperties");
        if (hasObjectKeywords && !objectType) {
            throw invalid(path + " object keywords require object type");
        }
        if (objectType) {
            normalizeObject(result, source, path);
        }
        normalizeSchemaNode(result, source, "items", path);
        normalizeSchemaArray(result, source, "prefixItems", path);
        normalizeSchemaArray(result, source, "anyOf", path);
        normalizeSchemaArray(result, source, "oneOf", path);
        normalizeSchemaArray(result, source, "allOf", path);
        normalizeDefinitions(result, source, "$defs", path);
        normalizeDefinitions(result, source, "definitions", path);

        if (containsType(type, "array") && !source.has("items") && !source.has("prefixItems")) {
            throw invalid(path + " array schema must declare items");
        }
        if (source.has("additionalItems") || source.has("patternProperties")
            || source.has("dependentSchemas") || source.has("unevaluatedProperties")) {
            throw invalid(path + " uses unsupported open-schema keywords");
        }
        if (root && !objectType) {
            throw invalid(path + " root must be an object schema");
        }
        return result;
    }

    /**
     * 保留各属性原始约束后重写对象节点，确保所有 wire 属性均为 required。
     */
    private static void normalizeObject(ObjectNode result, JsonNode source, String path) {
        JsonNode properties = source.get("properties");
        if (properties != null && !properties.isObject()) {
            throw invalid(path + ".properties must be an object");
        }
        ObjectNode normalizedProperties = JsonNodeFactory.instance.objectNode();
        if (properties != null) {
            Iterator<Map.Entry<String, JsonNode>> fields = ((ObjectNode) properties).properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                normalizedProperties.set(field.getKey(),
                        normalizeNode(field.getValue(), path + ".properties." + field.getKey(), false));
            }
        }

        Set<String> originalRequired = readRequired(source.get("required"), normalizedProperties, path);
        JsonNode additionalProperties = source.get("additionalProperties");
        if (additionalProperties != null
            && (!additionalProperties.isBoolean() || additionalProperties.booleanValue())) {
            // 静默关闭开放 schema 会改变 Tool 语义，因此直接拒绝。
            throw invalid(path + ".additionalProperties must be false");
        }
        ArrayList<String> sortedNames = new ArrayList<>();
        Iterator<String> namesIterator = normalizedProperties.fieldNames();
        while (namesIterator.hasNext()) sortedNames.add(namesIterator.next());
        sortedNames.sort(String::compareTo);
        ArrayNode required = JsonNodeFactory.instance.arrayNode();
        for (String name : sortedNames) {
            JsonNode property = normalizedProperties.get(name);
            if (!originalRequired.contains(name)) {
                normalizedProperties.set(name, nullable(property));
            }
            required.add(name);
        }
        result.set("properties", normalizedProperties);
        result.set("required", required);
        result.put("additionalProperties", false);
    }

    /**
     * 校验原始 required 集合，并拒绝不存在、重复或非文本的属性名。
     */
    private static Set<String> readRequired(JsonNode required, ObjectNode properties, String path) {
        if (required == null) return Set.of();
        if (!required.isArray()) throw invalid(path + ".required must be an array");
        Set<String> names = new HashSet<>();
        for (JsonNode name : required) {
            if (!name.isTextual() || !names.add(name.textValue()) || !properties.has(name.textValue())) {
                throw invalid(path + ".required contains an invalid property");
            }
        }
        return names;
    }

    /**
     * 递归处理 items 等单个子 schema，并保留 tuple 数组形式。
     */
    private static void normalizeSchemaNode(ObjectNode result, JsonNode source,
                                            String field, String path) {
        JsonNode child = source.get(field);
        if (child == null) return;
        if (child.isArray()) {
            ArrayNode normalized = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : child) {
                normalized.add(normalizeNode(item, path + "." + field, false));
            }
            result.set(field, normalized);
        } else {
            result.set(field, normalizeNode(child, path + "." + field, false));
        }
    }

    /**
     * 递归处理 schema 数组，不接受标量或 boolean schema 捷径。
     */
    private static void normalizeSchemaArray(ObjectNode result, JsonNode source,
                                             String field, String path) {
        JsonNode child = source.get(field);
        if (child == null) return;
        if (!child.isArray() || child.isEmpty()) throw invalid(path + "." + field + " must be non-empty");
        ArrayNode normalized = JsonNodeFactory.instance.arrayNode();
        for (JsonNode item : child) {
            normalized.add(normalizeNode(item, path + "." + field, false));
        }
        result.set(field, normalized);
    }

    /**
     * 转换命名定义，防止本地引用绕过嵌套对象的 strict 约束。
     */
    private static void normalizeDefinitions(ObjectNode result, JsonNode source,
                                             String field, String path) {
        JsonNode definitions = source.get(field);
        if (definitions == null) return;
        if (!definitions.isObject()) throw invalid(path + "." + field + " must be an object");
        ObjectNode normalized = JsonNodeFactory.instance.objectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = ((ObjectNode) definitions).properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> definition = fields.next();
            normalized.set(definition.getKey(),
                    normalizeNode(definition.getValue(), path + "." + field + "." + definition.getKey(), false));
        }
        result.set(field, normalized);
    }

    /**
     * 仅为原始可选值加入 null，并完整保留已有 nullable union。
     */
    private static JsonNode nullable(JsonNode source) {
        if (acceptsNull(source)) return source;
        JsonNode type = source.get("type");
        if (type != null && type.isTextual()) {
            ObjectNode copy = ((ObjectNode) source).deepCopy();
            ArrayNode union = JsonNodeFactory.instance.arrayNode();
            union.add(type.textValue()).add("null");
            copy.set("type", union);
            return copy;
        }
        ObjectNode union = JsonNodeFactory.instance.objectNode();
        ArrayNode variants = union.putArray("anyOf");
        variants.add(source);
        variants.addObject().put("type", "null");
        return union;
    }

    /**
     * 判断 type 数组、组合、enum 或 const 是否已经允许 JSON null。
     */
    private static boolean acceptsNull(JsonNode source) {
        JsonNode type = source.get("type");
        if (containsType(type, "null")) return true;
        for (String field : new String[]{"anyOf", "oneOf"}) {
            JsonNode variants = source.get(field);
            if (variants != null && variants.isArray()) {
                for (JsonNode variant : variants) {
                    if (variant.isObject() && (isType(variant.get("type"), "null")
                                               || (variant.get("const") != null && variant.get("const").isNull()))) {
                        return true;
                    }
                }
            }
        }
        JsonNode enumeration = source.get("enum");
        if (enumeration != null && enumeration.isArray()) {
            for (JsonNode value : enumeration) if (value.isNull()) return true;
        }
        JsonNode constant = source.get("const");
        return constant != null && constant.isNull();
    }

    /**
     * 校验 JSON Schema 类型词汇，并允许 strict schema 所需的 nullable union。
     */
    private static void validateType(JsonNode type, String path) {
        if (type == null) return;
        if (type.isTextual()) {
            if (!JSON_TYPES.contains(type.textValue())) throw invalid(path + ".type is unknown");
            return;
        }
        if (!type.isArray() || type.isEmpty()) throw invalid(path + ".type must be a JSON type");
        Set<String> seen = new HashSet<>();
        for (JsonNode value : type) {
            if (!value.isTextual() || !JSON_TYPES.contains(value.textValue())
                || !seen.add(value.textValue())) {
                throw invalid(path + ".type contains an invalid member");
            }
        }
    }

    /**
     * 判断 schema type 是指定文本类型或包含指定 union 成员。
     */
    private static boolean containsType(JsonNode type, String expected) {
        if (type == null) return false;
        if (type.isTextual()) return expected.equals(type.textValue());
        if (!type.isArray()) return false;
        for (JsonNode member : type) if (isType(member, expected)) return true;
        return false;
    }

    /**
     * 将节点与指定 JSON Schema 文本类型精确比较。
     */
    private static boolean isType(JsonNode type, String expected) {
        return type != null && type.isTextual() && expected.equals(type.textValue());
    }

    /**
     * 生成不回显 schema 内容的内部校验异常。
     */
    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("unsupported strict Tool schema: " + reason);
    }
}
