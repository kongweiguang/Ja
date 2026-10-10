// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase;
import io.github.kongweiguang.ja.attachment.application.service.AttachmentService;
import io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata;
import io.github.kongweiguang.ja.attachment.infrastructure.storage.ManagedAttachmentStore;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepository;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.JaMessageIds;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.ThreadForkMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.task.MybatisTaskRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import io.github.kongweiguang.ja.plan.application.gateway.PlanCanonicalizer;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;
import io.github.kongweiguang.ja.plan.domain.PlanModels.AcceptanceCriterion;
import io.github.kongweiguang.ja.plan.domain.PlanModels.Plan;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanDefinition;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanRevision;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanStatus;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanStep;
import io.github.kongweiguang.ja.plan.infrastructure.persistence.MybatisPlanRepository;
import io.github.kongweiguang.ja.plan.infrastructure.persistence.PlanRepositorySqliteTestSupport;
import io.github.kongweiguang.ja.plan.infrastructure.persistence.serialization.CanonicalPlanJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 以真实 SQLite 验证普通 Thread fork 的冻结、回执幂等与 revision 事务边界。 */
final class MybatisThreadForkPersistenceTest extends PersistenceTestSupport {
    private static final String SOURCE_THREAD = "thr_fork_source";
    private static final String SOURCE_TURN = "turn_fork_source";
    private static final String ACTIVE_TURN = "turn_fork_active";
    private static final String SOURCE_FINAL_MESSAGE = "msg_fork_final";
    private static final String SOURCE_ITEM = JaMessageIds.toItemId(SOURCE_FINAL_MESSAGE);

    /** 重复 operation key 必须回放最初冻结的回执，不能再次冻结或创建第二个 child。 */
    @Test
    void replaysCommittedReceiptForSameOperationKey() throws Exception {
        try (TestDatabase database = database("thread-fork-replay");
             MybatisTaskRepository tasks = database.taskRepository()) {
            MybatisHistoryService history = sourceThread(database);
            seedForkableSourceReply(database);
            history.bindThreadForkSnapshots(tasks);
            ThreadUseCase.ThreadForkRequest request = request(SOURCE_ITEM, sourceRevision(history), operation("a"));

            ThreadUseCase.ThreadForkResult first = history.forkThread(request);
            ThreadUseCase.ThreadForkResult replay = history.forkThread(request);

            assertEquals(first, replay);
            assertTrue(first.source().itemId().equals(SOURCE_ITEM), "receipt must bind the selected reply item");
            assertTrue(history.readThread(first.thread().threadId(), null, 10).isPresent());
        }
    }

    /** 已提交操作键绑定完整来源元组，异参重用应在 child 初始化前稳定冲突。 */
    @Test
    void rejectsDifferentRequestForCommittedOperationKey() throws Exception {
        try (TestDatabase database = database("thread-fork-conflict");
             MybatisTaskRepository tasks = database.taskRepository()) {
            MybatisHistoryService history = sourceThread(database);
            seedForkableSourceReply(database);
            history.bindThreadForkSnapshots(tasks);
            long revision = sourceRevision(history);
            history.forkThread(request(SOURCE_ITEM, revision, operation("b")));

            StorageException conflict = assertThrows(StorageException.class,
                    () -> history.forkThread(request("item_other_source", revision, operation("b"))));

            assertEquals(StorageException.Code.CAS_CONFLICT, conflict.code());
            assertEquals(2, history.listThreads("ws_fork", null, 10).items().size());
        }
    }

