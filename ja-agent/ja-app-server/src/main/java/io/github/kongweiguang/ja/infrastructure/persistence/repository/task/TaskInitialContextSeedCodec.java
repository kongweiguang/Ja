// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjectBuilder;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.InitialContextSeedCodec;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Task 持久层的可选继承上下文格式；显式区分合法空背景与缺失或损坏的数据。
 */
final class TaskInitialContextSeedCodec {
    private static final int SCHEMA_VERSION = 1;
    private static final String EMPTY = "empty";
    private static final String SEEDED = "seed";
    private static final Set<String> EMPTY_FIELDS = Set.of("schemaVersion", "kind");
    private static final Set<String> SEEDED_FIELDS = Set.of("schemaVersion", "kind", "seed");

    private final JsonValueCodec json;
    private final InitialContextSeedCodec initialSeeds;

    /**
     * 在 Task 列中只扩展空值表示，背景消息的字段语义继续由 Core codec 唯一管理。
     */
    TaskInitialContextSeedCodec(JsonValueCodec json) {
        this.json = Objects.requireNonNull(json, "json");
        this.initialSeeds = new InitialContextSeedCodec(json);
    }

    /**
     * 空态和 seed 态拥有互斥字段集，防止未来实现静默忽略持久化事实。
     */
    private static void requireFields(JsonObject object, Set<String> expected) {
        if (!object.members().keySet().equals(expected)) {
            throw invalid();
        }
    }

    /**
     * schemaVersion 必须是无小数、可精确表示的 JSON 整数。
     */
    private static long integer(JsonValue value) {
        if (!(value instanceof JsonNumber(java.math.BigDecimal value1))) {
            throw invalid();
        }
        try {
            return value1.longValueExact();
        } catch (ArithmeticException failure) {
            throw invalid(failure);
        }
    }

    /**
     * kind 必须是 JSON 字符串，不能把数字或 null 归一成文本。
     */
    private static String text(JsonValue value) {
        if (!(value instanceof JsonText(String value1))) {
            throw invalid();
        }
        return value1;
    }

    /**
     * 以固定安全错误拒绝未识别状态，不把上下文正文回显给持久层调用方。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid Task initial context seed envelope");
    }

    /**
     * 数值越界保持固定 envelope 错误文案，并保留精确转换失败原因。
     */
    private static IllegalArgumentException invalid(Throwable cause) {
        return new IllegalArgumentException("invalid Task initial context seed envelope", cause);
    }

    /**
     * 将 Optional 显式写为版本化 envelope，避免 EFFECTIVE_CONTEXT 与 SQL NULL CHECK 冲突。
     */
    String encode(Optional<InitialContextSeed> seed) {
        Optional<InitialContextSeed> value = Objects.requireNonNull(seed, "seed");
        JsonObjectBuilder envelope = JsonObjects.builder().putNumber("schemaVersion", SCHEMA_VERSION);
        if (value.isEmpty()) {
            return json.encode(envelope.putText("kind", EMPTY).build());
        }
        return json.encode(envelope.putText("kind", SEEDED)
                .put("seed", initialSeeds.encodeValue(value.orElseThrow())).build());
    }

    /**
     * 只接受当前精确 envelope；旧形状、未知版本及字段漂移都失败关闭。
     */
    Optional<InitialContextSeed> decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        try {
            JsonObject envelope = json.decodeObject(encoded);
            if (integer(envelope.get("schemaVersion")) != SCHEMA_VERSION) {
                throw invalid();
            }
            String kind = text(envelope.get("kind"));
            if (EMPTY.equals(kind)) {
                requireFields(envelope, EMPTY_FIELDS);
                return Optional.empty();
            }
            if (SEEDED.equals(kind)) {
                requireFields(envelope, SEEDED_FIELDS);
                return Optional.of(initialSeeds.decodeValue(envelope.get("seed")));
            }
            throw invalid();
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("invalid Task initial context seed", failure);
        }
    }
}
