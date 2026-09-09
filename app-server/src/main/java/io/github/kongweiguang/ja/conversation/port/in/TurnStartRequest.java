// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.in;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;

/**
 * 入站 Turn 启动意图；不包含 Provider、配置 Adapter 或已解析 Tool 等出站对象。
 */
public record TurnStartRequest(
        String threadId,
        String turnId,
        String workspaceId,
        Path workspaceRoot,
        UserContent content,
        String providerId,
        String modelId,
        String reasoningLevel,
        AccessMode accessMode,
        CollaborationMode collaborationMode,
        Duration deadline,
        long expectedThreadRevision,
        long initialTurnMutationVersion,
        Instant requestedAt) {

    /**
     * 在解析运行时前固定身份、路径和上限，避免出站 Resolver 接收未经约束的 Wire DTO。
     */
    public TurnStartRequest {
        threadId = TurnStartRequestValidation.identifier(threadId, "thr_", "threadId");
        turnId = TurnStartRequestValidation.identifier(turnId, "turn_", "turnId");
        workspaceId = TurnStartRequestValidation.identifier(workspaceId, "ws_", "workspaceId");
        workspaceRoot = TurnStartRequestValidation.workspaceRoot(workspaceRoot);
        content = Objects.requireNonNull(content, "content");
        providerId = TurnStartRequestValidation.identifier(providerId, "provider_", "providerId");
        modelId = TurnStartRequestValidation.identifier(modelId, "model_", "modelId");
        TurnStartRequestValidation.validateRuntime(reasoningLevel, accessMode, collaborationMode, deadline,
                expectedThreadRevision, initialTurnMutationVersion, requestedAt);
    }

    /** 返回正文派生视图，标题等纯文本策略不得把路径引用误当作已读取正文。 */
    public String input() {
        return content.text();
    }

    /** 返回独立附件 ID 快照，受管附件仍由 admission 事务核验所有权。 */
    public List<String> attachmentIds() {
        return content.attachmentIds();
    }

}
