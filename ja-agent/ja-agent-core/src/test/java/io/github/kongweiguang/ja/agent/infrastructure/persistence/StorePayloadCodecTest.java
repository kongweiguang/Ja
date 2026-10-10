// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.infrastructure.persistence;
import io.github.kongweiguang.ja.agent.domain.context.ToolProjection;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.conversation.ContextInstruction;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFact;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.tool.ToolExecutionBinding;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolState;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.ReasoningContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.domain.context.CheckpointUsage;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.AssistantMessage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ModelRequestIdentity;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolMessage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolStarted;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.RecordWrite;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.StoredRecord;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore.ContextCheckpoint;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore.ProjectionSnapshot;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec.ContextCompaction;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec.ModelCapabilities;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec.SeedSummaryReference;

/** 验证恢复时精确保留供应商内容，并拒绝含糊、损坏或不受支持的持久化输入。 */
final class StorePayloadCodecTest {
    private final StorePayloadCodec codec = new StorePayloadCodec(new ObjectMapper());
    private static final Instant AT = Instant.parse("2026-09-26T00:00:00Z");

    /** 审批时间损坏必须拒绝恢复，不能把日期解析异常当成可重试的外部故障。 */
    @Test
    void classifiesInvalidApprovalTimestampsAsCorruptStorage() {
        RecordWrite encoded = codec.encode("fact_bad_time",
                new ExecutionFacts.Approval("approval_bad_time", "call_bad_time", null, AT), AT);
        StoredRecord tampered = new AgentStore.StoredRecord(encoded.recordId(), encoded.recordType(),
                encoded.payloadSchemaVersion(), 1,
                PersistenceTestJson.mutate(encoded.payload(), json -> json.replace(AT.toString(), "invalid-time")), AT);

        StorageException failure = assertThrows(StorageException.class, () -> codec.decode(tampered));

        assertEquals(StorageException.Code.INVALID_STATE, failure.code());
    }

