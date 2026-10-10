// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.tool.ToolExecutionBinding;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.ToolArgumentValidator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 按冻结扩展顺序准备和调用单个 Tool；持久提交及恢复裁决由 AgentExecutionDriver 负责。
 */
final class AgentToolRunner {
    private static final int MAX_FACTS_PER_TRANSITION = 256;

    private final ToolArgumentValidator argumentValidator;
    private final List<ExtensionRegistry.MiddlewareRegistration> middleware;

    /**
     * 冻结注册顺序与中间件状态 schema，避免一次运行中授权和结果投影观察不同能力版本。
     */
    AgentToolRunner(ToolArgumentValidator argumentValidator,
                    List<ExtensionRegistry.MiddlewareRegistration> middleware) {
        this.argumentValidator = Objects.requireNonNull(argumentValidator, "argumentValidator");
        this.middleware = List.copyOf(Objects.requireNonNull(middleware, "middleware"));
        if (this.middleware.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("middleware list contains null");
        }
        if (this.middleware.stream().map(ExtensionRegistry.MiddlewareRegistration::namespace)
                    .distinct().count() != this.middleware.size()) {
            throw new IllegalArgumentException("duplicate middleware namespace");
        }
    }

    /**
     * 中间件不得通过状态对象宣称与 Registry 不同的 namespace 或 schema。
     */
    private static AgentMiddleware.ExtensionStateView state(
            ExtensionRegistry.MiddlewareRegistration registration,
            Map<String, AgentMiddleware.ExtensionStateView> states) {
        AgentMiddleware.ExtensionStateView value = Objects.requireNonNull(
                states.get(registration.namespace()), "middleware state");
        if (!value.namespace().equals(registration.namespace())
            || value.schemaVersion() != registration.stateSchemaVersion()) {
            throw new IllegalArgumentException("middleware state identity mismatch");
        }
        return value;
    }

    /**
     * 校验 patch 对应输入 revision，并把事实 namespace 固定到 Registry owner。
     */
    private static MiddlewareTransition transition(ExtensionRegistry.MiddlewareRegistration registration,
                                                   AgentMiddleware.ExtensionStateView state, AgentMiddleware.ExtensionDecision<?> decision) {
        AgentMiddleware.ExtensionDecision<?> value = Objects.requireNonNull(decision, "middleware decision");
        AgentMiddleware.ExtensionStatePatch patch = value.statePatch();
        if (patch.expectedRevision() != state.revision()) {
            throw new IllegalArgumentException("middleware returned a stale state patch");
        }
        patch.apply(state);
        List<AgentMiddleware.MiddlewareFact> facts = List.copyOf(value.facts());
        facts.forEach(fact -> Objects.requireNonNull(fact, "middleware fact"));
        if (facts.size() > MAX_FACTS_PER_TRANSITION) {
            throw new IllegalArgumentException("too many middleware facts");
        }
        return new MiddlewareTransition(registration.namespace(), patch, facts);
    }

    /**
     * 保留调用位置与身份，只替换中间件明确产生的新参数对象。
     */
    private static AgentTool.Invocation withArguments(AgentTool.Invocation current, JsonObject arguments) {
        return new AgentTool.Invocation(current.callId(), current.toolName(), arguments, current.ordinal(),
                current.preflightFailureCode());
    }

    /**
     * 冻结所有 intent 输入，准备事实只在真正进入可派发态时由宿主生成。
     */
    private static PreparedCall prepared(AgentTool tool, AgentTool.Invocation invocation,
                                         AgentTool.ExecutionContext execution, ToolSideEffect sideEffect,
                                         AgentTool.ApprovalRequirement approvalRequirement, Optional<ToolExecutionBinding> binding,
                                         List<AgentTool.ExtensionFact> preparedFacts, List<MiddlewareTransition> transitions) {
        return new PreparedCall(tool, invocation, execution, sideEffect, approvalRequirement, binding,
                preparedFacts, transitions);
    }

    /**
     * 动态副作用声明必须针对 middleware 最终参数重新读取。
     */
    private static ToolSideEffect sideEffect(AgentTool tool, AgentTool.Invocation invocation) {
        return Objects.requireNonNull(tool.sideEffect(invocation), "tool side effect");
    }

