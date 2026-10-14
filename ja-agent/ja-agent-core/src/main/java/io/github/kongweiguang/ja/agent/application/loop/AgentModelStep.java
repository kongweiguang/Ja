// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.application.api.TurnEventSink;
import io.github.kongweiguang.ja.agent.application.context.ContextException;
import io.github.kongweiguang.ja.agent.application.context.ContextOrchestrator;
import io.github.kongweiguang.ja.agent.application.context.ContextTokenMeter;
import io.github.kongweiguang.ja.agent.application.context.summary.ModelSummaryGenerator;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.error.OutputSchemaException;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.model.ModelContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.ToolArgumentValidator;
import io.github.kongweiguang.ja.agent.application.gateway.model.SummaryModel;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken.Registration;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.AssistantMessage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ModelRequestIdentity;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState.Common;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState.ProviderPending;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState.Ready;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware.ExtensionDecision;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware.ExtensionStateView;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware.RequestPatch;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry.MiddlewareRegistration;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort.ModelConfiguration;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.RecordWrite;

/**
 * 一次模型阶段共用上下文规划、请求意图和响应结算；不持有第二套执行游标。
 */
final class AgentModelStep {
    private static final Set<String> RETRYABLE_FAILURES = Set.of(
        "MODEL_UNAVAILABLE", "MODEL_STREAM_INVALID", "MODEL_IDLE_TIMEOUT");
    private final Host host;
    private int failedAttempts;

