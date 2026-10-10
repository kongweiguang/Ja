// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.json;

/**
 * 将 JSON 布尔值与字符串、数字显式区分，防止弱类型 coercion 越过领域边界。
 *
 * @param value 布尔值
 */
public record JsonBoolean(boolean value) implements JsonValue {
}
