// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepositoryException;
import io.github.kongweiguang.ja.bootstrap.App;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.infrastructure.persistence.database.DatabaseLeaseProbe;
import io.github.kongweiguang.ja.infrastructure.persistence.database.JaDatabase;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.infrastructure.persistence.support.EmbeddedPersistenceProbe;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceRepository;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.solon.MybatisAdapter;
import org.apache.ibatis.solon.integration.MybatisAdapterManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.Solon;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.execution;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Embedded Solon 验证新 AgentStore、Ja 同事务投影与数据库生命周期组合。 */
public final class EmbeddedSolonPersistenceTest {
    @TempDir
    Path temporary;

    /** 在独立 JDK 25 进程验证 Solon 持久化；MyBatis-Solon 的命名工厂是进程级注册表。 */
    @Test
    void injectedAgentStoreRollsBackProjectionFailure() throws Exception {
        assertEquals(25, Runtime.version().feature(), "embedded Solon persistence requires JDK 25");
        Path output = temporary.resolve("solon-persistence-child.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-Dja.log-dir=" + temporary.resolve("logs"),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                EmbeddedSolonPersistenceTest.class.getName(), temporary.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(45, TimeUnit.SECONDS), "isolated Solon persistence process did not stop");
            assertEquals(0, child.exitValue(), () -> {
                try {
                    String encoding = System.getProperty("stdout.encoding", Charset.defaultCharset().name());
                    return Files.readString(output, Charset.forName(encoding));
                } catch (java.io.IOException failure) {
                    return failure.getClass().getSimpleName();
                }
            });
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    /** 子进程入口保留全部真实 Solon、SQLite、参与者回滚断言，并拒绝非隔离参数调用。 */
    public static void main(String[] arguments) throws Exception {
        if (Runtime.version().feature() != 25 || arguments.length != 1) {
            throw new IllegalArgumentException("isolated persistence probe requires JDK 25 and one temp directory");
        }
        EmbeddedSolonPersistenceTest fixture = new EmbeddedSolonPersistenceTest();
        fixture.temporary = Path.of(arguments[0]);
        fixture.runProjectionRollbackScenario();
    }

    /**
     * 用 Solon 和真实 SQLite 验证业务异常身份、Error 回滚及参与者失败边界。
     *
     * @author kongweiguang
     */
    private void runProjectionRollbackScenario() throws Exception {
        Path data = Files.createDirectories(temporary.resolve("data"));
        Path run = Files.createDirectories(temporary.resolve("run"));
        String previousData = System.getProperty("ja.data-dir");
        String previousRun = System.getProperty("ja.run-dir");
        try {
            System.setProperty("ja.data-dir", data.toString());
            System.setProperty("ja.run-dir", run.toString());
            Solon.start(App.class, new String[0], started -> started.enableHttp(false));

            AgentStore store = Solon.context().getBean("jaAgentStore");
            AgentStore rawStore = Solon.context().getBean("jaRawAgentStore");
            WorkspaceRepository workspaces = Solon.context().getBean(WorkspaceRepository.class);
            ThreadUseCase history = Solon.context().getBean(ThreadUseCase.class);
            ObjectMapper mapper = Solon.context().getBean(ObjectMapper.class);
            assertNotSame(rawStore, store, "test must exercise the mandatory Ja projection decorator");
            EmbeddedPersistenceProbe probe = new EmbeddedPersistenceProbe();
            Solon.context().beanInject(probe);
            JaDatabase database = Solon.context().getBean(JaDatabase.class);
            assertEquals(data.resolve("ja.db").toAbsolutePath().normalize(), database.databasePath(),
                    "Solon and AgentStore must use the isolated database file");
            probe.requireWalCheckpointBinding(database);

            Instant now = Instant.parse("2026-08-25T12:00:00Z");
            workspaces.register(new Workspace.Registration("ws_embedded", data, "embedded", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, now));
            assertWorkspaceInAgentDatabase(database, "ws_embedded");
            ThreadPreferences threadPreferences = preferences("provider_embedded", "model_embedded");
            history.createThread(new ThreadSummary.Creation("thr_embedded", "ws_embedded", "embedded",
                    threadPreferences, now));
            verifyWorkspaceFailureClassification(workspaces, database, data, now);
            verifyConcurrentWorkspaceUpdateCas(workspaces, history, now);
            verifyTaskRepositoryConflictIdentityAndRollback(database);
            verifySqlFailureClassificationAndRollback(database);
            verifyCheckedFailureClassificationAndRollback(database);
            verifyErrorIdentityAndRollback(database);

            AgentIdentity identity = new AgentIdentity("ja.local", "thr_embedded", "turn_embedded", "user_local");
            AgentExecutionRequest request = new AgentExecutionRequest(identity,
                    AgentExecutionRequest.AdmissionKind.USER_INPUT,
                    Optional.of(new UserContent(List.of(new TextContent("hello")))), "item_embedded_user",
                    Optional.empty(), new AgentStore.OperationIdentity("operation_embedded", "turn/start", "a".repeat(64), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")), Map.of(),
                    AgentExecutionRequest.HistorySelection.currentConversation(), now, List.of(),
                    Optional.empty(), Optional.empty());
            AdmissionFacts facts = new AdmissionFacts(identity, "ws_embedded", TurnOrigin.USER, "turn/start", now,
                    null, null, null, null, null, null, null, 0, Optional.empty());

            try (AgentStoreProjectionFixture fixture = AgentStoreProjectionFixture.open(store, mapper, request,
                    facts, threadPreferences, 0)) {
                TurnExecutionState.Ready initial = execution();
                AgentStore.CommitReceipt running = fixture.commitRecords(TurnState.RUNNING, initial,
                        List.of(), List.of(), now.plusSeconds(1));
                assertEquals(2, running.threadRevision());
                assertEquals(2, running.mutationVersion());

                TurnExecutionState.Ready completed = new TurnExecutionState.Ready(
                        initial.common(), TurnExecutionState.Next.COMPLETION, initial.summary());
                String finalRecordId = fixture.recordId("agent.assistant", "item_final");
                String usageRecordId = fixture.recordId("agent.usage", "modelreq_invalid_profile");
                ExecutionFacts.AssistantMessage finalMessage = new ExecutionFacts.AssistantMessage("item_final",
                        new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("done"))), "done", null, 1);
                ExecutionFacts.ModelRequestIdentity incompleteProfile = new ExecutionFacts.ModelRequestIdentity(
                        "provider_embedded", "model_embedded", ModelPort.Api.OPENAI_RESPONSES.name(), "test-model",
                        "medium", "medium", 100_000, 8_192, JsonObjects.builder().build());
                ExecutionFacts.Usage invalidUsage = new ExecutionFacts.Usage("modelreq_invalid_profile",
                        new ModelUsage(3, 2, 5), 1, 1, ExecutionFacts.UsagePurpose.ASSISTANT,
                        ExecutionFacts.UsageCertainty.KNOWN, incompleteProfile, null);
                List<AgentStore.RecordWrite> completionFacts = List.of(
                        fixture.record("agent.assistant", "item_final", finalMessage, now.plusSeconds(2)),
                        fixture.record("agent.usage", "modelreq_invalid_profile", invalidUsage, now.plusSeconds(2)),
                        fixture.knownEmptyChangeSet(now.plusSeconds(2)));
                List<ExecutionEvent> completionEvents = List.of(
                        new ExecutionEvent.ModelCompleted(finalRecordId, 1, List.of(), usageRecordId),
                        new ExecutionEvent.Terminal(TurnState.COMPLETED, "done", null, null,
                                finalRecordId, usageRecordId));

                AgentStore.StoreParticipantException participantFailure = assertThrows(
                        AgentStore.StoreParticipantException.class, () -> fixture.commitRecords(
                                TurnState.COMPLETED, completed, completionFacts, completionEvents,
                                now.plusSeconds(2)));
                assertInstanceOf(SQLException.class, participantFailure.getCause());

                assertEquals(TurnState.RUNNING.name(), store.loadExecution(fixture.execution()).orElseThrow().state());
                ThreadSnapshot projected = history.readThread("thr_embedded", null, 20).orElseThrow();
                assertEquals(1, projected.items().size());
                assertEquals("running", projected.turns().getFirst().status());
                assertEquals(0, probe.countUsage("turn_embedded"));
            }
        } finally {
            if (Solon.context() != null) {
                Solon.stopBlock(false, 0);
            }
            restore("ja.data-dir", previousData);
            restore("ja.run-dir", previousRun);
        }
        try (AutoCloseable released = DatabaseLeaseProbe.acquire(data.resolve("ja.db"))) {
            assertNotNull(released);
        }
    }

