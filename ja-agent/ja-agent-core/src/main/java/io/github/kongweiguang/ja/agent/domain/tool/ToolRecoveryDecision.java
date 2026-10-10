// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.domain.tool;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 不含路径的持久恢复决策，记录外部结果未知后核验、重试或显式跳过的唯一状态。
 *
 * @param recoveryId 恢复标识
 * @param callId 模型生成的工具调用标识
 * @param revision 当前修订版本
 * @param status 当前状态分类
 * @param disposition 处置方式
 * @param attempt 尝试次数
 * @param attemptId 尝试标识
 * @param toolResultRecordId 工具结果记录标识
 * @param verificationFingerprint 副作用核验证据的指纹
 * @param updatedAt 最后更新时间
 */
public record ToolRecoveryDecision(String recoveryId,
                                   String callId,
                                   long revision,
                                   Status status,
                                   Disposition disposition,
                                   int attempt,
                                   String attemptId,
                                   String toolResultRecordId,
                                   String verificationFingerprint,
                                   Instant updatedAt) {
    /**
     * 重试派发前先保存 intent；崩溃后缺少结果仍按未知处理，绝不据此盲目重放。
     */
    public ToolRecoveryDecision {
        recoveryId = ContractChecks.identifier(recoveryId, "recoveryId");
        callId = ContractChecks.identifier(callId, "callId");
        if (revision < 1 || attempt < 0) {
            throw new IllegalArgumentException("invalid recovery decision version");
        }
        status = Objects.requireNonNull(status, "status");
        Objects.requireNonNull(updatedAt, "updatedAt");
        switch (status) {
            case PENDING -> {
                if (toolResultRecordId != null || verificationFingerprint != null
                    || (disposition == null && (attempt != 0 || attemptId != null))
                    || (disposition == Disposition.RETRY && (attempt < 1 || attemptId == null))
                    || (disposition != null && disposition != Disposition.RETRY)) {
                    throw new IllegalArgumentException("invalid pending recovery decision");
                }
            }
            case VERIFIED -> {
                requireOutcome(disposition, Disposition.VERIFIED, toolResultRecordId, attempt, attemptId);
                if (verificationFingerprint == null || !verificationFingerprint.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("verified recovery requires evidence fingerprint");
                }
            }
            case RETRIED -> {
                if (disposition != Disposition.RETRY || attempt < 1 || attemptId == null
                    || toolResultRecordId == null || verificationFingerprint != null) {
                    throw new IllegalArgumentException("retried recovery requires its completed attempt result");
                }
            }
            case SKIPPED -> requireOutcome(disposition, Disposition.SKIP, toolResultRecordId, attempt, attemptId);
        }
        if (attemptId != null) {
            attemptId = ContractChecks.identifier(attemptId, "attemptId");
        }
        if (toolResultRecordId != null)
            {
                toolResultRecordId = ContractChecks.identifier(toolResultRecordId, "toolResultRecordId");
            }
    }

    /**
     * 结果终态必须带有有效结果引用，且不携带待派发 attempt 身份。
     */
    private static void requireOutcome(Disposition actual, Disposition expected, String resultId,
                                       int attempt, String attemptId) {
        if (actual != expected || resultId == null || attempt != 0 || attemptId != null) {
            throw new IllegalArgumentException("invalid settled recovery outcome");
        }
    }

    /**
     * 恢复生命周期采用闭集，未知副作用不能被隐式解释为成功。
     */
    public enum Status {
        /**
         * 当前结果仍需核验或等待用户的明确恢复命令。
         */
        PENDING,
        /**
         * 工具已核实先前调用产生结果，并与结果事实一同提交。
         */
        VERIFIED,
        /**
         * 明确未执行后重新派发的 attempt 已有持久结果。
         */
        RETRIED,
        /**
         * 用户已放弃等待，原未知副作用仍保留为未知事实。
         */
        SKIPPED
    }

    /**
     * 结束或继续恢复的明确动作，不以工具名或异常类型推断。
     */
    public enum Disposition {
        /**
         * 业务工具以稳定核验指纹确认此前副作用已完成。
         */
        VERIFIED,
        /**
         * 仅在核验结果为 NotApplied 后允许创建下一派发 attempt。
         */
        RETRY,
        /**
         * 明确放弃恢复，保留外部副作用可能已发生的事实。
         */
        SKIP
    }
}
