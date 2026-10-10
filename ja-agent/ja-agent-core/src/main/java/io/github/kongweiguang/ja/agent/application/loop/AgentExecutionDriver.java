// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.TurnEventSink;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionOutcome;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.application.engine.AgentInitialQueuedInputsCodec;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.conversation.ContextInstruction;
import io.github.kongweiguang.ja.agent.domain.execution.ControlRequest;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.AgentEvent;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.ToolArgumentValidator;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentRecordIds;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionCursorCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionEventCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExtensionFactRecordCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.MiddlewareStateRecordCodec;
import io.github.kongweiguang.ja.agent.shared.Hashing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * 以 AgentStore 的 CAS 事务驱动唯一执行状态机，不让公开 Engine 或应用适配器重建循环语义。
 */
public final class AgentExecutionDriver {
    private final AgentStore store;
    private final AgentLoopPersistence persistence;
    private final ExecutionRecordCodec records;
    private final ExecutionCursorCodec cursors;
    private final ExecutionEventCodec events;
    private final AgentContextMapper mapper;
    private final ToolArgumentValidator toolArgumentValidator;
    private final ExecutionControlCodec controls;
    private final JsonValueCodec json;
    private final AgentInitialQueuedInputsCodec initialQueuedInputs;
    private final ExtensionFactRecordCodec extensionFacts;
    private final MiddlewareStateRecordCodec middlewareStates;
    private final Clock clock;
    private final Duration leaseDuration;

    /**
     * 固定唯一持久化边界；工具、模型和存储资源仍由 Engine 或业务宿主管理。
     */
    public AgentExecutionDriver(AgentStore store, AgentLoopPersistence persistence,
                                ExecutionRecordCodec records, ExecutionCursorCodec cursors,
                                ExecutionEventCodec events, AgentContextMapper mapper,
                                ToolArgumentValidator toolArgumentValidator,
                                ExecutionControlCodec controls, JsonValueCodec json,
                                ExtensionFactRecordCodec extensionFacts,
                                MiddlewareStateRecordCodec middlewareStates,
                                Clock clock, Duration leaseDuration) {
        this.store = Objects.requireNonNull(store, "store");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.records = Objects.requireNonNull(records, "records");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
        this.events = Objects.requireNonNull(events, "events");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.toolArgumentValidator = Objects.requireNonNull(toolArgumentValidator, "toolArgumentValidator");
        this.controls = Objects.requireNonNull(controls, "controls");
        this.json = Objects.requireNonNull(json, "json");
        this.initialQueuedInputs = new AgentInitialQueuedInputsCodec();
        this.extensionFacts = Objects.requireNonNull(extensionFacts, "extensionFacts");
        this.middlewareStates = Objects.requireNonNull(middlewareStates, "middlewareStates");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = positive(leaseDuration);
    }

    /**
     * 返回 Store 接受的唯一会话与执行身份映射。
     */
    static AgentStore.ExecutionKey executionKey(AgentIdentity identity) {
        return new AgentStore.ExecutionKey(new AgentStore.ConversationKey(
                identity.tenantId(), identity.threadId(), identity.userId()), identity.turnId());
    }

    /**
     * 构造请求已派发但用量未知的唯一审计事实；恢复与空闲取消共用同一轮次、用途和 ordinal 语义。
     */
    static ExecutionFacts.Usage unknownUsage(TurnExecutionState.ProviderPending pending) {
        Objects.requireNonNull(pending, "pending");
        return new ExecutionFacts.Usage(pending.modelRequestId(), null,
                Math.max(1, pending.common().modelRound() + 1), pending.common().nextProviderOrdinal(),
                pending.purpose() == TurnExecutionState.ProviderPurpose.ASSISTANT
                        ? ExecutionFacts.UsagePurpose.ASSISTANT : ExecutionFacts.UsagePurpose.SUMMARY,
                ExecutionFacts.UsageCertainty.UNKNOWN, pending.profile(), null);
    }

