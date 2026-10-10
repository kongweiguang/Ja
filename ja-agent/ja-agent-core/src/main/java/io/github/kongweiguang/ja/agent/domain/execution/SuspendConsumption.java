// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.execution;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 将一次恢复消费绑定到此前观察到的挂起请求，避免新请求被错误清除。
 *
 * @param suspendRequestRecordId 挂起请求记录标识
 * @param suspendOperationKey 挂起操作的幂等标识
 * @param resumeOperationKey 续跑操作的幂等标识
 * @param occurredAt 事件发生时间
 */
public record SuspendConsumption(String suspendRequestRecordId,
                                 String suspendOperationKey,
                                 String resumeOperationKey,
                                 Instant occurredAt) {
    /**
     * 固定请求与恢复操作的关联，具体是否可消费由带 CAS 的恢复状态机判定。
     */
    public SuspendConsumption {
        suspendRequestRecordId = ContractChecks.identifier(suspendRequestRecordId, "suspendRequestRecordId");
        suspendOperationKey = ContractChecks.identifier(suspendOperationKey, "suspendOperationKey");
        resumeOperationKey = ContractChecks.identifier(resumeOperationKey, "resumeOperationKey");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
