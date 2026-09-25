// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.port.out;

import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanSnapshot;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Plan 的独立无 Tool 验收端口；实现只能消费已提交的快照，不能执行项目或外部副作用。 */
public interface PlanEvaluatorPort {
    /** 对冻结 revision 的步骤和证据作纯判断；调用方持有显式取消权。 */
    CompletionStage<Evaluation> evaluate(PlanSnapshot snapshot, List<GoalModels.Evidence> evidence,
                                         EvaluationContext context);

    /** 验收与 Plan Run 共享取消源，不继承累计时长预算。 */
    record EvaluationContext(CancellationToken cancellation) {
        /** 取消源始终由执行协调器提供。 */
        public EvaluationContext {
            Objects.requireNonNull(cancellation, "cancellation");
        }
    }

    /** 结构化结果供仓储在同一状态事务中结算，禁止用自然语言推导完成。 */
    record Evaluation(GoalModels.EvaluationVerdict verdict, String summary) {
        /** evaluator 输出在跨线程/跨 RPC 前冻结，避免结算时观察到可变集合或空摘要。 */
        public Evaluation {
            if (verdict == null) throw new IllegalArgumentException("verdict is required");
            if (summary == null || summary.isBlank() || summary.length() > 4000) {
                throw new IllegalArgumentException("invalid evaluator summary");
            }
        }
    }
}
