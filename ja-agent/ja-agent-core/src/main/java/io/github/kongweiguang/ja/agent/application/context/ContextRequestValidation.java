// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.context;

import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Context 编排请求与压缩请求共享同一冻结边界，避免两层校验规则漂移。
 */
public final class ContextRequestValidation {
    /**
     * 请求由所属 record 构造，工具类不提供独立实例。
     */
    private ContextRequestValidation() {
    }

    /**
     * 在两层请求之间统一校验共享依赖，消息副本仍由其 record 构造器直接冻结。
     */
    public static void validate(String threadId, long sourceRevision,
                                List<ContextMessage> messages, ContextBudget budget,
                                Optional<ModelContinuation> continuation, ToolProjectionLimits outputLimits,
                                CheckpointStore.ProjectionBinding projectionBinding, ContextTokenMeter meter,
                                CancellationToken cancellation, Optional<ContextBaseline> baseline) {
        if (threadId == null || threadId.isBlank() || sourceRevision < 0) {
            throw new IllegalArgumentException("invalid context thread");
        }
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(continuation, "continuation");
        Objects.requireNonNull(outputLimits, "outputLimits");
        Objects.requireNonNull(projectionBinding, "projectionBinding");
        Objects.requireNonNull(meter, "meter");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(baseline, "baseline");
    }
}
