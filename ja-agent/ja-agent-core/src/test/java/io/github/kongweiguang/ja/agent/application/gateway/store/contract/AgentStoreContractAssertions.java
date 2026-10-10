// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway.store.contract;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.Admission;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.CommitIdentity;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.CommitReceipt;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ControlCommit;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ControlKind;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationInitialization;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationKey;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationRecordPage;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationRecordQuery;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationRevision;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.EventPage;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.EventQuery;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.EventWrite;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ExecutionCommit;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ExecutionKey;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.Lease;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.LeaseRequest;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.OperationIdentity;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.OperationKey;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.PersistedEvent;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.RecordPage;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.RecordQuery;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.RecordWrite;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.StoredRecord;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.StoreConflictException;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.TransactionKind;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.WaitControl;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.WaitSettlement;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.infrastructure.json.JsonValueCodecFactory;
import io.github.kongweiguang.ja.support.JsonTestValues;
import org.junit.jupiter.api.Assertions;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationSnapshot;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ExecutionSnapshot;

/** 共享合同断言用于在相互独立的真实 SQL 引擎上验证相同的持久化语义。
 * 这组断言在独立真实 SQL 引擎上复用同一契约，防止 SQLite 或 PostgreSQL 单边实现偏离统一事务语义。
 */
public final class AgentStoreContractAssertions {
    /** 断言工具只提供静态契约检查，禁止实例化以免被误当成有生命周期的存储对象。 */
    private AgentStoreContractAssertions() { }

    /** 验证原子受理、幂等性、带 fencing 的提交、等待结算、分页及参与者回滚。
     * 逐项验证准入幂等、fencing 提交、等待结算、固定水位分页和宿主回调回滚，覆盖 loop 依赖的关键持久边界。
     */
    public static void verify(DataSource dataSource, AgentStore store, AgentStore peerStore) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String tenant = "tenant-" + suffix;
        String threadId = "conversation-" + suffix;
        String userId = "user-" + suffix;
        String turnId = "execution-" + suffix;
        ConversationKey conversation = new ConversationKey(tenant, threadId, userId);
        ExecutionKey execution = new ExecutionKey(conversation, turnId);
        createHostTable(dataSource);

        Instant now = Instant.now();
        verifyConversationLifecycle(store, dataSource, tenant, suffix, now);
        verifyControlCommit(store, peerStore, dataSource, suffix, now);
        EventWrite admitted = event("event-admit-" + suffix, "agent.turn.queued", "{\"state\":\"QUEUED\"}", now);
        RecordWrite user = record("user-" + suffix, "agent.user.message",
                "{\"role\":\"USER\",\"text\":\"你好啊\"}", now);
        OperationIdentity operation = new OperationIdentity("operation-" + suffix, "turn.admit",
                fingerprint("admit-" + suffix), json("{\"turnId\":\"" + turnId + "\"}"));
        Admission admission = new Admission(execution, 0, 1, "QUEUED", json("{\"kind\":\"READY\"}"),
                List.of(user), List.of(admitted), operation, now);

        AgentStore.AdmissionReceipt firstAdmission = store.admit(admission);
        Assertions.assertTrue(firstAdmission.newlyAdmitted());
        Assertions.assertEquals(1, firstAdmission.threadRevision());
        Assertions.assertEquals(1, firstAdmission.mutationVersion());
        AgentStore.AdmissionReceipt retriedAdmission = store.admit(admission);
        Assertions.assertFalse(retriedAdmission.newlyAdmitted());
        Assertions.assertEquals(firstAdmission.receipt(), retriedAdmission.receipt());
        Assertions.assertEquals(1, store.readReceipt(new OperationKey(tenant, userId, operation.operationKey()))
                .orElseThrow().threadRevision());
        PersistedEvent admittedEvent = store.readEvents(EventQuery.latestWithDefaultMaxBytes(
                conversation, 0, 10)).events().getFirst();
        Assertions.assertEquals(1, admittedEvent.threadRevision());
        Assertions.assertEquals(1, admittedEvent.mutationVersion());
        Assertions.assertEquals(1, admittedEvent.lastRecordSequence());
        StoredRecord admittedRecord = store.readRecord(execution, user.recordId()).orElseThrow();
        Assertions.assertEquals(user.recordId(), admittedRecord.recordId());
        Assertions.assertEquals(user.recordType(), admittedRecord.recordType());
        Assertions.assertEquals(1, admittedRecord.sequence());
        Assertions.assertEquals(user.payload(), admittedRecord.payload());
        Assertions.assertTrue(store.readRecord(execution, "missing-" + suffix).isEmpty());
        Assertions.assertTrue(store.readRecord(new ExecutionKey(conversation, turnId + "-other"),
                user.recordId()).isEmpty());
        verifyOperationKeyConflicts(store, admission, operation, suffix);

        Lease lease = store.claimLease(new LeaseRequest(conversation, "owner-" + suffix,
                Instant.now(), Instant.now().plusSeconds(300)));
        Assertions.assertEquals(1, lease.fencingToken());
        ExecutionCommit failed = commit(execution, firstAdmission.threadRevision(), "QUEUED", 1,
                lease, suffix, "RUNNING", List.of(
                        record("assistant-failed-" + suffix, "agent.assistant.message", "{\"text\":\"partial\"}", now),
                        record("usage-failed-" + suffix, "agent.model.usage", "{\"inputTokens\":2}", now)),
                List.of(event("event-failed-" + suffix, "agent.model.completed", "{\"round\":1}", now)));