    /** 验证迁移往返后，不透明推理、公有文本和原生工具调用仍保持原有顺序。 */
    @Test
    void assistantPreservesNativeReasoningAndToolIntent() {
        ReasoningContent reasoning = new ReasoningContent("provider_test", "model_test", "openai_responses", "model",
                ReasoningContent.endpointFingerprint(URI.create("https://example.invalid/v1")), "reasoning",
                "{\"type\":\"reasoning\",\"encrypted_content\":\"opaque\"}");
        ModelMessage message = new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("先检查"), reasoning,
                new ToolCallContent("call_test", "lookup", JsonObject.empty())));
        AssistantMessage fact = new ExecutionFacts.AssistantMessage("item_test", message, "先检查", "检查数据", 2);
        RecordWrite record = codec.encode("fact_assistant", fact, AT);
        assertEquals("agent.assistant", record.recordType());
        assertEquals(fact, codec.decode(stored(record)));
    }

    /** 验证工具结果消息仍是 TOOL 消息，并与已派发事实分开，恢复历史不会重放动作。 */
    @Test
    void resultMessageAndStartedBoundaryRemainDistinct() {
        ToolMessage result = new ExecutionFacts.ToolMessage("item_result", new ModelMessage(ModelRole.TOOL,
                List.of(new ToolResultContent("call_test", "真实失败", true))));
        ToolStarted started = new ExecutionFacts.ToolStarted("call_test", 0);
        assertEquals(result, codec.decode(stored(codec.encode("fact_result", result, AT))));
        assertEquals(started, codec.decode(stored(codec.encode("fact_started", started, AT))));
    }

    /** 分别验证工具授权、待审批状态、失败结果、恢复证据和未知用量都能独立往返保存。 */
    @Test
    void preservesEveryExecutionBoundaryIncludingUnknownUsage() {
        ToolExecutionBinding binding = new ToolExecutionBinding("lookup", "1".repeat(64), "2".repeat(64), "3".repeat(64), JsonObject.empty());
        ModelRequestIdentity profile = new ExecutionFacts.ModelRequestIdentity("provider_test", "model_test", "openai_responses", "model",
                null, null, 100_000, 8_192, JsonObject.empty());
        List<ExecutionFact> facts = List.of(
                new ExecutionFacts.ToolPrepared("batch_test", "call_test", "lookup", JsonObject.empty(), 0,
                        ToolSideEffect.EXTERNAL, binding, true),
                new ExecutionFacts.ToolStarted("call_test", 0),
                new ExecutionFacts.RecoveryEvidence("call_test", 0, "business.receipt", 1,
                        JsonObject.empty(), "a".repeat(64)),
                new ExecutionFacts.ToolRetryIntent("call_test", 0, 1, "b".repeat(64)),
                new ExecutionFacts.ToolRecoveryVerified("call_test", 0, "c".repeat(64)),
                new ExecutionFacts.ToolResult("call_test", ToolState.FAILED, "failed", true,
                        ""),
                new ExecutionFacts.Approval("appr_test", "call_test", null, AT.plusSeconds(60)),
                new ExecutionFacts.ReasoningSummary("item_reasoning", "先核实", 1),
                new ExecutionFacts.Usage("modelreq_unknown", null, 1, 1,
                        ExecutionFacts.UsagePurpose.ASSISTANT, ExecutionFacts.UsageCertainty.UNKNOWN, profile, 512L),
                new ExecutionFacts.Usage("modelreq_known", new ModelUsage(10, 3, 13, null, 2L,
                        ModelUsage.InputAccounting.UNKNOWN), 1, 2, ExecutionFacts.UsagePurpose.ASSISTANT,
                        ExecutionFacts.UsageCertainty.KNOWN, profile, null));
        for (ExecutionFact fact : facts) {
            assertEquals(fact, codec.decode(stored(codec.encode("fact_test", fact, AT))), fact.getClass().getSimpleName());
        }
    }

    /** 金额等精确参数重启后必须与原始授权逐位一致，不能用 double 近似值重新派发工具。 */
    @Test
    void preservesDecimalArgumentsAcrossIntentAndPreparedFacts() {
        JsonObject arguments = new JsonObject(java.util.Map.of("amount",
                new io.github.kongweiguang.ja.agent.domain.json.JsonNumber(
                        new java.math.BigDecimal("1234567890.000123456789012345678900"))));
        ToolExecutionBinding binding = new ToolExecutionBinding("lookup", "1".repeat(64), "2".repeat(64),
                "3".repeat(64), arguments);
        List<ExecutionFact> facts = List.of(
                new ExecutionFacts.AssistantMessage("item_decimal", new ModelMessage(ModelRole.ASSISTANT,
                        List.of(new ToolCallContent("call_decimal", "lookup", arguments))), "", null, 1),
                new ExecutionFacts.ToolPrepared("batch_decimal", "call_decimal", "lookup", arguments, 0,
                        ToolSideEffect.EXTERNAL, binding, true));
        for (ExecutionFact fact : facts) {
            assertEquals(fact, codec.decode(stored(codec.encode("fact_decimal", fact, AT))));
        }
    }

    /** 验证准入记录保留用户原始身份，并且不能误按助手执行事实解码。 */
    @Test
    void userAdmissionRetainsContentAndIdentity() {
        UserContent input = new UserContent(List.of(new TextContent("中文输入\n第二行")));
        StoredRecord record = stored(codec.encodeUser("fact_input", "item_input", input, AT));
        assertEquals("agent.user.message", record.recordType());
        assertEquals(new io.github.kongweiguang.ja.agent.domain.conversation.UserMessage("item_input", input), codec.decodeUser(record));
        assertThrows(StorageException.class, () -> codec.decode(record));
        StoredRecord queued = stored(codec.encodeQueuedUser("fact_queued", "item_input", input, AT));
        assertEquals("agent.input.message", queued.recordType());
        assertEquals(codec.decodeUser(record), codec.decodeQueuedUser(queued));
        assertThrows(StorageException.class, () -> codec.decodeUser(queued));
        assertThrows(StorageException.class, () -> codec.decodeQueuedUser(record));
    }

    /** Seed 持久化需保留稀疏父 ordinal、opaque reasoning、完整 Tool 配对及独立摘要来源。 */
    @Test
    void initialContextSeedRoundTripsAtOriginalWatermarks() {
        ReasoningContent reasoning = new ReasoningContent("provider_test", "model_test", "openai_responses", "model",
                ReasoningContent.endpointFingerprint(URI.create("https://example.invalid/v1")), "reasoning",
                "{\"type\":\"reasoning\",\"encrypted_content\":\"opaque\"}");
        SummaryDocument summary = new SummaryDocument(List.of(new SummaryDocument.Fact("covered through 1000", 1_000)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        InitialContextSeed seed = new InitialContextSeed(
                new InitialContextSeed.SourceIdentity("tenant_test", "thread_parent", "turn_parent"), 1_005,
                List.of(
                        new ContextMessage("seed_user", "turn_parent", 1_001, ContextMessage.Role.USER,
                                List.of(new ContextMessage.TextBlock("保留尾部"), new ContextMessage.AttachmentBlock("attachment_test")), 8),
                        new ContextMessage("seed_assistant", "turn_parent", 1_003, ContextMessage.Role.ASSISTANT,
                                List.of(new ContextMessage.TextBlock("lookup"), new ContextMessage.ReasoningBlock(reasoning),
                                        new ContextMessage.ToolCallBlock("call_seed", "lookup", "{}")), 12),
                        new ContextMessage("seed_tool", "turn_parent", 1_005, ContextMessage.Role.TOOL,
                                List.of(new ContextMessage.ToolResultBlock("call_seed", "lookup",
                                        ContextMessage.ToolOutput.full("完整工具输出", "artifact://parent/tool", 0, null))), 6)),
                java.util.Optional.of(summary));

        RecordWrite record = codec.encodeInitialContextSeed("fact_seed", seed, AT);
        assertEquals("agent.context.seed", record.recordType());
        assertEquals(1, record.payloadSchemaVersion());
        assertEquals(seed, codec.decodeInitialContextSeed(stored(record)));
        assertThrows(StorageException.class, () -> codec.decodeInitialContextSeed(new AgentStore.StoredRecord(
                "fact_seed_future", "agent.context.seed", 2, 1, record.payload(), AT)));
    }

    /** 验证上下文恢复采用已冻结的摘要边界和工具投影选择，不根据当前状态重新计算。 */
    @Test
    void restoresCheckpointAndProjectionBoundaries() {
        ContextCheckpoint checkpoint = new CheckpointStore.ContextCheckpoint("checkpoint_test", "thr_test", 12, 13, 20,
                java.util.Optional.empty(), SummaryDocument.empty(), 128, "a".repeat(64), "summary_v1",
                new CheckpointUsage(10, 5, 15, 2, 0), AT);
        assertEquals(checkpoint, codec.decodeCheckpoint(stored(codec.encodeCheckpoint("fact_checkpoint", checkpoint, AT))));
        ProjectionSnapshot projection = new CheckpointStore.ProjectionSnapshot("projection_test", "thr_test", 3, 20,
                CheckpointStore.ProjectionReason.CHECKPOINT,
                new CheckpointStore.ProjectionBinding("a".repeat(64), "b".repeat(64)),
                java.util.Map.of("call_test", ToolProjection.HEAD_TAIL));
        assertEquals(projection, codec.decodeProjection(stored(codec.encodeProjection("fact_projection", projection, AT))));
    }

    /** 事务投影只认可 Core 明确支持的 context 类型及版本，避免按前缀跳过未知载荷。 */
    @Test
    void validatesKnownContextRecordsAndRejectsUnknownSchemas() {
        InitialContextSeed seed = new InitialContextSeed(new InitialContextSeed.SourceIdentity(
                "tenant_test", "conversation_test", "turn_parent"), 1,
                List.of(new ContextMessage("seed_context", "turn_parent", 1, ContextMessage.Role.USER,
                        List.of(new ContextMessage.TextBlock("seed")), 1)), Optional.empty());
        ContextCheckpoint checkpoint = new CheckpointStore.ContextCheckpoint("checkpoint_context", "thr_test", 0, 0, 0,
                Optional.empty(), SummaryDocument.empty(), 0, "a".repeat(64), "summary_v1",
                new CheckpointUsage(0, 0, 0, 0, 0), AT);
        ProjectionSnapshot projection = new CheckpointStore.ProjectionSnapshot("projection_context", "thr_test", 1, 0,
                CheckpointStore.ProjectionReason.INITIAL,
                new CheckpointStore.ProjectionBinding("a".repeat(64), "b".repeat(64)), java.util.Map.of());
        ContextCompaction compaction = new ExecutionRecordCodec.ContextCompaction("compaction_context", 0, 12, true);
        AgentStore.RecordWrite compactionRecord = codec.encodeContextCompaction("fact_compaction", compaction, AT);
        List<StoredRecord> records = List.of(
                stored(codec.encodeInitialContextSeed("fact_seed_context", seed, AT)),
                stored(codec.encodeInstruction("fact_instruction", new ContextInstruction("item_instruction", "rules"), AT)),
                stored(codec.encodeCheckpoint("fact_checkpoint_context", checkpoint, AT)),
                stored(codec.encodeProjection("fact_projection_context", projection, AT)),
                stored(compactionRecord));

        records.forEach(codec::validateContextRecord);
        assertEquals(compaction, codec.decodeContextCompaction(stored(compactionRecord)));
        assertThrows(StorageException.class, () -> codec.validateContextRecord(new AgentStore.StoredRecord(
                "fact_context_future", "agent.context.future", 1, 1, JsonObject.empty(), AT)));
        assertThrows(StorageException.class, () -> codec.validateContextRecord(new AgentStore.StoredRecord(
                "fact_compaction_future", ExecutionRecordCodec.CONTEXT_COMPACTION_RECORD_TYPE,
                2, 1, JsonObject.empty(), AT)));
        assertThrows(StorageException.class, () -> codec.validateContextRecord(new AgentStore.StoredRecord(
                "fact_compaction_extra", ExecutionRecordCodec.CONTEXT_COMPACTION_RECORD_TYPE, 1, 1,
                PersistenceTestJson.object("{\"compactionId\":\"cmp\",\"sourceRevision\":0,\"inputTokensBefore\":12,\"active\":true,\"extra\":1}"), AT)));
    }

    /** 模型能力快照经同一 Core codec 编解码，父摘要来源和执行派生身份均须保持一致。 */
    @Test
    void modelCapabilitiesRoundTripAndBindToExecution() {
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(
                new AgentStore.ConversationKey("tenant_test", "conversation_test", "user_test"), "turn_test");
        ModelCapabilities checkpointCapabilities = new ExecutionRecordCodec.ModelCapabilities("item_model_checkpoint",
                List.of("lookup", "read_file"), Optional.of("checkpoint_test"), Optional.empty());
        AgentStore.RecordWrite checkpointRecord = codec.encodeModelCapabilities(execution, checkpointCapabilities, AT);
        assertEquals(checkpointCapabilities, codec.decodeModelCapabilities(stored(checkpointRecord), execution));

        SeedSummaryReference seedReference = new ExecutionRecordCodec.SeedSummaryReference(
                new InitialContextSeed.SourceIdentity("tenant_parent", "conversation_parent", "turn_parent"), 19);
        ModelCapabilities seedCapabilities = new ExecutionRecordCodec.ModelCapabilities("item_model_seed", List.of("lookup"),
                Optional.empty(), Optional.of(seedReference));
        AgentStore.RecordWrite seedRecord = codec.encodeModelCapabilities(execution, seedCapabilities, AT);
        assertEquals(seedCapabilities, codec.decodeModelCapabilities(stored(seedRecord), execution));

        AgentStore.StoredRecord stored = stored(checkpointRecord);
        assertThrows(StorageException.class, () -> codec.decodeModelCapabilities(new AgentStore.StoredRecord(
                stored.recordId(), "agent.model.future", stored.payloadSchemaVersion(), stored.sequence(),
                stored.payload(), AT), execution));
        assertThrows(StorageException.class, () -> codec.decodeModelCapabilities(new AgentStore.StoredRecord(
                stored.recordId(), stored.recordType(), 4, stored.sequence(), stored.payload(), AT), execution));
        assertThrows(StorageException.class, () -> codec.decodeModelCapabilities(new AgentStore.StoredRecord(
                stored.recordId(), stored.recordType(), stored.payloadSchemaVersion(), stored.sequence(),
                PersistenceTestJson.mutate(stored.payload(), json -> json.substring(0, json.length() - 1)
                        + ",\"extra\":true}"), AT), execution));
        assertThrows(StorageException.class, () -> codec.decodeModelCapabilities(new AgentStore.StoredRecord(
                "record_wrong_identity", stored.recordType(), stored.payloadSchemaVersion(), stored.sequence(),
                stored.payload(), AT), execution));
    }

    /** 验证重复键、未知字段、非法类型、未来格式版本等输入均被拒绝且错误不回显敏感内容。 */
    @Test
    void rejectsAmbiguousAndUnsupportedPayloadsWithoutEchoingData() {
        for (String json : List.of("{\"callId\":\"call_test\",\"callId\":\"call_secret\"}",
                "{\"callId\":7}", "{\"callId\":\"call_test\",\"extra\":true}",
                "{\"callId\":\"call_test\"} {}", "{}", "", "null", "[]")) {
            StorageException failure = assertThrows(StorageException.class, () -> codec.decode("agent.tool.started", 1, json));
            assertFalse(failure.getMessage().contains("secret"));
        }
        assertThrows(StorageException.class, () -> codec.decode("agent.tool.started", 2, "{\"callId\":\"call_test\"}"));
        assertThrows(StorageException.class, () -> codec.decode("agent.future", 1, "{}"));
    }

    /** 验证轮次溢出、角色替换和无效消息身份都不会变成可接受的恢复记录。 */
    @Test
    void rejectsOverflowAndRoleSubstitution() {
        assertThrows(StorageException.class, () -> codec.decode("agent.reasoning_summary", 1,
                "{\"messageId\":\"item_test\",\"text\":\"x\",\"modelRound\":999999999999}"));
        StoredRecord record = new AgentStore.StoredRecord("fact_input", "agent.user.message", 1, 1,
                PersistenceTestJson.object("{\"messageId\":\"item_test\",\"role\":\"TOOL\",\"blocks\":\"[]\"}"), AT);
        assertThrows(StorageException.class, () -> codec.decodeUser(record));
        assertThrows(IllegalArgumentException.class, () -> codec.encodeUser("fact_input", " ",
                new UserContent(List.of(new TextContent("input"))), AT));
    }

    /** 为编解码回读补上存储分配的顺序，验证该顺序与稳定事实身份相互独立。 */
    private static AgentStore.StoredRecord stored(AgentStore.RecordWrite record) {
        return new AgentStore.StoredRecord(record.recordId(), record.recordType(), record.payloadSchemaVersion(),
                1, record.payload(), record.occurredAt());
    }

}
