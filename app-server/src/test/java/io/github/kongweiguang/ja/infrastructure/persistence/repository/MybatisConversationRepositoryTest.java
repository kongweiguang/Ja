// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.conversation.domain.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.SubagentPolicy;
import io.github.kongweiguang.ja.conversation.domain.approval.ApprovalDecision;

import io.github.kongweiguang.ja.conversation.domain.model.ToolResultContent;

import io.github.kongweiguang.ja.conversation.domain.model.ToolCallContent;

import io.github.kongweiguang.ja.conversation.domain.model.TextContent;

import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;

import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;

import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.model.ReasoningContent;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolState;

import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;

import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.AgentMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.database.DatabaseLeaseProbe;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.CheckpointMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.HistoryMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SchemaMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TurnExecutionStateCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.recovery.StartupRecoveryService;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.execution;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.binding;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.profile;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.usageFact;

import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointStore;
import io.github.kongweiguang.ja.conversation.application.context.ContextMessage;
import io.github.kongweiguang.ja.conversation.application.context.ContextPolicy;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryDocument;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.InputQueue;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 真实临时 SQLite 覆盖 V1 schema、事务原子性、恢复、CAS 与完整 blocks。 */
final class MybatisConversationRepositoryTest extends PersistenceTestSupport {
    /** Thread 创建只读取当时的全局策略；设置源变化与仓储重开都不能改写旧会话快照。 */
    @Test
    void freezesSubagentPolicyPerThreadAndAcrossRepositoryRestart() throws Exception {
        AtomicReference<SubagentPolicy> current = new AtomicReference<>(
                new SubagentPolicy(false, "provider_child", "model_child", "high"));
        try (TestDatabase database = database("thread-subagent-snapshot")) {
            MybatisConversationRepository first = database.agentStore(current::get);
            database.history(first).register(new Workspace.Registration("ws_policy",
                    database.path().getParent(), "policy", Workspace.Trust.TRUSTED, START));
            first.createThread(new ConversationRepository.ThreadDefinition(
                    "thr_policy_old", "ws_policy", "old", preferences("provider_1", "model_1"), START));

            current.set(SubagentPolicy.defaultPolicy());
            first.createThread(new ConversationRepository.ThreadDefinition(
                    "thr_policy_new", "ws_policy", "new", preferences("provider_1", "model_1"),
                    START.plusSeconds(1)));

            assertPolicy(database, "thr_policy_old", false, "provider_child", "model_child", "high");
            assertPolicy(database, "thr_policy_new", true, null, null, null);
        }

        try (TestDatabase reopened = database("thread-subagent-snapshot")) {
            assertPolicy(reopened, "thr_policy_old", false, "provider_child", "model_child", "high");
            assertPolicy(reopened, "thr_policy_new", true, null, null, null);
        }
    }