        Assertions.assertThrows(IllegalStateException.class, () -> store.commit(failed, transaction -> {
            Assertions.assertEquals(TransactionKind.COMMIT, transaction.kind());
            Assertions.assertEquals(failed.records(), transaction.records());
            participantSql(() -> insertHostFact(transaction.resources().unwrap(Connection.class),
                    suffix, "rolled-back"));
            throw new IllegalStateException("force transaction rollback");
        }));
        Assertions.assertEquals(1, store.loadConversation(conversation).orElseThrow().revision());
        Assertions.assertEquals(1, store.loadExecution(execution).orElseThrow().lastRecordSequence());
        Assertions.assertEquals(1, store.readEvents(EventQuery.latestWithDefaultMaxBytes(
                conversation, 0, 10)).events().size());
        Assertions.assertEquals(0, hostRows(dataSource, suffix));

        CommitReceipt committed = store.commit(failed);
        Assertions.assertTrue(committed.newlyCommitted());
        Assertions.assertEquals(2, committed.threadRevision());
        Assertions.assertEquals(2, committed.mutationVersion());
        CommitReceipt commitRetry = store.commit(failed);
        Assertions.assertFalse(commitRetry.newlyCommitted());
        Assertions.assertEquals(committed.receipt(), commitRetry.receipt());
        Assertions.assertEquals(committed.receipt(), store.reconcile(failed.identity()).orElseThrow().receipt());
        List<PersistedEvent> admissionAndCommitEvents = store.readEvents(EventQuery.latestWithDefaultMaxBytes(
                conversation, 0, 10)).events();
        Assertions.assertEquals(2, admissionAndCommitEvents.size());
        assertEventWatermarks(admissionAndCommitEvents.get(0), 1, 1, 1);
        assertEventWatermarks(admissionAndCommitEvents.get(1), 2, 2, 3);

        long recordWatermark = store.loadConversation(conversation).orElseThrow().recordSequence();
        RecordPage firstRecords = store.readRecords(RecordQuery.withDefaultMaxBytes(execution, 0,
                recordWatermark, 1));
        Assertions.assertEquals(1, firstRecords.records().size());
        Assertions.assertTrue(firstRecords.hasMore());
        RecordPage remainingRecords = store.readRecords(RecordQuery.withDefaultMaxBytes(execution,
                firstRecords.nextSequence(), recordWatermark, 10));
        Assertions.assertEquals(2, remainingRecords.records().size());
        Assertions.assertEquals("agent.assistant.message", remainingRecords.records().getFirst().recordType());
        long eventWatermark = store.loadConversation(conversation).orElseThrow().eventSequence();
        EventPage firstEvents = store.readEvents(EventQuery.withDefaultMaxBytes(conversation, 0,
                eventWatermark, 1));
        Assertions.assertEquals(1, firstEvents.events().getFirst().sequence());
        Assertions.assertTrue(firstEvents.hasMore());
        EventPage nextEvents = store.readEvents(EventQuery.withDefaultMaxBytes(conversation,
                firstEvents.nextSequence(), eventWatermark, 10));
        Assertions.assertEquals(2, nextEvents.events().getFirst().sequence());
        long admissionWatermark = store.loadConversation(conversation).orElseThrow().latestExecutionSequence();
        Assertions.assertEquals(1, store.readExecutions(conversation, 0, admissionWatermark, 10).executions().size());

        ExecutionCommit waitTransition = commit(execution, 2, "RUNNING", 2, lease, suffix + "-wait", "RUNNING",
                List.of(record("answer-" + suffix, "agent.wait.answer", "{\"text\":\"yes\"}", now),
                        record("tool-result-" + suffix, "agent.tool.result", "{\"state\":\"SUCCEEDED\"}", now)),
                List.of(event("event-answer-" + suffix, "agent.wait.settled", "{\"interactionId\":\"wait-1\"}", now)));
        CommitReceipt settled = store.settleWait(new WaitSettlement(waitTransition, "wait-" + suffix,
                "answer-" + suffix, List.of("tool-result-" + suffix)));
        Assertions.assertEquals(3, settled.threadRevision());
        Assertions.assertEquals(3, settled.mutationVersion());
        Assertions.assertThrows(IllegalArgumentException.class, () -> new WaitSettlement(waitTransition,
                "wait-" + suffix, "answer-" + suffix,
                List.of("tool-result-" + suffix, "tool-result-" + suffix)));
        ExecutionCommit noToolWaitTransition = commit(execution, 3, "RUNNING", 3, lease,
                suffix + "-clarification", "RUNNING",
                List.of(record("clarification-answer-" + suffix, "agent.wait.answer",
                        "{\"text\":\"clarified\"}", now)),
                List.of(event("event-clarification-" + suffix, "agent.wait.settled",
                        "{\"interactionId\":\"clarification-" + suffix + "\"}", now)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new WaitSettlement(
                noToolWaitTransition, "clarification-" + suffix,
                "clarification-answer-" + suffix, List.of("missing-tool-result-" + suffix)));
        CommitReceipt clarificationSettled = store.settleWait(new WaitSettlement(noToolWaitTransition,
                "clarification-" + suffix, "clarification-answer-" + suffix, List.of()), transaction -> {
            Assertions.assertEquals(TransactionKind.WAIT_SETTLEMENT, transaction.kind());
            Assertions.assertEquals("clarification-" + suffix, transaction.interactionId());
            Assertions.assertEquals("clarification-answer-" + suffix, transaction.answerId());
            Assertions.assertEquals(1, transaction.records().size());
            Assertions.assertEquals("agent.wait.answer", transaction.records().getFirst().recordType());
        });
        Assertions.assertEquals(4, clarificationSettled.threadRevision());
        Assertions.assertEquals(4, clarificationSettled.mutationVersion());
        List<PersistedEvent> laterEvents = store.readEvents(EventQuery.latestWithDefaultMaxBytes(
                conversation, 0, 10)).events();
        Assertions.assertEquals(4, laterEvents.size());
        assertEventWatermarks(laterEvents.get(0), 1, 1, 1);
        assertEventWatermarks(laterEvents.get(1), 2, 2, 3);
        assertEventWatermarks(laterEvents.get(2), 3, 3, 5);
        assertEventWatermarks(laterEvents.get(3), 4, 4, 6);
        Assertions.assertThrows(StoreConflictException.class, () -> peerStore.claimLease(new LeaseRequest(
                conversation, "other-owner-" + suffix, Instant.now(), Instant.now().plusSeconds(300))));

        Lease oldLease = lease;
        store.releaseLease(oldLease);
        Lease newLease = store.claimLease(new LeaseRequest(conversation, oldLease.ownerId(),
                Instant.now(), Instant.now().plusSeconds(300)));
        Assertions.assertTrue(newLease.fencingToken() > oldLease.fencingToken());
        ExecutionCommit stale = commit(execution, 4, "RUNNING", 4, oldLease, suffix + "-stale", "RUNNING",
                List.of(record("stale-" + suffix, "agent.test.stale", "{\"ignored\":true}", now)),
                List.of(event("event-stale-" + suffix, "agent.test.stale", "{\"ignored\":true}", now)));
        Assertions.assertThrows(StoreConflictException.class, () -> store.commit(stale));
        Assertions.assertEquals(4, store.loadConversation(conversation).orElseThrow().revision());

        testTenantIsolation(store, execution, operation, suffix);
        testConcurrentCompareAndSet(store, peerStore, execution, conversation, newLease, suffix, now);
        verifyByteBudgetPagination(store, suffix, now);
    }

