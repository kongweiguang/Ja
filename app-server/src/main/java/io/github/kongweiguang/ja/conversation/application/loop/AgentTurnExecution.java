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
import io.github.kongweiguang.ja.conversation.application.middleware.MiddlewareChain;
import io.github.kongweiguang.ja.conversation.application.presentation.ToolPresentationProjector;
import io.github.kongweiguang.ja.conversation.domain.model.ModelContent;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent;
import io.github.kongweiguang.ja.conversation.port.in.TurnEventSink;
import io.github.kongweiguang.ja.conversation.port.in.TurnResult;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSession;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
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
    private final MiddlewareChain middleware;

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
            MiddlewareChain middleware) {
        this.model = Objects.requireNonNull(model, "model");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory");
        this.contextMapper = Objects.requireNonNull(contextMapper, "contextMapper");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.toolRunner = Objects.requireNonNull(toolRunner, "toolRunner");
        this.deltaTimers = Objects.requireNonNull(deltaTimers, "deltaTimers");
        this.loopClosed = Objects.requireNonNull(loopClosed, "loopClosed");
        this.middleware = Objects.requireNonNull(middleware, "middleware");
    }

    /**
     * 按“准备上下文—调用模型—提交模型步—执行 Tool”的顺序循环，并在所有出口收敛到唯一终态。
     */
    @SuppressWarnings("PMD.CloseResource")
    TurnResult execute(TurnExecutionPlan request, CancellationToken cancellation, TurnEventSink sink, TerminalCoordinator terminalCoordinator) {
        TurnExecutionPlan command = request;
        TurnMcpOwner mcp = new TurnMcpOwner();
        AgentLoop.RuntimeState state =
                new AgentLoop.RuntimeState(
                        request.initialThreadRevision(), request.initialTurnMutationVersion());
        ModelPort.Continuation continuation = null;
        String continuationPromptRevision = null;
        String lastSummary = "";
        AgentRound current = null;
        UsageDurability currentUsageDurability = UsageDurability.NOT_COMMITTED;
        try {
            mcp.open(request.toolSessions(), cancellation);
            ContextOrchestrator contexts =
                    contextFactory.create(
                            new SummaryModel.TurnBinding(
                                    command.threadId(),
                                    command.model(),
                                    command.requestedAt().plus(command.limits().wallTimeout()),
                                    cancellation));
            List<AgentTool> tools = new ArrayList<>(request.tools());
            tools.addAll(mcp.tools());
            Map<String, AgentTool> toolCatalog = TurnExecutionPlan.createToolCatalog(tools);
            List<io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec> toolSpecs =
                    tools.stream().map(AgentTool::spec).toList();
            ModelPort.NativeAttachmentSupport nativeAttachments =
                    model.nativeAttachmentSupport(command.model());
            persistence.transition(request, state, TurnState.RUNNING, sink);
            for (int round = 1; round <= command.limits().maxModelRounds(); round++) {
                ensureActive(cancellation);
                consumeQueuedInput(request, state, ConversationRepository.InputKind.STEERING);
                if (!clock.instant().isBefore(command.requestedAt().plus(command.limits().wallTimeout()))) {
                    throw new AgentLoop.LoopFailure("REQUEST_DEADLINE_EXCEEDED", "turn deadline exceeded");
                }
                /* 新 round 真正取得 current 身份时才重置，避免轮次间取消误用上一轮的持久化状态。 */
                currentUsageDurability = UsageDurability.NOT_COMMITTED;
                AgentRound collector =
                        current =
                                new AgentRound(
                                        command.turnId(),
                                        cancellation,
                                        sink,
                                        loopClosed,
                                        state,
                                        round,
                                        deltaTimers.openTimer(command.turnId(), round));
                ConversationRepository.ThreadSnapshot snapshot =
                        store
                                .readThread(command.threadId())
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
                                .filter(turn -> turn.turnId().equals(command.turnId()))
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
                        command.promptSession().prepare(lastSummary, toolSpecs);
                boolean continuationMatches = continuation != null
                        && preliminaryPrompt.snapshot().revision().equals(continuationPromptRevision);
                ModelPort.Continuation contextContinuation = continuationMatches ? continuation : null;
                int modelRound = round;
                String expectedContinuationRevision = continuationMatches
                        ? continuationPromptRevision : null;
                Map<PromptIdentity, MeteredPrompt> meteredPrompts = new java.util.HashMap<>();
                ContextTokenMeter tokenMeter = (messages, summary, candidateContinuation, localCompaction) -> {
                    ContextOrchestrator.PreparedPrompt candidate = new ContextOrchestrator.PreparedPrompt(
                            messages, summary, 0, candidateContinuation, localCompaction);
                    String summaryText = summary.hasNoFacts() ? "" : summary.toPromptText();
                    AgentPromptSession.PreparedPrompt prepared =
                            command.promptSession().prepare(summaryText, toolSpecs);
                    ModelPort.Continuation providerContinuation = null;
                    if (!localCompaction && expectedContinuationRevision != null
                        && expectedContinuationRevision.equals(prepared.snapshot().revision())) {
                        providerContinuation = candidateContinuation
                                .map(contextMapper::toModelContinuation).orElse(null);
                    }
                    ModelPort.ModelRequest modelRequest = contextMapper.toModelRequest(
                            candidate, command.model(), prepared.snapshot(), toolSpecs,
                            providerContinuation, modelRound, command.threadId(), command.attachments(),
                            nativeAttachments);
                    try {
                        ModelPort.InputTokenCount count = await(
                                model.countInputTokens(modelRequest, cancellation));
                        PromptIdentity identity = PromptIdentity.from(candidate);
                        meteredPrompts.put(identity,
                                new MeteredPrompt(modelRequest, summaryText, prepared.snapshot().revision(), count));
                        return new ContextTokenMeter.Measurement(count.tokens(), count.fingerprint());
                    } catch (java.util.concurrent.CancellationException cancelled) {
                        throw cancelled;
                    } catch (RuntimeException failure) {
                        throw new ContextException(ContextException.Code.TOKEN_COUNT_UNAVAILABLE,
                                "provider input token count is unavailable", failure);
                    }
                };
                ContextOrchestrator.Request contextRequest =
                        new ContextOrchestrator.Request(
                                command.threadId(),
                                contextRevision,
                                contextMapper.fromSnapshot(snapshot, command.turnId()),
                                preliminaryPrompt.budget(),
                                false,
                                contextMapper.continuation(contextContinuation),
                                request.outputLimits(),
                                tokenMeter,
                                cancellation);
                ModelPort.ModelOutcome outcome;
                PromptCallState[] promptCall = new PromptCallState[1];
                ContextCompactionLifecycle compactionLifecycle = new ContextCompactionLifecycle(
                        command.workspaceId(), command.threadId(), command.turnId(), contextRevision,
                        "cmp_" + java.util.UUID.randomUUID().toString().replace("-", ""), sink, clock);
                try {
                    ContextOrchestrator.Execution<ModelPort.ModelOutcome> contextExecution =
                            contexts.execute(
                                    contextRequest,
                                    receipt -> persistence.observeCommittedCheckpoint(request, state, receipt),
                                    prompt -> {
                                        MeteredPrompt metered = meteredPrompts.get(PromptIdentity.from(prompt));
                                        if (metered == null) {
                                            throw new ContextException(
                                                    ContextException.Code.TOKEN_COUNT_UNAVAILABLE,
                                                    "provider request was not measured before send");
                                        }
                                        promptCall[0] = new PromptCallState(
                                                metered.summaryText(), metered.promptRevision());
                                        ModelPort.ModelRequest modelRequest = metered.request();
                                        try {
                                            middleware.beforeModel(modelRequest);
                                            ModelPort.ModelOutcome modelOutcome =
                                                    await(model.start(modelRequest, collector, cancellation));
                                            middleware.afterModel(modelRequest, modelOutcome);
                                            return modelOutcome;
                                        } catch (ModelPort.ContextOverflowException overflow) {
                                            throw new ContextException(
                                                    ContextException.Code.CONTEXT_LIMIT,
                                                    "provider context limit exceeded",
                                                    overflow);
                                        }
                                    }, compactionLifecycle, ContextCompactionEvent.Trigger.AUTOMATIC);
                    outcome = contextExecution.result();
                } finally {
                    collector.close();
                }
                if (promptCall[0] == null) {
                    throw new AgentLoop.LoopFailure("INVALID_STATE", "Prompt call state is unavailable");
                }
                lastSummary = promptCall[0].summary();
                String batchPromptRevision = promptCall[0].revision();
                ensureActive(cancellation);
                collector.recordOutcomeUsage(outcome.usage());
                if (outcome.finishReason() == ModelPort.FinishReason.MAX_OUTPUT_TOKENS) {
                    throw new AgentLoop.LoopFailure("BUDGET_EXCEEDED", "model output limit reached");
                }
                List<AgentTool.Invocation> calls = collector.orderedCalls();
                if (outcome.finishReason() == ModelPort.FinishReason.STOP) {
                    if (!calls.isEmpty()) {
                        throw new AgentLoop.LoopFailure(
                                "MODEL_PROTOCOL_ERROR", "model stopped with unresolved Tool calls");
                    }
                    ConversationRepository.InputKind queued =
                            consumeQueuedInput(request, state, ConversationRepository.InputKind.STEERING)
                                    ? ConversationRepository.InputKind.STEERING
                                    : consumeQueuedInput(request, state, ConversationRepository.InputKind.FOLLOW_UP)
                                            ? ConversationRepository.InputKind.FOLLOW_UP : null;
                    if (queued != null) {
                        long mutationVersionBeforeModelStep = state.turnMutationVersion;
                        commitModelStep(request, state, sink,
                                new ModelMessage(ModelRole.ASSISTANT, collector.assistantContent()),
                                collector.reasoningSummary(), collector.usage(), round, List.of(), toolCatalog);
                        if (state.turnMutationVersion > mutationVersionBeforeModelStep) {
                            currentUsageDurability = UsageDurability.COMMITTED;
                        }
                        continuation = null;
                        continuationPromptRevision = null;
                        continue;
                    }
                    return terminalAfterMcpClose(
                            request,
                            sink,
                            state,
                            cancellation,
                            terminalCoordinator,
                            TurnState.COMPLETED,
                            collector.terminalText(),
                            new ModelMessage(ModelRole.ASSISTANT, collector.assistantContent()),
                            collector.usage(),
                            round,
                            true,
                            null,
                            null,
                            mcp);
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
                long mutationVersionBeforeModelStep = state.turnMutationVersion;
                try {
                    commitModelStep(
                            request,
                            state,
                            sink,
                            assistantMessage,
                            collector.reasoningSummary(),
                            collector.usage(),
                            round,
                            calls,
                            toolCatalog);
                } finally {
                    /*
                     * emit 会先推进持久化回执再等待事件发布；即使投影失败，只要 mutation version
                     * 已推进，本轮 Usage 就是既有事实，终态事务不得再次插入同一唯一键。
                     */
                    if (state.turnMutationVersion > mutationVersionBeforeModelStep) {
                        currentUsageDurability = UsageDurability.COMMITTED;
                    }
                }
                AgentToolRunner.Execution toolExecution =
                        new AgentToolRunner.Execution(
                                command,
                                toolCatalog,
                                batchPromptRevision,
                                cancellation,
                                () -> persistence.draftContext(request, state),
                                count -> reserveToolBudget(state, command, count),
                                (event, facts) -> {
                                    /*
                                     * turn/cancel 可在 Tool 执行期间由独立 Owner 推进 mutation version；
                                     * batch 是已发生副作用的事实，只能通过窄化事务跨过取消门。
                                     */
                                    if (cancellation.isCancellationRequested()) {
                                        persistence.emitCancellationToolBatch(
                                                request, state, event, facts, sink);
                                    } else {
                                        persistence.emit(request, state, event, facts, sink);
                                    }
                                },
                                (target, event, facts) ->
                                        persistence.transitionWithFacts(request, state, target, event, facts, sink));
                toolRunner.execute(toolExecution, calls);
                // Tool batch 是已发生副作用的权威事实，必须先提交再响应取消；提交后立即终止，
                // 禁止带着已发布取消位进入下一轮 Provider 并等待远端自行观察。
                if (cancellation.isCancellationRequested()) {
                    throw new CancellationException(
                            cancellation.reason().orElse("turn cancelled after Tool batch"));
                }
                if (batchPromptRevision.equals(command.promptSession().currentRevision())) {
                    continuation = outcome.continuation();
                    continuationPromptRevision = continuation == null ? null : batchPromptRevision;
                } else {
                    continuation = null;
                    continuationPromptRevision = null;
                }
            }
            throw new AgentLoop.LoopFailure("BUDGET_EXCEEDED", "model round limit reached");
        } catch (AgentRound.DeltaDrainException failure) {
            /* 草稿 Sink 失败后已失去排序权威，因此恢复流程必须接管 RUNNING Turn。 */
            try {
                mcp.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
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
            return terminalAfterMcpClose(
                    request,
                    sink,
                    state,
                    cancellation,
                    terminalCoordinator,
                    TurnState.CANCELLED,
                    current == null ? "" : current.terminalText(),
                    null,
                    current == null ? null : current.usage(),
                    current == null ? 0 : current.round(),
                    currentUsageDurability == UsageDurability.NOT_COMMITTED,
                    null,
                    null,
                    mcp);
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
                        case TOKEN_COUNT_UNAVAILABLE -> "MODEL_UNAVAILABLE";
                        case SUMMARY_FAILURE -> "MODEL_UNAVAILABLE";
                        case INVALID_STATE -> "INTERNAL_ERROR";
                    };
            return terminalAfterMcpClose(
                    request,
                    sink,
                    state,
                    cancellation,
                    terminalCoordinator,
                    TurnState.FAILED,
                    current == null ? "" : current.terminalText(),
                    null,
                    current == null ? null : current.usage(),
                    current == null ? 0 : current.round(),
                    currentUsageDurability == UsageDurability.NOT_COMMITTED,
                    code,
                    "context preparation failed",
                    mcp);
        } catch (AgentLoop.LoopFailure failure) {
            return terminalAfterMcpClose(
                    request,
                    sink,
                    state,
                    cancellation,
                    terminalCoordinator,
                    TurnState.FAILED,
                    current == null ? "" : current.terminalText(),
                    null,
                    current == null ? null : current.usage(),
                    current == null ? 0 : current.round(),
                    currentUsageDurability == UsageDurability.NOT_COMMITTED,
                    failure.code(),
                    failure.getMessage(),
                    mcp);
        } catch (RuntimeException failure) {
            logInternalFailure(failure);
            return terminalAfterMcpClose(
                    request,
                    sink,
                    state,
                    cancellation,
                    terminalCoordinator,
                    TurnState.FAILED,
                    current == null ? "" : current.terminalText(),
                    null,
                    current == null ? null : current.usage(),
                    current == null ? 0 : current.round(),
                    currentUsageDurability == UsageDurability.NOT_COMMITTED,
                    "INTERNAL_ERROR",
                    "agent loop failed",
                    mcp);
        } finally {
            mcp.close();
        }
    }

    /**
     * 只把异常类型写入受管文件日志，既为 Native/JVM 的无栈失败保留可定位证据，也避免把
     * Prompt、Tool 参数、路径、Provider 响应或凭据带入 stderr 和支持日志。
     */
    private static void logInternalFailure(RuntimeException failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        LOGGER.info("Agent turn failed cause={} rootCause={}",
                failure.getClass().getSimpleName(), root.getClass().getSimpleName());
    }

    /**
     * 仅用 Token 相关提示字段定位本轮已计量 envelope，避免 estimatedTokens 等证据字段影响复用。
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

    /** 将最终发送请求与计量证据和 Prompt revision 绑定为不可拆分的本轮状态。 */
    private record MeteredPrompt(
            ModelPort.ModelRequest request, String summaryText, String promptRevision,
            ModelPort.InputTokenCount count) {
        /** 拒绝任一缺失字段，确保 sender 无法临时重建未计量请求。 */
        private MeteredPrompt {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(summaryText, "summaryText");
            Objects.requireNonNull(promptRevision, "promptRevision");
            Objects.requireNonNull(count, "count");
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
     * 将 assistant 内容、Usage 与整个 Tool batch 作为一次持久事实提交，随后才对外发布模型步事件。
     */
    private void commitModelStep(
            TurnExecutionPlan request,
            AgentLoop.RuntimeState state,
            TurnEventSink sink,
            ModelMessage assistant,
            String reasoningSummary,
            ModelUsage usage,
            int round,
            List<AgentTool.Invocation> calls,
            Map<String, AgentTool> catalog) {
        List<ConversationRepository.Fact> facts = new ArrayList<>();
        List<TurnEvent.ToolCall> toolCalls = new ArrayList<>();
        String messageId = "item_" + java.util.UUID.randomUUID();
        String publicText = render(assistant.content());
        facts.add(new ConversationRepository.AssistantFact(
                messageId, assistant, publicText, reasoningSummary, round));
        if (usage != null) {
            facts.add(new ConversationRepository.UsageFact(usage, round));
        }
        for (AgentTool.Invocation call : calls) {
            AgentTool tool = catalog.get(call.toolName());
            facts.add(
                    new ConversationRepository.ToolPreparedFact(
                            call.callId(),
                            call.toolName(),
                            call.arguments(),
                            call.ordinal(),
                            AgentToolRunner.sideEffect(tool),
                            ToolPresentationProjector.prepared(call, request.workspaceRoot(),
                                    request.presentationSecrets())));
            toolCalls.add(
                    new TurnEvent.ToolCall(call.callId(), call.toolName(),
                            ToolPresentationProjector.prepared(call, request.workspaceRoot(),
                                    request.presentationSecrets()), call.ordinal()));
        }
        persistence.emit(
                request,
                state,
                new TurnEvent.ModelStepCommitted(
                        persistence.draftContext(request, state),
                        messageId,
                        publicText,
                        reasoningSummary,
                        round,
                        usage,
                        toolCalls),
                facts,
                sink);
    }

    /**
     * 先关闭 Turn 级 MCP 会话，再让最新取消声明覆盖候选结果，最后竞争唯一终态提交。
     */
    private TurnResult terminalAfterMcpClose(
            TurnExecutionPlan request,
            TurnEventSink sink,
            AgentLoop.RuntimeState state,
            CancellationToken cancellation,
            TerminalCoordinator terminalCoordinator,
            TurnState target,
            String summary,
            ModelMessage finalMessage,
            ModelUsage usage,
            int modelRound,
            boolean persistUsage,
            String errorCode,
            String errorMessage,
            TurnMcpOwner mcp) {
        try {
            mcp.close();
        } catch (RuntimeException closeFailure) {
            if (!cancellation.isCancellationRequested()) {
                target = TurnState.FAILED;
                finalMessage = null;
                errorCode = "MCP_SERVER_UNAVAILABLE";
                errorMessage = "MCP cleanup failed";
            }
        }
        /* Provider 最后一次检查 Token 后，已持久化的取消声明仍可取得优先权。 */
        if (cancellation.isCancellationRequested()) {
            target = TurnState.CANCELLED;
            finalMessage = null;
            errorCode = null;
            errorMessage = null;
        }
        return persistence.terminal(
                request,
                sink,
                state,
                terminalCoordinator,
                target,
                summary,
                finalMessage,
                usage,
                modelRound,
                persistUsage,
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
     * 为整个 Tool batch 预留 Turn 预算，超限时拒绝整批而不留下部分计数。
     */
    private static void reserveToolBudget(
            AgentLoop.RuntimeState state, TurnExecutionPlan command, int count) {
        if (count < 0 || state.toolCalls + count > command.limits().maxToolCalls()) {
            throw new AgentLoop.LoopFailure("BUDGET_EXCEEDED", "Tool call limit reached");
        }
        state.toolCalls += count;
    }

    /**
     * 逐条消费指定 FIFO，并采用 Repository 返回的权威 revision；无输入时不改变任何状态。
     */
    private boolean consumeQueuedInput(TurnExecutionPlan request, AgentLoop.RuntimeState state,
                                       ConversationRepository.InputKind kind) {
        return store.consumeInput(request.threadId(), request.turnId(), kind,
                        state.turnMutationVersion, clock.instant())
                .map(consumption -> {
                    state.threadRevision = consumption.threadRevision();
                    state.turnMutationVersion = consumption.turnMutationVersion();
                    return true;
                }).orElse(false);
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