    /**
     * 最终参数变化后重算审批要求，不能沿用旧动作的许可。
     */
    private static AgentTool.ApprovalRequirement approvalRequirement(
            AgentTool tool, AgentTool.Invocation invocation) {
        return Objects.requireNonNull(tool.approvalRequirement(invocation), "tool approval requirement");
    }

    /**
     * 强制必需审批只能来自明确等待策略，并要求策略提供可恢复期限，不替宿主猜测 TTL。
     */
    private static void validateApprovalWait(AgentTool.ApprovalRequirement requirement,
                                             Optional<AgentMiddleware.WaitRequest> wait) {
        if (requirement == AgentTool.ApprovalRequirement.NOT_REQUIRED) {
            return;
        }
        AgentMiddleware.WaitRequest request = wait.orElseThrow(
                () -> new IllegalStateException("required Tool approval has no explicit wait policy"));
        if (!request.kind().equals("approval")) {
            throw new IllegalStateException("required Tool approval needs an approval wait request");
        }
        if (!(request.payload().members().get("expiresAt") instanceof io.github.kongweiguang.ja.agent.domain.json.JsonText(
                String value
        ))) {
            throw new IllegalStateException("approval wait request must include expiresAt");
        }
        try {
            Instant.parse(value);
        } catch (java.time.DateTimeException invalid) {
            throw new IllegalStateException("approval wait request has invalid expiresAt", invalid);
        }
    }

    /**
     * 拒绝或等待分支没有 binding，绝不能越过副作用边界进入执行或恢复。
     */
    private static PreparedCall dispatchable(PreparedCall call) {
        PreparedCall prepared = Objects.requireNonNull(call, "call");
        if (prepared.binding().isEmpty()) {
            throw new IllegalArgumentException("Tool call is not dispatchable");
        }
        return prepared;
    }

    /**
     * 冻结宿主事实集合并限制单一事务载荷数，避免扩展以无界记录拖垮恢复页。
     */
    private static List<AgentTool.ExtensionFact> immutableFacts(List<AgentTool.ExtensionFact> values, String field) {
        List<AgentTool.ExtensionFact> facts = List.copyOf(Objects.requireNonNull(values, field));
        facts.forEach(value -> Objects.requireNonNull(value, field));
        if (facts.size() > MAX_FACTS_PER_TRANSITION) {
            throw new IllegalArgumentException("too many " + field);
        }
        return facts;
    }

    /**
     * 把同步校验失败转成模型可修复结果，不回显参数或扩展异常正文。
     */
    private static AgentTool.ToolResult failed(String code, String message) {
        return new AgentTool.ToolResult(ToolOutcome.FAILED, message, Optional.empty(), code);
    }

    /**
     * 派发前取消变成明确终态；派发后取消结局由工具协作结果决定。
     */
    private static AgentTool.ToolResult cancelledResult() {
        return new AgentTool.ToolResult(ToolOutcome.CANCELLED, "", Optional.empty(), "CANCELLED");
    }