    /** Child 是无执行历史和 Task 谱系的普通 Thread；首轮读取获得持久化的冻结 seed。 */
    @Test
    void createsIndependentThreadWithReadableFrozenSeed() throws Exception {
        try (TestDatabase database = database("thread-fork-seed");
             MybatisTaskRepository tasks = database.taskRepository()) {
            MybatisHistoryService history = sourceThread(database);
            seedForkableSourceReply(database);
            history.bindThreadForkSnapshots(tasks);

            ThreadUseCase.ThreadForkResult result = history.forkThread(
                    request(SOURCE_ITEM, sourceRevision(history), operation("c")));
            String childId = result.thread().threadId();
            InitialContextSeed storedSeed = history.findForFirstTurn(childId).orElseThrow();

            assertEquals(SOURCE_THREAD, storedSeed.sourceIdentity().threadId());
            assertEquals(SOURCE_TURN, storedSeed.sourceIdentity().turnId());
            assertEquals(2, storedSeed.messages().size());
            assertEquals(ContextMessage.Role.ASSISTANT, storedSeed.messages().getLast().role());
            assertFalse(childId.equals(SOURCE_THREAD));
            assertEquals(0, result.thread().revision());
            assertNull(result.thread().latestTurnId());
            assertNull(result.thread().latestTurnStatus());
            assertEquals(1, history.readThread(childId, null, 10).orElseThrow().turns().size());
            assertEquals(2, history.readThread(childId, null, 10).orElseThrow().items().size());
            try (SqlSession session = database.sessions().openSession()) {
                TaskRecords.TaskRouteRow route = PersistenceMappers.open(session).tasks().selectTaskRoute(childId);
                assertNotNull(route);
                assertNull(route.rootThreadId());
                assertNull(route.depth());
                assertFalse(route.closing());
                assertNull(PersistenceMappers.open(session).tasks().selectTaskSummary(childId));
            }
            assertTrue(database.jaAgentStore().loadConversation(
                    new AgentStore.ConversationKey("ja.local", childId, "user_local")).isPresent());
        }
    }

    /** 来源删除后按 64 字节分段读取完整 user 图片，验证 child 授权和真实 blob。 */
    @Test
    void preservesUserAttachmentsAfterSourceDeletion() throws Exception {
        try (TestDatabase database = database("thread-fork-media");
             MybatisTaskRepository tasks = database.taskRepository()) {
            MybatisHistoryService history = sourceThread(database);
            Path runDirectory = Files.createDirectories(temp.resolve("fork-media-run"));
            Path dataDirectory = Files.createDirectories(temp.resolve("fork-media-data"));
            AttachmentService attachments = new AttachmentService(database.attachments(),
                    new ManagedAttachmentStore(runDirectory, dataDirectory),
                    CLOCK, Executors.newSingleThreadScheduledExecutor(), false);
            try {
                byte[] png = java.util.Base64.getDecoder().decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/kx8AAAAASUVORK5CYII=");
                AttachmentMetadata userImage = attachments.importMcpImage("ws_fork", "user.png", "image/png", png);
                AttachmentMetadata secondUserImage = attachments.importMcpImage(
                        "ws_fork", "user-second.png", "image/png", png);
                settleSourceAttachments(database, userImage.attachmentId(), secondUserImage.attachmentId());
                ThreadSummary source = findSource(history);
                history.bindThreadForkSnapshots(tasks);

                ThreadUseCase.ThreadForkResult result = history.forkThread(request(
                        SOURCE_ITEM, source.revision(), operation("e")));
                InitialContextSeed childSeed = history.findForFirstTurn(result.thread().threadId()).orElseThrow();
                List<String> childAttachmentIds = childSeed.messages().getFirst().blocks().stream()
                        .filter(ContextMessage.AttachmentBlock.class::isInstance)
                        .map(ContextMessage.AttachmentBlock.class::cast)
                        .map(ContextMessage.AttachmentBlock::attachmentId).toList();
                assertEquals(2, childAttachmentIds.size());
                assertFalse(childAttachmentIds.contains(userImage.attachmentId()));
                assertFalse(childAttachmentIds.contains(secondUserImage.attachmentId()));

                history.deleteThread(SOURCE_THREAD, source.revision());
                for (String attachmentId : childAttachmentIds) {
                    AttachmentMetadata metadata = database.attachments().findThread(
                            attachmentId, result.thread().threadId()).orElseThrow();
                    assertNull(metadata.boundMessageId());
                    assertEquals(result.thread().threadId(), metadata.boundThreadId());
                    org.junit.jupiter.api.Assertions.assertArrayEquals(png,
                            readWholeImage(attachments, attachmentId, result.thread().threadId()));
                }
            } finally {
                attachments.close();
            }
        }
    }

    /** Freeze 后 source revision 漂移时，Store conversation 与所有 Thread fork 行必须一同回滚。 */
    @Test
    void rollsBackChildWhenSourceRevisionChangesAfterFreeze() throws Exception {
        try (TestDatabase database = database("thread-fork-revision-race");
             MybatisTaskRepository tasks = database.taskRepository()) {
            MybatisHistoryService history = sourceThread(database);
            seedForkableSourceReply(database);
            long revision = sourceRevision(history);
            AtomicInteger freezes = new AtomicInteger();
            history.bindThreadForkSnapshots(snapshotSource(tasks, freezes,
                    () -> history.renameThread(SOURCE_THREAD, "renamed during freeze", revision)));

            StorageException conflict = assertThrows(StorageException.class,
                    () -> history.forkThread(request(SOURCE_ITEM, revision, operation("d"))));

            assertEquals(StorageException.Code.CAS_CONFLICT, conflict.code());
            assertEquals(1, freezes.get());
            assertEquals(1, history.listThreads("ws_fork", null, 10).items().size());
            assertTrue(database.jaAgentStore().loadConversation(new AgentStore.ConversationKey(
                    "ja.local", childId(operation("d")), "user_local")).isEmpty());
        }
    }

