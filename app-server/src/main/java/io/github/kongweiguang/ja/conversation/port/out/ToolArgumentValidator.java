// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.foundation.json.JsonObject;

import java.util.Optional;

/** 在应用 Tool Runner 与具体 JSON Schema 引擎之间提供无值泄漏的参数校验端口。 */
@FunctionalInterface
public interface ToolArgumentValidator {
    /**
     * 返回空值表示参数有效；失败原因只能包含 Schema 字段、位置和约束类型，禁止包含参数值。
     * Schema 本身非法属于内核配置故障，实现应抛异常而不是伪装成模型参数错误。
     */
    Optional<String> invalidReason(JsonObject schema, JsonObject arguments);
}
