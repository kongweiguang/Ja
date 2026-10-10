// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.store.pg.infrastructure;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant;
import io.github.kongweiguang.ja.agent.application.gateway.store.ConversationTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.ConversationTransactionParticipant;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.TransactionResource;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.infrastructure.json.JsonValueCodecFactory;
import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PostgreSQL 以行锁和事务 CAS 履行统一存储契约；fencing 拒绝过期节点，连接池仍由宿主管理。
 */
public final class PostgresAgentStore implements AgentStore {
    private static final int SCHEMA_VERSION = 1;
    private static final List<String> SCHEMA_TABLES = List.of("agent_schema_version", "agent_conversation",
            "agent_execution", "agent_record", "agent_event", "agent_operation_receipt",
            "agent_commit_receipt", "agent_lease");
    private static final String SCHEMA_RESOURCE = "/io/github/kongweiguang/ja/agent/store/pg/infrastructure/schema.sql";
    private static final String POSTGRES_NOW_MS = "CAST(EXTRACT(EPOCH FROM clock_timestamp()) * 1000 AS BIGINT)";
    private static final JsonValueCodec JSON = JsonValueCodecFactory.standard();

    private final DataSource dataSource;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 构造只校验宿主已初始化的 schema，避免服务装配期间产生隐式数据库写入。
     */
    public PostgresAgentStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        verifyOwnedSchema();
    }

    /**
     * 独立宿主显式创建Core schema；Skill扩展自有命名空间可共库，旧Core或缺失marker的部分结构仍拒绝。
     */
    public static void bootstrap(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getAutoCommit()) {
                throw new AgentStore.StoreUnavailableException(
                        "PostgreSQL DataSource returned a transaction-owned connection", null);
            }
            connection.setAutoCommit(false);
            try {
                Set<String> tables = tableNames(connection);
                boolean existing = tables.stream().anyMatch(PostgresAgentStore::isOwnedTableName);
                if (existing) {
                    if (tables.stream().anyMatch(name -> name.startsWith("ja_agent_store_"))) {
                        throw new AgentStore.StoreIntegrityException("legacy AgentStore schema is unsupported", null);
                    }
                    verifySchema(connection);
                } else {
                    initializeSchema(connection);
                }
                connection.commit();
            } catch (RuntimeException | Error | SQLException failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) {
            throw unavailable("could not bootstrap PostgreSQL AgentStore", failure);
        }
    }

    /**
     * 执行前建立会话元数据锚点，并与 Ja 创建行同事务落库，避免首次 Turn 前的线程操作失去 CAS 保护。
     */
    @Override
    public ConversationSnapshot initializeConversation(ConversationInitialization request,
                                                       ConversationTransactionParticipant participant) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(participant, "participant");
        return write(connection -> {
            if (findConversation(connection, request.conversation()).isPresent()) {
                throw new AgentStore.StoreConflictException("conversation already exists");
            }
            ensureConversationRow(connection, request.conversation(), 0, request.occurredAt().toEpochMilli());
            participant.apply(new ConversationTransactionContext(TransactionResource.of(connection), request.conversation(), -1, 0,
                    request.occurredAt()));
            return requireConversation(connection, request.conversation()).snapshot();
        });
    }

    /**
     * 会话元数据按 revision CAS 更新，不伪造 Agent 执行记录来承载 rename 或偏好变化。
     */
    @Override
    public ConversationSnapshot reviseConversation(ConversationRevision request,
                                                    ConversationTransactionParticipant participant) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(participant, "participant");
        if (request.expectedRevision() == Long.MAX_VALUE) {
            throw new IllegalArgumentException("conversation revision is exhausted");
        }
        return write(connection -> {
            String sql = """
                UPDATE agent_conversation SET revision = revision + 1, updated_at_ms = ? \
                WHERE tenant_id = ? AND thread_id = ? AND revision = ? AND user_id = ?\
                """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, request.occurredAt().toEpochMilli());
                bindConversation(statement, 2, request.conversation());
                statement.setLong(4, request.expectedRevision());
                statement.setString(5, request.conversation().userId());
                if (statement.executeUpdate() != 1) {
                    throw new AgentStore.StoreConflictException("conversation revision changed concurrently");
                }
            }
            ConversationSnapshot snapshot = requireConversation(connection, request.conversation()).snapshot();
            participant.apply(new ConversationTransactionContext(TransactionResource.of(connection), request.conversation(),
                    request.expectedRevision(), snapshot.revision(), request.occurredAt()));
            return snapshot;
        });
    }

    /**
     * 在调用方现有连接执行首版 DDL并校验，不提交或关闭连接，供 Flyway 在同一事务记录基线。
     */
    public static void initializeSchema(Connection connection) {
        Objects.requireNonNull(connection, "connection");
        try {
            String schema;
            try (InputStream input = PostgresAgentStore.class.getResourceAsStream(SCHEMA_RESOURCE)) {
                if (input == null) {
                    throw new IOException("PostgreSQL AgentStore schema resource is missing");
                }
                schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            try (Statement statement = connection.createStatement()) {
                for (String sql : schema.split(";")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
            }
            verifySchema(connection);
        } catch (IOException | SQLException failure) {
            throw new AgentStore.StoreUnavailableException("could not initialize PostgreSQL AgentStore schema", failure);
        }
    }

    /**
     * 准入事件使用本事务分配的 revision、初始 mutation 与 record 水位，不能在回放时借用后续快照。
     */
    @Override
    public AdmissionReceipt admit(Admission request, AgentStoreTransactionParticipant participant) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(participant, "participant");
        return write(connection -> {
            Optional<OperationReceipt> existing = readOperationReceipt(connection,
                    new OperationKey(request.execution().conversation().tenantId(),
                            request.execution().conversation().userId(), request.operation().operationKey()));
            if (existing.isPresent()) {
                return replayAdmission(existing.get(), request);
            }

            ConversationKey conversation = request.execution().conversation();
            long now = request.occurredAt().toEpochMilli();
            ensureConversationRow(connection, conversation, request.expectedThreadRevision(), now);
            advanceConversation(connection, conversation, request.expectedThreadRevision(),
                    request.records().size(), request.events().size(), 1, now);
            ConversationRow updatedConversation = requireConversation(connection, conversation);
            long admissionSequence = updatedConversation.latestExecutionSequence();
            long mutationVersion = 1;
            long firstRecord = updatedConversation.recordSequence() - request.records().size();
            insertExecution(connection, request, admissionSequence, updatedConversation.recordSequence(), now);
            insertRecords(connection, request.execution(), firstRecord, request.records());
            long firstEvent = firstSequence(updatedConversation.eventSequence(), request.events().size());
            insertEvents(connection, request.execution(), firstEvent, updatedConversation.revision(), mutationVersion,
                    updatedConversation.recordSequence(), request.events());
            long lastEvent = request.events().isEmpty() ? 0 : updatedConversation.eventSequence();
            AdmissionReceipt receipt = new AdmissionReceipt(updatedConversation.revision(), mutationVersion,
                    firstEvent, lastEvent, request.operation().receipt(), true);
            insertOperationReceipt(connection, request.execution(), request.operation(), receipt.threadRevision(),
                    receipt.mutationVersion(), receipt.firstEventSequence(), receipt.lastEventSequence(), now);
            participant.apply(context(connection, request, receipt, TransactionKind.ADMISSION, 0, -1, null, null));
            return receipt;
        });
    }

    /**
     * 仅在租约代际、会话 revision 和执行 mutation version 均匹配时提交，拒绝旧节点继续写入。
     */
    @Override
    public CommitReceipt commit(ExecutionCommit request, AgentStoreTransactionParticipant participant) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(participant, "participant");
        return write(connection -> commitInside(connection, request, participant, TransactionKind.COMMIT, null, null));
    }

    /**
     * 等待可以只提交答案；若本次确实产生工具结果，则答案、结果与游标仍共用一次事务。
     */
    @Override
    public CommitReceipt settleWait(WaitSettlement request, AgentStoreTransactionParticipant participant) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(participant, "participant");
        return write(connection -> commitInside(connection, request.transition(), participant,
                TransactionKind.WAIT_SETTLEMENT, request.interactionId(), request.answerId()));
    }

    /** 提交不驱动执行游标的控制事实；不读取或续租 Runner lease，CAS 保护并发控制与执行提交。 */
    @Override
    public CommitReceipt controlCommit(ControlCommit request, AgentStoreTransactionParticipant participant) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(participant, "participant");
        return write(connection -> controlCommitInside(connection, request, participant));
    }

    /**
     * 先取会话版本与水位，历史必须显式分页，避免大型会话加载造成内存峰值。
     */
    @Override
    public Optional<ConversationSnapshot> loadConversation(ConversationKey key) {
        Objects.requireNonNull(key, "key");
        return read(connection -> findConversation(connection, key).map(ConversationRow::snapshot));
    }

    /**
     * 仅读执行状态和游标，记录历史分开分页以控制恢复资源占用。
     */
    @Override
    public Optional<ExecutionSnapshot> loadExecution(ExecutionKey key) {
        Objects.requireNonNull(key, "key");
        return read(connection -> findExecution(connection, key));
    }

    /**
     * 按不可变准入序号读取有限执行页，保证重启恢复顺序稳定。
     */
    @Override
    public ExecutionPage readExecutions(ConversationKey key, long afterSequence,
                                       long throughSequence, int limit) {
        Objects.requireNonNull(key, "key");
        requirePage(afterSequence, throughSequence, limit);
        return read(connection -> {
            List<ExecutionSnapshot> values = new ArrayList<>();
            String sql = """
                SELECT turn_id, admission_sequence, state, mutation_version, cursor_schema_version, \
                cursor_json, last_record_sequence, created_at_ms, updated_at_ms \
                FROM agent_execution WHERE tenant_id = ? AND thread_id = ? \
                AND admission_sequence > ? AND admission_sequence <= ? AND user_id = ? \
                ORDER BY admission_sequence LIMIT ?\
                """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindConversation(statement, 1, key);
                statement.setLong(3, afterSequence);
                statement.setLong(4, throughSequence);
                statement.setString(5, key.userId());
                statement.setInt(6, limit + 1);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        values.add(executionSnapshot(
                            new ExecutionKey(key, rows.getString(1)), rows, 1));
                    }
                }
            }
            boolean more = values.size() > limit;
            if (more) {
                values.remove(values.size() - 1);
            }
            long cursor = values.isEmpty() ? afterSequence : values.get(values.size() - 1).admissionSequence();
            return new ExecutionPage(values, cursor, more);
        });
    }

    /**
     * 按执行内序号分页读取完整类型化事实、消息、用量和 checkpoint，支持确定性恢复。
     */
    @Override
    public RecordPage readRecords(RecordQuery query) {
        Objects.requireNonNull(query, "query");
        return read(connection -> {
            List<StoredRecord> values = new ArrayList<>();
            long payloadBytes = 0;
            boolean more = false;
            String sql = """
                SELECT record_id, record_type, payload_schema_version, record_sequence, payload_json, \
                occurred_at_ms FROM agent_record WHERE tenant_id = ? AND thread_id = ? \
                AND turn_id = ? AND record_sequence > ? AND record_sequence <= ? AND user_id = ? \
                ORDER BY record_sequence LIMIT ?\
                """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindExecution(statement, 1, query.execution());
                statement.setLong(4, query.afterSequence());
                statement.setLong(5, query.throughSequence());
                statement.setString(6, query.execution().conversation().userId());
                statement.setInt(7, query.limit() + 1);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String payload = rows.getString(5);
                        long itemBytes = storedPayloadBytes(payload);
                        if (values.size() == query.limit()
                                || (!values.isEmpty() && itemBytes > query.maxBytes() - payloadBytes)) {
                            more = true;
                            break;
                        }
                        values.add(new StoredRecord(rows.getString(1), rows.getString(2),
                            rows.getInt(3), rows.getLong(4), readJson(payload), instant(rows.getLong(6))));
                        payloadBytes += itemBytes;
                    }
                }
            }
            long cursor = values.isEmpty() ? query.afterSequence() : values.get(values.size() - 1).sequence();
            return new RecordPage(values, cursor, more);
        });
    }

    /**
     * 以单一会话序号水位读取迁移前会话记录和新执行记录，不按时间猜测归属。
     */
    @Override
    public ConversationRecordPage readConversationRecords(ConversationRecordQuery query) {
        Objects.requireNonNull(query, "query");
        return read(connection -> {
            List<ConversationRecord> values = new ArrayList<>();
            long payloadBytes = 0;
            boolean more = false;
            String sql = """
                SELECT turn_id, record_id, record_type, payload_schema_version, record_sequence, \
                payload_json, occurred_at_ms FROM agent_record WHERE tenant_id = ? \
                AND thread_id = ? AND record_sequence > ? AND record_sequence <= ? AND user_id = ? \
                ORDER BY record_sequence LIMIT ?\
                """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindConversationPage(statement, query.conversation(), query.afterSequence(),
                        query.throughSequence(), query.limit());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String payload = rows.getString(6);
                        long itemBytes = storedPayloadBytes(payload);
                        if (values.size() == query.limit()
                                || (!values.isEmpty() && itemBytes > query.maxBytes() - payloadBytes)) {
                            more = true;
                            break;
                        }
                        ExecutionKey execution = new ExecutionKey(query.conversation(), rows.getString(1));
                        values.add(new ConversationRecord(query.conversation(), execution, rows.getString(2),
                                rows.getString(3), rows.getInt(4), rows.getLong(5), readJson(payload),
                                instant(rows.getLong(7))));
                        payloadBytes += itemBytes;
                    }
                }
            }
            long cursor = values.isEmpty() ? query.afterSequence() : values.getLast().sequence();
            return new ConversationRecordPage(values, cursor, more);
        });
    }

    /**
     * 按排他事件游标读取已提交事件及其原始水位，后续写入不会改变历史恢复边界。
     */
    @Override
    public EventPage readEvents(EventQuery query) {
        Objects.requireNonNull(query, "query");
        return read(connection -> {
            List<PersistedEvent> values = new ArrayList<>();
            long payloadBytes = 0;
            boolean more = false;
            String sql = """
                SELECT turn_id, event_id, event_type, payload_schema_version, event_sequence, \
                thread_revision, mutation_version, last_record_sequence, payload_json, occurred_at_ms \
                FROM agent_event WHERE tenant_id = ? \
                AND thread_id = ? AND event_sequence > ? AND event_sequence <= ? AND user_id = ? \
                ORDER BY event_sequence LIMIT ?\
                """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindConversationPage(statement, query.conversation(), query.afterSequence(),
                        query.throughSequence(), query.limit());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String payload = rows.getString(9);
                        long itemBytes = storedPayloadBytes(payload);
                        if (values.size() == query.limit()
                                || (!values.isEmpty() && itemBytes > query.maxBytes() - payloadBytes)) {
                            more = true;
                            break;
                        }
                        ExecutionKey execution = new ExecutionKey(query.conversation(), rows.getString(1));
                        values.add(new PersistedEvent(query.conversation(), execution, rows.getString(2),
                                rows.getString(3), rows.getInt(4), rows.getLong(5), rows.getLong(6),
                                rows.getLong(7), rows.getLong(8), readJson(payload), instant(rows.getLong(10))));
                        payloadBytes += itemBytes;
                    }
                }
            }
            long cursor = values.isEmpty() ? query.afterSequence() : values.get(values.size() - 1).sequence();
            return new EventPage(values, cursor, more);
        });
    }

    /**
     * 依据租户、会话、执行和记录 ID 命中唯一索引；事件投影取正文时不物化全部历史记录。
     */
    @Override
    public Optional<StoredRecord> readRecord(ExecutionKey execution, String recordId) {
        Objects.requireNonNull(execution, "execution");
        if (recordId == null || recordId.isBlank() || recordId.length() > 256 || recordId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid recordId");
        }
        return read(connection -> {
            String sql = """
                SELECT record_id, record_type, payload_schema_version, record_sequence, payload_json, \
                occurred_at_ms FROM agent_record WHERE tenant_id = ? AND thread_id = ? \
                AND turn_id = ? AND record_id = ? AND user_id = ?\
                """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindExecution(statement, 1, execution);
                statement.setString(4, recordId);
                statement.setString(5, execution.conversation().userId());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new StoredRecord(rows.getString(1), rows.getString(2), rows.getInt(3),
                            rows.getLong(4), readJson(rows.getString(5)), instant(rows.getLong(6))));
                }
            }
        });
    }

    /**
     * 用租户级幂等身份读取原始回执，客户端超时后不会重复创建执行。
     */
    @Override
    public Optional<OperationReceipt> readReceipt(OperationKey identity) {
        Objects.requireNonNull(identity, "identity");
        return read(connection -> readOperationReceipt(connection, identity));
    }

    /**
     * 只有旧租约失效后才允许接管并递增 fencing 代际，避免同会话多节点并行执行。
     */
    @Override
    public Lease claimLease(LeaseRequest request) {
        Objects.requireNonNull(request, "request");
        return write(connection -> {
            ConversationKey key = request.conversation();
            requireConversation(connection, key);
            long now = databaseNow(connection);
            long expires = request.expiresAt().toEpochMilli();
            if (expires <= now) {
                throw new AgentStore.StoreConflictException("lease expiry is not in the future");
            }
            Optional<LeaseRow> row = findLease(connection, key, true);
            if (row.isEmpty()) {
                String insert = """
                    INSERT INTO agent_lease (tenant_id, thread_id, owner_id, \
                    fencing_token, expires_at_ms, updated_at_ms, user_id) VALUES (?, ?, ?, 1, ?, ?, ?) \
                    ON CONFLICT (tenant_id, thread_id) DO NOTHING\
                    """;
                try (PreparedStatement statement = connection.prepareStatement(insert)) {
                    bindConversation(statement, 1, key);
                    statement.setString(3, request.ownerId());
                    statement.setLong(4, expires);
                    statement.setLong(5, now);
                    statement.setString(6, key.userId());
                    if (statement.executeUpdate() == 1) {
                        return new Lease(key, request.ownerId(), 1, instant(expires));
                    }
                    row = findLease(connection, key, true);
                    if (row.isEmpty()) {
                        throw new AgentStore.StoreConflictException("conversation lease changed concurrently");
                    }
                }
            }
            LeaseRow current = row.get();
            if (current.expiresAtMillis() > now) {
                if (!current.ownerId().equals(request.ownerId())) {
                    throw new AgentStore.StoreConflictException("conversation is owned by another executor");
                }
                long extended = Math.max(current.expiresAtMillis(), expires);
                updateLeaseExpiry(connection, key, current, extended, now);
                return new Lease(key, current.ownerId(), current.fencingToken(), instant(extended));
            }
            long newFence = Math.addExact(current.fencingToken(), 1);
            String update = """
                UPDATE agent_lease SET owner_id = ?, fencing_token = ?, expires_at_ms = ?, \
                updated_at_ms = ? WHERE tenant_id = ? AND thread_id = ? AND fencing_token = ? \
                AND expires_at_ms <= ? AND user_id = ?\
                """;
            try (PreparedStatement statement = connection.prepareStatement(update)) {
                statement.setString(1, request.ownerId());
                statement.setLong(2, newFence);
                statement.setLong(3, expires);
                statement.setLong(4, now);
                bindConversation(statement, 5, key);
                statement.setLong(7, current.fencingToken());
                statement.setLong(8, now);
                statement.setString(9, key.userId());
                if (statement.executeUpdate() != 1) {
                    throw new AgentStore.StoreConflictException("conversation lease changed concurrently");
                }
            }
            return new Lease(key, request.ownerId(), newFence, instant(expires));
        });
    }

    /**
     * 仅当前有效代际可续租，旧持有者过期后不能恢复其 fencing token。
     */
    @Override
    public Lease renewLease(Lease lease, Instant expiresAt) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(expiresAt, "expiresAt");
        return write(connection -> {
            long now = databaseNow(connection);
            long expires = expiresAt.toEpochMilli();
            if (expires <= now) {
                throw new AgentStore.StoreConflictException("lease expiry is not in the future");
            }
            String sql = """
                UPDATE agent_lease SET expires_at_ms = ?, updated_at_ms = ? \
                WHERE tenant_id = ? AND thread_id = ? AND owner_id = ? AND fencing_token = ? \
                AND expires_at_ms > ? AND user_id = ?\
                """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, expires);
                statement.setLong(2, now);
                bindConversation(statement, 3, lease.conversation());
                statement.setString(5, lease.ownerId());
                statement.setLong(6, lease.fencingToken());
                statement.setLong(7, now);
                statement.setString(8, lease.conversation().userId());
                if (statement.executeUpdate() != 1) {
                    throw new AgentStore.StoreConflictException("lease is stale or has expired");
                }
            }
            return new Lease(lease.conversation(), lease.ownerId(), lease.fencingToken(), instant(expires));
        });
    }

    /**
     * 释放时匹配当前代际且保留代际行，旧节点既不能误释放新 owner，也不能让 token 复位。
     */
    @Override
    public void releaseLease(Lease lease) {
        Objects.requireNonNull(lease, "lease");
        write(connection -> {
            long now = databaseNow(connection);
            String sql = """
                UPDATE agent_lease SET expires_at_ms = 0, updated_at_ms = ? \
                WHERE tenant_id = ? AND thread_id = ? AND owner_id = ? AND fencing_token = ? AND user_id = ?\
                """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, now);
                bindConversation(statement, 2, lease.conversation());
                statement.setString(4, lease.ownerId());
                statement.setLong(5, lease.fencingToken());
                statement.setString(6, lease.conversation().userId());
                if (statement.executeUpdate() != 1) {
                    throw new AgentStore.StoreConflictException("lease is stale or already released");
                }
            }
            return null;
        });
    }

    /**
     * 请求指纹一致时返回既有回执；同键不同内容明确冲突，避免把不同操作合并。
     */
    @Override
    public Optional<CommitReceipt> reconcile(CommitIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return read(connection -> findCommitReceipt(connection, identity).map(receipt -> {
            if (!receipt.fingerprint().equals(identity.fingerprint())) {
                throw new AgentStore.StoreConflictException("commit key was reused with a different request");
            }
            return new CommitReceipt(receipt.threadRevision(), receipt.mutationVersion(),
                    receipt.firstEventSequence(), receipt.lastEventSequence(), receipt.receipt(), false);
        }));
    }

    /**
     * 关闭标记只阻止后续访问，不关闭注入的 DataSource 或业务连接池。
     */
    @Override
    public void close() {
        closed.set(true);
    }

    /**
     * 先检查租约与双版本 CAS；仅 SQL 操作产生受检异常，参与者故障由其中立运行时契约返回并回滚。
     */
    private CommitReceipt commitInside(Connection connection, ExecutionCommit request,
                                       AgentStoreTransactionParticipant participant, TransactionKind kind,
                                       String interactionId, String answerId) throws SQLException {
        Optional<CommitRow> prior = findCommitReceipt(connection, request.identity());
        if (prior.isPresent()) {
            return replayCommit(prior.get(), request.identity());
        }
        if (request.operation().isPresent()) {
            Optional<OperationReceipt> operation = readOperationReceipt(connection,
                    new OperationKey(request.execution().conversation().tenantId(),
                            request.execution().conversation().userId(),
                            request.operation().get().operationKey()));
            if (operation.isPresent()) {
                return replayOperationCommit(operation.get(), request.operation().get(), request.execution());
            }
        }

        requireLiveLease(connection, request.lease());
        long now = request.occurredAt().toEpochMilli();
        ConversationKey conversation = request.execution().conversation();
        advanceConversation(connection, conversation, request.expectedThreadRevision(),
                request.records().size(), request.events().size(), 0, now);
        ExecutionSnapshot current = requireExecution(connection, request.execution());
        ConversationRow updatedConversation = requireConversation(connection, conversation);
        long nextRecord = request.records().isEmpty() ? current.lastRecordSequence()
                : updatedConversation.recordSequence();
        String updateExecution = """
            UPDATE agent_execution SET state = ?, mutation_version = mutation_version + 1, \
            cursor_schema_version = ?, cursor_json = ?, last_record_sequence = ?, updated_at_ms = ? \
            WHERE tenant_id = ? AND thread_id = ? AND turn_id = ? AND state = ? \
            AND mutation_version = ? AND user_id = ?\
            """;
        try (PreparedStatement statement = connection.prepareStatement(updateExecution)) {
            statement.setString(1, request.state());
            statement.setInt(2, request.cursorSchemaVersion());
            statement.setString(3, writeJson(request.cursor()));
            statement.setLong(4, nextRecord);
            statement.setLong(5, now);
            bindExecution(statement, 6, request.execution());
            statement.setString(9, request.expectedState());
            statement.setLong(10, request.expectedMutationVersion());
            statement.setString(11, request.execution().conversation().userId());
            if (statement.executeUpdate() != 1) {
                throw new AgentStore.StoreConflictException("execution state or mutation version changed");
            }
        }
        long firstEvent = firstSequence(updatedConversation.eventSequence(), request.events().size());
        long firstRecord = updatedConversation.recordSequence() - request.records().size();
        long nextMutation = request.expectedMutationVersion() + 1;
        insertRecords(connection, request.execution(), firstRecord, request.records());
        insertEvents(connection, request.execution(), firstEvent, updatedConversation.revision(), nextMutation,
                nextRecord, request.events());
        long lastEvent = request.events().isEmpty() ? 0 : updatedConversation.eventSequence();
        JsonObject receiptJson = receiptJson(updatedConversation.revision(), nextMutation, firstEvent, lastEvent);
        CommitReceipt receipt = new CommitReceipt(updatedConversation.revision(), nextMutation,
                firstEvent, lastEvent, receiptJson, true);
        insertCommitReceipt(connection, request.identity(), receipt, now);
        request.operation().ifPresent(operation -> insertOperationReceiptUnchecked(connection, request.execution(),
                operation, receipt.threadRevision(), receipt.mutationVersion(),
                receipt.firstEventSequence(), receipt.lastEventSequence(), now));
        requireLiveLease(connection, request.lease());
        AgentStoreTransactionContext context = new AgentStoreTransactionContext(TransactionResource.of(connection), request.execution(),
                request.lease().fencingToken(), request.expectedThreadRevision(),
                request.expectedMutationVersion(), receipt.threadRevision(), receipt.mutationVersion(),
                receipt.firstEventSequence(), receipt.lastEventSequence(), kind, request.state(),
                request.cursorSchemaVersion(), request.cursor(), request.records(), request.events(),
                request.operation(), interactionId, answerId, request.occurredAt());
        participant.apply(context);
        requireLiveLease(connection, request.lease());
        return receipt;
    }

    /** 在单笔 PostgreSQL 事务完成 CAS；SQL 受检故障与中立参与者运行时失败都返回唯一控制层回滚。 */
    private CommitReceipt controlCommitInside(Connection connection, ControlCommit request,
                                              AgentStoreTransactionParticipant participant) throws SQLException {
        Optional<OperationReceipt> prior = readOperationReceipt(connection,
                new OperationKey(request.execution().conversation().tenantId(),
                        request.execution().conversation().userId(), request.operation().operationKey()));
        if (prior.isPresent()) {
            return replayOperationCommit(prior.orElseThrow(), request.operation(), request.execution());
        }

        long now = request.occurredAt().toEpochMilli();
        ConversationKey conversation = request.execution().conversation();
        advanceConversation(connection, conversation, request.expectedThreadRevision(),
                request.records().size(), request.events().size(), 0, now);
        ExecutionSnapshot current = requireExecution(connection, request.execution());
        String expectedState = request.expectedState();
        long expectedMutation = request.expectedMutationVersion();
        if (!current.state().equals(expectedState) || current.mutationVersion() != expectedMutation) {
            throw new AgentStore.StoreConflictException("execution state or mutation version changed");
        }

        boolean settlesWait = request.kind() == ControlKind.WAIT_SETTLEMENT;
        String committedState = settlesWait ? "SUSPENDED" : current.state();
        ConversationSnapshot updatedConversation = requireConversation(connection, conversation).snapshot();
        long nextRecord = request.records().isEmpty() ? current.lastRecordSequence()
                : updatedConversation.recordSequence();
        String updateExecution = """
            UPDATE agent_execution SET state = ?, mutation_version = mutation_version + 1, \
            cursor_schema_version = ?, cursor_json = ?, last_record_sequence = ?, updated_at_ms = ? \
            WHERE tenant_id = ? AND thread_id = ? AND turn_id = ? AND state = ? \
            AND mutation_version = ? AND user_id = ?\
            """;
        try (PreparedStatement statement = connection.prepareStatement(updateExecution)) {
            statement.setString(1, committedState);
            statement.setInt(2, current.cursorSchemaVersion());
            statement.setString(3, writeJson(current.cursor()));
            statement.setLong(4, nextRecord);
            statement.setLong(5, now);
            bindExecution(statement, 6, request.execution());
            statement.setString(9, expectedState);
            statement.setLong(10, expectedMutation);
            statement.setString(11, request.execution().conversation().userId());
            if (statement.executeUpdate() != 1) {
                throw new AgentStore.StoreConflictException("execution state or mutation version changed");
            }
        }

        long firstEvent = firstSequence(updatedConversation.eventSequence(), request.events().size());
        long firstRecord = updatedConversation.recordSequence() - request.records().size();
        long nextMutation = Math.addExact(expectedMutation, 1);
        insertRecords(connection, request.execution(), firstRecord, request.records());
        insertEvents(connection, request.execution(), firstEvent, updatedConversation.revision(), nextMutation,
                nextRecord, request.events());
        long lastEvent = updatedConversation.eventSequence();
        JsonObject receiptJson = receiptJson(updatedConversation.revision(), nextMutation, firstEvent, lastEvent);
        CommitReceipt receipt = new CommitReceipt(updatedConversation.revision(), nextMutation,
                firstEvent, lastEvent, receiptJson, true);
        insertOperationReceiptUnchecked(connection, request.execution(), request.operation(),
                receipt.threadRevision(), receipt.mutationVersion(), receipt.firstEventSequence(),
                receipt.lastEventSequence(), now);
        Optional<WaitControl> wait = request.waitControl();
        AgentStoreTransactionContext context = new AgentStoreTransactionContext(TransactionResource.of(connection), request.execution(), 0,
                request.expectedThreadRevision(), expectedMutation, receipt.threadRevision(),
                receipt.mutationVersion(), receipt.firstEventSequence(), receipt.lastEventSequence(),
                TransactionKind.CONTROL, committedState, current.cursorSchemaVersion(), current.cursor(),
                request.records(), request.events(), Optional.of(request.operation()),
                wait.map(WaitControl::interactionId).orElse(null),
                wait.flatMap(WaitControl::answerId).orElse(null), request.occurredAt());
        participant.apply(context);
        return receipt;
    }

    /**
     * 事务内完成初始记录及回执后构造上下文，让宿主映射看到本次准确准入事实。
     */
    private static AgentStoreTransactionContext context(Connection connection, Admission request, AdmissionReceipt receipt,
                                             TransactionKind kind, long fence, long expectedMutation,
                                             String interactionId, String answerId) {
        return new AgentStoreTransactionContext(TransactionResource.of(connection), request.execution(), fence, request.expectedThreadRevision(),
                expectedMutation, receipt.threadRevision(), receipt.mutationVersion(),
                receipt.firstEventSequence(), receipt.lastEventSequence(), kind, request.state(),
                request.cursorSchemaVersion(), request.cursor(), request.records(), request.events(),
                Optional.of(request.operation()), interactionId, answerId, request.occurredAt());
    }

    /**
     * 校验阶段只读取 schema，存储实例不得在构造期获得数据库初始化所有权。
     */
    private void verifyOwnedSchema() {
        try (Connection connection = openConnection()) {
            verifySchema(connection);
        } catch (SQLException failure) {
            throw unavailable("could not verify PostgreSQL AgentStore schema", failure);
        }
    }

    /**
     * 借用宿主连接池的一条连接；PG 自身的复合外键负责强制完整引用关系。
     */
    private Connection openConnection() throws SQLException {
        if (closed.get()) {
            throw new IllegalStateException("PostgreSQL AgentStore is closed");
        }
        return dataSource.getConnection();
    }

    /**
     * 只校验本模块 schema 标记，不加数据库级大锁，保持不同会话并发提交。
     */
    private static void reserveWriter(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                 SELECT schema_version FROM agent_schema_version \
                 WHERE singleton_id = 1\
                 """)) {
            if (!rows.next() || rows.getInt(1) != SCHEMA_VERSION) {
                throw new AgentStore.StoreIntegrityException("PostgreSQL AgentStore schema marker is missing", null);
            }
        }
    }

    /**
     * 存储拥有写事务；SQL 单独映射，参与者运行时故障和 Error 都先回滚再原样传播。
     */
    private <T> T write(SqlWork<T> work) {
        Objects.requireNonNull(work, "work");
        try (Connection connection = openConnection()) {
            if (!connection.getAutoCommit()) {
                throw new AgentStore.StoreUnavailableException("PostgreSQL DataSource returned a transaction-owned connection", null);
            }
            connection.setAutoCommit(false);
            try {
                reserveWriter(connection);
                T value = work.apply(connection);
                connection.commit();
                return value;
            } catch (RuntimeException | Error failure) {
                rollback(connection, failure);
                throw failure;
            } catch (SQLException failure) {
                rollback(connection, failure);
                throw translateSqlFailure("PostgreSQL AgentStore write failed", failure);
            }
        } catch (SQLException failure) {
            throw translateSqlFailure("PostgreSQL AgentStore write failed", failure);
        }
    }

    /**
     * 单连接读取完成后立即归还；内部工作只声明 SQLException，参与者运行时故障保持原样。
     */
    private <T> T read(SqlWork<T> work) {
        Objects.requireNonNull(work, "work");
        try (Connection connection = openConnection()) {
            return work.apply(connection);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (SQLException failure) {
            throw translateSqlFailure("PostgreSQL AgentStore read failed", failure);
        }
    }

    /**
     * Core固定表集合必须完整；agent_skill_由独立Skill扩展拥有，不能把合法共库误认为Core结构漂移。
     */
    private static void verifySchema(Connection connection) throws SQLException {
        Set<String> owned = new HashSet<>();
        for (String table : tableNames(connection)) {
            if (isOwnedTableName(table)) {
                owned.add(table);
            }
        }
        if (!owned.equals(Set.copyOf(SCHEMA_TABLES))) {
            throw new AgentStore.StoreIntegrityException("PostgreSQL AgentStore schema namespace is invalid", null);
        }
        PostgresSchemaContract.verify(connection);
        try (Statement statement = connection.createStatement()) {
            for (String table : SCHEMA_TABLES) {
                statement.executeQuery("SELECT 1 FROM " + table + " LIMIT 0").close();
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT schema_version FROM agent_schema_version WHERE singleton_id = 1")) {
                if (!rows.next() || rows.getInt(1) != SCHEMA_VERSION || rows.next()) {
                    throw new AgentStore.StoreIntegrityException("unsupported PostgreSQL AgentStore schema version", null);
                }
            }
        }
    }

    /**
     * Skill是独立持久化扩展且可先安装；排除它的命名空间，其余未知agent_表与旧Core前缀仍由闭集校验拒绝。
     */
    private static boolean isOwnedTableName(String name) {
        return (name.startsWith("agent_") && !name.startsWith("agent_skill_"))
                || name.startsWith("ja_agent_store_");
    }

    /**
     * 读取当前 schema 的表名闭集，bootstrap 只对空 Core 命名空间执行 DDL。
     */
    private static Set<String> tableNames(Connection connection) throws SQLException {
        Set<String> tables = new HashSet<>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rows = metadata.getTables(connection.getCatalog(), connection.getSchema(), "%",
                new String[]{"TABLE"})) {
            while (rows.next()) {
                String name = rows.getString("TABLE_NAME");
                if (name != null) {
                    tables.add(name.toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        return tables;
    }

    /**
     * 首次准入可建 revision=0 锚点；已有会话则必须匹配调用方版本才继续。
     */
    private static void ensureConversationRow(Connection connection, ConversationKey key,
                                              long expectedRevision, long occurredAt) throws SQLException {
        Optional<ConversationRow> row = findConversation(connection, key);
        if (row.isPresent()) {
            if (row.get().revision() != expectedRevision) {
                throw new AgentStore.StoreConflictException("conversation revision changed before admission");
            }
            return;
        }
        if (expectedRevision != 0) {
            throw new AgentStore.StoreConflictException("conversation does not exist");
        }
        String sql = """
            INSERT INTO agent_conversation (tenant_id, thread_id, revision, \
            event_sequence, record_sequence, latest_execution_sequence, created_at_ms, updated_at_ms, user_id) \
            VALUES (?, ?, 0, 0, 0, 0, ?, ?, ?)\
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindConversation(statement, 1, key);
            statement.setLong(3, occurredAt);
            statement.setLong(4, occurredAt);
            statement.setString(5, key.userId());
            statement.executeUpdate();
        }
    }

    /**
     * 在一个 CAS 更新中分配事件和记录水位，使会话级序号与本次事实提交保持一致。
     */
    private static void advanceConversation(Connection connection, ConversationKey key, long expectedRevision,
                                            int recordCount, int eventCount, int executionCount, long occurredAt)
            throws SQLException {
        String sql = """
            UPDATE agent_conversation SET revision = revision + 1, \
            event_sequence = event_sequence + ?, record_sequence = record_sequence + ?, \
            latest_execution_sequence = latest_execution_sequence + ?, \
            updated_at_ms = ? WHERE tenant_id = ? AND thread_id = ? AND revision = ? AND user_id = ?\
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, eventCount);
            statement.setInt(2, recordCount);
            statement.setInt(3, executionCount);
            statement.setLong(4, occurredAt);
            bindConversation(statement, 5, key);
            statement.setLong(7, expectedRevision);
            statement.setString(8, key.userId());
            if (statement.executeUpdate() != 1) {
                throw new AgentStore.StoreConflictException("conversation revision changed concurrently");
            }
        }
    }

    /**
     * 执行行一次保存初始状态、版本及游标 schema，避免创建后恢复数据尚未就绪。
     */
    private static void insertExecution(Connection connection, Admission request, long sequence,
                                        long lastRecordSequence, long occurredAt) throws SQLException {
        String sql = """
            INSERT INTO agent_execution (tenant_id, thread_id, turn_id, \
            admission_sequence, state, mutation_version, cursor_schema_version, cursor_json, \
            last_record_sequence, created_at_ms, updated_at_ms, user_id) \
            VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)\
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindExecution(statement, 1, request.execution());
            statement.setLong(4, sequence);
            statement.setString(5, request.state());
            statement.setInt(6, request.cursorSchemaVersion());
            statement.setString(7, writeJson(request.cursor()));
            statement.setLong(8, lastRecordSequence);
            statement.setLong(9, occurredAt);
            statement.setLong(10, occurredAt);
            statement.setString(11, request.execution().conversation().userId());
            statement.executeUpdate();
        }
    }

    /**
     * 按请求顺序分配会话级全局记录序号，保留旧迁移记录与新执行事实的统一分页顺序。
     */
    private static void insertRecords(Connection connection, ExecutionKey key, long previousSequence,
                                      List<RecordWrite> records) throws SQLException {
        String sql = """
            INSERT INTO agent_record (tenant_id, thread_id, turn_id, record_id, \
            record_type, payload_schema_version, record_sequence, payload_json, occurred_at_ms, user_id) \
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)\
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            long sequence = previousSequence;
            for (RecordWrite record : records) {
                String payload = writeJson(record.payload());
                bindExecution(statement, 1, key);
                statement.setString(4, record.recordId());
                statement.setString(5, record.recordType());
                statement.setInt(6, record.payloadSchemaVersion());
                statement.setLong(7, ++sequence);
                statement.setString(8, payload);
                statement.setLong(9, record.occurredAt().toEpochMilli());
                statement.setString(10, key.conversation().userId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /**
     * 将会话版本、执行版本和记录水位与事件序号一起写入，确保全部来自本事务已推进状态。
     */
    private static void insertEvents(Connection connection, ExecutionKey key, long firstSequence,
                                     long threadRevision, long mutationVersion, long lastRecordSequence,
                                     List<EventWrite> events) throws SQLException {
        String sql = """
            INSERT INTO agent_event (tenant_id, thread_id, event_sequence, turn_id, \
            event_id, event_type, payload_schema_version, thread_revision, mutation_version, \
            last_record_sequence, payload_json, occurred_at_ms, user_id) \
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)\
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < events.size(); index++) {
                EventWrite event = events.get(index);
                String payload = writeJson(event.payload());
                bindConversation(statement, 1, key.conversation());
                statement.setLong(3, firstSequence + index);
                statement.setString(4, key.turnId());
                statement.setString(5, event.eventId());
                statement.setString(6, event.eventType());
                statement.setInt(7, event.payloadSchemaVersion());
                statement.setLong(8, threadRevision);
                statement.setLong(9, mutationVersion);
                statement.setLong(10, lastRecordSequence);
                statement.setString(11, payload);
                statement.setLong(12, event.occurredAt().toEpochMilli());
                statement.setString(13, key.conversation().userId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /**
     * 每条会话 SQL 条件同时包含 tenant 与 conversation，数据库查询自身也强制租户隔离。
     */
    private static Optional<ConversationRow> findConversation(Connection connection, ConversationKey key)
            throws SQLException {
        String sql = """
            SELECT revision, event_sequence, record_sequence, latest_execution_sequence, \
            created_at_ms, updated_at_ms \
            FROM agent_conversation WHERE tenant_id = ? AND thread_id = ? AND user_id = ?\
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindConversation(statement, 1, key);
            statement.setString(3, key.userId());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new ConversationRow(key, rows.getLong(1), rows.getLong(2),
                        rows.getLong(3), rows.getLong(4), rows.getLong(5), rows.getLong(6))) : Optional.empty();
            }
        }
    }

    /**
     * 目标会话不存在时直接失败，不隐式补造会话来绕过初始化和 CAS 入口。
     */
    private static ConversationRow requireConversation(Connection connection, ConversationKey key)
            throws SQLException {
        return findConversation(connection, key)
                .orElseThrow(() -> new AgentStore.StoreConflictException("conversation does not exist"));
    }

    /**
     * 按完整租户、会话和执行复合身份装载快照，避免仅靠可能重复的 execution id 命中。
     */
    private static Optional<ExecutionSnapshot> findExecution(Connection connection, ExecutionKey key)
            throws SQLException {
        String sql = """
            SELECT admission_sequence, state, mutation_version, cursor_schema_version, cursor_json, \
            last_record_sequence, created_at_ms, updated_at_ms FROM agent_execution \
            WHERE tenant_id = ? AND thread_id = ? AND turn_id = ? AND user_id = ?\
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindExecution(statement, 1, key);
            statement.setString(4, key.conversation().userId());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(executionSnapshot(key, rows)) : Optional.empty();
            }
        }
    }

    /**
     * ResultSet 关闭前复制为不可变快照，禁止 JDBC 游标越过连接生命周期。
     */
    private static ExecutionSnapshot executionSnapshot(ExecutionKey key, ResultSet rows) throws SQLException {
        return executionSnapshot(key, rows, 0);
    }

    /**
     * 分页联表时使用调用方指定的列偏移，防止前置会话字段改变执行快照映射。
     */
    private static ExecutionSnapshot executionSnapshot(ExecutionKey key, ResultSet rows, int offset)
            throws SQLException {
        return new ExecutionSnapshot(key, rows.getLong(offset + 1), rows.getString(offset + 2),
                rows.getLong(offset + 3), rows.getInt(offset + 4), readJson(rows.getString(offset + 5)),
                rows.getLong(offset + 6), instant(rows.getLong(offset + 7)), instant(rows.getLong(offset + 8)));
    }

    /**
     * 操作回执只依赖通用租户键查询，不要求存储扩展了解 Ja 的业务表。
     */
    private static Optional<OperationReceipt> readOperationReceipt(Connection connection, OperationKey key)
            throws SQLException {
        String sql = """
            SELECT thread_id, turn_id, operation_type, fingerprint, thread_revision, \
            mutation_version, first_event_sequence, last_event_sequence, receipt_json, occurred_at_ms \
            FROM agent_operation_receipt WHERE tenant_id = ? AND operation_key = ? AND user_id = ?\
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.operationKey());
            statement.setString(3, key.userId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                ConversationKey conversation = new ConversationKey(key.tenantId(), rows.getString(1), key.userId());
                return Optional.of(new OperationReceipt(conversation, rows.getString(2), key.operationKey(),
                        rows.getString(4), rows.getString(3), rows.getLong(5), rows.getLong(6),
                        rows.getLong(7), rows.getLong(8), readJson(rows.getString(9)), instant(rows.getLong(10))));
            }
        }
    }

    /**
     * 将初始响应及权威版本范围与准入事务共存，保证回执可重建已提交位置。
     */
    private static void insertOperationReceipt(Connection connection, ExecutionKey execution,
                                               OperationIdentity identity, long revision, long mutation,
                                               long firstEvent, long lastEvent, long occurredAt) throws SQLException {
        String sql = """
            INSERT INTO agent_operation_receipt (tenant_id, operation_key, thread_id, \
            turn_id, operation_type, fingerprint, thread_revision, mutation_version, \
            first_event_sequence, last_event_sequence, receipt_json, occurred_at_ms, user_id) \
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)\
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, execution.conversation().tenantId());
            statement.setString(2, identity.operationKey());
            statement.setString(3, execution.conversation().threadId());
            statement.setString(4, execution.turnId());
            statement.setString(5, identity.operationType());
            statement.setString(6, identity.fingerprint());
            statement.setLong(7, revision);
            statement.setLong(8, mutation);
            statement.setLong(9, firstEvent);
            statement.setLong(10, lastEvent);
            statement.setString(11, writeJson(identity.receipt()));
            statement.setLong(12, occurredAt);
            statement.setString(13, execution.conversation().userId());
            statement.executeUpdate();
        }
    }

    /**
     * 可选回执写入中的受检 SQL 异常必须升级为事务失败，不能吞掉后继续提交。
     */
    private static void insertOperationReceiptUnchecked(Connection connection, ExecutionKey execution,
                                                        OperationIdentity operation, long revision, long mutation,
                                                        long firstEvent, long lastEvent, long occurredAt) {
        try {
            insertOperationReceipt(connection, execution, operation, revision, mutation, firstEvent, lastEvent, occurredAt);
        } catch (SQLException failure) {
            throw translateSqlFailure("could not write AgentStore operation receipt", failure);
        }
    }

    /**
     * 执行状态变更前先按提交键和指纹核对旧回执，避免重试把同一事实再写一次。
     */
    private static Optional<CommitRow> findCommitReceipt(Connection connection, CommitIdentity identity)
            throws SQLException {
        String sql = """
            SELECT fingerprint, thread_revision, mutation_version, first_event_sequence, \
            last_event_sequence, receipt_json FROM agent_commit_receipt WHERE tenant_id = ? \
            AND thread_id = ? AND turn_id = ? AND commit_key = ? AND user_id = ?\
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindExecution(statement, 1, identity.execution());
            statement.setString(4, identity.commitKey());
            statement.setString(5, identity.execution().conversation().userId());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new CommitRow(identity, rows.getString(1), rows.getLong(2),
                        rows.getLong(3), rows.getLong(4), rows.getLong(5), readJson(rows.getString(6)))) : Optional.empty();
            }
        }
    }

    /**
     * 提交回执、执行状态和持久事件同事务落库，响应丢失后能读取真实结果。
     */
    private static void insertCommitReceipt(Connection connection, CommitIdentity identity,
                                            CommitReceipt receipt, long occurredAt) throws SQLException {
        String sql = """
            INSERT INTO agent_commit_receipt (tenant_id, thread_id, turn_id, \
            commit_key, fingerprint, thread_revision, mutation_version, first_event_sequence, \
            last_event_sequence, receipt_json, occurred_at_ms, user_id) \
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)\
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindExecution(statement, 1, identity.execution());
            statement.setString(4, identity.commitKey());
            statement.setString(5, identity.fingerprint());
            statement.setLong(6, receipt.threadRevision());
            statement.setLong(7, receipt.mutationVersion());
            statement.setLong(8, receipt.firstEventSequence());
            statement.setLong(9, receipt.lastEventSequence());
            statement.setString(10, writeJson(receipt.receipt()));
            statement.setLong(11, occurredAt);
            statement.setString(12, identity.execution().conversation().userId());
            statement.executeUpdate();
        }
    }

    /**
     * 只有原请求指纹完全一致才重放提交回执，拒绝同键改内容。
     */
    private static CommitReceipt replayCommit(CommitRow row, CommitIdentity identity) {
        if (!row.fingerprint().equals(identity.fingerprint())) {
            throw new AgentStore.StoreConflictException("commit key was reused with a different request");
        }
        return new CommitReceipt(row.threadRevision(), row.mutationVersion(), row.firstEventSequence(),
                row.lastEventSequence(), row.receipt(), false);
    }

    /**
     * 回放前检查请求指纹及执行身份，避免回执跨执行误用。
     */
    private static CommitReceipt replayOperationCommit(OperationReceipt receipt, OperationIdentity requested,
                                                       ExecutionKey execution) {
        if (!receipt.fingerprint().equals(requested.fingerprint())
                || !receipt.operationType().equals(requested.operationType())
                || !receipt.conversation().equals(execution.conversation())
                || !receipt.turnId().equals(execution.turnId())) {
            throw new AgentStore.StoreConflictException("operation key was reused with a different request");
        }
        return new CommitReceipt(receipt.threadRevision(), receipt.mutationVersion(),
                receipt.firstEventSequence(), receipt.lastEventSequence(),
                receiptJson(receipt.threadRevision(), receipt.mutationVersion(),
                        receipt.firstEventSequence(), receipt.lastEventSequence()), false);
    }

    /**
     * 准入回放同时校验租户级幂等键和请求内容，重复请求不会创建第二个 execution。
     */
    private static AdmissionReceipt replayAdmission(OperationReceipt receipt, Admission request) {
        if (!receipt.fingerprint().equals(request.operation().fingerprint())
                || !receipt.operationType().equals(request.operation().operationType())
                || !receipt.conversation().equals(request.execution().conversation())
                || !receipt.turnId().equals(request.execution().turnId())) {
            throw new AgentStore.StoreConflictException("operation key was reused with a different request");
        }
        return new AdmissionReceipt(receipt.threadRevision(), receipt.mutationVersion(),
                receipt.firstEventSequence(), receipt.lastEventSequence(), receipt.receipt(), false);
    }

    /**
     * 先锁定并校验当前租约行，避免接管过程与宿主同事务写入交错。
     */
    private static void requireLiveLease(Connection connection, Lease lease) throws SQLException {
        String sql = """
            SELECT expires_at_ms FROM agent_lease WHERE tenant_id = ? AND thread_id = ? \
            AND owner_id = ? AND fencing_token = ? AND user_id = ? FOR UPDATE\
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindConversation(statement, 1, lease.conversation());
            statement.setString(3, lease.ownerId());
            statement.setLong(4, lease.fencingToken());
            statement.setString(5, lease.conversation().userId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getLong(1) <= databaseNow(connection)) {
                    throw new AgentStore.StoreConflictException("execution lease is stale or expired");
                }
            }
        }
    }

    /**
     * 仅在 owner 转换期间锁定租约行，会话 CAS 仍按行隔离，不阻塞其他会话。
     */
    private static Optional<LeaseRow> findLease(Connection connection, ConversationKey key, boolean lock)
            throws SQLException {
        String sql = """
            SELECT owner_id, fencing_token, expires_at_ms FROM agent_lease \
            WHERE tenant_id = ? AND thread_id = ? AND user_id = ?\
            """ + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindConversation(statement, 1, key);
            statement.setString(3, key.userId());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new LeaseRow(rows.getString(1), rows.getLong(2), rows.getLong(3)))
                        : Optional.empty();
            }
        }
    }

    /**
     * 同一有效持有者续租只延长到期时间，不递增 token 破坏当前执行。
     */
    private static void updateLeaseExpiry(Connection connection, ConversationKey key, LeaseRow current,
                                          long expiry, long now) throws SQLException {
        String sql = """
            UPDATE agent_lease SET expires_at_ms = ?, updated_at_ms = ? \
            WHERE tenant_id = ? AND thread_id = ? AND owner_id = ? AND fencing_token = ? AND user_id = ?\
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, expiry);
            statement.setLong(2, now);
            bindConversation(statement, 3, key);
            statement.setString(5, current.ownerId());
            statement.setLong(6, current.fencingToken());
            statement.setString(7, key.userId());
            if (statement.executeUpdate() != 1) {
                throw new AgentStore.StoreConflictException("conversation lease changed concurrently");
            }
        }
    }

    /**
     * 租约过期以数据库时钟裁决，避免多节点时钟偏差造成双重持有。
     */
    private static long databaseNow(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT " + POSTGRES_NOW_MS)) {
            if (!rows.next()) {
                throw new AgentStore.StoreIntegrityException("PostgreSQL clock query returned no row", null);
            }
            return rows.getLong(1);
        }
    }

    /**
     * 将数据库会话行复制为不可变投影，后续恢复不依赖结果集。
     *
     * @param key 键
     * @param revision 当前修订版本
     * @param eventSequence 事件序号
     * @param recordSequence 记录序号
     * @param latestExecutionSequence 最近执行序号
     * @param createdAtMillis 创建时间的 Unix 毫秒时间戳
     * @param updatedAtMillis 更新时间的 Unix 毫秒时间戳
     */
    private record ConversationRow(ConversationKey key,
                                   long revision,
                                   long eventSequence,
                                   long recordSequence,
                                   long latestExecutionSequence,
                                   long createdAtMillis,
                                   long updatedAtMillis) {
        /**
         * 先脱离 JDBC 行再转换时间，避免快照字段意外延长连接生命周期。
         */
        private ConversationSnapshot snapshot() {
            return new ConversationSnapshot(key, revision, eventSequence, latestExecutionSequence,
                    instant(createdAtMillis), instant(updatedAtMillis), recordSequence);
        }
    }

    /**
     * 租约内部投影只在当前 SQL 事务内使用，不作为可缓存的所有权凭证。
     *
     * @param ownerId 所有者标识
     * @param fencingToken 执行租约的隔离令牌，用于拒绝过期执行者
     * @param expiresAtMillis 过期时间的 Unix 毫秒时间戳
     */
    private record LeaseRow(String ownerId,
                            long fencingToken,
                            long expiresAtMillis) { }

    /**
     * 内部回执行额外保留请求指纹，用于确认公开回执是否对应精确重试请求。
     *
     * @param identity 存储事务提交的稳定身份
     * @param fingerprint 当前内容或配置的校验指纹
     * @param threadRevision 对话修订版本
     * @param mutationVersion 状态变更版本，用于并发提交校验
     * @param firstEventSequence 当前区间的首个事件序号
     * @param lastEventSequence 当前区间的最后事件序号
     * @param receipt 已校验并解码的提交回执 JSON 值
     */
    private record CommitRow(CommitIdentity identity,
                             String fingerprint,
                             long threadRevision,
                             long mutationVersion,
                             long firstEventSequence,
                             long lastEventSequence,
                             JsonObject receipt) { }

    /**
     * JDBC 工作只声明 SQL 受检故障；参与者以中立运行时错误返回，不把通用 Exception 扩散到事务层。
     */
    @FunctionalInterface
    private interface SqlWork<T> {
        /**
         * 在同一连接完成映射，只传播 JDBC 受检故障，禁止参与者另开连接导致事务分裂。
         */
        T apply(Connection connection) throws SQLException;
    }

    /** 固定会话水位与 owner，末尾多读一条只用于判断下一页，不推进到未交付记录。 */
    private static void bindConversationPage(PreparedStatement statement, ConversationKey conversation,
            long after, long through, int limit) throws SQLException {
        bindConversation(statement, 1, conversation);
        statement.setLong(3, after);
        statement.setLong(4, through);
        statement.setString(5, conversation.userId());
        statement.setInt(6, limit + 1);
    }

    /** 已准入执行才允许推进事务，缺失必须作为冲突拒绝，不能补造一个空执行。 */
    private static ExecutionSnapshot requireExecution(Connection connection, ExecutionKey execution) throws SQLException {
        return findExecution(connection, execution)
                .orElseThrow(() -> new AgentStore.StoreConflictException("execution does not exist"));
    }

    /**
     * 所有会话 SQL 都绑定 tenant 与 conversation 复合键，避免租户谓词遗漏。
     */
    private static void bindConversation(PreparedStatement statement, int start, ConversationKey key)
            throws SQLException {
        statement.setString(start, key.tenantId());
        statement.setString(start + 1, key.threadId());
    }

    /**
     * 执行级 SQL 绑定完整三元身份，保证执行记录不越过会话或租户边界。
     */
    private static void bindExecution(PreparedStatement statement, int start, ExecutionKey key)
            throws SQLException {
        bindConversation(statement, start, key.conversation());
        statement.setString(start + 2, key.turnId());
    }

    /**
     * 写入前统一限制单个 JSON payload 的 UTF-8 大小，避免异常负载耗尽本地库或远端数据库。
     */
    private static void requireWritePayloadSize(String payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length() > AgentStore.MAX_JSON_PAYLOAD_UTF8_BYTES
                || payload.getBytes(StandardCharsets.UTF_8).length > AgentStore.MAX_JSON_PAYLOAD_UTF8_BYTES) {
            throw new IllegalArgumentException("AgentStore JSON payload exceeds the 16 MiB limit");
        }
    }

    /**
     * 在序列化边界一次完成 Core JSON 编码和字节上限校验，避免本地 adapter 与 PostgreSQL 接受不同负载。
     */
    private static String writeJson(JsonObject value) {
        String encoded = JSON.encode(Objects.requireNonNull(value, "value"));
        requireWritePayloadSize(encoded);
        return encoded;
    }

    /**
     * 数据库 JSON 是恢复事实；格式或大小损坏必须作为完整性故障暴露，不能转为空对象继续执行。
     */
    private static JsonObject readJson(String encoded) {
        storedPayloadBytes(encoded);
        try {
            return JSON.decodeObject(encoded);
        } catch (RuntimeException failure) {
            throw new AgentStore.StoreIntegrityException("stored AgentStore JSON object is invalid", failure);
        }
    }

    /**
     * 读取时把违反 Core 写入合同的存量标记为完整性错误，不能静默跳过导致恢复游标越过数据。
     */
    private static long storedPayloadBytes(String payload) {
        if (payload == null || payload.length() > AgentStore.MAX_JSON_PAYLOAD_UTF8_BYTES) {
            throw new AgentStore.StoreIntegrityException("stored AgentStore JSON payload exceeds the 16 MiB limit", null);
        }
        long bytes = payload.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > AgentStore.MAX_JSON_PAYLOAD_UTF8_BYTES) {
            throw new AgentStore.StoreIntegrityException("stored AgentStore JSON payload exceeds the 16 MiB limit", null);
        }
        return bytes;
    }

    /**
     * 明确计算非空事件批次起点与空批次标记，避免空区间被误读为已发事件。
     */
    private static long firstSequence(long lastSequence, int count) {
        return count == 0 ? 0 : lastSequence - count + 1;
    }

    /**
     * 回执 JSON 用确定性字段表达已提交水位，便于超时协调而不重复业务数据。
     */
    private static JsonObject receiptJson(long revision, long mutation, long firstEvent, long lastEvent) {
        return JSON.decodeObject("{\"threadRevision\":" + revision + ",\"mutationVersion\":" + mutation
                + ",\"firstEventSequence\":" + firstEvent + ",\"lastEventSequence\":" + lastEvent + "}");
    }

    /**
     * 租约时间按毫秒统一比较；持久业务负载仍保留调用方原精度。
     */
    private static Instant instant(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis);
    }

    /**
     * 在进入 JDBC 前拒绝无界或非法页参数，保护数据库和恢复线程资源。
     */
    private static void requirePage(long afterSequence, long throughSequence, int limit) {
        if (afterSequence < 0 || throughSequence < afterSequence || limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("invalid page query");
        }
    }

    /**
     * 回滚失败只作为 suppressed 诊断附加，避免覆盖原始 SQL、回调或虚拟机错误。
     */
    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException | RuntimeException | Error rollbackFailure) {
            if (failure != rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

    /**
     * 将连接或 SQL 故障映射为稳定异常类别，SQL 文本不进入业务或模型内容。
     */
    private static AgentStore.StoreUnavailableException unavailable(String message, SQLException cause) {
        return new AgentStore.StoreUnavailableException(message, cause);
    }

    /**
     * 关系约束只暴露稳定冲突类别，原始 SQL 原因保留用于诊断；连接故障仍报告为不可用错误。
     */
    private static RuntimeException translateSqlFailure(String message, SQLException cause) {
        String state = cause.getSQLState();
        if (state != null && state.startsWith("23")) {
            AgentStore.StoreConflictException conflict = new AgentStore.StoreConflictException(message);
            conflict.initCause(cause);
            return conflict;
        }
        return unavailable(message, cause);
    }
}
