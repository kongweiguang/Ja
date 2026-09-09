// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.TaskMailboxPort;
import io.github.kongweiguang.ja.conversation.port.out.WorkspaceWriteClaimPort;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.foundation.json.JacksonJsonValues;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.AttachmentRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TurnExecutionStateCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.ThreadPersistenceMapping;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import io.github.kongweiguang.ja.task.domain.TaskModels;
import io.github.kongweiguang.ja.task.port.out.TaskRepository;
import io.github.kongweiguang.ja.task.port.out.TaskRepositoryException;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** MyBatis/SQLite Task adapter；全部可见回执只在事务提交之后返回给调度层。 */
public final class MybatisTaskRepository implements TaskRepository, TaskMailboxPort, WorkspaceWriteClaimPort {
    private static final Logger LOGGER = LoggerFactory.getLogger(MybatisTaskRepository.class);
    private static final int MAX_TREE_SIZE = 64;
    private static final int MAX_EFFECTIVE_MESSAGES = 512;
    private static final long MAX_EFFECTIVE_BYTES = 4L * 1024 * 1024;
    private static final int MAX_MAILBOX_PENDING = 256;
    private static final long MAX_MAILBOX_BYTES = 512L * 1024;
    private static final long MAX_TURN_ATTACHMENT_BYTES = 250L * 1024 * 1024;

    private final MybatisUnitOfWork transactions;
    private final ObjectMapper objectMapper;
    private final TaskJsonCodec json;
    private final PersistenceCodec messages;
    private final TurnExecutionStateCodec executions;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 生产事务由 MyBatis-Solon owner 管理，Repository 不自行 commit datasource。 */
    public MybatisTaskRepository(SqlSessionFactory sessions, ObjectMapper objectMapper) {
        this.transactions = new MybatisUnitOfWork(sessions);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.json = new TaskJsonCodec(objectMapper);
        this.messages = new PersistenceCodec(objectMapper);
        this.executions = new TurnExecutionStateCodec(objectMapper);
    }

    /** 聚焦测试显式注入真实 SQLite transaction owner，不向生产 composition 暴露 fallback。 */
    public MybatisTaskRepository(SqlSessionFactory sessions, ObjectMapper objectMapper,
                                 MybatisUnitOfWork.SessionOwner owner) {
        this.transactions = new MybatisUnitOfWork(sessions, owner);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.json = new TaskJsonCodec(objectMapper);
        this.messages = new PersistenceCodec(objectMapper);
        this.executions = new TurnExecutionStateCodec(objectMapper);
    }

    /** revision 校验、checkpoint 和 retained messages 在同一读事务中冻结，避免 TOCTOU 快照。 */
    @Override
    public TaskModels.EffectiveContextSnapshot freezeEffectiveContext(String parentThreadId,
                                                                      long expectedParentRevision) {
        ensureOpen();
        return transactions.required(mapper -> {
            TaskRecords.ParentRow parent = requireParent(mapper, parentThreadId);
            requireParentRevision(parent, expectedParentRevision);
            PersistenceRecords.CheckpointRow checkpoint = mapper.checkpoint().selectCheckpoint(parentThreadId);
            if (checkpoint != null && checkpoint.sourceRevision() > expectedParentRevision) {
                throw invalidState("checkpoint revision exceeds frozen parent revision");
            }
            long retainedFrom = checkpoint == null ? 1 : checkpoint.retainedFromOrdinal();
            List<PersistenceRecords.MessageRow> rows = mapper.tasks().selectEffectiveMessages(
                    parentThreadId, retainedFrom, MAX_EFFECTIVE_MESSAGES + 1);
            if (rows.size() > MAX_EFFECTIVE_MESSAGES) {
                throw invalidState("effective context requires compaction before forking");
            }
            ObjectNode context = objectMapper.createObjectNode().put("schemaVersion", 1)
                    .put("parentThreadId", parentThreadId).put("parentRevision", expectedParentRevision);
            context.set("checkpoint", checkpointNode(checkpoint));
            ArrayNode effectiveMessages = context.putArray("messages");
            ArrayNode references = objectMapper.createArrayNode();
            Set<String> referenceKeys = new HashSet<>();
            long totalBytes = 0;
            for (PersistenceRecords.MessageRow row : rows) {
                totalBytes = Math.addExact(totalBytes, row.blocksJson().getBytes(StandardCharsets.UTF_8).length);
                if (totalBytes > MAX_EFFECTIVE_BYTES) {
                    throw invalidState("effective context requires compaction before forking");
                }
                JsonNode blocks = parseArray(row.blocksJson(), "effective message blocks");
                if (checkpoint != null && checkpoint.retainedSplitJson() != null) {
                    blocks = applyRetainedSplit(row, blocks, checkpoint.retainedSplitJson());
                }
                ObjectNode message = effectiveMessages.addObject().put("sourceMessageId", row.messageId())
                        .put("sourceTurnId", row.turnId()).put("sourceOrdinal", row.ordinal())
                        .put("role", row.role());
                message.set("blocks", blocks);
                collectReferences(blocks, references, referenceKeys);
            }
            JsonObject permissionCeiling = JsonObjects.builder()
                    .putText("version", "task_access_v1")
                    .putText("accessMode", parent.accessMode().toLowerCase(Locale.ROOT))
                    .build();
            return new TaskModels.EffectiveContextSnapshot(parentThreadId, expectedParentRevision,
                    requireObject(context), requireArray(references), permissionCeiling);
        });
    }

    /** Child Thread 的全部关系、上下文、首 Turn 和投影在单一 write transaction 内提交。 */
    @Override
    public ConversationRepository.AdmissionReceipt admitChild(TaskModels.ChildAdmission child,
                                                               ConversationRepository.TurnAdmission turn) {
        ensureOpen();
        Objects.requireNonNull(child, "child");
        Objects.requireNonNull(turn, "turn");
        if (!child.childThread().threadId().equals(turn.threadId()) || turn.expectedThreadRevision() != 0) {
            throw relation("child Turn does not match the fresh Thread");
        }
        return transactions.required(mapper -> admitChild(mapper, child, turn));
    }

    /** 列表摘要不读取 seed JSON、活动正文或 Mailbox。 */
    @Override
    public Optional<TaskModels.Summary> findTask(String taskThreadId) {
        ensureOpen();
        return transactions.required(mapper -> Optional.ofNullable(mapper.tasks().selectTaskSummary(taskThreadId))
                .map(this::summary));
    }

