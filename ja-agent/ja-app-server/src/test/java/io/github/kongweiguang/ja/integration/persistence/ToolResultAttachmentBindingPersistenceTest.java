// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.integration.persistence;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.AssistantMessage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ModelRequestIdentity;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolMessage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolPrepared;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolResult;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.Usage;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.model.ModelContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference;
import io.github.kongweiguang.ja.agent.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase;
import io.github.kongweiguang.ja.attachment.application.service.AttachmentService;
import io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata;
import io.github.kongweiguang.ja.attachment.infrastructure.storage.ManagedAttachmentStore;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionPayloadCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.store.ToolPresentationCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 以真实 SQLite 和受管 blob 存储验证 ToolResult 图片 owner 与回执的原子性。 */
final class ToolResultAttachmentBindingPersistenceTest extends PersistenceTestSupport {
    private static final Instant ACCEPTED_AT = Instant.parse("2026-10-06T00:00:00Z");
    private static final String THREAD_ID = "thr_tool_media_owner";
    private static final String TURN_ID = "turn_tool_media_owner";
    private static final String WORKSPACE_ID = "ws_tool_media_owner";
    private static final ThreadPreferences PREFERENCES = new ThreadPreferences("provider_test", "model_test",
            "medium", AccessMode.FULL_ACCESS, CollaborationMode.DEFAULT,
            ThreadPreferences.TitleSource.PLACEHOLDER);

    /** 成功回执提交后 Reader 可读取图片，owner metadata 指向真实 Thread 且其它 Thread 无权读取。 */
    @Test
    void bindsSuccessfulMediaToItsThreadAndRejectsOtherOwners() throws Exception {
        try (TestDatabase database = database("tool-result-media-owner");
             AgentStoreProjectionFixture fixture = admit(database);
             AttachmentService attachments = attachmentService(database)) {
            AttachmentMetadata imported = importImage(attachments, WORKSPACE_ID);
            assertTrue(database.attachments().findThread(imported.attachmentId(), THREAD_ID).isEmpty());
            assertThrows(io.github.kongweiguang.ja.attachment.domain.model.AttachmentFailure.class,
                    () -> attachments.read(new AttachmentUseCase.ReadRequest(
                            imported.attachmentId(), THREAD_ID, 0, 64 * 1024)));
            finishSuccessfulMediaResult(fixture, database, imported, ACCEPTED_AT.plusSeconds(3));

            AttachmentUseCase.ReadResult read = attachments.read(new AttachmentUseCase.ReadRequest(
                    imported.attachmentId(), THREAD_ID, 0, 64 * 1024));
            assertEquals(AttachmentMetadata.Status.BOUND, read.metadata().status());
            assertNull(read.metadata().boundMessageId());
            assertEquals(THREAD_ID, read.metadata().boundThreadId());
            assertEquals(Base64.getEncoder().encodeToString(png()), read.content());
            assertFalse(database.attachments().findThread(imported.attachmentId(), "thr_media_other").isPresent());

            database.history().createThread(new ThreadSummary.Creation("thr_media_other", WORKSPACE_ID,
                    "Unrelated Thread", PREFERENCES, ACCEPTED_AT.plusSeconds(4)));
            assertThrows(io.github.kongweiguang.ja.attachment.domain.model.AttachmentFailure.class,
                    () -> attachments.read(new AttachmentUseCase.ReadRequest(
                            imported.attachmentId(), "thr_media_other", 0, 64 * 1024)));
        }
    }