    /** 直接读取 SQLite 快照行，避免通过可变全局设置伪造重启后的断言。 */
    private static void assertPolicy(TestDatabase database, String threadId, boolean enabled,
                                     String providerId, String modelId, String reasoningLevel) throws Exception {
        try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
            PersistenceRecords.SubagentPolicyRow row = PersistenceMappers.open(session)
                    .subagentPolicies().select(threadId);
            assertNotNull(row);
            assertEquals(enabled, row.enabled());
            assertEquals(providerId, row.providerId());
            assertEquals(modelId, row.modelId());
            assertEquals(reasoningLevel, row.reasoningLevel());
        }
    }
    /**
     * CRUD 只能推进队列 revision；提升按点击先后移到普通消息之前，重复提升保持幂等，
     * 而编辑/删除必须以条目 revision 拒绝过期操作。
     */
    @Test
    void mutatesAuthoritativeQueueWithIndependentRevisionsAndItemCas() throws Exception {
        try (TestDatabase database = database("input-queue-crud")) {
            MybatisConversationRepository store = initialized(database);
            admit(store);

            ConversationRepository.QueueMutation first = store.enqueueInput(pending(
                    "input_first", ConversationRepository.InputKind.FOLLOW_UP, "first", START.plusSeconds(1)));
            ConversationRepository.QueueMutation second = store.enqueueInput(pending(
                    "input_second", ConversationRepository.InputKind.FOLLOW_UP, "second", START.plusSeconds(2)));
            assertEquals(1, first.inputQueue().revision());
            assertEquals(2, second.inputQueue().revision());
            assertEquals(0, store.findTurn("thr_1", "turn_1").orElseThrow().turnMutationVersion());

            ConversationRepository.QueueMutation prioritized = store.prioritizeInput(
                    "thr_1", "turn_1", "input_second", 1, START.plusSeconds(3));
            assertEquals(3, prioritized.inputQueue().revision());
            assertEquals(List.of("input_second", "input_first"), prioritized.inputQueue().items().stream()
                    .map(InputQueue.QueuedInput::inputId).toList());
            assertEquals(InputQueue.Kind.STEERING, prioritized.inputQueue().items().getFirst().kind());
            assertEquals(2, prioritized.inputQueue().items().getFirst().inputRevision());

            ConversationRepository.QueueMutation repeated = store.prioritizeInput(
                    "thr_1", "turn_1", "input_second", 1, START.plusSeconds(4));
            assertFalse(repeated.changed());
            assertEquals(3, repeated.inputQueue().revision());

            ConversationRepository.QueueMutation edited = store.updateInput(
                    "thr_1", "turn_1", "input_first", 1, content("first edited"), START.plusSeconds(5));
            assertEquals(4, edited.inputQueue().revision());
            InputQueue.QueuedInput editedItem = edited.inputQueue().items().get(1);
            assertEquals("first edited", editedItem.content().text());
            assertEquals(2, editedItem.inputRevision());

            ConversationRepository.InputQueueException stale = assertThrows(
                    ConversationRepository.InputQueueException.class,
                    () -> store.updateInput("thr_1", "turn_1", "input_first", 1,
                            content("stale"), START.plusSeconds(6)));
            assertEquals(ConversationRepository.InputQueueFailure.CONFLICT, stale.failure());

            ConversationRepository.QueueMutation deleted = store.deleteInput(
                    "thr_1", "turn_1", "input_first", 2, START.plusSeconds(7));
            assertEquals(5, deleted.inputQueue().revision());
            assertEquals(List.of("input_second"), deleted.inputQueue().items().stream()
                    .map(InputQueue.QueuedInput::inputId).toList());
            assertEquals(0, store.findTurn("thr_1", "turn_1").orElseThrow().turnMutationVersion());
        }
    }

    /**
     * 队列数量与 UTF-8 总量是两个独立上限；多字节正文必须按真实字节拒绝，失败事务不能推进 revision。
     */
    @Test
    void enforcesQueuedInputCountAndUtf8ByteBudgets() throws Exception {
        try (TestDatabase database = database("input-queue-budget")) {
            MybatisConversationRepository store = initialized(database);
            admit(store);
            String ascii = "a".repeat(65_536);
            for (int index = 0; index < 7; index++) {
                store.enqueueInput(pending("input_ascii_" + index,
                        ConversationRepository.InputKind.FOLLOW_UP, ascii, START.plusSeconds(index + 1L)));
            }

            ConversationRepository.InputQueueException bytes = assertThrows(
                    ConversationRepository.InputQueueException.class,
                    () -> store.enqueueInput(pending("input_multibyte",
                            ConversationRepository.InputKind.FOLLOW_UP, "驾".repeat(65_536),
                            START.plusSeconds(8))));
            assertEquals(ConversationRepository.InputQueueFailure.CAPACITY, bytes.failure());

            ConversationRepository.QueueMutation eighth = store.enqueueInput(pending(
                    "input_eighth", ConversationRepository.InputKind.FOLLOW_UP, "fits", START.plusSeconds(9)));
            assertEquals(8, eighth.inputQueue().items().size());
            assertEquals(8, eighth.inputQueue().revision());
            ConversationRepository.InputQueueException count = assertThrows(
                    ConversationRepository.InputQueueException.class,
                    () -> store.enqueueInput(pending("input_ninth",
                            ConversationRepository.InputKind.FOLLOW_UP, "overflow", START.plusSeconds(10))));
            assertEquals(ConversationRepository.InputQueueFailure.CAPACITY, count.failure());
            assertEquals(8, database.history(store).readThread("thr_1", null, 20).orElseThrow()
                    .inputQueue().revision());
        }
    }

    /**
     * STOP 空队列检查必须在同一事务关闭接收门；关闭后新入队稳定失败，随后终态提交不得再次推进队列版本。
     */
    @Test
    void closesEmptyInputGateBeforeTerminalCommit() throws Exception {
        try (TestDatabase database = database("input-queue-stop-gate")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            ConversationRepository.CommitReceipt running = store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(),
                    admission.turnMutationVersion(), START.plusSeconds(1)));

            assertTrue(store.commitWithNextInput(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(), running.turnMutationVersion(),
                    START.plusSeconds(2)), null).isEmpty());
            ConversationRepository.InputQueueException closed = assertThrows(
                    ConversationRepository.InputQueueException.class,
                    () -> store.enqueueInput(pending("input_late",
                            ConversationRepository.InputKind.FOLLOW_UP, "late", START.plusSeconds(3))));
            assertEquals(ConversationRepository.InputQueueFailure.NOT_ACCEPTING, closed.failure());

            store.commitTerminal(new ConversationRepository.TerminalCommit(
                    "thr_1", "turn_1", TurnState.COMPLETED, "done", null, null,
                    "item_final_queue", new ModelMessage(ModelRole.ASSISTANT,
                    List.of(new TextContent("done"))), List.of(), running.turnMutationVersion(),
                    START.plusSeconds(4)));
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                PersistenceRecords.TurnRow turn = session.getMapper(AgentMapper.class)
                        .selectTurn(new PersistenceRecords.TurnKey("thr_1", "turn_1"));
                assertFalse(turn.acceptingInputs());
                assertEquals(1, turn.inputQueueRevision());
            }
        }
    }

    /** 同批随机 ID 不得打乱“摘要、进展、工具序号”；逐条分页必须与整页一致且不漏不重。 */
    @Test
    void ordersSameCommitProgressBeforeToolsAcrossPages() throws Exception {
        try (TestDatabase database = database("snapshot-semantic-order")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT, List.of(
                    new TextContent("先读取两个文件"),
                    new ToolCallContent("call_z", "read_file", textArguments("path", "README.md")),
                    new ToolCallContent("call_a", "read_file", textArguments("path", "AGENTS.md"))));
            store.commit(commitRequest("thr_1", "turn_1", TurnState.RUNNING, List.of(
                    new ConversationRepository.AssistantFact("item_zz_progress", assistant,
                            "先读取两个文件", "先确认范围", 1),
                    new ConversationRepository.ToolPreparedFact("call_z", "read_file",
                            textArguments("path", "README.md"), 0, ToolSideEffect.READ_ONLY,
                            presentation(ToolPresentation.Status.PENDING), binding("batch_fixture", "call_z", "read_file")),
                    new ConversationRepository.ToolPreparedFact("call_a", "read_file",
                            textArguments("path", "AGENTS.md"), 1, ToolSideEffect.READ_ONLY,
                            presentation(ToolPresentation.Status.PENDING), binding("batch_fixture", "call_a", "read_file"))),
                    admission.turnMutationVersion(), START.plusSeconds(1)));
            var history = database.history(store);
            List<ThreadSnapshot.Item> items = history.readThread("thr_1", null, 20).orElseThrow().items();
            assertEquals(List.of("REASONING_SUMMARY", "ASSISTANT_PROGRESS", "call_z", "call_a"),
                    items.stream().filter(item -> !(item instanceof ThreadSnapshot.UserInputItem))
                            .map(item -> item instanceof ThreadSnapshot.TextItem text
                                    ? text.kind().name() : ((ThreadSnapshot.ToolItem) item).callId()).toList());
            java.util.ArrayList<String> paged = new java.util.ArrayList<>();
            String cursor = null;
            for (int page = 0; page < 10; page++) {
                ThreadSnapshot snapshot = history.readThread("thr_1", cursor, 1).orElseThrow();
                paged.addAll(snapshot.items().stream().map(ThreadSnapshot.Item::itemId).toList());
                cursor = snapshot.nextCursor();
                if (cursor == null) break;
            }
            assertNull(cursor);
            assertEquals(items.stream().map(ThreadSnapshot.Item::itemId).toList(), paged);
            assertEquals(paged.size(), new java.util.HashSet<>(paged).size());
        }
    }

    /** 成功终态把 Provider 公开摘要与最终回复一次落库，且摘要不重复创建 Assistant message。 */
    @Test
    void persistsSuccessfulTerminalReasoningSummaryWithoutDuplicateMessage() throws Exception {
        try (TestDatabase database = database("terminal-reasoning-summary")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            ConversationRepository.CommitReceipt running = store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(),
                    admission.turnMutationVersion(), START.plusSeconds(1)));

            store.commitTerminal(new ConversationRepository.TerminalCommit(
                    "thr_1", "turn_1", TurnState.COMPLETED, "done", null, null,
                    "item_final_summary", new ModelMessage(ModelRole.ASSISTANT,
                    List.of(new TextContent("done"))),
                    List.of(new ConversationRepository.ReasoningSummaryFact(
                            "item_final_summary", "Checked the requested files", 1)),
                    running.turnMutationVersion(), START.plusSeconds(2)));

            ThreadSnapshot history = database.history(store).readThread("thr_1", null, 20).orElseThrow();
            assertEquals(2, store.readThread("thr_1").orElseThrow().messages().size());
            List<ThreadSnapshot.TextItem> textItems = history.items().stream()
                    .filter(ThreadSnapshot.TextItem.class::isInstance)
                    .map(ThreadSnapshot.TextItem.class::cast).toList();
            assertEquals(2, textItems.size());
            assertEquals(1, textItems.stream().filter(item -> item.kind()
                    == ThreadSnapshot.TextKind.FINAL_ANSWER).count());
            ThreadSnapshot.TextItem reasoning = textItems.stream().filter(item -> item.kind()
                    == ThreadSnapshot.TextKind.REASONING_SUMMARY).findFirst().orElseThrow();
            assertEquals("Checked the requested files", reasoning.text());
            assertEquals(1, reasoning.modelRound());
        }
    }

    /** 原生 reasoning 不是只存在于 Codec：真实 SQLite 提交、关闭、重开后仍须保留完整 opaque block。 */
    @Test
    void persistsNativeReasoningAcrossRepositoryRestart() throws Exception {
        String databaseName = "native-reasoning-restart";
        ReasoningContent reasoning = new ReasoningContent(
                "provider_1", "model_1", "openai_responses", "test-model",
                ReasoningContent.endpointFingerprint(java.net.URI.create("https://api.example/v1")),
                "reasoning", "{\"type\":\"reasoning\",\"encrypted_content\":\"opaque\"}");
        ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT,
                List.of(new TextContent("visible"), reasoning, new TextContent("answer")));

        try (TestDatabase database = database(databaseName)) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            ConversationRepository.CommitReceipt running = store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(),
                    admission.turnMutationVersion(), START.plusSeconds(1)));
            store.commitTerminal(new ConversationRepository.TerminalCommit(
                    "thr_1", "turn_1", TurnState.COMPLETED, "visibleanswer", null, null,
                    "item_native_reasoning", assistant, List.of(), running.turnMutationVersion(),
                    START.plusSeconds(2)));
        }

        try (TestDatabase reopened = database(databaseName)) {
            ModelMessage restored = reopened.agentStore().readThread("thr_1").orElseThrow().messages().stream()
                    .filter(message -> message.messageId().equals("item_native_reasoning"))
                    .findFirst().orElseThrow().message();
            assertEquals(assistantBlocks(assistant), assistantBlocks(restored));
            ReasoningContent restoredReasoning = assertInstanceOf(ReasoningContent.class,
                    restored.content().get(1));
            assertEquals(reasoning, restoredReasoning);
        }
    }

    /** 只比较块的有序类型和值，避免重启测试依赖 record 的内部实现细节。 */
    private static List<String> assistantBlocks(ModelMessage message) {
        return message.content().stream().map(block -> switch (block) {
            case TextContent text -> "text:" + text.text();
            case ReasoningContent reasoning -> "reasoning:" + reasoning.nativeJson();
            default -> block.getClass().getSimpleName();
        }).toList();
    }


    /** 失败和取消终态保留已公开 reasoning 摘要，但不创建最终 Assistant 消息。 */
    @Test
    void persistsReasoningSummaryForNonSuccessfulTerminal() throws Exception {
        for (TurnState target : List.of(TurnState.FAILED, TurnState.CANCELLED)) {
            try (TestDatabase database = database("terminal-reasoning-non-success-" + target.name().toLowerCase())) {
                MybatisConversationRepository store = initialized(database);
                ConversationRepository.AdmissionReceipt admission = admit(store);
                ConversationRepository.CommitReceipt running = store.commit(commitRequest(
                        "thr_1", "turn_1", TurnState.RUNNING, List.of(),
                        admission.turnMutationVersion(), START.plusSeconds(1)));

                store.commitTerminal(new ConversationRepository.TerminalCommit(
                        "thr_1", "turn_1", target, "terminal", "TEST", "terminal",
                        null, null,
                        List.of(new ConversationRepository.ReasoningSummaryFact(
                                "item_reasoning_non_success", "already displayed", 1)),
                        running.turnMutationVersion(), START.plusSeconds(2)));
                List<ThreadSnapshot.Item> items = database.history(store)
                        .readThread("thr_1", null, 20).orElseThrow().items();
                List<ThreadSnapshot.TextItem> textItems = items.stream()
                        .filter(ThreadSnapshot.TextItem.class::isInstance)
                        .map(ThreadSnapshot.TextItem.class::cast)
                        .toList();
                assertEquals(1, textItems.stream().filter(item -> item.kind()
                        == ThreadSnapshot.TextKind.REASONING_SUMMARY).count());
                assertEquals(0, textItems.stream().filter(item -> item.kind()
                        == ThreadSnapshot.TextKind.FINAL_ANSWER).count());
                ThreadSnapshot.TextItem reasoning = textItems.stream().filter(item -> item.kind()
                        == ThreadSnapshot.TextKind.REASONING_SUMMARY).findFirst().orElseThrow();
                assertEquals(ThreadSnapshot.TextKind.REASONING_SUMMARY, reasoning.kind());
                assertEquals("already displayed", reasoning.text());
            }
        }
    }

    /** 空白 reasoning 不应被终态投影当作摘要写入，也不应阻断成功终态。 */
    @Test
    void rejectsBlankReasoningSummaryFact() {
        assertThrows(IllegalArgumentException.class, () -> new ConversationRepository.ReasoningSummaryFact(
                "item_blank", " \t\r\n", 1));
    }

    /** 失败或取消终态必须在同一事务关闭接收门并解决全部剩余输入，不留下可恢复的幽灵队列。 */
    @Test
    void terminalCommitCancelsRemainingQueueAtomically() throws Exception {
        try (TestDatabase database = database("input-queue-terminal-cleanup")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            ConversationRepository.CommitReceipt running = store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(),
                    admission.turnMutationVersion(), START.plusSeconds(1)));
            store.enqueueInput(pending("input_remaining", ConversationRepository.InputKind.FOLLOW_UP,
                    "remaining", START.plusSeconds(2)));

            store.commitTerminal(new ConversationRepository.TerminalCommit(
                    "thr_1", "turn_1", TurnState.FAILED, "failed", "TEST_FAILURE", "failed",
                    null, null, List.of(), running.turnMutationVersion(), START.plusSeconds(3)));

            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                PersistenceRecords.TurnRow turn = session.getMapper(AgentMapper.class)
                        .selectTurn(new PersistenceRecords.TurnKey("thr_1", "turn_1"));
                assertFalse(turn.acceptingInputs());
                assertEquals(2, turn.inputQueueRevision());
                assertTrue(session.getMapper(AgentMapper.class).selectPendingInputs("turn_1").isEmpty());
            }
            assertNull(database.history(store).readThread("thr_1", null, 20).orElseThrow().inputQueue());
        }
    }

    /**
     * 排队输入跨关闭重开后仍按 Steering 优先、同类 FIFO 消费；每次 Assistant settlement 与 USER
     * 写入必须共享事务且保持 ordinal 相邻，空队列不得留下半次模型提交。
     */
    @Test
    void commitsSettlementBeforePrioritizedQueuedInputAcrossRestart() throws Exception {
        try (TestDatabase database = database("queued-input-restart-order")) {
            MybatisConversationRepository store = initialized(database);
            admit(store);
            Instant collidedAt = START.plusSeconds(1);
            store.enqueueInput(pending("input_follow_z_first", ConversationRepository.InputKind.FOLLOW_UP,
                    "follow-1", collidedAt));
            store.enqueueInput(pending("input_steer_z_first", ConversationRepository.InputKind.STEERING,
                    "steer-1", collidedAt));
            store.enqueueInput(pending("input_steer_a_second", ConversationRepository.InputKind.STEERING,
                    "steer-2", collidedAt));
            store.enqueueInput(pending("input_follow_a_second", ConversationRepository.InputKind.FOLLOW_UP,
                    "follow-2", collidedAt));
            store.close();

            MybatisConversationRepository restored = database.agentStore();
            long mutationVersion = 0;
            List<String> consumedIds = new java.util.ArrayList<>();
            for (int index = 1; index <= 4; index++) {
                ConversationRepository.AssistantFact settlement = new ConversationRepository.AssistantFact(
                        "item_settlement_" + index,
                        new ModelMessage(ModelRole.ASSISTANT,
                                List.of(new TextContent("assistant-" + index))),
                        "assistant-" + index, null, index);
                ConversationRepository.InputConsumption consumption = restored.commitWithNextInput(
                        commitRequest("thr_1", "turn_1", TurnState.RUNNING, List.of(settlement),
                                mutationVersion, START.plusSeconds(10L + index), execution("cfg_1")),
                        ConversationRepository.InputSelection.from(
                                restored.peekInput("turn_1", null).orElseThrow()))
                        .orElseThrow();
                consumedIds.add(consumption.input().inputId());
                mutationVersion = consumption.turnMutationVersion();
            }

            assertEquals(List.of("input_steer_z_first", "input_steer_a_second",
                    "input_follow_z_first", "input_follow_a_second"), consumedIds);
            ConversationRepository.ThreadSnapshot snapshot = restored.readThread("thr_1").orElseThrow();
            assertEquals(List.of("hello", "assistant-1", "steer-1", "assistant-2", "steer-2",
                            "assistant-3", "follow-1", "assistant-4", "follow-2"),
                    snapshot.messages().stream().map(message -> text(message.message())).toList());
            ThreadSnapshot history = database.history(restored).readThread("thr_1", null, 100).orElseThrow();
            assertEquals(List.of("assistant-1", "assistant-2", "assistant-3", "assistant-4"),
                    history.items().stream().filter(ThreadSnapshot.TextItem.class::isInstance)
                            .map(ThreadSnapshot.TextItem.class::cast)
                            .filter(item -> item.kind() == ThreadSnapshot.TextKind.FINAL_ANSWER)
                            .map(ThreadSnapshot.TextItem::text).toList());
            assertTrue(history.items().stream().filter(ThreadSnapshot.TextItem.class::isInstance)
                    .map(ThreadSnapshot.TextItem.class::cast)
                    .noneMatch(item -> item.kind() == ThreadSnapshot.TextKind.ASSISTANT_PROGRESS));
            long revisionBeforeEmpty = snapshot.revision();
            int messagesBeforeEmpty = snapshot.messages().size();
            assertTrue(restored.commitWithNextInput(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING,
                    List.of(new ConversationRepository.AssistantFact(
                            "item_uncommitted", new ModelMessage(ModelRole.ASSISTANT,
                            List.of(new TextContent("must-not-commit"))), "must-not-commit", null, 5)),
                    mutationVersion, START.plusSeconds(20), execution("cfg_1")), null).isEmpty());
            ConversationRepository.ThreadSnapshot afterEmpty = restored.readThread("thr_1").orElseThrow();
            assertEquals(revisionBeforeEmpty, afterEmpty.revision());
            assertEquals(messagesBeforeEmpty, afterEmpty.messages().size());
            restored.close();
        }
    }

    /**
     * 队首校验失败发生在消费事务之前，因此已完成的 STOP Assistant 必须独立结算为 Final，
     * 同时保留队首和接收门，避免刷新后回复折叠或用户失去修复入口。
     */
    @Test
    void commitsAssistantSettlementWithoutConsumingOrClosingQueuedInput() throws Exception {
        try (TestDatabase database = database("queued-input-rejected-settlement")) {
            MybatisConversationRepository store = initialized(database);
            admit(store);
            store.enqueueInput(pending("input_unavailable", ConversationRepository.InputKind.FOLLOW_UP,
                    "repair me", START.plusSeconds(1)));
            ConversationRepository.AssistantFact settlement = new ConversationRepository.AssistantFact(
                    "item_rejected_settlement",
                    new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("completed before rejection"))),
                    "completed before rejection", null, 1);

            ConversationRepository.CommitReceipt receipt = store.commitAssistantSettlement(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(settlement), 0,
                    START.plusSeconds(2), execution("cfg_1")));

            assertEquals("input_unavailable", store.peekInput("turn_1", null).orElseThrow().inputId());
            ThreadSnapshot history = database.history(store).readThread("thr_1", null, 100).orElseThrow();
            assertEquals(List.of("completed before rejection"), history.items().stream()
                    .filter(ThreadSnapshot.TextItem.class::isInstance)
                    .map(ThreadSnapshot.TextItem.class::cast)
                    .filter(item -> item.kind() == ThreadSnapshot.TextKind.FINAL_ANSWER)
                    .map(ThreadSnapshot.TextItem::text).toList());
            assertTrue(history.items().stream().filter(ThreadSnapshot.TextItem.class::isInstance)
                    .map(ThreadSnapshot.TextItem.class::cast)
                    .noneMatch(item -> item.kind() == ThreadSnapshot.TextKind.ASSISTANT_PROGRESS));
            assertEquals(1, history.inputQueue().items().size());
            assertTrue(history.inputQueue().accepting());
            assertEquals(receipt.threadRevision(), history.thread().revision());
        }
    }

    /** 自动标题落库后 Agent Loop 的下一轮历史读取仍应恢复 AUTO，而不是枚举同名转换失败。 */
    @Test
    void readsConversationSnapshotAfterAutomaticTitleCommit() throws Exception {
        try (TestDatabase database = database("automatic-title-conversation-snapshot")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            MybatisHistoryService history = database.history(store);

            assertTrue(history.writeAutomaticTitle("thr_1", "自动标题", admission.threadRevision()));
            ConversationRepository.ThreadSnapshot snapshot = store.readThread("thr_1").orElseThrow();

            assertEquals("自动标题", snapshot.title());
            assertEquals(io.github.kongweiguang.ja.conversation.domain.ThreadPreferences.TitleSource.AUTO,
                    snapshot.preferences().titleSource());
        }
    }

    /**
     * 临时标题与首轮 admission 必须一起跨重启恢复；重复 Turn identity 的失败事务不能推进
     * revision 或重开标题所有权，后续正常 admission 也只能保留首轮标题。
     */
    @Test
    void provisionalTitleSurvivesRestartAndDuplicateAdmissionDoesNotReopenOwnership() throws Exception {
        try (TestDatabase database = database("provisional-title-restart")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt first = admit(store);
            assertEquals(1, first.threadRevision());
            assertEquals("hello", first.provisionalTitle());
            store.close();

            MybatisConversationRepository restored = database.agentStore();
            ConversationRepository.ThreadSnapshot persisted = restored.readThread("thr_1").orElseThrow();
            assertEquals("hello", persisted.title());
            assertEquals(1, persisted.revision());
            assertEquals(io.github.kongweiguang.ja.conversation.domain.ThreadPreferences.TitleSource.PLACEHOLDER,
                    persisted.preferences().titleSource());
            assertEquals(1, persisted.turns().size());
            assertEquals(1, persisted.messages().size());

            assertThrows(StorageException.class, () -> restored.admit(new ConversationRepository.TurnAdmission(
                    "thr_1", "turn_1",
                    "item_user_duplicate", new ModelMessage(ModelRole.USER,
                    List.of(new TextContent("duplicate"))), List.of(), persisted.revision(),
                    START.plusSeconds(1), execution("cfg_1"))));
            ConversationRepository.ThreadSnapshot afterDuplicate = restored.readThread("thr_1").orElseThrow();
            assertEquals("hello", afterDuplicate.title());
            assertEquals(1, afterDuplicate.revision());
            assertEquals(1, afterDuplicate.turns().size());
            assertEquals(1, afterDuplicate.messages().size());

            ConversationRepository.AdmissionReceipt later = restored.admit(
                    new ConversationRepository.TurnAdmission(
                            "thr_1", "turn_2",
                            "item_user_2", new ModelMessage(ModelRole.USER,
                            List.of(new TextContent("later"))), List.of(), afterDuplicate.revision(),
                            START.plusSeconds(2), execution("cfg_1")));
            assertNull(later.provisionalTitle());
            assertEquals(2, later.threadRevision());
            ConversationRepository.ThreadSnapshot finalSnapshot = restored.readThread("thr_1").orElseThrow();
            assertEquals("hello", finalSnapshot.title());
            assertEquals(2, finalSnapshot.turns().size());
            assertEquals(2, finalSnapshot.messages().size());
            restored.close();
        }
    }

    /** schema 只能包含 fresh 单库表，events/journal/旧双 sequence 均不存在。 */
    @Test
    void createsOnlyFreshV1Tables() throws Exception {
        try (TestDatabase database = database("schema");
             org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
            List<String> tables = session.getMapper(SchemaMapper.class).selectUserTables();
            assertTrue(tables.containsAll(List.of("workspaces", "threads", "turns", "messages", "tools",
                    "approvals", "usage", "context_checkpoints")));
            assertFalse(tables.contains("events"));
            assertFalse(tables.contains("tool_operations"));
        }
    }

    /** 全新 schema 只在 Thread 保存下一轮偏好，Turn 行不得再复制请求环境。 */
    @Test
    void persistsThreadSelectorsWithoutTurnRuntimeColumns() throws Exception {
        try (TestDatabase database = database("final-selector-authority")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            assertEquals(1, admission.threadRevision());
            assertEquals("hello", admission.provisionalTitle());
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                PersistenceRecords.ThreadRow thread = session.getMapper(HistoryMapper.class).selectThread("thr_1");
                PersistenceRecords.TurnRow turn = session.getMapper(AgentMapper.class)
                        .selectTurn(new PersistenceRecords.TurnKey("thr_1", "turn_1"));
                assertEquals("provider_1", thread.providerId());
                assertEquals("model_1", thread.modelId());
                assertEquals("hello", thread.title());
                assertEquals("PLACEHOLDER", thread.titleSource());
                assertEquals(1, thread.revision());
                assertEquals("QUEUED", turn.state());
            }
            ConversationRepository.TurnSnapshot restored = store.findTurn("thr_1", "turn_1").orElseThrow();
            assertEquals(TurnState.QUEUED, restored.state());
        }
    }

    /** 取消 claim 在 SQLite 内推进双版本；重复请求复用同一 receipt 且不穿透取消门。 */
    @Test
    void claimsCancellationIdempotentlyAndBlocksNormalCompletion() throws Exception {
        try (TestDatabase database = database("cancel-claim")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            ConversationRepository.CancellationClaim claim = store.claimCancellation("thr_1", "turn_1",
                    admission.threadRevision(), "user cancelled", START.plusSeconds(1));
            assertTrue(claim.accepted());
            assertEquals(TurnState.QUEUED, claim.status());
            assertEquals(2, claim.threadRevision());
            assertEquals(1, claim.turnMutationVersion());

            // 后继 admission 只推进 Thread revision，不改变原 cancellation receipt；
            // 重试仍必须识别最初的 CAS 输入。
            store.admit(new ConversationRepository.TurnAdmission("thr_1", "turn_2",
                    "item_user_2", new ModelMessage(ModelRole.USER,
                    List.of(new TextContent("later"))), List.of(), claim.threadRevision(),
                    START.plusSeconds(1), execution("cfg_1")));

            ConversationRepository.CancellationClaim retry = store.claimCancellation("thr_1", "turn_1",
                    admission.threadRevision(), "different reason", START.plusSeconds(2));
            assertEquals(claim, retry);
            StorageException staleRetry = assertThrows(StorageException.class,
                    () -> store.claimCancellation("thr_1", "turn_1", claim.threadRevision(),
                            "stale retry", START.plusSeconds(2)));
            assertEquals(StorageException.Code.CAS_CONFLICT, staleRetry.code());
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                PersistenceRecords.TurnRow row = session.getMapper(AgentMapper.class)
                        .selectTurn(new PersistenceRecords.TurnKey("thr_1", "turn_1"));
                assertEquals("2026-08-25T12:00:01Z", row.cancelRequestedAt());
                assertEquals("user cancelled", row.cancelReason());
                assertEquals(admission.threadRevision(), row.cancelExpectedThreadRevision());
                assertEquals(claim.threadRevision(), row.cancelThreadRevision());
                assertEquals(claim.turnMutationVersion(), row.cancelTurnMutationVersion());
            }

assertThrows(StorageException.class, () -> store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(),
                    claim.turnMutationVersion(), START.plusSeconds(3))));
            assertThrows(StorageException.class, () -> store.commitTerminal(new ConversationRepository.TerminalCommit(
                    "thr_1", "turn_1", TurnState.COMPLETED, "done", null, null,
                    null, null, List.of(), claim.turnMutationVersion(), START.plusSeconds(4))));
            StorageException staleTerminal = assertThrows(StorageException.class,
                    () -> store.commitTerminal(new ConversationRepository.TerminalCommit(
                            "thr_1", "turn_1", TurnState.CANCELLED, "", "CANCELLED",
                            "turn cancelled", null, null, List.of(), admission.turnMutationVersion(),
                            START.plusSeconds(5))));
            assertEquals(StorageException.Code.CAS_CONFLICT, staleTerminal.code());
            ConversationRepository.CommitReceipt cancelled = store.commitTerminal(new ConversationRepository.TerminalCommit(
                    "thr_1", "turn_1", TurnState.CANCELLED, "", "CANCELLED",
                    "turn cancelled", null, null, List.of(), claim.turnMutationVersion(),
                    START.plusSeconds(5)));
            assertEquals(4, cancelled.threadRevision());
            assertEquals(2, cancelled.turnMutationVersion());
            assertEquals(TurnState.CANCELLED,
                    store.findTurn("thr_1", "turn_1").orElseThrow().state());
        }
    }

    /**
     * 模型步 Usage 已提交后，取消门只放行完整 TOOL 消息，终态不得重复计量且只能收敛为 CANCELLED。
     */
    @Test
    void commitsOnlyStrictToolBatchAfterCancellationClaim() throws Exception {
        try (TestDatabase database = database("cancel-tool-batch")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            ConversationRepository.CommitReceipt running = store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING,
                    List.of(
                            new ConversationRepository.AssistantFact(
                                    "item_assistant_1",
                                    new ModelMessage(ModelRole.ASSISTANT,
                                             List.of(new ToolCallContent(
                                                     "call_1", "shell", textArguments("command", "echo ok")))),
                                    "", null, 1),
                            usageFact(1, 1, null),
                            usageFact(1, 1, new ModelUsage(1, 1, 2)),
                             new ConversationRepository.ToolPreparedFact(
                                     "call_1", "shell", textArguments("command", "echo ok"), 0,
                                     ToolSideEffect.EXTERNAL, presentation(ToolPresentation.Status.PENDING),
                                     binding("batch_fixture", "call_1", "shell"))),
                    admission.turnMutationVersion(), START.plusSeconds(1)));
            List<ConversationRepository.Fact> batchFacts = List.of(
                    new ConversationRepository.ToolStartedFact("call_1"),
                     new ConversationRepository.ToolResultFact(
                             "call_1", ToolState.CANCELLED, "cancelled after cleanup", true,
                             presentation(ToolPresentation.Status.CANCELLED), ""),
                    new ConversationRepository.ToolResultMessageFact(
                            "item_tool_1", new ModelMessage(ModelRole.TOOL,
                            List.of(new ToolResultContent("call_1", "cancelled after cleanup", true)))));

            ConversationRepository.CancellationToolBatchCommit beforeClaim = cancellationToolBatch(
                    batchFacts, running.turnMutationVersion(), START.plusSeconds(2));
            assertThrows(StorageException.class, () -> store.commitCancellationToolBatch(beforeClaim));

            ConversationRepository.CancellationClaim claim = store.claimCancellation(
                    "thr_1", "turn_1", running.threadRevision(), "user cancelled", START.plusSeconds(2));
            assertThrows(StorageException.class, () -> store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(),
                    claim.turnMutationVersion(), START.plusSeconds(3))));
            assertThrows(IllegalArgumentException.class, () -> cancellationToolBatch(
                    List.of(usageFact(1, 1, new ModelUsage(1, 1, 2))),
                    claim.turnMutationVersion(), START.plusSeconds(3)));
            assertThrows(IllegalArgumentException.class, () -> cancellationToolBatch(
                    List.of(new ConversationRepository.ToolResultFact(
                            "call_1", ToolState.CANCELLED, "cancelled", true,
                            presentation(ToolPresentation.Status.CANCELLED), "")),
                    claim.turnMutationVersion(), START.plusSeconds(3)));
            assertThrows(IllegalArgumentException.class, () -> cancellationToolBatch(
                    List.of(new ConversationRepository.ToolResultFact(
                                    "call_1", ToolState.CANCELLED, "cancelled", true,
                                    presentation(ToolPresentation.Status.CANCELLED), ""),
                            new ConversationRepository.ToolResultFact(
                                    "call_1", ToolState.CANCELLED, "cancelled", true,
                                    presentation(ToolPresentation.Status.CANCELLED), ""),
                            new ConversationRepository.ToolResultMessageFact(
                                    "item_tool_duplicate", new ModelMessage(ModelRole.TOOL,
                                    List.of(new ToolResultContent("call_1", "cancelled", true))))),
                    claim.turnMutationVersion(), START.plusSeconds(3)));
            assertThrows(StorageException.class, () -> store.commitCancellationToolBatch(
                    cancellationToolBatch(batchFacts, running.turnMutationVersion(), START.plusSeconds(3))));

            ConversationRepository.CommitReceipt batch = store.commitCancellationToolBatch(
                    cancellationToolBatch(batchFacts, claim.turnMutationVersion(), START.plusSeconds(3)));
            assertEquals(4, batch.threadRevision());
            assertEquals(3, batch.turnMutationVersion());
            assertEquals(TurnState.RUNNING, store.findTurn("thr_1", "turn_1").orElseThrow().state());
            assertEquals(3, store.readThread("thr_1").orElseThrow().messages().size());
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                PersistenceRecords.ToolRow tool = session.getMapper(AgentMapper.class)
                        .selectTool(new PersistenceRecords.ToolKey("turn_1", "call_1"));
                assertEquals("CANCELLED", tool.state());
                assertEquals(2L, tool.revision());
            }

            assertThrows(StorageException.class, () -> store.commitTerminal(
                    new ConversationRepository.TerminalCommit(
                            "thr_1", "turn_1", TurnState.COMPLETED, "done", null, null,
                            null, null, List.of(), batch.turnMutationVersion(), START.plusSeconds(4))));
            ConversationRepository.CommitReceipt cancelled = store.commitTerminal(
                    new ConversationRepository.TerminalCommit(
                            "thr_1", "turn_1", TurnState.CANCELLED, "", null, null,
                            null, null, List.of(), batch.turnMutationVersion(), START.plusSeconds(4)));
            assertEquals(5, cancelled.threadRevision());
            assertEquals(4, cancelled.turnMutationVersion());
            assertEquals(TurnState.CANCELLED,
                    store.findTurn("thr_1", "turn_1").orElseThrow().state());
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                assertEquals(1, session.getMapper(AgentMapper.class).countUsageForTurn("turn_1"));
            }
        }
    }

    /**
     * 未知 Tool 仍需持久化调用与配对结果，但不能插入伪造 binding；缺失 binding 是执行器拒绝路由的
     * 权威事实，并允许模型在下一轮根据错误自行纠正。
     */
    @Test
    void persistsUnknownToolCallAndResultWithoutBinding() throws Exception {
        try (TestDatabase database = database("unknown-tool-binding")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            ConversationRepository.CommitReceipt prepared = store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING,
                    List.of(new ConversationRepository.ToolPreparedFact(
                            "call_unknown", "missing_tool", JsonObjects.builder().build(), 0,
                            ToolSideEffect.EXTERNAL, presentation(ToolPresentation.Status.PENDING), null)),
                    admission.turnMutationVersion(), START.plusSeconds(1)));

            assertTrue(store.findToolBinding("turn_1", "call_unknown").isEmpty());
            store.commit(commitRequest("thr_1", "turn_1", TurnState.RUNNING,
                    List.of(new ConversationRepository.ToolResultFact(
                                    "call_unknown", ToolState.FAILED,
                                    "Tool 'missing_tool' is unavailable for this call.", true,
                                    presentation(ToolPresentation.Status.ERROR), ""),
                            new ConversationRepository.ToolResultMessageFact(
                                    "item_tool_unknown", new ModelMessage(ModelRole.TOOL,
                                    List.of(new ToolResultContent("call_unknown",
                                            "Tool 'missing_tool' is unavailable for this call.", true))))),
                    prepared.turnMutationVersion(), START.plusSeconds(2)));
        }
    }

    /** stale Thread revision、终态和缺失 Turn 均在 claim 事务入口明确拒绝。 */
    @Test
    void rejectsStaleMissingAndTerminalCancellationClaims() throws Exception {
        try (TestDatabase database = database("cancel-reject")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            ConversationRepository.CommitReceipt running = store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(),
                    admission.turnMutationVersion(), START.plusSeconds(1)));
            StorageException stale = assertThrows(StorageException.class,
                    () -> store.claimCancellation("thr_1", "turn_1", admission.threadRevision(),
                            "stale", START.plusSeconds(2)));
            assertEquals(StorageException.Code.CAS_CONFLICT, stale.code());
            StorageException missing = assertThrows(StorageException.class,
                    () -> store.claimCancellation("thr_1", "turn_missing", running.threadRevision(),
                            "missing", START.plusSeconds(2)));
            assertEquals(StorageException.Code.NOT_FOUND, missing.code());

            ConversationRepository.CommitReceipt completed = store.commitTerminal(new ConversationRepository.TerminalCommit(
                    "thr_1", "turn_1", TurnState.COMPLETED, "done", null, null,
                    null, null, List.of(), running.turnMutationVersion(), START.plusSeconds(3)));
            StorageException terminal = assertThrows(StorageException.class,
                    () -> store.claimCancellation("thr_1", "turn_1", completed.threadRevision(),
                            "late", START.plusSeconds(4)));
            assertEquals(StorageException.Code.CAS_CONFLICT, terminal.code());
        }
    }

    /** 并发 claim/terminal 尝试共享 SQLite writer gate，只允许一个结果发布。 */
    @Test
    void cancellationAndTerminalRaceHasOneDurableWinner() throws Exception {
        try (TestDatabase database = database("cancel-race")) {
            MybatisConversationRepository first = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(first);
            MybatisConversationRepository second = database.agentStore();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<Boolean> claim = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        first.claimCancellation("thr_1", "turn_1", admission.threadRevision(),
                                "race", START.plusSeconds(1));
                        return true;
                    } catch (StorageException expected) {
                        return false;
                    }
                });
                Future<Boolean> terminal = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        second.commitTerminal(new ConversationRepository.TerminalCommit(
                                "thr_1", "turn_1", TurnState.COMPLETED, "done",
                                null, null, null, null, List.of(),
                                admission.turnMutationVersion(), START.plusSeconds(1)));
                        return true;
                    } catch (StorageException expected) {
                        return false;
                    }
                });
                assertTrue(ready.await(2, TimeUnit.SECONDS));
                start.countDown();
                assertTrue(claim.get(5, TimeUnit.SECONDS) ^ terminal.get(5, TimeUnit.SECONDS));
            }
        }
    }

    /** claim 工作后由 transaction owner 回滚，两个版本和 marker 都必须保持不变。 */
    @Test
    void rollsBackCancellationClaimAsOneTransaction() throws Exception {
        try (TestDatabase database = database("cancel-rollback")) {
            MybatisConversationRepository normal = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(normal);
            MybatisUnitOfWork.SessionOwner rollbackOwner =
                    new MybatisUnitOfWork.SessionOwner() {
                /** 故意回滚已执行的工作，验证取消双版本不会出现部分提交。 */
                @Override
                public <T> T execute(org.apache.ibatis.session.SqlSessionFactory sessions,
                                     MybatisUnitOfWork.Work<T> work) {
                    try (org.apache.ibatis.session.SqlSession session = sessions.openSession()) {
                        try {
                            work.apply(PersistenceMappers.open(session));
                            session.rollback();
                        } catch (Throwable failure) {
                            session.rollback();
                        }
                    }
                    throw new StorageException(StorageException.Code.TRANSACTION,
                            "forced cancellation transaction rollback");
                }
            };
            MybatisConversationRepository failing = new MybatisConversationRepository(database.sessions(), database.mapper(),
                    rollbackOwner, SubagentPolicy::defaultPolicy);
            assertThrows(StorageException.class, () -> failing.claimCancellation("thr_1", "turn_1",
                    admission.threadRevision(), "rollback", START.plusSeconds(1)));

            ConversationRepository.TurnSnapshot restored = normal.findTurn("thr_1", "turn_1").orElseThrow();
            assertEquals(admission.threadRevision(), restored.threadRevision());
            assertEquals(admission.turnMutationVersion(), restored.turnMutationVersion());
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                PersistenceRecords.TurnRow row = session.getMapper(AgentMapper.class)
                        .selectTurn(new PersistenceRecords.TurnKey("thr_1", "turn_1"));
                assertFalse(row.cancelRequestedAt() != null);
                assertFalse(row.cancelReason() != null);
            }
        }
    }

    /** AssistantFact 保存完整 Tool call blocks，并按 message ordinal 原样 roundtrip。 */
    @Test
    void roundTripsFullAssistantBlocksAndToolPairing() throws Exception {
        try (TestDatabase database = database("blocks")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT, List.of(
                    new TextContent("checking"),
                    new ToolCallContent("call_1", "read_file", textArguments("path", "README.md"))));
            ConversationRepository.CommitReceipt running = store.commit(commitRequest("thr_1", "turn_1",
                    TurnState.RUNNING, List.of(new ConversationRepository.AssistantFact(
                            "item_a", assistant, "checking", null, 1),
                    new ConversationRepository.ToolPreparedFact(
                             "call_1", "read_file", textArguments("path", "README.md"), 0,
                             ToolSideEffect.READ_ONLY, presentation(ToolPresentation.Status.PENDING),
                             binding("batch_fixture", "call_1", "read_file"))),
                    admission.turnMutationVersion(), START.plusSeconds(1)));
            ModelMessage toolResult = new ModelMessage(ModelRole.TOOL,
                    List.of(new ToolResultContent("call_1", "ok", false)));
            store.commit(commitRequest("thr_1", "turn_1", TurnState.RUNNING,
                    List.of(new ConversationRepository.ToolResultFact("call_1", ToolState.SUCCEEDED, "ok", false,
                                    presentation(ToolPresentation.Status.SUCCESS), ""),
                            new ConversationRepository.ToolResultMessageFact("item_tool_1", toolResult)),
                    running.turnMutationVersion(), START.plusSeconds(2)));

            ConversationRepository.ThreadSnapshot snapshot = store.readThread("thr_1").orElseThrow();
            assertEquals(3, snapshot.messages().size());
            assertEquals(assistant, snapshot.messages().get(1).message());
            assertEquals(2, snapshot.messages().get(1).ordinal());
            assertEquals(toolResult, snapshot.messages().get(2).message());
            assertEquals(3, snapshot.messages().get(2).ordinal());
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                PersistenceRecords.ToolRow tool = session.getMapper(AgentMapper.class)
                        .selectTool(new PersistenceRecords.ToolKey("turn_1", "call_1"));
                assertEquals(0, tool.ordinal());
                assertEquals("SUCCEEDED", tool.state());
                assertEquals(1L, tool.revision());
            }
        }
    }

    /** terminal 内任一事实触发唯一约束时，状态、最终消息和先前事实全部回滚。 */
    @Test
    void rollsBackWholeTerminalCommitOnConstraintFailure() throws Exception {
        try (TestDatabase database = database("rollback")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            ConversationRepository.CommitReceipt running = store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(),
                    admission.turnMutationVersion(), START.plusSeconds(1)));
            ModelUsage usage = new ModelUsage(10, 4, 14);
            ConversationRepository.TerminalCommit terminal = new ConversationRepository.TerminalCommit("thr_1", "turn_1",
                    TurnState.COMPLETED, "done", null, null, "item_final",
                    new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("done"))),
                    List.of(usageFact(1, 1, usage), usageFact(1, 1, usage)),
                    running.turnMutationVersion(), START.plusSeconds(2));
            assertThrows(StorageException.class, () -> store.commitTerminal(terminal));
            ConversationRepository.TurnSnapshot restored = store.findTurn("thr_1", "turn_1").orElseThrow();
            assertEquals(TurnState.RUNNING, restored.state());
            assertEquals(running.threadRevision(), restored.threadRevision());
            assertEquals(running.turnMutationVersion(), restored.turnMutationVersion());
            assertEquals(1, store.readThread("thr_1").orElseThrow().messages().size());
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                assertEquals(0, session.getMapper(AgentMapper.class).countUsageForTurn("turn_1"));
            }
        }
    }

    /** 成功终态拒绝悬空 Tool；失败终态必须在同一事务将其收敛为公开 error。 */
    @Test
    void settlesUnfinishedToolsOnlyForNonSuccessfulTerminalStates() throws Exception {
        try (TestDatabase database = database("terminal-tool-settlement")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            ConversationRepository.CommitReceipt running = store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING,
                    List.of(new ConversationRepository.ToolPreparedFact(
                            "call_pending", "shell", textArguments("command", "echo pending"), 0,
                            ToolSideEffect.EXTERNAL, presentation(ToolPresentation.Status.PENDING),
                            binding("batch_fixture", "call_pending", "shell"))),
                    admission.turnMutationVersion(), START.plusSeconds(1)));

            ConversationRepository.TerminalCommit completed = new ConversationRepository.TerminalCommit(
                    "thr_1", "turn_1", TurnState.COMPLETED, "done", null, null,
                    "item_final", new ModelMessage(ModelRole.ASSISTANT,
                    List.of(new TextContent("done"))), List.of(),
                    running.turnMutationVersion(), START.plusSeconds(2));
            assertThrows(StorageException.class, () -> store.commitTerminal(completed));
            assertEquals(TurnState.RUNNING, store.findTurn("thr_1", "turn_1").orElseThrow().state());

            ConversationRepository.TerminalCommit failed = new ConversationRepository.TerminalCommit(
                "thr_1", "turn_1", TurnState.FAILED, "failed", "MODEL_FAILURE", "provider failed",
                    "item_failure_reply", new ModelMessage(ModelRole.ASSISTANT,
                    List.of(new TextContent("safe failure reply"))), List.of(),
                    running.turnMutationVersion(), START.plusSeconds(3));
            store.commitTerminal(failed);

            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                PersistenceRecords.ToolRow tool = session.getMapper(AgentMapper.class)
                        .selectTool(new PersistenceRecords.ToolKey("turn_1", "call_pending"));
                assertEquals("FAILED", tool.state());
            }
            io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot history =
                    database.history(store).readThread("thr_1", null, 100).orElseThrow();
            io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot.ToolItem tool = history.items().stream()
                    .filter(io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot.ToolItem.class::isInstance)
                    .map(io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot.ToolItem.class::cast)
                    .filter(item -> item.callId().equals("call_pending"))
                    .findFirst().orElseThrow();
            assertEquals(ToolPresentation.Status.ERROR, tool.presentation().status());
            io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot.TextItem failureReply =
                    history.items().stream()
                            .filter(io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot.TextItem.class::isInstance)
                            .map(io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot.TextItem.class::cast)
                            .filter(item -> item.kind()
                                    == io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot.TextKind.FINAL_ANSWER)
                            .findFirst().orElseThrow();
            assertEquals(io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot.TextKind.FINAL_ANSWER,
                    failureReply.kind());
            assertEquals("safe failure reply", failureReply.text());
        }
    }

    /** 历史投影必须保持已持久化的 DSML 普通正文，读取路径不根据文本形状替换内容。 */
    @Test
    void preservesDsmlFinalAnswerInPublicHistoryProjection() throws Exception {
        try (TestDatabase database = database("legacy-dsml-history-projection")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            ConversationRepository.CommitReceipt running = store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(),
                    admission.turnMutationVersion(), START.plusSeconds(1), execution("cfg_1")));
            String legacyText = "<｜｜DSML｜｜tool_calls><｜｜DSML｜｜invoke name=\"shell\">";
            store.commitTerminal(new ConversationRepository.TerminalCommit(
                    "thr_1", "turn_1", TurnState.COMPLETED, legacyText, null, null,
                    "item_legacy_dsml", new ModelMessage(ModelRole.ASSISTANT,
                    List.of(new TextContent(legacyText))), List.of(),
                    running.turnMutationVersion(), START.plusSeconds(2)));

            ConversationRepository.ThreadSnapshot raw = store.readThread("thr_1").orElseThrow();
            assertEquals(legacyText, text(raw.messages().getLast().message()));

            ThreadSnapshot history = database.history(store).readThread("thr_1", null, 100).orElseThrow();
            ThreadSnapshot.TextItem projected = history.items().stream()
                    .filter(ThreadSnapshot.TextItem.class::isInstance)
                    .map(ThreadSnapshot.TextItem.class::cast)
                    .filter(item -> item.kind() == ThreadSnapshot.TextKind.FINAL_ANSWER)
                    .findFirst().orElseThrow();
            assertEquals(legacyText, projected.text());
            assertEquals(TurnState.COMPLETED, raw.turns().getFirst().state());
        }
    }

    /** checkpoint 保存完整结构化 SummaryDocument，并拒绝 stale revision。 */
    @Test
    void comparesAndSetsCompleteCheckpoint() throws Exception {
        try (TestDatabase database = database("checkpoint")) {
            MybatisConversationRepository store = initialized(database);
            admit(store);
            MybatisCheckpointStore checkpoints = database.checkpoints();
            SummaryDocument summary = new SummaryDocument(
                    List.of(new SummaryDocument.Fact("goal", 1)),
                    List.of(new SummaryDocument.Fact("constraint", 1)), List.of(),
                    List.of(new SummaryDocument.Fact("progress", 1)), List.of(),
                    List.of(new SummaryDocument.Fact("decision", 1)),
                    List.of(new SummaryDocument.Fact("next", 1)),
                    List.of(new SummaryDocument.Fact("critical", 1)),
                    List.of(new SummaryDocument.Fact("read", 1), new SummaryDocument.Fact("modified", 1)),
                    List.of(new SummaryDocument.Fact("pending", 1)), List.of());
            ContextMessage retainedMessage = ContextMessage.text("item_user-suffix", "turn_1", 1,
                    ContextMessage.Role.USER, "suffix", 2);
            ContextPolicy.RetainedSplit retainedSplit = new ContextPolicy.RetainedSplit(
                    "item_user", retainedMessage);
            CheckpointStore.ContextCheckpoint checkpoint = new CheckpointStore.ContextCheckpoint(
                    "cp_1", "thr_1", 1, 1, 1, java.util.Optional.of(retainedSplit), summary, 123,
                    "0".repeat(64), ContextCompactionEvent.STRATEGY_VERSION,
                    new io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointUsage(10, 2, 12, 3, 0), START.plusSeconds(3));
            CheckpointStore.CommittedCheckpoint firstReceipt = checkpoints.commit(
                    new CheckpointStore.CommitRequest("thr_1", 1, checkpoint));
            assertTrue(firstReceipt.newlyCommitted());
            assertEquals(2, firstReceipt.threadRevision());
            CheckpointStore.ContextCheckpoint restored = checkpoints.read("thr_1").checkpoint().orElseThrow();
            assertEquals(summary, restored.summary());
            assertEquals(java.util.Optional.of(retainedSplit), restored.retainedSplit());
            CheckpointStore.ContextCheckpoint stale = new CheckpointStore.ContextCheckpoint(
                    "cp_2", "thr_1", 2, 3, 1, java.util.Optional.empty(), summary, 100,
                    "0".repeat(64), ContextCompactionEvent.STRATEGY_VERSION,
                    io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointUsage.none(), START.plusSeconds(4));
            CheckpointStore.CommittedCheckpoint reused = checkpoints.commit(
                    new CheckpointStore.CommitRequest("thr_1", 1, stale));
            assertFalse(reused.newlyCommitted());
            assertEquals("cp_1", reused.checkpoint().checkpointId());
            long revision = store.commit(commitRequest("thr_1", "turn_1",
                    TurnState.RUNNING, List.of(), 0, START.plusSeconds(5))).threadRevision();
            CheckpointStore.ContextCheckpoint second = new CheckpointStore.ContextCheckpoint(
                    "cp_2", "thr_1", 2, 3, revision, java.util.Optional.empty(), summary, 100,
                    "0".repeat(64), ContextCompactionEvent.STRATEGY_VERSION,
                    io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointUsage.none(), START.plusSeconds(6));
            CheckpointStore.CommittedCheckpoint secondReceipt = checkpoints.commit(
                    new CheckpointStore.CommitRequest("thr_1", revision, second));
            assertTrue(secondReceipt.newlyCommitted());
            assertEquals(revision + 1, secondReceipt.threadRevision());
            assertTrue(checkpoints.read("thr_1").checkpoint().orElseThrow().retainedSplit().isEmpty());
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                assertEquals(2, session.getMapper(CheckpointMapper.class).countCheckpoints("thr_1"));
            }
        }
    }

    /** 自动 Summary checkpoint 必须与 READY(ASSISTANT) 和 Turn mutation 在同一 SQLite 事务提交。 */
    @Test
    void commitsAutomaticSummaryCheckpointAndExecutionAtomically() throws Exception {
        try (TestDatabase database = database("checkpoint-turn-operation")) {
            MybatisConversationRepository store = initialized(database);
            admit(store);
            TurnExecutionState.Common common = execution("cfg_1").common();
            TurnExecutionState.SummaryProgress progress = TurnExecutionState.SummaryProgress.candidate(
                    "{}", 1, 1, "4".repeat(64),
                    new TurnExecutionState.KnownUsage(10, 2, 12, 3, 1));
            TurnExecutionState.Ready summarizing = new TurnExecutionState.Ready(
                    common, TurnExecutionState.Next.SUMMARY, progress);
            ConversationRepository.CommitReceipt running = store.commit(new ConversationRepository.CommitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(), 0,
                    START.plusSeconds(1), summarizing));
            CheckpointStore.ContextCheckpoint checkpoint = checkpointFixture(
                    "cp_turn_operation", running.threadRevision(), "automatic-summary");
            TurnExecutionState.Ready completed = new TurnExecutionState.Ready(
                    common, TurnExecutionState.Next.ASSISTANT, null);

            CheckpointStore.CommittedCheckpoint receipt = database.checkpoints().commit(
                    new CheckpointStore.CommitRequest("thr_1", running.threadRevision(), checkpoint,
                            java.util.Optional.of(new CheckpointStore.TurnOperation(
                                    "turn_1", running.turnMutationVersion(), completed))));

            assertTrue(receipt.newlyCommitted());
            assertEquals(running.threadRevision() + 1, receipt.threadRevision());
            assertEquals(running.turnMutationVersion() + 1, receipt.turnMutationVersion());
            ConversationRepository.TurnSnapshot turn = store.findTurn("thr_1", "turn_1").orElseThrow();
            assertEquals(TurnState.RUNNING, turn.state());
            assertEquals(receipt.turnMutationVersion(), turn.turnMutationVersion());
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                PersistenceRecords.TurnExecutionRow row = session.getMapper(AgentMapper.class)
                        .selectTurnExecution("turn_1");
                TurnExecutionState restored = new TurnExecutionStateCodec(database.mapper()).read(row.stateJson());
                assertEquals(completed, restored);
                assertEquals(1, session.getMapper(CheckpointMapper.class).countCheckpoints("thr_1"));
            }
        }
    }

    /** Turn CAS 失败时 checkpoint 插入和 execution 替换必须整体回滚。 */
    @Test
    void rollsBackAutomaticSummaryCheckpointWhenTurnMutationIsStale() throws Exception {
        try (TestDatabase database = database("checkpoint-turn-operation-rollback")) {
            MybatisConversationRepository store = initialized(database);
            admit(store);
            TurnExecutionState.Common common = execution("cfg_1").common();
            TurnExecutionState.SummaryProgress progress = TurnExecutionState.SummaryProgress.candidate(
                    "{}", 1, 1, "5".repeat(64),
                    new TurnExecutionState.KnownUsage(1, 1, 2, 0, 0));
            TurnExecutionState.Ready summarizing = new TurnExecutionState.Ready(
                    common, TurnExecutionState.Next.SUMMARY, progress);
            ConversationRepository.CommitReceipt running = store.commit(new ConversationRepository.CommitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(), 0,
                    START.plusSeconds(1), summarizing));
            TurnExecutionState.Ready completed = new TurnExecutionState.Ready(
                    common, TurnExecutionState.Next.ASSISTANT, null);

            assertThrows(CheckpointStore.CommitConflict.class, () -> database.checkpoints().commit(
                    new CheckpointStore.CommitRequest("thr_1", running.threadRevision(),
                            checkpointFixture("cp_stale_turn", running.threadRevision(), "stale"),
                            java.util.Optional.of(new CheckpointStore.TurnOperation(
                                    "turn_1", running.turnMutationVersion() + 1, completed)))));

            assertEquals(running.threadRevision(), database.checkpoints().read("thr_1").threadRevision());
            assertTrue(database.checkpoints().read("thr_1").checkpoint().isEmpty());
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                PersistenceRecords.TurnExecutionRow row = session.getMapper(AgentMapper.class)
                        .selectTurnExecution("turn_1");
                assertEquals(summarizing,
                        new TurnExecutionStateCodec(database.mapper()).read(row.stateJson()));
            }
        }
    }

    /** Checkpoint、Summary、指纹与推进后的 revision 必须跨 SQLite 关闭重开完整恢复。 */
    @Test
    void restoresCheckpointAcrossDatabaseRestart() throws Exception {
        CheckpointStore.ContextCheckpoint expected = checkpointFixture("cp_restart", 1, "restart-fact");
        try (TestDatabase database = database("checkpoint-restart")) {
            MybatisConversationRepository store = initialized(database);
            admit(store);
            CheckpointStore.CommittedCheckpoint committed = database.checkpoints().commit(
                    new CheckpointStore.CommitRequest("thr_1", 1, expected));
            assertTrue(committed.newlyCommitted());
            assertEquals(2, committed.threadRevision());
        }

        try (TestDatabase reopened = database("checkpoint-restart")) {
            CheckpointStore.Snapshot restored = reopened.checkpoints().read("thr_1");
            assertEquals(2, restored.threadRevision());
            assertEquals(expected, restored.checkpoint().orElseThrow());
            assertEquals("restart-fact", restored.checkpoint().orElseThrow()
                    .summary().criticalFacts().getFirst().text());
            assertEquals("3".repeat(64), restored.checkpoint().orElseThrow().envelopeFingerprint());
        }
    }

    /** 两个事务竞争同一 source revision 时只有一个新增，败者必须复用同一持久 Checkpoint。 */
    @Test
    void serializesConcurrentCheckpointCommitsBySourceRevision() throws Exception {
        try (TestDatabase database = database("checkpoint-concurrent")) {
            MybatisConversationRepository store = initialized(database);
            admit(store);
            CountDownLatch start = new CountDownLatch(1);
            try (java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<CheckpointStore.CommittedCheckpoint> first = executor.submit(() -> {
                    start.await();
                    return database.checkpoints().commit(new CheckpointStore.CommitRequest(
                            "thr_1", 1, checkpointFixture("cp_race_a", 1, "race-a")));
                });
                Future<CheckpointStore.CommittedCheckpoint> second = executor.submit(() -> {
                    start.await();
                    return database.checkpoints().commit(new CheckpointStore.CommitRequest(
                            "thr_1", 1, checkpointFixture("cp_race_b", 1, "race-b")));
                });
                start.countDown();
                List<CheckpointStore.CommittedCheckpoint> results = List.of(
                        first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

                assertEquals(1, results.stream().filter(CheckpointStore.CommittedCheckpoint::newlyCommitted).count());
                assertEquals(1, results.stream().filter(value -> !value.newlyCommitted()).count());
                assertEquals(results.getFirst().checkpoint(), results.getLast().checkpoint());
                assertEquals(2, results.getFirst().threadRevision());
                assertEquals(2, database.checkpoints().read("thr_1").threadRevision());
                try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                    assertEquals(1, session.getMapper(CheckpointMapper.class).countCheckpoints("thr_1"));
                }
            }
        }
    }

    /** 启动恢复完整关闭崩溃批次，同时保持已经结算的 Tool 结果和单次 Thread revision。 */
    @Test
    void recoversActiveTurnAndRunningToolAtomically() throws Exception {
        try (TestDatabase database = database("recovery")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            ConversationRepository.CommitReceipt running = store.commit(new ConversationRepository.CommitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING,
                    List.of(new ConversationRepository.ToolPreparedFact(
                                     "call_1", "write_file", textArguments("path", "a"), 0,
                                     ToolSideEffect.EXTERNAL, presentation(ToolPresentation.Status.PENDING),
                                     binding("batch_fixture", "call_1", "write_file")),
                            new ConversationRepository.ToolStartedFact("call_1"),
                            new ConversationRepository.ToolPreparedFact(
                                     "call_2", "shell", textArguments("command", "x"), 1,
                                     ToolSideEffect.EXTERNAL, presentation(ToolPresentation.Status.PENDING),
                                     binding("batch_fixture", "call_2", "shell")),
                            new ConversationRepository.ToolPreparedFact(
                                     "call_3", "read_file", textArguments("path", "a"), 2,
                                     ToolSideEffect.READ_ONLY, presentation(ToolPresentation.Status.PENDING),
                                     binding("batch_fixture", "call_3", "read_file")),
                            new ConversationRepository.ToolPreparedFact(
                                     "call_4", "write_file", textArguments("path", "b"), 3,
                                     ToolSideEffect.EXTERNAL, presentation(ToolPresentation.Status.PENDING),
                                     binding("batch_fixture", "call_4", "write_file")),
                            new ConversationRepository.ToolResultFact("call_4", ToolState.SUCCEEDED, "ok", false,
                                    presentation(ToolPresentation.Status.SUCCESS), "")),
                    admission.turnMutationVersion(), START.plusSeconds(1),
                    new TurnExecutionState.Tools(execution("cfg_1").common(),
                            "batch_fixture", "item_assistant_recovery", 0, 3, 0)));
            StartupRecoveryService.RecoveryResult result = database.recovery().recover();
            assertEquals(1, result.turns());
            assertEquals(3, result.tools());
            assertEquals(0, result.changeSets());
            ConversationRepository.TurnSnapshot turn = store.findTurn("thr_1", "turn_1").orElseThrow();
            assertEquals(TurnState.SUSPENDED, turn.state());
            assertEquals(running.threadRevision() + 1, turn.threadRevision());
            assertEquals(running.turnMutationVersion() + 1, turn.turnMutationVersion());
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                AgentMapper mapper = session.getMapper(AgentMapper.class);
                assertEquals("FAILED", mapper.selectTool(new PersistenceRecords.ToolKey("turn_1", "call_1")).state());
                assertEquals("FAILED", mapper.selectTool(new PersistenceRecords.ToolKey("turn_1", "call_2")).state());
                assertEquals("FAILED", mapper.selectTool(new PersistenceRecords.ToolKey("turn_1", "call_3")).state());
                assertEquals("SUCCEEDED", mapper.selectTool(new PersistenceRecords.ToolKey("turn_1", "call_4")).state());
            }
            TurnExecutionState.Ready recovered = assertInstanceOf(
                    TurnExecutionState.Ready.class,
                    store.findResumeCandidate("turn_1").orElseThrow().execution());
            assertEquals(TurnExecutionState.Next.ASSISTANT, recovered.next());
            List<ToolResultContent> recoveryResults = toolResults(store.readThread("thr_1").orElseThrow());
            assertEquals(List.of("call_1", "call_2", "call_3"),
                    recoveryResults.stream().map(ToolResultContent::callId).toList());
            assertTrue(recoveryResults.stream().allMatch(resultContent -> resultContent.error()
                    && "TOOL_BINDING_UNAVAILABLE: Tool binding is unavailable."
                    .equals(resultContent.content())));
            io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot history =
                    database.history(store).readThread("thr_1", null, 100).orElseThrow();
            assertNull(history.turns().getFirst().changeSet());
            assertTrue(history.items().stream().filter(io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot.ToolItem.class::isInstance)
                    .map(io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot.ToolItem.class::cast)
                    .filter(item -> item.callId().equals("call_1"))
                    .allMatch(item -> item.presentation().status() == ToolPresentation.Status.ERROR));
            StartupRecoveryService.RecoveryResult repeated = database.recovery().recover();
            assertEquals(0, repeated.threads());
            assertEquals(0, repeated.turns());
            assertEquals(0, repeated.tools());
            assertEquals(0, repeated.changeSets());
            assertEquals(turn, store.findTurn("thr_1", "turn_1").orElseThrow());
        }
    }

    /** 只有仍持有初始 READY 游标的 QUEUED Turn 才会被登记为启动后可自动重接纳。 */
    @Test
    void exposesOnlyNeverStartedQueuedTurnsForRuntimeReadmission() throws Exception {
        try (TestDatabase database = database("recovery-never-started")) {
            MybatisConversationRepository store = initialized(database);
            admit(store);
            StartupRecoveryService recovery = database.recovery();

            StartupRecoveryService.RecoveryResult result = recovery.recover();

            assertEquals(1, result.turns());
            assertEquals(List.of(new StartupRecoveryService.QueuedTurn("thr_1", "turn_1")),
                    recovery.queuedTurns());
            assertEquals(TurnState.SUSPENDED,
                    store.findTurn("thr_1", "turn_1").orElseThrow().state());
        }
    }

    /**
     * 首个 Tool 已结算后的强杀必须保留其结果、Active Skill 与 Prompt checkpoint；
     * 后续未结算项写入失败并结束 batch，Resume 只能继续请求 Assistant。
     */
    @Test
    void preservesSettledToolCursorAndPromptCheckpointAcrossStartupRecovery() throws Exception {
        try (TestDatabase database = database("recovery-tools-next-ordinal")) {
            MybatisConversationRepository store = initialized(database);
            admit(store);
            CheckpointStore.ContextCheckpoint checkpoint = checkpointFixture(
                    "cp_tools_prompt", 1, "tools-prompt-summary");
            database.checkpoints().commit(new CheckpointStore.CommitRequest("thr_1", 1, checkpoint));
            TurnExecutionState.Common source = execution("cfg_1").common();
            List<TurnExecutionState.ActiveSkill> activeSkills = List.of(
                    new TurnExecutionState.ActiveSkill("skill_review"));
            TurnExecutionState.Common common = new TurnExecutionState.Common(
                    1, 2, 2, checkpoint.checkpointId(), activeSkills, source.deadlineAt(), source.origin());
            ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT, List.of(
                    new ToolCallContent("call_1", "read_file", textArguments("path", "a")),
                    new ToolCallContent("call_2", "read_file", textArguments("path", "b"))));
            TurnExecutionState.Tools cursor = new TurnExecutionState.Tools(
                    common, "batch_fixture", "item_assistant_tools", 0, 1, 1);
            store.commit(commitRequest("thr_1", "turn_1", TurnState.RUNNING, List.of(
                    new ConversationRepository.AssistantFact(
                            "item_assistant_tools", assistant, "", null, 1),
                    new ConversationRepository.ToolPreparedFact(
                            "call_1", "read_file", textArguments("path", "a"), 0,
                            ToolSideEffect.READ_ONLY, presentation(ToolPresentation.Status.PENDING),
                            binding("batch_fixture", "call_1", "read_file")),
                    new ConversationRepository.ToolResultFact(
                            "call_1", ToolState.SUCCEEDED, "done", false,
                            presentation(ToolPresentation.Status.SUCCESS), ""),
                    new ConversationRepository.ToolPreparedFact(
                            "call_2", "read_file", textArguments("path", "b"), 1,
                            ToolSideEffect.READ_ONLY, presentation(ToolPresentation.Status.PENDING),
                            binding("batch_fixture", "call_2", "read_file"))),
                    0, START.plusSeconds(2), cursor));

            StartupRecoveryService.RecoveryResult recovery = database.recovery().recover();
            assertEquals(1, recovery.tools());
            ConversationRepository.ResumeCandidate candidate = store.findResumeCandidate("turn_1")
                    .orElseThrow();
            TurnExecutionState.Ready recovered = assertInstanceOf(
                    TurnExecutionState.Ready.class, candidate.execution());

            assertEquals(TurnExecutionState.Next.ASSISTANT, recovered.next());
            assertEquals(activeSkills, recovered.common().activeSkills());
            assertEquals(checkpoint.summary().toPromptText(), candidate.promptSummary());
            assertEquals("hello", candidate.originalContent().text());
            assertTrue(candidate.provisionalTitleEligible());
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                AgentMapper mapper = session.getMapper(AgentMapper.class);
                assertEquals("SUCCEEDED", mapper.selectTool(
                        new PersistenceRecords.ToolKey("turn_1", "call_1")).state());
                assertEquals("FAILED", mapper.selectTool(
                        new PersistenceRecords.ToolKey("turn_1", "call_2")).state());
            }
            assertEquals(List.of(
                            new ToolResultContent("call_2",
                                    "TOOL_BINDING_UNAVAILABLE: Tool binding is unavailable.", true)),
                    toolResults(store.readThread("thr_1").orElseThrow()));
        }
    }

    /** 新需求在挂起期间替代问题必须同时结算原 Tool 与进入 steering 队列，不能伪造选项答案。 */
    @Test void supersedesPendingInteractionAtomicallyWithSteering() throws Exception {
        try (TestDatabase database = database("interaction-steering")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            TurnExecutionState.Tools tools = new TurnExecutionState.Tools(
                    execution("cfg_1").common(), "batch_fixture", "item_interaction", 0, 0, 0);
            var committed = store.commit(commitRequest("thr_1", "turn_1", TurnState.RUNNING,
                    List.of(new ConversationRepository.ToolPreparedFact("call_question", "request_user_input",
                            JsonObjects.builder().build(), 0, ToolSideEffect.READ_ONLY,
                            presentation(ToolPresentation.Status.PENDING), binding("batch_fixture", "call_question", "request_user_input")),
                            new ConversationRepository.ToolStartedFact("call_question")), admission.turnMutationVersion(), START.plusSeconds(1), tools));
            var question = new io.github.kongweiguang.ja.conversation.domain.interaction.InteractionQuestion(
                    "question_target", "选择目标", io.github.kongweiguang.ja.conversation.domain.interaction.InteractionQuestionType.TEXT,
                    List.of(), true, true);
            var interaction = new io.github.kongweiguang.ja.conversation.domain.interaction.InteractionRequest(
                    "interaction_steering", "thr_1", "turn_1", "call_question", null, null, null,
                    "interaction-steering", List.of(question), io.github.kongweiguang.ja.conversation.domain.interaction.InteractionStatus.PENDING,
                    List.of(), 0, START.plusSeconds(2), START.plusSeconds(2));
            store.suspendForInteraction(new ConversationRepository.InteractionSuspensionRequest(interaction, tools,
                    committed.turnMutationVersion(), START.plusSeconds(2)));
            var queued = store.enqueueInput(pending("input_revised", ConversationRepository.InputKind.FOLLOW_UP,
                    "换一种方案", START.plusSeconds(3)));
            assertEquals(InputQueue.Kind.STEERING, queued.inputQueue().items().getFirst().kind());
            assertInstanceOf(TurnExecutionState.Ready.class, store.findResumeCandidate("turn_1").orElseThrow().execution());
            try (var session = database.sessions().openSession()) {
                assertEquals("SUPERSEDED", session.getMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.InteractionMapper.class)
                        .selectInteraction(new PersistenceRecords.InteractionKey("thr_1", "interaction_steering")).status());
            }
        }
    }

    /** 取消问答必须留下配对 ToolResult，并关闭工具游标，下一轮不能读到悬空工具消息。 */
    @Test void cancellingInteractionSettlesToolTranscriptOnce() throws Exception {
        try (TestDatabase database = database("interaction-cancel-transcript")) {
            MybatisConversationRepository store = initialized(database);
            var admission = admit(store);
            var tools = new TurnExecutionState.Tools(execution("cfg_1").common(),
                    "batch_fixture", "item_interaction", 0, 0, 0);
            var committed = store.commit(commitRequest("thr_1", "turn_1", TurnState.RUNNING,
                    List.of(new ConversationRepository.ToolPreparedFact("call_question", "request_user_input",
                            JsonObjects.builder().build(), 0, ToolSideEffect.READ_ONLY,
                            presentation(ToolPresentation.Status.PENDING), binding("batch_fixture", "call_question", "request_user_input")),
                            new ConversationRepository.ToolStartedFact("call_question")),
                    admission.turnMutationVersion(), START.plusSeconds(1), tools));
            var question = new io.github.kongweiguang.ja.conversation.domain.interaction.InteractionQuestion(
                    "question_target", "选择目标", io.github.kongweiguang.ja.conversation.domain.interaction.InteractionQuestionType.TEXT,
                    List.of(), true, true);
            var request = new io.github.kongweiguang.ja.conversation.domain.interaction.InteractionRequest(
                    "interaction_cancel", "thr_1", "turn_1", "call_question", null, null, null,
                    "question-key", List.of(question), io.github.kongweiguang.ja.conversation.domain.interaction.InteractionStatus.PENDING,
                    List.of(), 0, START.plusSeconds(2), START.plusSeconds(2));
            store.suspendForInteraction(new ConversationRepository.InteractionSuspensionRequest(request, tools,
                    committed.turnMutationVersion(), START.plusSeconds(2)));
            var interactions = database.interactions();
            var cancelled = interactions.close("thr_1", "interaction_cancel", 0,
                    io.github.kongweiguang.ja.conversation.domain.interaction.InteractionStatus.CANCELLED, "cancel-key", START.plusSeconds(3));
            assertEquals(cancelled, interactions.close("thr_1", "interaction_cancel", 0,
                    io.github.kongweiguang.ja.conversation.domain.interaction.InteractionStatus.CANCELLED, "cancel-key", START.plusSeconds(3)));
            assertEquals(TurnState.CANCELLED, store.findTurn("thr_1", "turn_1").orElseThrow().state());
            assertTrue(store.findResumeCandidate("turn_1").isEmpty());
            var results = toolResults(store.readThread("thr_1").orElseThrow());
            assertEquals(1, results.size());
            assertEquals("call_question", results.getFirst().callId());
            assertTrue(results.getFirst().error());
            try (var session = database.sessions().openSession()) {
                assertEquals(0, session.getMapper(AgentMapper.class).countUnfinishedTools("turn_1"));
            }
        }
    }

    /** RUNNING READ_ONLY 也必须写入标准绑定失效结果，避免恢复路径以只读为由重放调用。 */
    @Test
    void settlesRunningReadOnlyToolWithoutReplay() throws Exception {
        try (TestDatabase database = database("recovery-running-read-only")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            TurnExecutionState.Tools tools = new TurnExecutionState.Tools(
                    execution("cfg_1").common(), "batch_fixture", "item_assistant_read", 0, 0, 0);
            store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING,
                    List.of(new ConversationRepository.ToolPreparedFact(
                                    "call_read", "read_file", textArguments("path", "README.md"), 0,
                                    ToolSideEffect.READ_ONLY, presentation(ToolPresentation.Status.PENDING),
                                    binding("batch_fixture", "call_read", "read_file")),
                            new ConversationRepository.ToolStartedFact("call_read")),
                    admission.turnMutationVersion(), START.plusSeconds(1), tools));

            StartupRecoveryService.RecoveryResult recovery = database.recovery().recover();

            assertEquals(1, recovery.turns());
            assertEquals(1, recovery.tools());
            assertEquals(TurnState.SUSPENDED,
                    store.findTurn("thr_1", "turn_1").orElseThrow().state());
            TurnExecutionState.Ready recovered = assertInstanceOf(
                    TurnExecutionState.Ready.class,
                    store.findResumeCandidate("turn_1").orElseThrow().execution());
            assertEquals(TurnExecutionState.Next.ASSISTANT, recovered.next());
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                assertEquals("FAILED", session.getMapper(AgentMapper.class).selectTool(
                        new PersistenceRecords.ToolKey("turn_1", "call_read")).state());
            }
            assertEquals(List.of(new ToolResultContent(
                            "call_read", "TOOL_BINDING_UNAVAILABLE: Tool binding is unavailable.", true)),
                    toolResults(store.readThread("thr_1").orElseThrow()));
            assertEquals(0, database.recovery().recover().turns());
        }
    }

    /** 异常旧游标即使越过 RUNNING 项也必须扫描整批并补齐结果，不能留下悬空 Tool message 配对。 */
    @Test
    void settlesUnfinishedToolsBeforePersistedCursor() throws Exception {
        try (TestDatabase database = database("recovery-tools-ahead-cursor")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            TurnExecutionState.Tools tools = new TurnExecutionState.Tools(
                    execution("cfg_1").common(), "batch_fixture", "item_assistant_ahead", 0, 1, 1);
            store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING,
                    List.of(new ConversationRepository.ToolPreparedFact(
                                    "call_early", "read_file", textArguments("path", "README.md"), 0,
                                    ToolSideEffect.READ_ONLY, presentation(ToolPresentation.Status.PENDING),
                                    binding("batch_fixture", "call_early", "read_file")),
                            new ConversationRepository.ToolStartedFact("call_early"),
                            new ConversationRepository.ToolPreparedFact(
                                    "call_late", "write_file", textArguments("path", "a"), 1,
                                    ToolSideEffect.EXTERNAL, presentation(ToolPresentation.Status.PENDING),
                                    binding("batch_fixture", "call_late", "write_file"))),
                    admission.turnMutationVersion(), START.plusSeconds(1), tools));

            StartupRecoveryService.RecoveryResult recovery = database.recovery().recover();

            assertEquals(2, recovery.tools());
            assertInstanceOf(TurnExecutionState.Ready.class,
                    store.findResumeCandidate("turn_1").orElseThrow().execution());
            assertEquals(List.of("call_early", "call_late"),
                    toolResults(store.readThread("thr_1").orElseThrow()).stream()
                            .map(ToolResultContent::callId)
                            .toList());
        }
    }

    /** Provider intent 与 UNKNOWN Usage 原子提交，并以 READY 到 PROVIDER_PENDING 的真实游标推进获得资格。 */
    @Test
    void persistsCursorOnlyProviderIntentAfterRunningTransition() throws Exception {
        try (TestDatabase database = database("provider-intent-cursor")) {
            MybatisConversationRepository store = initialized(database);
            admit(store);
            TurnExecutionState.Ready ready = execution("cfg_1");
            ConversationRepository.CommitReceipt running = store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(), 0,
                    START.plusSeconds(1), ready));
            TurnExecutionState.ProviderPending pending = new TurnExecutionState.ProviderPending(
                    ready.common(), "request_assistant", "item_assistant_pending",
                    TurnExecutionState.ProviderPurpose.ASSISTANT,
                    profile("provider_1", "model_1", "cfg_1"), "9".repeat(64), ready);

            ConversationRepository.CommitReceipt intent = store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(providerIntent(pending)),
                    running.turnMutationVersion(), START.plusSeconds(2), pending));

            assertEquals(running.threadRevision() + 1, intent.threadRevision());
            assertEquals(running.turnMutationVersion() + 1, intent.turnMutationVersion());
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                assertInstanceOf(TurnExecutionState.ProviderPending.class,
                        new TurnExecutionStateCodec(database.mapper()).read(session.getMapper(AgentMapper.class)
                                .selectTurnExecution("turn_1").stateJson()));
            }
            StorageException noOp = assertThrows(StorageException.class, () -> store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(providerIntent(pending)),
                    intent.turnMutationVersion(),
                    START.plusSeconds(3), pending)));
            assertEquals(StorageException.Code.CAS_CONFLICT, noOp.code());
        }
    }

    /**
     * UNKNOWN 请求时间与 KNOWN 测量时间是两个真实阶段；Repository 必须允许后到结算推进时间，
     * 且权威历史投影使用 Provider 返回时刻作为 measuredAt。
     */
    @Test
    void settlesUnknownUsageAtLaterMeasuredTime() throws Exception {
        try (TestDatabase database = database("delayed-usage-settlement")) {
            MybatisConversationRepository store = initialized(database);
            admit(store);
            TurnExecutionState.Ready resume = execution("cfg_1");
            TurnExecutionState.ProviderPending pending = new TurnExecutionState.ProviderPending(
                    resume.common(), "request_assistant", "item_assistant_pending",
                    TurnExecutionState.ProviderPurpose.ASSISTANT,
                    profile("provider_1", "model_1", "cfg_1"), "9".repeat(64), resume);
            ConversationRepository.CommitReceipt intent = store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(providerIntent(pending)), 0,
                    START.plusSeconds(1), pending));
            ModelUsage measured = new ModelUsage(10, 4, 14);
            Instant measuredAt = START.plusSeconds(4);
            ConversationRepository.UsageFact settlement = new ConversationRepository.UsageFact(
                    pending.requestId(), measured, 1, 1, ConversationRepository.UsagePurpose.ASSISTANT,
                    ConversationRepository.UsageCertainty.KNOWN, pending.profile());

            store.commit(commitRequest("thr_1", "turn_1", TurnState.RUNNING, List.of(settlement),
                    intent.turnMutationVersion(), measuredAt, resume));

            ThreadSnapshot.ContextUsage usage = database.history(store)
                    .readThread("thr_1", null, 20).orElseThrow().contextUsage();
            assertEquals(measuredAt, usage.measuredAt());
            assertEquals(io.github.kongweiguang.ja.conversation.domain.ProviderRequestUsage.Certainty.KNOWN,
                    usage.request().certainty());
            assertEquals(measured, usage.request().usage());
        }
    }

    /**
     * Assistant intent 已提交或处于流式中时的强杀必须只登记 UNKNOWN usage，恢复原 READY 工作并
     * 保持 SUSPENDED；启动调和自身不得发送第二次 Provider 请求。
     */
    @Test
    void recoversPendingAssistantProviderAsUnknownWithoutAdvancingModelRound() throws Exception {
        try (TestDatabase database = database("recovery-assistant-provider")) {
            MybatisConversationRepository store = initialized(database);
            admit(store);
            TurnExecutionState.Common common = execution("cfg_1").common();
            TurnExecutionState.Ready resume = new TurnExecutionState.Ready(
                    common, TurnExecutionState.Next.ASSISTANT, null);
            TurnExecutionState.ProviderPending pending = new TurnExecutionState.ProviderPending(
                    common, "request_assistant", "item_assistant_pending",
                    TurnExecutionState.ProviderPurpose.ASSISTANT,
                    profile("provider_1", "model_1", "cfg_1"), "9".repeat(64), resume);
            ConversationRepository.CommitReceipt running = store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(providerIntent(pending)), 0,
                    START.plusSeconds(1), pending));

            StartupRecoveryService.RecoveryResult recovered = database.recovery().recover();

            assertEquals(1, recovered.turns());
            ConversationRepository.TurnSnapshot turn = store.findTurn("thr_1", "turn_1").orElseThrow();
            assertEquals(TurnState.SUSPENDED, turn.state());
            assertEquals(running.turnMutationVersion() + 1, turn.turnMutationVersion());
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                AgentMapper mapper = session.getMapper(AgentMapper.class);
                assertEquals(1, mapper.countUsageForTurn("turn_1"));
                TurnExecutionState.Ready ready = assertInstanceOf(TurnExecutionState.Ready.class,
                        new TurnExecutionStateCodec(database.mapper())
                                .read(mapper.selectTurnExecution("turn_1").stateJson()));
                assertEquals(TurnExecutionState.Next.ASSISTANT, ready.next());
                assertEquals(0, ready.common().modelRound());
                assertEquals(2, ready.common().nextProviderOrdinal());
                try (java.sql.Statement statement = session.getConnection().createStatement();
                     java.sql.ResultSet usage = statement.executeQuery(
                             "SELECT usage_id,request_ordinal,purpose,certainty,"
                                     + "input_tokens,output_tokens,total_tokens "
                                     + "FROM usage WHERE turn_id='turn_1'")) {
                    assertTrue(usage.next());
                    assertEquals("usage_assistant", usage.getString("usage_id"));
                    assertEquals(1, usage.getInt("request_ordinal"));
                    assertEquals("ASSISTANT", usage.getString("purpose"));
                    assertEquals("UNKNOWN", usage.getString("certainty"));
                    assertNull(usage.getObject("input_tokens"));
                    assertNull(usage.getObject("output_tokens"));
                    assertNull(usage.getObject("total_tokens"));
                    assertFalse(usage.next());
                }
            }
            assertEquals(0, database.recovery().recover().turns());
        }
    }

    /** Summary Provider intent 强杀后写 UNKNOWN usage，并恢复原 READY(SUMMARY) 游标等待显式 Resume。 */
    @Test
    void recoversPendingSummaryProviderWithoutSkippingAcceptedProgress() throws Exception {
        try (TestDatabase database = database("recovery-summary-provider")) {
            MybatisConversationRepository store = initialized(database);
            admit(store);
            TurnExecutionState.Common common = execution("cfg_1").common();
            TurnExecutionState.SummaryProgress progress = TurnExecutionState.SummaryProgress.candidate(
                    "{}", 7, 2, "7".repeat(64),
                    new TurnExecutionState.KnownUsage(100, 10, 110, 4, 0));
            TurnExecutionState.Ready resume = new TurnExecutionState.Ready(
                    common, TurnExecutionState.Next.SUMMARY, progress);
            TurnExecutionState.ProviderPending pending = new TurnExecutionState.ProviderPending(
                    common, "request_summary", "item_summary", TurnExecutionState.ProviderPurpose.SUMMARY,
                    profile("provider_1", "model_1", "cfg_1"), "8".repeat(64), resume);
            ConversationRepository.CommitReceipt running = store.commit(new ConversationRepository.CommitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(providerIntent(pending)), 0,
                    START.plusSeconds(1), pending));

            StartupRecoveryService.RecoveryResult recovered = database.recovery().recover();

            assertEquals(1, recovered.turns());
            ConversationRepository.TurnSnapshot turn = store.findTurn("thr_1", "turn_1").orElseThrow();
            assertEquals(TurnState.SUSPENDED, turn.state());
            assertEquals(running.turnMutationVersion() + 1, turn.turnMutationVersion());
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                AgentMapper mapper = session.getMapper(AgentMapper.class);
                assertEquals(1, mapper.countUsageForTurn("turn_1"));
                TurnExecutionState restored = new TurnExecutionStateCodec(database.mapper())
                        .read(mapper.selectTurnExecution("turn_1").stateJson());
                TurnExecutionState.Ready ready = assertInstanceOf(TurnExecutionState.Ready.class, restored);
                assertEquals(TurnExecutionState.Next.SUMMARY, ready.next());
                assertEquals(progress, ready.summary());
                assertEquals(2, ready.common().nextProviderOrdinal());
            }
        }
    }

    /** 恢复尊重已持久化 cancel intent，不把它改写为 runtime 重启失败。 */
    @Test
    void recoveryPrioritizesCommittedCancellationIntent() throws Exception {
        try (TestDatabase database = database("recovery-cancel")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            ConversationRepository.CancellationClaim claim = store.claimCancellation("thr_1", "turn_1",
                    admission.threadRevision(), "user cancelled", START.plusSeconds(1));

            StartupRecoveryService.RecoveryResult recovered = database.recovery().recover();
            assertEquals(1, recovered.turns());
            assertEquals(1, recovered.changeSets());
            ConversationRepository.TurnSnapshot turn = store.findTurn("thr_1", "turn_1").orElseThrow();
            assertEquals(TurnState.CANCELLED, turn.state());
            assertEquals(claim.turnMutationVersion() + 1, turn.turnMutationVersion());
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                PersistenceRecords.TurnRow row = session.getMapper(AgentMapper.class)
                        .selectTurn(new PersistenceRecords.TurnKey("thr_1", "turn_1"));
                assertEquals("CANCELLED", row.errorCode());
                assertEquals("Cancelled before sidecar restart", row.terminalSummary());
                assertEquals("2026-08-25T12:00:01Z", row.cancelRequestedAt());
            }
            StartupRecoveryService.RecoveryResult repeated = database.recovery().recover();
            assertEquals(0, repeated.threads());
            assertEquals(0, repeated.turns());
            assertEquals(0, repeated.changeSets());
        }
    }

    /** READY 恢复只允许最早 SUSPENDED Turn 赢得双 CAS，并保持原 turnId。 */
    @Test
    void resumesSuspendedReadyTurnWithOriginalIdentity() throws Exception {
        try (TestDatabase database = database("resume-ready")) {
            MybatisConversationRepository store = initialized(database);
            admit(store);
            StartupRecoveryService.RecoveryResult recovered = database.recovery().recover();
            assertEquals(1, recovered.turns());
            ConversationRepository.ResumeCandidate candidate = store.findResumeCandidate("turn_1").orElseThrow();
            assertEquals(TurnState.SUSPENDED, store.findTurn("thr_1", "turn_1").orElseThrow().state());
            assertTrue(store.hasSuspendedTurn("thr_1"));

            ConversationRepository.ResumeReceipt receipt = store.resume("turn_1",
                    candidate.threadRevision(), candidate.turnMutationVersion(), START.plusSeconds(1));

            assertEquals("turn_1", receipt.turnId());
            assertEquals(TurnState.QUEUED, store.findTurn("thr_1", "turn_1").orElseThrow().state());
            assertFalse(store.hasSuspendedTurn("thr_1"));
            assertTrue(store.findResumeCandidate("turn_1").isEmpty());
        }
    }

    /** 相同 requestedAt 与反向 turnId 仍必须按 admission 序列恢复，禁止时间戳或 identity 偷渡 FIFO。 */
    @Test
    void resumeHeadOfLineUsesPersistedAdmissionSequence() throws Exception {
        try (TestDatabase database = database("resume-explicit-turn-sequence")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt first = store.admit(new ConversationRepository.TurnAdmission(
                    "thr_1", "turn_z", "item_user_z",
                    new ModelMessage(ModelRole.USER, List.of(new TextContent("first"))), List.of(),
                    0, START, execution("cfg_1")));
            store.admit(new ConversationRepository.TurnAdmission(
                    "thr_1", "turn_a", "item_user_a",
                    new ModelMessage(ModelRole.USER, List.of(new TextContent("second"))), List.of(),
                    first.threadRevision(), START, execution("cfg_1")));
            assertEquals(2, database.recovery().recover().turns());

            ConversationRepository.ResumeCandidate later = store.findResumeCandidate("turn_a").orElseThrow();
            StorageException orderConflict = assertThrows(StorageException.class, () -> store.resume(
                    "turn_a", later.threadRevision(), later.turnMutationVersion(), START.plusSeconds(1)));
            assertEquals(StorageException.Code.CAS_CONFLICT, orderConflict.code());

            ConversationRepository.ResumeCandidate earlier = store.findResumeCandidate("turn_z").orElseThrow();
            ConversationRepository.ResumeReceipt resumed = store.resume(
                    "turn_z", earlier.threadRevision(), earlier.turnMutationVersion(), START.plusSeconds(2));
            assertEquals("turn_z", resumed.turnId());
            assertEquals(TurnState.QUEUED,
                    store.findTurn("thr_1", "turn_z").orElseThrow().state());
            assertEquals(TurnState.SUSPENDED,
                    store.findTurn("thr_1", "turn_a").orElseThrow().state());
        }
    }

    /**
     * Provider settlement 引用的摘要与 Resume 后下一轮摘要必须分别读取；即使其间提交了更新
     * checkpoint，也不能用最新摘要重算原 batch 的 Prompt revision。
     */
    @Test
    void restoresReferencedPromptCheckpointSeparatelyFromLatestCheckpoint() throws Exception {
        try (TestDatabase database = database("resume-prompt-checkpoint")) {
            MybatisConversationRepository store = initialized(database);
            admit(store);
            CheckpointStore.ContextCheckpoint referenced = checkpointFixture(
                    "cp_prompt_a", 1, "provider-prompt-summary");
            CheckpointStore.CommittedCheckpoint first = database.checkpoints().commit(
                    new CheckpointStore.CommitRequest("thr_1", 1, referenced));
            TurnExecutionState.Ready settled = new TurnExecutionState.Ready(
                    executionWithPromptCheckpoint("cfg_1", referenced.checkpointId()),
                    TurnExecutionState.Next.ASSISTANT, null);
            ConversationRepository.CommitReceipt running = store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(), 0,
                    START.plusSeconds(2), settled));
            CheckpointStore.ContextCheckpoint latest = checkpointFixture(
                    "cp_prompt_b", running.threadRevision(), "next-round-summary");
            database.checkpoints().commit(new CheckpointStore.CommitRequest(
                    "thr_1", running.threadRevision(), latest));

            database.recovery().recover();
            ConversationRepository.ResumeCandidate candidate = store.findResumeCandidate("turn_1")
                    .orElseThrow();

            assertEquals(referenced.summary().toPromptText(), candidate.promptSummary());
            assertEquals(latest.summary().toPromptText(), candidate.latestCheckpointSummary());
            assertEquals("cp_prompt_a", candidate.execution().common().promptCheckpointId());
            assertTrue(first.newlyCommitted());
        }
    }

    /** execution 引用的 checkpoint 一旦缺失就必须失败关闭，禁止用空摘要或最新摘要猜测原 Prompt。 */
    @Test
    void rejectsResumeCandidateWhenPromptCheckpointIsMissing() throws Exception {
        try (TestDatabase database = database("resume-missing-prompt-checkpoint")) {
            MybatisConversationRepository store = initialized(database);
            admit(store);
            TurnExecutionState.Ready invalid = new TurnExecutionState.Ready(
                    executionWithPromptCheckpoint("cfg_1", "cp_missing"),
                    TurnExecutionState.Next.ASSISTANT, null);
            store.commit(commitRequest("thr_1", "turn_1", TurnState.RUNNING, List.of(), 0,
                    START.plusSeconds(1), invalid));
            database.recovery().recover();

            StorageException failure = assertThrows(StorageException.class,
                    () -> store.findResumeCandidate("turn_1"));

            assertEquals(StorageException.Code.INVALID_STATE, failure.code());
        }
    }

    /** SUSPENDED cancel 不依赖进程内 Scope，并在同事务删除 execution。 */
    @Test
    void cancelsSuspendedTurnWithoutRuntimeOwner() throws Exception {
        try (TestDatabase database = database("cancel-suspended")) {
            MybatisConversationRepository store = initialized(database);
            admit(store);
            database.recovery().recover();
            ConversationRepository.ResumeCandidate candidate = store.findResumeCandidate("turn_1").orElseThrow();

            ConversationRepository.CancelResult result = store.cancelSuspended(
                    "turn_1", candidate.threadRevision(), START.plusSeconds(1));

            assertEquals(candidate.threadRevision() + 1, result.threadRevision());
            assertEquals(TurnState.CANCELLED, store.findTurn("thr_1", "turn_1").orElseThrow().state());
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                assertNull(session.getMapper(AgentMapper.class).selectTurnExecution("turn_1"));
            }
        }
    }

    /** execution 行缺失必须稳定失败关闭，不能因清理删除零行而回滚整个启动恢复事务。 */
    @Test
    void recoveryFailsClosedWhenExecutionRowIsMissing() throws Exception {
        try (TestDatabase database = database("recovery-missing-execution")) {
            MybatisConversationRepository store = initialized(database);
            admit(store);
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                assertEquals(1, session.getMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.RecoveryMapper.class)
                        .deleteExecution("turn_1"));
                session.commit();
            }

            StartupRecoveryService.RecoveryResult recovered = database.recovery().recover();
            assertEquals(1, recovered.turns());
            assertEquals(1, recovered.changeSets());
            ConversationRepository.TurnSnapshot turn = store.findTurn("thr_1", "turn_1").orElseThrow();
            assertEquals(TurnState.FAILED, turn.state());
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                assertEquals("TURN_EXECUTION_STATE_CORRUPT", session.getMapper(AgentMapper.class)
                        .selectTurn(new PersistenceRecords.TurnKey("thr_1", "turn_1")).errorCode());
            }
            assertEquals(0, database.recovery().recover().turns());
        }
    }

    /** 审批决定提交后的强杀窗口保留决定，但关闭未执行 Tool 并继续到下一次 Assistant 请求。 */
    /** 已提交取消后仍须持久拒绝审批来唤醒 Broker，但不能复活 RUNNING 或放行 APPROVE。 */
    @Test void cancellationCanSettleApprovalWithoutRevivingTurn() throws Exception {
        try (TestDatabase database = database("cancel-approval-waiter")) {
            MybatisConversationRepository store = initialized(database);
            var admission = admit(store);
            var tools = new TurnExecutionState.Tools(execution("cfg_1").common(),
                    "batch_fixture", "item_assistant", 0, 0, 0);
            var prepared = store.commit(commitRequest("thr_1", "turn_1", TurnState.RUNNING,
                    List.of(new ConversationRepository.ToolPreparedFact("call_approval", "edit",
                            textArguments("path", "README.md"), 0, ToolSideEffect.EXTERNAL,
                            presentation(ToolPresentation.Status.PENDING), binding("batch_fixture", "call_approval", "edit"))),
                    admission.turnMutationVersion(), START.plusSeconds(1), tools));
            var waiting = store.commit(commitRequest("thr_1", "turn_1", TurnState.WAITING_APPROVAL,
                    List.of(new ConversationRepository.ApprovalFact("appr_cancel", "call_approval", null,
                            START.plusSeconds(60), presentation(ToolPresentation.Status.WAITING_APPROVAL))),
                    prepared.turnMutationVersion(), START.plusSeconds(2), tools));
            store.claimCancellation("thr_1", "turn_1", waiting.threadRevision(), "stop", START.plusSeconds(3));
            assertFalse(store.resolveApproval("appr_cancel", ApprovalDecision.APPROVE, START.plusSeconds(4)));
            assertTrue(store.resolveApproval("appr_cancel", ApprovalDecision.DENY, START.plusSeconds(4)));
            assertFalse(store.resolveApproval("appr_cancel", ApprovalDecision.DENY, START.plusSeconds(4)));
            assertEquals(TurnState.WAITING_APPROVAL, store.findTurn("thr_1", "turn_1").orElseThrow().state());
            try (var session = database.sessions().openSession()) {
                assertNotNull(session.getMapper(AgentMapper.class)
                        .selectTurn(new PersistenceRecords.TurnKey("thr_1", "turn_1")).cancelRequestedAt());
            }
        }
    }

    /** 启动恢复必须保留已落库的审批与 Tool 执行游标，避免重放未知副作用或丢失待处理审批。 */
    @Test
    void approvalDecisionPreservesToolsExecutionAcrossStartupRecovery() throws Exception {
        try (TestDatabase database = database("approval-decision-recovery")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            TurnExecutionState.Tools tools = new TurnExecutionState.Tools(
                    execution("cfg_1").common(), "batch_fixture", "item_assistant", 0, 0, 0);
            ConversationRepository.CommitReceipt prepared = store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING,
                    List.of(new ConversationRepository.ToolPreparedFact(
                            "call_approval", "edit", textArguments("path", "README.md"), 0,
                            ToolSideEffect.EXTERNAL, presentation(ToolPresentation.Status.PENDING),
                            binding("batch_fixture", "call_approval", "edit"))),
                    admission.turnMutationVersion(), START.plusSeconds(1), tools));
            Instant expiresAt = START.plus(Duration.ofMinutes(5));
            ConversationRepository.CommitReceipt waiting = store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.WAITING_APPROVAL,
                    List.of(new ConversationRepository.ApprovalFact(
                            "appr_existing", "call_approval", null, expiresAt,
                            presentation(ToolPresentation.Status.WAITING_APPROVAL))),
                    prepared.turnMutationVersion(), START.plusSeconds(2), tools));
            String beforeDecision;
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                beforeDecision = session.getMapper(AgentMapper.class)
                        .selectTurnExecution("turn_1").stateJson();
            }

            assertTrue(store.resolveApproval(
                    "appr_existing", ApprovalDecision.APPROVE, START.plusSeconds(3)));
            assertEquals(waiting.turnMutationVersion() + 1,
                    store.findTurn("thr_1", "turn_1").orElseThrow().turnMutationVersion());
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                PersistenceRecords.TurnExecutionRow afterDecision = session.getMapper(AgentMapper.class)
                        .selectTurnExecution("turn_1");
                assertNotNull(afterDecision);
                assertEquals(beforeDecision, afterDecision.stateJson());
            }

            StartupRecoveryService.RecoveryResult recovery = database.recovery().recover();
            assertEquals(1, recovery.turns());
            assertEquals(1, recovery.tools());
            assertEquals(TurnState.SUSPENDED,
                    store.findTurn("thr_1", "turn_1").orElseThrow().state());
            ConversationRepository.ResumeCandidate candidate = store.findResumeCandidate("turn_1").orElseThrow();
            TurnExecutionState.Ready recovered = assertInstanceOf(
                    TurnExecutionState.Ready.class, candidate.execution());
            assertEquals(TurnExecutionState.Next.ASSISTANT, recovered.next());
            assertEquals(ApprovalDecision.APPROVE,
                    store.findApproval("turn_1", "call_approval").orElseThrow().decision());
            assertEquals(List.of(new ToolResultContent(
                            "call_approval", "TOOL_BINDING_UNAVAILABLE: Tool binding is unavailable.", true)),
                    toolResults(store.readThread("thr_1").orElseThrow()));

            store.resume("turn_1", candidate.threadRevision(), candidate.turnMutationVersion(),
                    START.plusSeconds(4));
            assertEquals(TurnState.QUEUED,
                    store.findTurn("thr_1", "turn_1").orElseThrow().state());
        }
    }

    /** 启动恢复在 SQLite 内拒绝过期审批并关闭其 Tool，保留原 approvalId 且不唤醒外部执行。 */
    @Test
    void startupRecoveryDeniesExpiredApprovalWithoutReplacingIdentity() throws Exception {
        try (TestDatabase database = database("approval-expired-recovery")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            TurnExecutionState.Tools tools = new TurnExecutionState.Tools(
                    execution("cfg_1").common(), "batch_fixture", "item_assistant", 0, 0, 0);
            ConversationRepository.CommitReceipt prepared = store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING,
                    List.of(new ConversationRepository.ToolPreparedFact(
                            "call_expired", "edit", textArguments("path", "README.md"), 0,
                            ToolSideEffect.EXTERNAL, presentation(ToolPresentation.Status.PENDING),
                            binding("batch_fixture", "call_expired", "edit"))),
                    admission.turnMutationVersion(), START.plusSeconds(1), tools));
            store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.WAITING_APPROVAL,
                    List.of(new ConversationRepository.ApprovalFact(
                            "appr_expired", "call_expired", null, START.minusSeconds(1),
                            presentation(ToolPresentation.Status.WAITING_APPROVAL))),
                    prepared.turnMutationVersion(), START.plusSeconds(2), tools));

            StartupRecoveryService.RecoveryResult recovery = database.recovery().recover();

            assertEquals(1, recovery.turns());
            assertEquals(1, recovery.tools());
            assertEquals(TurnState.SUSPENDED,
                    store.findTurn("thr_1", "turn_1").orElseThrow().state());
            ConversationRepository.PendingApproval approval =
                    store.findApproval("turn_1", "call_expired").orElseThrow();
            assertEquals("appr_expired", approval.approvalId());
            assertEquals(ApprovalDecision.DENY, approval.decision());
            TurnExecutionState.Ready recovered = assertInstanceOf(
                    TurnExecutionState.Ready.class,
                    store.findResumeCandidate("turn_1").orElseThrow().execution());
            assertEquals(TurnExecutionState.Next.ASSISTANT, recovered.next());
            assertEquals(List.of(new ToolResultContent(
                            "call_expired", "TOOL_BINDING_UNAVAILABLE: Tool binding is unavailable.", true)),
                    toolResults(store.readThread("thr_1").orElseThrow()));
            assertEquals(0, database.recovery().recover().turns());
        }
    }

    /** 第二事务遇到 IMMEDIATE writer lock 时在 bounded busy timeout 内失败，首事务可回滚。 */
    @Test
    void boundsConcurrentBusyWriterAndPreservesRollback() throws Exception {
        try (TestDatabase database = database("busy", Duration.ofMillis(100))) {
            MybatisConversationRepository store = initialized(database);
            admit(store);
            try (org.apache.ibatis.session.SqlSession blocker = database.sessions().openSession()) {
                blocker.getMapper(HistoryMapper.class).advanceThreadFact(new PersistenceRecords.ThreadRevision(
                        "thr_1", START.plusSeconds(1).toString()));
                assertThrows(StorageException.class, () -> store.commit(commitRequest(
                        "thr_1", "turn_1", TurnState.RUNNING, List.of(), 0, START.plusSeconds(2))));
                blocker.rollback();
            }
            assertEquals(1, store.findTurn("thr_1", "turn_1").orElseThrow().threadRevision());
        }
    }

    /** 后继 admission 只推进全局 revision，不得使前序 RUNNING Turn 的内部提交永久冲突。 */
    @Test
    void commitsRunningTurnAfterSuccessorAdmissionWithIndependentTurnCas() throws Exception {
        try (TestDatabase database = database("interleaved-turns")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admittedA = admit(store);
            ConversationRepository.CommitReceipt runningA = store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(),
                    admittedA.turnMutationVersion(), START.plusSeconds(1)));
            ConversationRepository.AdmissionReceipt admittedB = store.admit(new ConversationRepository.TurnAdmission(
                    "thr_1", "turn_2", "item_user_2",
                    new ModelMessage(ModelRole.USER,
                            List.of(new TextContent("next"))), List.of(),
                    runningA.threadRevision(), START.plusSeconds(2), execution("cfg_1")));
            assertNull(admittedB.provisionalTitle());
            assertEquals("hello", store.readThread("thr_1").orElseThrow().title());
            ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT, List.of(
                    new TextContent("checking"),
                    new ToolCallContent("call_1", "read_file", textArguments("path", "README.md"))));
            ConversationRepository.CommitReceipt modelA = store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING,
                    List.of(new ConversationRepository.AssistantFact(
                                    "item_assistant_1", assistant, "checking", null, 1),
                            new ConversationRepository.ToolPreparedFact("call_1", "read_file",
                                    textArguments("path", "README.md"), 0, ToolSideEffect.READ_ONLY,
                                    presentation(ToolPresentation.Status.PENDING),
                                    binding("batch_fixture", "call_1", "read_file"))),
                    runningA.turnMutationVersion(), START.plusSeconds(3)));
            assertThrows(StorageException.class, () -> store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING,
                    List.of(new ConversationRepository.ToolStartedFact("call_1")), runningA.turnMutationVersion(),
                    START.plusSeconds(4))));
            ConversationRepository.CommitReceipt toolA = store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING,
                    List.of(new ConversationRepository.ToolResultFact("call_1", ToolState.SUCCEEDED, "ok", false,
                                    presentation(ToolPresentation.Status.SUCCESS), ""),
                            new ConversationRepository.ToolResultMessageFact("item_tool_1", new ModelMessage(
                                    ModelRole.TOOL,
                                    List.of(new ToolResultContent("call_1", "ok", false))))),
                    modelA.turnMutationVersion(), START.plusSeconds(5)));
            ConversationRepository.TerminalCommit terminal = new ConversationRepository.TerminalCommit(
                    "thr_1", "turn_1", TurnState.COMPLETED, "done", null, null,
                    "item_final", new ModelMessage(ModelRole.ASSISTANT,
                    List.of(new TextContent("done"))), List.of(),
                    toolA.turnMutationVersion(), START.plusSeconds(6));
            ConversationRepository.CommitReceipt terminalA = store.commitTerminal(terminal);

            assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), List.of(
                    admittedA.threadRevision(), runningA.threadRevision(), admittedB.threadRevision(),
                    modelA.threadRevision(), toolA.threadRevision(), terminalA.threadRevision()));
            assertEquals(4, terminalA.turnMutationVersion());
            assertEquals(0, admittedB.turnMutationVersion());
            assertEquals(TurnState.QUEUED,
                    store.findTurn("thr_1", "turn_2").orElseThrow().state());
            assertThrows(StorageException.class, () -> store.commitTerminal(new ConversationRepository.TerminalCommit(
                    "thr_1", "turn_1", TurnState.COMPLETED, "late", null, null,
                    null, null, List.of(), terminalA.turnMutationVersion(), START.plusSeconds(7))));
            assertEquals(6, store.findTurn("thr_1", "turn_1").orElseThrow().threadRevision());
        }
    }

    /** close 遇 active writer 不释放 lease；事务结束后 WAL truncate 成功才允许新 owner。 */
    @Test
    void checkpointsWalBeforeReleasingLease() throws Exception {
        try (TestDatabase database = database("wal-close", Duration.ofMillis(100))) {
            MybatisConversationRepository store = initialized(database);
            admit(store);
            StorageException duplicateBinding = assertThrows(StorageException.class,
                    () -> database.database().bindWalCheckpoint(database.sessions()));
            assertEquals(StorageException.Code.INVALID_STATE, duplicateBinding.code());
            try (org.apache.ibatis.session.SqlSession blocker = database.sessions().openSession()) {
                blocker.getMapper(HistoryMapper.class).advanceThreadFact(new PersistenceRecords.ThreadRevision(
                        "thr_1", START.plusSeconds(1).toString()));
                assertThrows(StorageException.class, database.database()::close);
                assertThrows(StorageException.class, () -> DatabaseLeaseProbe.acquire(database.path()));
                blocker.rollback();
            }
            database.database().close();
            database.database().close();
            java.nio.file.Path wal = java.nio.file.Path.of(database.path() + "-wal");
            assertTrue(!java.nio.file.Files.exists(wal) || java.nio.file.Files.size(wal) == 0);
            try (AutoCloseable ignored = DatabaseLeaseProbe.acquire(database.path())) {
                assertNotNull(ignored);
            }
        }
    }

    /** Thread 列表必须在 SQL keyset 之前按 Workspace 过滤，避免其它项目占满首屏。 */
    @Test
    void listsThreadsInsideOneWorkspaceOnly() throws Exception {
        try (TestDatabase database = database("thread-workspace-filter")) {
            MybatisConversationRepository store = initialized(database);
            MybatisHistoryService history = database.history(store);
            history.register(new Workspace.Registration("ws_2",
                    database.path().getParent().resolve("workspace-2"), "workspace-2",
                    Workspace.Trust.TRUSTED, START.plusSeconds(1)));
            store.createThread(new ConversationRepository.ThreadDefinition(
                    "thr_2", "ws_2", "thread-2", preferences("provider_1", "model_1"), START.plusSeconds(1)));

            CursorPage<ThreadSummary> first = history.listThreads("ws_1", null, 10);
            CursorPage<ThreadSummary> second = history.listThreads("ws_2", null, 10);
            assertEquals(List.of("thr_1"), first.items().stream().map(ThreadSummary::threadId).toList());
            assertEquals(List.of("thr_2"), second.items().stream().map(ThreadSummary::threadId).toList());
        }
    }

    /**
     * Pin、分页、归档、搜索恢复与 latest Turn 必须共享同一权威投影；生命周期 CAS 失败或
     * 非终态拒绝都不能留下半提交的置顶状态。
     */
    @Test
    void projectsPinnedLifecycleAndLatestTurnStatusWithStableCas() throws Exception {
        try (TestDatabase database = database("thread-pinned-lifecycle")) {
            MybatisConversationRepository store = initialized(database);
            MybatisHistoryService history = database.history(store);
            store.createThread(new ConversationRepository.ThreadDefinition(
                    "thr_2", "ws_1", "thread two", preferences("provider_1", "model_1"),
                    START.plusSeconds(1)));
            store.createThread(new ConversationRepository.ThreadDefinition(
                    "thr_3", "ws_1", "thread three", preferences("provider_1", "model_1"),
                    START.plusSeconds(2)));

            ThreadSummary pinned = history.pinThread("thr_1", true, 0);
            assertTrue(pinned.pinned());
            assertEquals(1, pinned.revision());
            CursorPage<ThreadSummary> first = history.listThreads("ws_1", null, 2);
            CursorPage<ThreadSummary> second = history.listThreads("ws_1", first.nextCursor(), 2);
            assertEquals("thr_1", first.items().getFirst().threadId());
            assertEquals(3, java.util.stream.Stream.concat(first.items().stream(), second.items().stream())
                    .map(ThreadSummary::threadId).distinct().count());
            assertNull(second.nextCursor());

            StorageException stale = assertThrows(StorageException.class,
                    () -> history.pinThread("thr_1", false, 0));
            assertEquals(StorageException.Code.CAS_CONFLICT, stale.code());
            ThreadSummary archived = history.archiveThread("thr_1", pinned.revision());
            assertEquals(ThreadSummary.Status.ARCHIVED, archived.status());
            assertFalse(archived.pinned());
            assertTrue(history.listThreads("ws_1", null, 10).items().stream()
                    .noneMatch(value -> value.threadId().equals("thr_1")));
            assertEquals(ThreadSummary.Status.ARCHIVED,
                    history.searchThreads("ws_1", "thread", null, 10).items().stream()
                            .filter(value -> value.threadId().equals("thr_1")).findFirst().orElseThrow().status());

            ThreadSummary restored = history.restoreThread("thr_1", archived.revision());
            assertEquals(ThreadSummary.Status.ACTIVE, restored.status());
            assertFalse(restored.pinned());
            ConversationRepository.AdmissionReceipt admission = store.admit(
                    new ConversationRepository.TurnAdmission(
                            "thr_1", "turn_latest",
                            "item_latest", new ModelMessage(ModelRole.USER,
                            List.of(new TextContent("latest"))), List.of(), restored.revision(),
                            START.plusSeconds(3), execution("cfg_1")));
            ThreadSummary projected = history.listThreads("ws_1", null, 10).items().stream()
                    .filter(value -> value.threadId().equals("thr_1")).findFirst().orElseThrow();
            assertEquals(TurnState.QUEUED, projected.latestTurnStatus());

            StorageException busy = assertThrows(StorageException.class,
                    () -> history.archiveThread("thr_1", admission.threadRevision()));
            assertEquals(StorageException.Code.INVALID_STATE, busy.code());
            ThreadSummary unchanged = history.listThreads("ws_1", null, 10).items().stream()
                    .filter(value -> value.threadId().equals("thr_1")).findFirst().orElseThrow();
            assertEquals(admission.threadRevision(), unchanged.revision());
            assertFalse(unchanged.pinned());
        }
    }

    /**
     * 已读边界只在最新成功/失败结果上推进：实时态不改 revision，终态之后先投影未读，
     * 再通过精确 CAS 持久化；归档与恢复不得丢失该边界，新终态仍会重新变为未读。
     */
    @Test
    void persistsSeenBoundaryAcrossTerminalTurnsAndLifecycle() throws Exception {
        try (TestDatabase database = database("thread-seen-boundary")) {
            MybatisConversationRepository store = initialized(database);
            MybatisHistoryService history = database.history(store);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            ConversationRepository.CommitReceipt running = store.commit(commitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(),
                    admission.turnMutationVersion(), START.plusSeconds(1)));

            ThreadSummary liveNoOp = history.markThreadSeen("thr_1", running.threadRevision());
            assertEquals(running.threadRevision(), liveNoOp.revision());
            assertEquals(TurnState.RUNNING, liveNoOp.latestTurnStatus());
            assertFalse(liveNoOp.latestTurnSeen());

            ConversationRepository.CommitReceipt completed = store.commitTerminal(
                    new ConversationRepository.TerminalCommit(
                            "thr_1", "turn_1", TurnState.COMPLETED, "done", null, null,
                            null, null, List.of(), running.turnMutationVersion(), START.plusSeconds(2)));
            ThreadSummary unseen = history.listThreads("ws_1", null, 10).items().getFirst();
            assertEquals(completed.threadRevision(), unseen.revision());
            assertEquals(TurnState.COMPLETED, unseen.latestTurnStatus());
            assertFalse(unseen.latestTurnSeen());

            StorageException stale = assertThrows(StorageException.class,
                    () -> history.markThreadSeen("thr_1", running.threadRevision()));
            assertEquals(StorageException.Code.CAS_CONFLICT, stale.code());
            ThreadSummary seen = history.markThreadSeen("thr_1", completed.threadRevision());
            assertTrue(seen.latestTurnSeen());
            assertEquals(completed.threadRevision() + 1, seen.revision());
            ThreadSummary idempotent = history.markThreadSeen("thr_1", completed.threadRevision());
            assertEquals(seen.revision(), idempotent.revision());
            assertTrue(idempotent.latestTurnSeen());

            ThreadSummary archived = history.archiveThread("thr_1", seen.revision());
            assertTrue(archived.latestTurnSeen());
            ThreadSummary searched = history.searchThreads("ws_1", "", null, 10).items().getFirst();
            assertEquals(ThreadSummary.Status.ARCHIVED, searched.status());
            assertTrue(searched.latestTurnSeen());
            ThreadSummary restored = history.restoreThread("thr_1", archived.revision());
            assertTrue(restored.latestTurnSeen());

            ConversationRepository.AdmissionReceipt secondAdmission = store.admit(
                    new ConversationRepository.TurnAdmission(
                            "thr_1", "turn_second",
                            "item_second", new ModelMessage(ModelRole.USER,
                            List.of(new TextContent("second"))), List.of(), restored.revision(),
                            START.plusSeconds(3), execution("cfg_1")));
            ConversationRepository.CommitReceipt secondRunning = store.commit(commitRequest(
                    "thr_1", "turn_second", TurnState.RUNNING, List.of(),
                    secondAdmission.turnMutationVersion(), START.plusSeconds(4)));
            store.commitTerminal(new ConversationRepository.TerminalCommit(
                    "thr_1", "turn_second", TurnState.FAILED, "failed", "MODEL_ERROR", "failed",
                    null, null, List.of(), secondRunning.turnMutationVersion(), START.plusSeconds(5)));
            ThreadSummary failed = history.listThreads("ws_1", null, 10).items().getFirst();
            assertEquals(TurnState.FAILED, failed.latestTurnStatus());
            assertFalse(failed.latestTurnSeen());
            ThreadSummary failedSeen = history.markThreadSeen("thr_1", failed.revision());
            assertEquals(TurnState.FAILED, failedSeen.latestTurnStatus());
            assertTrue(failedSeen.latestTurnSeen());
        }
    }

    /** ConversationRepository close 不拥有数据库资源，重复关闭幂等且所有后续入口稳定拒绝。 */
    @Test
    void closesStoreIdempotentlyWithoutClosingDatabase() throws Exception {
        try (TestDatabase database = database("store-close")) {
            MybatisConversationRepository store = initialized(database);
            store.close();
            store.close();
            StorageException closed = assertThrows(StorageException.class, () -> store.readThread("thr_1"));
            assertEquals(StorageException.Code.CLOSED, closed.code());
            assertEquals(1, database.history(database.agentStore()).listThreads("ws_1", null, 10).items().size());
        }
    }

    /**
     * 统一构造取消后的 Tool batch 请求，使正反向用例只改变事实闭集或 CAS token。
     */
    private static ConversationRepository.CancellationToolBatchCommit cancellationToolBatch(
            List<ConversationRepository.Fact> facts, long expectedTurnMutationVersion,
            java.time.Instant occurredAt) {
        return new ConversationRepository.CancellationToolBatchCommit(
                commitRequest(
                        "thr_1", "turn_1", TurnState.RUNNING, facts,
                        expectedTurnMutationVersion, occurredAt));
    }

    /** 测试提交也必须显式携带完整 READY 游标，避免重新引入生产 null 兼容构造。 */
    private static ConversationRepository.CommitRequest commitRequest(
            String threadId, String turnId, TurnState state, List<ConversationRepository.Fact> facts,
            long expectedTurnMutationVersion, java.time.Instant occurredAt) {
        return new ConversationRepository.CommitRequest(threadId, turnId, state, facts,
                expectedTurnMutationVersion, occurredAt, execution("cfg_1"));
    }

    /** 特定恢复测试可显式提供 TOOLS 等非 READY 游标。 */
    private static ConversationRepository.CommitRequest commitRequest(
            String threadId, String turnId, TurnState state, List<ConversationRepository.Fact> facts,
            long expectedTurnMutationVersion, Instant occurredAt, TurnExecutionState executionState) {
        return new ConversationRepository.CommitRequest(threadId, turnId, state, facts,
                expectedTurnMutationVersion, occurredAt, executionState);
    }

    /** ProviderPending 夹具必须在同一事务写入匹配的 UNKNOWN 请求事实，恢复只推进游标而不补猜 intent。 */
    private static ConversationRepository.UsageFact providerIntent(TurnExecutionState.ProviderPending pending) {
        ConversationRepository.UsagePurpose purpose = pending.purpose()
                == TurnExecutionState.ProviderPurpose.SUMMARY
                ? ConversationRepository.UsagePurpose.SUMMARY
                : ConversationRepository.UsagePurpose.ASSISTANT;
        return new ConversationRepository.UsageFact(pending.requestId(), null,
                Math.max(1, pending.common().modelRound() + 1), pending.common().nextProviderOrdinal(),
                purpose, ConversationRepository.UsageCertainty.UNKNOWN, pending.profile());
    }

    /** Workspace 由专属 Repository 注册，conversation 只引用已经存在的 workspaceId。 */
    private static MybatisConversationRepository initialized(TestDatabase database) {
        MybatisConversationRepository store = database.agentStore();
        database.history(store).register(new Workspace.Registration("ws_1", database.path().getParent(),
                "workspace", Workspace.Trust.TRUSTED, START));
        store.createThread(new ConversationRepository.ThreadDefinition(
                "thr_1", "ws_1", "thread", preferences("provider_1", "model_1"), START));
        return store;
    }

    /** admission helper 固定完整 USER blocks 与 revision 0。 */
    private static ConversationRepository.AdmissionReceipt admit(MybatisConversationRepository store) {
        return store.admit(new ConversationRepository.TurnAdmission(
                "thr_1", "turn_1",
                "item_user", new ModelMessage(ModelRole.USER,
                List.of(new TextContent("hello"))), List.of(), 0, START, execution("cfg_1")));
    }

    /** 构造独立排队输入，显式固定身份与时间以验证跨重启稳定排序。 */
    private static ConversationRepository.PendingInput pending(
            String inputId, ConversationRepository.InputKind kind, String text, Instant createdAt) {
        return new ConversationRepository.PendingInput(
                inputId, "thr_1", "turn_1", kind, content(text), createdAt);
    }

    /** SQLite 队列夹具使用正式结构化内容，不再提供字符串兼容入口。 */
    private static UserContent content(String text) {
        return new UserContent(List.of(new TextContent(text)));
    }

    /** 仅提取持久 TOOL 结果，便于恢复测试同时验证调用配对、内容和错误标志。 */
    private static List<ToolResultContent> toolResults(ConversationRepository.ThreadSnapshot snapshot) {
        return snapshot.messages().stream()
                .filter(message -> message.message().role() == ModelRole.TOOL)
                .flatMap(message -> message.message().content().stream())
                .filter(ToolResultContent.class::isInstance)
                .map(ToolResultContent.class::cast)
                .toList();
    }

    /** 提取单文本消息；本回归刻意拒绝结构化或空 blocks，避免排序断言掩盖内容漂移。 */
    private static String text(ModelMessage message) {
        assertEquals(1, message.content().size());
        return assertInstanceOf(TextContent.class, message.content().getFirst()).text();
    }

    /** 构造单文本成员参数，避免持久化夹具重新暴露弱类型 Map。 */
    private static JsonObject textArguments(String name, String value) {
        return JsonObjects.builder().putText(name, value).build();
    }

    /** 测试只构造不含 raw 内容的最小展示事实，避免 fixture 绕过生产安全投影。 */
    private static ToolPresentation presentation(ToolPresentation.Status status) {
        return new ToolPresentation(ToolPresentation.Kind.MCP, "fixture", status,
                null, null, List.of(), null, null, null, null, null, null, false, null);
    }

    /** 构造字段完整且来源一致的 Checkpoint，供重启与并发事务测试共享同一持久契约。 */
    private static CheckpointStore.ContextCheckpoint checkpointFixture(
            String checkpointId, long sourceRevision, String fact) {
        SummaryDocument summary = new SummaryDocument(List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                List.of(new SummaryDocument.Fact(fact, 1)), List.of(), List.of(), List.of());
        return new CheckpointStore.ContextCheckpoint(checkpointId, "thr_1", 1, 1, sourceRevision,
                java.util.Optional.empty(), summary, 123, "3".repeat(64), ContextCompactionEvent.STRATEGY_VERSION,
                io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointUsage.none(),
                START.plusSeconds(10));
    }

    /** 只替换 Provider prompt 所用 checkpoint 引用，保持其余冻结执行基线完全不变。 */
    private static TurnExecutionState.Common executionWithPromptCheckpoint(
            String generation, String checkpointId) {
        TurnExecutionState.Common source = execution(generation).common();
        return new TurnExecutionState.Common(source.modelRound(), source.usedToolCalls(),
                source.nextProviderOrdinal(), checkpointId,
                source.activeSkills(), source.deadlineAt(), source.origin());
    }

}
