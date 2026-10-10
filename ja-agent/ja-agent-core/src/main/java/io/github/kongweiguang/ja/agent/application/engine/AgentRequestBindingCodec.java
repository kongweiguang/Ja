// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.engine;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonNull;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.ModelSelection;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentRecordIds;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjectBuilder;

/**
 * 为 Engine 恢复保存受版本控制的请求路由元数据，不依赖宿主 Jackson 类型。
 */
public final class AgentRequestBindingCodec {
    public static final String RECORD_TYPE = "agent.request.binding";
    private static final int SCHEMA_VERSION = 3;
    private static final String STABLE_FACT_KEY = "request-binding:v3";

    /**
     * 根据 AgentExecutionKey 派生固定记录身份，不受可变配置或随机 UUID 影响。
     */
    public static String recordId(AgentStore.ExecutionKey execution) {
        return AgentRecordIds.derive(Objects.requireNonNull(execution, "execution"), RECORD_TYPE, STABLE_FACT_KEY);
    }

    /**
     * 按固定字段顺序构造当前版本请求绑定；完整 Schema 与属性一同冻结以支持恢复。
     */
    private static JsonObject toJson(AgentRequestBinding binding) {
        JsonObjectBuilder attributes = JsonObjects.builder();
        new TreeMap<>(binding.contextAttributes()).forEach(attributes::putText);
        AgentExecutionRequest.HistorySelection history = binding.history();
        JsonObjectBuilder historyJson = JsonObjects.builder()
                .putText("mode", history.mode().name())
                .put("visibleTurnIds", new JsonArray(history.visibleTurnIds().stream()
                        .map(JsonText::new).map(value -> (JsonValue) value).toList()))
                .put("expectedThreadRevision", history.expectedThreadRevision() == null
                        ? JsonNull.INSTANCE : new JsonNumber(history.expectedThreadRevision()));
        ModelSelection selection = binding.modelSelection();
        JsonObjectBuilder model = JsonObjects.builder().putText("providerId", selection.providerId())
                .putText("modelId", selection.modelId()).putText("api", selection.api().name())
                .putText("upstreamModel", selection.upstreamModel());
        return JsonObjects.builder().putNumber("schemaVersion", SCHEMA_VERSION)
                .putText("tenantId", binding.identity().tenantId())
                .putText("threadId", binding.identity().threadId())
                .putText("turnId", binding.identity().turnId())
                .putText("userId", binding.identity().userId())
                .putText("kind", binding.kind().name())
                .put("contextAttributes", attributes.build())
                .put("history", historyJson.build())
                .putText("acceptedAt", binding.acceptedAt().toString())
                .put("modelSelection", model.build())
                .putText("runtimeBindingFingerprint", binding.runtimeBindingFingerprint())
                .putText("initialQueuedInputsRecordId", binding.initialQueuedInputsRecordId())
                .put("outputSchema", binding.outputSchema().<JsonValue>map(value -> value).orElse(JsonNull.INSTANCE))
                .build();
    }

    /**
     * 读取属性键值；持久属性仅是有界文本，不执行类型推断或变量插值。
     */
    private static Map<String, String> attributes(JsonObject object) {
        Map<String, String> attributes = new LinkedHashMap<>();
        object.members().forEach((key, value) -> attributes.put(key, text(value)));
        return Map.copyOf(attributes);
    }

    /**
     * 只接受当前执行准入来源闭集，丢弃 Enum.valueOf 中可能回显的持久值。
     */
    private static AgentExecutionRequest.AdmissionKind admissionKind(String value) {
        try {
            return AgentExecutionRequest.AdmissionKind.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            throw corrupt();
        }
    }

    /**
     * 历史模式来自版本化绑定；未知值按损坏记录处理且不进入异常 cause。
     */
    private static AgentExecutionRequest.HistorySelection.Mode historyMode(String value) {
        try {
            return AgentExecutionRequest.HistorySelection.Mode.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            throw corrupt();
        }
    }

    /**
     * Provider 协议必须属于 Core 支持的闭集，不能把未知持久文本留在诊断链。
     */
    private static ModelPort.Api modelApi(String value) {
        try {
            return ModelPort.Api.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            throw corrupt();
        }
    }

