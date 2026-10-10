// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;

import java.io.IOException;
import java.util.Objects;

/**
 * 将内核的严格 JSON 值模型映射到 Jackson；重复字段和尾随内容都按损坏输入拒绝。
 */
public final class JacksonJsonValueCodec implements JsonValueCodec {
    private final ObjectMapper mapper;

    /**
     * 复制 Mapper 并固定十进制精度与小数位数，避免往返后事实或调用绑定指纹变化；不修改宿主实例。
     */
    public JacksonJsonValueCodec(ObjectMapper source) {
        ObjectMapper configured = Objects.requireNonNull(source, "source").copy();
        configured.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        configured.disable(JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION);
        configured.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        configured.configure(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);
        configured.configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
        this.mapper = configured;
    }

    /**
     * 使用库内确定性 Mapper 构造纯 Java 默认 JSON 边界，不需要消费方手写 Jackson 装配。
     */
    public JacksonJsonValueCodec() {
        this(new ObjectMapper());
    }

    /**
     * 通过 sealed JSON 值映射编码，避免任意 Java 对象被 Jackson 多态机制持久化。
     */
    @Override
    public String encode(JsonValue value) {
        Objects.requireNonNull(value, "value");
        try {
            return mapper.writeValueAsString(JacksonJsonValues.toNode(mapper, value));
        } catch (IOException failure) {
            throw new IllegalArgumentException("JSON value could not be encoded", failure);
        }
    }

    /**
     * 只接纳一个严格 JSON 根值；重复键、尾随内容和非 JSON 节点不会被宽松修复。
     */
    @Override
    public JsonValue decode(String encodedValue) {
        Objects.requireNonNull(encodedValue, "encodedValue");
        try {
            JsonNode node = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(encodedValue);
            return JacksonJsonValues.fromNode(node);
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("JSON value could not be decoded", failure);
        }
    }
}
