// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;
import io.github.kongweiguang.ja.agent.bootstrap.AgentEngineBuilder;

import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;

import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputMutationReceipt;
import io.github.kongweiguang.ja.agent.application.engine.AgentRequestFingerprint;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntimeResolver;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentRecordIds;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskMailboxQueueCommandMapper;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 以真实 SQLite 验证 Agent 队列命令与 Ja 输入投影共享同一 CAS、回执和事务边界。 */
final class JaInputQueueMutationPersistenceTest extends PersistenceTestSupport {
    private static final String THREAD_ID = "thr_queue_mutation";
    private static final String TURN_ID = "turn_queue_mutation";
    private static final String PRIMARY_INPUT_ID = "input_primary";
    private static final String SECONDARY_INPUT_ID = "input_secondary";

    /** 编辑、置顶和删除都重放同一 Core 回执；Ja 投影失败则同时回滚命令、水位与投影。 */
    @Test
    void persistsMutationReceiptsAndRollsBackCoreAndJaFactsTogether() throws Exception {
        AgentIdentity identity = identity();
        AgentStore.ExecutionKey execution = execution(identity);
        String databaseName = "ja-input-queue-mutations";
        AgentInputCommand.Enqueue primary = new AgentInputCommand.Enqueue(operationKey(1), PRIMARY_INPUT_ID,
                AgentInputCommand.Kind.FOLLOW_UP, text("原始输入"), metadata(1));
        AgentInputCommand.Enqueue secondary = new AgentInputCommand.Enqueue(operationKey(2), SECONDARY_INPUT_ID,
                AgentInputCommand.Kind.STEERING, text("另一条输入"), metadata(2));
        AgentInputCommand.Update update = new AgentInputCommand.Update(operationKey(3), PRIMARY_INPUT_ID,
                1, text("已编辑"), metadata(3));
        AgentInputCommand.Prioritize prioritize = new AgentInputCommand.Prioritize(operationKey(4),
                PRIMARY_INPUT_ID, 2, metadata(4));
        AgentInputCommand.Delete delete = new AgentInputCommand.Delete(operationKey(5), PRIMARY_INPUT_ID,
                3, metadata(5));

        try (TestDatabase database = database(databaseName)) {
            MybatisHistoryService history = createThread(database);
            AgentStore store = database.jaAgentStore();
            try (AgentStoreProjectionFixture admission = admit(database, store, identity);
                 AgentEngine engine = controlEngine(store)) {
                AgentInputMutationReceipt addedPrimary = mutate(engine, identity, primary);
                assertEquals(1, addedPrimary.queueRevision());
                assertInput(database, TURN_ID, PRIMARY_INPUT_ID, "FOLLOW_UP", "PENDING", 1, null, "原始输入");
                assertQueueRevision(database, TURN_ID, 1);
                assertInputReceipt(database, primary, "FOLLOW_UP");

                AgentInputMutationReceipt addedSecondary = mutate(engine, identity, secondary);
                assertEquals(2, addedSecondary.queueRevision());
                assertInput(database, TURN_ID, SECONDARY_INPUT_ID, "STEERING", "PENDING", 1, 1L, "另一条输入");
                assertQueueRevision(database, TURN_ID, 2);

                AgentStore.ExecutionSnapshot beforeRejectedMutation = store.loadExecution(execution).orElseThrow();
                AgentStore.ConversationSnapshot beforeRejectedConversation =
                        store.loadConversation(execution.conversation()).orElseThrow();
                int recordsBeforeRejectedMutation = recordCount(store, execution);
                installRejectingInputUpdate(database, PRIMARY_INPUT_ID);
                assertThrows(ExecutionException.class, () -> mutate(engine, identity, update));
                removeRejectingInputUpdate(database);

                assertSameStoreWatermarks(beforeRejectedMutation, store.loadExecution(execution).orElseThrow());
                assertEquals(beforeRejectedConversation, store.loadConversation(execution.conversation()).orElseThrow());
                assertEquals(recordsBeforeRejectedMutation, recordCount(store, execution));
                assertTrue(coreReceipt(store, identity, update).isEmpty(),
                        "failed Ja projection must not leave a Core operation receipt");
                assertInput(database, TURN_ID, PRIMARY_INPUT_ID, "FOLLOW_UP", "PENDING", 1, null, "原始输入");
                assertQueueRevision(database, TURN_ID, 2);

                AgentInputMutationReceipt edited = mutate(engine, identity, update);
                assertEquals(3, edited.queueRevision());
                assertInput(database, TURN_ID, PRIMARY_INPUT_ID, "FOLLOW_UP", "PENDING", 2, null, "已编辑");
                assertQueueRevision(database, TURN_ID, 3);
                assertEquals(edited, mutate(engine, identity, update), "an identical Update must replay its receipt");
                assertMutationUnchanged(store, execution, identity, update,
                        () -> mutate(engine, identity, update));

                AgentInputCommand.Update changedReplay = new AgentInputCommand.Update(operationKey(3),
                        PRIMARY_INPUT_ID, 1, text("同键不同内容"), metadata(3));
                AgentStore.ExecutionSnapshot beforeChangedReplay = store.loadExecution(execution).orElseThrow();
                AgentStore.ConversationSnapshot conversationBeforeChangedReplay =
                        store.loadConversation(execution.conversation()).orElseThrow();
                assertThrows(ExecutionException.class, () -> mutate(engine, identity, changedReplay),
                        "reusing an operation key with a different fingerprint must conflict");
                assertSameStoreWatermarks(beforeChangedReplay, store.loadExecution(execution).orElseThrow());
                assertEquals(conversationBeforeChangedReplay,
                        store.loadConversation(execution.conversation()).orElseThrow());
                assertEquals(coreReceipt(store, identity, update).orElseThrow(),
                        coreReceipt(store, identity, changedReplay).orElseThrow(),
                        "a conflicting retry must preserve the original durable receipt");
                AgentInputCommand.Update staleEdit = new AgentInputCommand.Update(operationKey(6),
                        PRIMARY_INPUT_ID, 1, text("陈旧版本"), metadata(6));
                AgentStore.ExecutionSnapshot beforeStaleEdit = store.loadExecution(execution).orElseThrow();
                AgentStore.ConversationSnapshot conversationBeforeStaleEdit =
                        store.loadConversation(execution.conversation()).orElseThrow();
                assertThrows(ExecutionException.class, () -> mutate(engine, identity, staleEdit),
                        "an old per-entry revision must not overwrite the committed edit");
                assertSameStoreWatermarks(beforeStaleEdit, store.loadExecution(execution).orElseThrow());
                assertEquals(conversationBeforeStaleEdit, store.loadConversation(execution.conversation()).orElseThrow());
                assertTrue(coreReceipt(store, identity, staleEdit).isEmpty(),
                        "a stale entry revision must not create an operation receipt");
                assertInput(database, TURN_ID, PRIMARY_INPUT_ID, "FOLLOW_UP", "PENDING", 2, null, "已编辑");
                assertQueueRevision(database, TURN_ID, 3);

                AgentInputMutationReceipt prioritized = mutate(engine, identity, prioritize);
                assertEquals(4, prioritized.queueRevision());
                assertInput(database, TURN_ID, PRIMARY_INPUT_ID, "STEERING", "PENDING", 3, 2L, "已编辑");
                assertQueueRevision(database, TURN_ID, 4);
                assertEquals(prioritized, mutate(engine, identity, prioritize),
                        "a repeated Prioritize must replay without moving the queue twice");
                assertMutationUnchanged(store, execution, identity, prioritize,
                        () -> mutate(engine, identity, prioritize));

                AgentInputMutationReceipt deleted = mutate(engine, identity, delete);
                assertEquals(5, deleted.queueRevision());
                assertInput(database, TURN_ID, PRIMARY_INPUT_ID, "STEERING", "CANCELLED", 4, 2L, "已编辑");
                assertQueueRevision(database, TURN_ID, 5);
                assertEquals(deleted, mutate(engine, identity, delete),
                        "a repeated Delete must return the committed queue revision");
                assertMutationUnchanged(store, execution, identity, delete,
                        () -> mutate(engine, identity, delete));
                assertEquals(5, engine.inspectInputQueue(identity).revision());
                assertEquals(List.of(SECONDARY_INPUT_ID), engine.inspectInputQueue(identity).entries().stream()
                        .map(AgentInputQueue.Entry::inputId).toList());
                assertNotNull(history.readThread(THREAD_ID, null, 10).orElseThrow());
            }
        }

        try (TestDatabase reopened = database(databaseName)) {
            AgentStore store = reopened.jaAgentStore();
            try (AgentEngine engine = controlEngine(store)) {
                assertInput(reopened, TURN_ID, PRIMARY_INPUT_ID, "STEERING", "CANCELLED", 4, 2L, "已编辑");
                assertQueueRevision(reopened, TURN_ID, 5);
                assertInputReceipt(reopened, primary, "FOLLOW_UP");
                assertEquals(5, engine.inspectInputQueue(identity).revision());
                assertEquals(List.of(SECONDARY_INPUT_ID), engine.inspectInputQueue(identity).entries().stream()
                        .map(AgentInputQueue.Entry::inputId).toList());
                assertEquals(1, mutate(engine, identity, primary).queueRevision(),
                        "the original Enqueue must remain idempotent after restart and later deletion");
                assertInput(reopened, TURN_ID, PRIMARY_INPUT_ID, "STEERING", "CANCELLED", 4, 2L, "已编辑");
                assertQueueRevision(reopened, TURN_ID, 5);
            }
        }
    }

