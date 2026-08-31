// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.port.out.JsonValueCodec;
import io.github.kongweiguang.ja.foundation.json.JacksonJsonValues;
import io.github.kongweiguang.ja.foundation.json.JsonValue;

import java.util.Objects;

/**
 * 组合根唯一 Jackson JSON 值编解码器，领域和应用层只观察纯 JDK sealed 模型。
 */
final class JacksonJsonValueCodec implements JsonValueCodec {
    private final ObjectMapper mapper;

    /**
     * 复用组合根的严格 ObjectMapper，避免持久化与 Prompt JSON 形成两套方言。
     */
    JacksonJsonValueCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * 先显式映射 sealed 闭集再序列化，禁止 Jackson 把 record 组件名写入 Wire。
     */
    @Override
    public String encode(JsonValue value) {
        Objects.requireNonNull(value, "value");
        try {
            return mapper.writeValueAsString(JacksonJsonValues.toNode(mapper, value));
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("JSON value cannot be encoded", failure);
        }
    }

    /**
     * 从 JsonNode 穷举恢复严格值；二进制、缺失节点和非有限数字一律显式失败。
     */
    @Override
    public JsonValue decode(String encodedValue) {
        Objects.requireNonNull(encodedValue, "encodedValue");
        try {
            JsonNode node = mapper.readTree(encodedValue);
            if (node == null || node.isMissingNode() || node.isBinary()) {
                throw new IllegalArgumentException("JSON value has an unsupported shape");
            }
            return JacksonJsonValues.fromNode(node);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("JSON value cannot be decoded", failure);
        }
    }

}
