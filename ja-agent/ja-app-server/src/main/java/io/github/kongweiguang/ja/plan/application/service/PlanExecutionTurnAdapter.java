// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.domain.model.TurnResult;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanSnapshot;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanStatus;
import io.github.kongweiguang.ja.plan.application.api.PlanExecutionEventSink;
import io.github.kongweiguang.ja.plan.application.gateway.PlanConversationPort;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;
import io.github.kongweiguang.ja.plan.application.gateway.PlanTaskPort;
import io.github.kongweiguang.ja.plan.application.gateway.PlanTurnPort;
import io.github.kongweiguang.ja.plan.application.gateway.PlanWorkspacePort;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * 将一次 standalone Plan execution 接入唯一 TurnService，执行上下文不写 USER message 历史。
 */
public final class PlanExecutionTurnAdapter implements PlanExecutionCoordinator.PlanExecutionTurnPort {
    private final PlanTurnPort turns;
    private final PlanConversationPort conversations;
    private final PlanWorkspacePort workspaces;
    private final PlanRepository plans;
    private final ObjectMapper json;
    private final Clock clock;
    private final PlanTaskPort tasks;
    private final ConcurrentMap<String, ActiveTurn> activeTurns = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ResumeRegistration> resumeRegistrations = new ConcurrentHashMap<>();
    /**
     * 保护 Turn admission、原始 completion 与结算 barrier 的复合身份更新，不把 ConcurrentHashMap 作为锁对象。
     */
    private final Object activeTurnLock = new Object();

    /**
     * adapter 复用现有 Thread、Workspace 与连接路由 owner，不保存第二份 Plan 状态。
     */
    public PlanExecutionTurnAdapter(PlanTurnPort turns, PlanConversationPort conversations,
                                    PlanWorkspacePort workspaces, PlanRepository plans,
                                    ObjectMapper json, Clock clock, PlanTaskPort tasks) {
        this.turns = Objects.requireNonNull(turns, "turns");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    /**
     * 生成只用于 SQLite 事件关联的不可预测 identity，不承载执行顺序或授权语义。
     */
    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 无 claim 的内部入口同样先领取持久 Run identity，防止恢复时重复派发同一 Turn。
     */
    @Override
    public CompletionStage<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                       PlanExecutionEventSink events) {
        return claimAndStart(request, events);
    }

