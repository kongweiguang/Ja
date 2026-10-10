// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnChangeSet;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase;
import io.github.kongweiguang.ja.conversation.domain.model.ProcessSessionScope;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TurnChangeSetCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实 SQLite 验证 Thread 生命周期 CAS、最新 Turn 投影与借入 Store 的关闭边界。 */
final class MybatisThreadLifecycleTest extends PersistenceTestSupport {
    /** 置顶/分页/归档/恢复都使用权威 Thread revision；活动 Turn 阻止归档并继续投影最新状态。 */
    @Test
    void projectsPinnedLifecycleAndLatestTurnStatusWithStableCas() throws Exception {
        try (TestDatabase database = database("thread-pinned-lifecycle-current")) {
            MybatisHistoryService history = createThreads(database, List.of("thr_1", "thr_2", "thr_3"));
            AgentStoreProjectionFixture fixture = admit(database, "thr_1", "turn_latest", 0);
            try (fixture) {
                ThreadSummary queued = findThread(history, "thr_1");
                assertEquals(TurnState.QUEUED, queued.latestTurnStatus());

                StorageException busy = assertThrows(StorageException.class,
                        () -> history.archiveThread("thr_1", queued.revision()));
                assertEquals(StorageException.Code.INVALID_STATE, busy.code());

                fixture.commit(TurnState.RUNNING, readyCursor(), List.of(), List.of(), START.plusSeconds(1));
                commitFailedTerminal(database, fixture, "not completed", START.plusSeconds(2));
            }

            ThreadSummary current = findThread(history, "thr_1");
            ThreadSummary pinned = history.pinThread("thr_1", true, current.revision());
            assertTrue(pinned.pinned());
            assertEquals(current.revision() + 1, pinned.revision());
            CursorPage<ThreadSummary> first = history.listThreads("ws_1", null, 2);
            CursorPage<ThreadSummary> second = history.listThreads("ws_1", first.nextCursor(), 2);
            assertEquals("thr_1", first.items().getFirst().threadId());
            assertEquals(3, java.util.stream.Stream.concat(first.items().stream(), second.items().stream())
                    .map(ThreadSummary::threadId).distinct().count());
            assertNull(second.nextCursor());

            StorageException stale = assertThrows(StorageException.class,
                    () -> history.pinThread("thr_1", false, current.revision()));
            assertEquals(StorageException.Code.CAS_CONFLICT, stale.code());
            ThreadSummary archived = history.archiveThread("thr_1", pinned.revision());
            assertEquals(ThreadSummary.Status.ARCHIVED, archived.status());
            assertFalse(archived.pinned());
            assertTrue(history.listThreads("ws_1", null, 10).items().stream()
                    .noneMatch(value -> value.threadId().equals("thr_1")));
            assertEquals(ThreadSummary.Status.ARCHIVED,
                    history.readThread("thr_1", null, 10).orElseThrow().thread().status());

            ThreadSummary restored = history.restoreThread("thr_1", archived.revision());
            assertEquals(ThreadSummary.Status.ACTIVE, restored.status());
            assertFalse(restored.pinned());
            assertEquals(TurnState.FAILED, restored.latestTurnStatus());
        }
    }

