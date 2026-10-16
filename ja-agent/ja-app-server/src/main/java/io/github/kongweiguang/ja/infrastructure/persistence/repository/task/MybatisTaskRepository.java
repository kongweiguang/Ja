// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.context.checkpoint.StoreBackedCheckpointStore;
import io.github.kongweiguang.ja.agent.application.loop.AgentContextMapper;
import io.github.kongweiguang.ja.agent.application.loop.AgentLoopPersistence;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFact;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.foundation.runtime.AppServerIdentity;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.JdbcTransactionResources;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.InitialContextSeedCodec;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.JaMessageIds;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadTitlePolicy;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.SubagentPolicy;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.AttachmentRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.ThreadForkRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.PersistenceRowProjections;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.ThreadPersistenceMapping;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.AgentStoreConnectionScope;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.collaboration.domain.model.AdmissionReceipt;
import io.github.kongweiguang.ja.collaboration.domain.model.ThreadDefinition;
import io.github.kongweiguang.ja.collaboration.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.collaboration.domain.model.TurnAdmission;
import io.github.kongweiguang.ja.collaboration.application.gateway.ConversationAcl;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskMailboxPort;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepository;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepositoryException;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskTerminalProjection;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceWriteClaimPort;
import org.apache.ibatis.session.SqlSessionFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MyBatis/SQLite Task adapter；全部可见回执只在事务提交之后返回给调度层。
 */
