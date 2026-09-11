// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** 新进程按 generation 对账 Goal Tool 与 continuation lease，永不盲目重放副作用。 */
public final class GoalStartupRecovery {
    private static final int PAGE_SIZE = 256;
    private static final int MAX_PAGES = 4096;
    private final GoalRepository goals;
    private final Clock clock;
    private final long processGeneration;
    private final ContinuationRecoveryHook continuationRecovery;

    /** 恢复器只持有持久端口和当前 generation，不依赖进程内 Turn/UI 状态。 */
    public GoalStartupRecovery(GoalRepository goals, Clock clock, long processGeneration) {
        this(goals, clock, processGeneration, ignored -> { });
    }

    /**
     * 组合根可注入 Interaction/Turn 的恢复准备 hook；hook 只负责重新取得新 fence 和绑定原 Turn，
     * 不允许通过恢复器创建 USER 消息或猜测已执行 Tool。
     */
    public GoalStartupRecovery(GoalRepository goals, Clock clock, long processGeneration,
                               ContinuationRecoveryHook continuationRecovery) {
        this.goals = Objects.requireNonNull(goals, "goals");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.continuationRecovery = Objects.requireNonNull(continuationRecovery, "continuationRecovery");
        if (processGeneration < 1) throw new IllegalArgumentException("invalid process generation");
        this.processGeneration = processGeneration;
    }

    /** 按固定页数恢复旧事实；重复页直接失败，避免存储 CAS 无进展时无限循环。 */
    public void recover() {
        recoverLeases();
        recoverEvaluations();
        recoverToolAttempts();
        recoverPlanExecutions();
    }

    /** 旧 evaluator 无法证明 Provider 是否返回，统一失败关闭并按对应 Run 恢复为 attention。 */
    private void recoverEvaluations() {
        recoverPages("evaluations", () -> goals.listUnsettledEvaluations(processGeneration, PAGE_SIZE),
                value -> value.evaluationId(),
                value -> goals.recoverEvaluation(value, processGeneration, clock.instant()));
    }

    /** 旧 lease 先废弃，再由窄 hook 判断是否存在可恢复 Interaction；普通 lease 不会伪造续跑。 */
    private void recoverLeases() {
        recoverPages("continuation leases", () -> goals.listHeldLeases(processGeneration, PAGE_SIZE),
                value -> value.leaseId(), value -> {
                    goals.releaseLease(value.goalId(), value.leaseId(), value.fencingToken(), true, clock.instant())
                            .ifPresent(continuationRecovery::recover);
                });
    }

    /** Tool ledger 先结算，Plan 扫描随后才能区分安全失败与未知副作用。 */
    private void recoverToolAttempts() {
        recoverPages("tool attempts", () -> goals.listUnsettledToolAttempts(processGeneration, PAGE_SIZE),
                value -> value.toolAttemptId(), this::recover);
    }

    /**
     * 旧 Plan run 无论停在执行还是验证都保留 run/revision/预算；孤立 claim 先终态化，
     * 这样用户继续时可安全领取新 Turn。未知 Tool/evaluator 由 repository 变成不可继续 blocker。
     */
    private void recoverPlanExecutions() {
        recoverPages("plan executions", () -> goals.listUnsettledPlanExecutions(processGeneration, PAGE_SIZE),
                value -> value.planId() + ":" + value.runId() + ":" + value.unsafe(), value -> {
                    goals.settleOrphanedPlanTurnClaims(value.runId(), clock.instant());
                    goals.recoverPlanExecution(new GoalRepository.RecoverPlanExecution(
                            value.planId(), value.expectedPlanRevision(), value.runId(), value.unsafe(),
                            id("evt_"), "startup-plan-recovery:" + value.runId(), clock.instant()));
                });
    }

