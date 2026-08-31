// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 将 OpenAI strict schema 产生的 null 占位还原为 Ja Tool 的原始可选字段语义。
 *
 * <p>该职责与 wire schema 规范化分离，避免请求编码策略和响应参数还原共享递归状态。
 * 原始 schema 仍由权威参数校验器最终验证，因此这里只删除已证明可选且不可为 null 的占位。</p>
 */
final class OpenAiStrictArgumentRestorer {
    private static final int MAX_SCHEMA_DEPTH = 128;

    /**
     * 纯静态策略不允许实例化，防止请求之间意外共享遍历状态。
     */
    private OpenAiStrictArgumentRestorer() {
    }

    /**
     * 在深拷贝上还原参数，既不修改冻结的 Tool schema，也不修改 Provider 原始参数树。
     */
    static ObjectNode restore(JsonNode sourceSchema, JsonNode wireArguments) {
        OpenAiStrictSchemaNormalizer.normalizeRoot(sourceSchema);
        if (wireArguments == null || !wireArguments.isObject()) {
            throw invalid("Tool arguments must be an object");
        }
        ObjectNode restored = ((ObjectNode) wireArguments).deepCopy();
        restoreArgumentNode(sourceSchema, List.of(sourceSchema), restored, 0);
        return restored;
    }

    /**
     * 汇总同一值的全部 schema 视图后再递归，避免组合分支顺序改变还原结果。
     */
    private static void restoreArgumentNode(JsonNode root, List<JsonNode> schemas,
                                            JsonNode value, int depth) {
        if (depth > MAX_SCHEMA_DEPTH) {
            throw invalid("Tool arguments exceed schema traversal depth");
        }
        List<JsonNode> views = new ArrayList<>();
        for (JsonNode schema : schemas) {
            collectSchemaViews(root, schema, value, views, new HashSet<>(), depth);
        }
        if (value.isObject()) {
            restoreObjectArguments(root, views, (ObjectNode) value, depth + 1);
        } else if (value.isArray()) {
            restoreArrayArguments(root, views, (ArrayNode) value, depth + 1);
        }
    }

    /**
     * 收集直接约束、本地引用和组合约束，确保 required 与 nullable 结论完整。
     */
    private static void collectSchemaViews(JsonNode root, JsonNode schema, JsonNode value,
                                           List<JsonNode> views, Set<String> referenceStack,
                                           int depth) {
        if (depth > MAX_SCHEMA_DEPTH || schema == null || !schema.isObject()) {
            throw invalid("Tool argument schema traversal is invalid");
        }
        views.add(schema);
        JsonNode reference = schema.get("$ref");
        if (reference != null) {
            if (!reference.isTextual()) throw invalid("$ref must be textual");
            String valueRef = reference.textValue();
            if (referenceStack.add(valueRef)) {
                try {
                    collectSchemaViews(root, resolveLocalReference(root, valueRef), value,
                            views, referenceStack, depth + 1);
                } finally {
                    referenceStack.remove(valueRef);
                }
            }
        }
        collectCompositionViews(root, schema.get("allOf"), value,
                views, referenceStack, depth, false);
        collectCompositionViews(root, schema.get("anyOf"), value,
                views, referenceStack, depth, true);
        collectCompositionViews(root, schema.get("oneOf"), value,
                views, referenceStack, depth, true);
    }

    /**
     * allOf 全量参与，union 仅选择结构匹配分支，null 占位无法判定时保守遍历全部分支。
     */
    private static void collectCompositionViews(JsonNode root, JsonNode variants, JsonNode value,
                                                List<JsonNode> views, Set<String> referenceStack,
                                                int depth, boolean selectByShape) {
        if (variants == null) return;
        if (!variants.isArray() || variants.isEmpty()) {
            throw invalid("schema composition must be a non-empty array");
        }
        List<JsonNode> selected = new ArrayList<>();
        for (JsonNode variant : variants) {
            if (!selectByShape || couldDescribe(root, variant, value,
                    new HashSet<>(), depth + 1)) {
                selected.add(variant);
            }
        }
        if (selected.isEmpty()) variants.forEach(selected::add);
        for (JsonNode variant : selected) {
            collectSchemaViews(root, variant, value, views, referenceStack, depth + 1);
        }
    }

