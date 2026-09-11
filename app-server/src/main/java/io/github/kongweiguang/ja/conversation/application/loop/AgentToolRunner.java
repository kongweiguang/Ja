// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.application.approval.ApprovalBroker;
import io.github.kongweiguang.ja.conversation.application.observation.ExecutionObservers;
import io.github.kongweiguang.ja.conversation.application.policy.ToolPolicyChain;
import io.github.kongweiguang.ja.conversation.application.policy.PlanToolPolicy;
import io.github.kongweiguang.ja.conversation.application.presentation.ToolPresentationProjector;
import io.github.kongweiguang.ja.conversation.application.interaction.InteractionSuspendedException;
import io.github.kongweiguang.ja.conversation.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.conversation.domain.TurnChangeSet;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.conversation.domain.permission.PermissionAction;
import io.github.kongweiguang.ja.conversation.domain.permission.PermissionRequest;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSession;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.GoalToolExecutionPort;
import io.github.kongweiguang.ja.conversation.port.out.ExecutionObserver;
import io.github.kongweiguang.ja.conversation.port.out.ToolArgumentValidator;
import io.github.kongweiguang.ja.conversation.port.out.ToolPolicy;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** 按模型输出顺序串行执行 Tool，并把逐次审批与结果提交保持在同一确定性路径。 */
final class AgentToolRunner implements AutoCloseable {
    private final ApprovalBroker approvalBroker;
    private final Clock clock;
    private final ToolPolicyChain policies;
    private final ExecutionObservers observers;
    private final ToolArgumentValidator argumentValidator;
    private volatile WorkspaceWriteLeaseCoordinator writeLeases;
    private volatile GoalToolExecutionPort goalTools;
    private volatile boolean closed;

    /** 审批归内核所有；外部策略只能收紧准入，观察器只能接收安全执行元数据。 */
    AgentToolRunner(ApprovalBroker approvalBroker, Clock clock,
                    ToolPolicyChain policies, ExecutionObservers observers,
                    ToolArgumentValidator argumentValidator) {
        this.approvalBroker = Objects.requireNonNull(approvalBroker, "approvalBroker");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.observers = Objects.requireNonNull(observers, "observers");
        this.argumentValidator = Objects.requireNonNull(argumentValidator, "argumentValidator");
        this.writeLeases = WorkspaceWriteLeaseCoordinator.disabled(clock);
        this.goalTools = GoalToolExecutionPort.disabled();
    }

    /** 生产组合根在 Loop 发布前替换 package 测试的无状态实现，重复绑定时关闭旧 heartbeat owner。 */
    void bindWriteLeases(WorkspaceWriteLeaseCoordinator coordinator) {
        WorkspaceWriteLeaseCoordinator replacement = Objects.requireNonNull(coordinator, "coordinator");
        if (writeLeases == replacement) return;
        try {
            writeLeases.close();
        } finally {
            writeLeases = replacement;
        }
    }

    /** 生产组合根只绑定一次 Goal ledger；测试和非 Goal Turn 继续使用空实现。 */
    void bindGoalTools(GoalToolExecutionPort port) {
        this.goalTools = Objects.requireNonNull(port, "port");
    }

    /** 为每个 Tool batch 冻结当前 Goal ledger，防止执行中热替换 owner 造成身份不一致。 */
    GoalToolExecutionPort goalTools() {
        return goalTools;
    }

