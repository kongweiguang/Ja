// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.gateway;

import io.github.kongweiguang.ja.conversation.domain.model.ProviderRequestProfile;
import io.github.kongweiguang.ja.conversation.domain.model.ProviderRequestUsage;
import io.github.kongweiguang.ja.plan.domain.PlanModels;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Plan 无 Tool evaluator 的最小持久化边界；实现应把 intent 与 usage 接入同一审计账本。
 *
 * <p>该端口刻意不依赖 PlanRepository，避免验收 adapter 在等待 Provider 时持有业务事务。
 * 主任务应在 {@code recordIntent} 中登记可恢复的请求身份，在 {@code recordUsage} 中登记
 * KNOWN 或 UNKNOWN 计量及成功时的结构化结论；任一持久化失败都必须阻止 evaluator 报告 MET。</p>
 */
public interface PlanEvaluationAuditPort {
    /**
     * 复用仓库公开的 request identity 合同，避免另造宽松格式。
     */
    private static void requireEvaluationId(String value) {
        if (value == null || !value.startsWith("evaluation_") || value.length() > 107
            || !value.substring("evaluation_".length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid evaluationId");
        }
    }

    /**
     * 模型请求身份独立于 Evaluation 聚合，避免重试审计与 Provider 计量共用一把主键。
     */
    private static void requireModelRequestId(String value) {
        if (value == null || !value.startsWith("modelreq_") || value.length() > 104
            || !value.substring("modelreq_".length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid modelRequestId");
        }
    }

    /**
     * Plan identity 只允许稳定键，不能混入 prompt、路径或诊断正文。
     */
    private static void requireId(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 160
            || !value.matches("[A-Za-z0-9._:-]+")) {
                throw new IllegalArgumentException("invalid " + field);
            }
    }

    /**
     * 输入 digest 采用 SHA-256 小写闭集，防止审计记录指向未冻结的输入。
     */
    private static void requireDigest(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid " + field);
        }
    }

    /**
     * Provider 发起前登记冻结的 Plan/revision/run、请求身份和非敏感 Profile。
     */
    void recordIntent(Intent intent);

    /**
     * Provider 终态登记真实计量或 UNKNOWN，禁止把缺失计量伪装为零。
     */
    void recordUsage(Usage usage);

    /**
     * 查询单次已登记请求；成功结论可恢复，失败请求永不以原身份重发。
     */
    Optional<Prior> find(String evaluationId);

    /**
     * 按冻结输入读取最新尝试，恢复时用更大的 ordinal 建立新请求身份。
     */
    Optional<Prior> findLatest(String planId, String planRevisionId, String runId, String inputDigest);

    /**
     * 进程重启后把失去 owner 的 RUNNING 请求标为 UNKNOWN，才能用新身份继续。
     */
    boolean markInterrupted(String evaluationId, Instant observedAt);

    /**
     * Provider 调用是否产生了可接受的结构化 evaluator 结果。
     */
    enum Outcome {
        /**
         * intent 已登记，Provider 仍未结算。
         */
        RUNNING,
        /**
         * Provider 返回并已持久化结构化 evaluator 结论。
         */
        SUCCEEDED,
        /**
         * Provider 明确失败且没有可恢复结论。
         */
        FAILED,
        /**
         * 计量或响应边界不确定；原请求不能重放，新身份可恢复纯模型验收。
         */
        UNKNOWN
    }

    /**
     * evaluator 调用前的不可变 intent；不包含端点、凭据或用户正文。
     *
     * @param evaluationId 验收评估标识
     * @param modelRequestId 一次真实模型请求的标识
     * @param planId Plan 标识
     * @param planRevisionId Plan 修订版本标识
     * @param runId 运行标识
     * @param ownerThreadId 所属对话标识
     * @param profile 配置档案
     * @param inputDigest 输入内容摘要
     * @param attemptOrdinal 本次尝试的顺序位置
     * @param startedAt 开始时间
     */
    record Intent(String evaluationId,
                  String modelRequestId,
                  String planId,
                  String planRevisionId,
                  String runId,
                  String ownerThreadId,
                  ProviderRequestProfile profile,
                  String inputDigest,
                  int attemptOrdinal,
                  Instant startedAt) {
        /**
         * 请求 identity 必须可跨重启去重，digest 必须绑定完整冻结输入。
         */
        public Intent {
            requireEvaluationId(evaluationId);
            requireModelRequestId(modelRequestId);
            requireId(planId, "planId");
            requireId(planRevisionId, "planRevisionId");
            requireId(runId, "runId");
            requireId(ownerThreadId, "ownerThreadId");
            Objects.requireNonNull(profile, "profile");
            requireDigest(inputDigest, "inputDigest");
            if (attemptOrdinal < 1) {
                throw new IllegalArgumentException("invalid evaluator attempt ordinal");
            }
            Objects.requireNonNull(startedAt, "startedAt");
        }
    }

