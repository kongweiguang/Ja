// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.bootstrap.SolonRuntimeComposition;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationWorkspaceTrustUseCase;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceTrustPort;
import io.github.kongweiguang.ja.workspace.application.service.WorkspaceService;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspacePolicy;
import io.github.kongweiguang.ja.workspace.infrastructure.filesystem.NioWorkspaceDirectoryAdapter;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SideChatPurgeMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskRecords;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** 真实 SQLite 验证 WorkspaceRepository CAS 与 WorkspaceService 持久化闭环。 */
final class MybatisWorkspaceRepositoryTest extends PersistenceTestSupport {
    /** 原行 CAS 迁移根目录且重置 trust，Thread 外键与会话历史仍由同一 Workspace identity 访问。 */
    @Test
    void rebindsProjectRootInPlaceAndPreservesConversationHistory() throws Exception {
        try (TestDatabase database = database("workspace-rebind")) {
            MybatisHistoryService history = database.history();
            Path oldRoot = temp.resolve("rebind-old").toAbsolutePath().normalize();
            Path newRoot = temp.resolve("rebind-new").toAbsolutePath().normalize();
            Workspace initial = history.register(new Workspace.Registration(
                    "ws_rebind_stable", oldRoot, "旧名称", Workspace.Trust.UNTRUSTED,
                    Workspace.Kind.PROJECT, START));
            history.createThread(new ThreadSummary.Creation("thr_rebind_history", initial.workspaceId(),
                    "保留会话", preferences("provider_rebind", "model_rebind"), START));
            Workspace trusted = history.updateTrust(initial.workspaceId(), Workspace.Trust.TRUSTED);

            Workspace moved = history.updateProject(initial.workspaceId(), trusted.revision(), newRoot,
                    "新名称", true, START.plusSeconds(2));

            assertEquals(initial.workspaceId(), moved.workspaceId());
            assertEquals(newRoot, moved.root());
            assertEquals("新名称", moved.displayName());
            assertEquals(Workspace.Trust.UNTRUSTED, moved.trust());
            assertEquals(trusted.revision() + 1, moved.revision());
            assertEquals(moved, history.findById(initial.workspaceId()).orElseThrow());
            ThreadSummary preserved = history.readThread("thr_rebind_history", null, 1)
                    .orElseThrow().thread();
            assertEquals(initial.workspaceId(), preserved.workspaceId());

            Path competingRoot = temp.resolve("rebind-competing").toAbsolutePath().normalize();
            Workspace other = history.register(new Workspace.Registration(
                    "ws_rebind_other", competingRoot, "另一个项目", Workspace.Trust.UNTRUSTED,
                    Workspace.Kind.PROJECT, START));
            assertThrows(io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure.class,
                    () -> history.updateProject(moved.workspaceId(), moved.revision(), other.root(),
                            moved.displayName(), true, START.plusSeconds(3)));
            assertEquals(moved, history.findById(moved.workspaceId()).orElseThrow());
        }
    }

    /** stale workspace/update 必须在 root/name 写入前拒绝 CAS，并保留真实 SQLite 行的全部投影。 */
    @Test
    void staleWorkspaceUpdateRevisionPreservesRootAndDisplayName() throws Exception {
        try (TestDatabase database = database("workspace-update-stale-revision")) {
            MybatisHistoryService history = database.history();
            Path originalRoot = temp.resolve("stale-original-root").toAbsolutePath().normalize();
            Path rejectedRoot = temp.resolve("stale-rejected-root").toAbsolutePath().normalize();
            Workspace initial = history.register(new Workspace.Registration(
                    "ws_stale_update", originalRoot, "当前项目名称", Workspace.Trust.UNTRUSTED,
                    Workspace.Kind.PROJECT, START));
            Workspace current = history.updateTrust(initial.workspaceId(), Workspace.Trust.TRUSTED);

            io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure failure = assertThrows(
                    io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure.class,
                    () -> history.updateProject(initial.workspaceId(), initial.revision(), rejectedRoot,
                            "过期请求名称", true, START.plusSeconds(1)));

            assertEquals(io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure.Code.UPDATE_CONFLICT,
                    failure.code());
            assertEquals(current, history.findById(initial.workspaceId()).orElseThrow());
            assertEquals(originalRoot, history.findByRoot(originalRoot).orElseThrow().root());
            assertTrue(history.findByRoot(rejectedRoot).isEmpty());
        }
    }

