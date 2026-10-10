// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.conversation.domain.model.ClientOperationReceipt;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.conversation.domain.model.SubagentPolicy;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.UserInputHistory;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.application.gateway.JaOperationReceiptRepository;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionPayloadCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.store.ToolPresentationCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Ja Thread 元数据与 AgentStore 工具事实在新事务边界下仍保持可恢复投影。 */
final class MybatisConversationRepositoryTest extends PersistenceTestSupport {
    private static final String THREAD_ID = "thr_projection_store";
    private static final String WORKSPACE_ID = "ws_projection_store";
    private static final String TURN_ID = "turn_projection_store";
    private static final String CALL_ID = "call_projection_store";
    private static final String BATCH_ID = "batch_projection_store";
    private static final ThreadPreferences PREFERENCES =
            ConversationTestFixtures.preferences("provider_projection", "model_projection");

    /** Thread 创建时的子 Agent 策略被冻结到该会话行，后续全局策略变化与数据库重开都不改旧快照。 */
    @Test
    void freezesSubagentPolicyPerThreadAcrossDatabaseRestart() throws Exception {
        AtomicReference<SubagentPolicy> current = new AtomicReference<>(
                new SubagentPolicy(false, "provider_child", "model_child", "high"));
        try (TestDatabase database = database("thread-subagent-snapshot")) {
            MybatisHistoryService history = database.history(current::get);
            history.register(new Workspace.Registration("ws_policy", database.path().getParent(), "policy", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, START));
            history.createThread(new ThreadSummary.Creation("thr_policy_old", "ws_policy", "old",
                    PREFERENCES, START));

            current.set(SubagentPolicy.defaultPolicy());
            history.createThread(new ThreadSummary.Creation("thr_policy_new", "ws_policy", "new",
                    PREFERENCES, START.plusSeconds(1)));
            assertPolicy(database, "thr_policy_old", false, "provider_child", "model_child", "high");
            assertPolicy(database, "thr_policy_new", true, null, null, null);
        }

        try (TestDatabase reopened = database("thread-subagent-snapshot")) {
            assertPolicy(reopened, "thr_policy_old", false, "provider_child", "model_child", "high");
            assertPolicy(reopened, "thr_policy_new", true, null, null, null);
        }
    }