public final class MybatisTaskRepository implements TaskRepository, TaskMailboxPort, WorkspaceWriteClaimPort,
        TaskTerminalProjection {
    private static final AppServerIdentity APP_IDENTITY = AppServerIdentity.local();
    private static final int MAX_TREE_SIZE = 64;
    private static final int MAX_EFFECTIVE_MESSAGES = 512;
    private static final long MAX_EFFECTIVE_BYTES = 4L * 1024 * 1024;
    private static final int MAX_MAILBOX_PENDING = 256;
    private static final long MAX_MAILBOX_BYTES = 512L * 1024;
    private static final long MAX_TURN_ATTACHMENT_BYTES = 250L * 1024 * 1024;

    private final MybatisUnitOfWork transactions;
    private final ObjectMapper objectMapper;
    private final TaskJsonCodec json;
    private final AgentStore rawAgentStore;
    private final ExecutionRecordCodec records;
    private final JsonValueCodec jsonValues;
    private final AgentLoopPersistence agentPersistence;
    private final AgentContextMapper agentContexts;
    private final StoreBackedCheckpointStore checkpoints;
    private final InitialContextSeedCodec seedCodec;
    private final TaskInitialContextSeedCodec taskSeedCodec;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Task SQL 与 Core Store 读口由组合根显式提供；本适配器只借用 raw store，不接管关闭权。
     */
    public MybatisTaskRepository(SqlSessionFactory sessions, ObjectMapper objectMapper,
                                 AgentStore rawAgentStore, ExecutionRecordCodec records,
                                 JsonValueCodec jsonValues, Clock clock) {
        this(new MybatisUnitOfWork(sessions), objectMapper, rawAgentStore, records, jsonValues, clock);
    }

    /**
     * 事务测试显式传入 SQLite owner，保证冻结历史仍来自真实 Store，而不是旧 Ja checkpoint 表。
     */
    public MybatisTaskRepository(SqlSessionFactory sessions, ObjectMapper objectMapper,
                                 MybatisUnitOfWork.SessionOwner owner, AgentStore rawAgentStore,
                                 ExecutionRecordCodec records, JsonValueCodec jsonValues, Clock clock) {
        this(new MybatisUnitOfWork(sessions, owner), objectMapper, rawAgentStore, records, jsonValues, clock);
    }

    /**
     * 收敛 production 与测试构造的资源边界，所有 Core 读适配都只借用组合根提供的实例。
     */
    private MybatisTaskRepository(MybatisUnitOfWork transactions, ObjectMapper objectMapper,
                                  AgentStore rawAgentStore, ExecutionRecordCodec records,
                                  JsonValueCodec jsonValues, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.json = new TaskJsonCodec(objectMapper);
        this.rawAgentStore = Objects.requireNonNull(rawAgentStore, "rawAgentStore");
        ExecutionRecordCodec decoder = Objects.requireNonNull(records, "records");
        this.records = decoder;
        JsonValueCodec values = Objects.requireNonNull(jsonValues, "jsonValues");
        this.jsonValues = values;
        Clock sourceClock = Objects.requireNonNull(clock, "clock");
        this.agentPersistence = new AgentLoopPersistence(this.rawAgentStore, sourceClock);
        this.agentContexts = new AgentContextMapper(values, decoder);
        this.checkpoints = new StoreBackedCheckpointStore(this.rawAgentStore, decoder);
        this.seedCodec = new InitialContextSeedCodec(values);
        this.taskSeedCodec = new TaskInitialContextSeedCodec(values);
    }

    /**
     * 将 Child Thread 的权威行投影为与主会话相同的元数据，保证任务设置读取不漂移。
     */
    private static ThreadSnapshot thread(PersistenceRecords.ThreadRow row) {
        return ConversationAcl.snapshot(PersistenceRowProjections.threadSummary(row));
    }

    /**
     * 跨 Workspace 的普通消息只传递用户明确写出的文本；附件、Skill 和路径引用不能随 Mailbox
     * 穿越 Workspace，因为接收方没有发送方资源的授权上下文。该检查放在同一事务快照内，避免
     * 应用层预读 Thread 后再入队导致 workspace 关系漂移；同 Workspace 消息保留原有内容语义。
     */
    private static void requireCrossWorkspaceMessageContent(PersistenceMappers mapper,
                                                            TaskModels.MailboxEnvelope mailbox) {
        PersistenceRecords.ThreadRow sender = mapper.history().selectThread(mailbox.senderThreadId());
        PersistenceRecords.ThreadRow target = mapper.history().selectThread(mailbox.targetThreadId());
        if (sender == null || target == null) {
            throw notFound("mailbox route");
        }
        if (sender.workspaceId().equals(target.workspaceId())) {
            return;
        }
        if (mailbox.content().blocks().stream().anyMatch(block -> !(block instanceof TextContent))) {
            throw relation("cross-workspace messages require text-only content");
        }
    }

    /**
     * 拒绝在 AgentStore 事务外写入 Task 准入投影，保证 Core 与 Ja 事实只能共同提交或共同回滚。
     */
    private static void requireStoreScope() {
        if (AgentStoreConnectionScope.current().isEmpty()) {
            throw new StorageException(StorageException.Code.TRANSACTION,
                    "Task admission must join the AgentStore transaction");
        }
    }

    /**
     * 业务因果键与 Ja turns 外层行必须对应同一次 Store admission。
     */
    private static void requireCausalBinding(AdmissionFacts facts, String parentTurnId, String rootTurnId) {
        if (!Objects.equals(facts.parentTurnId(), parentTurnId)
            || !Objects.equals(facts.rootTurnId(), rootTurnId)) {
            throw relation("Task causal binding changed before admission");
        }
    }

    /**
     * Core/Ja admission participant 是 Turn 唯一写者；Task 仅核实本事务已落下的身份、因果边和初始状态，
     * 以保持通用执行事实与业务 lineage 同事务且不形成第二个 Turn 写入入口。
     * 时间按数据库的 UTC 毫秒精度核对，避免宿主时钟的子毫秒部分使同一次准入被误判为身份漂移。
     */
    private static void verifyAdmittedTurnCausality(PersistenceMappers mapper, TurnAdmission turn,
                                                    String parentTurnId, String rootTurnId) {
        TaskRecords.TurnCausalityRow actual = mapper.tasks().selectTurnCausality(turn.turnId());
        if (actual == null || !actual.threadId().equals(turn.threadId())
            || !Objects.equals(actual.parentTurnId(), parentTurnId)
            || !Objects.equals(actual.rootTurnId(), rootTurnId)
            || !"QUEUED".equals(actual.state()) || actual.cancelRequestedAt() != null
            || !Instant.ofEpochMilli(turn.requestedAt().toEpochMilli()).toString().equals(actual.requestedAt())) {
            throw invalidState("Task admission Turn projection differs from its prepared causal identity");
        }
    }

    /**
     * 附件 DRAFT→BOUND 与 USER Message 同事务；消息归属使用稳定 messageId，附件自身 ordinal
     * 按输入顺序独立生成，避免把消息 ordinal 混入附件关系语义。
     */
    private static void bindAttachments(PersistenceMappers mapper, String workspaceId,
                                        TurnAdmission turn) {
        long totalBytes = 0;
        for (String attachmentId : turn.attachmentIds()) {
            AttachmentRecords.AttachmentRow row = mapper.attachments().selectUnreservedDraft(attachmentId, workspaceId);
            if (row == null || row.blobSha256() == null || !Instant.parse(row.expiresAt()).isAfter(turn.requestedAt())) {
                throw relation("task attachment is unavailable");
            }
            totalBytes = Math.addExact(totalBytes, row.sizeBytes());
            if (totalBytes > MAX_TURN_ATTACHMENT_BYTES) {
                throw relation("task attachment quota exceeded");
            }
        }
        int index = 0;
        for (String attachmentId : turn.attachmentIds()) {
            AttachmentRecords.AttachmentBind bind = new AttachmentRecords.AttachmentBind(attachmentId,
                    workspaceId, turn.messageId(), index++, instant(turn.requestedAt()));
            requireChanged(mapper.attachments().bindDraft(bind), "task attachment binding lost");
            requireChanged(mapper.attachments().insertMessageAttachment(bind), "task attachment relation lost");
        }
    }

    /**
     * 根 Turn 只能来自数据库既有因果事实；首个无 origin 侧边任务保持 null。
     */
    private static String causalRootTurn(PersistenceMappers mapper, String expectedParentThreadId,
                                         String parentTurnId, boolean requireNonTerminal) {
        if (parentTurnId == null) {
            return null;
        }
        TaskRecords.TurnCausalityRow causal = mapper.tasks().selectTurnCausality(parentTurnId);
        if (causal == null || (expectedParentThreadId != null && !expectedParentThreadId.equals(causal.threadId()))) {
            throw relation("origin Turn does not belong to parent Thread");
        }
        if (requireNonTerminal && (TurnState.valueOf(causal.state()).terminal()
                                   || causal.cancelRequestedAt() != null)) {
            throw invalidState("attached Task cannot outlive its origin Turn");
        }
        return causal.rootTurnId() == null ? parentTurnId : causal.rootTurnId();
    }

    /**
     * Child Thread 插入沿用现有线程偏好和 title source，初始 revision 仍由 schema 固定为零。
     */
    private static void insertThread(PersistenceMappers mapper, ThreadDefinition thread) {
        requireChanged(mapper.history().insertThread(ThreadPersistenceMapping.toInsert(
                        ConversationAcl.toConversation(thread))),
                "child Thread insert lost");
    }

    /**
     * SIDE_TASK 和 SUBAGENT 的上下文差异在持久入口再次 fail-closed。
     */
    private static void requireSeedMode(TaskModels.ChildAdmission child) {
        if (child.kind() == TaskModels.Kind.SIDE_TASK
            && child.contextSeed().inheritanceMode() != TaskModels.InheritanceMode.EFFECTIVE_CONTEXT
            || child.kind() == TaskModels.Kind.SUBAGENT
               && child.contextSeed().inheritanceMode() != TaskModels.InheritanceMode.BRIEF_ONLY) {
            throw relation("task kind does not match context inheritance mode");
        }
    }

    /**
     * MESSAGE 允许任意现存 Thread 间投递；Follow-up 仍要求同一任务树且至少一端为 Child。
     * 目标关闭标记由独立临时侧聊表在查询层拦截，避免关闭竞态下继续接纳消息。
     */
    private static RoutedTask route(PersistenceMappers mapper, String senderThreadId, String targetThreadId,
                                    boolean requireSameRoot, boolean requireChild) {
        TaskRecords.TaskRouteRow sender = mapper.tasks().selectTaskRoute(senderThreadId);
        TaskRecords.TaskRouteRow target = mapper.tasks().selectTaskRoute(targetThreadId);
        if (sender == null || target == null) {
            throw notFound("mailbox route");
        }
        SideChatPersistence.requireTaskAdmissionOpen(mapper, targetThreadId);
        String senderRoot = sender.rootThreadId() == null ? sender.threadId() : sender.rootThreadId();
        String targetRoot = target.rootThreadId() == null ? target.threadId() : target.rootThreadId();
        if (requireSameRoot && !senderRoot.equals(targetRoot)) {
            throw relation("mailbox cannot cross task roots");
        }
        String taskThreadId = target.depth() != null ? target.threadId()
                : sender.depth() != null ? sender.threadId() : null;
        if (requireChild && taskThreadId == null) {
            throw relation("mailbox requires a Child Task endpoint");
        }
        return new RoutedTask(targetRoot, taskThreadId);
    }

    /**
     * Message count 与 UTF-8 bytes 在同一 write transaction 校验，FINAL_ANSWER 走终态不可丢路径。
     */
    private static void requireMailboxCapacity(PersistenceMappers mapper, String targetThreadId,
                                               String contentJson) {
        TaskRecords.MailboxStats stats = mapper.tasks().selectMailboxStats(targetThreadId);
        long bytes = contentJson.getBytes(StandardCharsets.UTF_8).length;
        if (stats.pendingCount() >= MAX_MAILBOX_PENDING || stats.pendingBytes() + bytes > MAX_MAILBOX_BYTES) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.MAILBOX_FULL, "task mailbox is full");
        }
    }

    /**
     * 插入竞争后只允许回读同一发送方幂等事实。
     */
    private static TaskRecords.MailboxRow requireExistingMailbox(PersistenceMappers mapper,
                                                                 TaskModels.MailboxEnvelope envelope) {
        TaskRecords.MailboxRow row = mapper.tasks().selectMailboxByIdempotency(
                envelope.senderThreadId(), envelope.operationKey());
        if (row == null) {
            throw concurrent("mailbox insert changed concurrently");
        }
        return row;
    }

    /**
     * Mailbox 读端口仍校验外部身份形状，避免数据库查询接受空白或跨实体类型的标识。
     */
    private static String requireMailboxThreadId(String value) {
        if (value == null || value.length() > 128
            || !value.matches("thr_[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid mailbox Thread identity");
        }
        return value;
    }

    /**
     * 幂等键保留原值以维持发送端签名，并拒绝空白、控制字符和越界查询。
     */
    private static String requireMailboxOperationKey(String value) {
        if (value == null || value.isBlank() || value.length() > 128
            || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid mailbox idempotency key");
        }
        return value;
    }

    /**
     * Write claim 行保持 fencing token 原值，不从 sequence 推导。
     */
    private static WorkspaceWriteClaimPort.WriteClaim writeClaim(TaskRecords.WriteClaimRow row) {
        return new WorkspaceWriteClaimPort.WriteClaim(
                row.claimSequence(), row.claimId(), row.workspaceId(), row.threadId(),
                row.turnId(), row.processGeneration(), row.fencingToken(),
                WorkspaceWriteClaimPort.State.valueOf(row.state()), Instant.parse(row.requestedAt()),
                optionalInstant(row.acquiredAt()), optionalInstant(row.heartbeatAt()),
                optionalInstant(row.releasedAt()));
    }

    /**
     * Activity summary 的 text 成员是唯一列表预览；缺失时不从其它 JSON 猜测。
     */
    private static String safeSummary(JsonObject summary) {
        return summary.get("text") instanceof JsonText(String value) ? value : null;
    }

    /**
     * 从已冻结 Turn 派生活动身份，使事务回滚后的同一 admission 重试仍指向同一个事件键。
     */
    private static String userAdmissionActivityId(String turnId) {
        if (turnId == null || !turnId.startsWith("turn_") || turnId.length() <= "turn_".length()) {
            throw invalidState("Task USER admission Turn identity is invalid");
        }
        return "activity_" + turnId.substring("turn_".length());
    }

    /**
     * parent revision 冲突有独立错误分类，Coordinator 可映射 TASK_CONTEXT_REVISION_CONFLICT。
     */
    private static void requireParentRevision(TaskRecords.ParentRow parent, long expectedRevision) {
        if (expectedRevision < 0 || parent.revision() != expectedRevision) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.CONTEXT_REVISION_CONFLICT,
                    "parent Thread revision is stale");
        }
    }

    /**
     * parent 查找不接受已 soft-delete Thread。
     */
    private static TaskRecords.ParentRow requireParent(PersistenceMappers mapper, String threadId) {
        TaskRecords.ParentRow row = mapper.tasks().selectParent(threadId);
        if (row == null) {
            throw notFound("parent Thread");
        }
        return row;
    }

    /**
     * follow-up admission 读取目标 Thread 的权威 revision。
     */
    private static PersistenceRecords.ThreadRow requireThread(PersistenceMappers mapper, String threadId) {
        PersistenceRecords.ThreadRow row = mapper.history().selectThread(threadId);
        if (row == null) {
            throw notFound("task Thread");
        }
        return row;
    }

    /**
     * claim 回读缺失表明事务内不变量破坏。
     */
    private static TaskRecords.WriteClaimRow requireWriteClaim(PersistenceMappers mapper, String claimId) {
        TaskRecords.WriteClaimRow row = mapper.tasks().selectWriteClaim(claimId);
        if (row == null) {
            throw invalidState("write claim is unavailable");
        }
        return row;
    }

    /**
     * MyBatis 行数是所有单行状态门的最终竞争证据。
     */
    private static void requireChanged(int count, String message) {
        if (count != 1) {
            throw concurrent(message);
        }
    }

    /**
     * 终态判断集中一处，避免状态新增时 completed_at 约束漂移。
     */
    private static boolean terminal(TaskModels.State state) {
        return state == TaskModels.State.COMPLETED || state == TaskModels.State.FAILED
               || state == TaskModels.State.CANCELLED;
    }

    /**
     * 所有时刻保存 ISO-8601 UTC/offset 文本，不读取本机时区。
     */
    private static String instant(Instant value) {
        return Objects.requireNonNull(value, "instant").toString();
    }

    /**
     * 可空持久时刻只由 null 表达缺失。
     */
    private static Instant optionalInstant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    /**
     * Task 不存在错误不泄露具体数据库表。
     */
    private static TaskRepositoryException notFound(String entity) {
        return new TaskRepositoryException(TaskRepositoryException.Code.NOT_FOUND, entity + " is unavailable");
    }

    /**
     * 关系错误用于 workspace/root/origin/seed 不一致。
     */
    private static TaskRepositoryException relation(String message) {
        return new TaskRepositoryException(TaskRepositoryException.Code.RELATION_INVALID, message);
    }

    /**
     * CAS 竞争供调用方刷新 projection 后决定是否重试。
     */
    private static TaskRepositoryException concurrent(String message) {
        return new TaskRepositoryException(TaskRepositoryException.Code.CAS_CONFLICT, message);
    }

    /**
     * 损坏或非法恢复状态必须 fail-closed。
     */
    private static TaskRepositoryException invalidState(String message) {
        return new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE, message);
    }

    /**
     * 用父 Thread 持久 owner 构造 Core conversation key，冻结权威执行历史与 checkpoint，再复核 Ja revision；
     * owner 不从调用方或缺省值推断，避免把另一主体的 Core 历史拼入子任务上下文。
     */
    @Override
    public TaskModels.EffectiveContextSnapshot freezeEffectiveContext(String parentThreadId,
                                                                      String sourceTurnId,
                                                                      long expectedParentRevision) {
        ensureOpen();
        TaskRecords.ParentRow parent = transactions.required(mapper -> {
            TaskRecords.ParentRow current = requireParent(mapper, parentThreadId);
            requireParentRevision(current, expectedParentRevision);
            return current;
        });
        AgentStore.ConversationKey key = APP_IDENTITY.conversation(parentThreadId, parent.ownerUserId());
        Optional<InitialContextSeed> frozen = freezeStoreSeed(key, sourceTurnId);
        TaskRecords.ParentRow verified = transactions.required(mapper -> {
            TaskRecords.ParentRow current = requireParent(mapper, parentThreadId);
            requireParentRevision(current, expectedParentRevision);
            return current;
        });
        if (!parent.equals(verified)) {
            throw concurrent("parent changed while its Core context was frozen");
        }
        JsonArray references = frozen.map(this::attachmentReferences).orElseGet(() -> new JsonArray(List.of()));
        JsonObject permissionCeiling = JsonObjects.builder()
                .putText("version", "task_access_v1")
                .putText("accessMode", parent.accessMode().toLowerCase(Locale.ROOT))
                .build();
        return new TaskModels.EffectiveContextSnapshot(parentThreadId, expectedParentRevision,
                frozen.orElse(null), references, permissionCeiling);
    }

    /**
     * 冻结精确的已结算助手答复；继承项从目标自有原始历史读取，避免后续本地 Turn 或检查点越过旧分叉边界。
     *
     * @author kongweiguang
     */
    @Override
    public TaskModels.ThreadForkContextSnapshot freezeThreadForkContext(String parentThreadId,
                                                                        String sourceItemId,
                                                                        long expectedParentRevision) {
        ensureOpen();
        ForkSource source = transactions.required(mapper -> {
            TaskRecords.ParentRow current = requireParent(mapper, parentThreadId);
            requireParentRevision(current, expectedParentRevision);
            ThreadForkRecords.HistoryItemRow inherited = mapper.threadForks()
                    .selectHistoryItem(parentThreadId, sourceItemId);
            if (inherited != null) {
                PersistenceRecords.SnapshotItemRow publicRow = readFrozenItem(inherited.itemJson());
                requireForkableFinalAnswer(publicRow, sourceItemId);
                if (inherited.contextThroughOrdinal() == null) {
                    throw invalidState("inherited final answer has no frozen model boundary");
                }
                List<ContextMessage> messages = mapper.threadForks()
                        .selectContextMessages(parentThreadId, inherited.contextThroughOrdinal()).stream()
                        .map(row -> seedCodec.decodeMessageValue(row.messageJson())).toList();
                if (messages.isEmpty()) {
                    throw invalidState("inherited final answer has no frozen model history");
                }
                ContextMessage boundary = messages.getLast();
                if (boundary.ordinal() != inherited.contextThroughOrdinal()
                    || boundary.role() != ContextMessage.Role.ASSISTANT || boundary.hasToolCall()
                    || !inherited.sourceItemId().equals(JaMessageIds.toItemId(boundary.messageId()))
                    || !inherited.sourceTurnId().equals(boundary.turnId())) {
                    throw invalidState("inherited source identity does not match its model boundary");
                }
                return new ForkSource(current, publicRow, inherited.sourceTurnId(), inherited.sourceThreadId(),
                        inherited.sourceItemId(), inherited.contextThroughOrdinal(), messages, true);
            }
            PersistenceRecords.SnapshotItemRow publicRow = mapper.history()
                    .selectSnapshotItem(parentThreadId, sourceItemId);
            requireForkableFinalAnswer(publicRow, sourceItemId);
            return new ForkSource(current, publicRow, publicRow.turnId(), parentThreadId, sourceItemId,
                    null, List.of(), false);
        });

        List<ContextMessage> rawMessages;
        Optional<CheckpointStore.ContextCheckpoint> checkpoint = Optional.empty();
        if (source.inherited()) {
            rawMessages = source.contextMessages();
        } else {
            AgentStore.ConversationKey key = APP_IDENTITY.conversation(parentThreadId, source.parent().ownerUserId());
            AgentLoopPersistence.ConversationHistory history = agentPersistence.restoreConversation(
                    key, CancellationToken.none());
            List<PersistenceRecords.TurnRow> turnRows = transactions.required(mapper ->
                    mapper.agent().selectTurns(parentThreadId).stream()
                            .filter(PersistenceRecords.TurnRow::currentPath).toList());
            Set<String> visibleNativeTurnIds = turnRows.stream().map(PersistenceRecords.TurnRow::turnId)
                    .collect(java.util.stream.Collectors.toSet());
            List<ContextMessage> nativeMessages = agentContexts.fromSnapshot(history, visibleNativeTurnIds,
                            source.sourceTurnId()).stream()
                    .filter(message -> visibleNativeTurnIds.contains(message.turnId()))
                    .toList();
            List<ContextMessage> inheritedMessages = transactions.required(mapper ->
                    mapper.threadForks().selectContextMessages(parentThreadId, null).stream()
                            .map(row -> seedCodec.decodeMessageValue(row.messageJson())).toList());
            java.util.LinkedHashMap<String, ContextMessage> combined = new java.util.LinkedHashMap<>();
            inheritedMessages.forEach(message -> combined.put(message.messageId(), message));
            nativeMessages.forEach(message -> {
                if (combined.putIfAbsent(message.messageId(), message) != null) {
                    throw invalidState("native context duplicates a frozen inherited message");
                }
            });
            rawMessages = throughReply(combined.values().stream()
                    .sorted(java.util.Comparator.comparingLong(ContextMessage::ordinal)).toList(), sourceItemId);
        }
        if (rawMessages.isEmpty()) {
            throw invalidState("selected final answer has no model history");
        }
        InitialContextSeed.SourceIdentity sourceIdentity = new InitialContextSeed.SourceIdentity(
                APP_IDENTITY.tenantId(), source.contextSourceThreadId(), source.sourceTurnId());
        InitialContextSeed seed = agentContexts.freezeInitialContextSeedThroughMessages(
                rawMessages, sourceIdentity, checkpoint);
        TaskRecords.ParentRow verified = transactions.required(mapper -> {
            TaskRecords.ParentRow current = requireParent(mapper, parentThreadId);
            requireParentRevision(current, expectedParentRevision);
            return current;
        });
        if (!source.parent().equals(verified)) {
            throw concurrent("parent changed while its reply context was frozen");
        }
        return new TaskModels.ThreadForkContextSnapshot(parentThreadId, expectedParentRevision, sourceItemId,
                source.sourceTurnId(), source.contextSourceThreadId(), source.contextSourceItemId(), rawMessages, seed);
    }

    /**
     * 仅接受已持久化的最终答复作为分叉边界；同一 Turn 中更早提交的工具或进度项不能替代该答复。
     *
     * @author kongweiguang
     */
    private static void requireForkableFinalAnswer(PersistenceRecords.SnapshotItemRow item, String itemId) {
        if (item == null || !itemId.equals(item.itemId()) || !"message".equals(item.itemKind())
            || !"FINAL_ANSWER".equals(item.messageKind()) || item.turnId() == null
            || !itemId.equals(JaMessageIds.toItemId(item.itemId()))) {
            throw invalidState("fork source is not a settled assistant reply");
        }
    }

    /**
     * 只保留至指定公开助手答复的模型事实；调用方先验证 FINAL_ANSWER，故完整工具前缀不会截掉边界答复。
     *
     * @author kongweiguang
     */
    private static List<ContextMessage> throughReply(List<ContextMessage> source, String sourceItemId) {
        List<ContextMessage> prefix = new ArrayList<>();
        for (ContextMessage message : source) {
            prefix.add(message);
            if (sourceItemId.equals(JaMessageIds.toItemId(message.messageId()))) {
                if (message.role() != ContextMessage.Role.ASSISTANT || message.hasToolCall()) {
                    throw invalidState("fork boundary does not identify a complete assistant answer");
                }
                return List.copyOf(prefix);
            }
        }
        throw invalidState("fork answer is absent from the model history");
    }

    /**
     * 快照项 JSON 由存储序列化器生成而非客户端提交；严格解码使损坏行在分叉前失败关闭。
     * 解码失败保留底层 cause 供进程内诊断，RPC 继续只投影稳定 INVALID_STATE 分类。
     *
     * @author kongweiguang
     */
    private PersistenceRecords.SnapshotItemRow readFrozenItem(String jsonValue) {
        try {
            return objectMapper.readValue(jsonValue, PersistenceRecords.SnapshotItemRow.class);
        } catch (java.io.IOException failure) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                    "frozen history item is invalid", failure);
        }
    }

    /**
     * 普通 USER 首轮只读取创建时冻结的 SIDE_TASK typed seed，并从 Thread 行取得 Core owner；允许已初始化但无执行
     * 事实的 Store 会话，只有执行、记录或事件水位非空时才按缺少 Ja Turn 的损坏状态失败关闭。
     */
    @Override
    public Optional<InitialContextSeed> findInitialContextSeedForFirstTurn(String taskThreadId) {
        ensureOpen();
        String threadId = requireMailboxThreadId(taskThreadId);
        return transactions.required(mapper -> {
            TaskRecords.TaskSummaryRow row = mapper.tasks().selectTaskSummary(threadId);
            if (row == null || !TaskModels.Kind.SIDE_TASK.name().equals(row.taskKind())
                || !TaskModels.Lifecycle.INDEPENDENT.name().equals(row.lifecycle())) {
                return Optional.empty();
            }
            if (!mapper.agent().selectTurns(threadId).isEmpty()) {
                return Optional.empty();
            }
            PersistenceRecords.ThreadRow thread = mapper.history().selectThread(threadId);
            if (thread == null) {
                throw invalidState("side Task Thread is missing its persisted owner");
            }
            AgentStore.ConversationKey key = APP_IDENTITY.conversation(threadId, thread.ownerUserId());
            Optional<AgentStore.ConversationSnapshot> conversation = rawAgentStore.loadConversation(key);
            if (conversation.isPresent()) {
                AgentStore.ConversationSnapshot facts = conversation.orElseThrow();
                if (facts.latestExecutionSequence() > 0 || facts.recordSequence() > 0
                    || facts.eventSequence() > 0) {
                    throw invalidState("Task has Core execution facts without a Ja Turn projection");
                }
            }
            TaskRecords.ContextSeedRow stored = mapper.tasks().selectContextSeed(threadId);
            if (stored == null) {
                throw invalidState("side Task has no frozen context seed");
            }
            TaskModels.ContextSeed seed = seed(stored);
            if (seed.inheritanceMode() != TaskModels.InheritanceMode.EFFECTIVE_CONTEXT) {
                throw invalidState("side Task does not have effective-context inheritance");
            }
            return Optional.ofNullable(seed.initialContextSeed());
        });
    }

    /**
     * 空父 Thread 不造来源 Turn；有历史时只接受显式来源或该快照中的真实最新 Turn。
     */
    private Optional<InitialContextSeed> freezeStoreSeed(AgentStore.ConversationKey key, String requestedTurnId) {
        if (rawAgentStore.loadConversation(key).isEmpty()) {
            if (requestedTurnId != null) {
                throw invalidState("parent Core conversation does not exist");
            }
            return Optional.empty();
        }
        AgentLoopPersistence.ConversationHistory history = agentPersistence.restoreConversation(
                key, CancellationToken.none());
        String sourceTurnId = requestedTurnId;
        if (sourceTurnId == null) {
            sourceTurnId = history.executions().stream()
                    .reduce((first, later) -> later)
                    .map(execution -> execution.key().turnId()).orElse(null);
        }
        if (sourceTurnId == null) {
            return Optional.empty();
        }
        Set<String> visibleTurnIds = history.executions().stream()
                .map(execution -> execution.key().turnId()).collect(java.util.stream.Collectors.toSet());
        if (!visibleTurnIds.contains(sourceTurnId)) {
            throw invalidState("parent source Turn is absent from the Core snapshot");
        }
        Optional<CheckpointStore.ContextCheckpoint> checkpoint = checkpoints.read(history.conversation()).checkpoint();
        Optional<InitialContextSeed> seed = agentContexts.freezeInitialContextSeed(history, visibleTurnIds,
                sourceTurnId, new InitialContextSeed.SourceIdentity(key.tenantId(), key.threadId(),
                        sourceTurnId), checkpoint);
        if (seed.isEmpty()) {
            return Optional.empty();
        }
        InitialContextSeed value = seed.orElseThrow();
        if (value.messages().size() > MAX_EFFECTIVE_MESSAGES
            || seedCodec.encode(value).getBytes(StandardCharsets.UTF_8).length > MAX_EFFECTIVE_BYTES) {
            throw invalidState("effective context requires compaction before forking");
        }
        return seed;
    }

    /**
     * 只保存 seed 中真实保留的父附件引用，供 child AttachmentResolver 沿 lineage 授权读取原绑定。
     */
    private JsonArray attachmentReferences(InitialContextSeed seed) {
        List<io.github.kongweiguang.ja.agent.domain.json.JsonValue> references = new java.util.ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ContextMessage message : seed.messages()) {
            for (ContextMessage.Block block : message.blocks()) {
                if (!(block instanceof ContextMessage.AttachmentBlock(String attachmentId))
                    || !seen.add(attachmentId)) {
                        continue;
                    }
                references.add(JsonObjects.builder().putText("kind", "attachment")
                        .putText("attachmentId", attachmentId).build());
            }
        }
        return new JsonArray(references);
    }

    /**
     * 侧边任务以 Core conversation 初始化为外层事务，再通过借用连接写入 Ja Thread 和 Task facts；
     * 不创建 Turn 或 USER fact，避免空闲任务被误判成已经调用 Provider。
     * SQL 故障在中立参与者边界包装，业务运行时失败仍原样返回 Store 回滚。
     */
    @Override
    public TaskModels.Summary admitIdleChild(TaskModels.ChildAdmission child) {
        ensureOpen();
        Objects.requireNonNull(child, "child");
        if (child.kind() != TaskModels.Kind.SIDE_TASK
            || child.lifecycle() != TaskModels.Lifecycle.INDEPENDENT) {
            throw relation("only independent side tasks may be idle");
        }
        String threadId = child.childThread().threadId();
        AgentStore.ConversationKey conversation = APP_IDENTITY.conversation(threadId);
        rawAgentStore.initializeConversation(
                new AgentStore.ConversationInitialization(conversation, child.contextSeed().createdAt()),
                transaction -> AgentStoreConnectionScope.participate(JdbcTransactionResources.connection(transaction),
                        () -> transactions.required(mapper -> {
                            admitIdleChild(mapper, child);
                            return null;
                        })));
        return findTask(threadId).orElseThrow(() -> invalidState("idle Task was not committed"));
    }

    /**
     * admission 前解析 root Turn，participant 提交时会在写事务内再次复核完整因果链。
     */
    @Override
    public String rootTurnIdForAdmission(String parentThreadId, String parentTurnId, boolean requireNonTerminal) {
        ensureOpen();
        return transactions.required(mapper -> causalRootTurn(mapper, parentThreadId, parentTurnId,
                requireNonTerminal));
    }

    /**
     * Admission 中复核冻结的父策略并与 Child policy 行同时提交，阻止并发设置变化生成混合快照。
     */
    @Override
    public void applyChildAdmission(TaskModels.ChildAdmission child, TurnAdmission turn,
                                    AdmissionFacts facts, SubagentPolicy expectedParentPolicy) {
        ensureOpen();
        requireStoreScope();
        Objects.requireNonNull(child, "child");
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(expectedParentPolicy, "expectedParentPolicy");
        if (!child.childThread().threadId().equals(turn.threadId()) || turn.expectedThreadRevision() != 0
            || !child.childThread().threadId().equals(facts.identity().threadId())
            || !turn.turnId().equals(facts.identity().turnId())) {
            throw relation("child Turn does not match the prepared Ja admission");
        }
        transactions.required(mapper -> {
            applyChildAdmission(mapper, child, turn, facts, expectedParentPolicy);
            return null;
        });
    }

    /**
     * 普通 USER 准入仅补齐 Task context/activity 投影，禁止复制 AgentStore 已创建的 Turn 与 message。
     */
    @Override
    public void applyUserTurnAdmission(TaskModels.UserTurnAdmission admission, AdmissionFacts facts) {
        ensureOpen();
        requireStoreScope();
        TaskModels.UserTurnAdmission value = Objects.requireNonNull(admission, "admission");
        AdmissionFacts request = Objects.requireNonNull(facts, "facts");
        TurnAdmission turn = value.turn();
        if (request.origin() != TurnOrigin.USER
            || request.newThread().isPresent()
            || !turn.threadId().equals(request.identity().threadId())
            || !turn.turnId().equals(request.identity().turnId())
            || turn.expectedThreadRevision() != request.expectedThreadRevision()
            || !turn.requestedAt().equals(request.acceptedAt())) {
            throw relation("ordinary Task Turn does not match its Ja USER admission");
        }
        transactions.required(mapper -> {
            applyUserTurnAdmission(mapper, value, request);
            return null;
        });
    }

    /**
     * Plan 执行 Turn 的 Task 重开、RESUMED 活动与 Core admission 共用同一 MyBatis 连接。
     */
    @Override
    public void applyContinuationAdmission(TaskModels.ContinuationAdmission admission, AdmissionFacts facts) {
        ensureOpen();
        requireStoreScope();
        TaskModels.ContinuationAdmission value = Objects.requireNonNull(admission, "admission");
        AdmissionFacts request = Objects.requireNonNull(facts, "facts");
        TurnOrigin expectedOrigin = TurnOrigin.PLAN_EXECUTION;
        if (request.origin() != expectedOrigin || request.newThread().isPresent()
            || !value.taskThreadId().equals(request.identity().threadId())
            || !value.turnId().equals(request.identity().turnId())
            || !value.occurredAt().equals(request.acceptedAt())
            || request.runId() == null
            || value.kind() != TaskModels.ContinuationKind.PLAN
            || request.planRevisionId() == null) {
            throw relation("Task continuation does not match its Ja admission");
        }
        transactions.required(mapper -> {
            PersistenceRecords.ThreadRow thread = requireThread(mapper, value.taskThreadId());
            if (!thread.workspaceId().equals(request.workspaceId())
                || thread.revision() != request.expectedThreadRevision() + 1) {
                throw concurrent("Task continuation Thread admission revision changed");
            }
            TaskContinuationPersistence.activate(mapper, objectMapper, value);
            return null;
        });
    }

    /**
     * Task 状态和父 Mailbox 必须复用 AgentStore 当前提交的 MyBatis 连接；独立事务会造成 Turn 已完成而 Task 未完成。
     */
    @Override
    public void settleTerminal(AgentStoreTransactionContext transaction, ExecutionEvent.Terminal terminal,
                               Map<String, ExecutionFact> factsByRecordId) {
        ensureOpen();
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(factsByRecordId, "factsByRecordId");
        if (AgentStoreConnectionScope.current().orElse(null) != JdbcTransactionResources.connection(transaction)) {
            throw new IllegalStateException("Task terminal projection requires the AgentStore connection scope");
        }
        transactions.required(mapper -> TaskTerminalPersistence.settleFromExecution(
                mapper, objectMapper, transaction, terminal, factsByRecordId));
    }

    /**
     * 列表摘要不读取 seed JSON、活动正文或 Mailbox。
     */
    @Override
    public Optional<TaskModels.Summary> findTask(String taskThreadId) {
        ensureOpen();
        return transactions.required(mapper -> Optional.ofNullable(mapper.tasks().selectTaskSummary(taskThreadId))
                .map(this::summary));
    }

    /**
     * 根树上限为 64，仍复核结果数量以发现损坏或持久约束漂移。
     */
    @Override
    public List<TaskModels.Summary> listTree(String rootThreadId) {
        ensureOpen();
        return transactions.required(mapper -> {
            List<TaskModels.Summary> result = mapper.tasks().selectTaskTree(rootThreadId).stream()
                    .map(this::summary).toList();
            if (result.size() > MAX_TREE_SIZE) {
                throw invalidState("task tree exceeds storage limit");
            }
            return result;
        });
    }

    /**
     * 会话只观察自己直接委派的子任务；先在 SQL 限定 owner 再分页，侧聊及其后代不会挤掉主会话活动。
     */
    @Override
    public List<TaskModels.ActivityProjection> listRootActivities(String rootThreadId, int limit) {
        ensureOpen();
        if (limit < 1 || limit > 128) {
            throw new IllegalArgumentException("invalid root activity limit");
        }
        return transactions.required(mapper -> mapper.tasks().selectRootActivityProjections(rootThreadId, limit)
                .stream().map(row -> rootActivityProjection(rootThreadId, row)).toList());
    }

    /**
     * 临时标记与关闭回执共用事务 owner；已完成 root 重试读取原范围，进行中关闭返回冻结子树。
     */
    @Override
    public TaskModels.SideChatCloseState beginSideChatClose(String taskThreadId) {
        ensureOpen();
        return transactions.required(mapper -> SideChatPersistence.beginClose(mapper, taskThreadId));
    }

    /**
     * 转交冻结 owner 与身份供同一 purge transaction 重验并回执；ID 分批执行仍共享此事务，
     * 任何关系、容量或终态失败都不留下部分清理或成功回执。
     */
    @Override
    public TaskModels.SideChatCloseReceipt deleteClosedSideChat(String taskThreadId,
                                                                String expectedOwnerRootThreadId,
                                                                List<String> expectedThreadIds) {
        ensureOpen();
        List<String> frozenThreadIds = List.copyOf(Objects.requireNonNull(expectedThreadIds,
                "expectedThreadIds"));
        return transactions.required(mapper -> SideChatPurger.purge(mapper, taskThreadId,
                expectedOwnerRootThreadId, frozenThreadIds, true));
    }

    /**
     * 退出枚举只读取临时标记，不物化正文，也不把旧持久侧任务推断为可删除对象。
     */
    @Override
    public List<TaskRepository.TemporarySideChat> listTemporarySideChats() {
        ensureOpen();
        return transactions.required(SideChatPersistence::listMarkers);
    }

    /**
     * 详情的两个增量游标独立推进，避免全局 sequence 的空洞造成错误截断。
     */
    @Override
    public Optional<TaskModels.Detail> readTask(String taskThreadId, long afterActivitySequence,
                                                long afterMailboxSequence, int limit) {
        ensureOpen();
        if (afterActivitySequence < 0 || afterMailboxSequence < 0 || limit < 1 || limit > 200) {
            throw new IllegalArgumentException("invalid task detail page");
        }
        return transactions.required(mapper -> {
            TaskRecords.TaskSummaryRow row = mapper.tasks().selectTaskSummary(taskThreadId);
            if (row == null) {
                return Optional.empty();
            }
            TaskRecords.ContextSeedRow seed = mapper.tasks().selectContextSeed(taskThreadId);
            if (seed == null) {
                throw invalidState("task context seed is unavailable");
            }
            List<TaskModels.Activity> activities = mapper.tasks().selectActivities(
                    taskThreadId, afterActivitySequence, limit).stream().map(this::activity).toList();
            List<TaskModels.MailboxMessage> mailbox = mapper.tasks().selectMailbox(
                    taskThreadId, afterMailboxSequence, limit).stream().map(this::mailbox).toList();
            PersistenceRecords.ThreadRow threadRow = mapper.history().selectThread(taskThreadId);
            if (threadRow == null) {
                throw invalidState("task thread is unavailable");
            }
            return Optional.of(new TaskModels.Detail(summary(row), thread(threadRow), seed(seed), activities, mailbox));
        });
    }

    /**
     * 普通 MESSAGE 只写入有界 Mailbox；它是跨会话投递事实，不应制造 Task Activity 或未读投影。
     */
    @Override
    public TaskModels.MessageEnqueueReceipt enqueueMessage(TaskModels.MailboxEnvelope mailbox) {
        ensureOpen();
        Objects.requireNonNull(mailbox, "mailbox");
        if (mailbox.kind() != TaskModels.MailboxKind.MESSAGE) {
            throw new IllegalArgumentException("QueueOnly requires MESSAGE kind");
        }
        return transactions.required(mapper -> {
            RoutedTask route = route(mapper, mailbox.senderThreadId(), mailbox.targetThreadId(), false, false);
            requireCrossWorkspaceMessageContent(mapper, mailbox);
            TaskRecords.MailboxRow existing = mapper.tasks().selectMailboxByIdempotency(
                    mailbox.senderThreadId(), mailbox.operationKey());
            if (existing != null) {
                return messageReceipt(identicalMailbox(existing, mailbox), false);
            }
            String contentJson = json.writeContent(mailbox.content());
            requireMailboxCapacity(mapper, mailbox.targetThreadId(), contentJson);
            Long sequence = mapper.tasks().insertMailbox(mailboxInsert(mailbox, route.rootThreadId(),
                    contentJson, "PENDING", null));
            if (sequence == null) {
                return messageReceipt(identicalMailbox(requireExistingMailbox(mapper, mailbox), mailbox), false);
            }
            return messageReceipt(mailbox(mapper.tasks().selectMailboxByIdempotency(
                    mailbox.senderThreadId(), mailbox.operationKey())), true);
        });
    }

    /**
     * 回执只返回 Mailbox 事实；普通消息可能没有 Child projection owner。
     */
    private TaskModels.MessageEnqueueReceipt messageReceipt(TaskModels.MailboxMessage mailbox,
                                                            boolean inserted) {
        return new TaskModels.MessageEnqueueReceipt(mailbox, inserted);
    }

    /**
     * 公开重试必须在应用层创建 Turn 前取得原始绑定；只读事务仍执行完整路由与内容一致性校验。
     */
    @Override
    public Optional<TaskModels.FollowUpAdmissionReceipt> findFollowUpByIdempotency(
            TaskModels.MailboxEnvelope envelope) {
        ensureOpen();
        Objects.requireNonNull(envelope, "envelope");
        if (envelope.kind() != TaskModels.MailboxKind.FOLLOW_UP) {
            throw new IllegalArgumentException("idempotency lookup requires FOLLOW_UP kind");
        }
        return transactions.required(mapper -> {
            RoutedTask route = route(mapper, envelope.senderThreadId(), envelope.targetThreadId(), true, true);
            if (!route.taskThreadId().equals(envelope.targetThreadId())) {
                throw relation("follow-up target must be a Child Task");
            }
            TaskRecords.MailboxRow existing = mapper.tasks().selectMailboxByIdempotency(
                    envelope.senderThreadId(), envelope.operationKey());
            return existing == null ? Optional.empty() : Optional.of(existingFollowUp(mapper, envelope, existing));
        });
    }

    /**
     * Follow-up 绑定必须加入 admission 当前连接，使新 Turn、幂等 Mailbox 和消息顺序共同提交。
     */
    @Override
    public void applyFollowUpAdmission(TaskModels.FollowUpAdmission admission, AdmissionFacts facts) {
        ensureOpen();
        requireStoreScope();
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(facts, "facts");
        if (!admission.turn().threadId().equals(facts.identity().threadId())
            || !admission.turn().turnId().equals(facts.identity().turnId())
            || facts.origin() != io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin.CHILD_TASK) {
            throw relation("follow-up Turn does not match the prepared Ja admission");
        }
        transactions.required(mapper -> {
            applyFollowUpAdmission(mapper, admission, facts);
            return null;
        });
    }

    /**
     * 幂等回读只按发送方命名空间查询，不读取正文以外的任务树或触发 Mailbox 状态变化。
     */
    @Override
    public Optional<TaskModels.MailboxMessage> findByIdempotency(String senderThreadId, String operationKey) {
        ensureOpen();
        String sender = requireMailboxThreadId(senderThreadId);
        String key = requireMailboxOperationKey(operationKey);
        return transactions.required(mapper -> Optional.ofNullable(
                mapper.tasks().selectMailboxByIdempotency(sender, key)).map(this::mailbox));
    }

    /**
     * 在读取快照内按数据库 sequence 返回最多八条待投递跨会话消息，剩余行始终保持 PENDING。
     */
    @Override
    public List<TaskModels.MailboxMessage> listPending(String targetThreadId, int limit) {
        ensureOpen();
        String target = requireMailboxThreadId(targetThreadId);
        if (limit < 1 || limit > 8) {
            throw new IllegalArgumentException("mailbox query limit must be between 1 and 8");
        }
        return transactions.required(mapper -> mapper.tasks().selectPendingMessages(target, limit)
                .stream().map(this::mailbox).toList());
    }

    /**
     * 已有幂等事实只能回放其真实绑定身份；PENDING 或内容漂移都不能伪造成成功接纳。
     */
    private TaskModels.FollowUpAdmissionReceipt existingFollowUp(
            PersistenceMappers mapper, TaskModels.MailboxEnvelope envelope,
            TaskRecords.MailboxRow existing) {
        TaskModels.MailboxMessage persisted = identicalMailbox(existing, envelope);
        if (persisted.state() != TaskModels.MailboxState.CONSUMED || persisted.boundTurnId() == null) {
            throw invalidState("follow-up idempotency fact is not consumed");
        }
        PersistenceRecords.ThreadRow thread = requireThread(mapper, envelope.targetThreadId());
        AdmissionReceipt receipt = new AdmissionReceipt(
                thread.threadId(), persisted.boundTurnId(), thread.revision(), 0, null);
        return new TaskModels.FollowUpAdmissionReceipt(receipt, persisted);
    }

    /**
     * 已读边界由 SQL 重算未读数，避免 activity sequence 属于全库时错误做减法。
     */
    @Override
    public TaskModels.Summary markSeen(String taskThreadId, long expectedTaskRevision,
                                       long throughActivitySequence, Instant occurredAt) {
        ensureOpen();
        return transactions.required(mapper -> {
            requireChanged(mapper.tasks().compareAndSetSeen(new TaskRecords.SeenCas(taskThreadId,
                            expectedTaskRevision, throughActivitySequence, instant(occurredAt))),
                    "task seen boundary changed concurrently");
            return requireTask(mapper, taskThreadId);
        });
    }

    /**
     * 低频活动和 projection 状态通过同一 CAS 原子提交。
     */
    @Override
    public TaskModels.Summary recordActivity(TaskModels.ActivityMutation mutation) {
        ensureOpen();
        Objects.requireNonNull(mutation, "mutation");
        return transactions.required(mapper -> {
            TaskModels.Summary current = requireTask(mapper, mutation.taskThreadId());
            if (current.projection().revision() != mutation.expectedTaskRevision()) {
                throw concurrent("task projection revision is stale");
            }
            long sequence = insertActivity(mapper, mutation.activityId(), current.lineage().rootThreadId(),
                    mutation.taskThreadId(), mutation.actorThreadId(), mutation.causalTurnId(),
                    mutation.kind(), mutation.summary(), mutation.occurredAt());
            String startedAt = mutation.state() == TaskModels.State.RUNNING ? instant(mutation.occurredAt()) : null;
            String completedAt = terminal(mutation.state()) ? instant(mutation.occurredAt()) : null;
            requireChanged(mapper.tasks().compareAndSetProjection(new TaskRecords.ProjectionCas(
                            mutation.taskThreadId(), mutation.expectedTaskRevision(), mutation.state().name(), sequence,
                            mutation.latestSafeSummary(), startedAt, completedAt, instant(mutation.occurredAt()), true)),
                    "task projection changed concurrently");
            mapper.tasks().recomputeAncestorCounts(mutation.taskThreadId(), instant(mutation.occurredAt()));
            return requireTask(mapper, mutation.taskThreadId());
        });
    }

    /**
     * 递归 SQL 只沿 ATTACHED 边传播，遇到 INDEPENDENT 节点即停止。
     */
    @Override
    public List<TaskModels.Summary> attachedDescendants(String parentThreadId) {
        ensureOpen();
        return transactions.required(mapper -> mapper.tasks().selectAttachedDescendants(parentThreadId)
                .stream().map(this::summary).toList());
    }

    /**
     * 父取消行本身就是持久欠账；查询只暴露仍有非终态 ATTACHED 直接子 Turn 的身份。
     */
    @Override
    public List<TaskModels.CancellationPropagation> pendingCancellationPropagations() {
        ensureOpen();
        return transactions.required(mapper -> mapper.tasks().selectPendingCancellationPropagations().stream()
                .map(row -> new TaskModels.CancellationPropagation(row.parentThreadId(), row.parentTurnId()))
                .toList());
    }

    /**
     * 整树删除采用 Thread soft-delete + Task metadata 物理删除，保留不可变 Conversation 审计事实。
     */
    @Override
    public int deleteTree(String taskThreadId, long expectedTaskRevision, Instant occurredAt) {
        ensureOpen();
        return transactions.required(mapper -> {
            TaskModels.Summary task = requireTask(mapper, taskThreadId);
            if (task.projection().revision() != expectedTaskRevision
                || mapper.tasks().countDeleteTarget(new TaskRecords.TreeDelete(
                    taskThreadId, expectedTaskRevision, instant(occurredAt))) != 1) {
                throw concurrent("task tree delete revision is stale");
            }
            if (mapper.tasks().countNonTerminalTurnsInTree(taskThreadId) != 0) {
                throw new TaskRepositoryException(TaskRepositoryException.Code.TREE_DELETE_REQUIRED,
                        "task tree contains non-terminal Turns");
            }
            int count = mapper.tasks().countTaskSubtree(taskThreadId);
            List<String> seedIds = mapper.tasks().selectTreeSeedIds(taskThreadId);
            mapper.tasks().softDeleteTaskThreads(new TaskRecords.TreeDelete(
                    taskThreadId, expectedTaskRevision, instant(occurredAt)));
            mapper.tasks().deleteTreeWriteClaims(taskThreadId);
            mapper.tasks().deleteTreeMailbox(taskThreadId);
            mapper.tasks().deleteTreeProjections(taskThreadId);
            mapper.tasks().deleteTreeActivities(taskThreadId);
            mapper.tasks().deleteTreeLineage(taskThreadId);
            if (!seedIds.isEmpty()) {
                mapper.tasks().deleteContextSeeds(seedIds);
            }
            mapper.tasks().recomputeDescendantCounts(task.lineage().rootThreadId(), instant(occurredAt));
            return count;
        });
    }

    /**
     * WAITING 声明与 fencing token 在同一事务分配，SQLite writer lock 保证单调唯一。
     */
    @Override
    public WorkspaceWriteClaimPort.WriteClaim enqueue(String claimId, String workspaceId, String threadId,
                                                      String turnId, long processGeneration, Instant requestedAt) {
        ensureOpen();
        if (processGeneration < 1) {
            throw new IllegalArgumentException("invalid process generation");
        }
        return transactions.required(mapper -> {
            long token = mapper.tasks().selectNextFencingToken(workspaceId);
            Long sequence = mapper.tasks().insertWriteClaim(new TaskRecords.WriteClaimInsert(claimId,
                    workspaceId, threadId, turnId, processGeneration, token, instant(requestedAt)));
            if (sequence == null) {
                throw concurrent("write claim insert lost");
            }
            return writeClaim(requireWriteClaim(mapper, claimId));
        });
    }

    /**
     * 获取失败是正常 FIFO 竞争，返回 empty 而非把它伪装成存储异常。
     */
    @Override
    public Optional<WorkspaceWriteClaimPort.WriteClaim> tryAcquire(
            String claimId, long fencingToken, Instant acquiredAt) {
        ensureOpen();
        return mutateClaim(claimId, fencingToken, acquiredAt, ClaimMutation.ACQUIRE);
    }

    /**
     * heartbeat 竞争失败返回 empty，让 owner 立即停止写副作用。
     */
    @Override
    public Optional<WorkspaceWriteClaimPort.WriteClaim> heartbeat(
            String claimId, long fencingToken, Instant occurredAt) {
        ensureOpen();
        return mutateClaim(claimId, fencingToken, occurredAt, ClaimMutation.HEARTBEAT);
    }

    /**
     * release 重试回读相同 token 的终态行并幂等返回。
     */
    @Override
    public Optional<WorkspaceWriteClaimPort.WriteClaim> release(
            String claimId, long fencingToken, Instant occurredAt) {
        ensureOpen();
        return mutateClaim(claimId, fencingToken, occurredAt, ClaimMutation.RELEASE);
    }

    /**
     * abandon 可终结 WAITING 或 HELD，但仍要求 fencing token 匹配。
     */
    @Override
    public Optional<WorkspaceWriteClaimPort.WriteClaim> abandon(
            String claimId, long fencingToken, Instant occurredAt) {
        ensureOpen();
        return mutateClaim(claimId, fencingToken, occurredAt, ClaimMutation.ABANDON);
    }

    /**
     * 只允许在 App Server 持有 JaDatabase 独占 lease 且尚未接纳本进程 claim 时调用；代际分配与
     * 活动 claim 的废弃与新声明同事务提交，即使墙钟代际相同也不能残留两个当前 owner。
     */
    public long beginProcessGeneration(Instant occurredAt) {
        ensureOpen();
        return transactions.required(mapper -> {
            Long generation = mapper.tasks().allocateProcessGeneration();
            if (generation == null || generation < 1) {
                throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                        "process generation allocation lost");
            }
            mapper.tasks().abandonActiveWriteClaims(instant(occurredAt));
            return generation;
        });
    }

    /**
     * 以 Task 仓储唯一事务 owner 收敛自然过期后的 SUSPENDED 投影；重复调用由稳定活动身份保持幂等。
     */
    public Optional<TaskRecords.TaskSummaryRow> reconcileRecoveredSuspension(String turnId, Instant occurredAt) {
        ensureOpen();
        return transactions.required(mapper -> TaskRecoveryPersistence.reconcileSuspended(
                mapper, objectMapper, turnId, occurredAt));
    }

    /**
     * composition shutdown 后阻止迟到事务，datasource 生命周期仍由 JaDatabase owner 管理。
     */
    @Override
    public void close() {
        closed.set(true);
    }

    /**
     * Child 的通用 Turn 和 USER message 已由 admission participant 写入；本投影只验证因果行并补 Task 事实，
     * 不能再次 INSERT turns，否则会与同事务中的唯一执行身份冲突。
     */
    private void applyChildAdmission(PersistenceMappers mapper, TaskModels.ChildAdmission child,
                                     TurnAdmission turn, AdmissionFacts facts,
                                     SubagentPolicy expectedParentPolicy) {
        ChildAdmissionContext context = prepareChild(mapper, child);
        String rootThreadId = context.rootThreadId();
        String rootTurnId = causalRootTurn(mapper, child.parentThreadId(), child.originTurnId(),
                child.lifecycle() == TaskModels.Lifecycle.ATTACHED);
        requireCausalBinding(facts, child.originTurnId(), rootTurnId);
        UserContent admittedUser = verifyAdmittedUserMessage(mapper, turn);
        verifyAdmittedChildThread(mapper, child, facts, turn, admittedUser);
        insertChildMetadata(mapper, context, child, true, expectedParentPolicy);
        verifyAdmittedTurnCausality(mapper, turn, child.originTurnId(), rootTurnId);
        validateFirstTurnContextSeed(mapper, turn);
        bindAttachments(mapper, facts.workspaceId(), turn);
        long activitySequence = insertActivity(mapper, child.activityId(), rootThreadId,
                child.childThread().threadId(), child.parentThreadId(), child.originTurnId(),
                TaskModels.ActivityKind.DISPATCHED, child.activitySummary(), child.contextSeed().createdAt());
        requireChanged(mapper.tasks().insertProjection(new TaskRecords.ProjectionInsert(
                child.childThread().threadId(), rootThreadId, TaskModels.State.QUEUED.name(),
                activitySequence, safeSummary(child.activitySummary()), null,
                instant(child.contextSeed().createdAt()))), "task projection insert lost");
        if (child.kind() == TaskModels.Kind.SIDE_TASK) {
            SideChatPersistence.insertOpen(mapper, child.childThread().threadId());
        }
        // 上下文来源不是委派关系，创建侧聊不能增加来源任务的后代运行统计。
        if (child.lifecycle() == TaskModels.Lifecycle.ATTACHED) {
            mapper.tasks().incrementAncestorDescendants(child.parentThreadId());
        }
    }

    /**
     * Follow-up 只追加 Task/Mailbox 投影；通用 Turn、message、cursor 和 Ja timeline 已由 Store 写好。
     */
    private void applyFollowUpAdmission(PersistenceMappers mapper, TaskModels.FollowUpAdmission admission,
                                        AdmissionFacts facts) {
        TaskModels.MailboxEnvelope envelope = admission.mailbox();
        RoutedTask route = route(mapper, envelope.senderThreadId(), envelope.targetThreadId(), true, true);
        if (!route.taskThreadId().equals(envelope.targetThreadId())) {
            throw relation("follow-up target must be a Child Task");
        }
        TaskRecords.MailboxRow existing = mapper.tasks().selectMailboxByIdempotency(
                envelope.senderThreadId(), envelope.operationKey());
        if (existing != null) {
            throw concurrent("follow-up admission already has a mailbox receipt");
        }
        TaskModels.Summary task = requireTask(mapper, envelope.targetThreadId());
        if (task.projection().revision() != admission.expectedTaskRevision()) {
            throw concurrent("task projection revision is stale");
        }
        PersistenceRecords.ThreadRow thread = requireThread(mapper, envelope.targetThreadId());
        if (thread.revision() != facts.expectedThreadRevision() + 1
            || !thread.workspaceId().equals(facts.workspaceId())) {
            throw concurrent("follow-up Thread admission revision changed");
        }
        String rootTurnId = causalRootTurn(mapper, null, envelope.causalTurnId(), false);
        requireCausalBinding(facts, envelope.causalTurnId(), rootTurnId);
        verifyAdmittedTurnCausality(mapper, admission.turn(), envelope.causalTurnId(), rootTurnId);
        verifyAdmittedUserMessage(mapper, admission.turn());
        validateFirstTurnContextSeed(mapper, admission.turn());
        bindAttachments(mapper, facts.workspaceId(), admission.turn());
        String contentJson = json.writeContent(envelope.content());
        requireMailboxCapacity(mapper, envelope.targetThreadId(), contentJson);
        Long mailboxSequence = mapper.tasks().insertMailbox(mailboxInsert(envelope, route.rootThreadId(),
                contentJson, "PENDING", null));
        if (mailboxSequence == null) {
            throw concurrent("follow-up idempotency changed concurrently");
        }
        requireChanged(mapper.tasks().bindMailbox(new TaskRecords.MailboxBind(envelope.messageId(),
                        envelope.targetThreadId(), admission.turn().turnId(), instant(envelope.createdAt()))),
                "follow-up mailbox binding lost");
        requireChanged(mapper.tasks().consumeFollowUpMailbox(new TaskRecords.MailboxConsume(envelope.messageId(),
                        envelope.targetThreadId(), admission.turn().turnId(), instant(envelope.createdAt()))),
                "follow-up mailbox consumption lost");
        long activitySequence = insertActivity(mapper, admission.activityId(), route.rootThreadId(),
                route.taskThreadId(), envelope.senderThreadId(), envelope.causalTurnId(),
                TaskModels.ActivityKind.FOLLOW_UP_QUEUED, admission.activitySummary(), envelope.createdAt());
        requireChanged(mapper.tasks().compareAndSetFollowUpProjection(new TaskRecords.FollowUpProjectionCas(
                        route.taskThreadId(), admission.expectedTaskRevision(), activitySequence,
                        safeSummary(admission.activitySummary()), instant(envelope.createdAt()))),
                "task projection changed concurrently");
        mapper.tasks().recomputeAncestorCounts(route.taskThreadId(), instant(envelope.createdAt()));
    }

    /**
     * Task 状态、首次继承上下文、附件与 Activity 共用当前 admission connection 并整体回滚。
     */
    private void applyUserTurnAdmission(PersistenceMappers mapper, TaskModels.UserTurnAdmission admission,
                                        AdmissionFacts facts) {
        TurnAdmission turn = admission.turn();
        String threadId = turn.threadId();
        TaskModels.Summary task = requireTask(mapper, threadId);
        if (task.projection().revision() != admission.expectedTaskRevision()) {
            throw concurrent("Task projection changed before USER admission");
        }
        SideChatPersistence.requireTaskAdmissionOpen(mapper, threadId);
        PersistenceRecords.ThreadRow thread = requireThread(mapper, threadId);
        if (!thread.workspaceId().equals(facts.workspaceId())
            || thread.revision() != facts.expectedThreadRevision() + 1) {
            throw concurrent("Task Thread does not match the committed USER admission");
        }
        TaskModels.State state = task.projection().state();
        if (state == TaskModels.State.WAITING_APPROVAL || state == TaskModels.State.WAITING_INPUT
            || state == TaskModels.State.SUSPENDED) {
            throw relation("Task requires its explicit wait or recovery operation before a new USER Turn");
        }

        verifyAdmittedUserMessage(mapper, turn);
        validateFirstTurnContextSeed(mapper, turn);
        bindAttachments(mapper, facts.workspaceId(), turn);
        String activityId = userAdmissionActivityId(turn.turnId());
        String activitySummary = "用户提交了任务输入";
        long activitySequence = insertActivity(mapper, activityId, task.lineage().rootThreadId(), threadId,
                threadId, turn.turnId(), TaskModels.ActivityKind.DISPATCHED,
                JsonObjects.builder().putText("text", activitySummary).build(), turn.requestedAt());
        requireChanged(mapper.tasks().compareAndSetUserAdmissionProjection(
                        new TaskRecords.UserAdmissionProjectionCas(threadId, admission.expectedTaskRevision(),
                                activitySequence, activitySummary, instant(turn.requestedAt()), instant(turn.requestedAt()))),
                "Task projection changed during USER admission");
        mapper.tasks().recomputeAncestorCounts(threadId, instant(turn.requestedAt()));
    }

    /**
     * 验证 Thread 初始化快照；PLACEHOLDER 仅允许同一 admission USER 派生出的确定性标题变化。
     */
    private void verifyAdmittedChildThread(PersistenceMappers mapper, TaskModels.ChildAdmission child,
                                           AdmissionFacts facts, TurnAdmission turn, UserContent admittedUser) {
        if (facts.newThread().isEmpty()) {
            throw relation("new Child admission has no Thread initialization");
        }
        AdmissionFacts.ThreadInitialization expected = facts.newThread().orElseThrow();
        PersistenceRecords.ThreadRow actual = mapper.history().selectThread(child.childThread().threadId());
        ThreadPreferences preferences = ConversationAcl.toConversation(child.childThread().preferences());
        String expectedTitle = expected.title();
        if (preferences.titleSource() == ThreadPreferences.TitleSource.PLACEHOLDER) {
            if (!admittedUser.attachmentIds().equals(turn.attachmentIds())) {
                throw invalidState("Task USER attachment facts differ from the Core projection");
            }
            List<String> attachmentNames = new ArrayList<>(turn.attachmentIds().size());
            for (String attachmentId : turn.attachmentIds()) {
                AttachmentRecords.AttachmentRow attachment = mapper.attachments().selectAttachment(attachmentId);
                if (attachment == null) {
                    throw invalidState("Task USER title attachment is missing");
                }
                attachmentNames.add(attachment.displayName());
            }
            String provisionalTitle = ThreadTitlePolicy.provisionalTitle(admittedUser.text(), attachmentNames);
            if (!provisionalTitle.isBlank()) {
                expectedTitle = provisionalTitle;
            }
        }
        if (actual == null || actual.revision() != 1
            || !actual.workspaceId().equals(expected.workspaceId())
            || !actual.title().equals(expectedTitle)
            || !actual.providerId().equals(preferences.providerId())
            || !actual.modelId().equals(preferences.modelId())
            || !Objects.equals(actual.reasoningLevel(), preferences.reasoningLevel())
            || !actual.accessMode().equals(preferences.accessMode().name())
            || !actual.collaborationMode().equals(preferences.collaborationMode().name())
            || !actual.titleSource().equals(preferences.titleSource().name())) {
            throw relation("Child Thread differs from its Ja admission snapshot");
        }
    }

    /**
     * Mapper 在当前 Store 事务核验精确引用的 Core USER fact，Task 不执行技术 SQL 或重复造消息行。
     */
    private UserContent verifyAdmittedUserMessage(PersistenceMappers mapper, TurnAdmission turn) {
        PersistenceRecords.AdmissionUserRecordRow input = mapper.agent()
                .selectAdmissionUserMessage(turn.threadId(), turn.turnId(), APP_IDENTITY.userId());
        if (input == null || !input.messageId().equals(turn.messageId()) || !"USER".equals(input.role())) {
            throw invalidState("Task admission user message is not the unique Core projection");
        }
        return records.decodeUser(new AgentStore.StoredRecord(input.recordId(), input.recordType(),
                input.payloadSchemaVersion(), input.recordSequence(), jsonValues.decodeObject(input.payloadJson()),
                Instant.ofEpochMilli(input.occurredAtMs()))).content();
    }

    /**
     * 首轮附件沿冻结的 Seed 白名单校验；Seed 本身由 Core admission 原子写入，不复制到 Ja 历史。
     */
    private void validateFirstTurnContextSeed(PersistenceMappers mapper, TurnAdmission turn) {
        if (mapper.agent().hasOtherTurns(turn.threadId(), turn.turnId())) {
            return;
        }
        TaskRecords.ContextSeedRow row = mapper.tasks().selectContextSeed(turn.threadId());
        if (row == null) {
            throw invalidState("Task context seed is missing during its first Turn");
        }
        TaskContextAttachmentValidation.validate(mapper, seed(row), turn.threadId());
    }

    /**
     * idle admission 与首 Turn admission 共享关系校验和元数据写入，但刻意没有任何 Turn 写入。
     */
    private TaskModels.Summary admitIdleChild(PersistenceMappers mapper, TaskModels.ChildAdmission child) {
        ChildAdmissionContext context = prepareChild(mapper, child);
        String rootThreadId = context.rootThreadId();
        causalRootTurn(mapper, child.parentThreadId(), child.originTurnId(), false);
        insertChildMetadata(mapper, context, child, false);
        long activitySequence = insertActivity(mapper, child.activityId(), rootThreadId,
                child.childThread().threadId(), child.parentThreadId(), child.originTurnId(),
                TaskModels.ActivityKind.CREATED, child.activitySummary(), child.contextSeed().createdAt());
        requireChanged(mapper.tasks().insertProjection(new TaskRecords.ProjectionInsert(
                child.childThread().threadId(), rootThreadId, TaskModels.State.IDLE.name(),
                activitySequence, safeSummary(child.activitySummary()), null,
                instant(child.contextSeed().createdAt()))), "task projection insert lost");
        SideChatPersistence.insertOpen(mapper, child.childThread().threadId());
        // 独立侧聊只拥有自己的工作面，不让创建操作改变来源任务的投影版本。
        if (child.lifecycle() == TaskModels.Lifecycle.ATTACHED) {
            mapper.tasks().incrementAncestorDescendants(child.parentThreadId());
        }
        return requireTask(mapper, child.childThread().threadId());
    }

    /**
     * typed seed 先 canonical encode 再统一计算 fingerprint，调用方不能注入自定义签名。
     */
    private String insertSeed(PersistenceMappers mapper, TaskModels.ContextSeedDraft seed) {
        String brief = seed.taskBrief() == null ? null : json.writeContent(seed.taskBrief());
        String effective = seed.inheritanceMode() == TaskModels.InheritanceMode.EFFECTIVE_CONTEXT
                ? taskSeedCodec.encode(Optional.ofNullable(seed.initialContextSeed())) : null;
        String references = json.write(seed.references());
        String permission = json.write(seed.permissionCeiling());
        String fingerprint = json.fingerprint(seed.parentThreadId(), seed.parentTurnId(), seed.parentRevision(),
                seed.inheritanceMode().name(), brief, effective, references, permission);
        requireChanged(mapper.tasks().insertContextSeed(new TaskRecords.ContextSeedInsert(seed.contextSeedId(),
                        seed.parentThreadId(), seed.parentTurnId(), seed.parentRevision(), seed.inheritanceMode().name(),
                        brief, effective, references, permission, fingerprint, instant(seed.createdAt()))),
                "task context seed insert lost");
        return fingerprint;
    }

    /**
     * 幂等键复用必须逐字段相等，矛盾重试不能静默返回首个结果。
     */
    private TaskModels.MailboxMessage identicalMailbox(TaskRecords.MailboxRow existing,
                                                       TaskModels.MailboxEnvelope requested) {
        TaskModels.MailboxMessage persisted = mailbox(existing);
        if (!persisted.targetThreadId().equals(requested.targetThreadId())
            || !Objects.equals(persisted.causalTurnId(), requested.causalTurnId())
            || persisted.kind() != requested.kind()
            || !persisted.content().equals(requested.content())) {
            throw concurrent("idempotency key is already bound to different task content");
        }
        return persisted;
    }

    /**
     * claim 状态机的四个动作共享同一事务回读，但不把普通 FIFO miss 当异常。
     */
    private Optional<WorkspaceWriteClaimPort.WriteClaim> mutateClaim(
            String claimId, long fencingToken, Instant occurredAt, ClaimMutation mutation) {
        return transactions.required(mapper -> {
            TaskRecords.WriteClaimRow before = mapper.tasks().selectWriteClaim(claimId);
            if (before == null || before.fencingToken() != fencingToken) {
                return Optional.empty();
            }
            TaskRecords.WriteClaimCas command = new TaskRecords.WriteClaimCas(
                    claimId, fencingToken, instant(occurredAt));
            int changed = switch (mutation) {
                case ACQUIRE -> mapper.tasks().acquireWriteClaim(command);
                case HEARTBEAT -> mapper.tasks().heartbeatWriteClaim(command);
                case RELEASE -> mapper.tasks().releaseWriteClaim(command);
                case ABANDON -> mapper.tasks().abandonWriteClaim(command);
            };
            TaskRecords.WriteClaimRow after = mapper.tasks().selectWriteClaim(claimId);
            if (changed == 1) {
                return Optional.of(writeClaim(after));
            }
            boolean idempotentTerminal = mutation == ClaimMutation.RELEASE && "RELEASED".equals(before.state())
                                         || mutation == ClaimMutation.ABANDON && "ABANDONED".equals(before.state());
            return idempotentTerminal ? Optional.of(writeClaim(after)) : Optional.empty();
        });
    }

    /**
     * Activity JSON 只在 adapter 编码，并返回 SQLite 单调 sequence。
     */
    private long insertActivity(PersistenceMappers mapper, String activityId, String rootThreadId,
                                String taskThreadId, String actorThreadId, String causalTurnId,
                                TaskModels.ActivityKind kind, JsonObject summary, Instant occurredAt) {
        Long sequence = mapper.tasks().insertActivity(new TaskRecords.ActivityInsert(activityId,
                rootThreadId, taskThreadId, actorThreadId, causalTurnId, kind.name(), json.write(summary),
                instant(occurredAt)));
        if (sequence == null) {
            throw concurrent("task activity insert lost");
        }
        return sequence;
    }

    /**
     * Mailbox row insert 参数固定初态与根身份。
     */
    private TaskRecords.MailboxInsert mailboxInsert(TaskModels.MailboxEnvelope envelope,
                                                    String rootThreadId, String contentJson,
                                                    String state, String boundTurnId) {
        return new TaskRecords.MailboxInsert(envelope.messageId(), rootThreadId,
                envelope.senderThreadId(), envelope.targetThreadId(), envelope.causalTurnId(),
                envelope.kind().name(), contentJson, envelope.operationKey(), state, boundTurnId,
                instant(envelope.createdAt()), instant(envelope.createdAt()));
    }

    /**
     * Mapper summary 行在 SqlSession 内转换为领域值。
     */
    private TaskModels.Summary summary(TaskRecords.TaskSummaryRow row) {
        TaskModels.Lineage lineage = new TaskModels.Lineage(row.taskThreadId(), row.parentThreadId(),
                row.rootThreadId(), row.originTurnId(), row.taskName(), row.depth(),
                TaskModels.Kind.valueOf(row.taskKind()), TaskModels.Lifecycle.valueOf(row.lifecycle()),
                row.contextSeedId(), Instant.parse(row.createdAt()));
        TaskModels.Projection projection = new TaskModels.Projection(row.taskThreadId(), row.rootThreadId(),
                row.revision(), TaskModels.State.valueOf(row.state()), row.latestActivitySequence(),
                row.lastSeenActivitySequence(), row.unreadCount(), row.descendantCount(),
                row.runningDescendantCount(), row.needsAttentionCount(), row.latestSafeSummary(),
                optionalInstant(row.startedAt()), optionalInstant(row.completedAt()), Instant.parse(row.updatedAt()));
        return new TaskModels.Summary(lineage, projection);
    }

    /**
     * 两种 Child admission 共用同一关系快照；先冻结父 revision、树深度和根身份，再分别写入 Turn 或 idle 投影。
     */
    private ChildAdmissionContext prepareChild(PersistenceMappers mapper, TaskModels.ChildAdmission child) {
        SideChatPersistence.requireTaskAdmissionOpen(mapper, child.parentThreadId());
        TaskRecords.ParentRow parent = requireParent(mapper, child.parentThreadId());
        requireParentRevision(parent, child.expectedParentRevision());
        if (!parent.workspaceId().equals(child.childThread().workspaceId())) {
            throw relation("child and parent must share one Workspace");
        }
        int depth = parent.depth() == null ? 1 : parent.depth() + 1;
        if (depth > 4) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.DEPTH_LIMIT,
                "task depth exceeds limit");
        }
        String rootThreadId = parent.rootThreadId() == null ? parent.threadId() : parent.rootThreadId();
        if (mapper.tasks().countRootDescendants(rootThreadId) >= MAX_TREE_SIZE) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.TREE_LIMIT,
                    "task tree exceeds limit");
        }
        return new ChildAdmissionContext(parent, depth, rootThreadId);
    }

    /**
     * Child 的 typed seed、父策略、Thread 和 lineage 必须作为同一 admission 的 Ja 投影提交。
     */
    private void insertChildMetadata(PersistenceMappers mapper, ChildAdmissionContext context,
                                     TaskModels.ChildAdmission child, boolean threadAlreadyAdmitted) {
        insertChildMetadata(mapper, context, child, threadAlreadyAdmitted, null);
    }

    /**
     * 可选的冻结策略仅用于真实首 Turn admission；静态侧聊创建沿用事务内当前父策略。
     */
    private void insertChildMetadata(PersistenceMappers mapper, ChildAdmissionContext context,
                                     TaskModels.ChildAdmission child, boolean threadAlreadyAdmitted,
                                     SubagentPolicy expectedParentPolicy) {
        requireSeedMode(child);
        PersistenceRecords.SubagentPolicyRow parentPolicy = mapper.subagentPolicies().select(
                context.parent().threadId());
        if (parentPolicy == null) {
            throw invalidState("parent subagent policy is unavailable");
        }
        if (expectedParentPolicy != null && !matches(expectedParentPolicy, parentPolicy)) {
            throw invalidState("parent subagent policy changed before Child admission");
        }
        if (!threadAlreadyAdmitted) {
            insertThread(mapper, child.childThread());
        }
        requireChanged(mapper.subagentPolicies().insert(new PersistenceRecords.SubagentPolicyInsert(
                        child.childThread().threadId(), parentPolicy.enabled(), parentPolicy.providerId(),
                        parentPolicy.modelId(), parentPolicy.reasoningLevel(), instant(child.contextSeed().createdAt()))),
                "child subagent policy insert lost");
        insertSeed(mapper, child.contextSeed());
        requireChanged(mapper.tasks().insertLineage(new TaskRecords.LineageInsert(
                        child.childThread().threadId(), child.parentThreadId(), context.rootThreadId(), child.originTurnId(),
                        child.taskName(), context.depth(), child.kind().name(), child.lifecycle().name(),
                        child.contextSeed().contextSeedId(), instant(child.contextSeed().createdAt()))),
                "task lineage insert lost");
    }

    /**
     * 在准入事务内逐字段比较冻结策略，防止运行准备与实际落库采用不同的委派权限或模型。
     */
    private static boolean matches(SubagentPolicy expected, PersistenceRecords.SubagentPolicyRow actual) {
        return expected.enabled() == actual.enabled()
                && Objects.equals(expected.providerId(), actual.providerId())
                && Objects.equals(expected.modelId(), actual.modelId())
                && Objects.equals(expected.reasoningLevel(), actual.reasoningLevel());
    }

    /**
     * Seed JSON 由 Core typed codec 还原并复核完整 fingerprint，不接受双格式或局部列漂移。
     */
    private TaskModels.ContextSeed seed(TaskRecords.ContextSeedRow row) {
        String fingerprint = json.fingerprint(row.parentThreadId(), row.parentTurnId(), row.parentRevision(),
                row.inheritanceMode(), row.taskBriefJson(), row.effectiveContextJson(), row.referencesJson(),
                row.permissionCeilingJson());
        if (!fingerprint.equals(row.fingerprint())) {
            throw invalidState("Task context seed fingerprint mismatch");
        }
        TaskModels.InheritanceMode mode = TaskModels.InheritanceMode.valueOf(row.inheritanceMode());
        InitialContextSeed initialSeed = null;
        if (mode == TaskModels.InheritanceMode.EFFECTIVE_CONTEXT) {
            if (row.effectiveContextJson() == null) {
                throw invalidState("effective context snapshot is missing");
            }
            initialSeed = taskSeedCodec.decode(row.effectiveContextJson()).orElse(null);
        } else if (row.effectiveContextJson() != null) {
            throw invalidState("brief-only context contains an inherited Core seed");
        }
        return new TaskModels.ContextSeed(row.contextSeedId(), row.parentThreadId(), row.parentTurnId(),
                row.parentRevision(), mode,
                row.taskBriefJson() == null ? null : json.readContent(row.taskBriefJson()),
                initialSeed,
                json.readArray(row.referencesJson()),
                json.readObject(row.permissionCeilingJson()), row.fingerprint(), Instant.parse(row.createdAt()));
    }

    /**
     * Activity 行在 adapter 内解析安全 summary。
     */
    private TaskModels.Activity activity(TaskRecords.ActivityRow row) {
        return new TaskModels.Activity(row.activitySequence(), row.activityId(), row.rootThreadId(),
                row.taskThreadId(), row.actorThreadId(), row.causalTurnId(),
                TaskModels.ActivityKind.valueOf(row.kind()), json.readObject(row.summaryJson()),
                Instant.parse(row.createdAt()));
    }

    /**
     * LEFT JOIN 行先做完整关系校验再构造领域值，任何缺失或错配均失败关闭。
     */
    private TaskModels.ActivityProjection rootActivityProjection(
            String requestedRootThreadId, TaskRecords.RootActivityProjectionRow row) {
        if (!requestedRootThreadId.equals(row.parentThreadId())
            || !"SUBAGENT".equals(row.taskKind()) || !"ATTACHED".equals(row.lifecycle())
            || !row.activityTaskThreadId().equals(row.lineageTaskThreadId())
            || !row.activityRootThreadId().equals(row.lineageRootThreadId())
            || !row.activityTaskThreadId().equals(row.projectionTaskThreadId())
            || !row.activityRootThreadId().equals(row.projectionRootThreadId())
            || !row.activityTaskThreadId().equals(row.persistedThreadId())
            || row.threadDeletedAt() != null) {
            throw invalidState("task activity projection relationship is invalid");
        }
        TaskModels.Activity activity = activity(new TaskRecords.ActivityRow(
                row.activitySequence(), row.activityId(), row.activityRootThreadId(),
                row.activityTaskThreadId(), row.actorThreadId(), row.causalTurnId(),
                row.activityKind(), row.activitySummaryJson(), row.activityCreatedAt()));
        TaskModels.Summary task = summary(new TaskRecords.TaskSummaryRow(
                row.lineageTaskThreadId(), row.parentThreadId(), row.lineageRootThreadId(),
                row.originTurnId(), row.taskName(), row.depth(), row.taskKind(), row.lifecycle(),
                row.contextSeedId(), row.lineageCreatedAt(), row.projectionRevision(),
                row.projectionState(), row.latestActivitySequence(), row.lastSeenActivitySequence(),
                row.unreadCount(), row.descendantCount(), row.runningDescendantCount(),
                row.needsAttentionCount(), row.latestSafeSummary(), row.startedAt(), row.completedAt(),
                row.projectionUpdatedAt()));
        return new TaskModels.ActivityProjection(activity, task);
    }

    /**
     * Mailbox 行复用 UserContent codec，确保 Tool blocks 不能混入任务通信。
     */
    private TaskModels.MailboxMessage mailbox(TaskRecords.MailboxRow row) {
        return new TaskModels.MailboxMessage(row.mailboxSequence(), row.messageId(), row.rootThreadId(),
                row.senderThreadId(), row.senderTitle(), row.targetThreadId(), row.causalTurnId(),
                TaskModels.MailboxKind.valueOf(row.kind()), json.readContent(row.contentJson()),
                row.operationKey(), TaskModels.MailboxState.valueOf(row.state()), row.boundTurnId(),
                Instant.parse(row.createdAt()), Instant.parse(row.updatedAt()), optionalInstant(row.consumedAt()));
    }

    /**
     * Task summary 缺失统一映射 TASK_NOT_FOUND。
     */
    private TaskModels.Summary requireTask(PersistenceMappers mapper, String taskThreadId) {
        TaskRecords.TaskSummaryRow row = mapper.tasks().selectTaskSummary(taskThreadId);
        if (row == null) {
            throw notFound("Task");
        }
        return summary(row);
    }

    /**
     * close 后所有事务 fail-fast，避免 datasource shutdown 期间出现迟到写。
     */
    private void ensureOpen() {
        if (closed.get()) {
            throw new StorageException(StorageException.Code.CLOSED, "task store is closed");
        }
    }

    /**
     * 写声明的 CAS 动作闭集让四条 SQL 路径共享同一回读与幂等终态判断。
     */
    private enum ClaimMutation {
        /**
         * 尝试让 FIFO 队首取得 fencing 所有权。
         */
        ACQUIRE,
        /**
         * 仅由当前 fencing owner 延长存活证据。
         */
        HEARTBEAT,
        /**
         * 正常完成写操作后提交不可逆释放。
         */
        RELEASE,
        /**
         * 超时、取消或恢复时永久废弃旧声明。
         */
        ABANDON
    }

    /**
     * relation 预检结果只在当前事务内使用，不能携带可变 mapper 或跨事务状态。
     *
     * @param parent 父任务
     * @param depth 深度
     * @param rootThreadId 任务树根对话标识
     */
    private record ChildAdmissionContext(TaskRecords.ParentRow parent,
                                         int depth,
                                         String rootThreadId) {
    }

    /**
     * 路由结果同时保留根身份与实际 Child 端点，防止跨任务树投递 Mailbox。
     *
     * @param rootThreadId 任务树根对话标识
     * @param taskThreadId 任务对话标识
     */
    private record RoutedTask(String rootThreadId,
                              String taskThreadId) {
    }

    /**
     * 将读取阶段核验的 Thread、公开答复与其原始上下文绑定传递，避免分叉准备阶段混用不同快照。
     *
     * @param parent 经 revision 校验的来源 Thread 持久行
     * @param publicItem 已验证可分叉的公开答复投影
     * @param sourceTurnId 答复所属的来源 Turn 标识
     * @param contextSourceThreadId 原始模型上下文的直接来源 Thread
     * @param contextSourceItemId 原始模型上下文所截止的来源答复
     * @param contextThroughOrdinal 继承上下文已经冻结的末尾顺序号
     * @param contextMessages 与答复绑定的不可变中性消息前缀
     * @param inherited 该答复是否来自本 Thread 的冻结继承历史
     * @author kongweiguang
     */
    private record ForkSource(TaskRecords.ParentRow parent,
                              PersistenceRecords.SnapshotItemRow publicItem,
                              String sourceTurnId,
                              String contextSourceThreadId,
                              String contextSourceItemId,
                              Long contextThroughOrdinal,
                              List<ContextMessage> contextMessages,
                              boolean inherited) {
        /**
         * 复制消息列表以固定读取阶段的上下文，避免后续分叉准备意外观察到可变集合。
         *
         * @author kongweiguang
         */
        private ForkSource {
            contextMessages = List.copyOf(contextMessages);
        }
    }
}