    /** 验证 Thread 元数据可独立 CAS 修改，且只有执行事实进入记录流并携带执行身份。 */
    private static void verifyConversationLifecycle(AgentStore store, DataSource source,
                                                    String tenant, String suffix, Instant now) throws Exception {
        String scenario = "conversation-" + suffix;
        ConversationKey conversation = new ConversationKey(tenant, "thread-" + scenario, "user-" + suffix);
        ConversationSnapshot initialized = store.initializeConversation(
                new ConversationInitialization(conversation, now), transaction -> {
                    Assertions.assertEquals(-1, transaction.expectedRevision());
                    Assertions.assertEquals(0, transaction.committedRevision());
                    participantSql(() -> insertHostFact(transaction.resources().unwrap(Connection.class),
                            scenario, "thread-created"));
                });
        Assertions.assertEquals(0, initialized.revision());
        Assertions.assertEquals(0, initialized.recordSequence());
        Assertions.assertTrue(store.readConversationRecords(ConversationRecordQuery.withDefaultMaxBytes(
                conversation, 0, initialized.recordSequence(), 10)).records().isEmpty());

        ConversationSnapshot revised = store.reviseConversation(
                new ConversationRevision(conversation, 0, now), transaction -> {
                    Assertions.assertEquals(0, transaction.expectedRevision());
                    Assertions.assertEquals(1, transaction.committedRevision());
                    participantSql(() -> insertHostFact(transaction.resources().unwrap(Connection.class),
                            scenario, "thread-renamed"));
                });
        Assertions.assertEquals(1, revised.revision());
        Assertions.assertEquals(0, revised.recordSequence());
        Assertions.assertThrows(StoreConflictException.class,
                () -> store.reviseConversation(new ConversationRevision(conversation, 0, now)));
        Assertions.assertThrows(StoreConflictException.class,
                () -> store.initializeConversation(new ConversationInitialization(conversation, now)));

        ExecutionKey execution = new ExecutionKey(conversation, "first-execution-" + suffix);
        RecordWrite user = record("first-user-" + suffix, "agent.user.message", "{\"text\":\"next\"}", now);
        OperationIdentity operation = new OperationIdentity("first-op-" + suffix, "turn.admit",
                fingerprint("first-admit-" + suffix), json("{\"accepted\":true}"));
        store.admit(new Admission(execution, 1, 1, "QUEUED", json("{\"kind\":\"READY\"}"),
                List.of(user), List.of(), operation, now));

        ConversationRecordPage records = store.readConversationRecords(
                ConversationRecordQuery.withDefaultMaxBytes(conversation, 0, 1, 10));
        Assertions.assertEquals(1, records.records().size());
        Assertions.assertEquals(execution, records.records().getFirst().execution());
        Assertions.assertEquals(user.recordType(), records.records().getFirst().recordType());
        Assertions.assertEquals(1, records.nextSequence());
        Assertions.assertEquals(2, hostRows(source, scenario));
    }

