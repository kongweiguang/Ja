// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import io.github.kongweiguang.ja.transport.rpc.runtime.RpcServer;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;
import io.github.kongweiguang.ja.bootstrap.SolonRuntimeComposition;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationWorkspaceTrustUseCase;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisHistoryService;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.kongweiguang.ja.transport.rpc.runtime.RpcRuntimeTestAccess.server;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.model.UserInputHistory;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadUsageSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnSummary;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.workspace.infrastructure.filesystem.NioWorkspaceDirectoryAdapter;
import io.github.kongweiguang.ja.workspace.application.service.WorkspaceService;
import io.github.kongweiguang.ja.workspace.application.service.WorkspaceAdmissionGate;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceTrustPort;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspaceBusyMessage;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspacePolicy;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 通过真实 JSONL 验证 WorkspaceUseCase 是工作区 RPC 的唯一业务 owner。 */
final class WorkspaceSessionRpcTest extends PersistenceTestSupport {
    private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** admission 尚未提交持久状态时使用固定重试说明，不伪造数据库任务摘要。 */
    @Test
    void mapsWorkspaceBusyToActionableChineseMessage() {
        WorkspaceAdmissionGate gate = new WorkspaceAdmissionGate();
        try (var admission = gate.acquire("ws_gate_busy_message")) {
            WorkspaceFailure failure = assertThrows(WorkspaceFailure.class,
                    () -> gate.acquireRebind("ws_gate_busy_message"));
            var rpc = WorkspaceHandler.mapFailure(failure);

            assertEquals("WORKSPACE_BUSY", rpc.errorCode());
            assertEquals(WorkspaceBusyMessage.PREFIX + "正在启动任务，请稍后重试。", rpc.getMessage());

            String titledBusy = WorkspaceBusyMessage.PREFIX + "会话“C:\\workspace\\secret token=abc”：等待审批";
            JaRpcException publicTitle = WorkspaceHandler.mapFailure(new WorkspaceFailure(
                    WorkspaceFailure.Code.WORKSPACE_BUSY, titledBusy));
            assertEquals(titledBusy, publicTitle.getMessage());
            assertThrows(IllegalArgumentException.class,
                    () -> JaRpcException.of(JaErrorCatalog.WORKSPACE_BUSY, "C:\\workspace\\secret"));
            assertThrows(IllegalArgumentException.class,
                    () -> JaRpcException.of(JaErrorCatalog.INVALID_PARAMS, titledBusy));
        }
    }

    /** SQLite busy 事实经真实 JSON-RPC 有界展示；状态、余数、标题清理和正文隔离保持闭环。 */
    @Test
    void workspaceBusyRpcReturnsBoundedSanitizedSqliteBlockers(@TempDir Path temporaryRoot) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SidecarConfiguration configuration = configuration(temporaryRoot);
        Path originalRoot = Files.createDirectory(temporaryRoot.resolve("busy-summary-project")).toRealPath();
        Path requestedRoot = Files.createDirectory(temporaryRoot.resolve("busy-summary-next-root")).toRealPath();
        String workspaceId = "ws_busy_summary_rpc";

