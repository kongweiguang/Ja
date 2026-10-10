// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.test;

import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;

/** 聚焦持久化断言使用有类型的 JSON 契约，避免依赖序列化格式细节。 */
public final class JsonValueTestSupport {
    /** 静态测试工具不应被实例化，避免产生无状态的伪对象。 */
    private JsonValueTestSupport() {
    }

    /** 递归检查字符串叶节点，使嵌套事实断言不必匹配 JSON 文本格式。 */
    public static boolean containsText(JsonValue value, String expected) {
        if (value instanceof JsonText text) {
            return text.value().contains(expected);
        }
        if (value instanceof JsonObject object) {
            return object.members().values().stream().anyMatch(member -> containsText(member, expected));
        }
        if (value instanceof JsonArray array) {
            return array.values().stream().anyMatch(member -> containsText(member, expected));
        }
        return false;
    }
}
