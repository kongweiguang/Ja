// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.application.cancellation.CancellationCoordinator;
import io.github.kongweiguang.ja.conversation.application.context.ContextException;
import io.github.kongweiguang.ja.conversation.application.context.ContextCompactionLifecycle;
import io.github.kongweiguang.ja.conversation.application.context.ContextOrchestrator;
import io.github.kongweiguang.ja.conversation.application.context.ContextTokenMeter;
import io.github.kongweiguang.ja.conversation.application.context.ContextOrchestratorFactory;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryModel;
import io.github.kongweiguang.ja.conversation.application.context.summary.ModelSummaryGenerator;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryProgressCodec;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointUsage;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointStore;
import io.github.kongweiguang.ja.conversation.application.observation.ExecutionObservers;
import io.github.kongweiguang.ja.conversation.application.presentation.ToolPresentationProjector;
import io.github.kongweiguang.ja.conversation.domain.model.ModelContent;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestUsage;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent;
import io.github.kongweiguang.ja.conversation.port.in.TurnEventSink;
import io.github.kongweiguang.ja.conversation.port.in.TurnResult;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSession;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.ExecutionObserver;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 编排一个 Turn 的上下文准备、Provider 轮次、Tool batch 与唯一终态提交，是 Agent Loop 的状态机执行器。
 */
