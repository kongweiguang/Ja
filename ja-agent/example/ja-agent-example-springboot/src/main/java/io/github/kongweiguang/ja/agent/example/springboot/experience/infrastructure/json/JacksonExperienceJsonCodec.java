// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.experience.infrastructure.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.example.springboot.experience.application.gateway.ExperienceJsonCodec;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValues;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.ProviderJsonValues;

import java.io.IOException;
import java.util.Map;

/**
 * Jackson 适配器集中处理 Example 的协议转换，避免 Controller、Extension 各自复制 JSON 语义。
 */
public final class JacksonExperienceJsonCodec implements ExperienceJsonCodec {
    private final ObjectMapper mapper;

    /** 使用 Spring 组合根共享的 ObjectMapper；复制会拆分模块配置，codec 只借用该线程安全配置而不拥有它。 */
    public JacksonExperienceJsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 将 Schema 对象映射为 Core 严格对象。 */
    @Override
    public JsonObject object(Object value) {
        return ProviderJsonValues.toObject(mapper.valueToTree(value));
    }

    /** 严格解析 MCP 参数，重复键和尾随内容均视为调用方错误。 */
    @Override
    public JsonObject object(String value) {
        try {
            JsonNode node = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .readTree(value);
            return ProviderJsonValues.toObject(node);
        } catch (IOException failure) {
            throw new IllegalArgumentException("invalid MCP arguments JSON", failure);
        }
    }

    /** 统一处理严格 JsonValue 与普通 DTO 的输出。 */
    @Override
    public String write(Object value) {
        try {
            Object json = value instanceof JsonValue strict
                    ? JacksonJsonValues.toNode(mapper, strict) : value;
            return mapper.writeValueAsString(json);
        } catch (IOException failure) {
            throw new IllegalStateException("result serialization failed", failure);
        }
    }

    /** 返回 ObjectMapper 节点供 Spring HTTP 层转换为 Map。 */
    @Override
    public Map<String, Object> objectMap(JsonValue value) {
        return mapper.convertValue(JacksonJsonValues.toNode(mapper, value),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
    }
}
