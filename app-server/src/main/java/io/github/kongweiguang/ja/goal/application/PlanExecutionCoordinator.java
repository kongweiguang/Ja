// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.conversation.application.interaction.InteractionSuspendedException;
import io.github.kongweiguang.ja.conversation.application.loop.AgentLoop;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationSource;
import io.github.kongweiguang.ja.goal.domain.GoalModels.Plan;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanStatus;
import io.github.kongweiguang.ja.goal.port.in.PlanExecutionEventSink;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;
import io.github.kongweiguang.ja.goal.port.out.GoalRepositoryException;
import io.github.kongweiguang.ja.goal.port.out.PlanEvaluatorPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** 单次启动 PLAN_EXECUTION hidden Turn；独立 Plan 不获得 Goal 的永久自动续跑语义。 */
public final class PlanExecutionCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(PlanExecutionCoordinator.class);
    private final GoalRepository plans;
    private final PlanExecutionTurnPort turns;
    private final PlanEvaluatorPort evaluator;
    private final Clock clock;
    private final java.util.concurrent.ConcurrentMap<String, CancellationSource> activeEvaluations =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<String, CompletionStage<Plan>> evaluationCompletions =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Consumer<String> planCommitted = ignored -> { };

    /** coordinator 只持有权威 repository 与既有 Turn adapter port。 */
    PlanExecutionCoordinator(GoalRepository plans, PlanExecutionTurnPort turns, Clock clock) {
        this(plans, turns, clock, new DeterministicPlanEvaluator());
    }

    /** 生产可替换验收策略，默认策略只读取 SQLite 事实而不执行 Tool。 */
    public PlanExecutionCoordinator(GoalRepository plans, PlanExecutionTurnPort turns, Clock clock,
                                    PlanEvaluatorPort evaluator) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    /** 由组合根绑定提交后事件出口；验收结果已落库后才通知 Plan 观察者。 */
    public void bindPlanCommitObserver(Consumer<String> observer) {
        this.planCommitted = Objects.requireNonNull(observer, "observer");
    }

    /**
     * run 已持久化后启动一次 Turn；同步启动失败先结算 run，再返回 failed stage，确保所有失败都通过
     * CompletionStage 的同一异步契约交付，调用方不会面对同步/异步两种失败通道。
     */
    public CompletionStage<Void> start(Plan plan, PlanExecutionEventSink events) {
        if (plan.status() != PlanStatus.EXECUTING || plan.activePlanRevisionId() == null
                || plan.activeRunId() == null) throw new IllegalArgumentException("Plan run is not executable");
        Objects.requireNonNull(events, "events");
        ExecutionRequest request = new ExecutionRequest(plan.planId(), plan.ownerThreadId(),
                plan.activePlanRevisionId(), plan.activeRunId(), id("turn_"));
        // 预算已在 GoalService.executePlan 的同一事务内冻结；这里绝不能再次解析最新配置。
        java.util.Optional<GoalRepository.PlanTurnClaim> claim = plans.claimPlanTurn(
                new GoalRepository.ClaimPlanTurn(plan.planId(), plan.revision(),
                plan.activeRunId(), plan.activePlanRevisionId(), request.turnId(), id("evt_"),
                "plan-turn-claim:" + request.turnId(), clock.instant()));
        if (claim.isEmpty()) {
            // pause fence、预算耗尽或迟到 revision 已由 repository 原子处理；不能再次 admission。
            return CompletableFuture.completedFuture(null);
        }
        CompletionStage<Void> started;
        try {
            turns.registerResumeContinuation(request, events, resumed -> continueAfterResume(request, events, resumed));
            started = turns.start(request, claim.get(), events);
        } catch (RuntimeException failure) {
            turns.clearResumeContinuation(request);
            if (!isSuspended(failure)) settleIncomplete(request);
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
        return started.handle((ignored, failure) -> {
            if (failure != null) {
                LOG.warn("Standalone Plan Turn failed failureType={} rootType={}",
                        failure.getClass().getSimpleName(), rootType(failure));
                // SUSPENDED 已在 Turn/Interaction 事务中持久化恢复资格，不能把它误判为执行失败。
                if (!isSuspended(failure)) settleIncomplete(request);
                throw new java.util.concurrent.CompletionException(failure);
            }
            return continueRun(request, events);
        }).thenCompose(java.util.function.Function.identity()).whenComplete((ignored, failure) -> {
            if (failure != null && !isSuspended(failure)) {
                LOG.warn("Plan continuation failed cause={}", rootType(failure));
                settleIncomplete(request);
            }
        });
    }

    /** Interaction 恢复完成后重新进入同一 Plan run；失败只按原 run fencing 结算，绝不新建 Run。 */
    private void continueAfterResume(ExecutionRequest request, PlanExecutionEventSink events,
                                     CompletionStage<?> resumed) {
        // TurnService 消费掉上一回调后，当前同一 Turn 仍可能再次提出问题；先同步登记下一回调，
        // 保证 Loop 在本次恢复后快速到达第二个 request_user_input 时不会丢失唤醒。
        turns.registerResumeContinuation(request, events,
                resumedAgain -> continueAfterResume(request, events, resumedAgain));
        resumed.handle((ignored, failure) -> {
            if (failure != null) {
                if (!isSuspended(failure)) settleIncomplete(request);
                return CompletableFuture.<Void>failedFuture(failure);
            }
            return continueRun(request, events);
        }).thenCompose(java.util.function.Function.identity()).whenComplete((ignored, failure) -> {
            if (failure != null) LOG.warn("Plan continuation after interaction failed turnId={} cause={}",
                    request.turnId(), rootType(failure));
        });
    }

    /** Turn 完成后先尝试冻结验收边界；只有确认仍需执行时才领取下一 Turn。 */
    private CompletionStage<Void> continueRun(ExecutionRequest previous, PlanExecutionEventSink events) {
        Plan current = plans.readPlanSnapshot(previous.planId()).plan();
        if (!Objects.equals(current.activeRunId(), previous.runId())) {
            return CompletableFuture.completedFuture(null);
        }
        if (current.status() == PlanStatus.VERIFYING) {
            return verify(current).thenCompose(verified -> continueAfterVerification(verified, events));
        }
        if (current.status() != PlanStatus.EXECUTING) return CompletableFuture.completedFuture(null);
        Plan boundary = beginVerification(current);
        if (boundary.status() == PlanStatus.VERIFYING) {
            return verify(boundary).thenCompose(verified -> continueAfterVerification(verified, events));
        }
        return boundary.status() == PlanStatus.EXECUTING
                ? start(boundary, events) : CompletableFuture.completedFuture(null);
    }

    /** NOT_MET 已经完成一次验收，直接交给下一执行 Turn 修正，避免同一证据无限重验。 */
    private CompletionStage<Void> continueAfterVerification(Plan verified, PlanExecutionEventSink events) {
        if (verified.status() != PlanStatus.EXECUTING) return CompletableFuture.completedFuture(null);
        return start(verified, events);
    }

    /** 进入验收必须由 repository 对当前 run 执行真实 CAS，缺失实现应直接失败。 */
    private Plan beginVerification(Plan current) {
        return plans.beginPlanVerification(new GoalRepository.BeginPlanVerification(
                current.planId(), current.revision(), current.activeRunId(),
                "evt_plan_verification_ready_" + current.revision(),
                "plan-verification-ready:" + current.planId() + ":" + current.activeRunId()
                        + ":" + current.revision(), clock.instant()));
    }

    /**
     * 由步骤事务进入 VERIFYING 后调用独立 evaluator；验收结果再通过仓储 CAS 结算，
     * 因而 evaluator 崩溃只留下可恢复的 VERIFYING，而不会直接宣布完成。
     */
    public CompletionStage<Plan> verify(Plan plan) {
        if (plan.status() != PlanStatus.VERIFYING || plan.activeRunId() == null) {
            throw new IllegalArgumentException("Plan is not waiting for verification");
        }
        var snapshot = plans.readPlanSnapshot(plan.planId());
        var evidence = plans.listPlanEvidence(plan.planId(), plan.activeRunId(),
                plan.activePlanRevisionId(), 512);
        GoalRepository.PlanRunBudget budget = plans.readPlanRunBudget(plan.planId(), plan.activeRunId())
                .orElseThrow(() -> new IllegalStateException("Plan execution budget is unavailable"));
        CancellationSource cancellation = new CancellationSource();
        String key = plan.planId() + ":" + plan.activeRunId();
        CancellationSource previous = activeEvaluations.putIfAbsent(key, cancellation);
        if (previous != null) return CompletableFuture.failedFuture(new IllegalStateException("Plan verification is already running"));
        // 在 evaluator 可能同步完成前注册屏障，暂停不能越过尚未结算的 Provider，也不会留下已完成 map 项。
        CompletableFuture<Plan> verificationBarrier = new CompletableFuture<>();
        evaluationCompletions.put(key, verificationBarrier);
        PlanEvaluatorPort.EvaluationContext context = new PlanEvaluatorPort.EvaluationContext(cancellation,
                Duration.ofMillis(budget.remainingWallBudgetMillis()));
        CompletionStage<PlanEvaluatorPort.Evaluation> evaluation;
        try {
            evaluation = evaluator.evaluate(snapshot, evidence, context);
        } catch (RuntimeException failure) {
            evaluation = CompletableFuture.failedFuture(failure);
        }
        // 完整身份参与摘要，幂等键本身保持在 SQLite/RPC 的 128 字符边界内。
        String evaluationKey = "plan-verify:" + GoalDigest.sha256(plan.planId() + ":" + plan.activeRunId()
                + ":rev=" + plan.revision() + ":" + evidence.stream()
                .map(value -> value.evidenceId() + ":" + value.digest())
                .sorted().collect(java.util.stream.Collectors.joining("\n")));
        CompletionStage<Plan> completion = evaluation.handle((result, failure) -> {
                    if (cancellation.isCancellationRequested()) {
                        throw new java.util.concurrent.CancellationException("plan verification cancelled");
                    }
                    return failure == null && result != null
                            ? result
                            : new PlanEvaluatorPort.Evaluation(GoalModels.EvaluationVerdict.INCONCLUSIVE,
                            "Plan evaluator did not produce a verifiable result");
                })
                .thenApply(result -> plans.completePlanVerification(new GoalRepository.CompletePlanVerification(
                        plan.planId(), plan.revision(), plan.activeRunId(), result.verdict(), result.summary(),
                        id("evt_"), evaluationKey, clock.instant())))
                .whenComplete((completed, failure) -> {
                    activeEvaluations.remove(key, cancellation);
                    if (failure == null) verificationBarrier.complete(completed);
                    else verificationBarrier.completeExceptionally(failure);
                    evaluationCompletions.remove(key, verificationBarrier);
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
        return completion;
    }

    /** 在 Plan CAS 前要求当前真实 Turn 进入安全结算，暂停不会吞掉仍可恢复的输入游标。 */
    public CompletionStage<Void> pause(Plan plan) {
        return cancelVerification(plan).thenCompose(ignored -> control(plan, turns::pause));
    }

    /** 停止动作与暂停分离，adapter 必须取消当前 Turn 并等待其终态结算。 */
    public CompletionStage<Void> stop(Plan plan) {
        return cancelVerification(plan).thenCompose(ignored -> control(plan, turns::stop));
    }

    /**
     * 关闭临时 owner 前同步停止其当前 Plan；真实 Turn/evaluator 完成控制后才提交 STOPPED，
     * 从而避免 TaskCoordinator 只取消可见 Thread 就把隐藏 Plan 误报为已结束。
     */
    public void stopOwners(Set<String> ownerThreadIds) {
        Objects.requireNonNull(ownerThreadIds, "ownerThreadIds");
        for (String ownerThreadId : Set.copyOf(ownerThreadIds)) {
            if (ownerThreadId == null || ownerThreadId.isBlank()) {
                throw new IllegalArgumentException("invalid Plan owner identity");
            }
            stopOwner(ownerThreadId);
        }
    }

    /**
     * 单个 owner 的 Plan 关闭只接受仍绑定同一 run 的可停止投影；提交或观察失败不能回滚已
     * 发生的取消，且已被并发控制动作终结的目标只按幂等成功处理。
     */
    private void stopOwner(String ownerThreadId) {
        Plan plan = plans.findActivePlanByOwner(ownerThreadId).orElse(null);
        if (!isStoppable(plan)) return;

        awaitControl(stop(plan));
        try {
            Plan stopped = plans.stopPlan(new GoalRepository.StopPlan(plan.planId(), plan.revision(),
                    plan.activeRunId(), id("evt_"), "side-chat-owner-stop:" + plan.planId() + ":"
                    + plan.activeRunId(), clock.instant()));
            try {
                planCommitted.accept(stopped.planId());
            } catch (RuntimeException publicationFailure) {
                // STOPPED 已是持久事实，观察者故障不得让侧聊关闭重试重复控制同一 Plan。
                LOG.warn("Plan owner stop event publication failed cause={}", rootType(publicationFailure));
            }
        } catch (GoalRepositoryException failure) {
            if (!stoppablePlanStillOwned(plan)) return;
            throw failure;
        }
    }

    /** Plan 只有这三类状态仍可能持有需要停止的独立执行 run。 */
    private static boolean isStoppable(Plan plan) {
        return plan != null && plan.activeRunId() != null
                && (plan.status() == PlanStatus.EXECUTING
                || plan.status() == PlanStatus.VERIFYING
                || plan.status() == PlanStatus.PAUSED);
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

    /** 同步关闭边界需要等待真实 adapter 控制完成，并将异步失败还原为稳定 RuntimeException。 */
    private static void awaitControl(CompletionStage<Void> control) {
        try {
            control.toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Plan Turn control failed", cause);
        }
    }

    /** 暂停或停止先发取消信号，使独立 evaluator 释放 Provider，再由 Plan CAS 收口状态。 */
    private CompletionStage<Void> cancelVerification(Plan plan) {
        if (plan.activeRunId() == null) return CompletableFuture.completedFuture(null);
        CancellationSource source = activeEvaluations.get(plan.planId() + ":" + plan.activeRunId());
        if (source == null) return CompletableFuture.completedFuture(null);
        source.cancel("plan_controlled");
        CompletionStage<Plan> completion = evaluationCompletions.get(plan.planId() + ":" + plan.activeRunId());
        return completion == null ? CompletableFuture.completedFuture(null)
                : completion.handle((ignored, failure) -> null);
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
        String freshTurnId = id("turn_");
        ExecutionRequest request = requestFor(plan, freshTurnId);
        boolean hasResumableTurn = !request.turnId().equals(freshTurnId);
        try {
            turns.registerResumeContinuation(request, events,
                    resumed -> continueAfterResume(request, events, resumed));
            // 返回值只表示 adapter 的恢复 admission；真实 completion 已由注册回调统一跟踪，
            // 调用方可观察它，但不得在这里再次触发 continueRun 或 Plan 结算。
            CompletionStage<Void> resumed = turns.resume(request, events);
            // 没有可恢复 binding 时 adapter 会为同一 run 领取一个新 Turn；该路径没有
            // TurnService.resume 的 raw completion callback，必须由 coordinator 收口一次。
            if (!hasResumableTurn || !turns.resumeCompletionIsTrackedByContinuation()) {
                return resumed.handle((ignored, failure) -> {
                    if (failure != null) {
                        if (!isSuspended(failure)) settleIncomplete(request);
                        throw new java.util.concurrent.CompletionException(failure);
                    }
                    return continueRun(request, events);
                }).thenCompose(java.util.function.Function.identity());
            }
            resumed.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    // adapter 已声明 continuation 负责成功路径，但 admission 失败仍必须撤销旧注册，
                    // 否则迟到回答可能唤醒一个已经拒绝的 Plan run。
                    turns.clearResumeContinuation(request);
                    if (!isSuspended(failure)) settleIncomplete(request);
                }
            });
            // 保持 adapter 返回的 raw stage，避免新增 dependent 改变 TurnService continuation 与
            // coordinator 调度回调的完成顺序；失败仍由上面的副作用完成清理和 fencing。
            return resumed;
        } catch (RuntimeException failure) {
            turns.clearResumeContinuation(request);
            if (!isSuspended(failure)) settleIncomplete(request);
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }

    /** 控制只操作同一 run 的持久 Turn；没有活动 Turn 时返回成功供已收口竞态幂等重放。 */
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

    /** 以持久 binding 为优先恢复游标；binding 缺失表示上一 Turn 已安全终态。 */
    private ExecutionRequest requestFor(Plan plan, String freshTurnId) {
        return plans.findPlanTurnBinding(plan.planId(), plan.activeRunId())
                .map(binding -> new ExecutionRequest(plan.planId(), plan.ownerThreadId(),
                        binding.planRevisionId(), binding.runId(), binding.turnId()))
                .orElseGet(() -> new ExecutionRequest(plan.planId(), plan.ownerThreadId(),
                        plan.activePlanRevisionId(), plan.activeRunId(), freshTurnId));
    }

    /** 诊断只记录异常类型，不记录 Plan identity、路径、Provider 内容或用户数据。 */
    private static String rootType(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getClass().getSimpleName();
    }

    /** 识别两类已持久化等待态异常，避免 coordinator 关闭仍可由用户回答的 Plan run。 */
    private static boolean isSuspended(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof InteractionSuspendedException
                    || current instanceof AgentLoop.InputNeedsAttentionException
                    || current instanceof io.github.kongweiguang.ja.conversation.application.service.TurnService.PlanSuspendedException) return true;
            current = current.getCause();
        }
        return false;
    }

    /** callback 重新读取 Plan revision，迟到的旧 Turn 不能覆盖新 run。 */
    private void settleIncomplete(ExecutionRequest request) {
        Plan current = plans.readPlanSnapshot(request.planId()).plan();
        if ((current.status() != PlanStatus.EXECUTING && current.status() != PlanStatus.VERIFYING)
                || !Objects.equals(current.activeRunId(), request.runId())) return;
        plans.settlePlanExecution(new GoalRepository.SettlePlanExecution(current.planId(), current.revision(),
                request.runId(), id("evt_"), "plan-turn:" + request.turnId(), clock.instant()));
    }

    /** 隐藏 Turn adapter 不得创建 USER message 或借用 Goal continuation lease，独立 Plan 只启动一次。 */
    public interface PlanExecutionTurnPort {
        /** 只为已持久化 Run 启动一个 PLAN_EXECUTION Turn，adapter 必须在 admission 前重验 identity。 */
        CompletionStage<Void> start(ExecutionRequest request, PlanExecutionEventSink events);

        /** 将 Interaction 回答后的恢复 stage 回接 coordinator，保持 Plan event sink 与 run identity。 */
        default void registerResumeContinuation(ExecutionRequest request, PlanExecutionEventSink events,
                                                Consumer<CompletionStage<?>> continuation) { }

        /** 启动 admission 失败时撤销尚未使用的恢复回调。 */
        default void clearResumeContinuation(ExecutionRequest request) { }

        /**
         * 标记恢复 completion 是否由注册 continuation 完成预算结算与续跑；旧端口若未实现该能力，
         * coordinator 保留直接观察返回 stage 的兜底路径，避免把未声明的回调约束当作事实。
         */
        default boolean resumeCompletionIsTrackedByContinuation() { return false; }

        /** 受限 admission 必须把 Run 剩余预算继续下传到 Turn，禁止退回单 Turn 默认上限。 */
        CompletionStage<Void> start(ExecutionRequest request, GoalRepository.PlanTurnClaim claim,
                                     PlanExecutionEventSink events);

        /** 暂停必须先完成真实 Turn 的取消/清理；SUSPENDED 输入由 adapter 保留。 */
        CompletionStage<Void> pause(ExecutionRequest request);

        /** 停止必须取消真实 Turn，不能只写 Plan status。 */
        CompletionStage<Void> stop(ExecutionRequest request);

        /** 恢复默认启动新一轮；可恢复 SUSPENDED 的 adapter 应覆盖以调用 TurnService.resume。 */
        CompletionStage<Void> resume(ExecutionRequest request, PlanExecutionEventSink events);
    }

    /** 从真实 Turn runtime 取得并冻结的跨 Turn 上限；不得由 UI 或模型参数扩大。 */
    public interface PlanExecutionBudgetPort {
        /** 在首次 Turn admission 前解析当前有效 limits；repository 负责只初始化一次。 */
        EffectiveBudget resolve(ExecutionRequest request);
    }

    /** 跨 Turn Run ledger 的累计上限，wall budget 以毫秒保存以便 SQLite 原子比较。 */
    public record EffectiveBudget(int maxModelRounds, int maxToolCalls, long wallBudgetMillis,
                                  int antiLoopTurnBudget) {
        /** 预算值必须为正且保持与单 Turn domain limits 同量级，避免整数溢出。 */
        public EffectiveBudget {
            if (maxModelRounds < 1 || maxModelRounds > 1_000_000
                    || maxToolCalls < 0 || maxToolCalls > 10_000_000
                    || wallBudgetMillis <= 0 || wallBudgetMillis > 86_400_000L * 30
                    || antiLoopTurnBudget < 1 || antiLoopTurnBudget > 256) {
                throw new IllegalArgumentException("invalid Plan execution budget");
            }
        }
    }

    /** 请求冻结已批准 revision、Run 与 Turn identity，防止异步启动漂移到后来编辑的 Plan。 */
    public record ExecutionRequest(String planId, String ownerThreadId, String planRevisionId,
                                   String runId, String turnId) { }

    /** 随机 identity 仅用于关联持久事实，不承载时间或执行顺序语义。 */
    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