    /** 事务忙碌查询拦截活动 Turn，避免项目目录变化后恢复或继续执行落入另一物理根。 */
    @Test
    void rejectsProjectRootRebindWhileTurnIsRecoverable() throws Exception {
        try (TestDatabase database = database("workspace-rebind-busy")) {
            MybatisHistoryService history = database.history();
            Path oldRoot = temp.resolve("busy-old").toAbsolutePath().normalize();
            Path newRoot = temp.resolve("busy-new").toAbsolutePath().normalize();
            Workspace workspace = history.register(new Workspace.Registration(
                    "ws_rebind_busy", oldRoot, "活动项目", Workspace.Trust.UNTRUSTED,
                    Workspace.Kind.PROJECT, START));
            history.createThread(new ThreadSummary.Creation("thr_rebind_busy", workspace.workspaceId(),
                    "活动会话", preferences("provider_busy", "model_busy"), START));
            try (SqlSession session = database.sessions().openSession()) {
                try (java.sql.PreparedStatement insert = session.getConnection().prepareStatement(
                        "INSERT INTO ja_conversation_turn(turn_id,thread_id,state,requested_at_ms,updated_at_ms) "
                                + "VALUES(?,?,?,?,?)")) {
                    insert.setString(1, "turn_rebind_busy");
                    insert.setString(2, "thr_rebind_busy");
                    insert.setString(3, "RUNNING");
                    insert.setLong(4, START.toEpochMilli());
                    insert.setLong(5, START.toEpochMilli());
                    insert.executeUpdate();
                }
                session.commit();
            }

            io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure failure = assertThrows(
                    io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure.class,
                    () -> history.updateProject(workspace.workspaceId(), workspace.revision(), newRoot,
                            workspace.displayName(), true, START.plusSeconds(1)));

            assertEquals(io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure.Code.WORKSPACE_BUSY,
                    failure.code());
            assertEquals(workspace, history.findById(workspace.workspaceId()).orElseThrow());
        }
    }

    /** SQLite rebind busy 查询必须阻止 side Thread 等审批、等补充信息和暂停等待恢复的 Turn。 */
    @ParameterizedTest
    @EnumSource(TurnBusyState.class)
    void rejectsRootRebindForWaitingSideThreadTurns(TurnBusyState state) throws Exception {
        try (TestDatabase database = database("workspace-turn-busy-" + state.name().toLowerCase(java.util.Locale.ROOT))) {
            MybatisHistoryService history = database.history();
            WorkspaceThreads threads = createWorkspaceThreads(history, database, state.name().toLowerCase(java.util.Locale.ROOT));
            Workspace workspace = threads.workspace();
            executeSql(database,
                    "INSERT INTO ja_conversation_turn(turn_id,thread_id,state,requested_at_ms,updated_at_ms) "
                            + "VALUES(?,?,?,?,?)",
                    "turn_" + state.name().toLowerCase(java.util.Locale.ROOT), threads.sideThreadId(), state.name(),
                    START.toEpochMilli(), START.toEpochMilli());

            io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure failure = assertWorkspaceBusy(history,
                    workspace, temp.resolve("next-turn-root-" + state.name().toLowerCase(java.util.Locale.ROOT)),
                    START.plusSeconds(1));
            assertTrue(failure.getMessage().startsWith(
                    io.github.kongweiguang.ja.workspace.domain.model.WorkspaceBusyMessage.PREFIX));
            assertTrue(failure.getMessage().contains("会话“侧聊”"));
            String expectedStatus = switch (state) {
                case WAITING_APPROVAL -> "等待审批";
                case WAITING_INPUT -> "等待补充信息";
                case SUSPENDED -> "已暂停";
            };
            assertTrue(failure.getMessage().contains(expectedStatus));
        }
    }

