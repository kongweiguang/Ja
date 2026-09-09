// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.goal.domain.GoalModels.Plan;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanStatus;
import io.github.kongweiguang.ja.goal.port.in.PlanExecutionEventSink;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** 单次启动 PLAN_EXECUTION hidden Turn；独立 Plan 不获得 Goal 的永久自动续跑语义。 */
public final class PlanExecutionCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(PlanExecutionCoordinator.class);
    private final GoalRepository plans;
    private final PlanExecutionTurnPort turns;
    private final Clock clock;

    /** coordinator 只持有权威 repository 与既有 Turn adapter port。 */
    public PlanExecutionCoordinator(GoalRepository plans, PlanExecutionTurnPort turns, Clock clock) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.clock = Objects.requireNonNull(clock, "clock");
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
        CompletionStage<Void> started;
        try {
            started = turns.start(request, events);
        } catch (RuntimeException failure) {
            settleIncomplete(request);
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
        return started.whenComplete((ignored, failure) -> {
            if (failure != null) {
                LOG.warn("Standalone Plan Turn failed failureType={} rootType={}",
                        failure.getClass().getSimpleName(), rootType(failure));
            }
            settleIncomplete(request);
        });
    }

    /** 诊断只记录异常类型，不记录 Plan identity、路径、Provider 内容或用户数据。 */
    private static String rootType(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getClass().getSimpleName();
    }

    /** callback 重新读取 Plan revision，迟到的旧 Turn 不能覆盖新 run。 */
    private void settleIncomplete(ExecutionRequest request) {
        Plan current = plans.readPlanSnapshot(request.planId()).plan();
        if (current.status() != PlanStatus.EXECUTING
                || !Objects.equals(current.activeRunId(), request.runId())) return;
        plans.settlePlanExecution(new GoalRepository.SettlePlanExecution(current.planId(), current.revision(),
                request.runId(), id("evt_"), "plan-turn:" + request.turnId(), clock.instant()));
    }

    /** 隐藏 Turn adapter 不得创建 USER message 或借用 Goal continuation lease，独立 Plan 只启动一次。 */
    public interface PlanExecutionTurnPort {
        /** 只为已持久化 Run 启动一个 PLAN_EXECUTION Turn，adapter 必须在 admission 前重验 identity。 */
        CompletionStage<Void> start(ExecutionRequest request, PlanExecutionEventSink events);
    }

    /** 请求冻结已批准 revision、Run 与 Turn identity，防止异步启动漂移到后来编辑的 Plan。 */
    public record ExecutionRequest(String planId, String ownerThreadId, String planRevisionId,
                                   String runId, String turnId) { }

    /** 随机 identity 仅用于关联持久事实，不承载时间或执行顺序语义。 */
    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
