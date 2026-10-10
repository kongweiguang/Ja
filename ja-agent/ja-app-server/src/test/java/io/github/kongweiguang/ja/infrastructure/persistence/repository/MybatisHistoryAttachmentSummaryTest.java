// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata;
import io.github.kongweiguang.ja.conversation.domain.model.AttachmentSummary;
import io.github.kongweiguang.ja.attachment.application.gateway.AttachmentRepository;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 检查 History 对持久附件摘要的严格类型边界，并用真实 SQLite tamper 覆盖读路径。 */
final class MybatisHistoryAttachmentSummaryTest extends PersistenceTestSupport {
    private static final String THREAD_ID = "thr_history_attachment";
    private static final String WORKSPACE_ID = "ws_history_attachment";
    private static final String ATTACHMENT_ID = "att_history_attachment";
    private static final ThreadPreferences PREFERENCES =
            ConversationTestFixtures.preferences("provider_history", "model_history");

    /** SQL 投影损坏为非整数时，thread/read 必须报持久状态错误而不能静默转成零字节。 */
    @Test
    void rejectsFractionalSizeFromPersistedAttachmentProjection() throws Exception {
        try (TestDatabase database = database("history-attachment-fraction");
             AgentStore store = createQueuedAttachment(database)) {
            ThreadSnapshot before = database.history().readThread(THREAD_ID, null, 20).orElseThrow();
            assertEquals(12, before.inputQueue().items().getFirst().attachments().getFirst().sizeBytes());

            try (Connection connection = database.database().dataSource().getConnection();
                 PreparedStatement update = connection.prepareStatement(
                         "UPDATE ja_attachment SET size_bytes=? WHERE attachment_id=?")) {
                update.setDouble(1, 1.5d);
                update.setString(2, ATTACHMENT_ID);
                assertEquals(1, update.executeUpdate());
            }

            StorageException failure = assertThrows(StorageException.class,
                    () -> database.history().readThread(THREAD_ID, null, 20));
            assertEquals(StorageException.Code.INVALID_STATE, failure.code());
        }
    }

    /** 持久附件元数据的坏 MIME 必须使 thread/read 整体失败，不能投影未经校验的摘要。 */
    @Test
    void rejectsInvalidMediaTypeFromPersistedAttachmentProjection() throws Exception {
        try (TestDatabase database = database("history-attachment-media-type");
             AgentStore store = createQueuedAttachment(database)) {
            ThreadSnapshot before = database.history().readThread(THREAD_ID, null, 20).orElseThrow();
            assertEquals("text/plain", before.inputQueue().items().getFirst().attachments().getFirst().mediaType());

            try (Connection connection = database.database().dataSource().getConnection();
                 PreparedStatement update = connection.prepareStatement(
                         "UPDATE ja_attachment SET media_type=? WHERE attachment_id=?")) {
                update.setString(1, "Not a MIME");
                update.setString(2, ATTACHMENT_ID);
                assertEquals(1, update.executeUpdate());
            }

            StorageException failure = assertThrows(StorageException.class,
                    () -> database.history().readThread(THREAD_ID, null, 20));
            assertEquals(StorageException.Code.INVALID_STATE, failure.code());
        }
    }

    /** JSON 解码器拒绝字符串、分数、缺失、越界和额外字段，防止持久损坏被默认化。 */
    @Test
    void rejectsMalformedAttachmentSummaryJsonShapes() throws Exception {
        try (TestDatabase database = database("history-attachment-json")) {
            MybatisHistoryService history = database.history();
            assertInvalid(history, """
                [{"attachmentId":"att_one","displayName":"a",\
                "sizeBytes":"1","mediaKind":"text","mediaType":"text/plain"}]\
                """);
            assertInvalid(history, """
                [{"attachmentId":"att_one","displayName":"a",\
                "sizeBytes":1.5,"mediaKind":"text","mediaType":"text/plain"}]\
                """);
            assertInvalid(history, """
                [{"attachmentId":"att_one","displayName":"a",\
                "mediaKind":"text","mediaType":"text/plain"}]\
                """);
            assertInvalid(history, """
                [{"attachmentId":"att_one","displayName":"a",\
                "sizeBytes":9223372036854775808,"mediaKind":"text",\
                "mediaType":"text/plain"}]\
                """);
            assertInvalid(history, """
                [{"attachmentId":"att_one","displayName":"a",\
                "sizeBytes":1,"mediaKind":"text","mediaType":"text/plain",\
                "unexpected":true}]\
                """);
        }
    }