    /** 根树上限为 64，仍复核结果数量以发现损坏或持久约束漂移。 */
    @Override
    public List<TaskModels.Summary> listTree(String rootThreadId) {
        ensureOpen();
        return transactions.required(mapper -> {
            List<TaskModels.Summary> result = mapper.tasks().selectTaskTree(rootThreadId).stream()
                    .map(this::summary).toList();
            if (result.size() > MAX_TREE_SIZE) throw invalidState("task tree exceeds storage limit");
            return result;
        });
    }

    /** 根 Timeline 使用单条 SQL 截取最近活动并校验联结身份，避免读取 Child transcript 或全树正文。 */
    @Override
    public List<TaskModels.ActivityProjection> listRootActivities(String rootThreadId, int limit) {
        ensureOpen();
        if (limit < 1 || limit > 128) throw new IllegalArgumentException("invalid root activity limit");
        return transactions.required(mapper -> mapper.tasks().selectRootActivityProjections(rootThreadId, limit)
                .stream().map(row -> rootActivityProjection(rootThreadId, row)).toList());
    }

    /** 详情的两个增量游标独立推进，避免全局 sequence 的空洞造成错误截断。 */
    @Override
    public Optional<TaskModels.Detail> readTask(String taskThreadId, long afterActivitySequence,
                                                long afterMailboxSequence, int limit) {
        ensureOpen();
        if (afterActivitySequence < 0 || afterMailboxSequence < 0 || limit < 1 || limit > 200) {
            throw new IllegalArgumentException("invalid task detail page");
        }
        return transactions.required(mapper -> {
            TaskRecords.TaskSummaryRow row = mapper.tasks().selectTaskSummary(taskThreadId);
            if (row == null) return Optional.empty();
            TaskRecords.ContextSeedRow seed = mapper.tasks().selectContextSeed(taskThreadId);
            if (seed == null) throw invalidState("task context seed is unavailable");
            List<TaskModels.Activity> activities = mapper.tasks().selectActivities(
                    taskThreadId, afterActivitySequence, limit).stream().map(this::activity).toList();
            List<TaskModels.MailboxMessage> mailbox = mapper.tasks().selectMailbox(
                    taskThreadId, afterMailboxSequence, limit).stream().map(this::mailbox).toList();
            return Optional.of(new TaskModels.Detail(summary(row), seed(seed), activities, mailbox));
        });
    }

    /** QueueOnly 写入先做根树与容量校验，幂等重试不重复追加 Activity。 */
    @Override
    public TaskModels.MessageEnqueueReceipt enqueueMessage(TaskModels.MailboxEnvelope mailbox,
                                                            String activityId, JsonObject summary) {
        ensureOpen();
        Objects.requireNonNull(mailbox, "mailbox");
        if (mailbox.kind() != TaskModels.MailboxKind.MESSAGE) {
            throw new IllegalArgumentException("QueueOnly requires MESSAGE kind");
        }
        return transactions.required(mapper -> {
            RoutedTask route = route(mapper, mailbox.senderThreadId(), mailbox.targetThreadId());
            TaskRecords.MailboxRow existing = mapper.tasks().selectMailboxByIdempotency(
                    mailbox.senderThreadId(), mailbox.idempotencyKey());
            if (existing != null) {
                return messageReceipt(mapper, route, identicalMailbox(existing, mailbox), false);
            }
            String contentJson = json.writeContent(mailbox.content());
            requireMailboxCapacity(mapper, mailbox.targetThreadId(), contentJson);
            Long sequence = mapper.tasks().insertMailbox(mailboxInsert(mailbox, route.rootThreadId(),
                    contentJson, "PENDING", null));
            if (sequence == null) {
                return messageReceipt(mapper, route,
                        identicalMailbox(requireExistingMailbox(mapper, mailbox), mailbox), false);
            }
            TaskModels.Summary current = requireTask(mapper, route.taskThreadId());
            long activitySequence = insertActivity(mapper, activityId, route.rootThreadId(),
                    route.taskThreadId(), mailbox.senderThreadId(), mailbox.causalTurnId(),
                    TaskModels.ActivityKind.MESSAGE_SENT, summary, mailbox.createdAt());
            requireChanged(mapper.tasks().compareAndSetProjectionActivity(new TaskRecords.ProjectionActivityCas(
                    route.taskThreadId(), current.projection().revision(), activitySequence,
                    safeSummary(summary), instant(mailbox.createdAt()))), "task projection changed concurrently");
            return messageReceipt(mapper, route, mailbox(mapper.tasks().selectMailboxByIdempotency(
                    mailbox.senderThreadId(), mailbox.idempotencyKey())), true);
        });
    }