    /**
     * 保留显式历史顺序及 revision，包括带 CAS 的空路径；未知模式只产生固定损坏错误，不回显持久文本。
     */
    private static AgentExecutionRequest.HistorySelection history(JsonObject object) {
        fields(object, "mode", "visibleTurnIds", "expectedThreadRevision");
        AgentExecutionRequest.HistorySelection.Mode mode = historyMode(text(object.get("mode")));
        JsonValue rawIds = object.get("visibleTurnIds");
        if (!(rawIds instanceof JsonArray(List<JsonValue> values1))) {
            throw corrupt();
        }
        List<String> values = new ArrayList<>(values1.size());
        for (JsonValue value : values1) {
            values.add(text(value));
        }
        JsonValue rawRevision = object.get("expectedThreadRevision");
        if (mode == AgentExecutionRequest.HistorySelection.Mode.EXPLICIT) {
            return AgentExecutionRequest.HistorySelection.explicit(values, integer(rawRevision));
        }
        if (rawRevision != JsonNull.INSTANCE) {
            throw corrupt();
        }
        return switch (mode) {
            case CURRENT_CONVERSATION -> AgentExecutionRequest.HistorySelection.currentConversation();
            case EMPTY -> AgentExecutionRequest.HistorySelection.empty();
            case EXPLICIT -> throw corrupt();
        };
    }

    /**
     * 恢复唯一非秘密 Provider/model/protocol 选择，不包含 endpoint、key 或 generation；协议枚举受当前闭集约束。
     */
    private static ModelSelection modelSelection(JsonObject object) {
        fields(object, "providerId", "modelId", "api", "upstreamModel");
        return new ModelSelection(text(object.get("providerId")), text(object.get("modelId")),
                modelApi(text(object.get("api"))), text(object.get("upstreamModel")));
    }

    /**
     * 从强类型值闭集中提取对象并拒绝标量或数组伪装。
     */
    private static JsonObject object(JsonValue value) {
        if (!(value instanceof JsonObject object)) {
            throw corrupt();
        }
        return object;
    }

    /**
     * 恢复可选的终答约束；非对象或错误的 JSON 类型都作为损坏请求绑定拒绝。
     */
    private static java.util.Optional<JsonObject> outputSchema(JsonValue value) {
        if (value == JsonNull.INSTANCE) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(object(value));
    }

    /**
     * 精确检查 schema 字段闭集，版本演进必须显式新增版本而非忽略未知内容。
     */
    private static void fields(JsonObject object, String... names) {
        if (!object.members().keySet().equals(Set.of(names))) {
            throw corrupt();
        }
    }

    /**
     * 不把数字或布尔值隐式字符串化为标识或属性。
     */
    private static String text(JsonValue value) {
        if (!(value instanceof JsonText(String value1))) {
            throw corrupt();
        }
        return value1;
    }

    /**
     * 仅接受可精确表示为 long 的整数，避免 revision 精度被浮点化。
     */
    private static long integer(JsonValue value) {
        if (!(value instanceof JsonNumber(BigDecimal decimal))) {
            throw corrupt();
        }
        try {
            return decimal.longValueExact();
        } catch (ArithmeticException failure) {
            throw corrupt(failure);
        }
    }

    /**
     * 用不包含持久正文的稳定异常汇报损坏绑定。
     */
    private static IllegalStateException corrupt() {
        return new IllegalStateException("invalid stored agent request binding");
    }

    /**
     * 保留失败栈帧供本地诊断，但丢弃异常消息和下层 cause，避免持久正文进入日志。
     */
    private static IllegalStateException corrupt(Throwable cause) {
        IllegalStateException sanitizedCause = new IllegalStateException("stored agent request cause redacted");
        sanitizedCause.setStackTrace(cause.getStackTrace());
        return new IllegalStateException("invalid stored agent request binding", sanitizedCause);
    }