    /** 建立真实 thread、附件草稿和 Engine admission，使 History 读取走生产队列 SQL 投影。 */
    private AgentStore createQueuedAttachment(TestDatabase database) {
        database.history().register(new Workspace.Registration(WORKSPACE_ID, database.path().getParent(), "History attachment", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, START));
        database.history().createThread(new ThreadSummary.Creation(
                THREAD_ID, WORKSPACE_ID, "History attachment", PREFERENCES, START));
        database.attachments().createDraft(new AttachmentRepository.Draft(ATTACHMENT_ID, WORKSPACE_ID,
                "source.txt", 12, "c".repeat(64), AttachmentMetadata.MediaKind.TEXT, "text/plain",
                START, START.plusSeconds(86_400)));

        Instant acceptedAt = START.plusSeconds(1);
        AgentIdentity identity = new AgentIdentity("ja.local", THREAD_ID, "turn_history_attachment", "user_local");
        AgentInputCommand.Enqueue queued = new AgentInputCommand.Enqueue("operation_history_attachment",
                "input_history_attachment", AgentInputCommand.Kind.FOLLOW_UP,
                new UserContent(List.of(new AttachmentContent(ATTACHMENT_ID))), Map.of());
        AgentExecutionRequest request = new AgentExecutionRequest(identity,
                AgentExecutionRequest.AdmissionKind.USER_INPUT,
                Optional.of(new UserContent(List.of(new TextContent("queued source")))),
                "message_history_attachment", Optional.empty(),
                new AgentStore.OperationIdentity("operation_turn_history_attachment", "turn/start", "a".repeat(64), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")), Map.of(),
                AgentExecutionRequest.HistorySelection.currentConversation(), acceptedAt, List.of(queued),
                Optional.empty(), Optional.empty());
        AdmissionFacts facts = new AdmissionFacts(identity, WORKSPACE_ID, TurnOrigin.USER, "turn/start",
                acceptedAt, null, null, null, null, null, null, null, 0, Optional.empty());
        AgentStore store = database.jaAgentStore();
        try (AgentStoreProjectionFixture ignored = AgentStoreProjectionFixture.open(store, database.mapper(),
                request, facts, PREFERENCES, 0)) {
            // 准入 fixture 只负责落同事务队列事实；查询由正式 History adapter 执行。
        }
        return store;
    }

    /** 私有解码器保持应用读模型内聚；反射只用于不扩大生产 API 的严格负例。 */
    private static void assertInvalid(MybatisHistoryService history, String json) {
        StorageException failure = assertThrows(StorageException.class, () -> decode(history, json));
        assertEquals(StorageException.Code.INVALID_STATE, failure.code());
    }

    /** 私有解码入口不作为产品契约公开；测试把其预期运行时异常原样还原。 */
    @SuppressWarnings("unchecked")
    private static List<AttachmentSummary> decode(MybatisHistoryService history, String json) {
        try {
            Method method = MybatisHistoryService.class.getDeclaredMethod("readAttachmentSummaries", String.class);
            method.setAccessible(true);
            return (List<AttachmentSummary>) method.invoke(history, json);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new AssertionError("attachment summary decoder threw a checked failure", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("private attachment summary decoder is unavailable", failure);
        }
    }
}