    /** Seed SQL 失败须暴露稳定 TRANSACTION 错误，同时回滚 Core、Ja SQL 并允许原键重试。 */
    @Test
    void rollsBackEveryForkFactWhenSeedInsertFails() throws Exception {
        try (TestDatabase database = database("thread-fork-seed-failure");
             MybatisTaskRepository tasks = database.taskRepository()) {
            MybatisHistoryService history = sourceThread(database);
            seedForkableSourceReply(database);
            history.bindThreadForkSnapshots(tasks);
            long revision = sourceRevision(history);
            String operationKey = operation("f");
            String childThreadId = childId(operationKey);
            try (SqlSession session = database.sessions().openSession();
                 Statement ddl = session.getConnection().createStatement()) {
                ddl.executeUpdate("CREATE TRIGGER reject_thread_fork_seed BEFORE INSERT ON ja_thread_fork_seed "
                        + "BEGIN SELECT RAISE(ABORT, 'seed insert rejected'); END");
                session.commit();
            }

            StorageException failure = assertThrows(StorageException.class,
                    () -> history.forkThread(request(SOURCE_ITEM, revision, operationKey)));
            assertEquals(StorageException.Code.TRANSACTION, failure.code());

            assertEquals(1, history.listThreads("ws_fork", null, 10).items().size());
            assertTrue(database.jaAgentStore().loadConversation(
                    new AgentStore.ConversationKey("ja.local", childThreadId, "user_local")).isEmpty());
            try (SqlSession session = database.sessions().openSession()) {
                ThreadForkMapper forks = PersistenceMappers.open(session).threadForks();
                assertNull(forks.selectSeed(childThreadId));
                assertNull(forks.selectOperation(operationKey));
            }

            try (SqlSession session = database.sessions().openSession();
                 Statement ddl = session.getConnection().createStatement()) {
                ddl.executeUpdate("DROP TRIGGER reject_thread_fork_seed");
                session.commit();
            }
            ThreadUseCase.ThreadForkResult retry = history.forkThread(request(SOURCE_ITEM, revision, operationKey));
            assertEquals(childThreadId, retry.thread().threadId());
            assertTrue(history.findForFirstTurn(childThreadId).isPresent());
        }
    }

    /** Plan 测试注入 SQLite UoW owner 避开 Solon AppContext，仍验证活动审批与 PlanRun 留在来源。 */
    @Test
    void keepsPendingApprovalAndActivePlanRunWithSourceThread() throws Exception {
        try (TestDatabase database = database("thread-fork-active-state");
             MybatisTaskRepository tasks = database.taskRepository()) {
            MybatisHistoryService history = sourceThread(database);
            seedForkableSourceReply(database);
            long replyRevision = sourceRevision(history);
            try (AgentStoreProjectionFixture execution = admitSourceTurn(database, replyRevision)) {
                execution.commit(TurnState.RUNNING, readyCursor(), List.of(), List.of(), START.plusSeconds(1));
                execution.commit(TurnState.WAITING_APPROVAL, readyCursor(), List.of(), List.of(),
                        START.plusSeconds(2));
            }

            ThreadSummary source = findSource(history);
            seedPendingApprovalProjection(database, source);
            MybatisPlanRepository plans = PlanRepositorySqliteTestSupport.create(
                    database.sessions(), database.mapper());
            Plan activePlan = startPlanRun(plans, source);
            assertEquals(PlanStatus.EXECUTING, activePlan.status());
            assertEquals(SOURCE_THREAD, plans.findCurrentPlan(SOURCE_THREAD).orElseThrow().plan().ownerThreadId());

            ThreadSummary currentSource = findSource(history);
            history.bindThreadForkSnapshots(tasks);
            ThreadUseCase.ThreadForkResult fork = history.forkThread(request(
                    SOURCE_ITEM, currentSource.revision(), operation("7")));
            String childThreadId = fork.thread().threadId();

            assertEquals(1, approvalCount(database, SOURCE_THREAD));
            assertEquals(0, approvalCount(database, childThreadId));
            assertEquals(PlanStatus.EXECUTING,
                    plans.findCurrentPlan(SOURCE_THREAD).orElseThrow().plan().status());
            assertTrue(plans.findCurrentPlan(childThreadId).isEmpty());
            ThreadSnapshot child = history.readThread(childThreadId, null, 10).orElseThrow();
            assertEquals(1, child.turns().size());
            assertEquals(ThreadSnapshot.Origin.INHERITED, child.turns().getFirst().origin());
            assertEquals(2, child.items().size());
            assertTrue(child.items().stream().noneMatch(ThreadSnapshot.ApprovalItem.class::isInstance));
        }
    }