    /**
     * 每一页必须让持久查询结果发生变化；固定上限同时保护异常数据库/测试 fake，
     * 避免 startup 线程无限占用而阻塞 App Server 对外服务。
     */
    private <T> void recoverPages(String kind, Supplier<List<T>> pageLoader,
                                  Function<T, String> identity, Consumer<T> action) {
        String previousMarker = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<T> items = List.copyOf(Objects.requireNonNull(pageLoader.get(), kind + " page"));
            if (items.isEmpty()) return;
            String marker = items.stream().map(identity).sorted().collect(Collectors.joining("\n"));
            if (marker.equals(previousMarker)) {
                throw new IllegalStateException("startup recovery made no progress: " + kind);
            }
            previousMarker = marker;
            items.forEach(action);
        }
        throw new IllegalStateException("startup recovery exceeded page limit: " + kind);
    }

    /** PREPARED 从未越过执行边界；STARTED 副作用只能 UNKNOWN 并要求人工恢复。 */
    private void recover(GoalModels.ToolAttempt attempt) {
        if (attempt.state() == GoalModels.ToolAttemptState.PREPARED) {
            goals.startToolAttempt(attempt.toolAttemptId(), clock.instant());
            settleFailed(attempt, "not-executed-before-restart");
            recoverPlan(attempt, false);
            return;
        }
        if (attempt.state() != GoalModels.ToolAttemptState.STARTED) return;
        if (!attempt.sideEffect()) {
            settleFailed(attempt, "read-result-lost-after-restart");
            recoverPlan(attempt, false);
            return;
        }
        goals.settleToolAttempt(new GoalRepository.SettleToolAttempt(attempt.toolAttemptId(),
                GoalModels.ToolAttemptState.UNKNOWN, null, null, clock.instant()));
        if (attempt.goalId() == null) {
            recoverPlan(attempt, true);
            return;
        }
        GoalModels.Goal goal = goals.findGoal(attempt.goalId()).orElse(null);
        if (goal == null || goal.status() == GoalModels.GoalStatus.ACHIEVED
                || goal.status() == GoalModels.GoalStatus.STOPPED) return;
        goals.transition(new GoalRepository.Transition(goal.goalId(), goal.revision(),
                GoalModels.GoalStatus.PAUSED, GoalModels.GoalPhase.NEEDS_ATTENTION, true,
                id("evt_"), "recovery:" + attempt.toolAttemptId(), clock.instant()));
    }

    /** Plan-only attempt 以 run identity 结算；无 Plan owner 时保持 Goal 恢复路径不变。 */
    private void recoverPlan(GoalModels.ToolAttempt attempt, boolean unsafe) {
        if (attempt.planId() == null || attempt.goalId() != null) return;
        GoalModels.Plan plan = goals.readPlanSnapshot(attempt.planId()).plan();
        goals.recoverPlanExecution(new GoalRepository.RecoverPlanExecution(attempt.planId(), plan.revision(),
                attempt.runId(), unsafe, id("evt_"), "recovery:" + attempt.toolAttemptId(), clock.instant()));
    }

    /** 安全失败摘要使用固定原因生成 digest，不伪造 Tool 返回内容。 */
    private void settleFailed(GoalModels.ToolAttempt attempt, String reason) {
        goals.settleToolAttempt(new GoalRepository.SettleToolAttempt(attempt.toolAttemptId(),
                GoalModels.ToolAttemptState.FAILED, GoalDigest.sha256(reason), null, clock.instant()));
    }

    /** 恢复事件使用 opaque identity，幂等性由 attempt identity 固定。 */
    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Interaction backend 的最小恢复协作边界。实现需自行读取 Interaction/Turn 快照、重新获取
     * 当前 generation 的 Goal lease，并只对 ANSWERED/SUSPENDED 等可证明状态登记原 Turn 恢复。
     */
    @FunctionalInterface
    public interface ContinuationRecoveryHook {
        /** 旧 lease 已提交废弃后执行恢复准备；失败应留下可见 recovery blocker。 */
        void recover(GoalRepository.ContinuationLease abandonedLease);
    }
}
