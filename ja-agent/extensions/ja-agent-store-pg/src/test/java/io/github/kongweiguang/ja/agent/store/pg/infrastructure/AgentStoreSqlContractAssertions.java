// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.store.pg.infrastructure;

import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.Admission;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.CommitIdentity;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationKey;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationRecordPage;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationRecordQuery;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationSnapshot;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.EventPage;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.EventQuery;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.EventWrite;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ExecutionCommit;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ExecutionKey;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ExecutionSnapshot;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.Lease;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.LeaseRequest;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.OperationIdentity;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.RecordPage;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.RecordQuery;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.RecordWrite;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.infrastructure.json.JsonValueCodecFactory;
import org.junit.jupiter.api.Assertions;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Store 的真实数据库合同，专门验证 SQL 约束和 Core 默认字节分页边界。 */
final class AgentStoreSqlContractAssertions {
    private static final JsonValueCodec JSON = JsonValueCodecFactory.standard();
    private static final String FINGERPRINT = "0".repeat(64);
    private static final String INSERT_RECORD = "INSERT INTO agent_record (tenant_id, thread_id, turn_id, user_id, "
            + "record_id, record_type, payload_schema_version, record_sequence, payload_json, occurred_at_ms) "
            + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?)";
    private static final String INSERT_CONVERSATION = "INSERT INTO agent_conversation (tenant_id, thread_id, user_id, "
            + "revision, event_sequence, record_sequence, latest_execution_sequence, created_at_ms, updated_at_ms) "
            + "VALUES (?, ?, ?, 0, 0, 0, 0, ?, ?)";
    private static final String INSERT_EXECUTION = "INSERT INTO agent_execution (tenant_id, thread_id, turn_id, user_id, "
            + "admission_sequence, state, mutation_version, cursor_schema_version, cursor_json, last_record_sequence, "
            + "created_at_ms, updated_at_ms) VALUES (?, ?, ?, ?, ?, ?, 0, 1, '{}', 0, ?, ?)";
    private static final String INSERT_OPERATION_RECEIPT = "INSERT INTO agent_operation_receipt (tenant_id, user_id, "
            + "operation_key, thread_id, turn_id, operation_type, fingerprint, thread_revision, mutation_version, "
            + "first_event_sequence, last_event_sequence, receipt_json, occurred_at_ms) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1, 0, 0, '{}', ?)";

    /** 静态合同工具不持有数据库资源，生命周期交给各 extension 的集成测试。 */
    private AgentStoreSqlContractAssertions() {
    }

    /** 真实 SQL 引擎负责持久身份、状态、指纹及外键约束；Store API 负责软页、水位和 payload 限制。 */
    static void verify(DataSource database, AgentStore store) throws SQLException {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        verifyDatabaseConstraints(database, store, suffix, now);
        verifyUtf8PageBoundaries(store, suffix, now);
        verifyOversizedPayloadRejected(store, suffix, now);
    }

    /** 以有效父行建立隔离探针，验证 identity、TurnState、JSON、fingerprint、外键及禁止级联删除。 */
    private static void verifyDatabaseConstraints(DataSource database, AgentStore store,
                                                  String suffix, Instant now) throws SQLException {
        RecordWrite original = record("record-" + suffix, object("{}"), now);
        StoreFixture fixture = admit(store, "constraints-" + suffix, List.of(original), List.of(), now);
        assertRecordInsertRejected(database, fixture, original.recordId(), fixture.conversation().userId(),
                "{}", "record identity must be unique within an execution");
        assertRecordInsertRejected(database, fixture, "invalid-json-" + suffix, fixture.conversation().userId(),
                "{invalid", "record payload must be a JSON object");
        assertRecordInsertRejected(database, fixture, "foreign-owner-" + suffix,
                fixture.conversation().userId() + "-other", "{}",
                "record execution must belong to the conversation owner");
        verifyTurnStateConstraints(database, fixture, suffix, now);
        assertConversationInsertRejected(database, "", "thread-empty-tenant-" + suffix, "user-" + suffix,
                now, "tenant identity must not be blank");
        assertConversationInsertRejected(database, "tenant-" + suffix, "thread-" + "x".repeat(257),
                "user-" + suffix, now, "thread identity must be limited to 256 characters");
        executeExecutionInsert(database, fixture, "turn-" + "x".repeat(257), 100,
                TurnState.RUNNING.name(), now, true, "turn identity must be limited to 256 characters");
        assertOperationReceiptInsertRejected(database, fixture, "operation-" + "x".repeat(257), FINGERPRINT,
                now, "operation key must be limited to 256 characters");
        assertOperationReceiptInsertRejected(database, fixture, "invalid-fingerprint-" + suffix,
                "A".repeat(64), now, "operation fingerprint must be lowercase hexadecimal");
        assertDeleteRejected(database, "DELETE FROM agent_execution WHERE tenant_id=? AND thread_id=? "
                        + "AND turn_id=? AND user_id=?",
                "execution with child facts must require explicit cleanup", fixture.conversation().tenantId(),
                fixture.conversation().threadId(), fixture.execution().turnId(), fixture.conversation().userId());
        assertDeleteRejected(database, "DELETE FROM agent_conversation WHERE tenant_id=? AND thread_id=?",
                "conversation with executions must require explicit cleanup", fixture.conversation().tenantId(),
                fixture.conversation().threadId());
        Assertions.assertTrue(store.loadConversation(fixture.conversation()).isPresent());
        Assertions.assertTrue(store.loadExecution(fixture.execution()).isPresent());
        Assertions.assertEquals(original.payload(), store.readRecord(fixture.execution(), original.recordId())
                .orElseThrow().payload());
    }

    /** SQL 状态闭集必须精确覆盖 Core 八态，未知值由数据库拒绝以阻止绕过 Java codec。 */
    private static void verifyTurnStateConstraints(DataSource database, StoreFixture fixture,
                                                   String suffix, Instant now) throws SQLException {
        long admissionSequence = 2;
        for (TurnState state : TurnState.values()) {
            executeExecutionInsert(database, fixture, "turn-state-" + state.name() + "-" + suffix,
                    admissionSequence++, state.name(), now, false,
                    "Core TurnState must be accepted by the SQL schema: " + state);
        }
        executeExecutionInsert(database, fixture, "unknown-state-" + suffix, admissionSequence,
                "NOT_A_TURN_STATE", now, true, "unknown execution state must be rejected");
    }

    /** 空 tenant 和超长 thread 直插父表，避免外键失败掩盖 identity 列自身的 CHECK。 */
    private static void assertConversationInsertRejected(DataSource database, String tenantId, String threadId,
                                                          String userId, Instant now, String message)
            throws SQLException {
        try (Connection connection = database.getConnection()) {
            enableForeignKeys(connection);
            try (PreparedStatement insert = connection.prepareStatement(INSERT_CONVERSATION)) {
                insert.setString(1, tenantId);
                insert.setString(2, threadId);
                insert.setString(3, userId);
                insert.setLong(4, now.toEpochMilli());
                insert.setLong(5, now.toEpochMilli());
                Assertions.assertThrows(SQLException.class, () -> insert.executeUpdate(), message);
            }
        }
    }

    /** 通过唯一 admission sequence 隔离执行插入，只让本次 state 或 turn identity 触发约束。 */
    private static void executeExecutionInsert(DataSource database, StoreFixture fixture, String turnId,
                                               long admissionSequence, String state, Instant now,
                                               boolean rejected, String message) throws SQLException {
        try (Connection connection = database.getConnection()) {
            enableForeignKeys(connection);
            try (PreparedStatement insert = connection.prepareStatement(INSERT_EXECUTION)) {
                insert.setString(1, fixture.conversation().tenantId());
                insert.setString(2, fixture.conversation().threadId());
                insert.setString(3, turnId);
                insert.setString(4, fixture.conversation().userId());
                insert.setLong(5, admissionSequence);
                insert.setString(6, state);
                insert.setLong(7, now.toEpochMilli());
                insert.setLong(8, now.toEpochMilli());
                if (rejected) {
                    Assertions.assertThrows(SQLException.class, () -> insert.executeUpdate(), message);
                } else {
                    Assertions.assertEquals(1, insert.executeUpdate(), message);
                }
            }
        }
    }

    /** 以有效执行外键隔离 operation_key 与 fingerprint 检查，避免关系错误产生假阳性。 */
    private static void assertOperationReceiptInsertRejected(DataSource database, StoreFixture fixture,
                                                              String operationKey, String fingerprint,
                                                              Instant now, String message) throws SQLException {
        try (Connection connection = database.getConnection()) {
            enableForeignKeys(connection);
            try (PreparedStatement insert = connection.prepareStatement(INSERT_OPERATION_RECEIPT)) {
                insert.setString(1, fixture.conversation().tenantId());
                insert.setString(2, fixture.conversation().userId());
                insert.setString(3, operationKey);
                insert.setString(4, fixture.conversation().threadId());
                insert.setString(5, fixture.execution().turnId());
                insert.setString(6, "turn.admit");
                insert.setString(7, fingerprint);
                insert.setLong(8, now.toEpochMilli());
                Assertions.assertThrows(SQLException.class, () -> insert.executeUpdate(), message);
            }
        }
    }

    /** 分连接执行删除探针，确保 FK 是拒绝根因且失败事务不会污染后续断言。 */
    private static void assertDeleteRejected(DataSource database, String sql, String message, String... identity)
            throws SQLException {
        try (Connection connection = database.getConnection()) {
            enableForeignKeys(connection);
            try (PreparedStatement delete = connection.prepareStatement(sql)) {
                for (int index = 0; index < identity.length; index++) {
                    delete.setString(index + 1, identity[index]);
                }
                Assertions.assertThrows(SQLException.class, () -> delete.executeUpdate(), message);
            }
        }
    }

    /** SQLite 默认关闭外键；其他 JDBC 引擎已启用约束，故只为 SQLite 显式打开关系检查。 */
    private static void enableForeignKeys(Connection connection) throws SQLException {
        if ("SQLite".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
            try (Statement pragma = connection.createStatement()) {
                pragma.execute("PRAGMA foreign_keys = ON");
            }
        }
    }

    /** 直接插入只绕过 Store 编码层，以免 API 校验掩盖真实 SQL 唯一键、JSON 与复合外键。 */
    private static void assertRecordInsertRejected(DataSource database, StoreFixture fixture, String recordId,
                                                   String userId, String payload, String message) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement insert = connection.prepareStatement(INSERT_RECORD)) {
            insert.setString(1, fixture.conversation().tenantId());
            insert.setString(2, fixture.conversation().threadId());
            insert.setString(3, fixture.execution().turnId());
            insert.setString(4, userId);
            insert.setString(5, recordId);
            insert.setString(6, "agent.test.database-contract");
            insert.setLong(7, 2);
            insert.setString(8, payload);
            insert.setLong(9, Instant.now().toEpochMilli());
            Assertions.assertThrows(SQLException.class, () -> insert.executeUpdate(), message);
        }
    }

    /** 超过 4 MiB 的首项单独返回并推进游标，后续页仍受 admission 时的水位约束。 */
    private static void verifyUtf8PageBoundaries(AgentStore store, String suffix, Instant now) {
        JsonObject largePayload = object("{\"text\":\"" + "你".repeat(1_398_200) + "\"}");
        long largePayloadBytes = JSON.encode(largePayload).getBytes(StandardCharsets.UTF_8).length;
        Assertions.assertTrue(largePayloadBytes > AgentStore.DEFAULT_PAGE_MAX_BYTES);
        Assertions.assertTrue(largePayloadBytes <= AgentStore.MAX_JSON_PAYLOAD_UTF8_BYTES);
        JsonObject smallPayload = object("{}");
        RecordWrite largeRecord = record("large-record-" + suffix, largePayload, now);
        RecordWrite smallRecord = record("small-record-" + suffix, smallPayload, now);
        EventWrite largeEvent = event("large-event-" + suffix, largePayload, now);
        EventWrite smallEvent = event("small-event-" + suffix, smallPayload, now);
        StoreFixture fixture = admit(store, "pages-" + suffix, List.of(largeRecord, smallRecord),
                List.of(largeEvent, smallEvent), now);
        ConversationSnapshot snapshot = store.loadConversation(fixture.conversation()).orElseThrow();
        long recordWatermark = snapshot.recordSequence();
        long eventWatermark = snapshot.eventSequence();

        RecordPage records = store.readRecords(RecordQuery.withDefaultMaxBytes(
                fixture.execution(), 0, recordWatermark, 10));
        Assertions.assertEquals(List.of(largeRecord.recordId()), recordIds(records));
        Assertions.assertEquals(1, records.nextSequence());
        Assertions.assertTrue(records.hasMore());
        EventPage events = store.readEvents(EventQuery.withDefaultMaxBytes(
                fixture.conversation(), 0, eventWatermark, 10));
        Assertions.assertEquals(List.of(largeEvent.eventId()), eventIds(events));
        Assertions.assertEquals(1, events.nextSequence());
        Assertions.assertTrue(events.hasMore());
        ConversationRecordPage conversationRecords = store.readConversationRecords(
                ConversationRecordQuery.withDefaultMaxBytes(fixture.conversation(), 0, recordWatermark, 10));
        Assertions.assertEquals(List.of(largeRecord.recordId()), conversationRecordIds(conversationRecords));
        Assertions.assertEquals(1, conversationRecords.nextSequence());
        Assertions.assertTrue(conversationRecords.hasMore());

        appendSmallRecordAndEvent(store, fixture, "later-" + suffix, now.plusSeconds(1));
        RecordPage nextRecords = store.readRecords(RecordQuery.withDefaultMaxBytes(
                fixture.execution(), records.nextSequence(), recordWatermark, 10));
        Assertions.assertEquals(List.of(smallRecord.recordId()), recordIds(nextRecords));
        Assertions.assertEquals(2, nextRecords.nextSequence());
        Assertions.assertFalse(nextRecords.hasMore());
        EventPage nextEvents = store.readEvents(EventQuery.withDefaultMaxBytes(
                fixture.conversation(), events.nextSequence(), eventWatermark, 10));
        Assertions.assertEquals(List.of(smallEvent.eventId()), eventIds(nextEvents));
        Assertions.assertEquals(2, nextEvents.nextSequence());
        Assertions.assertFalse(nextEvents.hasMore());
        ConversationRecordPage nextConversationRecords = store.readConversationRecords(
                ConversationRecordQuery.withDefaultMaxBytes(fixture.conversation(),
                        conversationRecords.nextSequence(), recordWatermark, 10));
        Assertions.assertEquals(List.of(smallRecord.recordId()), conversationRecordIds(nextConversationRecords));
        Assertions.assertEquals(2, nextConversationRecords.nextSequence());
        Assertions.assertFalse(nextConversationRecords.hasMore());
        Assertions.assertEquals(List.of(largeRecord.recordId(), smallRecord.recordId(), "later-record-later-" + suffix),
                recordIds(store.readRecords(new RecordQuery(fixture.execution(), 0, Long.MAX_VALUE, 10,
                        AgentStore.MAX_JSON_PAYLOAD_UTF8_BYTES))));
        Assertions.assertEquals(List.of(largeEvent.eventId(), smallEvent.eventId(), "later-event-later-" + suffix),
                eventIds(store.readEvents(new EventQuery(fixture.conversation(), 0, Long.MAX_VALUE, 10,
                        AgentStore.MAX_JSON_PAYLOAD_UTF8_BYTES))));
    }

    /** 固定 admission 的记录与事件水位，在并发后续提交后仍能逐页读完原快照。 */
    private static void appendSmallRecordAndEvent(AgentStore store, StoreFixture fixture,
                                                  String suffix, Instant at) {
        Lease lease = store.claimLease(new LeaseRequest(fixture.conversation(), "owner-" + suffix,
                Instant.now(), Instant.now().plusSeconds(300)));
        try {
            ConversationSnapshot conversation = store.loadConversation(fixture.conversation()).orElseThrow();
            ExecutionSnapshot execution = store.loadExecution(fixture.execution()).orElseThrow();
            RecordWrite record = record("later-record-" + suffix, object("{}"), at);
            EventWrite event = event("later-event-" + suffix, object("{}"), at);
            ExecutionCommit commit = new ExecutionCommit(fixture.execution(), conversation.revision(),
                    execution.state(), execution.mutationVersion(), lease, 1, execution.state(), object("{}"),
                    List.of(record), List.of(event), new CommitIdentity(fixture.execution(), "commit-" + suffix,
                    FINGERPRINT), Optional.empty(), at);
            store.commit(commit);
        } finally {
            store.releaseLease(lease);
        }
    }

    /** Core 16 MiB payload 限制在触库前拒绝过大 JSON，并回滚同一准入事务中的会话创建。 */
    private static void verifyOversizedPayloadRejected(AgentStore store, String suffix, Instant now) {
        JsonObject oversized = object("{\"text\":\"" + "你".repeat(5_600_000) + "\"}");
        long payloadBytes = JSON.encode(oversized).getBytes(StandardCharsets.UTF_8).length;
        Assertions.assertTrue(payloadBytes > AgentStore.MAX_JSON_PAYLOAD_UTF8_BYTES);
        ConversationKey conversation = conversation("oversized-" + suffix);
        ExecutionKey execution = new ExecutionKey(conversation, "turn-oversized-" + suffix);
        Admission admission = admission(execution, List.of(record("oversized-record-" + suffix, oversized, now)),
                List.of(), "oversized-" + suffix, now);
        Assertions.assertThrows(IllegalArgumentException.class, () -> store.admit(admission));
        Assertions.assertTrue(store.loadConversation(conversation).isEmpty());
    }

    /** 通过真实 Store 准入建立带稳定 owner 的父行，使数据库关系断言不依赖手写 fixture 表。 */
    private static StoreFixture admit(AgentStore store, String suffix, List<RecordWrite> records,
                                      List<EventWrite> events, Instant now) {
        ExecutionKey execution = new ExecutionKey(conversation(suffix), "turn-" + suffix);
        store.admit(admission(execution, records, events, suffix, now));
        return new StoreFixture(execution.conversation(), execution);
    }

    /** 构造最小有效准入，保持分页、SQL 约束检查使用相同的唯一 identity 与 operation receipt。 */
    private static Admission admission(ExecutionKey execution, List<RecordWrite> records,
                                       List<EventWrite> events, String suffix, Instant now) {
        return new Admission(execution, 0, 1, "RUNNING", object("{}"), records, events,
                new OperationIdentity("operation-" + suffix, "turn.admit", FINGERPRINT, object("{}")), now);
    }

    /** 每个场景用隔离会话，保证真实 SQL 写入与分页序号互不依赖测试执行顺序。 */
    private static ConversationKey conversation(String suffix) {
        return new ConversationKey("tenant-" + suffix, "thread-" + suffix, "user-" + suffix);
    }

    /** 创建符合 Store 写入契约的版本化记录。 */
    private static RecordWrite record(String id, JsonObject payload, Instant at) {
        return new RecordWrite(id, "agent.test.database-contract", 1, payload, at);
    }

    /** 创建符合 Store 写入契约的版本化事件。 */
    private static EventWrite event(String id, JsonObject payload, Instant at) {
        return new EventWrite(id, "agent.test.database-contract", 1, payload, at);
    }

    /** 使用 Core 的严格 codec 保持 SQL 合同输入与生产持久化格式一致。 */
    private static JsonObject object(String source) {
        return JSON.decodeObject(source);
    }

    /** 抽取稳定记录标识，检查分页顺序、游标进度与水位隔离。 */
    private static List<String> recordIds(RecordPage page) {
        return page.records().stream().map(AgentStore.StoredRecord::recordId).toList();
    }

    /** 抽取会话记录标识，验证共享记录序号的分页行为。 */
    private static List<String> conversationRecordIds(ConversationRecordPage page) {
        return page.records().stream().map(AgentStore.ConversationRecord::recordId).toList();
    }

    /** 抽取事件标识，验证事件与记录采用一致的 UTF-8 软页规则。 */
    private static List<String> eventIds(EventPage page) {
        return page.events().stream().map(AgentStore.PersistedEvent::eventId).toList();
    }

    /**
     * 保存 SQL 约束探针所需的真实 owner 与执行身份，避免测试数据绕过组合外键。
     * @param conversation 对话 owner，作为测试 SQL 的租户、Thread 与用户外键来源
     * @param execution 从属该对话的执行身份，作为 Turn 和回执约束来源
     */
    private record StoreFixture(ConversationKey conversation,
                                ExecutionKey execution) {
    }
}
