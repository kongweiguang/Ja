// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentRecordIds;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.conversation.UserMessage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.JacksonExecutionEventCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.PersistenceCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskMailboxQueueCommandMapper;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.conversation.domain.model.JaMessageIds;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaMessageProjection.attachmentReferences;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaMessageProjection.contextReferences;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.encode;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.nextTimelineSequence;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.readStoredRecord;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.requireOne;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.stored;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.transactionRecord;

/** 邮箱投影只维护输入消费与可见消息的 CAS，正文归邮箱事实 owner，不重建 Core 队列。 */
final class JaMailboxProjection {
    private final ObjectMapper mapper;
    private final StorePayloadCodec facts;
    private final ControlPayloadCodec controls;
    private final PersistenceCodec messages;
    private final JsonValueCodec jsonValues;
    private final JacksonExecutionEventCodec executionEvents;

    /** 所有 codec 共用组合根配置；此协作者只接收连接，不持有数据库生命周期。 */
    JaMailboxProjection(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.facts = new StorePayloadCodec(mapper);
        this.controls = new ControlPayloadCodec(mapper);
        this.messages = new PersistenceCodec(mapper);
        this.jsonValues = new JacksonJsonValueCodec(mapper);
        this.executionEvents = new JacksonExecutionEventCodec(mapper);
    }

    /**
     * 邮箱 metadata schema 固定为八个 Ja provenance key，空因果 Turn 用空串而非缺字段表达。
     */
    private static MailboxMetadata mailboxMetadata(Map<String, String> values) throws SQLException {
        try {
            return new MailboxMetadata(requiredMetadata(values, "ja.mailbox.messageId"),
                    requiredMetadata(values, "ja.mailbox.messageKind"),
                    requiredMetadata(values, "ja.mailbox.senderThreadId"),
                    requiredMetadata(values, "ja.mailbox.targetThreadId"),
                    nullableMetadata(requiredMetadata(values, "ja.mailbox.causalTurnId")),
                    requiredMetadata(values, "ja.mailbox.senderTitle"),
                    requiredMetadata(values, "ja.mailbox.operationKey"),
                    Instant.parse(requiredMetadata(values, "ja.mailbox.createdAt")));
        } catch (RuntimeException invalid) {
            throw new SQLException("Ja mailbox queue metadata is invalid", invalid);
        }
    }

    /**
     * 不接受字段缺失或空值；只有 causalTurnId 按协议可用空串编码 null。
     */
    private static String requiredMetadata(Map<String, String> values, String key) throws SQLException {
        String value = values.get(key);
        if (value == null || (!"ja.mailbox.causalTurnId".equals(key) && value.isBlank())) {
            throw new SQLException("Ja mailbox queue metadata is incomplete");
        }
        return value;
    }

    /**
     * 空串只在 causal Turn key 上编码 null；其它身份字段必须完整非空。
     */
    private static String nullableMetadata(String value) {
        return value.isEmpty() ? null : value;
    }

    /**
     * 将 BOUND snapshot 复制为 mapper 所需的 PENDING 入队视图，不修改数据库状态。
     */
    private static TaskModels.MailboxMessage mailboxForCommandMapping(TaskModels.MailboxMessage mailbox) {
        return new TaskModels.MailboxMessage(mailbox.sequence(), mailbox.messageId(), mailbox.rootThreadId(),
                mailbox.senderThreadId(), mailbox.senderTitle(), mailbox.targetThreadId(), mailbox.causalTurnId(),
                mailbox.kind(), mailbox.content(), mailbox.operationKey(), TaskModels.MailboxState.PENDING,
                null, mailbox.createdAt(), mailbox.updatedAt(), null);
    }

    /**
     * ENQUEUE 和 CONSUME 各自只能结算一个稳定邮箱身份，防止重复操作悄悄推进 projection。
     */
    private static void requireUniqueMailboxMutation(Set<String> messageIds, String messageId) throws SQLException {
        if (!messageIds.add(messageId))
            {
                throw new SQLException("Ja mailbox is mutated more than once in one transaction");
            }
    }

