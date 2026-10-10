// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.engine;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInternalRequest;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentRequest;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.conversation.ContextInstruction;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 固定准入指纹的规范顺序，防止内部续跑重试遗漏指令或历史路径差异。 */
final class AgentRequestFingerprintTest {
    private static final JsonValueCodec RECEIPT_CODEC = new JacksonJsonValueCodec();

    /** 属性插入顺序不影响语义，而输入与模型变化必须改变摘要。 */
    @Test
    void fingerprintsCanonicalAttributesAndAllSubmittedContent() {
        AgentIdentity identity = new AgentIdentity("tenant_a", "thread_a", "turn_a", "user_a");
        AgentRequest request = AgentRequest.text(identity, "hello", "op_a")
                .withContextAttribute("z", "last")
                .withContextAttribute("a", "first");
        AgentRequest reordered = AgentRequest.text(identity, "hello", "op_a")
                .withContextAttribute("a", "first")
                .withContextAttribute("z", "last");
        AgentRequest changedInput = AgentRequest.text(identity, "changed", "op_a")
                .withContextAttribute("z", "last")
                .withContextAttribute("a", "first");

        AgentRequestFingerprint.PreparedRequest prepared = AgentRequestFingerprint.prepare(request, model("secret-key-a"), "0".repeat(64));
        AgentRequestFingerprint.PreparedRequest same = AgentRequestFingerprint.prepare(reordered, model("secret-key-a"), "0".repeat(64));
        AgentRequestFingerprint.PreparedRequest changedBody = AgentRequestFingerprint.prepare(changedInput, model("secret-key-a"), "0".repeat(64));
        AgentRequestFingerprint.PreparedRequest changedModel = AgentRequestFingerprint.prepare(request, model("secret-key-b"), "0".repeat(64));
        AgentRequestFingerprint.PreparedRequest changedRuntime = AgentRequestFingerprint.prepare(request, model("secret-key-a"), "1".repeat(64));

        assertEquals(prepared.fingerprint(), same.fingerprint());
        assertEquals(prepared.operation().operationKey(), same.operation().operationKey());
        assertEquals(prepared.inputMessageId(), same.inputMessageId());
        assertNotEquals(prepared.fingerprint(), changedBody.fingerprint());
        assertNotEquals(prepared.fingerprint(), changedModel.fingerprint());
        assertNotEquals(prepared.fingerprint(), changedRuntime.fingerprint());
        assertEquals(prepared.requestFingerprint(), changedModel.requestFingerprint());
        String receipt = RECEIPT_CODEC.encode(prepared.operation().receipt());
        assertFalse(receipt.contains("secret-key-a"));
        assertFalse(receipt.contains("secret-key-b"));
    }

    /** 验证请求重试绑定指令和历史语义，但不因系统受理时间变化而失去幂等性。 */
    @Test
    void fingerprintsInternalInstructionHistoryAndAcceptanceTime() {
        AgentIdentity identity = new AgentIdentity("tenant_a", "thread_a", "turn_a", "user_a");
        AgentInternalRequest request = internal(identity, "continue the goal", List.of("prior_a", "prior_b"), 7,
                Instant.parse("2026-09-26T01:02:03Z"));
        AgentInternalRequest same = internal(identity, "continue the goal", List.of("prior_a", "prior_b"), 7,
                Instant.parse("2026-09-26T01:02:03Z"));
        AgentInternalRequest changedInstruction = internal(identity, "use a different rule", List.of("prior_a", "prior_b"), 7,
                Instant.parse("2026-09-26T01:02:03Z"));
        AgentInternalRequest changedHistory = internal(identity, "continue the goal", List.of("prior_b", "prior_a"), 7,
                Instant.parse("2026-09-26T01:02:03Z"));
        AgentInternalRequest changedAcceptedAt = internal(identity, "continue the goal", List.of("prior_a", "prior_b"), 7,
                Instant.parse("2026-09-26T01:02:04Z"));

        AgentRequestFingerprint.PreparedInternalRequest prepared = AgentRequestFingerprint.prepareInternal(request, model("secret-key-a"), "0".repeat(64));
        AgentRequestFingerprint.PreparedInternalRequest repeated = AgentRequestFingerprint.prepareInternal(same, model("secret-key-a"), "0".repeat(64));
        AgentRequestFingerprint.PreparedInternalRequest changedPrompt = AgentRequestFingerprint.prepareInternal(changedInstruction, model("secret-key-a"), "0".repeat(64));
        AgentRequestFingerprint.PreparedInternalRequest changedPath = AgentRequestFingerprint.prepareInternal(changedHistory, model("secret-key-a"), "0".repeat(64));
        AgentRequestFingerprint.PreparedInternalRequest changedTime = AgentRequestFingerprint.prepareInternal(changedAcceptedAt, model("secret-key-a"), "0".repeat(64));

        assertEquals(prepared.operation().operationKey(), repeated.operation().operationKey());
        assertEquals(prepared.fingerprint(), repeated.fingerprint());
        assertNotEquals(prepared.requestFingerprint(), changedPrompt.requestFingerprint());
        assertNotEquals(prepared.requestFingerprint(), changedPath.requestFingerprint());
        assertEquals(prepared.requestFingerprint(), changedTime.requestFingerprint());
        String receipt = RECEIPT_CODEC.encode(prepared.operation().receipt());
        assertFalse(receipt.contains("continue the goal"));
        assertFalse(receipt.contains("secret-key-a"));
    }

