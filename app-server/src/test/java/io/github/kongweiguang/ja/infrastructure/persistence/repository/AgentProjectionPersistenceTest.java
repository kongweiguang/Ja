// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.TurnChangeSet;
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
import java.util.HexFormat;
import java.util.List;

import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.runtime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 SQLite 重启覆盖阶段、Tool artifact、TurnChangeSet 与身份隔离。 */
final class AgentProjectionPersistenceTest extends PersistenceTestSupport {

    /**
     * 结果和 diff 在事务后按完整身份分页恢复；重建 Repository 后仍保持 progress/final 分离，
     * 猜中 artifactId 但缺少所属 Thread/Turn/Call 任一身份都不得读取正文。
     */
    @Test
    void restoresSafeTimelineAndIsolatedArtifactsAfterRepositoryRestart() throws Exception {
        try (TestDatabase database = database("agent-projection-restart")) {
            MybatisConversationRepository store = initialized(database);
            ConversationRepository.AdmissionReceipt admission = store.admit(new ConversationRepository.TurnAdmission(
                    "thr_agent", "turn_agent", runtime("provider_agent", "model_agent", "cfg_agent"),
                    "item_user", new ModelMessage(ModelRole.USER, List.of(new TextContent("run"))),
                    List.of(), 0, START));
            ToolPresentation pending = presentation(ToolPresentation.Status.PENDING, null);
            ConversationRepository.CommitReceipt running = store.commit(new ConversationRepository.CommitRequest(
                    "thr_agent", "turn_agent", TurnState.RUNNING,
                    List.of(new ConversationRepository.AssistantFact("item_progress",
                                    new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("working"))),
                                    "working", "public reasoning", 1),
                            new ConversationRepository.UsageFact(new ModelUsage(42, 8, 50), 1),
                            new ConversationRepository.ToolPreparedFact("call_agent", "shell",
                                    JsonObjects.builder().putText("command", "echo ok").build(), 0,
                                    ToolSideEffect.EXTERNAL, pending)),
                    admission.turnMutationVersion(), START.plusSeconds(1)));
            String toolContent = "甲😀乙\nstdout";
            ToolPresentation completed = presentation(ToolPresentation.Status.SUCCESS, "artifact_tool_agent");
            ConversationRepository.CommitReceipt tool = store.commit(new ConversationRepository.CommitRequest(
                    "thr_agent", "turn_agent", TurnState.RUNNING,
                    List.of(new ConversationRepository.ToolStartedFact("call_agent"),
                            new ConversationRepository.ToolResultFact("call_agent", ToolState.SUCCEEDED,
                                    toolContent, false, completed, toolContent),
                            new ConversationRepository.ToolResultMessageFact("item_tool",
                                    new ModelMessage(ModelRole.TOOL,
                                            List.of(new ToolResultContent("call_agent", toolContent, false))))),
                    running.turnMutationVersion(), START.plusSeconds(2)));
            store.commitTerminal(new ConversationRepository.TerminalCommit(
                    "thr_agent", "turn_agent", TurnState.COMPLETED, "done", null, null,
                    "item_final", new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("done"))),
                    List.of(), tool.turnMutationVersion(), START.plusSeconds(3)));
            String diff = "diff --git a/甲.txt b/甲.txt\n+乙😀\n";
            TurnChangeSet changeSet = database.history(store).commitChangeSet(new ThreadUseCase.ChangeSetCommit(
                    "thr_agent", "turn_agent", "ws_agent",
                    new TurnChangeSet(TurnChangeSet.State.AVAILABLE, null,
                            List.of(new TurnChangeSet.FileChange("甲.txt", null,
                                    TurnChangeSet.FileStatus.MODIFIED, 1L, 0L, false, false)),
                            new TurnChangeSet.Stats(1, 1, 0, 0, false), null),
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
            assertEquals(new ThreadSnapshot.ContextUsage(
                    "turn_agent", 1, 42, 8, 50, START.plusSeconds(1)), snapshot.contextUsage());

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

            StringBuilder restoredDiff = new StringBuilder();
            int offset = 0;
            do {
                ThreadUseCase.BinaryTextArtifactPage page = restored.readChangeSetArtifact(
                        "thr_agent", "turn_agent", changeSet.artifactId(), offset, 17).orElseThrow();
                restoredDiff.append(page.content());
                if (page.nextOffsetBytes() == null) break;
                assertTrue(page.nextOffsetBytes() > offset);
                offset = page.nextOffsetBytes();
            } while (true);
            assertEquals(diff, restoredDiff.toString());
            assertFalse(restored.readChangeSetArtifact(
                    "thr_agent", "turn_other", changeSet.artifactId(), 0, 64).isPresent());
            restoredStore.close();
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
