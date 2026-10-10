// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.engine;

import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.ResourceReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.infrastructure.json.JsonValueCodecFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证准入批次的严格 schema 可无损恢复，且不按后续队列状态重新构造正文。 */
final class AgentInitialQueuedInputsCodecTest {
    private static final Instant NOW = Instant.parse("2026-09-27T02:03:04Z");
    private static final AgentStore.ExecutionKey EXECUTION = new AgentStore.ExecutionKey(
            new AgentStore.ConversationKey("tenant_queue", "thread_queue", "user_queue"), "turn_queue");
    private static final String OPERATION_KEY = AgentRequestFingerprint.internalSubmissionOperationKey(
            new io.github.kongweiguang.ja.agent.domain.model.AgentIdentity("tenant_queue", "thread_queue",
                    "turn_queue", "user_queue"), "stable_operation");

    /** 保存有序、带来源的完整 MESSAGE 内容并拒绝错用另一 admission operation。 */
    @Test
    void roundTripsTheExactAdmissionBatchAndOperationIdentity() {
        AgentInitialQueuedInputsCodec codec = new AgentInitialQueuedInputsCodec();
        List<AgentInputCommand.Enqueue> inputs = List.of(new AgentInputCommand.Enqueue(
                "mailbox_operation", "mailbox_input", AgentInputCommand.Kind.MESSAGE,
                new UserContent(List.of(new ResourceReferenceContent("workspace_a", "docs/plan.md", "file"),
                        new AttachmentContent("att_mailbox"), new TextContent("please inspect the attached plan"))),
                Map.of("ja.mailbox.messageId", "message_a", "ja.mailbox.senderThreadId", "thread_parent")));

        AgentStore.RecordWrite write = codec.encode(EXECUTION, OPERATION_KEY, inputs, NOW);
        AgentStore.StoredRecord stored = stored(write, 2);

        assertEquals(inputs, codec.decode(write, EXECUTION, OPERATION_KEY));
        assertEquals(inputs, codec.decode(stored, EXECUTION, OPERATION_KEY));
        assertThrows(IllegalStateException.class,
                () -> codec.decode(stored, EXECUTION, "operation_other"));
    }

    /** 空批次仍写有身份的记录，使其可与没有准入回执严格区分。 */
    @Test
    void encodesAnExplicitEmptyBatch() {
        AgentInitialQueuedInputsCodec codec = new AgentInitialQueuedInputsCodec();
        AgentStore.RecordWrite write = codec.encode(EXECUTION, "operation_empty", List.of(), NOW);

        assertEquals(List.of(), codec.decode(stored(write, 1), EXECUTION, "operation_empty"));
        assertThrows(IllegalStateException.class,
                () -> codec.decode(new AgentStore.StoredRecord(write.recordId(), write.recordType(), 2, 1,
                        write.payload(), NOW), EXECUTION, "operation_empty"));
    }

    /** 未知 Kind 保持固定存储错误，非法枚举文本不能从任何包装层的 cause 中泄漏。 */
    @Test
    void rejectsUnknownKindWithoutLeakingValueThroughCauseChain() {
        AgentInitialQueuedInputsCodec codec = new AgentInitialQueuedInputsCodec();
        AgentStore.RecordWrite write = codec.encode(EXECUTION, OPERATION_KEY, List.of(new AgentInputCommand.Enqueue(
                "mailbox_operation", "mailbox_input", AgentInputCommand.Kind.MESSAGE,
                new UserContent(List.of(new TextContent("queued input"))), Map.of())), NOW);
        String sentinel = "unknown-input-kind-sentinel";
        String encoded = JsonValueCodecFactory.standard().encode(write.payload());
        String payload = encoded.replace("\"kind\":\"MESSAGE\"", "\"kind\":\"" + sentinel + "\"");
        assertNotEquals(encoded, payload);
        AgentStore.StoredRecord stored = new AgentStore.StoredRecord(write.recordId(), write.recordType(),
                write.payloadSchemaVersion(), 1, JsonValueCodecFactory.standard().decodeObject(payload), NOW);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> codec.decode(stored, EXECUTION, OPERATION_KEY));

        assertEquals("invalid stored initial input batch", failure.getMessage());
        assertFalse(failure.getMessage().contains(sentinel));
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            assertFalse(cause.toString().contains(sentinel));
        }
    }

    /** 固定数据库回读值的本地序号，编码值本身不依赖测试分配的 sequence。 */
    private static AgentStore.StoredRecord stored(AgentStore.RecordWrite write, long sequence) {
        return new AgentStore.StoredRecord(write.recordId(), write.recordType(), write.payloadSchemaVersion(),
                sequence, write.payload(), write.occurredAt());
    }
}
