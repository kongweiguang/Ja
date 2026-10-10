// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.AgentEvent;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 协调唯一循环的恢复快照、事务回执和执行权，不解释业务投影或重新执行工具。
 */
public final class AgentLoopPersistence {
    private static final int PAGE_SIZE = 256;
    private final AgentStore store;
    private final Clock clock;

    /**
     * 只借用存储及时钟，数据库资源的关闭权保留给创建它的宿主。
     */
    public AgentLoopPersistence(AgentStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 不把非正数租期送入数据库时钟运算。
     */
    private static Instant expiry(Instant now, Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.isZero())
            {
                throw new IllegalArgumentException("positive lease duration required");
            }
        return now.plus(duration);
    }

    /**
     * 每次存储访问都携带权威用户 owner，避免同名会话跨身份读取或抢占。
     */
    private static AgentStore.ExecutionKey key(AgentIdentity identity) {
        return new AgentStore.ExecutionKey(new AgentStore.ConversationKey(
                identity.tenantId(), identity.threadId(), identity.userId()), identity.turnId());
    }

    /**
     * 数据异常不回显事实正文，宿主按调用身份查询受控诊断。
     */
    private static AgentStore.StoreIntegrityException corrupt() {
        return new AgentStore.StoreIntegrityException("inconsistent execution recovery snapshot", null);
    }

    /**
     * 连诊断监听也失败时只写日志，不能改变已经完成的数据库提交。
     */
    private static void report(Consumer<Throwable> diagnostics, Throwable failure) {
        try {
            diagnostics.accept(failure);
        } catch (RuntimeException diagnosticFailure) {
            System.getLogger(AgentLoopPersistence.class.getName()).log(System.Logger.Level.ERROR,
                    "Agent event diagnostics failed", diagnosticFailure);
        }
    }

    /**
     * 固定分页上界并复核会话版本；取消时只允许为已提交终态放行只读恢复，不把无取消令牌交给执行循环。
     */
    public Restored restore(AgentIdentity identity, CancellationToken cancellation) {
        Objects.requireNonNull(identity, "identity");
        AgentStore.ExecutionKey executionKey = key(identity);
        ConversationHistory history;
        try {
            history = restoreConversation(executionKey.conversation(), cancellation);
        } catch (CancellationException cancelled) {
            if (!cancellation.isCancellationRequested() || !hasTerminalExecution(executionKey)) {
                throw cancelled;
            }
            history = restoreConversation(executionKey.conversation(), CancellationToken.none());
        }
        AgentStore.ExecutionSnapshot execution = history.executions().stream()
                .filter(value -> value.key().equals(executionKey)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("execution does not exist"));
        if (execution.lastRecordSequence() > history.conversation().recordSequence()) {
            throw corrupt();
        }
        return new Restored(history.conversation(), execution, history.executions(), history.records());
    }

    /**
     * 只接受精确 ExecutionKey 和已定义 TurnState；枚举名比较避免把损坏状态文本带入异常链。
     */
    private boolean hasTerminalExecution(AgentStore.ExecutionKey key) {
        AgentStore.ExecutionSnapshot execution = store.loadExecution(key).orElse(null);
        if (execution == null) {
            return false;
        }
        if (!execution.key().equals(key)) {
            throw corrupt();
        }
        for (TurnState state : TurnState.values()) {
            if (state.name().equals(execution.state())) {
                return state.terminal();
            }
        }
        throw corrupt();
    }

