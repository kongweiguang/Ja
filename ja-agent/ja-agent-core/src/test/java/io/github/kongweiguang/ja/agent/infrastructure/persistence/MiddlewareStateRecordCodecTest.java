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
import io.github.kongweiguang.ja.agent.infrastructure.json.JsonValueCodecFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware.ExtensionStatePatch;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware.ExtensionStateView;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry.MiddlewareRegistration;

/** 校验 middleware 状态快照的事务记录格式、身份绑定和恢复 revision 连续性。 */
final class MiddlewareStateRecordCodecTest {
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-26T02:00:00Z");

    /** 新状态作为完整快照持久化，恢复只接受 registration 对应的下一 revision。 */
    @Test
    void encodesAndRestoresTheNextSnapshot() {
        MiddlewareStateRecordCodec codec = codec();
        MiddlewareRegistration registration = registration("request-policy", 2);
        ExtensionStateView initial = AgentMiddleware.ExtensionStateView.empty("request-policy", 2);
        ExtensionStatePatch patch = new AgentMiddleware.ExtensionStatePatch(0,
                Map.of("mode", new JsonText("strict")), Set.of());

        AgentStore.RecordWrite write = codec.encodePatch("record_1", registration, initial, patch, OCCURRED_AT)
                .orElseThrow();
        AgentMiddleware.ExtensionStateView restored = codec.decodeNext(
                stored(write, 1), registration, initial);

        assertEquals(MiddlewareStateRecordCodec.RECORD_TYPE, write.recordType());
        assertEquals(MiddlewareStateRecordCodec.ENVELOPE_VERSION, write.payloadSchemaVersion());
        assertEquals(restored, codec.decode(write));
        assertEquals(new AgentMiddleware.ExtensionStateView("request-policy", 1, 2,
                new JsonObject(Map.of("mode", new JsonText("strict")))), restored);
    }