    /** 第二个 owner 校验失败时，先处理的图片绑定、ToolResult、ToolMessage 与工具终态均回滚。 */
    @Test
    void rollsBackEarlierMediaBindingWhenAResultReferencesAnotherWorkspace() throws Exception {
        try (TestDatabase database = database("tool-result-media-rollback");
             AgentStoreProjectionFixture fixture = admit(database);
             AttachmentService attachments = attachmentService(database)) {
            String otherWorkspaceId = "ws_tool_media_other";
            database.history().register(new Workspace.Registration(otherWorkspaceId,
                    temp.resolve("other-workspace").toAbsolutePath(), "other workspace", Workspace.Trust.TRUSTED,
                    Workspace.Kind.PROJECT, ACCEPTED_AT));
            AttachmentMetadata accepted = importImage(attachments, WORKSPACE_ID);
            AttachmentMetadata foreign = importImage(attachments, otherWorkspaceId);

            assertThrows(io.github.kongweiguang.ja.agent.domain.error.StorageException.class,
                    () -> finishSuccessfulMediaResult(fixture, database, List.of(accepted, foreign),
                            ACCEPTED_AT.plusSeconds(3)));
            assertEquals(AttachmentMetadata.Status.DRAFT,
                    database.attachments().findDraft(accepted.attachmentId(), WORKSPACE_ID).orElseThrow().status());
            assertTrue(database.attachments().findThread(accepted.attachmentId(), THREAD_ID).isEmpty());
            assertEquals(AttachmentMetadata.Status.DRAFT,
                    database.attachments().findDraft(foreign.attachmentId(), otherWorkspaceId).orElseThrow().status());
            assertEquals(List.of(ToolState.RUNNING.name()), readTools(database, TURN_ID));
            assertEquals(TurnState.RUNNING.name(), database.agentStore().loadExecution(fixture.execution())
                    .orElseThrow().state());
        }
    }

    /** 夹具先建立 Host-owned 根目录，保留真实存储拒绝缺失目录的边界并覆盖磁盘 blob 回读。 */
    private AttachmentService attachmentService(TestDatabase database) throws IOException {
        ManagedAttachmentStore blobs = new ManagedAttachmentStore(
                Files.createDirectories(temp.resolve("run")), Files.createDirectories(temp.resolve("data")));
        return new AttachmentService(database.attachments(), blobs, Clock.fixed(ACCEPTED_AT, ZoneOffset.UTC),
                Executors.newSingleThreadScheduledExecutor(), false);
    }

    /** 每次通过受管附件导入路径创建真实 SQLite DRAFT 与内容寻址 blob。 */
    private static AttachmentMetadata importImage(AttachmentService attachments, String workspaceId) {
        return attachments.importMcpImage(workspaceId, "capture.png", "image/png", png());
    }

