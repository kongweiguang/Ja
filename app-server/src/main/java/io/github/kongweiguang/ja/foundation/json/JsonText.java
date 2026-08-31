// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.json;

import java.util.Objects;

/**
 * 保留 JSON 字符串原文的强类型标量，不施加协议之外的内容归一化。
 */
public record JsonText(String value) implements JsonValue {
    /**
     * 只拒绝 Java null；空串和控制字符由 JSON 编码器按标准转义，不能在基础值层擅自改变。
     */
    public JsonText {
        Objects.requireNonNull(value, "value");
    }
}
