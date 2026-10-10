// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application;

import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.application.context.checkpoint.StoreBackedCheckpointStore;
import io.github.kongweiguang.ja.agent.application.engine.AgentRequestFingerprint;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.application.loop.AgentContextMapper;
import io.github.kongweiguang.ja.agent.application.loop.AgentLoopPersistence;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentCapabilities;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentException;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentMetadata;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentService;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 在提交或排队前按 Core 当前有效上下文汇总附件字节；实际 Provider 准入仍由 Core 再次检查。
 */
@Service
public final class AttachmentRequestBudget {
    private final AgentEngine engine;
    private final AgentStore store;
    private final ExecutionRecordCodec records;
    private final AgentContextMapper contexts;
    private final AttachmentService attachments;
    private final AttachmentCapabilities capabilities;

    /** 复用 Core 的历史映射与同一附件能力快照，不读取附件正文或另建预算口径。 */
    public AttachmentRequestBudget(AgentEngine engine, AgentStore store, ExecutionRecordCodec records,
                                   JsonValueCodec jsonCodec, AttachmentService attachments,
                                   AttachmentCapabilities capabilities) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.store = Objects.requireNonNull(store, "store");
        this.records = Objects.requireNonNull(records, "records");
        this.contexts = new AgentContextMapper(Objects.requireNonNull(jsonCodec, "jsonCodec"), records);
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    /** 已完成 Thread 的新 Turn 按当前 Checkpoint 保留历史与新增输入计费。 */
    public void validateSubmission(AgentIdentity identity, String operationKey, UserContent addition) {
        preflight(identity, operationKey, addition, Scope.COMPLETED_THREAD, Optional.empty());
    }

    /** 补充输入按当前活动 Turn、所有未消费队列项和本次输入共同计费。 */
    public void validateAppend(AgentIdentity identity, String operationKey, UserContent addition) {
        preflight(identity, operationKey, addition, Scope.ACTIVE_TURN, Optional.empty());
    }

    /** 队列新增或有效替换先预检；陈旧或不可能执行的变更留给 Core 返回权威冲突。 */
    public void validateQueueMutation(AgentIdentity identity, AgentInputCommand command) {
        UserContent addition;
        if (command instanceof AgentInputCommand.Enqueue enqueue) {
            addition = enqueue.content();
        } else if (command instanceof AgentInputCommand.Update update) {
            addition = update.content();
        } else {
            return;
        }
        preflight(identity, command.operationKey(), addition, Scope.QUEUE_MUTATION,
                Optional.of(command));
    }

    /** 回执已存在时不让新的上下文预算阻止 Core 回放或报告原操作键冲突。 */
    private void preflight(AgentIdentity identity, String operationKey, UserContent addition,
                           Scope scope, Optional<AgentInputCommand> queueMutation) {
        if (hasAdmissionReceipt(identity, operationKey)) {
            return;
        }
        try {
            List<AttachmentMetadata> additionMetadata = attachments.validateInput(identity, addition.attachmentIds());
            ContextSnapshot context = contextSnapshot(identity, scope == Scope.ACTIVE_TURN
                    || scope == Scope.QUEUE_MUTATION);
            List<QueueItem> queue = List.of();
            Optional<String> replacedInputId = Optional.empty();
            if (scope == Scope.ACTIVE_TURN) {
                queue = queueItems(identity, engine.inspectInputQueue(identity));
            } else if (scope == Scope.QUEUE_MUTATION) {
                AgentInputQueue current = engine.inspectInputQueue(identity);
                QueuePlan plan = queuePlan(current, queueMutation.orElseThrow());
                if (!plan.valid()) {
                    return;
                }
                queue = queueItems(identity, current);
                replacedInputId = plan.replacedInputId();
            }
            List<String> references = collectReferences(context.messages(), context.retainedFromOrdinal(), queue,
                    replacedInputId, addition.attachmentIds());
            List<String> persistedReferences = collectReferences(context.messages(), context.retainedFromOrdinal(),
                    queue, replacedInputId, List.of());
            requireWithinLimit(identity, references, persistedReferences, additionMetadata);
        } catch (AttachmentException failure) {
            if (hasAdmissionReceipt(identity, operationKey)) {
                return;
            }
            throw failure;
        }
    }

