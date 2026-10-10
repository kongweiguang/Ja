// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.experience.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** JSON Schema 在 HTTP 与 MCP 边界使用的深度不可变快照。 */
public final class JsonSchemaSnapshot {
    private static final int MAX_NESTING = 128;

    /** 快照只通过静态边界函数创建，避免无状态实例造成额外生命周期。 */
    private JsonSchemaSnapshot() { }

    /**
     * 复制 JSON 值树而保留对象顺序和显式 null，拒绝循环及过深输入以免递归耗尽栈。
     */
    public static Map<String, Object> copyOf(Map<String, ?> schema) {
        return freezeMap(Objects.requireNonNull(schema, "schema"), new IdentityHashMap<>(), 0);
    }

    /** 保留 Schema 声明顺序，并让嵌套映射与顶层一起不可变。 */
    private static Map<String, Object> freezeMap(Map<?, ?> source,
                                                 IdentityHashMap<Object, Boolean> ancestors,
                                                 int depth) {
        enter(source, ancestors, depth);
        try {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("JSON Schema object keys must be strings");
                }
                copy.put(key, freezeValue(entry.getValue(), ancestors, depth + 1));
            }
            return Collections.unmodifiableMap(copy);
        } finally {
            ancestors.remove(source);
        }
    }

    /** 只接受 JSON 标量、对象和数组，避免把 SDK 的可变对象泄漏给调用方。 */
    private static Object freezeValue(Object value,
                                      IdentityHashMap<Object, Boolean> ancestors,
                                      int depth) {
        if (value == null || isJsonScalar(value)) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return freezeMap(map, ancestors, depth);
        }
        if (value instanceof List<?> list) {
            return freezeList(list, ancestors, depth);
        }
        throw new IllegalArgumentException("JSON Schema contains a non-JSON value");
    }

    /** 数组快照保留 null 元素，并拒绝后续写入。 */
    private static List<Object> freezeList(List<?> source,
                                           IdentityHashMap<Object, Boolean> ancestors,
                                           int depth) {
        enter(source, ancestors, depth);
        try {
            ArrayList<Object> copy = new ArrayList<>(source.size());
            for (Object value : source) {
                copy.add(freezeValue(value, ancestors, depth + 1));
            }
            return Collections.unmodifiableList(copy);
        } finally {
            ancestors.remove(source);
        }
    }

    /** 限制容器递归并检测当前路径中的循环引用，拒绝非 JSON 的对象图。 */
    private static void enter(Object container,
                              IdentityHashMap<Object, Boolean> ancestors,
                              int depth) {
        if (depth > MAX_NESTING) {
            throw new IllegalArgumentException("JSON Schema nesting limit exceeded");
        }
        if (ancestors.put(container, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("JSON Schema must not contain cycles");
        }
    }

    /** 仅共享不可变 JSON 数值类型；可变或非有限数值不属于有效 JSON Schema。 */
    private static boolean isJsonScalar(Object value) {
        if (value instanceof Double number) {
            return Double.isFinite(number);
        }
        if (value instanceof Float number) {
            return Float.isFinite(number);
        }
        return value instanceof String || value instanceof Boolean || value instanceof Byte
                || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger || value instanceof BigDecimal;
    }
}
