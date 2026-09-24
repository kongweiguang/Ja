// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.in;

import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/** 内部 continuation 的准入意图；类型上不提供 UserContent，避免重复或伪造 USER message。 */
public record InternalTurnStartRequest(
        String threadId,
        String turnId,
        String workspaceId,
        Path workspaceRoot,
        String providerId,
        String modelId,
        String reasoningLevel,
        AccessMode accessMode,
        CollaborationMode collaborationMode,
        Duration deadline,
        long expectedThreadRevision,
        long initialTurnMutationVersion,
        Instant requestedAt,
        TurnOrigin origin) {

    /** 内部请求沿用公开 Turn 的稳定身份和预算约束，只接受会话内三种隐藏来源。 */
    public InternalTurnStartRequest {
        threadId = TurnStartRequestValidation.identifier(threadId, "thr_", "threadId");
        turnId = TurnStartRequestValidation.identifier(turnId, "turn_", "turnId");
        workspaceId = TurnStartRequestValidation.identifier(workspaceId, "ws_", "workspaceId");
        workspaceRoot = TurnStartRequestValidation.workspaceRoot(workspaceRoot);
        providerId = TurnStartRequestValidation.identifier(providerId, "provider_", "providerId");
        modelId = TurnStartRequestValidation.identifier(modelId, "model_", "modelId");
        TurnStartRequestValidation.validateRuntime(reasoningLevel, accessMode, collaborationMode, deadline,
                expectedThreadRevision, initialTurnMutationVersion, requestedAt);
        if (origin == null || !origin.internal()) {
            throw new IllegalArgumentException("internal Turn origin is required");
        }
    }
}