    /**
     * 真实 Solon 事务 owner 必须将 busy、stale CAS、缺失身份与错误 Workspace kind 保留为业务失败，
     * 并在 busy/stale 分支确认权威根与 revision 均未变化。
     *
     * @author kongweiguang
     */
    private void verifyWorkspaceFailureClassification(WorkspaceRepository workspaces,
                                                      JaDatabase database,
                                                      Path dataRoot,
                                                      Instant now) throws Exception {
        Workspace original = workspaces.findById("ws_embedded").orElseThrow();
        Path nextRoot = Files.createDirectories(temporary.resolve("workspace-rebind-next"))
                .toAbsolutePath().normalize();
        try (Connection connection = database.dataSource().getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO ja_conversation_turn(turn_id,thread_id,state,requested_at_ms,updated_at_ms) "
                             + "VALUES(?,?,?,?,?)")) {
            insert.setString(1, "turn_workspace_busy_transaction");
            insert.setString(2, "thr_embedded");
            insert.setString(3, "RUNNING");
            insert.setLong(4, now.toEpochMilli());
            insert.setLong(5, now.toEpochMilli());
            assertEquals(1, insert.executeUpdate());
        }

        WorkspaceFailure busy = assertThrows(WorkspaceFailure.class,
                () -> workspaces.updateProject(original.workspaceId(), original.revision(), nextRoot,
                        original.displayName(), true, now.plusSeconds(1)));
        assertEquals(WorkspaceFailure.Code.WORKSPACE_BUSY, busy.code());
        assertTrue(busy.getMessage().contains("会话“embedded”：运行中"));
        assertEquals(original, workspaces.findById(original.workspaceId()).orElseThrow());

