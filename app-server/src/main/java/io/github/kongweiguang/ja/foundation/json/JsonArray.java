// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.json;

import java.util.List;
import java.util.Objects;

/**
 * 保存稳定顺序的不可变 JSON 数组，元素只能来自 JsonValue 闭集。
 */
public record JsonArray(List<JsonValue> values) implements JsonValue {
    /**
     * 使用 List.copyOf 同时冻结集合并拒绝 Java null，防止 Adapter 之后修改已准入参数。
     */
    public JsonArray {
        Objects.requireNonNull(values, "values");
        values = List.copyOf(values);
    }
}