    /** 原 admission 回执必须匹配完整有序 MESSAGE 批次，防止重试吞掉已绑定消息或借同键改正文。 */
    @Test
    void internalMailboxAdmissionReplayRequiresTheOriginalStableBatch() {
        AgentIdentity identity = new AgentIdentity("tenant_a", "thread_a", "turn_mailbox", "user_a");
        List<AgentInputCommand.Enqueue> originalBatch = List.of(
                queuedMessage("message_a", "operation_a", "first"),
                queuedMessage("message_b", "operation_b", "second"));
        AgentInternalRequest original = internal(identity, "continue the goal", List.of(), 7,
                Instant.parse("2026-09-27T01:02:03Z")).withInitialQueuedInputs(originalBatch);
        AgentInternalRequest exactRetry = internal(identity, "continue the goal", List.of(), 7,
                Instant.parse("2026-09-27T01:02:04Z")).withInitialQueuedInputs(List.copyOf(originalBatch));
        AgentInternalRequest reordered = internal(identity, "continue the goal", List.of(), 7,
                Instant.parse("2026-09-27T01:02:04Z")).withInitialQueuedInputs(
                List.of(originalBatch.get(1), originalBatch.getFirst()));
        AgentInternalRequest changedBody = internal(identity, "continue the goal", List.of(), 7,
                Instant.parse("2026-09-27T01:02:04Z")).withInitialQueuedInputs(List.of(
                queuedMessage("message_a", "operation_a", "changed"), originalBatch.get(1)));
        AgentRequestFingerprint.PreparedInternalRequest prepared = AgentRequestFingerprint.prepareInternal(original, model("secret-key-a"), "0".repeat(64));
        AgentStore.OperationIdentity operation = prepared.operation();
        AgentStore.OperationReceipt receipt = new AgentStore.OperationReceipt(
                new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), identity.userId()),
                identity.turnId(),
                operation.operationKey(), operation.fingerprint(), operation.operationType(), 1, 1, 0, 0,
                operation.receipt(), Instant.parse("2026-09-27T01:02:03Z"));

        AgentRequestFingerprint.requireSameInternalRequest(receipt, exactRetry);

        assertEquals(operation.operationKey(), AgentRequestFingerprint.prepareInternal(
                exactRetry, model("secret-key-a"), "0".repeat(64)).operation().operationKey());
        assertThrows(IllegalArgumentException.class,
                () -> AgentRequestFingerprint.requireSameInternalRequest(receipt, reordered));
        assertThrows(IllegalArgumentException.class,
                () -> AgentRequestFingerprint.requireSameInternalRequest(receipt, changedBody));
        assertThrows(IllegalArgumentException.class,
                () -> internal(identity, "continue the goal", List.of(), 7,
                        Instant.parse("2026-09-27T01:02:04Z")).withInitialQueuedInputs(List.of(
                        new AgentInputCommand.Enqueue("steer_op", "steer_input", AgentInputCommand.Kind.STEERING,
                                userContent("not a mailbox message"), Map.of()))));
    }

    /** 普通用户请求的历史路径与 CAS revision 都是可见上下文的一部分。 */
    @Test
    void fingerprintsSubmittedHistoryAndConversationRevision() {
        AgentIdentity identity = new AgentIdentity("tenant_a", "thread_a", "turn_a", "user_a");
        AgentRequest current = AgentRequest.text(identity, "hello", "op_a");
        AgentRequest selected = current.withHistory(AgentExecutionRequest.HistorySelection.explicit(
                List.of("prior_a", "prior_b"), 7));
        AgentRequest reordered = current.withHistory(AgentExecutionRequest.HistorySelection.explicit(
                List.of("prior_b", "prior_a"), 7));
        AgentRequest changedRevision = current.withHistory(AgentExecutionRequest.HistorySelection.explicit(
                List.of("prior_a", "prior_b"), 8));
        AgentRequest changedAcceptedAt = selected.withAcceptedAt(Instant.parse("2026-09-26T01:02:04Z"));

        assertNotEquals(AgentRequestFingerprint.requestFingerprint(current),
                AgentRequestFingerprint.requestFingerprint(selected));
        assertNotEquals(AgentRequestFingerprint.requestFingerprint(selected),
                AgentRequestFingerprint.requestFingerprint(reordered));
        assertNotEquals(AgentRequestFingerprint.requestFingerprint(selected),
                AgentRequestFingerprint.requestFingerprint(changedRevision));
        assertEquals(AgentRequestFingerprint.requestFingerprint(selected),
                AgentRequestFingerprint.requestFingerprint(changedAcceptedAt));
    }

    /** 新会话可用显式空路径绑定 revision，而不丢失来源会话的并发 CAS 约束。 */
    @Test
    void explicitEmptyHistoryRetainsConversationRevision() {
        AgentRequest current = AgentRequest.text(
                new AgentIdentity("tenant_a", "thread_a", "turn_a", "user_a"), "hello", "op_a");
        AgentRequest emptyAtRevision = current.withHistory(
                AgentExecutionRequest.HistorySelection.explicit(List.of(), 7));
        AgentRequest emptyAtNextRevision = current.withHistory(
                AgentExecutionRequest.HistorySelection.explicit(List.of(), 8));

        assertNotEquals(AgentRequestFingerprint.requestFingerprint(current),
                AgentRequestFingerprint.requestFingerprint(emptyAtRevision));
        assertNotEquals(AgentRequestFingerprint.requestFingerprint(emptyAtRevision),
                AgentRequestFingerprint.requestFingerprint(emptyAtNextRevision));
    }

    /** 首轮输入队列是准入语义的一部分，顺序与来源元数据必须参与幂等校验。 */
    @Test
    void fingerprintsInitialQueuedInputsInOrderWithStableMetadataOrdering() {
        AgentIdentity identity = new AgentIdentity("tenant_a", "thread_a", "turn_a", "user_a");
        AgentRequest base = AgentRequest.text(identity, "hello", "op_a");
        AgentInputCommand.Enqueue steering = new AgentInputCommand.Enqueue("op_steering", "input_steering",
                AgentInputCommand.Kind.STEERING, userContent("handle this first"),
                Map.of("ja.clientOperationId", "client_steering", "ja.source", "task-mailbox"));
        Map<String, String> reorderedMetadata = new LinkedHashMap<>();
        reorderedMetadata.put("ja.source", "task-mailbox");
        reorderedMetadata.put("ja.clientOperationId", "client_follow_up");
        AgentInputCommand.Enqueue followUp = new AgentInputCommand.Enqueue("op_follow_up", "input_follow_up",
                AgentInputCommand.Kind.FOLLOW_UP, userContent("handle this after the current response"),
                Map.of("ja.clientOperationId", "client_follow_up", "ja.source", "task-mailbox"));
        AgentInputCommand.Enqueue sameFollowUp = new AgentInputCommand.Enqueue("op_follow_up", "input_follow_up",
                AgentInputCommand.Kind.FOLLOW_UP, userContent("handle this after the current response"),
                reorderedMetadata);
        AgentInputCommand.Enqueue changedFollowUp = new AgentInputCommand.Enqueue("op_follow_up", "input_follow_up",
                AgentInputCommand.Kind.FOLLOW_UP, userContent("different content"), reorderedMetadata);
        AgentInputCommand.Enqueue steeringKind = new AgentInputCommand.Enqueue("op_follow_up", "input_follow_up",
                AgentInputCommand.Kind.STEERING, userContent("handle this after the current response"),
                reorderedMetadata);
        AgentInputCommand.Enqueue messageKind = new AgentInputCommand.Enqueue("op_follow_up", "input_follow_up",
                AgentInputCommand.Kind.MESSAGE, userContent("handle this after the current response"),
                reorderedMetadata);
        AgentInputCommand.Enqueue changedMetadata = new AgentInputCommand.Enqueue("op_follow_up", "input_follow_up",
                AgentInputCommand.Kind.FOLLOW_UP, userContent("handle this after the current response"),
                Map.of("ja.clientOperationId", "client_changed", "ja.source", "task-mailbox"));
        AgentInputCommand.Enqueue changedOperation = new AgentInputCommand.Enqueue("op_changed", "input_follow_up",
                AgentInputCommand.Kind.FOLLOW_UP, userContent("handle this after the current response"),
                reorderedMetadata);
        AgentInputCommand.Enqueue changedInputId = new AgentInputCommand.Enqueue("op_follow_up", "input_changed",
                AgentInputCommand.Kind.FOLLOW_UP, userContent("handle this after the current response"),
                reorderedMetadata);

        AgentRequest submitted = base.withInitialQueuedInputs(List.of(steering, followUp));
        AgentRequest same = base.withInitialQueuedInputs(List.of(steering, sameFollowUp));
        AgentRequest reversed = base.withInitialQueuedInputs(List.of(followUp, steering));
        AgentRequest changedBody = base.withInitialQueuedInputs(List.of(steering, changedFollowUp));
        AgentRequest changedKind = base.withInitialQueuedInputs(List.of(steering, steeringKind));
        AgentRequest changedMessageKind = base.withInitialQueuedInputs(List.of(steering, messageKind));
        AgentRequest changedSourceMetadata = base.withInitialQueuedInputs(List.of(steering, changedMetadata));
        AgentRequest changedOperationKey = base.withInitialQueuedInputs(List.of(steering, changedOperation));
        AgentRequest changedQueuedId = base.withInitialQueuedInputs(List.of(steering, changedInputId));

        assertEquals(AgentRequestFingerprint.requestFingerprint(submitted),
                AgentRequestFingerprint.requestFingerprint(same));
        assertNotEquals(AgentRequestFingerprint.requestFingerprint(submitted),
                AgentRequestFingerprint.requestFingerprint(reversed));
        assertNotEquals(AgentRequestFingerprint.requestFingerprint(submitted),
                AgentRequestFingerprint.requestFingerprint(changedBody));
        assertNotEquals(AgentRequestFingerprint.requestFingerprint(submitted),
                AgentRequestFingerprint.requestFingerprint(changedKind));
        assertNotEquals(AgentRequestFingerprint.requestFingerprint(submitted),
                AgentRequestFingerprint.requestFingerprint(changedMessageKind));
        assertNotEquals(AgentRequestFingerprint.requestFingerprint(submitted),
                AgentRequestFingerprint.requestFingerprint(changedSourceMetadata));
        assertNotEquals(AgentRequestFingerprint.requestFingerprint(submitted),
                AgentRequestFingerprint.requestFingerprint(changedOperationKey));
        assertNotEquals(AgentRequestFingerprint.requestFingerprint(submitted),
                AgentRequestFingerprint.requestFingerprint(changedQueuedId));
    }

    /** 冻结父上下文的来源、高水位、尾部原始序号与摘要 provenance 都参与准入幂等身份。 */
    @Test
    void fingerprintsInitialContextSeedAndIndependentSummaryProvenance() {
        AgentRequest base = AgentRequest.text(
                new AgentIdentity("tenant_a", "thread_child", "turn_child", "user_a"), "hello", "op_a");
        AgentRequest request = base.withInitialContextSeed(seed("turn_parent", 1_005, 1_001,
                "frozen parent tail", 1_000));
        AgentRequest equal = base.withInitialContextSeed(seed("turn_parent", 1_005, 1_001,
                "frozen parent tail", 1_000));
        AgentRequest changedSource = base.withInitialContextSeed(seed("turn_another_parent", 1_005, 1_001,
                "frozen parent tail", 1_000));
        AgentRequest changedWatermark = base.withInitialContextSeed(seed("turn_parent", 1_006, 1_001,
                "frozen parent tail", 1_000));
        AgentRequest changedTailOrdinal = base.withInitialContextSeed(seed("turn_parent", 1_005, 1_002,
                "frozen parent tail", 1_000));
        AgentRequest changedTail = base.withInitialContextSeed(seed("turn_parent", 1_005, 1_001,
                "changed parent tail", 1_000));
        AgentRequest changedSummaryProvenance = base.withInitialContextSeed(seed("turn_parent", 1_005, 1_001,
                "frozen parent tail", 999));

        String fingerprint = AgentRequestFingerprint.requestFingerprint(request);
        assertEquals(fingerprint, AgentRequestFingerprint.requestFingerprint(equal));
        assertNotEquals(fingerprint, AgentRequestFingerprint.requestFingerprint(changedSource));
        assertNotEquals(fingerprint, AgentRequestFingerprint.requestFingerprint(changedWatermark));
        assertNotEquals(fingerprint, AgentRequestFingerprint.requestFingerprint(changedTailOrdinal));
        assertNotEquals(fingerprint, AgentRequestFingerprint.requestFingerprint(changedTail));
        assertNotEquals(fingerprint, AgentRequestFingerprint.requestFingerprint(changedSummaryProvenance));
    }

    /** 内部续跑也必须把冻结父上下文纳入身份，防止同一操作键换用不同背景。 */
    @Test
    void fingerprintsInitialContextSeedForInternalRequests() {
        AgentIdentity identity = new AgentIdentity("tenant_a", "thread_child", "turn_child", "user_a");
        AgentInternalRequest base = internal(identity, "continue", List.of(), 7,
                Instant.parse("2026-09-26T01:02:03Z"));
        AgentInternalRequest seeded = base.withInitialContextSeed(
                seed("turn_parent", 1_005, 1_001, "frozen parent tail", 1_000));
        AgentInternalRequest changed = base.withInitialContextSeed(
                seed("turn_parent", 1_005, 1_001, "different frozen tail", 1_000));

        assertNotEquals(AgentRequestFingerprint.internalRequestFingerprint(base),
                AgentRequestFingerprint.internalRequestFingerprint(seeded));
        assertNotEquals(AgentRequestFingerprint.internalRequestFingerprint(seeded),
                AgentRequestFingerprint.internalRequestFingerprint(changed));
    }

    /** 构造父消息 ordinal 高于压缩摘要边界的合法 Seed，覆盖来源水位与 fact ordinal 分离。 */
    private static InitialContextSeed seed(String sourceTurnId, long sourceOrdinal, long messageOrdinal,
            String messageText, long factOrdinal) {
        SummaryDocument summary = new SummaryDocument(List.of(new SummaryDocument.Fact("parent fact", factOrdinal)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        return new InitialContextSeed(new InitialContextSeed.SourceIdentity(
                "tenant_a", "parent_thread", sourceTurnId), sourceOrdinal,
                List.of(ContextMessage.text("seed_message", sourceTurnId, messageOrdinal,
                        ContextMessage.Role.USER, messageText, 8)), Optional.of(summary));
    }

    /** 测试队列正文走完整 UserContent 编码，不依赖模型可见文本投影。 */
    private static UserContent userContent(String text) {
        return new UserContent(List.of(new TextContent(text)));
    }

    /** 队列指纹覆盖来源身份、稳定 command key 与正文，不能只哈希展示文本。 */
    private static AgentInputCommand.Enqueue queuedMessage(String inputId, String operationKey, String text) {
        return new AgentInputCommand.Enqueue(operationKey, inputId, AgentInputCommand.Kind.MESSAGE,
                userContent(text), Map.of("ja.mailbox.messageId", inputId,
                        "ja.mailbox.senderThreadId", "thread_sender"));
    }

    /** 按实际应用 DTO 建立隐藏请求，确保 facade 和 loop 对同一历史路径使用同一类型。 */
    private static AgentInternalRequest internal(AgentIdentity identity, String text,
            List<String> history, long revision, Instant acceptedAt) {
        return new AgentInternalRequest(identity, new ContextInstruction("instruction_a", text), "internal_a",
                Optional.empty(), Map.of(), AgentExecutionRequest.HistorySelection.explicit(history, revision),
                acceptedAt);
    }

    /** 配置身份固定后，重试不会静默选择不同的 Provider 行为。 */
    private static ModelPort.ModelConfiguration model(String key) {
        return new ModelPort.ModelConfiguration("provider_a", "model_a", "cfg_generation_a",
                ModelPort.Api.OPENAI_RESPONSES, "gpt-test", URI.create("https://model.example/v1"), key,
                Duration.ofSeconds(5), Duration.ofMinutes(1), Set.of(ModelPort.InputModality.TEXT),
                new ModelPort.GenerationOptions(0.2, 0.9, 4096, "medium"));
    }
}
