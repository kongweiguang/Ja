// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.json;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 使用 BigDecimal 保存 JSON 数字的十进制精度和 scale，避免 double 往返造成语义漂移。
 */
public record JsonNumber(BigDecimal value) implements JsonValue {
    /**
     * 保留调用方提供的精确十进制表示，不执行 stripTrailingZeros 等有损展示归一化。
     */
    public JsonNumber {
        Objects.requireNonNull(value, "value");
    }

    /**
     * 从 long 无损构造常用整数，避免 Schema 定义散落 BigDecimal 样板代码。
     */
    public JsonNumber(long value) {
        this(BigDecimal.valueOf(value));
    }
}
