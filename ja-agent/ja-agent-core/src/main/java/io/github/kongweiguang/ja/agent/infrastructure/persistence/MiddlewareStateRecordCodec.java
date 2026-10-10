// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.persistence;


import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;

import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 以完整状态快照记录扩展 patch，使执行恢复可按存储顺序重建并检测缺档或降级。
 */
public final class MiddlewareStateRecordCodec implements io.github.kongweiguang.ja.agent.application.gateway.store.MiddlewareStateRecordCodec {
    /**
     * 中间件状态使用独立记录类型，避免与业务扩展事实混淆或被事实投影覆盖。
     */
    public static final String RECORD_TYPE = "agent.middleware.state";
    /**
     * 外层版本只描述固定信封；中间件自身状态结构版本由注册项单独声明。
     */
    public static final int ENVELOPE_VERSION = 1;
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "namespace", "stateSchemaVersion", "revision", "value");
    private final JsonValueCodec json;

    /**
     * 复用 Core 唯一严格 JSON codec，避免存储 JSON 与中间件实际状态出现两套数字语义。
     */
    public MiddlewareStateRecordCodec(JsonValueCodec json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 要求当前快照由同一冻结 registration 管理，拒绝动态改名或 schema 升级中的静默清空。
     */
    private static void validateBinding(
            ExtensionRegistry.MiddlewareRegistration registration,
            AgentMiddleware.ExtensionStateView current) {
        Objects.requireNonNull(registration, "registration");
        Objects.requireNonNull(current, "current");
        if (!registration.namespace().equals(current.namespace())
            || registration.stateSchemaVersion() != current.schemaVersion()) {
            throw new IllegalArgumentException("middleware state registration mismatch");
        }
    }

    /**
     * 从 envelope 中读取严格字符串字段，不让缺字段或非字符串值伪装成有效 namespace。
     */
    private static String text(JsonObject object, String field) {
        JsonValue value = object.get(field);
        if (!(value instanceof JsonText(
                String value1
        ))) {
            throw new IllegalArgumentException("invalid middleware " + field);
        }
        return value1;
    }

    /**
     * 读取精确范围内的正 schema 整数，拒绝浮点、溢出和不兼容的隐式取整。
     */
    private static int exactInt(JsonObject object, String field) {
        long value = exactLong(object, field);
        if (value < 1 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid middleware " + field);
        }
        return (int) value;
    }

    /**
     * 读取非负 long 数字，保留 JSON 十进制精度并拒绝溢出。
     */
    private static long exactLong(JsonObject object, String field) {
        JsonValue value = object.get(field);
        if (!(value instanceof JsonNumber(java.math.BigDecimal value1))) {
            throw new IllegalArgumentException("invalid middleware " + field);
        }
        try {
            long result = value1.longValueExact();
            if (result < 0) {
                throw new IllegalArgumentException("invalid middleware " + field);
            }
            return result;
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("invalid middleware " + field, failure);
        }
    }

    /**
     * 统一处理 revision 上限，避免恢复或提交时 long 溢出后回绕成旧状态。
     */
    private static long nextRevision(long current) {
        try {
            return Math.addExact(current, 1);
        } catch (ArithmeticException failure) {
            throw new IllegalStateException("middleware state revision is exhausted", failure);
        }
    }

    /**
     * 将非空 patch 编成下一个完整快照；空或净效果为空的 patch 不推进 revision、不产生记录。
     */
    public Optional<AgentStore.RecordWrite> encodePatch(
            String recordId,
            ExtensionRegistry.MiddlewareRegistration registration,
            AgentMiddleware.ExtensionStateView current,
            AgentMiddleware.ExtensionStatePatch patch,
            Instant occurredAt) {
        Objects.requireNonNull(patch, "patch");
        validateBinding(registration, current);
        JsonObject nextValue = patch.apply(current);
        if (patch.isEmpty() || nextValue.equals(current.value())) {
            return Optional.empty();
        }

        long revision = nextRevision(current.revision());
        AgentMiddleware.ExtensionStateView next = new AgentMiddleware.ExtensionStateView(
                registration.namespace(), revision, registration.stateSchemaVersion(), nextValue);
        LinkedHashMap<String, JsonValue> envelope = new LinkedHashMap<>();
        envelope.put("namespace", new JsonText(next.namespace()));
        envelope.put("stateSchemaVersion", new JsonNumber(next.schemaVersion()));
        envelope.put("revision", new JsonNumber(next.revision()));
        envelope.put("value", next.value());
        AgentStore.RecordWrite write = new AgentStore.RecordWrite(
                recordId,
                RECORD_TYPE,
                ENVELOPE_VERSION,
                new JsonObject(envelope),
                Objects.requireNonNull(occurredAt, "occurredAt"));
        return Optional.of(write);
    }

    /**
     * 解码提交前尚未分配 Store 序号的状态快照，供事务参与者识别 Core 记录且不伪造运行时注册项。
     */
    public AgentMiddleware.ExtensionStateView decode(AgentStore.RecordWrite record) {
        Objects.requireNonNull(record, "record");
        return decodeSnapshot(record.recordType(), record.payloadSchemaVersion(), json.encode(record.payload()));
    }

    /**
     * 解码紧随当前快照的记录；逐条要求连续 revision，防止恢复时跳过或覆盖中间状态。
     */
    public AgentMiddleware.ExtensionStateView decodeNext(
            AgentStore.StoredRecord record,
            ExtensionRegistry.MiddlewareRegistration registration,
            AgentMiddleware.ExtensionStateView current) {
        Objects.requireNonNull(record, "record");
        validateBinding(registration, current);
        AgentMiddleware.ExtensionStateView next = decodeSnapshot(
                record.recordType(), record.payloadSchemaVersion(), json.encode(record.payload()));
        if (!registration.namespace().equals(next.namespace())
            || next.schemaVersion() != registration.stateSchemaVersion()
            || next.revision() != nextRevision(current.revision())) {
            throw new IllegalArgumentException("middleware state identity or revision mismatch");
        }
        return next;
    }

    /**
     * 共享信封解码规则，避免准入事务与恢复 reducer 对字段闭集或数字边界各自解释。
     */
    private AgentMiddleware.ExtensionStateView decodeSnapshot(String recordType, int payloadSchemaVersion,
                                                              String payloadJson) {
        if (!RECORD_TYPE.equals(recordType) || payloadSchemaVersion != ENVELOPE_VERSION) {
            throw new IllegalArgumentException("unsupported middleware state record");
        }
        JsonObject envelope = json.decodeObject(payloadJson);
        if (!envelope.members().keySet().equals(ENVELOPE_FIELDS)) {
            throw new IllegalArgumentException("invalid middleware state envelope");
        }
        String namespace = text(envelope, "namespace");
        int schemaVersion = exactInt(envelope, "stateSchemaVersion");
        long revision = exactLong(envelope, "revision");
        if (revision == 0) {
            throw new IllegalArgumentException("middleware state revision must be positive");
        }
        JsonValue rawValue = envelope.get("value");
        if (!(rawValue instanceof JsonObject value)) {
            throw new IllegalArgumentException("invalid middleware state value");
        }
        return new AgentMiddleware.ExtensionStateView(namespace, revision, schemaVersion, value);
    }
}
