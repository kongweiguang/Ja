// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.goal.domain.GoalModels.Goal;
import io.github.kongweiguang.ja.goal.domain.GoalModels.GoalPhase;
import io.github.kongweiguang.ja.goal.domain.GoalModels.GoalStatus;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** owner idle 时以 SQLite lease 单飞调度隐藏 GOAL_CONTINUATION Turn。 */
public final class GoalContinuationCoordinator implements AutoCloseable {
    private final GoalRepository goals;
    private final ContinuationTurnPort turns;
    private final Clock clock;
    private final long processGeneration;
    private final Consumer<String> committed;
    private final java.util.concurrent.ScheduledExecutorService retries;
    private final java.util.Set<String> scheduled = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** process generation 来自现有持久 ledger，禁止使用墙钟或 PID 代替 fencing。 */
    public GoalContinuationCoordinator(GoalRepository goals, ContinuationTurnPort turns,
                                       Clock clock, long processGeneration) {
        this(goals, turns, clock, processGeneration, ignored -> { });
    }

    /** notifier 只在无进展 mutation 提交后触发，观察者永远不会看到未提交投影。 */
    public GoalContinuationCoordinator(GoalRepository goals, ContinuationTurnPort turns,
                                       Clock clock, long processGeneration, Consumer<String> committed) {
        this.goals = Objects.requireNonNull(goals, "goals");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.committed = Objects.requireNonNull(committed, "committed");
        if (processGeneration < 1) throw new IllegalArgumentException("invalid process generation");
        this.processGeneration = processGeneration;
        this.retries = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("ja-goal-continuation-", 0).factory());
    }

    /** active+working Goal 无论是否挂 Plan 都可续跑；挂载时只携带冻结 link revision。 */
    public Optional<CompletionStage<Void>> continueIfEligible(String goalId) {
        Goal goal = goals.findGoal(goalId).orElse(null);
        if (goal == null || goal.status() != GoalStatus.ACTIVE || goal.phase() != GoalPhase.WORKING
                || goal.activeRunId() == null || goal.recoveryRequired()) return Optional.empty();
        if (!turns.ownerIdle(goal.ownerThreadId())) {
            scheduleRetry(goalId);
            return Optional.empty();
        }
        String leaseId = id("goallease_");
        Optional<GoalRepository.ContinuationLease> lease = goals.tryAcquireLease(
                new GoalRepository.AcquireLease(goalId, leaseId, processGeneration, clock.instant()));
        if (lease.isEmpty()) return Optional.empty();
        GoalRepository.ContinuationLease held = lease.orElseThrow();
        GoalModels.GoalPlanLink link = goals.readSnapshot(goalId).planLink();
        ContinuationRequest request = new ContinuationRequest(goal.goalId(), goal.ownerThreadId(),
                link == null ? null : link.planId(), link == null ? null : link.planRevisionId(),
                goal.activeRunId(), id("turn_"), held.fencingToken());
        CompletionStage<Void> started;
        try {
            started = turns.start(request);
        } catch (RuntimeException failure) {
            releaseAndSettle(request, leaseId, true);
            throw new ContinuationStartFailure(failure);
        }
        CompletionStage<Void> completion = started.whenComplete((ignored, failure) -> {
            // SUSPENDED 是可恢复的中间态：TurnService 已释放 Provider/运行租约，但 Goal fencing
            // lease 必须保留到原 Turn 真正终态，否则回答到达后 Tool ledger 无法验证身份。
            if (isSuspended(failure)) return;
            releaseAndSettle(request, leaseId, failure != null);
            recordNoProgress(goalId, goal.revision(), leaseId);
            scheduleRetry(goalId);
        });
        return Optional.of(completion);
    }

    /**
     * releaseLease 成功返回即证明本 identity 已提交终态或已被同一恢复路径结算；两种情况都应清除
     * 匹配 gate。真正仍 HELD 的 lease 与非终态 Turn 仍由仓储 run-replacement 检查阻断。
     */
    private void releaseAndSettle(ContinuationRequest request, String leaseId, boolean abandoned) {
        goals.releaseLease(request.goalId(), leaseId, request.fencingToken(), abandoned, clock.instant());
        turns.settled(request);
    }

    /** 识别 TurnService 的可恢复挂起信号，避免把等待回答当作执行失败收口。 */
    static boolean isSuspended(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof io.github.kongweiguang.ja.conversation.application.interaction.InteractionSuspendedException
                    || current instanceof io.github.kongweiguang.ja.conversation.application.loop.AgentLoop.InputNeedsAttentionException
                    || current instanceof io.github.kongweiguang.ja.conversation.application.service.TurnService.PlanSuspendedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 只有整个 Turn 期间 Goal revision 完全未变才计数，并用当前 revision 再做 CAS。 */
    private void recordNoProgress(String goalId, long startingRevision, String leaseId) {
        Goal current = goals.findGoal(goalId).orElse(null);
        if (current == null || current.revision() != startingRevision
                || current.status() != GoalStatus.ACTIVE || current.phase() != GoalPhase.WORKING) return;
        goals.recordContinuationNoProgress(goalId, current.revision(), id("evt_"),
                "continuation:" + leaseId, clock.instant());
        committed.accept(goalId);
    }

    /** busy owner 和刚完成 Turn 都短延迟复核；结构状态终止后自然停止，不设置 Goal 总时限。 */
    private void scheduleRetry(String goalId) {
        if (!scheduled.add(goalId) || retries.isShutdown()) return;
        retries.schedule(() -> {
            scheduled.remove(goalId);
            if (!retries.isShutdown()) continueIfEligible(goalId);
        }, 250, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** 关闭只停止新的自动续跑复核，已进入 TurnService 的 Turn 由其生命周期收口。 */
    @Override public void close() {
        retries.shutdownNow();
        scheduled.clear();
    }

    /** continuation port 由既有 Turn coordinator 实现，不创建 USER message。 */
    public interface ContinuationTurnPort {
        /** idle 判定必须来自权威 Turn 状态，不使用 UI store。 */
        boolean ownerIdle(String threadId);

        /** start 创建隐藏 GOAL_CONTINUATION turn，并绑定 lease fencing token。 */
        CompletionStage<Void> start(ContinuationRequest request);

        /** settled 仅在 coordinator 已提交 lease 终态后调用，adapter 用它开放 Goal 恢复。 */
        default void settled(ContinuationRequest request) { }

        /** 同一隐藏 Turn 的 Interaction 恢复仍归原 lease；每次暂停后都必须重新登记回调。 */
        default void registerResumeContinuation(ContinuationRequest request,
                                                 Consumer<CompletionStage<?>> continuation) { }

        /** admission 失败或 Turn 终态时清理尚未消费的恢复回调。 */
        default void clearResumeContinuation(ContinuationRequest request) { }
    }

    /** continuation 携带唯一 Turn 与 lease identity，不携带伪用户正文。 */
    public record ContinuationRequest(String goalId, String ownerThreadId, String planId, String planRevisionId,
                                      String runId, String turnId, long fencingToken) { }

    /** lease identity 唯一，真正单调性由 SQLite fencing token 提供。 */
    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 同步启动失败只以稳定内部类型越过调度边界；原始 cause 留给服务端诊断，不能成为外部契约。
     */
    static final class ContinuationStartFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        /** 固定消息避免 Provider、存储或路径细节随异常文本泄漏到上层日志与响应。 */
        ContinuationStartFailure(RuntimeException cause) {
            super("Goal continuation could not be started", Objects.requireNonNull(cause, "cause"));
        }
    }
}