    /**
     * 只删除全部适用视图均证明为可选且不可为 null 的对象属性。
     */
    private static void restoreObjectArguments(JsonNode root, List<JsonNode> views,
                                               ObjectNode value, int depth) {
        Set<String> required = new HashSet<>();
        for (JsonNode view : views) {
            JsonNode names = view.get("required");
            if (names == null) continue;
            if (!names.isArray()) throw invalid("required must be an array");
            for (JsonNode name : names) {
                if (!name.isTextual()) throw invalid("required contains a non-text name");
                required.add(name.textValue());
            }
        }
        List<String> fieldNames = new ArrayList<>();
        value.fieldNames().forEachRemaining(fieldNames::add);
        for (String fieldName : fieldNames) {
            List<JsonNode> childSchemas = new ArrayList<>();
            for (JsonNode view : views) {
                JsonNode properties = view.get("properties");
                if (properties != null && properties.isObject() && properties.has(fieldName)) {
                    childSchemas.add(properties.get(fieldName));
                }
            }
            if (childSchemas.isEmpty()) continue;
            JsonNode child = value.get(fieldName);
            if (child.isNull() && !required.contains(fieldName)
                && childSchemas.stream().noneMatch(schema ->
                    acceptsNull(root, schema, new HashSet<>(), depth))) {
                value.remove(fieldName);
            } else if (!child.isNull()) {
                restoreArgumentNode(root, childSchemas, child, depth);
            }
        }
    }

    /**
     * 按 tuple 或同构 items 规则递归数组元素，同时保持数组形状和顺序不变。
     */
    private static void restoreArrayArguments(JsonNode root, List<JsonNode> views,
                                              ArrayNode value, int depth) {
        for (int index = 0; index < value.size(); index++) {
            List<JsonNode> childSchemas = new ArrayList<>();
            for (JsonNode view : views) {
                JsonNode prefixItems = view.get("prefixItems");
                int prefixSize = prefixItems != null && prefixItems.isArray()
                        ? prefixItems.size() : 0;
                if (index < prefixSize) childSchemas.add(prefixItems.get(index));
                JsonNode items = view.get("items");
                if (items != null && items.isObject() && index >= prefixSize) {
                    childSchemas.add(items);
                } else if (items != null && items.isArray() && index < items.size()) {
                    childSchemas.add(items.get(index));
                }
            }
            if (!childSchemas.isEmpty() && !value.get(index).isNull()) {
                restoreArgumentNode(root, childSchemas, value.get(index), depth);
            }
        }
    }

    /**
     * 仅解析冻结文档内的 JSON Pointer，禁止通过外部引用改变请求边界。
     */
    private static JsonNode resolveLocalReference(JsonNode root, String reference) {
        if ("#".equals(reference)) return root;
        if (!reference.startsWith("#/")) {
            throw invalid("only local JSON Pointer references are supported");
        }
        JsonNode resolved = root.at(reference.substring(1));
        if (resolved.isMissingNode() || !resolved.isObject()) {
            throw invalid("local JSON Pointer reference is unresolved");
        }
        return resolved;
    }

    /**
     * 仅用结构、const 和 enum 证据选择 union 分支，不做类型强制转换。
     */
    private static boolean couldDescribe(JsonNode root, JsonNode schema, JsonNode value,
                                         Set<String> referenceStack, int depth) {
        if (depth > MAX_SCHEMA_DEPTH || schema == null || !schema.isObject()) return false;
        JsonNode type = schema.get("type");
        if (type != null && !typeAllows(type, value)) return false;
        JsonNode constant = schema.get("const");
        if (constant != null && !constant.equals(value)) return false;
        JsonNode enumeration = schema.get("enum");
        if (enumeration != null && enumeration.isArray()) {
            boolean match = false;
            for (JsonNode candidate : enumeration) {
                if (candidate.equals(value)) match = true;
            }
            if (!match) return false;
        }
        JsonNode reference = schema.get("$ref");
        if (reference != null && reference.isTextual()
            && referenceStack.add(reference.textValue())) {
            try {
                if (!couldDescribe(root, resolveLocalReference(root, reference.textValue()), value,
                        referenceStack, depth + 1)) return false;
            } finally {
                referenceStack.remove(reference.textValue());
            }
        }
        JsonNode allOf = schema.get("allOf");
        if (allOf != null) {
            for (JsonNode variant : allOf) {
                if (!couldDescribe(root, variant, value, referenceStack, depth + 1)) return false;
            }
        }
        for (String field : new String[]{"anyOf", "oneOf"}) {
            JsonNode variants = schema.get(field);
            if (variants == null) continue;
            boolean match = false;
            for (JsonNode variant : variants) {
                if (couldDescribe(root, variant, value, referenceStack, depth + 1)) match = true;
            }
            if (!match) return false;
        }
        return true;
    }

