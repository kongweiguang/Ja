// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.service;

import io.github.kongweiguang.ja.plan.application.gateway.PlanEvaluatorPort;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.domain.PlanPolicy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 基于已提交事实执行本地完成门判断；不调用 Provider 或 Tool，供协调器在无模型验收时使用。
 */
public final class DeterministicPlanEvaluator implements PlanEvaluatorPort {
    /**
     * 取消检查必须先于快照读取，避免暂停后的调用因为坏快照泄漏新的失败语义。
     */
    @Override
    public CompletionStage<Evaluation> evaluate(PlanModels.PlanSnapshot snapshot,
                                                List<PlanModels.Evidence> evidence,
                                                EvaluationContext context) {
        context.cancellation().throwIfCancellationRequested();
        if (snapshot == null || evidence == null) {
            throw new IllegalArgumentException("Plan evaluator facts are required");
        }
        PlanModels.Plan plan = snapshot.plan();
        Map<String, PlanModels.StepStatus> statuses = new HashMap<>();
        for (PlanModels.StepExecution execution : snapshot.stepExecutions()) {
            statuses.put(execution.stepId(), execution.status());
        }
        List<PlanModels.StepStatus> orderedStatuses = snapshot.currentRevision().definition().steps().stream()
                .map(step -> statuses.getOrDefault(step.stepId(), PlanModels.StepStatus.PENDING))
                .toList();
        Set<String> criteria = new HashSet<>();
        for (PlanModels.Evidence item : evidence) {
            if (plan.planId().equals(item.planId())
                    && plan.activeRunId().equals(item.runId())
                    && plan.activePlanRevisionId().equals(item.planRevisionId())
                    && item.criterionId() != null) {
                criteria.add(item.criterionId());
            }
        }
        PlanModels.PlanDefinition definition = snapshot.currentRevision().definition();
        PlanModels.CompletionSnapshot completion = new PlanModels.CompletionSnapshot(
                definition.steps(), definition.acceptanceCriteria(), orderedStatuses, List.copyOf(criteria),
                false, false, false, false, PlanModels.EvaluationVerdict.MET);
        PlanModels.EvaluationVerdict verdict = PlanPolicy.isAchieved(completion)
                ? PlanModels.EvaluationVerdict.MET : PlanModels.EvaluationVerdict.NOT_MET;
        return CompletableFuture.completedFuture(new Evaluation(verdict,
                verdict == PlanModels.EvaluationVerdict.MET ? "Plan facts satisfy completion gates"
                        : "Plan facts do not satisfy completion gates"));
    }
}
