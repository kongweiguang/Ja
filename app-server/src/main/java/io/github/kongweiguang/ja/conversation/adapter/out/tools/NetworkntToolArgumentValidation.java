// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import io.github.kongweiguang.ja.conversation.port.out.JsonValueCodec;
import io.github.kongweiguang.ja.conversation.port.out.ToolArgumentValidator;
import io.github.kongweiguang.ja.foundation.json.JsonObject;

import java.util.Objects;
import java.util.Optional;

/** 使用生产 JSON 编码与 networknt Schema 引擎实现 Tool 参数校验端口。 */
public final class NetworkntToolArgumentValidation implements ToolArgumentValidator {
    private final JsonValueCodec codec;

    /** 复用组合根唯一严格 JSON 编码，避免 Schema 引擎观察另一种数字或 null 方言。 */
    public NetworkntToolArgumentValidation(JsonValueCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /**
     * Schema 在参数异常捕获之前编译，使非法 Tool 定义继续失败关闭；只有实例不匹配才回给模型纠正。
     */
    @Override
    public Optional<String> invalidReason(JsonObject schema, JsonObject arguments) {
        NetworkntToolArgumentValidator validator = new NetworkntToolArgumentValidator(codec.encode(schema));
        try {
            validator.validate(codec.encode(arguments));
            return Optional.empty();
        } catch (ToolSchemaException invalidArguments) {
            return Optional.of(invalidArguments.getMessage());
        }
    }
}