    /**
     * evaluator Provider 终态的持久用量与结构化结论；UNKNOWN 不得伪造结论或 token。
     *
     * @param evaluationId 验收评估标识
     * @param planId Plan 标识
     * @param planRevisionId Plan 修订版本标识
     * @param runId 运行标识
     * @param providerUsage 供应商用量
     * @param outcome 执行结果
     * @param completedAt 完成时间
     * @param evaluation 验收评估
     */
    record Usage(String evaluationId,
                 String planId,
                 String planRevisionId,
                 String runId,
                 ProviderRequestUsage providerUsage,
                 Outcome outcome,
                 Instant completedAt,
                 EvaluationResult evaluation) {
        /**
         * 保持旧调用形状可读；非成功终态不携带结论。
         */
        public Usage(String evaluationId, String planId, String planRevisionId, String runId,
                     ProviderRequestUsage providerUsage, Outcome outcome, Instant completedAt) {
            this(evaluationId, planId, planRevisionId, runId, providerUsage, outcome, completedAt, null);
        }

        /**
         * usage 与 intent 共享 identity，审计层才能阻止迟到结果污染其它 revision。
         */
        public Usage {
            requireEvaluationId(evaluationId);
            requireId(planId, "planId");
            requireId(planRevisionId, "planRevisionId");
            requireId(runId, "runId");
            Objects.requireNonNull(providerUsage, "providerUsage");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(completedAt, "completedAt");
            if ((outcome == Outcome.SUCCEEDED) == (evaluation == null)) {
                throw new IllegalArgumentException("successful evaluator usage requires a result");
            }
        }
    }

    /**
     * 仅返回重试门所需的结果状态，不把 Provider 正文重新暴露给 evaluator。
     *
     * @param evaluationId 验收评估标识
     * @param modelRequestId 一次真实模型请求的标识
     * @param attemptOrdinal 本次尝试的顺序位置
     * @param outcome 执行结果
     * @param evaluation 验收评估
     */
    record Prior(String evaluationId,
                 String modelRequestId,
                 int attemptOrdinal,
                 Outcome outcome,
                 EvaluationResult evaluation) {
        /**
         * 历史状态必须绑定同一稳定 request identity。
         */
        public Prior {
            requireEvaluationId(evaluationId);
            requireModelRequestId(modelRequestId);
            if (attemptOrdinal < 1) {
                throw new IllegalArgumentException("invalid evaluator attempt ordinal");
            }
            Objects.requireNonNull(outcome, "outcome");
            if ((outcome == Outcome.SUCCEEDED) == (evaluation == null)) {
                throw new IllegalArgumentException("successful prior requires a result");
            }
        }
    }

    /**
     * 可跨进程恢复的 evaluator 结论；criteria 是结构化事实而非 summary 文本解析结果。
     *
     * @param verdict 验收结论
     * @param criteria 验收条件
     * @param summary 摘要
     */
    record EvaluationResult(PlanModels.EvaluationVerdict verdict,
                            List<PlanModels.CriterionEvaluation> criteria,
                            String summary) {
        /**
         * 冻结结论集合，并拒绝空摘要或非法 criterion，避免损坏审计事实进入恢复路径。
         */
        public EvaluationResult {
            Objects.requireNonNull(verdict, "verdict");
            criteria = List.copyOf(Objects.requireNonNull(criteria, "criteria"));
            for (PlanModels.CriterionEvaluation criterion : criteria) {
                Objects.requireNonNull(criterion, "criterion");
                PlanModels.requireId(criterion.criterionId(), "criterionId");
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
}

