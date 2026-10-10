// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.context.CheckpointUsage;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.RetainedSplit;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.NativeAttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.NativeToolResultContent;
import io.github.kongweiguang.ja.agent.domain.model.ReasoningContent;
import io.github.kongweiguang.ja.agent.domain.model.ResourceReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.agent.domain.model.UserContentBlock;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValues;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;

/**
 * 持久化 JSON 边界；普通内容保持公开结构，原生 reasoning 仅作为同身份请求所需的 opaque 历史保存。
 */
public final class PersistenceCodec {
    private final ObjectMapper mapper;

    /**
     * 冻结宿主配置并收紧持久协议，精确参数、必填凭证和失败标记不能随宿主配置漂移。
     */
    public PersistenceCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy()
                .disable(com.fasterxml.jackson.core.JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .configure(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
                .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
        this.mapper.setConfig(this.mapper.getDeserializationConfig().without(MapperFeature.ALLOW_COERCION_OF_SCALARS));
    }

    /** 持久结构采用字段闭集，未知字段不能被静默丢弃或作为未来格式兼容。 */
    private static void exact(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) {
            throw corrupt("persisted block has wrong shape", null);
        }
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(Set.of(fields))) {
            throw corrupt("persisted block fields are invalid", null);
        }
    }

    /** 工具错误位只能是真实布尔值，字符串或数值不能降级为成功。 */
    private static boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isBoolean()) {
            throw corrupt("persisted boolean field has wrong type", null);
        }
        return value.booleanValue();
    }

    /** 空白或 JSON null 不是持久消息或凭证，统一在解码入口拒绝。 */
    private JsonNode strictTree(String json) throws JsonProcessingException {
        JsonNode root = mapper.readTree(json);
        if (root == null || root.isNull()) {
            throw corrupt("persisted value must not be empty", null);
        }
        return root;
    }

    /**
     * 必填文本字段不接受 JSON coercion，避免损坏数据被静默兼容。
     */
    private static String required(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isTextual()) {
            throw corrupt("persisted block field has wrong type", null);
        }
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
                case NativeToolResultContent ignored -> throw new IllegalArgumentException(
                        "native tool media payload is request-scoped and cannot be persisted");
                case ReasoningContent reasoning -> node.put("kind", "reasoning")
                        .put("providerId", reasoning.providerId())
                        .put("modelId", reasoning.modelId())
                        .put("api", reasoning.api())
                        .put("upstreamModel", reasoning.upstreamModel())
                        .put("endpointFingerprint", reasoning.endpointFingerprint())
                        .put("wireField", reasoning.wireField())
                        .put("nativeJson", reasoning.nativeJson());
                case TextContent text -> node.put("kind", "text").put("text", text.text());
                case ResourceReferenceContent reference -> node.put("kind", "resource_reference")
                        .put("sourceId", reference.sourceId())
                        .put("resourceId", reference.resourceId())
                        .put("resourceKind", reference.kind());
                case SkillReferenceContent reference -> node.put("kind", "skill_reference")
                        .put("skillId", reference.skillId());
                case ToolCallContent call -> {
                    node.put("kind", "tool_call").put("callId", call.callId()).put("name", call.name());
                    node.set("arguments", JacksonJsonValues.toNode(mapper, call.arguments()));
                    if (call.preflightFailureCode() != null) {
                        node.put("preflightFailureCode", call.preflightFailureCode());
                    }
                }
                case ToolResultContent result -> {
                    ObjectNode output = node.put("kind", "tool_result")
                            .put("callId", result.callId()).put("content", result.content())
                            .put("error", result.error());
                    ArrayNode media = output.putArray("media");
                    result.media().forEach(reference -> media.addObject()
                            .put("attachmentId", reference.attachmentId())
                            .put("mediaType", reference.mediaType())
                            .put("displayName", reference.displayName())
                            .put("sizeBytes", reference.sizeBytes()));
                }
            }
        }
        return write(blocks);
    }

    /**
     * 按数据库 ordinal 原样恢复 blocks，未知字段、类型漂移和重复键直接拒绝恢复。
     */
    public ModelMessage readMessage(String role, String json) {
        try {
            JsonNode root = strictTree(json);
            if (!root.isArray() || root.isEmpty()) {
                throw corrupt("message blocks must be a non-empty array", null);
            }
            List<ModelContent> blocks = new ArrayList<>();
            for (JsonNode node : root) {
                blocks.add(switch (required(node, "kind")) {
                    case "attachment" -> {
                        exact(node, "kind", "attachmentId");
                        yield new AttachmentContent(required(node, "attachmentId"));
                    }
                    case "reasoning" -> {
                        exact(node, "kind", "providerId", "modelId", "api", "upstreamModel",
                                "endpointFingerprint", "wireField", "nativeJson");
                        yield new ReasoningContent(
                            required(node, "providerId"), required(node, "modelId"),
                            required(node, "api"), required(node, "upstreamModel"),
                            required(node, "endpointFingerprint"), required(node, "wireField"),
                            required(node, "nativeJson"));
                    }
                    case "text" -> {
                        exact(node, "kind", "text");
                        yield new TextContent(required(node, "text"));
                    }
                    case "resource_reference" -> {
                        exact(node, "kind", "sourceId", "resourceId", "resourceKind");
                        yield new ResourceReferenceContent(required(node, "sourceId"),
                                required(node, "resourceId"), required(node, "resourceKind"));
                    }
                    case "skill_reference" -> {
                        exact(node, "kind", "skillId");
                        yield new SkillReferenceContent(required(node, "skillId"));
                    }
                    case "tool_call" -> {
                        if (node.has("preflightFailureCode")) {
                            exact(node, "kind", "callId", "name", "arguments", "preflightFailureCode");
                        } else {
                            exact(node, "kind", "callId", "name", "arguments");
                        }
                        yield new ToolCallContent(required(node, "callId"),
                            required(node, "name"), requiredObjectValue(node.required("arguments")),
                            node.has("preflightFailureCode")
                                    ? required(node, "preflightFailureCode") : null);
                    }
                    case "tool_result" -> {
                        exact(node, "kind", "callId", "content", "error", "media");
                        yield new ToolResultContent(required(node, "callId"),
                                required(node, "content"), requiredBoolean(node, "error"), readToolMedia(node));
                    }
                    default -> throw corrupt("unknown message block kind", null);
                });
            }
            return new ModelMessage(ModelRole.valueOf(role), blocks);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw corrupt("cannot decode persisted message blocks", failure);
        }
    }

    /**
     * 严格读取历史图片引用；消息中只准有受管 opaque 身份与展示元数据，不允许媒体正文落库。
     */
    private List<io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference> readToolMedia(JsonNode node) {
        JsonNode media = node.required("media");
        if (!media.isArray() || media.size() > 10) {
            throw corrupt("persisted tool media is invalid", null);
        }
        List<io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference> references = new ArrayList<>();
        for (JsonNode reference : media) {
            if (!reference.isObject() || reference.size() != 4
                || !reference.path("sizeBytes").isIntegralNumber()
                || !reference.path("sizeBytes").canConvertToLong()) {
                throw corrupt("persisted tool media is invalid", null);
            }
            references.add(new io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference(
                    required(reference, "attachmentId"), required(reference, "mediaType"),
                    required(reference, "displayName"), reference.path("sizeBytes").longValue()));
        }
        return List.copyOf(references);
    }

    /**
     * 队列与 JA-RPC 使用与消息历史相同的内容块编码，不维护第二套 text/attachment 格式。
     */
    public String writeUserContent(UserContent content) {
        Objects.requireNonNull(content, "content");
        return writeMessage(new ModelMessage(ModelRole.USER, List.copyOf(content.blocks())));
    }

    /**
     * 队列解码拒绝 Tool、原生附件或非 USER 内容，损坏行不能降级成纯文本。
     */
    public UserContent readUserContent(String json) {
        ModelMessage message = readMessage(ModelRole.USER.name(), json);
        List<UserContentBlock> blocks = new ArrayList<>();
        for (ModelContent block : message.content()) {
            if (!(block instanceof UserContentBlock userBlock)) {
                throw corrupt("pending input contains a non-user block", null);
            }
            blocks.add(userBlock);
        }
        return new UserContent(blocks);
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
            return mapper.treeToValue(strictTree(json), SummaryDocument.class);
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
            return mapper.treeToValue(strictTree(json), CheckpointUsage.class);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw corrupt("cannot decode persisted checkpoint usage", failure);
        }
    }

    /**
     * split checkpoint 只持久化精确 text suffix，禁止把完整 source message 误当作 retained 内容。
     */
    public String writeRetainedSplit(Optional<RetainedSplit> retainedSplit) {
        Objects.requireNonNull(retainedSplit, "retainedSplit");
        if (retainedSplit.isEmpty()) {
            return null;
        }
        RetainedSplit split = retainedSplit.orElseThrow();
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
    public Optional<RetainedSplit> readRetainedSplit(String json) {
        if (json == null) {
            return Optional.empty();
        }
        try {
            JsonNode root = strictTree(json);
            JsonNode retained = root.required("retainedMessage");
            if (!root.isObject() || root.size() != 2 || !retained.isObject() || retained.size() != 6) {
                throw corrupt("retained split has wrong shape", null);
            }
            ContextMessage message = ContextMessage.text(required(retained, "messageId"),
                    required(retained, "turnId"), requiredLong(retained, "ordinal"),
                    ContextMessage.Role.valueOf(required(retained, "role")), required(retained, "text"),
                    requiredInt(retained, "estimatedTokens"));
            return Optional.of(new RetainedSplit(required(root, "sourceMessageId"), message));
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
}