    /** 已读边界跨生命周期保留；标题与偏好更新后，新准入仍须命中同一 Store revision。 */
    @Test
    void persistsSeenBoundaryAcrossTerminalTurnAndLifecycle() throws Exception {
        try (TestDatabase database = database("thread-seen-boundary-current")) {
            MybatisHistoryService history = createThreads(database, List.of("thr_seen"));
            AgentStoreProjectionFixture fixture = admit(database, "thr_seen", "turn_seen", 0);
            try (fixture) {
                fixture.commit(TurnState.RUNNING, readyCursor(), List.of(), List.of(), START.plusSeconds(1));
                ThreadSummary running = findThread(history, "thr_seen");
                ThreadSummary liveNoOp = history.markThreadSeen("thr_seen", running.revision());
                assertEquals(running.revision(), liveNoOp.revision());
                assertEquals(TurnState.RUNNING, liveNoOp.latestTurnStatus());
                assertFalse(liveNoOp.latestTurnSeen());

                commitFailedTerminal(database, fixture, "first failure", START.plusSeconds(2));
            }

            ThreadSummary unseen = findThread(history, "thr_seen");
            assertEquals(TurnState.FAILED, unseen.latestTurnStatus());
            assertFalse(unseen.latestTurnSeen());
            AgentStore.ConversationSnapshot beforeSeen = database.jaAgentStore().loadConversation(
                    new AgentStore.ConversationKey("ja.local", "thr_seen", "user_local")).orElseThrow();
            assertEquals(unseen.revision(), beforeSeen.revision(),
                    "Turn commits must mirror the Store revision into the Ja Thread row");
            StorageException stale = assertThrows(StorageException.class,
                    () -> history.markThreadSeen("thr_seen", unseen.revision() - 1));
            assertEquals(StorageException.Code.CAS_CONFLICT, stale.code());

            ThreadSummary seen = history.markThreadSeen("thr_seen", unseen.revision());
            assertTrue(seen.latestTurnSeen());
            assertEquals(unseen.revision() + 1, seen.revision());
            ThreadSummary replay = history.markThreadSeen("thr_seen", unseen.revision());
            assertTrue(replay.latestTurnSeen());
            assertEquals(seen.revision(), replay.revision());

            ThreadSummary archived = history.archiveThread("thr_seen", seen.revision());
            assertTrue(archived.latestTurnSeen());
            ThreadSummary searched = history.searchThreads("ws_1", "", null, 10).items().getFirst();
            assertEquals(ThreadSummary.Status.ARCHIVED, searched.status());
            assertTrue(searched.latestTurnSeen());
            ThreadSummary restored = history.restoreThread("thr_seen", archived.revision());
            assertTrue(restored.latestTurnSeen());

            ThreadSummary renamed = history.renameThread("thr_seen", "人工命名", restored.revision());
            assertEquals("人工命名", renamed.title());
            ThreadPreferences nextPreferences = new ThreadPreferences("provider_test", "model_test", "high",
                    AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT,
                    ThreadPreferences.TitleSource.MANUAL);
            ThreadSummary preferencesUpdated = history.updatePreferences(
                    "thr_seen", nextPreferences, renamed.revision());
            assertEquals("high", preferencesUpdated.preferences().reasoningLevel());

            AgentStore.ConversationKey conversation = new AgentStore.ConversationKey("ja.local", "thr_seen", "user_local");
            long storeRevision = database.jaAgentStore().loadConversation(conversation).orElseThrow().revision();
            assertEquals(preferencesUpdated.revision(), storeRevision,
                    "Thread metadata revisions must advance the Store conversation revision in the same transaction");

            AgentStoreProjectionFixture next = admit(database, "thr_seen", "turn_after_seen",
                    preferencesUpdated.revision());
            try (next) {
                next.commit(TurnState.RUNNING, readyCursor(), List.of(), List.of(), START.plusSeconds(3));
                commitFailedTerminal(database, next, "second failure", START.plusSeconds(4));
            }
            ThreadSummary newlyUnseen = findThread(history, "thr_seen");
            assertEquals(TurnState.FAILED, newlyUnseen.latestTurnStatus());
            assertFalse(newlyUnseen.latestTurnSeen());
        }
    }

    /** 关闭只标记 Store 适配器；重复关闭不触碰由 fixture 持有的 SQLite DataSource。 */
    @Test
    void closesInjectedStoreIdempotentlyWithoutClosingDatabase() throws Exception {
        try (TestDatabase database = database("store-close-current")) {
            MybatisHistoryService history = createThreads(database, List.of("thr_close"));
            AgentStore store = database.agentStore();
            AgentStore.ConversationKey conversation = new AgentStore.ConversationKey("ja.local", "thr_close", "user_local");

            store.close();
            store.close();

            assertThrows(IllegalStateException.class, () -> store.loadConversation(conversation));
            assertEquals(1, history.listThreads("ws_1", null, 10).items().size());
            ThreadSnapshot reopened = history.readThread("thr_close", null, 10).orElseThrow();
            assertEquals("thr_close", reopened.thread().threadId());
        }
    }

