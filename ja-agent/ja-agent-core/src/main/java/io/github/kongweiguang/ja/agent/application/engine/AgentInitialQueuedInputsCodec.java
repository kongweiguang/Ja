// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.engine;

import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjectBuilder;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.ResourceReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.UserContentBlock;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentRecordIds;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 冻结首次准入的完整队列批次，供幂等重放读取且不混入后来追加的输入。
 */
public final class AgentInitialQueuedInputsCodec {
    public static final String RECORD_TYPE = "agent.request.initial-inputs";
    public static final int SCHEMA_VERSION = 1;

    /**
     * 准入批次以 operation identity 派生稳定 ID，重试不会生成新事实。
     */
    public static String recordId(AgentStore.ExecutionKey execution, String operationKey) {
        return AgentRecordIds.derive(Objects.requireNonNull(execution, "execution"), RECORD_TYPE,
                Objects.requireNonNull(operationKey, "operationKey"));
    }

    /**
     * 保留正文块次序及宿主来源，内容变化必须改变持久回放结果。
     */
    private static JsonObject enqueue(AgentInputCommand.Enqueue input) {
        JsonObjectBuilder metadata = JsonObjects.builder();
        new TreeMap<>(input.hostMetadata()).forEach(metadata::putText);
        return JsonObjects.builder().putText("operationKey", input.operationKey())
                .putText("inputId", input.inputId()).putText("kind", input.kind().name())
                .put("content", encodeContent(input.content()))
                .put("hostMetadata", metadata.build()).build();
    }

    /**
     * 显式编码 Core 支持的四类用户块，避免抽象多态或宿主反射改变事实形状。
     */
    private static JsonArray encodeContent(UserContent content) {
        List<JsonValue> blocks = new ArrayList<>(content.blocks().size());
        for (UserContentBlock block : content.blocks()) {
            JsonObjectBuilder value = JsonObjects.builder();
            switch (block) {
                case TextContent text -> value.putText("kind", "text").putText("text", text.text());
                case AttachmentContent attachment -> value.putText("kind", "attachment")
                        .putText("attachmentId", attachment.attachmentId());
                case ResourceReferenceContent resource -> value.putText("kind", "resource-reference")
                        .putText("sourceId", resource.sourceId()).putText("resourceId", resource.resourceId())
                        .putText("resourceKind", resource.kind());
                case SkillReferenceContent skill -> value.putText("kind", "skill-reference")
                        .putText("skillId", skill.skillId());
            }
            blocks.add(value.build());
        }
        return new JsonArray(blocks);
    }

    /**
     * 解码严格版本字段并交由领域构造器复验正文顺序与资源约束；非法 Kind 原文不能进入异常链。
     */
    private static AgentInputCommand.Enqueue readEnqueue(JsonObject object) {
        fields(object, "operationKey", "inputId", "kind", "content", "hostMetadata");
        Map<String, String> metadata = new LinkedHashMap<>();
        object(object.get("hostMetadata")).members().forEach((key, value) -> metadata.put(key, text(value)));
        AgentInputCommand.Kind kind;
        try {
            kind = AgentInputCommand.Kind.valueOf(text(object.get("kind")));
        } catch (IllegalArgumentException ignored) {
            throw corrupt();
        }
        return new AgentInputCommand.Enqueue(text(object.get("operationKey")), text(object.get("inputId")),
                kind, readContent(object.get("content")), metadata);
    }

    /**
     * 只接受精确的内容块字段集合，未知块不能被降级为普通文本。
     */
    private static UserContent readContent(JsonValue value) {
        if (!(value instanceof JsonArray(List<JsonValue> values))) {
            throw corrupt();
        }
        List<UserContentBlock> blocks = new ArrayList<>(values.size());
        for (JsonValue raw : values) {
            JsonObject block = object(raw);
            String kind = text(block.get("kind"));
            blocks.add(switch (kind) {
                case "text" -> {
                    fields(block, "kind", "text");
                    yield new TextContent(text(block.get("text")));
                }
                case "attachment" -> {
                    fields(block, "kind", "attachmentId");
                    yield new AttachmentContent(text(block.get("attachmentId")));
                }
                case "resource-reference" -> {
                    fields(block, "kind", "sourceId", "resourceId", "resourceKind");
                    yield new ResourceReferenceContent(text(block.get("sourceId")),
                            text(block.get("resourceId")), text(block.get("resourceKind")));
                }
                case "skill-reference" -> {
                    fields(block, "kind", "skillId");
                    yield new SkillReferenceContent(text(block.get("skillId")));
                }
                default -> throw corrupt();
            });
        }
        return new UserContent(blocks);
    }

    /**
     * 对象和值都保持 JSON 强类型，缺失字段不解释成空内容或默认值。
     */
    private static JsonObject object(JsonValue value) {
        if (!(value instanceof JsonObject object)) {
            throw corrupt();
        }
        return object;
    }

    /**
     * 仅接受文本字段，防止数字、布尔和 JSON null 隐式成为身份或正文。
     */
    private static String text(JsonValue value) {
        if (!(value instanceof JsonText(String value1))) {
            throw corrupt();
        }
        return value1;
    }

