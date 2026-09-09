// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.domain;

import io.github.kongweiguang.ja.goal.domain.GoalModels.AcceptanceCriterion;
import io.github.kongweiguang.ja.goal.domain.GoalModels.CompletionSnapshot;
import io.github.kongweiguang.ja.goal.domain.GoalModels.EvaluationVerdict;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanDefinition;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanStep;
import io.github.kongweiguang.ja.goal.domain.GoalModels.StepStatus;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 计划 DAG、步骤推进与完成门的纯策略；不读取数据库或执行 Tool。 */
public final class PlanPolicy {
    /** 提案前验证稳定 ID、必要项与无环依赖，拒绝把不可执行计划冻结成 revision。 */
    public static void validate(PlanDefinition definition) {
        Map<String, PlanStep> steps = new HashMap<>();
        for (PlanStep step : definition.steps()) {
            if (steps.put(step.stepId(), step) != null) throw invalid("duplicate step id");
        }
        Set<String> criteria = new HashSet<>();
        boolean requiredCriterion = false;
        for (AcceptanceCriterion criterion : definition.acceptanceCriteria()) {
            if (!criteria.add(criterion.criterionId())) throw invalid("duplicate criterion id");
            requiredCriterion |= criterion.required();
        }
        if (!requiredCriterion) throw invalid("at least one acceptance criterion must be required");
        for (PlanStep step : definition.steps()) {
            for (String dependency : step.dependsOn()) {
                if (dependency.equals(step.stepId()) || !steps.containsKey(dependency)) {
                    throw invalid("step dependency is invalid");
                }
            }
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String stepId : steps.keySet()) visit(stepId, steps, visiting, visited);
    }

    /** 只有依赖都成功或明确跳过的 pending step 才可 ready，避免 Agent 自行越过 DAG。 */
    public static boolean canBecomeReady(PlanStep step, Map<String, StepStatus> statuses) {
        return step.dependsOn().stream().allMatch(dependency -> {
            StepStatus status = statuses.get(dependency);
            return status == StepStatus.SUCCEEDED || status == StepStatus.SKIPPED;
        });
    }

    /** 状态转换是闭集；重试失败步骤必须先回到 READY，不能直接伪造 RUNNING 或成功。 */
    public static boolean mayTransition(StepStatus from, StepStatus to) {
        if (from == to) return true;
        return switch (from) {
            case PENDING, BLOCKED, FAILED -> to == StepStatus.READY || to == StepStatus.SKIPPED;
            case READY -> to == StepStatus.RUNNING || to == StepStatus.BLOCKED || to == StepStatus.SKIPPED;
            case RUNNING -> to == StepStatus.SUCCEEDED || to == StepStatus.FAILED || to == StepStatus.BLOCKED;
            case SUCCEEDED, SKIPPED -> false;
        };
    }

    /** 完成必须同时通过必要步骤、当前证据、无未决工作与独立 evaluator，缺一不可。 */
    public static boolean isAchieved(CompletionSnapshot snapshot) {
        if (snapshot.steps().size() != snapshot.stepStatuses().size()) return false;
        for (int index = 0; index < snapshot.steps().size(); index++) {
            if (snapshot.steps().get(index).required()
                    && snapshot.stepStatuses().get(index) != StepStatus.SUCCEEDED) return false;
        }
        Set<String> evidenced = Set.copyOf(snapshot.evidencedCriteria());
        boolean criteriaMet = snapshot.criteria().stream()
                .filter(AcceptanceCriterion::required)
                .allMatch(criterion -> evidenced.contains(criterion.criterionId()));
        return criteriaMet && !snapshot.unresolvedInput() && !snapshot.unresolvedApproval()
                && !snapshot.unresolvedTool() && !snapshot.unresolvedTask()
                && snapshot.evaluatorVerdict() == EvaluationVerdict.MET;
    }

    /** 深度优先只读取已冻结的小型 DAG，visiting 命中即为循环。 */
    private static void visit(String id, Map<String, PlanStep> steps,
                              Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) throw invalid("plan step graph contains a cycle");
        for (String dependency : steps.get(id).dependsOn()) visit(dependency, steps, visiting, visited);
        visiting.remove(id);
        visited.add(id);
    }

    /** 策略错误使用稳定、无 payload 的消息，RPC adapter 再映射 PLAN_INVALID。 */
    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    /** 纯策略不进入容器。 */
    private PlanPolicy() { }
}