    /**
     * 消耗唯一已绑定行并写 ThreadMessageItem 投影，失败时 Store 与 Ja 两侧一起回滚。
     */
    private void consumeMailboxMessage(Connection connection, AgentStoreTransactionContext transaction,
                                       AgentInputQueue.Command command, MailboxSource source) throws SQLException {
        TaskModels.MailboxMessage mailbox = source.mailbox();
        try (PreparedStatement update = connection.prepareStatement(
                """
                    UPDATE ja_task_mailbox_message SET state='CONSUMED',consumed_at_ms=?,updated_at_ms=? WHERE message_id=? \
                    AND from_thread_id=? AND to_thread_id=? AND kind=? AND operation_key=? \
                    AND state='BOUND' AND bound_turn_id=? AND consumed_at_ms IS NULL\
                    """)) {
            update.setLong(1, command.occurredAt().toEpochMilli());
            update.setLong(2, command.occurredAt().toEpochMilli());
            update.setString(3, mailbox.messageId());
            update.setString(4, mailbox.senderThreadId());
            update.setString(5, mailbox.targetThreadId());
            update.setString(6, mailbox.kind().name());
            update.setString(7, mailbox.operationKey());
            update.setString(8, transaction.execution().turnId());
            requireOne(update.executeUpdate(), "Ja mailbox BOUND consumption CAS failed");
        }
        insertThreadMessageTimeline(connection, mailbox, transaction.execution());
    }