    /**
     * Schema 采用闭字段集，新增字段必须显式升级版本。
     */
    private static void fields(JsonObject value, String... names) {
        if (!value.members().keySet().equals(Set.of(names))) {
            throw corrupt();
        }
    }

    /**
     * 只接受精确整数版本。
     */
    private static long integer(JsonValue value) {
        if (!(value instanceof JsonNumber(java.math.BigDecimal value1))) {
            throw corrupt();
        }
        try {
            return value1.longValueExact();
        } catch (ArithmeticException invalid) {
            throw corrupt(invalid);
        }
    }

    /**
     * 不在错误文本中回显 mailbox 正文或宿主 metadata。
     */
    private static IllegalStateException corrupt() {
        return new IllegalStateException("invalid stored initial input batch");
    }

    /**
     * 保留损坏数值或载荷解析的本地根因，同时不把输入正文写入稳定错误消息。
     */
    private static IllegalStateException corrupt(Throwable cause) {
        return new IllegalStateException("invalid stored initial input batch", cause);
    }

    /**
     * 在 admission 中保存精确有序批次；显式记录空数组以区别于不存在的旧准入。
     */
    public AgentStore.RecordWrite encode(AgentStore.ExecutionKey execution, String operationKey,
                                         List<AgentInputCommand.Enqueue> initialInputs, Instant acceptedAt) {
        AgentStore.ExecutionKey key = Objects.requireNonNull(execution, "execution");
        String operation = Objects.requireNonNull(operationKey, "operationKey");
        Instant occurredAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
        List<AgentInputCommand.Enqueue> inputs = AgentInputCommand.freezeInitialQueuedInputs(initialInputs);
        JsonObject payload = JsonObjects.builder()
                .putNumber("schemaVersion", SCHEMA_VERSION)
                .putText("tenantId", key.conversation().tenantId())
                .putText("threadId", key.conversation().threadId())
                .putText("userId", key.conversation().userId())
                .putText("turnId", key.turnId())
                .putText("operationKey", operation)
                .put("inputs", new JsonArray(inputs.stream().map(AgentInitialQueuedInputsCodec::enqueue)
                        .map(value -> (JsonValue) value).toList()))
                .build();
        return new AgentStore.RecordWrite(recordId(key, operation), RECORD_TYPE, SCHEMA_VERSION,
                payload, occurredAt);
    }

    /**
     * 只接受身份、operation key 和派生 recordId 均吻合的已准入批次。
     */
    public List<AgentInputCommand.Enqueue> decode(AgentStore.StoredRecord record,
                                                  AgentStore.ExecutionKey execution,
                                                  String operationKey) {
        AgentStore.StoredRecord stored = Objects.requireNonNull(record, "record");
        return decode(stored.recordId(), stored.recordType(), stored.payloadSchemaVersion(), stored.payload(),
                execution, operationKey);
    }

    /**
     * 在 admission participant 校验事务写入值时使用相同 schema，不伪造数据库分配的序号。
     */
    public List<AgentInputCommand.Enqueue> decode(AgentStore.RecordWrite record,
                                                  AgentStore.ExecutionKey execution,
                                                  String operationKey) {
        AgentStore.RecordWrite write = Objects.requireNonNull(record, "record");
        return decode(write.recordId(), write.recordType(), write.payloadSchemaVersion(), write.payload(),
                execution, operationKey);
    }

    /**
     * 统一校验已提交回读和事务写入，避免两个持久化边界接受不同 payload。
     */
    private List<AgentInputCommand.Enqueue> decode(String recordId, String recordType, int schemaVersion,
                                                   JsonObject payload, AgentStore.ExecutionKey execution,
                                                   String operationKey) {
        AgentStore.ExecutionKey key = Objects.requireNonNull(execution, "execution");
        String operation = Objects.requireNonNull(operationKey, "operationKey");
        if (!RECORD_TYPE.equals(recordType) || schemaVersion != SCHEMA_VERSION
            || !recordId(key, operation).equals(recordId)) {
            throw corrupt();
        }
        try {
            JsonObject root = Objects.requireNonNull(payload, "payload");
            fields(root, "schemaVersion", "tenantId", "threadId", "userId", "turnId", "operationKey", "inputs");
            if (integer(root.get("schemaVersion")) != SCHEMA_VERSION
                || !key.conversation().tenantId().equals(text(root.get("tenantId")))
                || !key.conversation().threadId().equals(text(root.get("threadId")))
                || !key.conversation().userId().equals(text(root.get("userId")))
                || !key.turnId().equals(text(root.get("turnId")))
                || !operation.equals(text(root.get("operationKey")))) {
                throw corrupt();
            }
            if (!(root.get("inputs") instanceof JsonArray(List<JsonValue> values1))) {
                throw corrupt();
            }
            List<AgentInputCommand.Enqueue> decoded = new ArrayList<>(values1.size());
            for (JsonValue value : values1) {
                decoded.add(readEnqueue(object(value)));
            }
            return AgentInputCommand.freezeInitialQueuedInputs(decoded);
        } catch (RuntimeException failure) {
            throw corrupt(failure);
        }
    }
}
