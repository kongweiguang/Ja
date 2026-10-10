// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.context.CheckpointUsage;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.application.context.checkpoint.StoreBackedCheckpointStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.model.JaMessageIds;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.ContextCompactionEvent;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.application.gateway.JaAdmissionTransactionParticipant;
import io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.task.MybatisTaskRepository;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用真实 SQLite 验证重答准入、当前路径剪切与原始用量事实的恢复边界。 */
final class MybatisReaskPersistenceTest extends PersistenceTestSupport {
    private static final String THREAD_ID = "thr_reask_sql";
    private static final String WORKSPACE_ID = "ws_reask_sql";
    private static final String SOURCE_TURN_ID = "turn_reask_source";
    private static final String SOURCE_MESSAGE_ID = "message_reask_source";
    private static final String SAME_TURN_MESSAGE_ID = "message_reask_same_turn";
    private static final ThreadPreferences PREFERENCES =
            ConversationTestFixtures.preferences("provider_reask", "model_reask");

    /** 只允许当前路径最后的失败 USER 问题重答，较早消息和同 Turn 多问题都必须拒绝且不剪历史。 */
    @Test
    void rejectsNonLastAndEarlierSameTurnUserMessages() throws Exception {
        try (TestDatabase database = database("reask-source-eligibility")) {
            AgentStore store = createThread(database);
            AgentExecutionRequest source = request(SOURCE_TURN_ID, SOURCE_MESSAGE_ID,
                    "first question", "turn/start", START.plusSeconds(1));
            try (AgentStoreProjectionFixture run = AgentStoreProjectionFixture.open(store, database.mapper(),
                    source, admission(source, "turn/start", null, 0), PREFERENCES, 0)) {
                StorePayloadCodec codec = new StorePayloadCodec(database.mapper());
                AgentStore.RecordWrite laterUser = codec.encodeUser(
                        run.recordId("agent.user.message", "second-user"), SAME_TURN_MESSAGE_ID,
                        new UserContent(List.of(new TextContent("second question"))), START.plusSeconds(2));
                commitFailure(run, List.of(laterUser), START.plusSeconds(3));
            }

            long revision = threadRevision(store);
            assertReaskRejected(database, store, SOURCE_MESSAGE_ID, "turn_reask_earlier_source", revision);
            assertReaskRejected(database, store, SAME_TURN_MESSAGE_ID, "turn_reask_same_turn_source", revision);

            ThreadSnapshot snapshot = database.history().readThread(THREAD_ID, null, 20).orElseThrow();
            assertEquals(revision, snapshot.thread().revision());
            assertEquals(List.of(SOURCE_TURN_ID), snapshot.turns().stream().map(ThreadSnapshot.Turn::turnId).toList());
            assertEquals(2L, snapshot.items().stream().filter(ThreadSnapshot.UserInputItem.class::isInstance).count());
            store.close();
        }
    }