        WorkspaceFailure stale = assertThrows(WorkspaceFailure.class,
                () -> workspaces.updateProject(original.workspaceId(), original.revision() + 1, nextRoot,
                        original.displayName(), true, now.plusSeconds(2)));
        assertEquals(WorkspaceFailure.Code.UPDATE_CONFLICT, stale.code());
        assertEquals(original, workspaces.findById(original.workspaceId()).orElseThrow());

        WorkspaceFailure missing = assertThrows(WorkspaceFailure.class,
                () -> workspaces.updateProject("ws_missing_embedded", 0, nextRoot,
                        "missing", true, now.plusSeconds(3)));
        assertEquals(WorkspaceFailure.Code.DIRECTORY_UNAVAILABLE, missing.code());

        Path sessionRoot = Files.createDirectories(temporary.resolve("workspace-session-root"));
        Workspace session = workspaces.register(new Workspace.Registration("ws_embedded_session",
                sessionRoot, "session", Workspace.Trust.UNTRUSTED, Workspace.Kind.SESSION, now));
        WorkspaceFailure wrongKind = assertThrows(WorkspaceFailure.class,
                () -> workspaces.updateProject(session.workspaceId(), session.revision(), nextRoot,
                        session.displayName(), true, now.plusSeconds(4)));
        assertEquals(WorkspaceFailure.Code.IDENTITY_CONFLICT, wrongKind.code());