    /**
     * 每次调用重新校验被改写参数，并把中间件状态 patch/facts 固定到同一准备提交。
     */
    Preparation prepare(AgentTool tool, AgentTool.Invocation invocation,
                        AgentTool.ExecutionContext execution, AgentTool.ToolBindingContext bindingContext,
                        Map<String, AgentMiddleware.ExtensionStateView> extensionStates) {
        AgentTool requiredTool = Objects.requireNonNull(tool, "tool");
        AgentTool.Invocation initial = Objects.requireNonNull(invocation, "invocation");
        AgentTool.ExecutionContext context = Objects.requireNonNull(execution, "execution");
        AgentTool.ToolBindingContext binding = Objects.requireNonNull(bindingContext, "bindingContext");
        Map<String, AgentMiddleware.ExtensionStateView> states = stateSnapshot(extensionStates);
        if (!context.identity().toolCallKey(initial.callId()).equals(context.callKey())) {
            throw new IllegalArgumentException("tool execution context does not match call identity");
        }
        if (!Objects.requireNonNull(requiredTool.spec(), "tool spec").name().equals(initial.toolName())) {
            throw new IllegalArgumentException("Tool binding name mismatch");
        }

        Optional<AgentTool.ToolResult> invalid = invalidResult(requiredTool, initial);
        if (invalid.isPresent()) {
            return new Rejected(prepared(requiredTool, initial, context, sideEffect(requiredTool, initial),
                    approvalRequirement(requiredTool, initial), Optional.empty(),
                    immutableFacts(requiredTool.preparedFacts(initial, context), "prepared Tool facts"), List.of()),
                    invalid.orElseThrow());
        }

        AgentTool.Invocation current = initial;
        ToolSideEffect currentEffect = sideEffect(requiredTool, current);
        AgentTool.ApprovalRequirement currentApproval = approvalRequirement(requiredTool, current);
        List<MiddlewareTransition> transitions = new ArrayList<>(middleware.size());
        List<PendingWait> waitRequests = new ArrayList<>();
        for (ExtensionRegistry.MiddlewareRegistration registration : middleware) {
            AgentMiddleware middlewarePolicy = registration.middleware();
            AgentMiddleware.ExtensionStateView state = state(registration, states);
            AgentMiddleware.ExtensionDecision<AgentMiddleware.ToolDecision> result = Objects.requireNonNull(
                    middlewarePolicy.beforeTool(new AgentMiddleware.ToolContext(context.identity(), context,
                            current, currentEffect, currentApproval, state)), "middleware tool decision");
            transitions.add(transition(registration, state, result));
            switch (result.decision()) {
                case AgentMiddleware.ToolProceed proceed -> {
                    if (!current.arguments().equals(proceed.arguments())) {
                        current = withArguments(current, proceed.arguments());
                        invalid = invalidResult(requiredTool, current);
                        if (invalid.isPresent()) {
                            PreparedCall rejected = prepared(requiredTool, current, context,
                                    sideEffect(requiredTool, current), approvalRequirement(requiredTool, current),
                                    Optional.empty(), immutableFacts(requiredTool.preparedFacts(current, context),
                                            "prepared Tool facts"), transitions);
                            return new Rejected(rejected, invalid.orElseThrow());
                        }
                    }
                    currentEffect = sideEffect(requiredTool, current);
                    currentApproval = approvalRequirement(requiredTool, current);
                }
                case AgentMiddleware.ToolReject reject -> {
                    PreparedCall rejected = prepared(requiredTool, current, context, currentEffect,
                            approvalRequirement(requiredTool, current), Optional.empty(),
                            immutableFacts(requiredTool.preparedFacts(current, context), "prepared Tool facts"),
                            transitions);
                    return new Rejected(rejected, failed(reject.code(), reject.message()));
                }
                case AgentMiddleware.ToolWait wait -> {
                    AgentMiddleware.WaitRequest request = wait.request();
                    if (waitRequests.stream().noneMatch(existing -> existing.request().equals(request))) {
                        waitRequests.add(new PendingWait(request, current.arguments()));
                    }
                }
            }
        }

        if (waitRequests.size() > 1) {
            throw new IllegalStateException("conflicting Tool wait requests");
        }
        currentEffect = sideEffect(requiredTool, current);
        currentApproval = approvalRequirement(requiredTool, current);
        Optional<AgentMiddleware.WaitRequest> wait = Optional.empty();
        if (!waitRequests.isEmpty()) {
            PendingWait pending = waitRequests.getFirst();
            if (!pending.arguments().equals(current.arguments())) {
                throw new IllegalStateException("Tool arguments changed after a wait request");
            }
            wait = Optional.of(pending.request());
        }
        validateApprovalWait(currentApproval, wait);
        ToolExecutionBinding finalBinding = Objects.requireNonNull(
                requiredTool.executionBinding(current, binding), "tool execution binding");
        if (!finalBinding.toolName().equals(current.toolName())) {
            throw new IllegalArgumentException("Tool execution binding name mismatch");
        }
        List<AgentTool.ExtensionFact> preparedFacts = immutableFacts(
                requiredTool.preparedFacts(current, context), "prepared Tool facts");
        PreparedCall finalCall = prepared(requiredTool, current, context, currentEffect,
                currentApproval, Optional.of(finalBinding), preparedFacts, transitions);
        return wait.<Preparation>map(request -> new Waiting(finalCall, request)).orElseGet(() -> new Ready(finalCall));
    }

