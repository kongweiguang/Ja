// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.TurnEventSink;
import io.github.kongweiguang.ja.agent.application.api.TurnResult;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentCompactionReceipt;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionOutcome;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionStatus;
import io.github.kongweiguang.ja.agent.application.context.checkpoint.StoreBackedCheckpointStore;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.conversation.ContextInstruction;
import io.github.kongweiguang.ja.agent.domain.error.OutputSchemaException;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFact;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.domain.tool.ToolRecoveryDecision;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.AgentEvent;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentRecordIds;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.shared.Hashing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import io.github.kongweiguang.ja.agent.application.context.ContextOrchestrator.Execution;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue.Command;
import io.github.kongweiguang.ja.agent.domain.conversation.UserMessage;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState.Common;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState.Ready;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.CommitReceipt;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.EventPage;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.PersistedEvent;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.RecordWrite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一次持租约运行的唯一状态机；等待返回释放 owner，恢复始终重新读取已提交游标。
 */
final class AgentExecutionSession implements AgentModelStep.Host, AgentToolStep.Host {
    private static final Logger LOG = LoggerFactory.getLogger(AgentExecutionSession.class);

    /** 执行来源决定复用准入时的 Prompt 状态，还是从 Store 游标重建。 */
    enum PromptSource {
        /** 复用准入阶段已经固定的 Prompt 状态。 */
        ADMISSION,
        /** 根据已提交的 Store 游标重新建立 Prompt 状态。 */
        STORE
    }

    private final AgentExecutionDriver.Services services;
    private final AgentIdentity identity;
    private final AgentExecutionRequest.HistorySelection historySelection;
    private final AgentExecutionRequest.AdmissionKind kind;
    private final Optional<JsonObject> outputSchema;
    private final PromptSource promptSource;
    private final AgentRequestRuntime runtime;
    private final ExecutionLease lease;
    private final CancellationToken cancellation;
    private final Consumer<AgentEvent> listener;
    private final TurnEventSink stream;
    private final AgentStore.ExecutionKey execution;
    private final AtomicLong streamSequence;
    private final StoreBackedCheckpointStore checkpointRecords;
    private final CheckpointStore checkpoints;
    private final AgentSummaryOperation summaryOperation;
    private final AgentModelStep modelStep;
    private AgentLoopPersistence.Restored restored;
    private ExecutionStateView view;
    private TurnExecutionState cursor;
    private AgentExecutionOutcome outcome;

    /**
     * 绑定检查点前先恢复权威水位，避免初始化回调读取空快照；ADMISSION 沿用准入 runtime，STORE 从游标恢复。
     */
    AgentExecutionSession(AgentExecutionDriver driver, AgentIdentity identity,
                          AgentExecutionRequest.HistorySelection historySelection, AgentExecutionRequest.AdmissionKind kind,
                          Optional<JsonObject> outputSchema,
                          AgentRequestRuntime runtime, PromptSource promptSource,
                          ExecutionLease lease, CancellationToken cancellation, Consumer<AgentEvent> listener,
                          TurnEventSink stream) {
        this.services = Objects.requireNonNull(driver, "driver").services();
        this.identity = Objects.requireNonNull(identity, "identity");
        this.historySelection = Objects.requireNonNull(historySelection, "historySelection");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.outputSchema = Objects.requireNonNull(outputSchema, "outputSchema");
        this.promptSource = Objects.requireNonNull(promptSource, "promptSource");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.stream = Objects.requireNonNull(stream, "stream");
        execution = AgentExecutionDriver.executionKey(identity);
        streamSequence = new AtomicLong(Math.multiplyExact(lease.current().fencingToken(), 1L << 32));
        refresh();
        checkpointRecords = new StoreBackedCheckpointStore(services.store(), services.records());
        checkpoints = checkpointRecords.bind(execution,
                new StoreBackedCheckpointStore.Committer() {
                    /** 返回本执行最后确认水位，不读取当前数据库头来掩盖并发写入。 */
                    @Override
                    public AgentStore.ConversationSnapshot snapshot() {
                        return restored.conversation();
                    }

                    /** 投影与现有游标同事务提交，只有确认后 Context 才可把它作为冻结选择。 */
                    @Override
                    public AgentStore.CommitReceipt commitProjection(long expectedRevision,
                                                                     List<AgentStore.RecordWrite> records) {
                        requireContextRevision(expectedRevision);
                        return commit(TurnState.RUNNING, cursor, records, List.of());
                    }

                    /** 检查点、摘要完成游标与事件共享同一提交，不存在摘要已写而游标仍待请求的窗口。 */
                    @Override
                    public CheckpointStore.CommittedCheckpoint commitCheckpoint(
                            CheckpointStore.CommitRequest request, AgentStore.RecordWrite record) {
                        requireContextRevision(request.expectedThreadRevision());
                        TurnExecutionState next = request.turnOperation().<TurnExecutionState>map(operation -> {
                            if (!operation.turnId().equals(identity.turnId())
                                || operation.expectedTurnMutationVersion() != restored.execution().mutationVersion()) {
                                throw new CheckpointStore.CommitConflict("checkpoint execution changed");
                            }
                            return operation.completedExecution();
                        }).orElseThrow(() -> new IllegalArgumentException("checkpoint requires an execution cursor"));
                        ExecutionRecordCodec.ContextCompaction attempt = compactionAttempt()
                                .filter(ExecutionRecordCodec.ContextCompaction::active).orElseThrow(
                                        () -> new AgentStore.StoreIntegrityException("checkpoint has no compaction start", null));
                        CommitReceipt receipt = commit(TurnState.RUNNING, next,
                                List.of(record, compactionRecord(attempt, false)), List.of(new ExecutionEvent.ContextCompacted(
                                        attempt.id(), ExecutionEvent.CompactionOutcome.COMPACTED,
                                        request.checkpoint().checkpointId(), record.recordId(),
                                        attempt.sourceRevision(), Math.addExact(restored.conversation().revision(), 1),
                                        attempt.inputTokensBefore(), request.checkpoint().estimatedTokens())));
                        return CheckpointStore.CommittedCheckpoint.created(request.checkpoint(),
                                receipt.threadRevision(), receipt.mutationVersion());
                    }
                });
        summaryOperation = new AgentSummaryOperation(this);
        modelStep = new AgentModelStep(this);
    }

