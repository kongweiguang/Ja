// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.AdmissionReceipt;

/** 确定性故障注入只验证协调边界；数据库原子性另由真实 SQLite/PG 契约测试证明。 */
final class AgentStorePersistenceTest {
    private static final Instant NOW = Instant.parse("2026-09-26T00:00:00Z");
    private static final AgentIdentity ID = new AgentIdentity("tenant", "thread", "turn", "user");
    private static final AgentStore.ConversationKey CONVERSATION = new AgentStore.ConversationKey("tenant", "thread", "user");
    private static final AgentStore.ExecutionKey EXECUTION = new AgentStore.ExecutionKey(CONVERSATION, "turn");
    private static final io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant
            PARTICIPANT = context -> { };

    /** 已提交但响应丢失时只读原回执，不能重复提交或重新调用业务工具。 */
    @Test
    void resolvesLostCommitResponseWithoutRepeatingWrite() {
        AtomicInteger writes = new AtomicInteger();
        AgentStore.CommitReceipt expected = new AgentStore.CommitReceipt(2, 2, 1, 1, JsonObject.empty(), false);
        AgentLoopPersistence persistence = persistence((proxy, method, args) -> switch (method.getName()) {
            case "commit" -> {
                writes.incrementAndGet();
                throw new AgentStore.StoreUnavailableException("connection lost after commit", null);
            }
            case "reconcile" -> Optional.of(expected);
            default -> throw new AssertionError(method.getName());
        });
        assertEquals(expected, persistence.commit(commit(), PARTICIPANT));
        assertEquals(1, writes.get());
    }

    /** 对账无回执不能推断动作未发生，必须将稳定提交身份交给恢复流程。 */
    @Test
    void unknownWriteRemainsSuspendedRatherThanRetried() {
        AtomicInteger writes = new AtomicInteger();
        AgentLoopPersistence persistence = persistence((proxy, method, args) -> switch (method.getName()) {
            case "commit" -> {
                writes.incrementAndGet();
                throw new AgentStore.StoreUnavailableException("write unavailable", null);
            }
            case "reconcile" -> Optional.empty();
            default -> throw new AssertionError(method.getName());
        });
        AgentLoopPersistence.UncertainPersistenceException failure = assertThrows(AgentLoopPersistence.UncertainPersistenceException.class,
                () -> persistence.commit(commit(), PARTICIPANT));
        assertEquals("commit-one", failure.operationIdentity());
        assertEquals(1, writes.get());
    }

    /** 版本冲突已明确失败，不能借对账吞掉另一执行者的所有权冲突。 */
    @Test
    void staleCommitIsNotReconciledAsTransientFailure() {
        AgentLoopPersistence persistence = persistence((proxy, method, args) -> {
            if (method.getName().equals("commit")) {
                throw new AgentStore.StoreConflictException("stale fence");
            }
            throw new AssertionError("unexpected store call: " + method.getName());
        });
        assertThrows(AgentStore.StoreConflictException.class, () -> persistence.commit(commit(), PARTICIPANT));
    }

    /** 页读取期间有新提交时丢弃旧组合，第二次读取必须使用新的固定水位。 */
    @Test
    void retriesWholeRecoverySnapshotWhenRevisionChanges() {
        AtomicInteger headers = new AtomicInteger();
        List<Long> upperBounds = new ArrayList<>();
        AgentStore.ExecutionSnapshot execution = execution();
        AgentLoopPersistence persistence = persistence((proxy, method, args) -> switch (method.getName()) {
            case "loadConversation" -> Optional.of(conversation(headers.getAndIncrement() == 0 ? 1 : 2));
            case "loadExecution" -> Optional.of(execution);
            case "readExecutions" -> new AgentStore.ExecutionPage(List.of(execution), 1, false);
            case "readConversationRecords" -> {
                AgentStore.ConversationRecordQuery query = (AgentStore.ConversationRecordQuery) args[0];
                upperBounds.add(query.throughSequence());
                List<AgentStore.ConversationRecord> records = new ArrayList<>();
                for (long sequence = 1; sequence <= query.throughSequence(); sequence++) {
                    records.add(new AgentStore.ConversationRecord(CONVERSATION, EXECUTION,
                            "record-" + sequence, "agent.fact", 1, sequence, JsonObject.empty(), NOW));
                }
                yield new AgentStore.ConversationRecordPage(records, query.throughSequence(), false);
            }
            default -> throw new AssertionError(method.getName());
        });
        AgentLoopPersistence.Restored restored = persistence.restore(ID, CancellationToken.none());
        assertEquals(List.of(1L, 2L), upperBounds);
        assertEquals(2, restored.conversation().revision());
        assertEquals(2, restored.records().size());
    }

