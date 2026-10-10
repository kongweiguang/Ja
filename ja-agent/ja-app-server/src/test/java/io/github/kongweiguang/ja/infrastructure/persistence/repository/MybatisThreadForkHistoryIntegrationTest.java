// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference;
import io.github.kongweiguang.ja.agent.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase;
import io.github.kongweiguang.ja.attachment.application.service.AttachmentService;
import io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata;
import io.github.kongweiguang.ja.attachment.infrastructure.storage.ManagedAttachmentStore;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.task.MybatisTaskRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionPayloadCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.store.ToolPresentationCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 以真实 Core 事实、Ja 事务投影和 Task 冻结器验证回复级分叉，避免代理快照掩盖身份与正文截断错误。 */
final class MybatisThreadForkHistoryIntegrationTest extends PersistenceTestSupport {
    private static final String WORKSPACE = "ws_fork_history";
    private static final String SOURCE = "thr_fork_history_source";
    private static final String TURN = "turn_fork_history_source";
    private static final String FIRST_USER = "先处理第一条问题😀";
    private static final String LATER_USER = "LATER_USER_MUST_BE_EXCLUDED";
    private static final String LATER_FINAL = "LATER_FINAL_MUST_BE_EXCLUDED";
    private static final ThreadPreferences PREFERENCES = new ThreadPreferences(
            "provider_test", "model_test", "medium", AccessMode.APPROVAL_REQUIRED,
            CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.PLACEHOLDER);

    /**
     * 同一 Turn 仍运行时只继承选中 STOP 之前的事实；长正文必须来自 child 自己的冻结存储，不能依赖已删除来源。
     * @author kongweiguang
     */
    @Test
    void freezesEarlierSettledReplyWhileSameTurnRunsAndReadsLongBodyAfterSourceDeletion() throws Exception {
        try (TestDatabase database = database("fork-history-long-boundary");
             AgentStore store = database.jaAgentStore();
             MybatisTaskRepository tasks = database.taskRepository()) {
            MybatisHistoryService history = sourceHistory(database, tasks);
            String longBody = longBody();
            try (AgentStoreProjectionFixture run = admit(database, store, history, SOURCE, TURN,
                    "msg_history_user_first", FIRST_USER, START.plusSeconds(1))) {
                Instant sameMillisecond = START.plusSeconds(1);
                settleReply(run, "msg_history_final_first", longBody, 1, sameMillisecond);
                appendUser(database, run, "msg_history_user_later", LATER_USER, sameMillisecond);
                settleReply(run, "msg_history_final_later", LATER_FINAL, 2, sameMillisecond);
                appendToolIntent(database, run, sameMillisecond);

                ThreadSnapshot source = snapshot(history, SOURCE);
                assertEquals(TurnState.RUNNING, source.thread().latestTurnStatus());
                assertEquals(2, finalItems(source).size());
                assertTrue(source.items().stream().anyMatch(item -> unwrap(item) instanceof ThreadSnapshot.ToolItem));
                ThreadSnapshot.TextItem chosen = finalByPrefix(source, "长正文开头");
                assertTrue(chosen.truncated());
                assertEquals(longBody.codePointCount(0, longBody.length()), chosen.totalCharacters());
                assertEquals(longBody, readWholeBody(history, SOURCE, chosen.itemId()));

                ThreadUseCase.ThreadForkResult fork = fork(history, SOURCE, chosen.itemId(), '1');
                ThreadSnapshot child = snapshot(history, fork.thread().threadId());
                assertInheritedOnly(child);
                assertNull(child.thread().latestTurnId());
                assertNull(child.thread().latestTurnStatus());
                assertEquals(0, child.thread().revision());
                assertEquals(1, finalItems(child).size());
                assertEquals(List.of(ThreadSnapshot.UserInputItem.class, ThreadSnapshot.TextItem.class),
                        child.items().stream().map(MybatisThreadForkHistoryIntegrationTest::unwrap)
                                .map(Object::getClass).toList());
                assertTrue(child.items().stream().noneMatch(item -> unwrap(item) instanceof ThreadSnapshot.ToolItem));
                assertFalse(publicBodies(history, child).contains(LATER_USER));
                assertFalse(publicBodies(history, child).contains(LATER_FINAL));
                assertTrue(publicBodies(history, child).contains(FIRST_USER));
                assertIndependentIdentities(source, child);
                ThreadSnapshot.TextItem inherited = finalByPrefix(child, "长正文开头");
                assertEquals(longBody, readWholeBody(history, child.thread().threadId(), inherited.itemId()));
                InitialContextSeed seed = history.findForFirstTurn(child.thread().threadId()).orElseThrow();
                assertEquals(2, seed.messages().size());
                assertEquals(ContextMessage.Role.ASSISTANT, seed.messages().getLast().role());
                assertFalse(seed.messages().getLast().hasToolCall());
                assertTrue(seed.messages().stream().noneMatch(message -> message.blocks().stream()
                        .anyMatch(block -> block instanceof ContextMessage.ToolCallBlock)));

                cancel(run, START.plusSeconds(6));
                history.deleteThread(SOURCE, snapshot(history, SOURCE).thread().revision());
                assertTrue(history.readThread(SOURCE, null, 50).isEmpty());
                assertEquals(longBody, readWholeBody(history, child.thread().threadId(), inherited.itemId()));
                assertInheritedOnly(snapshot(history, child.thread().threadId()));
            }
        }
    }