    /** 用 ImageIO 生成完整有效的最小图片，避免仅凭 magic bytes 构造无效媒体样本。 */
    private static byte[] png() {
        try {
            BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", bytes)) {
                throw new IllegalStateException("PNG writer is unavailable");
            }
            return bytes.toByteArray();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("PNG fixture could not be encoded", failure);
        }
    }

    /** 经 production history/admission 写入 Thread、Turn 和冻结身份，后续事实只走 AgentStore。 */
    private AgentStoreProjectionFixture admit(TestDatabase database) {
        database.history().register(new Workspace.Registration(WORKSPACE_ID, temp.toAbsolutePath(), "workspace",
                Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, ACCEPTED_AT));
        database.history().createThread(new ThreadSummary.Creation(THREAD_ID, WORKSPACE_ID,
                "Tool media owner", PREFERENCES, ACCEPTED_AT));
        AgentIdentity identity = new AgentIdentity("ja.local", THREAD_ID, TURN_ID, "user_local");
        AgentExecutionRequest request = new AgentExecutionRequest(identity,
                AgentExecutionRequest.AdmissionKind.USER_INPUT,
                Optional.of(new UserContent(List.of(new TextContent("inspect image")))),
                "item_tool_media_owner_user", Optional.empty(), new AgentStore.OperationIdentity(
                        "operation_tool_media_owner", "turn/start", "a".repeat(64),
                        new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec(
                                database.mapper()).decodeObject("{}")), Map.of(),
                AgentExecutionRequest.HistorySelection.currentConversation(), ACCEPTED_AT,
                List.of(), Optional.empty(), Optional.empty());
        AdmissionFacts facts = new AdmissionFacts(identity, WORKSPACE_ID, TurnOrigin.USER, "turn/start",
                ACCEPTED_AT, null, null, null, null, null, null, null, 0, Optional.empty());
        return AgentStoreProjectionFixture.open(database.jaAgentStore(), database.mapper(), request, facts,
                PREFERENCES, 0);
    }

    /** 将成功模型提案与 Prepared 工具写入同一执行游标，供派发阶段使用。 */
    private static TurnExecutionState.Tools prepareTool(AgentStoreProjectionFixture fixture,
            TestDatabase database, Instant at) {
        String callId = "call_tool_media";
        String batchId = "batch_tool_media";
        String assistantId = "item_assistant_tool_media";
        String requestId = "modelreq_tool_media";
        ModelRequestIdentity profile = profile();
        Usage usage = new Usage(requestId, new ModelUsage(8, 2, 10), 1, 1,
                ExecutionFacts.UsagePurpose.ASSISTANT, ExecutionFacts.UsageCertainty.KNOWN, profile, null);
        AssistantMessage assistant = new AssistantMessage(assistantId,
                new ModelMessage(ModelRole.ASSISTANT,
                        List.of(new ToolCallContent(callId, "fixture_tool", io.github.kongweiguang.ja.agent.domain.json.JsonObject.empty()))),
                "", null, 1);
        ToolPrepared prepared = new ToolPrepared(batchId, callId, "fixture_tool",
                io.github.kongweiguang.ja.agent.domain.json.JsonObject.empty(), 0, ToolSideEffect.READ_ONLY,
                null, false);
        String assistantRecordId = fixture.recordId("agent.assistant", assistantId);
        String usageRecordId = fixture.recordId("agent.usage", requestId);
        TurnExecutionState.Common common = common(1, 2);
        TurnExecutionState.Tools cursor = new TurnExecutionState.Tools(common, batchId, assistantId, 0, 0, 0);
        fixture.commitRecords(TurnState.RUNNING, cursor, List.of(
                        fixture.record("agent.assistant", assistantId, assistant, at),
                        fixture.record("agent.usage", requestId, usage, at),
                        fixture.record("agent.tool.prepared", callId, prepared, at),
                        presentation(database, callId, 0, ToolState.PREPARED, ToolPresentation.Status.PENDING, at)),
                List.of(new ExecutionEvent.ModelCompleted(assistantRecordId, 1, List.of(callId), usageRecordId)), at);
        return cursor;
    }

    /** 首先持久化 ToolStarted，确保图片绑定只可能由派发后的成功回执引起。 */
    private static void startTool(AgentStoreProjectionFixture fixture, TurnExecutionState.Tools cursor,
                                  Instant at) {
        fixture.commitRecords(TurnState.RUNNING, cursor,
                List.of(fixture.record("agent.tool.started", "started:call_tool_media",
                        new ExecutionFacts.ToolStarted("call_tool_media", 0), at)),
                List.of(new ExecutionEvent.ToolStarted("call_tool_media", cursor.batchId(), "fixture_tool", 0)), at);
    }

    /** 一个成功 ToolResult 可以有序绑定多个图片，且其真实媒体引用进入 ToolMessage 历史。 */
    private static void finishSuccessfulMediaResult(AgentStoreProjectionFixture fixture,
            TestDatabase database, AttachmentMetadata attachment, Instant at) {
        finishSuccessfulMediaResult(fixture, database, List.of(attachment), at);
    }

    /** 结果一次绑定全部媒体；用于验证后续 owner 冲突会回滚先前的绑定。 */
    private static void finishSuccessfulMediaResult(AgentStoreProjectionFixture fixture,
            TestDatabase database, List<AttachmentMetadata> attachments, Instant at) {
        TurnExecutionState.Tools cursor = prepareTool(fixture, database, ACCEPTED_AT.plusSeconds(1));
        startTool(fixture, cursor, ACCEPTED_AT.plusSeconds(2));
        String callId = "call_tool_media";
        String content = "Image result is available.";
        String resultId = fixture.recordId("agent.tool.result", callId);
        String messageId = "item_tool_media_result";
        String messageRecordId = fixture.recordId("agent.tool.message", messageId);
        List<ToolMediaReference> references = attachments.stream().map(value -> new ToolMediaReference(
                value.attachmentId(), value.mediaType(), value.displayName(), value.sizeBytes())).toList();
        List<ModelContent> results = List.of(new ToolResultContent(callId, content, false, references));
        fixture.commitRecords(TurnState.RUNNING,
                new TurnExecutionState.Ready(cursor.common(), TurnExecutionState.Next.ASSISTANT, null),
                List.of(fixture.record("agent.tool.result", callId,
                                new ToolResult(callId, ToolState.SUCCEEDED, content, false, content), at),
                        fixture.record("agent.tool.message", messageId,
                                new ToolMessage(messageId, new ModelMessage(ModelRole.TOOL, results)), at),
                        presentation(database, callId, 1, ToolState.SUCCEEDED, ToolPresentation.Status.SUCCESS, at)),
                List.of(new ExecutionEvent.ToolCompleted(callId, ToolState.SUCCEEDED,
                        resultId, messageRecordId, null)), at);
    }

    /** 使用生产展示 codec 保存 Prepared 或成功状态，不能由测试直接构造授权工具行。 */
    private static AgentStore.RecordWrite presentation(TestDatabase database, String callId, long revision,
                                                       ToolState state, ToolPresentation.Status status, Instant at) {
        ToolPresentation value = new ToolPresentation(ToolPresentation.Kind.MCP, "fixture_tool", status,
                "{}", status == ToolPresentation.Status.SUCCESS ? "completed" : "", null,
                List.of(), null, null, null, null, null, null, false, null);
        return new JaProjectionPayloadCodec(database.mapper()).encodeToolPresentation(callId, revision, state,
                new ToolPresentationCodec(database.mapper()).write(value), at);
    }

    /** 测试使用固定 Provider 声明避免请求真实模型或依赖宿主凭据。 */
    private static ModelRequestIdentity profile() {
        io.github.kongweiguang.ja.agent.domain.json.JsonObject attributes =
                io.github.kongweiguang.ja.agent.domain.json.JsonObjects.builder()
                        .putText("accessMode", AccessMode.FULL_ACCESS.name())
                        .putText("collaborationMode", CollaborationMode.DEFAULT.name())
                        .putText("configGeneration", "cfg_tool_media_test")
                        .putText("promptRevision", "prompt_tool_media_test")
                        .putText("toolCatalogRevision", "b".repeat(64))
                        .putText("bindingFingerprint", "c".repeat(64)).build();
        return new ModelRequestIdentity("provider_test", "model_test", "openai_responses", "test-model",
                "medium", "medium", 100_000, 8_192, attributes);
    }

    /** 维持执行引擎的真实轮次与 Provider ordinal，不与 Ja 展示 ordinal 混用。 */
    private static TurnExecutionState.Common common(int modelRound, int nextProviderOrdinal) {
        return new TurnExecutionState.Common(modelRound, 0, nextProviderOrdinal,
                "prompt_tool_media_test", List.of());
    }

    /** 读取 SQLite 投影中的工具行，确认失败回滚没有留下虚假的成功工具。 */
    private static List<String> readTools(TestDatabase database, String turnId) throws Exception {
        try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession();
             java.sql.PreparedStatement query = session.getConnection().prepareStatement(
                     "SELECT state FROM ja_tool_call WHERE turn_id=?")) {
            query.setString(1, turnId);
            try (java.sql.ResultSet rows = query.executeQuery()) {
                java.util.ArrayList<String> states = new java.util.ArrayList<>();
                while (rows.next()) {
                    states.add(rows.getString(1));
                }
                return List.copyOf(states);
            }
        }
    }
}
