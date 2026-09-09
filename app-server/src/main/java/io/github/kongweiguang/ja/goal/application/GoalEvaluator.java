// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.goal.port.out.GoalEvaluatorPort;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** evaluator application service 在持久 intent 后调用 Provider，并把非法/失败结果收口为 attention。 */
public final class GoalEvaluator {
    private static final Logger LOG = LoggerFactory.getLogger(GoalEvaluator.class);
    private final GoalRepository goals;
    private final GoalEvaluatorPort evaluator;
    private final Clock clock;
    private final Consumer<String> committed;
    private final long processGeneration;

    /** Provider port 与 Goal repository 必须来自同一 composition generation。 */
    public GoalEvaluator(GoalRepository goals, GoalEvaluatorPort evaluator, Clock clock) {
        this(goals, evaluator, clock, ignored -> { }, 1);
    }

    /** notifier 只在数据库结算成功后发布最新 Goal 事件。 */
    public GoalEvaluator(GoalRepository goals, GoalEvaluatorPort evaluator, Clock clock,
                         Consumer<String> committed) {
        this(goals, evaluator, clock, committed, 1);
    }

    /** generation 只标识已持久 evaluator owner，Provider 请求仍由短租约管理。 */
    public GoalEvaluator(GoalRepository goals, GoalEvaluatorPort evaluator, Clock clock,
                         Consumer<String> committed, long processGeneration) {
        this.goals = Objects.requireNonNull(goals, "goals");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.committed = Objects.requireNonNull(committed, "committed");
        if (processGeneration < 1) throw new IllegalArgumentException("invalid process generation");
        this.processGeneration = processGeneration;
    }

    /** 请求先落 REQUESTED，再异步调用；失败不切模型、不重试付费调用。 */
    public CompletionStage<GoalRepository.CompleteEvaluation> evaluate(Evaluate command) {
        String evaluationId = id("evaluation_");
        goals.requestEvaluation(new GoalRepository.RequestEvaluation(command.request().goalId(),
                command.expectedGoalRevision(), evaluationId, command.request().runId(),
                command.request().planRevisionId(), processGeneration,
                java.util.List.of(), id("evt_"), command.requestIdempotencyKey(), clock.instant()));
        return evaluator.evaluate(command.request()).handle((result, failure) -> {
            long completionRevision = command.expectedGoalRevision() + 1;
            if (failure != null || result == null || !valid(command.request(), result)) {
                return new GoalRepository.CompleteEvaluation(command.request().goalId(), completionRevision,
                        evaluationId, null, null, null, "EVALUATOR_INVALID_OUTPUT", id("evt_"),
                        command.completionIdempotencyKey(), clock.instant());
            }
            return new GoalRepository.CompleteEvaluation(command.request().goalId(), completionRevision,
                    evaluationId, result.verdict(), criteriaJson(result), result.summary(), null, id("evt_"),
                    command.completionIdempotencyKey(), clock.instant());
        }).thenApply(completion -> {
            goals.completeEvaluation(completion);
            return completion;
        });
    }

