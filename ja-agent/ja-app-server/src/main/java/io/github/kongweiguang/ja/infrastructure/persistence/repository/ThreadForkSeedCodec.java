// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.InitialContextSeedCodec;

import java.util.Objects;
import java.util.Optional;

/**
 * Thread fork 首轮快照的严格 envelope；Core seed 字段由共享 codec 唯一编码。
 */
final class ThreadForkSeedCodec {
    private static final int SCHEMA_VERSION = 1;

    private final ObjectMapper mapper;
    private final InitialContextSeedCodec seeds;

    /**
     * 保持外层版本边界与 Core 消息 schema 分离，避免 fork 元数据改变 Core seed 兼容规则。
     */
    ThreadForkSeedCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.seeds = new InitialContextSeedCodec(new JacksonJsonValueCodec(mapper));
    }

    /**
     * 将合法无历史状态显式存成 null；非空内容经过 Core 的闭集 codec 后再嵌入 JSON 树。
     */
    String encode(InitialContextSeed seed) {
        ObjectNode payload = mapper.createObjectNode().put("schemaVersion", SCHEMA_VERSION);
        try {
            if (seed == null) {
                payload.putNull("seed");
            } else {
                payload.set("seed", mapper.readTree(seeds.encode(seed)));
            }
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("unable to encode thread fork seed", failure);
        }
    }

    /**
     * 严格读取 fork envelope，再交给 Core codec 验证消息块与摘要；未知字段不得静默丢弃。
     */
    Optional<InitialContextSeed> decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        try {
            JsonNode payload = mapper.readTree(encoded);
            JsonNode version = payload == null ? null : payload.get("schemaVersion");
            JsonNode seed = payload == null ? null : payload.get("seed");
            if (payload == null || !payload.isObject() || payload.size() != 2
                || version == null || !version.isIntegralNumber() || version.intValue() != SCHEMA_VERSION
                || seed == null) {
                throw invalid();
            }
            return seed.isNull() ? Optional.empty() : Optional.of(seeds.decode(seed.toString()));
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw invalid(failure);
        }
    }

    /**
     * 持久化损坏不回显冻结对话正文，错误保持可供仓储边界映射的稳定形态。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid thread fork seed envelope");
    }

    /**
     * 包装解析或 Core seed 校验失败，防止内部内容出现在异常消息中。
     */
    private static IllegalArgumentException invalid(Throwable cause) {
        return new IllegalArgumentException("invalid thread fork seed envelope", cause);
    }
}
