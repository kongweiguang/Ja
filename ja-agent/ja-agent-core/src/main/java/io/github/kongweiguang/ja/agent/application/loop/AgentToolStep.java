// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFact;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.agent.domain.tool.RecoveryCommand;
import io.github.kongweiguang.ja.agent.domain.tool.RecoveryCommandConsumption;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolRecoveryDecision;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentRecordIds;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.shared.Hashing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import io.github.kongweiguang.ja.agent.domain.tool.ToolExecutionBinding;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware.ToolResultProjection;

/**
 * 只推进一个已持久化工具游标；每次外部派发前先确认准备和 Started 事务。
 */
final class AgentToolStep {
    private static final String RECOVERY_DECISION_TYPE = "agent.tool.recovery.decision";
    private static final String RECOVERY_COMMAND_TYPE = "agent.tool.recovery.command";
    private static final String RECOVERY_COMMAND_CONSUMED_TYPE = "agent.tool.recovery.command.consumed";

    /**
     * 该步骤无实例状态；静态入口避免 Engine 误把一次请求内的执行事实留在可复用对象中。
     */
    private AgentToolStep() {
    }

    /**
     * 纯粹配对未 Started 的模型调用；Session 可把返回事实、事件和游标与终态原子提交。
     */
    static RemainingSettlement settlement(AgentIdentity identity, TurnExecutionState.Tools cursor,
                                          ExecutionStateView view, ToolOutcome outcome, String errorCode, String message) {
        AgentIdentity operationIdentity = Objects.requireNonNull(identity, "identity");
        TurnExecutionState.Tools current = Objects.requireNonNull(cursor, "cursor");
        ExecutionStateView snapshot = Objects.requireNonNull(view, "view");
        ToolOutcome resultOutcome = Objects.requireNonNull(outcome, "outcome");
        if (resultOutcome == ToolOutcome.SUCCEEDED)
            {
                throw new IllegalArgumentException("remaining Tools cannot succeed");
            }
        String code = io.github.kongweiguang.ja.agent.domain.validation.ContractChecks.identifier(errorCode, "errorCode");
        String content = io.github.kongweiguang.ja.agent.domain.validation.ContractChecks.text(message,
                "message", Integer.MAX_VALUE, true);
        ExecutionFacts.AssistantMessage assistant = snapshot.latest(ExecutionFacts.AssistantMessage.class,
                value -> value.messageId().equals(current.assistantMessageId())).orElseThrow(
                () -> corrupt("Tools cursor references a missing assistant message"));
        List<ToolCallContent> calls = assistant.message().content().stream()
                .filter(ToolCallContent.class::isInstance).map(ToolCallContent.class::cast).toList();
        if ((long) calls.size() != (long) current.lastOrdinal() - current.firstOrdinal() + 1) {
            throw corrupt("Tool batch size does not match its assistant message");
        }
        List<FactWrite> facts = new ArrayList<>();
        List<ExecutionEvent> events = new ArrayList<>();
        for (int ordinal = current.nextOrdinal(); ordinal <= current.lastOrdinal(); ordinal++) {
            ToolCallContent call = calls.get(ordinal - current.firstOrdinal());
            if (latest(snapshot, ExecutionFacts.ToolResult.class, call.callId()).isPresent()) {
                throw corrupt("Tool result exists before its cursor advanced");
            }
            if (latest(snapshot, ExecutionFacts.ToolStarted.class, call.callId()).isPresent()) {
                throw new UnsettledToolException(call.callId());
            }
            String resultId = recordId(operationIdentity, "agent.tool.result", "tool-result:" + call.callId());
            String messageId = recordId(operationIdentity, "agent.tool.message", "tool-message:" + call.callId());
            ExecutionFacts.ToolResult result = new ExecutionFacts.ToolResult(call.callId(), toolState(resultOutcome),
                    content, true, "");
            ModelMessage toolMessage = new ModelMessage(ModelRole.TOOL,
                    List.of(new ToolResultContent(call.callId(), content, true)));
            facts.add(new FactWrite(resultId, result));
            facts.add(new FactWrite(messageId, new ExecutionFacts.ToolMessage(messageId(operationIdentity, call.callId()), toolMessage)));
            events.add(new ExecutionEvent.ToolCompleted(call.callId(), toolState(resultOutcome), resultId,
                    messageId, code));
        }
        TurnExecutionState.Common common = current.common();
        TurnExecutionState.Common advanced = new TurnExecutionState.Common(common.modelRound(),
                Math.addExact(common.usedToolCalls(), current.lastOrdinal() - current.nextOrdinal() + 1),
                common.nextProviderOrdinal(), common.promptRevision(), common.activeSkills());
        TurnExecutionState.Ready next = new TurnExecutionState.Ready(advanced,
                TurnExecutionState.Next.ASSISTANT, null);
        return new RemainingSettlement(facts, events, next);
    }

    /**
     * 从宿主借用本次请求 runtime 仅推进一个工具游标，runtime 的生命周期仍由 Session 所属 Engine 管理。
     */
    @SuppressWarnings("PMD.CloseResource")
    static CompletionStage<Void> step(Host host) {
        Host required = Objects.requireNonNull(host, "host");
        AgentExecutionDriver.Services services = required.services();
        AgentRequestRuntime runtime = required.runtime();
        TurnExecutionState.Tools cursor = tools(required.cursor());
        AgentToolRunner runner = new AgentToolRunner(services.toolArgumentValidator(), runtime.extensions().middleware());
        CallPosition position = currentCall(required, cursor);
        Set<String> visibleTools = visibleTools(required, cursor);
        Optional<ExecutionFacts.ToolPrepared> persisted = latest(required.view(),
                ExecutionFacts.ToolPrepared.class, position.call().callId());
        Optional<ExecutionFacts.ToolStarted> started = latest(required.view(),
                ExecutionFacts.ToolStarted.class, position.call().callId());
        Optional<ExecutionFacts.ToolResult> result = latest(required.view(),
                ExecutionFacts.ToolResult.class, position.call().callId());
        Optional<ExecutionFacts.RecoveryEvidence> evidence = started.flatMap(value ->
                required.view().latest(ExecutionFacts.RecoveryEvidence.class, fact ->
                        fact.callId().equals(position.call().callId()) && fact.attempt() == value.attempt()));
        Optional<ToolRecoveryDecision> recovery = recoveryDecision(required, position.call().callId());
        if (result.isPresent()) {
            throw corrupt("tool result exists before its cursor advanced");
        }
        if (started.isPresent() && persisted.isEmpty()) {
            throw corrupt("started Tool has no prepared fact");
        }
        if (evidence.isPresent() && started.isEmpty()) {
            throw corrupt("recovery evidence exists before Started");
        }
        if (recovery.isPresent() && started.isEmpty()) {
            throw corrupt("Tool recovery exists before Started");
        }
        if (started.isPresent())
            {
                validateStartedAttempt(required.view(), position.call().callId(), started.orElseThrow());
            }
        if (recovery.isPresent() && recovery.orElseThrow().status() != ToolRecoveryDecision.Status.PENDING) {
            throw corrupt("settled Tool recovery has no matching Tool result");
        }
        if (started.isPresent() && recovery.isEmpty()) {
            persistInitialRecovery(required, cursor, position);
            return CompletableFuture.completedFuture(null);
        }

        AgentTool tool = runtime.extensions().tools().stream()
                .filter(candidate -> candidate.spec().name().equals(position.call().name()))
                .findFirst().orElse(null);
        if (tool == null || !visibleTools.contains(position.call().name())) {
            if (started.isPresent()) {
                Optional<PendingRecoveryCommand> command = recovery.flatMap(value -> recoveryCommand(required, value));
                if (command.filter(value -> value.command().decision() == RecoveryCommand.Decision.SKIP).isPresent()) {
                    return skipRecovery(required, cursor, position, recovery.orElseThrow(), command.orElseThrow());
                }
                required.suspend("TOOL_BINDING_CHANGED");
                return CompletableFuture.completedFuture(null);
            }
            return commitUnavailable(required, cursor, position, persisted.isPresent());
        }

        AgentTool.ExecutionContext execution = executionContext(required.identity(), cursor, position);
        AgentTool.ToolBindingContext bindingContext = bindingContext(runtime, visibleTools);
        if (persisted.isPresent()) {
            return resumePrepared(required, runner, tool, cursor, position,
                    persisted.orElseThrow(), started, evidence, recovery, execution, bindingContext);
        }

        AgentTool.Invocation invocation = invocation(position);
        AgentToolRunner.Preparation preparation = runner.prepare(tool, invocation, execution, bindingContext,
                required.view().middleware());
        return switch (preparation) {
            case AgentToolRunner.Ready ready -> {
                PromptGuard guard = checkPrompt(required, cursor, ready.call());
                if (guard.denial() != null) {
                    yield commitRejected(required, runner, cursor, position, ready.call(), guard.denial(),
                            guard.refreshed(), false);
                }
                persistPrepared(required, cursor, position, ready.call());
                yield dispatchNew(required, runner, cursor, position, ready.call());
            }
            case AgentToolRunner.Waiting waiting -> {
                PromptGuard guard = checkPrompt(required, cursor, waiting.call());
                if (guard.denial() != null) {
                    yield commitRejected(required, runner, cursor, position, waiting.call(), guard.denial(),
                            guard.refreshed(), false);
                }
                persistWait(required, cursor, position, waiting.call(), waiting.request());
                yield CompletableFuture.completedFuture(null);
            }
            case AgentToolRunner.Rejected rejected -> commitRejected(required, runner, cursor, position,
                    rejected.call(), rejected.result(), false, false);
        };
    }