    /** SQLite rebind busy 查询必须阻止 side Thread 上有活动 run 的执行、验收和暂停 Plan。 */
    @ParameterizedTest
    @CsvSource({"EXECUTING,RUNNING", "VERIFYING,VERIFYING", "PAUSED,PAUSED"})
    void rejectsRootRebindForActiveSideThreadPlans(String planState, String executionState) throws Exception {
        try (TestDatabase database = database("workspace-plan-busy-" + planState.toLowerCase(java.util.Locale.ROOT))) {
            MybatisHistoryService history = database.history();
            WorkspaceThreads threads = createWorkspaceThreads(history, database, planState.toLowerCase(java.util.Locale.ROOT));
            Workspace workspace = threads.workspace();
            String planId = "plan_busy_" + planState.toLowerCase(java.util.Locale.ROOT);
            String revisionId = "planrev_busy_" + planState.toLowerCase(java.util.Locale.ROOT);
            String runId = "run_busy_" + planState.toLowerCase(java.util.Locale.ROOT);
            String hash = "b".repeat(64);
            executeSql(database,
                    "INSERT INTO ja_plan(plan_id,owner_thread_id,objective,create_operation_key,status,revision,"
                            + "created_at_ms,updated_at_ms) VALUES(?,?,?,?,?,?,?,?)",
                    planId, threads.sideThreadId(), "可恢复项目任务", "plan:key:" + planState.toLowerCase(java.util.Locale.ROOT),
                    "DRAFT", 0, START.toEpochMilli(), START.toEpochMilli());
            executeSql(database,
                    "INSERT INTO ja_plan_revision(plan_revision_id,plan_id,revision_number,definition_json,plan_hash,"
                            + "created_by,created_at_ms) VALUES(?,?,?,?,?,?,?)",
                    revisionId, planId, 1, "{}", hash, "USER_UI", START.toEpochMilli());
            executeSql(database,
                    "INSERT INTO ja_plan_approval(approval_id,plan_id,plan_revision_id,plan_hash,actor,decision,created_at_ms) "
                            + "VALUES(?,?,?,?,?,?,?)",
                    "approval_" + planState.toLowerCase(java.util.Locale.ROOT), planId, revisionId, hash,
                    "USER_UI", "APPROVED", START.toEpochMilli());
            executeSql(database,
                    "INSERT INTO ja_plan_execution(run_id,plan_id,plan_revision_id,plan_hash,status,process_generation,"
                            + "started_at_ms,created_at_ms,updated_at_ms) VALUES(?,?,?,?,?,?,?,?,?)",
                    runId, planId, revisionId, hash, executionState, 1, START.toEpochMilli(),
                    START.toEpochMilli(), START.toEpochMilli());
            executeSql(database,
                    "UPDATE ja_plan SET status=?,revision=1,active_plan_revision_id=?,active_run_id=? WHERE plan_id=?",
                    planState, revisionId, runId, planId);

            io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure failure = assertWorkspaceBusy(history,
                    workspace, temp.resolve("next-plan-root-" + planState.toLowerCase(java.util.Locale.ROOT)),
                    START.plusSeconds(1));
            assertTrue(failure.getMessage().contains("会话“侧聊”"));
            String expectedStatus = switch (planState) {
                case "EXECUTING" -> "计划执行中";
                case "VERIFYING" -> "计划验收中";
                case "PAUSED" -> "计划已暂停";
                default -> throw new IllegalArgumentException("unexpected Plan status");
            };
            assertTrue(failure.getMessage().contains(expectedStatus));
        }
    }