    /** 将当前消息投影到与执行用途一致的完整历史或活动 Turn 范围。 */
    private ContextSnapshot contextSnapshot(AgentIdentity identity, boolean activeTurn) {
        AgentStore.ConversationKey conversation = conversationKey(identity);
        AgentLoopPersistence.ConversationHistory history = new AgentLoopPersistence(store, Clock.systemUTC())
                .restoreConversation(conversation, CancellationToken.none());
        Set<String> visibleTurns = history.executions().stream()
                .map(execution -> execution.key().turnId()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<ContextMessage> messages = activeTurn
                ? contexts.fromSnapshot(history, visibleTurns, identity.turnId())
                : contexts.fromCompleteSnapshot(history, visibleTurns);
        long retainedFromOrdinal = new StoreBackedCheckpointStore(store, records).read(history.conversation())
                .checkpoint().map(CheckpointStore.ContextCheckpoint::retainedFromOrdinal).orElse(0L);
        return new ContextSnapshot(messages, retainedFromOrdinal);
    }

    /** 读取未消费队列正文引用；排队内容独立于模型历史，必须作为本次请求预算的一部分。 */
    private List<QueueItem> queueItems(AgentIdentity identity, AgentInputQueue queue) {
        return queue.entries().stream().map(entry -> {
            AgentStore.StoredRecord record = store.readRecord(executionKey(identity), entry.messageRecordId())
                    .orElseThrow(() -> new ChatNotFoundException("queued input not found"));
            UserContent content = records.decodeQueuedUser(record).content();
            return new QueueItem(entry.inputId(), content.attachmentIds());
        }).toList();
    }

    /** 只有语义上可应用的 UPDATE 才替换旧预算项；陈旧版本由 Core 优先裁决。 */
    private static QueuePlan queuePlan(AgentInputQueue queue, AgentInputCommand command) {
        if (command instanceof AgentInputCommand.Enqueue enqueue) {
            boolean available = queue.entries().size() < AgentInputQueue.MAX_ENTRIES
                    && queue.entries().stream().noneMatch(entry -> entry.inputId().equals(enqueue.inputId()));
            return new QueuePlan(available, Optional.empty());
        }
        if (command instanceof AgentInputCommand.Update update) {
            boolean current = queue.entries().stream().anyMatch(entry -> entry.inputId().equals(update.inputId())
                    && entry.inputRevision() == update.expectedInputRevision());
            return new QueuePlan(current, current ? Optional.of(update.inputId()) : Optional.empty());
        }
        return new QueuePlan(false, Optional.empty());
    }

    /** 从 Core 映射的上下文、待消费队列和新增输入保留附件出现次数，不按 ID 去重计费。 */
    static List<String> collectReferences(List<ContextMessage> messages, long retainedFromOrdinal,
                                          List<QueueItem> queue, Optional<String> replacedInputId,
                                          List<String> additionIds) {
        List<String> references = new ArrayList<>();
        for (ContextMessage message : messages) {
            if (message.ordinal() < retainedFromOrdinal) {
                continue;
            }
            for (ContextMessage.Block block : message.blocks()) {
                if (block instanceof ContextMessage.AttachmentBlock attachment) {
                    references.add(attachment.attachmentId());
                }
            }
        }
        for (QueueItem item : queue) {
            if (replacedInputId.filter(item.inputId()::equals).isEmpty()) {
                references.addAll(item.attachmentIds());
            }
        }
        references.addAll(additionIds);
        return List.copyOf(references);
    }

    /** 复用新增输入已校验的元数据，历史/队列按唯一 ID 读取一次，之后按每次出现累加。 */
    private void requireWithinLimit(AgentIdentity identity, List<String> references,
                                    List<String> persistedReferences,
                                    List<AttachmentMetadata> additionMetadata) {
        if (references.isEmpty()) {
            return;
        }
        Map<String, AttachmentMetadata> metadata = new LinkedHashMap<>();
        additionMetadata.forEach(item -> metadata.put(item.id(), item));
        LinkedHashSet<String> persistedIds = new LinkedHashSet<>(persistedReferences);
        for (String id : persistedIds) {
            AttachmentMetadata known = metadata.get(id);
            if (known != null) {
                if (!known.referenced()) {
                    throw new AttachmentException(AttachmentException.Reason.NOT_FOUND);
                }
            }
        }
        List<String> missing = persistedIds.stream().filter(id -> !metadata.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            List<AttachmentMetadata> found = attachments.metadata(
                    new CurrentActor(identity.tenantId(), identity.userId()), identity.threadId(), missing);
            found.forEach(item -> metadata.put(item.id(), item));
            if (missing.stream().anyMatch(id -> !metadata.containsKey(id))) {
                throw new AttachmentException(AttachmentException.Reason.NOT_FOUND);
            }
        }
        for (String id : persistedIds) {
            AttachmentMetadata item = metadata.get(id);
            if (!item.referenced()) {
                throw new AttachmentException(AttachmentException.Reason.NOT_FOUND);
            }
            capabilities.requireSupported(item.mediaType(), item.sizeBytes());
        }
        for (AttachmentMetadata item : additionMetadata) {
            capabilities.requireSupported(item.mediaType(), item.sizeBytes());
        }
        totalBytes(references, metadata, capabilities.maxTotalBytes());
    }

    /** 对引用序列逐次累加，避免重复 ID 被误当成只向 Provider 发送一次。 */
    static long totalBytes(List<String> references, Map<String, AttachmentMetadata> metadata, long maximum) {
        long total = 0;
        for (String id : references) {
            AttachmentMetadata item = metadata.get(id);
            if (item == null) {
                throw new AttachmentException(AttachmentException.Reason.NOT_FOUND);
            }
            if (item.sizeBytes() > maximum - total) {
                throw new AttachmentException(AttachmentException.Reason.REQUEST_TOO_LARGE);
            }
            total += item.sizeBytes();
        }
        return total;
    }

    /** 查询当前线程用户的稳定回执，使并发重试沿 Core 指纹与冲突规则处理。 */
    private boolean hasAdmissionReceipt(AgentIdentity identity, String operationKey) {
        String scopedOperationKey = AgentRequestFingerprint.submissionOperationKey(identity, operationKey);
        Optional<AgentStore.OperationReceipt> receipt = store.readReceipt(new AgentStore.OperationKey(
                identity.tenantId(), identity.userId(), scopedOperationKey));
        return receiptMatches(identity, operationKey, receipt);
    }

    /** Store 以 Core 身份域化键查询；同操作键的跨线程冲突或回放仍由 Core 权威裁决。 */
    static boolean receiptMatches(AgentIdentity identity, String operationKey,
                                  Optional<AgentStore.OperationReceipt> receipt) {
        AgentStore.ConversationKey owner = conversationKey(identity);
        String scopedOperationKey = AgentRequestFingerprint.submissionOperationKey(identity, operationKey);
        return receipt.filter(value -> value.operationKey().equals(scopedOperationKey)
                && value.conversation().tenantId().equals(owner.tenantId())
                && value.conversation().userId().equals(owner.userId())).isPresent();
    }

    /** 将冻结身份映射为数据库 owner key，不从请求正文接受 tenant 或 user。 */
    private static AgentStore.ConversationKey conversationKey(AgentIdentity identity) {
        return new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), identity.userId());
    }

    /** 当前执行的队列正文只可能属于该身份对应的 Turn。 */
    private static AgentStore.ExecutionKey executionKey(AgentIdentity identity) {
        return new AgentStore.ExecutionKey(conversationKey(identity), identity.turnId());
    }

    /** 当前预算路径区分已完成历史、活动追加和队列变更的上下文边界。 */
    private enum Scope {
        /** 新 Turn 只计入 Checkpoint 保留的已完成历史。 */
        COMPLETED_THREAD,
        /** 活动 Turn 追加输入同时计入已有队列内容。 */
        ACTIVE_TURN,
        /** 队列变更按 Core 队列状态应用新增或替换语义。 */
        QUEUE_MUTATION
    }

    /** 队列内容视图不携带正文或元数据，只保留替换与附件计费所需字段。
     *
     * @param inputId 可被 UPDATE 替换的队列条目标识
     * @param attachmentIds 该条目实际引用的附件标识
     */
    record QueueItem(String inputId,
                     List<String> attachmentIds) {
        /** 使纯预算策略测试和调用方均持有不可变附件引用。 */
        QueueItem {
            attachmentIds = List.copyOf(attachmentIds);
        }
    }

    /** 历史映射与 Checkpoint 来自同一会话水位，避免并发写入混合两个预算快照。
     *
     * @param messages 在该历史水位内映射出的模型上下文消息
     * @param retainedFromOrdinal Checkpoint 当前保留历史的起始 ordinal
     */
    private record ContextSnapshot(List<ContextMessage> messages,
                                   long retainedFromOrdinal) {
        /** 预算调用方不能在映射完成后修改消息水位。 */
        private ContextSnapshot {
            messages = List.copyOf(messages);
        }
    }

    /** Core 决定队列操作有效性后，预算器才应用 UPDATE 替换语义。
     *
     * @param valid 本次队列变更能否按已观察状态纳入预算
     * @param replacedInputId 被有效 UPDATE 替换的队列条目标识
     */
    private record QueuePlan(boolean valid,
                             Optional<String> replacedInputId) {
    }
}