    /**
     * 每个调用先单独提交 RUNNING intent，再把单一结果、单元素 TOOL Message 和下一游标原子结算；
     * 后一个调用因此只能观察到前一个调用已持久完成的真实状态。
     * 只有内建 Tool 显式声明的可信内核操作免去外部动作审批，实际 MCP 与其它工具仍遵守原权限策略。
     */
    List<AgentTool.ToolResult> execute(Execution execution, List<AgentTool.Invocation> calls) {
        Objects.requireNonNull(execution, "execution");
        calls = List.copyOf(Objects.requireNonNull(calls, "calls"));
        List<AgentTool.ToolResult> results = new ArrayList<>(calls.size());
        for (int index = 0; index < calls.size(); index++) {
            AgentTool.Invocation call = calls.get(index);
            AgentTool tool = execution.catalog().get(call.toolName());
            AgentTool.ToolResult result = null;
            long duration = 0;
            boolean started = false;
            RuntimeException promptRefreshFailure = null;
            Optional<String> argumentError = tool == null
                    ? Optional.empty() : argumentValidator.invalidReason(tool.spec().inputSchema(), call.arguments());
            if (tool == null) {
                result = failed("TOOL_BINDING_UNAVAILABLE",
                        "Tool '" + call.toolName()
                                + "' is unavailable for this call. Use a Tool from the current catalog.");
            } else if (argumentError.isPresent()) {
                result = failed("TOOL_ARGUMENTS_INVALID",
                        argumentError.orElseThrow() + ". Correct the arguments and retry this Tool.");
            } else {
                AgentTool.ExecutionContext toolExecution = executionContext(execution, call);
                ToolSideEffect sideEffect = tool.sideEffect();
                AgentPromptSession.ToolGuard promptGuard = execution.command().promptSession().beforeTool(
                        call, sideEffect, execution.command().promptSession().currentRevision());
                if (!promptGuard.proceed()) {
                    result = failed(promptGuard.code(), promptGuard.message());
                } else {
                    ToolPolicy.Decision decision = null;
                    try {
                        decision = policies.evaluate(new ToolPolicy.Context(call, toolExecution, sideEffect));
                    } catch (CancellationException cancelled) {
                        if (!execution.cancellation().isCancellationRequested()) throw cancelled;
                        result = cancelledResult();
                    }
                    if (result == null && !Objects.requireNonNull(decision, "Tool policy decision").proceed()) {
                        result = failed(decision.code(), decision.message());
                    } else if (result == null) {
                        ToolPolicy.Decision planDecision = PlanToolPolicy.validate(tool,
                                execution.command().origin(), execution.collaborationMode());
                        if (!planDecision.proceed()) {
                            result = failed(planDecision.code(), planDecision.message());
                        }
                    }
                    if (result == null) {
                        try {
                            if (toolExecution.accessMode()
                                    == io.github.kongweiguang.ja.conversation.domain.permission.AccessMode
                                    .APPROVAL_REQUIRED
                                    && requiresExternalApproval(tool)
                                    && !awaitApproval(execution, call)) {
                                result = failed("TOOL_DENIED", "Tool denied by user");
                            }
                        } catch (CancellationException cancelled) {
                            if (!execution.cancellation().isCancellationRequested()) throw cancelled;
                            result = cancelledResult();
                        }
                    }
                    if (result == null) {
                        /* 可信内建 Tool 只改变 Ja 的控制面；跳过 Goal attempt，避免 request_user_input
                         * 抛出挂起信号时留下 STARTED 的伪执行记录。真正的 Plan/Goal 工作 Tool 仍走 ledger。 */
                        Optional<GoalToolExecutionPort.Attempt> goalAttempt = isTrustedInternal(tool)
                                ? Optional.empty()
                                : execution.goalTools().prepare(
                                        new GoalToolExecutionPort.Prepare(execution.command().threadId(),
                                                execution.command().turnId(), execution.command().origin(),
                                                call.callId(), call.toolName(),
                                                call.arguments(), sideEffect, clock.instant()));
                        execution.writer().commit(TurnState.RUNNING,
                                new TurnEvent.ToolStarted(execution.draftContext().get(),
                                        call.callId(), call.ordinal()),
                                List.of(new ConversationRepository.ToolStartedFact(call.callId())),
                                execution.cursor().get());
                        if (tool.workspaceMutationMode() == AgentTool.WorkspaceMutationMode.UNOBSERVABLE) {
                            execution.command().changeTracker()
                                    .markIncomplete(io.github.kongweiguang.ja.conversation.domain.TurnChangeSet
                                            .IncompleteReason.UNKNOWN_MUTATOR);
                        }
                        goalAttempt.ifPresent(attempt -> execution.goalTools().start(attempt, clock.instant()));
                        observers.observe(new ExecutionObserver.ToolStarted(
                                execution.command().threadId(), execution.command().turnId(), call.callId(),
                                call.toolName(), call.ordinal(), sideEffect));
                        started = true;
                        long startedAt = System.nanoTime();
                        result = executeOne(execution, call, tool);
                        duration = Math.max(0L,
                                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                        AgentTool.ToolResult settledResult = result;
                        goalAttempt.ifPresent(attempt -> execution.goalTools().settle(attempt,
                                new GoalToolExecutionPort.Settlement(settledResult.outcome(),
                                        settledResult.content(), settledResult.errorCode(), clock.instant())));
                        try {
                            execution.command().promptSession().afterTool(call, result);
                        } catch (RuntimeException failure) {
                            promptRefreshFailure = failure;
                        }
                    }
                }
            }
            results.add(result);
            publishResult(execution, call, result, duration,
                    index == calls.size() - 1);
            observers.observe(toolCompleted(execution, call, result, duration, started));
            if (promptRefreshFailure != null) throw promptRefreshFailure;
        }
        return List.copyOf(results);
    }

    /**
     * 审批豁免必须同时满足内建路由和显式内核标记；路由检查防止 MCP 适配器伪造内部审批语义。
     */
    private static boolean requiresExternalApproval(AgentTool tool) {
        return !isTrustedInternal(tool);
    }

    /** 可信控制面身份必须同时来自内建路由与审批元数据，外部 Tool 不能借标记逃避 Goal 记录。 */
    private static boolean isTrustedInternal(AgentTool tool) {
        return tool.bindingDescriptor().routeKind() == AgentTool.RouteKind.BUILTIN
                && tool.approvalRequirement() == AgentTool.ApprovalRequirement.TRUSTED_INTERNAL;
    }

    /**
     * 只在结果与 Goal ledger 已结算后构造安全完成观察；未越过执行边界的普通失败明确归为拒绝，
     * 不补造对应的 started 事件。
     */
    private static ExecutionObserver.ToolCompleted toolCompleted(
            Execution execution, AgentTool.Invocation call, AgentTool.ToolResult result,
            long duration, boolean started) {
        ExecutionObserver.CompletionStatus status;
        if (result.outcome() == ToolOutcome.CANCELLED) {
            status = ExecutionObserver.CompletionStatus.CANCELLED;
        } else if (result.errorCode() != null && result.errorCode().contains("TIMEOUT")) {
            status = ExecutionObserver.CompletionStatus.TIMED_OUT;
        } else if (!started) {
            status = ExecutionObserver.CompletionStatus.REJECTED;
        } else if (result.outcome() == ToolOutcome.SUCCEEDED) {
            status = ExecutionObserver.CompletionStatus.SUCCEEDED;
        } else {
            status = ExecutionObserver.CompletionStatus.FAILED;
        }
        return new ExecutionObserver.ToolCompleted(
                execution.command().threadId(), execution.command().turnId(), call.callId(), call.toolName(),
                call.ordinal(), status, result.outcome(), result.errorCode(), duration);
    }


    /**
     * 审批等待被 Turn 取消时仍生成可持久化结果，使原生 Tool call/result 历史保持配对；空正文
     * 避免把内部取消原因或路径带入后续 Provider 上下文。
     */
    private static AgentTool.ToolResult cancelledResult() {
        return new AgentTool.ToolResult(ToolOutcome.CANCELLED, "", Optional.empty(), "CANCELLED");
    }

    /** 关闭只阻止后续 Tool 准入；当前调用由 Turn CancellationToken 负责清理。 */
    @Override
    public void close() {
        closed = true;
        writeLeases.close();
    }

    /** 绝对截止线不再管理内部线程池，方法仅保持 AgentLoop 生命周期调用边界。 */
    void closeAt(long shutdownDeadlineNanos) {
        closed = true;
    }

    /**
     * 在 Tool 前登记一次审批并持久化 WAITING_APPROVAL/恢复状态；提交前吸收外部取消的最新 CAS
     * authority，避免把并发取消误判为普通内部错误，拒绝仍作为普通 ToolResult 返回。
     */
    private boolean awaitApproval(Execution execution, AgentTool.Invocation call) {
        Instant expiresAt = minimum(clock.instant().plus(Duration.ofMinutes(5)), deadline(execution.command()));
        PermissionRequest permission = permission(execution, call);
        ConversationRepository.PendingApproval persisted = execution.approvalLookup().find(call.callId()).orElse(null);
        if (persisted != null && persisted.decision() != null) {
            return persisted.decision() == ApprovalDecision.APPROVE;
        }
        ApprovalBroker.ApprovalRequest approval = new ApprovalBroker.ApprovalRequest(
                persisted == null ? "appr_" + UUID.randomUUID() : persisted.approvalId(), permission,
                "Tool requires approval", persisted == null ? expiresAt : persisted.expiresAt());
        var prepared = ToolPresentationProjector.prepared(call, execution.command().workspaceRoot(),
                execution.command().presentationSecrets());
        /*
         * Resume 先把 SUSPENDED 推进到 RUNNING；既有审批也必须重新取得 WAITING_APPROVAL
         * 状态门后才能登记 waiter，否则持久 resolve 会因状态不匹配而永久拒绝。审批行只在首次请求插入。
         */
        List<ConversationRepository.Fact> approvalFacts = persisted == null
                ? List.of(new ConversationRepository.ApprovalFact(
                        approval.approvalId(), call.callId(), null, approval.expiresAt(),
                        ToolPresentationProjector.withStatus(prepared,
                                io.github.kongweiguang.ja.conversation.domain.ToolPresentation.Status.WAITING_APPROVAL)))
                : List.of();
        commitWaitingApproval(execution, call, approval, approvalFacts);
        ApprovalBroker.Resolution resolution = await(approvalBroker.request(approval, execution.cancellation()));
        /* Broker 为保证审批账本闭环会把取消持久化为 DENY；这里必须重新读取 Turn Token，避免把
         * 系统取消误解释为用户拒绝并走普通 FAILED Tool 提交。 */
        execution.cancellation().throwIfCancellationRequested();
        if (!approval.approvalId().equals(resolution.approvalId())
                || resolution.resolvedAt().isAfter(approval.expiresAt())) {
            throw new AgentLoop.LoopFailure("APPROVAL_EXPIRED", "approval response is stale");
        }
        execution.refreshAuthority().run();
        execution.resolvedPublisher().publish(resolution.approvalId(), resolution.response());
        return resolution.response() == ApprovalDecision.APPROVE;
    }

    /**
     * WAITING_APPROVAL 提交前刷新外部 owner 的 authority 并检查取消；若取消恰好在检查与 CAS
     * 之间获胜，则失败后再次刷新并只在 Token 已确认时转为 CancellationException，使既有取消
     * Tool batch 补齐配对结果。非取消失败必须原样抛出，禁止把真实持久化故障伪装成审批等待。
     */
    private static void commitWaitingApproval(
            Execution execution,
            AgentTool.Invocation call,
            ApprovalBroker.ApprovalRequest approval,
            List<ConversationRepository.Fact> approvalFacts) {
        execution.refreshAuthority().run();
        execution.cancellation().throwIfCancellationRequested();
        try {
            execution.writer().commit(TurnState.WAITING_APPROVAL,
                    new TurnEvent.ApprovalRequested(execution.draftContext().get(), approval.approvalId(),
                            call.callId(), call.toolName(), approval.reason(), approval.expiresAt()),
                    approvalFacts, execution.cursor().get());
        } catch (RuntimeException failure) {
            try {
                execution.refreshAuthority().run();
            } catch (RuntimeException refreshFailure) {
                failure.addSuppressed(refreshFailure);
            }
            execution.cancellation().throwIfCancellationRequested();
            throw failure;
        }
    }

    /** 在同一 Turn 线程直接执行一次 Tool，异常只转换为该调用的结构化结果。 */
    private AgentTool.ToolResult executeOne(Execution execution, AgentTool.Invocation call, AgentTool tool) {
        if (closed) throw new CancellationException("Agent Tool runner is closing");
        try {
            execution.cancellation().throwIfCancellationRequested();
            AgentTool.ExecutionContext context = executionContext(execution, call);
            if (tool.sideEffect() == ToolSideEffect.READ_ONLY) {
                return await(tool.execute(call, context, execution.cancellation()));
            }
            return executeWithWriteLease(execution, call, tool, context);
        } catch (WorkspaceWriteLeaseCoordinator.LeaseFailure leaseFailure) {
            return new AgentTool.ToolResult(ToolOutcome.FAILED,
                    "Workspace write ownership was lost. Refresh the workspace state before retrying.",
                    Optional.empty(),
                    leaseFailure.code() == WorkspaceWriteLeaseCoordinator.LeaseFailure.Code.TIMEOUT
                            ? "WORKSPACE_WRITE_LEASE_TIMEOUT" : "WORKSPACE_WRITE_LEASE_LOST");
        } catch (CancellationException cancelled) {
            return new AgentTool.ToolResult(ToolOutcome.CANCELLED, "", Optional.empty(), "CANCELLED");
        } catch (InteractionSuspendedException suspended) {
            /* 请求已先落 SQLite；向 Turn 状态机透传，不能把用户等待伪装成 Tool 失败。 */
            throw suspended;
        } catch (RuntimeException failure) {
            boolean external = tool.sideEffect() == ToolSideEffect.EXTERNAL;
            return new AgentTool.ToolResult(ToolOutcome.FAILED,
                    external
                            ? "Tool execution did not return a confirmed result. "
                                    + "Check the actual external state before deciding whether to retry."
                            : "Tool execution failed. Correct the call or use another available Tool.",
                    Optional.empty(), external ? "TOOL_EXECUTION_UNCONFIRMED" : "TOOL_FAILED");
        }
    }

    /**
     * 写 Tool 使用租约派生取消令牌；close 故障优先于 Tool 的取消/返回值，确保 Heartbeat 丢失
     * 最终稳定收敛为 fencing 失败而不是成功或普通父取消。PMD 无法识别这种刻意反转
     * try-with-resources 异常优先级的手工结算，但 lease 在所有分支都会于本方法内关闭。
     */
    @SuppressWarnings("PMD.CloseResource")
    private AgentTool.ToolResult executeWithWriteLease(Execution execution, AgentTool.Invocation call,
                                                       AgentTool tool,
                                                       AgentTool.ExecutionContext context) {
        WorkspaceWriteLeaseCoordinator.Lease lease = writeLeases.acquire(context, execution.cancellation());
        AgentTool.ToolResult result = null;
        RuntimeException executionFailure = null;
        try {
            result = await(tool.execute(call, context, lease.cancellation()));
            lease.verifyOwnership();
        } catch (RuntimeException failure) {
            executionFailure = failure;
        }
        try {
            lease.close();
        } catch (RuntimeException releaseFailure) {
            if (executionFailure != null) releaseFailure.addSuppressed(executionFailure);
            executionFailure = releaseFailure;
        }
        if (executionFailure != null) throw executionFailure;
        return Objects.requireNonNull(result, "write Tool result");
    }

    /** 按原调用顺序一次提交 Started/Result 事实和 Tool Message。 */
    private static void publishResult(Execution execution, AgentTool.Invocation call,
                                       AgentTool.ToolResult result, long duration,
                                       boolean last) {
        List<ConversationRepository.Fact> facts = new ArrayList<>();
        ToolPresentationProjector.Completed completed = ToolPresentationProjector.completed(
                call, result, execution.command().workspaceRoot(), execution.command().presentationSecrets(), duration);
        facts.add(new ConversationRepository.ToolResultFact(call.callId(), toolState(result.outcome()),
                completed.artifactContent(), result.outcome() != ToolOutcome.SUCCEEDED,
                completed.presentation(), completed.artifactContent()));
        facts.add(new ConversationRepository.ToolResultMessageFact("item_" + UUID.randomUUID(),
                new ModelMessage(ModelRole.TOOL, List.of(new ToolResultContent(call.callId(),
                        completed.artifactContent(), result.outcome() != ToolOutcome.SUCCEEDED)))));
        TurnExecutionState.Tools current = execution.cursor().get();
        TurnExecutionState.Common common = promptCommon(current.common(), execution.command().promptSession());
        TurnExecutionState next = last
                ? new TurnExecutionState.Ready(common, TurnExecutionState.Next.ASSISTANT, null)
                : new TurnExecutionState.Tools(common, current.batchId(), current.assistantMessageId(),
                        current.firstOrdinal(), current.lastOrdinal(), call.ordinal() + 1);
        try {
            execution.writer().commit(TurnState.RUNNING,
                    new TurnEvent.ToolBatchCommitted(execution.draftContext().get(), List.of(
                            new TurnEvent.ToolBatchResult(call.callId(), result.outcome(), completed.presentation(),
                                    call.ordinal(), result.errorCode()))), facts, next);
        } catch (RuntimeException failure) {
            if (result.mutationReceipt().isPresent() || result.mutationObservationFailure().isPresent()) {
                execution.command().changeTracker().markCommitUnconfirmed();
            }
            throw failure;
        }
        result.mutationObservationFailure().map(AgentToolRunner::incompleteReason).ifPresent(reason -> {
            execution.command().changeTracker().markIncomplete(reason);
        });
        if (result.outcome() == ToolOutcome.SUCCEEDED) {
            if (execution.catalog().get(call.toolName()).workspaceMutationMode()
                    == AgentTool.WorkspaceMutationMode.EXACT_TEXT) {
                if (result.mutationReceipt().isEmpty()) {
                    execution.command().changeTracker()
                            .markIncomplete(io.github.kongweiguang.ja.conversation.domain.TurnChangeSet
                                    .IncompleteReason.CAPTURE_FAILED);
                } else {
                    result.mutationReceipt().ifPresent(execution.command().changeTracker()::apply);
                }
            }
        }
    }

    /** Tool Adapter 的无路径失败观察只映射到 ChangeSet 闭集，不进入持久 Tool 正文。 */
    private static TurnChangeSet.IncompleteReason incompleteReason(
            AgentTool.MutationObservationFailure failure) {
        return switch (failure) {
            case OUTSIDE_WORKSPACE -> TurnChangeSet.IncompleteReason.OUTSIDE_WORKSPACE;
            case CAPTURE_FAILED -> TurnChangeSet.IncompleteReason.CAPTURE_FAILED;
        };
    }

    /** 构造审批审计事实；资源列表已删除，Workspace 仅作为默认 cwd 展示。 */
    private static PermissionRequest permission(Execution execution, AgentTool.Invocation call) {
        ConversationRepository.ToolBinding binding = execution.bindingLookup().find(call.callId())
                .orElseThrow(() -> new AgentLoop.LoopFailure(
                        "TOOL_BINDING_UNAVAILABLE", "Tool binding is unavailable"));
        TurnExecutionPlan command = execution.command();
        return new PermissionRequest(command.threadId(), command.turnId(), command.model().configGeneration(),
                binding.accessMode(), actionKind(call.toolName()), call.toolName(), command.workspaceRoot(),
                List.of(), "shell".equals(call.toolName()) ? "shell command" : null);
    }

    /** 将四个内置名称和其它 MCP 名称映射到旧审计字段，字段不再参与授权判断。 */
    private static PermissionAction actionKind(String name) {
        return switch (name) {
            case "read" -> PermissionAction.READ;
            case "edit", "write" -> PermissionAction.WRITE;
            case "shell" -> PermissionAction.SHELL;
            default -> PermissionAction.MCP;
        };
    }

    /** Tool 执行上下文只携带稳定 Operation 身份、批次绑定权限、cwd 与绝对 Deadline。 */
    private static AgentTool.ExecutionContext executionContext(Execution execution, AgentTool.Invocation call) {
        ConversationRepository.ToolBinding binding = execution.bindingLookup().find(call.callId())
                .orElseThrow(() -> new AgentLoop.LoopFailure(
                        "TOOL_BINDING_UNAVAILABLE", "Tool binding is unavailable"));
        TurnExecutionPlan command = execution.command();
        Optional<GoalToolExecutionPort.ExecutionIdentity> identity = execution.goalTools()
                .executionIdentity(command.threadId(), command.turnId(), command.origin());
        return new AgentTool.ExecutionContext(command.threadId(), command.turnId(), command.workspaceRoot(),
                binding.accessMode(), command.model().configGeneration(), deadline(command), command.workspaceId(),
                identity.map(GoalToolExecutionPort.ExecutionIdentity::planRevisionId).orElse(null),
                identity.map(GoalToolExecutionPort.ExecutionIdentity::runId).orElse(null),
                identity.map(GoalToolExecutionPort.ExecutionIdentity::goalId).orElse(null), command.origin());
    }

    /** 从 Turn 请求时刻计算所有 Tool 与审批共享的固定 Deadline。 */
    private static Instant deadline(TurnExecutionPlan command) {
        return command.deadlineAt();
    }

    /** 选择两个时间中的较早者，审批绝不能扩大 Turn 生命周期。 */
    private static Instant minimum(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    /** 构造未执行调用的确定性失败结果。 */
    private static AgentTool.ToolResult failed(String code, String message) {
        return new AgentTool.ToolResult(ToolOutcome.FAILED, message, Optional.empty(), code);
    }

    /** 将公开 Outcome 映射为持久状态闭集。 */
    private static ToolState toolState(ToolOutcome outcome) {
        return switch (outcome) {
            case SUCCEEDED -> ToolState.SUCCEEDED;
            case FAILED -> ToolState.FAILED;
            case CANCELLED -> ToolState.CANCELLED;
        };
    }

    /** Tool 可能激活 Skill 或刷新规则，结果事务必须同步冻结最新 Prompt 身份。 */
    private static TurnExecutionState.Common promptCommon(
            TurnExecutionState.Common current, AgentPromptSession promptSession) {
        return new TurnExecutionState.Common(current.modelRound(), current.usedToolCalls(),
                current.nextProviderOrdinal(), current.promptCheckpointId(),
                promptSession.activeSkillReferences(), current.deadlineAt(), current.origin(),
                current.activeBudget());
    }

    /** 同步取得异步端口结果并保留运行时异常类型。 */
    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw failure;
        }
    }