        try (Connection connection = database.dataSource().getConnection();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM ja_conversation_turn WHERE turn_id=?")) {
            delete.setString(1, "turn_workspace_busy_transaction");
            assertEquals(1, delete.executeUpdate());
        }
        assertEquals(dataRoot.toAbsolutePath().normalize(), original.root());
    }

    /**
     * 在真实 Solon/SQLite owner 下并发提交相同 revision，验证只有一份名称与目录组合原子生效，
     * 且失败方不会覆盖信任状态或 Thread 的 Workspace 归属。
     *
     * @author kongweiguang
     */
    private void verifyConcurrentWorkspaceUpdateCas(WorkspaceRepository workspaces,
                                                   ThreadUseCase history,
                                                   Instant now) throws Exception {
        String workspaceId = "ws_embedded_concurrent_cas";
        Path originalRoot = Files.createDirectories(temporary.resolve("workspace-cas-original"))
                .toAbsolutePath().normalize();
        Path firstRoot = Files.createDirectories(temporary.resolve("workspace-cas-first"))
                .toAbsolutePath().normalize();
        Path secondRoot = Files.createDirectories(temporary.resolve("workspace-cas-second"))
                .toAbsolutePath().normalize();
        Workspace original = workspaces.register(new Workspace.Registration(workspaceId, originalRoot,
                "并发原名称", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, now));
        String threadId = "thr_embedded_concurrent_cas";
        history.createThread(new ThreadSummary.Creation(threadId, workspaceId, "并发重绑历史",
                preferences("provider_embedded_cas", "model_embedded_cas"), now));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<WorkspaceUpdateAttempt> first = executor.submit(() -> attemptWorkspaceUpdate(
                    workspaces, original, firstRoot, "并发名称甲", now.plusSeconds(10), ready, start));
            Future<WorkspaceUpdateAttempt> second = executor.submit(() -> attemptWorkspaceUpdate(
                    workspaces, original, secondRoot, "并发名称乙", now.plusSeconds(11), ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "both update contenders must reach the start gate");
            start.countDown();

            WorkspaceUpdateAttempt firstAttempt = first.get(20, TimeUnit.SECONDS);
            WorkspaceUpdateAttempt secondAttempt = second.get(20, TimeUnit.SECONDS);
            assertNull(firstAttempt.unexpectedFailure(), "first contender must not leak a storage/SQL failure");
            assertNull(secondAttempt.unexpectedFailure(), "second contender must not leak a storage/SQL failure");
            assertTrue((firstAttempt.workspace() == null) != (secondAttempt.workspace() == null),
                    "exactly one same-revision update must commit");

            WorkspaceUpdateAttempt committed = firstAttempt.workspace() != null ? firstAttempt : secondAttempt;
            WorkspaceUpdateAttempt rejected = firstAttempt.workspace() == null ? firstAttempt : secondAttempt;
            assertEquals(WorkspaceFailure.Code.UPDATE_CONFLICT, rejected.failure().code());

            Workspace persisted = workspaces.findById(workspaceId).orElseThrow();
            assertEquals(original.revision() + 1, persisted.revision());
            assertEquals(Workspace.Trust.UNTRUSTED, persisted.trust(),
                    "the winning root change resets trust once; the loser must not change it again");
            assertEquals(committed.workspace(), persisted);
            assertEquals(committed.workspace().root(), persisted.root());
            assertEquals(committed.workspace().displayName(), persisted.displayName());
            assertTrue(persisted.root().equals(firstRoot) || persisted.root().equals(secondRoot));
            String expectedDisplayName = persisted.root().equals(firstRoot) ? "并发名称甲" : "并发名称乙";
            assertEquals(expectedDisplayName, committed.workspace().displayName(),
                    "the committed projection must keep the name paired with its requested root");
            assertEquals(workspaceId, persisted.workspaceId());
            assertEquals(Workspace.Kind.PROJECT, persisted.kind());
            assertEquals(expectedDisplayName, persisted.displayName(),
                    "the persisted root/name pair must match one complete contender, never a mixed update");
            assertTrue(workspaces.findByRoot(persisted.root()).isPresent());
            assertTrue(workspaces.findByRoot(persisted.root().equals(firstRoot) ? secondRoot : firstRoot).isEmpty(),
                    "the losing directory must remain unbound");
            assertTrue(workspaces.findByRoot(originalRoot).isEmpty(), "the original directory must be released");

            ThreadSnapshot retained = history.readThread(threadId, null, 20).orElseThrow();
            assertEquals(workspaceId, retained.thread().workspaceId());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS),
                    "workspace update contenders must terminate with the isolated Solon process");
        }
    }

    /**
     * 两个调用者在不使用 sleep 的闸门后同时进入真实 Workspace repository；非业务冲突故障原样记录供断言定位。
     *
     * @author kongweiguang
     */
    private static WorkspaceUpdateAttempt attemptWorkspaceUpdate(WorkspaceRepository workspaces,
                                                                 Workspace original,
                                                                 Path root,
                                                                 String displayName,
                                                                 Instant now,
                                                                 CountDownLatch ready,
                                                                 CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("workspace update start gate timed out");
        }
        try {
            return new WorkspaceUpdateAttempt(workspaces.updateProject(original.workspaceId(), original.revision(),
                    root, displayName, true, now), null, null);
        } catch (WorkspaceFailure failure) {
            return new WorkspaceUpdateAttempt(null, failure, null);
        } catch (RuntimeException failure) {
            return new WorkspaceUpdateAttempt(null, null, failure);
        }
    }

    /** 同时保留提交、预期业务拒绝与意外持久化失败，避免测试把锁错误误判为 CAS 冲突。 */
    private record WorkspaceUpdateAttempt(Workspace workspace,
                                          WorkspaceFailure failure,
                                          RuntimeException unexpectedFailure) {
    }

    /**
     * 真实 Solon owner 回滚业务 CAS 冲突后仍须保留异常实例和稳定 code，供 TaskCoordinator 分类重试。
     */
    private static void verifyTaskRepositoryConflictIdentityAndRollback(JaDatabase database) throws SQLException {
        MybatisAdapter adapter = MybatisAdapterManager.getOnly("ja");
        assertNotNull(adapter, "isolated Ja MyBatis adapter must be registered");
        MybatisUnitOfWork transactions = new MybatisUnitOfWork(adapter.getFactory());
        TaskRepositoryException expected = new TaskRepositoryException(
                TaskRepositoryException.Code.CAS_CONFLICT, "task revision changed during persistence");
        Throwable actual = assertThrows(Throwable.class, () -> transactions.required(mappers -> {
            int updated = mappers.history().compareAndSetThreadPin(new PersistenceRecords.ThreadPinCas(
                    "thr_embedded", "2026-08-25T12:00:02Z", 0, "2026-08-25T12:00:02Z"));
            assertEquals(1, updated, "probe write must be part of the transaction under test");
            throw expected;
        }));

        assertThreadPinRolledBack(database, "business conflict must roll back its preceding mapper write");
        TaskRepositoryException conflict = assertInstanceOf(TaskRepositoryException.class, actual,
                "Solon transaction must preserve the Task repository retry classification");
        assertSame(expected, conflict, "business conflict identity must survive transaction rollback");
        assertEquals(TaskRepositoryException.Code.CAS_CONFLICT, conflict.code());
    }

    /**
     * SQL 失败仍归为基础设施事务错误，并验证失败前的 Mapper 写入一并回滚。
     */
    private static void verifySqlFailureClassificationAndRollback(JaDatabase database) throws SQLException {
        MybatisAdapter adapter = MybatisAdapterManager.getOnly("ja");
        assertNotNull(adapter, "isolated Ja MyBatis adapter must be registered");
        MybatisUnitOfWork transactions = new MybatisUnitOfWork(adapter.getFactory());
        Throwable actual = assertThrows(Throwable.class, () -> transactions.required(mappers -> {
            int updated = mappers.history().compareAndSetThreadPin(new PersistenceRecords.ThreadPinCas(
                    "thr_embedded", "2026-08-25T12:00:03Z", 0, "2026-08-25T12:00:03Z"));
            assertEquals(1, updated, "probe write must be part of the transaction under test");
            PersistenceRecords.ThreadRow thread = mappers.history().selectThread("thr_embedded");
            assertNotNull(thread, "duplicate-key probe must use the existing isolated Thread");
            mappers.history().insertThread(new PersistenceRecords.ThreadInsert(
                    thread.threadId(), thread.ownerUserId(), thread.workspaceId(), thread.title(),
                    thread.providerId(), thread.modelId(), thread.reasoningLevel(), thread.accessMode(),
                    thread.collaborationMode(), thread.titleSource(), thread.createdAt()));
            return null;
        }));

        assertThreadPinRolledBack(database, "SQL failure must roll back its preceding mapper write");
        StorageException failure = assertInstanceOf(StorageException.class, actual,
                "SQL failure must remain in the stable storage error surface");
        assertEquals(StorageException.Code.TRANSACTION, failure.code());
        assertInstanceOf(PersistenceException.class, failure.getCause(),
                "the transaction boundary must retain the direct MyBatis SQL failure for local diagnosis");
    }

    /**
     * 受检回调失败保持 TRANSACTION 分类，回滚后仍保留原始直接原因供本地诊断。
     */
    private static void verifyCheckedFailureClassificationAndRollback(JaDatabase database) throws SQLException {
        MybatisAdapter adapter = MybatisAdapterManager.getOnly("ja");
        assertNotNull(adapter, "isolated Ja MyBatis adapter must be registered");
        MybatisUnitOfWork transactions = new MybatisUnitOfWork(adapter.getFactory());
        IOException expected = new IOException("checked transaction probe");
        Throwable actual = assertThrows(Throwable.class, () -> transactions.required(mappers -> {
            int updated = mappers.history().compareAndSetThreadPin(new PersistenceRecords.ThreadPinCas(
                    "thr_embedded", "2026-08-25T12:00:04Z", 0, "2026-08-25T12:00:04Z"));
            assertEquals(1, updated, "probe write must be part of the transaction under test");
            throw expected;
        }));

        assertThreadPinRolledBack(database, "checked callback failure must roll back its mapper write");
        StorageException failure = assertInstanceOf(StorageException.class, actual,
                "checked callback failure must remain in the stable storage error surface");
        assertEquals(StorageException.Code.TRANSACTION, failure.code());
        assertSame(expected, failure.getCause(), "checked failure identity must remain available as the direct cause");
    }

    /**
     * 共用同一真实 SQLite 状态核对回滚，避免异常分类正确却遗漏此前写入的撤销。
     */
    private static void assertThreadPinRolledBack(JaDatabase database, String reason) throws SQLException {
        try (Connection connection = database.dataSource().getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT pinned_at_ms FROM ja_conversation_thread WHERE thread_id=?")) {
            query.setString(1, "thr_embedded");
            try (ResultSet row = query.executeQuery()) {
                assertTrue(row.next());
                assertNull(row.getString(1), reason);
            }
        }
    }

    /** 真实 MyBatis-Solon owner 在写入后遇到 Error 时必须回滚并原样传播同一异常实例。 */
    private static void verifyErrorIdentityAndRollback(JaDatabase database) throws SQLException {
        MybatisAdapter adapter = MybatisAdapterManager.getOnly("ja");
        assertNotNull(adapter, "isolated Ja MyBatis adapter must be registered");
        MybatisUnitOfWork transactions = new MybatisUnitOfWork(adapter.getFactory());
        AssertionError expected = new AssertionError("transaction identity probe");
        AssertionError actual = assertThrows(AssertionError.class, () -> transactions.required(mappers -> {
            int updated = mappers.history().compareAndSetThreadPin(new PersistenceRecords.ThreadPinCas(
                    "thr_embedded", "2026-08-25T12:00:01Z", 0, "2026-08-25T12:00:01Z"));
            assertEquals(1, updated, "probe write must be part of the transaction under test");
            throw expected;
        }));
        assertSame(expected, actual, "Error must not be wrapped as a storage exception");

        try (Connection connection = database.dataSource().getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT pinned_at_ms FROM ja_conversation_thread WHERE thread_id=?")) {
            query.setString(1, "thr_embedded");
            try (ResultSet row = query.executeQuery()) {
                assertTrue(row.next());
                assertNull(row.getString(1), "Solon must roll back the mapper write after Error");
            }
        }
    }

    /** 直接核验当前 JaDatabase 文件已收到 Workspace 注册，避免只在缓存的 MyBatis 工厂中自洽。 */
    private static void assertWorkspaceInAgentDatabase(JaDatabase database, String workspaceId) throws SQLException {
        try (Connection connection = database.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM ja_workspace WHERE workspace_id = ?")) {
            statement.setString(1, workspaceId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1), "workspace registration must target the AgentStore database");
            }
        }
    }

    /** 恢复 process-global 属性，避免后续 embedded composition 误用本测试目录。 */
    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        }
        else {
            System.setProperty(name, value);
        }
    }
}
