// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.persistence;

import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonBoolean;
import io.github.kongweiguang.ja.agent.domain.json.JsonNull;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.model.ReasoningContent;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjectBuilder;

/**
 * 显式编解码冻结上下文，避免 Jackson 反射漏掉 opaque native metadata。
 */
public final class InitialContextSeedCodec {
    /**
     * 首个闭合结构版本；变更消息或摘要字段时必须同步调整版本与迁移。
     */
    public static final int SCHEMA_VERSION = 1;
    /**
     * Seed 记录只出现一次，消息始终作为稳定父历史前缀，不属于 child 执行副作用。
     */
    public static final String RECORD_TYPE = ExecutionRecordCodec.INITIAL_CONTEXT_SEED_RECORD_TYPE;
    private static final Set<String> SUMMARY_FIELDS = Set.of("goals", "constraints", "completedProgress",
            "currentProgress", "blockers", "decisions", "nextSteps", "criticalFacts", "files",
            "pendingEffects", "retirements");

    private final JsonValueCodec json;

    /**
     * 复用组合根的严格 JSON 方言，同时保持业务内容转换为本类的显式闭集。
     */
    public InitialContextSeedCodec(JsonValueCodec json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 将摘要事实作为有序文本和来源 ordinal 编码，不接受只含 prompt 文本的降级表示。
     */
    private static JsonArray facts(List<SummaryDocument.Fact> facts) {
        return new JsonArray(facts.stream().<JsonValue>map(fact -> JsonObjects.builder().putText("text", fact.text())
                .putNumber("sourceOrdinal", fact.sourceOrdinal()).build()).toList());
    }

    /**
     * 从固定分区中恢复事实，不让反序列化次序被 Map 遍历顺序改变。
     */
    private static List<SummaryDocument.Fact> readFacts(JsonObject summary, String field) {
        return array(summary.get(field)).stream().map(item -> {
            JsonObject fact = object(item, "text", "sourceOrdinal");
            return new SummaryDocument.Fact(text(fact.get("text")), number(fact.get("sourceOrdinal")));
        }).toList();
    }

    /**
     * 让 type 判别器先经对象校验，随后每个 switch 分支再验证完整字段集合。
     */
    private static JsonObject objectAtLeastType(JsonValue value) {
        if (!(value instanceof JsonObject object) || !object.containsKey("type")) {
            throw invalid();
        }
        return object;
    }

    /**
     * 校验对象没有未知字段，也没有缺失字段。
     */
    private static void requireFields(JsonObject object, String... names) {
        if (!object.members().keySet().equals(Set.of(names))) {
            throw invalid();
        }
    }

    /**
     * 把字段精确集合限制在当前 schema，不能将未知扩展字段解释为忽略项。
     */
    private static JsonObject object(JsonValue value, String... fields) {
        if (!(value instanceof JsonObject object)) {
            throw invalid();
        }
        requireFields(object, fields);
        return object;
    }

    /**
     * JSON array 类型必须精确，避免标量或对象被弱化成空列表。
     */
    private static List<JsonValue> array(JsonValue value) {
        if (!(value instanceof JsonArray(List<JsonValue> values))) {
            throw invalid();
        }
        return values;
    }

    /**
     * 文本只接受 JsonText，禁止数字、布尔值或 null 被字符串化。
     */
    private static String text(JsonValue value) {
        if (!(value instanceof JsonText(String value1))) {
            throw invalid();
        }
        return value1;
    }

    /**
     * 只接受精确整数且检查 long 范围，ordinal 不允许浮点截断。
     */
    private static long number(JsonValue value) {
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
     * 确保 schema version 和 token estimate 均精确落入 Java int 范围。
     */
    private static int integer(JsonValue value) {
        try {
            return Math.toIntExact(number(value));
        } catch (ArithmeticException failure) {
            throw invalid(failure);
        }
    }

    /**
     * 严格恢复工具投影标志，不能用文本或整数作为真值替代。
     */
    private static boolean bool(JsonValue value) {
        if (value instanceof JsonBoolean jsonBoolean) {
            return jsonBoolean.value();
        }
        throw invalid();
    }

    /**
     * 将可空文本写成显式 JSON null，保持缺失字段与空字段的含义不同。
     */
    private static JsonValue nullableText(String value) {
        return value == null ? JsonNull.INSTANCE : new JsonText(value);
    }

    /**
     * 将可空退出码写成显式 JSON null，避免把未知当成成功码零。
     */
    private static JsonValue nullableInteger(Integer value) {
        return value == null ? JsonNull.INSTANCE : new JsonNumber(value.longValue());
    }

    /**
     * 可空正文只接受 JSON null 或实际文本。
     */
    private static String nullableText(JsonValue value) {
        return value == JsonNull.INSTANCE ? null : text(value);
    }

    /**
     * 可空整数只接受 JSON null 或 Java int 范围内的精确值。
     */
    private static Integer nullableInteger(JsonValue value) {
        return value == JsonNull.INSTANCE ? null : integer(value);
    }

    /**
     * 非法载荷使用固定诊断，不回显种子中的父对话内容。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid initial context seed payload");
    }

    /**
     * 把数值溢出作为固定损坏原因报告，不暴露待解析正文。
     */
    private static IllegalArgumentException invalid(Throwable cause) {
        return new IllegalArgumentException("invalid initial context seed payload", cause);
    }

    /**
     * 将冻结值编码为可被任务快照和 Store 记录共用的有序 JSON 文本。
     */
    public String encode(InitialContextSeed seed) {
        return json.encode(encodeValue(seed));
    }

    /**
     * 单独持久化冻结历史消息，避免大前缀被塞入 Seed JSON；消息仍使用 Seed 的严格闭集编码。
     */
    public String encodeMessageValue(ContextMessage message) {
        return json.encode(encodeMessage(Objects.requireNonNull(message, "message")));
    }

    /**
     * 读取 child-owned 中立消息副本，不依赖来源 Thread/Core record 是否仍存在。
     */
    public ContextMessage decodeMessageValue(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        try {
            return decodeMessage(json.decode(encoded));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("invalid frozen context message", failure);
        }
    }

    /**
     * 严格恢复唯一结构版本；未知字段、类型或消息块不能被忽略。
     */
    public InitialContextSeed decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        try {
            return decodeValue(json.decode(encoded));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("invalid initial context seed", failure);
        }
    }

    /**
     * 构造全字段显式 JsonValue，供 RecordCodec 与宿主快照存储共用同一 schema。
     */
    public JsonObject encodeValue(InitialContextSeed seed) {
        Objects.requireNonNull(seed, "seed");
        JsonObject source = JsonObjects.builder()
                .putText("tenantId", seed.sourceIdentity().tenantId())
                .putText("threadId", seed.sourceIdentity().threadId())
                .putText("turnId", seed.sourceIdentity().turnId())
                .build();
        ArrayList<JsonValue> messages = new ArrayList<JsonValue>(seed.messages().size());
        seed.messages().forEach(message -> messages.add(encodeMessage(message)));
        JsonValue summary = seed.summary().<JsonValue>map(this::encodeSummary).orElse(JsonNull.INSTANCE);
        return JsonObjects.builder()
                .putNumber("schemaVersion", SCHEMA_VERSION)
                .put("sourceIdentity", source)
                .putNumber("sourceOrdinal", seed.sourceOrdinal())
                .put("messages", new JsonArray(messages))
                .put("summary", summary)
                .build();
    }

    /**
     * 按精确字段集还原 Seed，禁止默认补造来源、ordinal 或上下文摘要。
     */
    public InitialContextSeed decodeValue(JsonValue value) {
        JsonObject root = object(value, "schemaVersion", "sourceIdentity", "sourceOrdinal", "messages", "summary");
        if (integer(root.get("schemaVersion")) != SCHEMA_VERSION) {
            throw invalid();
        }
        JsonObject source = object(root.get("sourceIdentity"), "tenantId", "threadId", "turnId");
        InitialContextSeed.SourceIdentity sourceIdentity = new InitialContextSeed.SourceIdentity(
                text(source.get("tenantId")), text(source.get("threadId")), text(source.get("turnId")));
        List<ContextMessage> messages = array(root.get("messages")).stream().map(this::decodeMessage).toList();
        JsonValue summaryValue = root.get("summary");
        java.util.Optional<SummaryDocument> summary = summaryValue == JsonNull.INSTANCE
                ? java.util.Optional.empty() : java.util.Optional.of(decodeSummary(summaryValue));
        return new InitialContextSeed(sourceIdentity, number(root.get("sourceOrdinal")), messages, summary);
    }

    /**
     * 保留历史消息身份、ordinal、预算估计与完整 block 顺序。
     */
    private JsonObject encodeMessage(ContextMessage message) {
        List<JsonValue> blocks = message.blocks().stream().<JsonValue>map(this::encodeBlock).toList();
        return JsonObjects.builder()
                .putText("messageId", message.messageId())
                .putText("turnId", message.turnId())
                .putNumber("ordinal", message.ordinal())
                .putText("role", message.role().name())
                .put("blocks", new JsonArray(blocks))
                .putNumber("estimatedTokens", message.estimatedTokens())
                .build();
    }

    /**
     * 穷举领域消息块；opaque reasoning 和完整 Tool 输出均不经 ModelContent 投影丢字段。
     */
    private JsonObject encodeBlock(ContextMessage.Block block) {
        JsonObjectBuilder value = JsonObjects.builder();
        if (block instanceof ContextMessage.TextBlock(String value1)) {
            return value.putText("type", "text").putText("value", value1).build();
        }
        if (block instanceof ContextMessage.AttachmentBlock(String attachmentId)) {
            return value.putText("type", "attachment").putText("attachmentId", attachmentId).build();
        }
        if (block instanceof ContextMessage.ReasoningBlock(ReasoningContent content)) {
            return value.putText("type", "reasoning").putText("providerId", content.providerId())
                    .putText("modelId", content.modelId()).putText("api", content.api())
                    .putText("upstreamModel", content.upstreamModel())
                    .putText("endpointFingerprint", content.endpointFingerprint())
                    .putText("wireField", content.wireField()).putText("nativeJson", content.nativeJson()).build();
        }
        if (block instanceof ContextMessage.ToolCallBlock(String id, String name1, String arguments)) {
            return value.putText("type", "tool_call").putText("callId", id)
                    .putText("name", name1).putText("arguments", arguments).build();
        }
        if (block instanceof ContextMessage.ToolResultBlock(
                String callId, String name, ContextMessage.ToolOutput output
        )) {
            return value.putText("type", "tool_result").putText("callId", callId)
                    .putText("name", name).putText("content", output.content())
                    .put("artifactReference", nullableText(output.artifactReference()))
                    .put("exitCode", nullableInteger(output.exitCode()))
                    .put("error", nullableText(output.error()))
                    .putBoolean("promptProjection", output.promptProjection())
                    .put("media", encodeToolMedia(output.media())).build();
        }
        throw invalid();
    }

    /**
     * 严格还原消息身份，不依赖消息文本猜测历史顺序。
     */
    private ContextMessage decodeMessage(JsonValue value) {
        JsonObject node = object(value, "messageId", "turnId", "ordinal", "role", "blocks", "estimatedTokens");
        List<ContextMessage.Block> blocks = array(node.get("blocks")).stream().map(this::decodeBlock).toList();
        return new ContextMessage(text(node.get("messageId")), text(node.get("turnId")),
                number(node.get("ordinal")), ContextMessage.Role.valueOf(text(node.get("role"))), blocks,
                integer(node.get("estimatedTokens")));
    }

    /**
     * 只接受当前版本声明的 block 字段闭集，非法或未来 block 明确 fail closed。
     */
    private ContextMessage.Block decodeBlock(JsonValue value) {
        JsonObject node = objectAtLeastType(value);
        return switch (text(node.get("type"))) {
            case "text" -> {
                requireFields(node, "type", "value");
                yield new ContextMessage.TextBlock(text(node.get("value")));
            }
            case "attachment" -> {
                requireFields(node, "type", "attachmentId");
                yield new ContextMessage.AttachmentBlock(text(node.get("attachmentId")));
            }
            case "reasoning" -> {
                requireFields(node, "type", "providerId", "modelId", "api", "upstreamModel",
                        "endpointFingerprint", "wireField", "nativeJson");
                yield new ContextMessage.ReasoningBlock(new ReasoningContent(text(node.get("providerId")),
                        text(node.get("modelId")), text(node.get("api")), text(node.get("upstreamModel")),
                        text(node.get("endpointFingerprint")), text(node.get("wireField")), text(node.get("nativeJson"))));
            }
            case "tool_call" -> {
                requireFields(node, "type", "callId", "name", "arguments");
                yield new ContextMessage.ToolCallBlock(text(node.get("callId")), text(node.get("name")),
                        text(node.get("arguments")));
            }
            case "tool_result" -> {
                requireFields(node, "type", "callId", "name", "content", "artifactReference", "exitCode",
                        "error", "promptProjection", "media");
                Integer exitCode = nullableInteger(node.get("exitCode"));
                ContextMessage.ToolOutput output = new ContextMessage.ToolOutput(text(node.get("content")),
                        nullableText(node.get("artifactReference")), exitCode, nullableText(node.get("error")),
                        bool(node.get("promptProjection")), decodeToolMedia(node.get("media")));
                yield new ContextMessage.ToolResultBlock(text(node.get("callId")), text(node.get("name")), output);
            }
            default -> throw invalid();
        };
    }

    /**
     * 冻结媒体只保存 opaque 引用，禁止把 provider request 中的 Base64 复制进 child seed。
     */
    private JsonArray encodeToolMedia(List<io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference> media) {
        return new JsonArray(media.stream().map(reference -> (JsonValue) JsonObjects.builder()
                .putText("attachmentId", reference.attachmentId()).putText("mediaType", reference.mediaType())
                .putText("displayName", reference.displayName()).putNumber("sizeBytes", reference.sizeBytes())
                .build()).toList());
    }

    /**
     * 严格解码 seed 媒体身份，避免跨会话继承时凭图片元数据伪造附件读取权限。
     */
    private List<io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference> decodeToolMedia(JsonValue value) {
        if (!(value instanceof JsonArray array) || array.values().size() > 10) {
            throw invalid();
        }
        return array.values().stream().map(item -> {
            JsonObject node = object(item, "attachmentId", "mediaType", "displayName", "sizeBytes");
            return new io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference(
                    text(node.get("attachmentId")), text(node.get("mediaType")), text(node.get("displayName")),
                    number(node.get("sizeBytes")));
        }).toList();
    }

    /**
     * 保留摘要所有事实分区和退休证据，不能把它压成无法恢复来源的 Prompt 文本。
     */
    private JsonObject encodeSummary(SummaryDocument summary) {
        JsonObjectBuilder builder = JsonObjects.builder();
        builder.put("goals", facts(summary.goals())).put("constraints", facts(summary.constraints()))
                .put("completedProgress", facts(summary.completedProgress()))
                .put("currentProgress", facts(summary.currentProgress())).put("blockers", facts(summary.blockers()))
                .put("decisions", facts(summary.decisions())).put("nextSteps", facts(summary.nextSteps()))
                .put("criticalFacts", facts(summary.criticalFacts())).put("files", facts(summary.files()))
                .put("pendingEffects", facts(summary.pendingEffects()));
        List<JsonValue> retirements = summary.retirements().stream().<JsonValue>map(retirement -> JsonObjects.builder()
                .putText("text", retirement.text()).putNumber("sourceOrdinal", retirement.sourceOrdinal())
                .putText("status", retirement.status().name()).build()).toList();
        builder.put("retirements", new JsonArray(retirements));
        return builder.build();
    }

    /**
     * 严格恢复每个摘要分区原有顺序和 source ordinal。
     */
    private SummaryDocument decodeSummary(JsonValue value) {
        JsonObject node = object(value, SUMMARY_FIELDS.toArray(String[]::new));
        List<SummaryDocument.Retirement> retirements = array(node.get("retirements")).stream().map(item -> {
            JsonObject retirement = object(item, "text", "sourceOrdinal", "status");
            return new SummaryDocument.Retirement(text(retirement.get("text")),
                    number(retirement.get("sourceOrdinal")),
                    SummaryDocument.Status.valueOf(text(retirement.get("status"))));
        }).toList();
        return new SummaryDocument(readFacts(node, "goals"), readFacts(node, "constraints"),
                readFacts(node, "completedProgress"), readFacts(node, "currentProgress"),
                readFacts(node, "blockers"), readFacts(node, "decisions"), readFacts(node, "nextSteps"),
                readFacts(node, "criticalFacts"), readFacts(node, "files"), readFacts(node, "pendingEffects"),
                retirements);
    }
}