    /** 冻结一次 Tool batch 所需的命令、目录、取消令牌和持久化回调。 */
    record Execution(TurnExecutionPlan command, Map<String, AgentTool> catalog,
                     CancellationToken cancellation, Supplier<TurnEvent.Context> draftContext,
                     Supplier<TurnExecutionState.Tools> cursor, DurableWriter writer,
                     ApprovalLookup approvalLookup, BindingLookup bindingLookup, Runnable refreshAuthority,
                     ResolvedPublisher resolvedPublisher,
                     io.github.kongweiguang.ja.conversation.domain.CollaborationMode collaborationMode,
                     GoalToolExecutionPort goalTools) {
        /** 保持测试与非 Provider 直接调用方的构造面；生产执行必须传入冻结的实际模式。 */
        Execution(TurnExecutionPlan command, Map<String, AgentTool> catalog,
                  CancellationToken cancellation, Supplier<TurnEvent.Context> draftContext,
                  Supplier<TurnExecutionState.Tools> cursor, DurableWriter writer,
                  ApprovalLookup approvalLookup, BindingLookup bindingLookup, Runnable refreshAuthority,
                  ResolvedPublisher resolvedPublisher) {
            this(command, catalog, cancellation, draftContext, cursor, writer, approvalLookup, bindingLookup,
                    refreshAuthority, resolvedPublisher,
                    io.github.kongweiguang.ja.conversation.domain.CollaborationMode.DEFAULT,
                    GoalToolExecutionPort.disabled());
        }

        /** 带模式的测试/适配器构造仍使用空 Goal ledger，避免隐藏 identity 查询改变旧夹具。 */
        Execution(TurnExecutionPlan command, Map<String, AgentTool> catalog,
                  CancellationToken cancellation, Supplier<TurnEvent.Context> draftContext,
                  Supplier<TurnExecutionState.Tools> cursor, DurableWriter writer,
                  ApprovalLookup approvalLookup, BindingLookup bindingLookup, Runnable refreshAuthority,
                  ResolvedPublisher resolvedPublisher,
                  io.github.kongweiguang.ja.conversation.domain.CollaborationMode collaborationMode) {
            this(command, catalog, cancellation, draftContext, cursor, writer, approvalLookup, bindingLookup,
                    refreshAuthority, resolvedPublisher, collaborationMode, GoalToolExecutionPort.disabled());
        }

        /** 复制 Tool 目录并校验回调，避免执行期间观察到注册变化。 */
        Execution {
            Objects.requireNonNull(command, "command");
            catalog = Map.copyOf(Objects.requireNonNull(catalog, "catalog"));
            Objects.requireNonNull(cancellation, "cancellation");
            Objects.requireNonNull(draftContext, "draftContext");
            Objects.requireNonNull(cursor, "cursor");
            Objects.requireNonNull(writer, "writer");
            Objects.requireNonNull(approvalLookup, "approvalLookup");
            Objects.requireNonNull(bindingLookup, "bindingLookup");
            Objects.requireNonNull(refreshAuthority, "refreshAuthority");
            Objects.requireNonNull(resolvedPublisher, "resolvedPublisher");
            Objects.requireNonNull(collaborationMode, "collaborationMode");
            Objects.requireNonNull(goalTools, "goalTools");
        }

        /** Runner 从同一个 Execution 读取 ledger，防止被全局可变字段替换。 */
        public GoalToolExecutionPort goalTools() { return goalTools; }
    }

    /** 原子提交 Tool 事实后再发布事件。 */
    @FunctionalInterface interface DurableWriter {
        /** 每次提交必须携带完整下一游标，事件只含本次单一 Tool。 */
        void commit(TurnState target, TurnEvent event, List<ConversationRepository.Fact> facts,
                    TurnExecutionState execution);
    }
    /** 恢复审批只按 callId 读取 SQLite 权威行。 */
    @FunctionalInterface interface ApprovalLookup {
        /** 返回原审批 ID、决定和 expiry；空值表示从未请求。 */
        Optional<ConversationRepository.PendingApproval> find(String callId);
    }
    /** Tool 执行与审批均从 SQLite 读取原 batch 权限和路由身份。 */
    @FunctionalInterface interface BindingLookup {
        /** 缺失绑定必须稳定失败，禁止用当前 Thread access mode 补写。 */
        Optional<ConversationRepository.ToolBinding> find(String callId);
    }
    /** 发布已经由 SQLite 决定事务提交的审批结果，不再制造第二个数据库 revision。 */
    @FunctionalInterface interface ResolvedPublisher {
        /** approvalId 与 decision 必须来自 Broker 的 durable resolution。 */
        void publish(String approvalId, ApprovalDecision decision);
    }

}