    /** 分叉锚点限定 Turn 展示时间与状态；来源之后失败或取消不能泄漏，较早真实失败仍须保留。 @author kongweiguang */
    @ParameterizedTest
    @EnumSource(value = TurnState.class, names = {"FAILED", "CANCELLED"})
    void freezesTurnProjectionAtReplyBoundaryAndRetainsEarlierFailure(TurnState laterSourceState) throws Exception {
        try (TestDatabase database = database("thread-fork-boundary-" + laterSourceState.name().toLowerCase());
             MybatisTaskRepository tasks = database.taskRepository()) {
            MybatisHistoryService history = sourceThread(database);
            seedPriorFailedTurn(database);
            history.bindThreadForkSnapshots(tasks);

            Instant acceptedAt = START.plusSeconds(3);
            Instant answerAt = START.plusSeconds(5);
            Instant laterTerminalAt = START.plusSeconds(9);
            try (AgentStoreProjectionFixture execution = admitBoundaryReply(
                    database, sourceRevision(history), acceptedAt)) {
                AgentStore.RecordWrite answer = execution.record("agent.assistant", SOURCE_FINAL_MESSAGE,
                        new ExecutionFacts.AssistantMessage(SOURCE_FINAL_MESSAGE,
                                new ModelMessage(ModelRole.ASSISTANT,
                                        List.of(new TextContent("selected answer before later terminal"))),
                                "selected answer before later terminal", null, 2), answerAt);
                execution.commitRecords(TurnState.RUNNING, readyCursor(), List.of(answer),
                        List.of(new ExecutionEvent.AssistantSettled(answer.recordId())), answerAt);

                ThreadSummary beforeLaterTerminal = findSource(history);
                ThreadUseCase.ThreadForkResult fork = history.forkThread(request(
                        SOURCE_ITEM, beforeLaterTerminal.revision(), operation("9")));
                String childThreadId = fork.thread().threadId();
                String errorCode = laterSourceState == TurnState.FAILED ? "MODEL_UNAVAILABLE" : null;
                execution.commitRecords(laterSourceState, completionCursor(), List.of(),
                        List.of(new ExecutionEvent.Terminal(laterSourceState, "later source terminal",
                                errorCode, errorCode == null ? null : "later provider failure", null, null)),
                        laterTerminalAt);

                ThreadSnapshot source = history.readThread(SOURCE_THREAD, null, 20).orElseThrow();
                ThreadSnapshot child = history.readThread(childThreadId, null, 20).orElseThrow();
                ThreadSnapshot.Turn sourceBoundary = source.turns().stream()
                        .filter(turn -> turn.turnId().equals(SOURCE_TURN)).findFirst().orElseThrow();
                ThreadSnapshot.Turn childBoundary = child.turns().stream()
                        .filter(turn -> turn.status().equals("completed")).findFirst().orElseThrow();
                ThreadSnapshot.Turn earlierFailure = child.turns().stream()
                        .filter(turn -> turn.errorCode() != null).findFirst().orElseThrow();

                assertEquals(laterSourceState.name().toLowerCase(), sourceBoundary.status());
                assertEquals(laterTerminalAt, sourceBoundary.updatedAt());
                assertEquals("completed", childBoundary.status());
                assertEquals(answerAt, childBoundary.updatedAt());
                assertEquals(answerAt, childBoundary.completedAt());
                assertNull(childBoundary.errorCode());
                assertEquals(0, childBoundary.modelRound());
                assertEquals("failed", earlierFailure.status());
                assertEquals("PRIOR_FAILURE", earlierFailure.errorCode());
                assertEquals(START.plusSeconds(2), earlierFailure.completedAt());
                assertTrue(child.turns().stream().allMatch(turn ->
                        turn.origin() == ThreadSnapshot.Origin.INHERITED));
            }
        }
    }