    /**
     * 未发送首条输入的 child 也可从继承回复再次分叉；后续本地回复继续 fork 时原始前缀只能出现一次。
     * @author kongweiguang
     */
    @Test
    void reforksInheritedHistoryBeforeFirstInputAndNativeReplyWithoutDuplicatingPrefix() throws Exception {
        try (TestDatabase database = database("fork-history-refork");
             AgentStore store = database.jaAgentStore();
             MybatisTaskRepository tasks = database.taskRepository()) {
            MybatisHistoryService history = sourceHistory(database, tasks);
            try (AgentStoreProjectionFixture run = admit(database, store, history, SOURCE, TURN,
                    "msg_history_refork_user", FIRST_USER, START.plusSeconds(1))) {
                settleReply(run, "msg_history_refork_first", "FIRST_REPLY_REFORK😀", 1, START.plusSeconds(2));
                appendUser(database, run, "msg_history_refork_later_user", LATER_USER, START.plusSeconds(3));
                settleReply(run, "msg_history_refork_later", LATER_FINAL, 2, START.plusSeconds(4));
                ThreadSnapshot source = snapshot(history, SOURCE);
                ThreadUseCase.ThreadForkResult branch = fork(history, SOURCE,
                        finalByPrefix(source, LATER_FINAL).itemId(), '2');
                ThreadSnapshot branchHistory = snapshot(history, branch.thread().threadId());
                assertInheritedOnly(branchHistory);
                assertIndependentIdentities(source, branchHistory);

                ThreadUseCase.ThreadForkResult early = fork(history, branch.thread().threadId(),
                        finalByPrefix(branchHistory, "FIRST_REPLY_REFORK").itemId(), '3');
                ThreadSnapshot earlyHistory = snapshot(history, early.thread().threadId());
                assertInheritedOnly(earlyHistory);
                assertEquals(2, earlyHistory.items().size());
                assertFalse(publicBodies(history, earlyHistory).contains(LATER_USER));
                assertFalse(publicBodies(history, earlyHistory).contains(LATER_FINAL));
                assertIndependentIdentities(branchHistory, earlyHistory);

                String branchUser = "BRANCH_NATIVE_USER";
                String branchReply = "BRANCH_NATIVE_REPLY😀";
                try (AgentStoreProjectionFixture childRun = admit(database, store, history, branch.thread().threadId(),
                        "turn_history_branch", "msg_history_branch_user", branchUser, START.plusSeconds(5))) {
                    settleReply(childRun, "msg_history_branch_reply", branchReply, 1, START.plusSeconds(6));
                    ThreadSnapshot withNative = snapshot(history, branch.thread().threadId());
                    ThreadUseCase.ThreadForkResult descendant = fork(history, branch.thread().threadId(),
                            finalByPrefix(withNative, branchReply).itemId(), '4');
                    ThreadSnapshot descendantHistory = snapshot(history, descendant.thread().threadId());
                    assertInheritedOnly(descendantHistory);
                    assertEquals(6, descendantHistory.items().size());
                    assertEquals(3, finalItems(descendantHistory).size());
                    assertIndependentIdentities(withNative, descendantHistory);
                    String publicText = publicBodies(history, descendantHistory);
                    assertEquals(1, occurrences(publicText, FIRST_USER));
                    assertEquals(1, occurrences(publicText, "FIRST_REPLY_REFORK😀"));
                    assertEquals(1, occurrences(publicText, LATER_USER));
                    assertEquals(1, occurrences(publicText, LATER_FINAL));
                    assertEquals(1, occurrences(publicText, branchUser));
                    assertEquals(1, occurrences(publicText, branchReply));
                    assertEquals(6, history.findForFirstTurn(descendant.thread().threadId()).orElseThrow()
                            .messages().size());
                }
            }
        }
    }