    /**
     * 把 Plan coordinator 的恢复回调绑定到 TurnService 的唯一 Interaction resume owner。
     * 同一 turn 的重复注册只更新事件出口和 continuation，不再次向 TurnService 注册，
     * 这样手动 Resume 与自动回答竞争时仍只有一个可消费的恢复回调。
     */
    @Override
    public void registerResumeContinuation(PlanExecutionCoordinator.ExecutionRequest request,
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

    /**
     * admission 失败时同时清理本地注册和 TurnService 中尚未消费的 Plan continuation。
     */
    @Override
    public void clearResumeContinuation(PlanExecutionCoordinator.ExecutionRequest request) {
        resumeRegistrations.remove(request.turnId());
        turns.clearResumeContinuation(request.turnId());
    }

    /**
     * 恢复 completion 已在注册回调中统一 tracking；coordinator 不得再次结算或推进同一 Run。
     */
    @Override
    public boolean resumeCompletionIsTrackedByContinuation() {
        return true;
    }

    /**
     * 为没有持久 binding 的恢复创建同一 Run 的新 Turn claim；claim 为空表示另一执行者或暂停
     * fence 已获胜，调用方必须保持幂等成功而不能绕过持久 admission。
     */
    private CompletionStage<Void> claimAndStart(PlanExecutionCoordinator.ExecutionRequest request,
                                                PlanExecutionEventSink events) {
        PlanSnapshot snapshot = plans.readPlanSnapshot(request.planId());
        PlanModels.Plan plan = snapshot.plan();
        if (plan.status() != PlanStatus.EXECUTING
            || !Objects.equals(plan.activePlanRevisionId(), request.planRevisionId())
            || !Objects.equals(plan.activeRunId(), request.runId())) {
            return CompletableFuture.completedFuture(null);
        }
        Instant at = clock.instant();
        PlanRepository.ClaimPlanTurn command = new PlanRepository.ClaimPlanTurn(
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
     * claim 再校验成功时立即预留 barrier，使 Stop fence 与真实 Turn admission 有单一先后顺序。
     */
    @Override
    public CompletionStage<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                       PlanRepository.PlanTurnClaim claim,
                                       PlanExecutionEventSink events) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(events, "events");
        Optional<TurnAdmission> admission;
        try {
            admission = confirmClaimAndReserveAdmission(request, Optional.of(claim));
        } catch (RuntimeException failure) {
            clearResumeContinuation(request);
            return CompletableFuture.failedFuture(failure);
        }
        if (admission.isEmpty()) {
            clearResumeContinuation(request);
            return CompletableFuture.completedFuture(null);
        }
        TurnAdmission reservation = admission.get();
        if (!reservation.owner()) {
            return automaticProgression(reservation.completion());
        }
        CompletableFuture<TurnResult> admissionBarrier = reservation.completion();
        AtomicBoolean registered = new AtomicBoolean();
        try {
            PlanSnapshot plan = reservation.snapshot();
            return registerAndTrackTurn(request, events, registered, admissionBarrier, false,
                    (thread, workspace) -> {
                PlanTurnPort.StartRequest command = PlanTurnPort.StartRequest.planExecution(
                        thread, workspace, request.turnId(), clock.instant(), request.planRevisionId(), request.runId());
                return turns.startContinuation(command, hiddenContext(plan),
                        projectedEvents(thread.threadId(), registered.get(), events),
                        turns.runContext(request.planId(), request.runId()));
            });
        } catch (RuntimeException failure) {
            clearResumeContinuation(request);
            rejectAdmission(request, admissionBarrier, failure);
            if (registered.get()) {
                events.abandonTurn(request.turnId());
            }
            // start 的异步契约必须把同步 admission 异常也归一为失败 stage，调用方才能统一结算 Plan run。
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Plan pause 使用 TurnService 的可恢复控制入口；它会等待当前调用结算并保留 cursor，
     * 因而 Plan CAS 不会先于实际安全暂停对外可见。
     */
    @Override
    public CompletionStage<Void> pause(PlanExecutionCoordinator.ExecutionRequest request) {
        return turns.suspendPlanRun(request.turnId());
    }

    /**
     * 等待真实 Turn 终态及 Plan claim 事务结算后才允许 coordinator 提交 STOPPED。
     */
    @Override
    public CompletionStage<Void> stop(PlanExecutionCoordinator.ExecutionRequest request) {
        return cancelForStop(request);
    }

    /**
     * 优先恢复持久 SUSPENDED 游标；恢复前重验 claim 并预留 barrier，避免 Stop 越过迟到恢复。
     */
    @Override
    public CompletionStage<Void> resume(PlanExecutionCoordinator.ExecutionRequest request,
                                        PlanExecutionEventSink events) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(events, "events");
        Optional<PlanRepository.InternalTurnBinding> binding = plans.findPlanTurnBinding(
                request.planId(), request.runId());
        if (binding.isEmpty()) {
            return claimAndStart(request, events);
        }
        PlanRepository.InternalTurnBinding current = binding.get();
        if (!Objects.equals(current.turnId(), request.turnId())
            || !Objects.equals(current.planId(), request.planId())
            || !Objects.equals(current.runId(), request.runId())
            || !Objects.equals(current.planRevisionId(), request.planRevisionId())) {
            return CompletableFuture.failedFuture(new IllegalStateException("Plan resume binding identity conflicts"));
        }
        Optional<TurnAdmission> admission;
        try {
            admission = confirmClaimAndReserveAdmission(request, Optional.empty());
        } catch (RuntimeException failure) {
            clearResumeContinuation(request);
            return CompletableFuture.failedFuture(failure);
        }
        if (admission.isEmpty()) {
            clearResumeContinuation(request);
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<TurnResult> admissionBarrier = admission.get().completion();
        AtomicBoolean registered = new AtomicBoolean();
        try {
            return registerAndTrackTurn(request, events, registered, admissionBarrier, true,
                    (thread, workspace) -> turns.resume(request.turnId(), thread.revision(),
                            projectedEvents(thread.threadId(), registered.get(), events),
                            turns.runContext(request.planId(), request.runId())));
        } catch (RuntimeException failure) {
            // TurnService 在找到候选或版本校验失败时会同步拒绝；ACL 尚未交付恢复 completion，
            // 必须先清理 continuation，避免迟到答案再次恢复已拒绝的 Turn。
            clearResumeContinuation(request);
            rejectAdmission(request, admissionBarrier, failure);
            if (registered.get()) {
                events.abandonTurn(request.turnId());
            }
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * 共用新建和恢复的事件登记、终态跟踪顺序，同时把各自不同的 TurnService 调用留在原入口。
     *
     * @param request 冻结的 Plan/run/Turn 身份，用于绑定本次执行和登记的连接上下文
     * @param events 当前连接的 Plan 事件出口
     * @param registered 记录登记结果，供同步拒绝路径决定是否释放连接关联
     * @param admissionBarrier 与该 Turn 配对的结算屏障，供 Stop 等待真实终态
     * @param resuming 是否恢复同一持久 Turn，而不是创建新的隐藏 Turn
     * @param turnOperation 保留 startContinuation 或 resume 的实际语义，不混合新建和游标恢复
     */
    private CompletionStage<Void> registerAndTrackTurn(
            PlanExecutionCoordinator.ExecutionRequest request,
            PlanExecutionEventSink events,
            AtomicBoolean registered,
            CompletableFuture<TurnResult> admissionBarrier,
            boolean resuming,
            BiFunction<PlanConversationPort.ThreadBinding, PlanWorkspacePort.Binding,
                    PlanTurnPort.Accepted> turnOperation) {
        PlanConversationPort.ThreadBinding thread = conversations.readThread(request.ownerThreadId());
        PlanWorkspacePort.Binding workspace = workspaces.requireOpenWorkspace(thread.workspaceId());
        boolean observing = resuming
                ? events.registerResumedTurn(request.turnId(), workspace.workspaceId(),
                        thread.threadId(), thread.revision())
                : events.registerTurn(request.turnId(), workspace.workspaceId(),
                        thread.threadId(), thread.revision());
        registered.set(observing);
        PlanTurnPort.Accepted accepted = turnOperation.apply(thread, workspace);
        CompletionStage<TurnResult> settled = trackCompletion(request, accepted.completion(),
                clock.instant(), admissionBarrier);
        CompletionStage<Void> completion = automaticProgression(settled);
        completion.whenComplete((ignored, failure) -> {
            if (registered.get()) {
                events.abandonTurn(request.turnId());
            }
        });
        return completion;
    }

    /**
     * Plan 新建与恢复共用同一 Task 投影出口；未注册观察时只保留内部事实，不伪造公开事件。
     */
    private io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink projectedEvents(
            String threadId, boolean registered, PlanExecutionEventSink events) {
        return tasks.projectContinuationEvents(threadId, events, registered);
    }

    /**
     * claim 校验与 admission barrier 在同一锁内线性化；单个 holder 让 Stop 总能同时取得准入与结算屏障。
     */
    private Optional<TurnAdmission> confirmClaimAndReserveAdmission(
            PlanExecutionCoordinator.ExecutionRequest request, Optional<PlanRepository.PlanTurnClaim> expectedClaim) {
        synchronized (activeTurnLock) {
            PlanSnapshot snapshot = plans.readPlanSnapshot(request.planId());
            PlanModels.Plan plan = snapshot.plan();
            if (plan.status() != PlanStatus.EXECUTING
                || !Objects.equals(plan.activePlanRevisionId(), request.planRevisionId())
                || !Objects.equals(plan.activeRunId(), request.runId())) {
                return Optional.empty();
            }
            PlanRepository.ClaimPlanTurn confirmation = new PlanRepository.ClaimPlanTurn(request.planId(),
                    plan.revision(), request.runId(), request.planRevisionId(), request.turnId(),
                    id("evt_"), "plan-turn-claim:" + request.turnId(), clock.instant());
            Optional<PlanRepository.PlanTurnClaim> confirmed = plans.claimPlanTurn(confirmation);
            if (confirmed.isEmpty() || expectedClaim.filter(claim -> !claim.equals(confirmed.get())).isPresent()) {
                return Optional.empty();
            }
            ActiveTurn existing = activeTurns.get(request.turnId());
            if (existing != null) {
                return Optional.of(new TurnAdmission(existing.completion, false, snapshot));
            }
            ActiveTurn reserved = new ActiveTurn(new CompletableFuture<>());
            activeTurns.put(request.turnId(), reserved);
            return Optional.of(new TurnAdmission(reserved.completion, true, snapshot));
        }
    }

    /**
     * 从同一 holder 读取取消前的准入和结算屏障；结算失败阻止本次 STOP，后续 Stop 会重试 terminal settlement。
     */
    private CompletionStage<Void> cancelForStop(PlanExecutionCoordinator.ExecutionRequest request) {
        Objects.requireNonNull(request, "request");
        ActiveTurn activeTurn;
        boolean submitCancellation;
        synchronized (activeTurnLock) {
            activeTurn = activeTurns.get(request.turnId());
            if (activeTurn != null && activeTurn.completion.isCompletedExceptionally()) {
                activeTurns.remove(request.turnId(), activeTurn);
                activeTurn = null;
            }
            submitCancellation = activeTurn == null;
            if (submitCancellation) {
                activeTurn = new ActiveTurn(new CompletableFuture<>());
                activeTurns.put(request.turnId(), activeTurn);
            }
        }
        CompletableFuture<TurnResult> barrier = activeTurn.completion;
        CompletableFuture<Void> admission = activeTurn.admission;

        if (submitCancellation) {
            try {
                CompletionStage<TurnResult> cancelled = turns.cancel(request.turnId());
                trackCompletion(request, cancelled, clock.instant(), barrier);
            } catch (io.github.kongweiguang.ja.conversation.application.api.TurnUseCase.TurnCancellationException failure) {
                rejectAdmission(request, barrier, failure);
                if (failure.failure() == io.github.kongweiguang.ja.conversation.application.api.TurnUseCase.CancelFailure.TURN_NOT_FOUND) {
                    return CompletableFuture.completedFuture(null);
                }
                return CompletableFuture.failedFuture(failure);
            } catch (RuntimeException failure) {
                rejectAdmission(request, barrier, failure);
                return CompletableFuture.failedFuture(failure);
            }
            return barrier.thenApply(ignored -> null);
        }

        return admission.thenCompose(ignored -> {
            try {
                return turns.cancel(request.turnId()).thenCompose(ignoredResult -> barrier.thenApply(result -> null));
            } catch (io.github.kongweiguang.ja.conversation.application.api.TurnUseCase.TurnCancellationException failure) {
                if (failure.failure() == io.github.kongweiguang.ja.conversation.application.api.TurnUseCase.CancelFailure.TURN_NOT_FOUND
                    && barrier.isDone() && !barrier.isCompletedExceptionally()) {
                    return barrier.thenApply(result -> null);
                }
                return CompletableFuture.failedFuture(failure);
            }
        });
    }

    /**
     * 明确的 admission 拒绝不代表 Turn terminal；只移除仍持有该屏障的 holder，避免误删并发替换状态。
     */
    private void rejectAdmission(PlanExecutionCoordinator.ExecutionRequest request,
                                 CompletableFuture<TurnResult> barrier, RuntimeException failure) {
        CompletableFuture<Void> admission = null;
        synchronized (activeTurnLock) {
            ActiveTurn activeTurn = activeTurns.get(request.turnId());
            if (activeTurn != null && activeTurn.completion == barrier) {
                activeTurns.remove(request.turnId(), activeTurn);
                admission = activeTurn.admission;
            }
        }
        if (admission != null) {
            admission.completeExceptionally(failure);
        }
        barrier.completeExceptionally(failure);
    }

    /**
     * 为 Turn 预留成对屏障；暂停后的恢复沿用未完成 holder，停止不会错过取消结果。
     */
    private CompletableFuture<TurnResult> reserveCompletionBarrier(
            PlanExecutionCoordinator.ExecutionRequest request) {
        synchronized (activeTurnLock) {
            ActiveTurn existing = activeTurns.get(request.turnId());
            if (existing != null && !existing.completion.isDone()) {
                return existing.completion;
            }
            ActiveTurn reserved = new ActiveTurn(new CompletableFuture<>());
            activeTurns.put(request.turnId(), reserved);
            return reserved.completion;
        }
    }

    /**
     * 用 TurnService 的真实终态结算账本；来源与成对屏障一起保留，失败 holder 供后续 Stop 重试。
     */
    private CompletionStage<TurnResult> trackCompletion(PlanExecutionCoordinator.ExecutionRequest request,
                                                         CompletionStage<TurnResult> accepted,
                                                         Instant startedAt,
                                                         CompletableFuture<TurnResult> barrier) {
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(barrier, "barrier");
        ActiveTurn trackedTurn;
        CompletableFuture<Void> admission;
        synchronized (activeTurnLock) {
            ActiveTurn current = activeTurns.get(request.turnId());
            if (current != null && current.completion != barrier) {
                return current.completion;
            }
            if (current == null) {
                current = new ActiveTurn(barrier);
                activeTurns.put(request.turnId(), current);
            }
            if (current.source == accepted) {
                return barrier;
            }
            current.source = accepted;
            trackedTurn = current;
            admission = current.admission;
        }
        admission.complete(null);
        trackedCompletion(request, accepted, startedAt).whenComplete((result, failure) -> {
            if (failure == null) {
                barrier.complete(result);
                synchronized (activeTurnLock) {
                    activeTurns.remove(request.turnId(), trackedTurn);
                }
            } else {
                barrier.completeExceptionally(failure);
            }
            resumeRegistrations.remove(request.turnId());
        });
        return barrier;
    }

    /**
     * 等待交互期間不會產生 TurnResult；收到真實終態後才把 usage 與活動牆鐘同帳本提交。
     */
    private CompletionStage<TurnResult> trackedCompletion(PlanExecutionCoordinator.ExecutionRequest request,
                                                           CompletionStage<TurnResult> accepted,
                                                           Instant startedAt) {
        return settleCompletion(accepted, () -> {
            long activeMillis = Math.max(0L, java.time.Duration.between(startedAt, clock.instant()).toMillis());
            plans.settlePlanTurn(new PlanRepository.SettlePlanTurn(request.planId(), request.runId(),
                    request.turnId(), activeMillis, "evt_plan_turn_settle_" + request.turnId(),
                    "plan-turn-settle:" + request.turnId(), clock.instant()));
        });
    }

    /**
     * 只有已提交的 TurnResult 才能结算 ledger；completion 异常表示结果未知，不能伪造结算事实。
     */
    static CompletionStage<TurnResult> settleCompletion(CompletionStage<TurnResult> accepted, Runnable settlement) {
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(settlement, "settlement");
        return accepted.thenApply(result -> {
            if (result == null) {
                throw new IllegalStateException("Turn completion returned no terminal result");
            }
            settlement.run();
            return result;
        });
    }

    /**
     * 取消请求完成后读取终态 settlement barrier；非 COMPLETED 结果不会触发 Plan 自动推进。
     */
    private CompletionStage<Void> automaticProgression(CompletionStage<TurnResult> settled) {
        return settled.thenCompose(result -> result.state() == TurnState.COMPLETED
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.failedFuture(new IllegalStateException(
                        "Plan Turn ended in " + result.state())));
    }

    /**
     * 隐藏上下文仅携带冻结 Plan 与 Run identity，不复制对话历史，避免旧消息改变已批准执行语义。
     */
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

    /**
     * 单个 Turn 的 continuation 注册记录；更新出口不改变底层 TurnService 的单消费约束。
     */
    private final class ResumeRegistration {
        private final PlanExecutionCoordinator.ExecutionRequest request;
        private final AtomicBoolean delivered = new AtomicBoolean();
        private volatile Consumer<CompletionStage<?>> continuation;

        /**
         * 创建绑定当前 Plan/run/Turn identity 的恢复注册记录。
         */
        private ResumeRegistration(PlanExecutionCoordinator.ExecutionRequest request,
                                   Consumer<CompletionStage<?>> continuation) {
            this.request = Objects.requireNonNull(request, "request");
            this.continuation = Objects.requireNonNull(continuation, "continuation");
        }

        /**
         * 更新当前连接的 continuation，但拒绝把另一个 Plan/run 路由到已有 Turn。
         */
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
            if (!delivered.get()) {
                this.continuation = continuation;
            }
        }

        /**
         * 锁外读取并发 Map 安全发布的 completion 引用；状态配对和来源去重由 activeTurnLock 内处理。
         */
        private void deliver(CompletionStage<TurnResult> completion) {
            if (!delivered.compareAndSet(false, true)) {
                return;
            }
            ActiveTurn activeTurn = activeTurns.get(request.turnId());
            CompletableFuture<TurnResult> barrier;
            if (activeTurn == null) {
                barrier = reserveCompletionBarrier(request);
            } else {
                barrier = activeTurn.completion;
            }
            CompletionStage<Void> tracked = automaticProgression(
                    trackCompletion(request, completion, clock.instant(), barrier));
            try {
                continuation.accept(tracked);
            } catch (RuntimeException failure) {
                // TurnService.resume 正处于 admission 临界区，回调异常不能让已恢复 Turn 被误结算为失败。
                throw failure;
            }
        }
    }

    /**
     * 将 claim 校验取得的屏障、启动权和快照绑定，避免调用方拼接不同准入时序的状态。
     *
     * @param completion 与该 Turn 配对的结算屏障，供控制方等待同一权威终态
     * @param owner 本次调用是否预留屏障并负责实际启动该 Turn
     * @param snapshot claim 校验时读取的 Plan 快照，作为该 Turn 启动所依据的冻结状态
     */
    private record TurnAdmission(CompletableFuture<TurnResult> completion,
                                 boolean owner,
                                 PlanSnapshot snapshot) {
    }

    /**
     * 将同一 Turn 的准入屏障、结算屏障和来源阶段绑定，避免并行索引在替换时失去配对关系。
     * completion 与 admission 通过 final 字段安全发布；source 只在 activeTurnLock 下访问。
     */
    private static final class ActiveTurn {
        private final CompletableFuture<TurnResult> completion;
        private final CompletableFuture<Void> admission;
        private CompletionStage<TurnResult> source;

        /** 固定屏障身份，保证整个生命周期内准入与结算属于同一 Turn holder。 */
        private ActiveTurn(CompletableFuture<TurnResult> completion) {
            this.completion = Objects.requireNonNull(completion, "completion");
            this.admission = new CompletableFuture<>();
        }
    }
}

