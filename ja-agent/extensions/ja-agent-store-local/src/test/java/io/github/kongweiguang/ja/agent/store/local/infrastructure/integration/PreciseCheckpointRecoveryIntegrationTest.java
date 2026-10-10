// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.store.local.infrastructure.integration;
import io.github.kongweiguang.ja.agent.bootstrap.AgentEngineBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentAnswer;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionOutcome;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationKey;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationSnapshot;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationRecordPage;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ExecutionSnapshot;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.RecordPage;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.StoredRecord;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentRequest;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.CheckpointUsage;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.context.SummaryContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.infrastructure.json.JsonValueCodecFactory;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.ModelEventSink;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.model.SummaryModel;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentRecordIds;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;
import io.github.kongweiguang.ja.agent.application.context.checkpoint.StoreBackedCheckpointStore;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.agent.store.local.infrastructure.SQLiteAgentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.net.URI;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用真实 SQLite 验证模型请求的摘要身份跨更新和重启保持精确。 */
final class PreciseCheckpointRecoveryIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TENANT = "tenant_checkpoint";
    private static final String SESSION = "thread_checkpoint";
    @TempDir
    Path directory;

    /** 新摘要出现后，旧工具批次仍用原摘要恢复，并可在旧分支继续压缩而不重放工具。 */
    @Test
    void restoresTheCheckpointUsedByTheToolRequestAfterANewerCheckpointIsCommitted() throws Exception {
        Scenario scenario = startScenario("precise-success.sqlite");
        String checkpointB;
        try (AgentEngine first = engine(scenario.store(), scenario.model(), scenario.executions())) {
            AgentExecutionOutcome.Completed seed = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    first.submit(AgentRequest.text(identity("execution-seed"), "SEED_" + "s".repeat(12_000),
                            "submit-seed"))
                            .completion().toCompletableFuture().get(30, TimeUnit.SECONDS));
            assertEquals(TurnState.COMPLETED, seed.result().state());
            String checkpointA = latestCheckpointId(scenario.store(), identity("execution-seed"));
            assertTrue(checkpointSummary(scenario.store(), identity("execution-seed"), checkpointA)
                    .contains("CHECKPOINT_A"));

            AgentExecutionOutcome.Suspended waiting = assertInstanceOf(AgentExecutionOutcome.Suspended.class,
                    first.submit(AgentRequest.text(scenario.original(), "ORIGINAL_SMALL_INPUT",
                            "submit-original"))
                            .completion().toCompletableFuture().get(30, TimeUnit.SECONDS));
            assertTrue(waiting.interactionId().isPresent());
            assertEquals(0, scenario.executions().get());
            assertEquals(checkpointA, capabilityCheckpointId(scenario.store(), scenario.original()));

            AgentIdentity newer = identity("execution-newer");
            AgentRequest newerRequest = AgentRequest.text(newer, "NEWER_" + "b".repeat(12_000), "submit-newer")
                    .withHistory(AgentExecutionRequest.HistorySelection.explicit(
                            List.of("execution-seed", newer.turnId()),
                            threadRevision(scenario.store(), scenario.original())));
            AgentExecutionOutcome.Completed newerResult = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    first.submit(newerRequest)
                            .completion().toCompletableFuture().get(30, TimeUnit.SECONDS));
            assertEquals(TurnState.COMPLETED, newerResult.result().state());
            checkpointB = latestCheckpointId(scenario.store(), scenario.original());
            assertNotEquals(checkpointA, checkpointB);
            assertTrue(checkpointSummary(scenario.store(), scenario.original(), checkpointB).contains("CHECKPOINT_B"));
            assertEquals(checkpointA, new StoreBackedCheckpointStore(scenario.store(), new StorePayloadCodec(JSON))
                    .readCheckpoint(scenario.store().loadConversation(execution(scenario.original()).conversation())
                            .orElseThrow(), checkpointA).checkpointId());

            first.answer(scenario.original(), approved(waiting.interactionId().orElseThrow(), "approve-original"))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        try (AgentEngine recovered = engine(scenario.store(), scenario.model(), scenario.executions())) {
            AgentExecutionOutcome.Completed result = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    recovered.resume(scenario.original()).completion().toCompletableFuture()
                            .get(30, TimeUnit.SECONDS));
            assertEquals(TurnState.COMPLETED, result.result().state(), result.result() + ", requests="
                    + scenario.model().requests.get() + ", toolExecutions=" + scenario.executions().get());
            assertEquals(1, scenario.executions().get());
            assertEquals(5, scenario.model().requests.get());
            assertTrue(scenario.model().resumedPromptUsedCheckpointA.get());
            assertFalse(scenario.model().resumedPromptUsedCheckpointB.get());
            String checkpointC = latestCheckpointId(scenario.store(), scenario.original());
            assertNotEquals(checkpointB, checkpointC);
            assertEquals(3, scenario.model().summaries.get());
            assertTrue(scenario.model().checkpointCSummaryUsedA.get());
            assertFalse(scenario.model().checkpointCSummaryUsedB.get());
            assertTrue(checkpointSummary(scenario.store(), scenario.original(), checkpointC).contains("CHECKPOINT_A"));
            assertFalse(checkpointSummary(scenario.store(), scenario.original(), checkpointC).contains("CHECKPOINT_B"));

            AgentExecutionOutcome.Completed duplicate = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    recovered.resume(scenario.original()).completion().toCompletableFuture()
                            .get(30, TimeUnit.SECONDS));
            assertEquals(result.result(), duplicate.result());
            assertEquals(1, scenario.executions().get());
            assertEquals(5, scenario.model().requests.get());
        }
    }

    /** 显式选择旧历史分支时，最新但不可见的 sibling checkpoint 不能替换所选分支摘要。 */
    @Test
    void freshExplicitBranchDoesNotAdoptCheckpointFromHiddenSibling() throws Exception {
        Scenario scenario = startScenario("precise-branch.sqlite", true);
        try (AgentEngine engine = engine(scenario.store(), scenario.model(), scenario.executions())) {
            AgentExecutionOutcome.Completed seed = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    engine.submit(AgentRequest.text(identity("execution-seed"), "SEED_" + "s".repeat(12_000),
                            "branch-seed")).completion().toCompletableFuture().get(30, TimeUnit.SECONDS));
            assertEquals(TurnState.COMPLETED, seed.result().state());
            assertTrue(checkpointSummary(scenario.store(), identity("execution-seed"),
                    latestCheckpointId(scenario.store(), identity("execution-seed"))).contains("CHECKPOINT_A"));

            AgentIdentity sibling = identity("execution-sibling");
            AgentRequest siblingRequest = AgentRequest.text(sibling, "SIBLING_" + "b".repeat(12_000), "branch-sibling")
                    .withHistory(AgentExecutionRequest.HistorySelection.explicit(
                            List.of("execution-seed", sibling.turnId()),
                            threadRevision(scenario.store(), sibling)));
            AgentExecutionOutcome.Completed siblingResult = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    engine.submit(siblingRequest).completion().toCompletableFuture().get(30, TimeUnit.SECONDS));
            assertEquals(TurnState.COMPLETED, siblingResult.result().state());
            String checkpointB = latestCheckpointId(scenario.store(), sibling);
            assertTrue(checkpointSummary(scenario.store(), sibling, checkpointB).contains("CHECKPOINT_B"));

            AgentIdentity selected = identity("execution-selected-branch");
            AgentRequest selectedRequest = AgentRequest.text(selected, "SELECTED_SMALL_INPUT", "branch-selected")
                    .withHistory(AgentExecutionRequest.HistorySelection.explicit(
                            List.of("execution-seed", selected.turnId()),
                            threadRevision(scenario.store(), selected)));
            AgentExecutionOutcome.Completed selectedResult = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    engine.submit(selectedRequest).completion().toCompletableFuture().get(30, TimeUnit.SECONDS));
            assertEquals(TurnState.COMPLETED, selectedResult.result().state());
            assertTrue(scenario.model().selectedBranchUsedCheckpointA.get());
            assertFalse(scenario.model().selectedBranchUsedCheckpointB.get());
        }
    }

    /** 删除原摘要后即使 newer 摘要仍在同一 SQLite 会话，也必须失败收尾且绝不派发旧工具。 */
    @Test
    void failsClosedWhenTheReferencedCheckpointIsMissing() throws Exception {
        Scenario scenario = startScenario("precise-missing.sqlite");
        String checkpointA;
        String interactionId;
        try (AgentEngine first = engine(scenario.store(), scenario.model(), scenario.executions())) {
            AgentExecutionOutcome.Completed seed = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    first.submit(AgentRequest.text(identity("execution-seed"), "SEED_" + "s".repeat(12_000),
                            "submit-seed"))
                            .completion().toCompletableFuture().get(30, TimeUnit.SECONDS));
            assertEquals(TurnState.COMPLETED, seed.result().state());
            String checkpointSeed = latestCheckpointId(scenario.store(), identity("execution-seed"));
            assertTrue(checkpointSummary(scenario.store(), identity("execution-seed"), checkpointSeed)
                    .contains("CHECKPOINT_A"));

            AgentExecutionOutcome.Suspended waiting = assertInstanceOf(AgentExecutionOutcome.Suspended.class,
                    first.submit(AgentRequest.text(scenario.original(), "ORIGINAL_SMALL_INPUT",
                            "submit-original"))
                            .completion().toCompletableFuture().get(30, TimeUnit.SECONDS));
            interactionId = waiting.interactionId().orElseThrow();
            checkpointA = capabilityCheckpointId(scenario.store(), scenario.original());
            assertEquals(checkpointSeed, checkpointA);

            AgentIdentity newer = identity("execution-newer");
            AgentRequest newerRequest = AgentRequest.text(newer, "NEWER_" + "b".repeat(12_000), "submit-newer")
                    .withHistory(AgentExecutionRequest.HistorySelection.explicit(
                            List.of("execution-seed", newer.turnId()),
                            threadRevision(scenario.store(), scenario.original())));
            AgentExecutionOutcome newerResult = first.submit(newerRequest)
                    .completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED,
                    assertInstanceOf(AgentExecutionOutcome.Completed.class, newerResult).result().state());
            assertNotEquals(checkpointA, latestCheckpointId(scenario.store(), scenario.original()));
            deleteCheckpoint(scenario.store(), scenario.original(), checkpointA);
            first.answer(scenario.original(), approved(interactionId, "approve-original"))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        try (AgentEngine recovered = engine(scenario.store(), scenario.model(), scenario.executions())) {
            AgentExecutionOutcome.Completed result = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    recovered.resume(scenario.original()).completion().toCompletableFuture()
                            .get(30, TimeUnit.SECONDS));
            assertEquals(TurnState.FAILED, result.result().state());
            assertEquals("INVALID_STATE", result.result().errorCode());
            assertEquals(0, scenario.executions().get());
            assertEquals(3, scenario.model().requests.get());
        }
    }

    /** 显式建立 Core schema 后，父摘要和稀疏高 ordinal 尾部经压缩、重启仍组成同一模型上下文。 */
    @Test
    void preservesHighOrdinalSeedSummaryAndTailAcrossChildCheckpointAndRestart() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + directory.resolve("precise-seed-high-ordinal.sqlite"));
        SQLiteAgentStore.bootstrap(dataSource);
        SQLiteAgentStore store = new SQLiteAgentStore(dataSource);
        AgentIdentity child = identity("execution-seed-child");
        AtomicInteger executions = new AtomicInteger();
        SeedCheckpointModel model = new SeedCheckpointModel();
        InitialContextSeed seed = highOrdinalSeed();
        String childInput = "CHILD_INPUT_" + "x".repeat(16_000);
        String checkpointId;
        String interactionId;

        try (AgentEngine first = seededEngine(store, model, executions)) {
            AgentExecutionOutcome.Suspended suspended = assertInstanceOf(AgentExecutionOutcome.Suspended.class,
                    first.submit(AgentRequest.text(child, childInput, "seed-child-submit")
                            .withInitialContextSeed(seed))
                            .completion().toCompletableFuture().get(30, TimeUnit.SECONDS));
            interactionId = suspended.interactionId().orElseThrow();
            checkpointId = capabilityCheckpointId(store, child);
            CheckpointStore.ContextCheckpoint checkpoint = checkpointRows(store, child).stream()
                    .map(CheckpointRow::checkpoint).filter(value -> value.checkpointId().equals(checkpointId))
                    .findFirst().orElseThrow();
            assertTrue(checkpoint.throughOrdinal() > seed.sourceOrdinal(),
                    "child messages must continue after the parent ordinal watermark");
            assertTrue(checkpoint.summary().toPromptText().contains("PARENT_SUMMARY_FACT"));
            assertTrue(checkpoint.summary().toPromptText().contains("PARENT_TAIL_1000"));
            assertTrue(model.summarySawParentSummary.get());
            assertTrue(model.maxEvictedOrdinal.get() > seed.sourceOrdinal());
            first.answer(child, approved(interactionId, "seed-child-approve"))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        try (AgentEngine recovered = seededEngine(store, model, executions)) {
            AgentExecutionOutcome.Completed completed = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    recovered.resume(child).completion().toCompletableFuture().get(30, TimeUnit.SECONDS));
            assertEquals(TurnState.COMPLETED, completed.result().state());
            assertEquals(checkpointId, capabilityCheckpointId(store, child));
            assertTrue(model.requests.get() >= 2);
            assertTrue(model.everyRequestSawSeedContext.get());
            assertEquals(1, model.summaries.get());
            assertEquals(1, executions.get());
        }
    }

    /** 每场景先显式建立隔离 Core schema，再共享计数器区分执行和恢复重放。 */
    private Scenario startScenario(String filename) {
        return startScenario(filename, false);
    }

    /** 分支场景显式建立隔离 Core schema，只改变响应序列以保留生产恢复路径。 */
    private Scenario startScenario(String filename, boolean branchSelectionOnly) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        Path database = directory.resolve(filename);
        dataSource.setUrl("jdbc:sqlite:" + database);
        SQLiteAgentStore.bootstrap(dataSource);
        AgentIdentity identity = identity("execution-original");
        AtomicInteger executions = new AtomicInteger();
        return new Scenario(identity, new SQLiteAgentStore(dataSource), new CheckpointModel(branchSelectionOnly), executions);
    }

    /** 固定模型、工具和摘要扩展装配，确保检查点由正式上下文编排而非测试直接写入。 */
    private static AgentEngine engine(AgentStore store, CheckpointModel model, AtomicInteger executions) {
        ModelPort.ModelConfiguration configuration = new ModelPort.ModelConfiguration("checkpoint-provider", "checkpoint-model", "cfg_checkpoint",
                ModelPort.Api.OPENAI_RESPONSES, "checkpoint-model", URI.create("https://example.invalid/v1"),
                "test-secret", Duration.ofSeconds(2), Duration.ofSeconds(10), Set.of(ModelPort.InputModality.TEXT),
                ModelPort.GenerationOptions.defaults());
        return new AgentEngineBuilder().store(store).model(configuration)
                .contextBudget(new ContextBudget(6_000, 1_000, true))
                .extensions(registry -> {
                    registry.registerModelProvider("checkpoint-provider", model, binding -> model);
                    registry.registerTool(new CountingTool(executions));
                    registry.registerMiddleware(new ApprovalGate());
                }).build();
    }

    /** 将高 ordinal 父尾与独立来源摘要作为一次准入 seed，固定 child 的持久输入样本。 */
    private static InitialContextSeed highOrdinalSeed() {
        List<ContextMessage> messages = List.of(996L, 997L, 998L, 999L, 1_000L).stream()
                .map(ordinal -> ContextMessage.text("parent-tail-" + ordinal, "parent-turn-" + ordinal,
                        ordinal, ContextMessage.Role.USER, "PARENT_TAIL_" + ordinal, 8))
                .toList();
        SummaryDocument summary = new SummaryDocument(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(new SummaryDocument.Fact("PARENT_SUMMARY_FACT", 1_000)),
                List.of(), List.of(), List.of());
        return new InitialContextSeed(new InitialContextSeed.SourceIdentity(
                TENANT, "parent-conversation", "parent-execution"), 1_000, messages, Optional.of(summary));
    }

    /** 复用纯 Java SQLite engine 装配，避免测试内人为写 checkpoint 或改历史 ordinal。 */
    private static AgentEngine seededEngine(AgentStore store, SeedCheckpointModel model, AtomicInteger executions) {
        ModelPort.ModelConfiguration configuration = new ModelPort.ModelConfiguration("checkpoint-provider", "checkpoint-model", "cfg_checkpoint",
                ModelPort.Api.OPENAI_RESPONSES, "checkpoint-model", URI.create("https://example.invalid/v1"),
                "test-secret", Duration.ofSeconds(2), Duration.ofSeconds(10), Set.of(ModelPort.InputModality.TEXT),
                ModelPort.GenerationOptions.defaults());
        return new AgentEngineBuilder().store(store).model(configuration)
                .contextBudget(new ContextBudget(6_000, 1_000, true))
                .extensions(registry -> {
                    registry.registerModelProvider("checkpoint-provider", model, binding -> model);
                    registry.registerTool(new CountingTool(executions));
                    registry.registerMiddleware(new ApprovalGate());
                }).build();
    }

    /** 以 ProviderPending 同事务写入的 v3 能力事实读取互斥且明确的摘要来源。 */
    private static String capabilityCheckpointId(AgentStore store, AgentIdentity identity) throws Exception {
        AgentStore.ExecutionKey execution = execution(identity);
        ExecutionSnapshot snapshot = store.loadExecution(execution).orElseThrow();
        RecordPage records = store.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(
                execution, 0, snapshot.lastRecordSequence(), 1_000));
        StoredRecord capability = records.records().stream()
                .filter(record -> record.recordType().equals("agent.model.capabilities"))
                .findFirst().orElseThrow();
        assertEquals(3, capability.payloadSchemaVersion());
        JsonNode payload = JSON.readTree(JsonValueCodecFactory.standard().encode(capability.payload()));
        assertEquals(AgentRecordIds.derive(execution, "agent.model.capabilities", payload.path("messageId").asText()),
                capability.recordId());
        assertTrue(payload.has("checkpointId"), "checkpoint reference must be explicit");
        assertTrue(payload.has("seedSummaryRef"), "seed summary reference must be explicit");
        assertTrue(payload.path("seedSummaryRef").isNull(), "checkpoint and seed references are mutually exclusive");
        return payload.path("checkpointId").asText();
    }

    /** 从固定会话记录水位按持久身份解码 checkpoint，不依赖 SQL 行顺序或最新指针。 */
    private static String latestCheckpointId(AgentStore store, AgentIdentity identity) {
        return checkpointRows(store, identity).getLast().checkpoint().checkpointId();
    }

    /** 读取指定摘要正文，验证测试实际生成了互不相同的 A/B checkpoint。 */
    private static String checkpointSummary(AgentStore store, AgentIdentity identity, String checkpointId) {
        return checkpointRows(store, identity).stream().map(row -> row.checkpoint())
                .filter(value -> value.checkpointId().equals(checkpointId)).findFirst().orElseThrow()
                .summary().toPromptText();
    }

    /** 按 Store 会话水位固定分页并使用正式 codec 解码全部历史 checkpoint。 */
    private static List<CheckpointRow> checkpointRows(AgentStore store, AgentIdentity identity) {
        ConversationKey conversationKey = execution(identity).conversation();
        ConversationSnapshot snapshot = store.loadConversation(conversationKey).orElseThrow();
        StorePayloadCodec codec = new StorePayloadCodec(JSON);
        List<CheckpointRow> result = new ArrayList<>();
        long after = 0;
        while (after < snapshot.recordSequence()) {
            ConversationRecordPage page = store.readConversationRecords(new AgentStore.ConversationRecordQuery(conversationKey,
                    after, snapshot.recordSequence(), 256, AgentStore.DEFAULT_PAGE_MAX_BYTES));
            for (AgentStore.ConversationRecord row : page.records()) {
                if (!row.recordType().equals("agent.context.checkpoint")) {
                    continue;
                }
                StoredRecord stored = new StoredRecord(row.recordId(), row.recordType(), row.payloadSchemaVersion(),
                        row.sequence(), row.payload(), row.occurredAt());
                result.add(new CheckpointRow(row.recordId(), codec.decodeCheckpoint(stored)));
            }
            if (page.nextSequence() <= after) {
                throw new AgentStore.StoreIntegrityException("checkpoint test read stalled", null);
            }
            after = page.nextSequence();
        }
        return List.copyOf(result);
    }

    /** 在故障用例中模拟已损坏的历史摘要行，保留更晚 checkpoint 与当前记录水位。 */
    private void deleteCheckpoint(AgentStore store, AgentIdentity identity, String checkpointId) throws Exception {
        CheckpointRow row = checkpointRows(store, identity).stream()
                .filter(value -> value.checkpoint().checkpointId().equals(checkpointId)).findFirst().orElseThrow();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("precise-missing.sqlite"));
             PreparedStatement statement = connection.prepareStatement("""
                 DELETE FROM agent_record \
                 WHERE tenant_id = ? AND thread_id = ? AND record_id = ?\
                 """)) {
            statement.setString(1, TENANT);
            statement.setString(2, SESSION);
            statement.setString(3, row.recordId());
            assertEquals(1, statement.executeUpdate());
        }
    }

    /** 将审批答案绑定到原 wait revision，副作用只能在恢复后派发一次。 */
    private static AgentAnswer approved(String interactionId, String operationKey) {
        return new AgentAnswer(interactionId, operationKey, 0,
                List.of(new InteractionAnswer("question_approval", List.of("option_approve"), null, false)), Map.of());
    }

    /** 用同一业务会话和执行身份构造可跨 Engine 恢复的稳定键。 */
    private static AgentIdentity identity(String turnId) {
        return new AgentIdentity(TENANT, SESSION, turnId, "user_checkpoint");
    }

    /** 统一把 Engine 请求身份映射到真实 Store 租户键，避免测试按字符串手工拼 execution key。 */
    private static AgentStore.ExecutionKey execution(AgentIdentity identity) {
        return new AgentStore.ExecutionKey(new AgentStore.ConversationKey(
                identity.tenantId(), identity.threadId(), identity.userId()), identity.turnId());
    }

    /** 冻结显式历史前读取真实会话 CAS 版本，排除尚未完成的其他工具批次。 */
    private static long threadRevision(AgentStore store, AgentIdentity identity) {
        return store.loadConversation(execution(identity).conversation()).orElseThrow().revision();
    }

    /**
     * 保存成功和缺失记录场景共用的真实执行组件，不共享模型游标或 Store 内存状态。
     *
     * @param original 原始内容
     * @param store 存储端口
     * @param model 模型
     * @param executions 执行列表
     */
    private record Scenario(AgentIdentity original,
                            SQLiteAgentStore store,
                            CheckpointModel model,
                            AtomicInteger executions) { }

    /**
     * 关联 checkpoint Store 行身份和解码后的严格摘要对象。
     *
     * @param recordId 记录标识
     * @param checkpoint 检查点
     */
    private record CheckpointRow(String recordId,
                                 CheckpointStore.ContextCheckpoint checkpoint) { }

    /** 依次覆盖种子摘要、旧工具提案、新摘要和原工具恢复四个模型请求。 */
    private static final class CheckpointModel implements ModelPort, SummaryModel {
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger summaries = new AtomicInteger();
        private final boolean branchSelectionOnly;
        private final AtomicBoolean resumedPromptUsedCheckpointA = new AtomicBoolean();
        private final AtomicBoolean resumedPromptUsedCheckpointB = new AtomicBoolean();
        private final AtomicBoolean selectedBranchUsedCheckpointA = new AtomicBoolean();
        private final AtomicBoolean selectedBranchUsedCheckpointB = new AtomicBoolean();
        private final AtomicBoolean checkpointCSummaryUsedA = new AtomicBoolean();
        private final AtomicBoolean checkpointCSummaryUsedB = new AtomicBoolean();

        /** 固定本次测试的响应路径，真实模型请求与摘要仍经正式 Engine 状态机提交。 */
        private CheckpointModel(boolean branchSelectionOnly) {
            this.branchSelectionOnly = branchSelectionOnly;
        }

        /** 计量用户与工具正文，确保大工具回执确实触发旧分支上的生产压缩路径。 */
        @Override
        public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
            long tokens = 100 + request.prompt().systemPrompt().length() + request.messages().stream()
                    .flatMap(message -> message.content().stream())
                    .mapToLong(content -> switch (content) {
                        case io.github.kongweiguang.ja.agent.domain.model.TextContent text -> text.text().length();
                        case io.github.kongweiguang.ja.agent.domain.model.ToolResultContent tool -> tool.content().length();
                        default -> 0;
                    }).sum();
            return new InputTokenEstimate(tokens, tokens, "d".repeat(64));
        }

        /** 在旧批次恢复后的首次请求注入可控溢出，验证内核只从 A 压缩并重试。 */
        @Override
        public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink,
                CancellationToken cancellation) {
            int round = requests.incrementAndGet();
            if (branchSelectionOnly) {
                if (round == 1) {
                    assertTrue(request.prompt().systemPrompt().contains("CHECKPOINT_A"));
                } else if (round == 2) {
                    assertTrue(request.prompt().systemPrompt().contains("CHECKPOINT_B"));
                }
                else {
                    assertEquals(3, round);
                    boolean hasA = request.prompt().systemPrompt().contains("CHECKPOINT_A");
                    boolean hasB = request.prompt().systemPrompt().contains("CHECKPOINT_B");
                    selectedBranchUsedCheckpointA.set(hasA);
                    selectedBranchUsedCheckpointB.set(hasB);
                }
                sink.onEvent(new TextDelta("分支执行完成")).toCompletableFuture().join();
                return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.STOP, null,
                        new ModelUsage(100, 10, 110)));
            }
            if (round == 1) {
                assertTrue(request.prompt().systemPrompt().contains("CHECKPOINT_A"));
                sink.onEvent(new TextDelta("种子执行完成")).toCompletableFuture().join();
            } else if (round == 2) {
                assertTrue(request.prompt().systemPrompt().contains("CHECKPOINT_A"));
                sink.onEvent(new ToolCallReady("call-precise-checkpoint", "lookup", JsonObject.empty(), 0))
                        .toCompletableFuture().join();
            } else if (round == 3) {
                assertTrue(request.prompt().systemPrompt().contains("CHECKPOINT_B"));
                sink.onEvent(new TextDelta("新执行完成")).toCompletableFuture().join();
            } else if (round == 4) {
                throw new ModelPort.ContextOverflowException(new IllegalStateException("controlled overflow"));
            } else {
                assertEquals(5, round);
                boolean hasA = request.prompt().systemPrompt().contains("CHECKPOINT_A");
                boolean hasB = request.prompt().systemPrompt().contains("CHECKPOINT_B");
                resumedPromptUsedCheckpointA.set(hasA);
                resumedPromptUsedCheckpointB.set(hasB);
                sink.onEvent(new TextDelta("原工具批次已恢复")).toCompletableFuture().join();
            }
            return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.STOP, null,
                    new ModelUsage(100, 10, 110)));
        }

        /** 为摘要 Provider 单独计量，摘要请求不进入普通 Assistant 请求计数。 */
        @Override
        public InputTokenEstimate estimateInputTokens(SummaryPrompt prompt) {
            return new InputTokenEstimate(100, 100, "e".repeat(64));
        }

        /** 每次真实自动压缩生成不同摘要，便于验证旧请求 ID 不会追随 latest。 */
        @Override
        public Result summarize(SummaryPrompt prompt) {
            int summaryNumber = summaries.incrementAndGet();
            if (summaryNumber == 3) {
                String previous = prompt.previousSummary().map(SummaryDocument::toPromptText).orElse("");
                checkpointCSummaryUsedA.set(previous.contains("CHECKPOINT_A"));
                checkpointCSummaryUsedB.set(previous.contains("CHECKPOINT_B"));
            }
            String checkpoint = switch (summaryNumber) {
                case 1 -> "CHECKPOINT_A";
                case 2 -> "CHECKPOINT_B";
                default -> "CHECKPOINT_C";
            };
            SummaryDocument previous = prompt.previousSummary().orElseGet(SummaryDocument::empty);
            List<SummaryDocument.Fact> facts = new ArrayList<>(previous.criticalFacts());
            Set<Long> alreadyCovered = new java.util.HashSet<>();
            previous.allFacts().forEach(fact -> alreadyCovered.add(fact.sourceOrdinal()));
            previous.retirements().forEach(retirement -> alreadyCovered.add(retirement.sourceOrdinal()));
            prompt.evictedMessages().stream().filter(message -> message.role() == SummaryContextMessage.Role.USER)
                    .filter(message -> alreadyCovered.add(message.ordinal()))
                    .map(message -> new SummaryDocument.Fact(checkpoint + "_" + message.ordinal(), message.ordinal()))
                    .forEach(facts::add);
            SummaryDocument summary = new SummaryDocument(previous.goals(), previous.constraints(), previous.completedProgress(),
                    previous.currentProgress(), previous.blockers(), previous.decisions(), previous.nextSteps(), facts,
                    previous.files(), previous.pendingEffects(), previous.retirements());
            return new Result(summary, new CheckpointUsage(100, 10, 110, 0, 0));
        }

    }

    /** 首次工具提案必须经过持久用户等待，测试才能在副作用前建立可恢复断点。 */
    private static final class ApprovalGate implements AgentMiddleware {
        /** 持久命名空间在新 Engine 实例中保持不变。 */
        @Override
        public String id() {
            return "checkpoint-approval-gate";
        }

        /** 显式挂起同一工具调用，证明恢复使用原模型请求的授权与上下文快照。 */
        @Override
        public ExtensionDecision<ToolDecision> beforeTool(ToolContext context) {
            JsonObject payload = JsonObjects.builder().putText("expiresAt", Instant.now().plus(Duration.ofMinutes(10)).toString())
                    .build();
            return ExtensionDecision.unchanged(ToolDecision.waitFor(new WaitRequest("approval", "确认执行查询？", payload)),
                    context.extensionState());
        }
    }

    /**
     * 有一次可观测外部副作用的工具，用计数器证明缺摘要失败或重复恢复都不会再次派发。
     *
     * @param executions 执行列表
     */
    private record CountingTool(AtomicInteger executions) implements AgentTool {
        /** 工具 Schema 明确拒绝额外参数，恢复不依赖测试专用的宽松校验。 */
        @Override
        public ToolSpec spec() {
            return new ToolSpec("lookup", "读取测试业务记录", JsonObjects.builder().putText("type", "object")
                    .putBoolean("additionalProperties", false).build());
        }

        /** 每次工具实际执行都会改变外部可观察计数。 */
        @Override
        public ToolSideEffect sideEffect() {
            return ToolSideEffect.EXTERNAL;
        }

        /** 长 ToolResult 在恢复后触发再次压缩，执行计数仍由正式 loop 结算观察。 */
        @Override
        public CompletionStage<ExecutionResult> execute(Invocation invocation, ExecutionContext context,
                io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken cancellation) {
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(ExecutionResult.of(
                    new ToolResult(ToolOutcome.SUCCEEDED, "TOOL_RESULT_" + "r".repeat(2_500), Optional.empty(), null)));
        }
    }

    /** 验证初始摘要基线及高 ordinal 尾部经真实 compaction、恢复后仍可见。 */
    private static final class SeedCheckpointModel implements ModelPort, SummaryModel {
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger summaries = new AtomicInteger();
        private final AtomicInteger maxEvictedOrdinal = new AtomicInteger();
        private final AtomicBoolean summarySawParentSummary = new AtomicBoolean();
        private final AtomicBoolean everyRequestSawSeedContext = new AtomicBoolean(true);

        /** 使用单一模型对象跨 Engine 重建，准确模拟只恢复持久状态而不保留运行内存。 */
        private SeedCheckpointModel() { }

        /** 依据同一可见 Prompt 估算输入，使大 child 输入确实触发生产压缩策略。 */
        @Override
        public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
            long tokens = 100L + request.prompt().systemPrompt().length() + request.messages().stream()
                    .flatMap(message -> message.content().stream())
                    .mapToLong(content -> content instanceof io.github.kongweiguang.ja.agent.domain.model.TextContent text
                            ? text.text().length() : 0L).sum();
            return new InputTokenEstimate(tokens, tokens, "f".repeat(64));
        }

        /** 首次请求挂起工具，重启恢复后继续完成，且每次都检查摘要和父尾实际可见。 */
        @Override
        public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink,
                CancellationToken cancellation) {
            String visible = request.prompt().systemPrompt() + request.messages();
            everyRequestSawSeedContext.set(everyRequestSawSeedContext.get()
                    && visible.contains("PARENT_SUMMARY_FACT") && visible.contains("PARENT_TAIL_1000"));
            int round = requests.incrementAndGet();
            if (round == 1) {
                sink.onEvent(new ToolCallReady("call-seed-checkpoint", "lookup", JsonObject.empty(), 0))
                        .toCompletableFuture().join();
            } else {
                assertEquals(2, round);
                sink.onEvent(new TextDelta("继承上下文恢复完成")).toCompletableFuture().join();
            }
            return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.STOP, null,
                    new ModelUsage(100, 10, 110)));
        }

        /** 摘要请求按预算固定估算值，避免测试摘要模型替代输入装配与 Token 规划。 */
        @Override
        public InputTokenEstimate estimateInputTokens(SummaryPrompt prompt) {
            return new InputTokenEstimate(100, 100, "a".repeat(64));
        }

        /** 保留 seed 已冻结的摘要证据，并记录实际淘汰的高 ordinal child 消息来源。 */
        @Override
        public Result summarize(SummaryPrompt prompt) {
            summaries.incrementAndGet();
            SummaryDocument previous = prompt.previousSummary().orElseGet(SummaryDocument::empty);
            summarySawParentSummary.set(previous.toPromptText().contains("PARENT_SUMMARY_FACT"));
            List<SummaryDocument.Fact> facts = new ArrayList<>(previous.criticalFacts());
            for (SummaryContextMessage message : prompt.evictedMessages()) {
                maxEvictedOrdinal.accumulateAndGet(Math.toIntExact(message.ordinal()), Math::max);
                if (message.role() != SummaryContextMessage.Role.USER) {
                    continue;
                }
                message.blocks().stream().filter(SummaryContextMessage.Text.class::isInstance)
                        .map(SummaryContextMessage.Text.class::cast)
                        .filter(text -> text.value().startsWith("PARENT_TAIL_"))
                        .map(text -> new SummaryDocument.Fact(text.value(), message.ordinal()))
                        .forEach(facts::add);
                if (message.ordinal() > 1_000) {
                    facts.add(new SummaryDocument.Fact("CHILD_INPUT_PREFIX_" + message.ordinal(),
                            message.ordinal()));
                }
            }
            SummaryDocument document = new SummaryDocument(previous.goals(), previous.constraints(),
                    previous.completedProgress(), previous.currentProgress(), previous.blockers(),
                    previous.decisions(), previous.nextSteps(), facts, previous.files(), previous.pendingEffects(),
                    previous.retirements());
            return new Result(document, new CheckpointUsage(100, 10, 110, 0, 0));
        }
    }
}