    /** 已提交终态只绕过恢复读取的取消，恢复仍保持只读且不向执行阶段传播无取消令牌。 */
    @Test
    void restoresCommittedTerminalAfterCallerCancellationWithoutWrites() {
        CancellationException cancellation = new CancellationException("requested");
        AgentStore.ExecutionSnapshot terminal = execution("CANCELLED");
        AtomicInteger probes = new AtomicInteger();
        AgentLoopPersistence persistence = persistence((proxy, method, args) -> switch (method.getName()) {
            case "loadExecution" -> {
                probes.incrementAndGet();
                yield Optional.of(terminal);
            }
            case "loadConversation" -> Optional.of(conversation(1));
            case "readExecutions" -> new AgentStore.ExecutionPage(List.of(terminal), 1, false);
            case "readConversationRecords" -> new AgentStore.ConversationRecordPage(List.of(record()), 1, false);
            default -> throw new AssertionError("unexpected store operation: " + method.getName());
        });

        AgentLoopPersistence.Restored restored = persistence.restore(ID, cancelledToken(cancellation));

        assertEquals(1, probes.get());
        assertEquals(terminal, restored.execution());
        assertEquals(List.of(terminal), restored.executions());
        assertEquals(List.of(record()), restored.records());
    }

    /** 非终态取消保留原异常类别与原因，且不得读取或推进完整历史。 */
    @Test
    void keepsCancellationForNonTerminalExecutionWithoutReadingHistory() {
        CancellationException expected = new CancellationException("requested");
        AtomicInteger historyReads = new AtomicInteger();
        AgentLoopPersistence persistence = persistence((proxy, method, args) -> switch (method.getName()) {
            case "loadExecution" -> Optional.of(execution());
            case "loadConversation" -> {
                historyReads.incrementAndGet();
                throw new AssertionError("cancelled recovery must not load conversation history");
            }
            default -> throw new AssertionError("unexpected store operation: " + method.getName());
        });

        CancellationException failure = assertThrows(CancellationException.class,
                () -> persistence.restore(ID, cancelledToken(expected)));

        assertSame(expected, failure);
        assertEquals(0, historyReads.get());
    }

    /** 监听器崩溃独立报告，后续已提交事件仍按持久顺序通知。 */
    @Test
    void listenerFailureDoesNotRewriteCommittedOutcome() {
        List<Throwable> diagnostics = new ArrayList<>();
        List<Long> delivered = new ArrayList<>();
        AgentLoopPersistence persistence = persistence((proxy, method, args) -> {
            if (!method.getName().equals("readEvents")) {
                throw new AssertionError(method.getName());
            }
            return new AgentStore.EventPage(List.of(event(1), event(2)), 2, false);
        });
        persistence.publishCommitted(ID, new AgentStore.CommitReceipt(2, 2, 1, 2, JsonObject.empty(), true), event -> {
            delivered.add(event.persistedEvent().sequence());
            if (event.persistedEvent().sequence() == 1) {
                throw new IllegalStateException("observer failed");
            }
        }, diagnostics::add);
        assertEquals(List.of(1L, 2L), delivered);
        assertEquals(1, diagnostics.size());
    }

    /** 派发前必须访问存储校验租约，不能只信任本地尚未过期的缓存。 */
    @Test
    void dispatchChecksDatabaseEvenForLocallyLiveLease() {
        AtomicInteger checks = new AtomicInteger();
        AgentLoopPersistence persistence = persistence((proxy, method, args) -> {
            if (!method.getName().equals("renewLease")) {
                throw new AssertionError(method.getName());
            }
            checks.incrementAndGet();
            throw new AgentStore.StoreConflictException("owner fenced");
        });
        assertThrows(AgentStore.StoreConflictException.class,
                () -> persistence.assertDispatchAuthority(lease(), Duration.ofSeconds(30)));
        assertEquals(1, checks.get());
    }

