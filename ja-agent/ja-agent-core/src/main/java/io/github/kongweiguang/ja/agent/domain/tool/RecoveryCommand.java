// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.tool;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 宿主对某次未知 Tool 副作用的显式裁决；它只授权核验或放弃，不证明外部操作结果。
 *
 * @param recoveryId 恢复标识
 * @param callId 模型生成的工具调用标识
 * @param expectedRevision 提交时预期的修订版本
 * @param decision 决策
 * @param authorizationId 授权标识
 * @param operationKey 业务操作标识，重试时复用以识别同一操作
 * @param occurredAt 事件发生时间
 */
public record RecoveryCommand(String recoveryId,
                              String callId,
                              long expectedRevision,
                              Decision decision,
                              String authorizationId,
                              String operationKey,
                              Instant occurredAt) {
    /**
     * 绑定恢复聚合版本和真实 callId，禁止用恢复键猜测待核验的外部副作用。
     */
    public RecoveryCommand {
        recoveryId = ContractChecks.identifier(recoveryId, "recoveryId");
        callId = ContractChecks.identifier(callId, "callId");
        if (expectedRevision < 1) {
            throw new IllegalArgumentException("invalid recovery revision");
        }
        Objects.requireNonNull(decision, "decision");
        authorizationId = ContractChecks.identifier(authorizationId, "authorizationId");
        operationKey = ContractChecks.identifier(operationKey, "operationKey");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    /**
     * 明确区分重做核验与放弃，不把授权身份当作外部系统的幂等或重复风险证明。
     */
    public enum Decision {
        /**
         * 要求恢复步骤重新核验旧调用；只有明确未执行时才可派发新 attempt。
         */
        RETRY,
        /**
         * 要求停止等待并回报失败，但保留旧调用可能已产生副作用的未知事实。
         */
        SKIP
    }
}
