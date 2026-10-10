// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.CheckpointUsage;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.context.RetainedSplit;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.conversation.ContextInstruction;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValues;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.PersistenceTestJson;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationKey;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationRecord;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ExecutionSnapshot;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.RecordWrite;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore.ContextCheckpoint;

/** 用真实记录编解码验证有效历史，防止 UI 审计、旧分支或隐藏续跑产生角色混淆。 */
final class AgentStoreContextMapperTest {
    private static final Instant NOW = Instant.parse("2026-09-26T00:00:00Z");
    private static final AgentStore.ConversationKey KEY = new AgentStore.ConversationKey("tenant", "thread", "user");
    private final ObjectMapper json = new ObjectMapper();
    private final StorePayloadCodec codec = new StorePayloadCodec(json);
    private final AgentContextMapper mapper = new AgentContextMapper(arguments(), codec);

    /** 宿主指定有效分支后，旧回复和后续排队输入都不能进入当前模型请求。 */
    @Test
    void respectsSelectedBranchAndCurrentExecutionBoundary() {
        ExecutionSnapshot first = execution("one", 1, "COMPLETED");
        ExecutionSnapshot current = execution("two", 2, "RUNNING");
        ExecutionSnapshot later = execution("three", 3, "QUEUED");
        AgentLoopPersistence.ConversationHistory history = history(List.of(first, current, later), List.of(
                record(first, 1, codec.encodeUser("r1", "u1", input("old branch"), NOW)),
                record(current, 2, codec.encodeUser("r2", "u2", input("current"), NOW)),
                record(later, 3, codec.encodeUser("r3", "u3", input("future"), NOW))));
        List<ContextMessage> messages = mapper.fromSnapshot(history, Set.of("two", "three"), "two");
        assertEquals(List.of("u2"), messages.stream().map(ContextMessage::messageId).toList());
        assertThrows(IllegalArgumentException.class, () -> mapper.fromSnapshot(history, Set.of("one"), "two"));
        assertThrows(IllegalArgumentException.class, () -> mapper.fromCompleteSnapshot(history, Set.of("unknown")));
    }

    /** 内部指令恢复为模型上下文，但失败审计和用户原始输入仍保持不同持久类型。 */
    @Test
    void keepsInternalInstructionAndIgnoresApplicationAudit() {
        ExecutionSnapshot execution = execution("one", 1, "FAILED");
        RecordWrite instruction = codec.encodeInstruction("r1", new ContextInstruction("i1", "继续已提交的结果"), NOW);
        RecordWrite audit = new AgentStore.RecordWrite("r2", "ja.assistant.partial", 1,
                PersistenceTestJson.object("{\"text\":\"半截回复\"}"), NOW);
        AgentLoopPersistence.ConversationHistory history = history(List.of(execution), List.of(record(execution, 1, instruction), record(execution, 2, audit)));
        List<ContextMessage> messages = mapper.fromCompleteSnapshot(history, Set.of("one"));
        assertEquals(1, messages.size());
        assertEquals(ContextMessage.Role.USER, messages.getFirst().role());
        assertEquals("继续已提交的结果", ((ContextMessage.TextBlock) messages.getFirst().blocks().getFirst()).value());
        assertThrows(RuntimeException.class, () -> codec.decodeUser(stored(instruction, 1)));
    }

