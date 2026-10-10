// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.api;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 内部 continuation 的准入意图；类型上不提供 UserContent，避免重复或伪造 USER message。
 *
 * @param threadId 所属对话的稳定标识
 * @param turnId 本次可恢复执行的稳定标识
 * @param workspaceId 工作区稳定标识
 * @param workspaceRoot 工作区根目录
 * @param providerId 模型供应商标识
 * @param modelId 模型标识
 * @param reasoningLevel 模型推理等级
 * @param accessMode 访问模式
 * @param collaborationMode 协作模式
 * @param expectedThreadRevision 预期对话修订版本
 * @param initialTurnMutationVersion 初始执行的状态变更版本
 * @param requestedAt 请求时间
 * @param origin 来源分类
 * @param planRevisionId Plan 修订版本标识
 * @param runId 运行标识
 */
public record InternalTurnStartRequest(String threadId,
                                       String turnId,
                                       String workspaceId,
                                       Path workspaceRoot,
                                       String providerId,
                                       String modelId,
                                       String reasoningLevel,
                                       AccessMode accessMode,
                                       CollaborationMode collaborationMode,
                                       long expectedThreadRevision,
                                       long initialTurnMutationVersion,
                                       Instant requestedAt,
                                       TurnOrigin origin,
                                       String planRevisionId,
                                       String runId) {

    /**
     * 内部执行身份在 admission 时冻结，禁止恢复时从当前 Plan 反推并把旧 Turn 改绑到新 Run。
     */
    public InternalTurnStartRequest {
        threadId = TurnStartRequestValidation.identifier(threadId, "thr_", "threadId");
        turnId = TurnStartRequestValidation.identifier(turnId, "turn_", "turnId");
        workspaceId = TurnStartRequestValidation.identifier(workspaceId, "ws_", "workspaceId");
        workspaceRoot = TurnStartRequestValidation.workspaceRoot(workspaceRoot);
        providerId = TurnStartRequestValidation.identifier(providerId, "provider_", "providerId");
        modelId = TurnStartRequestValidation.identifier(modelId, "model_", "modelId");
        TurnStartRequestValidation.validateRuntime(reasoningLevel, accessMode, collaborationMode,
                expectedThreadRevision, initialTurnMutationVersion, requestedAt);
        if (origin == null || !origin.internal()) {
            throw new IllegalArgumentException("internal Turn origin is required");
        }
        if (planRevisionId != null) {
            planRevisionId = ContractChecks.identifier(planRevisionId, "planRevisionId");
        }
        if (runId != null) {
            runId = ContractChecks.identifier(runId, "runId");
        }
        switch (origin) {
            case PLAN_EXECUTION -> {
                if (planRevisionId == null || runId == null) {
                    throw new IllegalArgumentException("Plan execution requires its frozen Run and revision");
                }
            }
            case USER_CONTINUATION, CHILD_TASK -> {
                if (planRevisionId != null || runId != null) {
                    throw new IllegalArgumentException("ordinary internal Turn cannot carry Plan identity");
                }
            }
            case USER -> throw new IllegalArgumentException("user Turn is not an internal request");
        }
    }
}
