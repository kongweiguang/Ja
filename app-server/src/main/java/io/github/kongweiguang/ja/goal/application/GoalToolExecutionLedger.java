// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.port.out.GoalToolExecutionPort;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** 把活动 Goal 当前步骤绑定到 conversation 已持久化的真实 Tool call。 */
public final class GoalToolExecutionLedger implements GoalToolExecutionPort {
    private final GoalRepository goals;
    private final long processGeneration;
    private final Consumer<String> committed;

    /** generation 使用现有进程 ledger，崩溃恢复不能用 PID 或墙钟推断所有权。 */
    public GoalToolExecutionLedger(GoalRepository goals, Clock clock, long processGeneration) {
        this(goals, clock, processGeneration, ignored -> { });
    }

    /** notifier 仅在 Tool 事务已把 Goal 原子收口为 ACHIEVED 后发布，不能提前暴露半提交状态。 */
    public GoalToolExecutionLedger(GoalRepository goals, Clock clock, long processGeneration,
                                   Consumer<String> committed) {
        this.goals = Objects.requireNonNull(goals, "goals");
        Objects.requireNonNull(clock, "clock");
        if (processGeneration < 1) throw new IllegalArgumentException("invalid process generation");
        this.processGeneration = processGeneration;
        this.committed = Objects.requireNonNull(committed, "committed");
    }

    /**
     * 只有内部执行 origin 才进入 Goal ledger；按 owner 猜测优先级会在 Goal 与 standalone Plan 并存时
     * 串账，普通 USER/CHILD_TASK Turn 即使同 Thread 有活动聚合也不能生成验收证据。
     */
    @Override
    public Optional<Attempt> prepare(Prepare request) {
        return switch (request.origin()) {
            case GOAL_CONTINUATION -> goals.findInternalTurnBinding(request.turnId())
                    .filter(binding -> "GOAL_CONTINUATION".equals(binding.origin()))
                    .flatMap(binding -> prepareGoal(request, binding));
            case PLAN_EXECUTION -> goals.findInternalTurnBinding(request.turnId())
                    .filter(binding -> "PLAN_EXECUTION".equals(binding.origin()))
                    .flatMap(binding -> preparePlan(request, binding));
            case USER, CHILD_TASK -> Optional.empty();
        };
    }

    /**
     * Interaction 创建前从不可变 Turn binding 读取身份；同时核对聚合 owner，防止错误 Thread 复用同一
     * turnId 的迟到请求。恢复与重启都走这条 SQLite 查询，不依赖进程内缓存。
     */
    @Override
    public Optional<ExecutionIdentity> executionIdentity(String threadId, String turnId, io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin origin) {
        return goals.findInternalTurnBinding(turnId)
                .filter(binding -> origin.name().equals(binding.origin()))
                .flatMap(binding -> {
                    if (origin == io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin.GOAL_CONTINUATION
                            && binding.goalId() != null) {
                        return goals.findGoal(binding.goalId())
                                .filter(goal -> goal.ownerThreadId().equals(threadId))
                                .map(ignored -> new ExecutionIdentity(binding.planRevisionId(), binding.runId(),
                                        binding.goalId()));
                    }
                    if (origin == io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin.PLAN_EXECUTION
                            && binding.planId() != null) {
                        try {
                            return Optional.of(goals.readPlanSnapshot(binding.planId()))
                                    .filter(snapshot -> snapshot.plan().ownerThreadId().equals(threadId))
                                    .map(ignored -> new ExecutionIdentity(binding.planRevisionId(), binding.runId(),
                                            null));
                        } catch (RuntimeException missing) {
                            return Optional.empty();
                        }
                    }
                    return Optional.empty();
                });
    }

    /** GOAL_CONTINUATION 必须同时匹配不可变 context、当前 Goal run/link 与仍持有的 fencing lease。 */
    private Optional<Attempt> prepareGoal(Prepare request, GoalRepository.InternalTurnBinding binding) {
        GoalModels.Goal goal = goals.findGoal(binding.goalId()).orElse(null);
        if (goal == null) return Optional.empty();
        if (!goal.ownerThreadId().equals(request.threadId())
                || goal.status() != GoalModels.GoalStatus.ACTIVE
                || goal.phase() != GoalModels.GoalPhase.WORKING || goal.activeRunId() == null
                || goal.recoveryRequired() || !goal.activeRunId().equals(binding.runId())
                || goal.goalDefinitionRevision() != binding.goalDefinitionRevision()) return Optional.empty();
        GoalModels.GoalSnapshot snapshot = goals.readSnapshot(goal.goalId());
        String stepId = snapshot.currentStepId();
        GoalModels.GoalPlanLink link = snapshot.planLink();
        if (!matchesPlanBinding(binding, link)) return Optional.empty();
        int attempt = 1;
        if (stepId != null && link != null) {
            GoalModels.PlanSnapshot plan = goals.readPlanSnapshot(link.planId());
            if (plan.currentRevision() == null
                    || !plan.currentRevision().planRevisionId().equals(binding.planRevisionId())
                    || !plan.currentRevision().planHash().equals(binding.planHash())) return Optional.empty();
            attempt = plan.stepExecutions().stream()
                    .filter(step -> step.stepId().equals(stepId)).findFirst()
                    .map(step -> Math.max(1, step.attempt())).orElse(1);
        }
        String attemptId = id("toolattempt_");
        goals.prepareToolAttempt(new GoalRepository.PrepareToolAttempt(new GoalModels.ToolAttempt(
                attemptId, goal.goalId(), link == null ? null : link.planId(), goal.goalDefinitionRevision(),
                goal.activeRunId(), link == null ? null : link.planRevisionId(), stepId,
                attempt, request.turnId(), request.callId(), processGeneration,
                request.sideEffect() != ToolSideEffect.READ_ONLY, GoalModels.ToolAttemptState.PREPARED,
                GoalDigest.sha256(request.toolName() + '\n' + request.arguments()), null,
                request.at(), null, null)));
        return Optional.of(new Attempt(attemptId));
    }

