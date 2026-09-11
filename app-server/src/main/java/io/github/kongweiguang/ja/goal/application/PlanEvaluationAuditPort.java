// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestUsage;
import io.github.kongweiguang.ja.goal.domain.GoalModels;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Plan 无 Tool evaluator 的最小持久化边界；实现应把 intent 与 usage 接入同一审计账本。
 *
 * <p>该端口刻意不依赖 GoalRepository，避免验收 adapter 在等待 Provider 时持有业务事务。
 * 主任务应在 {@code recordIntent} 中登记可恢复的请求身份，在 {@code recordUsage} 中登记
 * KNOWN 或 UNKNOWN 计量及成功时的结构化结论；任一持久化失败都必须阻止 evaluator 报告 MET。</p>
 */
public interface PlanEvaluationAuditPort {
    /** Provider 发起前登记冻结的 Plan/revision/run、请求身份和非敏感 Profile。 */
    void recordIntent(Intent intent);

    /** Provider 终态登记真实计量或 UNKNOWN，禁止把缺失计量伪装为零。 */
    void recordUsage(Usage usage);

    /** 查询同一 plan/revision/run 的历史 intent，UNKNOWN 或已完成请求不得重复付费调用。 */
    Optional<Prior> find(String requestId);

    /** evaluator 调用前的不可变 intent；不包含端点、凭据或用户正文。 */
    record Intent(String requestId, String planId, String planRevisionId, String runId,
                  String ownerThreadId, ProviderRequestProfile profile, String inputDigest,
                  Instant startedAt) {
        /** 请求 identity 必须可跨重启去重，digest 必须绑定完整冻结输入。 */
        public Intent {
            requireRequestId(requestId);
            requireId(planId, "planId");
            requireId(planRevisionId, "planRevisionId");
            requireId(runId, "runId");
            requireId(ownerThreadId, "ownerThreadId");
            Objects.requireNonNull(profile, "profile");
            requireDigest(inputDigest, "inputDigest");
            Objects.requireNonNull(startedAt, "startedAt");
        }
    }

    /** evaluator Provider 终态的持久用量与结构化结论；UNKNOWN 不得伪造结论或 token。 */
    record Usage(String requestId, String planId, String planRevisionId, String runId,
                 ProviderRequestUsage providerUsage, Outcome outcome, Instant completedAt,
                 EvaluationResult evaluation) {
        /** 保持旧调用形状可读；非成功终态不携带结论。 */
        public Usage(String requestId, String planId, String planRevisionId, String runId,
                     ProviderRequestUsage providerUsage, Outcome outcome, Instant completedAt) {
            this(requestId, planId, planRevisionId, runId, providerUsage, outcome, completedAt, null);
        }

        /** usage 与 intent 共享 identity，审计层才能阻止迟到结果污染其它 revision。 */
        public Usage {
            requireRequestId(requestId);
            requireId(planId, "planId");
            requireId(planRevisionId, "planRevisionId");
            requireId(runId, "runId");
            Objects.requireNonNull(providerUsage, "providerUsage");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(completedAt, "completedAt");
            if (!requestId.equals(providerUsage.requestId())) {
                throw new IllegalArgumentException("usage request identity mismatch");
            }
            if ((outcome == Outcome.SUCCEEDED) != (evaluation != null)) {
                throw new IllegalArgumentException("successful evaluator usage requires a result");
            }
        }
    }

    /** Provider 调用是否产生了可接受的结构化 evaluator 结果。 */
    enum Outcome {
        /** intent 已登记，Provider 仍未结算。 */
        RUNNING,
        /** Provider 返回并已持久化结构化 evaluator 结论。 */
        SUCCEEDED,
        /** Provider 明确失败且没有可恢复结论。 */
        FAILED,
        /** 计量或响应边界不确定，禁止自动重试。 */
        UNKNOWN
    }

    /** 仅返回重试门所需的结果状态，不把 Provider 正文重新暴露给 evaluator。 */
    record Prior(String requestId, Outcome outcome, EvaluationResult evaluation) {
        /** 保持仅检查状态的旧构造形状；历史 UNKNOWN/运行中没有结论可恢复。 */
        public Prior(String requestId, Outcome outcome) {
            this(requestId, outcome, null);
        }

        /** 历史状态必须绑定同一稳定 request identity。 */
        public Prior {
            requireRequestId(requestId);
            Objects.requireNonNull(outcome, "outcome");
            if ((outcome == Outcome.SUCCEEDED) != (evaluation != null)) {
                throw new IllegalArgumentException("successful prior requires a result");
            }
        }
    }

    /** 可跨进程恢复的 evaluator 结论；criteria 是结构化事实而非 summary 文本解析结果。 */
    record EvaluationResult(GoalModels.EvaluationVerdict verdict,
                            List<GoalModels.CriterionEvaluation> criteria, String summary) {
        /** 冻结结论集合，并拒绝空摘要或非法 criterion，避免损坏审计事实进入恢复路径。 */
        public EvaluationResult {
            Objects.requireNonNull(verdict, "verdict");
            criteria = List.copyOf(Objects.requireNonNull(criteria, "criteria"));
            for (GoalModels.CriterionEvaluation criterion : criteria) {
                Objects.requireNonNull(criterion, "criterion");
                GoalModels.requireId(criterion.criterionId(), "criterionId");
                Objects.requireNonNull(criterion.verdict(), "criterion verdict");
                if (criterion.reason() == null || criterion.reason().isBlank()
                        || criterion.reason().length() > 1_000) {
                    throw new IllegalArgumentException("invalid criterion reason");
                }
            }
            if (summary == null || summary.isBlank() || summary.length() > 4_000) {
                throw new IllegalArgumentException("invalid evaluator summary");
            }
        }
    }

    /** 复用仓库公开的 request identity 合同，避免另造宽松格式。 */
    private static void requireRequestId(String value) {
        if (value == null || !value.startsWith("request_") || value.length() > 103
                || !value.substring("request_".length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid requestId");
        }
    }

    /** Plan identity 只允许稳定键，不能混入 prompt、路径或诊断正文。 */
    private static void requireId(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 160
                || !value.matches("[A-Za-z0-9._:-]+")) throw new IllegalArgumentException("invalid " + field);
    }

    /** 输入 digest 采用 SHA-256 小写闭集，防止审计记录指向未冻结的输入。 */
    private static void requireDigest(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid " + field);
    }
}
