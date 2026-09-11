// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.application.service.TurnService;
import io.github.kongweiguang.ja.conversation.application.interaction.InteractionSuspendedException;
import io.github.kongweiguang.ja.conversation.application.loop.AgentLoop;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.port.in.InternalTurnStartRequest;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnLimits;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanSnapshot;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanStatus;
import io.github.kongweiguang.ja.goal.port.in.PlanExecutionEventSink;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;
import io.github.kongweiguang.ja.task.application.TaskCoordinator;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 将一次 standalone Plan execution 接入唯一 TurnService，执行上下文不写 USER message 历史。 */
public final class PlanExecutionTurnAdapter implements PlanExecutionCoordinator.PlanExecutionTurnPort,
        PlanExecutionCoordinator.PlanExecutionBudgetPort {
    private static final int DEFAULT_TURN_BUDGET = 32;
    private final TurnService turns;
    private final ConversationRepository conversations;
    private final WorkspaceUseCase workspaces;
    private final GoalRepository plans;
    private final ObjectMapper json;
    private final Clock clock;
    private final TaskCoordinator tasks;
    private final TurnRuntimeResolver runtimeResolver;
    private final ConcurrentMap<String, CompletionStage<Void>> activeTurns = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletionStage<?>> activeTurnSources = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ResumeRegistration> resumeRegistrations = new ConcurrentHashMap<>();

    /** adapter 复用现有 Thread、Workspace 与连接路由 owner，不保存第二份 Plan 状态。 */
    public PlanExecutionTurnAdapter(TurnService turns, ConversationRepository conversations,
                                     WorkspaceUseCase workspaces, GoalRepository plans,
                                     ObjectMapper json, Clock clock, TaskCoordinator tasks) {
        this(turns, conversations, workspaces, plans, json, clock, tasks, null);
    }

    /**
     * 生产构造额外注入 RuntimeResolver，使 Plan 冻结的预算来自真实配置代际，而不是 UI 或常量。
     * 旧构造保留给不涉及执行的单元测试；缺少 resolver 的实例在真正解析预算时显式失败。
     */
    public PlanExecutionTurnAdapter(TurnService turns, ConversationRepository conversations,
                                     WorkspaceUseCase workspaces, GoalRepository plans,
                                     ObjectMapper json, Clock clock, TaskCoordinator tasks,
                                     TurnRuntimeResolver runtimeResolver) {
        this.turns = Objects.requireNonNull(turns, "turns");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.runtimeResolver = runtimeResolver;
    }

    /**
     * execute 前只解析配置预算；此时 Run/Turn 尚未准入，完整 RuntimeLease 会错误要求已经存在
     * 的 Plan binding。工具和 Prompt 仍由真正 Turn admission 后的完整解析建立。
     */
    @Override public PlanExecutionCoordinator.EffectiveBudget resolve(
            PlanExecutionCoordinator.ExecutionRequest request) {
        if (runtimeResolver == null) {
            throw new IllegalStateException("Plan execution runtime resolver is unavailable");
        }
        ConversationRepository.ThreadSnapshot thread = conversations.readThread(request.ownerThreadId())
                .orElseThrow(() -> new IllegalStateException("Plan owner Thread is unavailable"));
        Workspace workspace = workspaces.requireOpenWorkspace(thread.workspaceId());
        InternalTurnStartRequest command = InternalTurnRequests.create(
                thread, workspace, request.turnId(), clock.instant(), TurnOrigin.PLAN_EXECUTION);
        var limits = runtimeResolver.resolveLimits(new io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeRequest(
                command.threadId(), command.turnId(), command.workspaceRoot(), command.workspaceId(),
                command.providerId(), command.modelId(), command.reasoningLevel(), command.accessMode(),
                command.collaborationMode(), command.origin(), command.deadline(), command.requestedAt()));
        return new PlanExecutionCoordinator.EffectiveBudget(limits.maxModelRounds(), limits.maxToolCalls(),
                limits.wallTimeout().toMillis(), DEFAULT_TURN_BUDGET);
    }

    /**
     * 无 claim 的内部入口也必须先经过持久 Run 预算 admission；保留该入口只是为了
     * 让端口在恢复/测试场景保持可调用，不能让它成为绕过 plan_turn_claims 的后门。
     */
    @Override public CompletionStage<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                 PlanExecutionEventSink events) {
        return claimAndStart(request, events);
    }

    /**
     * 把 Plan coordinator 的恢复回调绑定到 TurnService 的唯一 Interaction resume owner。
     * 同一 turn 的重复注册只更新事件出口和 continuation，不再次向 TurnService 注册，
     * 这样手动 Resume 与自动回答竞争时仍只有一个可消费的恢复回调。
     */
    @Override public void registerResumeContinuation(PlanExecutionCoordinator.ExecutionRequest request,
                                                     PlanExecutionEventSink events,
                                                     Consumer<CompletionStage<?>> continuation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(continuation, "continuation");
        ResumeRegistration candidate = new ResumeRegistration(request, continuation);
        ResumeRegistration existing = resumeRegistrations.putIfAbsent(request.turnId(), candidate);
        if (existing != null) {
            existing.replace(request, continuation);
            return;
        }
        try {
            turns.registerResumeContinuation(request.turnId(), raw -> {
                resumeRegistrations.remove(request.turnId(), candidate);
                candidate.deliver(raw);
            });
        } catch (RuntimeException failure) {
            resumeRegistrations.remove(request.turnId(), candidate);
            throw failure;
        }
    }

    /** admission 失败时同时清理本地注册和 TurnService 中尚未消费的 Plan continuation。 */
    @Override public void clearResumeContinuation(PlanExecutionCoordinator.ExecutionRequest request) {
        resumeRegistrations.remove(request.turnId());
        turns.clearResumeContinuation(request.turnId());
    }

    /** 恢复 completion 已在注册回调中统一 tracking；coordinator 不得再次结算或推进同一 Run。 */
    @Override public boolean resumeCompletionIsTrackedByContinuation() {
        return true;
    }

    /**
     * 为没有持久 binding 的恢复创建同一 Run 的新 Turn claim；claim 为空表示另一执行者、暂停
     * fence 或预算门已经获胜，调用方必须保持幂等成功而不能直接启动未经冻结预算的 Turn。
     */
    private CompletionStage<Void> claimAndStart(PlanExecutionCoordinator.ExecutionRequest request,
                                                 PlanExecutionEventSink events) {
        PlanSnapshot snapshot = plans.readPlanSnapshot(request.planId());
        var plan = snapshot.plan();
        if (plan.status() != PlanStatus.EXECUTING
                || !Objects.equals(plan.activePlanRevisionId(), request.planRevisionId())
                || !Objects.equals(plan.activeRunId(), request.runId())) {
            return CompletableFuture.completedFuture(null);
        }
        Instant at = clock.instant();
        GoalRepository.ClaimPlanTurn command = new GoalRepository.ClaimPlanTurn(
                request.planId(), plan.revision(), request.runId(), request.planRevisionId(), request.turnId(),
                id("evt_"), "plan-turn-claim:" + request.turnId(), at);
        return plans.claimPlanTurn(command)
                .map(claim -> start(request, claim, events))
                .orElseGet(() -> {
                    clearResumeContinuation(request);
                    return CompletableFuture.completedFuture(null);
                });
    }

    /**
     * admission 使用 SQLite 返回的 Run 剩余预算收紧当前 Turn；ceiling 只限制模型轮次、Tool 次数和墙钟，
     * Provider 能力、token 上限及权限仍由 RuntimeLease owner 决定，避免跨 Turn 预算绕过。
     */
    @Override public CompletionStage<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                 GoalRepository.PlanTurnClaim claim,
                                                 PlanExecutionEventSink events) {
        PlanSnapshot plan = plans.readPlanSnapshot(request.planId());
        if (plan.plan().status() != PlanStatus.EXECUTING
                || !Objects.equals(plan.plan().activePlanRevisionId(), request.planRevisionId())
                || !Objects.equals(plan.plan().activeRunId(), request.runId())) {
            clearResumeContinuation(request);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        ConversationRepository.ThreadSnapshot thread = conversations.readThread(request.ownerThreadId())
                .orElseThrow(() -> new IllegalStateException("Plan owner Thread is unavailable"));
        Workspace workspace = workspaces.requireOpenWorkspace(thread.workspaceId());
        InternalTurnStartRequest command = InternalTurnRequests.create(
                thread, workspace, request.turnId(), clock.instant(), TurnOrigin.PLAN_EXECUTION);
        boolean registered = events.registerTurn(request.turnId(), workspace.workspaceId(),
                thread.threadId(), thread.revision());
        try {
            var accepted = turns.startContinuation(command, hiddenContext(plan),
                    tasks.projectContinuationEvents(thread.threadId(), registered
                            ? events::publish : io.github.kongweiguang.ja.conversation.port.in.TurnEventSink.noop()),
                    ceiling(claim));
            CompletionStage<Void> completion = trackCompletion(request, accepted.completion(), clock.instant(),
                    "start");
            completion.whenComplete((ignored, failure) -> {
                if (registered) events.abandonTurn(request.turnId());
            });
            return completion;
        } catch (RuntimeException failure) {
            clearResumeContinuation(request);
            if (registered) events.abandonTurn(request.turnId());
            // start 的异步契约必须把同步 admission 异常也归一为失败 stage，调用方才能统一结算 Plan run。
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Plan pause 使用 TurnService 的可恢复控制入口；它会等待当前调用结算并保留 cursor，
     * 因而 Plan CAS 不会先于实际安全暂停对外可见。
     */
    @Override public CompletionStage<Void> pause(PlanExecutionCoordinator.ExecutionRequest request) {
        ConversationRepository.ThreadSnapshot thread = conversations.readThread(request.ownerThreadId())
                .orElseThrow(() -> new IllegalStateException("Plan owner Thread is unavailable"));
        return turns.suspendPlanRun(request.turnId(), thread.revision());
    }

    /** 停止真实 Turn；SUSPENDED 也必须进入取消终态，不保留可恢复的交互请求。 */
    @Override public CompletionStage<Void> stop(PlanExecutionCoordinator.ExecutionRequest request) {
        return cancel(request, true);
    }

    /** 优先恢复持久 SUSPENDED 游标；上一 Turn 已终态时才 admission 同一 run 的新 Turn。 */
    @Override public CompletionStage<Void> resume(PlanExecutionCoordinator.ExecutionRequest request,
                                                  PlanExecutionEventSink events) {
        if (plans.findPlanTurnBinding(request.planId(), request.runId()).isEmpty()) {
            return claimAndStart(request, events);
        }
        ConversationRepository.ThreadSnapshot thread = conversations.readThread(request.ownerThreadId())
                .orElseThrow(() -> new IllegalStateException("Plan owner Thread is unavailable"));
        Workspace workspace = workspaces.requireOpenWorkspace(thread.workspaceId());
        boolean registered = events.registerTurn(request.turnId(), workspace.workspaceId(),
                thread.threadId(), thread.revision());
        try {
            var accepted = turns.resume(request.turnId(), thread.revision(),
                    tasks.projectContinuationEvents(thread.threadId(), registered
                            ? events::publish : io.github.kongweiguang.ja.conversation.port.in.TurnEventSink.noop()));
            // TurnService.resume 会在 admission 内把原始 completion 交给已注册 continuation；
            // adapter 不再创建第二个 tracked wrapper，避免手动 Resume 与自动回答重复结算。
            CompletionStage<Void> completion = accepted.completion().thenApply(ignored -> null);
            completion.whenComplete((ignored, failure) -> {
                if (registered) events.abandonTurn(request.turnId());
            });
            return completion;
        } catch (RuntimeException failure) {
            // TurnService 在找到候选或版本校验失败时会同步拒绝；此时没有 raw completion
            // 可以消费旧注册，必须先清理 continuation，避免迟到答案再次恢复已拒绝的 Turn。
            clearResumeContinuation(request);
            if (registered) events.abandonTurn(request.turnId());
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** pause 保留 SUSPENDED 游标，stop 则将其取消；运行态取消后共享 Turn completion barrier。 */
    private CompletionStage<Void> cancel(PlanExecutionCoordinator.ExecutionRequest request, boolean stop) {
        ConversationRepository.ThreadSnapshot thread = conversations.readThread(request.ownerThreadId())
                .orElseThrow(() -> new IllegalStateException("Plan owner Thread is unavailable"));
        ConversationRepository.TurnSnapshot turn = thread.turns().stream()
                .filter(candidate -> candidate.turnId().equals(request.turnId())).findFirst().orElse(null);
        if (turn == null || turn.state().terminal()) return CompletableFuture.completedFuture(null);
        if (!stop && turn.state() == io.github.kongweiguang.ja.conversation.domain.turn.TurnState.SUSPENDED) {
            return CompletableFuture.completedFuture(null);
        }
        for (int attempt = 0; ; attempt++) {
            try {
                turns.cancel(request.turnId(), thread.revision());
                break;
            } catch (io.github.kongweiguang.ja.conversation.port.in.TurnUseCase.TurnCancellationException conflict) {
                if (conflict.failure() != io.github.kongweiguang.ja.conversation.port.in.TurnUseCase.CancelFailure.CONFLICT
                        || attempt >= 2) throw conflict;
                // Plan 已写 pause fence；只重读同一 Turn 的提交水位，不允许工具结算竞态撤销用户停止意图。
                thread = conversations.readThread(request.ownerThreadId()).orElseThrow();
                var current = thread.turns().stream().filter(value -> value.turnId().equals(request.turnId())).findFirst();
                if (current.isEmpty() || current.get().state().terminal()) return CompletableFuture.completedFuture(null);
            }
        }
        CompletionStage<Void> completion = activeTurns.get(request.turnId());
        return completion == null ? CompletableFuture.completedFuture(null) : completion;
    }

    /**
     * 以原始 Turn completion 作为幂等身份建立唯一 tracking stage；同一 turn 的旧暂停 stage
     * 即使尚未完成清理，也不能遮住新 Resume 产生的 completion，避免恢复后续跑被旧异常短路。
     */
    private CompletionStage<Void> trackCompletion(PlanExecutionCoordinator.ExecutionRequest request,
                                                   CompletionStage<?> accepted, Instant startedAt,
                                                   String segment) {
        Objects.requireNonNull(accepted, "accepted");
        synchronized (activeTurns) {
            CompletionStage<?> source = activeTurnSources.get(request.turnId());
            CompletionStage<Void> existing = activeTurns.get(request.turnId());
            if (source == accepted && existing != null) return existing;
            CompletionStage<Void> tracked = trackedCompletion(request, accepted, startedAt, segment);
            activeTurnSources.put(request.turnId(), accepted);
            activeTurns.put(request.turnId(), tracked);
            tracked.whenComplete((ignored, failure) -> {
                synchronized (activeTurns) {
                    activeTurns.remove(request.turnId(), tracked);
                    activeTurnSources.remove(request.turnId(), accepted);
                }
                if (!isSuspended(failure)) resumeRegistrations.remove(request.turnId());
            });
            return tracked;
        }
    }

    /**
     * 恢复回调发生在 TurnService 的 admission 临界区内，使用当前 mutation version 生成活动片段
     * 标识；读取失败时降级为稳定的 resume 段名，实际预算结算仍由同一幂等账本保护。
     */
    private String resumeSegment(PlanExecutionCoordinator.ExecutionRequest request) {
        return conversations.readThread(request.ownerThreadId())
                .map(thread -> activitySegment(request, thread)).orElse("resume");
    }

    /**
     * 将一次真实 Turn 的 usage 和活动墙钟在 completion 前结算；等待用户输入不结算，恢复后仍沿用同一
     * Turn identity。这样 coordinator 观察到完成时，Run ledger 已经包含本 Turn 的真实消耗。
     */
    private CompletionStage<Void> trackedCompletion(PlanExecutionCoordinator.ExecutionRequest request,
                                                     CompletionStage<?> accepted, java.time.Instant startedAt,
                                                     String segment) {
        return accepted.handle((ignored, failure) -> {
            if (isSuspended(failure)) {
                plans.settlePlanTurnActivity(new GoalRepository.SettlePlanTurnActivity(
                        request.planId(), request.runId(), request.turnId(),
                        activeMillis(startedAt), "evt_plan_turn_activity_" + request.turnId() + "_" + segment,
                        "plan-turn-activity:" + request.turnId() + ":" + segment, clock.instant()));
            } else {
                long activeMillis = Math.max(0L, java.time.Duration.between(startedAt, clock.instant()).toMillis());
                plans.settlePlanTurn(new GoalRepository.SettlePlanTurn(request.planId(), request.runId(),
                        request.turnId(), activeMillis, "evt_plan_turn_settle_" + request.turnId(),
                        "plan-turn-settle:" + request.turnId(), clock.instant()));
            }
            if (failure != null) throw new java.util.concurrent.CompletionException(failure);
            return null;
        });
    }

    /** 以恢复前 Turn mutation version 标识活动片段，进程重启后重试仍命中同一幂等键。 */
    private static String activitySegment(PlanExecutionCoordinator.ExecutionRequest request,
                                          ConversationRepository.ThreadSnapshot thread) {
        return thread.turns().stream().filter(turn -> turn.turnId().equals(request.turnId()))
                .findFirst().map(turn -> "mutation-" + turn.turnMutationVersion())
                .orElse("resume");
    }

    /** 墙钟读取集中在这里，确保所有暂停路径都不因负时钟漂移污染累计账本。 */
    private long activeMillis(java.time.Instant startedAt) {
        return Math.max(0L, java.time.Duration.between(startedAt, clock.instant()).toMillis());
    }

    /** 交互暂停是可恢复控制流，不应写入 Plan 预算或把 Turn 关闭为失败。 */
    private static boolean isSuspended(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof InteractionSuspendedException
                    || current instanceof AgentLoop.InputNeedsAttentionException
                    || current instanceof TurnService.PlanSuspendedException) return true;
            current = current.getCause();
        }
        return false;
    }

    /** 单个 Turn 的 continuation 注册记录；更新出口不改变底层 TurnService 的单消费约束。 */
    private final class ResumeRegistration {
        private final PlanExecutionCoordinator.ExecutionRequest request;
        private final AtomicBoolean delivered = new AtomicBoolean();
        private volatile Consumer<CompletionStage<?>> continuation;

        /** 创建绑定当前 Plan/run/Turn identity 的恢复注册记录。 */
        private ResumeRegistration(PlanExecutionCoordinator.ExecutionRequest request,
                                   Consumer<CompletionStage<?>> continuation) {
            this.request = Objects.requireNonNull(request, "request");
            this.continuation = Objects.requireNonNull(continuation, "continuation");
        }

        /** 更新当前连接的 continuation，但拒绝把另一个 Plan/run 路由到已有 Turn。 */
        private void replace(PlanExecutionCoordinator.ExecutionRequest next,
                             Consumer<CompletionStage<?>> continuation) {
            Objects.requireNonNull(next, "request");
            Objects.requireNonNull(continuation, "continuation");
            if (!Objects.equals(request.planId(), next.planId())
                    || !Objects.equals(request.ownerThreadId(), next.ownerThreadId())
                    || !Objects.equals(request.planRevisionId(), next.planRevisionId())
                    || !Objects.equals(request.runId(), next.runId())
                    || !Objects.equals(request.turnId(), next.turnId())) {
                throw new IllegalStateException("Plan resume continuation identity conflicts");
            }
            if (!delivered.get()) this.continuation = continuation;
        }

        /** 将 raw completion 统一包装为 tracking stage；回调失败先清理本地 tracking 再交回 admission。 */
        private void deliver(CompletionStage<?> raw) {
            if (!delivered.compareAndSet(false, true)) return;
            CompletionStage<Void> tracked = trackCompletion(request, raw, clock.instant(), resumeSegment(request));
            try {
                continuation.accept(tracked);
            } catch (RuntimeException failure) {
                // TurnService.resume 正处于 admission 临界区，回调异常不能让已恢复 Turn 被误结算为失败。
                activeTurns.remove(request.turnId(), tracked);
                activeTurnSources.remove(request.turnId(), raw);
                throw failure;
            }
        }
    }

    /** 把 Run remainder 映射为 TurnService 可验证的单 Turn ceiling，处理旧无冻结测试数据的无穷哨兵。 */
    private static TurnLimits ceiling(GoalRepository.PlanTurnClaim claim) {
        if (claim == null) return null;
        int rounds = Math.min(128, Math.max(1, claim.remainingModelRounds()));
        int tools = Math.min(1_024, Math.max(0, claim.remainingToolCalls()));
        long wall = Math.min(24L * 60 * 60 * 1_000, Math.max(1L, claim.remainingWallBudgetMillis()));
        return new TurnLimits(rounds, tools, 4_000_000, 1_000_000,
                java.time.Duration.ofMillis(wall));
    }

    /** 隐藏上下文仅携带冻结 Plan 与 Run identity，不复制对话历史，避免旧消息改变已批准执行语义。 */
    private String hiddenContext(PlanSnapshot snapshot) {
        try {
            ObjectNode root = json.createObjectNode();
            root.put("kind", "PLAN_EXECUTION");
            root.put("planId", snapshot.plan().planId());
            root.put("planRevisionId", snapshot.plan().activePlanRevisionId());
            root.put("planHash", snapshot.currentRevision().planHash());
            root.put("runId", snapshot.plan().activeRunId());
            root.set("plan", json.readTree(snapshot.currentRevision().canonicalJson()));
            return json.writeValueAsString(root);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Plan execution context cannot be encoded", failure);
        }
    }

    /** 生成只用于 SQLite 事件关联的不可预测 identity，不承载执行顺序或授权语义。 */
    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
