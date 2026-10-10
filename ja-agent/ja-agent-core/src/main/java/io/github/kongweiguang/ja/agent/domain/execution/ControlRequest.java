// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.execution;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 请求取消或挂起时持久化的中立控制事实，不携带应用状态或执行凭据。
 *
 * @param operationKey 业务操作标识，重试时复用以识别同一操作
 * @param reasonCode 原因代码
 * @param occurredAt 事件发生时间
 */
public record ControlRequest(String operationKey,
                             String reasonCode,
                             Instant occurredAt) {
    /**
     * 将控制原因限制为稳定码，使恢复逻辑不依赖日志文本或本地化描述。
     */
    public ControlRequest {
        operationKey = ContractChecks.identifier(operationKey, "operationKey");
        if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("invalid control reason code");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