    /** 验证等待结算及输入、取消、暂停和工具恢复控制均保留游标并共享无租约事务契约。 */
    private static void verifyControlCommit(AgentStore store, AgentStore peerStore, DataSource dataSource,
                                            String suffix, Instant now) throws Exception {
        String scenario = "control-" + suffix;
        ConversationKey conversation = new ConversationKey("tenant-" + scenario, "conversation-" + scenario,
                "user-" + scenario);
        ExecutionKey execution = new ExecutionKey(conversation, "execution-" + scenario);
        JsonObject cursor = json("{\"kind\":\"WAITING\",\"step\":\"human-input\"}");
        RecordWrite persistedWait = record("pending-wait-" + scenario, "agent.interaction.wait",
                "{\"opaque\":true}", now);
        OperationIdentity admissionOperation = new OperationIdentity("admit-" + scenario, "turn.admit",
                fingerprint("admit-control-" + scenario), json("{\"accepted\":true}"));
        store.admit(new Admission(execution, 0, 1, "WAITING_INPUT", cursor, List.of(persistedWait),
                List.of(event("queued-" + scenario, "agent.turn.queued", "{\"state\":\"WAITING_INPUT\"}", now)),
                admissionOperation, now));

        Lease ownerLease = store.claimLease(new LeaseRequest(conversation, "owner-" + scenario,
                now, now.plusSeconds(300)));
        Assertions.assertThrows(StoreConflictException.class, () -> peerStore.claimLease(new LeaseRequest(
                conversation, "peer-" + scenario, now, now.plusSeconds(300))));
        ConversationSnapshot waitingConversation = store.loadConversation(conversation).orElseThrow();
        ExecutionSnapshot waitingExecution = store.loadExecution(execution).orElseThrow();
        String answerId = "answer-" + scenario;
        ControlCommit settlement = control(execution, waitingConversation.revision(), waitingExecution.state(),
                waitingExecution.mutationVersion(), ControlKind.WAIT_SETTLEMENT, scenario + "-settle",
                List.of(record(answerId, "agent.interaction.answer", "{\"answer\":\"yes\"}", now)),
                List.of(event("settled-event-" + scenario, "agent.wait.settled", "{\"interactionId\":\"wait-"
                        + scenario + "\"}", now)),
                Optional.of(new WaitControl("wait-" + scenario, 1, Optional.of(answerId))), now);
        CommitReceipt settled = store.controlCommit(settlement, transaction -> {
            Assertions.assertEquals(TransactionKind.CONTROL, transaction.kind());
            Assertions.assertEquals(0, transaction.fencingToken());
            Assertions.assertEquals("SUSPENDED", transaction.state());
            Assertions.assertEquals(cursor, transaction.cursor());
            Assertions.assertEquals(waitingExecution.cursorSchemaVersion(), transaction.cursorSchemaVersion());
            Assertions.assertEquals("wait-" + scenario, transaction.interactionId());
            Assertions.assertEquals(answerId, transaction.answerId());
            Assertions.assertEquals(settlement.records(), transaction.records());
            participantSql(() -> insertHostFact(transaction.resources().unwrap(Connection.class),
                    scenario, "wait-settled"));
        });
        Assertions.assertTrue(settled.newlyCommitted());
        Assertions.assertEquals(2, settled.threadRevision());
        Assertions.assertEquals(2, settled.mutationVersion());
        ExecutionSnapshot suspendedExecution = store.loadExecution(execution).orElseThrow();
        Assertions.assertEquals("SUSPENDED", suspendedExecution.state());
        Assertions.assertEquals(cursor, suspendedExecution.cursor());
        Assertions.assertEquals(2, suspendedExecution.lastRecordSequence());
        CommitReceipt settledReplay = store.controlCommit(settlement, transaction -> Assertions.fail(
                "idempotent control replay must not re-run its participant"));
        Assertions.assertFalse(settledReplay.newlyCommitted());
        Assertions.assertEquals(settled.receipt(), settledReplay.receipt());
        Assertions.assertEquals(1, hostRows(dataSource, scenario));
        store.releaseLease(ownerLease);

        for (ControlKind kind : List.of(ControlKind.INPUT, ControlKind.CANCEL_REQUEST,
                ControlKind.SUSPEND_REQUEST, ControlKind.SUSPEND_CONSUME, ControlKind.TOOL_RECOVERY)) {
            ConversationSnapshot beforeConversation = store.loadConversation(conversation).orElseThrow();
            ExecutionSnapshot beforeExecution = store.loadExecution(execution).orElseThrow();
            String kindName = kind.name().toLowerCase(Locale.ROOT);
            String recordType = controlRecordType(kind);
            ControlCommit request = control(execution, beforeConversation.revision(), beforeExecution.state(),
                    beforeExecution.mutationVersion(), kind, scenario + "-" + kindName,
                    List.of(record("fact-" + kindName + "-" + scenario,
                            recordType, "{\"kind\":\"" + kind + "\"}", now)),
                    List.of(event("event-" + kindName + "-" + scenario,
                            "agent.test.control", "{\"kind\":\"" + kind + "\"}", now)), Optional.empty(), now);
            if (kind == ControlKind.TOOL_RECOVERY) {
                Assertions.assertThrows(IllegalStateException.class, () -> store.controlCommit(request, transaction -> {
                    Assertions.assertEquals(0, transaction.fencingToken());
                    participantSql(() -> insertHostFact(transaction.resources().unwrap(Connection.class),
                            scenario, "control-rollback"));
                    throw new IllegalStateException("force control rollback");
                }));
                Assertions.assertEquals(beforeConversation.revision(),
                        store.loadConversation(conversation).orElseThrow().revision());
                Assertions.assertEquals(beforeExecution.mutationVersion(),
                        store.loadExecution(execution).orElseThrow().mutationVersion());
                Assertions.assertTrue(store.readRecord(execution, request.records().getFirst().recordId()).isEmpty());
                Assertions.assertTrue(store.readReceipt(new OperationKey(conversation.tenantId(), conversation.userId(),
                        request.operation().operationKey())).isEmpty());
                Assertions.assertEquals(1, hostRows(dataSource, scenario));
            }
            CommitReceipt committed = store.controlCommit(request, transaction -> {
                Assertions.assertEquals(TransactionKind.CONTROL, transaction.kind());
                Assertions.assertEquals(0, transaction.fencingToken());
                Assertions.assertEquals(beforeExecution.state(), transaction.state());
                Assertions.assertEquals(beforeExecution.cursorSchemaVersion(), transaction.cursorSchemaVersion());
                Assertions.assertEquals(beforeExecution.cursor(), transaction.cursor());
            });
            Assertions.assertEquals(beforeConversation.revision() + 1, committed.threadRevision());
            Assertions.assertEquals(beforeExecution.mutationVersion() + 1, committed.mutationVersion());
            ExecutionSnapshot afterExecution = store.loadExecution(execution).orElseThrow();
            Assertions.assertEquals(beforeExecution.state(), afterExecution.state());
            Assertions.assertEquals(beforeExecution.cursorSchemaVersion(), afterExecution.cursorSchemaVersion());
            Assertions.assertEquals(beforeExecution.cursor(), afterExecution.cursor());
        }

        ConversationSnapshot raceConversation = store.loadConversation(conversation).orElseThrow();
        ExecutionSnapshot raceExecution = store.loadExecution(execution).orElseThrow();
        List<ControlCommit> competing = List.of(
                control(execution, raceConversation.revision(), raceExecution.state(), raceExecution.mutationVersion(),
                        ControlKind.INPUT, scenario + "-race-a",
                        List.of(record("race-record-a-" + scenario, "agent.input.queue.command", "{\"item\":\"a\"}", now)),
                        List.of(event("race-event-a-" + scenario, "agent.input.queued", "{\"item\":\"a\"}", now)),
                        Optional.empty(), now),
                control(execution, raceConversation.revision(), raceExecution.state(), raceExecution.mutationVersion(),
                        ControlKind.INPUT, scenario + "-race-b",
                        List.of(record("race-record-b-" + scenario, "agent.input.queue.command", "{\"item\":\"b\"}", now)),
                        List.of(event("race-event-b-" + scenario, "agent.input.queued", "{\"item\":\"b\"}", now)),
                        Optional.empty(), now));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<CommitReceipt>> attempts = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                int selected = index;
                attempts.add(pool.submit(() -> {
                    start.await();
                    try {
                        return (selected == 0 ? store : peerStore).controlCommit(competing.get(selected));
                    } catch (StoreConflictException conflict) {
                        return null;
                    }
                }));
            }
            start.countDown();
            CommitReceipt winnerReceipt = null;
            ControlCommit winnerRequest = null;
            for (int index = 0; index < attempts.size(); index++) {
                CommitReceipt result = attempts.get(index).get();
                if (result != null) {
                    Assertions.assertNull(winnerReceipt, "only one same-snapshot control CAS may win");
                    winnerReceipt = result;
                    winnerRequest = competing.get(index);
                }
            }
            Assertions.assertNotNull(winnerReceipt);
            CommitReceipt replay = store.controlCommit(winnerRequest);
            Assertions.assertFalse(replay.newlyCommitted());
            Assertions.assertEquals(winnerReceipt.receipt(), replay.receipt());
            ControlCommit losing = competing.get(competing.indexOf(winnerRequest) == 0 ? 1 : 0);
            Assertions.assertTrue(store.readRecord(execution, losing.records().getFirst().recordId()).isEmpty());
            Assertions.assertTrue(store.readRecord(execution, winnerRequest.records().getFirst().recordId()).isPresent());
            Assertions.assertEquals(raceConversation.revision() + 1,
                    store.loadConversation(conversation).orElseThrow().revision());
            Assertions.assertEquals(raceExecution.mutationVersion() + 1,
                    store.loadExecution(execution).orElseThrow().mutationVersion());
        } finally {
            pool.shutdownNow();
        }
        verifySuspendRequestWhileLeaseIsHeld(store, peerStore, scenario, now);
    }

    /** 暂停请求在执行租约仍由另一个节点持有时只追加意图，不接管或覆盖执行游标。 */
    private static void verifySuspendRequestWhileLeaseIsHeld(AgentStore store, AgentStore peerStore,
                                                              String scenario, Instant now) {
        ConversationKey conversation = new ConversationKey("tenant-suspend-" + scenario,
                "conversation-suspend-" + scenario, "user-suspend-" + scenario);
        ExecutionKey execution = new ExecutionKey(conversation, "execution-suspend-" + scenario);
        JsonObject cursor = json("{\"step\":\"assistant\"}");
        OperationIdentity admissionOperation = new OperationIdentity("admit-suspend-" + scenario,
                "turn.admit", fingerprint("admit-suspend-body-" + scenario), json("{\"accepted\":true}"));
        store.admit(new Admission(execution, 0, 1, "RUNNING", cursor,
                List.of(record("running-suspend-" + scenario, "agent.execution.started", "{}", now)),
                List.of(event("running-suspend-event-" + scenario, "agent.turn.started", "{}", now)),
                admissionOperation, now));
        Lease ownerLease = store.claimLease(new LeaseRequest(conversation, "owner-suspend-" + scenario,
                now, now.plusSeconds(300)));
        ConversationSnapshot beforeConversation = store.loadConversation(conversation).orElseThrow();
        ExecutionSnapshot beforeExecution = store.loadExecution(execution).orElseThrow();
        ControlCommit request = control(execution, beforeConversation.revision(), beforeExecution.state(),
                beforeExecution.mutationVersion(), ControlKind.SUSPEND_REQUEST, scenario + "-active-suspend",
                List.of(record("suspend-request-" + scenario, "agent.execution.suspend-request",
                        "{\"requested\":true}", now)),
                List.of(event("suspend-request-event-" + scenario, "agent.execution.suspend-requested",
                        "{\"requested\":true}", now)), Optional.empty(), now);

        store.controlCommit(request);

        ExecutionSnapshot afterExecution = store.loadExecution(execution).orElseThrow();
        Assertions.assertEquals("RUNNING", afterExecution.state());
        Assertions.assertEquals(beforeExecution.cursor(), afterExecution.cursor());
        Assertions.assertEquals(beforeExecution.cursorSchemaVersion(), afterExecution.cursorSchemaVersion());
        Assertions.assertEquals(beforeExecution.mutationVersion() + 1, afterExecution.mutationVersion());
        Assertions.assertThrows(StoreConflictException.class, () -> peerStore.claimLease(new LeaseRequest(
                conversation, "peer-suspend-" + scenario, now, now.plusSeconds(300))));
        store.releaseLease(ownerLease);
    }

    /** 为有控制语义要求的操作写入对应稳定事实类型，保持 Core 校验与数据库适配器解耦。 */
    private static String controlRecordType(ControlKind kind) {
        return switch (kind) {
            case INPUT -> "agent.input.queue.command";
            case CANCEL_REQUEST -> "agent.execution.cancel-request";
            case SUSPEND_REQUEST -> "agent.execution.suspend-request";
            case SUSPEND_CONSUME -> "agent.execution.suspend-consumed";
            case WAIT_SETTLEMENT -> "agent.interaction.answer";
            case TOOL_RECOVERY -> "agent.tool.recovery.command";
        };
    }

    /** 构造绑定唯一操作身份的控制请求，便于在两个 SQL adapter 上复用相同 CAS 场景。 */
    private static ControlCommit control(ExecutionKey execution, long threadRevision, String expectedState,
                                         long mutationVersion, ControlKind kind, String suffix,
                                         List<RecordWrite> records, List<EventWrite> events,
                                         Optional<WaitControl> wait, Instant now) {
        OperationIdentity operation = new OperationIdentity("control-op-" + suffix, "control." + kind.name(),
                fingerprint("control-body-" + suffix), json("{\"accepted\":true}"));
        return new ControlCommit(execution, threadRevision, expectedState, mutationVersion, kind,
                records, events, operation, wait, now);
    }

    /** 从同一冻结 revision 并发执行多个状态转换，并要求持久化结果中恰有一个胜出者。
     * 两个独立 store 实例基于同一快照竞争 CAS，只有一个提交成功，证明互斥来自数据库而非 JVM 锁。
     */
    private static void testConcurrentCompareAndSet(AgentStore firstStore, AgentStore secondStore,
                                                   ExecutionKey execution,
                                                   ConversationKey conversation, Lease lease,
                                                   String suffix, Instant now) throws Exception {
        ConversationSnapshot currentConversation = firstStore.loadConversation(conversation).orElseThrow();
        ExecutionSnapshot currentExecution = firstStore.loadExecution(execution).orElseThrow();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int index = i;
                attempts.add(pool.submit(() -> {
                    start.await();
                    ExecutionCommit request = commit(execution, currentConversation.revision(),
                            currentExecution.state(), currentExecution.mutationVersion(), lease, suffix + index,
                            "RUNNING", List.of(record("race-record-" + index + "-" + suffix,
                                    "agent.test.race", "{\"winner\":" + index + "}", now)),
                            List.of(event("race-event-" + index + "-" + suffix, "agent.test.race",
                                    "{\"winner\":" + index + "}", now)));
                    try {
                        (index == 0 ? firstStore : secondStore).commit(request);
                        return true;
                    } catch (StoreConflictException expected) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get()) {
                winners++;
            }
            }
            Assertions.assertEquals(1, winners);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 验证相同的 conversation id 在所有主键和读取路径上仍按 tenant 隔离。
     * 使用同一会话 ID 跨租户读取与写入，确认每条主键路径都受 tenant 隔离。
     */
    private static void testTenantIsolation(AgentStore store, ExecutionKey original,
                                            OperationIdentity operation, String suffix) {
        ConversationKey foreign = new ConversationKey("other-tenant-" + suffix,
                original.conversation().threadId(), original.conversation().userId());
        Assertions.assertTrue(store.loadConversation(foreign).isEmpty());
        Assertions.assertTrue(store.loadExecution(new ExecutionKey(foreign, original.turnId())).isEmpty());
        Assertions.assertTrue(store.readRecord(new ExecutionKey(foreign, original.turnId()),
                "user-" + suffix).isEmpty());
        Assertions.assertTrue(store.readEvents(EventQuery.latestWithDefaultMaxBytes(foreign, 0, 10))
                .events().isEmpty());
        Assertions.assertTrue(store.readConversationRecords(ConversationRecordQuery.withDefaultMaxBytes(
                foreign, 0, Long.MAX_VALUE, 10)).records().isEmpty());
        Assertions.assertTrue(store.readReceipt(new OperationKey(foreign.tenantId(), foreign.userId(),
                operation.operationKey())).isEmpty());

        ConversationKey foreignUser = new ConversationKey(original.conversation().tenantId(),
                original.conversation().threadId(), "other-user-" + suffix);
        Assertions.assertTrue(store.loadConversation(foreignUser).isEmpty());
        Assertions.assertTrue(store.loadExecution(new ExecutionKey(foreignUser, original.turnId())).isEmpty());
        Assertions.assertTrue(store.readEvents(EventQuery.latestWithDefaultMaxBytes(
                foreignUser, 0, 10)).events().isEmpty());
        Assertions.assertTrue(store.readConversationRecords(ConversationRecordQuery.withDefaultMaxBytes(
                foreignUser, 0, Long.MAX_VALUE, 10)).records().isEmpty());
        Assertions.assertTrue(store.readReceipt(new OperationKey(foreignUser.tenantId(), foreignUser.userId(),
                operation.operationKey())).isEmpty());
    }

    /** 验证同一幂等键绑定操作类型、正文指纹、Turn 与 Thread，避免跨操作误回放旧回执。 */
    private static void verifyOperationKeyConflicts(AgentStore store, Admission original,
                                                     OperationIdentity operation, String suffix) {
        assertAdmissionConflict(store, original, new OperationIdentity(operation.operationKey(), "turn.cancel",
                operation.fingerprint(), operation.receipt()), original.execution());
        assertAdmissionConflict(store, original, new OperationIdentity(operation.operationKey(),
                operation.operationType(), fingerprint("changed-content-" + suffix), operation.receipt()),
                original.execution());

        ExecutionKey otherTurn = new ExecutionKey(original.execution().conversation(),
                original.execution().turnId() + "-reused");
        assertAdmissionConflict(store, original, operation, otherTurn);

        ConversationKey otherThread = new ConversationKey(original.execution().conversation().tenantId(),
                original.execution().conversation().threadId() + "-reused", original.execution().conversation().userId());
        assertAdmissionConflict(store, original, operation, new ExecutionKey(otherThread,
                original.execution().turnId()));
    }

    /** 复用初始事务内容，只替换要冲突的幂等身份维度，确保失败发生在任何新写入之前。 */
    private static void assertAdmissionConflict(AgentStore store, Admission original,
                                                OperationIdentity operation, ExecutionKey execution) {
        Admission conflicting = new Admission(execution, original.expectedThreadRevision(),
                original.cursorSchemaVersion(), original.state(), original.cursor(), original.records(),
                original.events(), operation, original.occurredAt());
        Assertions.assertThrows(StoreConflictException.class, () -> store.admit(conflicting));
    }

    /** 验证 UTF-8 软预算、超预算首项单独返回、所有游标前进及固定水位不读入后续提交。 */
    private static void verifyByteBudgetPagination(AgentStore store, String suffix, Instant now) {
        String scenario = "byte-page-" + suffix;
        ConversationKey conversation = new ConversationKey("tenant-" + scenario,
                "thread-" + scenario, "user-" + scenario);
        ExecutionKey execution = new ExecutionKey(conversation, "turn-" + scenario);
        JsonObject multibyte = json("{\"text\":\"你好啊\"}");
        JsonObject small = json("{}");
        long byteBudget = 16;
        Assertions.assertTrue(jsonBytes(multibyte) > byteBudget);
        Assertions.assertTrue(jsonBytes(small) <= byteBudget);
        RecordWrite multibyteRecord = new RecordWrite("record-utf8-" + scenario,
                "agent.test.utf8", 1, multibyte, now);
        RecordWrite smallRecord = new RecordWrite("record-small-" + scenario,
                "agent.test.small", 1, small, now);
        EventWrite multibyteEvent = new EventWrite("event-utf8-" + scenario,
                "agent.test.utf8", 1, multibyte, now);
        EventWrite smallEvent = new EventWrite("event-small-" + scenario,
                "agent.test.small", 1, small, now);
        OperationIdentity operation = new OperationIdentity("admit-" + scenario, "turn.admit",
                fingerprint("admit-" + scenario), json("{\"accepted\":true}"));
        store.admit(new Admission(execution, 0, 1, "RUNNING", json("{}"),
                List.of(multibyteRecord, smallRecord), List.of(multibyteEvent, smallEvent), operation, now));

        long recordWatermark = store.loadConversation(conversation).orElseThrow().recordSequence();
        RecordPage firstRecords = store.readRecords(new RecordQuery(execution, 0, recordWatermark, 10, byteBudget));
        Assertions.assertEquals(List.of(multibyteRecord.recordId()), recordIds(firstRecords));
        Assertions.assertEquals(1, firstRecords.nextSequence());
        Assertions.assertTrue(firstRecords.hasMore());
        RecordPage secondRecords = store.readRecords(new RecordQuery(execution, firstRecords.nextSequence(),
                recordWatermark, 10, byteBudget));
        Assertions.assertEquals(List.of(smallRecord.recordId()), recordIds(secondRecords));
        Assertions.assertEquals(2, secondRecords.nextSequence());
        Assertions.assertFalse(secondRecords.hasMore());

        long eventWatermark = store.loadConversation(conversation).orElseThrow().eventSequence();
        EventPage firstEvents = store.readEvents(new EventQuery(conversation, 0, eventWatermark, 10, byteBudget));
        Assertions.assertEquals(List.of(multibyteEvent.eventId()), eventIds(firstEvents));
        Assertions.assertEquals(1, firstEvents.nextSequence());
        Assertions.assertTrue(firstEvents.hasMore());
        EventPage secondEvents = store.readEvents(new EventQuery(conversation, firstEvents.nextSequence(),
                eventWatermark, 10, byteBudget));
        Assertions.assertEquals(List.of(smallEvent.eventId()), eventIds(secondEvents));
        Assertions.assertEquals(2, secondEvents.nextSequence());
        Assertions.assertFalse(secondEvents.hasMore());

        ConversationRecordPage firstConversationRecords = store.readConversationRecords(
                new ConversationRecordQuery(conversation, 0, recordWatermark, 10, byteBudget));
        Assertions.assertEquals(List.of(multibyteRecord.recordId()), conversationRecordIds(firstConversationRecords));
        Assertions.assertEquals(1, firstConversationRecords.nextSequence());
        Assertions.assertTrue(firstConversationRecords.hasMore());
        ConversationRecordPage secondConversationRecords = store.readConversationRecords(
                new ConversationRecordQuery(conversation, firstConversationRecords.nextSequence(),
                        recordWatermark, 10, byteBudget));
        Assertions.assertEquals(List.of(smallRecord.recordId()), conversationRecordIds(secondConversationRecords));
        Assertions.assertEquals(2, secondConversationRecords.nextSequence());
        Assertions.assertFalse(secondConversationRecords.hasMore());

        Lease lease = store.claimLease(new LeaseRequest(conversation, "owner-" + scenario,
                now, now.plusSeconds(300)));
        ConversationSnapshot snapshot = store.loadConversation(conversation).orElseThrow();
        ExecutionSnapshot executionSnapshot = store.loadExecution(execution).orElseThrow();
        store.commit(commit(execution, snapshot.revision(), executionSnapshot.state(),
                executionSnapshot.mutationVersion(), lease, scenario + "-append", "RUNNING",
                List.of(record("record-later-" + scenario, "agent.test.later", "{}", now)),
                List.of(event("event-later-" + scenario, "agent.test.later", "{}", now))));

        RecordPage fixedRecords = store.readRecords(RecordQuery.withDefaultMaxBytes(execution,
                0, recordWatermark, 10));
        Assertions.assertEquals(List.of(multibyteRecord.recordId(), smallRecord.recordId()), recordIds(fixedRecords));
        EventPage fixedEvents = store.readEvents(EventQuery.withDefaultMaxBytes(conversation, 0,
                eventWatermark, 10));
        Assertions.assertEquals(List.of(multibyteEvent.eventId(), smallEvent.eventId()), eventIds(fixedEvents));
        ConversationRecordPage fixedConversationRecords = store.readConversationRecords(
                ConversationRecordQuery.withDefaultMaxBytes(conversation, 0, recordWatermark, 10));
        Assertions.assertEquals(List.of(multibyteRecord.recordId(), smallRecord.recordId()),
                conversationRecordIds(fixedConversationRecords));
        Assertions.assertEquals(2, fixedRecords.nextSequence());
        Assertions.assertEquals(2, fixedEvents.nextSequence());
        Assertions.assertEquals(2, fixedConversationRecords.nextSequence());
    }

    /** 提取记录 ID 以同时检查排序、无重复和无遗漏。 */
    private static List<String> recordIds(RecordPage page) {
        return page.records().stream().map(AgentStore.StoredRecord::recordId).toList();
    }

    /** 提取会话记录 ID 并保留共享序号分页的顺序。 */
    private static List<String> conversationRecordIds(ConversationRecordPage page) {
        return page.records().stream().map(AgentStore.ConversationRecord::recordId).toList();
    }

    /** 提取事件 ID 以检查字节预算分页未重复或丢失事件。 */
    private static List<String> eventIds(EventPage page) {
        return page.events().stream().map(PersistedEvent::eventId).toList();
    }

    /** 使用实际 Core 序列化结果计数，固定合同中 UTF-8 字节预算的边界样例。 */
    private static int jsonBytes(JsonObject value) {
        return JsonValueCodecFactory.standard().encode(value).getBytes(StandardCharsets.UTF_8).length;
    }

    /** 使用稳定的 fingerprint 和当前 lease generation 创建下一次不可变状态转换。
     * 构造带当前租约代际和稳定指纹的下一次提交，供幂等与旧 owner 拒绝断言复用。
     */
    private static ExecutionCommit commit(ExecutionKey execution, long threadRevision, String expectedState,
                                          long mutationVersion, Lease lease, String keySuffix, String nextState,
                                          List<RecordWrite> records, List<EventWrite> events) {
        CommitIdentity identity = new CommitIdentity(execution, "commit-" + keySuffix,
                fingerprint("commit-body-" + keySuffix));
        return new ExecutionCommit(execution, threadRevision, expectedState, mutationVersion, lease,
                1, nextState, json("{\"kind\":\"READY\",\"step\":\"" + keySuffix + "\"}"),
                records, events, identity, Optional.empty(), Instant.now());
    }

    /** 比较所有已持久化水位，避免后续快照悄然替换事件自身的提交状态。
     * 逐个核对事件提交时的版本与完整记录水位，防止后续快照覆盖历史事件实际生成位置。
     */
    private static void assertEventWatermarks(PersistedEvent event, long revision, long mutation, long recordWatermark) {
        Assertions.assertEquals(revision, event.threadRevision());
        Assertions.assertEquals(mutation, event.mutationVersion());
        Assertions.assertEquals(recordWatermark, event.lastRecordSequence());
    }

    /** 创建带 schema 限定的宿主事实表，以验证回调写入与 AgentStore 共用同一事务。
     * 建立带 schema 前缀的宿主表，实测回调使用同一 DataSource 连接并参与原子提交。
     */
    private static void createHostTable(DataSource source) throws Exception {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS ja_store_contract_host_fact \
                (scenario_id VARCHAR(128) NOT NULL, row_id VARCHAR(128) NOT NULL, \
                PRIMARY KEY (scenario_id, row_id))\
                """);
        }
    }

    /** 通过事务借用的连接写宿主事实，确认 thread 生命周期回调与 Store 共享同一事务。 */
    private static void insertHostFact(Connection connection, String scenario, String rowId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO ja_store_contract_host_fact (scenario_id, row_id) VALUES (?, ?)")) {
            insert.setString(1, scenario);
            insert.setString(2, rowId);
            Assertions.assertEquals(1, insert.executeUpdate());
        }
    }

    /** 将合同测试中的 JDBC 受检错误映射到 Core 参与者的运行时失败边界。 */
    private static void participantSql(ParticipantSqlAction action) {
        try {
            action.run();
        } catch (SQLException failure) {
            throw new AgentStore.StoreParticipantException("store contract participant failed", failure);
        }
    }

    /** 表示 Core 合同测试参与者内的一次 JDBC 写入，将 checked failure 留在测试边界。 */
    @FunctionalInterface
    private interface ParticipantSqlAction {
        /** 在测试参与者内执行一项可能失败的 JDBC 写入。 */
        void run() throws SQLException;
    }

    /** 统计已提交的参与者写入，以验证回滚和连接池所有权，不读取 Store 内部状态。
     * 通过宿主表已提交行数验证回滚及连接池仍由调用方管理，不读取适配器私有状态。
     */
    private static int hostRows(DataSource source, String scenario) throws Exception {
        try (Connection connection = source.getConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM ja_store_contract_host_fact WHERE scenario_id = ?")) {
            query.setString(1, scenario);
            try (ResultSet rows = query.executeQuery()) {
                Assertions.assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    /** 创建带明确持久化类型的稳定版本化 payload 记录。
     * 测试事实显式设置稳定类型和 schema 版本，验证 core 类型化负载可被原样恢复。
     */
    private static RecordWrite record(String id, String type, String payload, Instant at) {
        return new RecordWrite(id, type, 1, json(payload), at);
    }

    /** 创建用于重连重放的稳定版本化事件 payload。
     * 测试事件显式带类型与版本，重连时可按持久序号回放。
     */
    private static EventWrite event(String id, String type, String payload, Instant at) {
        return new EventWrite(id, type, 1, json(payload), at);
    }

    /** 使用 Core JSON codec 创建不可变对象，令共享合同只依赖领域 JSON 值。 */
    private static JsonObject json(String source) {
        return JsonTestValues.object(source);
    }

    /** 生成持久化幂等键所需的小写 SHA-256 请求绑定值。
     * 生成规范小写 SHA-256 指纹，确保幂等键绑定的请求内容稳定。
     */
    private static String fingerprint(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
