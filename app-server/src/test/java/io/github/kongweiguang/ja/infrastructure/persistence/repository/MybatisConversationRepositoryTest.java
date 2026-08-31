// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.conversation.domain.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.TurnChangeSet;

import io.github.kongweiguang.ja.conversation.domain.model.ToolResultContent;

import io.github.kongweiguang.ja.conversation.domain.model.ToolCallContent;

import io.github.kongweiguang.ja.conversation.domain.model.TextContent;

import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;

import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;

import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolState;

import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;

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
import io.github.kongweiguang.ja.infrastructure.persistence.recovery.StartupRecoveryService;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.runtime;

import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointStore;
import io.github.kongweiguang.ja.conversation.application.context.ContextMessage;
import io.github.kongweiguang.ja.conversation.application.context.ContextPolicy;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryDocument;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 真实临时 SQLite 覆盖 V1 schema、事务原子性、恢复、CAS 与完整 blocks。 */
final class MybatisConversationRepositoryTest extends PersistenceTestSupport {
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
                    "thr_1", "turn_1", runtime("provider_1", "model_1", "cfg_1"),
                    "item_user_duplicate", new ModelMessage(ModelRole.USER,
                    List.of(new TextContent("duplicate"))), List.of(), persisted.revision(),
                    START.plusSeconds(1))));
            ConversationRepository.ThreadSnapshot afterDuplicate = restored.readThread("thr_1").orElseThrow();
            assertEquals("hello", afterDuplicate.title());
            assertEquals(1, afterDuplicate.revision());
            assertEquals(1, afterDuplicate.turns().size());
            assertEquals(1, afterDuplicate.messages().size());

            ConversationRepository.AdmissionReceipt later = restored.admit(
                    new ConversationRepository.TurnAdmission(
                            "thr_1", "turn_2", runtime("provider_1", "model_1", "cfg_1"),
                            "item_user_2", new ModelMessage(ModelRole.USER,
                            List.of(new TextContent("later"))), List.of(), afterDuplicate.revision(),
                            START.plusSeconds(2)));
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

    /** 全新 schema 分别保存 Thread 下一轮偏好和 Turn 独立运行快照。 */
    @Test
    void persistsNullableSelectorsAndGenerationInFinalColumns() throws Exception {
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
                assertEquals("provider_1", turn.providerId());
                assertEquals("model_1", turn.modelId());
                assertEquals("cfg_1", turn.configGeneration());
            }
            ConversationRepository.TurnSnapshot restored = store.findTurn("thr_1", "turn_1").orElseThrow();
            assertEquals("provider_1", restored.runtime().providerId());
            assertEquals("model_1", restored.runtime().modelId());
            assertEquals("cfg_1", restored.runtime().configGeneration());
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
            store.admit(new ConversationRepository.TurnAdmission("thr_1", "turn_2", runtime("provider_1", "model_1", "cfg_1"),
                    "item_user_2", new ModelMessage(ModelRole.USER,
                    List.of(new TextContent("later"))), List.of(), claim.threadRevision(),
                    START.plusSeconds(1)));

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

            assertThrows(StorageException.class, () -> store.commit(new ConversationRepository.CommitRequest(
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
            ConversationRepository.CommitReceipt running = store.commit(new ConversationRepository.CommitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING,
                    List.of(
                            new ConversationRepository.AssistantFact(
                                    "item_assistant_1",
                                     new ModelMessage(ModelRole.ASSISTANT,
                                             List.of(new ToolCallContent(
                                                     "call_1", "shell", textArguments("command", "echo ok")))),
                                    "", null, 1),
                            new ConversationRepository.UsageFact(new ModelUsage(1, 1, 2), 1),
                             new ConversationRepository.ToolPreparedFact(
                                     "call_1", "shell", textArguments("command", "echo ok"), 0,
                                     ToolSideEffect.EXTERNAL, presentation(ToolPresentation.Status.PENDING))),
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
            assertThrows(StorageException.class, () -> store.commit(new ConversationRepository.CommitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(),
                    claim.turnMutationVersion(), START.plusSeconds(3))));
            assertThrows(IllegalArgumentException.class, () -> cancellationToolBatch(
                    List.of(new ConversationRepository.UsageFact(new ModelUsage(1, 1, 2), 1)),
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

    /** stale Thread revision、终态和缺失 Turn 均在 claim 事务入口明确拒绝。 */
    @Test
    void rejectsStaleMissingAndTerminalCancellationClaims() throws Exception {
        try (TestDatabase database = database("cancel-reject")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            ConversationRepository.CommitReceipt running = store.commit(new ConversationRepository.CommitRequest(
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
                    rollbackOwner);
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
            ConversationRepository.CommitReceipt running = store.commit(new ConversationRepository.CommitRequest("thr_1", "turn_1",
                    TurnState.RUNNING, List.of(new ConversationRepository.AssistantFact(
                            "item_a", assistant, "checking", null, 1),
                    new ConversationRepository.ToolPreparedFact(
                             "call_1", "read_file", textArguments("path", "README.md"), 0,
                             ToolSideEffect.READ_ONLY, presentation(ToolPresentation.Status.PENDING))),
                    admission.turnMutationVersion(), START.plusSeconds(1)));
            ModelMessage toolResult = new ModelMessage(ModelRole.TOOL,
                    List.of(new ToolResultContent("call_1", "ok", false)));
            store.commit(new ConversationRepository.CommitRequest("thr_1", "turn_1", TurnState.RUNNING,
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
            ConversationRepository.CommitReceipt running = store.commit(new ConversationRepository.CommitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(),
                    admission.turnMutationVersion(), START.plusSeconds(1)));
            ModelUsage usage = new ModelUsage(10, 4, 14);
            ConversationRepository.TerminalCommit terminal = new ConversationRepository.TerminalCommit("thr_1", "turn_1",
                    TurnState.COMPLETED, "done", null, null, "item_final",
                    new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("done"))),
                    List.of(new ConversationRepository.UsageFact(usage, 1), new ConversationRepository.UsageFact(usage, 1)),
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
            ConversationRepository.CommitReceipt running = store.commit(new ConversationRepository.CommitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING,
                    List.of(new ConversationRepository.ToolPreparedFact(
                            "call_pending", "shell", textArguments("command", "echo pending"), 0,
                            ToolSideEffect.EXTERNAL, presentation(ToolPresentation.Status.PENDING))),
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
                    null, null, List.of(), running.turnMutationVersion(), START.plusSeconds(3));
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
                    "0".repeat(64), "ja-context-v3",
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
                    "0".repeat(64), "ja-context-v3",
                    io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointUsage.none(), START.plusSeconds(4));
            CheckpointStore.CommittedCheckpoint reused = checkpoints.commit(
                    new CheckpointStore.CommitRequest("thr_1", 1, stale));
            assertFalse(reused.newlyCommitted());
            assertEquals("cp_1", reused.checkpoint().checkpointId());
            long revision = store.commit(new ConversationRepository.CommitRequest("thr_1", "turn_1",
                    TurnState.RUNNING, List.of(), 0, START.plusSeconds(5))).threadRevision();
            CheckpointStore.ContextCheckpoint second = new CheckpointStore.ContextCheckpoint(
                    "cp_2", "thr_1", 2, 3, revision, java.util.Optional.empty(), summary, 100,
                    "0".repeat(64), "ja-context-v3",
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

    /** 启动恢复把 active Turn 与 RUNNING Tool 同事务收敛，并只推进一次 Thread revision。 */
    @Test
    void recoversActiveTurnAndRunningToolAtomically() throws Exception {
        try (TestDatabase database = database("recovery")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = admit(store);
            ConversationRepository.CommitReceipt running = store.commit(new ConversationRepository.CommitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING,
                    List.of(new ConversationRepository.ToolPreparedFact(
                                     "call_1", "write_file", textArguments("path", "a"), 0,
                                     ToolSideEffect.EXTERNAL, presentation(ToolPresentation.Status.PENDING)),
                            new ConversationRepository.ToolStartedFact("call_1"),
                            new ConversationRepository.ToolPreparedFact(
                                     "call_2", "shell", textArguments("command", "x"), 1,
                                     ToolSideEffect.EXTERNAL, presentation(ToolPresentation.Status.PENDING)),
                            new ConversationRepository.ToolPreparedFact(
                                     "call_3", "read_file", textArguments("path", "a"), 2,
                                     ToolSideEffect.READ_ONLY, presentation(ToolPresentation.Status.PENDING)),
                            new ConversationRepository.ToolPreparedFact(
                                     "call_4", "write_file", textArguments("path", "b"), 3,
                                     ToolSideEffect.EXTERNAL, presentation(ToolPresentation.Status.PENDING)),
                            new ConversationRepository.ToolResultFact("call_4", ToolState.SUCCEEDED, "ok", false,
                                    presentation(ToolPresentation.Status.SUCCESS), "")),
                    admission.turnMutationVersion(),
                    START.plusSeconds(1)));
            StartupRecoveryService.RecoveryResult result = database.recovery().recover();
            assertEquals(1, result.turns());
            assertEquals(2, result.tools());
            assertEquals(1, result.changeSets());
            ConversationRepository.TurnSnapshot turn = store.findTurn("thr_1", "turn_1").orElseThrow();
            assertEquals(TurnState.CANCELLED, turn.state());
            assertEquals(running.threadRevision() + 1, turn.threadRevision());
            assertEquals(running.turnMutationVersion() + 1, turn.turnMutationVersion());
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                AgentMapper mapper = session.getMapper(AgentMapper.class);
                assertEquals("UNKNOWN", mapper.selectTool(new PersistenceRecords.ToolKey("turn_1", "call_1")).state());
                assertEquals("UNKNOWN", mapper.selectTool(new PersistenceRecords.ToolKey("turn_1", "call_2")).state());
                assertEquals("PREPARED", mapper.selectTool(new PersistenceRecords.ToolKey("turn_1", "call_3")).state());
                assertEquals("SUCCEEDED", mapper.selectTool(new PersistenceRecords.ToolKey("turn_1", "call_4")).state());
            }
            io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot history =
                    database.history(store).readThread("thr_1", null, 100).orElseThrow();
            TurnChangeSet recoveredChanges = history.turns().getFirst().changeSet();
            assertNotNull(recoveredChanges);
            assertEquals(TurnChangeSet.State.UNAVAILABLE, recoveredChanges.state());
            assertEquals("capture_failed", recoveredChanges.reason());
            assertEquals(0, recoveredChanges.stats().files());
            assertTrue(history.items().stream().filter(io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot.ToolItem.class::isInstance)
                    .map(io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot.ToolItem.class::cast)
                    .filter(item -> item.callId().equals("call_1") || item.callId().equals("call_2"))
                    .allMatch(item -> item.presentation().status() == ToolPresentation.Status.ERROR));
            StartupRecoveryService.RecoveryResult repeated = database.recovery().recover();
            assertEquals(0, repeated.threads());
            assertEquals(0, repeated.turns());
            assertEquals(0, repeated.tools());
            assertEquals(0, repeated.changeSets());
            assertEquals(turn, store.findTurn("thr_1", "turn_1").orElseThrow());
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

    /** 第二事务遇到 IMMEDIATE writer lock 时在 bounded busy timeout 内失败，首事务可回滚。 */
    @Test
    void boundsConcurrentBusyWriterAndPreservesRollback() throws Exception {
        try (TestDatabase database = database("busy", Duration.ofMillis(100))) {
            MybatisConversationRepository store = initialized(database);
            admit(store);
            try (org.apache.ibatis.session.SqlSession blocker = database.sessions().openSession()) {
                blocker.getMapper(HistoryMapper.class).advanceThreadFact(new PersistenceRecords.ThreadRevision(
                        "thr_1", START.plusSeconds(1).toString()));
                assertThrows(StorageException.class, () -> store.commit(new ConversationRepository.CommitRequest(
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
            ConversationRepository.CommitReceipt runningA = store.commit(new ConversationRepository.CommitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING, List.of(),
                    admittedA.turnMutationVersion(), START.plusSeconds(1)));
            ConversationRepository.AdmissionReceipt admittedB = store.admit(new ConversationRepository.TurnAdmission(
                    "thr_1", "turn_2", runtime("provider_1", "model_1", "cfg_1"), "item_user_2",
                    new ModelMessage(ModelRole.USER,
                            List.of(new TextContent("next"))), List.of(),
                    runningA.threadRevision(), START.plusSeconds(2)));
            assertNull(admittedB.provisionalTitle());
            assertEquals("hello", store.readThread("thr_1").orElseThrow().title());
            ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT, List.of(
                    new TextContent("checking"),
                    new ToolCallContent("call_1", "read_file", textArguments("path", "README.md"))));
            ConversationRepository.CommitReceipt modelA = store.commit(new ConversationRepository.CommitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING,
                    List.of(new ConversationRepository.AssistantFact(
                                    "item_assistant_1", assistant, "checking", null, 1),
                            new ConversationRepository.ToolPreparedFact("call_1", "read_file",
                                    textArguments("path", "README.md"), 0, ToolSideEffect.READ_ONLY,
                                    presentation(ToolPresentation.Status.PENDING))),
                    runningA.turnMutationVersion(), START.plusSeconds(3)));
            assertThrows(StorageException.class, () -> store.commit(new ConversationRepository.CommitRequest(
                    "thr_1", "turn_1", TurnState.RUNNING,
                    List.of(new ConversationRepository.ToolStartedFact("call_1")), runningA.turnMutationVersion(),
                    START.plusSeconds(4))));
            ConversationRepository.CommitReceipt toolA = store.commit(new ConversationRepository.CommitRequest(
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
                new ConversationRepository.CommitRequest(
                        "thr_1", "turn_1", TurnState.RUNNING, facts,
                        expectedTurnMutationVersion, occurredAt));
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
                "thr_1", "turn_1", runtime("provider_1", "model_1", "cfg_1"),
                "item_user", new ModelMessage(ModelRole.USER,
                List.of(new TextContent("hello"))), List.of(), 0, START));
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
                java.util.Optional.empty(), summary, 123, "3".repeat(64), "ja-context-v3",
                io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointUsage.none(),
                START.plusSeconds(10));
    }

}