    /** Revision 过期或 Turn 活动中的生命周期请求必须在 process owner 被调用前拒绝。 */
    @Test
    void staleAndBusyLifecycleRequestsDoNotCloseThreadProcesses() throws Exception {
        try (TestDatabase database = database("thread-process-lifecycle-admission")) {
            MybatisHistoryService history = createThreads(database, List.of("thr_process_guard"));
            List<ProcessSessionScope> closes = new ArrayList<>();
            List<ProcessSessionScope> reopens = new ArrayList<>();
            history.bindProcessSessions(processSessions(17, closes, reopens, false));

            StorageException stale = assertThrows(StorageException.class,
                    () -> history.deleteThread("thr_process_guard", 1));
            assertEquals(StorageException.Code.CAS_CONFLICT, stale.code());
            assertTrue(closes.isEmpty());

            AgentStoreProjectionFixture fixture = admit(database, "thr_process_guard", "turn_process_busy", 0);
            try (fixture) {
                ThreadSummary busy = findThread(history, "thr_process_guard");
                StorageException active = assertThrows(StorageException.class,
                        () -> history.deleteThread("thr_process_guard", busy.revision()));
                assertEquals(StorageException.Code.INVALID_STATE, active.code());
            }

            assertTrue(closes.isEmpty());
            assertTrue(reopens.isEmpty());
        }
    }