    /** 提交边界无需伪造 Registry/current state，也只接受完整且已推进的 Core 快照。 */
    @Test
    void decodesCommitSnapshotAndRejectsUnsupportedIdentityOrRevision() {
        MiddlewareStateRecordCodec codec = codec();
        LinkedHashMap<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("namespace", new JsonText("request-policy"));
        fields.put("stateSchemaVersion", new JsonNumber(1));
        fields.put("revision", new JsonNumber(0));
        fields.put("value", JsonObject.empty());
        JsonObject payload = new JsonObject(fields);

        AgentStore.RecordWrite zeroRevision = new AgentStore.RecordWrite("record_1",
                MiddlewareStateRecordCodec.RECORD_TYPE, MiddlewareStateRecordCodec.ENVELOPE_VERSION,
                payload, OCCURRED_AT);
        AgentStore.RecordWrite unsupportedEnvelope = new AgentStore.RecordWrite("record_2",
                MiddlewareStateRecordCodec.RECORD_TYPE, MiddlewareStateRecordCodec.ENVELOPE_VERSION + 1,
                payload, OCCURRED_AT);
        AgentStore.RecordWrite wrongType = new AgentStore.RecordWrite("record_3",
                "agent.extension.fact", MiddlewareStateRecordCodec.ENVELOPE_VERSION, payload, OCCURRED_AT);

        assertThrows(IllegalArgumentException.class, () -> codec.decode(zeroRevision));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(unsupportedEnvelope));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(wrongType));
    }

    /** 空 patch 不写记录或推进版本；即使无写入也必须拒绝基于过期快照产生的决策。 */
    @Test
    void omitsUnchangedStateAndRejectsStalePatch() {
        MiddlewareStateRecordCodec codec = codec();
        MiddlewareRegistration registration = registration("request-policy", 1);
        ExtensionStateView initial = new AgentMiddleware.ExtensionStateView("request-policy", 0, 1,
                new JsonObject(Map.of("mode", new JsonText("safe"))));

        assertFalse(codec.encodePatch("record_1", registration, initial,
                AgentMiddleware.ExtensionStatePatch.unchanged(initial), OCCURRED_AT).isPresent());
        assertFalse(codec.encodePatch("record_1", registration, initial,
                new AgentMiddleware.ExtensionStatePatch(0, Map.of("mode", new JsonText("safe")), Set.of()),
                OCCURRED_AT).isPresent());
        assertThrows(IllegalStateException.class, () -> codec.encodePatch("record_1", registration, initial,
                new AgentMiddleware.ExtensionStatePatch(1, Map.of("mode", new JsonText("strict")), Set.of()),
                OCCURRED_AT));
    }

    /** 恢复拒绝跳过 revision、跨 namespace 读取或用新 schema 解释旧状态。 */
    @Test
    void rejectsGapsAndRegistrationMismatch() {
        MiddlewareStateRecordCodec codec = codec();
        MiddlewareRegistration registration = registration("request-policy", 2);
        ExtensionStateView initial = AgentMiddleware.ExtensionStateView.empty("request-policy", 2);
        AgentStore.RecordWrite first = codec.encodePatch("record_1", registration, initial,
                new AgentMiddleware.ExtensionStatePatch(0, Map.of("mode", new JsonText("one")), Set.of()),
                OCCURRED_AT).orElseThrow();
        AgentMiddleware.ExtensionStateView afterFirst = codec.decodeNext(stored(first, 1), registration, initial);
        AgentStore.RecordWrite second = codec.encodePatch("record_2", registration, afterFirst,
                new AgentMiddleware.ExtensionStatePatch(1, Map.of("mode", new JsonText("two")), Set.of()),
                OCCURRED_AT).orElseThrow();

        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeNext(stored(second, 2), registration, initial));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeNext(stored(first, 1), registration("other-policy", 2), initial));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeNext(stored(first, 1), registration("request-policy", 3),
                        AgentMiddleware.ExtensionStateView.empty("request-policy", 3)));
    }

    /** 严格 envelope 只接受已知字段和精确数值，防止损坏记录被当作空状态恢复。 */
    @Test
    void rejectsMalformedEnvelope() {
        MiddlewareStateRecordCodec codec = codec();
        MiddlewareRegistration registration = registration("request-policy", 1);
        ExtensionStateView initial = AgentMiddleware.ExtensionStateView.empty("request-policy", 1);
        LinkedHashMap<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("namespace", new JsonText("request-policy"));
        fields.put("stateSchemaVersion", new JsonNumber(1));
        fields.put("revision", new JsonNumber(new BigDecimal("1.5")));
        fields.put("value", JsonObject.empty());
        fields.put("ignored", new JsonText("unknown"));
        AgentStore.RecordWrite malformed = new AgentStore.RecordWrite("record_1",
                MiddlewareStateRecordCodec.RECORD_TYPE, MiddlewareStateRecordCodec.ENVELOPE_VERSION,
                new JsonObject(fields), OCCURRED_AT);

        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeNext(stored(malformed, 1), registration, initial));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeNext(
                new AgentStore.StoredRecord("record_1", "agent.extension.fact", 1, 1, JsonObject.empty(), OCCURRED_AT),
                registration, initial));
    }

    /** 为单条恢复记录复用其存储分配的有序序号，避免测试隐含页顺序之外的来源。 */
    private static AgentStore.StoredRecord stored(AgentStore.RecordWrite write, long sequence) {
        return new AgentStore.StoredRecord(write.recordId(), write.recordType(), write.payloadSchemaVersion(),
                sequence, write.payload(), write.occurredAt());
    }

    /** 使用一个显式 middleware 身份构造冻结注册项，测试不依赖 classpath 扫描或服务发现。 */
    private static ExtensionRegistry.MiddlewareRegistration registration(String namespace, int schemaVersion) {
        return new ExtensionRegistry().registerMiddleware(new AgentMiddleware() {
            /** 返回本测试固定的状态命名空间。 */
            @Override
            public String id() {
                return namespace;
            }

            /** 返回测试指定 schema，覆盖升级冲突时的恢复拒绝。 */
            @Override
            public int stateSchemaVersion() {
                return schemaVersion;
            }
        }).freeze().middleware().getFirst();
    }

    /** 每个用例共享严格 Jackson JSON codec，确保恢复检查覆盖真实 envelope 编解码路径。 */
    private static MiddlewareStateRecordCodec codec() {
        return new MiddlewareStateRecordCodec(JsonValueCodecFactory.standard());
    }

    /** 防止测试容器实例化契约测试类。 */
    private MiddlewareStateRecordCodecTest() {
    }
}
