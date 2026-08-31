// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.json;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 为静态 Schema 和 Adapter 映射提供强类型构造器，避免重新引入 Object 可变参数。
 */
public final class JsonObjectBuilder {
    private final Map<String, JsonValue> members = new LinkedHashMap<>();

    /**
     * 构造器只供 JsonObjects 工厂创建，使可变阶段不会被误当作领域值传递。
     */
    JsonObjectBuilder() {
    }

    /**
     * 添加一个已经类型化的值，并拒绝覆盖同名字段以尽早发现 Schema 拼装错误。
     */
    public JsonObjectBuilder put(String name, JsonValue value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (members.putIfAbsent(name, value) != null) {
            throw new IllegalArgumentException("duplicate JSON object member");
        }
        return this;
    }

    /**
     * 以明确文本类型添加成员，避免调用方直接接触弱类型 Object。
     */
    public JsonObjectBuilder putText(String name, String value) {
        return put(name, new JsonText(value));
    }

    /**
     * 以明确布尔类型添加成员，保持 Schema 标志的真实 JSON 类型。
     */
    public JsonObjectBuilder putBoolean(String name, boolean value) {
        return put(name, new JsonBoolean(value));
    }

    /**
     * 以无损整数添加成员，避免数字先经过 double。
     */
    public JsonObjectBuilder putNumber(String name, long value) {
        return put(name, new JsonNumber(value));
    }

    /**
     * 以精确十进制数添加成员，保留调用方 scale。
     */
    public JsonObjectBuilder putNumber(String name, BigDecimal value) {
        return put(name, new JsonNumber(value));
    }

    /**
     * 冻结当前成员快照；后续 Builder 修改不会影响已经构造的对象。
     */
    public JsonObject build() {
        return new JsonObject(members);
    }
}