        try (TestDatabase database = database("workspace-busy-summary-rpc")) {
            MybatisHistoryService history = database.history();
            Workspace workspace = history.register(new Workspace.Registration(workspaceId, originalRoot,
                    "忙碌摘要项目", Workspace.Trust.UNTRUSTED, Workspace.Kind.PROJECT, NOW));
            String approvalTitle = "审批\r\n主会话🙂 C:\\项目\\secret " + "界".repeat(180);
            String queuedInputTitle = "队列侧聊🚀 token=" + "界".repeat(180);
            String pausedPlanTitle = "暂停计划会话🐎 " + "a".repeat(32);
            createPersistentBusyThread(history, workspaceId, "thr_busy_01_approval", approvalTitle);
            createPersistentBusyThread(history, workspaceId, "thr_busy_02_input", queuedInputTitle);
            createPersistentBusyThread(history, workspaceId, "thr_busy_03_plan", pausedPlanTitle);
            createPersistentBusyThread(history, workspaceId, "thr_busy_04_extra", "未展示的运行会话");
            executeSql(database,
                    "UPDATE ja_conversation_thread SET temporary_lifecycle_state='OPEN' WHERE thread_id IN (?,?)",
                    "thr_busy_02_input", "thr_busy_03_plan");
            executeSql(database,
                    "INSERT INTO ja_conversation_turn(turn_id,thread_id,state,requested_at_ms,updated_at_ms) "
                            + "VALUES(?,?,?,?,?)",
                    "turn_busy_approval", "thr_busy_01_approval", "WAITING_APPROVAL",
                    NOW.toEpochMilli(), NOW.toEpochMilli());
            executeSql(database,
                    "INSERT INTO ja_conversation_turn(turn_id,thread_id,state,requested_at_ms,updated_at_ms,"
                            + "completed_at_ms,terminal_summary) VALUES(?,?,?,?,?,?,?)",
                    "turn_busy_input", "thr_busy_02_input", "COMPLETED", NOW.toEpochMilli(), NOW.toEpochMilli(),
                    NOW.toEpochMilli(), "completed");
            executeSql(database,
                    "INSERT INTO ja_turn_input(input_id,thread_id,turn_id,kind,content_json,state,created_at_ms,updated_at_ms) "
                            + "VALUES(?,?,?,?,?,?,?,?)",
                    "input_busy_private", "thr_busy_02_input", "turn_busy_input", "FOLLOW_UP",
                    "[{\"text\":\"private-body-sentinel\"}]", "PENDING", NOW.toEpochMilli(), NOW.toEpochMilli());
            persistPausedBusyPlan(database, "thr_busy_03_plan", NOW.toEpochMilli());
            executeSql(database,
                    "INSERT INTO ja_conversation_turn(turn_id,thread_id,state,requested_at_ms,updated_at_ms) "
                            + "VALUES(?,?,?,?,?)",
                    "turn_busy_extra", "thr_busy_04_extra", "RUNNING", NOW.toEpochMilli(), NOW.toEpochMilli());

            WorkspaceService sqlBackedWorkspaces = new WorkspaceService(history,
                    new NioWorkspaceDirectoryAdapter(configuration.homeDirectory()), ignored -> { },
                    (ignored, trust) -> { }, new WorkspacePolicy(), CLOCK);
            RunResult run = run(configuration, new WorkspaceHistory(), mapper, ignored -> { },
                    ignored -> sqlBackedWorkspaces,
                    request(mapper, "c:busy-summary-update", "workspace/update", mapper.createObjectNode()
                            .put("workspaceId", workspaceId).put("expectedRevision", workspace.revision())
                            .put("cwd", requestedRoot.toString())));

            assertEquals("WORKSPACE_BUSY", errorCode(run.frames(), "c:busy-summary-update"));
            String message = response(run.frames(), "c:busy-summary-update")
                    .path("error").path("message").textValue();
            String expectedMessage = WorkspaceBusyMessage.durableWork(java.util.List.of(
                    new WorkspaceBusyMessage.Blocker(approvalTitle, "TURN", "WAITING_APPROVAL", 1),
                    new WorkspaceBusyMessage.Blocker(queuedInputTitle, "INPUT", "PENDING", 1),
                    new WorkspaceBusyMessage.Blocker(pausedPlanTitle, "PLAN", "PAUSED", 1)), 4);
            assertEquals(expectedMessage, message);
            assertTrue(message.startsWith(WorkspaceBusyMessage.PREFIX));
            assertTrue(message.contains("会话“审批 主会话🙂"));
            assertTrue(message.contains("界"));
            assertTrue(message.contains("C:\\项目\\secret"));
            assertTrue(message.contains("🚀"));
            assertTrue(message.contains("token="));
            assertTrue(message.contains("🐎"));
            assertTrue(message.contains("a".repeat(32)));
            assertTrue(message.contains("等待审批"));
            assertTrue(message.contains("待处理输入"));
            assertTrue(message.contains("计划已暂停"));
            assertFalse(message.contains("WAITING_APPROVAL"));
            assertFalse(message.contains("（PAUSED）"));
            assertFalse(message.contains("Turn "));
            assertFalse(message.contains("Plan "));
            assertTrue(message.contains("另有 1 项未列出"));
            assertTrue(message.contains("提交或取消待处理输入"));
            assertTrue(message.contains("继续或取消暂停计划后重试"));
            assertFalse(message.contains("取消不需要的排队任务"));
            assertFalse(message.contains("等待运行任务结束"));
            assertFalse(message.contains("界".repeat(40)));
            assertFalse(message.contains("未展示的运行会话"));
            assertFalse(message.contains("private-body-sentinel"));
            assertTrue(message.codePoints().noneMatch(Character::isISOControl));
            assertTrue(message.getBytes(StandardCharsets.UTF_8).length <= 512);
            assertTrue(message.length() <= 512);
            ObjectNode error = response(run.frames(), "c:busy-summary-update").path("error").deepCopy();
            assertEquals(-32_100, error.path("code").intValue());
            assertEquals("WORKSPACE_BUSY", error.path("data").path("errorCode").textValue());
            assertEquals("conflict", error.path("data").path("category").textValue());
            assertTrue(error.path("data").path("retryable").booleanValue());
            assertEquals(workspace, history.findById(workspaceId).orElseThrow());
        }
    }

    /** 两个无项目 Thread 获得不同会话根；重启后只能按登记 ID 重开。 */
    @Test
    void noProjectThreadsUseDistinctDurableSessionDirectories(@TempDir Path temporaryRoot)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SidecarConfiguration configuration = configuration(temporaryRoot);
        WorkspaceHistory history = new WorkspaceHistory();
        RunResult first = run(configuration, history, mapper,
                request(mapper, "c:thread-first", "thread/create", mapper.createObjectNode()
                        .put("title", "无项目测试")
                        .put("providerId", "provider_test").put("modelId", "model_test")
                        .put("reasoningLevel", "medium").put("accessMode", "approval_required")
                        .put("collaborationMode", "default")),
                request(mapper, "c:thread-second", "thread/create", mapper.createObjectNode()
                        .put("title", "第二个无项目测试")
                        .put("providerId", "provider_test").put("modelId", "model_test")
                        .put("reasoningLevel", "medium").put("accessMode", "approval_required")
                        .put("collaborationMode", "default")),
                request(mapper, "c:thread-explicit-null-cwd", "thread/create", mapper.createObjectNode()
                        .putNull("cwd").put("title", "null cwd must fail")
                        .put("providerId", "provider_test").put("modelId", "model_test")
                        .put("reasoningLevel", "medium").put("accessMode", "approval_required")
                        .put("collaborationMode", "default")));
        RunResult sessionThreads = run(configuration, history, mapper,
                request(mapper, "c:session-thread-list", "thread/list", mapper.createObjectNode()
                        .put("workspaceKind", "session")),
                request(mapper, "c:session-thread-search", "thread/search", mapper.createObjectNode()
                        .put("workspaceKind", "session").put("query", "第二")),
                request(mapper, "c:session-workspace-list", "workspace/list", mapper.createObjectNode()
                        .put("kind", "session")),
                request(mapper, "c:legacy-workspace-kind", "workspace/list", mapper.createObjectNode()
                        .put("kind", "legacy_shared")),
                request(mapper, "c:legacy-workspace-field", "workspace/list", mapper.createObjectNode()
                        .put("kind", "project").putNull("legacySharedWorkspaceId")));

        ObjectNode firstThread = result(first.frames(), "c:thread-first");
        ObjectNode secondThread = result(first.frames(), "c:thread-second");
        String firstWorkspaceId = firstThread.path("workspaceId").textValue();
        String secondWorkspaceId = secondThread.path("workspaceId").textValue();
        assertTrue(firstWorkspaceId.startsWith("ws_"));
        assertNotEquals(firstWorkspaceId, secondWorkspaceId);
        assertEquals("session", firstThread.path("workspaceKind").textValue());
        assertEquals("session", secondThread.path("workspaceKind").textValue());
        assertFalse(firstThread.has("legacySharedWorkspaceId"));
        assertFalse(secondThread.has("legacySharedWorkspaceId"));
        Workspace firstWorkspace = history.findById(firstWorkspaceId).orElseThrow();
        Workspace secondWorkspace = history.findById(secondWorkspaceId).orElseThrow();
        assertEquals(configuration.homeDirectory().resolve("workspaces").resolve(firstThread.path("threadId").textValue())
                .toRealPath(), firstWorkspace.root());
        assertEquals(configuration.homeDirectory().resolve("workspaces").resolve(secondThread.path("threadId").textValue())
                .toRealPath(), secondWorkspace.root());
        assertNotEquals(firstWorkspace.root(), secondWorkspace.root());
        assertEquals(0, first.prepareCalls());
        assertEquals("INVALID_PARAMS", errorCode(first.frames(), "c:thread-explicit-null-cwd"));
        assertEquals(2, history.threads.size(), "explicit null cannot create a third session alias");
        ObjectNode listedThreads = result(sessionThreads.frames(), "c:session-thread-list");
        ObjectNode searchedThreads = result(sessionThreads.frames(), "c:session-thread-search");
        ObjectNode listedWorkspaces = result(sessionThreads.frames(), "c:session-workspace-list");
        assertEquals(2, listedThreads.path("items").size());
        assertEquals("session", listedThreads.path("items").get(0).path("workspaceKind").textValue());
        assertEquals(1, searchedThreads.path("items").size());
        assertEquals("第二个无项目测试", searchedThreads.path("items").get(0).path("title").textValue());
        assertEquals(2, listedWorkspaces.path("items").size());
        assertEquals("session", listedWorkspaces.path("items").get(0).path("kind").textValue());
        assertEquals("INVALID_PARAMS", errorCode(sessionThreads.frames(), "c:legacy-workspace-kind"));
        assertEquals("INVALID_PARAMS", errorCode(sessionThreads.frames(), "c:legacy-workspace-field"));

        RunResult restarted = run(configuration, history, mapper,
                request(mapper, "c:session-reopened", "workspace/open", mapper.createObjectNode()
                        .put("workspaceId", firstWorkspaceId)));
        ObjectNode reopened = result(restarted.frames(), "c:session-reopened");
        assertExactKeys(reopened, "workspaceId", "root", "displayName", "trust", "kind", "revision");
        assertEquals(firstWorkspaceId, reopened.path("workspaceId").textValue());
        assertEquals(firstWorkspace.root().toString(), reopened.path("root").textValue());
        assertEquals("session", reopened.path("kind").textValue());
        assertFalse(reopened.has("legacySharedWorkspaceId"));
    }

    /** 会话目录创建失败必须阻止 thread/create，不得登记空会话或退回旧共享路径。 */
    @Test
    void sessionDirectoryCreationFailureDoesNotFallbackOrCreateThread(@TempDir Path temporaryRoot)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SidecarConfiguration configuration = configuration(temporaryRoot);
        Files.writeString(configuration.homeDirectory().resolve("workspaces"), "not a directory");
        WorkspaceHistory history = new WorkspaceHistory();
        RunResult failed = run(configuration, history, mapper,
                request(mapper, "c:thread-directory-failure", "thread/create", mapper.createObjectNode()
                        .put("title", "创建失败")
                        .put("providerId", "provider_test").put("modelId", "model_test")
                        .put("accessMode", "approval_required").put("collaborationMode", "default")));

        assertEquals("WORKSPACE_NOT_FOUND", errorCode(failed.frames(), "c:thread-directory-failure"));
        assertTrue(history.byId.isEmpty());
        assertTrue(history.threads.isEmpty());
        assertFalse(Files.exists(configuration.dataDirectory().resolve("general-workspace")));
    }

    /** 项目打开、Thread 创建、信任、统一分页和注销必须通过同一应用 owner 闭环。 */
    @Test
    void projectWorkspaceCommandsUseOneApplicationOwner(@TempDir Path temporaryRoot) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SidecarConfiguration configuration = configuration(temporaryRoot);
        Path project = Files.createDirectory(temporaryRoot.resolve("project"));
        WorkspaceHistory history = new WorkspaceHistory();
        WorkspaceHistory nameValidation = new WorkspaceHistory();
        RunResult nullDisplayName = run(configuration, nameValidation, mapper,
                request(mapper, "c:open-null-name", "workspace/open", mapper.createObjectNode()
                        .put("cwd", project.toString()).putNull("displayName")));
        assertEquals("INVALID_PARAMS", errorCode(nullDisplayName.frames(), "c:open-null-name"));
        RunResult omittedDisplayName = run(configuration, nameValidation, mapper,
                request(mapper, "c:open-omitted-name", "workspace/open", mapper.createObjectNode()
                        .put("cwd", project.toString())));
        assertTrue(result(omittedDisplayName.frames(), "c:open-omitted-name")
                .path("workspaceId").textValue().matches("ws_[0-9a-f]{32}"));
        RunResult openedRun = run(configuration, history, mapper,
                request(mapper, "c:open", "workspace/open", mapper.createObjectNode()
                        .put("cwd", project.toString()).put("displayName", "项目")));
        String workspaceId = result(openedRun.frames(), "c:open").path("workspaceId").textValue();
        RunResult threadRun = run(configuration, history, mapper,
                request(mapper, "c:thread", "thread/create", mapper.createObjectNode()
                        .put("cwd", project.toString()).put("title", "项目会话")
                        .put("providerId", "provider_test").put("modelId", "model_test")
                        .put("reasoningLevel", "medium").put("accessMode", "approval_required")
                        .put("collaborationMode", "default")));
        RunResult trustRun = run(configuration, history, mapper,
                workspaces -> workspaces.openWorkspace(
                        new WorkspaceUseCase.OpenWorkspace(project, null)),
                request(mapper, "c:trust", "workspace/set-trust", mapper.createObjectNode()
                        .put("workspaceId", workspaceId).put("trust", "trusted")));
        RunResult listRun = run(configuration, history, mapper,
                request(mapper, "c:list", "workspace/list", mapper.createObjectNode()
                        .put("limit", 20).put("kind", "project")));
        RunResult unregisterRun = run(configuration, history, mapper,
                workspaces -> workspaces.openWorkspace(
                        new WorkspaceUseCase.OpenWorkspace(project, null)),
                request(mapper, "c:unregister", "workspace/unregister", mapper.createObjectNode()
                        .put("workspaceId", workspaceId).put("expectedRevision", 1)));

        ObjectNode opened = result(openedRun.frames(), "c:open");
        assertEquals(opened.path("workspaceId").textValue(),
                result(threadRun.frames(), "c:thread").path("workspaceId").textValue());
        assertTrue(result(trustRun.frames(), "c:trust").path("accepted").booleanValue());
        ObjectNode page = result(listRun.frames(), "c:list");
        assertExactKeys(page, "items", "nextCursor");
        assertEquals("trusted", page.path("items").get(0).path("trust").textValue());
        assertEquals("project", page.path("items").get(0).path("kind").textValue());
        assertEquals(1, page.path("items").size());
        assertTrue(result(unregisterRun.frames(), "c:unregister").path("accepted").booleanValue());
        assertEquals(1, openedRun.prepareCalls());
        assertEquals(1, threadRun.prepareCalls());
        assertEquals(2, trustRun.prepareCalls());
        assertEquals(0, listRun.prepareCalls());
        assertEquals(1, unregisterRun.prepareCalls());
    }

    /** 新增 Workspace 元数据 RPC 对字段集与名称长度采用单一闭集边界，并让恰好 512 字符可提交。 */
    @Test
    void workspaceMetadataCommandsEnforceClosedFieldsAndDisplayNameBounds(@TempDir Path temporaryRoot)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SidecarConfiguration configuration = configuration(temporaryRoot);
        Path missingRoot = temporaryRoot.resolve("project-root-removed").toAbsolutePath().normalize();
        String workspaceId = "ws_metadata_validation";
        WorkspaceHistory history = new WorkspaceHistory();
        history.register(new Workspace.Registration(workspaceId, missingRoot, "初始名称",
                Workspace.Trust.UNTRUSTED, Workspace.Kind.PROJECT, CLOCK.instant()));

        RunResult run = run(configuration, history, mapper,
                request(mapper, "c:read-extra", "workspace/read", mapper.createObjectNode()
                        .put("workspaceId", workspaceId).put("unexpected", true)),
                request(mapper, "c:update-extra", "workspace/update", mapper.createObjectNode()
                        .put("workspaceId", workspaceId).put("expectedRevision", 0)
                        .put("displayName", "不应提交").put("unexpected", true)),
                request(mapper, "c:update-empty-name", "workspace/update", mapper.createObjectNode()
                        .put("workspaceId", workspaceId).put("expectedRevision", 0).put("displayName", "")),
                request(mapper, "c:update-long-name", "workspace/update", mapper.createObjectNode()
                        .put("workspaceId", workspaceId).put("expectedRevision", 0)
                        .put("displayName", "项".repeat(513))),
                request(mapper, "c:update-boundary-name", "workspace/update", mapper.createObjectNode()
                        .put("workspaceId", workspaceId).put("expectedRevision", 0)
                        .put("displayName", "项".repeat(512))));

        assertEquals("INVALID_PARAMS", errorCode(run.frames(), "c:read-extra"));
        assertEquals("INVALID_PARAMS", errorCode(run.frames(), "c:update-extra"));
        assertEquals("INVALID_PARAMS", errorCode(run.frames(), "c:update-empty-name"));
        assertEquals("INVALID_PARAMS", errorCode(run.frames(), "c:update-long-name"));
        ObjectNode updated = result(run.frames(), "c:update-boundary-name");
        assertEquals(512, updated.path("displayName").textValue().length());
        Workspace persisted = history.findById(workspaceId).orElseThrow();
        assertEquals(missingRoot, persisted.root());
        assertEquals(Workspace.Trust.UNTRUSTED, persisted.trust());
        assertEquals(1, persisted.revision());
    }

    /** SQLite revision 已提交而后续目录同步失败时，RPC 仍回读发布事件并保留原始错误响应。 */
    @Test
    void publishesCommittedWorkspaceRevisionWhenPostCommitUpdateFails(@TempDir Path temporaryRoot) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SidecarConfiguration configuration = configuration(temporaryRoot);
        Path originalRoot = Files.createDirectory(temporaryRoot.resolve("original-project")).toRealPath();
        Path reboundRoot = Files.createDirectory(temporaryRoot.resolve("rebound-project")).toRealPath();
        String workspaceId = "ws_post_commit_rebind";
        WorkspaceHistory history = new WorkspaceHistory();
        history.register(new Workspace.Registration(workspaceId, originalRoot, "原项目",
                Workspace.Trust.UNTRUSTED, Workspace.Kind.PROJECT, CLOCK.instant()));

        RunResult run = run(configuration, history, mapper, ignored -> { },
                WorkspaceSessionRpcTest::failAfterWorkspaceUpdate,
                request(mapper, "c:update-fails-after-commit", "workspace/update", mapper.createObjectNode()
                        .put("workspaceId", workspaceId).put("expectedRevision", 0)
                        .put("cwd", reboundRoot.toString())));

        assertEquals("INTERNAL_ERROR", errorCode(run.frames(), "c:update-fails-after-commit"));
        // RPC dispatch is concurrent, so issue the verification read after update completion in a new generation.
        RunResult readRun = run(configuration, history, mapper,
                request(mapper, "c:read-after-failed-update", "workspace/read", mapper.createObjectNode()
                        .put("workspaceId", workspaceId)));
        ObjectNode persisted = result(readRun.frames(), "c:read-after-failed-update");
        assertEquals(reboundRoot.toString(), persisted.path("root").textValue());
        assertEquals(1, persisted.path("revision").longValue());
        List<ObjectNode> notifications = run.frames().stream()
                .filter(frame -> "workspace/metadata-changed".equals(frame.path("method").textValue()))
                .toList();
        assertEquals(1, notifications.size());
        ObjectNode event = (ObjectNode) notifications.getFirst().path("params");
        assertEquals(workspaceId, event.path("workspaceId").textValue());
        assertEquals(1, event.path("revision").longValue());
        assertTrue(event.hasNonNull("serverInstanceId"));
        assertTrue(event.hasNonNull("eventId"));
        assertTrue(event.hasNonNull("sequence"));
        assertTrue(event.hasNonNull("occurredAt"));
        assertTrue(event.hasNonNull("generation"));
    }

    /** 真实 WorkspaceTrustPort 在 SQLite CAS 后同步失败时，更新错误不掩盖 DB 事实且 revision 事件仍闭环。 */
    @Test
    void publishesAndReadsSqliteWorkspaceWhenConfigurationSyncFailsAfterCas(@TempDir Path temporaryRoot)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SidecarConfiguration configuration = configuration(temporaryRoot);
        Path originalRoot = Files.createDirectory(temporaryRoot.resolve("sqlite-original-project")).toRealPath();
        Path reboundRoot = Files.createDirectory(temporaryRoot.resolve("sqlite-rebound-project")).toRealPath();
        String workspaceId = "ws_sqlite_post_commit_failure";

        try (TestDatabase database = database("workspace-config-sync-failure")) {
            MybatisHistoryService history = database.history();
            Workspace registered = history.register(new Workspace.Registration(workspaceId, originalRoot,
                    "原项目名称", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, NOW));
            List<String> synchronizedTrust = new ArrayList<>();
            ConfigurationWorkspaceTrustUseCase configurationTrust = (root, trusted) -> {
                synchronizedTrust.add(root + ":" + trusted);
                if (root.equals(reboundRoot)) {
                    throw new IllegalStateException("simulated configuration synchronization failure");
                }
            };
            WorkspaceTrustPort trustPort = new SolonRuntimeComposition().workspaceTrustPort(configurationTrust);
            WorkspaceService sqlBackedWorkspaces = new WorkspaceService(history,
                    new NioWorkspaceDirectoryAdapter(configuration.homeDirectory()), ignored -> { }, trustPort,
                    new WorkspacePolicy(), CLOCK);

            RunResult updateRun = run(configuration, new WorkspaceHistory(), mapper, ignored -> { },
                    ignored -> sqlBackedWorkspaces,
                    request(mapper, "c:sqlite-update-fails-after-cas", "workspace/update", mapper.createObjectNode()
                            .put("workspaceId", workspaceId).put("expectedRevision", registered.revision())
                            .put("displayName", "重绑后名称").put("cwd", reboundRoot.toString())));
            RunResult readRun = run(configuration, new WorkspaceHistory(), mapper, ignored -> { },
                    ignored -> sqlBackedWorkspaces,
                    request(mapper, "c:sqlite-read-after-failed-sync", "workspace/read", mapper.createObjectNode()
                            .put("workspaceId", workspaceId)));

            assertEquals("INTERNAL_ERROR", errorCode(updateRun.frames(), "c:sqlite-update-fails-after-cas"));
            ObjectNode persisted = result(readRun.frames(), "c:sqlite-read-after-failed-sync");
            assertExactKeys(persisted, "workspaceId", "root", "displayName", "trust", "kind", "revision");
            assertEquals(workspaceId, persisted.path("workspaceId").textValue());
            assertEquals(reboundRoot.toString(), persisted.path("root").textValue());
            assertEquals("重绑后名称", persisted.path("displayName").textValue());
            assertEquals("untrusted", persisted.path("trust").textValue());
            assertEquals("project", persisted.path("kind").textValue());
            assertEquals(registered.revision() + 1, persisted.path("revision").longValue());
            assertEquals(List.of(originalRoot + ":false", reboundRoot + ":false"), synchronizedTrust);

            List<ObjectNode> notifications = updateRun.frames().stream()
                    .filter(frame -> "workspace/metadata-changed".equals(frame.path("method").textValue()))
                    .toList();
            assertEquals(1, notifications.size());
            ObjectNode event = (ObjectNode) notifications.getFirst().path("params");
            assertEquals(workspaceId, event.path("workspaceId").textValue());
            assertEquals(registered.revision() + 1, event.path("revision").longValue());
            assertTrue(event.hasNonNull("serverInstanceId"));
            assertTrue(event.hasNonNull("eventId"));
            assertTrue(event.hasNonNull("sequence"));
            assertTrue(event.hasNonNull("occurredAt"));
            assertTrue(event.hasNonNull("generation"));
        }
    }

    /** workspace/update 的过期 CAS 必须拒绝名称与目录同时变更，并保留真实 SQLite 当前投影。 */
    @Test
    void staleWorkspaceUpdateLeavesSqliteRootAndDisplayNameUnchanged(@TempDir Path temporaryRoot)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SidecarConfiguration configuration = configuration(temporaryRoot);
        Path originalRoot = Files.createDirectory(temporaryRoot.resolve("stale-original-project")).toRealPath();
        Path rejectedRoot = Files.createDirectory(temporaryRoot.resolve("stale-rejected-project")).toRealPath();
        String workspaceId = "ws_stale_rpc_update";

        try (TestDatabase database = database("workspace-stale-rpc-update")) {
            MybatisHistoryService history = database.history();
            Workspace initial = history.register(new Workspace.Registration(workspaceId, originalRoot,
                    "当前名称", Workspace.Trust.UNTRUSTED, Workspace.Kind.PROJECT, NOW));
            Workspace current = history.updateTrust(workspaceId, Workspace.Trust.TRUSTED);
            WorkspaceService sqlBackedWorkspaces = new WorkspaceService(history,
                    new NioWorkspaceDirectoryAdapter(configuration.homeDirectory()), ignored -> { },
                    (ignored, trust) -> { }, new WorkspacePolicy(), CLOCK);

            RunResult updateRun = run(configuration, new WorkspaceHistory(), mapper, ignored -> { },
                    ignored -> sqlBackedWorkspaces,
                    request(mapper, "c:stale-workspace-update", "workspace/update", mapper.createObjectNode()
                            .put("workspaceId", workspaceId).put("expectedRevision", initial.revision())
                            .put("displayName", "过期名称").put("cwd", rejectedRoot.toString())));
            RunResult readRun = run(configuration, new WorkspaceHistory(), mapper, ignored -> { },
                    ignored -> sqlBackedWorkspaces,
                    request(mapper, "c:read-after-stale-workspace-update", "workspace/read",
                            mapper.createObjectNode().put("workspaceId", workspaceId)));

            assertEquals("CONFLICT", errorCode(updateRun.frames(), "c:stale-workspace-update"));
            ObjectNode persisted = result(readRun.frames(), "c:read-after-stale-workspace-update");
            assertEquals(workspaceId, persisted.path("workspaceId").textValue());
            assertEquals(originalRoot.toString(), persisted.path("root").textValue());
            assertEquals("当前名称", persisted.path("displayName").textValue());
            assertEquals("trusted", persisted.path("trust").textValue());
            assertEquals(current.revision(), persisted.path("revision").longValue());
            List<ObjectNode> notifications = updateRun.frames().stream()
                    .filter(frame -> "workspace/metadata-changed".equals(frame.path("method").textValue()))
                    .toList();
            assertEquals(1, notifications.size(), "stale clients should learn the current committed revision");
            assertEquals(current.revision(), notifications.getFirst().path("params").path("revision").longValue());
        }
    }

    /** thread/seen 只转发精确身份与 revision，无 Turn 时幂等返回完整已读投影并严格拒绝额外字段。 */
    @Test
    void threadSeenUsesStrictParamsAndReturnsCompleteProjection(@TempDir Path temporaryRoot) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SidecarConfiguration configuration = configuration(temporaryRoot);
        WorkspaceHistory history = new WorkspaceHistory();
        RunResult created = run(configuration, history, mapper,
                request(mapper, "c:create-seen", "thread/create", mapper.createObjectNode()
                        .put("title", "已读测试")
                        .put("providerId", "provider_test").put("modelId", "model_test")
                        .putNull("reasoningLevel").put("accessMode", "approval_required")
                        .put("collaborationMode", "default")));
        String threadId = result(created.frames(), "c:create-seen").path("threadId").textValue();

        RunResult seen = run(configuration, history, mapper,
                request(mapper, "c:seen", "thread/seen", mapper.createObjectNode()
                        .put("threadId", threadId).put("expectedThreadRevision", 0)),
                request(mapper, "c:seen-extra", "thread/seen", mapper.createObjectNode()
                        .put("threadId", threadId).put("expectedThreadRevision", 0).put("turnId", "turn_client")));

        ObjectNode projection = result(seen.frames(), "c:seen");
        assertExactKeys(projection, "threadId", "workspaceId", "workspaceKind", "title", "preferences", "status", "pinned",
                "latestTurnStatus", "latestTurnSeen", "revision", "createdAt", "updatedAt");
        assertFalse(projection.has("legacySharedWorkspaceId"));
        assertTrue(projection.path("latestTurnSeen").booleanValue());
        assertTrue(projection.path("latestTurnStatus").isNull());
        assertEquals("INVALID_PARAMS", errorCode(seen.frames(), "c:seen-extra"));
    }

    /** 仓储返回不同物理根时必须在 transport 绑定前映射为脱敏约束错误。 */
    @Test
    void sessionWorkspaceRejectsPersistedRootMismatch(@TempDir Path temporaryRoot) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path wrongRoot = Files.createDirectory(temporaryRoot.resolve("wrong-root"));
        RunResult run = run(configuration(temporaryRoot), new WorkspaceHistory(wrongRoot), mapper,
                request(mapper, "c:thread-root", "thread/create", mapper.createObjectNode()
                        .put("title", "隔离会话")
                        .put("providerId", "provider_test").put("modelId", "model_test")
                        .put("accessMode", "approval_required").put("collaborationMode", "default")));

        assertEquals("WORKSPACE_CONFINEMENT", errorCode(run.frames(), "c:thread-root"));
    }

    /** 通过正式 History 用例创建会话，确保忙碌摘要标题来自真实 Thread 元数据。 */
    private static void createPersistentBusyThread(MybatisHistoryService history, String workspaceId,
                                                   String threadId, String title) {
        history.createThread(new ThreadSummary.Creation(threadId, workspaceId, title,
                preferences("provider_busy_summary", "model_busy_summary"), NOW));
    }

    /** 构造满足真实外键与执行状态约束的 paused Plan，不从其 objective 或执行记录读取正文。 */
    private static void persistPausedBusyPlan(TestDatabase database, String threadId, long at) throws Exception {
        String planId = "plan_busy_summary";
        String revisionId = "planrev_busy_summary";
        String runId = "run_busy_summary";
        String hash = "c".repeat(64);
        executeSql(database,
                "INSERT INTO ja_plan(plan_id,owner_thread_id,objective,create_operation_key,status,revision,"
                        + "created_at_ms,updated_at_ms) VALUES(?,?,?,?,?,?,?,?)",
                planId, threadId, "摘要隔离测试", "busy:summary:plan", "DRAFT", 0, at, at);
        executeSql(database,
                "INSERT INTO ja_plan_revision(plan_revision_id,plan_id,revision_number,definition_json,plan_hash,"
                        + "created_by,created_at_ms) VALUES(?,?,?,?,?,?,?)",
                revisionId, planId, 1, "{}", hash, "USER_UI", at);
        executeSql(database,
                "INSERT INTO ja_plan_approval(approval_id,plan_id,plan_revision_id,plan_hash,actor,decision,created_at_ms) "
                        + "VALUES(?,?,?,?,?,?,?)",
                "approval_busy_summary", planId, revisionId, hash, "USER_UI", "APPROVED", at);
        executeSql(database,
                "INSERT INTO ja_plan_execution(run_id,plan_id,plan_revision_id,plan_hash,status,process_generation,"
                        + "started_at_ms,created_at_ms,updated_at_ms) VALUES(?,?,?,?,?,?,?,?,?)",
                runId, planId, revisionId, hash, "PAUSED", 1, at, at, at);
        executeSql(database,
                "UPDATE ja_plan SET status='PAUSED',revision=1,active_plan_revision_id=?,active_run_id=? "
                        + "WHERE plan_id=?",
                revisionId, runId, planId);
    }

    /** 在隔离真实 SQLite 中写入符合当前持久状态约束的最小事实，不依赖测试执行次序。 */
    private static void executeSql(TestDatabase database, String sql, Object... values) throws Exception {
        try (SqlSession session = database.sessions().openSession()) {
            try (java.sql.PreparedStatement statement = session.getConnection().prepareStatement(sql)) {
                for (int index = 0; index < values.length; index++) {
                    statement.setObject(index + 1, values[index]);
                }
                statement.executeUpdate();
            }
            session.commit();
        }
    }

    /** 构造不启动 Solon 的生产布局，确保会话根落在独立 Ja Home 而不是数据库 data 目录。 */
    private static SidecarConfiguration configuration(Path temporaryRoot) throws Exception {
        Path home = Files.createDirectory(temporaryRoot.resolve("home"));
        Path data = Files.createDirectory(temporaryRoot.resolve("data"));
        Path run = Files.createDirectory(temporaryRoot.resolve("run"));
        Path logs = Files.createDirectory(temporaryRoot.resolve("logs"));
        return new SidecarConfiguration(home, data, run, logs);
    }

    /** 对一个隔离 sidecar generation 执行真实 JSONL 请求，并保留预热次数。 */
    private static RunResult run(
            SidecarConfiguration configuration,
            WorkspaceHistory history,
            ObjectMapper mapper,
            ObjectNode... requests) throws Exception {
        return run(configuration, history, mapper, ignored -> { }, requests);
    }

    /** 允许测试在启动前通过正式入站端口绑定物理目录，避免依赖异步请求执行顺序。 */
    private static RunResult run(
            SidecarConfiguration configuration,
            WorkspaceHistory history,
            ObjectMapper mapper,
            Consumer<WorkspaceUseCase> beforeRun,
            ObjectNode... requests) throws Exception {
        return run(configuration, history, mapper, beforeRun, UnaryOperator.identity(), requests);
    }

    /** Decorates the actual application owner only for tests that exercise RPC behavior after a durable commit. */
    private static RunResult run(
            SidecarConfiguration configuration,
            WorkspaceHistory history,
            ObjectMapper mapper,
            Consumer<WorkspaceUseCase> beforeRun,
            UnaryOperator<WorkspaceUseCase> workspaceDecorator,
            ObjectNode... requests) throws Exception {
        StringBuilder input = new StringBuilder();
        input.append(mapper.writeValueAsString(initialize(mapper))).append('\n');
        input.append("""
            {"jsonrpc":"2.0","method":"runtime/initialized",\
            "params":{"readyToken":"0123456789abcdef0123456789abcdef"}}
            """);
        for (ObjectNode request : requests) {
            input.append(mapper.writeValueAsString(request)).append('\n');
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicInteger prepareCalls = new AtomicInteger();
        WorkspaceUseCase workspaces = new WorkspaceService(
                history,
                new NioWorkspaceDirectoryAdapter(configuration.homeDirectory()),
                ignored -> prepareCalls.incrementAndGet(),
                (ignored, trust) -> { },
                new WorkspacePolicy(),
                CLOCK);
        beforeRun.accept(workspaces);
        WorkspaceUseCase exposedWorkspaces = workspaceDecorator.apply(workspaces);
        RpcServer server = server(
                new ByteArrayInputStream(input.toString().getBytes(StandardCharsets.UTF_8)),
                output,
                configuration,
                CLOCK,
                ignored -> RpcTestBindings.create(
                        exposedWorkspaces, history, null, null, null, () -> closed.set(true)),
                TestConfigurationPorts.unavailable());
        assertEquals(0, server.run());
        List<ObjectNode> frames = new ArrayList<>();
        for (String line : output.toString(StandardCharsets.UTF_8).lines().toList()) {
            if (!line.isBlank()) {
                frames.add((ObjectNode) mapper.readTree(line));
            }
        }
        assertTrue(closed.get());
        assertEquals(2, result(frames, "c:init").path("protocolMajor").intValue());
        return new RunResult(frames, prepareCalls.get());
    }

    /** Calls the production update first and injects a failure afterward to model native sync failing after CAS. */
    private static WorkspaceUseCase failAfterWorkspaceUpdate(WorkspaceUseCase delegate) {
        return (WorkspaceUseCase) Proxy.newProxyInstance(WorkspaceUseCase.class.getClassLoader(),
                new Class<?>[]{WorkspaceUseCase.class}, (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if ("updateWorkspace".equals(method.getName())) {
                            throw new IllegalStateException("simulated post-commit synchronization failure");
                        }
                        return result;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    /** 构造当前 JA-RPC 2.0 initialize 请求，避免 Workspace 测试携带过期协议版本。 */
    private static ObjectNode initialize(ObjectMapper mapper) {
        ObjectNode params = mapper.createObjectNode().put("protocolMajor", 2).put("protocolMinor", 0)
                .put("clientVersion", "0.2.0");
        params.set("capabilities", HandshakeHandler.capabilities(mapper));
        params.set("limits", HandshakeHandler.limits(mapper));
        return request(mapper, "c:init", "runtime/initialize", params);
    }

    /** 构造带明确 object params 边界的请求。 */
    private static ObjectNode request(ObjectMapper mapper, String id, String method, ObjectNode params) {
        return mapper.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method)
                .set("params", params);
    }

    /** 按 id 查找响应并忽略 sidecar 生命周期通知。 */
    private static ObjectNode response(List<ObjectNode> frames, String id) {
        return frames.stream().filter(frame -> id.equals(frame.path("id").textValue()))
                .findFirst().orElseThrow(() -> new AssertionError("response is missing: " + id));
    }

    /** 解包成功 JSON-RPC envelope，供测试断言公开结果。 */
    private static ObjectNode result(List<ObjectNode> frames, String id) {
        ObjectNode frame = response(frames, id);
        assertTrue(frame.path("result") instanceof ObjectNode,
                () -> "response does not contain an object result: " + frame);
        return (ObjectNode) frame.path("result");
    }

    /** 严格比较公开对象字段，防止旧 DTO 或持久化字段重新出现。 */
    private static void assertExactKeys(ObjectNode result, String... keys) {
        Set<String> expected = Set.of(keys);
        Set<String> actual = new java.util.HashSet<>();
        result.fieldNames().forEachRemaining(actual::add);
        assertEquals(expected, actual);
    }

    /** 只提取稳定机器错误码，不依赖脱敏消息文本。 */
    private static String errorCode(List<ObjectNode> frames, String id) {
        return response(frames, id).path("error").path("data").path("errorCode").textValue();
    }

    /**
     * 保存一轮真实 RPC 输出和跨域预热观测。
     *
     * @param frames 协议帧列表
     * @param prepareCalls 准备调用次数
     */
    private record RunResult(List<ObjectNode> frames,
                             int prepareCalls) {
        /** 复制帧集合，避免测试在服务关闭后修改观测结果。 */
        private RunResult {
            frames = List.copyOf(frames);
        }
    }

    /** 内存仓储模拟 SQLite 工作区事实，并同时承载本测试的 Thread 历史。 */
    private static final class WorkspaceHistory implements WorkspaceRepository, ThreadUseCase {
        private final Map<String, Workspace> byRoot = new LinkedHashMap<>();
        private final Map<String, Workspace> byId = new LinkedHashMap<>();
        private final Map<String, ThreadSummary> threads = new LinkedHashMap<>();
        private final Path forcedRoot;

        /** 创建正常根目录仓储。 */
        private WorkspaceHistory() {
            this(null);
        }

        /** 注入错误持久化根，仅用于验证 fail-closed identity check。 */
        private WorkspaceHistory(Path forcedRoot) {
            this.forcedRoot = forcedRoot;
        }

        /** 幂等注册并返回 revision 为零的权威事实。 */
        @Override
        public synchronized Workspace register(Workspace.Registration registration) {
            Workspace existing = byRoot.get(registration.root().toString());
            if (existing != null) {
                return existing;
            }
            Path persistedRoot = forcedRoot == null ? registration.root() : forcedRoot;
            Workspace value = new Workspace(registration.workspaceId(), persistedRoot,
                    registration.displayName(), registration.trust(), registration.kind(), 0);
            byRoot.put(persistedRoot.toString(), value);
            byId.put(value.workspaceId(), value);
            return value;
        }

        /** 服务端先筛 kind 再构造工作区页，测试不依赖前端本地丢弃混合类型。 */
        @Override
        public synchronized CursorPage<Workspace> list(String cursor, int limit, Workspace.Kind kind) {
            return new CursorPage<>(byRoot.values().stream()
                    .filter(workspace -> kind == null || workspace.kind() == kind)
                    .toList(), null);
        }

        /** 按身份读取权威事实。 */
        @Override
        public synchronized Optional<Workspace> findById(String workspaceId) {
            return Optional.ofNullable(byId.get(workspaceId));
        }

        /** 按规范根读取权威事实。 */
        @Override
        public synchronized Optional<Workspace> findByRoot(Path root) {
            return Optional.ofNullable(byRoot.get(root.toAbsolutePath().normalize().toString()));
        }

        /** 更新信任并推进 revision。 */
        @Override
        public synchronized Workspace updateTrust(String workspaceId, Workspace.Trust trust) {
            Workspace current = byId.get(workspaceId);
            Workspace updated = new Workspace(current.workspaceId(), current.root(),
                    current.displayName(), trust, current.kind(), current.revision() + 1);
            byId.put(workspaceId, updated);
            byRoot.put(current.root().toString(), updated);
            return updated;
        }

        /**
         * 模拟 Workspace 原地 CAS，root 唯一冲突拒绝重绑且项目历史身份保持不变。
         */
        @Override
        public synchronized Workspace updateProject(String workspaceId, long expectedRevision, Path root,
                                                    String displayName, boolean rootChanged, Instant occurredAt) {
            Workspace current = byId.get(workspaceId);
            if (current == null || current.revision() != expectedRevision) {
                throw new IllegalStateException("workspace revision conflict");
            }
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Workspace owner = byRoot.get(normalizedRoot.toString());
            if (rootChanged && owner != null && !owner.workspaceId().equals(workspaceId)) {
                throw new IllegalStateException("workspace root conflict");
            }
            if (rootChanged) {
                byRoot.remove(current.root().toString());
            }
            Workspace updated = new Workspace(current.workspaceId(),
                    rootChanged ? normalizedRoot : current.root(), displayName,
                    rootChanged ? Workspace.Trust.UNTRUSTED : current.trust(), current.kind(),
                    current.revision() + 1);
            byId.put(workspaceId, updated);
            byRoot.put(updated.root().toString(), updated);
            return updated;
        }

        /** revision 匹配时只删除元数据，不删除测试目录。 */
        @Override
        public synchronized void unregister(String workspaceId, long expectedRevision) {
            Workspace current = byId.get(workspaceId);
            if (current == null || current.revision() != expectedRevision) {
                throw new IllegalStateException("revision conflict");
            }
            byId.remove(workspaceId);
            byRoot.remove(current.root().toString());
        }

        /** 测试 Thread 只投影已注册的 workspace 身份与当前最小字段集合。 */
        @Override
        public synchronized ThreadSummary createThread(ThreadSummary.Creation request) {
            if (!byId.containsKey(request.workspaceId())) {
                throw new IllegalStateException("workspace is missing");
            }
            ThreadSummary value = new ThreadSummary(
                    request.threadId(), request.workspaceId(), request.title(),
                    byId.get(request.workspaceId()).kind().name().toLowerCase(java.util.Locale.ROOT),
                    request.preferences(),
                    ThreadSummary.Status.ACTIVE, false, null, true, 0,
                    request.occurredAt(), request.occurredAt());
            threads.put(value.threadId(), value);
            return value;
        }

        /** 返回当前内存顺序的 Thread 页。 */
        @Override
        public synchronized CursorPage<ThreadSummary> listThreads(String workspaceId, String cursor, int limit) {
            return new CursorPage<>(List.copyOf(threads.values()), null);
        }

        /** session 导航只返回拥有独立会话目录的主 Thread，不把项目 workspaceId 当作分组键。 */
        @Override
        public synchronized CursorPage<ThreadSummary> listSessionThreads(String cursor, int limit) {
            return new CursorPage<>(threads.values().stream()
                    .filter(thread -> "session".equals(thread.workspaceKind())).toList(), null);
        }

        /** 本用例搜索路径未启用，避免把列表夹具误当搜索语义。 */
        @Override
        public CursorPage<ThreadSummary> searchThreads(
                String workspaceId, String query, String cursor, int limit) {
            throw new UnsupportedOperationException();
        }

        /** session 搜索与 session 列表共用类型判定，避免标题相似的项目 Thread 混入无项目导航。 */
        @Override
        public synchronized CursorPage<ThreadSummary> searchSessionThreads(String query, String cursor, int limit) {
            return new CursorPage<>(threads.values().stream()
                    .filter(thread -> "session".equals(thread.workspaceKind()))
                    .filter(thread -> thread.title().contains(query)).toList(), null);
        }

        /** 本测试不构造 Thread 快照。 */
        @Override
        public Optional<ThreadSnapshot> readThread(String threadId, String cursor, int limit) {
            return Optional.empty();
        }

        /** 本测试未声明重命名能力。 */
        @Override public ThreadSummary renameThread(String threadId, String title, long revision) { throw new UnsupportedOperationException(); }
        /** 本测试未声明下一轮偏好修改能力。 */
        @Override public ThreadSummary updatePreferences(String threadId, io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences value, long revision) { throw new UnsupportedOperationException(); }
        /** 本测试未声明自动标题写入能力。 */
        @Override public boolean writeAutomaticTitle(String threadId, String title, long revision) { throw new UnsupportedOperationException(); }

        /** 无 Turn 的内存投影已经是已读；此幂等路径不应伪造新 revision。 */
        @Override
        public synchronized ThreadSummary markThreadSeen(String threadId, long expectedThreadRevision) {
            ThreadSummary current = threads.get(threadId);
            if (current == null) {
                throw new IllegalStateException("thread is missing");
            }
            return current;
        }

        /** 本测试不覆盖置顶排序事务。 */
        @Override
        public ThreadSummary pinThread(String threadId, boolean pinned, long expectedThreadRevision) {
            throw new UnsupportedOperationException();
        }

        /** 本测试未声明归档能力。 */
        @Override
        public ThreadSummary archiveThread(String threadId, long expectedThreadRevision) {
            throw new UnsupportedOperationException();
        }

        /** 工作区会话测试不覆盖归档恢复事务。 */
        @Override
        public ThreadSummary restoreThread(String threadId, long expectedThreadRevision) {
            throw new UnsupportedOperationException();
        }

        /** 本测试未声明删除能力。 */
        @Override
        public void deleteThread(String threadId, long expectedThreadRevision) {
            throw new UnsupportedOperationException();
        }

        /** 本测试未声明 Turn 查找能力。 */
        @Override
        public Optional<TurnSummary> findTurn(String turnId) {
            return Optional.empty();
        }

        /** 工作区注册流程不读取冻结文件差异，意外访问必须留在未配置状态。 */
        @Override
        public Optional<ChangeSetArtifactFile> readChangeSetArtifact(
                String threadId, String turnId, String artifactId, String filePath) {
            throw new UnsupportedOperationException();
        }

        /** 工作区会话测试不展开 item 正文，避免夹具隐式模拟正文仓储。 */
        @Override
        public Optional<ItemContentPage> readItemContent(
                String threadId, String itemId, int offsetCharacters, int limitCharacters) {
            throw new UnsupportedOperationException();
        }

        /** 工作区会话测试不物化 Tool 输出 artifact。 */
        @Override
        public Optional<TextArtifactPage> readToolArtifact(String threadId, String turnId, String callId,
                                                          String artifactId, int offsetCharacters,
                                                          int limitCharacters) {
            throw new UnsupportedOperationException();
        }

        /** 工作区会话测试不执行 Thread 发现查询。 */
        @Override
        public CursorPage<ThreadDiscovery> discoverThreads(ThreadDiscovery.Query query) {
            throw new UnsupportedOperationException();
        }

        /** 工作区会话测试不搜索已提交输入。 */
        @Override
        public CursorPage<UserInputHistory> searchUserInputs(String query, String cursor, int limit) {
            throw new UnsupportedOperationException();
        }

        /** 工作区会话测试不读取 Thread 最新页。 */
        @Override
        public Optional<ThreadSnapshot> readThreadLatest(String threadId, String beforeCursor, int limit) {
            throw new UnsupportedOperationException();
        }

        /** 工作区会话测试不读取用量投影。 */
        @Override
        public Optional<ThreadUsageSummary> readThreadUsageSummary(String threadId) {
            throw new UnsupportedOperationException();
        }

    }
}