    /** 使用历史返回的真实来源身份重答；路径剪切和准入同事务，回滚、审计及重开不改变消息身份。 */
    @Test
    void cutsCurrentPathAtomicallyAndRetainsOffPathUsageAfterRestart() throws Exception {
        String databaseName = "reask-atomic-history";
        String reaskTurnId = "turn_reask_retry";
        String usageRecordId;
        AgentStore.ExecutionKey sourceTurn = new AgentStore.ExecutionKey(
                new AgentStore.ConversationKey("ja.local", THREAD_ID, "user_local"), SOURCE_TURN_ID);

        try (TestDatabase database = database(databaseName)) {
            AgentStore store = createThread(database);
            AgentExecutionRequest source = request(SOURCE_TURN_ID, SOURCE_MESSAGE_ID,
                    "original question", "turn/start", START.plusSeconds(1));
            try (AgentStoreProjectionFixture run = AgentStoreProjectionFixture.open(store, database.mapper(),
                    source, admission(source, "turn/start", null, 0), PREFERENCES, 0)) {
                Instant failedAt = START.plusSeconds(2);
                String modelRequestId = "modelreq_reask_source";
                ExecutionFacts.Usage unknownUsage = new ExecutionFacts.Usage(modelRequestId, null, 1, 1,
                        ExecutionFacts.UsagePurpose.ASSISTANT, ExecutionFacts.UsageCertainty.UNKNOWN,
                        modelProfile(), null);
                AgentStore.RecordWrite usage = run.record("agent.usage", modelRequestId, unknownUsage, failedAt);
                usageRecordId = usage.recordId();
                commitFailure(run, List.of(usage), failedAt);
            }

            long expectedRevision = threadRevision(store);
            ThreadSnapshot sourceHistory = database.history().readThread(THREAD_ID, null, 20).orElseThrow();
            String publicSourceId = sourceHistory.turns().getFirst().sourceMessageId();
            assertEquals(JaMessageIds.toItemId(SOURCE_MESSAGE_ID), publicSourceId);
            assertTrue(sourceHistory.items().stream().filter(ThreadSnapshot.UserInputItem.class::isInstance)
                    .anyMatch(item -> item.itemId().equals(publicSourceId)));
            AgentExecutionRequest reask = request(reaskTurnId, "message_reask_retry", "revised question",
                    "turn/reask", START.plusSeconds(3));
            AdmissionFacts facts = admission(reask, "turn/reask", publicSourceId, expectedRevision);
            assertThrows(RuntimeException.class, () -> AgentStoreProjectionFixture.open(store, database.mapper(),
                    reask, facts, PREFERENCES, expectedRevision, failAfterProjection()));

            ThreadSnapshot afterRollback = database.history().readThread(THREAD_ID, null, 20).orElseThrow();
            assertEquals(expectedRevision, afterRollback.thread().revision());
            assertEquals(List.of(SOURCE_TURN_ID),
                    afterRollback.turns().stream().map(ThreadSnapshot.Turn::turnId).toList());
            assertTrue(store.loadExecution(new AgentStore.ExecutionKey(sourceTurn.conversation(), reaskTurnId))
                    .isEmpty());
            assertTrue(store.readRecord(sourceTurn, usageRecordId).isPresent());

            try (AgentStoreProjectionFixture ignored = AgentStoreProjectionFixture.open(store, database.mapper(),
                    reask, facts, PREFERENCES, expectedRevision)) {
                // 准入提交本身必须完成切路；后续执行是否启动不影响该原子事实。
            }
            ThreadSnapshot committed = database.history().readThread(THREAD_ID, null, 20).orElseThrow();
            assertEquals(List.of(reaskTurnId), committed.turns().stream().map(ThreadSnapshot.Turn::turnId).toList());
            assertEquals(reaskTurnId, committed.thread().latestTurnId());
            assertEquals(reaskTurnId, database.history().listThreads(WORKSPACE_ID, null, 20)
                    .items().getFirst().latestTurnId());
            assertEquals(reaskTurnId, database.history().discoverThreads(
                    new ThreadDiscovery.Query("all", null, null, 20, WORKSPACE_ID))
                    .items().getFirst().latestTurnId());
            assertTrue(committed.items().stream().filter(ThreadSnapshot.UserInputItem.class::isInstance)
                    .map(ThreadSnapshot.UserInputItem.class::cast)
                    .anyMatch(item -> item.content().text().equals("revised question")));
            assertFalse(committed.items().stream().filter(ThreadSnapshot.UserInputItem.class::isInstance)
                    .map(ThreadSnapshot.UserInputItem.class::cast)
                    .anyMatch(item -> item.content().text().equals("original question")));
            assertTrue(store.readRecord(sourceTurn, usageRecordId).isPresent());
            assertEquals(1, database.history().readThreadUsageSummary(THREAD_ID).orElseThrow().requestCount());
            store.close();
        }

        try (TestDatabase reopened = database(databaseName)) {
            ThreadSnapshot restored = reopened.history().readThread(THREAD_ID, null, 20).orElseThrow();
            assertEquals(List.of(reaskTurnId), restored.turns().stream().map(ThreadSnapshot.Turn::turnId).toList());
            assertEquals(reaskTurnId, reopened.history().listThreads(WORKSPACE_ID, null, 20)
                    .items().getFirst().latestTurnId());
            assertEquals(1, reopened.history().readThreadUsageSummary(THREAD_ID).orElseThrow().requestCount());
            assertTrue(reopened.jaAgentStore().readRecord(sourceTurn, usageRecordId).isPresent());
        }
    }

