// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonValue;

/**
 * 在严格 JSON 值模型与持久化文本之间转换，隔离具体序列化库和弱类型对象图。
 */
public interface JsonValueCodec {
    /**
     * 编码已经冻结的 JSON 值，异常不得回显可能包含敏感信息的正文。
     */
    String encode(JsonValue value);

    /**
     * 解码任意严格 JSON 值，数字必须保持十进制精度且 JSON null 必须显式建模。
     */
    JsonValue decode(String encodedValue);

    /**
     * 为 Tool arguments 收紧顶层对象约束，防止数组或标量进入调用端口。
     */
    default JsonObject decodeObject(String encodedValue) {
        JsonValue decoded = decode(encodedValue);
        if (!(decoded instanceof JsonObject object)) {
            throw new IllegalArgumentException("JSON value must be an object");
        }
        return object;
    }
}
