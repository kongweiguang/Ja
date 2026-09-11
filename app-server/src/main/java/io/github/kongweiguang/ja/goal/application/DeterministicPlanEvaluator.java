// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.domain.GoalModels.Evidence;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanSnapshot;
import io.github.kongweiguang.ja.goal.domain.GoalModels.StepStatus;
import io.github.kongweiguang.ja.goal.port.out.PlanEvaluatorPort;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 默认 Plan evaluator 只检查持久化事实，作为无 Tool 完成门并可被生产组合替换。 */
public final class DeterministicPlanEvaluator implements PlanEvaluatorPort {
    /** 不调用模型或文件系统；证据和步骤必须来自当前冻结 revision/run。 */
    @Override
    public CompletionStage<Evaluation> evaluate(PlanSnapshot snapshot, List<Evidence> evidence,
                                                PlanEvaluatorPort.EvaluationContext context) {
        context.cancellation().throwIfCancellationRequested();
        if (snapshot == null || snapshot.currentRevision() == null) {
            return CompletableFuture.completedFuture(new Evaluation(GoalModels.EvaluationVerdict.INCONCLUSIVE,
                    "Plan revision is unavailable"));
        }
        Set<String> evidenced = evidence.stream()
                .filter(item -> snapshot.plan().activeRunId().equals(item.runId())
                        && snapshot.currentRevision().planRevisionId().equals(item.planRevisionId()))
                .map(Evidence::criterionId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        boolean steps = snapshot.stepExecutions().stream()
                .filter(step -> snapshot.currentRevision().definition().steps().stream()
                        .anyMatch(definition -> definition.stepId().equals(step.stepId()) && definition.required()))
                .allMatch(step -> step.status() == StepStatus.SUCCEEDED);
        boolean criteria = snapshot.currentRevision().definition().acceptanceCriteria().stream()
                .filter(GoalModels.AcceptanceCriterion::required)
                .allMatch(item -> evidenced.contains(item.criterionId()));
        return CompletableFuture.completedFuture(new Evaluation(steps && criteria ? GoalModels.EvaluationVerdict.MET
                        : GoalModels.EvaluationVerdict.NOT_MET,
                steps && criteria ? "所有必要步骤和当前验收证据均已满足"
                        : "必要步骤或验收证据尚未满足"));
    }
}
