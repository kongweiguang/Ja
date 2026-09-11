// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile;
import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.foundation.error.StorageException;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 无反射编码 Turn Operation 游标，并以精确字段集合拒绝损坏或未来格式。 */
public final class TurnExecutionStateCodec {
    private final ObjectMapper mapper;

    /** 复用 composition 的 ObjectMapper，但所有字段仍由本类显式读写而非 databind record 反射。 */
    public TurnExecutionStateCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** 将 sealed 状态写成版本化对象，持久化永远保存完整快照。 */
    public String write(TurnExecutionState state) {
        Objects.requireNonNull(state, "state");
        ObjectNode root = mapper.createObjectNode().put("schemaVersion", TurnExecutionState.SCHEMA_VERSION);
        root.set("common", common(state.common()));
        switch (state) {
            case TurnExecutionState.Ready ready -> writeReady(root, ready);
            case TurnExecutionState.ProviderPending pending -> {
                root.put("kind", "PROVIDER_PENDING").put("requestId", pending.requestId())
                        .put("messageId", pending.messageId()).put("purpose", pending.purpose().name())
                        .put("envelopeFingerprint", pending.envelopeFingerprint());
                root.set("profile", profile(pending.profile()));
                ObjectNode resume = mapper.createObjectNode();
                writeReady(resume, pending.resume());
                root.set("resume", resume);
            }
            case TurnExecutionState.Tools tools -> root.put("kind", "TOOLS")
                    .put("batchId", tools.batchId())
                    .put("assistantMessageId", tools.assistantMessageId())
                    .put("firstOrdinal", tools.firstOrdinal()).put("lastOrdinal", tools.lastOrdinal())
                    .put("nextOrdinal", tools.nextOrdinal());
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException failure) {
            throw corrupt("cannot encode Turn execution state", failure);
        }
    }

    /** 严格恢复执行游标；未知字段、类型、枚举或 schema 版本一律 fail-closed。 */
    public TurnExecutionState read(String json) {
        try {
            JsonNode parsed = mapper.readTree(json);
            ObjectNode root = object(parsed, "execution state");
            if (integer(root, "schemaVersion") != TurnExecutionState.SCHEMA_VERSION) {
                throw corrupt("unsupported Turn execution state version", null);
            }
            TurnExecutionState.Common common = common(object(root.required("common"), "common"));
            return switch (text(root, "kind")) {
                case "READY" -> readReady(root, common, true);
                case "PROVIDER_PENDING" -> {
                    exact(root, Set.of("schemaVersion", "kind", "common", "requestId", "messageId",
                            "purpose", "profile", "envelopeFingerprint", "resume"));
                    TurnExecutionState.Ready resume = readReady(object(root.required("resume"), "resume"), common, false);
                    yield new TurnExecutionState.ProviderPending(common, text(root, "requestId"),
                            text(root, "messageId"), enumValue(TurnExecutionState.ProviderPurpose.class,
                            text(root, "purpose")), profile(object(root.required("profile"), "profile")),
                            text(root, "envelopeFingerprint"), resume);
                }
                case "TOOLS" -> {
                    exact(root, Set.of("schemaVersion", "kind", "common", "batchId", "assistantMessageId",
                            "firstOrdinal", "lastOrdinal", "nextOrdinal"));
                    yield new TurnExecutionState.Tools(common, text(root, "batchId"),
                            text(root, "assistantMessageId"),
                            integer(root, "firstOrdinal"), integer(root, "lastOrdinal"),
                            integer(root, "nextOrdinal"));
                }
                default -> throw corrupt("unknown Turn execution state kind", null);
            };
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw corrupt("cannot decode Turn execution state", failure);
        }
    }

    /** READY 既可作为顶层状态也可作为 Provider 恢复目标，字段集合按位置精确校验。 */
    private TurnExecutionState.Ready readReady(ObjectNode node, TurnExecutionState.Common common, boolean root) {
        exact(node, root
                ? Set.of("schemaVersion", "kind", "common", "next", "summary")
                : Set.of("kind", "next", "summary"));
        if (!"READY".equals(text(node, "kind"))) throw corrupt("provider resume must be READY", null);
        TurnExecutionState.Next next = enumValue(TurnExecutionState.Next.class, text(node, "next"));
        JsonNode value = node.required("summary");
        TurnExecutionState.SummaryProgress summary = value.isNull() ? null : summary(object(value, "summary"));
        return new TurnExecutionState.Ready(common, next, summary);
    }

    /** READY 写入显式 null Summary，避免缺失字段与“不需要摘要”混为一谈。 */
    private void writeReady(ObjectNode node, TurnExecutionState.Ready ready) {
        node.put("kind", "READY").put("next", ready.next().name());
        if (ready.summary() == null) node.putNull("summary");
        else node.set("summary", summary(ready.summary()));
    }