    /**
     * 从模型事实恢复调用顺序；Provider 给出的名称和参数永不从请求参数或当前工具猜测。
     */
    private static CallPosition currentCall(Host host, TurnExecutionState.Tools cursor) {
        ExecutionFacts.AssistantMessage assistant = host.view().latest(ExecutionFacts.AssistantMessage.class,
                value -> value.messageId().equals(cursor.assistantMessageId())).orElseThrow(
                () -> corrupt("Tools cursor references a missing assistant message"));
        List<ToolCallContent> calls = assistant.message().content().stream()
                .filter(ToolCallContent.class::isInstance).map(ToolCallContent.class::cast).toList();
        long expectedCalls = (long) cursor.lastOrdinal() - cursor.firstOrdinal() + 1;
        if (expectedCalls != calls.size()) {
            throw corrupt("Tool batch size does not match its assistant message");
        }
        int index = cursor.nextOrdinal() - cursor.firstOrdinal();
        if (index < 0 || index >= calls.size()) {
            throw corrupt("Tools cursor is outside its batch");
        }
        ToolCallContent call = calls.get(index);
        if (calls.stream().map(ToolCallContent::callId).distinct().count() != calls.size()) {
            throw corrupt("assistant message contains duplicate Tool call identities");
        }
        return new CallPosition(call, cursor.nextOrdinal());
    }

    /**
     * 只根据本轮模型请求提交的严格能力事实派发，注册表当前存在不等同于当时已暴露。
     */
    private static Set<String> visibleTools(Host host, TurnExecutionState.Tools cursor) {
        return Set.copyOf(host.view().modelCapabilities(cursor.assistantMessageId(), host.services()).visibleTools());
    }

    /**
     * 持久准备已存在时只重建原绑定；已过期的审批直接失败结算，不重跑策略或伪造用户拒绝。
     */
    private static CompletionStage<Void> resumePrepared(Host host, AgentToolRunner runner, AgentTool tool,
                                                        TurnExecutionState.Tools cursor, CallPosition position, ExecutionFacts.ToolPrepared persisted,
                                                        Optional<ExecutionFacts.ToolStarted> started, Optional<ExecutionFacts.RecoveryEvidence> evidence,
                                                        Optional<ToolRecoveryDecision> recovery,
                                                        AgentTool.ExecutionContext execution, AgentTool.ToolBindingContext bindingContext) {
        if (!persisted.batchId().equals(cursor.batchId()) || !persisted.callId().equals(position.call().callId())
            || !persisted.toolName().equals(position.call().name()) || persisted.ordinal() != position.ordinal()) {
            throw corrupt("persisted Tool preparation does not match the cursor");
        }
        if (persisted.binding() == null) {
            throw corrupt("non-dispatchable Tool preparation has no result");
        }
        AgentTool.Invocation frozen = new AgentTool.Invocation(persisted.callId(), persisted.toolName(),
                persisted.arguments(), persisted.ordinal());
        AgentToolRunner.PreparedCall call;
        try {
            call = runner.restorePreparedCall(tool, frozen, execution, bindingContext,
                    persisted.binding(), persisted.sideEffect(), List.of());
        } catch (RuntimeException changed) {
            host.suspend("TOOL_BINDING_CHANGED");
            return CompletableFuture.completedFuture(null);
        }
        boolean toolRequiresApproval = call.approvalRequirement() == AgentTool.ApprovalRequirement.REQUIRED;
        if (toolRequiresApproval && !persisted.approvalRequired()) {
            host.suspend("TOOL_APPROVAL_BINDING_CHANGED");
            return CompletableFuture.completedFuture(null);
        }
        Optional<InteractionWait> wait = waitForCall(host.view(), persisted.callId());
        if (wait.filter(value -> value.status() == InteractionStatus.PENDING).isPresent()) {
            throw corrupt("pending Tool wait reached execution step");
        }
        Optional<InteractionWait> expired = wait.filter(value -> value.status() == InteractionStatus.EXPIRED);
        if (expired.isPresent()) {
            boolean approvalExpired = approvalWait(expired.orElseThrow());
            return commitRejected(host, runner, cursor, position, call,
                    failed(approvalExpired ? "TOOL_APPROVAL_EXPIRED" : "TOOL_WAIT_EXPIRED",
                            approvalExpired ? "The approval expired before this Tool call was dispatched."
                                    : "The Tool request expired before it was answered."), false, true);
        }
        Optional<ExecutionFacts.Approval> approval = latest(host.view(),
                ExecutionFacts.Approval.class, persisted.callId());
        if (persisted.approvalRequired()) {
            if (approval.isEmpty() || approval.orElseThrow().decision() == null) {
                host.suspend("TOOL_APPROVAL_STATE_MISSING");
                return CompletableFuture.completedFuture(null);
            }
            if (approval.orElseThrow().decision() != io.github.kongweiguang.ja.agent.domain.approval.ApprovalDecision.APPROVE) {
                return commitRejected(host, runner, cursor, position, call,
                        failed("TOOL_APPROVAL_DENIED", "The user did not approve this Tool call."), false, true);
            }
        }
        if (wait.isPresent() && wait.orElseThrow().status() != InteractionStatus.ANSWERED) {
            return commitRejected(host, runner, cursor, position, call,
                    failed("TOOL_WAIT_NOT_ANSWERED", "The Tool request was not answered."), false, true);
        }
        if (started.isPresent()) {
            return reconcileStarted(host, runner, cursor, position, call, started.orElseThrow(), evidence, recovery);
        }
        PromptGuard guard = checkPrompt(host, cursor, call);
        if (guard.denial() != null) {
            return commitRejected(host, runner, cursor, position, call, guard.denial(), guard.refreshed(), true);
        }
        return dispatchNew(host, runner, cursor, position, call);
    }

    /**
     * 新准备在等待或立即执行前先提交最终参数、路由绑定、宿主 facts 和策略状态。
     */
    private static void persistPrepared(Host host, TurnExecutionState.Tools cursor,
                                        CallPosition position, AgentToolRunner.PreparedCall call) {
        Instant at = host.services().clock().instant();
        List<AgentStore.RecordWrite> writes = new ArrayList<>();
        appendPreparedWrites(host, writes, cursor, position, call,
                call.approvalRequirement() == AgentTool.ApprovalRequirement.REQUIRED, true, at);
        host.commit(TurnState.RUNNING, cursor, writes, List.of());
    }

    /**
     * 等待请求和最终 ToolPrepared 使用单一事务提交，回答后即可恢复而无需重跑策略。
     */
    private static void persistWait(Host host, TurnExecutionState.Tools cursor, CallPosition position,
                                    AgentToolRunner.PreparedCall call, AgentMiddleware.WaitRequest request) {
        Instant at = host.services().clock().instant();
        List<AgentStore.RecordWrite> writes = new ArrayList<>();
        appendPreparedWrites(host, writes, cursor, position, call, approvalRequired(call, request), true, at);
        host.openWait(request, position.call().callId(), writes);
    }

    /**
     * 同一原子边界写入最终准备快照、宿主事实和策略状态；拒绝路径可显式保留无绑定准备事实。
     */
    private static Map<String, AgentMiddleware.ExtensionStateView> appendPreparedWrites(Host host,
                                                                                        List<AgentStore.RecordWrite> writes, TurnExecutionState.Tools cursor, CallPosition position,
                                                                                        AgentToolRunner.PreparedCall call, boolean approvalRequired, boolean bindingRequired, Instant at) {
        ToolExecutionBinding binding = bindingRequired ? call.binding().orElseThrow() : call.binding().orElse(null);
        String callId = position.call().callId();
        writes.add(encode(host, new ExecutionFacts.ToolPrepared(cursor.batchId(), callId,
                position.call().name(), call.invocation().arguments(), position.ordinal(), call.sideEffect(),
                binding, approvalRequired), "agent.tool.prepared", "tool-prepared:" + callId, at));
        appendExtensionFacts(host, writes, call.preparedFacts(), callId, FactStage.PREPARED, at);
        return appendTransitions(host, writes, call.transitions(), host.view().middleware(),
                "tool-prepared:" + callId, at);
    }

