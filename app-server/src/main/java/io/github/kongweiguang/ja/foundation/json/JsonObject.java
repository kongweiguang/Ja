// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;

/**
 * 保持成员插入顺序的不可变 JSON 对象，稳定 Provider、持久化与测试输出的遍历次序。
 */
public record JsonObject(SequencedMap<String, JsonValue> members) implements JsonValue {
    /**
     * 复制调用方集合并拒绝 Java null；JSON null 必须显式使用 JsonNull，避免缺省与 null 混淆。
     */
    public JsonObject {
        Objects.requireNonNull(members, "members");
        LinkedHashMap<String, JsonValue> copy = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : members.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "JSON object key");
            if (key.isEmpty()) throw new IllegalArgumentException("JSON object key must not be empty");
            copy.put(key, Objects.requireNonNull(entry.getValue(), "JSON object value"));
        }
        members = Collections.unmodifiableSequencedMap(copy);
    }

    /**
     * 接受普通 Map 作为构造输入，但立即复制为有序不可变结构，避免把 Map 泄漏为模型契约。
     */
    public JsonObject(Map<String, ? extends JsonValue> members) {
        this(sequencedCopy(members));
    }

    /**
     * 返回保持插入顺序的防御性只读快照；即使底层实现已不可变，也不把 record 的内部表示
     * 暴露给 Provider、持久化或 Tool 调用方。
     */
    @Override
    public SequencedMap<String, JsonValue> members() {
        return Collections.unmodifiableSequencedMap(new LinkedHashMap<>(members));
    }

    /**
     * 返回共享语义的空对象，而不是用 null 表示没有成员。
     */
    public static JsonObject empty() {
        return new JsonObject(new LinkedHashMap<>());
    }

    /**
     * 按成员名读取强类型值；缺失返回 null，与 Map 泄漏不同，调用方仍只能观察 JsonValue 闭集。
     */
    public JsonValue get(String name) {
        return members.get(Objects.requireNonNull(name, "name"));
    }

    /**
     * 显式查询成员存在性，使缺失与值为 JsonNull 的情况保持可区分。
     */
    public boolean containsKey(String name) {
        return members.containsKey(Objects.requireNonNull(name, "name"));
    }

    /**
     * 返回是否没有任何成员，供空 Tool arguments 和 Schema 边界断言使用。
     */
    public boolean isEmpty() {
        return members.isEmpty();
    }

    /**
     * 将普通 Map 收敛为 SequencedMap，使公开 record 组件准确声明顺序保证。
     */
    private static SequencedMap<String, JsonValue> sequencedCopy(Map<String, ? extends JsonValue> members) {
        Objects.requireNonNull(members, "members");
        LinkedHashMap<String, JsonValue> copy = new LinkedHashMap<>();
        copy.putAll(members);
        return copy;
    }
}
