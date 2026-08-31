// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.json;

/**
 * 显式表达已存在的 JSON null；字段缺省继续由 Optional 或对象成员缺失表达。
 */
public enum JsonNull implements JsonValue {
    /**
     * JSON null 没有身份差异，单例可避免在跨层传递中制造无意义对象。
     */
    INSTANCE
}