    /**
     * 请求控制、事件与事务仍归 Session，单步服务只解释当前 Provider 边界。
     */
    AgentModelStep(Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * 宿主可补充非敏感属性，但不能覆盖实际请求产生的配置、Prompt 或工具目录证据。
     */
    private static void requireAuditValue(Map<String, String> attributes, String key, String actual) {
        String supplied = attributes.putIfAbsent(key, actual);
        if (supplied != null && !supplied.equals(actual)) {
            throw new IllegalArgumentException("model identity attribute conflicts with actual request: " + key);
        }
    }

    /**
     * 冻结计量候选并绑定 Provider ordinal；压缩请求推进游标后按新身份重建候选，checkpoint 来源仍延迟到提交后确定。
     */
    void advance() {
        prepare(false);
    }

    /**
     * 手动压缩复用同一计量与检查点协议，但绝不发送普通助手请求或运行工具。
     */
    ContextOrchestrator.Execution<Void> compact() {
        return prepare(true);
    }

    /**
     * 强制压缩只改变规划意图；计量同时保留基础展示值与准入上界，借用 Runtime 由 Engine 在执行权周期结束后关闭。
     */
    @SuppressWarnings("PMD.CloseResource")
    private ContextOrchestrator.Execution<Void> prepare(boolean forceCompaction) {
        AgentRequestRuntime runtime = host.runtime();
        AgentExecutionDriver.Services services = host.services();
        Optional<JsonObject> outputSchema = host.outputSchema();
        if (outputSchema.isPresent()) {
            validateOutputSchema(outputSchema, runtime.model().supportsOutputSchema(runtime.modelConfiguration()),
                    services.toolArgumentValidator());
        }
        io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession.PreparedPrompt preliminary = runtime.promptSession().prepare("", runtime.extensions().tools().stream().map(AgentTool::spec).toList());
        ModelRequestIdentity profile = profile(preliminary.budget(), preliminary.snapshot().revision());
        AgentRequestContext context = new AgentRequestContext(host.identity(), runtime, services.mapper(), host.checkpoints(),
            host.cancellation(), services.clock(), () -> new ModelSummaryGenerator.RequestRuntime(
            new SummaryModel.TurnBinding(host.identity().threadId(), runtime.modelConfiguration(),
                services.clock().instant().plus(runtime.modelConfiguration().requestTimeout()), host.cancellation()),
            Optional.of(profile), () -> {
        }), host.summaryOperation());
        Map<PromptKey, FrozenRequest> candidates = new HashMap<>();
        ContextTokenMeter meter = (messages, summary, continuation, compacted) -> {
            io.github.kongweiguang.ja.agent.application.context.ContextOrchestrator.PreparedPrompt prompt = new ContextOrchestrator.PreparedPrompt(messages, summary, 0, continuation, compacted);
            int modelRequestOrdinal = host.cursor().common().nextProviderOrdinal();
            PromptKey key = new PromptKey(messages, summary, compacted, modelRequestOrdinal);
            FrozenRequest frozen = candidates.computeIfAbsent(key, ignored -> freeze(prompt, modelRequestOrdinal));
            return new ContextTokenMeter.Measurement(frozen.estimate().conservativeUpperBound(),
                    frozen.estimate().estimatedTokens(), frozen.estimate().fingerprint());
        };
        AgentRequestContext.Request request = new AgentRequestContext.Request(preliminary.budget(), forceCompaction, Optional.empty(),
            runtime.outputLimits(), new CheckpointStore.ProjectionBinding(runtime.bindingFingerprint(), catalogFingerprint()),
            meter, services.records(), services.json(), frozenCapabilities());
        AgentLoopPersistence.Restored snapshot = host.restored();
        AgentLoopPersistence.ConversationHistory history = new AgentLoopPersistence.ConversationHistory(snapshot.conversation(), snapshot.executions(), snapshot.records());
        return context.prepare(history, host.historySelection(), host.cursor().common(), request,
            receipt -> { /* 检查点事务的持久事件已由 Session 发布，不重复投递。 */ }, prompt -> {
                int modelRequestOrdinal = host.cursor().common().nextProviderOrdinal();
                FrozenRequest frozen = candidates.get(new PromptKey(prompt.messages(), prompt.summary(),
                    prompt.localCompaction(), modelRequestOrdinal));
                if (frozen == null) {
                    throw new IllegalStateException("unmeasured Provider envelope");
                }
                if (!forceCompaction) {
                    send(frozen, context.summarySourceFor(prompt.summary()));
                }
                return null;
            }, before -> host.beginCompaction(history.conversation().revision(), before));
    }

    /**
     * 在准备提示词与 Provider 请求前拒绝不能满足 object 返回契约的 Schema，避免产生无效网络请求。
     * Schema 编译异常可能包含用户正文；公开的预检故障只保留固定诊断，不传播原始 cause。
     * 与首次准入共用领域分类，恢复时配置能力变化也能得到相同的明确错误。
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    static void validateOutputSchema(Optional<JsonObject> outputSchema, boolean modelSupportsOutputSchema,
                                    ToolArgumentValidator validator) {
        Objects.requireNonNull(outputSchema, "outputSchema");
        Objects.requireNonNull(validator, "validator");
        if (outputSchema.isEmpty()) {
            return;
        }
        JsonObject schema = outputSchema.orElseThrow();
        if (!allowsObjectOutput(schema)) {
            throw OutputSchemaException.invalid();
        }
        try {
            // 空对象可能缺少 required 字段；仍需调用校验器以便在派发前完成 Schema 编译。
            validator.invalidReason(schema, JsonObject.empty());
        } catch (IllegalArgumentException invalidSchema) {
            throw OutputSchemaException.invalid();
        }
        if (!modelSupportsOutputSchema) {
            throw OutputSchemaException.unsupported();
        }
    }

    /**
     * 根 Schema 未限制类型时仍可校验 object；显式 type 则必须包含 object，匹配 JSON 解码器契约。
     */
    private static boolean allowsObjectOutput(JsonObject schema) {
        JsonValue declaredType = schema.get("type");
        if (declaredType == null) {
            return true;
        }
        if (declaredType instanceof JsonText type) {
            return "object".equals(type.value());
        }
        return declaredType instanceof JsonArray types && types.values().stream()
                .anyMatch(type -> type instanceof JsonText value && "object".equals(value.value()));
    }

    /**
     * 计量候选复用完整信封及待提交补丁，并在冻结时绑定当前 Provider ordinal；Runtime 仅借用，关闭责任仍属于外层 Engine。
     */
    @SuppressWarnings("PMD.CloseResource")
    private FrozenRequest freeze(ContextOrchestrator.PreparedPrompt prompt, int modelRequestOrdinal) {
        AgentRequestRuntime runtime = host.runtime();
        String modelRequestId = host.id("modelreq", "provider:" + modelRequestOrdinal);
        ModelPort.RequestDiagnostics diagnostics = new ModelPort.RequestDiagnostics(
            host.identity().threadId(), host.identity().turnId(), modelRequestId, modelRequestOrdinal);
        List<AgentTool> tools = runtime.extensions().tools();
        AgentPromptSession.PreparedPrompt prepared = runtime.promptSession().prepare(
            prompt.summary().hasNoFacts() ? "" : prompt.summary().toPromptText(), tools.stream().map(AgentTool::spec).toList());
        ModelPort.ModelRequest base = host.services().mapper().toModelRequest(prompt, runtime.modelConfiguration(),
            prepared.snapshot(), tools, null, host.cursor().common().modelRound() + 1, host.identity().threadId(),
            runtime.attachments(), runtime.model().nativeAttachmentSupport(runtime.modelConfiguration()),
            host.outputSchema());
        StringBuilder system = new StringBuilder(base.prompt().systemPrompt());
        List<ModelMessage> messages = new ArrayList<>(base.messages());
        List<AgentStore.RecordWrite> writes = new ArrayList<>();
        Set<String> visible = tools.stream().map(tool -> tool.spec().name()).collect(java.util.stream.Collectors.toSet());
        for (MiddlewareRegistration registration : runtime.extensions().middleware()) {
            ExtensionStateView state = host.view().middleware().get(registration.namespace());
            ExtensionDecision<RequestPatch> decision = Objects.requireNonNull(registration.middleware().beforeRequest(new AgentMiddleware.RequestContext(
                    host.identity(), system.toString(), List.copyOf(messages), tools.stream().map(AgentTool::spec).toList(), state)),
                "request extension decision");
            RequestPatch patch = decision.decision();
            if (!patch.instructionSuffix().isEmpty()) {
                system.append('\n').append(patch.instructionSuffix());
            }
            messages.addAll(patch.contextAdditions());
            if (patch.visibleToolNames().isPresent()) {
                Set<String> requested = patch.visibleToolNames().orElseThrow();
                Set<String> registered = tools.stream().map(tool -> tool.spec().name()).collect(java.util.stream.Collectors.toSet());
                if (!registered.containsAll(requested)) {
                    throw new IllegalArgumentException("request exposed an unregistered Tool");
                }
                visible.retainAll(requested);
            }
            host.appendMiddleware(writes, registration, decision, "request:" + host.cursor().common().nextProviderOrdinal());
        }
        String finalSystem = system.toString();
        String revision = "prompt_" + host.hash(finalSystem);
        ModelPort.ModelRequest request = new ModelPort.ModelRequest(base.configuration(),
            new ModelPort.PromptPayload(finalSystem, revision), messages,
            tools.stream().filter(tool -> visible.contains(tool.spec().name())).map(AgentTool::spec).toList(),
            null, base.round(), ModelPort.RequestDeadlinePolicy.TURN_MANAGED, diagnostics,
            base.outputSchema());
        ModelPort.InputTokenEstimate estimate = runtime.model().estimateInputTokens(request, host.cancellation());
        return new FrozenRequest(request, prepared.budget(), prepared.snapshot().revision(), estimate, List.copyOf(writes));
    }

    /**
     * ProviderPending 与本地估算先原子提交，随后消费 Prompt 提示并确认执行权；派发复用冻结诊断 ID，避免 ordinal 错配。
     */
    private void send(FrozenRequest frozen, AgentRequestContext.SummarySource summarySource) {
        Objects.requireNonNull(summarySource, "summarySource");
        Ready current = (TurnExecutionState.Ready) host.cursor();
        ModelPort.RequestDiagnostics diagnostics = Objects.requireNonNull(
            frozen.request().diagnostics(), "frozen model request diagnostics");
        if (diagnostics.modelRequestOrdinal() != current.common().nextProviderOrdinal()) {
            throw new IllegalStateException("frozen model request ordinal does not match the execution cursor");
        }
        String modelRequestId = diagnostics.modelRequestId();
        String messageId = host.id("item", modelRequestId);
        Common common = new TurnExecutionState.Common(current.common().modelRound(), current.common().usedToolCalls(),
            current.common().nextProviderOrdinal(), frozen.toolGuardRevision(),
            host.runtime().promptSession().activeSkillReferences());
        Ready ready = new TurnExecutionState.Ready(common, TurnExecutionState.Next.ASSISTANT, null);
        ProviderPending pending = new TurnExecutionState.ProviderPending(common, modelRequestId, messageId,
            TurnExecutionState.ProviderPurpose.ASSISTANT, profile(frozen.budget(), frozen.request().prompt().revision()),
            frozen.estimate().fingerprint(), ready);
        List<AgentStore.RecordWrite> intentWrites = new ArrayList<>(frozen.middlewareWrites());
        ExecutionRecordCodec.ModelCapabilities capabilities = new ExecutionRecordCodec.ModelCapabilities(messageId,
            frozen.request().tools().stream().map(ToolSpec::name).toList(),
            summarySource.checkpointId(), summarySource.seedSummaryRef());
        intentWrites.add(host.services().records().encodeModelCapabilities(
            host.restored().execution().key(), capabilities, host.services().clock().instant()));
        RecordWrite estimateWrite = estimatedUsage(pending, frozen.estimate().estimatedTokens());
        intentWrites.add(estimateWrite);
        host.commit(TurnState.RUNNING, pending, intentWrites,
            List.of(new ExecutionEvent.ContextUsageEstimated(estimateWrite.recordId())));
        host.runtime().promptSession().requestCommitted();
        host.assertDispatchAuthority();
        AgentRound round = new AgentRound(host.identity(), modelRequestId, host.cancellation(), host.stream(),
            host.streamSequence(), host.services().clock());
        ModelPort.ModelOutcome result;
        try {
            host.cancellation().throwIfCancellationRequested();
            result = Objects.requireNonNull(host.runtime().model().start(frozen.request(), round, host.cancellation())
                .toCompletableFuture().join(), "model outcome");
            round.close();
        } catch (RuntimeException failure) {
            round.close();
            round.reset("MODEL_REQUEST_FAILED");
            handleFailure(pending, failure, round.usage());
            return;
        }
        if (round.message() == null) {
            handleFailure(pending, new IllegalStateException("empty Provider response"), result.usage());
            return;
        }
        if (round.calls().isEmpty() && result.finishReason() == ModelPort.FinishReason.TOOL_CALLS) {
            handleFailure(pending, new IllegalStateException("Provider finish reason has no Tool calls"), result.usage());
            return;
        }
        ModelUsage usage = result.usage() != null ? result.usage() : round.usage();
        if (round.calls().isEmpty() && result.finishReason() == ModelPort.FinishReason.STOP) {
            Optional<String> schemaFailure = outputSchemaFailure(round.message());
            if (schemaFailure.isPresent()) {
                handleFailure(pending, new OutputSchemaValidationFailure(schemaFailure.orElseThrow()), usage);
                return;
            }
        }
        for (int index = 0; index < round.calls().size(); index++) {
            if (round.calls().get(index).ordinal() != index) {
                handleFailure(pending, new IllegalStateException("Tool ordinal sequence is not contiguous"),
                    usage);
                return;
            }
            String callId = round.calls().get(index).callId();
            boolean reused = host.view().latest(ExecutionFacts.AssistantMessage.class, message ->
                message.message().content().stream().anyMatch(content ->
                    content instanceof io.github.kongweiguang.ja.agent.domain.model.ToolCallContent call
                    && call.callId().equals(callId))).isPresent();
            if (reused) {
                handleFailure(pending, new IllegalStateException("Provider reused a committed Tool call identity"),
                    usage);
                return;
            }
        }
        Optional<RecordWrite> usageWrite = usageSettlement(pending, usage);
        String usageRecordId = usageWrite.map(RecordWrite::recordId).orElseGet(estimateWrite::recordId);
        int modelRound = Math.addExact(common.modelRound(), 1);
        AssistantMessage assistant = new ExecutionFacts.AssistantMessage(messageId, round.message(), round.partialText(),
            round.reasoningSummary(), modelRound);
        RecordWrite assistantWrite = host.fact(assistant, messageId);
        Common completed = new TurnExecutionState.Common(modelRound, common.usedToolCalls(),
            Math.addExact(common.nextProviderOrdinal(), 1), common.promptRevision(), common.activeSkills());
        TurnExecutionState next;
        if (round.calls().isEmpty()) {
            next = new TurnExecutionState.Ready(completed, result.finishReason() == ModelPort.FinishReason.MAX_OUTPUT_TOKENS
                ? TurnExecutionState.Next.ASSISTANT : TurnExecutionState.Next.COMPLETION, null);
        } else {
            next = new TurnExecutionState.Tools(completed, host.id("batch", modelRequestId), messageId,
                0, round.calls().size() - 1, 0);
        }
        List<RecordWrite> completionWrites = new ArrayList<>(List.of(assistantWrite));
        usageWrite.ifPresent(completionWrites::add);
        List<ExecutionEvent> completionEvents = new ArrayList<>();
        completionEvents.add(new ExecutionEvent.ModelCompleted(assistantWrite.recordId(), modelRound,
                round.calls().stream().map(AgentTool.Invocation::callId).toList(), usageRecordId));
        if (round.calls().isEmpty() && result.finishReason() == ModelPort.FinishReason.STOP) {
            completionEvents.add(new ExecutionEvent.AssistantSettled(assistantWrite.recordId()));
        }
        host.commit(TurnState.RUNNING, next, completionWrites, completionEvents);
        failedAttempts = 0;
    }

    /**
     * 仅令紧邻工具批次的恢复请求沿用原摘要引用，避免旧调用被更新后的会话摘要覆盖。
     */
    private Optional<ExecutionRecordCodec.ModelCapabilities> frozenCapabilities() {
        if (!(host.cursor() instanceof TurnExecutionState.Ready ready)) {
            return Optional.empty();
        }
        return host.view().latest(ExecutionFacts.AssistantMessage.class, ignored -> true)
            .filter(message -> message.modelRound() == ready.common().modelRound()
                               && message.message().content().stream().anyMatch(
                io.github.kongweiguang.ja.agent.domain.model.ToolCallContent.class::isInstance))
            .map(message -> host.view().modelCapabilities(message.messageId(), host.services()));
    }

    /**
     * 可恢复故障仅重试同一模型；失败轮保留估算与真实用量，取消交由 Session 核对持久暂停/取消事实。
     */
    private void handleFailure(TurnExecutionState.ProviderPending pending, RuntimeException original,
                               ModelUsage usage) {
        Throwable cause = original;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String code = cause instanceof OutputSchemaValidationFailure ? "OUTPUT_SCHEMA_VALIDATION_FAILED"
            : cause instanceof ModelPort.ModelUnavailableException provider ? provider.terminalErrorCode()
            : cause instanceof java.util.concurrent.CancellationException ? "CANCELLED" : "MODEL_PROTOCOL_ERROR";
        boolean retry = isRetryableFailure(code);
        int attempt = failedAttempts == Integer.MAX_VALUE ? failedAttempts : ++failedAttempts;
        long baseMillis = Math.min(30_000L, 500L << Math.min(16, Math.max(0, attempt - 1)));
        Duration delay = Duration.ofMillis((long) (baseMillis * java.util.concurrent.ThreadLocalRandom.current().nextDouble(0.75, 1.0)));
        if (cause instanceof ModelPort.ModelUnavailableException provider && provider.retryAfterHint().isPresent()) {
            delay = provider.retryAfterHint().orElseThrow();
        }
        Ready next = pending.resume().advanceProviderOrdinal();
        List<ExecutionEvent> events = retry ? List.of(new ExecutionEvent.RetryScheduled(
            pending.modelRequestId(), attempt, delay.toMillis(), code)) : List.of();
        host.commit(TurnState.RUNNING, next, usageSettlement(pending, usage).stream().toList(), events);
        if (cause instanceof ModelPort.ContextOverflowException) {
            throw new ContextException(ContextException.Code.CONTEXT_LIMIT, "provider context window exceeded", cause);
        }
        if (!retry) {
            if (cause instanceof java.util.concurrent.CancellationException cancelled) {
                throw cancelled;
            }
            String message = cause instanceof OutputSchemaValidationFailure validation
                    ? "Final response did not satisfy the required JSON Schema: " + validation.getMessage()
                    : "Model request failed";
            host.terminate(TurnState.FAILED, code, message);
            return;
        }
        CountDownLatch wakeup = new CountDownLatch(1);
        Registration registration = host.cancellation().onCancellation(wakeup::countDown);
        try (registration) {
            try {
                if (wakeup.await(delay.toNanos(), TimeUnit.NANOSECONDS)) {
                    host.cancellation().throwIfCancellationRequested();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                java.util.concurrent.CancellationException cancelled =
                        new java.util.concurrent.CancellationException("model retry interrupted");
                cancelled.initCause(interrupted);
                throw cancelled;
            }
        }
        host.cancellation().throwIfCancellationRequested();
    }

    /**
     * Provider 声明支持结构化输出不代表结果有效；提交前仍须按冻结 Schema 在本地复核终答。
     */
    private Optional<String> outputSchemaFailure(ModelMessage message) {
        Optional<JsonObject> schema = host.outputSchema();
        if (schema.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder();
        for (ModelContent block : Objects.requireNonNull(message, "message").content()) {
            if (!(block instanceof TextContent value)) {
                return Optional.of("response contains non-text content");
            }
            text.append(value.text());
        }
        final JsonObject output;
        try {
            output = host.services().json().decodeObject(text.toString());
        } catch (IllegalArgumentException malformed) {
            return Optional.of("response is not a valid JSON object");
        }
        return host.services().toolArgumentValidator().invalidReason(schema.orElseThrow(), output);
    }

    /**
     * 估算与响应结算使用不同稳定键，Store 才能保留派发前事实并追加真实用量。
     */
    private AgentStore.RecordWrite estimatedUsage(TurnExecutionState.ProviderPending pending, long estimate) {
        ExecutionFacts.Usage fact = new ExecutionFacts.Usage(pending.modelRequestId(), null,
            Math.max(1, pending.common().modelRound() + 1), pending.common().nextProviderOrdinal(),
            ExecutionFacts.UsagePurpose.ASSISTANT, ExecutionFacts.UsageCertainty.UNKNOWN, pending.profile(), estimate);
        String recordId = AgentExecutionDriver.estimatedUsageRecordId(
            host.restored().execution().key(), pending.modelRequestId());
        return host.services().records().encode(recordId, fact, host.services().clock().instant());
    }

    /**
     * Provider 未返回用量时沿用派发前估算；只有真实用量形成独立的已知结算事实。
     */
    private Optional<AgentStore.RecordWrite> usageSettlement(TurnExecutionState.ProviderPending pending, ModelUsage usage) {
        if (usage == null) {
            return Optional.empty();
        }
        ExecutionFacts.Usage fact = new ExecutionFacts.Usage(pending.modelRequestId(), usage,
            Math.max(1, pending.common().modelRound() + 1), pending.common().nextProviderOrdinal(),
            ExecutionFacts.UsagePurpose.ASSISTANT, ExecutionFacts.UsageCertainty.KNOWN, pending.profile(), null);
        return Optional.of(host.fact(fact, pending.modelRequestId()));
    }

    /**
     * 保留 Provider adapter 的瞬时不可用分类为持续重试，不把确定性协议错误伪装成瞬时故障。
     */
    static boolean isRetryableFailure(String code) {
        return RETRYABLE_FAILURES.contains(Objects.requireNonNull(code, "code"));
    }

    /**
     * 模型审计只存路由、能力和非敏感配置代际，不序列化 API key 或服务地址。
     */
    private ExecutionFacts.ModelRequestIdentity profile(ContextBudget budget, String promptRevision) {
        ModelConfiguration configuration = host.runtime().modelConfiguration();
        String api = switch (configuration.api()) {
            case ANTHROPIC_MESSAGES -> "anthropic_messages";
            case OPENAI_RESPONSES -> "openai_responses";
            case OPENAI_CHAT_COMPLETIONS -> "openai_chat_completions";
        };
        Map<String, String> audit = new TreeMap<>(host.runtime().modelIdentityAttributes());
        requireAuditValue(audit, "configGeneration", configuration.configGeneration());
        requireAuditValue(audit, "bindingFingerprint", host.runtime().bindingFingerprint());
        requireAuditValue(audit, "promptRevision", promptRevision);
        requireAuditValue(audit, "toolCatalogRevision", catalogFingerprint());
        Map<String, JsonValue> attributes = new TreeMap<>();
        audit.forEach((key, value) -> attributes.put(key, new JsonText(value)));
        return new ExecutionFacts.ModelRequestIdentity(configuration.providerId(), configuration.modelId(), api,
            configuration.model(), configuration.generation().reasoningLevel(), configuration.generation().reasoningLevel(),
            Math.toIntExact(budget.contextWindowTokens()), Math.toIntExact(budget.maxOutputTokens()),
            new JsonObject(attributes));
    }

    /**
     * 工具目录摘要保留显式注册顺序及 schema，动态可见集合仍在真实 envelope 中单独计量。
     */
    private String catalogFingerprint() {
        return host.hash(host.runtime().extensions().tools().stream().map(tool -> tool.spec().name()
                                                                                  + ":" + AgentTool.schemaFingerprint(tool.spec())).collect(java.util.stream.Collectors.joining("\n")));
    }

    /**
     * Session 是唯一事务所有者，模型步骤只借用必要能力，不接触 Ja 或数据库实现。
     */
        interface Host extends AgentSummaryOperation.Host {
        /**
         * 只借用本次冻结 runtime，不扩展资源关闭权。
         */
        AgentRequestRuntime runtime();

        /**
         * 返回与当前游标同一水位的控制及扩展状态。
         */
        ExecutionStateView view();

        /**
         * 取消传播到本地估算、网络与退避等待。
         */
        CancellationToken cancellation();

        /**
         * 草稿通道不得阻塞持久执行。
         */
        TurnEventSink stream();

        /**
         * 跨请求共用序号，重试不会倒退或重用旧帧身份。
         */
        LongSupplier streamSequence();

        /**
         * 所有检查点写入必须经过 Session 的执行 CAS。
         */
        CheckpointStore checkpoints();

        /**
         * 摘要复用同一持久状态机，不使用进程内恢复占位。
         */
        ModelSummaryGenerator.SummaryOperation summaryOperation();

        /**
         * 准入冻结的历史选择不能在恢复时重新猜测。
         */
        AgentExecutionRequest.HistorySelection historySelection();

        /**
         * 返回准入时冻结的终答 Schema；恢复不能按当前 CLI 参数替换旧执行约束。
         */
        Optional<JsonObject> outputSchema();

        /**
         * 将纯扩展决策编码为随当前请求一起提交的记录。
         */
        void appendMiddleware(List<AgentStore.RecordWrite> writes, ExtensionRegistry.MiddlewareRegistration registration,
                              AgentMiddleware.ExtensionDecision<?> decision, String boundary);

        /**
         * 事实身份由执行所有者统一派生。
         */
        AgentStore.RecordWrite fact(io.github.kongweiguang.ja.agent.domain.execution.ExecutionFact fact, String key);

        /**
         * 稳定键包含完整租户、会话与执行身份。
         */
        String id(String prefix, String key);

        /**
         * 哈希必须采用与其他执行事实相同的编码约定。
         */
        String hash(String value);

        /**
         * 终态只有 Session 确认提交后才能对外宣布。
         */
        void terminate(TurnState state, String code, String message);

        /**
         * 由上下文策略的真实计量触发，必须在摘要 IO 之前确认持久 Started。
         */
        void beginCompaction(long sourceRevision, long inputTokensBefore);
    }

    /**
     * Token 计量值不参与候选身份，但 Provider ordinal 必须参与，避免压缩期间推进调用游标后复用旧请求身份。
     *
     * @param messages  消息列表
     * @param summary   摘要
     * @param compacted 是否已压缩上下文
     * @param modelRequestOrdinal 当前冻结请求占用的 Provider 顺序位置
     */
    private record PromptKey(List<ContextMessage> messages,
                             SummaryDocument summary,
                             boolean compacted,
                             int modelRequestOrdinal) {
    }

    /**
     * 已计量请求与中间件补丁一起冻结，发送阶段不再读取变化中的工具目录。
     *
     * @param request           请求
     * @param budget            预算
     * @param toolGuardRevision 工具校验修订版本
     * @param estimate          估算
     * @param middlewareWrites  中间件写入列表
     */
    private record FrozenRequest(ModelPort.ModelRequest request,
                                 ContextBudget budget,
                                 String toolGuardRevision,
                                 ModelPort.InputTokenEstimate estimate,
                                 List<AgentStore.RecordWrite> middlewareWrites) {
    }

    /**
     * 校验失败是确定性终态，不能误判为可重试的 Provider 故障。
     */
    private static final class OutputSchemaValidationFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /**
         * 禁用堆栈生成，使校验失败只携带稳定的脱敏消息用于终态映射。
         */
        private OutputSchemaValidationFailure(String safeMessage) {
            super(Objects.requireNonNull(safeMessage, "safeMessage"), null, false, false);
        }
    }
}
