// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.in.GoalUseCase;
import io.github.kongweiguang.ja.goal.port.out.GoalEvaluatorPort;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** 监听已提交 VERIFYING 投影，以 evaluator intent CAS 保证单次独立请求。 */
public final class GoalEvaluationDispatcher implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(GoalEvaluationDispatcher.class);
    private final GoalRepository goals;
    private final GoalEvaluator evaluator;
    private final AutoCloseable subscription;
    private final java.util.concurrent.ConcurrentHashMap<String, CancellationSource> active =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 事件只负责唤醒；真正输入始终从同一 Goal Repository 重新组装。 */
    public GoalEvaluationDispatcher(GoalUseCase useCase, GoalRepository goals, GoalEvaluator evaluator) {
        this.goals = Objects.requireNonNull(goals, "goals");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.subscription = Objects.requireNonNull(useCase, "useCase").subscribe(event -> {
            try {
                GoalModels.Goal current = event.snapshot().goal();
                if (current.status() != GoalModels.GoalStatus.ACTIVE
                        || current.phase() != GoalModels.GoalPhase.VERIFYING) {
                    CancellationSource running = active.remove(current.goalId());
                    if (running != null) running.cancel("goal_stopped");
                }
                dispatch(event.snapshot());
            } catch (RuntimeException failure) {
                LOG.error("Goal evaluator dispatch failed after committed event", failure);
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    /** 只有 ACTIVE/VERIFYING 且成功领取 REQUESTED intent 时才可能调用 Provider。 */
    private void dispatch(GoalModels.GoalSnapshot snapshot) {
        GoalModels.Goal goal = snapshot.goal();
        if (goal.status() != GoalModels.GoalStatus.ACTIVE
                || goal.phase() != GoalModels.GoalPhase.VERIFYING || goal.activeRunId() == null) return;
        goals.claimRequestedEvaluation(goal.goalId(), goal.activeRunId()).ifPresent(intent -> {
            GoalModels.GoalPlanLink link = snapshot.planLink();
            GoalModels.PlanRevision revision = null;
            if (link != null) revision = goals.readPlanSnapshot(link.planId()).currentRevision();
            if (goal.goalDefinitionRevision() != intent.goalDefinitionRevision()
                    || !Objects.equals(link == null ? null : link.planRevisionId(), intent.planRevisionId())
                    || (link != null && (revision == null
                    || !revision.planRevisionId().equals(link.planRevisionId())
                    || !revision.planHash().equals(link.planHash())))) {
                evaluator.rejectRequested(intent.evaluationId(), goal.revision(), goal.goalId(),
                        "evaluation_rejected:" + intent.evaluationId());
                return;
            }
            java.util.ArrayList<GoalEvaluatorPort.Criterion> criteria = new java.util.ArrayList<>();
            snapshot.definition().acceptanceCriteria().forEach(item -> criteria.add(
                    new GoalEvaluatorPort.Criterion(item.criterionId(), item.description(), item.required())));
            if (revision != null) revision.definition().acceptanceCriteria().forEach(item -> criteria.add(
                    new GoalEvaluatorPort.Criterion(item.criterionId(), item.description(), item.required())));
            CancellationSource cancellation = new CancellationSource();
            CancellationSource previous = active.put(goal.goalId(), cancellation);
            if (previous != null) previous.cancel("goal_replaced");
            GoalEvaluatorPort.Request request = new GoalEvaluatorPort.Request(goal.goalId(),
                    goal.ownerThreadId(), goal.goalDefinitionRevision(), intent.planRevisionId(), intent.runId(),
                    intent.providerId(), intent.modelId(), goal.objective(),
                    revision == null ? null : revision.canonicalJson(), criteria,
                    goals.listEvidence(goal.goalId(), intent.runId(), 512).stream().map(item ->
                            new GoalEvaluatorPort.EvidenceDigest(item.criterionId(),
                                    item.sourceType().name(), item.sourceId(), item.summary(), item.digest()))
                            .toList(), cancellation);
            GoalModels.Goal latest = goals.readSnapshot(goal.goalId()).goal();
            if (latest.status() != GoalModels.GoalStatus.ACTIVE
                    || latest.phase() != GoalModels.GoalPhase.VERIFYING) {
                active.remove(goal.goalId(), cancellation);
                cancellation.cancel("goal_stopped");
                return;
            }
            evaluator.evaluateRequested(intent.evaluationId(), goal.revision(), request,
                    "evaluation_complete:" + intent.evaluationId())
                    .whenComplete((ignored, failure) -> active.remove(goal.goalId(), cancellation))
                    .exceptionally(failure -> {
                        LOG.error("Goal evaluator settlement failed", failure);
                        return null;
                    });
        });
    }

    /** 解除订阅后不再领取新 intent；已经发出的 Provider 请求由其 stage 自行结算。 */
    @Override public void close() {
        active.forEach((goalId, cancellation) -> cancellation.cancel("goal_dispatcher_closed"));
        active.clear();
        try {
            subscription.close();
        } catch (Exception failure) {
            throw new IllegalStateException("Goal evaluator subscription close failed", failure);
        }
    }
}