    /**
     * 给派发前估算分配独立稳定 ID，避免它与同一请求的 Provider 结算事实互相覆盖。
     */
    static String estimatedUsageRecordId(AgentStore.ExecutionKey execution, String modelRequestId) {
        return recordId(execution, "agent.usage", "estimate:" + Objects.requireNonNull(modelRequestId, "modelRequestId"));
    }

    /**
     * 根据执行身份、事实类型和稳定业务键分配固定记录 ID，重试不能新建另一份事实。
     */
    static String recordId(AgentStore.ExecutionKey execution, String recordType, String stableFactKey) {
        return AgentRecordIds.derive(execution, recordType, stableFactKey);
    }

    /**
     * 事件身份由原操作派生，不含时间或随机数，未知提交结果可精确核对。
     */
    private static String eventId(AgentStore.ExecutionKey execution, String stableKey, String suffix) {
        String value = recordId(execution, "agent.event", stableKey + ":" + suffix);
        return "evt_" + value.substring("rec_".length());
    }

    /**
     * 租期仅用于多实例执行权接管，必须为正且不能被当成总执行时长预算。
     */
    private static Duration positive(Duration value) {
        Objects.requireNonNull(value, "leaseDuration");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("lease duration must be positive");
        }
        return value;
    }

    /**
     * 将一次循环必须共享的持久化边界集中为包级服务视图，避免 Session 复制 codec、存储或时钟装配。
     */
    Services services() {
        return new Services(store, persistence, records, cursors, events, mapper, toolArgumentValidator,
                controls, json, extensionFacts, middlewareStates, clock, leaseDuration);
    }

    /**
     * 构造完整准入事务，用户正文或内部指令及其初始队列、绑定记录、游标与事件一次提交，
     * 避免消息投递与执行身份分属两个提交。
     */
    public AgentStore.Admission prepareAdmission(AgentExecutionRequest request,
                                                 AgentRequestRuntime runtime,
                                                 AgentStore.RecordWrite requestBinding,
                                                 long expectedThreadRevision) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(requestBinding, "requestBinding");
        if (expectedThreadRevision < 0) {
            throw new IllegalArgumentException("negative conversation revision");
        }
        if (!"agent.request.binding".equals(requestBinding.recordType()))
            {
                throw new IllegalArgumentException("request binding record type mismatch");
            }
        if (!request.identity().tenantId().equals(executionKey(request.identity()).conversation().tenantId()))
            {
                throw new IllegalArgumentException("request identity does not match execution key");
            }
        request.initialContextSeed().ifPresent(seed -> requireFreshSeedConversation(
                request, seed, expectedThreadRevision));

        Instant now = request.acceptedAt();
        AgentStore.ExecutionKey execution = executionKey(request.identity());
        TurnExecutionState.Common common = new TurnExecutionState.Common(0, 0, 1,
                runtime.promptSession().currentRevision(), runtime.promptSession().activeSkillReferences());
        TurnExecutionState.Next initialNext = request.kind() == AgentExecutionRequest.AdmissionKind.COMPACTION
                ? TurnExecutionState.Next.COMPACTION : TurnExecutionState.Next.ASSISTANT;
        TurnExecutionState.Ready initial = new TurnExecutionState.Ready(common, initialNext, null);
        List<AgentStore.RecordWrite> writes = new ArrayList<>();
        String inputMessageId = null;
        String instructionMessageId = null;
        request.initialContextSeed().ifPresent(seed -> writes.add(records.encodeInitialContextSeed(
                recordId(execution, "agent.context.seed", "initial"), seed, now)));
        if (request.kind() == AgentExecutionRequest.AdmissionKind.USER_INPUT) {
            inputMessageId = request.inputMessageId();
            writes.add(records.encodeUser(recordId(execution, "agent.user.message", inputMessageId),
                    inputMessageId, request.input().orElseThrow(), now));
        } else if (request.kind() == AgentExecutionRequest.AdmissionKind.INTERNAL) {
            ContextInstruction instruction = request.instruction().orElseThrow(
                    () -> new IllegalArgumentException("internal admission needs a context instruction"));
            instructionMessageId = instruction.messageId();
            writes.add(records.encodeInstruction(recordId(execution, "agent.context.instruction", instructionMessageId),
                    instruction, now));
        }
        int queueRevision = 0;
        for (AgentInputCommand.Enqueue enqueue : request.initialQueuedInputs()) {
            String queuedMessageRecordId = recordId(execution, "agent.input.message", enqueue.operationKey());
            String messageId = recordId(execution, "agent.input.message-id", enqueue.operationKey());
            String commandId = recordId(execution, "agent.input.queue.command", enqueue.operationKey());
            writes.add(records.encodeQueuedUser(queuedMessageRecordId, messageId, enqueue.content(), now));
            AgentInputQueue.Command command = new AgentInputQueue.Command(commandId,
                    AgentInputQueue.Action.ENQUEUE, enqueue.inputId(),
                    AgentInputQueue.Kind.valueOf(enqueue.kind().name()), 0, ++queueRevision,
                    queuedMessageRecordId, null, now, enqueue.hostMetadata());
            writes.add(controls.encodeInputQueueCommand(commandId, command, now));
        }
        writes.add(initialQueuedInputs.encode(execution, request.operation().operationKey(),
                request.initialQueuedInputs(), now));
        writes.add(requestBinding);
        AgentStore.EventWrite admitted = events.encode(eventId(execution, request.operation().operationKey(), "admitted"),
                new ExecutionEvent.Admitted(request.kind().name(), inputMessageId, instructionMessageId), now);
        return new AgentStore.Admission(execution, expectedThreadRevision, cursors.schemaVersion(),
                TurnState.QUEUED.name(), cursors.write(initial), writes, List.of(admitted),
                request.operation(), now);
    }

    /**
     * 只允许在 CAS 水位下尚无执行与消息记录的会话导入背景；标题等元数据 revision 不改变该语义。
     */
    private void requireFreshSeedConversation(AgentExecutionRequest request, InitialContextSeed seed,
                                              long expectedThreadRevision) {
        AgentStore.ConversationKey target = executionKey(request.identity()).conversation();
        if (target.threadId().equals(seed.sourceIdentity().threadId())) {
            throw new IllegalArgumentException("initial context seed cannot reference its child conversation");
        }
        Optional<AgentStore.ConversationSnapshot> loaded = store.loadConversation(target);
        if (loaded.isEmpty()) {
            if (expectedThreadRevision != 0) {
                throw new AgentStore.StoreConflictException("seed conversation revision is not initialized");
            }
            return;
        }
        AgentStore.ConversationSnapshot snapshot = loaded.orElseThrow();
        if (snapshot.revision() != expectedThreadRevision || snapshot.latestExecutionSequence() != 0
            || snapshot.recordSequence() != 0) {
            throw new AgentStore.StoreConflictException("initial context seed requires an empty conversation");
        }
    }

    /**
     * 新准入沿用提交游标时已初始化的同一 Prompt runtime，后续状态推进由恢复入口共享。
     */
    public CompletionStage<AgentExecutionOutcome> execute(AgentExecutionRequest request, AgentStore.AdmissionReceipt admission,
                                                          AgentRequestRuntime runtime, ExecutionLease lease,
                                                          CancellationToken cancellation, Consumer<AgentEvent> afterCommit,
                                                          TurnEventSink streamSink) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(afterCommit, "afterCommit");
        Objects.requireNonNull(streamSink, "streamSink");
        return new AgentExecutionSession(this, request.identity(), request.history(), request.kind(),
                request.outputSchema(), runtime,
                AgentExecutionSession.PromptSource.ADMISSION, lease, cancellation, afterCommit, streamSink).run();
    }

    /**
     * 恢复使用准入时冻结的 history/context binding，并从 Store 游标重建当前 Prompt Skill 状态。
     */
    public CompletionStage<AgentExecutionOutcome> resume(AgentExecutionBinding binding, AgentRequestRuntime runtime,
                                                         ExecutionLease lease, CancellationToken cancellation,
                                                         Consumer<AgentEvent> afterCommit, TurnEventSink streamSink) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(afterCommit, "afterCommit");
        Objects.requireNonNull(streamSink, "streamSink");
        return new AgentExecutionSession(this, binding.identity(), binding.history(), binding.kind(),
                binding.outputSchema(), runtime,
                AgentExecutionSession.PromptSource.STORE, lease, cancellation, afterCommit, streamSink).run();
    }

    /**
     * 在任务已等待或执行者已退出时完成取消结算；该路径只读内核持久事实，不恢复 Provider、工具或业务扩展。
     * Store 装饰器会把 Ja 参与者合入同一事务；未知 Tool Started 保留 SUSPENDED，其余结算一次 CAS 提交。
     */
    public Optional<AgentStore.CommitReceipt> settleCancellation(AgentIdentity identity, ExecutionLease lease,
                                                                 CancellationToken cancellation, Consumer<AgentEvent> afterCommit) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(afterCommit, "afterCommit");
        AgentStore.ExecutionKey execution = executionKey(identity);
        AgentLoopPersistence.Restored restored = persistence.restore(identity, CancellationToken.none());
        TurnState prior = TurnState.valueOf(restored.execution().state());
        if (prior.terminal()) {
            return Optional.empty();
        }
        ExecutionStateView view = ExecutionStateView.forCancellation(restored, services());
        AgentStore.StoredRecord cancelRecord = view.latestRecord("agent.execution.cancel-request")
                .orElseThrow(() -> new AgentStore.StoreConflictException("cancel request is not durable"));
        ControlRequest cancel = controls.decodeCancelRequest(cancelRecord);
        TurnExecutionState cursor = cursors.read(restored.execution().cursorSchemaVersion(),
                restored.execution().cursor());
        Instant at = clock.instant();
        List<AgentStore.RecordWrite> writes = new ArrayList<>();
        List<ExecutionEvent> emitted = new ArrayList<>();
        TurnExecutionState next = cursor;
        String usageRecordId = null;
        boolean unknownTool = view.hasUnsettledTool();
        if (unknownTool && !(cursor instanceof TurnExecutionState.Tools)) {
            throw new AgentStore.StoreIntegrityException(
                    "unsettled Tool result is not represented by the active cursor", null);
        }

        if (cursor instanceof TurnExecutionState.ProviderPending pending) {
            ExecutionFacts.Usage usage = unknownUsage(pending);
            usageRecordId = recordId(execution, "agent.usage", "Usage:" + pending.modelRequestId());
            writes.add(records.encode(usageRecordId, usage, at));
            next = pending.resume().advanceProviderOrdinal();
        }
        if (!unknownTool && cursor instanceof TurnExecutionState.Tools tools) {
            AgentToolStep.RemainingSettlement settlement = AgentToolStep.settlement(identity, tools, view,
                    ToolOutcome.CANCELLED, "CANCELLED", "This Tool call was not executed because the turn was cancelled.");
            settlement.facts().forEach(fact -> writes.add(records.encode(fact.recordId(), fact.fact(), at)));
            emitted.addAll(settlement.events());
            next = settlement.nextCursor();
        }

        for (InteractionWait wait : view.waits()) {
            if (wait.status() != InteractionStatus.PENDING) {
                continue;
            }
            InteractionWait closed = wait.close(InteractionStatus.CANCELLED, at);
            String waitRecordId = recordId(execution, "agent.interaction.wait",
                    wait.interactionId() + ":interaction-revision:" + closed.interactionRevision());
            writes.add(controls.encodeWait(waitRecordId, closed, at));
            emitted.add(new ExecutionEvent.ControlRecorded(ExecutionEvent.ControlAction.WAIT_RESOLVED,
                    waitRecordId, cancel.operationKey(), "CANCELLED"));
        }

        activeCompaction(view).ifPresent(attempt -> {
            writes.add(compactionRecord(execution, attempt, false, at));
            emitted.add(new ExecutionEvent.ContextCompactionFailed(attempt.id(), attempt.sourceRevision(),
                    attempt.inputTokensBefore(), "CANCELLED"));
        });

        TurnState target = unknownTool ? TurnState.SUSPENDED : TurnState.CANCELLED;
        if (unknownTool && prior != TurnState.SUSPENDED) {
            String recordId = recordId(execution, ExecutionControlCodec.SUSPENSION_RECORD_TYPE,
                    Long.toString(restored.execution().mutationVersion()));
            writes.add(controls.encodeSuspension(recordId, "CANCELLED_WITH_UNKNOWN_TOOL_RESULT", at));
        }
        if (prior != target) {
            emitted.add(0, new ExecutionEvent.StateChanged(prior, target));
        }
        if (target == TurnState.CANCELLED) {
            emitted.add(new ExecutionEvent.Terminal(TurnState.CANCELLED, "", "CANCELLED",
                    "Execution cancelled", null, usageRecordId));
        }
        if (writes.isEmpty() && emitted.isEmpty()) {
            return Optional.empty();
        }

        AgentStore.Lease activeLease = lease.assertDispatchAuthority();
        List<AgentStore.EventWrite> eventWrites = new ArrayList<>(emitted.size());
        for (int index = 0; index < emitted.size(); index++) {
            String source = recordId(execution, "agent.event",
                    cancel.operationKey() + ":cancel-settlement:" + restored.execution().mutationVersion() + ":" + index);
            String eventId = "evt_" + source.substring("rec_".length());
            eventWrites.add(events.encode(eventId, emitted.get(index), at));
        }
        JsonObject nextCursor = cursors.write(next);
        String fingerprint = cancellationFingerprint(execution, restored, prior, target,
                nextCursor, writes, eventWrites, activeLease, at);
        AgentStore.ExecutionCommit commit = new AgentStore.ExecutionCommit(execution,
                restored.conversation().revision(), prior.name(), restored.execution().mutationVersion(),
                activeLease, cursors.schemaVersion(), target.name(), nextCursor, writes, eventWrites,
                new AgentStore.CommitIdentity(execution, "cancel_" + fingerprint, fingerprint), Optional.empty(), at);
        AgentStore.CommitReceipt receipt = persistence.commit(commit,
                io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant.NONE);
        persistence.publishCommitted(identity, receipt, afterCommit,
                failure -> System.getLogger(AgentExecutionDriver.class.getName()).log(
                        System.Logger.Level.WARNING, "Cancellation settlement observation failed", failure));
        return Optional.of(receipt);
    }

    /**
     * 为取消结算读取活动摘要标记；仅关闭本执行水位内最后一个活动标记，不按时间推断阶段。
     */
    private Optional<ExecutionRecordCodec.ContextCompaction> activeCompaction(ExecutionStateView view) {
        return view.latestRecord(ExecutionRecordCodec.CONTEXT_COMPACTION_RECORD_TYPE)
                .map(records::decodeContextCompaction).filter(ExecutionRecordCodec.ContextCompaction::active);
    }

    /**
     * 关闭摘要阶段时沿用 Session 的严格 schema 与稳定记录键，避免取消留下永久 active 标记。
     */
    private AgentStore.RecordWrite compactionRecord(AgentStore.ExecutionKey execution,
                                                    ExecutionRecordCodec.ContextCompaction attempt, boolean active, Instant at) {
        return records.encodeContextCompaction(recordId(execution, ExecutionRecordCodec.CONTEXT_COMPACTION_RECORD_TYPE,
                        attempt.id() + ":" + active),
                new ExecutionRecordCodec.ContextCompaction(attempt.id(), attempt.sourceRevision(),
                        attempt.inputTokensBefore(), active), at);
    }

    /**
     * 对完整结算载荷计算提交指纹，确保相同键无法把不同 wait、facts 或事件误认为同一事务。
     */
    private String cancellationFingerprint(AgentStore.ExecutionKey execution,
                                           AgentLoopPersistence.Restored restored, TurnState prior, TurnState target,
                                           JsonObject cursor, List<AgentStore.RecordWrite> writes,
                                           List<AgentStore.EventWrite> eventWrites, AgentStore.Lease lease, Instant at) {
        List<JsonValue> factValues = writes.stream().map(record -> (JsonValue) new JsonObject(Map.of(
                "recordId", new JsonText(record.recordId()),
                "recordType", new JsonText(record.recordType()),
                "payloadSchemaVersion", new JsonNumber(record.payloadSchemaVersion()),
                "payload", record.payload(),
                "occurredAt", new JsonText(record.occurredAt().toString())))).toList();
        List<JsonValue> eventValues = eventWrites.stream().map(event -> (JsonValue) new JsonObject(Map.of(
                "eventId", new JsonText(event.eventId()),
                "eventType", new JsonText(event.eventType()),
                "payloadSchemaVersion", new JsonNumber(event.payloadSchemaVersion()),
                "payload", event.payload(),
                "occurredAt", new JsonText(event.occurredAt().toString())))).toList();
        JsonObject body = new JsonObject(Map.ofEntries(
                Map.entry("tenantId", new JsonText(execution.conversation().tenantId())),
                Map.entry("threadId", new JsonText(execution.conversation().threadId())),
                Map.entry("turnId", new JsonText(execution.turnId())),
                Map.entry("threadRevision", new JsonNumber(restored.conversation().revision())),
                Map.entry("expectedState", new JsonText(prior.name())),
                Map.entry("expectedMutationVersion", new JsonNumber(restored.execution().mutationVersion())),
                Map.entry("fencingToken", new JsonNumber(lease.fencingToken())),
                Map.entry("leaseOwner", new JsonText(lease.ownerId())),
                Map.entry("leaseExpiresAt", new JsonText(lease.expiresAt().toString())),
                Map.entry("targetState", new JsonText(target.name())),
                Map.entry("cursorSchemaVersion", new JsonNumber(cursors.schemaVersion())),
                Map.entry("cursor", cursor),
                Map.entry("records", new JsonArray(factValues)),
                Map.entry("events", new JsonArray(eventValues)),
                Map.entry("occurredAt", new JsonText(at.toString()))));
        return Hashing.sha256Hex(json.encode(body));
    }

    /**
     * 仅供同包执行状态机使用的不可变依赖组；凭据、请求 runtime 与工具资源不进入此共享视图。
     *
     * @param store 存储端口
     * @param persistence 持久化边界
     * @param records 记录列表
     * @param cursors 游标集合
     * @param events 事件列表
     * @param mapper Agent 上下文的数据库映射器
     * @param toolArgumentValidator 工具参数校验器
     * @param controls 控制集合
     * @param json JSON 编解码器
     * @param extensionFacts 扩展事实列表
     * @param middlewareStates 中间件状态集合
     * @param clock 时钟
     * @param leaseDuration 租约持续时间
     */
    record Services(AgentStore store,
                    AgentLoopPersistence persistence,
                    ExecutionRecordCodec records,
                    ExecutionCursorCodec cursors,
                    ExecutionEventCodec events,
                    AgentContextMapper mapper,
                    ToolArgumentValidator toolArgumentValidator,
                    ExecutionControlCodec controls,
                    JsonValueCodec json,
                    ExtensionFactRecordCodec extensionFacts,
                    MiddlewareStateRecordCodec middlewareStates,
                    Clock clock,
                    Duration leaseDuration) {
        /**
         * 防止执行器绕开标准组合根以空 codec、替代 store 或无界租期启动。
         */
        Services {
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(persistence, "persistence");
            Objects.requireNonNull(records, "records");
            Objects.requireNonNull(cursors, "cursors");
            Objects.requireNonNull(events, "events");
            Objects.requireNonNull(mapper, "mapper");
            Objects.requireNonNull(toolArgumentValidator, "toolArgumentValidator");
            Objects.requireNonNull(controls, "controls");
            Objects.requireNonNull(json, "json");
            Objects.requireNonNull(extensionFacts, "extensionFacts");
            Objects.requireNonNull(middlewareStates, "middlewareStates");
            Objects.requireNonNull(clock, "clock");
            leaseDuration = positive(leaseDuration);
        }
    }
}