    /**
     * 重答后旧分支摘要仍可能按序号落在新路径区间内；分叉必须重建当前原始路径，不能把语义失效的摘要带进 seed。
     * @author kongweiguang
     */
    @Test
    void forkAfterReaskRebuildsRawCurrentPathInsteadOfUsingHiddenCheckpoint() throws Exception {
        try (TestDatabase database = database("reask-fork-hidden-checkpoint")) {
            AgentStore store = createThread(database);
            completeTurn(database, store, "turn_reask_prefix", "msg_reask_prefix_user",
                    "REASK_PREFIX_USER", "REASK_PREFIX_REPLY", START.plusSeconds(1));
            completeTurn(database, store, "turn_reask_branch_a", "msg_reask_branch_a_user",
                    "REASK_BRANCH_A_USER", "REASK_BRANCH_A_REPLY", START.plusSeconds(10));

            AgentExecutionRequest oldPath = request("turn_reask_branch_b_old", "msg_reask_branch_b_old_user",
                    "REASK_OLD_PATH_USER", "turn/start", START.plusSeconds(20));
            long oldPathRevision = threadRevision(store);
            try (AgentStoreProjectionFixture run = AgentStoreProjectionFixture.open(store, database.mapper(),
                    oldPath, admission(oldPath, "turn/start", null, oldPathRevision), PREFERENCES, oldPathRevision)) {
                long sourceRevision = run.conversationSnapshot().revision();
                SummaryDocument poisoned = new SummaryDocument(List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(),
                        List.of(new SummaryDocument.Fact("REASK_OFF_PATH_CHECKPOINT_POISON", 5)),
                        List.of(), List.of(), List.of());
                CheckpointStore.ContextCheckpoint checkpoint = new CheckpointStore.ContextCheckpoint(
                        "checkpoint_reask_hidden_branch", THREAD_ID, 5, 6, sourceRevision, Optional.empty(),
                        poisoned, 64, "c".repeat(64), ContextCompactionEvent.STRATEGY_VERSION,
                        CheckpointUsage.none(), START.plusSeconds(21));
                run.commitCheckpoint(readyCursor(), checkpoint, 128, START.plusSeconds(21));
                commitFailure(run, List.of(), START.plusSeconds(22));
            }

            ThreadSnapshot failedPath = database.history().readThread(THREAD_ID, null, 32).orElseThrow();
            String sourceMessageId = failedPath.items().stream()
                    .filter(ThreadSnapshot.UserInputItem.class::isInstance)
                    .map(ThreadSnapshot.UserInputItem.class::cast)
                    .filter(item -> item.content().text().equals("REASK_OLD_PATH_USER"))
                    .map(ThreadSnapshot.UserInputItem::itemId).findFirst().orElseThrow();
            long reaskRevision = threadRevision(store);
            AgentExecutionRequest reask = request("turn_reask_branch_b_current", "msg_reask_branch_b_current_user",
                    "REASK_CURRENT_PATH_USER", "turn/reask", START.plusSeconds(30));
            try (AgentStoreProjectionFixture run = AgentStoreProjectionFixture.open(store, database.mapper(), reask,
                    admission(reask, "turn/reask", sourceMessageId, reaskRevision), PREFERENCES, reaskRevision)) {
                completeReply(run, "REASK_CURRENT_PATH_REPLY", START.plusSeconds(32));
            }

            CheckpointStore.ContextCheckpoint retained = new StoreBackedCheckpointStore(store,
                    new StorePayloadCodec(database.mapper())).read(store.loadConversation(
                            new AgentStore.ConversationKey("ja.local", THREAD_ID, "user_local")).orElseThrow())
                    .checkpoint().orElseThrow();
            assertEquals("checkpoint_reask_hidden_branch", retained.checkpointId(),
                    "the stale summary remains durable, so the fork must establish path eligibility itself");
            MybatisTaskRepository tasks = database.taskRepository();
            MybatisHistoryService history = database.history();
            history.bindThreadForkSnapshots(tasks);
            ThreadSnapshot currentPath = history.readThread(THREAD_ID, null, 32).orElseThrow();
            String selectedReplyId = finalReplyItemId(currentPath, "turn_reask_branch_b_current");
            ThreadUseCase.ThreadForkResult fork = history.forkThread(new ThreadUseCase.ThreadForkRequest(
                    THREAD_ID, selectedReplyId, currentPath.thread().revision(), "op_" + "9".repeat(32)));

            InitialContextSeed seed = history.findForFirstTurn(fork.thread().threadId()).orElseThrow();
            String seedText = seed.messages().stream().flatMap(message -> message.blocks().stream())
                    .filter(ContextMessage.TextBlock.class::isInstance)
                    .map(ContextMessage.TextBlock.class::cast).map(ContextMessage.TextBlock::value)
                    .collect(Collectors.joining("\n"));
            assertTrue(seedText.contains("REASK_PREFIX_USER"));
            assertTrue(seedText.contains("REASK_PREFIX_REPLY"));
            assertTrue(seedText.contains("REASK_BRANCH_A_USER"));
            assertTrue(seedText.contains("REASK_BRANCH_A_REPLY"));
            assertTrue(seedText.contains("REASK_CURRENT_PATH_USER"));
            assertTrue(seedText.contains("REASK_CURRENT_PATH_REPLY"));
            assertFalse(seedText.contains("REASK_OLD_PATH_USER"));
            assertFalse(seedText.contains("REASK_BRANCH_B_OLD"));
            assertFalse(seedText.contains("REASK_OFF_PATH_CHECKPOINT_POISON"));
            assertTrue(seed.summary().isEmpty(), "the fork seed is rebuilt from selected raw messages");
            store.close();
        }
    }