    /** 已领取 intent 不再重复插入 evaluator；完成仍以当前 Goal revision CAS。 */
    public CompletionStage<GoalRepository.CompleteEvaluation> evaluateRequested(
            String evaluationId, long expectedGoalRevision, GoalEvaluatorPort.Request request,
            String completionIdempotencyKey) {
        CompletionStage<GoalEvaluatorPort.Result> stage;
        try {
            stage = evaluator.evaluate(request);
        } catch (RuntimeException failure) {
            stage = java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
        return stage.handle((result, failure) -> completion(
                        evaluationId, expectedGoalRevision, request, result, failure, completionIdempotencyKey))
                .thenApply(completion -> {
                    var settled = goals.completeEvaluation(completion);
                    committed.accept(request.goalId());
                    if (completion.verdict() == io.github.kongweiguang.ja.goal.domain.GoalModels.EvaluationVerdict.MET) {
                        achieveOrPause(settled, evaluationId);
                    }
                    return completion;
                });
    }

    /** 已领取但无法安全组装请求的 intent 仍要结算，不能永久占用 run 的唯一活跃索引。 */
    public void rejectRequested(String evaluationId, long expectedGoalRevision, String goalId,
                                String completionIdempotencyKey) {
        goals.completeEvaluation(new GoalRepository.CompleteEvaluation(goalId, expectedGoalRevision,
                evaluationId, null, null, null, "EVALUATOR_INVALID_OUTPUT", id("evt_"),
                completionIdempotencyKey, clock.instant()));
        committed.accept(goalId);
    }

    /**
     * MET 后尝试通过仓储完成门；当前 evaluation Tool 可能尚未写入终态，因此证据不完整只表示
     * 合法竞态窗口，保持 ACTIVE/WORKING 并由 Tool settlement 在同一事务内再次收口。
     */
    private void achieveOrPause(io.github.kongweiguang.ja.goal.domain.GoalModels.Goal settled,
                                String evaluationId) {
        try {
            goals.transition(new GoalRepository.Transition(settled.goalId(), settled.revision(),
                    io.github.kongweiguang.ja.goal.domain.GoalModels.GoalStatus.ACHIEVED,
                    io.github.kongweiguang.ja.goal.domain.GoalModels.GoalPhase.ACHIEVED, false,
                    id("evt_"), "evaluation_achieve:" + evaluationId, clock.instant()));
        } catch (io.github.kongweiguang.ja.goal.port.out.GoalRepositoryException failure) {
            if (failure.code() != io.github.kongweiguang.ja.goal.port.out.GoalRepositoryException.Code.GOAL_EVIDENCE_INCOMPLETE) {
                throw failure;
            }
        }
        committed.accept(settled.goalId());
    }

    /** 严格校验逐条件闭集；部分 criteria 或自相矛盾 verdict 一律按非法输出收口。 */
    private GoalRepository.CompleteEvaluation completion(String evaluationId, long expectedGoalRevision,
            GoalEvaluatorPort.Request request, GoalEvaluatorPort.Result result, Throwable failure,
            String idempotencyKey) {
        if (failure != null || !valid(request, result)) {
            if (failure != null) {
                LOG.warn("Goal evaluator output rejected failureType={} rootType={}",
                        failure.getClass().getSimpleName(), rootType(failure));
            } else {
                LOG.warn("Goal evaluator output rejected by structural validation");
            }
            return new GoalRepository.CompleteEvaluation(request.goalId(), expectedGoalRevision, evaluationId,
                    null, null, null, "EVALUATOR_INVALID_OUTPUT", id("evt_"), idempotencyKey, clock.instant());
        }
        return new GoalRepository.CompleteEvaluation(request.goalId(), expectedGoalRevision, evaluationId,
                result.verdict(), criteriaJson(result), result.summary(), null, id("evt_"), idempotencyKey,
                clock.instant());
    }

    /** 诊断只保留异常类型而不记录 message、响应正文、Goal identity 或 Provider payload。 */
    private static String rootType(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getClass().getSimpleName();
    }

    /** evaluator 必须逐项覆盖冻结 criterion，且总体 MET 只能由全部 required MET 得出。 */
    private static boolean valid(GoalEvaluatorPort.Request request, GoalEvaluatorPort.Result result) {
        if (result == null || result.summary() == null || result.summary().isBlank()
                || result.criteria().size() != request.criteria().size()) return false;
        java.util.Map<String, io.github.kongweiguang.ja.goal.domain.GoalModels.EvaluationVerdict> verdicts =
                new java.util.HashMap<>();
        for (var item : result.criteria()) {
            if (item == null || item.reason() == null || item.reason().isBlank()
                    || verdicts.putIfAbsent(item.criterionId(), item.verdict()) != null) return false;
        }
        if (!verdicts.keySet().equals(request.criteria().stream()
                .map(GoalEvaluatorPort.Criterion::criterionId).collect(java.util.stream.Collectors.toSet()))) {
            return false;
        }
        boolean allRequiredMet = request.criteria().stream().filter(GoalEvaluatorPort.Criterion::required)
                .allMatch(item -> verdicts.get(item.criterionId())
                        == io.github.kongweiguang.ja.goal.domain.GoalModels.EvaluationVerdict.MET);
        return result.verdict() != io.github.kongweiguang.ja.goal.domain.GoalModels.EvaluationVerdict.MET
                || allRequiredMet;
    }

    /** 结果只编码 evaluator 结构化 fields，避免把异常或执行历史写入 SQLite。 */
    private static String criteriaJson(GoalEvaluatorPort.Result result) {
        StringBuilder value = new StringBuilder("[");
        for (GoalModelsCriterion criterion : result.criteria().stream().map(item ->
                new GoalModelsCriterion(item.criterionId(), item.verdict().name(), item.reason())).toList()) {
            if (value.length() > 1) value.append(',');
            value.append("{\"criterionId\":\"").append(escape(criterion.id()))
                    .append("\",\"verdict\":\"").append(criterion.verdict())
                    .append("\",\"reason\":\"").append(escape(criterion.reason())).append("\"}");
        }
        return value.append(']').toString();
    }

    /** JSON 字符串最小转义覆盖 evaluator 可见文本，不允许控制字符直写。 */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /** identity 随请求生成，持久幂等键负责公开重试。 */
    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    /** evaluator orchestration 输入包含 intent 与 settlement 两个幂等键。 */
    public record Evaluate(long expectedGoalRevision, GoalEvaluatorPort.Request request,
                           String requestIdempotencyKey, String completionIdempotencyKey) { }

    /** 小型内部值用于安全编码，不泄漏 evaluator adapter 类型。 */
    private record GoalModelsCriterion(String id, String verdict, String reason) { }
}
