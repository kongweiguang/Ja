// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.tool.ToolState;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.conversation.domain.model.JaToolCallId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 将 Ja 展示投影写入共享追加日志，同时禁止其成为执行状态与副作用的权威来源。
 */
public final class JaProjectionPayloadCodec {
    public static final int SCHEMA_VERSION = 1;
    public static final String TOOL_PRESENTATION_TYPE = "ja.tool.presentation";

    private final ObjectMapper mapper;

    /**
     * 复用宿主 JSON 设置，并额外拒绝重复键和未声明字段，固定持久化 schema 边界。
     */
    public JaProjectionPayloadCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * 使用完整 callId 摘要构造稳定 recordId，避免分隔符歧义及超出 Store 标识长度限制。
     */
    private static String presentationRecordId(String callId, long revision) {
        return "ja_presentation_" + sha256(callId.getBytes(StandardCharsets.UTF_8)) + "_" + revision;
    }

    /**
     * 必需值必须确实是 JSON 字符串，不接受 Jackson 的标量隐式转换。
     */
    private static String string(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) {
            throw corrupt();
        }
        return value.textValue();
    }

    /**
     * SHA-256 只参与追加日志的稳定标识生成，不参与安全或权限裁决。
     */
    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    /**
     * 将损坏载荷统一折叠为不回显原文的持久化错误。
     */
    private static IllegalArgumentException corrupt() {
        return new IllegalArgumentException("invalid Ja tool presentation record");
    }

    /**
     * 无法解码的快照使用固定脱敏文案并保留解析异常。
     */
    private static IllegalArgumentException corrupt(Throwable cause) {
        return new IllegalArgumentException("invalid Ja tool presentation record", cause);
    }

    /**
     * 将安全展示快照绑定原生 callId；独立投影 revision 不等于 Store mutationVersion，也不能改变真实结果。
     */
    public AgentStore.RecordWrite encodeToolPresentation(String callId, long revision, ToolState state,
                                                         String presentationJson, Instant occurredAt) {
        String validCallId = JaToolCallId.require(callId);
        if (revision < 0) {
            throw new IllegalArgumentException("negative presentation revision");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requirePresentationObject(presentationJson);
        ObjectNode payload = mapper.createObjectNode().put("callId", validCallId).put("revision", revision)
                .put("state", state.name()).put("presentationJson", presentationJson);
        return new AgentStore.RecordWrite(presentationRecordId(validCallId, revision), TOOL_PRESENTATION_TYPE,
                SCHEMA_VERSION, new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec(mapper).decodeObject(payload.toString()), occurredAt);
    }

    /**
     * 只解析 Ja presentation 类型，通用执行事实仍由 Core codec 唯一解释。
     */
    public ToolPresentationSnapshot decodeToolPresentation(AgentStore.RecordWrite record) {
        Objects.requireNonNull(record, "record");
        if (!TOOL_PRESENTATION_TYPE.equals(record.recordType()) || record.payloadSchemaVersion() != SCHEMA_VERSION) {
            throw corrupt();
        }
        return decodeToolPresentation(record.recordType(), record.payloadSchemaVersion(), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec(mapper).encode(record.payload()));
    }

    /**
     * 拒绝未知版本和字段，避免 schema 漂移时静默改写用户已经看到的工具历史。
     */
    public ToolPresentationSnapshot decodeToolPresentation(String type, int schemaVersion, String json) {
        if (!TOOL_PRESENTATION_TYPE.equals(type) || schemaVersion != SCHEMA_VERSION) {
            throw corrupt();
        }
        try {
            JsonNode node = mapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .readTree(Objects.requireNonNull(json, "json"));
            if (!(node instanceof ObjectNode object) || object.size() != 4
                || !object.has("callId") || !object.has("revision") || !object.has("state")
                || !object.has("presentationJson")) {
                    throw corrupt();
                }
            String callId = JaToolCallId.require(string(object, "callId"));
            JsonNode revisionNode = object.get("revision");
            if (!revisionNode.isIntegralNumber() || !revisionNode.canConvertToLong() || revisionNode.longValue() < 0) {
                throw corrupt();
            }
            ToolState state = ToolState.valueOf(string(object, "state"));
            String presentationJson = string(object, "presentationJson");
            requirePresentationObject(presentationJson);
            return new ToolPresentationSnapshot(callId, revisionNode.longValue(), state, presentationJson);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw corrupt(failure);
        }
    }

    /**
     * 展示载荷必须是无重复键的 JSON 对象，供 UI codec 按结构读取。
     */
    private void requirePresentationObject(String json) {
        try {
            JsonNode value = mapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .readTree(Objects.requireNonNull(json, "presentationJson"));
            if (!(value instanceof ObjectNode))
                {
                    throw new IllegalArgumentException("tool presentation must be an object");
                }
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("invalid tool presentation JSON", failure);
        }
    }

    /**
     * 明确区分展示投影和执行事实，展示 revision 不得被误当作执行 mutationVersion。
     *
     * @param callId 模型生成的工具调用标识
     * @param revision 当前修订版本
     * @param state 当前状态
     * @param presentationJson 展示信息 JSON 表示
     */
    public record ToolPresentationSnapshot(String callId,
                                           long revision,
                                           ToolState state,
                                           String presentationJson) {
        /**
         * 投影写入前校验 callId 与独立 revision，防止持久状态绕过编码器。
         */
        public ToolPresentationSnapshot {
            callId = JaToolCallId.require(callId);
            if (revision < 0) {
                throw new IllegalArgumentException("negative presentation revision");
            }
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(presentationJson, "presentationJson");
        }
    }
}
