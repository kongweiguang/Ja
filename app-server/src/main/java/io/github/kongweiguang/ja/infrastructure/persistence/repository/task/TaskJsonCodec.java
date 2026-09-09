// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.foundation.json.JacksonJsonValues;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonValue;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceCodec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Task 表的唯一 JSON/canonical fingerprint 适配器。 */
final class TaskJsonCodec {
    private final ObjectMapper mapper;
    private final PersistenceCodec content;

    /** 复用组合根 ObjectMapper 和既有 UserContent codec，避免 Task 自建第二种 blocks 格式。 */
    TaskJsonCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.content = new PersistenceCodec(mapper);
    }

    /** Task brief 与 Mailbox 始终使用和 USER Message 相同的严格 blocks JSON。 */
    String writeContent(UserContent value) {
        return content.writeUserContent(value);
    }

    /** 损坏的 Task blocks 按现有持久化 codec fail-closed。 */
    UserContent readContent(String json) {
        return content.readUserContent(json);
    }

    /** JSON 值通过 sealed foundation 类型进入 Jackson，禁止 Map/Object 泄漏。 */
    String write(JsonValue value) {
        try {
            return mapper.writeValueAsString(JacksonJsonValues.toNode(mapper, value));
        } catch (JsonProcessingException failure) {
            throw corrupt("cannot encode task JSON", failure);
        }
    }

    /** 从 SQLite 恢复对象时同时复核顶层形状。 */
    JsonObject readObject(String json) {
        JsonValue value = read(json);
        if (!(value instanceof JsonObject object)) throw corrupt("task JSON is not an object", null);
        return object;
    }

    /** 从 SQLite 恢复数组时同时复核顶层形状。 */
    JsonArray readArray(String json) {
        JsonValue value = read(json);
        if (!(value instanceof JsonArray array)) throw corrupt("task JSON is not an array", null);
        return array;
    }

    /** seed fingerprint 覆盖身份、revision、模式和四份 canonical JSON，不依赖数据库列拼接顺序。 */
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

    /** 长度前缀使相邻字段即使包含分隔字符也不能形成相同 fingerprint 输入。 */
    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    /** Jackson 树只接受标准 JSON 节点并立即复制到 foundation 闭集。 */
    private JsonValue read(String json) {
        try {
            JsonNode node = mapper.readTree(Objects.requireNonNull(json, "json"));
            return JacksonJsonValues.fromNode(node);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw corrupt("cannot decode task JSON", failure);
        }
    }

    /** codec 失败不回显 payload，防止 prompt 或路径进入外部错误。 */
    private static StorageException corrupt(String message, Throwable cause) {
        return new StorageException(StorageException.Code.INVALID_STATE, message, cause);
    }
}
