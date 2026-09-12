// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.conversation.domain.AttachmentSummary;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointStore;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointUsage;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryDocument;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent;
import io.github.kongweiguang.ja.conversation.domain.InputQueue;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestUsage;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.TurnChangeSet;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Statement;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.binding;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.execution;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.usageFact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 SQLite 重启覆盖阶段、Tool artifact、TurnChangeSet 与身份隔离。 */
final class AgentProjectionPersistenceTest extends PersistenceTestSupport {

    /** 重建历史服务后以持久 checkpoint 失效旧占用，同时账本计量不变；后续真实 usage 恢复 KNOWN。 */
    @Test
    void checkpointInvalidatesUsageAcrossRestartWithoutRewritingLedger() throws Exception {
        for (int offset : List.of(0, 1)) {
            try (TestDatabase database = database("agent-checkpoint-usage-" + offset)) {
                MybatisConversationRepository store = initialized(database);
                ConversationRepository.AdmissionReceipt admission = store.admit(new ConversationRepository.TurnAdmission(
                        "thr_agent", "turn_agent", "item_user",
                        new ModelMessage(ModelRole.USER, List.of(new TextContent("run"))),
                        List.of(), 0, START, execution("cfg_agent")));
                ConversationRepository.CommitReceipt committed = store.commit(new ConversationRepository.CommitRequest(
                        "thr_agent", "turn_agent", TurnState.RUNNING,
                        List.of(usageFact(1, 1, null), usageFact(1, 1, new ModelUsage(42000, 8, 42008))),
                        admission.turnMutationVersion(), START.plusSeconds(1), execution("cfg_agent")));
                assertEquals(ProviderRequestUsage.Certainty.KNOWN, database.history(store)
                        .readThread("thr_agent", null, 100).orElseThrow().contextUsage().request().certainty());
                long revision = database.checkpoints().read("thr_agent").threadRevision();
                CheckpointStore.ContextCheckpoint checkpoint = new CheckpointStore.ContextCheckpoint(
                        "checkpoint_usage", "thr_agent", 1, 2, revision, java.util.Optional.empty(),
                        SummaryDocument.empty(), 100, "0".repeat(64), ContextCompactionEvent.STRATEGY_VERSION,
                        CheckpointUsage.none(), START.plusSeconds(1 + offset));
                database.checkpoints().commit(new CheckpointStore.CommitRequest("thr_agent", revision, checkpoint));
                store.close();
                MybatisConversationRepository restored = database.agentStore();
                ThreadSnapshot.ContextUsage unknown = database.history(restored)
                        .readThread("thr_agent", null, 100).orElseThrow().contextUsage();
                assertEquals(ProviderRequestUsage.Certainty.UNKNOWN, unknown.request().certainty());
                assertNull(unknown.request().usage());
                assertEquals("request_1", unknown.request().requestId());
                try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession();
                     Statement sql = session.getConnection().createStatement();
                     java.sql.ResultSet rows = sql.executeQuery("SELECT certainty,input_tokens FROM usage WHERE request_id='request_1'")) {
                    assertTrue(rows.next());
                    assertEquals("KNOWN", rows.getString(1));
                    assertEquals(42000, rows.getLong(2));
                }
                restored.commit(new ConversationRepository.CommitRequest(
                        "thr_agent", "turn_agent", TurnState.RUNNING,
                        List.of(usageFact(2, 2, null), usageFact(2, 2, new ModelUsage(100, 8, 108))),
                        committed.turnMutationVersion(), START.plusSeconds(3), execution("cfg_agent")));
                ThreadSnapshot.ContextUsage fresh = database.history(restored)
                        .readThread("thr_agent", null, 100).orElseThrow().contextUsage();
                assertEquals(ProviderRequestUsage.Certainty.KNOWN, fresh.request().certainty());
                assertEquals(100, fresh.request().usage().inputTokens());
                restored.close();
            }
        }
    }

    /**
     * started 事务必须同时推进内部 Tool 状态与公开 presentation；重建 Repository 后的
     * thread/read 也只能看到 running，不能退回截图中的“等待执行”。
     */
    @Test
    void restoresRunningPresentationAfterStartedCommit() throws Exception {
        try (TestDatabase database = database("agent-projection-running")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = store.admit(new ConversationRepository.TurnAdmission(
                    "thr_agent", "turn_agent",
                    "item_user", new ModelMessage(ModelRole.USER, List.of(new TextContent("run"))),
                    List.of(), 0, START, execution("cfg_agent")));
            ConversationRepository.CommitReceipt prepared = store.commit(new ConversationRepository.CommitRequest(
                    "thr_agent", "turn_agent", TurnState.RUNNING,
                    List.of(new ConversationRepository.ToolPreparedFact("call_agent", "shell",
                            JsonObjects.builder().putText("command", "echo ok").build(), 0,
                            ToolSideEffect.EXTERNAL, presentation(ToolPresentation.Status.PENDING, null),
                            binding("batch_fixture", "call_agent", "shell"))),
                    admission.turnMutationVersion(), START.plusSeconds(1), execution("cfg_agent")));

            store.commit(new ConversationRepository.CommitRequest(
                    "thr_agent", "turn_agent", TurnState.RUNNING,
                    List.of(new ConversationRepository.ToolStartedFact("call_agent")),
                    prepared.turnMutationVersion(), START.plusSeconds(2), execution("cfg_agent")));
            store.close();

            MybatisConversationRepository restoredStore = database.agentStore();
            ThreadSnapshot snapshot = database.history(restoredStore)
                    .readThread("thr_agent", null, 100).orElseThrow();
            ThreadSnapshot.ToolItem tool = snapshot.items().stream()
                    .filter(ThreadSnapshot.ToolItem.class::isInstance)
                    .map(ThreadSnapshot.ToolItem.class::cast)
                    .findFirst().orElseThrow();
            assertEquals(ToolPresentation.Status.RUNNING, tool.presentation().status());
            restoredStore.close();
        }
    }

    /**
     * 结果和 diff 在事务后按完整身份分页恢复；最近 UNKNOWN Usage 必须保留未知语义而非回退上一条
     * KNOWN 计量，猜中 artifactId 但缺少所属 Thread/Turn/Call 任一身份都不得读取正文。
     */
    @Test
    void restoresSafeTimelineAndIsolatedArtifactsAfterRepositoryRestart() throws Exception {
        try (TestDatabase database = database("agent-projection-restart")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = store.admit(new ConversationRepository.TurnAdmission(
                    "thr_agent", "turn_agent",
                    "item_user", new ModelMessage(ModelRole.USER, List.of(new TextContent("run"))),
                    List.of(), 0, START, execution("cfg_agent")));
            ToolPresentation pending = presentation(ToolPresentation.Status.PENDING, null);
            ConversationRepository.CommitReceipt running = store.commit(new ConversationRepository.CommitRequest(
                    "thr_agent", "turn_agent", TurnState.RUNNING,
                    List.of(new ConversationRepository.AssistantFact("item_progress",
                                    new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("working"))),
                                    "working", "public reasoning", 1),
                            usageFact(1, 1, null),
                            usageFact(1, 1, new ModelUsage(42, 8, 50)),
                            new ConversationRepository.ToolPreparedFact("call_agent", "shell",
                                    JsonObjects.builder().putText("command", "echo ok").build(), 0,
                                    ToolSideEffect.EXTERNAL, pending,
                                    binding("batch_fixture", "call_agent", "shell"))),
                    admission.turnMutationVersion(), START.plusSeconds(1), execution("cfg_agent")));
            String toolContent = "甲😀乙\nstdout";
            ToolPresentation completed = presentation(ToolPresentation.Status.SUCCESS, "artifact_tool_agent");
            ConversationRepository.CommitReceipt tool = store.commit(new ConversationRepository.CommitRequest(
                    "thr_agent", "turn_agent", TurnState.RUNNING,
                    List.of(new ConversationRepository.ToolStartedFact("call_agent"),
                            new ConversationRepository.ToolResultFact("call_agent", ToolState.SUCCEEDED,
                                    toolContent, false, completed, toolContent),
                            new ConversationRepository.ToolResultMessageFact("item_tool",
                                    new ModelMessage(ModelRole.TOOL,
                                            List.of(new ToolResultContent("call_agent", toolContent, false)))),
                            usageFact(2, 2, null)),
                    running.turnMutationVersion(), START.plusSeconds(2), execution("cfg_agent")));
            String diff = "--- a/甲.txt\n+++ b/甲.txt\n@@ -1,1 +1,1 @@\n-甲\n+乙😀\n";
            TurnChangeSet changeSet = new TurnChangeSet(TurnChangeSet.State.COMPLETE, java.util.Set.of(),
                    List.of(new TurnChangeSet.FileChange("甲.txt", TurnChangeSet.FileStatus.MODIFIED,
                            1L, 0L, false, false)),
                    new TurnChangeSet.Stats(1, 1, 0, 0, false), "artifact_change_agent");
            store.commitTerminal(new ConversationRepository.TerminalCommit(
                    "thr_agent", "turn_agent", TurnState.COMPLETED, "done", null, null,
                    "item_final", new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("done"))),
                    List.of(), tool.turnMutationVersion(), START.plusSeconds(3), changeSet,
                    sha256(diff), (long) diff.getBytes(StandardCharsets.UTF_8).length, diff));
            assertNotNull(changeSet.artifactId());

            store.close();
            MybatisConversationRepository restoredStore = database.agentStore();
            MybatisHistoryService restored = database.history(restoredStore);
            ThreadSnapshot snapshot = restored.readThread("thr_agent", null, 100).orElseThrow();

            assertEquals(1, snapshot.items().stream().filter(ThreadSnapshot.TextItem.class::isInstance)
                    .map(ThreadSnapshot.TextItem.class::cast)
                    .filter(item -> item.kind() == ThreadSnapshot.TextKind.ASSISTANT_PROGRESS).count());
            assertEquals(1, snapshot.items().stream().filter(ThreadSnapshot.TextItem.class::isInstance)
                    .map(ThreadSnapshot.TextItem.class::cast)
                    .filter(item -> item.kind() == ThreadSnapshot.TextKind.FINAL_ANSWER).count());
            assertEquals(changeSet, snapshot.turns().getFirst().changeSet());
            assertEquals("turn_agent", snapshot.contextUsage().turnId());
            assertEquals("request_2", snapshot.contextUsage().request().requestId());
            assertEquals(2, snapshot.contextUsage().request().requestOrdinal());
            assertEquals(ProviderRequestUsage.Certainty.UNKNOWN,
                    snapshot.contextUsage().request().certainty());
            assertEquals("provider_test", snapshot.contextUsage().request().profile().providerId());
            assertEquals("model_test", snapshot.contextUsage().request().profile().modelId());
            assertNull(snapshot.contextUsage().request().usage());

            ThreadUseCase.TextArtifactPage first = restored.readToolArtifact(
                    "thr_agent", "turn_agent", "call_agent", "artifact_tool_agent", 0, 2).orElseThrow();
            ThreadUseCase.TextArtifactPage second = restored.readToolArtifact(
                    "thr_agent", "turn_agent", "call_agent", "artifact_tool_agent",
                    first.nextOffsetCharacters(), 64).orElseThrow();
            assertEquals(toolContent, first.content() + second.content());
            assertTrue(restored.readToolArtifact("thr_other", "turn_agent", "call_agent",
                    "artifact_tool_agent", 0, 64).isEmpty());
            assertTrue(restored.readToolArtifact("thr_agent", "turn_other", "call_agent",
                    "artifact_tool_agent", 0, 64).isEmpty());
            assertTrue(restored.readToolArtifact("thr_agent", "turn_agent", "call_other",
                    "artifact_tool_agent", 0, 64).isEmpty());

            ThreadUseCase.ChangeSetArtifactFile restoredFile = restored.readChangeSetArtifact(
                    "thr_agent", "turn_agent", changeSet.artifactId(), "甲.txt").orElseThrow();
            String restoredDiff = new String(Base64.getDecoder().decode(restoredFile.contentBase64()),
                    StandardCharsets.UTF_8);
            assertEquals(diff, restoredDiff);
            assertEquals(diff.getBytes(StandardCharsets.UTF_8).length, restoredFile.byteLength());
            assertFalse(restored.readChangeSetArtifact(
                    "thr_agent", "turn_other", changeSet.artifactId(), "甲.txt").isPresent());
            restoredStore.close();
        }
    }

    /**
     * 迁移遗留的坏附件引用必须能通过严格 thread/read 投影为可识别占位，并允许用户编辑移除；
     * 不可用摘要只保留原 identity，不读取不存在或不属于该 input 的附件元数据。
     */
    @Test
    void restoresAndRepairsUnavailableQueuedAttachment() throws Exception {
        try (TestDatabase database = database("agent-projection-unavailable-attachment")) {
            MybatisConversationRepository store = initialized(database);
            store.admit(new ConversationRepository.TurnAdmission(
                    "thr_agent", "turn_agent", "item_user",
                    new ModelMessage(ModelRole.USER, List.of(new TextContent("run"))),
                    List.of(), 0, START, execution("cfg_agent")));
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession();
                 Statement sql = session.getConnection().createStatement()) {
                sql.executeUpdate("INSERT INTO pending_inputs(input_id,thread_id,turn_id,kind,content_json,"
                        + "state,validation_status,issue_error_code,issue_message,issue_retryable,input_revision,"
                        + "created_at,updated_at) VALUES ('input_broken','thr_agent','turn_agent','FOLLOW_UP',"
                        + "'[{\"kind\":\"attachment\",\"attachmentId\":\"att_missing\"}]','PENDING',"
                        + "'NEEDS_ATTENTION','ATTACHMENT_UNAVAILABLE','附件不可用，请移除后继续。',0,1,"
                        + "'2026-08-25T12:00:01Z','2026-08-25T12:00:01Z')");
                sql.executeUpdate("UPDATE turns SET input_queue_revision=1 WHERE turn_id='turn_agent'");
                session.commit();
            }

            ThreadSnapshot snapshot = database.history(store)
                    .readThread("thr_agent", null, 100).orElseThrow();
            InputQueue.QueuedInput broken = snapshot.inputQueue().items().getFirst();
            assertEquals(InputQueue.Status.NEEDS_ATTENTION, broken.status());
            assertEquals("ATTACHMENT_UNAVAILABLE", broken.issue().errorCode());
            assertEquals(List.of(new AttachmentSummary(
                    "att_missing", "附件不可用", 0, "binary", "application/octet-stream")),
                    broken.attachments());

            ConversationRepository.QueueMutation repaired = store.updateInput(
                    "thr_agent", "turn_agent", "input_broken", 1,
                    new UserContent(List.of(new TextContent("已移除失效附件"))),
                    START.plusSeconds(2));
            InputQueue.QueuedInput repairedInput = repaired.inputQueue().items().getFirst();
            assertEquals(InputQueue.Status.PENDING, repairedInput.status());
            assertNull(repairedInput.issue());
            assertTrue(repairedInput.attachments().isEmpty());
            assertTrue(repairedInput.content().attachmentIds().isEmpty());
            store.close();
        }
    }

    /** 创建完整 Workspace/Thread 事实，测试不借助应用 composition 或用户目录。 */
    private MybatisConversationRepository initialized(TestDatabase database) {
        MybatisConversationRepository store = database.agentStore();
        database.history(store).register(new Workspace.Registration(
                "ws_agent", temp.toAbsolutePath(), "workspace", Workspace.Trust.TRUSTED, START));
        store.createThread(new ConversationRepository.ThreadDefinition(
                "thr_agent", "ws_agent", "thread", preferences("provider_agent", "model_agent"), START));
        return store;
    }

    /** 构造不含 raw 参数或结果的最小安全展示；正文只进入独立 artifact 表。 */
    private static ToolPresentation presentation(ToolPresentation.Status status, String artifactId) {
        return new ToolPresentation(ToolPresentation.Kind.SHELL, "shell", status,
                "echo ok", status == ToolPresentation.Status.SUCCESS ? "ok" : null,
                List.of(), "echo ok", ".", status == ToolPresentation.Status.SUCCESS ? "ok" : null,
                null, status == ToolPresentation.Status.SUCCESS ? 0 : null,
                status == ToolPresentation.Status.SUCCESS ? 10L : null, false, artifactId);
    }

    /** ChangeSet artifact 身份绑定完整 UTF-8 正文摘要，分页不能改变该摘要。 */
    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