    /**
     * 用户附件和已闭合 Tool 图片都必须转为 child 的独立授权；seed 之后的 SQL 故障不能留下任何孤儿投影或元数据。
     * @author kongweiguang
     */
    @Test
    void freezesRealUserAndToolImagesAtomicallyAndReforksAfterSourceDeletion() throws Exception {
        try (TestDatabase database = database("fork-history-real-media");
             AgentStore store = database.jaAgentStore();
             MybatisTaskRepository tasks = database.taskRepository();
             AttachmentService attachments = attachmentService(database)) {
            MybatisHistoryService history = sourceHistory(database, tasks);
            byte[] image = png();
            AttachmentMetadata userImage = attachments.importMcpImage(WORKSPACE, "user.png", "image/png", image);
            AttachmentMetadata toolImage = attachments.importMcpImage(WORKSPACE, "tool.png", "image/png", image);
            UserContent input = new UserContent(List.of(new AttachmentContent(userImage.attachmentId()),
                    new TextContent("inspect attached image")));
            try (AgentStoreProjectionFixture run = admitContent(database, store, history, SOURCE, TURN,
                    "msg_history_image_user", input, START.plusSeconds(1))) {
                TurnExecutionState.Tools tool = appendToolIntent(database, run, START.plusSeconds(2));
                finishToolImage(database, run, tool, toolImage, START.plusSeconds(3));
                settleReply(run, "msg_history_image_final", "IMAGE_PREFIX_FINAL😀", 4, START.plusSeconds(4));
                ThreadSnapshot source = snapshot(history, SOURCE);
                String chosen = finalByPrefix(source, "IMAGE_PREFIX_FINAL").itemId();
                String operationKey = "op_" + "5".repeat(32);
                ThreadUseCase.ThreadForkRequest request = new ThreadUseCase.ThreadForkRequest(
                        SOURCE, chosen, source.thread().revision(), operationKey);
                Map<String, Long> before = forkTableCounts(database);
                executeDdl(database, "CREATE TRIGGER reject_history_fork_seed BEFORE INSERT ON ja_thread_fork_seed "
                        + "BEGIN SELECT RAISE(ABORT, 'history fork seed rejected'); END");

                StorageException failure = assertThrows(StorageException.class, () -> history.forkThread(request));
                assertEquals(StorageException.Code.TRANSACTION, failure.code());
                assertEquals(before, forkTableCounts(database));
                assertEquals(source.thread().revision(), snapshot(history, SOURCE).thread().revision());
                assertTrue(history.readThreadForkOperation(SOURCE, operationKey).isEmpty());
                assertTrue(store.loadConversation(new AgentStore.ConversationKey(
                        "ja.local", childId(operationKey), "user_local")).isEmpty());

                executeDdl(database, "DROP TRIGGER reject_history_fork_seed");
                ThreadUseCase.ThreadForkResult fork = history.forkThread(request);
                ThreadSnapshot child = snapshot(history, fork.thread().threadId());
                assertInheritedOnly(child);
                assertIndependentIdentities(source, child);
                List<String> childImages = assertMediaPrefix(history, child);
                assertFalse(childImages.contains(userImage.attachmentId()));
                assertFalse(childImages.contains(toolImage.attachmentId()));
                assertEquals(2, new HashSet<>(childImages).size());
                for (String childImage : childImages) {
                    assertArrayEquals(image, readWholeImage(attachments, childImage, child.thread().threadId()));
                }

                cancel(run, START.plusSeconds(5));
                history.deleteThread(SOURCE, snapshot(history, SOURCE).thread().revision());
                assertTrue(history.readThread(SOURCE, null, 50).isEmpty());
                for (String childImage : childImages) {
                    assertArrayEquals(image, readWholeImage(attachments, childImage, child.thread().threadId()));
                }
                ThreadUseCase.ThreadForkResult refork = fork(history, child.thread().threadId(),
                        finalByPrefix(child, "IMAGE_PREFIX_FINAL").itemId(), '6');
                ThreadSnapshot descendant = snapshot(history, refork.thread().threadId());
                assertInheritedOnly(descendant);
                assertIndependentIdentities(child, descendant);
                List<String> descendantImages = assertMediaPrefix(history, descendant);
                assertTrue(descendantImages.stream().noneMatch(childImages::contains));
                for (String descendantImage : descendantImages) {
                    assertArrayEquals(image, readWholeImage(attachments, descendantImage,
                            descendant.thread().threadId()));
                }
            }
        }
    }

