// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.service;

import io.github.kongweiguang.ja.agent.application.concurrent.CancellationSource;
import io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot;
import io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext;
import io.github.kongweiguang.ja.foundation.error.FailureDiagnostics;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.domain.PlanModels.Plan;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanStatus;
import io.github.kongweiguang.ja.plan.application.api.PlanExecutionEventSink;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepositoryException;
import io.github.kongweiguang.ja.plan.application.gateway.PlanEvaluatorPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 在同一 Plan Run 内按 revision 与 Turn identity 协调隐藏执行、终态推进和验收。
 */
public final class PlanExecutionCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(PlanExecutionCoordinator.class);
    private final PlanRepository plans;
    private final PlanExecutionTurnPort turns;
    private final PlanEvaluatorPort evaluator;
    private final Clock clock;
    private final ConcurrentMap<String, EvaluationControl> activeEvaluations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TurnTerminalAdvancement> terminalAdvancements = new ConcurrentHashMap<>();
    private volatile Consumer<String> planCommitted = ignored -> {
    };

    /**
     * coordinator 只持有权威 repository 与既有 Turn adapter port。
     */
    PlanExecutionCoordinator(PlanRepository plans, PlanExecutionTurnPort turns, Clock clock) {
        this(plans, turns, clock, new DeterministicPlanEvaluator());
    }

    /**
     * 生产可替换验收策略，默认策略只读取 SQLite 事实而不执行 Tool。
     */
    public PlanExecutionCoordinator(PlanRepository plans, PlanExecutionTurnPort turns, Clock clock,
                                    PlanEvaluatorPort evaluator) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    /**
     * Plan 只有这三类状态仍可能持有需要停止的独立执行 run。
     */
    private static boolean isStoppable(Plan plan) {
        return plan != null && plan.activeRunId() != null
               && (plan.status() == PlanStatus.EXECUTING
                   || plan.status() == PlanStatus.VERIFYING
                   || plan.status() == PlanStatus.PAUSED);
    }

    /**
     * 同步关闭边界只等待调用方剩余预算；超时和中断均保持失败，避免未结算 Turn 被伪报为已停止。
     * 已有 RuntimeException 保持原实例，其他异步 cause 与等待失败保留为新异常的 cause。
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    private static void awaitControl(CompletionStage<Void> control, long deadlineNanos) {
        try {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new IllegalStateException("Plan owner stop exceeded side chat close deadline");
            }
            control.toCompletableFuture().get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Plan owner stop was interrupted", failure);
        } catch (TimeoutException failure) {
            throw new IllegalStateException("Plan owner stop exceeded side chat close deadline", failure);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Plan Turn control failed", cause);
        }
    }

    /**
     * 首次执行与显式恢复绑定发起客户端，后续异步 Turn 沿用精确 Run 的内存快照；
     * 共享后台重启后没有新连接时不得偷用自己的启动环境继续旧计划。
     */
    private static void bindRunContext(Plan plan) {
        NativeExecutionContext bridge = NativeExecutionContext.shared();
        NativeExecutionSnapshot current = bridge.current().orElse(null);
        if (current != null) {
            bridge.bindPlanRun(plan.planId(), plan.activeRunId(), current);
        }
        if (bridge.sharedMode()
            && bridge.findPlanRun(plan.planId(), plan.activeRunId()).isEmpty()) {
            throw new IllegalStateException("native execution context must be rebound before Plan execution");
        }
    }

    /**
     * 类型诊断共用身份去重的摘要入口，间接 cause 环不能阻断失败回调的结算。
     */
    private static String rootType(Throwable failure) {
        return FailureDiagnostics.summarize(failure).type();
    }

    /**
     * 随机 identity 仅用于关联持久事实，不承载时间或执行顺序语义。
     */
    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 由组合根绑定提交后事件出口；验收结果已落库后才通知 Plan 观察者。
     */
    public void bindPlanCommitObserver(Consumer<String> observer) {
        this.planCommitted = Objects.requireNonNull(observer, "observer");
    }

    /**
     * run 已持久化后启动一次 Turn；同步启动失败先结算 run，再返回 failed stage，确保所有失败都通过
     * CompletionStage 的同一异步契约交付，调用方不会面对同步/异步两种失败通道。
     */
    public CompletionStage<Void> start(Plan plan, PlanExecutionEventSink events) {
        if (plan.status() != PlanStatus.EXECUTING || plan.activePlanRevisionId() == null
            || plan.activeRunId() == null) {
                throw new IllegalArgumentException("Plan run is not executable");
            }
        Objects.requireNonNull(events, "events");
        bindRunContext(plan);
        ExecutionRequest request = new ExecutionRequest(plan.planId(), plan.ownerThreadId(),
                plan.activePlanRevisionId(), plan.activeRunId(), id("turn_"));
        java.util.Optional<PlanRepository.PlanTurnClaim> claim = plans.claimPlanTurn(
                new PlanRepository.ClaimPlanTurn(plan.planId(), plan.revision(),
                        plan.activeRunId(), plan.activePlanRevisionId(), request.turnId(), id("evt_"),
                        "plan-turn-claim:" + request.turnId(), clock.instant()));
        if (claim.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        TurnTerminalAdvancement advancement = terminalAdvancement(request);
        CompletionStage<Void> started;
        try {
            turns.registerResumeContinuation(request, events,
                    resumed -> continueAfterResume(request, events, resumed, advancement));
            started = turns.start(request, claim.get(), events);
        } catch (RuntimeException failure) {
            turns.clearResumeContinuation(request);
            return advanceTerminal(request, events, advancement, failure);
        }
        return started.handle((ignored, failure) -> {
            return advanceTerminal(request, events, advancement, failure);
        }).thenCompose(java.util.function.Function.identity()).whenComplete((ignored, failure) -> {
            if (failure != null) {
                LOG.warn("Plan continuation failed cause={}", rootType(failure));
            }
        });
    }

    /**
     * typed wait 的多次恢复仍观察同一 completion；共享推进门防止重复领取 Plan 验收或下一个 Turn。
     */
    private void continueAfterResume(ExecutionRequest request, PlanExecutionEventSink events,
                                     CompletionStage<?> resumed, TurnTerminalAdvancement advancement) {
        // TurnService 消费掉上一回调后，当前同一 Turn 仍可能再次提出问题；先同步登记下一回调，
        // 保证 Loop 在本次恢复后快速到达第二个 request_user_input 时不会丢失唤醒。
        turns.registerResumeContinuation(request, events,
                resumedAgain -> continueAfterResume(request, events, resumedAgain, advancement));
        resumed.handle((ignored, failure) -> {
            return advanceTerminal(request, events, advancement, failure);
        }).thenCompose(java.util.function.Function.identity()).whenComplete((ignored, failure) -> {
            if (failure != null) {
                LOG.warn("Plan continuation after interaction failed turnId={} cause={}",
                    request.turnId(), rootType(failure));
            }
        });
    }

    /**
     * 启动和恢复都可能观察同一个持久 completion；同一 Turn 只允许执行一次终态推进。
     * 故障位置复用有界脱敏摘要，定位控制与恢复竞态而不让用户内容进入日志或改变失败结算规则。
     */
    private CompletionStage<Void> advanceTerminal(ExecutionRequest request, PlanExecutionEventSink events,
                                                  TurnTerminalAdvancement advancement, Throwable failure) {
        return advancement.advance(() -> terminalAdvancements.remove(request.turnId(), advancement), () -> {
            if (failure != null) {
                FailureDiagnostics.Summary diagnostic = FailureDiagnostics.summarize(failure);
                LOG.warn("Standalone Plan Turn failed failureType={} rootType={} origin={}",
                        failure.getClass().getSimpleName(), diagnostic.type(), diagnostic.origin());
                try {
                    settleIncomplete(request);
                } catch (RuntimeException settlementFailure) {
                    failure.addSuppressed(settlementFailure);
                }
                return CompletableFuture.failedFuture(failure);
            }
            return continueRun(request, events);
        });
    }

    /**
     * 为同一活动 Turn 复用推进门；持久 completion 终态后由 advanceTerminal 移除。
     */
    private TurnTerminalAdvancement terminalAdvancement(ExecutionRequest request) {
        return terminalAdvancements.computeIfAbsent(request.turnId(), ignored -> new TurnTerminalAdvancement());
    }

    /**
     * Turn 完成后在同一 Run 内尝试验收；仍需执行时继续领取该 Run 的下一 Turn。
     */
    private CompletionStage<Void> continueRun(ExecutionRequest previous, PlanExecutionEventSink events) {
        Plan current = plans.readPlanSnapshot(previous.planId()).plan();
        if (!Objects.equals(current.activeRunId(), previous.runId())) {
            return CompletableFuture.completedFuture(null);
        }
        if (current.status() == PlanStatus.VERIFYING) {
            return verify(current).thenCompose(verified -> continueAfterVerification(verified, events));
        }
        if (current.status() != PlanStatus.EXECUTING) {
            return CompletableFuture.completedFuture(null);
        }
        Plan boundary = beginVerification(current);
        if (boundary.status() == PlanStatus.VERIFYING) {
            return verify(boundary).thenCompose(verified -> continueAfterVerification(verified, events));
        }
        return boundary.status() == PlanStatus.EXECUTING
                ? start(boundary, events) : CompletableFuture.completedFuture(null);
    }

    /**
     * NOT_MET 已经完成一次验收，继续同一 Run 的下一 Turn 修正，避免同一证据无限重验。
     */
    private CompletionStage<Void> continueAfterVerification(Plan verified, PlanExecutionEventSink events) {
        if (verified.status() != PlanStatus.EXECUTING) {
            return CompletableFuture.completedFuture(null);
        }
        return start(verified, events);
    }

    /**
     * 进入验收必须由 repository 对当前 run 执行真实 CAS，缺失实现应直接失败。
     */
    private Plan beginVerification(Plan current) {
        return plans.beginPlanVerification(new PlanRepository.BeginPlanVerification(
                current.planId(), current.revision(), current.activeRunId(),
                "evt_plan_verification_ready_" + current.revision(),
                "plan-verification-ready:" + current.planId() + ":" + current.activeRunId()
                + ":" + current.revision(), clock.instant()));
    }

    /**
     * 先读取并校验权威事实，再单独调用 evaluator；事实或存储失败保留原异常，只有 evaluator 无结论时才写入
     * INCONCLUSIVE，避免把基础设施故障伪装成验收结论并确保取消屏障持有完整 owner。
     */
    public CompletionStage<Plan> verify(Plan plan) {
        if (plan.status() != PlanStatus.VERIFYING || plan.activeRunId() == null) {
            throw new IllegalArgumentException("Plan is not waiting for verification");
        }
        CancellationSource cancellation = new CancellationSource();
        String key = plan.planId() + ":" + plan.activeRunId();
        EvaluationControl control = new EvaluationControl(cancellation, new CompletableFuture<>());
        EvaluationControl previous = activeEvaluations.putIfAbsent(key, control);
        if (previous != null)
            {
                return CompletableFuture.failedFuture(new IllegalStateException("Plan verification is already running"));
            }
        PlanEvaluatorPort.EvaluationContext context = new PlanEvaluatorPort.EvaluationContext(cancellation);
        PlanModels.PlanSnapshot snapshot;
        List<PlanModels.Evidence> evidence;
        String evaluationKey;
        try {
            // 先公开完整控制状态，再做同步读取；暂停若并发到达，会取消并等待这个 owner。
            snapshot = Objects.requireNonNull(plans.readPlanSnapshot(plan.planId()),
                    "Plan verification snapshot is required");
            cancellation.throwIfCancellationRequested();
            evidence = Objects.requireNonNull(plans.listPlanEvidence(plan.planId(), plan.activeRunId(),
                    plan.activePlanRevisionId(), 512), "Plan verification evidence is required");
            cancellation.throwIfCancellationRequested();
            // 完整身份参与摘要，幂等键本身保持在 SQLite/RPC 的 128 字符边界内。
            evaluationKey = "plan-verify:" + PlanDigest.sha256(plan.planId() + ":" + plan.activeRunId()
                    + ":rev=" + plan.revision() + ":" + evidence.stream()
                    .map(value -> value.evidenceId() + ":" + value.digest())
                    .sorted().collect(java.util.stream.Collectors.joining("\n")));
        } catch (RuntimeException failure) {
            cancellation.cancel("plan_verification_setup_failed");
            activeEvaluations.remove(key, control);
            control.completion().completeExceptionally(failure);
            return control.completion();
        }
        CompletionStage<PlanEvaluatorPort.Evaluation> evaluation;
        try {
            cancellation.throwIfCancellationRequested();
            evaluation = evaluator.evaluate(snapshot, evidence, context);
        } catch (RuntimeException evaluatorFailure) {
            evaluation = CompletableFuture.failedFuture(evaluatorFailure);
        }
        if (evaluation == null) {
            evaluation = CompletableFuture.failedFuture(
                    new IllegalStateException("Plan evaluator did not return a completion stage"));
        }
        evaluation.handle((result, failure) -> {
                    if (cancellation.isCancellationRequested()) {
                        throw new java.util.concurrent.CancellationException("plan verification cancelled");
                    }
                    return failure == null && result != null
                            ? result
                            : new PlanEvaluatorPort.Evaluation(PlanModels.EvaluationVerdict.INCONCLUSIVE,
                            "Plan evaluator did not produce a verifiable result");
                })
                .thenApply(result -> plans.completePlanVerification(new PlanRepository.CompletePlanVerification(
                        plan.planId(), plan.revision(), plan.activeRunId(), result.verdict(), result.summary(),
                        id("evt_"), evaluationKey, clock.instant())))
                .whenComplete((completed, failure) -> {
                    activeEvaluations.remove(key, control);
                    if (failure == null) {
                        control.completion().complete(completed);
                    }
                    else {
                        control.completion().completeExceptionally(failure);
                    }
                    if (failure == null && completed != null) {
                        try {
                            planCommitted.accept(completed.planId());
                        } catch (RuntimeException callbackFailure) {
                            // 观察者故障不能把已提交的验收事实伪装成失败或触发重复执行。
                            LOG.warn("Plan verification event publication failed cause={}",
                                    rootType(callbackFailure));
                        }
                    }
                });
        return control.completion();
    }

    /**
     * 在 Plan CAS 前要求当前真实 Turn 进入安全结算，暂停不会吞掉仍可恢复的输入游标。
     */
    public CompletionStage<Void> pause(Plan plan) {
        return cancelVerification(plan).thenCompose(ignored -> control(plan, turns::pause));
    }

    /**
     * 停止动作与暂停分离，adapter 必须取消当前 Turn 并等待其终态结算。
     */
    public CompletionStage<Void> stop(Plan plan) {
        return cancelVerification(plan).thenCompose(ignored -> control(plan, turns::stop));
    }

    /**
     * 关闭临时 owner 前同步停止其当前 Plan；真实 Turn/evaluator 必须在共享 deadline 前完成控制，
     * 才提交 STOPPED，避免侧聊关闭无限等待或把隐藏 Plan 误报为已结束。
     */
    public void stopOwners(Set<String> ownerThreadIds, long deadlineNanos) {
        Objects.requireNonNull(ownerThreadIds, "ownerThreadIds");
        for (String ownerThreadId : Set.copyOf(ownerThreadIds)) {
            if (ownerThreadId == null || ownerThreadId.isBlank()) {
                throw new IllegalArgumentException("invalid Plan owner identity");
            }
            stopOwner(ownerThreadId, deadlineNanos);
        }
    }

    /**
     * 单个 owner 的 Plan 关闭先持久 fence 同一 Run，再等待真实 Turn/evaluator 收口；关闭可推进 Plan revision，
     * 因此等待控制完成后重读 owner，再以最新 revision 做 STOPPED CAS。超时或账本失败不提交 STOPPED。
     */
    private void stopOwner(String ownerThreadId, long deadlineNanos) {
        Plan plan = plans.findActivePlanByOwner(ownerThreadId).orElse(null);
        if (!isStoppable(plan)) {
            return;
        }

        // 侧聊关闭先封住同一 Run 的后续 Turn/验收准入，再等待当前执行者真实收口。
        plans.requestPlanPause(new PlanRepository.RequestPlanPause(plan.planId(), plan.revision(),
                plan.activeRunId(), id("evt_"), "side-chat-owner-stop-fence:" + plan.planId() + ":"
                                                        + plan.activeRunId(), clock.instant()));
        awaitControl(stop(plan), deadlineNanos);
        Plan current = plans.findActivePlanByOwner(ownerThreadId).orElse(null);
        if (!isStoppable(current) || !current.planId().equals(plan.planId())
            || !Objects.equals(current.activeRunId(), plan.activeRunId())
            || !Objects.equals(current.activePlanRevisionId(), plan.activePlanRevisionId())) {
            return;
        }
        try {
            Plan stopped = plans.stopPlan(new PlanRepository.StopPlan(current.planId(), current.revision(),
                    current.activeRunId(), id("evt_"), "side-chat-owner-stop:" + current.planId() + ":"
                                                      + current.activeRunId(), clock.instant()));
            try {
                planCommitted.accept(stopped.planId());
            } catch (RuntimeException publicationFailure) {
                // STOPPED 已是持久事实，观察者故障不得让侧聊关闭重试重复控制同一 Plan。
                LOG.warn("Plan owner stop event publication failed cause={}", rootType(publicationFailure));
            }
        } catch (PlanRepositoryException failure) {
            if (!stoppablePlanStillOwned(plan)) {
                return;
            }
            throw failure;
        }
    }

    /**
     * 重新读取 owner 投影区分“控制竞态已完成”与真正 revision/state 故障；同一 plan/run 仍可停止
     * 时必须保留原异常，避免关闭流程以假成功掩盖持久化不一致。
     */
    private boolean stoppablePlanStillOwned(Plan expected) {
        Plan current = plans.findActivePlanByOwner(expected.ownerThreadId()).orElse(null);
        return isStoppable(current) && current.planId().equals(expected.planId())
               && Objects.equals(current.activeRunId(), expected.activeRunId());
    }

    /**
     * 暂停或停止先发取消信号，使独立 evaluator 释放 Provider，再由 Plan CAS 收口状态。
     */
    private CompletionStage<Void> cancelVerification(Plan plan) {
        if (plan.activeRunId() == null) {
            return CompletableFuture.completedFuture(null);
        }
        EvaluationControl control = activeEvaluations.get(plan.planId() + ":" + plan.activeRunId());
        if (control == null) {
            return CompletableFuture.completedFuture(null);
        }
        control.cancellation().cancel("plan_controlled");
        return control.completion().handle((ignored, failure) -> null);
    }

    /**
     * 恢复沿用原 run/revision；没有可恢复 Turn 时才创建同一 run 的新 Turn。
     * TurnService 会在 resume admission 内消费 continuation，因此恢复完成后的预算结算和
     * continueRun 只能由该回调链负责；这里不能再观察返回 completion，否则一次回答会推进两次。
     */
    public CompletionStage<Void> resume(Plan plan, PlanExecutionEventSink events) {
        if (plan.status() != PlanStatus.EXECUTING && plan.status() != PlanStatus.PAUSED) {
            throw new IllegalArgumentException("Plan run is not resumable");
        }
        Objects.requireNonNull(events, "events");
        bindRunContext(plan);
        String freshTurnId = id("turn_");
        ExecutionRequest request = requestFor(plan, freshTurnId);
        boolean hasResumableTurn = !request.turnId().equals(freshTurnId);
        TurnTerminalAdvancement advancement = terminalAdvancement(request);
        try {
            turns.registerResumeContinuation(request, events,
                    resumed -> continueAfterResume(request, events, resumed, advancement));
            CompletionStage<Void> resumed = turns.resume(request, events);
            // 同一适配器 continuation 与返回 stage 可能指向同一 Turn；推进门负责合并终态观察。
            if (!hasResumableTurn || !turns.resumeCompletionIsTrackedByContinuation()) {
                return resumed.handle((ignored, failure) -> {
                    return advanceTerminal(request, events, advancement, failure);
                }).thenCompose(java.util.function.Function.identity());
            }
            resumed.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    // adapter 已声明 continuation 负责成功路径，但 admission 失败仍必须撤销旧注册，
                    // 否则迟到回答可能唤醒一个已经拒绝的 Plan run。
                    turns.clearResumeContinuation(request);
                    advanceTerminal(request, events, advancement, failure);
                }
            });
            // 保持 adapter 返回的 raw stage，避免新增 dependent 改变 TurnService continuation 与
            // coordinator 调度回调的完成顺序；失败仍由上面的副作用完成清理和 fencing。
            return resumed;
        } catch (RuntimeException failure) {
            turns.clearResumeContinuation(request);
            return advanceTerminal(request, events, advancement, failure);
        }
    }

    /**
     * 控制只操作同一 run 的持久 Turn；没有活动 Turn 时返回成功供已收口竞态幂等重放。
     */
    private CompletionStage<Void> control(Plan plan,
                                          java.util.function.Function<ExecutionRequest, CompletionStage<Void>> action) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(action, "action");
        if (plan.activeRunId() == null || plan.activePlanRevisionId() == null) {
            return CompletableFuture.completedFuture(null);
        }
        return plans.findPlanTurnBinding(plan.planId(), plan.activeRunId())
                .map(binding -> action.apply(new ExecutionRequest(plan.planId(), plan.ownerThreadId(),
                        binding.planRevisionId(), binding.runId(), binding.turnId())))
                .orElseGet(() -> CompletableFuture.completedFuture(null));
    }

    /**
     * 以持久 binding 为优先恢复游标；binding 缺失表示上一 Turn 已安全终态。
     */
    private ExecutionRequest requestFor(Plan plan, String freshTurnId) {
        return plans.findPlanTurnBinding(plan.planId(), plan.activeRunId())
                .map(binding -> new ExecutionRequest(plan.planId(), plan.ownerThreadId(),
                        binding.planRevisionId(), binding.runId(), binding.turnId()))
                .orElseGet(() -> new ExecutionRequest(plan.planId(), plan.ownerThreadId(),
                        plan.activePlanRevisionId(), plan.activeRunId(), freshTurnId));
    }

    /**
     * callback 重新读取 Plan revision，迟到的旧 Turn 不能覆盖新 run。
     */
    private void settleIncomplete(ExecutionRequest request) {
        Plan current = plans.readPlanSnapshot(request.planId()).plan();
        if ((current.status() != PlanStatus.EXECUTING && current.status() != PlanStatus.VERIFYING)
            || !Objects.equals(current.activeRunId(), request.runId())) {
                return;
            }
        plans.settlePlanExecution(new PlanRepository.SettlePlanExecution(current.planId(), current.revision(),
                request.runId(), id("evt_"), "plan-turn:" + request.turnId(), clock.instant()));
    }

    /**
     * 隐藏 Turn adapter 不得创建 USER message；每个 Turn 都绑定持久 Run 与当前执行 identity。
     */
    public interface PlanExecutionTurnPort {
        /**
         * 只为已持久化 Run 启动一个 PLAN_EXECUTION Turn，adapter 必须在 admission 前重验 identity。
         */
        CompletionStage<Void> start(ExecutionRequest request, PlanExecutionEventSink events);

        /**
         * 将 Interaction 回答后的恢复 stage 回接 coordinator，保持 Plan event sink 与 run identity。
         */
        default void registerResumeContinuation(ExecutionRequest request, PlanExecutionEventSink events,
                                                Consumer<CompletionStage<?>> continuation) {
        }

        /**
         * 启动 admission 失败时撤销尚未使用的恢复回调。
         */
        default void clearResumeContinuation(ExecutionRequest request) {
        }

        /**
         * 当前 adapter 的恢复 callback 与返回 stage 都可能观察终态；协调器用同 Turn 推进门去重。
         */
        default boolean resumeCompletionIsTrackedByContinuation() {
            return false;
        }

        /**
         * 受限 admission 必须把 Run 剩余预算继续下传到 Turn，禁止退回单 Turn 默认上限。
         */
        CompletionStage<Void> start(ExecutionRequest request, PlanRepository.PlanTurnClaim claim,
                                    PlanExecutionEventSink events);

        /**
         * 暂停必须先完成真实 Turn 的取消/清理；SUSPENDED 输入由 adapter 保留。
         */
        CompletionStage<Void> pause(ExecutionRequest request);

        /**
         * 停止必须取消真实 Turn，不能只写 Plan status。
         */
        CompletionStage<Void> stop(ExecutionRequest request);

        /**
         * 恢复默认启动新一轮；可恢复 SUSPENDED 的 adapter 应覆盖以调用 TurnService.resume。
         */
        CompletionStage<Void> resume(ExecutionRequest request, PlanExecutionEventSink events);
    }

    /**
     * 请求冻结已批准 revision、Run 与 Turn identity，防止异步启动漂移到后来编辑的 Plan。
     *
     * @param planId Plan 标识
     * @param ownerThreadId 所属对话标识
     * @param planRevisionId Plan 修订版本标识
     * @param runId 运行标识
     * @param turnId 本次可恢复执行的稳定标识
     */
    public record ExecutionRequest(String planId,
                                   String ownerThreadId,
                                   String planRevisionId,
                                   String runId,
                                   String turnId) {
    }

    /**
     * 取消句柄与终态屏障作为同一 map 值发布，控制动作不能观察到缺失的完成等待项。
     *
     * @param cancellation 当前评估的取消所有者
     * @param completion 控制动作等待评估退出的唯一完成屏障
     */
    private record EvaluationControl(CancellationSource cancellation,
                                     CompletableFuture<Plan> completion) {
    }

    /**
     * 将同一 Turn 的所有 terminal observer 合并为一个异步 Plan 推进结果。
     */
    private static final class TurnTerminalAdvancement {
        private final AtomicReference<CompletableFuture<Void>> completion = new AtomicReference<>();

        /**
         * 首个观察者先释放已终态 Turn 的索引，再推进下游；其它已注册观察者共享该结果。
         */
        private CompletionStage<Void> advance(Runnable claimed, Supplier<CompletionStage<Void>> action) {
            CompletableFuture<Void> existing = completion.get();
            if (existing != null) {
                return existing;
            }
            CompletableFuture<Void> candidate = new CompletableFuture<>();
            if (!completion.compareAndSet(null, candidate)) {
                return completion.get();
            }
            try {
                claimed.run();
                Objects.requireNonNull(action.get(), "terminal advancement stage")
                        .whenComplete((ignored, failure) -> {
                            if (failure == null) {
                                candidate.complete(null);
                            }
                            else {
                                candidate.completeExceptionally(failure);
                            }
                        });
            } catch (RuntimeException | Error failure) {
                candidate.completeExceptionally(failure);
            }
            return candidate;
        }
    }
}