    /**
     * 恢复已提交的调用准备快照；只重验当前工具路由与授权绑定，不再重跑旧中间件。
     */
    PreparedCall restorePreparedCall(AgentTool tool, AgentTool.Invocation persistedInvocation,
                                     AgentTool.ExecutionContext execution, AgentTool.ToolBindingContext bindingContext,
                                     ToolExecutionBinding persistedBinding, ToolSideEffect persistedSideEffect,
                                     List<AgentTool.ExtensionFact> persistedFacts) {
        AgentTool requiredTool = Objects.requireNonNull(tool, "tool");
        AgentTool.Invocation invocation = Objects.requireNonNull(persistedInvocation, "persistedInvocation");
        AgentTool.ExecutionContext context = Objects.requireNonNull(execution, "execution");
        AgentTool.ToolBindingContext binding = Objects.requireNonNull(bindingContext, "bindingContext");
        ToolExecutionBinding saved = Objects.requireNonNull(persistedBinding, "persistedBinding");
        if (!context.identity().toolCallKey(invocation.callId()).equals(context.callKey())) {
            throw new IllegalArgumentException("tool execution context does not match call identity");
        }
        if (!Objects.requireNonNull(requiredTool.spec(), "tool spec").name().equals(invocation.toolName())) {
            throw new IllegalArgumentException("Tool binding name mismatch");
        }
        Optional<AgentTool.ToolResult> invalid = invalidResult(requiredTool, invocation);
        if (invalid.isPresent()) {
            throw new IllegalArgumentException("persisted Tool arguments are no longer valid");
        }
        ToolSideEffect effect = sideEffect(requiredTool, invocation);
        AgentTool.ApprovalRequirement approval = approvalRequirement(requiredTool, invocation);
        if (effect != Objects.requireNonNull(persistedSideEffect, "persistedSideEffect")) {
            throw new IllegalStateException("persisted Tool side-effect declaration changed");
        }
        ToolExecutionBinding current = Objects.requireNonNull(
                requiredTool.executionBinding(invocation, binding), "tool execution binding");
        if (!current.equals(saved)) {
            throw new IllegalStateException("persisted Tool binding changed");
        }
        return prepared(requiredTool, invocation, context, effect, approval, Optional.of(saved),
                immutableFacts(persistedFacts, "persisted prepared Tool facts"), List.of());
    }

    /**
     * 只有准备事实已由 Driver 持久提交后才读取恢复证据，且不会派发任何业务动作。
     */
    Optional<AgentTool.RecoveryEvidence> prepareRecoveryEvidence(
            PreparedCall call, CancellationToken cancellation) {
        PreparedCall prepared = dispatchable(call);
        CancellationToken token = Objects.requireNonNull(cancellation, "cancellation");
        token.throwIfCancellationRequested();
        return Objects.requireNonNull(prepared.tool().prepareRecoveryEvidence(
                prepared.invocation(), prepared.execution(), token), "recovery evidence");
    }

    /**
     * 在 Started 事务提交前采集工具执行阶段 facts，失败时调用方仍不得派发副作用。
     */
    List<AgentTool.ExtensionFact> startedFacts(PreparedCall call) {
        PreparedCall prepared = dispatchable(call);
        return immutableFacts(prepared.tool().startedFacts(prepared.invocation(), prepared.execution()),
                "started Tool facts");
    }

    /**
     * 在 Driver 已提交 Started 意图后调用冻结工具；异常保持未结算供恢复核验，不盲目重放。
     */
    CompletionStage<AgentTool.ExecutionResult> execute(PreparedCall call, CancellationToken cancellation) {
        PreparedCall prepared = dispatchable(call);
        CancellationToken token = Objects.requireNonNull(cancellation, "cancellation");
        if (token.isCancellationRequested()) {
            return CompletableFuture.completedFuture(new AgentTool.ExecutionResult(cancelledResult(), List.of()));
        }
        CompletionStage<AgentTool.ExecutionResult> result = Objects.requireNonNull(
                prepared.tool().execute(prepared.invocation(), prepared.execution(), token), "tool execution stage");
        return result.thenApply(value -> Objects.requireNonNull(value, "tool execution result"));
    }

