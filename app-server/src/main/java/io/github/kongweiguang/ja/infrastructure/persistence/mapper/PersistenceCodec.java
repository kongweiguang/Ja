// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointUsage;
import io.github.kongweiguang.ja.conversation.application.context.ContextMessage;
import io.github.kongweiguang.ja.conversation.application.context.ContextPolicy;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryDocument;
import io.github.kongweiguang.ja.conversation.domain.model.ModelContent;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.conversation.domain.model.NativeAttachmentContent;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.conversation.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.foundation.json.JacksonJsonValues;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 持久化 JSON 边界，只编码公开结构，不保留 provider raw payload。
 */
public final class PersistenceCodec {
    private final ObjectMapper mapper;

    /**
     * 使用 composition 注入的 Jackson 配置，避免创建另一套序列化事实源。
     */
    public PersistenceCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * 编码完整有序 blocks，并显式写 kind 以避免依赖反射型多态；请求期原生附件载荷必须拒绝持久化，
     * 数据库只保存 opaque attachmentId，防止大块用户内容或 Provider 私有表示进入历史事实。
     */
    public String writeMessage(ModelMessage message) {
        ArrayNode blocks = mapper.createArrayNode();
        for (ModelContent content : message.content()) {
            ObjectNode node = blocks.addObject();
            switch (content) {
                case AttachmentContent attachment -> node.put("kind", "attachment")
                        .put("attachmentId", attachment.attachmentId());
                case NativeAttachmentContent ignored -> throw new IllegalArgumentException(
                        "native attachment payload is request-scoped and cannot be persisted");
                case TextContent text -> node.put("kind", "text").put("text", text.text());
                case ToolCallContent call -> {
                    node.put("kind", "tool_call").put("callId", call.callId()).put("name", call.name());
                    node.set("arguments", JacksonJsonValues.toNode(mapper, call.arguments()));
                }
                case ToolResultContent result -> node.put("kind", "tool_result")
                        .put("callId", result.callId()).put("content", result.content()).put("error", result.error());
            }
        }
        return write(blocks);
    }

