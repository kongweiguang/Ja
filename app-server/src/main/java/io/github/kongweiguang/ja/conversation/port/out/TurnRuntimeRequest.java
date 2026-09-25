// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.foundation.validation.ContractChecks;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.NativeExecutionSnapshot;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * 运行时解析器所需的最小请求，刻意不引用入站 TurnStartRequest。
 */
public record TurnRuntimeRequest(String threadId, String turnId, Path workspaceRoot, String workspaceId,
                                 String providerId, String modelId, String reasoningLevel,
                                 io.github.kongweiguang.ja.conversation.domain.permission.AccessMode accessMode,
                                 io.github.kongweiguang.ja.conversation.domain.CollaborationMode collaborationMode,
                                 TurnOrigin origin, Instant requestedAt,
                                 NativeExecutionSnapshot executionContext) {
    /**
     * 内部协调器及既有 stdio 合同使用后台启动环境；原生客户端的请求必须显式传入
     * Turn 准入冻结的 Snapshot，不能在 Resolver 工作线程读取 ThreadLocal。
     */
    public TurnRuntimeRequest(String threadId, String turnId, Path workspaceRoot, String workspaceId,
                              String providerId, String modelId, String reasoningLevel,
                              io.github.kongweiguang.ja.conversation.domain.permission.AccessMode accessMode,
                              io.github.kongweiguang.ja.conversation.domain.CollaborationMode collaborationMode,
                              TurnOrigin origin, Instant requestedAt) {
        this(threadId, turnId, workspaceRoot, workspaceId, providerId, modelId, reasoningLevel,
                accessMode, collaborationMode, origin, requestedAt, null);
    }
    /**
     * 固定运行时解析边界，出站 Adapter 不得读取或改写 Thread、Turn 与用户输入。
     */
    public TurnRuntimeRequest {
        threadId = ContractChecks.identifier(threadId, "threadId");
        if (turnId != null) turnId = ContractChecks.identifier(turnId, "turnId");
        workspaceRoot = ContractChecks.absolutePath(workspaceRoot, "workspaceRoot");
        workspaceId = ContractChecks.identifier(workspaceId, "workspaceId");
        providerId = ContractChecks.identifier(providerId, "providerId");
        modelId = ContractChecks.identifier(modelId, "modelId");
        if (reasoningLevel != null && !reasoningLevel.matches("off|minimal|low|medium|high|xhigh|max")) {
            throw new IllegalArgumentException("invalid reasoningLevel");
        }
        Objects.requireNonNull(accessMode, "accessMode");
        Objects.requireNonNull(collaborationMode, "collaborationMode");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