    /**
     * 只把原绑定交给工具恢复核验；是否重试和如何提交由 Driver 根据 durable evidence 决定。
     */
    CompletionStage<AgentTool.RecoveryResolution> reconcile(
            PreparedCall call, Optional<AgentTool.RecoveryEvidence> evidence, CancellationToken cancellation) {
        PreparedCall prepared = dispatchable(call);
        Optional<AgentTool.RecoveryEvidence> recovery = Objects.requireNonNull(evidence, "evidence");
        CancellationToken token = Objects.requireNonNull(cancellation, "cancellation");
        token.throwIfCancellationRequested();
        CompletionStage<AgentTool.RecoveryResolution> resolution = Objects.requireNonNull(
                prepared.tool().reconcile(prepared.invocation(), prepared.execution(), recovery, token),
                "tool recovery stage");
        return resolution.thenApply(value -> Objects.requireNonNull(value, "tool recovery resolution"));
    }

    /**
     * 持有前次核验资源时继续 reconcile，避免宿主 claim 在恢复安全点之间被释放。
     */
    CompletionStage<AgentTool.RecoveryResolution> reconcileWithSettlement(
            PreparedCall call, Optional<AgentTool.RecoveryEvidence> evidence, CancellationToken cancellation,
            AgentTool.SettlementResource settlementResource) {
        PreparedCall prepared = dispatchable(call);
        CompletionStage<AgentTool.RecoveryResolution> resolution = Objects.requireNonNull(
                prepared.tool().reconcileWithSettlement(prepared.invocation(), prepared.execution(),
                        Objects.requireNonNull(evidence, "evidence"),
                        Objects.requireNonNull(cancellation, "cancellation"),
                        Objects.requireNonNull(settlementResource, "settlementResource")),
                "tool recovery stage");
        return resolution.thenApply(value -> Objects.requireNonNull(value, "tool recovery resolution"));
    }

    /**
     * token 在 Hook 入口前已取消时返回带核验资源的未派发结果；Hook 内异常仍由调用方按未知处理。
     */
    CompletionStage<AgentTool.ExecutionResult> executeAfterNotApplied(
            PreparedCall call, CancellationToken cancellation,
            AgentTool.SettlementResource settlementResource) {
        PreparedCall prepared = dispatchable(call);
        CancellationToken token = Objects.requireNonNull(cancellation, "cancellation");
        AgentTool.SettlementResource settlement = Objects.requireNonNull(settlementResource, "settlementResource");
        if (token.isCancellationRequested()) {
            return CompletableFuture.completedFuture(new AgentTool.ExecutionResult(
                    cancelledResult(), List.of(), settlement));
        }
        return Objects.requireNonNull(prepared.tool().executeAfterNotApplied(prepared.invocation(),
                        prepared.execution(), token, settlement),
                "tool retry stage").thenApply(value -> Objects.requireNonNull(value, "tool execution result"));
    }

    /**
     * 失败空结果先补成安全诊断再交给中间件和持久化，Started 标志仍区分拒绝与可能已有副作用的结算。
     */
    ProjectedResult projectResult(PreparedCall call, AgentTool.ExecutionResult executionResult,
                                  Map<String, AgentMiddleware.ExtensionStateView> extensionStates,
                                  boolean executionStarted) {
        PreparedCall prepared = Objects.requireNonNull(call, "call");
        AgentTool.ExecutionResult executed = failureDiagnosticIfEmpty(
                Objects.requireNonNull(executionResult, "executionResult"));
        AgentTool.ToolResult authoritative = executed.result();
        Map<String, AgentMiddleware.ExtensionStateView> states = stateSnapshot(extensionStates);
        String content = authoritative.content();
        Optional<io.github.kongweiguang.ja.agent.domain.json.JsonValue> structured =
                authoritative.structuredContent();
        Optional<String> artifactContent = authoritative.artifactContent();
        List<MiddlewareTransition> transitions = new ArrayList<>(middleware.size());
        for (ExtensionRegistry.MiddlewareRegistration registration : middleware) {
            AgentMiddleware.ExtensionStateView state = state(registration, states);
            AgentMiddleware.ExtensionDecision<AgentMiddleware.ToolResultProjection> result = Objects.requireNonNull(
                    registration.middleware().projectToolResult(new AgentMiddleware.ToolResultContext(
                            prepared.execution().identity(), prepared.invocation(), authoritative.outcome(),
                            executionStarted, content, structured, artifactContent, executed.facts(), state)),
                    "middleware result projection");
            transitions.add(transition(registration, state, result));
            content = result.decision().content();
            structured = result.decision().structuredContent();
            artifactContent = result.decision().artifactContent();
        }
        AgentMiddleware.ToolResultProjection visible = new AgentMiddleware.ToolResultProjection(
                content, structured, artifactContent);
        List<AgentTool.ExtensionFact> facts = new ArrayList<>(executed.facts());
        facts.addAll(immutableFacts(prepared.tool().resultFacts(prepared.invocation(), prepared.execution(),
                executed, visible), "result Tool facts"));
        if (facts.size() > MAX_FACTS_PER_TRANSITION) {
            throw new IllegalArgumentException("too many Tool result facts");
        }
        AgentTool.ExecutionResult committed = new AgentTool.ExecutionResult(
                authoritative, facts, executed.settlementResource(), executed.executionDurationMillis());
        return new ProjectedResult(visible, committed, transitions);
    }