    /** 旧快照即使 payload 可读也不能提交，拒绝必须与执行水位和 Ja 投影同事务回滚。
     * @author kongweiguang
     */
    @Test
    void rejectsRetiredQueueSnapshotInCoreAndJaTransaction() throws Exception {
        AgentIdentity identity = identity();
        AgentStore.ExecutionKey execution = execution(identity);

        try (TestDatabase database = database("ja-retired-queue-snapshot")) {
            createThread(database);
            AgentStore store = database.jaAgentStore();
            try (AgentStoreProjectionFixture admission = admit(database, store, identity)) {
                AgentStore.ExecutionSnapshot before = store.loadExecution(execution).orElseThrow();
                int recordsBefore = recordCount(store, execution);
                AgentStore.RecordWrite retired = new AgentStore.RecordWrite(
                        admission.recordId("agent.input.queue.snapshot", "retired"),
                        "agent.input.queue.snapshot", 1, JsonObject.empty(), START.plusSeconds(20));
                TurnExecutionState cursor = new io.github.kongweiguang.ja.agent.infrastructure.persistence
                        .TurnExecutionStateCodec(database.mapper()).read(before.cursorSchemaVersion(), before.cursor());

                assertThrows(AgentStore.StoreParticipantException.class, () -> admission.commitRecords(
                        TurnState.valueOf(before.state()), cursor, List.of(retired), List.of(), START.plusSeconds(20)));

                assertEquals(before, store.loadExecution(execution).orElseThrow());
                assertEquals(recordsBefore, recordCount(store, execution));
            }
        }
    }