final class AgentTurnExecution {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentTurnExecution.class);
    private final ModelPort model;
    private final ConversationRepository store;
    private final Clock clock;
    private final ContextOrchestratorFactory contextFactory;
    private final AgentContextMapper contextMapper;
    private final AgentLoopPersistence persistence;
    private final AgentToolRunner toolRunner;
    private final DeltaTimerScheduler deltaTimers;
    private final BooleanSupplier loopClosed;
    private final ExecutionObservers observers;
    private final TerminalFailureReplyPolicy failureReplyPolicy = new TerminalFailureReplyPolicy();

    /**
     * 固定各出站端口与生命周期 owner，使执行过程中只推进 Turn 状态而不重新选择基础设施。
     */
    AgentTurnExecution(
            ModelPort model,
            ConversationRepository store,
            Clock clock,
            ContextOrchestratorFactory contextFactory,
            AgentContextMapper contextMapper,
            AgentLoopPersistence persistence,
            AgentToolRunner toolRunner,
            DeltaTimerScheduler deltaTimers,
            BooleanSupplier loopClosed,
            ExecutionObservers observers) {
        this.model = Objects.requireNonNull(model, "model");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory");
        this.contextMapper = Objects.requireNonNull(contextMapper, "contextMapper");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.toolRunner = Objects.requireNonNull(toolRunner, "toolRunner");
        this.deltaTimers = Objects.requireNonNull(deltaTimers, "deltaTimers");
        this.loopClosed = Objects.requireNonNull(loopClosed, "loopClosed");
        this.observers = Objects.requireNonNull(observers, "observers");
    }

    /**
     * 按“准备上下文—调用模型—提交模型步—执行 Tool”的顺序循环，并在所有出口收敛到唯一终态；
     * 安全点消费的新用户输入必须同时失效 Provider continuation，保证下一轮从完整权威历史开始。
     */
    @SuppressWarnings("PMD.CloseResource")
    TurnResult execute(TurnExecutionPlan request, CancellationToken cancellation, TurnEventSink sink,
                       TerminalCoordinator terminalCoordinator, TurnExecutionState initialExecution) {
        observers.observe(new ExecutionObserver.TurnStarted(request.threadId(), request.turnId()));
        try {
            TurnResult result = executeObserved(
                    request, cancellation, sink, terminalCoordinator, initialExecution);
            observers.observe(new ExecutionObserver.TurnCompleted(
                    request.threadId(), request.turnId(), turnStatus(result), result.terminal().errorCode()));
            return result;
        } catch (AgentLoop.InputNeedsAttentionException suspended) {
            /* SUSPENDED 不是 Turn 终态；恢复会作为新的执行尝试进入，不能在此伪造 completed。 */
            throw suspended;
        } catch (RuntimeException failure) {
            observers.observe(new ExecutionObserver.TurnCompleted(
                    request.threadId(), request.turnId(), failureStatus(failure), failureCode(failure)));
            throw failure;
        }
    }

    /**
     * 保持原有单一 Turn 状态机主体，外围只增加不参与决策的开始/完成观察，避免各失败分支重复通知。
     */
    @SuppressWarnings("PMD.CloseResource")
    private TurnResult executeObserved(
            TurnExecutionPlan request, CancellationToken cancellation, TurnEventSink sink,
            TerminalCoordinator terminalCoordinator, TurnExecutionState initialExecution) {
        AgentLoop.RuntimeState state =
                new AgentLoop.RuntimeState(
                        request.initialThreadRevision(), request.initialTurnMutationVersion(), initialExecution);
        ModelPort.Continuation continuation = null;
        ProviderRequestProfile continuationProfile = null;
        String lastSummary = request.initialSummary();
        AgentRound current = null;
        UsageDurability currentUsageDurability = UsageDurability.NOT_COMMITTED;
        UsageCursor usageCursor = new UsageCursor();
        try {
            persistence.transition(request, state, TurnState.RUNNING, sink);
            if (state.execution instanceof TurnExecutionState.Tools toolsState) {
                executeToolsWithLatestRuntime(request, state, sink, cancellation, lastSummary, toolsState);
            }
            consumeRecoveredFollowUp(request, state, initialExecution, sink);
            int firstRound = state.execution.common().modelRound() + 1;
            for (int round = firstRound; round <= request.limits().maxModelRounds(); round++) {
                /* Steering 与 Task mailbox 会改变权威消息及 active Skill。它们必须先于请求环境解析，
                 * 否则当前轮会拿到旧 Prompt/Skill catalog，直到再下一轮才生效。 */
                boolean contextChanged = consumeQueuedInput(
                        request, state, ConversationRepository.InputKind.STEERING, sink);
                contextChanged |= persistence.consumeTaskMailbox(request, state);
                if (contextChanged) {
                    continuation = null;
                    continuationProfile = null;
                }
                TurnExecutionPlan.RequestRuntime planningRuntime =
                        request.openRequestRuntime(state.execution.common(), lastSummary);
                boolean planningRuntimeClosed = false;
                TurnMcpOwner planningMcp = new TurnMcpOwner();
                AssistantRequestResources[] assistantRequest = new AssistantRequestResources[1];
                try {
                    TurnExecutionPlan planningCommand = planningRuntime.plan();
                    planningMcp.open(planningCommand.toolSessions(), cancellation);
                    String summaryAtRequestStart = lastSummary;
                    ContextOrchestrator contexts = contextFactory.create(planningCommand.threadId(), () -> {
                        TurnExecutionPlan.RequestRuntime latest = request.openRequestRuntime(
                                state.execution.common(), summaryAtRequestStart);
                        boolean transferred = false;
                        try {
                            TurnExecutionPlan currentPlan = latest.plan();
                            ModelSummaryGenerator.RequestRuntime result =
                                    new ModelSummaryGenerator.RequestRuntime(
                                            new SummaryModel.TurnBinding(currentPlan.threadId(), currentPlan.model(),
                                                    currentPlan.deadlineAt(), cancellation),
                                            Optional.of(latest.profile()), latest);
                            transferred = true;
                            return result;
                        } finally {
                            if (!transferred) latest.close();
                        }
                    }, new DurableSummaryOperation(request, state, sink));
                    List<AgentTool> planningTools = new ArrayList<>(planningCommand.tools());
                    planningTools.addAll(planningMcp.tools());
                    List<io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec> planningToolSpecs =
                            planningTools.stream().map(AgentTool::spec).toList();
                    ModelPort.NativeAttachmentSupport planningNativeAttachments =
                            model.nativeAttachmentSupport(planningCommand.model());
                ensureActive(cancellation);
                if (!clock.instant().isBefore(planningCommand.deadlineAt())) {
                    throw new AgentLoop.LoopFailure("REQUEST_DEADLINE_EXCEEDED", "turn deadline exceeded");
                }
                /* 新 round 真正取得 current 身份时才重置，避免轮次间取消误用上一轮的持久化状态。 */
                currentUsageDurability = UsageDurability.NOT_COMMITTED;
                AgentRound collector =
                        current =
                                new AgentRound(
                                        planningCommand.turnId(),
                                        cancellation,
                                        sink,
                                        loopClosed,
                                        state,
                                        round,
                                        deltaTimers.openTimer(planningCommand.turnId(), round));
                ConversationRepository.ThreadSnapshot snapshot =
                        store
                                .readThread(planningCommand.threadId())
                                .orElseThrow(
                                        () ->
                                                new AgentLoop.LoopFailure(
                                                        "INVALID_STATE", "Thread history is unavailable"));
                /*
                 * Checkpoint 事务可能先于 ConversationRepository 读取同一 SQLite 快照而推进本地 revision；
                 * 滞后投影不得覆盖已提交回执，只有观察到更新 revision 时才能推进循环游标。
                 */
                if (snapshot.revision() > state.threadRevision) {
                    state.threadRevision = snapshot.revision();
                }
                long contextRevision = Math.max(snapshot.revision(), state.threadRevision);
                ConversationRepository.TurnSnapshot currentTurn =
                        snapshot.turns().stream()
                                .filter(turn -> turn.turnId().equals(planningCommand.turnId()))
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new AgentLoop.LoopFailure(
                                                        "INVALID_STATE", "current Turn is absent from history"));
                if (currentTurn.turnMutationVersion() != state.turnMutationVersion
                    || currentTurn.state() != state.state) {
                    throw new AgentLoop.LoopFailure("CONFLICT", "current Turn mutation changed");
                }
                AgentPromptSession.PreparedPrompt preliminaryPrompt =
                        planningCommand.promptSession().prepare(
                                lastSummary, planningToolSpecs);
                ProviderRequestProfile preliminaryProfile = planningRuntime.profile()
                        .withPromptRevision(preliminaryPrompt.snapshot().revision());
                boolean continuationMatches = continuation != null
                        && preliminaryProfile.equals(continuationProfile);
                ModelPort.Continuation contextContinuation = continuationMatches ? continuation : null;
                int modelRound = round;
                ProviderRequestProfile expectedContinuationProfile = continuationMatches
                        ? continuationProfile : null;
                Map<PromptIdentity, String> plannedPrompts = new java.util.HashMap<>();
                ContextTokenMeter tokenMeter = (messages, summary, candidateContinuation, localCompaction) -> {
                    ContextOrchestrator.PreparedPrompt candidate = new ContextOrchestrator.PreparedPrompt(
                            messages, summary, 0, candidateContinuation, localCompaction);
                    String summaryText = summary.hasNoFacts()
                            ? summaryAtRequestStart : summary.toPromptText();
                    AgentPromptSession.PreparedPrompt prepared =
                            planningCommand.promptSession().prepare(
                                    summaryText, planningToolSpecs);
                    ModelPort.Continuation providerContinuation = null;
                    ProviderRequestProfile candidateProfile = planningRuntime.profile()
                            .withPromptRevision(prepared.snapshot().revision());
                    if (!localCompaction && expectedContinuationProfile != null
                        && expectedContinuationProfile.equals(candidateProfile)) {
                        providerContinuation = candidateContinuation
                                .map(contextMapper::toModelContinuation).orElse(null);
                    }
                    ModelPort.ModelRequest modelRequest = contextMapper.toModelRequest(
                            candidate, planningCommand.model(), prepared.snapshot(),
                            planningToolSpecs,
                            providerContinuation, modelRound, planningCommand.threadId(),
                            planningCommand.attachments(), planningNativeAttachments);
                    try {
                        ModelPort.InputTokenEstimate estimate =
                                model.estimateInputTokens(modelRequest, cancellation);
                        PromptIdentity identity = PromptIdentity.from(candidate);
                        plannedPrompts.put(identity, summaryText);
                        return new ContextTokenMeter.Measurement(
                                estimate.conservativeUpperBound(), estimate.fingerprint());
                    } catch (java.util.concurrent.CancellationException cancelled) {
                        throw cancelled;
                    } catch (RuntimeException failure) {
                        throw new ContextException(ContextException.Code.INVALID_STATE,
                                "local provider input token estimation failed", failure);
                    }
                };
                ContextOrchestrator.Request contextRequest =
                        new ContextOrchestrator.Request(
                                planningCommand.threadId(),
                                contextRevision,
                                contextMapper.fromSnapshot(snapshot, planningCommand.turnId()),
                                preliminaryPrompt.budget(),
                                false,
                                contextMapper.continuation(contextContinuation),
                                planningCommand.outputLimits(),
                                tokenMeter,
                                cancellation);
                ModelPort.ModelOutcome outcome;
                String promptCheckpointId;
                PromptCallState[] promptCall = new PromptCallState[1];
                ContextCompactionLifecycle compactionLifecycle = new ContextCompactionLifecycle(
                        planningCommand.workspaceId(), planningCommand.threadId(),
                        planningCommand.turnId(), contextRevision,
                        "cmp_" + java.util.UUID.randomUUID().toString().replace("-", ""), sink, clock);
                try {
                    ContextOrchestrator.Execution<ModelPort.ModelOutcome> contextExecution =
                            contexts.execute(
                                    contextRequest,
                                    receipt -> persistence.observeCommittedCheckpoint(request, state, receipt),
                                    prompt -> {
                                        String plannedSummary = plannedPrompts.get(PromptIdentity.from(prompt));
                                        if (plannedSummary == null) {
                                            throw new ContextException(
                                                    ContextException.Code.INVALID_STATE,
                                                    "provider request was not estimated before send");
                                        }
                                        TurnExecutionPlan.RequestRuntime dispatchRuntime =
                                                request.openRequestRuntime(state.execution.common(),
                                                        plannedSummary);
                                        TurnMcpOwner dispatchMcp = new TurnMcpOwner();
                                        boolean transferred = false;
                                        try {
                                            TurnExecutionPlan dispatchCommand = dispatchRuntime.plan();
                                            dispatchMcp.open(dispatchCommand.toolSessions(), cancellation);
                                            List<AgentTool> dispatchTools = new ArrayList<>(dispatchCommand.tools());
                                            dispatchTools.addAll(dispatchMcp.tools());
                                            Map<String, AgentTool> dispatchToolCatalog =
                                                    TurnExecutionPlan.createToolCatalog(dispatchTools);
                                            List<io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec>
                                                    dispatchToolSpecs = dispatchTools.stream()
                                                            .map(AgentTool::spec).toList();
                                            AgentPromptSession.PreparedPrompt dispatchPrompt =
                                                    dispatchCommand.promptSession().prepare(
                                                            plannedSummary, dispatchToolSpecs);
                                            ProviderRequestProfile dispatchProfile = dispatchRuntime.profile()
                                                    .withPromptRevision(dispatchPrompt.snapshot().revision());
                                            ModelPort.Continuation dispatchContinuation = null;
                                            if (!prompt.localCompaction()
                                                && expectedContinuationProfile != null
                                                && expectedContinuationProfile.equals(dispatchProfile)) {
                                                dispatchContinuation = prompt.continuation()
                                                        .map(contextMapper::toModelContinuation).orElse(null);
                                            }
                                            ModelPort.NativeAttachmentSupport dispatchNativeAttachments =
                                                    model.nativeAttachmentSupport(dispatchCommand.model());
                                            ModelPort.ModelRequest modelRequest = contextMapper.toModelRequest(
                                                    prompt, dispatchCommand.model(), dispatchPrompt.snapshot(),
                                                    dispatchToolSpecs,
                                                    dispatchContinuation, modelRound, dispatchCommand.threadId(),
                                                    dispatchCommand.attachments(), dispatchNativeAttachments);
                                            ModelPort.InputTokenEstimate dispatchEstimate =
                                                    model.estimateInputTokens(modelRequest, cancellation);
                                            ensureLatestContextFits(dispatchEstimate, dispatchPrompt.budget());
                                            promptCall[0] = new PromptCallState(
                                                    plannedSummary, dispatchPrompt.snapshot().revision());
                                            TurnExecutionState.Ready ready = ready(state.execution);
                                            String requestId = "request_" + compactUuid();
                                            TurnExecutionState.ProviderPending pending =
                                                    new TurnExecutionState.ProviderPending(
                                                            ready.common(), requestId, "item_" + compactUuid(),
                                                            TurnExecutionState.ProviderPurpose.ASSISTANT,
                                                            dispatchProfile, dispatchEstimate.fingerprint(), ready);
                                            persistence.emit(request, state, null, List.of(
                                                    new ConversationRepository.UsageFact(
                                                            requestId, null, modelRound,
                                                            ready.common().nextProviderOrdinal(),
                                                            ConversationRepository.UsagePurpose.ASSISTANT,
                                                            ConversationRepository.UsageCertainty.UNKNOWN,
                                                            dispatchProfile)), pending, sink);
                                            observers.observe(new ExecutionObserver.ModelStarted(
                                                    dispatchCommand.threadId(), dispatchCommand.turnId(),
                                                    requestId, modelRound));
                                            ModelPort.ModelOutcome modelOutcome;
                                            try {
                                                modelOutcome = await(model.start(
                                                        modelRequest, collector, cancellation));
                                                observers.observe(new ExecutionObserver.ModelCompleted(
                                                        dispatchCommand.threadId(), dispatchCommand.turnId(),
                                                        requestId, modelRound,
                                                        ExecutionObserver.CompletionStatus.SUCCEEDED,
                                                        modelOutcome.finishReason(), modelOutcome.usage(), null));
                                            } catch (RuntimeException failure) {
                                                observers.observe(new ExecutionObserver.ModelCompleted(
                                                        dispatchCommand.threadId(), dispatchCommand.turnId(),
                                                        requestId, modelRound, failureStatus(failure), null, null,
                                                        failureCode(failure)));
                                                throw failure;
                                            }
                                            assistantRequest[0] = new AssistantRequestResources(
                                                    dispatchRuntime, dispatchMcp, dispatchToolCatalog,
                                                    dispatchProfile);
                                            transferred = true;
                                            return modelOutcome;
                                        } catch (ModelPort.ContextOverflowException overflow) {
                                            persistence.emit(request, state, null, List.of(),
                                                    pending(state.execution).resume()
                                                            .advanceProviderOrdinal(), sink);
                                            throw new ContextException(
                                                    ContextException.Code.CONTEXT_LIMIT,
                                                    "provider context limit exceeded",
                                                    overflow);
                                        } catch (ModelPort.ModelUnavailableException failure) {
                                            AgentLoop.LoopFailure sinkFailure = loopFailureCause(failure);
                                            if (sinkFailure != null) throw sinkFailure;
                                            throw failure;
                                        } finally {
                                            if (!transferred) {
                                                dispatchMcp.close();
                                                dispatchRuntime.close();
                                            }
                                        }
                                    }, compactionLifecycle, ContextCompactionEvent.Trigger.AUTOMATIC);
                    outcome = contextExecution.result();
                    promptCheckpointId = contextExecution.checkpoint()
                            .map(CheckpointStore.ContextCheckpoint::checkpointId).orElse(null);
                } finally {
                    collector.close();
                }
                planningMcp.close();
                planningRuntimeClosed = true;
                planningRuntime.close();
                if (promptCall[0] == null) {
                    throw new AgentLoop.LoopFailure("INVALID_STATE", "Prompt call state is unavailable");
                }
                AssistantRequestResources dispatched = assistantRequest[0];
                if (dispatched == null) {
                    throw new AgentLoop.LoopFailure("INVALID_STATE", "Assistant request resources are unavailable");
                }
                try {
                TurnExecutionPlan command = dispatched.plan();
                Map<String, AgentTool> toolCatalog = dispatched.toolCatalog();
                lastSummary = promptCall[0].summary();
                String batchPromptRevision = promptCall[0].revision();
                ensureActive(cancellation);
                collector.recordOutcomeUsage(outcome.usage());
                if (outcome.finishReason() == ModelPort.FinishReason.MAX_OUTPUT_TOKENS) {
                    throw new AgentLoop.LoopFailure("BUDGET_EXCEEDED", "model output limit reached");
                }
                List<AgentTool.Invocation> calls = collector.orderedCalls();
                if (outcome.finishReason() == ModelPort.FinishReason.STOP && calls.isEmpty()) {
                    long mutationVersionBeforeModelStep = state.turnMutationVersion;
                    boolean continued = commitModelStep(request, command, state, sink,
                            new ModelMessage(ModelRole.ASSISTANT, collector.assistantContent()),
                            collector.reasoningSummary(), collector.usage(), round, List.of(), toolCatalog,
                            promptCheckpointId, pending(state.execution), true);
                    if (continued) {
                        if (state.turnMutationVersion > mutationVersionBeforeModelStep) {
                            currentUsageDurability = UsageDurability.COMMITTED;
                        }
                        continuation = null;
                        continue;
                    }
                    /* 成功终态没有异常展开可替我们先执行 finally；先释放请求和 MCP 资源，
                     * 保证客户端看见完成时，本次 Provider 安全点已经完成资源收口。 */
                    dispatched.close();
                    return terminal(
                            request,
                            sink,
                            state,
                            cancellation,
                            terminalCoordinator,
                            TurnState.COMPLETED,
                            collector.terminalText(),
                            new ModelMessage(ModelRole.ASSISTANT, collector.assistantContent()),
                            collector.reasoningSummary(),
                            collector.usage(),
                            round,
                            true,
                            null,
                            null,
                            null);
                }
                if (calls.isEmpty()) {
                    throw new AgentLoop.LoopFailure(
                            "MODEL_PROTOCOL_ERROR", "model requested Tool continuation without calls");
                }
                List<ModelContent> assistant = collector.assistantContent();
                if (assistant.isEmpty()) {
                    throw new AgentLoop.LoopFailure(
                            "MODEL_PROTOCOL_ERROR", "model Tool round has no assistant content");
                }
                ModelMessage assistantMessage =
                        new ModelMessage(ModelRole.ASSISTANT, assistant);
                reserveToolBudget(state, request, calls.size());
                TurnExecutionState.ProviderPending providerPending = pending(state.execution);
                long mutationVersionBeforeModelStep = state.turnMutationVersion;
                try {
                    commitModelStep(
                            request,
                            command,
                            state,
                            sink,
                            assistantMessage,
                            collector.reasoningSummary(),
                            collector.usage(),
                            round,
                            calls,
                            toolCatalog,
                            promptCheckpointId,
                            providerPending,
                            false);
                    usageCursor.latest = requestUsage(providerPending, collector.usage(), round);
                } finally {
                    /*
                     * emit 会先推进持久化回执再等待事件发布；即使投影失败，只要 mutation version
                     * 已推进，本轮 Usage 就是既有事实，终态事务不得再次插入同一唯一键。
                     */
                    if (state.turnMutationVersion > mutationVersionBeforeModelStep) {
                        currentUsageDurability = UsageDurability.COMMITTED;
                    }
                }
                /* Provider 与配置租约到此已经结算；MCP owner 继续 pin 住生成本 batch 的精确路由，
                 * 直到全部 Tool 结果提交。崩溃恢复才会通过持久 binding 重新解析目录。 */
                dispatched.closeRuntime();
                executeTools(command, state, sink, cancellation, toolCatalog,
                        (TurnExecutionState.Tools) state.execution);
                // Tool batch 是已发生副作用的权威事实，必须先提交再响应取消；提交后立即终止，
                // 禁止带着已发布取消位进入下一轮 Provider 并等待远端自行观察。
                if (cancellation.isCancellationRequested()) {
                    throw new CancellationException(
                            cancellation.reason().orElse("turn cancelled after Tool batch"));
                }
                if (batchPromptRevision.equals(command.promptSession().currentRevision())) {
                    continuation = outcome.continuation();
                    continuationProfile = continuation == null ? null
                            : dispatched.profile().withPromptRevision(batchPromptRevision);
                } else {
                    continuation = null;
                    continuationProfile = null;
                }
                    } finally {
                        dispatched.close();
                    }
                    } finally {
                        try {
                            if (assistantRequest[0] != null) {
                                assistantRequest[0].close();
                            }
                        } finally {
                            try {
                                planningMcp.close();
                            } finally {
                                if (!planningRuntimeClosed) {
                                    planningRuntimeClosed = true;
                                    planningRuntime.close();
                                }
                            }
                        }
                    }
            }
            throw new AgentLoop.LoopFailure("BUDGET_EXCEEDED", "model round limit reached");
        } catch (AgentRound.DeltaDrainException failure) {
            /* 草稿 Sink 失败后已失去排序权威，因此恢复流程必须接管 RUNNING Turn。 */
            throw new AgentLoop.UnsafeGenerationException(failure.code(), failure);
        } catch (CancellationException cancelled) {
            if (cancellation instanceof CancellationCoordinator.CancellationScope scope) {
                try {
                    await(scope.cleanupCompletion());
                } catch (RuntimeException cleanupFailure) {
                    /*
                     * 取消声明已经独占终态方向；资源清理失败由 TurnService 的取消债务继续对调用方暴露，
                     * 但不能把唯一合法的 CANCELLED 持久终态改写为随后必然被取消门拒绝的 FAILED。
                     */
                    cancelled.addSuppressed(cleanupFailure);
                }
            } else if (!loopClosed.getAsBoolean() && !cancellation.isCancellationRequested()) {
                throw new IllegalStateException("cleanup boundary reached without cancellation");
            }
            return terminal(
                    request,
                    sink,
                    state,
                    cancellation,
                    terminalCoordinator,
                    TurnState.CANCELLED,
                    current == null ? "" : current.terminalText(),
                    null,
                    null,
                    current == null ? null : current.usage(),
                    current == null ? 0 : current.round(),
                    currentUsageDurability == UsageDurability.NOT_COMMITTED,
                    usageCursor.latest,
                    null,
                    null);
        } catch (TerminalCoordinator.CommitFailure failure) {
            /* 数据库尚未进入终态，应用紧急 Owner 继续持有终态提交权。 */
            throw failure;
        } catch (TerminalCoordinator.ProjectionFailure failure) {
            /* 终态回执已经是权威事实，投影失败不得触发第二次写入。 */
            throw failure;
        } catch (ContextException failure) {
            String code =
                    switch (failure.code()) {
                        case CONTEXT_LIMIT -> "CONTEXT_LIMIT";
                        case CAS_CONFLICT -> "CONFLICT";
                        case SUMMARY_FAILURE -> "MODEL_UNAVAILABLE";
                        case INVALID_STATE -> "INTERNAL_ERROR";
                    };
            return terminal(
                    request,
                    sink,
                    state,
                    cancellation,
                    terminalCoordinator,
                    TurnState.FAILED,
                    current == null ? "" : current.terminalText(),
                    null,
                    null,
                    current == null ? null : current.usage(),
                    current == null ? 0 : current.round(),
                    currentUsageDurability == UsageDurability.NOT_COMMITTED,
                    usageCursor.latest,
                    code,
                    "context preparation failed");
        } catch (AgentLoop.LoopFailure failure) {
            return terminal(
                    request,
                    sink,
                    state,
                    cancellation,
                    terminalCoordinator,
                    TurnState.FAILED,
                    current == null ? "" : current.terminalText(),
                    null,
                    null,
                    current == null ? null : current.usage(),
                    current == null ? 0 : current.round(),
                    currentUsageDurability == UsageDurability.NOT_COMMITTED,
                    usageCursor.latest,
                    failure.code(),
                    failure.getMessage());
        } catch (ModelPort.ModelUnavailableException failure) {
            logModelUnavailable(failure);
            String errorCode = failure.terminalErrorCode();
            return terminal(
                    request,
                    sink,
                    state,
                    cancellation,
                    terminalCoordinator,
                    TurnState.FAILED,
                    current == null ? "" : current.terminalText(),
                    null,
                    null,
                    current == null ? null : current.usage(),
                    current == null ? 0 : current.round(),
                    currentUsageDurability == UsageDurability.NOT_COMMITTED,
                    usageCursor.latest,
                    errorCode,
                    "MODEL_PROTOCOL_ERROR".equals(errorCode)
                            ? "model provider rejected the request"
                            : "model provider is unavailable");
        } catch (AgentLoop.InputNeedsAttentionException attention) {
            /* 队首问题与 SUSPENDED 已先持久化；继续交给 TurnService 清理 Scope，禁止二次提交 FAILED。 */
            throw attention;
        } catch (RuntimeException failure) {
            logInternalFailure(failure);
            return terminal(
                    request,
                    sink,
                    state,
                    cancellation,
                    terminalCoordinator,
                    TurnState.FAILED,
                    current == null ? "" : current.terminalText(),
                    null,
                    null,
                    current == null ? null : current.usage(),
                    current == null ? 0 : current.round(),
                    currentUsageDurability == UsageDurability.NOT_COMMITTED,
                    usageCursor.latest,
                    "INTERNAL_ERROR",
                    "agent loop failed");
        }
    }

    /**
     * 从 Provider 的受限包装链中恢复 AgentRound 已确定的失败；限制深度并拒绝自环，避免异常链
     * 畸形时影响终态收口，同时不依赖任何 Provider adapter 具体类型或消息文本。
     */
    private static AgentLoop.LoopFailure loopFailureCause(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof AgentLoop.LoopFailure loopFailure) return loopFailure;
            Throwable cause = current.getCause();
            if (cause == current) return null;
            current = cause;
        }
        return null;
    }

    /**
     * 只记录 Provider 中立异常类型，既能区分外部服务故障，也不把响应正文、账号池或凭据带入日志。
     */
    private static void logModelUnavailable(ModelPort.ModelUnavailableException failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        LOGGER.info("Agent model unavailable cause={} rootCause={}",
                failure.getClass().getSimpleName(), root.getClass().getSimpleName());
    }

    /**
     * 只把异常类型、首个 Ja 代码帧与 StorageException 自身保证脱敏的稳定 code/message 写入受管
     * 文件日志；origin 不包含文件名或异常 message，使 Native/JVM 差异可定位而不暴露 Prompt、SQL、
     * 路径、Provider 响应或凭据。
     */
    private static void logInternalFailure(RuntimeException failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        StorageException storage = failure instanceof StorageException storageFailure
                ? storageFailure : null;
        LOGGER.info("Agent turn failed cause={} rootCause={} origin={} storageCode={} storageMessage={}",
                failure.getClass().getSimpleName(), root.getClass().getSimpleName(),
                internalFailureOrigin(root),
                storage == null ? "NONE" : storage.code().name(),
                storage == null ? "NONE" : storage.getMessage());
    }

    /**
     * 从最深异常提取首个项目代码帧；字节码身份和行号足以关联源码，同时刻意丢弃文件名、
     * 异常 message 与参数，避免诊断增强扩大生产日志的数据面。
     */
    static String internalFailureOrigin(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        for (StackTraceElement frame : failure.getStackTrace()) {
            if (frame.getClassName().startsWith("io.github.kongweiguang.ja.")) {
                return frame.getClassName() + "#" + frame.getMethodName() + ":"
                        + Math.max(0, frame.getLineNumber());
            }
        }
        return "UNKNOWN";
    }

    /**
     * 仅用 Token 相关提示字段定位本轮已估算 envelope，避免 estimatedTokens 等证据字段影响复用。
     */
    private record PromptIdentity(
            List<io.github.kongweiguang.ja.conversation.application.context.ContextMessage> messages,
            io.github.kongweiguang.ja.conversation.application.context.summary.SummaryDocument summary,
            Optional<io.github.kongweiguang.ja.conversation.application.context.ModelContinuation> continuation,
            boolean localCompaction) {
        /** 从冻结提示复制不可变字段，使 HashMap 键不依赖调用方集合。 */
        private static PromptIdentity from(ContextOrchestrator.PreparedPrompt prompt) {
            return new PromptIdentity(prompt.messages(), prompt.summary(), prompt.continuation(),
                    prompt.localCompaction());
        }
    }

    /**
     * 独占 Assistant 请求的配置租约和 MCP 路由；Provider settlement 后可先释放配置，
     * 但 MCP 必须保持到整个 Tool batch 结算，所有异常出口最终仍由幂等 close 收口。
     */
    private static final class AssistantRequestResources implements AutoCloseable {
        private final TurnExecutionPlan.RequestRuntime runtime;
        private final TurnMcpOwner mcp;
        private final Map<String, AgentTool> toolCatalog;
        private final ProviderRequestProfile profile;
        private boolean runtimeClosed;
        private boolean mcpClosed;

        /** 请求发送成功后才转移所有权，失败和 Context overflow 分支不会留下半打开资源。 */
        private AssistantRequestResources(TurnExecutionPlan.RequestRuntime runtime,
                                          TurnMcpOwner mcp,
                                          Map<String, AgentTool> toolCatalog,
                                          ProviderRequestProfile profile) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.mcp = Objects.requireNonNull(mcp, "mcp");
            this.toolCatalog = Map.copyOf(Objects.requireNonNull(toolCatalog, "toolCatalog"));
            this.profile = Objects.requireNonNull(profile, "profile");
        }

        /** 返回本次 Provider 请求使用的最新能力视图，后续 settlement 禁止重新解析配置。 */
        private TurnExecutionPlan plan() {
            return runtime.plan();
        }

        /** Tool binding 必须引用生成该 batch 的精确目录，而不是下一安全点的新目录。 */
        private Map<String, AgentTool> toolCatalog() {
            return toolCatalog;
        }

        /** continuation 只记录实际发送请求的完整 Profile。 */
        private ProviderRequestProfile profile() {
            return profile;
        }

        /** Provider settlement 后立即释放配置代际；幂等性允许 finally 统一兜底。 */
        private void closeRuntime() {
            if (runtimeClosed) return;
            runtimeClosed = true;
            runtime.close();
        }

        /** MCP 会话只有在无 Tool 或整个 batch 已结算后才释放。 */
        private void closeMcp() {
            if (mcpClosed) return;
            mcpClosed = true;
            mcp.close();
        }

        /** 异常、继续和终态出口共享同一资源收口，不延长到下一 Provider 请求。 */
        @Override
        public void close() {
            try {
                closeMcp();
            } finally {
                closeRuntime();
            }
        }
    }

    /** 绑定 Provider 实际看到的 summary 与 Prompt revision，Tool batch 必须复用同一份证据。 */
    private record PromptCallState(String summary, String revision) {
        /** 拒绝缺失 revision，summary 允许为空表示尚未产生 checkpoint。 */
        private PromptCallState {
            summary = Objects.requireNonNull(summary, "summary");
            revision = Objects.requireNonNull(revision, "revision");
        }
    }

    /**
     * Provider 安全点必须以最新模型窗口关闭已知越界请求；抛出统一 CONTEXT_LIMIT 后，
     * 现有编排最多执行一次压缩恢复，且在 intent 落库和 HTTP dispatch 前不会产生远端副作用。
     */
    private static void ensureLatestContextFits(ModelPort.InputTokenEstimate estimate,
                                                io.github.kongweiguang.ja.conversation.domain.ContextBudget budget) {
        Objects.requireNonNull(estimate, "estimate");
        Objects.requireNonNull(budget, "budget");
        if (estimate.conservativeUpperBound() > budget.sendCeilingTokens()) {
            throw new ContextException(ContextException.Code.CONTEXT_LIMIT,
                    "provider request exceeds the latest context send ceiling");
        }
    }

    /**
     * 将 Assistant、Usage 与可选 Tool batch 作为一次持久事实提交；稳定 Operation 提供 CAS 身份，
     * requestRuntime 提供实际生成该 batch 的脱敏边界，防止环境热更新后误用首轮 Secret 快照。
     * STOP continuation 还要求存储在同一事务追加下一条排队输入，避免纯文本 settlement 与 USER
     * Message 跨事务倒序或断裂。
     */
    private boolean commitModelStep(
            TurnExecutionPlan request,
            TurnExecutionPlan requestRuntime,
            AgentLoop.RuntimeState state,
            TurnEventSink sink,
            ModelMessage assistant,
            String reasoningSummary,
            ModelUsage usage,
            int round,
            List<AgentTool.Invocation> calls,
            Map<String, AgentTool> catalog,
            String promptCheckpointId,
            TurnExecutionState.ProviderPending pending,
            boolean requireQueuedInput) {
        List<ConversationRepository.Fact> facts = new ArrayList<>();
        List<TurnEvent.ToolCall> toolCalls = new ArrayList<>();
        String messageId = pending.messageId();
        String publicText = render(assistant.content());
        facts.add(new ConversationRepository.AssistantFact(
                messageId, assistant, publicText, reasoningSummary, round));
        if (usage != null) {
            facts.add(new ConversationRepository.UsageFact(pending.requestId(), usage, round,
                    pending.common().nextProviderOrdinal(), ConversationRepository.UsagePurpose.ASSISTANT,
                    ConversationRepository.UsageCertainty.KNOWN, pending.profile()));
        }
        for (AgentTool.Invocation call : calls) {
            AgentTool tool = catalog.get(call.toolName());
            String batchId = "batch_" + pending.requestId().substring("request_".length());
            ConversationRepository.ToolBinding binding = tool == null ? null
                    : binding(batchId, call, tool.bindingDescriptor(), pending.profile());
            facts.add(
                    new ConversationRepository.ToolPreparedFact(
                            call.callId(),
                            call.toolName(),
                            call.arguments(),
                            call.ordinal(),
                            /* 未知名称没有可信能力声明，审计按潜在外部副作用保守记录；无 binding 仍保证零执行。 */
                            tool == null
                                    ? io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect.EXTERNAL
                                    : tool.sideEffect(),
                            ToolPresentationProjector.prepared(call, requestRuntime.workspaceRoot(),
                                    requestRuntime.presentationSecrets()),
                            binding));
            toolCalls.add(
                    new TurnEvent.ToolCall(call.callId(), call.toolName(),
                            ToolPresentationProjector.prepared(call, requestRuntime.workspaceRoot(),
                                    requestRuntime.presentationSecrets()), call.ordinal()));
        }
        TurnExecutionState.Common common = afterProvider(
                pending.common(), round, calls.size(), promptCheckpointId);
        TurnExecutionState nextExecution = calls.isEmpty()
                ? new TurnExecutionState.Ready(common, TurnExecutionState.Next.ASSISTANT, null)
                : new TurnExecutionState.Tools(common,
                        "batch_" + pending.requestId().substring("request_".length()), messageId,
                        calls.getFirst().ordinal(), calls.getLast().ordinal(), calls.getFirst().ordinal());
        /* 纯文本 STOP 由后续 queued input 把 Turn 保持为非终态，但它不是 Tool model step；
         * 只持久化完整 Assistant fact，不伪造要求至少一个 Tool call 的协议事件。 */
        TurnEvent.ModelStepCommitted event = toolCalls.isEmpty() ? null : new TurnEvent.ModelStepCommitted(
                persistence.draftContext(request, state), messageId, publicText, reasoningSummary,
                round, requestUsage(pending, usage, round), toolCalls);
        if (requireQueuedInput) {
            return persistence.emitWithNextInput(request, state, event, facts, nextExecution,
                    requestUsage(pending, usage, round), sink);
        }
        persistence.emit(request, state, event, facts, nextExecution, sink);
        return true;
    }

    /**
     * 只为目录中真实存在的 Tool 冻结恢复 binding；未知名称不能伪造 route/hash 来满足非空结构。
     */
    private static ConversationRepository.ToolBinding binding(
            String batchId, AgentTool.Invocation call, AgentTool.ToolBindingDescriptor descriptor,
            ProviderRequestProfile profile) {
        return new ConversationRepository.ToolBinding(batchId, call.callId(), descriptor.routeKind(),
                descriptor.localName(), descriptor.serverId(), descriptor.remoteName(), descriptor.schemaHash(),
                descriptor.routeHash(), profile.toolCatalogRevision(), profile.accessMode());
    }

    /** 将本次 pending 与可选 Provider 计量合成完整请求事实，UNKNOWN 仍保留真实 Profile。 */
    private static ProviderRequestUsage requestUsage(TurnExecutionState.ProviderPending pending,
                                                      ModelUsage usage, int modelRound) {
        ProviderRequestUsage.Purpose purpose = pending.purpose() == TurnExecutionState.ProviderPurpose.SUMMARY
                ? ProviderRequestUsage.Purpose.SUMMARY : ProviderRequestUsage.Purpose.ASSISTANT;
        return new ProviderRequestUsage(pending.requestId(), pending.common().nextProviderOrdinal(), modelRound,
                purpose, usage == null ? ProviderRequestUsage.Certainty.UNKNOWN
                        : ProviderRequestUsage.Certainty.KNOWN,
                pending.profile(), usage);
    }

    /**
     * 从已持久 Assistant blocks 重建当前 batch，并逐项解释 TOOLS 游标；恢复与新鲜执行共享同一路径，
     * 因而不会依赖进程内 collector 或扫描历史猜测已完成位置。
     */
    private void executeTools(TurnExecutionPlan request, AgentLoop.RuntimeState state, TurnEventSink sink,
                               CancellationToken cancellation, Map<String, AgentTool> catalog,
                               TurnExecutionState.Tools tools) {
        ConversationRepository.ThreadSnapshot snapshot = store.readThread(request.threadId())
                .orElseThrow(() -> new AgentLoop.LoopFailure("INVALID_STATE", "Thread history is unavailable"));
        ModelMessage assistant = snapshot.messages().stream()
                .filter(message -> request.turnId().equals(message.turnId())
                        && message.messageId().equals(tools.assistantMessageId()))
                .map(ConversationRepository.StoredMessage::message).findFirst()
                .orElseThrow(() -> new AgentLoop.LoopFailure("INVALID_STATE", "Tool assistant message is unavailable"));
        List<ToolCallContent> blocks = assistant.content().stream().filter(ToolCallContent.class::isInstance)
                .map(ToolCallContent.class::cast).toList();
        if (blocks.size() != tools.lastOrdinal() - tools.firstOrdinal() + 1) {
            throw new AgentLoop.LoopFailure("INVALID_STATE", "Tool batch does not match persisted cursor");
        }
        List<AgentTool.Invocation> calls = new ArrayList<>();
        Map<String, AgentTool> exactCatalog = new java.util.HashMap<>();
        for (int index = tools.nextOrdinal() - tools.firstOrdinal(); index < blocks.size(); index++) {
            ToolCallContent block = blocks.get(index);
            AgentTool.Invocation call = new AgentTool.Invocation(block.callId(), block.name(), block.arguments(),
                    tools.firstOrdinal() + index);
            calls.add(call);
            ConversationRepository.ToolBinding binding = store.findToolBinding(request.turnId(), call.callId())
                    .filter(value -> value.batchId().equals(tools.batchId()))
                    .orElse(null);
            AgentTool current = catalog.get(call.toolName());
            if (binding != null && current != null && binding.descriptor().equals(current.bindingDescriptor())) {
                exactCatalog.put(call.toolName(), current);
            }
        }
        if (calls.isEmpty()) {
            persistence.emit(request, state, null, List.of(),
                    new TurnExecutionState.Ready(tools.common(), TurnExecutionState.Next.ASSISTANT, null), sink);
            return;
        }
        AgentToolRunner.Execution execution = new AgentToolRunner.Execution(request, exactCatalog,
                cancellation, () -> persistence.draftContext(request, state),
                () -> (TurnExecutionState.Tools) state.execution,
                (target, event, facts, nextExecution) -> {
                    if (cancellation.isCancellationRequested() && event instanceof TurnEvent.ToolBatchCommitted) {
                        persistence.emitCancellationToolBatch(request, state, event, facts, nextExecution, sink);
                    } else if (target == state.state) {
                        persistence.emit(request, state, event, facts, nextExecution, sink);
                    } else {
                        persistence.transitionWithFacts(request, state, target, event, facts, nextExecution, sink);
                    }
                }, callId -> store.findApproval(request.turnId(), callId),
                callId -> store.findToolBinding(request.turnId(), callId),
                () -> persistence.refreshExternalAuthority(request, state),
                (approvalId, decision) -> persistence.publishExternalAuthorityEvent(
                        request, state, new TurnEvent.ApprovalResolved(
                                persistence.draftContext(request, state), approvalId, decision), sink));
        toolRunner.execute(execution, calls);
    }

    /**
     * Tool batch 在 Provider 请求租约释放后重新解析当前目录，并只执行与持久 binding 精确相等的路由。
     */
    private void executeToolsWithLatestRuntime(TurnExecutionPlan request, AgentLoop.RuntimeState state,
                                               TurnEventSink sink, CancellationToken cancellation,
                                               String promptSummary, TurnExecutionState.Tools toolsState) {
        try (TurnExecutionPlan.RequestRuntime runtime =
                     request.openRequestRuntime(toolsState.common(), promptSummary)) {
            TurnMcpOwner mcp = new TurnMcpOwner();
            try {
                mcp.open(runtime.plan().toolSessions(), cancellation);
                List<AgentTool> tools = new ArrayList<>(runtime.plan().tools());
                tools.addAll(mcp.tools());
                executeTools(runtime.plan(), state, sink, cancellation,
                        TurnExecutionPlan.createToolCatalog(tools), toolsState);
            } finally {
                mcp.close();
            }
        }
    }

    /**
     * 先关闭 Turn 级 MCP 会话，再让最新取消声明覆盖候选结果，最后竞争唯一终态提交。
     */
    private TurnResult terminal(
            TurnExecutionPlan request,
            TurnEventSink sink,
            AgentLoop.RuntimeState state,
            CancellationToken cancellation,
            TerminalCoordinator terminalCoordinator,
            TurnState target,
            String summary,
            ModelMessage finalMessage,
            String reasoningSummary,
            ModelUsage usage,
            int modelRound,
            boolean persistUsage,
            ProviderRequestUsage committedUsage,
            String errorCode,
            String errorMessage) {
        /* Provider 最后一次检查 Token 后，已持久化的取消声明仍可取得优先权。 */
        if (cancellation.isCancellationRequested()) {
            target = TurnState.CANCELLED;
            finalMessage = null;
            reasoningSummary = null;
            errorCode = null;
            errorMessage = null;
        }
        if (target == TurnState.FAILED) {
            String failureReply = failureReplyPolicy.replyFor(errorCode);
            summary = failureReply;
            finalMessage = new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent(failureReply)));
            reasoningSummary = null;
        }
        return persistence.terminal(
                request,
                sink,
                state,
                terminalCoordinator,
                target,
                summary,
                finalMessage == null ? null : failureReplyPolicy.messageIdFor(request.turnId(), state.execution),
                finalMessage,
                reasoningSummary,
                usage,
                modelRound,
                providerRequestOrdinal(state.execution),
                persistUsage,
                committedUsage,
                errorCode,
                errorMessage);
    }

    /**
     * 区分当前模型轮次的 Usage 是否已进入权威存储，避免用 nullable Usage 同时表达展示与写入语义。
     */
    private enum UsageDurability {
        /**
         * 当前轮 Usage 尚未随模型步提交，终态事务负责在同一原子边界内写入。
         */
        NOT_COMMITTED,

        /**
         * 当前轮 Usage 已随 Tool 模型步提交，终态只可投影而不能重复计量。
         */
        COMMITTED
    }

    /**
     * execution 前进到 TOOLS 后只保留最近已提交请求的展示事实；它不参与恢复或再次写库，
     * 因而不会把进程内缓存误当成权威执行游标。
     */
    private static final class UsageCursor {
        private ProviderRequestUsage latest;
    }

    /**
     * 为整个 Tool batch 预留 Turn 预算，超限时拒绝整批而不留下部分计数。
     */
    private static void reserveToolBudget(
            AgentLoop.RuntimeState state, TurnExecutionPlan command, int count) {
        if (count < 0 || state.toolCalls + count > command.limits().maxToolCalls()) {
            throw new AgentLoop.LoopFailure("BUDGET_EXCEEDED", "Tool call limit reached");
        }
        state.toolCalls += count;
    }

    /** Provider settlement 单调推进轮次、用量 ordinal 与已预留 Tool 数；Prompt revision 已归入请求审计。 */
    private static TurnExecutionState.Common afterProvider(TurnExecutionState.Common common,
                                                           int modelRound, int toolCalls,
                                                           String promptCheckpointId) {
        return new TurnExecutionState.Common(modelRound,
                Math.addExact(common.usedToolCalls(), toolCalls),
                Math.addExact(common.nextProviderOrdinal(), 1),
                promptCheckpointId, common.activeSkills(), common.deadlineAt(), common.origin());
    }

    /** 只有 READY 可以产生新的 Provider intent，避免一次崩溃窗口叠加两个可能计费请求。 */
    private static TurnExecutionState.Ready ready(TurnExecutionState execution) {
        if (execution instanceof TurnExecutionState.Ready ready) return ready;
        throw new AgentLoop.LoopFailure("INVALID_STATE", "Provider request requires READY execution state");
    }

    /** Provider 响应只能结算当前已提交 intent。 */
    private static TurnExecutionState.ProviderPending pending(TurnExecutionState execution) {
        if (execution instanceof TurnExecutionState.ProviderPending pending) return pending;
        throw new AgentLoop.LoopFailure("INVALID_STATE", "Provider settlement requires pending intent");
    }

    /** 终态 Usage 使用 intent ordinal；已结算 Tool 后的终态只投影既有上一请求。 */
    private static int providerRequestOrdinal(TurnExecutionState execution) {
        return execution instanceof TurnExecutionState.ProviderPending pending
                ? pending.common().nextProviderOrdinal()
                : Math.max(1, execution.common().nextProviderOrdinal() - 1);
    }

    /**
     * 把 ModelSummaryGenerator 的每个可能计费步骤接到 Turn execution CAS；只有 settlement 提交后
     * 才推进滚动游标，崩溃恢复因此不会跳过未落盘结果，也不会把 UNKNOWN 当作零 Usage。
     */
    private final class DurableSummaryOperation implements ModelSummaryGenerator.SummaryOperation {
        private final TurnExecutionPlan request;
        private final AgentLoop.RuntimeState state;
        private final TurnEventSink sink;
        private final SummaryProgressCodec codec = new SummaryProgressCodec();

        /** 绑定唯一 Turn 和当前内存镜像，所有写入仍由 AgentLoopPersistence 校验回执。 */
        private DurableSummaryOperation(TurnExecutionPlan request, AgentLoop.RuntimeState state,
                                        TurnEventSink sink) {
            this.request = Objects.requireNonNull(request, "request");
            this.state = Objects.requireNonNull(state, "state");
            this.sink = Objects.requireNonNull(sink, "sink");
        }

        /** 首次进入 SUMMARY 或恢复同一计划；计划漂移时保持 fail-closed。 */
        @Override
        public Progress start(String planFingerprint,
                              io.github.kongweiguang.ja.conversation.application.context.summary.SummaryDocument initial) {
            TurnExecutionState.Ready ready = ready(state.execution);
            TurnExecutionState.SummaryProgress stored;
            if (ready.next() == TurnExecutionState.Next.ASSISTANT) {
                stored = progress(Progress.candidate(
                        initial, CheckpointUsage.none(), 0, 0, planFingerprint));
                persistence.emit(request, state, null, List.of(),
                        new TurnExecutionState.Ready(ready.common(), TurnExecutionState.Next.SUMMARY, stored), sink);
            } else {
                stored = ready.summary();
                if (!stored.planFingerprint().equals(planFingerprint)) {
                    throw new ContextException(ContextException.Code.SUMMARY_FAILURE,
                            "persisted Summary plan no longer matches the frozen context");
                }
            }
            return restore(stored);
        }

        /** Provider 调用前先提交 SUMMARY intent，预留身份只用于审计而不生成对话 Message。 */
        @Override
        public void begin(String promptFingerprint, Optional<ProviderRequestProfile> profile) {
            TurnExecutionState.Ready ready = ready(state.execution);
            if (ready.next() != TurnExecutionState.Next.SUMMARY) {
                throw new AgentLoop.LoopFailure("INVALID_STATE", "Summary Provider requires READY(SUMMARY)");
            }
            ProviderRequestProfile current = Objects.requireNonNull(profile, "profile")
                    .orElseThrow(() -> new AgentLoop.LoopFailure(
                            "INVALID_STATE", "Summary Provider profile is unavailable"));
            TurnExecutionState.ProviderPending pending = new TurnExecutionState.ProviderPending(
                    ready.common(), "request_" + compactUuid(), "item_" + compactUuid(),
                    TurnExecutionState.ProviderPurpose.SUMMARY,
                    current.withPromptRevision(summaryPromptRevision(
                            current.promptRevision(), promptFingerprint)), promptFingerprint, ready);
            persistence.emit(request, state, null, List.of(new ConversationRepository.UsageFact(
                    pending.requestId(), null, Math.max(1, ready.common().modelRound() + 1),
                    ready.common().nextProviderOrdinal(), ConversationRepository.UsagePurpose.SUMMARY,
                    ConversationRepository.UsageCertainty.UNKNOWN, pending.profile())), pending, sink);
        }

        /** Provider 返回后原子写 KNOWN Usage 与下一显式子阶段，再允许 repair、fallback 或下一块。 */
        @Override
        public void settle(CheckpointUsage callUsage, Progress accepted) {
            TurnExecutionState.ProviderPending pending = pending(state.execution);
            if (pending.purpose() != TurnExecutionState.ProviderPurpose.SUMMARY) {
                throw new AgentLoop.LoopFailure("INVALID_STATE", "Summary settlement purpose changed");
            }
            TurnExecutionState.Common common = advanceProviderOrdinal(pending.common());
            TurnExecutionState.SummaryProgress summary = progress(
                    Objects.requireNonNull(accepted, "accepted"));
            TurnExecutionState.Ready next = new TurnExecutionState.Ready(
                    common, TurnExecutionState.Next.SUMMARY, summary);
            ModelUsage usage = new ModelUsage(callUsage.inputTokens(), callUsage.outputTokens(),
                    callUsage.totalTokens());
            persistence.emit(request, state, null, List.of(new ConversationRepository.UsageFact(
                    pending.requestId(), usage, Math.max(1, common.modelRound() + 1),
                    pending.common().nextProviderOrdinal(), ConversationRepository.UsagePurpose.SUMMARY,
                    ConversationRepository.UsageCertainty.KNOWN, pending.profile())), next, sink);
        }

        /** deterministic fallback 无 Provider 行，只推进同一计划内的接纳游标。 */
        @Override
        public void advance(Progress accepted) {
            TurnExecutionState.Ready ready = ready(state.execution);
            persistence.emit(request, state, null, List.of(), new TurnExecutionState.Ready(
                    ready.common(), TurnExecutionState.Next.SUMMARY, progress(accepted)), sink);
        }

        /** checkpoint 必须绑定最后一次 Summary settlement 后的公开 revision。 */
        @Override
        public long checkpointSourceRevision(long originalRevision) {
            return state.threadRevision;
        }

        /** 最终 checkpoint 事务负责把 READY(SUMMARY) 原子改为 READY(ASSISTANT)。 */
        @Override
        public Optional<CheckpointStore.TurnOperation> checkpointTurnOperation() {
            TurnExecutionState.Ready current = ready(state.execution);
            if (current.next() != TurnExecutionState.Next.SUMMARY) return Optional.empty();
            TurnExecutionState.Ready completed = new TurnExecutionState.Ready(
                    current.common(), TurnExecutionState.Next.ASSISTANT, null);
            return Optional.of(new CheckpointStore.TurnOperation(
                    request.turnId(), state.turnMutationVersion, completed));
        }

        /** 将应用进度无损投影为严格 execution JSON 领域值。 */
        private TurnExecutionState.SummaryProgress progress(Progress value) {
            CheckpointUsage usage = value.usage();
            return new TurnExecutionState.SummaryProgress(codec.encode(value.document()),
                    value.throughOrdinal(), value.nextTurn(), value.planFingerprint(),
                    TurnExecutionState.SummaryStage.valueOf(value.stage().name()),
                    value.targetNextTurn(), value.targetThroughOrdinal(), value.violations(),
                    value.promptFingerprint(),
                    new TurnExecutionState.KnownUsage(usage.inputTokens(), usage.outputTokens(),
                            usage.totalTokens(), usage.cacheReadTokens(), usage.cacheWriteTokens()));
        }

        /** 从 SQLite execution 恢复滚动正文和累计 Usage；损坏 JSON 由 Codec fail-closed。 */
        private Progress restore(TurnExecutionState.SummaryProgress value) {
            TurnExecutionState.KnownUsage usage = value.usage();
            return new Progress(codec.decode(value.summaryJson()), new CheckpointUsage(
                    usage.inputTokens(), usage.outputTokens(), usage.totalTokens(),
                    usage.cacheReadTokens(), usage.cacheWriteTokens()), value.nextChunk(),
                    value.throughOrdinal(), value.planFingerprint(),
                    ModelSummaryGenerator.SummaryOperation.Stage.valueOf(value.stage().name()),
                    value.targetNextChunk(), value.targetThroughOrdinal(), value.violations(),
                    value.promptFingerprint());
        }

        /** Summary 请求只推进 Provider ordinal，不冒充成功 Assistant model round。 */
        private TurnExecutionState.Common advanceProviderOrdinal(TurnExecutionState.Common common) {
            return new TurnExecutionState.Common(common.modelRound(), common.usedToolCalls(),
                    Math.addExact(common.nextProviderOrdinal(), 1), common.promptCheckpointId(),
                    common.activeSkills(), common.deadlineAt(), common.origin());
        }
    }

    /**
     * Summary Profile 同时绑定最新 Agent Prompt/Skill revision 与本次结构化摘要 prompt，
     * 任一环境或输入变化都会形成不同请求事实，且不把原始 prompt 写入持久层。
     */
    private static String summaryPromptRevision(String environmentRevision, String promptFingerprint) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(environmentRevision.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(promptFingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** UUID 去分隔符后仍保留稳定前缀校验所需的有界身份。 */
    private static String compactUuid() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 逐条消费指定 FIFO，并采用 Repository 返回的权威 revision；无输入时不改变任何状态。
     */
    private boolean consumeQueuedInput(TurnExecutionPlan request, AgentLoop.RuntimeState state,
                                       ConversationRepository.InputKind kind, TurnEventSink sink) {
        return persistence.consumeInput(request, state, kind, sink);
    }

    /**
     * 附件失效会在前一条 Assistant 已原子提交后阻止下一条 FOLLOW_UP 消费；显式恢复时只有历史
     * 最后一条事实仍是 Assistant，才说明当前 READY 没有待回答的 USER/TOOL，可以先消费修复后的
     * 队首。崩溃恢复若停在 USER 或 TOOL 后则保持原顺序，禁止把另一条输入并入未完成回复。
     */
    private void consumeRecoveredFollowUp(TurnExecutionPlan request, AgentLoop.RuntimeState state,
                                           TurnExecutionState initialExecution, TurnEventSink sink) {
        if (!(initialExecution instanceof TurnExecutionState.Ready ready)
                || ready.next() != TurnExecutionState.Next.ASSISTANT) {
            return;
        }
        ConversationRepository.ThreadSnapshot snapshot = store.readThread(request.threadId())
                .orElseThrow(() -> new AgentLoop.LoopFailure(
                        "INVALID_STATE", "Thread history is unavailable"));
        ModelRole latestRole = snapshot.messages().stream()
                .filter(message -> request.turnId().equals(message.turnId()))
                .max(java.util.Comparator.comparingLong(ConversationRepository.StoredMessage::ordinal))
                .map(message -> message.message().role())
                .orElse(null);
        if (latestRole == ModelRole.ASSISTANT) {
            persistence.consumeInput(request, state, ConversationRepository.InputKind.FOLLOW_UP, sink);
        }
    }

    /**
     * 在每个外部调用边界检查 Turn 与 Loop 两级取消，避免关闭后继续产生副作用。
     */
    private void ensureActive(CancellationToken cancellation) {
        cancellation.throwIfCancellationRequested();
        if (loopClosed.getAsBoolean()) {
            throw new CancellationException("agent loop closing");
        }
    }

    /**
     * 在同步状态机边界等待异步端口，并保留端口抛出的运行时失败类型。
     */
    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw failure;
        }
    }

    /** 终态状态直接映射为观察分类；FAILED deadline 保留超时语义。 */
    private static ExecutionObserver.CompletionStatus turnStatus(TurnResult result) {
        return switch (result.state()) {
            case COMPLETED -> ExecutionObserver.CompletionStatus.SUCCEEDED;
            case CANCELLED -> ExecutionObserver.CompletionStatus.CANCELLED;
            case FAILED -> timeoutCode(result.terminal().errorCode())
                    ? ExecutionObserver.CompletionStatus.TIMED_OUT
                    : ExecutionObserver.CompletionStatus.FAILED;
            case QUEUED, RUNNING, WAITING_APPROVAL, SUSPENDED ->
                    throw new IllegalArgumentException("terminal result required");
        };
    }

    /** 异常只映射稳定类别，不把异常正文带入观察端口。 */
    private static ExecutionObserver.CompletionStatus failureStatus(RuntimeException failure) {
        if (failure instanceof CancellationException) return ExecutionObserver.CompletionStatus.CANCELLED;
        return timeoutCode(failureCode(failure))
                ? ExecutionObserver.CompletionStatus.TIMED_OUT
                : ExecutionObserver.CompletionStatus.FAILED;
    }

    /** 优先保留领域稳定码，其余异常统一为内部失败且不暴露 message。 */
    private static String failureCode(RuntimeException failure) {
        if (failure instanceof AgentLoop.LoopFailure loopFailure) return loopFailure.code();
        if (failure instanceof ModelPort.ModelUnavailableException unavailable) {
            return unavailable.terminalErrorCode();
        }
        if (failure instanceof ModelPort.ContextOverflowException) return "CONTEXT_LIMIT";
        if (failure instanceof AgentLoop.UnsafeGenerationException unsafe) return unsafe.code().name();
        if (failure instanceof CancellationException) return "CANCELLED";
        if (failure instanceof TerminalCoordinator.CommitFailure) return "TERMINAL_COMMIT_FAILED";
        if (failure instanceof TerminalCoordinator.ProjectionFailure) return "TERMINAL_PROJECTION_FAILED";
        return "INTERNAL_ERROR";
    }

    /** 仅按稳定错误码识别期限耗尽，避免解析供应商或异常正文。 */
    private static boolean timeoutCode(String code) {
        return code != null && (code.contains("TIMEOUT") || code.contains("DEADLINE"));
    }

    /**
     * 生成有界的公开文本投影；Tool 身份已有结构化 toolCalls 字段承载，不能再混入助手文本，
     * 否则中间模型步会在最终答复中泄漏内部 callId 并与 Tool 时间线重复展示。
     */
    private static String render(List<ModelContent> content) {
        StringBuilder result = new StringBuilder();
        for (ModelContent block : content) {
            if (block instanceof TextContent text) {
                result.append(text.text());
            }
        }
        if (result.length() > 1_048_576) {
            result.setLength(1_048_576);
        }
        return result.toString();
    }
}