    /** 使用真实仓储注册项目与普通 Thread，并把 Core 读取器绑定到真正的 Task 冻结入口。 @author kongweiguang */
    private MybatisHistoryService sourceHistory(TestDatabase database, MybatisTaskRepository tasks) {
        MybatisHistoryService history = database.history();
        history.register(new Workspace.Registration(WORKSPACE, database.path().getParent(), "fork history",
                Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, START));
        history.createThread(new ThreadSummary.Creation(SOURCE, WORKSPACE, "source", PREFERENCES, START));
        history.bindThreadForkSnapshots(tasks);
        return history;
    }

    /** 准入沿用真实 Core driver；child 的 seed 只在首条输入前读取一次，不能手工复制为本地 Turn。 @author kongweiguang */
    private static AgentStoreProjectionFixture admit(TestDatabase database, AgentStore store,
            MybatisHistoryService history, String threadId, String turnId, String userMessageId, String text, Instant at) {
        return admitContent(database, store, history, threadId, turnId, userMessageId,
                new UserContent(List.of(new TextContent(text))), at);
    }

    /** 结构化输入经真实 admission 绑定附件，避免手工授权制造一个 production 不可能接受的 seed。 @author kongweiguang */
    private static AgentStoreProjectionFixture admitContent(TestDatabase database, AgentStore store,
            MybatisHistoryService history, String threadId, String turnId, String userMessageId,
            UserContent content, Instant at) {
        AgentIdentity identity = new AgentIdentity("ja.local", threadId, turnId, "user_local");
        AgentExecutionRequest request = new AgentExecutionRequest(identity, AgentExecutionRequest.AdmissionKind.USER_INPUT,
                Optional.of(content), userMessageId, Optional.empty(),
                new AgentStore.OperationIdentity("operation_" + turnId, "turn/start", "a".repeat(64), JsonObject.empty()),
                Map.of(), AgentExecutionRequest.HistorySelection.currentConversation(), at, List.of(), Optional.empty(),
                history.findForFirstTurn(threadId));
        long revision = snapshot(history, threadId).thread().revision();
        AdmissionFacts facts = new AdmissionFacts(identity, WORKSPACE, TurnOrigin.USER, "turn/start", at,
                null, null, null, null, null, null, null, revision, Optional.empty());
        return AgentStoreProjectionFixture.open(store, database.mapper(), request, facts, PREFERENCES, revision);
    }