    /** Active mailbox ENQUEUE 用 scoped Store key 派生事实，但仍严格绑定 mapper 的原始消息正文与来源。 */
    @Test
    void bindsActiveMailboxMessageWithScopedStoreIdentityAndRollsBackSpoofedContent() throws Exception {
        AgentIdentity identity = identity();
        AgentStore.ExecutionKey execution = execution(identity);
        String senderThreadId = "thr_queue_sender";
        String mailboxMessageId = "msg_active_queue_mailbox";
        String databaseName = "ja-active-mailbox-control";

        try (TestDatabase database = database(databaseName)) {
            MybatisHistoryService history = createThread(database);
            history.createThread(new ThreadSummary.Creation(senderThreadId, "ws_1", "消息发送方",
                    preferences(), START.plusSeconds(1)));
            AgentStore store = database.jaAgentStore();
            try (AgentStoreProjectionFixture admission = admit(database, store, identity);
                 AgentEngine engine = controlEngine(store)) {
                TaskModels.MailboxMessage mailbox = database.taskRepository().enqueueMessage(
                        new TaskModels.MailboxEnvelope(mailboxMessageId, senderThreadId, THREAD_ID, null,
                                TaskModels.MailboxKind.MESSAGE, text("活动执行中的侧聊消息"),
                                "mailbox_active_1", START.plusSeconds(20))).mailbox();
                AgentInputCommand.Enqueue command = TaskMailboxQueueCommandMapper.toQueueCommand(mailbox,
                        new JacksonJsonValueCodec(database.mapper()));
                AgentStore.OperationIdentity scoped = AgentRequestFingerprint.prepareQueueMutation(identity, command)
                        .operation();
                assertEquals("agent.input.queue", scoped.operationType());
                assertNotEquals(command.operationKey(), scoped.operationKey());

                AgentStore.ExecutionSnapshot before = store.loadExecution(execution).orElseThrow();
                AgentStore.ConversationSnapshot conversationBefore =
                        store.loadConversation(execution.conversation()).orElseThrow();
                int recordsBefore = recordCount(store, execution);
                AgentInputCommand.Enqueue altered = new AgentInputCommand.Enqueue(command.operationKey(),
                        command.inputId(), command.kind(), text("伪造的队列正文"), command.hostMetadata());
                assertThrows(ExecutionException.class, () -> mutate(engine, identity, altered));
                assertSameStoreWatermarks(before, store.loadExecution(execution).orElseThrow());
                assertEquals(conversationBefore, store.loadConversation(execution.conversation()).orElseThrow());
                assertEquals(recordsBefore, recordCount(store, execution));
                assertTrue(coreReceipt(store, identity, altered).isEmpty());
                assertMailboxState(database, mailboxMessageId, "PENDING", null, null);

                AgentInputMutationReceipt receipt = mutate(engine, identity, command);
                assertEquals(1, receipt.queueRevision());
                String bodyRecordId = AgentRecordIds.derive(execution, "agent.input.message", scoped.operationKey());
                String messageId = AgentRecordIds.derive(execution, "agent.input.message-id", scoped.operationKey());
                String commandRecordId = AgentRecordIds.derive(execution,
                        ExecutionControlCodec.INPUT_QUEUE_COMMAND_RECORD_TYPE, scoped.operationKey());
                AgentStore.StoredRecord bodyRecord = store.readRecord(execution, bodyRecordId).orElseThrow();
                io.github.kongweiguang.ja.agent.domain.conversation.UserMessage queued =
                        new StorePayloadCodec(database.mapper()).decodeQueuedUser(bodyRecord);
                assertEquals(messageId, queued.messageId());
                assertEquals(command.content(), queued.content());
                AgentStore.StoredRecord commandRecord = store.readRecord(execution, commandRecordId).orElseThrow();
                AgentInputQueue.Command persisted = new ControlPayloadCodec(database.mapper())
                        .decodeInputQueueCommand(commandRecord);
                assertEquals(commandRecordId, persisted.commandId());
                assertEquals(command.inputId(), persisted.inputId());
                assertEquals(command.hostMetadata(), persisted.hostMetadata());
                assertEquals(bodyRecordId, persisted.messageRecordId());
                assertMailboxState(database, mailboxMessageId, "BOUND", TURN_ID, null);
                AgentInputQueue.Entry queuedEntry = engine.inspectInputQueue(identity).entries().stream()
                        .filter(entry -> entry.inputId().equals(command.inputId())).findFirst().orElseThrow();
                assertEquals(AgentInputQueue.Kind.MESSAGE, queuedEntry.kind());
                assertEquals(bodyRecordId, queuedEntry.messageRecordId());
            }
        }
    }