    /** 真正失败任务之前已提交的工具调用与结果继续保留，不能因最终失败删掉副作用事实。 */
    @Test
    void preservesToolPairingAcrossFailedExecution() {
        ExecutionSnapshot execution = execution("one", 1, "FAILED");
        RecordWrite call = codec.encode("r1", new ExecutionFacts.AssistantMessage("a1", new ModelMessage(ModelRole.ASSISTANT,
                List.of(new ToolCallContent("call1", "lookup", JsonObject.empty()))), "", null, 1), NOW);
        RecordWrite result = codec.encode("r2", new ExecutionFacts.ToolMessage("t1", new ModelMessage(ModelRole.TOOL,
                List.of(new ToolResultContent("call1", "已完成业务", false)))), NOW);
        AgentLoopPersistence.ConversationHistory history = history(List.of(execution), List.of(record(execution, 1, call), record(execution, 2, result)));
        List<ContextMessage> messages = mapper.fromCompleteSnapshot(history, Set.of("one"));
        assertEquals(2, messages.size());
        assertEquals("lookup", ((ContextMessage.ToolResultBlock) messages.getLast().blocks().getFirst()).name());
        assertThrows(io.github.kongweiguang.ja.agent.application.context.ContextException.class, () -> mapper.fromCompleteSnapshot(
                history(List.of(execution), List.of(record(execution, 1, result))), Set.of("one")));
    }

    /** 压缩必须拒绝仍运行的有效任务，不能把执行中的不完整工具配对冻结进摘要。 */
    @Test
    void rejectsCompactionWhileSelectedExecutionIsActive() {
        ExecutionSnapshot execution = execution("one", 1, "RUNNING");
        assertThrows(io.github.kongweiguang.ja.agent.application.context.ContextException.class,
                () -> mapper.fromCompleteSnapshot(history(List.of(execution), List.of()), Set.of("one")));
    }

