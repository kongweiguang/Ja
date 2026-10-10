// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.support;

import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.infrastructure.json.JsonValueCodecFactory;

/** 将短 JSON fixture 解码为 Core 公开的不可变领域值。 */
public final class JsonTestValues {
    /** 测试 helper 无生命周期，不应被误当作可配置的 JSON 服务。 */
    private JsonTestValues() {
    }

    /**
     * 复用严格 Core JSON 语义构造 Store 测试值，避免在测试里引入 Map、Jackson tree 或字符串负载 API。
     */
    public static JsonObject object(String json) {
        return JsonValueCodecFactory.standard().decodeObject(json);
    }
}
