// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.engine;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ConversationTransactionParticipant;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 装饰宿主注入的 Store 以加入应用事务，同时保留原有资源所有权。
 */
public final class ParticipatingAgentStore implements AgentStore {
    private final AgentStore delegate;
    private final AgentStoreTransactionParticipant participant;

    /**
     * 所有读写继续经过同一个 Store，并将宿主事实加入 Core 提交事务。
     */
    public ParticipatingAgentStore(AgentStore delegate, AgentStoreTransactionParticipant participant) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.participant = Objects.requireNonNull(participant, "participant");
    }

    /**
     * 在底层 Store 提供的事务中执行每个不同的应用参与者一次。
     */
    private static AgentStoreTransactionParticipant combine(AgentStoreTransactionParticipant first,
                                                            AgentStoreTransactionParticipant second) {
        AgentStoreTransactionParticipant left = Objects.requireNonNull(first, "first");
        AgentStoreTransactionParticipant right = Objects.requireNonNull(second, "second");
        if (left == AgentStoreTransactionParticipant.NONE) {
            return right;
        }
        if (right == AgentStoreTransactionParticipant.NONE || left == right) {
            return left;
        }
        return transaction -> {
            left.apply(transaction);
            right.apply(transaction);
        };
    }

    /**
     * 保留 Store 的连接与版本语义，会话初始化仍由宿主负责。
     */
    @Override
    public ConversationSnapshot initializeConversation(ConversationInitialization request,
                                                       ConversationTransactionParticipant conversationParticipant) {
        return delegate.initializeConversation(request, conversationParticipant);
    }

    /**
     * 转发会话元数据 CAS，避免执行事务参与者反向控制会话初始化。
     */
    @Override
    public ConversationSnapshot reviseConversation(ConversationRevision request,
                                                   ConversationTransactionParticipant conversationParticipant) {
        return delegate.reviseConversation(request, conversationParticipant);
    }

    /**
     * 在 Store 的原子准入边界内组合 Engine 与本次请求参与者。
     */
    @Override
    public AdmissionReceipt admit(Admission request, AgentStoreTransactionParticipant perCall) {
        return delegate.admit(request, combine(participant, perCall));
    }

    /**
     * 在 Store 的执行提交事务内组合 Engine 与本次请求参与者。
     */
    @Override
    public CommitReceipt commit(ExecutionCommit request, AgentStoreTransactionParticipant perCall) {
        return delegate.commit(request, combine(participant, perCall));
    }

    /**
     * 在等待和回答的原子结算事务内组合 Engine 与本次请求参与者。
     */
    @Override
    public CommitReceipt settleWait(WaitSettlement request, AgentStoreTransactionParticipant perCall) {
        return delegate.settleWait(request, combine(participant, perCall));
    }

    /**
     * 在不抢占活动 Runner 租约的控制事务内投影宿主事实。
     */
    @Override
    public CommitReceipt controlCommit(ControlCommit request, AgentStoreTransactionParticipant perCall) {
        return delegate.controlCommit(request, combine(participant, perCall));
    }

    /**
     * 从同一个已配置 Store 读取会话水位。
     */
    @Override
    public Optional<ConversationSnapshot> loadConversation(ConversationKey key) {
        return delegate.loadConversation(key);
    }

    /**
     * 读取恢复和状态查询使用的精确执行快照。
     */
    @Override
    public Optional<ExecutionSnapshot> loadExecution(ExecutionKey key) {
        return delegate.loadExecution(key);
    }

    /**
     * 原样传递两个游标边界，允许调用方针对固定恢复快照分页。
     */
    @Override
    public ExecutionPage readExecutions(ConversationKey key, long afterSequence, long throughSequence, int limit) {
        return delegate.readExecutions(key, afterSequence, throughSequence, limit);
    }

    /**
     * 转发执行级持久记录查询，不改写游标。
     */
    @Override
    public RecordPage readRecords(RecordQuery query) {
        return delegate.readRecords(query);
    }

    /**
     * 按执行内稳定记录身份直读一条已提交事实，不扩大其所属执行或版本水位。
     */
    @Override
    public Optional<StoredRecord> readRecord(ExecutionKey execution, String recordId) {
        return delegate.readRecord(execution, recordId);
    }

    /**
     * 使用原始快照水位转发会话执行记录读取。
     */
    @Override
    public ConversationRecordPage readConversationRecords(ConversationRecordQuery query) {
        return delegate.readConversationRecords(query);
    }

    /**
     * 不变地转发持久事件游标，使重连仍以 Store 为准。
     */
    @Override
    public EventPage readEvents(EventQuery query) {
        return delegate.readEvents(query);
    }

    /**
     * 原样转发幂等回执查询，不转换操作身份。
     */
    @Override
    public Optional<OperationReceipt> readReceipt(OperationKey key) {
        return delegate.readReceipt(key);
    }

    /**
     * 将单会话执行权和 fencing 校验交给选定 Store 实现。
     */
    @Override
    public Lease claimLease(LeaseRequest request) {
        return delegate.claimLease(request);
    }

    /**
     * 使用原持有者和 fencing token 续租。
     */
    @Override
    public Lease renewLease(Lease lease, Instant expiresAt) {
        return delegate.renewLease(lease, expiresAt);
    }

    /**
     * 仅释放持有者与 fencing token 均匹配的租约。
     */
    @Override
    public void releaseLease(Lease lease) {
        delegate.releaseLease(lease);
    }

    /**
     * 按身份核对结果未知的提交，不重放工具或 Provider 副作用。
     */
    @Override
    public Optional<CommitReceipt> reconcile(CommitIdentity identity) {
        return delegate.reconcile(identity);
    }

    /**
     * 即使内部服务关闭 Store 引用，也不关闭宿主借出的实际 Store。
     */
    @Override
    public void close() {
        // 该装饰器由 AgentEngine 持有；底层 store 仍由调用方持有并负责释放。
    }
}
