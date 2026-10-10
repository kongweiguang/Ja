// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValues;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.PersistenceCodec;
import io.github.kongweiguang.ja.foundation.hash.DigestFields;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Task 表的唯一 JSON/canonical fingerprint 适配器。
 */
final class TaskJsonCodec {
    private final ObjectMapper mapper;
    private final PersistenceCodec content;

    /**
     * 复用组合根 ObjectMapper 和既有 UserContent codec，避免 Task 自建第二种 blocks 格式。
     */
    TaskJsonCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.content = new PersistenceCodec(mapper);
    }

    /**
     * 统一使用 Ja 的保留 null 哨兵和 UTF-8 长度前缀，避免相邻指纹字段发生拼接歧义。
     */
    private static void add(MessageDigest digest, String value) {
        DigestFields.appendUtf8(digest, value);
    }

    /**
     * codec 失败不回显 payload，防止 prompt 或路径进入外部错误。
     */
    private static StorageException corrupt(String message, Throwable cause) {
        return new StorageException(StorageException.Code.INVALID_STATE, message, cause);
    }

    /**
     * Task brief 与 Mailbox 始终使用和 USER Message 相同的严格 blocks JSON。
     */
    String writeContent(UserContent value) {
        return content.writeUserContent(value);
    }

    /**
     * 损坏的 Task blocks 按现有持久化 codec fail-closed。
     */
    UserContent readContent(String json) {
        return content.readUserContent(json);
    }

    /**
     * JSON 值通过 sealed foundation 类型进入 Jackson，禁止 Map/Object 泄漏。
     */
    String write(JsonValue value) {
        try {
            return mapper.writeValueAsString(JacksonJsonValues.toNode(mapper, value));
        } catch (JsonProcessingException failure) {
            throw corrupt("cannot encode task JSON", failure);
        }
    }

    /**
     * 从 SQLite 恢复对象时同时复核顶层形状。
     */
    JsonObject readObject(String json) {
        JsonValue value = read(json);
        if (!(value instanceof JsonObject object)) {
            throw corrupt("task JSON is not an object", null);
        }
        return object;
    }

    /**
     * 从 SQLite 恢复数组时同时复核顶层形状。
     */
    JsonArray readArray(String json) {
        JsonValue value = read(json);
        if (!(value instanceof JsonArray array)) {
            throw corrupt("task JSON is not an array", null);
        }
        return array;
    }

    /**
     * seed fingerprint 覆盖身份、revision、模式和四份 canonical JSON，不依赖数据库列拼接顺序。
     */
    String fingerprint(String parentThreadId, String parentTurnId, long parentRevision,
                       String inheritanceMode, String taskBriefJson, String effectiveContextJson,
                       String referencesJson, String permissionCeilingJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, parentThreadId);
            add(digest, parentTurnId == null ? "" : parentTurnId);
            add(digest, Long.toString(parentRevision));
            add(digest, inheritanceMode);
            add(digest, taskBriefJson);
            add(digest, effectiveContextJson == null ? "" : effectiveContextJson);
            add(digest, referencesJson);
            add(digest, permissionCeilingJson);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 持久恢复拒绝重复字段和尾随内容，并关闭解析源码片段，避免损坏行被部分接纳或在异常诊断中回显。
     */
    private JsonValue read(String json) {
        try {
            JsonNode node = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                            DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .without(JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION)
                    .readTree(Objects.requireNonNull(json, "json"));
            return JacksonJsonValues.fromNode(node);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw corrupt("cannot decode task JSON", failure);
        }
    }
}
