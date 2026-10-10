// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.persistence;


import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;

import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 为扩展事实提供无命名冲突的通用信封；核心记录类型与宿主 namespace 保持分离。
 */
public final class ExtensionFactRecordCodec implements io.github.kongweiguang.ja.agent.application.gateway.store.ExtensionFactRecordCodec {
    /**
     * 所有扩展事实共用 envelope row type，防止 namespace/type 点号拼接产生歧义。
     */
    public static final String RECORD_TYPE = "agent.extension.fact";
    /**
     * 外层协议只承载 namespace、type、扩展 schema 版本和 payload 四个字段。
     */
    public static final int ENVELOPE_VERSION = 1;
    /**
     * 信封字段必须是字符串；异常不回显业务 payload。
     */
    private static String text(JsonObject object, String field) {
        JsonValue value = object.members().get(field);
        if (!(value instanceof JsonText(String value1))) {
            throw new IllegalArgumentException("invalid extension " + field);
        }
        return value1;
    }

    /**
     * 将扩展事实编码为同一执行事务内的普通记录，不接受工具自行指定 recordId。
     */
    public AgentStore.RecordWrite encode(String recordId, AgentTool.ExtensionFact fact, Instant occurredAt) {
        Objects.requireNonNull(fact, "fact");
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("namespace", new JsonText(fact.namespace()));
        fields.put("type", new JsonText(fact.type()));
        fields.put("schemaVersion", new JsonNumber(BigDecimal.valueOf(fact.schemaVersion())));
        fields.put("payload", fact.payload());
        JsonObject payload = new JsonObject(fields);
        return new AgentStore.RecordWrite(recordId, RECORD_TYPE, ENVELOPE_VERSION,
                payload, Objects.requireNonNull(occurredAt, "occurredAt"));
    }

    /**
     * 严格解码 envelope，拒绝未知字段、错误版本或非对象 payload，供宿主按 namespace 投影。
     */
    public AgentTool.ExtensionFact decode(AgentStore.StoredRecord record) {
        Objects.requireNonNull(record, "record");
        if (!RECORD_TYPE.equals(record.recordType()) || record.payloadSchemaVersion() != ENVELOPE_VERSION) {
            throw new IllegalArgumentException("unsupported extension fact record");
        }
        JsonObject object = record.payload();
        if (!object.members().keySet().equals(java.util.Set.of("namespace", "type", "schemaVersion", "payload"))) {
            throw new IllegalArgumentException("invalid extension fact envelope");
        }
        String namespace = text(object, "namespace");
        String type = text(object, "type");
        JsonValue version = object.members().get("schemaVersion");
        if (!(version instanceof JsonNumber(BigDecimal value1)))
            {
                throw new IllegalArgumentException("invalid extension schema version");
            }
        int schemaVersion;
        try {
            schemaVersion = value1.intValueExact();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("invalid extension schema version", failure);
        }
        JsonValue payload = object.members().get("payload");
        if (!(payload instanceof JsonObject jsonPayload))
            {
                throw new IllegalArgumentException("invalid extension payload");
            }
        return new AgentTool.ExtensionFact(namespace, type, schemaVersion, jsonPayload);
    }
}