    /** 普通成功 Turn 经真实 Core USER admission 和终态投影建立可回读的上下文前缀。 @author kongweiguang */
    private static void completeTurn(TestDatabase database, AgentStore store, String turnId,
                                     String messageId, String userText, String reply, Instant acceptedAt) {
        long revision = threadRevision(store);
        AgentExecutionRequest request = request(turnId, messageId, userText, "turn/start", acceptedAt);
        try (AgentStoreProjectionFixture run = AgentStoreProjectionFixture.open(store, database.mapper(), request,
                admission(request, "turn/start", null, revision), PREFERENCES, revision)) {
            completeReply(run, reply, acceptedAt.plusSeconds(2));
        }
    }

    /** 成功终态同时绑定助手事实、已知用量与确定性 ChangeSet，避免测试伪造可分叉回复。 @author kongweiguang */
    private static void completeReply(AgentStoreProjectionFixture run, String reply, Instant occurredAt) {
        String turnId = run.execution().turnId();
        String requestId = "modelreq_" + turnId;
        TurnExecutionState.Ready pendingCursor = readyCursor();
        run.commitRecords(TurnState.RUNNING, pendingCursor, List.of(run.record("agent.usage", "usage_pending",
                new ExecutionFacts.Usage(requestId, null, 1, 1, ExecutionFacts.UsagePurpose.ASSISTANT,
                        ExecutionFacts.UsageCertainty.UNKNOWN, modelProfile(), null), occurredAt)), List.of(), occurredAt);
        ExecutionFacts.AssistantMessage assistantFact = new ExecutionFacts.AssistantMessage(
                "message_" + turnId, new io.github.kongweiguang.ja.agent.domain.model.ModelMessage(
                        io.github.kongweiguang.ja.agent.domain.model.ModelRole.ASSISTANT,
                        List.of(new TextContent(reply))), reply, null, 1);
        AgentStore.RecordWrite assistant = run.record("agent.assistant", "assistant_final", assistantFact, occurredAt);
        AgentStore.RecordWrite usage = run.record("agent.usage", "usage_final",
                new ExecutionFacts.Usage(requestId, new io.github.kongweiguang.ja.agent.domain.model.ModelUsage(8, 3, 11),
                        1, 1, ExecutionFacts.UsagePurpose.ASSISTANT, ExecutionFacts.UsageCertainty.KNOWN,
                        modelProfile(), null), occurredAt);
        TurnExecutionState.Ready completeCursor = new TurnExecutionState.Ready(
                new TurnExecutionState.Common(1, 0, 2, "prompt_reask_test", List.of()),
                TurnExecutionState.Next.COMPLETION, null);
        run.commitRecords(TurnState.COMPLETED, completeCursor,
                List.of(assistant, usage, run.knownEmptyChangeSet(occurredAt)),
                List.of(new ExecutionEvent.Terminal(TurnState.COMPLETED, reply, null, null,
                        assistant.recordId(), usage.recordId())), occurredAt);
    }