    /** 回执始终使用路由事务内解析出的 Child 投影 owner，root 端点不需要伪造 Task lineage。 */
    private TaskModels.MessageEnqueueReceipt messageReceipt(PersistenceMappers mapper, RoutedTask route,
                                                            TaskModels.MailboxMessage mailbox,
                                                            boolean inserted) {
        return new TaskModels.MessageEnqueueReceipt(mailbox, requireTask(mapper, route.taskThreadId()), inserted);
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
            RoutedTask route = route(mapper, envelope.senderThreadId(), envelope.targetThreadId());
            if (!route.taskThreadId().equals(envelope.targetThreadId())) {
                throw relation("follow-up target must be a Child Task");
            }
            TaskRecords.MailboxRow existing = mapper.tasks().selectMailboxByIdempotency(
                    envelope.senderThreadId(), envelope.idempotencyKey());
            return existing == null ? Optional.empty() : Optional.of(existingFollowUp(mapper, envelope, existing));
        });
    }

    /** Follow-up 重试复用已绑定 Turn；首次写入才创建 Turn、消息、Activity 和 projection revision。 */
    @Override
    public TaskModels.FollowUpAdmissionReceipt admitFollowUp(TaskModels.FollowUpAdmission admission) {
        ensureOpen();
        Objects.requireNonNull(admission, "admission");
        return transactions.required(mapper -> {
            TaskModels.MailboxEnvelope envelope = admission.mailbox();
            RoutedTask route = route(mapper, envelope.senderThreadId(), envelope.targetThreadId());
            if (!route.taskThreadId().equals(envelope.targetThreadId())) {
                throw relation("follow-up target must be a Child Task");
            }
            TaskRecords.MailboxRow existing = mapper.tasks().selectMailboxByIdempotency(
                    envelope.senderThreadId(), envelope.idempotencyKey());
            if (existing != null) {
                return existingFollowUp(mapper, envelope, existing);
            }
            TaskModels.Summary task = requireTask(mapper, envelope.targetThreadId());
            if (task.projection().revision() != admission.expectedTaskRevision()) {
                throw concurrent("task projection revision is stale");
            }
            String contentJson = json.writeContent(envelope.content());
            requireMailboxCapacity(mapper, envelope.targetThreadId(), contentJson);
            Long mailboxSequence = mapper.tasks().insertMailbox(mailboxInsert(envelope, route.rootThreadId(),
                    contentJson, "PENDING", null));
            if (mailboxSequence == null) throw concurrent("follow-up idempotency changed concurrently");
            ConversationRepository.AdmissionReceipt receipt = admitExistingTaskTurn(mapper, admission.turn(),
                    envelope.causalTurnId());
            requireChanged(mapper.tasks().bindMailbox(new TaskRecords.MailboxBind(envelope.messageId(),
                    envelope.targetThreadId(), receipt.turnId(), instant(envelope.createdAt()))),
                    "follow-up mailbox binding lost");
            long activitySequence = insertActivity(mapper, admission.activityId(), route.rootThreadId(),
                    route.taskThreadId(), envelope.senderThreadId(), envelope.causalTurnId(),
                    TaskModels.ActivityKind.FOLLOW_UP_QUEUED, admission.activitySummary(), envelope.createdAt());
            requireChanged(mapper.tasks().compareAndSetFollowUpProjection(new TaskRecords.FollowUpProjectionCas(
                    route.taskThreadId(), admission.expectedTaskRevision(), activitySequence,
                    safeSummary(admission.activitySummary()), instant(envelope.createdAt()))),
                    "task projection changed concurrently");
            mapper.tasks().recomputeAncestorCounts(route.taskThreadId(), instant(envelope.createdAt()));
            TaskModels.MailboxMessage persisted = mailbox(mapper.tasks().selectMailboxByIdempotency(
                    envelope.senderThreadId(), envelope.idempotencyKey()));
            return new TaskModels.FollowUpAdmissionReceipt(receipt, persisted);
        });
    }

    /**
     * 已有幂等事实只能回放其真实绑定身份；PENDING 或内容漂移都不能伪造成成功接纳。
     */
    private TaskModels.FollowUpAdmissionReceipt existingFollowUp(
            PersistenceMappers mapper, TaskModels.MailboxEnvelope envelope,
            TaskRecords.MailboxRow existing) {
        TaskModels.MailboxMessage persisted = identicalMailbox(existing, envelope);
        if (persisted.state() != TaskModels.MailboxState.BOUND || persisted.boundTurnId() == null) {
            throw invalidState("follow-up idempotency fact is not bound");
        }
        PersistenceRecords.ThreadRow thread = requireThread(mapper, envelope.targetThreadId());
        ConversationRepository.AdmissionReceipt receipt = new ConversationRepository.AdmissionReceipt(
                thread.threadId(), persisted.boundTurnId(), thread.revision(), 0, null);
        return new TaskModels.FollowUpAdmissionReceipt(receipt, persisted);
    }

    /**
     * claim 先重放同 Turn 已绑定批次，再从 PENDING 补足剩余额度；目标存在其它 owner 时失败关闭，
     * 避免两个运行 Turn 同时把同一目标的消息注入各自模型上下文。
     */
    @Override
    public TaskMailboxPort.ClaimBatch claimPendingMessages(String targetThreadId, String turnId,
                                                            int limit, Instant occurredAt) {
        ensureOpen();
        Objects.requireNonNull(targetThreadId, "targetThreadId");
        Objects.requireNonNull(turnId, "turnId");
        if (limit < 1 || limit > MAX_MAILBOX_PENDING) throw new IllegalArgumentException("invalid mailbox limit");
        String now = instant(occurredAt);
        ClaimMeasurement measurement = transactions.required(mapper -> {
            PersistenceRecords.TurnRow turn = mapper.agent().selectTurnById(turnId);
            if (turn == null) throw notFound("mailbox target Turn");
            if (!targetThreadId.equals(turn.threadId())) throw relation("mailbox Turn does not belong to target");
            if (TurnState.valueOf(turn.state()).terminal()) throw invalidState("terminal Turn cannot claim mailbox");
            List<TaskRecords.MailboxRow> existing = mapper.tasks().selectBoundMailboxForTurn(turnId, limit + 1);
            if (existing.size() > limit) throw invalidState("existing mailbox claim exceeds requested limit");
            int remaining = limit - existing.size();
            if (remaining > 0) {
                mapper.tasks().claimPendingMailbox(new TaskRecords.MailboxClaim(
                        targetThreadId, turnId, remaining, now));
            }
            List<TaskRecords.MailboxRow> rows = mapper.tasks().selectBoundMailboxForTurn(turnId, limit);
            List<TaskMailboxPort.ClaimedMessage> claimed = rows.stream().map(this::claimedMailbox).toList();
            long throughSequence = claimed.isEmpty() ? 0 : claimed.getLast().sequence();
            long lagMillis = rows.isEmpty() ? 0 : Math.max(0L,
                    java.time.Duration.between(Instant.parse(rows.getFirst().createdAt()), occurredAt).toMillis());
            return new ClaimMeasurement(new TaskMailboxPort.ClaimBatch(claimed, throughSequence), lagMillis);
        });
        if (!measurement.batch().messages().isEmpty()) {
            LOGGER.info("event=task_mailbox_lag lag_ms={} message_count={}",
                    measurement.lagMillis(), measurement.batch().messages().size());
        }
        return measurement.batch();
    }

    /** 已读边界由 SQL 重算未读数，避免 activity sequence 属于全库时错误做减法。 */
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

    /** 低频活动和 projection 状态通过同一 CAS 原子提交。 */
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

    /** 递归 SQL 只沿 ATTACHED 边传播，遇到 INDEPENDENT 节点即停止。 */
    @Override
    public List<TaskModels.Summary> attachedDescendants(String parentThreadId) {
        ensureOpen();
        return transactions.required(mapper -> mapper.tasks().selectAttachedDescendants(parentThreadId)
                .stream().map(this::summary).toList());
    }

    /** 父取消行本身就是持久欠账；查询只暴露仍有非终态 ATTACHED 直接子 Turn 的身份。 */
    @Override
    public List<TaskModels.CancellationPropagation> pendingCancellationPropagations() {
        ensureOpen();
        return transactions.required(mapper -> mapper.tasks().selectPendingCancellationPropagations().stream()
                .map(row -> new TaskModels.CancellationPropagation(row.parentThreadId(), row.parentTurnId()))
                .toList());
    }

    /** 整树删除采用 Thread soft-delete + Task metadata 物理删除，保留不可变 Conversation 审计事实。 */
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
            if (!seedIds.isEmpty()) mapper.tasks().deleteContextSeeds(seedIds);
            mapper.tasks().recomputeDescendantCounts(task.lineage().rootThreadId(), instant(occurredAt));
            return count;
        });
    }

    /** WAITING 声明与 fencing token 在同一事务分配，SQLite writer lock 保证单调唯一。 */
    @Override
    public WorkspaceWriteClaimPort.WriteClaim enqueue(String claimId, String workspaceId, String threadId,
                                                       String turnId, long processGeneration, Instant requestedAt) {
        ensureOpen();
        if (processGeneration < 1) throw new IllegalArgumentException("invalid process generation");
        return transactions.required(mapper -> {
            long token = mapper.tasks().selectNextFencingToken(workspaceId);
            Long sequence = mapper.tasks().insertWriteClaim(new TaskRecords.WriteClaimInsert(claimId,
                    workspaceId, threadId, turnId, processGeneration, token, instant(requestedAt)));
            if (sequence == null) throw concurrent("write claim insert lost");
            return writeClaim(requireWriteClaim(mapper, claimId));
        });
    }

    /** 获取失败是正常 FIFO 竞争，返回 empty 而非把它伪装成存储异常。 */
    @Override
    public Optional<WorkspaceWriteClaimPort.WriteClaim> tryAcquire(
            String claimId, long fencingToken, Instant acquiredAt) {
        ensureOpen();
        return mutateClaim(claimId, fencingToken, acquiredAt, ClaimMutation.ACQUIRE);
    }

    /** heartbeat 竞争失败返回 empty，让 owner 立即停止写副作用。 */
    @Override
    public Optional<WorkspaceWriteClaimPort.WriteClaim> heartbeat(
            String claimId, long fencingToken, Instant occurredAt) {
        ensureOpen();
        return mutateClaim(claimId, fencingToken, occurredAt, ClaimMutation.HEARTBEAT);
    }

    /** release 重试回读相同 token 的终态行并幂等返回。 */
    @Override
    public Optional<WorkspaceWriteClaimPort.WriteClaim> release(
            String claimId, long fencingToken, Instant occurredAt) {
        ensureOpen();
        return mutateClaim(claimId, fencingToken, occurredAt, ClaimMutation.RELEASE);
    }

    /** abandon 可终结 WAITING 或 HELD，但仍要求 fencing token 匹配。 */
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

    /** composition shutdown 后阻止迟到事务，datasource 生命周期仍由 JaDatabase owner 管理。 */
    @Override
    public void close() {
        closed.set(true);
    }

    /**
     * Child 首次写入只在已持有 SqlSession 的事务 callback 中运行；USER message ordinal 由插入
     * 函数内部用于绑定附件，调用方不保留无后续语义的返回值。
     */
    private ConversationRepository.AdmissionReceipt admitChild(PersistenceMappers mapper,
                                                                TaskModels.ChildAdmission child,
                                                                ConversationRepository.TurnAdmission turn) {
        TaskRecords.ParentRow parent = requireParent(mapper, child.parentThreadId());
        requireParentRevision(parent, child.expectedParentRevision());
        if (!parent.workspaceId().equals(child.childThread().workspaceId())) {
            throw relation("child and parent must share one Workspace");
        }
        int depth = parent.depth() == null ? 1 : parent.depth() + 1;
        if (depth > 4) throw new TaskRepositoryException(TaskRepositoryException.Code.DEPTH_LIMIT,
                "task depth exceeds limit");
        String rootThreadId = parent.rootThreadId() == null ? parent.threadId() : parent.rootThreadId();
        if (mapper.tasks().countRootDescendants(rootThreadId) >= MAX_TREE_SIZE) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.TREE_LIMIT,
                    "task tree exceeds limit");
        }
        String rootTurnId = causalRootTurn(mapper, child.parentThreadId(), child.originTurnId(),
                child.lifecycle() == TaskModels.Lifecycle.ATTACHED);
        requireSeedMode(child);
        insertThread(mapper, child.childThread());
        String fingerprint = insertSeed(mapper, child.contextSeed());
        requireChanged(mapper.tasks().insertLineage(new TaskRecords.LineageInsert(
                child.childThread().threadId(), child.parentThreadId(), rootThreadId, child.originTurnId(),
                child.taskName(), depth, child.kind().name(), child.lifecycle().name(),
                child.contextSeed().contextSeedId(), instant(child.contextSeed().createdAt()))),
                "task lineage insert lost");
        requireChanged(mapper.tasks().insertChildTurn(childTurn(turn, child.originTurnId(), rootTurnId)),
                "child Turn insert lost");
        requireChanged(mapper.agent().insertTurnExecution(executionWrite(turn.turnId(), turn.initialExecution())),
                "child execution insert lost");
        long ordinal = 1;
        if (child.kind() == TaskModels.Kind.SIDE_TASK) {
            ordinal = injectEffectiveContext(mapper, child, turn, fingerprint);
        }
        insertUserMessage(mapper, child.childThread().workspaceId(), turn, ordinal, true);
        var preferences = child.childThread().preferences();
        requireChanged(mapper.history().compareAndSetThreadAdmission(new PersistenceRecords.ThreadAdmissionCas(
                turn.threadId(), preferences.providerId(), preferences.modelId(),
                preferences.reasoningLevel(), preferences.accessMode().name(),
                preferences.collaborationMode().name(), null,
                0, instant(turn.requestedAt()))), "child Thread admission revision lost");
        long activitySequence = insertActivity(mapper, child.activityId(), rootThreadId,
                child.childThread().threadId(), child.parentThreadId(), child.originTurnId(),
                TaskModels.ActivityKind.DISPATCHED, child.activitySummary(), child.contextSeed().createdAt());
        requireChanged(mapper.tasks().insertProjection(new TaskRecords.ProjectionInsert(
                child.childThread().threadId(), rootThreadId, TaskModels.State.QUEUED.name(),
                activitySequence, safeSummary(child.activitySummary()), null,
                instant(child.contextSeed().createdAt()))), "task projection insert lost");
        mapper.tasks().incrementAncestorDescendants(child.parentThreadId());
        return new ConversationRepository.AdmissionReceipt(turn.threadId(), turn.turnId(), 1, 0, null);
    }

    /** 已存在 Task 的后续 Turn 复用普通 admission 约束，并继承 causal root Turn。 */
    private ConversationRepository.AdmissionReceipt admitExistingTaskTurn(PersistenceMappers mapper,
                                                                           ConversationRepository.TurnAdmission turn,
                                                                           String parentTurnId) {
        PersistenceRecords.ThreadRow thread = requireThread(mapper, turn.threadId());
        if (thread.revision() != turn.expectedThreadRevision()) {
            throw concurrent("child Thread revision is stale");
        }
        String rootTurnId = causalRootTurn(mapper, null, parentTurnId, false);
        requireChanged(mapper.tasks().insertChildTurn(childTurn(turn, parentTurnId, rootTurnId)),
                "follow-up Turn insert lost");
        requireChanged(mapper.agent().insertTurnExecution(executionWrite(turn.turnId(), turn.initialExecution())),
                "follow-up execution insert lost");
        insertUserMessage(mapper, thread.workspaceId(), turn,
                mapper.agent().selectNextMessageOrdinal(turn.threadId()), true);
        requireChanged(mapper.history().compareAndSetThreadAdmission(new PersistenceRecords.ThreadAdmissionCas(
                turn.threadId(), thread.providerId(), thread.modelId(),
                thread.reasoningLevel(), thread.accessMode(), thread.collaborationMode(), null,
                turn.expectedThreadRevision(), instant(turn.requestedAt()))),
                "follow-up Thread admission revision lost");
        return new ConversationRepository.AdmissionReceipt(turn.threadId(), turn.turnId(),
                turn.expectedThreadRevision() + 1, 0, null);
    }

    /** effective context 消息只进入模型历史；Timeline 不展示继承内容，也不伪装成 Child 对话。 */
    private long injectEffectiveContext(PersistenceMappers mapper, TaskModels.ChildAdmission child,
                                        ConversationRepository.TurnAdmission turn, String fingerprint) {
        JsonNode context = JacksonJsonValues.toNode(objectMapper, child.contextSeed().effectiveContext());
        if (!context.isObject() || context.path("schemaVersion").asInt(-1) != 1
                || !context.path("parentThreadId").asText().equals(child.parentThreadId())
                || context.path("parentRevision").asLong(-1) != child.expectedParentRevision()
                || !context.path("messages").isArray()) {
            throw relation("effective context does not match frozen parent revision");
        }
        long ordinal = 1;
        for (JsonNode inherited : context.path("messages")) {
            String role = inherited.path("role").asText();
            JsonNode blocks = inherited.path("blocks");
            if (!("USER".equals(role) || "ASSISTANT".equals(role) || "TOOL".equals(role)) || !blocks.isArray()) {
                throw invalidState("effective context message has an invalid shape");
            }
            String messageId = inheritedMessageId(child.contextSeed().contextSeedId(), ordinal);
            requireChanged(mapper.agent().insertMessage(new PersistenceRecords.MessageInsert(
                    messageId, turn.threadId(), turn.turnId(), ordinal, role,
                    compact(blocks), instant(turn.requestedAt()))), "effective context message insert lost");
            ordinal++;
        }
        JsonNode checkpoint = context.path("checkpoint");
        if (checkpoint.isObject()) {
            JsonNode summary = checkpoint.path("summary");
            JsonNode usage = checkpoint.path("usage");
            if (!summary.isObject() || !usage.isObject()) {
                throw invalidState("effective context checkpoint has an invalid shape");
            }
            requireChanged(mapper.checkpoint().insertCheckpoint(new PersistenceRecords.CheckpointInsert(
                    inheritedCheckpointId(child.contextSeed().contextSeedId()), turn.threadId(), 1,
                    0, ordinal == 1 ? 0 : 1, null, compact(summary),
                    checkpoint.path("estimatedTokens").asInt(0), fingerprint,
                    ContextCompactionEvent.STRATEGY_VERSION,
                    compact(usage), instant(turn.requestedAt()))), "effective context checkpoint insert lost");
        } else if (!checkpoint.isNull()) {
            throw invalidState("effective context checkpoint has an invalid shape");
        }
        return ordinal;
    }

    /** task brief 是 Child 第一条可见 USER_INPUT，继承内容刻意不写 Timeline。 */
    private long insertUserMessage(PersistenceMappers mapper, String workspaceId,
                                   ConversationRepository.TurnAdmission turn, long ordinal,
                                   boolean timeline) {
        requireChanged(mapper.agent().insertMessage(new PersistenceRecords.MessageInsert(
                turn.messageId(), turn.threadId(), turn.turnId(), ordinal, turn.userMessage().role().name(),
                messages.writeMessage(turn.userMessage()), instant(turn.requestedAt()))),
                "task user message insert lost");
        bindAttachments(mapper, workspaceId, turn);
        if (timeline) {
            requireChanged(mapper.agent().insertTimelineMessage(new PersistenceRecords.TimelineMessageInsert(
                    turn.messageId(), turn.threadId(), turn.turnId(), "USER_INPUT",
                    visibleText(turn), null, instant(turn.requestedAt()))), "task Timeline insert lost");
        }
        return ordinal;
    }

    /**
     * 附件 DRAFT→BOUND 与 USER Message 同事务；消息归属使用稳定 messageId，附件自身 ordinal
     * 按输入顺序独立生成，避免把消息 ordinal 混入附件关系语义。
     */
    private static void bindAttachments(PersistenceMappers mapper, String workspaceId,
                                        ConversationRepository.TurnAdmission turn) {
        long totalBytes = 0;
        for (String attachmentId : turn.attachmentIds()) {
            AttachmentRecords.AttachmentRow row = mapper.attachments().selectUnreservedDraft(attachmentId, workspaceId);
            if (row == null || row.blobSha256() == null || !Instant.parse(row.expiresAt()).isAfter(turn.requestedAt())) {
                throw relation("task attachment is unavailable");
            }
            totalBytes = Math.addExact(totalBytes, row.sizeBytes());
            if (totalBytes > MAX_TURN_ATTACHMENT_BYTES) throw relation("task attachment quota exceeded");
        }
        int index = 0;
        for (String attachmentId : turn.attachmentIds()) {
            AttachmentRecords.AttachmentBind bind = new AttachmentRecords.AttachmentBind(attachmentId,
                    workspaceId, turn.messageId(), index++, instant(turn.requestedAt()));
            requireChanged(mapper.attachments().bindDraft(bind), "task attachment binding lost");
            requireChanged(mapper.attachments().insertMessageAttachment(bind), "task attachment relation lost");
        }
    }

    /** 根 Turn 只能来自数据库既有因果事实；首个无 origin 侧边任务保持 null。 */
    private static String causalRootTurn(PersistenceMappers mapper, String expectedParentThreadId,
                                         String parentTurnId, boolean requireNonTerminal) {
        if (parentTurnId == null) return null;
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

    /** Child Thread 插入沿用现有线程偏好和 title source，初始 revision 仍由 schema 固定为零。 */
    private static void insertThread(PersistenceMappers mapper, ConversationRepository.ThreadDefinition thread) {
        requireChanged(mapper.history().insertThread(ThreadPersistenceMapping.toInsert(thread)),
                "child Thread insert lost");
    }

    /** Seed JSON 先 canonical encode 再统一计算 fingerprint，调用方不能注入自定义签名。 */
    private String insertSeed(PersistenceMappers mapper, TaskModels.ContextSeedDraft seed) {
        String brief = json.writeContent(seed.taskBrief());
        String effective = seed.effectiveContext() == null ? null : json.write(seed.effectiveContext());
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

    /** SIDE_TASK 和 SUBAGENT 的上下文差异在持久入口再次 fail-closed。 */
    private static void requireSeedMode(TaskModels.ChildAdmission child) {
        if (child.kind() == TaskModels.Kind.SIDE_TASK
                && child.contextSeed().inheritanceMode() != TaskModels.InheritanceMode.EFFECTIVE_CONTEXT
                || child.kind() == TaskModels.Kind.SUBAGENT
                && child.contextSeed().inheritanceMode() != TaskModels.InheritanceMode.BRIEF_ONLY) {
            throw relation("task kind does not match context inheritance mode");
        }
    }

    /** Mailbox 路由只能位于同一根树，且至少一端必须是 Child Task。 */
    private static RoutedTask route(PersistenceMappers mapper, String senderThreadId, String targetThreadId) {
        TaskRecords.TaskRouteRow sender = mapper.tasks().selectTaskRoute(senderThreadId);
        TaskRecords.TaskRouteRow target = mapper.tasks().selectTaskRoute(targetThreadId);
        if (sender == null || target == null) throw notFound("mailbox route");
        String senderRoot = sender.rootThreadId() == null ? sender.threadId() : sender.rootThreadId();
        String targetRoot = target.rootThreadId() == null ? target.threadId() : target.rootThreadId();
        if (!senderRoot.equals(targetRoot)) throw relation("mailbox cannot cross task roots");
        String taskThreadId = target.depth() != null ? target.threadId()
                : sender.depth() != null ? sender.threadId() : null;
        if (taskThreadId == null) throw relation("mailbox requires a Child Task endpoint");
        return new RoutedTask(senderRoot, taskThreadId);
    }

    /** Message count 与 UTF-8 bytes 在同一 write transaction 校验，FINAL_ANSWER 走终态不可丢路径。 */
    private static void requireMailboxCapacity(PersistenceMappers mapper, String targetThreadId,
                                               String contentJson) {
        TaskRecords.MailboxStats stats = mapper.tasks().selectMailboxStats(targetThreadId);
        long bytes = contentJson.getBytes(StandardCharsets.UTF_8).length;
        if (stats.pendingCount() >= MAX_MAILBOX_PENDING || stats.pendingBytes() + bytes > MAX_MAILBOX_BYTES) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.MAILBOX_FULL, "task mailbox is full");
        }
    }

    /** 幂等键复用必须逐字段相等，矛盾重试不能静默返回首个结果。 */
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

    /** 插入竞争后只允许回读同一发送方幂等事实。 */
    private static TaskRecords.MailboxRow requireExistingMailbox(PersistenceMappers mapper,
                                                                 TaskModels.MailboxEnvelope envelope) {
        TaskRecords.MailboxRow row = mapper.tasks().selectMailboxByIdempotency(
                envelope.senderThreadId(), envelope.idempotencyKey());
        if (row == null) throw concurrent("mailbox insert changed concurrently");
        return row;
    }

    /** claim 状态机的四个动作共享同一事务回读，但不把普通 FIFO miss 当异常。 */
    private Optional<WorkspaceWriteClaimPort.WriteClaim> mutateClaim(
            String claimId, long fencingToken, Instant occurredAt, ClaimMutation mutation) {
        return transactions.required(mapper -> {
            TaskRecords.WriteClaimRow before = mapper.tasks().selectWriteClaim(claimId);
            if (before == null || before.fencingToken() != fencingToken) return Optional.empty();
            TaskRecords.WriteClaimCas command = new TaskRecords.WriteClaimCas(
                    claimId, fencingToken, instant(occurredAt));
            int changed = switch (mutation) {
                case ACQUIRE -> mapper.tasks().acquireWriteClaim(command);
                case HEARTBEAT -> mapper.tasks().heartbeatWriteClaim(command);
                case RELEASE -> mapper.tasks().releaseWriteClaim(command);
                case ABANDON -> mapper.tasks().abandonWriteClaim(command);
            };
            TaskRecords.WriteClaimRow after = mapper.tasks().selectWriteClaim(claimId);
            if (changed == 1) return Optional.of(writeClaim(after));
            boolean idempotentTerminal = mutation == ClaimMutation.RELEASE && "RELEASED".equals(before.state())
                    || mutation == ClaimMutation.ABANDON && "ABANDONED".equals(before.state());
            return idempotentTerminal ? Optional.of(writeClaim(after)) : Optional.empty();
        });
    }

    /** 将普通 TurnAdmission 扩展为 Child 因果 insert，保持父子身份在单一事务内冻结。 */
    private static TaskRecords.ChildTurnInsert childTurn(ConversationRepository.TurnAdmission turn,
                                                         String parentTurnId, String rootTurnId) {
        return new TaskRecords.ChildTurnInsert(turn.turnId(), turn.threadId(), parentTurnId, rootTurnId,
                instant(turn.requestedAt()));
    }

    /** execution JSON 使用 Conversation 的同一 schema version 和严格 codec。 */
    private PersistenceRecords.TurnExecutionWrite executionWrite(String turnId, TurnExecutionState state) {
        return new PersistenceRecords.TurnExecutionWrite(turnId, TurnExecutionState.SCHEMA_VERSION,
                executions.write(state));
    }

    /** Activity JSON 只在 adapter 编码，并返回 SQLite 单调 sequence。 */
    private long insertActivity(PersistenceMappers mapper, String activityId, String rootThreadId,
                                String taskThreadId, String actorThreadId, String causalTurnId,
                                TaskModels.ActivityKind kind, JsonObject summary, Instant occurredAt) {
        Long sequence = mapper.tasks().insertActivity(new TaskRecords.ActivityInsert(activityId,
                rootThreadId, taskThreadId, actorThreadId, causalTurnId, kind.name(), json.write(summary),
                instant(occurredAt)));
        if (sequence == null) throw concurrent("task activity insert lost");
        return sequence;
    }

    /** Mailbox row insert 参数固定初态与根身份。 */
    private TaskRecords.MailboxInsert mailboxInsert(TaskModels.MailboxEnvelope envelope,
                                                    String rootThreadId, String contentJson,
                                                    String state, String boundTurnId) {
        return new TaskRecords.MailboxInsert(envelope.messageId(), rootThreadId,
                envelope.senderThreadId(), envelope.targetThreadId(), envelope.causalTurnId(),
                envelope.kind().name(), contentJson, envelope.idempotencyKey(), state, boundTurnId,
                instant(envelope.createdAt()), instant(envelope.createdAt()));
    }

    /** Mapper summary 行在 SqlSession 内转换为领域值。 */
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

    /** Seed JSON 使用相同 codec 还原，不接受 null EFFECTIVE_CONTEXT 漂移。 */
    private TaskModels.ContextSeed seed(TaskRecords.ContextSeedRow row) {
        return new TaskModels.ContextSeed(row.contextSeedId(), row.parentThreadId(), row.parentTurnId(),
                row.parentRevision(), TaskModels.InheritanceMode.valueOf(row.inheritanceMode()),
                json.readContent(row.taskBriefJson()), row.effectiveContextJson() == null ? null
                : json.readObject(row.effectiveContextJson()), json.readArray(row.referencesJson()),
                json.readObject(row.permissionCeilingJson()), row.fingerprint(), Instant.parse(row.createdAt()));
    }

    /** Activity 行在 adapter 内解析安全 summary。 */
    private TaskModels.Activity activity(TaskRecords.ActivityRow row) {
        return new TaskModels.Activity(row.activitySequence(), row.activityId(), row.rootThreadId(),
                row.taskThreadId(), row.actorThreadId(), row.causalTurnId(),
                TaskModels.ActivityKind.valueOf(row.kind()), json.readObject(row.summaryJson()),
                Instant.parse(row.createdAt()));
    }

    /** LEFT JOIN 行先做完整关系校验再构造领域值，任何缺失或错配均失败关闭。 */
    private TaskModels.ActivityProjection rootActivityProjection(
            String requestedRootThreadId, TaskRecords.RootActivityProjectionRow row) {
        if (!requestedRootThreadId.equals(row.activityRootThreadId())
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

    /** Mailbox 行复用 UserContent codec，确保 Tool blocks 不能混入任务通信。 */
    private TaskModels.MailboxMessage mailbox(TaskRecords.MailboxRow row) {
        return new TaskModels.MailboxMessage(row.mailboxSequence(), row.messageId(), row.rootThreadId(),
                row.senderThreadId(), row.targetThreadId(), row.causalTurnId(),
                TaskModels.MailboxKind.valueOf(row.kind()), json.readContent(row.contentJson()),
                row.idempotencyKey(), TaskModels.MailboxState.valueOf(row.state()), row.boundTurnId(),
                Instant.parse(row.createdAt()), Instant.parse(row.updatedAt()), optionalInstant(row.consumedAt()));
    }

    /** Loop Mailbox 投影只携带模型安全点消费所需字段，不把 Task UI 状态反向泄漏到 Conversation。 */
    private TaskMailboxPort.ClaimedMessage claimedMailbox(TaskRecords.MailboxRow row) {
        return new TaskMailboxPort.ClaimedMessage(row.mailboxSequence(), row.messageId(), row.rootThreadId(),
                row.senderThreadId(), row.targetThreadId(), row.causalTurnId(),
                TaskMailboxPort.MessageKind.valueOf(row.kind()), json.readContent(row.contentJson()),
                row.idempotencyKey(), row.boundTurnId());
    }

    /** Write claim 行保持 fencing token 原值，不从 sequence 推导。 */
    private static WorkspaceWriteClaimPort.WriteClaim writeClaim(TaskRecords.WriteClaimRow row) {
        return new WorkspaceWriteClaimPort.WriteClaim(
                row.claimSequence(), row.claimId(), row.workspaceId(), row.threadId(),
                row.turnId(), row.processGeneration(), row.fencingToken(),
                WorkspaceWriteClaimPort.State.valueOf(row.state()), Instant.parse(row.requestedAt()),
                optionalInstant(row.acquiredAt()), optionalInstant(row.heartbeatAt()),
                optionalInstant(row.releasedAt()));
    }

    /** Context checkpoint 节点保留 summary/usage 与展示边界，但不携带 secret 或原始隐藏推理。 */
    private JsonNode checkpointNode(PersistenceRecords.CheckpointRow checkpoint) {
        if (checkpoint == null) return objectMapper.nullNode();
        ObjectNode node = objectMapper.createObjectNode().put("checkpointId", checkpoint.checkpointId())
                .put("sourceRevision", checkpoint.sourceRevision())
                .put("throughOrdinal", checkpoint.throughOrdinal())
                .put("retainedFromOrdinal", checkpoint.retainedFromOrdinal())
                .put("estimatedTokens", checkpoint.estimatedTokens());
        node.set("summary", parseObject(checkpoint.summaryJson(), "checkpoint summary"));
        node.set("usage", parseObject(checkpoint.usageJson(), "checkpoint usage"));
        if (checkpoint.retainedSplitJson() == null) node.putNull("retainedSplit");
        else node.set("retainedSplit", parseObject(checkpoint.retainedSplitJson(), "retained split"));
        return node;
    }

    /** retained split 只替换其源消息的 text blocks，冻结结果不再依赖父 checkpoint 解码。 */
    private JsonNode applyRetainedSplit(PersistenceRecords.MessageRow row, JsonNode blocks, String splitJson) {
        JsonNode split = parseObject(splitJson, "retained split");
        if (!row.messageId().equals(split.path("sourceMessageId").asText())) return blocks;
        JsonNode retained = split.path("retainedMessage");
        if (!retained.isObject() || !retained.path("text").isTextual()) {
            throw invalidState("retained split has an invalid shape");
        }
        ArrayNode replacement = objectMapper.createArrayNode();
        replacement.addObject().put("kind", "text").put("text", retained.path("text").asText());
        return replacement;
    }

    /** 引用只从真正保留的 blocks 收集，并按首次出现顺序去重。 */
    private static void collectReferences(JsonNode blocks, ArrayNode references, Set<String> seen) {
        for (JsonNode block : blocks) {
            String kind = block.path("kind").asText();
            if (!("attachment".equals(kind) || "workspace_reference".equals(kind)
                    || "skill_reference".equals(kind))) continue;
            String key = block.toString();
            if (seen.add(key)) references.add(block.deepCopy());
        }
    }

    /** inherited message ID 由唯一 seed 和局部 ordinal 派生，重试得到完全相同身份。 */
    private static String inheritedMessageId(String seedId, long ordinal) {
        return "item_ctx_" + seedId.substring("seed_".length()) + '_' + ordinal;
    }

    /** inherited checkpoint ID 同样由 seed 唯一派生，事务重试不会生成第二份 checkpoint。 */
    private static String inheritedCheckpointId(String seedId) {
        return "checkpoint_task_" + seedId.substring("seed_".length());
    }

    /** Activity summary 的 text 成员是唯一列表预览；缺失时不从其它 JSON 猜测。 */
    private static String safeSummary(JsonObject summary) {
        return summary.get("text") instanceof JsonText text ? text.value() : null;
    }

    /** Timeline 只显示 task brief 的用户正文，结构化引用通过既有附件/引用 UI 展示。 */
    private static String visibleText(ConversationRepository.TurnAdmission turn) {
        return turn.userMessage().content().stream().filter(TextContent.class::isInstance)
                .map(TextContent.class::cast).map(TextContent::text).findFirst().orElse("");
    }

    /** ObjectMapper 只接受数组形状，未知或损坏 JSON 不降级为空。 */
    private JsonNode parseArray(String value, String label) {
        JsonNode node = parse(value, label);
        if (!node.isArray()) throw invalidState(label + " is not an array");
        return node;
    }

    /** ObjectMapper 只接受对象形状，未知或损坏 JSON 不降级为空。 */
    private JsonNode parseObject(String value, String label) {
        JsonNode node = parse(value, label);
        if (!node.isObject()) throw invalidState(label + " is not an object");
        return node;
    }

    /** 解析错误统一为安全持久化状态错误，不回显原始 prompt JSON。 */
    private JsonNode parse(String value, String label) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE, "cannot decode " + label, failure);
        }
    }

    /** Jackson 树复制到 foundation 对象，领域记录不暴露可变节点。 */
    private static JsonObject requireObject(JsonNode node) {
        return (JsonObject) JacksonJsonValues.fromNode(node);
    }

    /** Jackson 树复制到 foundation 数组，领域记录不暴露可变节点。 */
    private static JsonArray requireArray(JsonNode node) {
        return (JsonArray) JacksonJsonValues.fromNode(node);
    }

    /** compact JSON 用于 Child 注入，避免缩进差异污染 byte budget 和 fingerprint。 */
    private String compact(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE, "cannot encode task context", failure);
        }
    }

    /** parent revision 冲突有独立错误分类，Coordinator 可映射 TASK_CONTEXT_REVISION_CONFLICT。 */
    private static void requireParentRevision(TaskRecords.ParentRow parent, long expectedRevision) {
        if (expectedRevision < 0 || parent.revision() != expectedRevision) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.CONTEXT_REVISION_CONFLICT,
                    "parent Thread revision is stale");
        }
    }

    /** parent 查找不接受已 soft-delete Thread。 */
    private static TaskRecords.ParentRow requireParent(PersistenceMappers mapper, String threadId) {
        TaskRecords.ParentRow row = mapper.tasks().selectParent(threadId);
        if (row == null) throw notFound("parent Thread");
        return row;
    }

    /** follow-up admission 读取目标 Thread 的权威 revision。 */
    private static PersistenceRecords.ThreadRow requireThread(PersistenceMappers mapper, String threadId) {
        PersistenceRecords.ThreadRow row = mapper.history().selectThread(threadId);
        if (row == null) throw notFound("task Thread");
        return row;
    }

    /** Task summary 缺失统一映射 TASK_NOT_FOUND。 */
    private TaskModels.Summary requireTask(PersistenceMappers mapper, String taskThreadId) {
        TaskRecords.TaskSummaryRow row = mapper.tasks().selectTaskSummary(taskThreadId);
        if (row == null) throw notFound("Task");
        return summary(row);
    }

    /** claim 回读缺失表明事务内不变量破坏。 */
    private static TaskRecords.WriteClaimRow requireWriteClaim(PersistenceMappers mapper, String claimId) {
        TaskRecords.WriteClaimRow row = mapper.tasks().selectWriteClaim(claimId);
        if (row == null) throw invalidState("write claim is unavailable");
        return row;
    }

    /** MyBatis 行数是所有单行状态门的最终竞争证据。 */
    private static void requireChanged(int count, String message) {
        if (count != 1) throw concurrent(message);
    }

    /** 终态判断集中一处，避免状态新增时 completed_at 约束漂移。 */
    private static boolean terminal(TaskModels.State state) {
        return state == TaskModels.State.COMPLETED || state == TaskModels.State.FAILED
                || state == TaskModels.State.CANCELLED;
    }

    /** 所有时刻保存 ISO-8601 UTC/offset 文本，不读取本机时区。 */
    private static String instant(Instant value) {
        return Objects.requireNonNull(value, "instant").toString();
    }

    /** 可空持久时刻只由 null 表达缺失。 */
    private static Instant optionalInstant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    /** Task 不存在错误不泄露具体数据库表。 */
    private static TaskRepositoryException notFound(String entity) {
        return new TaskRepositoryException(TaskRepositoryException.Code.NOT_FOUND, entity + " is unavailable");
    }

    /** 关系错误用于 workspace/root/origin/seed 不一致。 */
    private static TaskRepositoryException relation(String message) {
        return new TaskRepositoryException(TaskRepositoryException.Code.RELATION_INVALID, message);
    }

    /** CAS 竞争供调用方刷新 projection 后决定是否重试。 */
    private static TaskRepositoryException concurrent(String message) {
        return new TaskRepositoryException(TaskRepositoryException.Code.CAS_CONFLICT, message);
    }

    /** 损坏或非法恢复状态必须 fail-closed。 */
    private static TaskRepositoryException invalidState(String message) {
        return new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE, message);
    }

    /** close 后所有事务 fail-fast，避免 datasource shutdown 期间出现迟到写。 */
    private void ensureOpen() {
        if (closed.get()) throw new StorageException(StorageException.Code.CLOSED, "task store is closed");
    }

    /** 路由结果同时保留根身份与实际 Child 端点，防止跨任务树投递 Mailbox。 */
    private record RoutedTask(String rootThreadId, String taskThreadId) { }

    /** Mailbox claim 提交后才输出等待时长，避免回滚事务制造不存在的运行指标。 */
    private record ClaimMeasurement(TaskMailboxPort.ClaimBatch batch, long lagMillis) { }

    /** 写声明的 CAS 动作闭集让四条 SQL 路径共享同一回读与幂等终态判断。 */
    private enum ClaimMutation {
        /** 尝试让 FIFO 队首取得 fencing 所有权。 */
        ACQUIRE,
        /** 仅由当前 fencing owner 延长存活证据。 */
        HEARTBEAT,
        /** 正常完成写操作后提交不可逆释放。 */
        RELEASE,
        /** 超时、取消或恢复时永久废弃旧声明。 */
        ABANDON
    }
}