    /** closeThread 失败时必须恢复准入 fence，并保留未删除的 Thread 供调用方重试。 */
    @Test
    void reopensThreadAfterProcessCloseFailure() throws Exception {
        try (TestDatabase database = database("thread-process-close-failure")) {
            MybatisHistoryService history = createThreads(database, List.of("thr_process_close_failure"));
            List<ProcessSessionScope> closes = new ArrayList<>();
            List<ProcessSessionScope> reopens = new ArrayList<>();
            history.bindProcessSessions(processSessions(23, closes, reopens, true));
            ProcessSessionScope expected = new ProcessSessionScope(
                    "ws_1", "thr_process_close_failure", 23);

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> history.deleteThread("thr_process_close_failure", 0));

            assertEquals("process close failed", failure.getMessage());
            assertEquals(List.of(expected), closes);
            assertEquals(List.of(expected), reopens);
            assertEquals(ThreadSummary.Status.ACTIVE,
                    history.readThread("thr_process_close_failure", null, 10).orElseThrow().thread().status());
        }
    }

    /** 生命周期关闭使用唯一绑定 owner 的 generation 与 Thread scope，不允许后续替换 owner。 */
    @Test
    void bindsSingleProcessOwnerAndClosesExactThreadScope() throws Exception {
        try (TestDatabase database = database("thread-process-owner-scope")) {
            MybatisHistoryService history = createThreads(database, List.of("thr_process_owner"));
            List<ProcessSessionScope> closes = new ArrayList<>();
            List<ProcessSessionScope> reopens = new ArrayList<>();
            history.bindProcessSessions(processSessions(31, closes, reopens, false));

            assertThrows(IllegalStateException.class,
                    () -> history.bindProcessSessions(processSessions(99, new ArrayList<>(), new ArrayList<>(), false)));
            history.deleteThread("thr_process_owner", 0);

            assertEquals(List.of(new ProcessSessionScope("ws_1", "thr_process_owner", 31)), closes);
            assertTrue(reopens.isEmpty());
        }
    }

    /** 显式建立真实 Workspace 与 Thread；每项使用不同创建时刻，便于验证分页游标不重不漏。 */
    private MybatisHistoryService createThreads(TestDatabase database, List<String> threadIds) {
        MybatisHistoryService history = database.history();
        history.register(new Workspace.Registration("ws_1", temp.toAbsolutePath(), "workspace", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, START));
        for (int index = 0; index < threadIds.size(); index++) {
            history.createThread(new ThreadSummary.Creation(threadIds.get(index), "ws_1",
                    "thread " + (index + 1), preferences(), START.plusSeconds(index)));
        }
        return history;
    }

    /** 走真实 Ja admission 参与者建立 Store 会话与 Turn 锚点，保证后续状态来自提交事实。 */
    private AgentStoreProjectionFixture admit(TestDatabase database, String threadId,
                                               String turnId, long expectedRevision) {
        Instant acceptedAt = START.plusSeconds(10);
        AgentIdentity identity = new AgentIdentity("ja.local", threadId, turnId, "user_local");
        AgentExecutionRequest request = new AgentExecutionRequest(identity,
                AgentExecutionRequest.AdmissionKind.USER_INPUT,
                Optional.of(new UserContent(List.of(new TextContent("run")))), "item_user_" + turnId,
                Optional.empty(),
                new AgentStore.OperationIdentity("operation_" + turnId, "turn/start", "a".repeat(64),
                        new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec()
                                .decodeObject("{}")),
                Map.of(), AgentExecutionRequest.HistorySelection.currentConversation(),
                acceptedAt, List.of(), Optional.empty(), Optional.empty());
        AdmissionFacts facts = new AdmissionFacts(identity, "ws_1", TurnOrigin.USER, "turn/start", acceptedAt,
                null, null, null, null, null, null, null, expectedRevision, Optional.empty());
        return AgentStoreProjectionFixture.open(database.jaAgentStore(), database.mapper(), request,
                facts, preferences(), expectedRevision);
    }

    /** 以唯一稳定失败终态推进 Thread 摘要；失败不需要伪造最终 Assistant 正文。 */
    private static void commitFailedTerminal(TestDatabase database, AgentStoreProjectionFixture fixture,
                                             String summary, Instant occurredAt) {
        ObjectMapper mapper = database.mapper();
        AgentStore.RecordWrite changeSet = fixture.extensionFact("agent.extension.fact",
                "ja.turn:change-set:" + fixture.execution().turnId(), "ja.turn", "change-set", 1,
                new TurnChangeSetCodec(mapper).writePayload(TurnChangeSet.emptyComplete()), occurredAt);
        fixture.commitRecords(TurnState.FAILED, completionCursor(), List.of(changeSet),
                List.of(new ExecutionEvent.Terminal(TurnState.FAILED, summary, "TEST_FAILURE", summary,
                        null, null)), occurredAt);
    }

    /** 执行中游标保持 READY，测试只改变提交状态而不制造虚假的 Provider 或工具意图。 */
    private static TurnExecutionState.Ready readyCursor() {
        return new TurnExecutionState.Ready(new TurnExecutionState.Common(0, 0, 1, null, List.of()),
                TurnExecutionState.Next.ASSISTANT, null);
    }

    /** 终态游标指向完成结算，避免 Store 记录与可恢复派发位置不一致。 */
    private static TurnExecutionState.Ready completionCursor() {
        return new TurnExecutionState.Ready(new TurnExecutionState.Common(0, 0, 1, null, List.of()),
                TurnExecutionState.Next.COMPLETION, null);
    }

    /** 测试偏好为创建 Thread 时冻结的完整业务值，不从运行时默认配置补充字段。 */
    private static ThreadPreferences preferences() {
        return new ThreadPreferences("provider_test", "model_test", "medium", AccessMode.APPROVAL_REQUIRED,
                CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.PLACEHOLDER);
    }

    /** 仅实现 lifecycle 使用的进程 owner 行为，并记录完整关闭范围以检验 generation fencing。 */
    private static ProcessSessionUseCase processSessions(long generation,
            List<ProcessSessionScope> closes, List<ProcessSessionScope> reopens,
            boolean failClose) {
        return (ProcessSessionUseCase) Proxy.newProxyInstance(ProcessSessionUseCase.class.getClassLoader(),
                new Class<?>[] {ProcessSessionUseCase.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("generation")) {
                        return generation;
                    }
                    if (method.getName().equals("closeThread")) {
                        closes.add((ProcessSessionScope) arguments[0]);
                        if (failClose) {
                            throw new IllegalStateException("process close failed");
                        }
                        return null;
                    }
                    if (method.getName().equals("reopenThread")) {
                        reopens.add((ProcessSessionScope) arguments[0]);
                        return null;
                    }
                    throw new UnsupportedOperationException("unexpected ProcessSessionUseCase call: "
                            + method.getName());
                });
    }

    /** 按权威 Thread id 从完整 workspace 页查询，避免依赖排序位置推断对象身份。 */
    private static ThreadSummary findThread(MybatisHistoryService history, String threadId) {
        return history.listThreads("ws_1", null, 20).items().stream()
                .filter(value -> value.threadId().equals(threadId)).findFirst().orElseThrow();
    }
}