    /** PLAN_EXECUTION 直接读取 context 指定 Plan，旧 Turn 不能跟随 owner 的 replacement run。 */
    private Optional<Attempt> preparePlan(Prepare request, GoalRepository.InternalTurnBinding binding) {
        GoalModels.PlanSnapshot snapshot;
        try {
            snapshot = goals.readPlanSnapshot(binding.planId());
        } catch (RuntimeException missing) {
            return Optional.empty();
        }
        GoalModels.Plan plan = snapshot.plan();
        if (!plan.ownerThreadId().equals(request.threadId())
                || plan.status() != GoalModels.PlanStatus.EXECUTING
                || !Objects.equals(plan.activeRunId(), binding.runId())
                || !Objects.equals(plan.activePlanRevisionId(), binding.planRevisionId())
                || snapshot.currentRevision() == null
                || !snapshot.currentRevision().planHash().equals(binding.planHash())) return Optional.empty();
        GoalModels.StepExecution step = snapshot.stepExecutions().stream()
                .filter(item -> item.status() == GoalModels.StepStatus.RUNNING
                        || item.status() == GoalModels.StepStatus.READY)
                .findFirst().orElse(null);
        String attemptId = id("toolattempt_");
        goals.prepareToolAttempt(new GoalRepository.PrepareToolAttempt(new GoalModels.ToolAttempt(
                attemptId, null, plan.planId(), null, plan.activeRunId(), plan.activePlanRevisionId(),
                // 独立验收 NOT_MET 后步骤可能均为成功；修正调用仍必须入原 Run 账本，不能因无活动步骤漏记副作用。
                step == null ? null : step.stepId(), step == null ? 1 : Math.max(1, step.attempt()),
                request.turnId(), request.callId(), processGeneration,
                request.sideEffect() != ToolSideEffect.READ_ONLY, GoalModels.ToolAttemptState.PREPARED,
                GoalDigest.sha256(request.toolName() + '\n' + request.arguments()), null,
                request.at(), null, null)));
        return Optional.of(new Attempt(attemptId));
    }

    /** Goal-only binding 要求三个 Plan 字段全空；linked binding 必须逐项等于冻结 link。 */
    private static boolean matchesPlanBinding(GoalRepository.InternalTurnBinding binding,
                                              GoalModels.GoalPlanLink link) {
        if (link == null) {
            return binding.planId() == null && binding.planRevisionId() == null && binding.planHash() == null;
        }
        return link.planId().equals(binding.planId())
                && link.planRevisionId().equals(binding.planRevisionId())
                && link.planHash().equals(binding.planHash());
    }

    /** Goal STARTED 是外部调用的 write-ahead fence，失败必须阻止 Tool 执行。 */
    @Override
    public void start(Attempt attempt, java.time.Instant at) {
        goals.startToolAttempt(attempt.attemptId(), at);
    }

    /**
     * 终态只结算真实 Tool ledger；若该事务恰好关闭 evaluator 竞态并完成 Goal，提交后再发布最新事件。
     */
    @Override
    public void settle(Attempt attempt, Settlement settlement) {
        String resultDigest = GoalDigest.sha256(settlement.outcome().name() + '\n'
                + Objects.toString(settlement.errorCode(), "") + '\n' + settlement.content());
        GoalModels.ToolAttempt settled = goals.settleToolAttempt(new GoalRepository.SettleToolAttempt(
                attempt.attemptId(),
                settlement.outcome() == ToolOutcome.SUCCEEDED ? GoalModels.ToolAttemptState.SUCCEEDED
                        : GoalModels.ToolAttemptState.FAILED,
                resultDigest, null, settlement.at()));
        if (settled.goalId() != null) {
            goals.findGoal(settled.goalId()).filter(goal -> goal.status() == GoalModels.GoalStatus.ACHIEVED)
                    .ifPresent(goal -> committed.accept(goal.goalId()));
        }
    }

    /** 随机 opaque identity 不承担排序语义。 */
    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
