// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.experience.application.gateway;

import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;

import java.util.Map;

/**
 * Experience 用例使用的 JSON 边界契约；Jackson 和 Core 的具体映射只存在于基础设施适配器。
 */
public interface ExperienceJsonCodec {
    /** 将普通 Schema 值转成 Core 严格对象，拒绝数组和标量根。 */
    JsonObject object(Object value);

    /** 将外部 JSON 文本解析为严格对象，拒绝尾随内容和重复键。 */
    JsonObject object(String value);

    /** 将严格值或普通 DTO 序列化为稳定 JSON 文本。 */
    String write(Object value);

    /** 将严格对象转换成 HTTP 映射所需的普通 Map。 */
    Map<String, Object> objectMap(JsonValue value);
}