    /** 名称更新不占用 root gate；成功后原活动 Turn 仍令真实目录重绑被拒绝。 */
    @Test
    void permitsNameOnlyUpdateWhileWorkspaceBusyAndKeepsRootRebindBlocked() throws Exception {
        try (TestDatabase database = database("workspace-name-update-while-busy")) {
            MybatisHistoryService history = database.history();
            WorkspaceThreads threads = createWorkspaceThreads(history, database, "name_busy");
            Workspace initial = threads.workspace();
            Workspace trusted = history.updateTrust(initial.workspaceId(), Workspace.Trust.TRUSTED);
            executeSql(database,
                    "INSERT INTO ja_conversation_turn(turn_id,thread_id,state,requested_at_ms,updated_at_ms) "
                            + "VALUES(?,?,?,?,?)",
                    "turn_name_busy", threads.primaryThreadId(), "RUNNING", START.toEpochMilli(), START.toEpochMilli());

            Workspace renamed = history.updateProject(initial.workspaceId(), trusted.revision(), initial.root(),
                    "忙碌时允许改名", false, START.plusSeconds(1));

            assertEquals(initial.workspaceId(), renamed.workspaceId());
            assertEquals(initial.root(), renamed.root());
            assertEquals("忙碌时允许改名", renamed.displayName());
            assertEquals(Workspace.Trust.TRUSTED, renamed.trust());
            assertEquals(trusted.revision() + 1, renamed.revision());
            io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure failure = assertWorkspaceBusy(
                    history, renamed, temp.resolve("name-busy-next-root"), START.plusSeconds(2));
            assertTrue(failure.getMessage().contains("会话“主线程”"));
            assertTrue(failure.getMessage().contains("运行中"));
        }
    }

    /** 同一 Workspace 下创建主 Thread 与标记为 side chat 的 Thread，供跨 Thread SQL 门禁复用。 */
    private WorkspaceThreads createWorkspaceThreads(MybatisHistoryService history, TestDatabase database,
                                                    String suffix) throws Exception {
        Path root = temp.resolve("workspace-root-" + suffix).toAbsolutePath().normalize();
        Workspace workspace = history.register(new Workspace.Registration(
                "ws_busy_" + suffix, root, "忙碌测试", Workspace.Trust.UNTRUSTED, Workspace.Kind.PROJECT, START));
        String primaryThreadId = "thr_busy_" + suffix + "_primary";
        String sideThreadId = "thr_busy_" + suffix + "_side";
        history.createThread(new ThreadSummary.Creation(primaryThreadId, workspace.workspaceId(),
                "主线程", preferences("provider_busy", "model_busy"), START));
        history.createThread(new ThreadSummary.Creation(sideThreadId, workspace.workspaceId(),
                "侧聊", preferences("provider_busy", "model_busy"), START));
        executeSql(database,
                "UPDATE ja_conversation_thread SET temporary_lifecycle_state='OPEN' WHERE thread_id=?", sideThreadId);
        return new WorkspaceThreads(workspace, primaryThreadId, sideThreadId);
    }

    private enum TurnBusyState {
        WAITING_APPROVAL,
        WAITING_INPUT,
        SUSPENDED
    }

    /** 保留两个 Workspace 内 Thread 身份，避免测试只验证单线程局部查询。 */
    private record WorkspaceThreads(Workspace workspace, String primaryThreadId, String sideThreadId) {
    }

