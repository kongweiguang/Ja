// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.port.out;

import io.github.kongweiguang.ja.goal.domain.GoalModels.CriterionEvaluation;
import io.github.kongweiguang.ja.goal.domain.GoalModels.EvaluationVerdict;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** 独立 evaluator 出站端口；实现必须禁用 Tool 且不携带执行对话历史。 */
public interface GoalEvaluatorPort {
    /** 只发送冻结目标、计划、验收条件与脱敏证据。 */
    CompletionStage<Result> evaluate(Request request);

    /** evaluator 请求显式固定模型，不允许 adapter 隐藏切换 Provider。 */
    record Request(String goalId, String ownerThreadId, long goalDefinitionRevision,
                   String planRevisionId, String runId,
                   String providerId, String modelId, String objective, String canonicalPlanJson,
                   List<Criterion> criteria, List<EvidenceDigest> evidence) {
        /** 防御性复制 evidence，异步调用期间输入不可变化。 */
        public Request {
            criteria = List.copyOf(criteria);
            evidence = List.copyOf(evidence);
        }
    }

    /** criterion 描述来自冻结 revision，required 决定总体 verdict 的一致性检查。 */
    record Criterion(String criterionId, String description, boolean required) { }

    /** 证据只携带安全摘要和 digest，不传原始 Tool output。 */
    record EvidenceDigest(String criterionId, String sourceType, String sourceId,
                          String summary, String digest) { }

    /** 合法结果必须为闭集 verdict 和逐条件结论。 */
    record Result(EvaluationVerdict verdict, List<CriterionEvaluation> criteria, String summary) {
        /** 冻结逐条件结果，禁止 adapter 后续修改 verdict 依据。 */
        public Result { criteria = List.copyOf(criteria); }
    }
}