    /** 只用 Thread snapshot 返回的真实 FINAL_ANSWER Item 选择分叉边界，不以 Turn 末尾代替回复。 @author kongweiguang */
    private static String finalReplyItemId(ThreadSnapshot snapshot, String turnId) {
        return snapshot.items().stream().filter(ThreadSnapshot.TextItem.class::isInstance)
                .map(ThreadSnapshot.TextItem.class::cast)
                .filter(item -> item.kind() == ThreadSnapshot.TextKind.FINAL_ANSWER)
                .filter(item -> turnId.equals(item.turnId())).map(ThreadSnapshot.TextItem::itemId)
                .reduce((left, right) -> right).orElseThrow();
    }

    /** 复用成功结束游标，令压缩/reask测试按与 Agent Loop 相同的阶段边界提交 checkpoint。 @author kongweiguang */
    private static TurnExecutionState.Ready readyCursor() {
        return new TurnExecutionState.Ready(new TurnExecutionState.Common(0, 0, 1,
                "prompt_reask_test", List.of()), TurnExecutionState.Next.ASSISTANT, null);
    }

    /** 在完整 SQLite history 中创建 Thread；JaHistory 已初始化 Store conversation，不再手工造第二个锚点。 */
    private AgentStore createThread(TestDatabase database) {
        database.history().register(new Workspace.Registration(WORKSPACE_ID, database.path().getParent(), "reask", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, START));
        database.history().createThread(new ThreadSummary.Creation(
                THREAD_ID, WORKSPACE_ID, "reask", PREFERENCES, START));
        return database.jaAgentStore();
    }

