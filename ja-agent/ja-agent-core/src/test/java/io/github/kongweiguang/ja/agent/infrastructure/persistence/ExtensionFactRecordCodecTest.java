// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.persistence;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;

import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool.ExtensionFact;

/** 扩展事实通过独立信封保留宿主身份，不能借用核心记录类型或跳过版本校验。 */
final class ExtensionFactRecordCodecTest {
    /** 往返保留 namespace、内层版本与原 JsonObject，不把宿主 payload 压平成字段集合。 */
    @Test
    void preservesEnvelopeIdentityAndPayload() {
        ExtensionFactRecordCodec codec = new ExtensionFactRecordCodec();
        ExtensionFact fact = new AgentTool.ExtensionFact("ja.tool", "presentation", 3,
                new JsonObject(Map.of("callId", new JsonText("call_1"))));
        Instant at = Instant.parse("2026-09-26T02:00:00Z");
        AgentStore.RecordWrite write = codec.encode("record_1", fact, at);

        assertEquals(ExtensionFactRecordCodec.RECORD_TYPE, write.recordType());
        assertEquals(1, write.payloadSchemaVersion());
        assertEquals(fact, codec.decode(new AgentStore.StoredRecord(write.recordId(), write.recordType(),
                write.payloadSchemaVersion(), 1, write.payload(), write.occurredAt())));
    }

    /** 核心或旧格式记录不能被扩展解码器当成宿主事实接收。 */
    @Test
    void rejectsAnotherRecordType() {
        ExtensionFactRecordCodec codec = new ExtensionFactRecordCodec();
        AgentStore.StoredRecord stored = new AgentStore.StoredRecord("record_1", "agent.tool.result", 1, 1, JsonObject.empty(),
                Instant.parse("2026-09-26T02:00:00Z"));

        assertThrows(IllegalArgumentException.class, () -> codec.decode(stored));
    }
}