    /**
     * 只把稳定错误码和受限的顶层 category 放进诊断，避免异常正文或任意结构化载荷泄漏给模型。
     */
    private static String failureDiagnostic(AgentTool.ToolResult result) {
        String errorCode = result.errorCode();
        if (errorCode == null || !errorCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
            errorCode = "TOOL_FAILED";
        }
        String category = result.structuredContent()
                .filter(JsonObject.class::isInstance)
                .map(JsonObject.class::cast)
                .map(value -> value.get("category"))
                .filter(JsonText.class::isInstance)
                .map(JsonText.class::cast)
                .map(JsonText::value)
                .filter(value -> value.matches("[A-Za-z0-9_-]{1,64}"))
                .orElse(null);
        return category == null
                ? "Tool failed with code " + errorCode + "."
                : "Tool failed with code " + errorCode + " (category: " + category + ").";
    }

    /**
     * 仅规范空白 FAILED 内容，并沿用原 facts、结算资源和耗时供正常及 fallback 结算共同使用。
     */
    private static AgentTool.ExecutionResult failureDiagnosticIfEmpty(AgentTool.ExecutionResult executed) {
        AgentTool.ToolResult result = executed.result();
        if (result.outcome() != ToolOutcome.FAILED || !result.content().isBlank()) {
            return executed;
        }
        AgentTool.ToolResult diagnosed = new AgentTool.ToolResult(result.outcome(), failureDiagnostic(result),
                result.structuredContent(), result.errorCode());
        return new AgentTool.ExecutionResult(diagnosed, executed.facts(), executed.settlementResource(),
                executed.executionDurationMillis());
    }

    /**
     * 扩展投影故障后隐藏可见正文，同时先规范权威空白失败并保留执行结算元数据。
     */
    ProjectedResult projectAuthoritativeResult(PreparedCall call, AgentTool.ExecutionResult executionResult) {
        PreparedCall prepared = Objects.requireNonNull(call, "call");
        AgentTool.ExecutionResult executed = failureDiagnosticIfEmpty(
                Objects.requireNonNull(executionResult, "executionResult"));
        AgentMiddleware.ToolResultProjection withheld = new AgentMiddleware.ToolResultProjection(
                "The Tool result was withheld because safe output projection failed.", java.util.Optional.empty());
        List<AgentTool.ExtensionFact> facts = new ArrayList<>(executed.facts());
        facts.addAll(immutableFacts(prepared.tool().resultFacts(prepared.invocation(), prepared.execution(),
                executed, withheld), "authoritative result Tool facts"));
        if (facts.size() > MAX_FACTS_PER_TRANSITION) {
            throw new IllegalArgumentException("too many Tool result facts");
        }
        AgentTool.ExecutionResult committed = new AgentTool.ExecutionResult(
                executed.result(), facts, executed.settlementResource(), executed.executionDurationMillis());
        return new ProjectedResult(withheld, committed, List.of());
    }

