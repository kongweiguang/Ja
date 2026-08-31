// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 运行时解析器所需的最小请求，刻意不引用入站 TurnStartRequest。
 */
public record TurnRuntimeRequest(String threadId, Path workspaceRoot, String workspaceId,
                                 String providerId, String modelId, String reasoningLevel,
                                 io.github.kongweiguang.ja.conversation.domain.permission.AccessMode accessMode,
                                 Duration deadline, Instant requestedAt) {
    /**
     * 固定运行时解析边界，出站 Adapter 不得读取或改写 Thread、Turn 与用户输入。
     */
    public TurnRuntimeRequest {
        threadId = ContractChecks.identifier(threadId, "threadId");
        workspaceRoot = ContractChecks.absolutePath(workspaceRoot, "workspaceRoot");
        workspaceId = ContractChecks.identifier(workspaceId, "workspaceId");
        providerId = ContractChecks.identifier(providerId, "providerId");
        modelId = ContractChecks.identifier(modelId, "modelId");
        if (reasoningLevel != null && !reasoningLevel.matches("off|minimal|low|medium|high|xhigh|max")) {
            throw new IllegalArgumentException("invalid reasoningLevel");
        }
        Objects.requireNonNull(accessMode, "accessMode");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
