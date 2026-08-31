// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.port.out.WorkspaceRepository;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.runtime;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 真实 SQLite 验证 WorkspaceRepository 出站合同及 revision CAS。 */
final class MybatisWorkspaceRepositoryTest extends PersistenceTestSupport {
    /** 注册、两种查询、分页、信任 CAS 和注销构成完整仓储闭环。 */
    @Test
    void persistsAndRemovesWorkspaceThroughOutboundRepository() throws Exception {
        try (TestDatabase database = database("workspace-repository")) {
            WorkspaceRepository repository = database.history(database.agentStore());
            Path root = temp.resolve("project").toAbsolutePath().normalize();
            Workspace.Registration registration = new Workspace.Registration(
                    "ws_project", root, "项目", Workspace.Trust.UNTRUSTED, START);

            Workspace registered = repository.register(registration);

            assertEquals(0, registered.revision());
            assertEquals(registered, repository.findById("ws_project").orElseThrow());
            assertEquals(registered, repository.findByRoot(root).orElseThrow());
            assertEquals(1, repository.list(null, 10).items().size());

            Workspace trusted = repository.updateTrust("ws_project", Workspace.Trust.TRUSTED);
            assertEquals(1, trusted.revision());
            assertEquals(Workspace.Trust.TRUSTED, trusted.trust());
            StorageException stale = assertThrows(StorageException.class,
                    () -> repository.unregister("ws_project", 0));
            assertEquals(StorageException.Code.CAS_CONFLICT, stale.code());
            repository.unregister("ws_project", trusted.revision());
            assertFalse(repository.findById("ws_project").isPresent());
        }
    }

    /** 同一身份重试不推进 revision，同一物理根也不允许绑定第二个身份。 */
    @Test
    void keepsRegistrationIdempotentAndRejectsRootIdentityConflict() throws Exception {
        try (TestDatabase database = database("workspace-identity")) {
            WorkspaceRepository repository = database.history(database.agentStore());
            Path root = temp.resolve("same-project").toAbsolutePath().normalize();
            Workspace.Registration registration = new Workspace.Registration(
                    "ws_stable", root, "首次名称", Workspace.Trust.UNTRUSTED, START);
            Workspace first = repository.register(registration);

            Workspace retry = repository.register(new Workspace.Registration(
                    "ws_stable", root, "重试名称", Workspace.Trust.TRUSTED, START.plusSeconds(1)));

            assertEquals(first, retry);
            assertEquals(0, retry.revision());
            StorageException conflict = assertThrows(StorageException.class,
                    () -> repository.register(new Workspace.Registration(
                            "ws_conflict", root, "冲突", Workspace.Trust.UNTRUSTED,
                            START.plusSeconds(2))));
            assertEquals(StorageException.Code.STORAGE_CONFLICT, conflict.code());
            assertTrue(repository.findById("ws_conflict").isEmpty());
            assertEquals(first, repository.findByRoot(root).orElseThrow());
        }
    }