    /** Common 同时保存绝对截止线与暂停期间不消耗的剩余活动预算。 */
    private ObjectNode common(TurnExecutionState.Common value) {
        ObjectNode node = mapper.createObjectNode().put("modelRound", value.modelRound())
                .put("usedToolCalls", value.usedToolCalls())
                .put("nextProviderOrdinal", value.nextProviderOrdinal())
                .put("deadlineAt", value.deadlineAt().toString())
                .put("activeBudgetMillis", value.activeBudget().toMillis())
                .put("origin", value.origin().name());
        if (value.promptCheckpointId() == null) node.putNull("promptCheckpointId");
        else node.put("promptCheckpointId", value.promptCheckpointId());
        ArrayNode skills = node.putArray("activeSkills");
        value.activeSkills().forEach(skill -> skills.addObject().put("skillId", skill.skillId()));
        return node;
    }

    /** Common 的每个计数和 Skill 引用都无损读取，禁止 Jackson 数字/字符串 coercion。 */
    private TurnExecutionState.Common common(ObjectNode node) {
        exact(node, Set.of("modelRound", "usedToolCalls", "nextProviderOrdinal", "promptCheckpointId",
                "activeSkills", "deadlineAt", "activeBudgetMillis", "origin"));
        JsonNode array = node.required("activeSkills");
        if (!array.isArray()) throw corrupt("activeSkills must be an array", null);
        List<TurnExecutionState.ActiveSkill> skills = new ArrayList<>();
        for (JsonNode entry : array) {
            ObjectNode skill = object(entry, "activeSkill");
            exact(skill, Set.of("skillId"));
            skills.add(new TurnExecutionState.ActiveSkill(text(skill, "skillId")));
        }
        JsonNode promptCheckpointId = node.required("promptCheckpointId");
        if (!promptCheckpointId.isNull() && !promptCheckpointId.isTextual()) {
            throw corrupt("promptCheckpointId has wrong type", null);
        }
        return new TurnExecutionState.Common(integer(node, "modelRound"), integer(node, "usedToolCalls"),
                integer(node, "nextProviderOrdinal"),
                promptCheckpointId.isNull() ? null : promptCheckpointId.textValue(), skills,
                Instant.parse(text(node, "deadlineAt")), enumValue(TurnOrigin.class, text(node, "origin")),
                Duration.ofMillis(integer(node, "activeBudgetMillis")));
    }

    /** Profile 显式编码全部等价键；凭据、URL 与请求正文永不进入 execution JSON。 */
    private ObjectNode profile(ProviderRequestProfile value) {
        ObjectNode node = mapper.createObjectNode().put("providerId", value.providerId())
                .put("modelId", value.modelId()).put("api", value.api())
                .put("upstreamModel", value.upstreamModel())
                .put("accessMode", value.accessMode().name())
                .put("collaborationMode", value.collaborationMode().name())
                .put("configGeneration", value.configGeneration())
                .put("promptRevision", value.promptRevision())
                .put("toolCatalogRevision", value.toolCatalogRevision())
                .put("contextWindowTokens", value.contextWindowTokens())
                .put("maxOutputTokens", value.maxOutputTokens());
        nullable(node, "requestedReasoning", value.requestedReasoning());
        nullable(node, "effectiveReasoning", value.effectiveReasoning());
        return node;
    }

    /** Profile 解码拒绝缺失或额外键，continuation 不能在未知未来字段下被误复用。 */
    private ProviderRequestProfile profile(ObjectNode node) {
        exact(node, Set.of("providerId", "modelId", "api", "upstreamModel", "requestedReasoning",
                "effectiveReasoning", "accessMode", "collaborationMode", "configGeneration", "promptRevision",
                "toolCatalogRevision", "contextWindowTokens", "maxOutputTokens"));
        return new ProviderRequestProfile(text(node, "providerId"), text(node, "modelId"),
                text(node, "api"), text(node, "upstreamModel"), nullableText(node, "requestedReasoning"),
                nullableText(node, "effectiveReasoning"), enumValue(AccessMode.class, text(node, "accessMode")),
                enumValue(CollaborationMode.class, text(node, "collaborationMode")),
                text(node, "configGeneration"), text(node, "promptRevision"),
                text(node, "toolCatalogRevision"), integer(node, "contextWindowTokens"),
                integer(node, "maxOutputTokens"));
    }

    /** Nullable reasoning 必须显式出现，避免缺失字段被误解为 Provider 默认。 */
    private static void nullable(ObjectNode node, String field, String value) {
        if (value == null) node.putNull(field); else node.put(field, value);
    }

    /** 只接受 JSON null 或 text，不允许数字/布尔 coercion。 */
    private static String nullableText(ObjectNode node, String field) {
        JsonNode value = node.required(field);
        if (value.isNull()) return null;
        if (!value.isTextual()) throw corrupt("execution nullable text field has wrong type", null);
        return value.textValue();
    }