    /**
     * 拒绝在 Started 前结算，显式传 false 使中间件不把审批/准入拒绝误认为外部副作用。
     */
    private static CompletionStage<Void> commitRejected(Host host, AgentToolRunner runner,
                                                        TurnExecutionState.Tools cursor, CallPosition position, AgentToolRunner.PreparedCall call,
                                                        AgentTool.ToolResult rejected, boolean promptRefreshed, boolean preparedExists) {
        Map<String, AgentMiddleware.ExtensionStateView> afterPrepare = runner.stateAfter(
                host.view().middleware(), call.transitions());
        AgentToolRunner.ProjectedResult projected;
        try (AgentTool.ExecutionResult raw = AgentTool.ExecutionResult.of(rejected)) {
            projected = runner.projectResult(call, raw, afterPrepare, false);
        }
        try {
            List<AgentStore.RecordWrite> writes = new ArrayList<>();
            Instant at = host.services().clock().instant();
            Map<String, AgentMiddleware.ExtensionStateView> afterPrepareCommit = host.view().middleware();
            if (!preparedExists) {
                afterPrepareCommit = appendPreparedWrites(host, writes, cursor, position, call,
                        call.approvalRequirement() == AgentTool.ApprovalRequirement.REQUIRED, false, at);
            }
            ExecutionEvent.ToolCompleted completed = appendResultAndEvent(host, writes, position, projected, at);
            appendTransitions(host, writes, projected.transitions(), afterPrepareCommit,
                    "tool-result:" + position.call().callId(), at);
            host.commit(TurnState.RUNNING, cursorAfter(cursor,
                    promptState(host, cursor, promptRefreshed, false)), writes, List.of(completed));
        } catch (RuntimeException | Error failure) {
            closeSettlementWithoutMasking(projected.committedResult(), failure);
            throw failure;
        }
        closeSettlement(projected.committedResult(), host);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Started 后的派发检查由 execute 负责结算取消；证据准备失败可见，权限与 Store 故障仍原样传播。
     */
    private static CompletionStage<Void> dispatchNew(Host host, AgentToolRunner runner,
                                                     TurnExecutionState.Tools cursor, CallPosition position, AgentToolRunner.PreparedCall call) {
        Optional<AgentTool.RecoveryEvidence> evidence;
        List<AgentTool.ExtensionFact> startedFacts;
        try {
            evidence = runner.prepareRecoveryEvidence(call, host.cancellation());
            startedFacts = runner.startedFacts(call);
        } catch (RuntimeException failure) {
            return commitRejected(host, runner, cursor, position, call,
                    failed("TOOL_START_PREPARATION_FAILED", "The Tool could not prepare a safe execution record."),
                    false, true);
        }
        host.assertDispatchAuthority();
        Instant at = host.services().clock().instant();
        List<AgentStore.RecordWrite> writes = startedWrites(host, position, 0, evidence,
                startedFacts, at);
        host.commit(TurnState.RUNNING, cursor, writes, List.of(startedEvent(cursor, position)));
        return execute(host, runner, cursor, position, call);
    }

    /**
     * RETRY 只授权重新核验；只有 NotApplied 能先写重试意图与 Started，再派发下一 attempt。
     */
    private static CompletionStage<Void> reconcileStarted(Host host, AgentToolRunner runner,
                                                          TurnExecutionState.Tools cursor, CallPosition position, AgentToolRunner.PreparedCall call,
                                                          ExecutionFacts.ToolStarted started, Optional<ExecutionFacts.RecoveryEvidence> savedEvidence,
                                                          Optional<ToolRecoveryDecision> recovery) {
        ToolRecoveryDecision pending = recovery.orElseThrow(
                () -> corrupt("Started Tool has no recovery decision"));
        Optional<PendingRecoveryCommand> command = recoveryCommand(host, pending);
        if (command.filter(value -> value.command().decision() == RecoveryCommand.Decision.SKIP).isPresent()) {
            return skipRecovery(host, cursor, position, pending, command.orElseThrow());
        }
        Optional<AgentTool.RecoveryEvidence> evidence = savedEvidence.map(value ->
                new AgentTool.RecoveryEvidence(value.typeId(), value.schemaVersion(), value.payload(), value.fingerprint()));
        CompletionStage<AgentTool.RecoveryResolution> stage;
        try {
            stage = runner.reconcile(call, evidence, host.cancellation());
        } catch (RuntimeException failure) {
            return recoveryUnknown(host, cursor, pending, command.orElse(null),
                    new AgentTool.RecoveryResolution.Unknown("TOOL_RECOVERY_OUTCOME_UNKNOWN"));
        }
        return stage.handle((resolution, failure) -> {
            if (failure != null) {
                return new AgentTool.RecoveryResolution.Unknown("TOOL_RECOVERY_OUTCOME_UNKNOWN");
            }
            return Objects.requireNonNull(resolution, "recovery resolution");
        }).thenCompose(resolution -> switch (resolution) {
            case AgentTool.RecoveryResolution.Applied applied -> commitResult(host, runner, cursor, position, call,
                    applied.result(), new RecoverySettlement(pending, command.orElse(null),
                            applied.verificationFingerprint(), started.attempt(), true));
            case AgentTool.RecoveryResolution.NotApplied notApplied -> retryNotApplied(host, runner, cursor,
                    position, call, started, pending, command.orElse(null), notApplied);
            case AgentTool.RecoveryResolution.Unknown unknown -> recoveryUnknown(host, cursor, pending,
                    command.orElse(null), unknown);
        });
    }

    /**
     * 派发前取消能确定未执行并结算；进入工具调用后 stage 报错仍保留未知结果，不能重放副作用。
     */
    private static CompletionStage<Void> execute(Host host, AgentToolRunner runner,
                                                 TurnExecutionState.Tools cursor, CallPosition position, AgentToolRunner.PreparedCall call) {
        CompletionStage<AgentTool.ExecutionResult> stage;
        try {
            host.assertDispatchAuthority();
        } catch (java.util.concurrent.CancellationException cancelled) {
            return commitRejected(host, runner, cursor, position, call,
                    new AgentTool.ToolResult(ToolOutcome.CANCELLED, "The Tool was cancelled before dispatch.",
                            Optional.empty(), "CANCELLED"), false, true);
        }
        try {
            stage = runner.execute(call, host.cancellation());
        } catch (RuntimeException failure) {
            persistInitialRecovery(host, cursor, position);
            host.suspend("TOOL_EXECUTION_OUTCOME_UNKNOWN");
            return CompletableFuture.completedFuture(null);
        }
        return stage.handle((result, failure) -> {
            return failure == null ? result : null;
        }).thenCompose(result -> result == null
                ? unknownInitialExecution(host, cursor, position)
                : commitResult(host, runner, cursor, position, call, result));
    }

    /**
     * 明确未执行后先持久重试意图；Started 后的唯一派发门负责把未进入 Hook 的取消结算。
     */
    private static CompletionStage<Void> retryNotApplied(Host host, AgentToolRunner runner,
                                                         TurnExecutionState.Tools cursor, CallPosition position, AgentToolRunner.PreparedCall call,
                                                         ExecutionFacts.ToolStarted started, ToolRecoveryDecision pending, PendingRecoveryCommand command,
                                                         AgentTool.RecoveryResolution.NotApplied notApplied) {
        AgentTool.SettlementResource settlement = notApplied.settlementResource();
        if (command == null || command.command().decision() != RecoveryCommand.Decision.RETRY) {
            if (!closeRecoveryResource(settlement, host)) {
                return CompletableFuture.completedFuture(null);
            }
            host.suspend("TOOL_RECOVERY_RETRY_REQUIRED");
            return CompletableFuture.completedFuture(null);
        }

        int nextAttempt;
        String attemptId;
        AgentTool.RecoveryEvidence evidence;
        List<AgentTool.ExtensionFact> startedFacts;
        try {
            nextAttempt = Math.addExact(started.attempt(), 1);
            attemptId = "attempt_" + Hashing.sha256Hex(String.join("\0", pending.recoveryId(),
                    command.command().operationKey(), Integer.toString(nextAttempt))).substring(0, 48);
            evidence = runner.prepareRecoveryEvidence(call, host.cancellation()).orElse(null);
            startedFacts = runner.startedFacts(call);
        } catch (RuntimeException failure) {
            if (!closeRecoveryResource(settlement, host)) {
                return CompletableFuture.completedFuture(null);
            }
            host.suspend("TOOL_RETRY_PREPARATION_FAILED");
            return CompletableFuture.completedFuture(null);
        } catch (Error failure) {
            closeResourceWithoutMasking(settlement, failure);
            throw failure;
        }
        try {
            host.assertDispatchAuthority();
        } catch (RuntimeException | Error failure) {
            closeResourceWithoutMasking(settlement, failure);
            throw failure;
        }

        ToolRecoveryDecision retrying;
        try {
            Instant at = host.services().clock().instant();
            retrying = new ToolRecoveryDecision(pending.recoveryId(), pending.callId(),
                    Math.addExact(pending.revision(), 1), ToolRecoveryDecision.Status.PENDING,
                    ToolRecoveryDecision.Disposition.RETRY, nextAttempt, attemptId, null, null, at);
            List<AgentStore.RecordWrite> writes = new ArrayList<>();
            writes.add(encode(host, new ExecutionFacts.ToolRetryIntent(position.call().callId(), started.attempt(),
                            nextAttempt, notApplied.verificationFingerprint()), "agent.tool.retry.intent",
                    "tool-retry-intent:" + position.call().callId() + ":" + nextAttempt, at));
            writes.addAll(startedWrites(host, position, nextAttempt, Optional.ofNullable(evidence),
                    startedFacts, at));
            AgentStore.RecordWrite decisionWrite = encodeRecoveryDecision(host, retrying, at);
            writes.add(decisionWrite);
            writes.add(commandConsumption(host, command, at));
            host.commit(TurnState.RUNNING, cursor, writes,
                    List.of(new ExecutionEvent.ToolRecoveryChanged(retrying.recoveryId(), retrying.callId(),
                            retrying.revision(), decisionWrite.recordId())));
        } catch (RuntimeException | Error failure) {
            closeResourceWithoutMasking(settlement, failure);
            throw failure;
        }
        return executeAfterNotApplied(host, runner, cursor, position, call, settlement,
                new RecoverySettlement(retrying, null, null, nextAttempt, false));
    }

    /**
     * Started 后仅在 authority/token guard 确认未进 Hook 时结算取消，Hook 或 stage 异常仍按未知处理。
     */
    private static CompletionStage<Void> executeAfterNotApplied(Host host, AgentToolRunner runner,
                                                                TurnExecutionState.Tools cursor, CallPosition position, AgentToolRunner.PreparedCall call,
                                                                AgentTool.SettlementResource settlement, RecoverySettlement recovery) {
        CompletionStage<AgentTool.ExecutionResult> stage;
        try {
            host.assertDispatchAuthority();
        } catch (java.util.concurrent.CancellationException cancelled) {
            AgentTool.ExecutionResult result = new AgentTool.ExecutionResult(
                    new AgentTool.ToolResult(ToolOutcome.CANCELLED, "", Optional.empty(), "CANCELLED"),
                    List.of(), settlement);
            return commitResult(host, runner, cursor, position, call, result, recovery);
        } catch (RuntimeException | Error failure) {
            closeResourceWithoutMasking(settlement, failure);
            throw failure;
        }
        try {
            stage = runner.executeAfterNotApplied(call, host.cancellation(), settlement);
        } catch (RuntimeException | Error failure) {
            if (!closeRecoveryResource(settlement, host)) {
                return CompletableFuture.completedFuture(null);
            }
            host.suspend("TOOL_EXECUTION_OUTCOME_UNKNOWN");
            return CompletableFuture.completedFuture(null);
        }
        return stage.handle((result, failure) -> failure == null ? result : null).thenCompose(result -> {
            if (result == null) {
                if (!closeRecoveryResource(settlement, host)) {
                    return CompletableFuture.completedFuture(null);
                }
                host.suspend("TOOL_EXECUTION_OUTCOME_UNKNOWN");
                return CompletableFuture.completedFuture(null);
            }
            return commitResult(host, runner, cursor, position, call, result, recovery);
        });
    }

    /**
     * 将异步结果及其结算资源委托给完整事务路径，保留所有权直到结果提交或异常清理结束。
     */
    @SuppressWarnings({"PMD.CloseResource", "PMD.PreserveStackTrace"}) // 回退故障为主异常，资源关闭失败只附加到它上面。
    private static CompletionStage<Void> commitResult(Host host, AgentToolRunner runner,
                                                      TurnExecutionState.Tools cursor, CallPosition position, AgentToolRunner.PreparedCall call,
                                                      AgentTool.ExecutionResult executionResult) {
        return commitResult(host, runner, cursor, position, call, executionResult, null);
    }

    /**
     * Started 后结算真实异步结果并传 true；资源与 Store/fencing/CAS 故障仍沿原事务边界处理。
     */
    @SuppressWarnings({"PMD.CloseResource", "PMD.PreserveStackTrace"}) // 回退故障为主异常，资源关闭失败只附加到它上面。
    private static CompletionStage<Void> commitResult(Host host, AgentToolRunner runner,
                                                      TurnExecutionState.Tools cursor, CallPosition position, AgentToolRunner.PreparedCall call,
                                                      AgentTool.ExecutionResult executionResult, RecoverySettlement recovery) {
        AgentToolRunner.ProjectedResult projected;
        RuntimeException projectionFailure = null;
        try {
            projected = runner.projectResult(call, executionResult,
                    host.view().middleware(), true);
        } catch (RuntimeException failure) {
            projectionFailure = failure;
            try {
                projected = runner.projectAuthoritativeResult(call, executionResult);
            } catch (RuntimeException | Error fallbackFailure) {
                closeSettlementWithoutMasking(executionResult, fallbackFailure);
                throw fallbackFailure;
            }
        } catch (Error failure) {
            closeSettlementWithoutMasking(executionResult, failure);
            throw failure;
        }
        AgentTool.ExecutionResult committed = projected.committedResult();
        try {
            List<AgentStore.RecordWrite> writes = new ArrayList<>();
            Instant at = host.services().clock().instant();
            ExecutionEvent.ToolCompleted completed = appendResultAndEvent(host, writes, position, projected, at);
            appendTransitions(host, writes, projected.transitions(), host.view().middleware(),
                    "tool-result:" + position.call().callId(), at);
            List<ExecutionEvent> events = new ArrayList<>();
            events.add(completed);
            if (recovery != null) {
                appendRecoverySettlement(host, writes, events, recovery,
                    completed.resultRecordId(), at);
            }
            host.commit(TurnState.RUNNING, cursorAfter(cursor, promptState(host, cursor, false, true)), writes, events);
        } catch (RuntimeException | Error failure) {
            closeSettlementWithoutMasking(committed, failure);
            throw failure;
        }
        if (!closeSettlement(committed, host)) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            host.runtime().promptSession().afterTool(call.invocation(), committed.result());
        } catch (RuntimeException failure) {
            host.suspend("TOOL_AFTER_EXECUTION_FAILED");
            return CompletableFuture.completedFuture(null);
        }
        if (projectionFailure != null) {
            host.suspend("TOOL_RESULT_PROJECTION_FAILED");
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 模型隐藏的注册工具和不存在的名称统一返回可纠正失败，不读取或执行其实现。
     */
    private static CompletionStage<Void> commitUnavailable(Host host, TurnExecutionState.Tools cursor,
                                                           CallPosition position, boolean preparedExists) {
        AgentTool.ToolResult result = failed("TOOL_NOT_AVAILABLE",
                "This Tool is not available for the current request. Choose an exposed Tool and retry.");
        Instant at = host.services().clock().instant();
        List<AgentStore.RecordWrite> writes = new ArrayList<>();
        if (!preparedExists) {
            writes.add(encode(host, new ExecutionFacts.ToolPrepared(cursor.batchId(), position.call().callId(),
                    position.call().name(), position.call().arguments(), position.ordinal(), ToolSideEffect.EXTERNAL,
                    null, false), "agent.tool.prepared", "tool-prepared:" + position.call().callId(), at));
        }
        AgentToolRunner.ProjectedResult projected = plainProjection(result);
        ExecutionEvent.ToolCompleted completed = appendResultAndEvent(host, writes, position, projected, at);
        host.commit(TurnState.RUNNING, cursorAfter(cursor, promptState(host, cursor, false, false)), writes,
                List.of(completed));
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 将受管媒体句柄与可见文本一起写入同一结算事务；实际图片正文仍只在下一次 Provider 请求中按 Thread 授权读取。
     */
    @SuppressWarnings("PMD.CloseResource")
    private static void appendToolResult(Host host, List<AgentStore.RecordWrite> writes,
                                         AgentToolRunner.ProjectedResult projected, String callId, Instant at) {
        AgentTool.ExecutionResult committed = projected.committedResult();
        AgentTool.ToolResult authoritative = committed.result();
        boolean error = authoritative.outcome() != ToolOutcome.SUCCEEDED;
        ToolState state = toolState(authoritative.outcome());
        writes.add(encode(host, new ExecutionFacts.ToolResult(callId, state, authoritative.content(), error, ""),
                "agent.tool.result", "tool-result:" + callId, at));
        ModelMessage modelResult = new ModelMessage(ModelRole.TOOL, List.of(new ToolResultContent(callId,
                projected.visibleResult().content(), error, authoritative.media())));
        writes.add(encode(host, new ExecutionFacts.ToolMessage(messageId(host.identity(), callId), modelResult),
                "agent.tool.message", "tool-message:" + callId, at));
        appendExtensionFacts(host, writes, committed.facts(), callId, FactStage.RESULT, at);
    }

    /**
     * 只借用投影结果生成持久记录，结算资源由调用方在同一事务完成后关闭。
     */
    @SuppressWarnings("PMD.CloseResource")
    private static ExecutionEvent.ToolCompleted appendResultAndEvent(Host host,
                                                                     List<AgentStore.RecordWrite> writes, CallPosition position,
                                                                     AgentToolRunner.ProjectedResult projected, Instant at) {
        String callId = position.call().callId();
        appendToolResult(host, writes, projected, callId, at);
        AgentStore.RecordWrite resultWrite = findFactWrite(host, writes, "agent.tool.result",
                "tool-result:" + callId);
        AgentStore.RecordWrite messageWrite = findFactWrite(host, writes, "agent.tool.message",
                "tool-message:" + callId);
        return completedEvent(position, projected.committedResult().result(),
                resultWrite.recordId(), messageWrite.recordId());
    }

    /**
     * Started 与核验依据和宿主审计事实同事务提交，确保派发前这些恢复线索已可重建。
     */
    private static List<AgentStore.RecordWrite> startedWrites(Host host, CallPosition position, int attempt,
                                                              Optional<AgentTool.RecoveryEvidence> evidence, List<AgentTool.ExtensionFact> startedFacts,
                                                              Instant at) {
        List<AgentStore.RecordWrite> writes = new ArrayList<>();
        evidence.ifPresent(value -> writes.add(encode(host, new ExecutionFacts.RecoveryEvidence(
                position.call().callId(), attempt, value.typeId(), value.schemaVersion(), value.payload(),
                value.fingerprint()), "agent.tool.recovery", "tool-recovery:" + position.call().callId()
                                                             + ":" + attempt, at)));
        writes.add(encode(host, new ExecutionFacts.ToolStarted(position.call().callId(), attempt),
                "agent.tool.started", "tool-started:" + position.call().callId() + ":" + attempt, at));
        appendExtensionFacts(host, writes, startedFacts, position.call().callId(), FactStage.STARTED, attempt, at);
        return List.copyOf(writes);
    }

    /**
     * 用注册时冻结的 owner 编码中间件状态与同阶段 facts，并逐 namespace 推进虚拟 revision。
     */
    private static Map<String, AgentMiddleware.ExtensionStateView> appendTransitions(Host host,
                                                                                     List<AgentStore.RecordWrite> writes, List<AgentToolRunner.MiddlewareTransition> transitions,
                                                                                     Map<String, AgentMiddleware.ExtensionStateView> initial, String boundary, Instant at) {
        Map<String, AgentMiddleware.ExtensionStateView> current = new LinkedHashMap<>(initial);
        Set<String> namespaces = new HashSet<>();
        for (AgentToolRunner.MiddlewareTransition transition : transitions) {
            if (!namespaces.add(transition.namespace())) {
                throw new IllegalArgumentException("duplicate middleware transition namespace");
            }
            ExtensionRegistry.MiddlewareRegistration registration = host.runtime().extensions().middleware().stream()
                    .filter(value -> value.namespace().equals(transition.namespace())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("middleware owner is unavailable"));
            AgentMiddleware.ExtensionStateView before = Objects.requireNonNull(current.get(transition.namespace()),
                    "middleware state");
            if (before.schemaVersion() != registration.stateSchemaVersion()
                || transition.statePatch().expectedRevision() != before.revision()) {
                throw new IllegalStateException("middleware transition revision changed");
            }
            String stateKey = boundary + ":" + transition.namespace();
            host.services().middlewareStates().encodePatch(recordId(host, "agent.extension.middleware.state", stateKey),
                    registration, before, transition.statePatch(), at).ifPresent(writes::add);
            JsonObject next = transition.statePatch().apply(before);
            long revision = next.equals(before.value()) ? before.revision() : Math.addExact(before.revision(), 1);
            current.put(transition.namespace(), new AgentMiddleware.ExtensionStateView(transition.namespace(),
                    revision, registration.stateSchemaVersion(), next));
            for (int index = 0; index < transition.facts().size(); index++) {
                AgentMiddleware.MiddlewareFact fact = transition.facts().get(index);
                appendExtensionFact(host, writes, new AgentTool.ExtensionFact(transition.namespace(), fact.type(),
                        fact.schemaVersion(), fact.payload()), "middleware:" + boundary + ":"
                                                               + transition.namespace() + ":" + fact.type() + ":" + index, at);
            }
        }
        return Map.copyOf(current);
    }

    /**
     * 为同一执行的调用阶段按 call、阶段和顺序分配通用扩展事实 ID。
     */
    private static void appendExtensionFacts(Host host, List<AgentStore.RecordWrite> writes,
                                             List<AgentTool.ExtensionFact> facts, String callId, FactStage stage, Instant at) {
        appendExtensionFacts(host, writes, facts, callId, stage, 0, at);
    }

    /**
     * 把重试 attempt 纳入 Started facts 身份，恢复时不会与前一派发的扩展事实冲突。
     */
    private static void appendExtensionFacts(Host host, List<AgentStore.RecordWrite> writes,
                                             List<AgentTool.ExtensionFact> facts, String callId, FactStage stage, int attempt, Instant at) {
        Set<String> keys = new HashSet<>();
        for (int index = 0; index < facts.size(); index++) {
            AgentTool.ExtensionFact fact = Objects.requireNonNull(facts.get(index), "extension fact");
            String key = "tool:" + callId + ":" + stage.key() + ":" + attempt + ":"
                         + fact.namespace() + ":" + fact.type() + ":" + index;
            if (!keys.add(key)) {
                throw new IllegalArgumentException("duplicate Tool extension fact key");
            }
            appendExtensionFact(host, writes, fact, key, at);
        }
    }

    /**
     * 将扩展事实写成通用 envelope；业务事务参与者继续在同一个 Store commit 中解释其 payload。
     */
    private static void appendExtensionFact(Host host, List<AgentStore.RecordWrite> writes,
                                            AgentTool.ExtensionFact fact, String stableKey, Instant at) {
        String id = AgentRecordIds.derive(AgentExecutionDriver.executionKey(host.identity()),
                "agent.extension.fact", stableKey);
        writes.add(host.services().extensionFacts().encode(id, fact, at));
    }

    /**
     * 将数据库结果事实映射为模型消息、扩展记录和固定 record id。
     */
    private static AgentStore.RecordWrite encode(Host host, ExecutionFact fact, String recordType,
                                                 String stableKey, Instant at) {
        String id = recordId(host, recordType, stableKey);
        return host.services().records().encode(id, fact, at);
    }

    /**
     * 使用 AgentRecordIds 的执行/租户作用域，确保提交重试重用完全相同记录。
     */
    private static String recordId(Host host, String recordType, String stableKey) {
        return recordId(host.identity(), recordType, stableKey);
    }

    /**
     * 从完整执行身份派生稳定记录 ID，纯结算计划与真正 Store 提交保持同一键算法。
     */
    private static String recordId(AgentIdentity identity, String recordType, String stableKey) {
        return AgentRecordIds.derive(AgentExecutionDriver.executionKey(identity), recordType, stableKey);
    }

    /**
     * 从本批预期稳定键读取刚生成的引用，避免根据 writes 列表顺序推测 event 目标。
     */
    private static AgentStore.RecordWrite findFactWrite(Host host, List<AgentStore.RecordWrite> writes,
                                                        String recordType, String stableKey) {
        String id = recordId(host, recordType, stableKey);
        return writes.stream().filter(write -> write.recordId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Tool commit is missing a required fact"));
    }

    /**
     * 只映射模型结果真实成败，模型投影只能改变内容不能改变状态。
     */
    private static ExecutionEvent.ToolCompleted completedEvent(CallPosition position,
                                                               AgentTool.ToolResult result, String resultRecordId, String messageRecordId) {
        return new ExecutionEvent.ToolCompleted(position.call().callId(), toolState(result.outcome()),
                resultRecordId, messageRecordId, result.errorCode());
    }

    /**
     * Started 事件与持久调用绑定同一 batch、call 和 ordinal。
     */
    private static ExecutionEvent.ToolStarted startedEvent(TurnExecutionState.Tools cursor,
                                                           CallPosition position) {
        return new ExecutionEvent.ToolStarted(position.call().callId(), cursor.batchId(),
                position.call().name(), position.ordinal());
    }

    /**
     * 为工具输出建立可恢复消息 ID，防止同一 call 在崩溃恢复后生成第二条历史记录。
     */
    private static String messageId(AgentIdentity identity, String callId) {
        return "msg_" + Hashing.sha256Hex(String.join("\0", identity.tenantId(), identity.threadId(),
                identity.turnId(), identity.userId(), callId)).substring(0, 48);
    }

    /**
     * 已结算一项 Tool 后只推进一个 ordinal，并携带调用结算时冻结的 prompt/Skill 状态。
     */
    private static TurnExecutionState cursorAfter(TurnExecutionState.Tools cursor,
                                                  TurnExecutionState.Common settledCommon) {
        TurnExecutionState.Common advanced = new TurnExecutionState.Common(settledCommon.modelRound(),
                Math.addExact(settledCommon.usedToolCalls(), 1), settledCommon.nextProviderOrdinal(),
                settledCommon.promptRevision(), settledCommon.activeSkills());
        if (cursor.nextOrdinal() == cursor.lastOrdinal()) {
            return new TurnExecutionState.Ready(advanced, TurnExecutionState.Next.ASSISTANT, null);
        }
        return new TurnExecutionState.Tools(advanced, cursor.batchId(), cursor.assistantMessageId(),
                cursor.firstOrdinal(), cursor.lastOrdinal(), Math.addExact(cursor.nextOrdinal(), 1));
    }

    /**
     * 同批调用继续使用模型意图时的材料指纹；只有最后一次结算才推进最新 Prompt revision。
     */
    private static TurnExecutionState.Common promptState(Host host, TurnExecutionState.Tools cursor,
                                                         boolean promptRefreshed, boolean afterTool) {
        String revision = cursor.common().promptRevision();
        if (cursor.nextOrdinal() == cursor.lastOrdinal() && (promptRefreshed || afterTool)) {
            revision = host.runtime().promptSession().currentRevision();
        }
        return new TurnExecutionState.Common(cursor.common().modelRound(), cursor.common().usedToolCalls(),
                cursor.common().nextProviderOrdinal(), revision,
                host.runtime().promptSession().activeSkillReferences());
    }

    /**
     * 在最终参数冻结后检查本轮 Prompt revision；拒绝会记录本次是否已刷新策略材料。
     */
    private static PromptGuard checkPrompt(Host host, TurnExecutionState.Tools cursor,
                                           AgentToolRunner.PreparedCall call) {
        AgentPromptSession prompt = host.runtime().promptSession();
        String before = prompt.currentRevision();
        AgentPromptSession.ToolGuard guard = Objects.requireNonNull(prompt.beforeTool(call.invocation(),
                call.sideEffect(), cursor.common().promptRevision()), "Tool prompt guard");
        boolean refreshed = !before.equals(prompt.currentRevision());
        AgentTool.ToolResult denial = guard.proceed() ? null
                : failed(guard.code(), guard.message());
        return new PromptGuard(denial, refreshed);
    }

    /**
     * 从模型请求冻结快照构造目录指纹，并将当期权限 binding 指纹用于恢复授权比对。
     */
    private static AgentTool.ToolBindingContext bindingContext(AgentRequestRuntime runtime,
                                                               Set<String> visibleTools) {
        String material = runtime.extensions().tools().stream()
                .filter(tool -> visibleTools.contains(tool.spec().name()))
                .map(tool -> tool.spec().name() + "\0" + AgentTool.schemaFingerprint(tool.spec()))
                .collect(java.util.stream.Collectors.joining("\0"));
        return new AgentTool.ToolBindingContext(Hashing.sha256Hex(material), runtime.bindingFingerprint());
    }

    /**
     * 仅提供稳定 batch/call 元数据；路径、Service 和 Ja Workspace 上下文由应用 adapter 捕获。
     */
    private static AgentTool.ExecutionContext executionContext(AgentIdentity identity,
                                                               TurnExecutionState.Tools cursor, CallPosition position) {
        Map<String, String> attributes = Map.of("toolBatchId", cursor.batchId(),
                "toolOrdinal", Integer.toString(position.ordinal()),
                "modelRound", Integer.toString(cursor.common().modelRound()));
        return new AgentTool.ExecutionContext(identity, identity.toolCallKey(position.call().callId()), attributes);
    }

    /**
     * 初次准备保留 Provider 的完整解析状态，截断参数永远不能进入副作用边界。
     */
    private static AgentTool.Invocation invocation(CallPosition position) {
        ToolCallContent call = position.call();
        return new AgentTool.Invocation(call.callId(), call.name(), call.arguments(), position.ordinal(),
                call.preflightFailureCode());
    }

    /**
     * 从通用执行快照读取唯一最新事实，不把旧 call 的记录误配给当前 Tools ordinal。
     */
    private static <T extends ExecutionFact> Optional<T> latest(ExecutionStateView view,
                                                                Class<T> type, String callId) {
        return view.latest(type, value -> switch (value) {
            case ExecutionFacts.ToolPrepared prepared -> prepared.callId().equals(callId);
            case ExecutionFacts.ToolStarted started -> started.callId().equals(callId);
            case ExecutionFacts.ToolResult result -> result.callId().equals(callId);
            case ExecutionFacts.RecoveryEvidence evidence -> evidence.callId().equals(callId);
            case ExecutionFacts.Approval approval -> approval.callId().equals(callId);
            default -> false;
        });
    }

    /**
     * 同一 Tools cursor 只能存在一个最新恢复聚合，旧 call 的已结算恢复不会被当前调用复用。
     */
    private static Optional<ToolRecoveryDecision> recoveryDecision(Host host, String callId) {
        return host.view().latestRecord("agent.tool.recovery.decision")
                .map(host.services().controls()::decodeToolRecoveryDecision)
                .filter(value -> value.callId().equals(callId));
    }

    /**
     * 首次恢复前先公开可定位的 PENDING 身份，进程崩溃后仍可回答而不会重放 Started。
     */
    private static void persistInitialRecovery(Host host, TurnExecutionState.Tools cursor, CallPosition position) {
        Instant at = host.services().clock().instant();
        String recoveryId = host.identity().toolCallKey(position.call().callId());
        ToolRecoveryDecision pending = new ToolRecoveryDecision(recoveryId, position.call().callId(), 1,
                ToolRecoveryDecision.Status.PENDING, null, 0, null, null, null, at);
        AgentStore.RecordWrite write = encodeRecoveryDecision(host, pending, at);
        host.commit(TurnState.RUNNING, cursor, List.of(write),
                List.of(new ExecutionEvent.ToolRecoveryChanged(recoveryId, position.call().callId(), 1,
                        write.recordId())));
    }

    /**
     * 只读取当前固定水位最新控制命令，并按独立 consumption fact 判断它是否已原子消费。
     */
    private static Optional<PendingRecoveryCommand> recoveryCommand(Host host, ToolRecoveryDecision pending) {
        Optional<AgentStore.StoredRecord> latest = host.view().latestRecord(RECOVERY_COMMAND_TYPE);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        AgentStore.StoredRecord record = latest.orElseThrow();
        RecoveryCommand command = host.services().controls().decodeRecoveryCommand(record);
        Optional<AgentStore.StoredRecord> consumedRecord = host.view().latestRecord(RECOVERY_COMMAND_CONSUMED_TYPE);
        if (consumedRecord.isPresent()) {
            RecoveryCommandConsumption consumed = host.services().controls()
                    .decodeRecoveryCommandConsumption(consumedRecord.orElseThrow());
            if (consumed.commandRecordId().equals(record.recordId())
                && consumed.operationKey().equals(command.operationKey())) {
                    return Optional.empty();
                }
        }
        if (!command.recoveryId().equals(pending.recoveryId())) {
            return Optional.empty();
        }
        if (!command.callId().equals(pending.callId()) || command.expectedRevision() != pending.revision()) {
            throw corrupt("recovery command does not match the pending recovery revision");
        }
        return Optional.of(new PendingRecoveryCommand(record, command));
    }

    /**
     * 一次恢复命令在其结果、重试 intent 或新的 PENDING 决策提交时一同标记消费。
     */
    private static AgentStore.RecordWrite commandConsumption(Host host, PendingRecoveryCommand pending,
                                                             Instant at) {
        RecoveryCommand command = pending.command();
        RecoveryCommandConsumption consumption = new RecoveryCommandConsumption(
                pending.record().recordId(), command.operationKey(), at);
        String key = pending.record().recordId() + ":" + command.operationKey();
        String id = recordId(host, RECOVERY_COMMAND_CONSUMED_TYPE, key);
        return host.services().controls().encodeRecoveryCommandConsumption(id, consumption);
    }

    /**
     * RETRY 后仍无法结算时更新可回答的 pending revision，并与本次授权消费同事务提交。
     */
    private static void persistPendingRecovery(Host host, TurnExecutionState.Tools cursor,
                                               ToolRecoveryDecision previous, PendingRecoveryCommand command) {
        Instant at = host.services().clock().instant();
        ToolRecoveryDecision.Disposition disposition = previous.disposition() == ToolRecoveryDecision.Disposition.RETRY
                ? ToolRecoveryDecision.Disposition.RETRY : null;
        ToolRecoveryDecision pending = new ToolRecoveryDecision(previous.recoveryId(), previous.callId(),
                Math.addExact(previous.revision(), 1), ToolRecoveryDecision.Status.PENDING, disposition,
                disposition == null ? 0 : previous.attempt(), disposition == null ? null : previous.attemptId(),
                null, null, at);
        AgentStore.RecordWrite decisionWrite = encodeRecoveryDecision(host, pending, at);
        List<AgentStore.RecordWrite> writes = new ArrayList<>();
        writes.add(decisionWrite);
        if (command != null) {
            writes.add(commandConsumption(host, command, at));
        }
        host.commit(TurnState.RUNNING, cursor, writes,
                List.of(new ExecutionEvent.ToolRecoveryChanged(pending.recoveryId(), pending.callId(),
                        pending.revision(), decisionWrite.recordId())));
    }

    /**
     * 保留 Unknown 的不确定性；显式 RETRY 只消费授权并重开选择，不会把 Unknown 变成派发许可。
     */
    private static CompletionStage<Void> recoveryUnknown(Host host, TurnExecutionState.Tools cursor,
                                                         ToolRecoveryDecision pending, PendingRecoveryCommand command,
                                                         AgentTool.RecoveryResolution.Unknown unknown) {
        boolean mustAdvanceDecision = command != null || pending.disposition() != null;
        if (mustAdvanceDecision) {
            try {
                persistPendingRecovery(host, cursor, pending, command);
            } catch (RuntimeException | Error failure) {
                closeResourceWithoutMasking(unknown.settlementResource(), failure);
                throw failure;
            }
        }
        if (!closeRecoveryResource(unknown.settlementResource(), host)) {
            return CompletableFuture.completedFuture(null);
        }
        host.suspend(unknown.reasonCode());
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 根据核验与真实 ToolResult 原子结算 recovery decision，并让事件精确引用该记录。
     */
    private static void appendRecoverySettlement(Host host, List<AgentStore.RecordWrite> writes,
                                                 List<ExecutionEvent> events, RecoverySettlement recovery, String resultRecordId, Instant at) {
        ToolRecoveryDecision previous = recovery.pending();
        if (recovery.command() != null) {
            writes.add(commandConsumption(host, recovery.command(), at));
        }
        ToolRecoveryDecision settled;
        if (previous.disposition() == ToolRecoveryDecision.Disposition.RETRY) {
            settled = new ToolRecoveryDecision(previous.recoveryId(), previous.callId(),
                    Math.addExact(previous.revision(), 1), ToolRecoveryDecision.Status.RETRIED,
                    ToolRecoveryDecision.Disposition.RETRY, previous.attempt(), previous.attemptId(),
                    resultRecordId, null, at);
        } else if (recovery.reconciled() && recovery.verificationFingerprint() != null) {
            writes.add(encode(host, new ExecutionFacts.ToolRecoveryVerified(previous.callId(), recovery.attempt(),
                            recovery.verificationFingerprint()), "agent.tool.recovery.verified",
                    "tool-recovery-verified:" + previous.callId() + ":" + recovery.attempt(), at));
            settled = new ToolRecoveryDecision(previous.recoveryId(), previous.callId(),
                    Math.addExact(previous.revision(), 1), ToolRecoveryDecision.Status.VERIFIED,
                    ToolRecoveryDecision.Disposition.VERIFIED, 0, null, resultRecordId,
                    recovery.verificationFingerprint(), at);
        } else {
            throw corrupt("recovered Tool result has no verification evidence or retry intent");
        }
        AgentStore.RecordWrite decisionWrite = encodeRecoveryDecision(host, settled, at);
        writes.add(decisionWrite);
        events.add(new ExecutionEvent.ToolRecoveryChanged(settled.recoveryId(), settled.callId(),
                settled.revision(), decisionWrite.recordId()));
    }

    /**
     * 初次派发结局未知时先原子公开恢复身份，再暂停当前执行者。
     */
    private static CompletionStage<Void> unknownInitialExecution(Host host, TurnExecutionState.Tools cursor,
                                                                 CallPosition position) {
        persistInitialRecovery(host, cursor, position);
        host.suspend("TOOL_EXECUTION_OUTCOME_UNKNOWN");
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 只在显式 approval wait 或工具自身声明中记录审批要求，拒绝恢复时弱化授权。
     */
    private static boolean approvalRequired(AgentToolRunner.PreparedCall call,
                                            AgentMiddleware.WaitRequest request) {
        return call.approvalRequirement() == AgentTool.ApprovalRequirement.REQUIRED
               || request.kind().equals("approval");
    }

    /**
     * 明确跳过保留的未知副作用只写失败事实，不宣称外部动作未发生或核验成功。
     */
    private static CompletionStage<Void> skipRecovery(Host host, TurnExecutionState.Tools cursor,
                                                      CallPosition position, ToolRecoveryDecision pending, PendingRecoveryCommand command) {
        Instant at = host.services().clock().instant();
        String text = "The previous Tool outcome remains unknown; recovery was skipped. "
                      + "External effects may already have occurred.";
        AgentTool.ToolResult result = failed("TOOL_RECOVERY_SKIPPED", text);
        AgentToolRunner.ProjectedResult projection = plainProjection(result);
        List<AgentStore.RecordWrite> writes = new ArrayList<>();
        appendToolResult(host, writes, projection, position.call().callId(), at);
        AgentStore.RecordWrite resultWrite = findFactWrite(host, writes, "agent.tool.result",
                "tool-result:" + position.call().callId());
        AgentStore.RecordWrite messageWrite = findFactWrite(host, writes, "agent.tool.message",
                "tool-message:" + position.call().callId());
        writes.add(commandConsumption(host, command, at));
        ToolRecoveryDecision skipped = new ToolRecoveryDecision(pending.recoveryId(), pending.callId(),
                Math.addExact(pending.revision(), 1), ToolRecoveryDecision.Status.SKIPPED,
                ToolRecoveryDecision.Disposition.SKIP, 0, null, resultWrite.recordId(), null, at);
        AgentStore.RecordWrite decisionWrite = encodeRecoveryDecision(host, skipped, at);
        writes.add(decisionWrite);
        List<ExecutionEvent> events = new ArrayList<>();
        events.add(completedEvent(position, result, resultWrite.recordId(), messageWrite.recordId()));
        events.add(new ExecutionEvent.ToolRecoveryChanged(skipped.recoveryId(), skipped.callId(),
                skipped.revision(), decisionWrite.recordId()));
        host.commit(TurnState.RUNNING, cursorAfter(cursor, promptState(host, cursor, false, false)), writes, events);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 决策载荷和事件始终由相同 record id 引用，保持跨存储恢复水位一致。
     */
    private static AgentStore.RecordWrite encodeRecoveryDecision(Host host,
                                                                 ToolRecoveryDecision decision, Instant at) {
        String key = decision.recoveryId() + ":" + decision.revision();
        String id = recordId(host, RECOVERY_DECISION_TYPE, key);
        return host.services().controls().encodeToolRecoveryDecision(id, decision, at);
    }

    /**
     * 校验 Started attempt 与持久核验记录相符，避免恢复时把更旧的证据配给新的派发。
     */
    private static void validateStartedAttempt(ExecutionStateView view, String callId,
                                               ExecutionFacts.ToolStarted started) {
        if (!started.callId().equals(callId)) {
            throw corrupt("Started fact belongs to another call");
        }
        Optional<ExecutionFacts.RecoveryEvidence> evidence = view.latest(ExecutionFacts.RecoveryEvidence.class,
                value -> value.callId().equals(callId));
        if (evidence.isPresent() && evidence.orElseThrow().attempt() != started.attempt()) {
            throw corrupt("recovery evidence does not match the latest Started attempt");
        }
        if (started.attempt() > 0) {
            ExecutionFacts.ToolRetryIntent retry = view.latest(ExecutionFacts.ToolRetryIntent.class,
                    value -> value.callId().equals(callId)).orElseThrow(
                    () -> corrupt("retry Started has no verified retry intent"));
            if (retry.nextAttempt() != started.attempt()) {
                throw corrupt("retry intent does not match the Started attempt");
            }
        }
    }

    /**
     * 同一调用最多有一个等待聚合；多个等待记录表示事务或恢复状态已损坏。
     */
    private static Optional<InteractionWait> waitForCall(ExecutionStateView view, String callId) {
        List<InteractionWait> matches = view.waits().stream()
                .filter(wait -> callId.equals(wait.toolCallId())).toList();
        if (matches.size() > 1) {
            throw corrupt("Tool call has multiple persisted waits");
        }
        return matches.stream().findFirst();
    }

    /**
     * 根据等待事务中冻结的 kind 区分审批超时与普通澄清超时，不从 Tool 要求臆测等待类型。
     */
    private static boolean approvalWait(InteractionWait wait) {
        if (!(wait.attributes().get("kind") instanceof JsonText(String value))) {
            throw corrupt("expired Tool wait has no persisted kind");
        }
        return "approval".equals(value);
    }

    /**
     * 生成非 Ja 语义的工具拒绝投影，未知能力不会泄露注册表或工作区细节。
     */
    private static AgentToolRunner.ProjectedResult plainProjection(AgentTool.ToolResult result) {
        ToolResultProjection visible = new AgentMiddleware.ToolResultProjection(result.content(), result.structuredContent());
        return new AgentToolRunner.ProjectedResult(visible, AgentTool.ExecutionResult.of(result), List.of());
    }

    /**
     * 把调用拒绝转换为模型可纠正结果，不包含未经信任的调用参数。
     */
    private static AgentTool.ToolResult failed(String code, String message) {
        return new AgentTool.ToolResult(ToolOutcome.FAILED, message, Optional.empty(), code);
    }

    /**
     * 结果提交后释放跨事务资源；释放失败暂停后续工作，避免 Workspace claim 留在内存占用。
     */
    private static boolean closeSettlement(AgentTool.ExecutionResult result, Host host) {
        try {
            result.close();
            return true;
        } catch (RuntimeException failure) {
            host.suspend("TOOL_SETTLEMENT_RELEASE_FAILED");
            return false;
        }
    }

    /**
     * 关闭核验租约；失败时保留会话并暂停派发，避免下一步和未释放资源竞争。
     */
    private static boolean closeRecoveryResource(AgentTool.SettlementResource resource, Host host) {
        try {
            resource.close();
            return true;
        } catch (RuntimeException failure) {
            host.suspend("TOOL_SETTLEMENT_RELEASE_FAILED");
            return false;
        }
    }

    /**
     * 清理失败不能覆盖原始提交或fencing错误，只将关闭异常附在主故障上。
     */
    private static void closeSettlementWithoutMasking(AgentTool.ExecutionResult result, Throwable failure) {
        try {
            result.close();
        } catch (RuntimeException | Error closeFailure) {
            if (closeFailure != failure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    /**
     * 清理失败不能掩盖 Store 故障，也不能因此声称结果已经挂起或结算。
     */
    private static void closeResourceWithoutMasking(AgentTool.SettlementResource resource, Throwable failure) {
        try {
            resource.close();
        } catch (RuntimeException | Error closeFailure) {
            if (closeFailure != failure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    /**
     * 统一从真实 outcome 派生存储和事件使用的闭集 ToolState。
     */
    private static ToolState toolState(ToolOutcome outcome) {
        return switch (Objects.requireNonNull(outcome, "outcome")) {
            case SUCCEEDED -> ToolState.SUCCEEDED;
            case FAILED -> ToolState.FAILED;
            case CANCELLED -> ToolState.CANCELLED;
        };
    }

    /**
     * 将存储损坏归类为不可恢复完整性错误，而不是静默跳过一个 Tool。
     */
    private static AgentStore.StoreIntegrityException corrupt(String reason) {
        return new AgentStore.StoreIntegrityException(reason, null);
    }

    /**
     * 阻止其他 cursor 类型误入工具步骤，避免按形状猜测执行阶段。
     */
    private static TurnExecutionState.Tools tools(TurnExecutionState cursor) {
        if (cursor instanceof TurnExecutionState.Tools tools) {
            return tools;
        }
        throw new IllegalArgumentException("Tool step requires a Tools cursor");
    }

    /**
     * 同一结果事实序号用于确定 presentation revision 与其他阶段 facts 的持久身份。
     */
    private enum FactStage {
        /**
         * 在 ToolPrepared commit 中保存的宿主 facts。
         */
        PREPARED("prepared"),
        /**
         * 在 ToolStarted commit 中保存的工具启动 facts。
         */
        STARTED("started"),
        /**
         * 与权威 ToolResult 同事务提交的终态 facts。
         */
        RESULT("result");

        private final String key;

        /**
         * 固定稳定阶段名，避免把 Java enum 字符串格式写成业务身份。
         */
        FactStage(String key) {
            this.key = key;
        }

        /**
         * 返回跨进程稳定的事实键片段。
         */
        String key() {
            return key;
        }
    }

    /**
     * Session 只提供事务所有权和已解码视图，具体 Tool 事实由本步骤集中构造。
     */
    interface Host {
        /**
         * 返回执行租户、会话、任务和调用者身份。
         */
        AgentIdentity identity();

        /**
         * 返回本次冻结的模型、工具、middleware 和 Ja 事务参与者。
         */
        AgentRequestRuntime runtime();

        /**
         * 暴露唯一 Store codec/时钟边界；工具步骤不自行创建数据库资源。
         */
        AgentExecutionDriver.Services services();

        /**
         * 返回当前固定水位的已解码执行事实与 extension state。
         */
        ExecutionStateView view();

        /**
         * 返回完整游标，步骤仅接受 Tools 分支并负责推动一个 ordinal。
         */
        TurnExecutionState cursor();

        /**
         * 返回与当前会话执行权联动的取消令牌。
         */
        CancellationToken cancellation();

        /**
         * 在真实 Tool dispatch 前续租并检查当前 fencing authority。
         */
        void assertDispatchAuthority();

        /**
         * 将游标、records、events 与请求 TransactionParticipant 一起原子提交。
         */
        AgentStore.CommitReceipt commit(TurnState target, TurnExecutionState next,
                                        List<AgentStore.RecordWrite> records, List<ExecutionEvent> events);

        /**
         * 将 ToolPrepared、等待问题和可选审批事实一并原子提交并释放执行线程。
         */
        void openWait(AgentMiddleware.WaitRequest request, String callId,
                      List<AgentStore.RecordWrite> records);

        /**
         * 保留非终态恢复资格并停止当前 Session 的进一步派发。
         */
        void suspend(String reasonCode);
    }

    /**
     * 将唯一 Tool batch position 作为一对不可变的 Provider call 与持久 ordinal。
     *
     * @param call 调用
     * @param ordinal 当前项的顺序位置
     */
    private record CallPosition(ToolCallContent call,
                                int ordinal) {
        /**
         * 游标索引必须和调用一起冻结，避免异步执行期间读取下一个工具。
         */
        private CallPosition {
            Objects.requireNonNull(call, "call");
            if (ordinal < 0) {
                throw new IllegalArgumentException("negative Tool ordinal");
            }
        }
    }

    /**
     * 纯结算计划把稳定 record id、事实、事件和后继游标一起交给 Session 原子提交。
     *
     * @param facts 事实列表
     * @param events 事件列表
     * @param nextCursor 读取下一页时使用的游标
     */
    record RemainingSettlement(List<FactWrite> facts,
                               List<ExecutionEvent> events,
                               TurnExecutionState nextCursor) {
        /**
         * 冻结所有记录与事件，避免终态提交前被调用方改写已配对事实。
         */
        RemainingSettlement {
            facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
            events = List.copyOf(Objects.requireNonNull(events, "events"));
            Objects.requireNonNull(nextCursor, "nextCursor");
        }
    }

    /**
     * 单个业务无关事实及其稳定记录 ID，不在纯规划阶段访问 Store 或 codec。
     *
     * @param recordId 记录标识
     * @param fact 事实
     */
    record FactWrite(String recordId,
                     ExecutionFact fact) {
        /**
         * 要求可持久化的 record id 与非空领域事实。
         */
        FactWrite {
            recordId = io.github.kongweiguang.ja.agent.domain.validation.ContractChecks.identifier(
                    recordId, "recordId");
            Objects.requireNonNull(fact, "fact");
        }
    }

    /**
     * 绑定固定水位中的原始命令身份，决定提交必须同时写入对应消费事实。
     *
     * @param record 记录
     * @param command 命令
     */
    private record PendingRecoveryCommand(AgentStore.StoredRecord record,
                                          RecoveryCommand command) {
        /**
         * 避免消费事实与另一个同 callId 的授权请求错配。
         */
        private PendingRecoveryCommand {
            Objects.requireNonNull(record, "record");
            Objects.requireNonNull(command, "command");
        }
    }

    /**
     * 一次恢复结果的提交元数据；它只参与同事务事实构造，不替代 durable decision。
     *
     * @param pending 待处理
     * @param command 命令
     * @param verificationFingerprint 副作用核验证据的指纹
     * @param attempt 尝试次数
     * @param reconciled 是否已完成恢复对账
     */
    private record RecoverySettlement(ToolRecoveryDecision pending,
                                      PendingRecoveryCommand command,
                                      String verificationFingerprint,
                                      int attempt,
                                      boolean reconciled) {
        /**
         * 检查核验结算的身份和 attempt，防止不同调用的证据被拼接进同一结果提交。
         */
        private RecoverySettlement {
            Objects.requireNonNull(pending, "pending");
            if (attempt < 0) {
                throw new IllegalArgumentException("negative recovery attempt");
            }
            if (reconciled && verificationFingerprint == null
                && pending.disposition() != ToolRecoveryDecision.Disposition.RETRY) {
                throw new IllegalArgumentException("verified recovery settlement requires evidence fingerprint");
            }
        }
    }

    /**
     * 拒绝把未核实的 Started 调用伪装为未派发并终态收口。
     */
    static final class UnsettledToolException extends IllegalStateException {
        private final String callId;

        /**
         * 保留阻塞调用身份，使 Session 能继续挂起而不丢失核验入口。
         */
        UnsettledToolException(String callId) {
            super("Tool outcome requires recovery");
            this.callId = callId;
        }

        /**
         * 返回需继续恢复流程的原始 callId。
         */
        String callId() {
            return callId;
        }
    }

    /**
     * beforeTool 可能拒绝已冻结调用，也可能刷新规则；两种结果都必须反馈游标提交。
     *
     * @param denial 拒绝原因
     * @param refreshed 是否已刷新
     */
    private record PromptGuard(AgentTool.ToolResult denial,
                               boolean refreshed) {
    }
}