    /** 冻结父 checkpoint 的完整保留 split 与尾部，摘要来源 ordinal 不覆盖父消息水位语义。 */
    @Test
    void freezesCheckpointRetainedTailWithIndependentSummaryProvenance() {
        ConversationKey parentKey = new AgentStore.ConversationKey("tenant", "parent", "user");
        ExecutionSnapshot execution = execution(parentKey, "parent", 1, "COMPLETED");
        List<RecordWrite> writes = List.of(
                codec.encodeUser("r1", "parent_user", input("old prompt"), NOW),
                codec.encode("r2", new ExecutionFacts.AssistantMessage("parent_assistant",
                        new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("old prefix"))), "old prefix", null, 1), NOW),
                codec.encodeUser("r3", "parent_tail", input("retained tail"), NOW));
        AgentLoopPersistence.ConversationHistory history = history(parentKey, List.of(execution), records(parentKey, execution, writes));
        SummaryDocument summary = summary("parent summary", 99);
        ContextMessage retained = ContextMessage.text("parent_assistant_suffix", "parent", 2,
                ContextMessage.Role.ASSISTANT, "retained suffix", 5);
        CheckpointStore.ContextCheckpoint checkpoint = new CheckpointStore.ContextCheckpoint(
                "checkpoint_parent", parentKey.threadId(), 2, 2, history.conversation().revision(),
                Optional.of(new RetainedSplit("parent_assistant", retained)), summary, 16,
                "a".repeat(64), "summary_v1", CheckpointUsage.none(), NOW);

        InitialContextSeed seed = mapper.freezeInitialContextSeed(history, Set.of("parent"), "parent",
                new InitialContextSeed.SourceIdentity("tenant", "parent", "parent"), Optional.of(checkpoint))
                .orElseThrow();

        assertEquals(3, seed.sourceOrdinal());
        assertEquals(List.of("parent_assistant_suffix", "parent_tail"),
                seed.messages().stream().map(ContextMessage::messageId).toList());
        assertEquals(List.of(2L, 3L), seed.messages().stream().map(ContextMessage::ordinal).toList());
        assertEquals(Optional.of(summary), seed.summary());
    }

    /** 保持父高水位消息 ordinal 与摘要 provenance 同轴，避免 child 首次压缩丢失尾部。 */
    @Test
    void preservesHighParentOrdinalsAcrossSeedAndChildHistory() {
        ConversationKey parentKey = new AgentStore.ConversationKey("tenant", "parent", "user");
        ExecutionSnapshot parent = execution(parentKey, "turn_parent", 1, "COMPLETED");
        List<AgentStore.RecordWrite> parentWrites = java.util.stream.LongStream.rangeClosed(1, 1_005)
                .mapToObj(ordinal -> codec.encodeUser("parent_record_" + ordinal, "parent_message_" + ordinal,
                        input("parent message " + ordinal), NOW)).toList();
        AgentLoopPersistence.ConversationHistory parentHistory = history(parentKey, List.of(parent), records(parentKey, parent, parentWrites));
        SummaryDocument parentSummary = summary("already covered through 1000", 1_000);
        ContextCheckpoint parentCheckpoint = new CheckpointStore.ContextCheckpoint("parent_checkpoint", parentKey.threadId(),
                1_000, 1_001, parentHistory.conversation().revision(), Optional.empty(), parentSummary, 64,
                "c".repeat(64), "summary_v1", CheckpointUsage.none(), NOW);

        InitialContextSeed seed = mapper.freezeInitialContextSeed(parentHistory, Set.of("turn_parent"),
                "turn_parent", new InitialContextSeed.SourceIdentity("tenant", "parent", "turn_parent"),
                Optional.of(parentCheckpoint)).orElseThrow();

        assertEquals(1_005, seed.sourceOrdinal());
        assertEquals(List.of(1_001L, 1_002L, 1_003L, 1_004L, 1_005L),
                seed.messages().stream().map(ContextMessage::ordinal).toList());
        assertEquals(1_000, seed.summary().orElseThrow().goals().getFirst().sourceOrdinal());

        ConversationKey childKey = new AgentStore.ConversationKey("tenant", "child", "user");
        ExecutionSnapshot child = execution(childKey, "turn_child", 1, "COMPLETED");
        RecordWrite seedRecord = codec.encodeInitialContextSeed("child_seed_record", seed, NOW);
        RecordWrite childUser = codec.encodeUser("child_user_record", "child_user_message", input("continue"), NOW);
        AgentLoopPersistence.ConversationHistory childHistory = history(childKey, List.of(child), List.of(
                record(childKey, child, 1, seedRecord), record(childKey, child, 2, childUser)));

        assertEquals(List.of(1_001L, 1_002L, 1_003L, 1_004L, 1_005L, 1_006L),
                mapper.fromCompleteSnapshot(childHistory, Set.of("turn_child")).stream()
                        .map(ContextMessage::ordinal).toList());
    }

    /** 子任务从活动工具中启动时，只冻结首个未配对 Tool call 之前的完整父消息前缀。 */
    @Test
    void freezesOnlyCompleteToolPrefixWhenParentToolIsStillRunning() {
        ConversationKey parentKey = new AgentStore.ConversationKey("tenant", "parent", "user");
        ExecutionSnapshot execution = execution(parentKey, "parent", 1, "RUNNING");
        List<RecordWrite> writes = List.of(
                codec.encodeUser("r1", "parent_user", input("before tool"), NOW),
                codec.encode("r2", new ExecutionFacts.AssistantMessage("parent_tool_call",
                        new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("starting child"),
                                new ToolCallContent("call_spawn", "spawn_agent", JsonObject.empty()))),
                        "starting child", null, 1), NOW));
        AgentLoopPersistence.ConversationHistory history = history(parentKey, List.of(execution), records(parentKey, execution, writes));

        InitialContextSeed seed = mapper.freezeInitialContextSeed(history, Set.of("parent"), "parent",
                new InitialContextSeed.SourceIdentity("tenant", "parent", "parent"), Optional.empty())
                .orElseThrow();

        assertEquals(2, seed.sourceOrdinal());
        assertEquals(List.of("parent_user"), seed.messages().stream().map(ContextMessage::messageId).toList());
    }

    /** 会话级 seed 不随 reask 的分支选择消失，child checkpoint 提交后只停止重复提供父摘要。 */
    @Test
    void keepsSeedAcrossReaskAndStopsReusingItsSummaryAfterChildCheckpoint() {
        ExecutionSnapshot first = execution("child_first", 1, "COMPLETED");
        ExecutionSnapshot second = execution("child_second", 2, "RUNNING");
        SummaryDocument summary = summary("frozen parent fact", 73);
        InitialContextSeed seed = new InitialContextSeed(
                new InitialContextSeed.SourceIdentity("tenant", "parent", "turn_parent"), 3,
                List.of(ContextMessage.text("seed_user", "turn_parent", 1,
                        ContextMessage.Role.USER, "frozen parent message", 6)), Optional.of(summary));
        RecordWrite seedRecord = codec.encodeInitialContextSeed("r_seed", seed, NOW);
        RecordWrite firstUser = codec.encodeUser("r_first_user", "first_user", input("first child request"), NOW);
        RecordWrite secondUser = codec.encodeUser("r_second_user", "second_user", input("reask request"), NOW);
        List<ConversationRecord> records = List.of(record(first, 1, seedRecord), record(first, 2, firstUser), record(second, 3, secondUser));
        AgentLoopPersistence.ConversationHistory history = history(List.of(first, second), records);
        AgentIdentity child = new AgentIdentity("tenant", "thread", "child_second", "user");

        assertEquals(List.of("seed_user", "second_user"), mapper.fromSnapshot(history, Set.of("child_second"), "child_second")
                .stream().map(ContextMessage::messageId).toList());
        assertEquals(List.of(1L, 4L), mapper.fromSnapshot(history, Set.of("child_second"), "child_second")
                .stream().map(ContextMessage::ordinal).toList());
        assertEquals(Optional.of(seed), mapper.initialContextSeed(history, child));
        assertEquals(Optional.of(summary), mapper.seedSummary(history, child));

        CheckpointStore.ContextCheckpoint checkpoint = new CheckpointStore.ContextCheckpoint(
                "checkpoint_child", KEY.threadId(), 1, 1, history.conversation().revision(), Optional.empty(),
                summary("child checkpoint fact", 1), 8, "b".repeat(64), "summary_v1", CheckpointUsage.none(), NOW);
        RecordWrite checkpointRecord = codec.encodeCheckpoint("r_checkpoint", checkpoint, NOW);
        AgentLoopPersistence.ConversationHistory checkpointed = history(List.of(first, second), List.of(
                records.get(0), records.get(1), records.get(2), record(second, 4, checkpointRecord)));

        assertEquals(Optional.empty(), mapper.seedSummary(checkpointed, child));
        assertEquals(List.of("seed_user", "second_user"), mapper.fromSnapshot(checkpointed,
                Set.of("child_second"), "child_second").stream().map(ContextMessage::messageId).toList());
    }

    /** 测试使用真实 JSON 转换，仅隔离无关的 Provider 网络与数据库。 */
    private JsonValueCodec arguments() {
        return new JsonValueCodec() {
            /** 保持工具参数原生 JSON，不通过字符串拼接伪造调用形状。 */
            @Override public String encode(JsonValue value) { return JacksonJsonValues.toNode(json, value).toString(); }
            /** 损坏输入直接失败，测试不能用默认空对象掩盖恢复错误。 */
            @Override public JsonValue decode(String value) {
                try { return JacksonJsonValues.fromNode(json.readTree(value)); }
                catch (java.io.IOException failure) { throw new IllegalArgumentException(failure); }
            }
        };
    }

    /** 保留会话、执行、记录三层身份，让测试覆盖真实记录形状。 */
    private static AgentStore.ConversationRecord record(AgentStore.ExecutionSnapshot execution, long sequence, AgentStore.RecordWrite write) {
        return record(KEY, execution, sequence, write);
    }

    /** 将 fixture 会话身份随父子历史明确传递，避免错误共享静态 session key。 */
    private static AgentStore.ConversationRecord record(AgentStore.ConversationKey key,
            AgentStore.ExecutionSnapshot execution, long sequence, AgentStore.RecordWrite write) {
        return new AgentStore.ConversationRecord(key, execution.key(), write.recordId(), write.recordType(),
                write.payloadSchemaVersion(), sequence, write.payload(), NOW);
    }

    /** 解码边界只使用记录内容，序列仍来自存储而不由编码器分配。 */
    private static AgentStore.StoredRecord stored(AgentStore.RecordWrite write, long sequence) {
        return new AgentStore.StoredRecord(write.recordId(), write.recordType(), write.payloadSchemaVersion(), sequence, write.payload(), NOW);
    }

    /** 按明确输入顺序构造父历史记录，不依赖时间戳或 stream 扩展猜测序号。 */
    private static List<AgentStore.ConversationRecord> records(AgentStore.ExecutionSnapshot execution,
                                                                  List<AgentStore.RecordWrite> writes) {
        return java.util.stream.IntStream.range(0, writes.size())
                .mapToObj(index -> record(execution, index + 1L, writes.get(index))).toList();
    }

    /** 父会话历史沿用显式 conversation key，记录序号按编码顺序固定。 */
    private static List<AgentStore.ConversationRecord> records(AgentStore.ConversationKey key,
            AgentStore.ExecutionSnapshot execution, List<AgentStore.RecordWrite> writes) {
        return java.util.stream.IntStream.range(0, writes.size())
                .mapToObj(index -> record(key, execution, index + 1L, writes.get(index))).toList();
    }

    /** 任务顺序与终态由测试显式声明，不从时间戳猜测历史归属。 */
    private static AgentStore.ExecutionSnapshot execution(String id, long ordinal, String state) {
        return new AgentStore.ExecutionSnapshot(new AgentStore.ExecutionKey(KEY, id), ordinal, state, 1, 1,
                JsonObject.empty(), 0, NOW, NOW);
    }

    /** 父子会话 fixture 使用独立执行键，避免 seed 身份被共享常量掩盖。 */
    private static AgentStore.ExecutionSnapshot execution(AgentStore.ConversationKey key, String id,
            long admissionSequence, String state) {
        return new AgentStore.ExecutionSnapshot(new AgentStore.ExecutionKey(key, id), admissionSequence,
                state, 1, 1, JsonObject.empty(), 0, NOW, NOW);
    }

    /** 已验证水位视图作为 mapper 输入，存储分页一致性由独立持久协调测试覆盖。 */
    private static AgentLoopPersistence.ConversationHistory history(List<AgentStore.ExecutionSnapshot> executions,
                                                                    List<AgentStore.ConversationRecord> records) {
        return history(KEY, executions, records);
    }

    /** 会话快照保留每个执行已关联的最大记录水位，避免测试伪造不完整恢复视图。 */
    private static AgentLoopPersistence.ConversationHistory history(AgentStore.ConversationKey key,
            List<AgentStore.ExecutionSnapshot> executions, List<AgentStore.ConversationRecord> records) {
        List<AgentStore.ExecutionSnapshot> withWatermarks = executions.stream().map(execution -> {
            long lastRecord = records.stream().filter(record -> record.execution().equals(execution.key()))
                    .mapToLong(AgentStore.ConversationRecord::sequence).max().orElse(0);
            return new AgentStore.ExecutionSnapshot(execution.key(), execution.admissionSequence(), execution.state(),
                    execution.mutationVersion(), execution.cursorSchemaVersion(), execution.cursor(), lastRecord,
                    execution.createdAt(), execution.updatedAt());
        }).toList();
        return new AgentLoopPersistence.ConversationHistory(new AgentStore.ConversationSnapshot(key, 1, 0,
                executions.size(), NOW, NOW, records.size()), withWatermarks, records);
    }

    /** 为断言独立父摘要和 checkpoint 来源序号创建最小有效文档。 */
    private static SummaryDocument summary(String fact, long sourceOrdinal) {
        return new SummaryDocument(List.of(new SummaryDocument.Fact(fact, sourceOrdinal)), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** 使用合法用户块，确保历史过滤测试不依赖无效输入的意外异常。 */
    private static UserContent input(String text) { return new UserContent(List.of(new TextContent(text))); }
}