    /**
     * 计算刚提交准备阶段 patch 后的虚拟状态，让同事务拒绝结算观察正确 revision。
     */
    Map<String, AgentMiddleware.ExtensionStateView> stateAfter(
            Map<String, AgentMiddleware.ExtensionStateView> extensionStates,
            List<MiddlewareTransition> transitions) {
        Map<String, AgentMiddleware.ExtensionStateView> current = new java.util.LinkedHashMap<>(
                stateSnapshot(extensionStates));
        Set<String> changed = new java.util.HashSet<>();
        for (MiddlewareTransition transition : List.copyOf(Objects.requireNonNull(transitions, "transitions"))) {
            MiddlewareTransition value = Objects.requireNonNull(transition, "transition");
            if (!changed.add(value.namespace())) {
                throw new IllegalArgumentException("middleware namespace changed more than once in a step");
            }
            ExtensionRegistry.MiddlewareRegistration registration = middleware.stream()
                    .filter(candidate -> candidate.namespace().equals(value.namespace())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("middleware transition owner is unavailable"));
            AgentMiddleware.ExtensionStateView before = state(registration, current);
            JsonObject next = value.statePatch().apply(before);
            long revision = next.equals(before.value()) ? before.revision() : Math.addExact(before.revision(), 1);
            current.put(value.namespace(), new AgentMiddleware.ExtensionStateView(value.namespace(), revision,
                    registration.stateSchemaVersion(), next));
        }
        return Map.copyOf(current);
    }

