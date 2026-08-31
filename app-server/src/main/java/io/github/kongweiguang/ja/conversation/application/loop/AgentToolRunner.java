// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.application.approval.ApprovalBroker;
import io.github.kongweiguang.ja.conversation.application.middleware.AgentMiddleware;
import io.github.kongweiguang.ja.conversation.application.middleware.MiddlewareChain;
import io.github.kongweiguang.ja.conversation.application.presentation.ToolPresentationProjector;
import io.github.kongweiguang.ja.conversation.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.conversation.domain.model.ModelContent;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.conversation.domain.permission.PermissionAction;
import io.github.kongweiguang.ja.conversation.domain.permission.PermissionRequest;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSession;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
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
    private final MiddlewareChain middleware;
    private volatile boolean closed;

    /** 仅保留内部审批 Broker；权限模式直接决定是否逐次请求，不再查询 Tool Policy Registry。 */
    AgentToolRunner(ApprovalBroker approvalBroker, Clock clock, MiddlewareChain middleware) {
        this.approvalBroker = Objects.requireNonNull(approvalBroker, "approvalBroker");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.middleware = Objects.requireNonNull(middleware, "middleware");
    }

    /**
     * 整批预留预算后逐个执行，后一个调用永远观察到前一个调用已完成的真实文件系统状态。
     */
    List<AgentTool.ToolResult> execute(Execution execution, List<AgentTool.Invocation> calls) {
        Objects.requireNonNull(execution, "execution");
        calls = List.copyOf(Objects.requireNonNull(calls, "calls"));
        execution.toolBudget().reserve(calls.size());
        List<AgentTool.ToolResult> results = new ArrayList<>(calls.size());
        boolean[] started = new boolean[calls.size()];
        long[] durations = new long[calls.size()];
        for (int index = 0; index < calls.size(); index++) {
            AgentTool.Invocation call = calls.get(index);
            AgentTool tool = execution.catalog().get(call.toolName());
            AgentTool.ToolResult result;
            if (tool == null) {
                result = failed("TOOL_NOT_FOUND", "Tool is unavailable");
            } else {
                AgentPromptSession.ToolGuard promptGuard = execution.command().promptSession().beforeTool(
                        call, sideEffect(tool), execution.promptRevision());
                if (!promptGuard.proceed()) {
                    result = failed(promptGuard.code(), promptGuard.message());
                } else {
                    AgentMiddleware.ToolContext context = new AgentMiddleware.ToolContext(call,
                            executionContext(execution.command()), () -> awaitApproval(execution, call));
                    AgentMiddleware.ToolDecision decision = middleware.beforeTool(context);
                    if (!decision.proceed()) {
                        result = failed(decision.code(), decision.message());
                    } else {
                        started[index] = true;
                        long startedAt = System.nanoTime();
                        result = executeOne(execution, call, tool);
                        durations[index] = Math.max(0L,
                                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                        execution.command().promptSession().afterTool(call, result);
                        middleware.afterTool(context, result);
                    }
                }
            }
            results.add(result);
        }
        publishResults(execution, calls, results, started, durations);
        return List.copyOf(results);
    }

    /** 关闭只阻止后续 Tool 准入；当前调用由 Turn CancellationToken 负责清理。 */
    @Override
    public void close() {
        closed = true;
    }

    /** 绝对截止线不再管理内部线程池，方法仅保持 AgentLoop 生命周期调用边界。 */
    void closeAt(long shutdownDeadlineNanos) {
        closed = true;
    }

    /** 在 Tool 前登记一次审批并持久化 WAITING_APPROVAL/恢复状态；拒绝作为普通 ToolResult 返回。 */
    private boolean awaitApproval(Execution execution, AgentTool.Invocation call) {
        Instant expiresAt = minimum(clock.instant().plus(Duration.ofMinutes(5)), deadline(execution.command()));
        PermissionRequest permission = permission(execution.command(), call);
        ApprovalBroker.ApprovalRequest approval = new ApprovalBroker.ApprovalRequest(
                "appr_" + UUID.randomUUID(), permission, "Tool requires approval", expiresAt);
        var prepared = ToolPresentationProjector.prepared(call, execution.command().workspaceRoot(),
                execution.command().presentationSecrets());
        execution.approvalWriter().commit(TurnState.WAITING_APPROVAL,
                new TurnEvent.ApprovalRequested(execution.draftContext().get(), approval.approvalId(),
                        call.callId(), call.toolName(), approval.reason(), expiresAt),
                List.of(new ConversationRepository.ApprovalFact(
                        approval.approvalId(), call.callId(), null, expiresAt,
                        ToolPresentationProjector.withStatus(
                                prepared, io.github.kongweiguang.ja.conversation.domain.ToolPresentation.Status.WAITING_APPROVAL))));
        ApprovalBroker.Resolution resolution = await(approvalBroker.request(approval, execution.cancellation()));
        if (!approval.approvalId().equals(resolution.approvalId()) || resolution.resolvedAt().isAfter(expiresAt)) {
            throw new AgentLoop.LoopFailure("APPROVAL_EXPIRED", "approval response is stale");
        }
        execution.approvalWriter().commit(TurnState.RUNNING,
                new TurnEvent.ApprovalResolved(execution.draftContext().get(),
                        resolution.approvalId(), resolution.response()),
                List.of(new ConversationRepository.ApprovalFact(
                        approval.approvalId(), call.callId(), resolution.response(), expiresAt,
                        ToolPresentationProjector.withStatus(
                                prepared, io.github.kongweiguang.ja.conversation.domain.ToolPresentation.Status.RUNNING))));
        return resolution.response() == ApprovalDecision.APPROVE;
    }

    /** 在同一 Turn 线程直接执行一次 Tool，异常只转换为该调用的结构化结果。 */
    private AgentTool.ToolResult executeOne(Execution execution, AgentTool.Invocation call, AgentTool tool) {
        if (closed) throw new CancellationException("Agent Tool runner is closing");
        try {
            execution.cancellation().throwIfCancellationRequested();
            return await(tool.execute(call, executionContext(execution.command()), execution.cancellation()));
        } catch (CancellationException cancelled) {
            return new AgentTool.ToolResult(ToolOutcome.CANCELLED, "", Optional.empty(), "CANCELLED");
        } catch (RuntimeException failure) {
            return new AgentTool.ToolResult(ToolOutcome.FAILED, "", Optional.empty(),
                    sideEffect(tool) == ToolSideEffect.EXTERNAL
                            ? "TOOL_EXECUTION_UNCONFIRMED" : "TOOL_FAILED");
        }
    }

    /** 按原调用顺序一次提交 Started/Result 事实和 Tool Message。 */
    private static void publishResults(Execution execution, List<AgentTool.Invocation> calls,
                                       List<AgentTool.ToolResult> results, boolean[] started, long[] durations) {
        List<TurnEvent.ToolBatchResult> projections = new ArrayList<>();
        List<ConversationRepository.Fact> facts = new ArrayList<>();
        List<ModelContent> resultBlocks = new ArrayList<>();
        for (int index = 0; index < calls.size(); index++) {
            AgentTool.Invocation call = calls.get(index);
            AgentTool.ToolResult result = results.get(index);
            ToolPresentationProjector.Completed completed = ToolPresentationProjector.completed(
                    call, result, execution.command().workspaceRoot(), execution.command().presentationSecrets(),
                    durations[index]);
            if (started[index]) facts.add(new ConversationRepository.ToolStartedFact(call.callId()));
            facts.add(new ConversationRepository.ToolResultFact(call.callId(), toolState(result.outcome()),
                    completed.artifactContent(), result.outcome() != ToolOutcome.SUCCEEDED,
                    completed.presentation(), completed.artifactContent()));
            projections.add(new TurnEvent.ToolBatchResult(call.callId(), result.outcome(), completed.presentation(),
                    call.ordinal(), result.errorCode()));
            resultBlocks.add(new ToolResultContent(call.callId(), completed.artifactContent(),
                    result.outcome() != ToolOutcome.SUCCEEDED));
        }
        facts.add(new ConversationRepository.ToolResultMessageFact("item_" + UUID.randomUUID(),
                new ModelMessage(ModelRole.TOOL, resultBlocks)));
        execution.writer().commit(new TurnEvent.ToolBatchCommitted(execution.draftContext().get(),
                projections), facts);
    }

    /** 构造审批审计事实；资源列表已删除，Workspace 仅作为默认 cwd 展示。 */
    private static PermissionRequest permission(TurnExecutionPlan command, AgentTool.Invocation call) {
        return new PermissionRequest(command.threadId(), command.turnId(), command.model().configGeneration(),
                command.accessMode(), actionKind(call.toolName()), call.toolName(), command.workspaceRoot(),
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

    /** Tool 执行上下文只携带冻结 Turn 身份、cwd、权限模式与 Deadline。 */
    private static AgentTool.ExecutionContext executionContext(TurnExecutionPlan command) {
        return new AgentTool.ExecutionContext(command.threadId(), command.turnId(), command.workspaceRoot(),
                command.accessMode(), command.model().configGeneration(), deadline(command), command.workspaceId());
    }

    /** 所有非 read Tool 都按外部副作用处理，失败后禁止框架级静默重试。 */
    static ToolSideEffect sideEffect(AgentTool tool) {
        return tool != null && ("read".equals(tool.spec().name())
                || "read_attachment".equals(tool.spec().name()))
                ? ToolSideEffect.READ_ONLY : ToolSideEffect.EXTERNAL;
    }

    /** 从 Turn 请求时刻计算所有 Tool 与审批共享的固定 Deadline。 */
    private static Instant deadline(TurnExecutionPlan command) {
        return command.requestedAt().plus(command.limits().wallTimeout());
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
    record Execution(TurnExecutionPlan command, Map<String, AgentTool> catalog, String promptRevision,
                     CancellationToken cancellation, Supplier<TurnEvent.Context> draftContext,
                     ToolBudget toolBudget, DurableWriter writer, ApprovalWriter approvalWriter) {
        /** 复制 Tool 目录并校验回调，避免执行期间观察到注册变化。 */
        Execution {
            Objects.requireNonNull(command, "command");
            catalog = Map.copyOf(Objects.requireNonNull(catalog, "catalog"));
            Objects.requireNonNull(promptRevision, "promptRevision");
            Objects.requireNonNull(cancellation, "cancellation");
            Objects.requireNonNull(draftContext, "draftContext");
            Objects.requireNonNull(toolBudget, "toolBudget");
            Objects.requireNonNull(writer, "writer");
            Objects.requireNonNull(approvalWriter, "approvalWriter");
        }
    }

    /** 预留整个 batch 的 Tool 调用预算。 */
    @FunctionalInterface interface ToolBudget {
        /** 在任何 Tool 开始前原子预留整批调用数。 */
        void reserve(int count);
    }
    /** 原子提交 Tool 事实后再发布事件。 */
    @FunctionalInterface interface DurableWriter {
        /** 先提交全部 Tool 事实，再发布绑定 revision 的事件。 */
        void commit(TurnEvent event, List<ConversationRepository.Fact> facts);
    }
    /** 原子提交审批状态、事件与事实。 */
    @FunctionalInterface interface ApprovalWriter {
        /** 在一个事务内提交审批状态边和事实。 */
        void commit(TurnState target, TurnEvent event, List<ConversationRepository.Fact> facts);
    }
}