    /** 建立 fork 所需的权威 Workspace 与 revision 为零的来源 Thread。 */
    private MybatisHistoryService sourceThread(TestDatabase database) {
        MybatisHistoryService history = database.history();
        history.register(new Workspace.Registration("ws_fork", temp.toAbsolutePath(), "fork workspace",
                Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, START));
        history.createThread(new ThreadSummary.Creation(SOURCE_THREAD, "ws_fork", "source",
                preferences(), START));
        return history;
    }

    /** 创建截止位置之前的真实失败 Turn，用于确保分叉不会把历史终态统一洗成 completed。 @author kongweiguang */
    private void seedPriorFailedTurn(TestDatabase database) {
        String turnId = "turn_fork_prior_failure";
        Instant acceptedAt = START.plusSeconds(1);
        AgentIdentity identity = new AgentIdentity("ja.local", SOURCE_THREAD, turnId, "user_local");
        AgentExecutionRequest request = new AgentExecutionRequest(identity,
                AgentExecutionRequest.AdmissionKind.USER_INPUT,
                Optional.of(new UserContent(List.of(new TextContent("earlier failed request")))),
                "msg_fork_prior_user", Optional.empty(),
                new AgentStore.OperationIdentity("operation_fork_prior_failure", "turn/start",
                        "e".repeat(64), new JacksonJsonValueCodec().decodeObject("{}")), Map.of(),
                AgentExecutionRequest.HistorySelection.currentConversation(), acceptedAt, List.of(),
                Optional.empty(), Optional.empty());
        AdmissionFacts facts = new AdmissionFacts(identity, "ws_fork", TurnOrigin.USER, "turn/start", acceptedAt,
                null, null, null, null, null, null, null, 0, Optional.empty());
        try (AgentStoreProjectionFixture execution = AgentStoreProjectionFixture.open(database.jaAgentStore(),
                database.mapper(), request, facts, preferences(), 0)) {
            execution.commitRecords(TurnState.FAILED, completionCursor(), List.of(),
                    List.of(new ExecutionEvent.Terminal(TurnState.FAILED, "earlier failure",
                            "PRIOR_FAILURE", "earlier provider failure", null, null)), START.plusSeconds(2));
        }
    }

    /** 在指定 revision 接纳边界 Turn，留给测试先冻结答复、再提交后续终态。 @author kongweiguang */
    private AgentStoreProjectionFixture admitBoundaryReply(TestDatabase database, long expectedRevision,
                                                            Instant acceptedAt) {
        AgentIdentity identity = new AgentIdentity("ja.local", SOURCE_THREAD, SOURCE_TURN, "user_local");
        AgentExecutionRequest request = new AgentExecutionRequest(identity,
                AgentExecutionRequest.AdmissionKind.USER_INPUT,
                Optional.of(new UserContent(List.of(new TextContent("boundary reply request")))),
                "msg_fork_boundary_user", Optional.empty(),
                new AgentStore.OperationIdentity("operation_fork_boundary", "turn/start",
                        "f".repeat(64), new JacksonJsonValueCodec().decodeObject("{}")), Map.of(),
                AgentExecutionRequest.HistorySelection.currentConversation(), acceptedAt, List.of(),
                Optional.empty(), Optional.empty());
        AdmissionFacts facts = new AdmissionFacts(identity, "ws_fork", TurnOrigin.USER, "turn/start", acceptedAt,
                null, null, null, null, null, null, null, expectedRevision, Optional.empty());
        return AgentStoreProjectionFixture.open(database.jaAgentStore(), database.mapper(), request,
                facts, preferences(), expectedRevision);
    }

    /** 建立真实来源 Turn，再把它推进到需要审批的活动状态供 fork owner 边界测试。 */
    private AgentStoreProjectionFixture admitSourceTurn(TestDatabase database, long expectedRevision) {
        AgentIdentity identity = new AgentIdentity("ja.local", SOURCE_THREAD, ACTIVE_TURN, "user_local");
        Instant acceptedAt = START.plusSeconds(3);
        AgentExecutionRequest request = new AgentExecutionRequest(identity,
                AgentExecutionRequest.AdmissionKind.USER_INPUT,
                Optional.of(new UserContent(List.of(new TextContent("active tool run")))), "msg_fork_active_user",
                Optional.empty(), new AgentStore.OperationIdentity("operation_fork_active", "turn/start",
                        "b".repeat(64), new JacksonJsonValueCodec().decodeObject("{}")), Map.of(),
                AgentExecutionRequest.HistorySelection.currentConversation(), acceptedAt, List.of(),
                Optional.empty(), Optional.empty());
        AdmissionFacts facts = new AdmissionFacts(identity, "ws_fork", TurnOrigin.USER, "turn/start", acceptedAt,
                null, null, null, null, null, null, null, expectedRevision, Optional.empty());
        return AgentStoreProjectionFixture.open(database.jaAgentStore(), database.mapper(), request,
                facts, preferences(), expectedRevision);
    }

