// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.infrastructure.persistence;

import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.json.JsonValueCodecFactory;

import java.util.Objects;
import java.util.function.UnaryOperator;

/** 为 codec 测试构造不可变 Store JSON，并把字节级损坏限制在 codec 边界。 */
public final class PersistenceTestJson {
    private static final JsonValueCodec CODEC = JsonValueCodecFactory.standard();

    /** 测试工具无状态且只复用不可变 codec 配置，避免每个 fixture 重建 Jackson。 */
    private PersistenceTestJson() {
    }

    /** 将测试字面量解析为 Store 使用的对象值，语法错误仍交给原始 codec 输入测试覆盖。 */
    public static JsonObject object(String json) {
        return CODEC.decodeObject(Objects.requireNonNull(json, "json"));
    }

    /** 只在测试构造损坏输入时序列化，生产 Store API 始终保持不可变 JSON 类型。 */
    public static JsonObject mutate(JsonObject payload, UnaryOperator<String> mutation) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(mutation, "mutation");
        return object(mutation.apply(CODEC.encode(payload)));
    }
}