    /** 创建真实 Thread 与其 Store 锚点，准入参与者随后在该会话上写入 Turn 投影。 */
    private MybatisHistoryService createThread(TestDatabase database) {
        MybatisHistoryService history = database.history();
        history.register(new Workspace.Registration("ws_1", temp.toAbsolutePath(), "workspace", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, START));
        history.createThread(new ThreadSummary.Creation(THREAD_ID, "ws_1", "队列回归", preferences(), START));
        return history;
    }

    /** 用真实 Ja admission participant 建立 Core 执行和 Ja Turn，确保控制操作能引用现存归属行。 */
    private AgentStoreProjectionFixture admit(TestDatabase database, AgentStore store, AgentIdentity identity) {
        Instant acceptedAt = START.plusSeconds(10);
        long revision = store.loadConversation(new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), "user_local"))
                .orElseThrow().revision();
        AgentExecutionRequest request = new AgentExecutionRequest(identity,
                AgentExecutionRequest.AdmissionKind.USER_INPUT, Optional.of(text("启动执行")),
                "item_user_" + identity.turnId(), Optional.empty(),
                new AgentStore.OperationIdentity("operation_" + identity.turnId(), "turn/start", "a".repeat(64), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")), Map.of(),
                AgentExecutionRequest.HistorySelection.currentConversation(), acceptedAt, List.of(),
                Optional.empty(), Optional.empty());
        AdmissionFacts facts = new AdmissionFacts(identity, "ws_1", TurnOrigin.USER, "turn/start", acceptedAt,
                null, null, null, null, null, null, null, revision, Optional.empty());
        return AgentStoreProjectionFixture.open(store, database.mapper(), request, facts, preferences(), revision);
    }

    /** 控制事务不需要模型绑定；失败即表示测试误触发了执行阶段的 runtime 解析。 */
    private static AgentEngine controlEngine(AgentStore store) {
        AgentRequestRuntimeResolver resolver = (request, extensions, recovered) -> {
            throw new AssertionError("queue control mutations must not resolve a model runtime");
        };
        return new AgentEngineBuilder().store(store).runtimeResolver(resolver).build();
    }

    /** 在固定租户身份下使用公开 Engine 入口执行实际 Store reducer 与 Ja participant。 */
    private static AgentInputMutationReceipt mutate(AgentEngine engine, AgentIdentity identity,
                                                   AgentInputCommand command) throws Exception {
        return engine.mutateInput(identity, command).toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** 从同一测试数据库检查 mailbox CAS 的三态列，避免只凭 Core 队列快照推断 Ja 已绑定。 */
    private static void assertMailboxState(TestDatabase database, String messageId, String expectedState,
                                          String expectedBoundTurn, String expectedConsumedAt) throws SQLException {
        try (Connection connection = database.database().dataSource().getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT state,bound_turn_id,consumed_at_ms FROM ja_task_mailbox_message WHERE message_id=?")) {
            query.setString(1, messageId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new AssertionError("mailbox row is missing");
                }
                assertEquals(expectedState, rows.getString(1));
                assertEquals(expectedBoundTurn, rows.getString(2));
                assertEquals(expectedConsumedAt, rows.getString(3));
                if (rows.next()) {
                    throw new AssertionError("mailbox identity is not unique");
                }
            }
        }
    }

    /** 将重放前后 Core 回执及 Store 水位逐项比较，确保重试只返回原事务结果。 */
    private static void assertMutationUnchanged(AgentStore store, AgentStore.ExecutionKey execution,
            AgentIdentity identity, AgentInputCommand command, CheckedMutation mutation) throws Exception {
        AgentStore.ExecutionSnapshot before = store.loadExecution(execution).orElseThrow();
        AgentStore.ConversationSnapshot conversationBefore = store.loadConversation(execution.conversation())
                .orElseThrow();
        AgentStore.OperationReceipt receiptBefore = coreReceipt(store, identity, command).orElseThrow();
        mutation.run();
        assertSameStoreWatermarks(before, store.loadExecution(execution).orElseThrow());
        assertEquals(conversationBefore, store.loadConversation(execution.conversation()).orElseThrow());
        assertEquals(receiptBefore, coreReceipt(store, identity, command).orElseThrow());
    }

    /** 只比较由核心控制事务拥有的版本和游标，避免把非权威数据库时间纳入幂等断言。 */
    private static void assertSameStoreWatermarks(AgentStore.ExecutionSnapshot before,
                                                   AgentStore.ExecutionSnapshot after) {
        assertEquals(before.mutationVersion(), after.mutationVersion());
        assertEquals(before.lastRecordSequence(), after.lastRecordSequence());
        assertEquals(before.cursorSchemaVersion(), after.cursorSchemaVersion());
        assertEquals(before.cursor(), after.cursor());
        assertEquals(before.state(), after.state());
    }

    /** 固定执行水位物化全部小型测试事实，回滚断言不会被事件或记录分页遗漏。 */
    private static int recordCount(AgentStore store, AgentStore.ExecutionKey execution) {
        AgentStore.ExecutionSnapshot snapshot = store.loadExecution(execution).orElseThrow();
        AgentStore.RecordPage page = store.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(
                execution, 0, snapshot.lastRecordSequence(), 1_000));
        assertFalse(page.hasMore(), "queue regression fixture must fit in one bounded record page");
        return page.records().size();
    }

    /** 查询 Ja 展示投影而不是用其重建队列，确保测试同时观察到两套事务内镜像。 */
    private static void assertInput(TestDatabase database, String turnId, String inputId, String kind,
            String state, long revision, Long priority, String contentMarker) throws SQLException {
        PendingInput row = input(database, turnId, inputId);
        assertEquals(kind, row.kind());
        assertEquals(state, row.state());
        assertEquals(revision, row.revision());
        assertEquals(priority, row.prioritySequence());
        assertTrue(row.contentJson().contains(contentMarker), "Ja row must contain the latest committed draft");
    }

    /** 从 Ja 的唯一展示表读取输入项，供 revision、顺序及重启断言使用。 */
    private static PendingInput input(TestDatabase database, String turnId, String inputId) throws SQLException {
        try (Connection connection = database.database().dataSource().getConnection();
             PreparedStatement query = connection.prepareStatement("""
                 SELECT kind,state,input_revision,priority_sequence,\
                 content_json FROM ja_turn_input WHERE turn_id=? AND input_id=?\
                 """)) {
            query.setString(1, turnId);
            query.setString(2, inputId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new AssertionError("Ja pending input projection is missing");
                }
                long priorityValue = rows.getLong(4);
                Long priority = rows.wasNull() ? null : priorityValue;
                PendingInput result = new PendingInput(rows.getString(1), rows.getString(2), rows.getLong(3),
                        priority, rows.getString(5));
                if (rows.next()) {
                    throw new AssertionError("Ja pending input identity is not unique");
                }
                return result;
            }
        }
    }

    /** 核对 turns 的展示队列版本与 Core reducer 分配值逐次同步且不因重放递增。 */
    private static void assertQueueRevision(TestDatabase database, String turnId, long expected)
            throws SQLException {
        try (Connection connection = database.database().dataSource().getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT input_queue_revision FROM ja_conversation_turn WHERE turn_id=?")) {
            query.setString(1, turnId);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next(), "Ja Turn row should exist");
                assertEquals(expected, rows.getLong(1));
                assertFalse(rows.next(), "Ja Turn identity must be unique");
            }
        }
    }

    /** 查询独立 Ja Enqueue 回执，保证断连重试仍绑定同一输入与本次请求指纹。 */
    private static void assertInputReceipt(TestDatabase database, AgentInputCommand.Enqueue command,
                                           String expectedKind) throws SQLException {
        String expectedFingerprint = command.hostMetadata().get("ja.requestFingerprint");
        try (Connection connection = database.database().dataSource().getConnection();
             PreparedStatement query = connection.prepareStatement("""
                 SELECT request_fingerprint,input_id,input_kind,\
                 thread_id,turn_id FROM ja_input_operation_receipt WHERE operation_key=?\
                 """)) {
            query.setString(1, command.hostMetadata().get("ja.operationKey"));
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next(), "Ja Enqueue receipt should be committed with the queue command");
                assertEquals(expectedFingerprint, rows.getString(1));
                assertEquals(command.inputId(), rows.getString(2));
                assertEquals(expectedKind, rows.getString(3));
                assertEquals(THREAD_ID, rows.getString(4));
                assertEquals(TURN_ID, rows.getString(5));
                assertFalse(rows.next(), "one client operation must have one Ja receipt");
            }
        }
    }

    /** Store 操作回执与 Ja RPC 回执不是同一标识，按 Engine 规范化后的租户键单独读取。 */
    private static Optional<AgentStore.OperationReceipt> coreReceipt(AgentStore store, AgentIdentity identity,
            AgentInputCommand command) {
        AgentStore.OperationIdentity operation = AgentRequestFingerprint.prepareQueueMutation(identity, command).operation();
        return store.readReceipt(new AgentStore.OperationKey(identity.tenantId(), "user_local", operation.operationKey()));
    }

    /** 注入仅命中该行的 SQLite 写失败，确保回滚由真实 Ja participant SQL 路径触发。 */
    private static void installRejectingInputUpdate(TestDatabase database, String inputId) throws SQLException {
        try (Connection connection = database.database().dataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TRIGGER reject_test_pending_input_update BEFORE UPDATE ON ja_turn_input \
                WHEN OLD.input_id='\
                """ + inputId + "' BEGIN SELECT RAISE(ABORT,'injected projection failure'); END");
        }
    }

    /** 在失败断言后移除临时触发器，使同一未提交幂等操作可以被安全重试。 */
    private static void removeRejectingInputUpdate(TestDatabase database) throws SQLException {
        try (Connection connection = database.database().dataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER reject_test_pending_input_update");
        }
    }

    /** 构造 Ja 明确要求的执行身份；tenant/thread/turn/user 不从数据库展示字段反推。 */
    private static AgentIdentity identity() {
        return new AgentIdentity("ja.local", THREAD_ID, TURN_ID, "user_local");
    }

    /** 由身份的完整四元组构造 Store 查询键，防止测试误跨租户读取。 */
    private static AgentStore.ExecutionKey execution(AgentIdentity identity) {
        return new AgentStore.ExecutionKey(new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), "user_local"),
                identity.turnId());
    }

    /** 生成通过 Ja RPC 字段校验的稳定操作键，不依赖随机值或默认哈希。 */
    private static String operationKey(int ordinal) {
        return "op_%032x".formatted(ordinal);
    }

    /** 将业务幂等回执信息原样放入命令，核心与 Ja 各自按契约保存自己的回执。 */
    private static Map<String, String> metadata(int ordinal) {
        return Map.of("ja.operationKey", operationKey(ordinal),
                "ja.requestFingerprint", "%064x".formatted(ordinal));
    }

    /** 构造纯文本队列项以隔离测试目标，不引入附件授权或解析路径。 */
    private static UserContent text(String value) {
        return new UserContent(List.of(new TextContent(value)));
    }

    /** 创建时冻结的权限和协作策略供 admission projection 校验，不从运行时补默认值。 */
    private static ThreadPreferences preferences() {
        return new ThreadPreferences("provider_test", "model_test", "medium", AccessMode.APPROVAL_REQUIRED,
                CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.PLACEHOLDER);
    }

    /** 表示可能异步失败的幂等重放调用，便于统一比较重试前后的持久水位。 */
    @FunctionalInterface
    private interface CheckedMutation {
        /** 执行公开 Engine 控制请求并等待其持久回执。 */
        void run() throws Exception;
    }

    /**
     * 保留 Ja 表中的最小输入快照，避免把展示记录误当成 Core 队列权威。
     *
     * @param kind 类型分类
     * @param state 当前状态
     * @param revision 当前修订版本
     * @param prioritySequence 优先级序号
     * @param contentJson 内容 JSON 表示
     */
    private record PendingInput(String kind,
                                String state,
                                long revision,
                                Long prioritySequence,
                                String contentJson) {
        /** SQL 查询必须带齐正文和行版本，便于区分内容写入与队列顺序写入。 */
        private PendingInput {
            assertNotNull(kind);
            assertNotNull(state);
            assertNotNull(contentJson);
        }
    }
}