    /**
     * 按数据库 ordinal 原样恢复 blocks，未知 kind 直接 fail closed。
     */
    public ModelMessage readMessage(String role, String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.isArray() || root.isEmpty()) throw corrupt("message blocks must be a non-empty array", null);
            List<ModelContent> blocks = new ArrayList<>();
            for (JsonNode node : root) {
                blocks.add(switch (required(node, "kind")) {
                    case "attachment" -> new AttachmentContent(required(node, "attachmentId"));
                    case "text" -> new TextContent(required(node, "text"));
                    case "tool_call" -> new ToolCallContent(required(node, "callId"),
                            required(node, "name"), requiredObjectValue(node.required("arguments")));
                    case "tool_result" -> new ToolResultContent(required(node, "callId"),
                            required(node, "content"), node.required("error").booleanValue());
                    default -> throw corrupt("unknown message block kind", null);
                });
            }
            return new ModelMessage(ModelRole.valueOf(role), blocks);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw corrupt("cannot decode persisted message blocks", failure);
        }
    }

    /**
     * 参数 JSON 保持对象形状，Tool prepare 与实际执行共享同一不可变输入。
     */
    public String writeArguments(JsonObject arguments) {
        return write(JacksonJsonValues.toNode(mapper, arguments));
    }

    /**
     * SummaryDocument 以固定字段 JSON 保存，不退化为 prompt string。
     */
    public String writeSummary(SummaryDocument summary) {
        Objects.requireNonNull(summary, "summary");
        return write(mapper.valueToTree(summary));
    }

    /**
     * 完整恢复结构化 summary，字段缺失或类型漂移都视为数据库损坏。
     */
    public SummaryDocument readSummary(String json) {
        try {
            return mapper.readValue(json, SummaryDocument.class);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw corrupt("cannot decode persisted context summary", failure);
        }
    }

    /**
     * checkpoint usage 使用固定 provider-neutral record，不依赖独占 Provider ModelPort。
     */
    public String writeCheckpointUsage(CheckpointUsage usage) {
        ObjectNode node = mapper.createObjectNode();
        node.put("inputTokens", usage.inputTokens());
        node.put("outputTokens", usage.outputTokens());
        node.put("totalTokens", usage.totalTokens());
        node.put("cacheReadTokens", usage.cacheReadTokens());
        node.put("cacheWriteTokens", usage.cacheWriteTokens());
        return write(node);
    }

    /**
     * usage_json 任一字段缺失或类型错误都按数据库损坏 fail closed。
     */
    public CheckpointUsage readCheckpointUsage(String json) {
        try {
            return mapper.readValue(json, CheckpointUsage.class);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw corrupt("cannot decode persisted checkpoint usage", failure);
        }
    }

    /**
     * split checkpoint 只持久化精确 text suffix，禁止把完整 source message 误当作 retained 内容。
     */
    public String writeRetainedSplit(Optional<ContextPolicy.RetainedSplit> retainedSplit) {
        Objects.requireNonNull(retainedSplit, "retainedSplit");
        if (retainedSplit.isEmpty()) return null;
        ContextPolicy.RetainedSplit split = retainedSplit.orElseThrow();
        ContextMessage message = split.retainedMessage();
        ContextMessage.TextBlock text = (ContextMessage.TextBlock) message.blocks().getFirst();
        ObjectNode root = mapper.createObjectNode();
        root.put("sourceMessageId", split.sourceMessageId());
        ObjectNode retained = root.putObject("retainedMessage");
        retained.put("messageId", message.messageId());
        retained.put("turnId", message.turnId());
        retained.put("ordinal", message.ordinal());
        retained.put("role", message.role().name());
        retained.put("estimatedTokens", message.estimatedTokens());
        retained.put("text", text.value());
        return write(root);
    }

    /**
     * nullable JSON 精确恢复 retained suffix；字段漂移或非 text 形状统一按库损坏拒绝。
     */
    public Optional<ContextPolicy.RetainedSplit> readRetainedSplit(String json) {
        if (json == null) return Optional.empty();
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode retained = root.required("retainedMessage");
            if (!root.isObject() || root.size() != 2 || !retained.isObject() || retained.size() != 6) {
                throw corrupt("retained split has wrong shape", null);
            }
            ContextMessage message = ContextMessage.text(required(retained, "messageId"),
                    required(retained, "turnId"), requiredLong(retained, "ordinal"),
                    ContextMessage.Role.valueOf(required(retained, "role")), required(retained, "text"),
                    requiredInt(retained, "estimatedTokens"));
            return Optional.of(new ContextPolicy.RetainedSplit(required(root, "sourceMessageId"), message));
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw corrupt("cannot decode retained split", failure);
        }
    }

    /**
     * 将受控结构写成紧凑 JSON，异常只保留稳定分类而不回显 payload。
     */
    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "cannot encode persistence value", failure);
        }
    }

    /**
     * 只允许持久化 Tool arguments 恢复为对象；标量或数组代表数据库内容已损坏。
     */
    private JsonObject requiredObjectValue(JsonNode node) {
        JsonValue value = JacksonJsonValues.fromNode(node);
        if (!(value instanceof JsonObject object)) {
            throw corrupt("persisted Tool arguments must be an object", null);
        }
        return object;
    }

    /**
     * 必填文本字段不接受 JSON coercion，避免损坏数据被静默兼容。
     */
    private static String required(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isTextual()) throw corrupt("persisted block field has wrong type", null);
        return value.textValue();
    }

    /**
     * ordinal 必须保持无损 long，拒绝浮点或字符串 coercion。
     */
    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw corrupt("persisted numeric field has wrong type", null);
        }
        return value.longValue();
    }

    /**
     * token estimate 使用有界 int，拒绝 overflow。
     */
    private static int requiredInt(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw corrupt("persisted numeric field has wrong type", null);
        }
        return value.intValue();
    }

    /**
     * 统一数据库内容损坏分类，调用方不得继续恢复部分消息。
     */
    private static StorageException corrupt(String message, Throwable cause) {
        return new StorageException(StorageException.Code.INVALID_STATE, message, cause);
    }
}