    /**
     * 控制属性必须显式为文本；缺值不自动映射成审批或授权。
     */
    private static String textAttribute(JsonObject value, String key) {
        return value.get(key) instanceof io.github.kongweiguang.ja.agent.domain.json.JsonText(String value1) ? value1 : null;
    }

    /**
     * 异步包装不能改变持久化故障或取消的分类，否则会把结果未知误报成普通扩展失败。
     */
    private static RuntimeException unwrapStepFailure(RuntimeException failure) {
        while (failure instanceof java.util.concurrent.CompletionException
               && failure.getCause() instanceof RuntimeException cause) {
                   failure = cause;
               }
        return failure;
    }

    /**
     * 虚拟线程只承载当前会话的顺序工作，不占用全局公共 ForkJoin 池等待网络或业务工具。
     */
    CompletionStage<AgentExecutionOutcome> run() {
        CompletableFuture<AgentExecutionOutcome> completion = new CompletableFuture<>();
        Thread.ofVirtual().name("agent-execution-" + identity.turnId()).start(() -> {
            try {
                completion.complete(drive());
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        });
        return completion;
    }

    /**
     * 工具/Provider 恢复严格读取请求实际采用的摘要；新 Ready 请求才允许选 latest。
     * ADMISSION 复用准入时已固定的 Prompt 状态，STORE 从游标恢复一次；请求准备仅校验 Skill 身份。
     * 持久暂停优先于仅有的本地取消信号并保留当前游标，显式持久取消仍按终态处理。
     */
    private AgentExecutionOutcome drive() {
        refresh();
        boolean promptInitialized = promptSource == PromptSource.ADMISSION;
        while (outcome == null) {
            TurnState state = TurnState.valueOf(restored.execution().state());
            if (state.terminal()) {
                return readTerminal();
            }
            if (view.suspensionRequested() && !view.cancellationRequested()) {
                suspend();
                break;
            }
            if (cancellation.isCancellationRequested() || view.cancellationRequested()) {
                if (cursor instanceof TurnExecutionState.ProviderPending pending) {
                    recoverProvider(pending);
                }
                if (view.hasUnsettledTool()) {
                    suspend();
                    break;
                }
                terminate(TurnState.CANCELLED, "", "CANCELLED", "Execution cancelled");
                break;
            }
            if (state == TurnState.SUSPENDED) {
                if (!commitStartupState(TurnState.QUEUED)) {
                    continue;
                }
                state = TurnState.QUEUED;
            }
            if (state != TurnState.RUNNING && state != TurnState.WAITING_INPUT
                && state != TurnState.WAITING_APPROVAL) {
                if (!commitStartupState(TurnState.RUNNING)) {
                    continue;
                }
                state = TurnState.RUNNING;
            }
            if (view.waits().stream().anyMatch(wait -> wait.status() == InteractionStatus.PENDING)) {
                InteractionWait wait = view.waits().stream().filter(value -> value.status() == InteractionStatus.PENDING)
                        .findFirst().orElseThrow();
                TurnState waiting = "approval".equals(textAttribute(wait.attributes(), "kind"))
                        ? TurnState.WAITING_APPROVAL : TurnState.WAITING_INPUT;
                if (state != waiting) {
                    commit(waiting, cursor, List.of(), List.of());
                }
                return suspended(Optional.of(wait.interactionId()));
            }
            if (state != TurnState.RUNNING) {
                commit(TurnState.RUNNING, cursor, List.of(), List.of());
            }
            try {
                if (!promptInitialized) {
                    String summary = promptSummaryForCursor();
                    runtime.promptSession().restoreActiveSkills(summary, cursor.common().activeSkills());
                    promptInitialized = true;
                }
                switch (cursor) {
                    case TurnExecutionState.ProviderPending pending -> recoverProvider(pending);
                    case TurnExecutionState.Tools ignored -> AgentToolStep.step(this).toCompletableFuture().join();
                    case TurnExecutionState.Ready ready -> {
                        if (kind == AgentExecutionRequest.AdmissionKind.COMPACTION) {
                            compactManually();
                            continue;
                        }
                        if (consumeInput(ready.next() == TurnExecutionState.Next.COMPLETION
                                ? AgentInputQueue.SafePoint.MODEL_STOPPED : AgentInputQueue.SafePoint.MODEL_COMPLETED)) {
                            continue;
                        }
                        if (ready.next() == TurnExecutionState.Next.COMPLETION) {
                            completeCandidate();
                        }
                        else {
                            modelStep.advance();
                        }
                    }
                }
            } catch (AgentStore.StoreConflictException conflict) {
                lease.assertDispatchAuthority();
                refresh();
            } catch (AgentLoopPersistence.UncertainPersistenceException | AgentStore.StoreUnavailableException
                     | AgentStore.StoreIntegrityException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                failure = unwrapStepFailure(failure);
                if (failure instanceof AgentStore.StoreConflictException) {
                    lease.assertDispatchAuthority();
                    refresh();
                    continue;
                }
                if (failure instanceof AgentLoopPersistence.UncertainPersistenceException
                    || failure instanceof AgentStore.StoreUnavailableException
                    || failure instanceof AgentStore.StoreIntegrityException) {
                        throw failure;
                }
                if (!(failure instanceof java.util.concurrent.CancellationException)) {
                    LOG.error("Agent step failed: {}; at {}", failure.getClass().getName(),
                            java.util.Arrays.toString(failure.getStackTrace()));
                }
                refresh();
                if (view.suspensionRequested() && !view.cancellationRequested()) {
                    suspend();
                } else if (view.hasUnsettledTool()) {
                    suspend();
                }
                else {
                    if (cursor instanceof TurnExecutionState.ProviderPending pending) {
                        recoverProvider(pending);
                    }
                    OutputSchemaException outputSchemaFailure = failure
                            instanceof OutputSchemaException value ? value : null;
                    String code = outputSchemaFailure != null ? outputSchemaFailure.code()
                            : failure instanceof io.github.kongweiguang.ja.agent.application.context.ContextException context
                                ? context.code().name() : failure instanceof ModelPort.ModelUnavailableException provider
                                                          ? provider.terminalErrorCode() : "EXTENSION_FAILURE";
                    if (failure instanceof java.util.concurrent.CancellationException) {
                        terminate(TurnState.CANCELLED, "", "CANCELLED", "Execution cancelled");
                    } else {
                        String message = outputSchemaFailure != null ? outputSchemaFailure.getMessage()
                                : failure instanceof ModelPort.ModelUnavailableException
                                    ? "Model request preparation failed" : "Execution step failed";
                        terminate(TurnState.FAILED, "", code, message);
                    }
                }
            }
            if (outcome == null) {
                refresh();
            }
        }
        return outcome;
    }

    /**
     * 恢复启动阶段尚未派发任何外部动作，只有 Store 水位确已推进时才重读并回到控制检查循环。
     * 不用派发权限检查覆盖已持久化的暂停/取消；无推进冲突仍原样失败，后续派发继续执行原租约核验。
     *
     * @author kongweiguang
     */
    private boolean commitStartupState(TurnState target) {
        AgentLoopPersistence.Restored before = restored;
        try {
            commit(target, cursor, List.of(), List.of());
            return true;
        } catch (AgentStore.StoreConflictException conflict) {
            lease.current();
            refresh();
            boolean conversationAdvanced = restored.conversation().revision() > before.conversation().revision();
            boolean executionAdvanced = restored.execution().mutationVersion() > before.execution().mutationVersion();
            if (!conversationAdvanced && !executionAdvanced) {
                throw conflict;
            }
            return false;
        }
    }

    /**
     * 用模型请求同事务提交的能力事实解析摘要引用；ProviderPending 的摘要请求仍走自身恢复协议。
     */
    private String promptSummaryForCursor() {
        String messageId = switch (cursor) {
            case TurnExecutionState.Tools tools -> tools.assistantMessageId();
            case TurnExecutionState.ProviderPending pending
                    when pending.purpose() == TurnExecutionState.ProviderPurpose.ASSISTANT -> pending.messageId();
            case TurnExecutionState.Ready ready -> latestToolRequestMessageId(ready.common().modelRound()).orElse(null);
            default -> null;
        };
        if (messageId == null) {
            return "";
        }
        try {
            ExecutionRecordCodec.ModelCapabilities capabilities = view.modelCapabilities(messageId, services);
            if (capabilities.checkpointId().isPresent()) {
                return checkpointRecords.readCheckpoint(restored.conversation(),
                        capabilities.checkpointId().orElseThrow()).summary().toPromptText();
            }
            if (capabilities.seedSummaryRef().isPresent()) {
                ExecutionRecordCodec.SeedSummaryReference reference = capabilities.seedSummaryRef().orElseThrow();
                AgentLoopPersistence.ConversationHistory history = new AgentLoopPersistence.ConversationHistory(restored.conversation(),
                        restored.executions(), restored.records());
                InitialContextSeed seed = services.mapper().initialContextSeed(history, identity).orElseThrow(
                        () -> new AgentStore.StoreIntegrityException("referenced initial seed is unavailable", null));
                if (!seed.sourceIdentity().equals(reference.sourceIdentity())
                    || seed.sourceOrdinal() != reference.sourceOrdinal()) {
                    throw new AgentStore.StoreIntegrityException("initial seed reference does not match", null);
                }
                return seed.summary().orElseThrow(() -> new AgentStore.StoreIntegrityException(
                        "referenced initial seed summary is unavailable", null)).toPromptText();
            }
            return "";
        } catch (AgentStore.StoreIntegrityException invalidReference) {
            throw new io.github.kongweiguang.ja.agent.application.context.ContextException(
                    io.github.kongweiguang.ja.agent.application.context.ContextException.Code.INVALID_STATE,
                    "model request checkpoint reference is unavailable", invalidReference);
        }
    }

    /**
     * 同一工具批次的模型续答沿用原请求 checkpoint，避免并发执行的摘要切分污染旧历史路径。
     */
    private Optional<String> latestToolRequestMessageId(int modelRound) {
        return view.latest(ExecutionFacts.AssistantMessage.class, ignored -> true)
                .filter(assistant -> assistant.modelRound() == modelRound && assistant.message().content().stream()
                        .anyMatch(ToolCallContent.class::isInstance))
                .map(ExecutionFacts.AssistantMessage::messageId);
    }

    /**
     * 恢复取得同一水位的记录、游标和中间件状态，不把当前数据库头拼到旧模型历史。
     */
    private void refresh() {
        restored = services.persistence().restore(identity, CancellationToken.none());
        cursor = services.cursors().read(restored.execution().cursorSchemaVersion(), restored.execution().cursor());
        view = new ExecutionStateView(restored, services, runtime.extensions().middleware());
    }

    /**
     * 提交使用已读快照的双版本和最新租约；监听失败经宿主 SLF4J 配置记录类型与栈帧，不记录异常正文且不改写提交结果。
     */
    @Override
    public AgentStore.CommitReceipt commit(TurnState target, TurnExecutionState next,
                                           List<AgentStore.RecordWrite> records, List<ExecutionEvent> events) {
        while (true) {
            Instant at = services.clock().instant();
            List<ExecutionEvent> emitted = new ArrayList<>(events);
            TurnState prior = TurnState.valueOf(restored.execution().state());
            if (prior != target) {
                emitted.addFirst(new ExecutionEvent.StateChanged(prior, target));
            }
            JsonObject nextCursor = services.cursors().write(next);
            List<AgentStore.EventWrite> eventWrites = new ArrayList<>();
            for (int index = 0; index < emitted.size(); index++) {
                eventWrites.add(services.events().encode(id("evt", "event:" + restored.execution().mutationVersion()
                                                                   + ":" + index), emitted.get(index), at));
            }
            String fingerprint = hash(restored.conversation().revision() + "\0" + restored.execution().mutationVersion()
                                      + "\0" + prior + "\0" + target + "\0" + nextCursor + "\0" + records + "\0" + eventWrites);
            AgentStore.ExecutionCommit request = new AgentStore.ExecutionCommit(execution,
                    restored.conversation().revision(), prior.name(), restored.execution().mutationVersion(),
                    lease.current(), services.cursors().schemaVersion(), target.name(), nextCursor,
                    records, eventWrites, new AgentStore.CommitIdentity(execution, "commit_" + fingerprint,
                    fingerprint), Optional.empty(), at);
            AgentStore.CommitReceipt receipt;
            try {
                receipt = services.persistence().commit(request, runtime.transactionParticipant());
            } catch (AgentStore.StoreConflictException conflict) {
                boolean settledExternalCall = records.stream().anyMatch(record ->
                        record.recordType().equals("agent.tool.result"))
                        || cursor instanceof TurnExecutionState.ProviderPending && !cursor.equals(next);
                if (!settledExternalCall || target.terminal()) {
                    throw conflict;
                }
                JsonObject expectedCursor = restored.execution().cursor();
                String expectedState = restored.execution().state();
                refresh();
                if (!expectedCursor.equals(restored.execution().cursor())
                    || !expectedState.equals(restored.execution().state())) {
                        throw conflict;
                    }
                lease.assertDispatchAuthority();
                continue;
            }
            services.persistence().publishCommitted(identity, receipt, listener,
                    failure -> LOG.warn("Committed Agent event observation failed: {}; at {}",
                            failure.getClass().getName(), java.util.Arrays.toString(failure.getStackTrace())));
            refresh();
            return receipt;
        }
    }

    /**
     * 未结算助手请求已有派发前估算，不重复写 UNKNOWN；Summary 没有该估算事实，仍需补用量审计。
     */
    private void recoverProvider(TurnExecutionState.ProviderPending pending) {
        List<RecordWrite> records = pending.purpose() == TurnExecutionState.ProviderPurpose.ASSISTANT
            ? List.of() : List.of(fact(AgentExecutionDriver.unknownUsage(pending), pending.modelRequestId()));
        commit(TurnState.RUNNING, pending.resume().advanceProviderOrdinal(), records, List.of());
    }

    /**
     * 安全点消费正文与队列版本；用户输入替换 Skill，外部消息仅补充上下文并保留用户原有选择。
     */
    private boolean consumeInput(AgentInputQueue.SafePoint safePoint) {
        Optional<AgentInputQueue.Entry> candidate = view.queue().next(safePoint);
        if (candidate.isEmpty()) {
            return false;
        }
        AgentInputQueue.Entry entry = candidate.orElseThrow();
        if (entry.status() == AgentInputQueue.Status.NEEDS_ATTENTION) {
            suspend();
            return true;
        }
        UserMessage input = services.records().decodeQueuedUser(view.record(entry.messageRecordId()));
        try {
            for (String attachmentId : input.content().attachmentIds()) {
                runtime.attachments().inspect(attachmentId, identity.threadId());
            }
        } catch (io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader.ReadFailure unavailable) {
            markInputNeedsAttention(entry, "ATTACHMENT_UNAVAILABLE", "附件当前无法读取，请修正后重试");
            return true;
        }
        Optional<AgentPromptSession.SkillReplacement> replacement = Optional.empty();
        try {
            if (entry.kind() != AgentInputQueue.Kind.MESSAGE) {
                replacement = Optional.of(runtime.promptSession().prepareSkillReplacement(input.content().blocks().stream()
                        .filter(SkillReferenceContent.class::isInstance).map(SkillReferenceContent.class::cast)
                        .map(SkillReferenceContent::skillId).toList()));
            }
        } catch (java.util.concurrent.CancellationException | AgentStore.StoreUnavailableException
                 | AgentStore.StoreIntegrityException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            markInputNeedsAttention(entry, "SKILL_LOAD_FAILED", "所选 Skill 当前无法读取，请修正后重试");
            return true;
        }
        Instant at = services.clock().instant();
        Command command = new AgentInputQueue.Command(id("input", "consume:" + view.queue().revision()),
                AgentInputQueue.Action.CONSUME, entry.inputId(), null, entry.inputRevision(),
                Math.addExact(view.queue().revision(), 1), null, null, at, Map.of());
        Common common = cursor.common();
        Common nextCommon = replacement.map(value -> new TurnExecutionState.Common(common.modelRound(),
                common.usedToolCalls(), common.nextProviderOrdinal(), value.promptRevision(),
                value.activeSkillReferences())).orElse(common);
        Ready next = new TurnExecutionState.Ready(nextCommon, TurnExecutionState.Next.ASSISTANT, null);
        commit(TurnState.RUNNING, next, List.of(
                        services.records().encodeUser(recordId("input-consumed", input.messageId()), input.messageId(), input.content(), at),
                        services.controls().encodeInputQueueCommand(command.commandId(), command, at)),
                List.of(new ExecutionEvent.InputAccepted(input.messageId())));
        replacement.ifPresent(AgentPromptSession.SkillReplacement::commit);
        return true;
    }

    /**
     * 输入引用无法加载时保留原正文和修复入口，不把可修正输入消费掉或错误终结整个任务。
     */
    private void markInputNeedsAttention(AgentInputQueue.Entry entry, String code, String message) {
        Instant at = services.clock().instant();
        Command command = new AgentInputQueue.Command(id("input", "attention:" + view.queue().revision()),
                AgentInputQueue.Action.NEEDS_ATTENTION, entry.inputId(), null, entry.inputRevision(),
                Math.addExact(view.queue().revision(), 1), null,
                new AgentInputQueue.Issue(code, message, true),
                at, Map.of());
        RecordWrite write = services.controls().encodeInputQueueCommand(command.commandId(), command, at);
        commit(TurnState.SUSPENDED, cursor, List.of(write), List.of(new ExecutionEvent.ControlRecorded(
                ExecutionEvent.ControlAction.INPUT_CHANGED, write.recordId(), command.commandId(),
                code)));
        outcome = suspended(Optional.empty());
    }

    /**
     * 先冻结全部完成决策；状态随实际分支提交，候选 facts 只随唯一终态一同落库。
     */
    private void completeCandidate() {
        ExecutionFacts.AssistantMessage assistant = view.latest(ExecutionFacts.AssistantMessage.class,
                value -> value.modelRound() == cursor.common().modelRound()).orElseThrow(
                () -> new AgentStore.StoreIntegrityException("completion candidate has no assistant fact", null));
        List<String> instructions = new ArrayList<>();
        List<CompletionHookDecision> decisions = new ArrayList<>();
        AgentMiddleware.WaitRequest wait = null;
        boolean hasUnverifiedToolEffects = hasUnverifiedToolEffects();
        for (ExtensionRegistry.MiddlewareRegistration registration : runtime.extensions().middleware()) {
            AgentMiddleware.ExtensionStateView current = view.middleware().get(registration.namespace());
            AgentMiddleware.ExtensionDecision<AgentMiddleware.CompletionDecision> decision = Objects.requireNonNull(
                    registration.middleware().beforeCompletion(new AgentMiddleware.CompletionContext(identity,
                            assistant.publicText(), false, hasUnverifiedToolEffects, current)),
                    "completion extension decision");
            decisions.add(new CompletionHookDecision(registration, decision));
            switch (decision.decision()) {
                case AgentMiddleware.Complete ignored -> {
                }
                case AgentMiddleware.Continue next -> instructions.add(next.instruction());
                case AgentMiddleware.Await waiting -> {
                    if (wait != null) {
                        throw new IllegalStateException("multiple completion waits conflict");
                    }
                    wait = waiting.request();
                }
            }
        }
        if (wait != null && !instructions.isEmpty()) {
            throw new IllegalStateException("completion continue and wait decisions conflict");
        }
        String boundary = "completion:" + restored.execution().mutationVersion();
        List<AgentStore.RecordWrite> writes = new ArrayList<>();
        for (CompletionHookDecision hook : decisions) {
            appendMiddlewareStatePatch(writes, hook.registration(), hook.decision().statePatch(), boundary);
        }
        if (wait != null) {
            openWait(wait, null, writes);
        } else if (!instructions.isEmpty()) {
            String messageId = id("msg", "continue:" + restored.execution().mutationVersion());
            writes.add(services.records().encodeInstruction(recordId("instruction", messageId),
                    new ContextInstruction(messageId, String.join("\n", instructions)), services.clock().instant()));
            commit(TurnState.RUNNING, new TurnExecutionState.Ready(cursor.common(),
                    TurnExecutionState.Next.ASSISTANT, null), writes, List.of());
        } else {
            for (CompletionHookDecision hook : decisions) {
                appendMiddlewareFacts(writes, hook.registration(), hook.decision().facts(), boundary);
            }
            ExecutionFacts.Usage usage = view.latest(ExecutionFacts.Usage.class, value ->
                    value.purpose() == ExecutionFacts.UsagePurpose.ASSISTANT
                    && value.modelRound() == assistant.modelRound()).orElseThrow(
                    () -> new AgentStore.StoreIntegrityException("completion candidate has no usage fact", null));
            commit(TurnState.COMPLETED, cursor, writes, List.of(new ExecutionEvent.Terminal(
                    TurnState.COMPLETED, assistant.publicText(), null, null,
                    view.recordFor(assistant).recordId(), view.recordFor(usage).recordId())));
            outcome = new AgentExecutionOutcome.Completed(new TurnResult(TurnState.COMPLETED,
                    assistant.publicText(), null, null));
        }
    }

    /**
     * 汇总当前执行的最新恢复裁决；Pending 与 Skipped 均保留尚未核验的外部结果。
     */
    private boolean hasUnverifiedToolEffects() {
        Map<String, ToolRecoveryDecision> latest = new LinkedHashMap<>();
        for (AgentStore.ConversationRecord row : restored.records()) {
            if (!row.execution().equals(execution)
                || !row.recordType().equals("agent.tool.recovery.decision")) {
                    continue;
                }
            ToolRecoveryDecision decision = services.controls().decodeToolRecoveryDecision(
                    ExecutionStateView.stored(row));
            ToolRecoveryDecision previous = latest.put(decision.recoveryId(), decision);
            if ((previous == null && decision.revision() != 1)
                || (previous != null && (!previous.callId().equals(decision.callId())
                                         || decision.revision() != Math.addExact(previous.revision(), 1)))) {
                throw new AgentStore.StoreIntegrityException("Tool recovery decision history is not contiguous", null);
            }
        }
        return latest.values().stream().anyMatch(decision ->
                decision.status() == ToolRecoveryDecision.Status.PENDING
                || decision.status() == ToolRecoveryDecision.Status.SKIPPED);
    }

    /**
     * 中间件事实只能使用冻结注册的 namespace；补丁基于同一水位，禁止隐式跳版本。
     */
    @Override
    public void appendMiddleware(List<AgentStore.RecordWrite> writes,
                                 ExtensionRegistry.MiddlewareRegistration registration,
                                 AgentMiddleware.ExtensionDecision<?> decision, String boundary) {
        appendMiddlewareStatePatch(writes, registration, decision.statePatch(), boundary);
        appendMiddlewareFacts(writes, registration, decision.facts(), boundary);
    }

    /**
     * 只将本命名空间状态 patch 编入实际分支事务，空 patch 不推进状态修订。
     */
    private void appendMiddlewareStatePatch(List<AgentStore.RecordWrite> writes,
                                            ExtensionRegistry.MiddlewareRegistration registration,
                                            AgentMiddleware.ExtensionStatePatch patch, String boundary) {
        AgentMiddleware.ExtensionStateView current = view.middleware().get(registration.namespace());
        services.middlewareStates().encodePatch(recordId("middleware-state", boundary + registration.namespace()),
                registration, current, patch, services.clock().instant()).ifPresent(writes::add);
    }

    /**
     * 事实由 Engine 绑定 namespace；完成候选调用方仅在所有策略接受 Complete 后调用。
     */
    private void appendMiddlewareFacts(List<AgentStore.RecordWrite> writes,
                                       ExtensionRegistry.MiddlewareRegistration registration,
                                       List<AgentMiddleware.MiddlewareFact> facts, String boundary) {
        for (int index = 0; index < facts.size(); index++) {
            AgentMiddleware.MiddlewareFact value = facts.get(index);
            writes.add(services.extensionFacts().encode(recordId("middleware-fact",
                            boundary + registration.namespace() + ":" + index), new AgentTool.ExtensionFact(
                            registration.namespace(), value.type(), value.schemaVersion(), value.payload()),
                    services.clock().instant()));
        }
    }

    /**
     * 问题与准备事实同事务提交；后续授权只从持久 wait 的 typed 身份恢复，不复用属性副本。
     */
    @Override
    public void openWait(AgentMiddleware.WaitRequest request, String callId,
                         List<AgentStore.RecordWrite> prepared) {
        Instant at = services.clock().instant();
        String interactionId = id(request.kind().equals("approval") ? "appr" : "interaction",
                "wait:" + restored.execution().mutationVersion() + ":" + Objects.toString(callId, "completion"));
        InteractionWait wait = AgentWaitRequests.create(interactionId, callId, request, at);
        List<AgentStore.RecordWrite> writes = new ArrayList<>(prepared);
        RecordWrite waitRecord = services.controls().encodeWait(recordId("interaction.wait", wait.interactionId()), wait, at);
        writes.add(waitRecord);
        if (request.kind().equals("approval")) {
            String toolCallId = Objects.requireNonNull(wait.toolCallId(), "approval Tool call identity");
            writes.add(fact(new ExecutionFacts.Approval(wait.interactionId(), toolCallId, null,
                    Instant.parse(textAttribute(wait.attributes(), "expiresAt"))), wait.interactionId()));
        }
        TurnState target = request.kind().equals("approval") ? TurnState.WAITING_APPROVAL : TurnState.WAITING_INPUT;
        commit(target, cursor, writes, List.of(new ExecutionEvent.WaitRequested(
                wait.interactionId(), request.kind(), waitRecord.recordId())));
        outcome = suspended(Optional.of(wait.interactionId()));
    }

    /**
     * 挂起返回的是当前 owner 的结束，不代表任务终态或业务动作回滚。
     */
    private void suspend() {
        if (!TurnState.SUSPENDED.name().equals(restored.execution().state())) {
            commit(TurnState.SUSPENDED, cursor, List.of(), List.of());
        }
        outcome = suspended(Optional.empty());
    }

    /**
     * 剩余调用结果、等待关闭和终态同事务提交，避免崩溃后丢失收口意图；存储失败不能冒充已取消。
     */
    private void terminate(TurnState state, String summary, String code, String message) {
        List<AgentStore.RecordWrite> records = new ArrayList<>();
        List<ExecutionEvent> events = new ArrayList<>();
        TurnExecutionState terminalCursor = cursor;
        if (cursor instanceof TurnExecutionState.Tools tools) {
            if (state == TurnState.COMPLETED) {
                throw new AgentStore.StoreIntegrityException("execution cannot complete with pending tools", null);
            }
            AgentToolStep.RemainingSettlement settlement = AgentToolStep.settlement(identity, tools, view,
                    state == TurnState.CANCELLED
                            ? io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome.CANCELLED
                            : io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome.FAILED,
                    code, message);
            Instant at = services.clock().instant();
            for (AgentToolStep.FactWrite fact : settlement.facts()) {
                records.add(services.records().encode(fact.recordId(), fact.fact(), at));
            }
            events.addAll(settlement.events());
            terminalCursor = settlement.nextCursor();
        }
        for (InteractionWait wait : view.waits()) {
            if (wait.status() != InteractionStatus.PENDING) {
                continue;
            }
            if (state == TurnState.COMPLETED) {
                throw new AgentStore.StoreIntegrityException("execution cannot complete with a pending wait", null);
            }
            Instant at = services.clock().instant();
            InteractionWait closed = wait.close(InteractionStatus.CANCELLED, at);
            String recordId = recordId("interaction.wait.closed",
                    wait.interactionId() + ":" + closed.interactionRevision());
            records.add(services.controls().encodeWait(recordId, closed, at));
            events.add(new ExecutionEvent.ControlRecorded(ExecutionEvent.ControlAction.WAIT_RESOLVED,
                    recordId, id("op", "close-wait:" + wait.interactionId() + ":"
                            + closed.interactionRevision()), "CANCELLED"));
        }
        compactionAttempt().filter(ExecutionRecordCodec.ContextCompaction::active).ifPresent(attempt -> {
            if (state == TurnState.COMPLETED) {
                throw new IllegalStateException("compaction has not settled");
            }
            records.add(compactionRecord(attempt, false));
            events.add(new ExecutionEvent.ContextCompactionFailed(attempt.id(), attempt.sourceRevision(),
                    attempt.inputTokensBefore(), Objects.requireNonNullElse(code, "EXECUTION_FAILED")));
        });
        events.add(new ExecutionEvent.Terminal(state, summary, code, message, null, null));
        commit(state, terminalCursor, records, events);
        outcome = new AgentExecutionOutcome.Completed(new TurnResult(state, summary, code, message));
    }

    /**
     * 挂起句柄携带数据库真实水位供调用者重连，不能用内存状态声称已完成等待提交。
     */
    private AgentExecutionOutcome suspended(Optional<String> interactionId) {
        return new AgentExecutionOutcome.Suspended(new AgentExecutionStatus(identity, restored.execution().state(),
                restored.conversation().revision(), restored.execution().mutationVersion(),
                restored.execution().updatedAt()), interactionId);
    }

    /**
     * 已终态的重复运行读取原终态事件，禁止创建第二份终态或猜测失败文案。
     */
    private AgentExecutionOutcome readTerminal() {
        long after = 0;
        ExecutionEvent.Terminal terminal = null;
        long through = restored.conversation().eventSequence();
        while (after < through) {
            EventPage page = services.store().readEvents(AgentStore.EventQuery.withDefaultMaxBytes(
                    execution.conversation(), after, through, 256));
            if (page.events().isEmpty() || page.nextSequence() <= after) {
                throw new AgentStore.StoreIntegrityException("terminal event stream has a gap", null);
            }
            for (PersistedEvent event : page.events()) {
                if (event.execution().equals(execution)
                    && services.events().decode(event) instanceof ExecutionEvent.Terminal value) {
                        terminal = value;
                    }
            }
            after = page.nextSequence();
        }
        if (terminal == null) {
            throw new AgentStore.StoreIntegrityException("terminal receipt is missing", null);
        }
        if (kind == AgentExecutionRequest.AdmissionKind.COMPACTION && terminal.state() == TurnState.COMPLETED) {
            return new AgentExecutionOutcome.Compacted(compactionReceipt().orElseThrow(
                    () -> new AgentStore.StoreIntegrityException("compaction receipt is missing", null)));
        }
        return new AgentExecutionOutcome.Completed(new TurnResult(terminal.state(), terminal.summary(),
                terminal.errorCode(), terminal.errorMessage()));
    }

    /**
     * 手动操作先复用已提交回执；检查点后进程退出时不会再次请求模型生成摘要。
     */
    private void compactManually() {
        Optional<AgentCompactionReceipt> receipt = compactionReceipt();
        if (receipt.isEmpty()) {
            long sourceRevision = restored.conversation().revision();
            Execution<Void> compacted = modelStep.compact();
            receipt = compactionReceipt();
            if (receipt.isEmpty()) {
                long tokens = compacted.prompt().estimatedTokens();
                String compactionId = id("cmp", "unchanged:" + sourceRevision);
                commit(TurnState.RUNNING, cursor, List.of(), List.of(new ExecutionEvent.ContextCompacted(
                        compactionId, ExecutionEvent.CompactionOutcome.UNCHANGED, null, null,
                        sourceRevision, Math.addExact(restored.conversation().revision(), 1), tokens, tokens)));
                receipt = compactionReceipt();
            }
        }
        AgentCompactionReceipt committed = receipt.orElseThrow();
        terminate(TurnState.COMPLETED, "", null, null);
        outcome = new AgentExecutionOutcome.Compacted(committed);
    }

    /**
     * 仅扫描本次执行固定事件水位，返回检查点事务的原始结果而非最新会话检查点。
     */
    private Optional<AgentCompactionReceipt> compactionReceipt() {
        long after = 0;
        long through = restored.conversation().eventSequence();
        AgentCompactionReceipt result = null;
        while (after < through) {
            EventPage page = services.store().readEvents(AgentStore.EventQuery.withDefaultMaxBytes(
                    execution.conversation(), after, through, 256));
            if (page.events().isEmpty() || page.nextSequence() <= after) {
                throw new AgentStore.StoreIntegrityException("compaction event stream has a gap", null);
            }
            for (PersistedEvent event : page.events()) {
                if (event.execution().equals(execution)
                    && services.events().decode(event) instanceof ExecutionEvent.ContextCompacted value) {
                    result = new AgentCompactionReceipt(AgentCompactionReceipt.Outcome.valueOf(value.outcome().name()),
                            value.compactionId(), Optional.ofNullable(value.checkpointId()), value.sourceRevision(),
                            value.threadRevision(), value.inputTokensBefore(), value.inputTokensAfter());
                }
            }
            after = page.nextSequence();
        }
        return Optional.ofNullable(result);
    }

    /**
     * 事实键只由完整执行身份与稳定业务键派生；跨租户同名调用不会碰撞。
     */
    @Override
    public AgentStore.RecordWrite fact(ExecutionFact value, String key) {
        return services.records().encode(recordId(value.getClass().getSimpleName(), key), value, services.clock().instant());
    }

    /**
     * 统一命名域后生成持久记录身份，不复用模型原始调用前缀做字符串裁剪。
     */
    private String recordId(String type, String key) {
        return AgentRecordIds.derive(execution, "agent." + type, key);
    }

    /**
     * 消息和事件使用相同完整身份摘要，但保留各自类型前缀供上层校验。
     */
    @Override
    public String id(String prefix, String key) {
        return prefix + "_" + hash(execution + "\0" + key);
    }

    /**
     * 稳定摘要固定 UTF-8，避免 Windows 默认字符集影响重启后的事务身份。
     */
    @Override
    public String hash(String value) {
        return Hashing.sha256Hex(value);
    }

    /**
     * 上下文来源必须等于本 Session 确认水位，不能自动提升旧摘要的来源版本。
     */
    private void requireContextRevision(long expected) {
        if (expected != restored.conversation().revision())
            {
                throw new CheckpointStore.CommitConflict("context source changed");
            }
    }

    /**
     * 开始压缩先保存真实输入计量；重启已有活动阶段时复用原身份，不再发送第二份 Started。
     */
    @Override
    public void beginCompaction(long sourceRevision, long inputTokensBefore) {
        if (compactionAttempt().filter(ExecutionRecordCodec.ContextCompaction::active).isPresent()) {
            return;
        }
        ExecutionRecordCodec.ContextCompaction attempt = new ExecutionRecordCodec.ContextCompaction(
                id("cmp", "compaction:" + restored.execution().mutationVersion()),
                sourceRevision, inputTokensBefore, true);
        commit(TurnState.RUNNING, cursor, List.of(compactionRecord(attempt, true)), List.of(
                new ExecutionEvent.ContextCompactionStarted(attempt.id(), sourceRevision, inputTokensBefore)));
    }

    /**
     * 压缩阶段身份必须可恢复，不能依赖模型请求栈上的局部计量或随机事件 ID。
     */
    private Optional<ExecutionRecordCodec.ContextCompaction> compactionAttempt() {
        return view.latestRecord(ExecutionRecordCodec.CONTEXT_COMPACTION_RECORD_TYPE)
                .map(services.records()::decodeContextCompaction);
    }

    /**
     * 开始和结算共享追加记录格式，过期进程不能改写旧压缩的身份或已测输入值。
     */
    private AgentStore.RecordWrite compactionRecord(ExecutionRecordCodec.ContextCompaction attempt, boolean active) {
        return services.records().encodeContextCompaction(recordId("context.compaction", attempt.id() + ":" + active),
                new ExecutionRecordCodec.ContextCompaction(attempt.id(), attempt.sourceRevision(),
                        attempt.inputTokensBefore(), active), services.clock().instant());
    }

    /**
     * 未知业务结果保留原工具游标和脱敏原因，不把挂起解释成失败或自动重放授权。
     */
    @Override
    public void suspend(String reasonCode) {
        RecordWrite record = services.controls().encodeSuspension(recordId("execution.suspension",
                Long.toString(restored.execution().mutationVersion())), reasonCode, services.clock().instant());
        commit(TurnState.SUSPENDED, cursor, List.of(record), List.of());
        outcome = suspended(Optional.empty());
    }

    /**
     * 步骤只读取完整执行身份，业务路径和服务对象不进入这些恢复字段。
     */
    @Override
    public AgentIdentity identity() {
        return identity;
    }

    /**
     * 返回本次请求冻结的能力与资源租约，步骤不得将其序列化。
     */
    @Override
    public AgentRequestRuntime runtime() {
        return runtime;
    }

    /**
     * 编解码及存储保持唯一装配，步骤不能自建替代实现。
     */
    @Override
    public AgentExecutionDriver.Services services() {
        return services;
    }

    /**
     * 每次提交后由持久快照替换当前游标，步骤不单独更新内存进度。
     */
    @Override
    public TurnExecutionState cursor() {
        return cursor;
    }

    /**
     * 返回固定水位，用于摘要源版本及执行 CAS 验证。
     */
    @Override
    public AgentLoopPersistence.Restored restored() {
        return restored;
    }

    /**
     * 扩展和工具读取同一不可变控制状态，避免恢复时混合两代事实。
     */
    @Override
    public ExecutionStateView view() {
        return view;
    }

    /**
     * 派发前检查取消与数据库执行权；旧节点不能仅凭过期内存租约继续调用。
     */
    @Override
    public void assertDispatchAuthority() {
        cancellation.throwIfCancellationRequested();
        lease.assertDispatchAuthority();
    }

    /**
     * 将 Engine 的取消通道传给模型和工具，不赋予订阅者取消权。
     */
    @Override
    public CancellationToken cancellation() {
        return cancellation;
    }

    /**
     * 临时事件使用 Engine 的有界队列，不替代持久执行事件。
     */
    @Override
    public TurnEventSink stream() {
        return stream;
    }

    /**
     * 共享序号跨模型轮次单调递增，算术溢出明确失败而不回绕成旧帧。
     */
    @Override
    public java.util.function.LongSupplier streamSequence() {
        return () -> streamSequence.updateAndGet(value -> Math.addExact(value, 1));
    }

    /**
     * 检查点适配器的提交回调受本 Session 租约和版本控制。
     */
    @Override
    public CheckpointStore checkpoints() {
        return checkpoints;
    }

    /**
     * 摘要请求和用量由专用单步回调映射进同一执行游标。
     */
    @Override
    public AgentSummaryOperation summaryOperation() {
        return summaryOperation;
    }

    /**
     * 显式历史来源由准入冻结，具体选择与当前执行合并只由上下文服务实现。
     */
    @Override
    public AgentExecutionRequest.HistorySelection historySelection() {
        return historySelection;
    }

    /**
     * 暴露绑定中冻结的输出约束，供模型步骤持续生成并验证同一 JSON 结果。
     */
    @Override
    public Optional<JsonObject> outputSchema() {
        return outputSchema;
    }

    /**
     * 模型单步只能提出明确终态，最终保存与返回仍由 Session 统一控制。
     */
    @Override
    public void terminate(TurnState state, String code, String message) {
        terminate(state, "", code, message);
    }

    /**
     * 保留冻结注册身份与该扩展在本次候选中返回的结果，供分支判定后再构造提交事实。
     *
     * @param registration 注册信息
     * @param decision 决策
     */
    private record CompletionHookDecision(ExtensionRegistry.MiddlewareRegistration registration,
                                          AgentMiddleware.ExtensionDecision<AgentMiddleware.CompletionDecision> decision) {
    }
}