    /** Admission 與首批 queued input 的 Ja 幂等回执必须同事务持久，重复准入及重开不能再建第二条身份。 */
    @Test
    void persistsAdmissionAndInitialInputReceiptsAcrossDatabaseRestart() throws Exception {
        String admissionOperationId = "op_00000000000000000000000000000011";
        String admissionFingerprint = "a".repeat(64);
        String inputOperationId = "op_00000000000000000000000000000012";
        String inputFingerprint = "b".repeat(64);
        AgentInputCommand.Enqueue queued = new AgentInputCommand.Enqueue("input_operation_projection",
                "input_projection_initial", AgentInputCommand.Kind.FOLLOW_UP,
                new UserContent(List.of(new TextContent("later input"))),
                Map.of("ja.operationKey", inputOperationId,
                        "ja.requestFingerprint", inputFingerprint));
        AgentExecutionRequest request = request(START, List.of(queued));
        AdmissionFacts facts = admissionFacts(request, null, admissionOperationId, admissionFingerprint);

        try (TestDatabase database = database("ja-operation-receipts")) {
            AgentStore store = prepareConversation(database);
            try (AgentStoreProjectionFixture ignored = AgentStoreProjectionFixture.open(store, database.mapper(),
                    request, facts, PREFERENCES, 0)) {
                // 首个 fixture 关闭 lease 后重放同一 receipt；Ja participant 不得再推进 Thread 或重复写行。
            }
            try (AgentStoreProjectionFixture ignored = AgentStoreProjectionFixture.open(store, database.mapper(),
                    request, facts, PREFERENCES, 0)) {
                ThreadSnapshot snapshot = database.history().readThread(THREAD_ID, null, 50).orElseThrow();
                assertEquals(1, snapshot.thread().revision());
                assertEquals(1, snapshot.turns().size());
            }
            AgentExecutionRequest conflictingRequest = request(START, List.of(queued), "c".repeat(64));
            assertThrows(RuntimeException.class, () -> AgentStoreProjectionFixture.open(store, database.mapper(),
                    conflictingRequest, admissionFacts(conflictingRequest, null), PREFERENCES, 0));
            ThreadSnapshot unchanged = database.history().readThread(THREAD_ID, null, 50).orElseThrow();
            assertEquals(1, unchanged.thread().revision());
            assertEquals(1, unchanged.turns().size());

            AgentInputCommand.Enqueue reusedInputOperation = new AgentInputCommand.Enqueue(
                    "input_receipt_conflict", "input_projection_conflict", AgentInputCommand.Kind.FOLLOW_UP,
                    new UserContent(List.of(new TextContent("different queued body"))),
                    Map.of("ja.operationKey", inputOperationId,
                            "ja.requestFingerprint", "c".repeat(64)));
            AgentIdentity next = nextIdentity("turn_receipt_conflict");
            AgentExecutionRequest conflictingInput = new AgentExecutionRequest(next,
                    AgentExecutionRequest.AdmissionKind.USER_INPUT,
                    Optional.of(new UserContent(List.of(new TextContent("second user input")))),
                    "message_receipt_conflict", Optional.empty(),
                    new AgentStore.OperationIdentity("operation_turn_receipt_conflict", "turn/start", "d".repeat(64), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")), Map.of(),
                    AgentExecutionRequest.HistorySelection.currentConversation(), START.plusSeconds(1),
                    List.of(reusedInputOperation), Optional.empty(), Optional.empty());
            AdmissionFacts conflictingInputFacts = new AdmissionFacts(next, WORKSPACE_ID,
                    TurnOrigin.USER, "turn/start", conflictingInput.acceptedAt(), null, null, null,
                    null, null, null, null, 1, Optional.empty());
            assertThrows(RuntimeException.class, () -> AgentStoreProjectionFixture.open(store, database.mapper(),
                    conflictingInput, conflictingInputFacts, PREFERENCES, 1));
            AgentStore.ExecutionKey rejectedExecution = new AgentStore.ExecutionKey(
                    new AgentStore.ConversationKey("ja.local", THREAD_ID, "user_local"), "turn_receipt_conflict");
            assertTrue(store.loadExecution(rejectedExecution).isEmpty());
            unchanged = database.history().readThread(THREAD_ID, null, 50).orElseThrow();
            assertEquals(1, unchanged.thread().revision());
            assertEquals(1, unchanged.turns().size());
            store.close();
        }

        try (TestDatabase reopened = database("ja-operation-receipts")) {
            AgentStore reopenedStore = reopened.jaAgentStore();
            try (AgentStoreProjectionFixture ignored = AgentStoreProjectionFixture.open(reopenedStore,
                    reopened.mapper(), request, facts, PREFERENCES, 0)) {
                ThreadSnapshot snapshot = reopened.history().readThread(THREAD_ID, null, 50).orElseThrow();
                assertEquals(1, snapshot.thread().revision());
                assertEquals(1, snapshot.turns().size());
            }
            reopenedStore.close();
            MybatisJaOperationReceiptRepository receipts = new MybatisJaOperationReceiptRepository(
                    reopened.sessions(), readOnlyTestTransactions());
            ClientOperationReceipt admission = receipts.readClientOperation(admissionOperationId).orElseThrow();
            assertEquals(admissionOperationId, admission.operationKey());
            assertTrue(admission.matches("turn/start", admissionFingerprint));
            assertEquals(THREAD_ID, admission.threadId());
            assertEquals(TURN_ID, admission.turnId());
            assertEquals(1, admission.threadRevision());
            assertTrue(admission.queued());

            JaOperationReceiptRepository.InputReceipt input =
                    receipts.readInputOperation(inputOperationId).orElseThrow();
            assertEquals(inputFingerprint, input.fingerprint());
            assertEquals(THREAD_ID, input.threadId());
            assertEquals(TURN_ID, input.turnId());
            assertEquals("input_projection_initial", input.inputId());
            assertEquals(io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue.Kind.FOLLOW_UP,
                    input.kind());
        }
    }

    /** SQLite 可保存单独的审批回执字段，但仓储回读必须拒绝这种含糊事实。 */
    @Test
    void rejectsClientOperationReceiptsWithPartialApprovalFacts() throws Exception {
        String operationId = "op_00000000000000000000000000000021";
        AgentExecutionRequest request = request(START);
        AdmissionFacts facts = admissionFacts(request, null, operationId, "e".repeat(64));

        try (TestDatabase database = database("partial-operation-receipt")) {
            AgentStore store = prepareConversation(database);
            try (AgentStoreProjectionFixture ignored = AgentStoreProjectionFixture.open(store, database.mapper(),
                    request, facts, PREFERENCES, 0)) {
                // 先用真实 admission 写入满足 schema 的正常回执，再只篡改临时测试库中的审批列。
            }
            store.close();

            MybatisJaOperationReceiptRepository receipts = new MybatisJaOperationReceiptRepository(
                    database.sessions(), readOnlyTestTransactions());
            setReceiptApprovalFields(database, operationId, "appr_partial_receipt", null);
            assertInvalidOperationReceipt(receipts, operationId);

            setReceiptApprovalFields(database, operationId, null, "approve");
            assertInvalidOperationReceipt(receipts, operationId);
        }
    }

    /** 历史搜索只读取已提交输入，并以持久复合游标稳定分页；重开数据库后顺序仍可复现。 */
    @Test
    void searchesPersistedUserInputsAcrossRestartWithStablePages() throws Exception {
        try (TestDatabase database = database("conversation-input-search-pages")) {
            AgentStore store = prepareConversation(database);
            List<String> texts = List.of("Needle first", "Needle second", "Needle third");
            for (int index = 0; index < texts.size(); index++) {
                Instant acceptedAt = START.plusSeconds(index + 1L);
                AgentExecutionRequest request = request(acceptedAt, "turn_search_" + index,
                        "message_search_" + index, texts.get(index));
                AdmissionFacts facts = admissionFacts(request, null, index);
                try (AgentStoreProjectionFixture ignored = AgentStoreProjectionFixture.open(store,
                        database.mapper(), request, facts, PREFERENCES, index)) {
                    // 关闭只释放当前执行租约；准入事实已是搜索唯一来源。
                }
            }
            store.close();
        }

        try (TestDatabase reopened = database("conversation-input-search-pages")) {
            MybatisHistoryService history = reopened.history();
            CursorPage<UserInputHistory> first = history.searchUserInputs("  NEEDLE  ", null, 2);
            assertEquals(List.of("Needle third", "Needle second"), first.items().stream()
                    .map(UserInputHistory::text).toList());
            assertTrue(first.items().stream().noneMatch(UserInputHistory::truncated));
            assertNotNull(first.nextCursor());

            CursorPage<UserInputHistory> older = history.searchUserInputs("needle", first.nextCursor(), 2);
            assertEquals(List.of("Needle first"), older.items().stream().map(UserInputHistory::text).toList());
            assertNull(older.nextCursor());
            assertTrue(history.searchUserInputs("not present", null, 10).items().isEmpty());
        }
    }

    /** 同提交的助手进展必须先于工具，并在正反向小页中无重复、无遗漏地保持同一排序。 */
    @Test
    void ordersCommittedProgressAndToolCallsAcrossBothPageDirections() throws Exception {
        try (TestDatabase database = database("conversation-same-commit-order")) {
            AgentStore store = prepareConversation(database);
            AgentExecutionRequest request = request(START);
            try (AgentStoreProjectionFixture run = AgentStoreProjectionFixture.open(store, database.mapper(),
                    request, admissionFacts(request, null), PREFERENCES, 0)) {
                commitOrderedToolBatch(run, database);

                MybatisHistoryService history = database.history();
                List<ThreadSnapshot.Item> complete = history.readThread(THREAD_ID, null, 20).orElseThrow().items();
                List<ThreadSnapshot.Item> committedBatch = complete.stream()
                        .filter(item -> item.createdAt().equals(START.plusSeconds(1)))
                        .toList();
                assertEquals(3, committedBatch.size());
                assertTrue(committedBatch.get(0) instanceof ThreadSnapshot.TextItem);
                assertTrue(committedBatch.get(1) instanceof ThreadSnapshot.ToolItem);
                assertTrue(committedBatch.get(2) instanceof ThreadSnapshot.ToolItem);
                assertEquals(List.of("call_order_z", "call_order_a"), committedBatch.subList(1, 3).stream()
                        .map(item -> ((ThreadSnapshot.ToolItem) item).callId()).toList());

                List<String> expectedIds = complete.stream().map(ThreadSnapshot.Item::itemId).toList();
                List<String> forwardIds = new ArrayList<>();
                String cursor = null;
                for (int page = 0; page < 10; page++) {
                    ThreadSnapshot snapshot = history.readThread(THREAD_ID, cursor, 1).orElseThrow();
                    forwardIds.addAll(snapshot.items().stream().map(ThreadSnapshot.Item::itemId).toList());
                    cursor = snapshot.nextCursor();
                    if (cursor == null) {
                        break;
                    }
                }
                assertNull(cursor);
                assertEquals(expectedIds, forwardIds);
                assertEquals(forwardIds.size(), new java.util.HashSet<>(forwardIds).size());

                List<String> reverseIds = new ArrayList<>();
                cursor = null;
                for (int page = 0; page < 10; page++) {
                    ThreadSnapshot snapshot = history.readThreadLatest(THREAD_ID, cursor, 2).orElseThrow();
                    List<String> pageIds = snapshot.items().stream().map(ThreadSnapshot.Item::itemId).toList();
                    int end = expectedIds.size() - reverseIds.size();
                    assertEquals(expectedIds.subList(end - pageIds.size(), end), pageIds);
                    reverseIds.addAll(0, pageIds);
                    cursor = snapshot.nextCursor();
                    if (cursor == null) {
                        break;
                    }
                }
                assertNull(cursor);
                assertEquals(expectedIds, reverseIds);
                assertEquals(reverseIds.size(), new java.util.HashSet<>(reverseIds).size());
            }
            store.close();
        }
    }

    /** 最新页即使只携带旧条目，也保留真实 Thread head 与该页条目的 owner。 */
    @Test
    void latestHistoryPagesRetainItemOwnersAndActualThreadHead() throws Exception {
        try (TestDatabase database = database("conversation-latest-page-head")) {
            AgentStore store = prepareConversation(database);
            long expectedRevision = 0;
            for (int index = 1; index <= 3; index++) {
                Instant acceptedAt = START.plusSeconds(index * 2L);
                String turnId = "turn_latest_" + index;
                AgentExecutionRequest request = request(acceptedAt, turnId,
                        "message_latest_" + index, "question " + index);
                AdmissionFacts facts = admissionFacts(request, null, expectedRevision);
                try (AgentStoreProjectionFixture run = AgentStoreProjectionFixture.open(store,
                        database.mapper(), request, facts, PREFERENCES, expectedRevision)) {
                    commitCompletedTerminal(run, acceptedAt.plusSeconds(1));
                }
                expectedRevision += 2;
            }

            MybatisHistoryService history = database.history();
            ThreadSnapshot newest = history.readThreadLatest(THREAD_ID, null, 1).orElseThrow();
            assertEquals(List.of("turn_latest_3"), newest.items().stream()
                    .map(ThreadSnapshot.Item::turnId).toList());
            assertEquals(List.of("turn_latest_3"), newest.turns().stream()
                    .map(ThreadSnapshot.Turn::turnId).toList());

            ThreadSnapshot middle = history.readThreadLatest(THREAD_ID, newest.nextCursor(), 1).orElseThrow();
            assertEquals(List.of("turn_latest_2"), middle.items().stream()
                    .map(ThreadSnapshot.Item::turnId).toList());
            assertEquals(List.of("turn_latest_2", "turn_latest_3"), middle.turns().stream()
                    .map(ThreadSnapshot.Turn::turnId).toList());

            ThreadSnapshot oldest = history.readThreadLatest(THREAD_ID, middle.nextCursor(), 1).orElseThrow();
            assertEquals(List.of("turn_latest_1"), oldest.items().stream()
                    .map(ThreadSnapshot.Item::turnId).toList());
            assertEquals(List.of("turn_latest_1", "turn_latest_3"), oldest.turns().stream()
                    .map(ThreadSnapshot.Turn::turnId).toList());
            assertNull(oldest.nextCursor());
            assertEquals(expectedRevision, oldest.thread().revision());
            store.close();
        }
    }

    /** 长推理摘要的历史预览保持有界，Unicode 正文仍按 code point 游标完整分页。 */
    @Test
    void pagesLargeReasoningSummaryWithoutLeakingItIntoHistoryPreview() throws Exception {
        try (TestDatabase database = database("conversation-large-reasoning-summary")) {
            AgentStore store = prepareConversation(database);
            AgentExecutionRequest request = request(START);
            try (AgentStoreProjectionFixture run = AgentStoreProjectionFixture.open(store, database.mapper(),
                    request, admissionFacts(request, null), PREFERENCES, 0)) {
                Instant at = START.plusSeconds(1);
                String assistantId = "message_summary_answer";
                String summaryId = "message_summary_reasoning";
                String assistantRecordId = run.recordId("agent.assistant", assistantId);
                String summaryText = "思".repeat(65_536) + "😀终";
                ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT,
                        List.of(new TextContent("done")));
                List<AgentStore.RecordWrite> records = List.of(
                        run.record("agent.assistant", assistantId,
                                new ExecutionFacts.AssistantMessage(assistantId, assistant, "done", null, 1), at),
                        run.record("agent.reasoning_summary", summaryId,
                                new ExecutionFacts.ReasoningSummary(summaryId, summaryText, 1), at),
                        run.knownEmptyChangeSet(at));
                run.commitRecords(TurnState.COMPLETED,
                        new TurnExecutionState.Ready(new TurnExecutionState.Common(1, 0, 2,
                                "prompt_projection_test", List.of()), TurnExecutionState.Next.ASSISTANT, null),
                        records, List.of(new ExecutionEvent.Terminal(TurnState.COMPLETED, "done", null, null,
                                assistantRecordId, null)), at);
            }

            ThreadSnapshot snapshot = database.history().readThread(THREAD_ID, null, 20).orElseThrow();
            List<ThreadSnapshot.TextItem> textItems = snapshot.items().stream()
                    .filter(ThreadSnapshot.TextItem.class::isInstance)
                    .map(ThreadSnapshot.TextItem.class::cast).toList();
            assertEquals(2, textItems.size());
            assertEquals(1, textItems.stream().filter(item -> item.kind()
                    == ThreadSnapshot.TextKind.FINAL_ANSWER).count());
            assertEquals(1, textItems.stream().filter(item -> item.kind()
                    == ThreadSnapshot.TextKind.REASONING_SUMMARY).count());
            ThreadSnapshot.TextItem reasoning = snapshot.items().stream()
                    .filter(ThreadSnapshot.TextItem.class::isInstance)
                    .map(ThreadSnapshot.TextItem.class::cast)
                    .filter(item -> item.kind() == ThreadSnapshot.TextKind.REASONING_SUMMARY)
                    .findFirst().orElseThrow();
            assertEquals(ThreadSnapshot.TEXT_PREVIEW_CODE_POINTS,
                    reasoning.text().codePointCount(0, reasoning.text().length()));
            assertEquals(65_538, reasoning.totalCharacters());
            assertTrue(reasoning.truncated());
            ThreadUseCase.ItemContentPage tail = database.history()
                    .readItemContent(THREAD_ID, reasoning.itemId(), 65_536, 8)
                    .orElseThrow();
            assertEquals("😀终", tail.content());
            assertEquals(65_538, tail.totalCharacters());
            assertNull(tail.nextOffsetCharacters());
            store.close();
        }
    }

