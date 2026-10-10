// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.infrastructure.persistence.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.conversation.UserMessage;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.JdbcTransactionResources;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.PersistenceCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.conversation.domain.model.JaMessageIds;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadTitlePolicy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 将输入队列与附件关系加入执行提交，数据库仅保存展示投影，不成为第二套执行队列。
 */
public final class JaInputProjection {
    private static final long MAX_INPUT_ATTACHMENT_BYTES = 250L * 1024 * 1024;
    private final StorePayloadCodec facts;
    private final ControlPayloadCodec controls;
    private final PersistenceCodec contents;

    /**
     * 与 Ja 主参与者共用严格正文 codec，不解析自造的备用输入格式。
     */
    public JaInputProjection(ObjectMapper mapper) {
        facts = new StorePayloadCodec(Objects.requireNonNull(mapper, "mapper"));
        controls = new ControlPayloadCodec(mapper);
        contents = new PersistenceCodec(mapper);
    }

    /**
     * 只有已观察的输入版本能够改变投影；固定 SQL 片段来自上方闭集，参数始终单独绑定。
     */
    private static void mutate(Connection connection, String turn, AgentInputQueue.Command command,
                               String assignment, Object... values) throws SQLException {
        List<Object> arguments = new ArrayList<>(List.of(values));
        arguments.add(command.occurredAt().toEpochMilli());
        arguments.add(turn);
        arguments.add(command.inputId());
        arguments.add(command.expectedInputRevision());
        requireOne(execute(connection, "UPDATE ja_turn_input SET " + assignment
                                       + """
                                           ,input_revision=input_revision+1,updated_at_ms=? WHERE turn_id=? AND \
                                           input_id=? \
                                           AND state='PENDING' AND input_revision=?\
                                           """, arguments.toArray()), "Ja input projection CAS failed");
    }

    /**
     * 排队只占有草稿，不提前创建可见消息；更新允许保留同一 input 已占有的附件。
     */
    private static void reserve(Connection connection, Owner owner, String input, UserContent content,
                                Instant at) throws SQLException {
        List<Attachment> attachments = validateAttachments(connection, owner, content.attachmentIds(), input, null, at);
        execute(connection, "DELETE FROM ja_turn_input_attachment WHERE input_id=?", input);
        for (int ordinal = 0; ordinal < attachments.size(); ordinal++) {
            requireOne(execute(connection, """
                INSERT INTO ja_turn_input_attachment(input_id,attachment_id,ordinal,created_at_ms) \
                VALUES(?,?,?,?)\
                """, input, attachments.get(ordinal).id(), ordinal, at.toEpochMilli()), "attachment reservation failed");
        }
    }

    /**
     * USER 消息存在后再绑定，任何附件失效都回滚消息、队列与全部已处理附件。
     */
    private static void bind(Connection connection, AgentStoreTransactionContext transaction, Owner owner,
                             UserMessage user, String reservedInput) throws SQLException {
        String message = JaMessageIds.toItemId(user.messageId());
        List<Attachment> attachments = validateAttachments(connection, owner, user.content().attachmentIds(), reservedInput,
                message, transaction.occurredAt());
        if (reservedInput != null)
            {
                execute(connection, "DELETE FROM ja_turn_input_attachment WHERE input_id=?", reservedInput);
            }
        for (int ordinal = 0; ordinal < attachments.size(); ordinal++) {
            Attachment attachment = attachments.get(ordinal);
            if (attachment.existingRelation()) {
                continue;
            }
            if (attachment.draft()) {
                requireOne(execute(connection, """
                UPDATE ja_attachment SET status='BOUND',bound_at_ms=? \
                WHERE attachment_id=? AND status='DRAFT'\
                """, transaction.occurredAt().toEpochMilli(), attachment.id()),
                    "attachment binding changed");
            }
            requireOne(execute(connection, """
                INSERT INTO ja_message_attachment(message_id,attachment_id,ordinal,created_at_ms) \
                VALUES(?,?,?,?)\
                """, message, attachment.id(), ordinal, transaction.occurredAt().toEpochMilli()), "attachment relation failed");
        }
    }

