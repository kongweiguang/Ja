// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.AssistantMessage;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.TurnExecutionStateCodec;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.SubagentPolicy;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.collaboration.infrastructure.acl.TaskAdmissionTransactionParticipant;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.collaboration.domain.model.ThreadDefinition;
import io.github.kongweiguang.ja.collaboration.application.gateway.ConversationAcl;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepositoryException;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实 SQLite AgentStore 验证 Task 终态、Ja 投影与最终答复同提交或同回滚。 */
final class TaskTerminalProjectionTest extends PersistenceTestSupport {
    private static final String WORKSPACE = "ws_terminal_projection";
    private static final String ROOT = "thr_terminal_projection_root";
    private static final String PARENT_TURN = "turn_terminal_projection_parent";

    /** 长中文与补充平面字符只截短 Task 预览；完整最终答复和 Core 消息仍在同一事务中保留。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void boundsTerminalTaskPreviewWithoutTruncatingFinalAnswer(boolean explicitSummary) throws Exception {
        try (TestDatabase database = database("task-terminal-long-preview")) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            AgentStore store = database.jaAgentStore();
            admitNonTerminalParent(database, store);
            long parentRevision = database.history().readThread(ROOT, null, 20).orElseThrow().thread().revision();
            String childId = "thr_terminal_projection_long";
            String body = "\u957f\ud83d\ude80".repeat(35_000);
            try (AgentStoreProjectionFixture child = admitChild(database, store, tasks, childId, parentRevision)) {
                complete(child, explicitSummary ? body : "", body, START.plusSeconds(10));
                TaskModels.Detail detail = tasks.readTask(childId, 0, 0, 20).orElseThrow();
                String preview = detail.task().projection().latestSafeSummary();

                assertEquals(4_096, preview.codePointCount(0, preview.length()));
                assertTrue(preview.getBytes(StandardCharsets.UTF_8).length <= 32_768);
                assertTrue(body.startsWith(preview));
                assertEquals(new JsonText(preview), detail.activities().getLast().summary().get("text"));
                assertEquals(body, detail.mailbox().getFirst().content().text());
                assertTrue(store.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(child.execution(),
                                0, Long.MAX_VALUE, 100)).records().stream()
                        .filter(record -> record.recordType().equals("agent.assistant"))
                        .map(record -> new StorePayloadCodec(database.mapper()).decode(record))
                        .anyMatch(fact -> fact instanceof AssistantMessage assistant
                                && body.equals(assistant.publicText())));
            }
        }
    }

    /**
     * 成功终态必须在同一个 Store commit 中写入持久 Terminal 事件、Ja Turn、Task Activity 与 FINAL_ANSWER。
     */
    @Test
    void commitsTaskProjectionAndFinalAnswerWithAgentTerminalReceipt() throws Exception {
        try (TestDatabase database = database("task-terminal-atomic")) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            AgentStore store = database.jaAgentStore();
            admitNonTerminalParent(database, store);
            long parentRevision = database.history().readThread(ROOT, null, 20).orElseThrow().thread().revision();
            String childId = "thr_terminal_projection_child";
            try (AgentStoreProjectionFixture child = admitChild(database, store, tasks, childId, parentRevision)) {
                AgentStore.CommitReceipt receipt = complete(child, "最终答复", "最终答复",
                        START.plusSeconds(10));

                assertTrue(receipt.newlyCommitted());
                assertEquals(TurnState.COMPLETED.name(), store.loadExecution(child.execution()).orElseThrow().state());
                assertTrue(store.reconcile(terminalCommitIdentity(child, START.plusSeconds(10))).isPresent());
                AgentStore.EventPage events = store.readEvents(AgentStore.EventQuery.latestWithDefaultMaxBytes(
                        child.execution().conversation(), 0, 100));
                assertTrue(events.events().stream().anyMatch(event ->
                        event.eventType().equals("agent.execution.terminal")));

                ThreadSnapshot history = database.history().readThread(childId, null, 20).orElseThrow();
                assertEquals(TurnState.COMPLETED.name().toLowerCase(Locale.ROOT),
                        history.turns().getFirst().status());
                TaskModels.Detail detail = tasks.readTask(childId, 0, 0, 20).orElseThrow();
                assertEquals(TaskModels.State.COMPLETED, detail.task().projection().state());
                assertNotNull(detail.task().projection().completedAt());
                assertEquals(TaskModels.ActivityKind.COMPLETED, detail.activities().getLast().kind());
                assertEquals(1, detail.mailbox().size());
                assertEquals(TaskModels.MailboxKind.FINAL_ANSWER, detail.mailbox().getFirst().kind());
                assertEquals(childId, detail.mailbox().getFirst().senderThreadId());
                assertEquals(ROOT, detail.mailbox().getFirst().targetThreadId());
                assertEquals("最终答复", detail.mailbox().getFirst().content().text());
            }
        }
    }

    /**
     * FINAL_ANSWER 幂等键冲突发生在同一提交内时，AgentStore 的 Terminal、Ja Turn 和 Task Activity 全部回滚。
     */
    @Test
    void rollsBackStoreAndJaTerminalWhenTaskFinalAnswerConflicts() throws Exception {
        try (TestDatabase database = database("task-terminal-rollback")) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            AgentStore store = database.jaAgentStore();
            admitNonTerminalParent(database, store);
            long parentRevision = database.history().readThread(ROOT, null, 20).orElseThrow().thread().revision();
            String childId = "thr_terminal_projection_conflict";
            try (AgentStoreProjectionFixture child = admitChild(database, store, tasks, childId, parentRevision)) {
                TaskModels.Summary before = tasks.findTask(childId).orElseThrow();
                tasks.enqueueMessage(new TaskModels.MailboxEnvelope("msg_terminal_conflict", childId, ROOT,
                        child.execution().turnId(), TaskModels.MailboxKind.MESSAGE,
                        content("不同的既存事实"), TaskTerminalPersistence.terminalOperationKey(
                        child.execution().turnId()), START.plusSeconds(9)));

                TaskRepositoryException failure = assertThrows(TaskRepositoryException.class,
                        () -> complete(child, "最终答复", "最终答复", START.plusSeconds(10)));

                assertEquals(TaskRepositoryException.Code.CAS_CONFLICT, failure.code());
                assertEquals(TurnState.QUEUED.name(), store.loadExecution(child.execution()).orElseThrow().state());
                assertEquals(before.projection().state(), tasks.findTask(childId).orElseThrow().projection().state());
                assertEquals(before.projection().revision(), tasks.findTask(childId).orElseThrow().projection().revision());
                assertEquals(1, tasks.readTask(childId, 0, 0, 20).orElseThrow().mailbox().size());
                assertFalse(database.history().readThread(childId, null, 20).orElseThrow().turns().getFirst()
                        .status().equals(TurnState.COMPLETED.name().toLowerCase(Locale.ROOT)));
                assertFalse(store.readEvents(AgentStore.EventQuery.latestWithDefaultMaxBytes(
                        child.execution().conversation(), 0, 100))
                        .events().stream().anyMatch(event -> event.eventType().equals("agent.execution.terminal")));
                assertFalse(store.reconcile(terminalCommitIdentity(child, START.plusSeconds(10))).isPresent());
            }
        }
    }

    /** Task Thread、父会话和 workspace 在 History owner 中创建，避免测试绕过正式初始化路径。 */
    private void setupRoot(TestDatabase database) {
        database.history().register(new Workspace.Registration(WORKSPACE, temp.toAbsolutePath(), "terminal test", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, START));
        database.history().createThread(new ThreadSummary.Creation(ROOT, WORKSPACE, "Root",
                ConversationTestFixtures.preferences(), START));
        assertNotNull(database.history().readThread(ROOT, null, 20).orElseThrow());
    }

    /** 父 USER Turn 先通过 AgentStore 准入并保持非终态，Child admission 才能合法引用来源 Turn。 */
    private void admitNonTerminalParent(TestDatabase database, AgentStore store) {
        AgentStoreProjectionFixture parent = openUser(database, store, ROOT, PARENT_TURN, "item_terminal_parent",
                content("创建子任务"), START, 0, "turn/start");
        try (parent) {
            assertEquals(TurnState.QUEUED.name(), store.loadExecution(parent.execution()).orElseThrow().state());
        }
    }

    /** Child Task 通过两阶段 admission participant 与 Core/Ja 投影共同写入一笔交易。 */
    private AgentStoreProjectionFixture admitChild(TestDatabase database, AgentStore store,
                                                   MybatisTaskRepository tasks, String childId,
                                                   long parentRevision) {
        Instant at = START.plusSeconds(5);
        ThreadPreferences preferences = ConversationTestFixtures.preferences();
        UserContent brief = content("执行并返回最终结果");
        TaskModels.ChildAdmission child = new TaskModels.ChildAdmission(
                new ThreadDefinition(childId, WORKSPACE, "Task child", ConversationAcl.fromConversation(preferences), at),
                ROOT, parentRevision, PARENT_TURN, "Terminal Task", TaskModels.Kind.SUBAGENT,
                TaskModels.Lifecycle.ATTACHED,
                new TaskModels.ContextSeedDraft("seed_terminal_" + childId.substring(childId.lastIndexOf('_') + 1),
                        ROOT, PARENT_TURN, parentRevision, TaskModels.InheritanceMode.BRIEF_ONLY, brief, null,
                        new JsonArray(List.of()), permission(), at),
                "activity_terminal_created_" + childId.substring(childId.lastIndexOf('_') + 1),
                textSummary("子任务已创建"));
        AdmissionFacts facts = new AdmissionFacts(identity(childId, "turn_terminal_child"), WORKSPACE,
                TurnOrigin.CHILD_TASK, "task/create", at, null, PARENT_TURN, PARENT_TURN,
                null, null, null, null, 0,
                Optional.of(new AdmissionFacts.ThreadInitialization(WORKSPACE, "Task child", preferences, at)));
        TaskAdmissionTransactionParticipant business = TaskAdmissionTransactionParticipant.forChild(tasks, facts,
                child, SubagentPolicy.defaultPolicy(), new StorePayloadCodec(database.mapper()),
                new TurnExecutionStateCodec(database.mapper()));
        return AgentStoreProjectionFixture.open(store, database.mapper(), request(facts.identity(),
                        "turn_terminal_child", "item_terminal_child", brief, at, "task/create"), facts,
                preferences, 0, business);
    }

    /** 构造受真实 JaAdmissionParticipant 校验的 USER 请求，身份与请求指纹均固定。 */
    private AgentStoreProjectionFixture openUser(TestDatabase database, AgentStore store, String threadId,
                                                 String turnId, String messageId, UserContent content,
                                                 Instant at, long revision, String method) {
        AgentIdentity identity = identity(threadId, turnId);
        AdmissionFacts facts = new AdmissionFacts(identity, WORKSPACE, TurnOrigin.USER, method, at,
                null, null, null, null, null, null, null, revision, Optional.empty());
        return AgentStoreProjectionFixture.open(store, database.mapper(),
                request(identity, turnId, messageId, content, at, method), facts,
                ConversationTestFixtures.preferences(), revision);
    }

    /** 请求与 Ja admission 共享方法和 acceptedAt，避免 fixture 为测试猜测来源身份。 */
    private static io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest request(
            AgentIdentity identity, String turnId, String messageId, UserContent content, Instant at, String method) {
        return new io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest(identity,
                io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest.AdmissionKind.USER_INPUT,
                Optional.of(content), messageId, Optional.empty(),
                new AgentStore.OperationIdentity("operation_" + turnId, method, "a".repeat(64), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")),
                Map.of(), io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest.HistorySelection
                .currentConversation(), at, List.of(), Optional.empty(), Optional.empty());
    }

    /** Core Terminal event 是唯一终态来源；Ja Turn 和 Task 投影应由同一事件提交派生。 */
    private static AgentStore.CommitReceipt complete(AgentStoreProjectionFixture fixture,
                                                     String terminalSummary, String publicText, Instant at) {
        String turnId = fixture.execution().turnId();
        AgentStore.RecordWrite assistant = fixture.record("agent.assistant", "terminal-assistant",
                new AssistantMessage("msg_terminal_" + turnId.substring(turnId.lastIndexOf('_') + 1),
                        new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent(publicText))),
                        publicText, null, 1), at);
        return fixture.commitRecords(TurnState.COMPLETED, completionCursor(),
                List.of(assistant, fixture.knownEmptyChangeSet(at)),
                List.of(new ExecutionEvent.Terminal(TurnState.COMPLETED, terminalSummary, null, null,
                        assistant.recordId(), null)), at);
    }

    /** 精确构造 fixture 使用的提交身份，以便检查提交回执而不把它误当准入操作回执。 */
    private static AgentStore.CommitIdentity terminalCommitIdentity(AgentStoreProjectionFixture fixture,
                                                                    Instant occurredAt) {
        String key = "commit_projection_0";
        String fingerprint = sha256(fixture.execution().turnId() + ":" + key + ":"
                + TurnState.COMPLETED + ":" + occurredAt);
        return new AgentStore.CommitIdentity(fixture.execution(), key, fingerprint);
    }

    /** 保持测试侧精确复现 fixture 的 UTF-8 SHA-256 计算，避免通过宽松查询伪造提交已持久化。 */
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }

    /** 终态游标和 Terminal event 必须共同指向已完成，而不是仍待下一次模型调用。 */
    private static TurnExecutionState.Ready completionCursor() {
        return new TurnExecutionState.Ready(new TurnExecutionState.Common(0, 0, 1, null, List.of()),
                TurnExecutionState.Next.COMPLETION, null);
    }

    /** 测试库工厂为 TaskRepo 装配 raw Store 与事务 owner，终态仍借用 participant 的当前连接。 */
    private static MybatisTaskRepository repository(TestDatabase database) {
        return database.taskRepository();
    }

    /** 不可变 Task user 内容只用文本块，确保 FINAL_ANSWER 不附带来源不明的 Skill 或资源权限。 */
    private static UserContent content(String text) {
        return new UserContent(List.of(new TextContent(text)));
    }

    /** 任务权限 fixture 明确限制为需审批，避免终态测试依赖宿主默认策略。 */
    private static io.github.kongweiguang.ja.agent.domain.json.JsonObject permission() {
        return JsonObjects.builder().putText("version", "task_access_v1")
                .putText("accessMode", "approval_required").build();
    }

    /** Activity 摘要只保存稳定的安全文本字段，不将错误对象或内部堆栈写入业务记录。 */
    private static io.github.kongweiguang.ja.agent.domain.json.JsonObject textSummary(String text) {
        return JsonObjects.builder().putText("text", text).build();
    }

    /** Ja 业务身份固定采用本地 tenant/app caller，不把 workspace 或任务类型放进 Core execution identity。 */
    private static AgentIdentity identity(String threadId, String turnId) {
        return new AgentIdentity("ja.local", threadId, turnId, "user_local");
    }
}