    /** Thread 快照必须把空 Tool 结果原位投影到调用项，并保持稳定的 Wire ItemId。 */
    @Test
    void readsEmptyToolResultFromAuthoritativeThreadSnapshot() throws Exception {
        try (TestDatabase database = database("empty-tool-result")) {
            MybatisConversationRepository store = database.agentStore();
            MybatisHistoryService history = database.history(store);
            Path root = temp.resolve("tool-result-project").toAbsolutePath().normalize();
            history.register(new Workspace.Registration(
                    "ws_tool", root, "工具结果", Workspace.Trust.TRUSTED, START));
            store.createThread(new ConversationRepository.ThreadDefinition(
                    "thr_tool", "ws_tool", "工具结果",
                    preferences("provider_tool", "model_tool"), START));
            ConversationRepository.AdmissionReceipt admission = store.admit(
                    new ConversationRepository.TurnAdmission(
                            "thr_tool", "turn_tool", runtime("provider_tool", "model_tool", "cfg_tool"),
                            "item_user_tool",
                            new ModelMessage(ModelRole.USER, List.of(new TextContent("运行命令"))),
                            List.of(), 0, START));
            store.commit(new ConversationRepository.CommitRequest(
                    "thr_tool", "turn_tool", TurnState.RUNNING,
                    List.of(
                            new ConversationRepository.ToolPreparedFact(
                                    "call_tool", "shell", JsonObjects.builder()
                                     .put("argv", new JsonArray(List.of(new JsonText("pwsh.exe")))).build(), 0,
                                     ToolSideEffect.EXTERNAL, presentation(ToolPresentation.Status.PENDING)),
                            new ConversationRepository.ToolStartedFact("call_tool"),
                             new ConversationRepository.ToolResultFact(
                                     "call_tool", ToolState.FAILED, "", true,
                                     presentation(ToolPresentation.Status.ERROR), "")),
                    admission.turnMutationVersion(), START.plusSeconds(1)));

            ThreadSnapshot snapshot = history.readThread("thr_tool", null, 20).orElseThrow();
            ThreadSnapshot.ToolItem call = snapshot.items().stream()
                    .filter(ThreadSnapshot.ToolItem.class::isInstance)
                    .map(ThreadSnapshot.ToolItem.class::cast)
                    .filter(item -> item.kind() == ThreadSnapshot.ToolKind.TOOL_CALL)
                    .findFirst()
                    .orElseThrow();
            assertTrue(call.itemId().matches("item_[0-9a-f]{64}"));
            assertEquals(1, snapshot.items().stream().filter(ThreadSnapshot.ToolItem.class::isInstance).count());
            ThreadSnapshot repeated = history.readThread("thr_tool", null, 20).orElseThrow();
            assertEquals(snapshot.items().stream().map(ThreadSnapshot.Item::itemId).toList(),
                    repeated.items().stream().map(ThreadSnapshot.Item::itemId).toList());
            assertEquals(ToolPresentation.Status.ERROR, call.presentation().status());
        }
    }

    /**
     * 自动标题以 placeholder 作为真正所有权 CAS：普通偏好/Turn revision 推进不能饿死首次标题，
     * 而人工标题无论先提交还是在自动标题之后刷新 revision，最终都保持永久优先。
     */
    @Test
    void automaticTitleSurvivesRevisionProgressWhileManualTitleKeepsOwnership() throws Exception {
        try (TestDatabase database = database("automatic-title-ownership")) {
            MybatisConversationRepository store = database.agentStore();
            MybatisHistoryService history = database.history(store);
            Path root = temp.resolve("automatic-title-project").toAbsolutePath().normalize();
            history.register(new Workspace.Registration(
                    "ws_title", root, "标题竞争", Workspace.Trust.TRUSTED, START));
            ThreadPreferences initial = preferences("provider_title", "model_title");
            store.createThread(new ConversationRepository.ThreadDefinition(
                    "thr_auto", "ws_title", "新会话", initial, START));

            ThreadSummary progressed = history.updatePreferences(
                    "thr_auto", initial, 0);
            assertEquals(1, progressed.revision());
            assertTrue(history.writeAutomaticTitle("thr_auto", "自动标题", 0));
            ThreadSummary automatic = history.readThread("thr_auto", null, 1).orElseThrow().thread();
            assertEquals("自动标题", automatic.title());
            assertEquals(ThreadPreferences.TitleSource.AUTO, automatic.preferences().titleSource());

            StorageException staleManual = assertThrows(StorageException.class,
                    () -> history.renameThread("thr_auto", "人工标题", progressed.revision()));
            assertEquals(StorageException.Code.CAS_CONFLICT, staleManual.code());
            ThreadSummary manual = history.renameThread(
                    "thr_auto", "人工标题", automatic.revision());
            assertEquals(ThreadPreferences.TitleSource.MANUAL, manual.preferences().titleSource());
            assertFalse(history.writeAutomaticTitle("thr_auto", "迟到标题", automatic.revision()));
            assertEquals("人工标题", history.readThread("thr_auto", null, 1).orElseThrow().thread().title());

            store.createThread(new ConversationRepository.ThreadDefinition(
                    "thr_manual_first", "ws_title", "新会话", initial, START));
            ThreadSummary manualFirst = history.renameThread("thr_manual_first", "先人工", 0);
            assertFalse(history.writeAutomaticTitle("thr_manual_first", "后自动", 0));
            assertEquals("先人工", manualFirst.title());
        }
    }

    /** 历史仓储测试使用不含 raw 内容的最小安全展示对象。 */
    private static ToolPresentation presentation(ToolPresentation.Status status) {
        return new ToolPresentation(ToolPresentation.Kind.SHELL, "shell", status,
                null, null, List.of(), null, null, null, null, null, null, false, null);
    }
}
