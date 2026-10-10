// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.tool;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 指向已消费恢复命令的追加事实，进程重启后不会再次应用相同授权。
 *
 * @param commandRecordId 命令记录标识
 * @param operationKey 业务操作标识，重试时复用以识别同一操作
 * @param occurredAt 事件发生时间
 */
public record RecoveryCommandConsumption(String commandRecordId,
                                         String operationKey,
                                         Instant occurredAt) {
    /**
     * 保留命令与消费操作的稳定关联，消费事实本身不伪装成工具核验结果。
     */
    public RecoveryCommandConsumption {
        commandRecordId = ContractChecks.identifier(commandRecordId, "commandRecordId");
        operationKey = ContractChecks.identifier(operationKey, "operationKey");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