    /** 固定完整身份、输入与 Core operation，确保存储测试经过真实 AgentStore admission。 */
    private static AgentExecutionRequest request(String turnId, String messageId, String text,
                                                String operationType, Instant acceptedAt) {
        AgentIdentity identity = new AgentIdentity("ja.local", THREAD_ID, turnId, "user_local");
        return new AgentExecutionRequest(identity, AgentExecutionRequest.AdmissionKind.USER_INPUT,
                Optional.of(new UserContent(List.of(new TextContent(text)))), messageId, Optional.empty(),
                new AgentStore.OperationIdentity("operation_" + turnId, operationType, "a".repeat(64), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")),
                Map.of(), AgentExecutionRequest.HistorySelection.currentConversation(), acceptedAt, List.of(),
                Optional.empty(), Optional.empty());
    }

    /** AdmissionFacts 将业务重答来源与当前 Thread CAS 一起冻结，避免测试绕过 Ja participant 校验。 */
    private static AdmissionFacts admission(AgentExecutionRequest request, String method,
                                            String sourceMessageId, long expectedRevision) {
        return new AdmissionFacts(request.identity(), WORKSPACE_ID, TurnOrigin.USER, method,
                request.acceptedAt(), sourceMessageId, null, null, null, null, null,
                null, expectedRevision, Optional.empty());
    }

    /** FAILED terminal 与待审计记录同一提交，提供真实的重答资格和可保留用量事实。 */
    private static void commitFailure(AgentStoreProjectionFixture run, List<AgentStore.RecordWrite> records,
                                      Instant occurredAt) {
        TurnExecutionState.Common common = new TurnExecutionState.Common(0, 0, 1,
                "prompt_reask_test", List.of());
        run.commitRecords(TurnState.FAILED,
                new TurnExecutionState.Ready(common, TurnExecutionState.Next.ASSISTANT, null), records,
                List.of(new ExecutionEvent.Terminal(TurnState.FAILED, "provider failed", "MODEL_FAILURE",
                        "provider request failed", null, null)), occurredAt);
    }

    /** 仅在重答 admission 后置阶段注入故障，验证已剪路径和已写 USER facts 会随事务一起回滚。 */
    private static JaAdmissionTransactionParticipant failAfterProjection() {
        return new JaAdmissionTransactionParticipant() {
            /** 让源问题资格校验照常运行，故障只注入在 Ja 通用投影之后。 */
            @Override
            public void beforeProjection(AgentStoreTransactionContext transaction) { }

            /** 模拟最终阶段提交失败，验证路径剪切与准入事实一并回滚。 */
            @Override
            public void afterProjection(AgentStoreTransactionContext transaction) {
                throw new IllegalStateException("injected post-projection failure");
            }
        };
    }

    /** 对每个候选使用新的执行键，确保拒绝发生在 admission 之前且不留下半个 Store execution。 */
    private static void assertReaskRejected(TestDatabase database, AgentStore store, String sourceMessageId,
                                            String candidateTurnId, long expectedRevision) {
        AgentExecutionRequest candidate = request(candidateTurnId, "message_" + candidateTurnId,
                "retry", "turn/reask", START.plusSeconds(4));
        AdmissionFacts facts = admission(candidate, "turn/reask", sourceMessageId, expectedRevision);
        assertThrows(RuntimeException.class, () -> AgentStoreProjectionFixture.open(store, database.mapper(),
                candidate, facts, PREFERENCES, expectedRevision));
        AgentStore.ExecutionKey key = new AgentStore.ExecutionKey(
                new AgentStore.ConversationKey("ja.local", THREAD_ID, "user_local"), candidateTurnId);
        assertTrue(store.loadExecution(key).isEmpty());
    }

    /** 读取 Store 权威修订供下一次 admission CAS，不能从 Ja 行独立推断。 */
    private static long threadRevision(AgentStore store) {
        return store.loadConversation(new AgentStore.ConversationKey("ja.local", THREAD_ID, "user_local"))
                .orElseThrow().revision();
    }

    /** Usage Profile 字段完整且不包含凭据，供 Ja UI 镜像验证未知用量仍保留调用身份。 */
    private static ExecutionFacts.ModelRequestIdentity modelProfile() {
        JsonObject attributes = new JsonObject(Map.of(
                "accessMode", new JsonText("FULL_ACCESS"),
                "collaborationMode", new JsonText("DEFAULT"),
                "configGeneration", new JsonText("cfg_reask_test"),
                "promptRevision", new JsonText("prompt_reask_test"),
                "toolCatalogRevision", new JsonText("b".repeat(64))));
        return new ExecutionFacts.ModelRequestIdentity("provider_reask", "model_reask", "openai_responses",
                "model_reask", "medium", "medium", 8192, 1024, attributes);
    }
}