    /**
     * 草稿和来源关系共同鉴权；用户可修正的附件失效归为 CAS 冲突，不能误报数据库不可用并诱导重试。
     */
    private static List<Attachment> validateAttachments(Connection connection, Owner owner, List<String> ids,
                                                        String input, String message, Instant at) throws SQLException {
        List<Attachment> result = new ArrayList<>();
        long bytes = 0;
        for (String id : ids) {
            try (PreparedStatement query = statement(connection, """
                SELECT a.display_name,a.size_bytes,a.status,a.expires_at_ms,\
                a.blob_sha256,(SELECT input_id FROM ja_turn_input_attachment WHERE attachment_id=a.attachment_id),\
                EXISTS(SELECT 1 FROM ja_message_attachment WHERE attachment_id=a.attachment_id AND message_id=?),\
                EXISTS(SELECT 1 FROM ja_message_attachment ma JOIN ja_conversation_message m ON m.message_id=ma.message_id \
                WHERE ma.attachment_id=a.attachment_id AND ma.message_id=? AND m.thread_id=?) \
                FROM ja_attachment a WHERE a.attachment_id=? AND a.workspace_id=?\
                """,
                    message, owner.sourceMessageId(), owner.threadId(), id, owner.workspaceId()); ResultSet rows = query.executeQuery()) {
                if (!rows.next() || rows.getString(5) == null) {
                    throw new StorageException(StorageException.Code.CAS_CONFLICT, "附件已失效，请重新选择");
                }
                boolean draft = "DRAFT".equals(rows.getString(3));
                boolean existing = rows.getBoolean(7);
                boolean authorizedBound = message != null && "BOUND".equals(rows.getString(3)) && (existing || rows.getBoolean(8));
                String reservation = rows.getString(6);
                boolean ownedDraft = draft && (reservation == null
                        ? Instant.ofEpochMilli(rows.getLong(4)).isAfter(at) : reservation.equals(input));
                if (!authorizedBound && !ownedDraft) {
                    throw new StorageException(StorageException.Code.CAS_CONFLICT,
                            "附件已失效或不属于当前输入，请重新选择");
                }
                bytes = Math.addExact(bytes, rows.getLong(2));
                if (bytes > MAX_INPUT_ATTACHMENT_BYTES) {
                    throw new StorageException(StorageException.Code.CAS_CONFLICT,
                            "本次输入的附件总大小不能超过 250 MiB");
                }
                result.add(new Attachment(id, draft, existing));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 编辑和删除只能丢弃本条输入拥有的草稿，保留项稍后按新顺序重建 reservation。
     */
    private static void removeReservations(Connection connection, String input, List<String> retained,
                                           long at) throws SQLException {
        List<String> removed = new ArrayList<>();
        try (PreparedStatement query = statement(connection, "SELECT attachment_id FROM ja_turn_input_attachment WHERE input_id=?", input);
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                if (!retained.contains(rows.getString(1))) {
                removed.add(rows.getString(1));
            }
            }
        }
        for (String id : removed) {
            requireOne(execute(connection, """
                UPDATE ja_attachment SET status='DISCARDED',discarded_at_ms=?,blob_sha256=NULL \
                WHERE attachment_id=? AND status='DRAFT'\
                """, at, id), "reserved attachment was changed");
            requireOne(execute(connection, "DELETE FROM ja_turn_input_attachment WHERE input_id=? AND attachment_id=?",
                    input, id), "attachment reservation was changed");
        }
    }

    /**
     * 终态一次性释放尚未消费的附件和普通输入，已经绑定的消息与真实业务事实保持原样。
     */
    private static void releaseTerminal(Connection connection, AgentStoreTransactionContext transaction) throws SQLException {
        List<String> inputs = new ArrayList<>();
        try (PreparedStatement query = statement(connection, "SELECT input_id FROM ja_turn_input WHERE turn_id=? AND state='PENDING'",
                transaction.execution().turnId()); ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                inputs.add(rows.getString(1));
            }
        }
        for (String input : inputs)
            {
                removeReservations(connection, input, List.of(), transaction.occurredAt().toEpochMilli());
            }
        execute(connection, """
            UPDATE ja_turn_input SET state='CANCELLED',input_revision=input_revision+1,validation_status='PENDING',\
            issue_error_code=NULL,issue_message=NULL,is_issue_retryable=NULL,updated_at_ms=?,resolved_at_ms=? WHERE turn_id=? \
            AND state='PENDING'\
            """,
                transaction.occurredAt().toEpochMilli(), transaction.occurredAt().toEpochMilli(), transaction.execution().turnId());
        requireOne(execute(connection, "UPDATE ja_conversation_turn SET is_accepting_inputs=0 WHERE turn_id=?", transaction.execution().turnId()),
                "terminal input owner is missing");
    }

    /**
     * 首条真实用户输入生成即时占位标题，人工或自动标题不会被后来输入覆盖。
     */
    private static void projectInitialTitle(Connection connection, AgentStoreTransactionContext transaction,
                                            Owner owner, UserMessage user) throws SQLException {
        String message = JaMessageIds.toItemId(user.messageId());
        execute(connection, "UPDATE ja_conversation_turn SET source_message_id=? WHERE turn_id=? AND source_message_id IS NULL",
                message, transaction.execution().turnId());
        List<String> names = new ArrayList<>();
        try (PreparedStatement query = statement(connection, """
            SELECT a.display_name FROM ja_message_attachment ma \
            JOIN ja_attachment a ON a.attachment_id=ma.attachment_id WHERE ma.message_id=? ORDER BY ma.ordinal\
            """, message);
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        String title = ThreadTitlePolicy.provisionalTitle(user.content().text(), names);
        if (!title.isBlank())
            {
                execute(connection, """
                UPDATE ja_conversation_thread SET title=? WHERE thread_id=? AND title_source='PLACEHOLDER' \
                AND NOT EXISTS(SELECT 1 FROM ja_conversation_turn WHERE thread_id=? AND turn_id<>?)\
                """, title, owner.threadId(),
                    owner.threadId(), transaction.execution().turnId());
            }
    }

    /**
     * 只读取同事务已创建的 Ja 所有权行，不能从调用方自由元数据猜 Workspace 权限。
     */
    private static Owner owner(Connection connection, AgentStoreTransactionContext transaction) throws SQLException {
        try (PreparedStatement query = statement(connection, """
            SELECT t.workspace_id,r.source_message_id FROM ja_conversation_turn r \
            JOIN ja_conversation_thread t ON t.thread_id=r.thread_id WHERE r.turn_id=? AND r.thread_id=?\
            """,
                transaction.execution().turnId(), transaction.execution().conversation().threadId());
             ResultSet rows = query.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("Ja input owner is missing");
            }
            return new Owner(transaction.execution().conversation().threadId(), rows.getString(1), rows.getString(2));
        }
    }

    /**
     * 单次 UPDATE 的资源在返回前释放，连接生命周期始终归外层 Store。
     */
    private static int execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = statement(connection, sql, values)) {
            return statement.executeUpdate();
        }
    }

    /**
     * 仅创建参数化语句；绑定失败立即关闭，成功后的关闭职责转移到调用方 try-with-resources。
     */
    private static PreparedStatement statement(Connection connection, String sql, Object... values) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            return statement;
        } catch (SQLException | RuntimeException failure) {
            try {
                statement.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /**
     * 精确一行是投影 CAS 的必要结果，零行和多行都须回滚整个执行提交。
     */
    private static void requireOne(int changed, String message) throws SQLException {
        if (changed != 1) {
            throw new SQLException(message);
        }
    }

    /**
     * 解码事务内记录时使用局部非零序号，不伪造数据库的全局恢复游标。
     */
    private static AgentStore.StoredRecord stored(AgentStore.RecordWrite record) {
        return new AgentStore.StoredRecord(record.recordId(), record.recordType(), record.payloadSchemaVersion(),
                1, record.payload(), record.occurredAt());
    }

    /**
     * USER 投影后借用 Store 的同一事务连接；提交、回滚与关闭均归 Store，参与者不得提前关闭。
     */
    @SuppressWarnings("PMD.CloseResource")
    public void apply(AgentStoreTransactionContext transaction) throws SQLException {
        if (!"ja.local".equals(transaction.execution().conversation().tenantId())) {
            throw new SQLException("Ja input projection received a foreign tenant");
        }
        Connection connection = JdbcTransactionResources.connection(transaction);
        Owner owner = owner(connection, transaction);
        List<UserMessage> users = new ArrayList<>();
        List<AgentInputQueue.Command> commands = new ArrayList<>();
        for (AgentStore.RecordWrite record : transaction.records()) {
            if (record.recordType().equals("agent.user.message")) {
                users.add(facts.decodeUser(stored(record)));
            }
            if (record.recordType().equals(ExecutionControlCodec.INPUT_QUEUE_COMMAND_RECORD_TYPE)) {
                commands.add(controls.decodeInputQueueCommand(record));
            }
        }
        Set<String> consumed = new HashSet<>();
        for (AgentInputQueue.Command command : commands) {
            if (queueKind(connection, transaction, command.inputId()) != AgentInputQueue.Kind.MESSAGE) {
                projectCommand(connection, transaction, owner, command, users, consumed);
            }
            requireOne(execute(connection, """
                UPDATE ja_conversation_turn SET input_queue_revision=? WHERE turn_id=? \
                AND input_queue_revision=?\
                """, command.queueRevision(), transaction.execution().turnId(),
                    command.queueRevision() - 1), "Ja queue revision is not contiguous");
        }
        for (UserMessage user : users) {
            if (!consumed.contains(user.messageId())) {
                bind(connection, transaction, owner, user, null);
            }
        }
        if (transaction.kind() == AgentStore.TransactionKind.ADMISSION && !users.isEmpty()) {
            projectInitialTitle(connection, transaction, owner, users.getFirst());
        }
        if (TurnState.valueOf(transaction.state()).terminal()) {
            releaseTerminal(connection, transaction);
        }
    }

    /**
     * 重放已验证的命令并镜像条目 CAS；MESSAGE 由 mailbox 参与者处理，不能混入 Composer 队列。
     */
    private void projectCommand(Connection connection, AgentStoreTransactionContext transaction, Owner owner,
                                AgentInputQueue.Command command, List<UserMessage> users, Set<String> consumed) throws SQLException {
        String turn = transaction.execution().turnId();
        long at = command.occurredAt().toEpochMilli();
        if (command.action() == AgentInputQueue.Action.ENQUEUE) {
            UserMessage body = queuedBody(connection, transaction, command.messageRecordId());
            requireOne(execute(connection, """
                INSERT INTO ja_turn_input(input_id,thread_id,turn_id,kind,content_json,\
                state,validation_status,input_revision,priority_sequence,created_at_ms,updated_at_ms) \
                VALUES(?,?,?,?,?,'PENDING','PENDING',1,CASE WHEN ?='STEERING' THEN \
                (SELECT COALESCE(MAX(priority_sequence)+1,1) FROM ja_turn_input WHERE turn_id=?) ELSE NULL END,?,?)\
                """,
                    command.inputId(), transaction.execution().conversation().threadId(), turn, command.kind().name(),
                    contents.writeUserContent(body.content()), command.kind().name(), turn, at, at), "Ja input insert failed");
            reserve(connection, owner, command.inputId(), body.content(), command.occurredAt());
            return;
        }
        UserContent previous = pendingContent(connection, turn, command);
        switch (command.action()) {
            case UPDATE -> {
                UserMessage next = queuedBody(connection, transaction, command.messageRecordId());
                removeReservations(connection, command.inputId(), next.content().attachmentIds(), at);
                reserve(connection, owner, command.inputId(), next.content(), command.occurredAt());
                mutate(connection, turn, command, """
                    content_json=?,validation_status='PENDING',issue_error_code=NULL,\
                    issue_message=NULL,is_issue_retryable=NULL\
                    """, contents.writeUserContent(next.content()));
            }
            case DELETE -> {
                removeReservations(connection, command.inputId(), List.of(), at);
                mutate(connection, turn, command, """
                    state='CANCELLED',resolved_at_ms=?,validation_status='PENDING',\
                    issue_error_code=NULL,issue_message=NULL,is_issue_retryable=NULL\
                    """, at);
            }
            case CONSUME -> {
                UserMessage source = lastQueuedBody(connection, transaction, command.inputId());
                UserMessage user = users.stream().filter(value -> value.messageId().equals(source.messageId()))
                        .findFirst().orElseThrow(() -> new SQLException("input consumption has no matching USER fact"));
                if (!previous.equals(user.content()) || !source.content().equals(user.content())) {
                    throw new SQLException("consumed input differs from its committed body");
                }
                bind(connection, transaction, owner, user, command.inputId());
                consumed.add(user.messageId());
                mutate(connection, turn, command, """
                    state='CONSUMED',resolved_at_ms=?,validation_status='PENDING',\
                    issue_error_code=NULL,issue_message=NULL,is_issue_retryable=NULL\
                    """, at);
            }
            case PRIORITIZE -> mutate(connection, turn, command, """
                kind='STEERING',priority_sequence=\
                (SELECT COALESCE(MAX(priority_sequence)+1,1) FROM ja_turn_input WHERE turn_id=?)\
                """, turn);
            case NEEDS_ATTENTION -> mutate(connection, turn, command,
                    "validation_status='NEEDS_ATTENTION',issue_error_code=?,issue_message=?,issue_retryable=?",
                    command.issue().code(), command.issue().message(), command.issue().retryable() ? 1 : 0);
            case RETRY -> mutate(connection, turn, command, """
                validation_status='PENDING',issue_error_code=NULL,\
                issue_message=NULL,is_issue_retryable=NULL\
                """);
            case ENQUEUE -> throw new SQLException("enqueue was already handled");
        }
    }

    /**
     * 当前队列内容只用于校验与展示；恢复资格仍以 Core 命令流为准。
     */
    private UserContent pendingContent(Connection connection, String turn, AgentInputQueue.Command command) throws SQLException {
        try (PreparedStatement query = statement(connection, """
            SELECT content_json FROM ja_turn_input \
            WHERE turn_id=? AND input_id=? AND state='PENDING' AND input_revision=?\
            """,
                turn, command.inputId(), command.expectedInputRevision()); ResultSet rows = query.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("Ja input projection is missing or stale");
            }
            return contents.readUserContent(rows.getString(1));
        }
    }

    /**
     * 最初 ENQUEUE 冻结输入类别，之后的无类别命令不能被误判成普通用户输入。
     */
    private AgentInputQueue.Kind queueKind(Connection connection, AgentStoreTransactionContext transaction,
                                           String input) throws SQLException {
        AgentInputQueue.Command original = storedCommand(connection, transaction, input, true);
        return Objects.requireNonNull(original.kind(), "initial queue kind");
    }

    /**
     * 消费必须找回最后一次已提交的正文替换，不能靠内容相似度或列表位置推测身份。
     */
    private UserMessage lastQueuedBody(Connection connection, AgentStoreTransactionContext transaction,
                                       String input) throws SQLException {
        return queuedBody(connection, transaction, storedCommand(connection, transaction, input, false).messageRecordId());
    }

    /**
     * 使用 Store 已写入的有限操作闭集定位该条目的来源，完整租户/会话/执行条件防止跨边界引用。
     */
    private AgentInputQueue.Command storedCommand(Connection connection, AgentStoreTransactionContext transaction,
                                                  String input, boolean initial) throws SQLException {
        String action = initial ? "json_extract(payload_json,'$.action')='ENQUEUE'"
                : "json_extract(payload_json,'$.action') IN ('ENQUEUE','UPDATE')";
        try (PreparedStatement query = statement(connection, """
            SELECT record_id,payload_schema_version,record_sequence,\
            payload_json,occurred_at_ms FROM agent_record WHERE tenant_id=? AND thread_id=? \
            AND turn_id=? AND record_type=? AND json_extract(payload_json,'$.inputId')=? AND \
            """ + action
                                                             + " ORDER BY record_sequence DESC LIMIT 1", transaction.execution().conversation().tenantId(),
                transaction.execution().conversation().threadId(), transaction.execution().turnId(),
                ExecutionControlCodec.INPUT_QUEUE_COMMAND_RECORD_TYPE, input); ResultSet rows = query.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("input command source is missing");
            }
            return controls.decodeInputQueueCommand(new AgentStore.StoredRecord(rows.getString(1),
                    ExecutionControlCodec.INPUT_QUEUE_COMMAND_RECORD_TYPE, rows.getInt(2), rows.getLong(3),
                    new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject(rows.getString(4)), Instant.ofEpochMilli(rows.getLong(5))));
        }
    }

    /**
     * 正文必须确实是该执行的待消费事实，不能引用任意历史消息作为新入队来源。
     */
    private UserMessage queuedBody(Connection connection, AgentStoreTransactionContext transaction,
                                   String recordId) throws SQLException {
        try (PreparedStatement query = statement(connection, """
            SELECT payload_schema_version,record_sequence,payload_json,\
            occurred_at_ms FROM agent_record WHERE tenant_id=? AND thread_id=? AND turn_id=? \
            AND record_type='agent.input.message' AND record_id=?\
            """, transaction.execution().conversation().tenantId(),
                transaction.execution().conversation().threadId(), transaction.execution().turnId(), recordId);
             ResultSet rows = query.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("queued input body is missing");
            }
            return facts.decodeQueuedUser(new AgentStore.StoredRecord(recordId, "agent.input.message", rows.getInt(1),
                    rows.getLong(2), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject(rows.getString(3)), Instant.ofEpochMilli(rows.getLong(4))));
        }
    }

    /**
     * 所有权只在参与者当前调用内使用，不持有连接或扩大读取授权。
     *
     * @param threadId 所属对话的稳定标识
     * @param workspaceId 工作区稳定标识
     * @param sourceMessageId 来源消息标识
     */
    private record Owner(String threadId,
                         String workspaceId,
                         String sourceMessageId) {
    }

    /**
     * 验证结果仅保留绑定所需字段，避免把附件正文或存储路径带出授权查询。
     *
     * @param id 当前实体的稳定标识
     * @param draft 是否为草稿
     * @param existingRelation 是否已有该关联
     */
    private record Attachment(String id,
                              boolean draft,
                              boolean existingRelation) {
    }
}