    /** STOP 结算与助手记录在同一事务提交，Turn 保持 RUNNING，以覆盖下一条输入已消费后的历史分叉。 @author kongweiguang */
    private static void settleReply(AgentStoreProjectionFixture run, String messageId, String body, int round, Instant at) {
        AgentStore.RecordWrite assistant = run.record("agent.assistant", messageId,
                new ExecutionFacts.AssistantMessage(messageId,
                        new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent(body))), body, null, round), at);
        run.commitRecords(TurnState.RUNNING, ready(round), List.of(assistant),
                List.of(new ExecutionEvent.AssistantSettled(assistant.recordId())), at);
    }

    /** 追加已接纳 USER 事实而不建立新 Turn，测试只关心真实记录排序，不模拟 UI 排队协议。 @author kongweiguang */
    private static void appendUser(TestDatabase database, AgentStoreProjectionFixture run,
            String messageId, String body, Instant at) {
        AgentStore.RecordWrite user = new StorePayloadCodec(database.mapper()).encodeUser(
                run.recordId("agent.user.message", messageId), messageId,
                new UserContent(List.of(new TextContent(body))), at);
        run.commitRecords(TurnState.RUNNING, ready(1), List.of(user),
                List.of(new ExecutionEvent.InputAccepted(messageId)), at);
    }

    /** Tool 意图使用真实 typed Core 与 Ja presentation 事实；不派发外部操作，仍能验证选中回复后 Tool 被排除。 @author kongweiguang */
    private static TurnExecutionState.Tools appendToolIntent(TestDatabase database,
            AgentStoreProjectionFixture run, Instant at) {
        String callId = "call_fork_later_tool";
        String batchId = "batch_fork_later_tool";
        String messageId = "msg_fork_later_tool";
        ToolPresentation presentation = new ToolPresentation(ToolPresentation.Kind.MCP, "later_tool",
                ToolPresentation.Status.PENDING, "{}", "", null, List.of(), List.of(),
                null, null, null, null, null, null, false, null, null, List.of());
        List<AgentStore.RecordWrite> writes = List.of(
                run.record("agent.assistant", messageId, new ExecutionFacts.AssistantMessage(messageId,
                        new ModelMessage(ModelRole.ASSISTANT,
                                List.of(new ToolCallContent(callId, "later_tool", JsonObject.empty()))), "", null, 3), at),
                run.record("agent.tool.prepared", callId, new ExecutionFacts.ToolPrepared(batchId, callId,
                        "later_tool", JsonObject.empty(), 0, ToolSideEffect.READ_ONLY, null, false), at),
                new JaProjectionPayloadCodec(database.mapper()).encodeToolPresentation(callId, 0, ToolState.PREPARED,
                        new ToolPresentationCodec(database.mapper()).write(presentation), at));
        TurnExecutionState.Tools cursor = new TurnExecutionState.Tools(
                ready(3).common(), batchId, messageId, 0, 0, 0);
        run.commitRecords(TurnState.RUNNING, cursor, writes, List.of(), at);
        return cursor;
    }

    /** 先提交 ToolStarted 再结算图片结果，配对调用、模型历史与附件 owner 必须一起经 Core 事务生效。 @author kongweiguang */
    private static void finishToolImage(TestDatabase database, AgentStoreProjectionFixture run,
            TurnExecutionState.Tools cursor, AttachmentMetadata image, Instant at) {
        String callId = "call_fork_later_tool";
        run.commitRecords(TurnState.RUNNING, cursor,
                List.of(run.record("agent.tool.started", "started:" + callId,
                        new ExecutionFacts.ToolStarted(callId, 0), at)),
                List.of(new ExecutionEvent.ToolStarted(callId, cursor.batchId(), "later_tool", 0)), at);
        String messageId = "msg_history_image_tool_result";
        String content = "tool image loaded";
        List<ToolMediaReference> media = List.of(new ToolMediaReference(image.attachmentId(),
                image.mediaType(), image.displayName(), image.sizeBytes()));
        ToolPresentation presentation = new ToolPresentation(ToolPresentation.Kind.MCP, "later_tool",
                ToolPresentation.Status.SUCCESS, "{}", content, null, List.of(), List.of(),
                null, null, null, null, null, null, false, null, null, media);
        AgentStore.RecordWrite result = run.record("agent.tool.result", callId,
                new ExecutionFacts.ToolResult(callId, ToolState.SUCCEEDED, content, false, content), at);
        AgentStore.RecordWrite message = run.record("agent.tool.message", messageId,
                new ExecutionFacts.ToolMessage(messageId, new ModelMessage(ModelRole.TOOL,
                        List.of(new ToolResultContent(callId, content, false, media)))), at);
        run.commitRecords(TurnState.RUNNING,
                new TurnExecutionState.Ready(cursor.common(), TurnExecutionState.Next.ASSISTANT, null),
                List.of(result, message, new JaProjectionPayloadCodec(database.mapper()).encodeToolPresentation(
                        callId, 1, ToolState.SUCCEEDED, new ToolPresentationCodec(database.mapper())
                                .write(presentation), at)),
                List.of(new ExecutionEvent.ToolCompleted(callId, ToolState.SUCCEEDED,
                        result.recordId(), message.recordId(), null)), at);
    }

    /** 删除来源前真实结算执行，不通过改状态列绕过活跃 Turn 删除门禁。 @author kongweiguang */
    private static void cancel(AgentStoreProjectionFixture run, Instant at) {
        run.commitRecords(TurnState.CANCELLED, ready(3), List.of(run.knownEmptyChangeSet(at)),
                List.of(new ExecutionEvent.Terminal(TurnState.CANCELLED, "cancelled", null, null, null, null)), at);
    }

    /** 游标只表示已提交模型轮次的下一安全步骤，不创建运行时重试或实际 Provider。 @author kongweiguang */
    private static TurnExecutionState.Ready ready(int round) {
        return new TurnExecutionState.Ready(new TurnExecutionState.Common(round, 0, round + 1,
                "prompt_projection_test", List.of()), TurnExecutionState.Next.ASSISTANT, null);
    }

    /** 固定不同动作键并回读来源 revision，避免连续分叉误复用另一个动作的回执。 @author kongweiguang */
    private static ThreadUseCase.ThreadForkResult fork(MybatisHistoryService history,
            String threadId, String itemId, char key) {
        return history.forkThread(new ThreadUseCase.ThreadForkRequest(threadId, itemId,
                snapshot(history, threadId).thread().revision(), "op_" + String.valueOf(key).repeat(32)));
    }

    /** 本测试最多六条消息，选择大于 fixture 的页大小，防止分页缺项冒充精确前缀。 @author kongweiguang */
    private static ThreadSnapshot snapshot(MybatisHistoryService history, String threadId) {
        ThreadSnapshot result = history.readThread(threadId, null, 50).orElseThrow();
        assertNull(result.nextCursor());
        return result;
    }

    /** 解包只用于验证类型；来源标记仍由外层 OriginItem 的实际契约断言。 @author kongweiguang */
    private static ThreadSnapshot.Item unwrap(ThreadSnapshot.Item item) {
        return item instanceof ThreadSnapshot.OriginItem inherited ? inherited.item() : item;
    }

    /** 按公开类型筛选真实 STOP 回复，不把 commentary 或 Tool 文本当作分叉候选。 @author kongweiguang */
    private static List<ThreadSnapshot.TextItem> finalItems(ThreadSnapshot snapshot) {
        return snapshot.items().stream().map(MybatisThreadForkHistoryIntegrationTest::unwrap)
                .filter(ThreadSnapshot.TextItem.class::isInstance).map(ThreadSnapshot.TextItem.class::cast)
                .filter(item -> item.kind() == ThreadSnapshot.TextKind.FINAL_ANSWER).toList();
    }

    /** 测试 marker 仅用于发现 opaque item，后续请求和读取一律使用仓储返回的真实身份。 @author kongweiguang */
    private static ThreadSnapshot.TextItem finalByPrefix(ThreadSnapshot snapshot, String prefix) {
        return finalItems(snapshot).stream().filter(item -> item.text().startsWith(prefix)).findFirst().orElseThrow();
    }

    /** 分段按 code point 游标重组，特意以奇数页长穿过代理对边界，验证长回复完整且没有 Unicode 损坏。 @author kongweiguang */
    private static String readWholeBody(MybatisHistoryService history, String threadId, String itemId) {
        StringBuilder body = new StringBuilder();
        int offset = 0;
        while (true) {
            ThreadUseCase.ItemContentPage part = history.readItemContent(threadId, itemId, offset, 257).orElseThrow();
            assertEquals(itemId, part.itemId());
            assertEquals(offset, part.offsetCharacters());
            body.append(part.content());
            if (part.nextOffsetCharacters() == null) {
                assertFalse(part.truncated());
                assertEquals(part.totalCharacters(), body.codePointCount(0, body.length()));
                return body.toString();
            }
            assertTrue(part.nextOffsetCharacters() > offset);
            offset = part.nextOffsetCharacters();
        }
    }

    /** 全文比较走公开分页接口，不能只依据 snapshot 的 512 字符预览判定后缀是否遗漏。 @author kongweiguang */
    private static String publicBodies(MybatisHistoryService history, ThreadSnapshot snapshot) {
        StringBuilder result = new StringBuilder();
        for (ThreadSnapshot.Item item : snapshot.items()) {
            if (unwrap(item) instanceof ThreadSnapshot.UserInputItem || unwrap(item) instanceof ThreadSnapshot.TextItem) {
                result.append(readWholeBody(history, snapshot.thread().threadId(), item.itemId())).append('\n');
            }
        }
        return result.toString();
    }

    /** child 保留冻结展示元数据，但没有真实执行和输入队列；继承 Turn 的旧状态不能被当作 child 运行。 @author kongweiguang */
    private static void assertInheritedOnly(ThreadSnapshot child) {
        assertFalse(child.items().isEmpty());
        assertTrue(child.items().stream().allMatch(item -> item.origin() == ThreadSnapshot.Origin.INHERITED));
        assertTrue(child.turns().stream().allMatch(turn -> turn.origin() == ThreadSnapshot.Origin.INHERITED));
        assertNull(child.inputQueue());
        assertNull(child.contextUsage());
    }

    /** 独立 child 展示身份不能与来源相撞，防止全局客户端 Store 把两个 Thread 的回复覆盖为同一对象。 @author kongweiguang */
    private static void assertIndependentIdentities(ThreadSnapshot source, ThreadSnapshot child) {
        Set<String> sourceItems = new HashSet<>(source.items().stream().map(ThreadSnapshot.Item::itemId).toList());
        assertTrue(child.items().stream().noneMatch(item -> sourceItems.contains(item.itemId())));
        Set<String> sourceTurns = new HashSet<>(source.turns().stream().map(ThreadSnapshot.Turn::turnId).toList());
        assertTrue(child.turns().stream().noneMatch(turn -> sourceTurns.contains(turn.turnId())));
        assertNotNull(child.thread().threadId());
        assertFalse(source.thread().threadId().equals(child.thread().threadId()));
    }

    /** 用户 block、成功 Tool 展示和模型 seed 必须引用同一组新附件，且调用与结果在前缀内成对闭合。 @author kongweiguang */
    private static List<String> assertMediaPrefix(MybatisHistoryService history, ThreadSnapshot snapshot) {
        ThreadSnapshot.UserInputItem user = snapshot.items().stream()
                .map(MybatisThreadForkHistoryIntegrationTest::unwrap)
                .filter(ThreadSnapshot.UserInputItem.class::isInstance)
                .map(ThreadSnapshot.UserInputItem.class::cast).findFirst().orElseThrow();
        ThreadSnapshot.ToolItem tool = snapshot.items().stream()
                .map(MybatisThreadForkHistoryIntegrationTest::unwrap)
                .filter(ThreadSnapshot.ToolItem.class::isInstance)
                .map(ThreadSnapshot.ToolItem.class::cast).findFirst().orElseThrow();
        assertEquals(1, user.attachments().size());
        assertEquals(user.content().attachmentIds().getFirst(), user.attachments().getFirst().attachmentId());
        assertEquals(ToolPresentation.Status.SUCCESS, tool.presentation().status());
        assertEquals(1, tool.presentation().media().size());
        String userId = user.attachments().getFirst().attachmentId();
        String toolId = tool.presentation().media().getFirst().attachmentId();
        InitialContextSeed seed = history.findForFirstTurn(snapshot.thread().threadId()).orElseThrow();
        List<ContextMessage.Block> blocks = seed.messages().stream()
                .flatMap(message -> message.blocks().stream()).toList();
        assertEquals(List.of(userId), blocks.stream().filter(ContextMessage.AttachmentBlock.class::isInstance)
                .map(ContextMessage.AttachmentBlock.class::cast)
                .map(ContextMessage.AttachmentBlock::attachmentId).toList());
        List<String> callIds = blocks.stream().filter(ContextMessage.ToolCallBlock.class::isInstance)
                .map(ContextMessage.ToolCallBlock.class::cast).map(ContextMessage.ToolCallBlock::callId).toList();
        List<ContextMessage.ToolResultBlock> results = blocks.stream()
                .filter(ContextMessage.ToolResultBlock.class::isInstance)
                .map(ContextMessage.ToolResultBlock.class::cast).toList();
        assertEquals(1, callIds.size());
        assertEquals(callIds, results.stream().map(ContextMessage.ToolResultBlock::callId).toList());
        assertEquals(List.of(toolId), results.getFirst().output().media().stream()
                .map(ToolMediaReference::attachmentId).toList());
        return List.of(userId, toolId);
    }

    /** 受管存储使用测试专属磁盘目录与固定时钟，不向生产路径写入内容或开启后台回收。 @author kongweiguang */
    private AttachmentService attachmentService(TestDatabase database) throws IOException {
        return new AttachmentService(database.attachments(), new ManagedAttachmentStore(
                Files.createDirectories(temp.resolve("run")), Files.createDirectories(temp.resolve("data"))),
                CLOCK, Executors.newSingleThreadScheduledExecutor(), false);
    }

    /** ImageIO 生成完整图片而非魔数占位，附件导入必须真实通过格式检测并写入 blob。 @author kongweiguang */
    private static byte[] png() throws IOException {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", content));
        return content.toByteArray();
    }

    /** 使用公开附件范围读取跨过分块边界，证明 child 授权与源删除后的 blob 内容都仍完整。 @author kongweiguang */
    private static byte[] readWholeImage(AttachmentService attachments, String attachmentId, String threadId) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        long offset = 0;
        while (true) {
            AttachmentUseCase.ReadResult part = attachments.read(new AttachmentUseCase.ReadRequest(
                    attachmentId, threadId, offset, 64));
            assertEquals(offset, part.offsetBytes());
            assertEquals("base64", part.encoding());
            content.writeBytes(Base64.getDecoder().decode(part.content()));
            if (part.endOfFile()) {
                return content.toByteArray();
            }
            assertTrue(part.nextOffsetBytes() > offset);
            offset = part.nextOffsetBytes();
        }
    }

    /** 白名单与 V1 migration 的真实表名一致；失败前后逐表计数可发现 metadata、grants、正文和回执任一孤儿行。 @author kongweiguang */
    private static Map<String, Long> forkTableCounts(TestDatabase database) throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (SqlSession session = database.sessions().openSession();
             Statement query = session.getConnection().createStatement()) {
            for (String table : List.of("ja_conversation_thread", "ja_conversation_subagent_policy",
                    "ja_attachment", "ja_attachment_blob",
                    "ja_thread_fork_seed", "ja_thread_fork_operation", "ja_thread_fork_attachment",
                    "ja_thread_fork_history_item", "ja_thread_fork_context_message")) {
                try (ResultSet rows = query.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    assertTrue(rows.next());
                    counts.put(table, rows.getLong(1));
                }
            }
        }
        return counts;
    }

    /** 只注入可撤销的本地 SQLite 故障，不改仓储实现或用代理制造部分提交。 @author kongweiguang */
    private static void executeDdl(TestDatabase database, String sql) throws Exception {
        try (SqlSession session = database.sessions().openSession();
             Statement ddl = session.getConnection().createStatement()) {
            ddl.executeUpdate(sql);
            session.commit();
        }
    }

    /** 从操作身份推导待创建 owner，用于直接确认 Core conversation 在事务失败后不存在。 @author kongweiguang */
    private static String childId(String operationKey) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(("thread-fork\0" + operationKey).getBytes(StandardCharsets.UTF_8));
            return "thr_" + HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is unavailable", impossible);
        }
    }

    /** 超过 snapshot 与旧消息上限，尾部混用中文和补充平面字符，确保只保存预览无法使该测试通过。 @author kongweiguang */
    private static String longBody() {
        return "长正文开头😀\n" + "真实长正文😀甲乙\n".repeat(8_000) + "长正文尾部必须完整😀";
    }

    /** 以不重叠匹配数核对整个继承前缀，不能仅用 contains 隐藏重复 seed。 @author kongweiguang */
    private static int occurrences(String value, String marker) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(marker, offset)) >= 0) {
            count++;
            offset += marker.length();
        }
        return count;
    }
}