    /**
     * 空会话和手动压缩也可读取同一权威历史，无需伪造执行身份或复用 Ja 投影表。
     */
    public ConversationHistory restoreConversation(AgentStore.ConversationKey key, CancellationToken cancellation) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(cancellation, "cancellation");
        while (true) {
            cancellation.throwIfCancellationRequested();
            AgentStore.ConversationSnapshot before = store.loadConversation(key)
                    .orElseThrow(() -> new IllegalArgumentException("conversation does not exist"));
            if (!before.key().equals(key)) {
                throw corrupt();
            }
            List<AgentStore.ExecutionSnapshot> executions = executions(before, cancellation);
            List<AgentStore.ConversationRecord> records = records(before, cancellation);
            cancellation.throwIfCancellationRequested();
            Optional<AgentStore.ConversationSnapshot> after = store.loadConversation(key);
            if (after.isEmpty()) {
                throw new AgentStore.StoreConflictException("conversation was removed during recovery");
            }
            if (!before.equals(after.orElseThrow())) {
                continue;
            }
            if (executions.stream().anyMatch(value -> value.lastRecordSequence() > before.recordSequence()))
                {
                    throw corrupt();
                }
            return new ConversationHistory(before, executions, records);
        }
    }

    /**
     * 准入响应丢失先查询原回执；无法确认时明确挂起，不再创建另一任务。
     */
    @SuppressWarnings("PMD.PreserveStackTrace") // 收到的回执内容与请求身份冲突是新检测出的确定性错误，不替换原异常。
    public AgentStore.AdmissionReceipt admit(AgentStore.Admission request, AgentStoreTransactionParticipant participant) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(participant, "participant");
        try {
            return store.admit(request, participant);
        } catch (AgentStore.StoreUnavailableException failure) {
            try {
                Optional<AgentStore.OperationReceipt> found = store.readReceipt(new AgentStore.OperationKey(
                        request.execution().conversation().tenantId(), request.execution().conversation().userId(),
                        request.operation().operationKey()));
                if (found.isPresent()) {
                    AgentStore.OperationReceipt receipt = found.orElseThrow();
                    if (!receipt.conversation().equals(request.execution().conversation())
                        || !receipt.turnId().equals(request.execution().turnId())
                        || !receipt.fingerprint().equals(request.operation().fingerprint())
                        || !receipt.operationType().equals(request.operation().operationType())) {
                        throw new AgentStore.StoreConflictException("admission receipt identity conflict");
                    }
                    return new AgentStore.AdmissionReceipt(receipt.threadRevision(), receipt.mutationVersion(),
                            receipt.firstEventSequence(), receipt.lastEventSequence(), receipt.receipt(), false);
                }
            } catch (AgentStore.StoreConflictException conflict) {
                throw conflict;
            } catch (RuntimeException verificationFailure) {
                if (failure != verificationFailure) {
                    failure.addSuppressed(verificationFailure);
                }
            }
            throw new UncertainPersistenceException("admission outcome is unknown", request.operation().operationKey(), failure);
        }
    }

    /**
     * 一次提交只尝试一次；查询原回执可恢复确认结果，工具动作绝不能在这里重放。
     */
    public AgentStore.CommitReceipt commit(AgentStore.ExecutionCommit request, AgentStoreTransactionParticipant participant) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(participant, "participant");
        return reconcile(request.identity(), () -> store.commit(request, participant));
    }

    /**
     * 回答、工具结果与恢复资格共用原等待事务，对账不会再次消费用户回答。
     */
    public AgentStore.CommitReceipt settleWait(AgentStore.WaitSettlement request, AgentStoreTransactionParticipant participant) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(participant, "participant");
        return reconcile(request.transition().identity(), () -> store.settleWait(request, participant));
    }

    /**
     * 执行者只声明租约时长，实际过期与代际由数据库校验；这不是任务耗时预算。
     */
    public AgentStore.Lease claim(AgentStore.ConversationKey conversation, String ownerId, Duration duration) {
        Instant now = clock.instant();
        return store.claimLease(new AgentStore.LeaseRequest(conversation, ownerId, now, expiry(now, duration)));
    }

    /**
     * 过期租约必须重新争用，不能用普通续租复活旧执行者。
     */
    public AgentStore.Lease renew(AgentStore.Lease lease, Duration duration) {
        return store.renewLease(Objects.requireNonNull(lease, "lease"), expiry(clock.instant(), duration));
    }

    /**
     * 每次真实派发前同步校验数据库执行权，新租期继续用于后续提交。
     */
    public AgentStore.Lease assertDispatchAuthority(AgentStore.Lease lease, Duration duration) {
        return renew(lease, duration);
    }

    /**
     * 只释放指定代际；存储保留单调令牌，旧节点不能释放后继节点的租约。
     */
    public void release(AgentStore.Lease lease) {
        store.releaseLease(Objects.requireNonNull(lease, "lease"));
    }

    /**
     * 提交后才通知观察者；监听或补读失败独立报告，不改变已提交执行结果。
     */
    public void publishCommitted(AgentIdentity identity, AgentStore.CommitReceipt receipt,
                                 Consumer<AgentEvent> listener, Consumer<Throwable> diagnostics) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (receipt.firstEventSequence() == 0) {
            return;
        }
        AgentStore.ExecutionKey execution = key(identity);
        long cursor = receipt.firstEventSequence() - 1;
        try {
            while (cursor < receipt.lastEventSequence()) {
                AgentStore.EventPage page = store.readEvents(AgentStore.EventQuery.withDefaultMaxBytes(
                        execution.conversation(), cursor, receipt.lastEventSequence(), PAGE_SIZE));
                for (AgentStore.PersistedEvent event : page.events()) {
                    if (!event.execution().equals(execution) || event.sequence() != cursor + 1
                        || event.sequence() > receipt.lastEventSequence()) {
                            throw corrupt();
                        }
                    cursor = event.sequence();
                    try {
                        listener.accept(new AgentEvent(identity, event));
                    } catch (RuntimeException observationFailure) {
                        report(diagnostics, observationFailure);
                    }
                }
                if (page.nextSequence() != cursor || (cursor < receipt.lastEventSequence() && !page.hasMore())
                    || page.events().isEmpty()) {
                        throw corrupt();
                    }
            }
        } catch (RuntimeException readFailure) {
            report(diagnostics, readFailure);
        }
    }

    /**
     * 按固定准入水位读任务头，不把停滞的页游标解释为恢复完成。
     */
    private List<AgentStore.ExecutionSnapshot> executions(AgentStore.ConversationSnapshot snapshot, CancellationToken cancellation) {
        List<AgentStore.ExecutionSnapshot> result = new ArrayList<>();
        long cursor = 0;
        while (cursor < snapshot.latestExecutionSequence()) {
            cancellation.throwIfCancellationRequested();
            AgentStore.ExecutionPage page = store.readExecutions(snapshot.key(), cursor, snapshot.latestExecutionSequence(), PAGE_SIZE);
            long previous = cursor;
            for (AgentStore.ExecutionSnapshot execution : page.executions()) {
                if (!execution.key().conversation().equals(snapshot.key()) || execution.admissionSequence() <= cursor
                    || execution.admissionSequence() > snapshot.latestExecutionSequence()) {
                        throw corrupt();
                    }
                cursor = execution.admissionSequence();
                result.add(execution);
            }
            if (cursor <= previous || page.nextSequence() != cursor) {
                throw corrupt();
            }
            if (!page.hasMore()) {
                break;
            }
        }
        if (cursor != snapshot.latestExecutionSequence()) {
            throw corrupt();
        }
        return List.copyOf(result);
    }

    /**
     * 会话级旧检查点和新执行事实共用追加序列，不为迁移数据臆造任务归属。
     */
    private List<AgentStore.ConversationRecord> records(AgentStore.ConversationSnapshot snapshot, CancellationToken cancellation) {
        List<AgentStore.ConversationRecord> result = new ArrayList<>();
        long cursor = 0;
        while (cursor < snapshot.recordSequence()) {
            cancellation.throwIfCancellationRequested();
            AgentStore.ConversationRecordPage page = store.readConversationRecords(
                    AgentStore.ConversationRecordQuery.withDefaultMaxBytes(
                            snapshot.key(), cursor, snapshot.recordSequence(), PAGE_SIZE));
            long previous = cursor;
            for (AgentStore.ConversationRecord record : page.records()) {
                if (!record.conversation().equals(snapshot.key()) || record.sequence() <= cursor
                    || record.sequence() > snapshot.recordSequence()) {
                        throw corrupt();
                    }
                cursor = record.sequence();
                result.add(record);
            }
            if (cursor <= previous || page.nextSequence() != cursor) {
                throw corrupt();
            }
            if (!page.hasMore()) {
                break;
            }
        }
        if (cursor != snapshot.recordSequence()) {
            throw corrupt();
        }
        return List.copyOf(result);
    }

    /**
     * 只对存储不可用做回执核验；确定性的 CAS、损坏或参与者失败直接保留原因。
     */
    @SuppressWarnings("PMD.PreserveStackTrace") // StoreConflictException 在核验路径原样重抛，底层不确定结果另保留为 cause。
    private AgentStore.CommitReceipt reconcile(AgentStore.CommitIdentity identity, Supplier<AgentStore.CommitReceipt> action) {
        try {
            return action.get();
        } catch (AgentStore.StoreUnavailableException failure) {
            try {
                Optional<AgentStore.CommitReceipt> receipt = store.reconcile(identity);
                if (receipt.isPresent()) {
                    return receipt.orElseThrow();
                }
            } catch (AgentStore.StoreConflictException conflict) {
                throw conflict;
            } catch (RuntimeException verificationFailure) {
                if (failure != verificationFailure) {
                    failure.addSuppressed(verificationFailure);
                }
            }
            throw new UncertainPersistenceException("execution commit outcome is unknown", identity.commitKey(), failure);
        }
    }

    /**
     * 经过水位和版本复核的恢复材料；游标由唯一状态机解释，存储层不猜测。
     *
     * @param conversation 对话
     * @param execution 执行
     * @param executions 执行列表
     * @param records 记录列表
     */
    public record Restored(AgentStore.ConversationSnapshot conversation,
                           AgentStore.ExecutionSnapshot execution,
                           List<AgentStore.ExecutionSnapshot> executions,
                           List<AgentStore.ConversationRecord> records) {
        /**
         * 冻结完整视图，后续安全点重新读取而不能改写本次恢复快照。
         */
        public Restored {
            Objects.requireNonNull(conversation, "conversation");
            Objects.requireNonNull(execution, "execution");
            executions = List.copyOf(executions);
            records = List.copyOf(records);
        }
    }

    /**
     * 任务选择由宿主业务决定，会话历史本身保留完整水位与中立执行事实。
     *
     * @param conversation 对话
     * @param executions 执行列表
     * @param records 记录列表
     */
    public record ConversationHistory(AgentStore.ConversationSnapshot conversation,
                                      List<AgentStore.ExecutionSnapshot> executions,
                                      List<AgentStore.ConversationRecord> records) {
        /**
         * 跨页一致性由读取器保证，返回值复制后不再随数据库后续提交变化。
         */
        public ConversationHistory {
            Objects.requireNonNull(conversation, "conversation");
            executions = List.copyOf(executions);
            records = List.copyOf(records);
        }
    }

    /**
     * 写入结果未明时提供稳定对账身份，不允许当普通模型故障自动重试。
     */
    public static final class UncertainPersistenceException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final String operationIdentity;

        /**
         * 保存原存储异常供诊断，对外只给出非敏感提交身份。
         */
        public UncertainPersistenceException(String message, String operationIdentity, Throwable cause) {
            super(message, cause);
            this.operationIdentity = Objects.requireNonNull(operationIdentity, "operationIdentity");
        }

        /**
         * 恢复查询原回执，不重新分配调用键或重复业务动作。
         */
        public String operationIdentity() {
            return operationIdentity;
        }
    }
}
