// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.store;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.JdbcTransactionResources;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant;
import io.github.kongweiguang.ja.agent.application.gateway.store.ConversationTransactionParticipant;
import io.github.kongweiguang.ja.conversation.application.gateway.JaAdmissionTransactionParticipant;
import io.github.kongweiguang.ja.foundation.error.FailureDiagnostics;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.AgentStoreConnectionScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Ja 对通用 AgentStore 的持久化边界适配器。Ja 投影和调用方业务写入加入存储事务，执行事实与恢复读取仍由通用 Store 唯一持有。
 */
public final class JaAgentStore implements AgentStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(JaAgentStore.class);
    private final AgentStore delegate;
    private final AgentStoreTransactionParticipant jaParticipant;
    private final ConversationTransactionParticipant jaConversationTransactionParticipant;

    /**
     * 在组合时强制安装 Ja 投影参与者，避免任何循环写入绕过 Ja 必需的同事务投影。
     */
    public JaAgentStore(AgentStore delegate, AgentStoreTransactionParticipant jaParticipant,
                        ConversationTransactionParticipant jaConversationTransactionParticipant) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.jaParticipant = Objects.requireNonNull(jaParticipant, "jaParticipant");
        this.jaConversationTransactionParticipant = Objects.requireNonNull(jaConversationTransactionParticipant,
                "jaConversationTransactionParticipant");
    }

    /**
     * 先执行本次请求的业务写入，再执行强制投影，以便后者校验最终 Ja 状态。
     */
    private static AgentStoreTransactionParticipant combine(AgentStoreTransactionParticipant first, AgentStoreTransactionParticipant second) {
        Objects.requireNonNull(first, "participant");
        Objects.requireNonNull(second, "participant");
        if (second == AgentStoreTransactionParticipant.NONE) {
            return first;
        }
        return transaction -> {
            invokeParticipant(() -> {
                first.apply(transaction);
                second.apply(transaction);
            });
        };
    }

    /**
     * 让 Ja Thread/Turn 锚点先存在，再投影 Core user message，最后写依赖该消息序号的 Ja 业务事实。
     */
    private static AgentStoreTransactionParticipant combineAdmission(AgentStoreTransactionParticipant participant,
                                                           AgentStoreTransactionParticipant projection) {
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(projection, "projection");
        if (!(participant instanceof JaAdmissionTransactionParticipant staged)) {
            return combine(participant, projection);
        }
        return transaction -> invokeParticipant(() -> {
            if (JaTransactionParticipant.isCompactionAdmission(transaction)) {
                projection.apply(transaction);
                return;
            }
            AgentStoreConnectionScope.call(JdbcTransactionResources.connection(transaction), () -> {
                staged.beforeProjection(transaction);
                return null;
            });
            projection.apply(transaction);
            AgentStoreConnectionScope.call(JdbcTransactionResources.connection(transaction), () -> {
                staged.afterProjection(transaction);
                return null;
            });
        });
    }

    /**
     * 使用同一连接先写业务 Thread 元数据，再让 Ja 参与者校验并同步 Store revision。
     */
    private static ConversationTransactionParticipant combine(ConversationTransactionParticipant first,
                                                   ConversationTransactionParticipant second) {
        Objects.requireNonNull(first, "participant");
        Objects.requireNonNull(second, "participant");
        if (second == ConversationTransactionParticipant.NONE) {
            return first;
        }
        return transaction -> invokeParticipant(() -> {
            first.apply(transaction);
            second.apply(transaction);
        });
    }

    /**
     * 只把 JDBC 受检失败标为中立参与者错误；已包装故障保持原实例，并仅记录脱敏诊断。
     */
    private static void invokeParticipant(ParticipantWork work) {
        try {
            work.apply();
        } catch (AgentStore.StoreParticipantException failure) {
            logParticipantFailure(failure);
            throw failure;
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (SQLException failure) {
            logParticipantFailure(failure);
            throw new AgentStore.StoreParticipantException("Ja transaction participant failed", failure);
        }
    }

    /**
     * 异常边界收窄后仍保留原 SQL 诊断能力；只输出类型、代码与脱敏来源，不输出 SQL 或业务载荷。
     */
    private static void logParticipantFailure(Throwable failure) {
        FailureDiagnostics.Summary diagnostic = FailureDiagnostics.summarize(failure);
        LOGGER.warn("Ja Store participant failed causeType={} origin={} sqlState={} databaseCode={}",
                diagnostic.type(), diagnostic.origin(), participantSqlState(failure),
                participantDatabaseCode(failure));
    }

    /**
     * 只提取规范 SQLState，拒绝将驱动异常中的任意字符串写入运行日志。
     */
    private static String participantSqlState(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof SQLException sqlFailure) {
                String sqlState = sqlFailure.getSQLState();
                return sqlState != null && sqlState.matches("[A-Z0-9]{5}") ? sqlState : "unknown";
            }
        }
        return "none";
    }

    /**
     * SQLite 不提供 SQLState 时仅记录 JDBC 数字错误码，避免依赖驱动异常消息定位约束类别。
     */
    private static String participantDatabaseCode(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof SQLException sqlFailure) {
                return Integer.toString(sqlFailure.getErrorCode());
            }
        }
        return "none";
    }

    /**
     * 首次会话初始化由 Store 原子创建；业务参与者先插入 Ja Thread 元数据，随后校验初始 revision。
     */
    @Override
    public ConversationSnapshot initializeConversation(ConversationInitialization request,
                                                       ConversationTransactionParticipant participant) {
        return delegate.initializeConversation(Objects.requireNonNull(request, "request"),
                combine(participant, jaConversationTransactionParticipant));
    }

    /**
     * 先写调用方的 Ja 元数据，再将 Thread revision 镜像到 Store 已提交的版本，二者共享一笔事务。
     */
    @Override
    public ConversationSnapshot reviseConversation(ConversationRevision request,
                                                   ConversationTransactionParticipant participant) {
        return delegate.reviseConversation(Objects.requireNonNull(request, "request"),
                combine(participant, jaConversationTransactionParticipant));
    }

    /**
     * 准入按 Ja 元数据、Core 投影、业务投影顺序执行，任一步失败都回滚同一 Store 事务。
     */
    @Override
    public AdmissionReceipt admit(Admission request, AgentStoreTransactionParticipant participant) {
        return delegate.admit(Objects.requireNonNull(request, "request"),
                combineAdmission(participant, jaParticipant));
    }

    /**
     * Store 完成 revision 与 fencing 校验后，才允许 Ja 参与者镜像本次已持久化事实。
     */
    @Override
    public CommitReceipt commit(ExecutionCommit request, AgentStoreTransactionParticipant participant) {
        return delegate.commit(Objects.requireNonNull(request, "request"), combine(participant, jaParticipant));
    }

    /**
     * 等待回答、执行事实和 Ja 可见投影由 Store 的同一事务结算。
     */
    @Override
    public CommitReceipt settleWait(WaitSettlement request, AgentStoreTransactionParticipant participant) {
        return delegate.settleWait(Objects.requireNonNull(request, "request"), combine(participant, jaParticipant));
    }

    /**
     * 让不推进执行游标的控制事实也经过 Ja 同事务投影，避免回答或输入回执只存在于 Core Store。
     */
    @Override
    public CommitReceipt controlCommit(ControlCommit request, AgentStoreTransactionParticipant participant) {
        return delegate.controlCommit(Objects.requireNonNull(request, "request"), combine(participant, jaParticipant));
    }

    /**
     * 直接返回通用 Store 的会话快照，避免再维护一份执行状态投影。
     */
    @Override
    public Optional<ConversationSnapshot> loadConversation(ConversationKey key) {
        return delegate.loadConversation(Objects.requireNonNull(key, "key"));
    }

    /**
     * 从通用 Store 读取权威游标和执行状态，不从 Ja 展示表推导恢复位置。
     */
    @Override
    public Optional<ExecutionSnapshot> loadExecution(ExecutionKey key) {
        return delegate.loadExecution(Objects.requireNonNull(key, "key"));
    }

    /**
     * 保留调用方固定的 admission 水位，防止分页恢复混入之后新准入的执行。
     */
    @Override
    public ExecutionPage readExecutions(ConversationKey key, long afterSequence, long throughSequence, int limit) {
        return delegate.readExecutions(Objects.requireNonNull(key, "key"), afterSequence, throughSequence, limit);
    }

    /**
     * 按 Store 分配的稳定顺序读取某次执行的类型化持久事实。
     */
    @Override
    public RecordPage readRecords(RecordQuery query) {
        return delegate.readRecords(Objects.requireNonNull(query, "query"));
    }

    /**
     * 按完整执行身份读取一条稳定记录，避免应用侧为查回执而扫描或跨执行搜索。
     */
    @Override
    public Optional<StoredRecord> readRecord(ExecutionKey execution, String recordId) {
        return delegate.readRecord(Objects.requireNonNull(execution, "execution"),
                Objects.requireNonNull(recordId, "recordId"));
    }

    /**
     * 按调用方冻结的 conversation watermark 读取迁移期的会话级检查点与投影记录。
     */
    @Override
    public ConversationRecordPage readConversationRecords(ConversationRecordQuery query) {
        return delegate.readConversationRecords(Objects.requireNonNull(query, "query"));
    }

    /**
     * 从 Store 读取重连事件，不物化 Ja 的旧展示时间线。
     */
    @Override
    public EventPage readEvents(EventQuery query) {
        return delegate.readEvents(Objects.requireNonNull(query, "query"));
    }

    /**
     * 用租户隔离的持久回执核对响应丢失，不重放已有操作或 Ja 参与者。
     */
    @Override
    public Optional<OperationReceipt> readReceipt(OperationKey key) {
        return delegate.readReceipt(Objects.requireNonNull(key, "key"));
    }

    /**
     * 申请会话唯一执行租约，互斥与 fencing 语义由通用 Store 执行。
     */
    @Override
    public Lease claimLease(LeaseRequest request) {
        return delegate.claimLease(Objects.requireNonNull(request, "request"));
    }

    /**
     * 只续租 Store 先前发出的精确 owner 与 fencing token。
     */
    @Override
    public Lease renewLease(Lease lease, Instant expiresAt) {
        return delegate.renewLease(Objects.requireNonNull(lease, "lease"), Objects.requireNonNull(expiresAt, "expiresAt"));
    }

    /**
     * 释放精确租约；已过期或被替换的 fencing token 不能影响当前执行者。
     */
    @Override
    public void releaseLease(Lease lease) {
        delegate.releaseLease(Objects.requireNonNull(lease, "lease"));
    }

    /**
     * 按不可变提交身份核对不确定结果，不能再次执行 Ja 投影参与者。
     */
    @Override
    public Optional<CommitReceipt> reconcile(CommitIdentity identity) {
        return delegate.reconcile(Objects.requireNonNull(identity, "identity"));
    }

    /**
     * 不关闭注入的 Store 或 DataSource；资源生命周期仍由应用组合根负责。
     */
    @Override
    public void close() {
        // Store 和数据源的生命周期由应用组合根管理。
    }

    /**
     * 仅承载同步参与者回调；异常必须在 Store 提交边界内立即传播。
     */
    @FunctionalInterface
    private interface ParticipantWork {
        /**
         * 只传播 Ja 两阶段适配的 SQL 受检故障；Core participant 本身不暴露驱动异常。
         */
        void apply() throws SQLException;
    }
}