    /** Summary 的 usage、子阶段和冻结 prompt 身份整体写入；未知请求另存 usage row。 */
    private ObjectNode summary(TurnExecutionState.SummaryProgress value) {
        ObjectNode node = mapper.createObjectNode().put("summaryJson", value.summaryJson())
                .put("throughOrdinal", value.throughOrdinal()).put("nextChunk", value.nextChunk())
                .put("planFingerprint", value.planFingerprint()).put("stage", value.stage().name())
                .put("targetNextChunk", value.targetNextChunk())
                .put("targetThroughOrdinal", value.targetThroughOrdinal());
        ArrayNode violations = node.putArray("violations");
        value.violations().forEach(violations::add);
        if (value.promptFingerprint() == null) node.putNull("promptFingerprint");
        else node.put("promptFingerprint", value.promptFingerprint());
        node.set("usage", usage(value.usage()));
        return node;
    }

    /** 严格恢复滚动 Summary 的子阶段、目标块、违规码、prompt 身份和已知 Usage。 */
    private TurnExecutionState.SummaryProgress summary(ObjectNode node) {
        exact(node, Set.of("summaryJson", "throughOrdinal", "nextChunk", "planFingerprint", "stage",
                "targetNextChunk", "targetThroughOrdinal", "violations", "promptFingerprint", "usage"));
        JsonNode violationsNode = node.required("violations");
        if (!violationsNode.isArray()) throw corrupt("summary violations must be an array", null);
        List<String> violations = new ArrayList<>();
        for (JsonNode value : violationsNode) {
            if (!value.isTextual()) throw corrupt("summary violation has wrong type", null);
            violations.add(value.textValue());
        }
        JsonNode promptFingerprint = node.required("promptFingerprint");
        if (!promptFingerprint.isNull() && !promptFingerprint.isTextual()) {
            throw corrupt("summary prompt fingerprint has wrong type", null);
        }
        ObjectNode usage = object(node.required("usage"), "summary usage");
        exact(usage, Set.of("inputTokens", "outputTokens", "totalTokens", "cacheReadTokens",
                "cacheWriteTokens"));
        return new TurnExecutionState.SummaryProgress(text(node, "summaryJson"),
                longInteger(node, "throughOrdinal"), integer(node, "nextChunk"),
                text(node, "planFingerprint"), enumValue(TurnExecutionState.SummaryStage.class,
                text(node, "stage")), integer(node, "targetNextChunk"),
                longInteger(node, "targetThroughOrdinal"), violations,
                promptFingerprint.isNull() ? null : promptFingerprint.textValue(),
                new TurnExecutionState.KnownUsage(
                longInteger(usage, "inputTokens"), longInteger(usage, "outputTokens"),
                longInteger(usage, "totalTokens"), longInteger(usage, "cacheReadTokens"),
                longInteger(usage, "cacheWriteTokens")));
    }

    /** Known Usage 由三个显式非负整数构成。 */
    private ObjectNode usage(TurnExecutionState.KnownUsage value) {
        return mapper.createObjectNode().put("inputTokens", value.inputTokens())
                .put("outputTokens", value.outputTokens()).put("totalTokens", value.totalTokens())
                .put("cacheReadTokens", value.cacheReadTokens())
                .put("cacheWriteTokens", value.cacheWriteTokens());
    }

    /** 精确字段集合禁止把未知结构猜测为当前执行游标，避免错误续跑已持久化副作用。 */
    private static void exact(ObjectNode node, Set<String> fields) {
        Set<String> actual = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) throw corrupt("Turn execution state has an unexpected shape", null);
    }

    /** 只接受 JSON object，数组、标量与 null 都代表存储损坏。 */
    private static ObjectNode object(JsonNode node, String field) {
        if (!(node instanceof ObjectNode object)) throw corrupt(field + " must be an object", null);
        return object;
    }

    /** 文本读取禁止隐式 stringify。 */
    private static String text(ObjectNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isTextual()) throw corrupt("execution text field has wrong type", null);
        return value.textValue();
    }

    /** int 游标必须无损且不能以浮点或字符串表示。 */
    private static int integer(ObjectNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw corrupt("execution int field has wrong type", null);
        }
        return value.intValue();
    }

    /** long 游标必须无损，Duration 溢出由领域构造继续 fail-closed。 */
    private static long longInteger(ObjectNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw corrupt("execution long field has wrong type", null);
        }
        return value.longValue();
    }

    /** 枚举只接受当前闭集精确名称，不保留别名。 */
    private static <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException failure) {
            throw corrupt("execution enum field is invalid", failure);
        }
    }

    /** 所有执行 JSON 损坏统一映射为不可恢复的存储状态错误。 */
    private static StorageException corrupt(String message, Throwable cause) {
        return new StorageException(StorageException.Code.INVALID_STATE, message, cause);
    }
}