    /** 准入响应丢失仍返回原任务身份，重复请求不能显示为新建。 */
    @Test
    void admissionUsesOriginalReceiptAfterResponseLoss() {
        AgentStore.OperationIdentity operation = new AgentStore.OperationIdentity("operation", "submit", "a".repeat(64), JsonObject.empty());
        AgentLoopPersistence persistence = persistence((proxy, method, args) -> switch (method.getName()) {
            case "admit" -> throw new AgentStore.StoreUnavailableException("response lost", null);
            case "readReceipt" -> Optional.of(new AgentStore.OperationReceipt(CONVERSATION, "turn", "operation",
                    "a".repeat(64), "submit", 1, 1, 0, 0, JsonObject.empty(), NOW));
            default -> throw new AssertionError(method.getName());
        });
        AdmissionReceipt receipt = persistence.admit(new AgentStore.Admission(EXECUTION, 0, 1, "QUEUED", JsonObject.empty(),
                List.of(), List.of(), operation, NOW), PARTICIPANT);
        assertFalse(receipt.newlyAdmitted());
        assertEquals(1, receipt.mutationVersion());
    }

    /** 代理仅暴露测试声明过的调用，任何意外重放或旁路读取都会失败。 */
    private static AgentLoopPersistence persistence(InvocationHandler behavior) {
        AgentStore store = (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, behavior);
        return new AgentLoopPersistence(store, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** 固定事务身份让测试检测是否错误地为重试分配新键。 */
    private static AgentStore.ExecutionCommit commit() {
        return new AgentStore.ExecutionCommit(EXECUTION, 1, "RUNNING", 1, lease(), 1, "COMPLETED", JsonObject.empty(),
                List.of(), List.of(), new AgentStore.CommitIdentity(EXECUTION, "commit-one", "b".repeat(64)), Optional.empty(), NOW);
    }

    /** 本地有效时间故意足够长，用于证明派发仍需数据库确认。 */
    private static AgentStore.Lease lease() { return new AgentStore.Lease(CONVERSATION, "owner", 1, NOW.plusSeconds(60)); }

    /** 模拟追加记录后的会话水位，任务本身保持不变以隔离快照一致性测试。 */
    private static AgentStore.ConversationSnapshot conversation(long revision) {
        return new AgentStore.ConversationSnapshot(CONVERSATION, revision, 0, 1, NOW, NOW, revision);
    }

    /** 执行游标绑定已提交记录上界，后续会话追加不应被解释成该游标的执行结果。 */
    private static AgentStore.ExecutionSnapshot execution() {
        return execution("RUNNING");
    }

    /** 终态与运行态共用同一恢复游标，只改变正式持久化状态字段。 */
    private static AgentStore.ExecutionSnapshot execution(String state) {
        return new AgentStore.ExecutionSnapshot(EXECUTION, 1, state, 1, 1, JsonObject.empty(), 1, NOW, NOW);
    }

    /** 取消令牌固定为已取消，使恢复边界测试无需线程或调度时序。 */
    private static CancellationToken cancelledToken(CancellationException failure) {
        return new CancellationToken() {
            /** 固定发布取消意图，确保测试不依赖外部线程时序。 */
            @Override
            public boolean isCancellationRequested() {
                return true;
            }

            /** 返回固定原因，便于验证非终态分支保留原取消语义。 */
            @Override
            public Optional<String> reason() {
                return Optional.of("requested");
            }

            /** 取消回调不参与恢复读取测试，也不保留调用方资源。 */
            @Override
            public Registration onCancellation(Runnable callback) {
                return Registration.noop();
            }

            /** 使用同一个异常对象，检验恢复层不会把取消改写成普通失败。 */
            @Override
            public void throwIfCancellationRequested() {
                throw failure;
            }
        };
    }

    /** 已提交事实固定在同一记录水位，测试只比较恢复结果而不构造任何写入。 */
    private static AgentStore.ConversationRecord record() {
        return new AgentStore.ConversationRecord(CONVERSATION, EXECUTION,
                "record-1", "agent.fact", 1, 1, JsonObject.empty(), NOW);
    }

    /** 使用真实存储事件结构测试游标顺序，不构造临时 token 事件替代持久事件。 */
    private static AgentStore.PersistedEvent event(long sequence) {
        return new AgentStore.PersistedEvent(CONVERSATION, EXECUTION, "event-" + sequence, "agent.state", 1,
                sequence, sequence, sequence, sequence, JsonObject.empty(), NOW);
    }
}
