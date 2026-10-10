// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.service;

import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 新进程按 generation 对账 Plan Tool attempt 与 Turn claim，永不盲目重放副作用。
 */
public final class PlanStartupRecovery {
    private static final int PAGE_SIZE = 256;
    private static final int MAX_PAGES = 4096;
    private final PlanRepository plans;
    private final Clock clock;
    private final long processGeneration;

    /**
     * 恢复器只持有持久端口和当前 generation，不依赖进程内 Turn/UI 状态。
     */
    public PlanStartupRecovery(PlanRepository plans, Clock clock, long processGeneration) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (processGeneration < 1) {
            throw new IllegalArgumentException("invalid process generation");
        }
        this.processGeneration = processGeneration;
    }

    /**
     * 恢复事件使用 opaque identity，幂等性由 attempt identity 固定。
     */
    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 按固定页数恢复旧事实；重复页直接失败，避免存储 CAS 无进展时无限循环。
     */
    public void recover() {
        recoverToolAttempts();
        recoverPlanExecutions();
    }

    /**
     * 先结算旧 generation 的全部 STARTED attempt，不在逐项处理中改变 Plan 状态；后续唯一 Run 查询
     * 会按持久化事实重新计算 unsafe，包含本次或上次启动遗留的 UNKNOWN blocker。
     */
    private void recoverToolAttempts() {
        recoverPages("tool attempts", () -> plans.listUnsettledToolAttempts(processGeneration, PAGE_SIZE),
                value -> value.toolAttemptId(), this::recover);
    }

    /**
     * 保留原 Run 与冻结 revision；只放弃没有 Turn 的准入意图，不伪造终态 Turn 的账本结算。
     * 未知 Tool/evaluator 仍由 repository 转成不可继续 blocker。
     */
    private void recoverPlanExecutions() {
        recoverPages("plan executions", () -> plans.listUnsettledPlanExecutions(processGeneration, PAGE_SIZE),
                value -> value.planId() + ":" + value.runId() + ":" + value.unsafe(), value -> {
                    plans.abandonUnadmittedPlanTurnClaims(value.runId());
                    plans.recoverPlanExecution(new PlanRepository.RecoverPlanExecution(
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
            if (items.isEmpty()) {
                return;
            }
            String marker = items.stream().map(identity).sorted().collect(Collectors.joining("\n"));
            if (marker.equals(previousMarker)) {
                throw new IllegalStateException("startup recovery made no progress: " + kind);
            }
            previousMarker = marker;
            items.forEach(action);
        }
        throw new IllegalStateException("startup recovery exceeded page limit: " + kind);
    }

    /**
     * PREPARED 缺少同提交 Core terminal 证明时保留 blocker；只有 STARTED 才按副作用边界恢复。
     */
    private void recover(PlanModels.ToolAttempt attempt) {
        if (attempt.state() != PlanModels.ToolAttemptState.STARTED) {
            return;
        }
        if (!attempt.sideEffect()) {
            settleFailed(attempt, "read-result-lost-after-restart");
        } else {
            plans.settleToolAttempt(new PlanRepository.SettleToolAttempt(attempt.toolAttemptId(),
                    PlanModels.ToolAttemptState.UNKNOWN, null, null, clock.instant()));
        }
    }

    /**
     * 安全失败摘要使用固定原因生成 digest，不伪造 Tool 返回内容。
     */
    private void settleFailed(PlanModels.ToolAttempt attempt, String reason) {
        plans.settleToolAttempt(new PlanRepository.SettleToolAttempt(attempt.toolAttemptId(),
                PlanModels.ToolAttemptState.FAILED, PlanDigest.sha256(reason), null, clock.instant()));
    }

}