    /**
     * 将 Jackson 节点类别与 JSON Schema 原始类型精确比较。
     */
    private static boolean typeAllows(JsonNode type, JsonNode value) {
        if (type.isTextual()) return valueHasType(value, type.textValue());
        if (!type.isArray()) return false;
        for (JsonNode candidate : type) {
            if (candidate.isTextual() && valueHasType(value, candidate.textValue())) return true;
        }
        return false;
    }

    /**
     * 映射 JSON 节点类别，不接受字符串到数字等隐式转换。
     */
    private static boolean valueHasType(JsonNode value, String type) {
        return switch (type) {
            case "null" -> value.isNull();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "string" -> value.isTextual();
            default -> false;
        };
    }

    /**
     * 通过本地引用和组合规则证明 null 可接受；遇到条件 schema 时保守保留 null，
     * 避免还原器删除无法完整证明语义的真实参数。
     */
    private static boolean acceptsNull(JsonNode root, JsonNode schema,
                                       Set<String> referenceStack, int depth) {
        if (depth > MAX_SCHEMA_DEPTH || schema == null || !schema.isObject()) return true;
        if (schema.has("not") || schema.has("if")
            || schema.has("then") || schema.has("else")) return true;
        JsonNode type = schema.get("type");
        if (type != null && !containsType(type, "null")) return false;
        JsonNode constant = schema.get("const");
        if (constant != null) return constant.isNull();
        JsonNode enumeration = schema.get("enum");
        if (enumeration != null && enumeration.isArray()) {
            boolean hasNull = false;
            for (JsonNode candidate : enumeration) {
                if (candidate.isNull()) hasNull = true;
            }
            if (!hasNull) return false;
        }
        JsonNode reference = schema.get("$ref");
        if (reference != null) {
            if (!reference.isTextual() || !referenceStack.add(reference.textValue())) return true;
            try {
                if (!acceptsNull(root, resolveLocalReference(root, reference.textValue()),
                        referenceStack, depth + 1)) return false;
            } finally {
                referenceStack.remove(reference.textValue());
            }
        }
        JsonNode allOf = schema.get("allOf");
        if (allOf != null) {
            for (JsonNode variant : allOf) {
                if (!acceptsNull(root, variant, referenceStack, depth + 1)) return false;
            }
        }
        JsonNode anyOf = schema.get("anyOf");
        if (anyOf != null) {
            boolean admitted = false;
            for (JsonNode variant : anyOf) {
                if (acceptsNull(root, variant, referenceStack, depth + 1)) admitted = true;
            }
            if (!admitted) return false;
        }
        JsonNode oneOf = schema.get("oneOf");
        if (oneOf != null) {
            int admitted = 0;
            for (JsonNode variant : oneOf) {
                if (acceptsNull(root, variant, referenceStack, depth + 1)) admitted++;
            }
            if (admitted != 1) return false;
        }
        return true;
    }

    /**
     * 判断 type 是指定原始类型或包含指定 union 成员。
     */
    private static boolean containsType(JsonNode type, String expected) {
        if (type == null) return false;
        if (type.isTextual()) return expected.equals(type.textValue());
        if (!type.isArray()) return false;
        for (JsonNode member : type) {
            if (member.isTextual() && expected.equals(member.textValue())) return true;
        }
        return false;
    }

    /**
     * 生成不包含 schema 或参数内容的本地校验异常。
     */
    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("unsupported strict Tool schema: " + reason);
    }
}