    /** 快照只保留有界预览；稳定 item 身份分页读取必须仍能还原完整最终答复。 */
    @Test
    void preservesFullFinalAnswerAndPagesFromStableHistoryIdentity() throws Exception {
        try (TestDatabase database = database("conversation-large-final-answer")) {
            AgentStore store = prepareConversation(database);
            AgentExecutionRequest request = request(START);
            try (AgentStoreProjectionFixture run = AgentStoreProjectionFixture.open(store, database.mapper(),
                    request, admissionFacts(request, null), PREFERENCES, 0)) {
                Instant at = START.plusSeconds(1);
                String assistantId = "message_long_final";
                String fullText = "x".repeat(1_200_000);
                String assistantRecordId = run.recordId("agent.assistant", assistantId);
                ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent(fullText)));
                run.commitRecords(TurnState.COMPLETED,
                        new TurnExecutionState.Ready(new TurnExecutionState.Common(1, 0, 2,
                                "prompt_projection_test", List.of()), TurnExecutionState.Next.ASSISTANT, null),
                        List.of(run.record("agent.assistant", assistantId,
                                new ExecutionFacts.AssistantMessage(assistantId, assistant, fullText, null, 1), at),
                                run.knownEmptyChangeSet(at)),
                        List.of(new ExecutionEvent.Terminal(TurnState.COMPLETED, "done", null, null,
                                assistantRecordId, null)), at);

                ThreadSnapshot snapshot = database.history().readThread(THREAD_ID, null, 20).orElseThrow();
                ThreadSnapshot.TextItem answer = snapshot.items().stream()
                        .filter(ThreadSnapshot.TextItem.class::isInstance)
                        .map(ThreadSnapshot.TextItem.class::cast)
                        .filter(item -> item.kind() == ThreadSnapshot.TextKind.FINAL_ANSWER)
                        .findFirst().orElseThrow();
                assertEquals(ThreadSnapshot.TEXT_PREVIEW_CODE_POINTS,
                        answer.text().codePointCount(0, answer.text().length()));
                assertEquals(fullText.length(), answer.totalCharacters());
                assertTrue(answer.truncated());
                assertEquals(fullText.substring(0, ThreadSnapshot.TEXT_PREVIEW_CODE_POINTS), answer.text());

                ThreadUseCase.ItemContentPage firstPage = database.history()
                        .readItemContent(THREAD_ID, answer.itemId(), 0, 65_536)
                        .orElseThrow();
                ThreadUseCase.ItemContentPage secondPage = database.history().readItemContent(THREAD_ID, answer.itemId(),
                        firstPage.nextOffsetCharacters(), 65_536).orElseThrow();
                assertEquals(1_200_000, firstPage.totalCharacters());
                assertEquals(65_536, firstPage.content().length());
                assertEquals(65_536, secondPage.offsetCharacters());
                assertEquals(fullText.substring(0, 131_072), firstPage.content() + secondPage.content());
                StringBuilder restored = new StringBuilder(fullText.length())
                        .append(firstPage.content()).append(secondPage.content());
                Integer nextOffset = secondPage.nextOffsetCharacters();
                while (nextOffset != null) {
                    ThreadUseCase.ItemContentPage page = database.history()
                            .readItemContent(THREAD_ID, answer.itemId(), nextOffset, 65_536).orElseThrow();
                    assertEquals(nextOffset.intValue(), page.offsetCharacters());
                    assertEquals(1_200_000, page.totalCharacters());
                    restored.append(page.content());
                    nextOffset = page.nextOffsetCharacters();
                }
                assertEquals(fullText, restored.toString());
                assertTrue(database.history().readItemContent(THREAD_ID, "message_projection_user", 0, 100)
                        .isEmpty());
            }
            store.close();
        }
    }

    /** 过期 Thread revision 的 reask 只能失败，不能在同一 admission 中剪掉仍有效的 current path。 */
    @Test
    void staleReaskAdmissionLeavesFailedQuestionOnCurrentPath() throws Exception {
        try (TestDatabase database = database("conversation-reask-stale-cas")) {
            AgentStore store = prepareConversation(database);
            AgentExecutionRequest original = request(START.plusSeconds(1), "turn_reask_source",
                    "message_reask_source", "original question");
            try (AgentStoreProjectionFixture run = AgentStoreProjectionFixture.open(store, database.mapper(),
                    original, admissionFacts(original, null), PREFERENCES, 0)) {
                commitTerminal(run, TurnState.FAILED, "failed", "MODEL_FAILURE",
                        "provider failure", START.plusSeconds(2));
            }

            AgentExecutionRequest reask = request(START.plusSeconds(3), "turn_reask_stale",
                    "message_reask_attempt", "retry question", "turn/reask");
            AdmissionFacts staleFacts = new AdmissionFacts(reask.identity(), WORKSPACE_ID, TurnOrigin.USER,
                    "turn/reask", reask.acceptedAt(), "message_reask_source", null, null,
                    null, null, null, null, 1, Optional.empty());
            assertThrows(RuntimeException.class, () -> AgentStoreProjectionFixture.open(store, database.mapper(),
                    reask, staleFacts, PREFERENCES, 1));

            ThreadSnapshot snapshot = database.history().readThread(THREAD_ID, null, 20).orElseThrow();
            assertEquals(2, snapshot.thread().revision());
            assertEquals(1, snapshot.turns().size());
            assertEquals("turn_reask_source", snapshot.turns().getFirst().turnId());
            assertEquals("failed", snapshot.turns().getFirst().status());

            AgentStore.ExecutionKey staleExecution = new AgentStore.ExecutionKey(
                    new AgentStore.ConversationKey("ja.local", THREAD_ID, "user_local"), "turn_reask_stale");
            assertTrue(store.loadExecution(staleExecution).isEmpty());
            store.close();
        }
    }

    /** 空失败结果仍完成同一工具项的稳定投影，不复制成第二条时间线记录或伪造成功状态。 */
    @Test
    void projectsEmptyFailedToolResultAsOneStableErrorItem() throws Exception {
        try (TestDatabase database = database("empty-failed-tool-projection")) {
            AgentStore store = prepareConversation(database);
            AgentExecutionRequest request = request(START);
            AdmissionFacts facts = admissionFacts(request, null);
            try (AgentStoreProjectionFixture run = AgentStoreProjectionFixture.open(store, database.mapper(),
                    request, facts, PREFERENCES, 0)) {
                TurnExecutionState.Tools tools = commitPreparedTool(run, database, true);
                String preparedItemId = onlyToolItem(database).itemId();
                assertEquals(ToolPresentation.Status.PENDING, onlyToolItem(database).presentation().status());

                commitStartedTool(run, database, tools);
                ThreadSnapshot.ToolItem started = onlyToolItem(database);
                assertEquals(preparedItemId, started.itemId());
                assertEquals(ToolPresentation.Status.RUNNING, started.presentation().status());

                commitFailedTool(run, database, tools);
                ThreadSnapshot.ToolItem failed = onlyToolItem(database);
                assertEquals(preparedItemId, failed.itemId());
                assertTrue(failed.itemId().matches("item_[0-9a-f]{64}"));
                assertEquals(ToolPresentation.Status.ERROR, failed.presentation().status());
                assertEquals("", failed.presentation().outputPreview());
            }
        }
    }

    /** Ja 准备投影缺项时整笔 Store 与 Thread 事务回滚，避免留下无法展示的执行事实。 */
    @Test
    void rollsBackPreparedToolFactsWhenPresentationIsMissing() throws Exception {
        try (TestDatabase database = database("tool-projection-rollback")) {
            AgentStore store = prepareConversation(database);
            AgentExecutionRequest request = request(START);
            try (AgentStoreProjectionFixture run = AgentStoreProjectionFixture.open(store, database.mapper(),
                    request, admissionFacts(request, null), PREFERENCES, 0)) {
                assertThrows(RuntimeException.class, () -> commitPreparedTool(run, database, false));

                AgentStore verify = database.agentStore();
                AgentStore.ConversationKey conversation = new AgentStore.ConversationKey("ja.local", THREAD_ID, "user_local");
                AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(conversation, TURN_ID);
                assertEquals(2, verify.loadConversation(conversation).orElseThrow().revision());
                assertEquals(2, verify.loadExecution(execution).orElseThrow().mutationVersion());
                assertTrue(database.history().readThread(THREAD_ID, null, 50).orElseThrow().items().stream()
                        .noneMatch(ThreadSnapshot.ToolItem.class::isInstance));
                verify.close();
            }
        }
    }

    /** Thread 创建已与 conversation 初始化同事务；复用其 Ja Store，避免重复建立锚点。 */
    private AgentStore prepareConversation(TestDatabase database) {
        database.history().register(new Workspace.Registration(WORKSPACE_ID, database.path().getParent(), "projection", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, START));
        database.history().createThread(new ThreadSummary.Creation(
                THREAD_ID, WORKSPACE_ID, "projection", PREFERENCES, START));
        return database.jaAgentStore();
    }

    /** 构造固定身份和 operation，确保测试覆盖持久准入而非只调用 Ja 投影器。 */
    private static AgentExecutionRequest request(Instant acceptedAt) {
        return request(acceptedAt, List.of());
    }

    /** 将初始队列作为准入事务的显式输入，避免测试私自插入尚未受理的队列投影。 */
    private static AgentExecutionRequest request(Instant acceptedAt,
                                                 List<AgentInputCommand.Enqueue> initialQueuedInputs) {
        return request(acceptedAt, initialQueuedInputs, "a".repeat(64));
    }

    /** 为每条历史记录建立独立 execution/message 身份，避免分页回归依赖旧 Repository 的合成行。 */
    private static AgentExecutionRequest request(Instant acceptedAt, String turnId,
                                                 String messageId, String text) {
        return request(acceptedAt, turnId, messageId, text, "turn/start");
    }

    /** Reask 只调整 Ja admission method；正文、execution 身份与 operation key 仍保持独立。 */
    private static AgentExecutionRequest request(Instant acceptedAt, String turnId,
                                                 String messageId, String text, String operationType) {
        AgentIdentity identity = new AgentIdentity("ja.local", THREAD_ID, turnId, "user_local");
        return new AgentExecutionRequest(identity, AgentExecutionRequest.AdmissionKind.USER_INPUT,
                Optional.of(new UserContent(List.of(new TextContent(text)))), messageId,
                Optional.empty(), new AgentStore.OperationIdentity("operation_" + turnId, operationType, "a".repeat(64), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")), Map.of(),
                AgentExecutionRequest.HistorySelection.currentConversation(), acceptedAt, List.of(),
                Optional.empty(), Optional.empty());
    }

    /** 为准入 rollback 断言创建与持久 execution 身份一致的独立键。 */
    private static AgentIdentity nextIdentity(String turnId) {
        return new AgentIdentity("ja.local", THREAD_ID, turnId, "user_local");
    }

    /** 固定操作键仅用于冲突重放场景，改变指纹应拒绝重用而不创建第二条 Ja Turn。 */
    private static AgentExecutionRequest request(Instant acceptedAt,
                                                 List<AgentInputCommand.Enqueue> initialQueuedInputs,
                                                 String operationFingerprint) {
        AgentIdentity identity = new AgentIdentity("ja.local", THREAD_ID, TURN_ID, "user_local");
        return new AgentExecutionRequest(identity, AgentExecutionRequest.AdmissionKind.USER_INPUT,
                Optional.of(new UserContent(List.of(new TextContent("run tool")))), "message_projection_user",
                Optional.empty(), new AgentStore.OperationIdentity("operation_projection", "turn/start", operationFingerprint, new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")), Map.of(),
                AgentExecutionRequest.HistorySelection.currentConversation(), acceptedAt, initialQueuedInputs,
                Optional.empty(), Optional.empty());
    }

    /** 投影 admission 只把 Thread 锚点和 USER 事实关联起来，不伪造 reask 或外部消息来源。 */
    private static AdmissionFacts admissionFacts(AgentExecutionRequest request, String sourceMessageId) {
        return admissionFacts(request, sourceMessageId, null, null, 0);
    }

    /** 在准入同一事务中可选写入 Ja client receipt，不把 Core OperationIdentity 当作 JA-RPC receipt。 */
    private static AdmissionFacts admissionFacts(AgentExecutionRequest request, String sourceMessageId,
                                                 String clientOperationId, String requestFingerprint) {
        return admissionFacts(request, sourceMessageId, clientOperationId, requestFingerprint, 0);
    }

    /** 将同一 thread 的下一次 admission 绑定到测试显式追踪的 revision，避免读取最新值掩盖 CAS 错误。 */
    private static AdmissionFacts admissionFacts(AgentExecutionRequest request, String sourceMessageId,
                                                 long expectedThreadRevision) {
        return admissionFacts(request, sourceMessageId, null, null, expectedThreadRevision);
    }

    /** 可选 RPC receipt 与 Thread CAS 共用同一组冻结准入字段。 */
    private static AdmissionFacts admissionFacts(AgentExecutionRequest request, String sourceMessageId,
                                                 String clientOperationId, String requestFingerprint,
                                                 long expectedThreadRevision) {
        return new AdmissionFacts(request.identity(), WORKSPACE_ID, TurnOrigin.USER, "turn/start",
                request.acceptedAt(), sourceMessageId, null, null, null, null, clientOperationId,
                requestFingerprint, expectedThreadRevision, Optional.empty());
    }

    /** 写入完整助手批次、用量和准备事实；可省略宿主 presentation 以验证 Ja participant 的原子拒绝。 */
    private static TurnExecutionState.Tools commitPreparedTool(AgentStoreProjectionFixture run,
                                                               TestDatabase database,
                                                               boolean includePresentation) {
        Instant at = START.plusSeconds(1);
        String assistantId = "message_projection_assistant";
        String usageId = "modelreq_projection_assistant";
        String assistantRecord = run.recordId("agent.assistant", assistantId);
        String usageRecord = run.recordId("agent.usage", usageId);
        commitPendingAssistantUsage(run, usageId, providerProfile(), at.minusMillis(1));
        JsonObject arguments = JsonObject.empty();
        ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT,
                List.of(new ToolCallContent(CALL_ID, "unknown_tool", arguments)));
        ExecutionFacts.AssistantMessage message = new ExecutionFacts.AssistantMessage(
                assistantId, assistant, "", null, 1);
        ExecutionFacts.ModelRequestIdentity profile = providerProfile();
        ExecutionFacts.Usage usage = new ExecutionFacts.Usage(usageId,
                new ModelUsage(8, 2, 10), 1, 1, ExecutionFacts.UsagePurpose.ASSISTANT,
                ExecutionFacts.UsageCertainty.KNOWN, profile, null);
        ExecutionFacts.ToolPrepared prepared = new ExecutionFacts.ToolPrepared(BATCH_ID, CALL_ID,
                "unknown_tool", arguments, 0, ToolSideEffect.READ_ONLY, null, false);
        List<AgentStore.RecordWrite> records = new java.util.ArrayList<>(List.of(
                run.record("agent.assistant", assistantId, message, at),
                run.record("agent.usage", usageId, usage, at),
                run.record("agent.tool.prepared", CALL_ID, prepared, at)));
        if (includePresentation) {
            records.add(presentation(database, CALL_ID, 0, ToolState.PREPARED,
                ToolPresentation.Status.PENDING, at));
        }
        TurnExecutionState.Common common = new TurnExecutionState.Common(1, 0, 2,
                "prompt_projection_test", List.of());
        TurnExecutionState.Tools tools = new TurnExecutionState.Tools(common, BATCH_ID, assistantId, 0, 0, 0);
        run.commitRecords(TurnState.RUNNING, tools, records,
                List.of(new ExecutionEvent.ModelCompleted(assistantRecord, 1, List.of(CALL_ID), usageRecord)), at);
        return tools;
    }

    /** 助手正文与两个带 ordinal 的工具意图在一次 Store commit 中冻结，供双向分页排序校验。 */
    private static void commitOrderedToolBatch(AgentStoreProjectionFixture run, TestDatabase database) {
        Instant at = START.plusSeconds(1);
        String assistantId = "message_projection_ordered_assistant";
        String usageId = "modelreq_projection_ordered_assistant";
        commitPendingAssistantUsage(run, usageId, providerProfile(), at.minusMillis(1));
        String firstCall = "call_order_z";
        String secondCall = "call_order_a";
        JsonObject arguments = JsonObject.empty();
        ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT, List.of(
                new TextContent("先读取两个文件"),
                new ToolCallContent(firstCall, "unknown_tool", arguments),
                new ToolCallContent(secondCall, "unknown_tool", arguments)));
        ExecutionFacts.AssistantMessage message = new ExecutionFacts.AssistantMessage(
                assistantId, assistant, "先读取两个文件", null, 1);
        ExecutionFacts.ModelRequestIdentity profile = providerProfile();
        ExecutionFacts.Usage usage = new ExecutionFacts.Usage(usageId,
                new ModelUsage(8, 2, 10), 1, 1, ExecutionFacts.UsagePurpose.ASSISTANT,
                ExecutionFacts.UsageCertainty.KNOWN, profile, null);
        ExecutionFacts.ToolPrepared first = new ExecutionFacts.ToolPrepared(BATCH_ID, firstCall,
                "unknown_tool", arguments, 0, ToolSideEffect.READ_ONLY, null, false);
        ExecutionFacts.ToolPrepared second = new ExecutionFacts.ToolPrepared(BATCH_ID, secondCall,
                "unknown_tool", arguments, 1, ToolSideEffect.READ_ONLY, null, false);
        List<AgentStore.RecordWrite> records = new ArrayList<>(List.of(
                run.record("agent.assistant", assistantId, message, at),
                run.record("agent.usage", usageId, usage, at),
                run.record("agent.tool.prepared", firstCall, first, at),
                run.record("agent.tool.prepared", secondCall, second, at),
                presentation(database, firstCall, 0, ToolState.PREPARED, ToolPresentation.Status.PENDING, at),
                presentation(database, secondCall, 0, ToolState.PREPARED, ToolPresentation.Status.PENDING, at)));
        TurnExecutionState.Common common = new TurnExecutionState.Common(1, 0, 2,
                "prompt_projection_test", List.of());
        TurnExecutionState.Tools tools = new TurnExecutionState.Tools(common, BATCH_ID, assistantId, 0, 0, 0);
        run.commitRecords(TurnState.RUNNING, tools, records,
                List.of(new ExecutionEvent.ModelCompleted(run.recordId("agent.assistant", assistantId), 1,
                        List.of(firstCall, secondCall), run.recordId("agent.usage", usageId))), at);
    }

    /** 先持久未知用量才能记录响应后的 KNOWN 结算，遵循崩溃恢复中不伪造已知账单的 Store 顺序。 */
    private static void commitPendingAssistantUsage(AgentStoreProjectionFixture run, String modelRequestId,
                                                     ExecutionFacts.ModelRequestIdentity profile, Instant occurredAt) {
        ExecutionFacts.Usage pending = new ExecutionFacts.Usage(modelRequestId, null, 1, 1,
                ExecutionFacts.UsagePurpose.ASSISTANT, ExecutionFacts.UsageCertainty.UNKNOWN, profile, null);
        TurnExecutionState.Common common = new TurnExecutionState.Common(0, 0, 1,
                "prompt_projection_test", List.of());
        TurnExecutionState.Ready ready = new TurnExecutionState.Ready(common,
                TurnExecutionState.Next.ASSISTANT, null);
        run.commitRecords(TurnState.RUNNING, ready,
                List.of(run.record("agent.usage", modelRequestId + "_pending", pending, occurredAt)),
                List.of(), occurredAt);
    }

    /** 将 Ja terminal 摘要、Store 状态与终态事件一起提交，供历史 head 查询使用真实终态 Turn。 */
    private static void commitCompletedTerminal(AgentStoreProjectionFixture run, Instant occurredAt) {
        commitTerminal(run, TurnState.COMPLETED, "done", null, null, occurredAt);
    }

    /** 终态摘要事实与 Store 事件共用当前租约和版本 CAS，避免历史快照出现半完成状态。 */
    private static void commitTerminal(AgentStoreProjectionFixture run, TurnState state,
                                       String summary, String errorCode,
                                       String errorMessage, Instant occurredAt) {
        TurnExecutionState.Common common = new TurnExecutionState.Common(0, 0, 1,
                "prompt_projection_test", List.of());
        List<AgentStore.RecordWrite> records = state == TurnState.COMPLETED
                ? List.of(run.knownEmptyChangeSet(occurredAt)) : List.of();
        run.commitRecords(state, new TurnExecutionState.Ready(common, TurnExecutionState.Next.ASSISTANT, null),
                records, List.of(new ExecutionEvent.Terminal(state, summary, errorCode,
                        errorMessage, null, null)), occurredAt);
    }

    /** STARTED 与 RUNNING 展示同事务推进，测试不绕过 Store 的 lease 和 mutation CAS。 */
    private static void commitStartedTool(AgentStoreProjectionFixture run, TestDatabase database,
                                          TurnExecutionState.Tools tools) {
        Instant at = START.plusSeconds(2);
        List<AgentStore.RecordWrite> records = List.of(
                run.record("agent.tool.started", CALL_ID, new ExecutionFacts.ToolStarted(CALL_ID, 0), at),
                presentation(database, CALL_ID, 1, ToolState.RUNNING, ToolPresentation.Status.RUNNING, at));
        run.commitRecords(TurnState.RUNNING,
                new TurnExecutionState.Tools(tools.common(), BATCH_ID, tools.assistantMessageId(), 0, 0, 0),
                records, List.of(new ExecutionEvent.ToolStarted(CALL_ID, BATCH_ID, "unknown_tool", 0)), at);
    }

    /** 空正文失败结果与工具消息、ERROR 展示及事件引用同事务提交，证明错误不会成为空白成功项。 */
    private static void commitFailedTool(AgentStoreProjectionFixture run, TestDatabase database,
                                         TurnExecutionState.Tools tools) {
        Instant at = START.plusSeconds(3);
        String toolMessageId = "message_projection_tool";
        String resultRecordId = run.recordId("agent.tool.result", CALL_ID);
        String toolMessageRecordId = run.recordId("agent.tool.message", toolMessageId);
        ExecutionFacts.ToolResult result = new ExecutionFacts.ToolResult(
                CALL_ID, ToolState.FAILED, "", true, "");
        ExecutionFacts.ToolMessage toolMessage = new ExecutionFacts.ToolMessage(toolMessageId,
                new ModelMessage(ModelRole.TOOL, List.of(new ToolResultContent(CALL_ID, "", true))));
        List<AgentStore.RecordWrite> records = List.of(
                run.record("agent.tool.result", CALL_ID, result, at),
                run.record("agent.tool.message", toolMessageId, toolMessage, at),
                presentation(database, CALL_ID, 2, ToolState.FAILED, ToolPresentation.Status.ERROR, at));
        run.commitRecords(TurnState.RUNNING,
                new TurnExecutionState.Ready(tools.common(), TurnExecutionState.Next.ASSISTANT, null), records,
                List.of(new ExecutionEvent.ToolCompleted(CALL_ID, ToolState.FAILED, resultRecordId,
                        toolMessageRecordId, "TOOL_EXECUTION_FAILED")), at);
    }

    /** 按专用版本化 codec 生成展示记录，避免测试手写 JSON 或给 Ja 事实另开事务。 */
    private static AgentStore.RecordWrite presentation(TestDatabase database, String callId, long revision,
                                                       ToolState state, ToolPresentation.Status display,
                                                       Instant occurredAt) {
        ToolPresentation value = new ToolPresentation(ToolPresentation.Kind.MCP, "unknown_tool", display,
                "{}", "", null, List.of(), null, null, null, null, null, null, false, null);
        return new JaProjectionPayloadCodec(database.mapper()).encodeToolPresentation(callId, revision, state,
                new ToolPresentationCodec(database.mapper()).write(value), occurredAt);
    }

    /** 每次从 Ja 的权威历史快照重读，检验投影跨提交仍只有同一条 Tool item。 */
    private static ThreadSnapshot.ToolItem onlyToolItem(TestDatabase database) {
        List<ThreadSnapshot.ToolItem> tools = database.history().readThread(THREAD_ID, null, 50).orElseThrow()
                .items().stream().filter(ThreadSnapshot.ToolItem.class::isInstance)
                .map(ThreadSnapshot.ToolItem.class::cast).toList();
        assertEquals(1, tools.size());
        return tools.getFirst();
    }

    /** Ja receipt reader 只做 SELECT；测试注入独立 MyBatis owner，避免依赖未启动的 Solon 上下文。 */
    private static MybatisUnitOfWork.SessionOwner readOnlyTestTransactions() {
        return new MybatisUnitOfWork.SessionOwner() {
            /** 该 owner 只服务本类的回执读取，直接关闭只读 SqlSession，不伪造生产事务生命周期。 */
            @Override
            public <T> T execute(SqlSessionFactory sessions, MybatisUnitOfWork.Work<T> work) {
                try (SqlSession session = sessions.openSession()) {
                    return work.apply(PersistenceMappers.open(session));
                } catch (RuntimeException | Error failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new IllegalStateException("receipt read failed", failure);
                }
            }
        };
    }

    /** 只改写当前临时数据库中的测试回执，两个绑定字段始终由同一条参数化语句更新。 */
    private static void setReceiptApprovalFields(TestDatabase database, String operationId,
                                                 String approvalId, String decision) throws Exception {
        try (SqlSession session = database.sessions().openSession()) {
            try (PreparedStatement update = session.getConnection().prepareStatement(
                    "UPDATE ja_client_operation SET approval_id=?,decision=? WHERE operation_key=?")) {
                update.setString(1, approvalId);
                update.setString(2, decision);
                update.setString(3, operationId);
                assertEquals(1, update.executeUpdate());
            }
            session.commit();
        }
    }

    /** 损坏的持久回执必须以稳定存储错误分类退出，不能被解释成可重试成功。 */
    private static void assertInvalidOperationReceipt(MybatisJaOperationReceiptRepository receipts,
                                                      String operationId) {
        StorageException failure = assertThrows(StorageException.class,
                () -> receipts.readClientOperation(operationId));
        assertEquals(StorageException.Code.INVALID_STATE, failure.code());
    }

    /** Ja 用量镜像要求冻结访问、协作、配置、Prompt 与工具目录五个真实 profile 属性。 */
    private static ExecutionFacts.ModelRequestIdentity providerProfile() {
        JsonObject attributes = new JsonObject(Map.of(
                "accessMode", new JsonText("FULL_ACCESS"),
                "collaborationMode", new JsonText("DEFAULT"),
                "configGeneration", new JsonText("cfg_projection_test"),
                "promptRevision", new JsonText("prompt_projection_test"),
                "toolCatalogRevision", new JsonText("b".repeat(64))));
        return new ExecutionFacts.ModelRequestIdentity("provider_projection", "model_projection",
                "openai_responses", "model_projection", "medium", "medium", 8192, 1024, attributes);
    }

    /** 直接读取 Thread 创建时冻结的策略行，不能用当前可变 source 代替持久值。 */
    private static void assertPolicy(TestDatabase database, String threadId, boolean enabled,
                                     String providerId, String modelId, String reasoningLevel) throws Exception {
        try (SqlSession session = database.sessions().openSession()) {
            PersistenceRecords.SubagentPolicyRow row = PersistenceMappers.open(session)
                    .subagentPolicies().select(threadId);
            assertNotNull(row);
            assertEquals(enabled, row.enabled());
            assertEquals(providerId, row.providerId());
            assertEquals(modelId, row.modelId());
            assertEquals(reasoningLevel, row.reasoningLevel());
        }
    }
}