    /**
     * 跨 Thread item 只引用 Mailbox 正文，时间线保留有界预览和来源快照。
     */
    private void insertThreadMessageTimeline(Connection connection, TaskModels.MailboxMessage mailbox,
                                             AgentStore.ExecutionKey execution) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                """
                    INSERT INTO ja_conversation_timeline_entry(item_id,thread_id,turn_id,sequence,kind,is_current_path,\
                    message_id,mailbox_message_id,preview_text,total_characters,attachments_json,context_references_json,\
                    model_round,created_at_ms,source_thread_id,source_title) \
                    VALUES(?,?,?,?, 'THREAD_MESSAGE',1,NULL,?,?,?,?,?,NULL,?,?,?)\
                    """)) {
            insert.setString(1, JaMessageIds.toItemId(mailbox.messageId()));
            insert.setString(2, execution.conversation().threadId());
            insert.setString(3, execution.turnId());
            insert.setLong(4, nextTimelineSequence(connection, execution.conversation().threadId()));
            insert.setString(5, mailbox.messageId());
            insert.setString(6, ThreadSnapshot.textPreview(mailbox.content().text()));
            insert.setInt(7, mailbox.content().text().codePointCount(0, mailbox.content().text().length()));
            insert.setString(8, encode(mapper, attachmentReferences(mailbox.content())));
            insert.setString(9, encode(mapper, contextReferences(mailbox.content())));
            insert.setLong(10, mailbox.createdAt().toEpochMilli());
            insert.setString(11, mailbox.senderThreadId());
            insert.setString(12, mailbox.senderTitle());
            requireOne(insert.executeUpdate(), "Ja Thread message timeline insert failed");
        }
    }

    /**
     * 终态提交返还未消费的跨 Thread 消息；已消费行不满足 BOUND CAS，不能被重复投递。
     */
    static void releaseUnconsumedMailboxMessages(Connection connection,
                                                         AgentStoreTransactionContext transaction) throws SQLException {
        if (!TurnState.valueOf(transaction.state()).terminal()) {
            return;
        }
        try (PreparedStatement update = connection.prepareStatement(
                """
                    UPDATE ja_task_mailbox_message SET state='PENDING',bound_turn_id=NULL,updated_at_ms=? \
                    WHERE to_thread_id=? AND bound_turn_id=? AND state='BOUND' \
                    AND kind IN ('MESSAGE','FINAL_ANSWER') AND consumed_at_ms IS NULL\
                    """)) {
            update.setLong(1, transaction.occurredAt().toEpochMilli());
            update.setString(2, transaction.execution().conversation().threadId());
            update.setString(3, transaction.execution().turnId());
            update.executeUpdate();
        }
    }

    /**
     * 在写任何 Ja 投影前闭合 MESSAGE 的权威链：ENQUEUE 必须对应一条 PENDING mailbox 和 queued body，
     * CONSUME 必须对应同执行中最近的原始 ENQUEUE，避免依赖内存缓存跨重启推断来源。
     */
    List<MailboxQueueProjection> prepareMailboxQueue(Connection connection,
                                                             AgentStoreTransactionContext transaction, List<QueueCommandFact> commands) throws SQLException {
        List<MailboxQueueProjection> projections = new ArrayList<>();
        Set<String> messageIds = new HashSet<>();
        for (QueueCommandFact fact : commands) {
            AgentInputQueue.Command command = fact.command();
            if (command.action() == AgentInputQueue.Action.ENQUEUE) {
                if (command.kind() != AgentInputQueue.Kind.MESSAGE) {
                    if (command.hostMetadata().keySet().stream().anyMatch(key -> key.startsWith("ja.mailbox."))) {
                        throw new SQLException("Ja mailbox metadata requires MESSAGE queue kind");
                    }
                    continue;
                }
                AgentStore.StoredRecord queuedRecord = transactionRecord(transaction, command.messageRecordId());
                UserMessage queuedMessage = decodeQueuedMessage(queuedRecord);
                MailboxQueueProjection projection = mailboxProjection(connection, transaction, command,
                        command, queuedRecord, queuedMessage, TaskModels.MailboxState.PENDING);
                requireUniqueMailboxMutation(messageIds, projection.source().mailbox().messageId());
                projections.add(projection);
                continue;
            }

            QueueCommandFact sourceEnqueue = lastQueueEntry(connection, transaction, commands, command);
            if (sourceEnqueue == null || sourceEnqueue.command().kind() != AgentInputQueue.Kind.MESSAGE) {
                continue;
            }
            if (command.action() != AgentInputQueue.Action.CONSUME) {
                throw new SQLException("Ja mailbox MESSAGE cannot be edited or removed from the Core queue");
            }
            AgentInputQueue.Command enqueue = sourceEnqueue.command();
            AgentStore.StoredRecord queuedRecord = persistedQueuedMessage(connection, transaction,
                    enqueue.messageRecordId());
            UserMessage queuedMessage = decodeQueuedMessage(queuedRecord);
            MailboxQueueProjection projection = mailboxProjection(connection, transaction, command,
                    enqueue, queuedRecord, queuedMessage, TaskModels.MailboxState.BOUND);
            verifyConsumedUserFact(transaction, queuedMessage);
            requireUniqueMailboxMutation(messageIds, projection.source().mailbox().messageId());
            projections.add(projection);
        }
        return List.copyOf(projections);
    }

    /**
     * ENQUEUE/CONSUME 只接受同一条 queue record、mailbox row 与正文 record 的闭合身份。
     */
    private MailboxQueueProjection mailboxProjection(Connection connection, AgentStoreTransactionContext transaction,
                                                     AgentInputQueue.Command mutation, AgentInputQueue.Command enqueue, AgentStore.StoredRecord queuedRecord,
                                                     UserMessage queuedMessage, TaskModels.MailboxState expectedState) throws SQLException {
        MailboxMetadata metadata = mailboxMetadata(enqueue.hostMetadata());
        TaskModels.MailboxMessage mailbox = readMailbox(connection, metadata.messageId());
        validateMailboxFacts(connection, transaction, enqueue, queuedRecord, queuedMessage, metadata,
                mailbox, expectedState);
        if (mutation.action() == AgentInputQueue.Action.ENQUEUE
            && !mutation.messageRecordId().equals(queuedRecord.recordId())) {
            throw new SQLException("Ja mailbox ENQUEUE references a different queued body");
        }
        return new MailboxQueueProjection(mutation, new MailboxSource(mailbox, queuedRecord.recordId(), queuedMessage));
    }

    /**
     * 比较 mapper 的原始消息身份与 Core Store 身份；两层 operation key 各自绑定对应字段。
     */
    private void validateMailboxFacts(Connection connection, AgentStoreTransactionContext transaction,
                                      AgentInputQueue.Command enqueue,
                                      AgentStore.StoredRecord queuedRecord, UserMessage queuedMessage, MailboxMetadata metadata,
                                      TaskModels.MailboxMessage mailbox, TaskModels.MailboxState expectedState) throws SQLException {
        if (mailbox.state() != expectedState
            || (expectedState == TaskModels.MailboxState.BOUND
                && !transaction.execution().turnId().equals(mailbox.boundTurnId()))
            || !transaction.execution().conversation().threadId().equals(metadata.targetThreadId())
            || !Objects.equals(metadata.messageId(), mailbox.messageId())
            || !Objects.equals(metadata.kind(), mailbox.kind().name())
            || !Objects.equals(metadata.senderThreadId(), mailbox.senderThreadId())
            || !Objects.equals(metadata.targetThreadId(), mailbox.targetThreadId())
            || !Objects.equals(metadata.causalTurnId(), mailbox.causalTurnId())
            || !Objects.equals(metadata.senderTitle(), mailbox.senderTitle())
            || !Objects.equals(metadata.operationKey(), mailbox.operationKey())
            || !Objects.equals(metadata.createdAt(), mailbox.createdAt())) {
            throw new SQLException("Ja mailbox metadata, state, and queued message disagree");
        }
        TaskModels.MailboxMessage pending = mailboxForCommandMapping(mailbox);
        AgentInputCommand.Enqueue expected = TaskMailboxQueueCommandMapper.toQueueCommand(pending, jsonValues);
        AgentStore.ExecutionKey execution = transaction.execution();
        String coreOperationKey = mailboxRecordOperationKey(connection, transaction, enqueue,
                expected.operationKey(), expectedState);
        if (enqueue.action() != AgentInputQueue.Action.ENQUEUE
            || enqueue.kind() != AgentInputQueue.Kind.MESSAGE
            || !enqueue.inputId().equals(expected.inputId())
            || !enqueue.hostMetadata().equals(expected.hostMetadata())
            || !enqueue.messageRecordId().equals(queuedRecord.recordId())
            || !queuedRecord.recordId().equals(AgentRecordIds.derive(execution,
                "agent.input.message", coreOperationKey))
            || !queuedMessage.messageId().equals(AgentRecordIds.derive(execution,
                "agent.input.message-id", coreOperationKey))
            || !enqueue.commandId().equals(AgentRecordIds.derive(execution,
                "agent.input.queue.command", coreOperationKey))
            || !queuedMessage.content().equals(expected.content())) {
            throw new SQLException("Ja mailbox does not match its stable Core queue identities");
        }
    }

    /**
     * 身份跟随原始入队事务，消费时不能借用本次 CONSUME 的 operation key。
     */
    private String mailboxRecordOperationKey(Connection connection, AgentStoreTransactionContext transaction,
                                             AgentInputQueue.Command enqueue, String mapperOperationKey, TaskModels.MailboxState expectedState)
            throws SQLException {
        if (expectedState == TaskModels.MailboxState.BOUND) {
            return persistedMailboxOperationKey(connection, transaction, enqueue, mapperOperationKey);
        }
        if (transaction.kind() == AgentStore.TransactionKind.ADMISSION) {
            return mapperOperationKey;
        }
        if (transaction.kind() != AgentStore.TransactionKind.CONTROL) {
            throw new SQLException("Ja mailbox PENDING enqueue appeared outside admission or control");
        }
        AgentStore.OperationIdentity operation = transaction.operation().orElseThrow(
                () -> new SQLException("Ja mailbox control has no Store operation identity"));
        if (!"agent.input.queue".equals(operation.operationType())) {
            throw new SQLException("Ja mailbox control uses another Store operation type");
        }
        String commandId = AgentRecordIds.derive(transaction.execution(),
                ExecutionControlCodec.INPUT_QUEUE_COMMAND_RECORD_TYPE, operation.operationKey());
        if (!commandId.equals(enqueue.commandId())) {
            throw new SQLException("Ja mailbox queue command is not bound to its Store operation");
        }
        int inputChangedEvents = 0;
        for (AgentStore.EventWrite write : transaction.events()) {
            if (!"agent.execution.control".equals(write.eventType())) {
                continue;
            }
            ExecutionEvent decoded;
            try {
                decoded = executionEvents.decode(write.eventType(), write.payloadSchemaVersion(), jsonValues.encode(write.payload()));
            } catch (RuntimeException invalid) {
                throw new SQLException("Ja mailbox control event is invalid", invalid);
            }
            if (!(decoded instanceof ExecutionEvent.ControlRecorded(
                    ExecutionEvent.ControlAction action, String controlRecordId, String operationKey, String reasonCode
            ))
                || action != ExecutionEvent.ControlAction.INPUT_CHANGED) {
                throw new SQLException("Ja mailbox control contains a different control event");
            }
            inputChangedEvents++;
            if (!commandId.equals(controlRecordId)
                || !operation.operationKey().equals(operationKey)
                || reasonCode != null
                || !write.occurredAt().equals(transaction.occurredAt())) {
                throw new SQLException("Ja mailbox queue event differs from its Store operation");
            }
        }
        if (inputChangedEvents != 1) {
            throw new SQLException("Ja mailbox control requires one matching input-change event");
        }
        return operation.operationKey();
    }

    /**
     * 原始队列记录、事件与 Core 操作回执按完整执行 owner 和提交版本闭合，消费不能借用另一用户或本次事务的回执。
     */
    private String persistedMailboxOperationKey(Connection connection, AgentStoreTransactionContext transaction,
                                                AgentInputQueue.Command enqueue, String mapperOperationKey) throws SQLException {
        String sql = """
            SELECT e.event_type,e.payload_schema_version,e.payload_json,o.operation_key,o.operation_type \
            FROM agent_record q JOIN agent_event e \
            ON e.tenant_id=q.tenant_id AND e.thread_id=q.thread_id AND e.user_id=q.user_id \
            AND e.turn_id=q.turn_id JOIN agent_operation_receipt o \
            ON o.tenant_id=e.tenant_id AND o.thread_id=e.thread_id \
            AND o.turn_id=e.turn_id AND o.user_id=e.user_id AND e.event_sequence BETWEEN \
            o.first_event_sequence AND o.last_event_sequence \
            AND o.thread_revision=e.thread_revision AND o.mutation_version=e.mutation_version \
            WHERE q.tenant_id=? AND q.thread_id=? AND q.turn_id=? AND q.user_id=? AND q.record_id=? \
            AND q.record_type=? AND ((e.event_type='agent.execution.admitted' \
            AND q.record_sequence<=e.last_record_sequence) OR (e.event_type='agent.execution.control' \
            AND json_extract(e.payload_json,'$.controlRecordId')=?))\
            """;
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, transaction.execution().conversation().tenantId());
            query.setString(2, transaction.execution().conversation().threadId());
            query.setString(3, transaction.execution().turnId());
            query.setString(4, transaction.execution().conversation().userId());
            query.setString(5, enqueue.commandId());
            query.setString(6, ExecutionControlCodec.INPUT_QUEUE_COMMAND_RECORD_TYPE);
            query.setString(7, enqueue.commandId());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Ja mailbox enqueue has no original operation evidence");
                }
                ExecutionEvent event;
                try {
                    event = executionEvents.decode(rows.getString(1), rows.getInt(2), rows.getString(3));
                } catch (RuntimeException invalid) {
                    throw new SQLException("Ja mailbox original enqueue event is invalid", invalid);
                }
                String operationKey = rows.getString(4);
                String operationType = rows.getString(5);
                String originalKey;
                if (event instanceof ExecutionEvent.Admitted
                    && ("agent.submit".equals(operationType) || "agent.internal".equals(operationType))) {
                    originalKey = mapperOperationKey;
                } else if (event instanceof ExecutionEvent.ControlRecorded(
                        ExecutionEvent.ControlAction action, String controlRecordId, String key, String reasonCode
                )
                           && action == ExecutionEvent.ControlAction.INPUT_CHANGED
                           && reasonCode == null && enqueue.commandId().equals(controlRecordId)
                           && operationKey.equals(key) && "agent.input.queue".equals(operationType)) {
                    originalKey = operationKey;
                } else {
                    throw new SQLException("Ja mailbox original operation does not identify its enqueue");
                }
                if (rows.next()) {
                    throw new SQLException("Ja mailbox enqueue operation evidence is not unique");
                }
                return originalKey;
            }
        }
    }

    /**
     * 从同连接 task_mailbox 读取完整快照；重复主键或损坏 JSON 一律阻断执行提交。
     */
    private TaskModels.MailboxMessage readMailbox(Connection connection, String messageId) throws SQLException {
        String sql = """
            SELECT mailbox_sequence,message_id,root_thread_id,from_thread_id,sender_title,\
            to_thread_id,causal_turn_id,kind,content_json,operation_key,state,bound_turn_id,\
            created_at_ms,updated_at_ms,consumed_at_ms FROM ja_task_mailbox_message WHERE message_id=?\
            """;
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, messageId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Ja mailbox row is missing for MESSAGE queue fact");
                }
                try {
                    TaskModels.MailboxMessage value = new TaskModels.MailboxMessage(rows.getLong(1), rows.getString(2),
                            rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6),
                            rows.getString(7), TaskModels.MailboxKind.valueOf(rows.getString(8)),
                            messages.readUserContent(rows.getString(9)), rows.getString(10),
                            TaskModels.MailboxState.valueOf(rows.getString(11)), rows.getString(12),
                            Instant.ofEpochMilli(rows.getLong(13)), Instant.ofEpochMilli(rows.getLong(14)),
                            rows.getString(15) == null ? null : Instant.ofEpochMilli(rows.getLong(15)));
                    if (rows.next()) {
                        throw new SQLException("Ja mailbox message identity is not unique");
                    }
                    return value;
                } catch (RuntimeException invalid) {
                    throw new SQLException("Ja mailbox row is invalid", invalid);
                }
            }
        }
    }

    /**
     * 查找同一输入在本执行中最新的前序命令；MESSAGE 只有原始 ENQUEUE 后直接消费才合法。
     */
    private QueueCommandFact lastQueueEntry(Connection connection, AgentStoreTransactionContext transaction,
                                            List<QueueCommandFact> currentCommands, AgentInputQueue.Command mutation) throws SQLException {
        List<QueueCommandFact> history = new ArrayList<>(storedQueueCommands(connection, transaction));
        history.addAll(currentCommands);
        return history.stream().filter(value -> value.command().inputId().equals(mutation.inputId())
                                                && value.command().queueRevision() < mutation.queueRevision())
                .max(java.util.Comparator.comparingLong(value -> value.command().queueRevision()))
                .orElse(null);
    }

    /**
     * 同一 Store 连接按完整执行 owner 读取已提交 ENQUEUE 历史，CONSUME 在重启后仍可核验其来源。
     */
    private List<QueueCommandFact> storedQueueCommands(Connection connection,
                                                       AgentStoreTransactionContext transaction) throws SQLException {
        List<QueueCommandFact> commands = new ArrayList<>();
        String sql = """
            SELECT record_id,payload_schema_version,record_sequence,payload_json,occurred_at_ms \
            FROM agent_record WHERE tenant_id=? AND thread_id=? AND turn_id=? AND user_id=? \
            AND record_type=? ORDER BY record_sequence\
            """;
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, transaction.execution().conversation().tenantId());
            query.setString(2, transaction.execution().conversation().threadId());
            query.setString(3, transaction.execution().turnId());
            query.setString(4, transaction.execution().conversation().userId());
            query.setString(5, ExecutionControlCodec.INPUT_QUEUE_COMMAND_RECORD_TYPE);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    AgentStore.StoredRecord record = new AgentStore.StoredRecord(rows.getString(1),
                            ExecutionControlCodec.INPUT_QUEUE_COMMAND_RECORD_TYPE, rows.getInt(2), rows.getLong(3),
                            new JacksonJsonValueCodec().decodeObject(rows.getString(4)), Instant.ofEpochMilli(rows.getLong(5)));
                    AgentInputQueue.Command command = controls.decodeInputQueueCommand(record);
                    if (!record.recordId().equals(command.commandId())) {
                        throw new SQLException("Ja input queue command record identity differs from its payload");
                    }
                    commands.add(new QueueCommandFact(command));
                }
            } catch (RuntimeException invalid) {
                throw new SQLException("Ja input queue history is invalid", invalid);
            }
        }
        return commands;
    }

    /**
     * 读取 queue ENQUEUE 引用的待消费正文；优先使用当前事务 write，随后才查已提交 Store 记录。
     */
    private AgentStore.StoredRecord persistedQueuedMessage(Connection connection,
                                                           AgentStoreTransactionContext transaction, String recordId) throws SQLException {
        for (int index = 0; index < transaction.records().size(); index++) {
            AgentStore.RecordWrite record = transaction.records().get(index);
            if (record.recordId().equals(recordId)) {
                return stored(record, index + 1L);
            }
        }
        return readStoredRecord(connection, transaction, recordId,
                "Ja queued MESSAGE body is missing", "Ja queued MESSAGE body identity is not unique");
    }

    /**
     * Decoder 异常不降级成普通输入，保证 malformed queue bodies 阻断同一 Store commit。
     */
    private UserMessage decodeQueuedMessage(AgentStore.StoredRecord record) throws SQLException {
        try {
            return facts.decodeQueuedUser(record);
        } catch (RuntimeException invalid) {
            throw new SQLException("Ja queued MESSAGE body is invalid", invalid);
        }
    }

    /**
     * 消费前必须证明同一事务的 USER fact 正好承接了待消费正文，而不是只推进 Mailbox 状态。
     */
    private void verifyConsumedUserFact(AgentStoreTransactionContext transaction, UserMessage queued)
            throws SQLException {
        int matches = 0;
        for (AgentStore.RecordWrite record : transaction.records()) {
            if (!"agent.user.message".equals(record.recordType())) {
                continue;
            }
            try {
                UserMessage user = facts.decodeUser(stored(record, 1));
                if (queued.messageId().equals(user.messageId())) {
                    if (!queued.content().equals(user.content())) {
                        throw new SQLException("Ja consumed USER fact differs from its is_queued body");
                    }
                    matches++;
                }
            } catch (RuntimeException invalid) {
                throw new SQLException("Ja consumed USER fact is invalid", invalid);
            }
        }
        if (matches != 1) {
            throw new SQLException("Ja mailbox CONSUME has no unique matching USER fact");
        }
    }

    /**
     * 同连接把 PENDING→BOUND 或 BOUND→CONSUMED 与 Store 队列记录一起提交；THREAD_MESSAGE timeline 仅在真实消费时生成。
     */
    void applyMailboxQueue(Connection connection, AgentStoreTransactionContext transaction,
                                   List<MailboxQueueProjection> projections) throws SQLException {
        for (MailboxQueueProjection projection : projections) {
            AgentInputQueue.Command command = projection.command();
            TaskModels.MailboxMessage mailbox = projection.source().mailbox();
            if (command.action() == AgentInputQueue.Action.ENQUEUE) {
                try (PreparedStatement update = connection.prepareStatement(
                        """
                            UPDATE ja_task_mailbox_message SET state='BOUND',bound_turn_id=?,updated_at_ms=? WHERE message_id=? \
                            AND root_thread_id=? AND from_thread_id=? AND to_thread_id=? AND kind=? \
                            AND content_json=? AND operation_key=? AND created_at_ms=? AND sender_title=? \
                            AND state='PENDING' AND bound_turn_id IS NULL AND consumed_at_ms IS NULL\
                            """)) {
                    update.setString(1, transaction.execution().turnId());
                    update.setLong(2, command.occurredAt().toEpochMilli());
                    update.setString(3, mailbox.messageId());
                    update.setString(4, mailbox.rootThreadId());
                    update.setString(5, mailbox.senderThreadId());
                    update.setString(6, mailbox.targetThreadId());
                    update.setString(7, mailbox.kind().name());
                    update.setString(8, messages.writeUserContent(mailbox.content()));
                    update.setString(9, mailbox.operationKey());
                    update.setLong(10, mailbox.createdAt().toEpochMilli());
                    update.setString(11, mailbox.senderTitle());
                    requireOne(update.executeUpdate(), "Ja mailbox PENDING binding CAS failed");
                }
            } else if (command.action() == AgentInputQueue.Action.CONSUME) {
                consumeMailboxMessage(connection, transaction, command, projection.source());
            } else {
                throw new SQLException("unsupported Ja mailbox queue mutation");
            }
        }
    }

    /**
     * 队列重放只依赖已严格校验 commandId 与 recordId 的中立命令。
     *
     * @param command 命令
     */
    record QueueCommandFact(AgentInputQueue.Command command) {
    }

    /**
     * ENQUEUE 中携带的 Ja 源身份；该元数据由 mailbox 入队 mapper 一次性冻结。
     *
     * @param messageId 消息标识
     * @param kind 类型分类
     * @param senderThreadId 投递消息的来源对话标识
     * @param targetThreadId 投递消息的目标对话标识
     * @param causalTurnId 触发当前事实的执行标识
     * @param senderTitle 发送方标题
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param createdAt 创建时间
     */
    private record MailboxMetadata(String messageId,
                                   String kind,
                                   String senderThreadId,
                                   String targetThreadId,
                                   String causalTurnId,
                                   String senderTitle,
                                   String operationKey,
                                   Instant createdAt) {
    }

    /**
     * 已与通用 queued USER body 严格比对的邮箱快照。
     *
     * @param mailbox 收件箱
     * @param queuedRecordId 已排队记录标识
     * @param queuedMessage 已排队消息
     */
    record MailboxSource(TaskModels.MailboxMessage mailbox,
                         String queuedRecordId,
                         UserMessage queuedMessage) {
    }

    /**
     * 一次 Store queue 写入需要原子应用的 Ja mailbox 边界。
     *
     * @param command 命令
     * @param source 来源
     */
    record MailboxQueueProjection(AgentInputQueue.Command command,
                                  MailboxSource source) {
    }
}