    /** 同一 Workspace 的主 Thread 排队、侧聊输入队列和 paused Plan 都参与持久忙碌门禁。 */
    @Test
    void rebindBusyQueryCoversPrimarySideChatQueuesAndPausedPlan() throws Exception {
        try (TestDatabase database = database("workspace-rebind-lifecycle")) {
            MybatisHistoryService history = database.history();
            Path root = temp.resolve("lifecycle-old").toAbsolutePath().normalize();
            Path nextRoot = temp.resolve("lifecycle-new").toAbsolutePath().normalize();
            Workspace workspace = history.register(new Workspace.Registration(
                    "ws_lifecycle", root, "生命周期", Workspace.Trust.UNTRUSTED,
                    Workspace.Kind.PROJECT, START));
            history.createThread(new ThreadSummary.Creation("thr_lifecycle_primary", workspace.workspaceId(),
                    "主线程", preferences("provider_lifecycle", "model_lifecycle"), START));
            history.createThread(new ThreadSummary.Creation("thr_lifecycle_side", workspace.workspaceId(),
                    "侧聊", preferences("provider_lifecycle", "model_lifecycle"), START));

            executeSql(database,
                    "INSERT INTO ja_conversation_turn(turn_id,thread_id,state,requested_at_ms,updated_at_ms) "
                            + "VALUES(?,?,?,?,?)",
                    "turn_lifecycle_primary", "thr_lifecycle_primary", "QUEUED",
                    START.toEpochMilli(), START.toEpochMilli());
            io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure queuedTurnBusy = assertWorkspaceBusy(
                    history, workspace, nextRoot, START.plusSeconds(1));
            assertTrue(queuedTurnBusy.getMessage().contains("排队中"));

            executeSql(database, "DELETE FROM ja_conversation_turn WHERE turn_id=?", "turn_lifecycle_primary");
            executeSql(database,
                    "UPDATE ja_conversation_thread SET temporary_lifecycle_state='OPEN' WHERE thread_id=?",
                    "thr_lifecycle_side");
            executeSql(database,
                    "INSERT INTO ja_conversation_turn(turn_id,thread_id,state,requested_at_ms,updated_at_ms,"
                            + "completed_at_ms,terminal_summary) VALUES(?,?,?,?,?,?,?)",
                    "turn_lifecycle_side", "thr_lifecycle_side", "COMPLETED",
                    START.toEpochMilli(), START.toEpochMilli(), START.toEpochMilli(), "done");
            executeSql(database,
                    "INSERT INTO ja_turn_input(input_id,thread_id,turn_id,kind,content_json,state,created_at_ms,updated_at_ms) "
                            + "VALUES(?,?,?,?,?,?,?,?)",
                    "input_lifecycle_side", "thr_lifecycle_side", "turn_lifecycle_side", "FOLLOW_UP", "[]",
                    "PENDING", START.toEpochMilli(), START.toEpochMilli());
            io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure queuedInputBusy = assertWorkspaceBusy(
                    history, workspace, nextRoot, START.plusSeconds(2));
            assertTrue(queuedInputBusy.getMessage().contains("待处理输入"));

            executeSql(database, "DELETE FROM ja_turn_input WHERE input_id=?", "input_lifecycle_side");
            executeSql(database, "DELETE FROM ja_conversation_turn WHERE turn_id=?", "turn_lifecycle_side");
            executeSql(database,
                    "INSERT INTO ja_plan(plan_id,owner_thread_id,objective,create_operation_key,status,revision,"
                            + "created_at_ms,updated_at_ms) VALUES(?,?,?,?,?,?,?,?)",
                    "plan_awaiting", "thr_lifecycle_primary", "等待用户确认", "plan:awaiting",
                    "AWAITING_APPROVAL", 1, START.toEpochMilli(), START.toEpochMilli());
            Workspace moved = history.updateProject(workspace.workspaceId(), workspace.revision(), nextRoot,
                    workspace.displayName(), true, START.plusSeconds(3));
            assertEquals(nextRoot, moved.root());
            executeSql(database, "DELETE FROM ja_plan WHERE plan_id=?", "plan_awaiting");

            String hash = "0".repeat(64);
            try (SqlSession session = database.sessions().openSession()) {
                executeSql(session,
                        "INSERT INTO ja_plan(plan_id,owner_thread_id,objective,create_operation_key,status,revision,"
                                + "created_at_ms,updated_at_ms) VALUES(?,?,?,?,?,?,?,?)",
                        "plan_approved", "thr_lifecycle_primary", "已批准待启动", "plan:approved", "DRAFT", 1,
                        START.toEpochMilli(), START.toEpochMilli());
                executeSql(session,
                        "INSERT INTO ja_plan_revision(plan_revision_id,plan_id,revision_number,definition_json,plan_hash,"
                                + "created_by,created_at_ms) VALUES(?,?,?,?,?,?,?)",
                        "planrev_lifecycle_approved", "plan_approved", 1, "{}", hash, "USER_UI",
                        START.toEpochMilli());
                executeSql(session,
                        "UPDATE ja_plan SET status='APPROVED',revision=2,active_plan_revision_id=? WHERE plan_id=?",
                        "planrev_lifecycle_approved", "plan_approved");
                session.commit();
            }
            Path approvedRoot = temp.resolve("lifecycle-approved-root").toAbsolutePath().normalize();
            Workspace afterApprovedPlan = history.updateProject(moved.workspaceId(), moved.revision(), approvedRoot,
                    moved.displayName(), true, START.plusSeconds(4));

            try (SqlSession session = database.sessions().openSession()) {
                executeSql(session,
                        "INSERT INTO ja_plan(plan_id,owner_thread_id,objective,create_operation_key,status,revision,"
                                + "created_at_ms,updated_at_ms) VALUES(?,?,?,?,?,?,?,?)",
                        "plan_paused", "thr_lifecycle_side", "可恢复执行", "plan:paused", "DRAFT", 1,
                        START.toEpochMilli(), START.toEpochMilli());
                executeSql(session,
                        "INSERT INTO ja_plan_revision(plan_revision_id,plan_id,revision_number,definition_json,plan_hash,"
                                + "created_by,created_at_ms) VALUES(?,?,?,?,?,?,?)",
                        "planrev_lifecycle_paused", "plan_paused", 1, "{}", hash, "USER_UI", START.toEpochMilli());
                executeSql(session,
                        "INSERT INTO ja_plan_execution(run_id,plan_id,plan_revision_id,plan_hash,status,"
                                + "process_generation,created_at_ms,updated_at_ms) VALUES(?,?,?,?,?,?,?,?)",
                        "run_lifecycle_paused", "plan_paused", "planrev_lifecycle_paused", hash, "PAUSED", 1,
                        START.toEpochMilli(), START.toEpochMilli());
                executeSql(session,
                        "UPDATE ja_plan SET status='PAUSED',revision=3,active_plan_revision_id=?,active_run_id=? "
                                + "WHERE plan_id=?",
                        "planrev_lifecycle_paused", "run_lifecycle_paused", "plan_paused");
                session.commit();
            }

            io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure pausedPlanBusy = assertWorkspaceBusy(
                    history, afterApprovedPlan,
                    temp.resolve("lifecycle-paused-root").toAbsolutePath().normalize(), START.plusSeconds(5));
            assertTrue(pausedPlanBusy.getMessage().contains("计划已暂停"));
        }
    }