    /**
     * 派生同一权威用户会话下的执行键，所有读写身份都来自公共 AgentIdentity。
     */
    private static AgentStore.ExecutionKey key(AgentIdentity identity) {
        return new AgentStore.ExecutionKey(new AgentStore.ConversationKey(
                identity.tenantId(), identity.threadId(), identity.userId()),
                identity.turnId());
    }

    /**
     * 生成可与执行准入事实同事务写入的记录，模型密钥和配置代际不会进入 payload。
     */
    public AgentStore.RecordWrite encode(AgentExecutionRequest request, AgentRequestRuntime runtime) {
        AgentRequestBinding binding = AgentRequestBinding.from(request, runtime);
        return encode(binding);
    }

    /**
     * 编码已由可信迁移器解析的中立绑定，使历史执行能使用同一严格 schema 而无需伪造运行时对象。
     */
    public AgentStore.RecordWrite encode(AgentRequestBinding binding) {
        AgentRequestBinding value = Objects.requireNonNull(binding, "binding");
        AgentStore.ExecutionKey execution = key(value.identity());
        return new AgentStore.RecordWrite(recordId(execution), RECORD_TYPE, SCHEMA_VERSION,
                toJson(value), value.acceptedAt());
    }

    /**
     * 从稳定记录身份读取恢复元数据，并检查其记录水位确属当前执行快照。
     */
    public AgentRequestBinding read(AgentStore store, AgentStore.ExecutionSnapshot snapshot,
                                    AgentIdentity requestedIdentity) {
        Objects.requireNonNull(store, "store");
        AgentStore.ExecutionSnapshot state = Objects.requireNonNull(snapshot, "snapshot");
        AgentIdentity identity = Objects.requireNonNull(requestedIdentity, "requestedIdentity");
        AgentStore.ExecutionKey execution = key(identity);
        if (!state.key().equals(execution)) {
            throw corrupt();
        }
        AgentStore.StoredRecord record = store.readRecord(execution, recordId(execution)).orElseThrow(
                AgentRequestBindingCodec::corrupt);
        if (record.sequence() > state.lastRecordSequence()) {
            throw corrupt();
        }
        AgentRequestBinding binding = decode(record);
        if (!binding.identity().equals(identity)) {
            throw corrupt();
        }
        return binding;
    }

    /**
     * 只接受本执行唯一版本化绑定记录，拒绝未知字段、类型或 schema version；解析失败只保留脱敏诊断栈。
     */
    public AgentRequestBinding decode(AgentStore.StoredRecord record) {
        AgentStore.StoredRecord stored = Objects.requireNonNull(record, "record");
        if (!stored.recordType().equals(RECORD_TYPE) || stored.payloadSchemaVersion() != SCHEMA_VERSION) {
            throw corrupt();
        }
        try {
            JsonObject root = stored.payload();
            fields(root, "schemaVersion", "tenantId", "threadId", "turnId", "userId", "kind",
                    "contextAttributes", "history", "acceptedAt", "modelSelection", "runtimeBindingFingerprint",
                    "initialQueuedInputsRecordId", "outputSchema");
            if (integer(root.get("schemaVersion")) != SCHEMA_VERSION) {
                throw corrupt();
            }
            AgentIdentity identity = new AgentIdentity(text(root.get("tenantId")), text(root.get("threadId")),
                    text(root.get("turnId")), text(root.get("userId")));
            AgentExecutionRequest.AdmissionKind kind = admissionKind(text(root.get("kind")));
            Map<String, String> attributes = attributes(object(root.get("contextAttributes")));
            AgentExecutionRequest.HistorySelection history = history(object(root.get("history")));
            Instant acceptedAt = Instant.parse(text(root.get("acceptedAt")));
            ModelSelection model = modelSelection(object(root.get("modelSelection")));
            if (!stored.recordId().equals(recordId(key(identity)))) {
                throw corrupt();
            }
            return new AgentRequestBinding(identity, kind, attributes, history, acceptedAt, model,
                    text(root.get("runtimeBindingFingerprint")), text(root.get("initialQueuedInputsRecordId")),
                    outputSchema(root.get("outputSchema")));
        } catch (RuntimeException failure) {
            throw corrupt(failure);
        }
    }
}