    /** Fixture 直接表示已提交的待审批 projection；其 owner FK 与活动 Turn 均由真实数据库校验。 */
    private static void seedPendingApprovalProjection(TestDatabase database, ThreadSummary source) throws Exception {
        Instant requestedAt = START.plusSeconds(3);
        Instant expiresAt = requestedAt.plusSeconds(300);
        try (SqlSession session = database.sessions().openSession()) {
            try (PreparedStatement call = session.getConnection().prepareStatement("""
                    INSERT INTO ja_tool_call(call_id,thread_id,turn_id,ordinal,tool_name,side_effect,
                    presentation_json,state,revision,created_at_ms,updated_at_ms)
                    VALUES(?,?,?,0,'shell','EXTERNAL','{"status":"pending"}','PREPARED',0,?,?)
                    """)) {
                call.setString(1, "call_fork_active_approval");
                call.setString(2, SOURCE_THREAD);
                call.setString(3, ACTIVE_TURN);
                call.setLong(4, requestedAt.toEpochMilli());
                call.setLong(5, requestedAt.toEpochMilli());
                assertEquals(1, call.executeUpdate());
            }
            try (PreparedStatement approval = session.getConnection().prepareStatement("""
                    INSERT INTO ja_approval(approval_id,thread_id,turn_id,call_id,decision,
                    requested_at_ms,resolved_at_ms,expires_at_ms) VALUES(?,?,?, ?,NULL,?,NULL,?)
                    """)) {
                approval.setString(1, "approval_fork_active");
                approval.setString(2, SOURCE_THREAD);
                approval.setString(3, ACTIVE_TURN);
                approval.setString(4, "call_fork_active_approval");
                approval.setLong(5, requestedAt.toEpochMilli());
                approval.setLong(6, expiresAt.toEpochMilli());
                assertEquals(1, approval.executeUpdate());
            }
            session.commit();
        }
        assertEquals(TurnState.WAITING_APPROVAL, source.latestTurnStatus());
        assertEquals(1, approvalCount(database, SOURCE_THREAD));
    }

    /** 通过公开 Plan 仓储建立真实执行 Run，确认 fork 不能重写其 owner Thread。 */
    private static Plan startPlanRun(MybatisPlanRepository plans, ThreadSummary source) {
        Plan created = plans.createPlan(new PlanRepository.CreatePlan("plan_fork_active", SOURCE_THREAD,
                "源会话的活动计划", source.revision(), "create:fork-active", START.plusSeconds(4)));
        PlanDefinition definition = new PlanDefinition("源会话活动计划", List.of("只属于来源"),
                List.of(), List.of(), List.of(),
                List.of(new PlanStep("step_work", "实施", "执行来源计划", true, List.of())),
                List.of(new AcceptanceCriterion("criterion_done", "来源计划完成", true)),
                List.of(), List.of("核实完成"));
        PlanCanonicalizer.Encoded canonical = new CanonicalPlanJson(new ObjectMapper()).encode(definition);
        PlanRevision revision = new PlanRevision("revision_fork_active", created.planId(), 1, definition,
                canonical.json(), canonical.sha256(), "AGENT", START.plusSeconds(5));
        plans.propose(new PlanRepository.ProposePlan(created.planId(), created.revision(), revision,
                "event_fork_active_proposed", "propose:fork-active", START.plusSeconds(5)));
        Plan awaiting = plans.readPlanSnapshot(created.planId()).plan();
        return plans.executePlan(new PlanRepository.ExecutePlan(awaiting.planId(), awaiting.revision(),
                revision.planRevisionId(), revision.planHash(), "approval_fork_active_plan", "run_fork_active",
                1, "event_fork_active_run", "execute:fork-active", START.plusSeconds(6)));
    }