    /**
     * 只接受注册表内的完整 namespace 快照；缺失与多余状态都表示恢复边界不完整。
     */
    private Map<String, AgentMiddleware.ExtensionStateView> stateSnapshot(
            Map<String, AgentMiddleware.ExtensionStateView> values) {
        Map<String, AgentMiddleware.ExtensionStateView> states = Map.copyOf(
                Objects.requireNonNull(values, "extensionStates"));
        Set<String> expected = middleware.stream()
                .map(ExtensionRegistry.MiddlewareRegistration::namespace)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!states.keySet().equals(expected)) {
            throw new IllegalArgumentException("middleware state set mismatch");
        }
        return states;
    }

    /**
     * 先处理截断等闭集预检错误，再验证 Schema 和工具特定的动态约束。
     */
    private Optional<AgentTool.ToolResult> invalidResult(AgentTool tool, AgentTool.Invocation invocation) {
        if (invocation.preflightFailureCode() != null) {
            return Optional.of(failed(invocation.preflightFailureCode(),
                    "The model returned an incomplete Tool call. Generate a complete call again."));
        }
        if (!tool.validatesResolvedArguments()) {
            Optional<String> reason = Objects.requireNonNull(argumentValidator.invalidReason(
                    tool.spec().inputSchema(), invocation.arguments()), "argument validation result");
            if (reason.isPresent()) {
                return Optional.of(failed("TOOL_ARGUMENTS_INVALID", reason.orElseThrow()
                                                                    + ". Correct the arguments and retry this Tool."));
            }
        }
        Optional<AgentTool.InvocationValidationFailure> toolFailure = Objects.requireNonNull(
                tool.validationFailure(invocation), "tool validation result");
        if (toolFailure.isPresent()) {
            AgentTool.InvocationValidationFailure failure = toolFailure.orElseThrow();
            return Optional.of(failed(failure.code(), failure.message()));
        }
        return Optional.empty();
    }

    /**
     * 准备结果将成功派发与拒绝/等待三种副作用边界分开表达。
     */
    sealed interface Preparation permits Ready, Rejected, Waiting {
    }

    /**
     * 一个验证完成的调用快照；binding 为空只表示拒绝或等待分支。
     *
     * @param tool 工具
     * @param invocation 调用
     * @param execution 执行
     * @param sideEffect 工具副作用分类
     * @param approvalRequirement 审批要求
     * @param binding 绑定（可选）
     * @param preparedFacts 已准备事实列表
     * @param transitions 状态迁移列表
     */
    record PreparedCall(AgentTool tool,
                        AgentTool.Invocation invocation,
                        AgentTool.ExecutionContext execution,
                        ToolSideEffect sideEffect,
                        AgentTool.ApprovalRequirement approvalRequirement,
                        Optional<ToolExecutionBinding> binding,
                        List<AgentTool.ExtensionFact> preparedFacts,
                        List<MiddlewareTransition> transitions) {
        /**
         * 校验值对象完整性并复制同一准备事务的 facts/transitions。
         */
        PreparedCall {
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(invocation, "invocation");
            Objects.requireNonNull(execution, "execution");
            Objects.requireNonNull(sideEffect, "sideEffect");
            Objects.requireNonNull(approvalRequirement, "approvalRequirement");
            binding = Objects.requireNonNull(binding, "binding");
            preparedFacts = immutableFacts(preparedFacts, "preparedFacts");
            transitions = List.copyOf(Objects.requireNonNull(transitions, "transitions"));
            transitions.forEach(value -> Objects.requireNonNull(value, "middleware transition"));
        }
    }

    /**
     * 保存等待决定时的参数快照，后续中间件改写调用会被拒绝而不会误批另一动作。
     *
     * @param request 请求
     * @param arguments 工具调用参数
     */
    private record PendingWait(AgentMiddleware.WaitRequest request,
                               JsonObject arguments) {
        /**
         * 固定等待问题与其授权依据，避免后续 hook 改写原问题覆盖的调用。
         */
        private PendingWait {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(arguments, "arguments");
        }
    }

    /**
     * 核心能力贡献的状态 patch 与 facts 绑定 Registry namespace，不能由中间件自行冒充 owner。
     *
     * @param namespace 命名空间
     * @param statePatch 状态增量修改
     * @param facts 事实列表
     */
    record MiddlewareTransition(String namespace,
                                AgentMiddleware.ExtensionStatePatch statePatch,
                                List<AgentMiddleware.MiddlewareFact> facts) {
        /**
         * 冻结每个 hook 的单 namespace 变更，交给 Driver 与相应核心事实同事务提交。
         */
        MiddlewareTransition {
            namespace = io.github.kongweiguang.ja.agent.domain.validation.ContractChecks.identifier(
                    namespace, "namespace");
            Objects.requireNonNull(statePatch, "statePatch");
            facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
            if (facts.size() > MAX_FACTS_PER_TRANSITION)
                {
                    throw new IllegalArgumentException("too many middleware facts");
                }
        }
    }

    /**
     * 模型可见投影与权威 ToolResult/扩展事实分开返回，方便 Driver 原子提交后再继续模型。
     *
     * @param visibleResult 可见结果
     * @param committedResult 已提交结果
     * @param transitions 状态迁移列表
     */
    record ProjectedResult(AgentMiddleware.ToolResultProjection visibleResult,
                           AgentTool.ExecutionResult committedResult,
                           List<MiddlewareTransition> transitions) {
        /**
         * 确保模型投影不能代替 Store 需要的真实结果，并固定所有结果阶段状态 patch。
         */
        ProjectedResult {
            Objects.requireNonNull(visibleResult, "visibleResult");
            Objects.requireNonNull(committedResult, "committedResult");
            transitions = List.copyOf(Objects.requireNonNull(transitions, "transitions"));
            transitions.forEach(value -> Objects.requireNonNull(value, "middleware transition"));
        }
    }

    /**
     * 只有含绑定的 Ready 才可由 Driver 提交 Started 后调用工具。
     *
     * @param call 调用
     */
    record Ready(PreparedCall call) implements Preparation {
        /**
         * 禁止 ready 分支缺少恢复所需绑定。
         */
        public Ready {
            Objects.requireNonNull(call, "call");
            if (call.binding().isEmpty()) {
                throw new IllegalArgumentException("ready Tool call requires a binding");
            }
        }
    }

    /**
     * 确定性拒绝附有最终调用和中间件事实，Driver可成对写入 ToolResult 并提交扩展策略结果。
     *
     * @param call 调用
     * @param result 处理结果
     */
    record Rejected(PreparedCall call,
                    AgentTool.ToolResult result) implements Preparation {
        /**
         * 拒绝结局不能报成功，避免准入失败进入完成分支。
         */
        public Rejected {
            Objects.requireNonNull(call, "call");
            Objects.requireNonNull(result, "result");
            if (result.outcome() == ToolOutcome.SUCCEEDED) {
                throw new IllegalArgumentException("rejected Tool call cannot report success");
            }
        }
    }

    /**
     * 挂起请求与本次决策 transitions 共同持久化，回答前不调用工具。
     *
     * @param call 调用
     * @param request 请求
     */
    record Waiting(PreparedCall call,
                   AgentMiddleware.WaitRequest request) implements Preparation {
        /**
         * 固定恢复调用与待答问题，禁止缺失值进入等待状态机。
         */
        public Waiting {
            Objects.requireNonNull(call, "call");
            Objects.requireNonNull(request, "request");
        }
    }
}