    /** 将测试 SQL 写入独立 SQLite 事务，覆盖生产查询使用的最小可持久状态。 */
    private static void executeSql(TestDatabase database, String sql, Object... values) throws Exception {
        try (SqlSession session = database.sessions().openSession()) {
            executeSql(session, sql, values);
            session.commit();
        }
    }

    /** 在调用方事务中执行语句，使互相引用的 Plan/Revision/Run 可一次提交。 */
    private static void executeSql(SqlSession session, String sql, Object... values) throws Exception {
        try (java.sql.PreparedStatement statement = session.getConnection().prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    /** 统一断言根变更命中领域 busy 状态且 revision/root 均未被部分写入。 */
    private static io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure assertWorkspaceBusy(
            MybatisHistoryService history, Workspace workspace, Path newRoot, java.time.Instant at) {
        io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure failure = assertThrows(
                io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure.class,
                () -> history.updateProject(workspace.workspaceId(), workspace.revision(), newRoot,
                        workspace.displayName(), true, at));
        assertEquals(io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure.Code.WORKSPACE_BUSY,
                failure.code());
        assertEquals(workspace, history.findById(workspace.workspaceId()).orElseThrow());
        return failure;
    }

    /**
     * 注册、查询、信任 CAS 与关闭回执清理共享 Workspace 注销事务；过期 CAS 必须保留完整回执。
     */
    @Test
    void persistsAndRemovesWorkspaceThroughOutboundRepository() throws Exception {
        try (TestDatabase database = database("workspace-repository")) {
            WorkspaceRepository repository = database.history();
            Path root = temp.resolve("project").toAbsolutePath().normalize();
            Workspace.Registration registration = new Workspace.Registration("ws_project", root, "项目", Workspace.Trust.UNTRUSTED, Workspace.Kind.PROJECT, START);

            Workspace registered = repository.register(registration);

            assertEquals(0, registered.revision());
            assertEquals(registered, repository.findById("ws_project").orElseThrow());
            assertEquals(registered, repository.findByRoot(root).orElseThrow());
            assertEquals(1, repository.list(null, 10, null).items().size());
            persistCloseReceipt(database, "thr_closed_side_chat", "thr_route_root", "ws_project");

            Workspace trusted = repository.updateTrust("ws_project", Workspace.Trust.TRUSTED);
            assertEquals(1, trusted.revision());
            assertEquals(Workspace.Trust.TRUSTED, trusted.trust());
            StorageException stale = assertThrows(StorageException.class,
                    () -> repository.unregister("ws_project", 0));
            assertEquals(StorageException.Code.CAS_CONFLICT, stale.code());
            try (SqlSession session = database.sessions().openSession()) {
                SideChatPurgeMapper mapper = PersistenceMappers.open(session).sideChatPurges();
                assertEquals("thr_route_root", mapper.selectCloseReceipt("thr_closed_side_chat").ownerRootThreadId());
                assertEquals(1, mapper.selectCloseReceiptMembers("thr_closed_side_chat").size());
            }

            repository.unregister("ws_project", trusted.revision());
            assertFalse(repository.findById("ws_project").isPresent());
            try (SqlSession session = database.sessions().openSession()) {
                SideChatPurgeMapper mapper = PersistenceMappers.open(session).sideChatPurges();
                assertNull(mapper.selectCloseReceipt("thr_closed_side_chat"));
                assertTrue(mapper.selectCloseReceiptMembers("thr_closed_side_chat").isEmpty());
            }
        }
    }

    /**
     * 通过生产 Mapper 持久化一个已删除 target 的最小回执，验证 workspace owner 清理不依赖其路由 Thread。
     */
    private static void persistCloseReceipt(TestDatabase database, String closedThreadId,
                                            String ownerRootThreadId, String workspaceId) {
        try (SqlSession session = database.sessions().openSession()) {
            SideChatPurgeMapper mapper = PersistenceMappers.open(session).sideChatPurges();
            assertEquals(1, mapper.insertCloseReceipt(new TaskRecords.SideChatCloseReceiptInsert(
                    closedThreadId, workspaceId, ownerRootThreadId, 1, START.toEpochMilli())));
            assertEquals(1, mapper.insertCloseReceiptMembers(List.of(
                    new TaskRecords.SideChatCloseMemberInsert(closedThreadId, 0, closedThreadId))));
            session.commit();
        }
    }

    /** Thread 创建后设置项目为 trusted，验证真实 MyBatis CAS、workspace/list 回读和组合根 trust 映射。 */
    @Test
    void persistsServiceTrustAfterThreadCreationThroughRealSqlite() throws Exception {
        try (TestDatabase database = database("workspace-service-trust")) {
            MybatisHistoryService history = database.history();
            Path project = Files.createDirectory(temp.resolve("trusted-project")).toRealPath();
            List<String> synchronizedTrust = new ArrayList<>();
            ConfigurationWorkspaceTrustUseCase configurationTrust = (root, trusted) ->
                    synchronizedTrust.add(root + ":" + trusted);
            WorkspaceTrustPort trustPort = new SolonRuntimeComposition()
                    .workspaceTrustPort(configurationTrust);
            WorkspaceService service = new WorkspaceService(
                    history,
                    new NioWorkspaceDirectoryAdapter(temp.resolve("ja-home")),
                    ignored -> { },
                    trustPort,
                    new WorkspacePolicy(),
                    CLOCK);

            Workspace opened = service.openWorkspace(new WorkspaceUseCase.OpenWorkspace(project, "项目"));
            assertEquals(Workspace.Trust.UNTRUSTED,
                    history.findById(opened.workspaceId()).orElseThrow().trust());
            history.createThread(new ThreadSummary.Creation(
                    "thr_workspace_trust", opened.workspaceId(), "新会话",
                    preferences("provider_workspace", "model_workspace"), START));

            Workspace trusted = service.setWorkspaceTrust(opened.workspaceId(), Workspace.Trust.TRUSTED);

            assertEquals(Workspace.Trust.TRUSTED, trusted.trust());
            assertEquals(trusted, history.findById(opened.workspaceId()).orElseThrow());
            assertEquals(Workspace.Trust.TRUSTED,
                    service.listWorkspaces(null, 10, Workspace.Kind.PROJECT).items().getFirst().trust());
            assertEquals(List.of(project + ":true"), synchronizedTrust);
        }
    }

    /** 同一身份重试不推进 revision，同一物理根也不允许绑定第二个身份。 */
    @Test
    void keepsRegistrationIdempotentAndRejectsRootIdentityConflict() throws Exception {
        try (TestDatabase database = database("workspace-identity")) {
            WorkspaceRepository repository = database.history();
            Path root = temp.resolve("same-project").toAbsolutePath().normalize();
            Workspace.Registration registration = new Workspace.Registration("ws_stable", root, "首次名称", Workspace.Trust.UNTRUSTED, Workspace.Kind.PROJECT, START);
            Workspace first = repository.register(registration);

            Workspace retry = repository.register(new Workspace.Registration("ws_stable", root, "重试名称", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, START.plusSeconds(1)));

            assertEquals(first, retry);
            assertEquals(0, retry.revision());
            StorageException conflict = assertThrows(StorageException.class,
                    () -> repository.register(new Workspace.Registration("ws_conflict", root, "冲突", Workspace.Trust.UNTRUSTED, Workspace.Kind.PROJECT, START.plusSeconds(2))));
            assertEquals(StorageException.Code.STORAGE_CONFLICT, conflict.code());
            assertTrue(repository.findById("ws_conflict").isEmpty());
            assertEquals(first, repository.findByRoot(root).orElseThrow());
        }
    }

    /**
     * 自动标题以 placeholder 作为真正所有权 CAS：普通偏好/Turn revision 推进不能饿死首次标题，
     * 而人工标题无论先提交还是在自动标题之后刷新 revision，最终都保持永久优先。
     */
    @Test
    void automaticTitleSurvivesRevisionProgressWhileManualTitleKeepsOwnership() throws Exception {
        try (TestDatabase database = database("automatic-title-ownership")) {
            MybatisHistoryService history = database.history();
            Path root = temp.resolve("automatic-title-project").toAbsolutePath().normalize();
            history.register(new Workspace.Registration("ws_title", root, "标题竞争", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, START));
            ThreadPreferences initial = preferences("provider_title", "model_title");
            history.createThread(new ThreadSummary.Creation(
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

            history.createThread(new ThreadSummary.Creation(
                    "thr_manual_first", "ws_title", "新会话", initial, START));
            ThreadSummary manualFirst = history.renameThread("thr_manual_first", "先人工", 0);
            assertFalse(history.writeAutomaticTitle("thr_manual_first", "后自动", 0));
            assertEquals("先人工", manualFirst.title());
        }
    }

}