    /** 按 Ja projection owner 查询仍未决的审批行数，确保 child 未继承来源授权。 */
    private static int approvalCount(TestDatabase database, String threadId) throws Exception {
        try (SqlSession session = database.sessions().openSession();
             PreparedStatement query = session.getConnection().prepareStatement(
                     "SELECT COUNT(*) FROM ja_approval WHERE thread_id=? AND decision IS NULL")) {
            query.setString(1, threadId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new AssertionError("missing approval count");
                }
                return rows.getInt(1);
            }
        }
    }

    /** 分段遵循 64 字节读取上限并按 next offset 重组，避免把首块误当成整个图片。 */
    private static byte[] readWholeImage(AttachmentService attachments, String attachmentId, String threadId) {
        java.io.ByteArrayOutputStream content = new java.io.ByteArrayOutputStream();
        long offsetBytes = 0;
        while (true) {
            AttachmentUseCase.ReadResult part = attachments.read(new AttachmentUseCase.ReadRequest(
                    attachmentId, threadId, offsetBytes, 64));
            assertEquals(offsetBytes, part.offsetBytes());
            content.writeBytes(java.util.Base64.getDecoder().decode(part.content()));
            if (part.endOfFile()) {
                return content.toByteArray();
            }
            assertTrue(part.nextOffsetBytes() > offsetBytes, "nonterminal attachment range must advance");
            offsetBytes = part.nextOffsetBytes();
        }
    }

    /** 构造真实已结算回复；测试的 seed 必须来自 Core 历史而不是代理返回的合成上下文。 */
    private static void seedForkableSourceReply(TestDatabase database) {
        Instant acceptedAt = START.plusSeconds(1);
        AgentIdentity identity = new AgentIdentity("ja.local", SOURCE_THREAD, SOURCE_TURN, "user_local");
        AgentExecutionRequest request = new AgentExecutionRequest(identity,
                AgentExecutionRequest.AdmissionKind.USER_INPUT,
                Optional.of(new UserContent(List.of(new TextContent("source question")))), "msg_fork_source_user",
                Optional.empty(), new AgentStore.OperationIdentity("operation_fork_source", "turn/start",
                        "c".repeat(64), new JacksonJsonValueCodec().decodeObject("{}")), Map.of(),
                AgentExecutionRequest.HistorySelection.currentConversation(), acceptedAt, List.of(),
                Optional.empty(), Optional.empty());
        AdmissionFacts facts = new AdmissionFacts(identity, "ws_fork", TurnOrigin.USER, "turn/start", acceptedAt,
                null, null, null, null, null, null, null, 0, Optional.empty());
        try (AgentStoreProjectionFixture execution = AgentStoreProjectionFixture.open(database.jaAgentStore(),
                database.mapper(), request, facts, preferences(), 0)) {
            AgentStore.RecordWrite assistant = execution.record("agent.assistant", SOURCE_FINAL_MESSAGE,
                    new ExecutionFacts.AssistantMessage(SOURCE_FINAL_MESSAGE,
                            new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("selected source reply"))),
                            "selected source reply", null, 1), START.plusSeconds(2));
            execution.commitRecords(TurnState.COMPLETED, completionCursor(),
                    List.of(assistant, execution.knownEmptyChangeSet(START.plusSeconds(2))),
                    List.of(new ExecutionEvent.AssistantSettled(assistant.recordId()),
                            new ExecutionEvent.Terminal(TurnState.COMPLETED, "done", null, null,
                                    assistant.recordId(), null)), START.plusSeconds(2));
        }
    }

    /** Fork revision 总从实际 Thread owner 行读取，避免测试把零版本与执行投影变化耦合。 */
    private static long sourceRevision(MybatisHistoryService history) {
        return findSource(history).revision();
    }

    /** 只在真实冻结完成后注入来源 revision 变化，保持竞态测试覆盖真实 Core 截断路径。 */
    private static TaskRepository snapshotSource(TaskRepository delegate, AtomicInteger calls,
                                                 Runnable afterFreeze) {
        return (TaskRepository) Proxy.newProxyInstance(TaskRepository.class.getClassLoader(),
                new Class<?>[] {TaskRepository.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("freezeThreadForkContext")) {
                        calls.incrementAndGet();
                        TaskModels.ThreadForkContextSnapshot frozen = delegate.freezeThreadForkContext(
                                (String) arguments[0], (String) arguments[1], (long) arguments[2]);
                        if (afterFreeze != null) {
                            afterFreeze.run();
                        }
                        return frozen;
                    }
                    throw new UnsupportedOperationException("unexpected TaskRepository call: " + method.getName());
                });
    }

    /** 正常准入并结算带两个受管附件的回复，确保附件 owner 与准确边界由 SQLite 事实证明。 */
    private void settleSourceAttachments(TestDatabase database, String userAttachmentId,
                                         String toolAttachmentId) {
        Instant acceptedAt = START.plusSeconds(10);
        AgentIdentity identity = new AgentIdentity("ja.local", SOURCE_THREAD, SOURCE_TURN, "user_local");
        UserContent input = new UserContent(List.of(new AttachmentContent(userAttachmentId),
                new AttachmentContent(toolAttachmentId)));
        AgentExecutionRequest request = new AgentExecutionRequest(identity,
                AgentExecutionRequest.AdmissionKind.USER_INPUT, Optional.of(input), "item_fork_user",
                Optional.empty(), new AgentStore.OperationIdentity("operation_fork_user", "turn/start",
                        "a".repeat(64), new JacksonJsonValueCodec().decodeObject("{}")), Map.of(),
                AgentExecutionRequest.HistorySelection.currentConversation(), acceptedAt, List.of(),
                Optional.empty(), Optional.empty());
        AdmissionFacts facts = new AdmissionFacts(identity, "ws_fork", TurnOrigin.USER, "turn/start", acceptedAt,
                null, null, null, null, null, null, null, 0, Optional.empty());
        try (AgentStoreProjectionFixture execution = AgentStoreProjectionFixture.open(database.jaAgentStore(),
                database.mapper(), request, facts, preferences(), 0)) {
            AgentStore.RecordWrite assistant = execution.record("agent.assistant", SOURCE_FINAL_MESSAGE,
                    new ExecutionFacts.AssistantMessage(SOURCE_FINAL_MESSAGE,
                            new ModelMessage(ModelRole.ASSISTANT,
                                    List.of(new TextContent("selected answer with image attachments"))),
                            "selected answer with image attachments", null, 1), START.plusSeconds(12));
            execution.commitRecords(TurnState.COMPLETED, completionCursor(),
                    List.of(assistant, execution.knownEmptyChangeSet(START.plusSeconds(12))),
                    List.of(new ExecutionEvent.AssistantSettled(assistant.recordId()),
                            new ExecutionEvent.Terminal(TurnState.COMPLETED, "done", null, null,
                                    assistant.recordId(), null)), START.plusSeconds(12));
        }
    }


    /** 找回来源 Thread 最新 revision，终态 Turn 的状态 projection 已同步到 Ja 行。 */
    private static ThreadSummary findSource(MybatisHistoryService history) {
        return history.listThreads("ws_fork", null, 10).items().stream()
                .filter(thread -> thread.threadId().equals(SOURCE_THREAD)).findFirst().orElseThrow();
    }

    /** 运行中的执行只推进到可恢复的下一 Assistant 步骤。 */
    private static TurnExecutionState.Ready readyCursor() {
        return new TurnExecutionState.Ready(new TurnExecutionState.Common(0, 0, 1, null, List.of()),
                TurnExecutionState.Next.ASSISTANT, null);
    }

    /** 失败结算游标必须与 Terminal 事实指向同一 completion 边界。 */
    private static TurnExecutionState.Ready completionCursor() {
        return new TurnExecutionState.Ready(new TurnExecutionState.Common(0, 0, 1, null, List.of()),
                TurnExecutionState.Next.COMPLETION, null);
    }

    /** 固定 fork 请求身份，确保同一操作键只变化需要被测试的请求维度。 */
    private static ThreadUseCase.ThreadForkRequest request(String turnId, long revision, String operationKey) {
        return new ThreadUseCase.ThreadForkRequest(SOURCE_THREAD, turnId, revision, operationKey);
    }

    /** 仅接受一个小写十六进制 fixture 字符，避免测试绕过持久 RPC operation key 契约。 */
    private static String operation(String suffix) {
        if (suffix == null || !suffix.matches("[0-9a-f]")) {
            throw new IllegalArgumentException("operation suffix must be one lowercase hex digit");
        }
        return "op_" + suffix.repeat(32);
    }

    /** 与测试 Thread 一致地推导稳定 child ID，供 rollback 断言检查 Core Store。 */
    private static String childId(String operationKey) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("thread-fork\0" + operationKey).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "thr_" + java.util.HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** 所有测试 Thread 使用完整明确偏好，避免默认配置掩盖创建时冻结语义。 */
    private static ThreadPreferences preferences() {
        return new ThreadPreferences("provider_test", "model_test", "medium", AccessMode.APPROVAL_REQUIRED,
                CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.PLACEHOLDER);
    }
}
