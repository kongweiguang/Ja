// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ReviewContext;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 运行时解析器所需的最小请求，刻意不引用入站 TurnStartRequest。
 *
 * @param threadId 所属对话的稳定标识
 * @param turnId 本次可恢复执行的稳定标识
 * @param workspaceRoot 工作区根目录
 * @param workspaceId 工作区稳定标识
 * @param providerId 模型供应商标识
 * @param modelId 模型标识
 * @param reasoningLevel 模型推理等级
 * @param accessMode 访问模式
 * @param collaborationMode 协作模式
 * @param origin 来源分类
 * @param planRevisionId Plan 修订版本标识
 * @param runId 运行标识
 * @param requestedAt 请求时间
 * @param executionContext 执行上下文
 * @param reviewContext 恢复时从可信准入事实读取的审查身份，决定只读目录而非新执行状态机
 */
public record TurnRuntimeRequest(String threadId,
                                 String turnId,
                                 Path workspaceRoot,
                                 String workspaceId,
                                 String providerId,
                                 String modelId,
                                 String reasoningLevel,
                                 io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode accessMode,
                                 io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode collaborationMode,
                                 TurnOrigin origin,
                                 String planRevisionId,
                                 String runId,
                                 Instant requestedAt,
                                 NativeExecutionSnapshot executionContext,
                                 Optional<ReviewContext> reviewContext) {
    /**
     * 普通用户、用户续接和 Task Turn 不带 Plan Run 身份；Plan 来源必须使用完整构造器传入
     * 持久化的关联身份，不能从 Thread 类型或工具名称推导。
     */
    public TurnRuntimeRequest(String threadId, String turnId, Path workspaceRoot, String workspaceId,
                              String providerId, String modelId, String reasoningLevel,
                              io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode accessMode,
                              io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode collaborationMode,
                              TurnOrigin origin, Instant requestedAt) {
        this(threadId, turnId, workspaceRoot, workspaceId, providerId, modelId, reasoningLevel,
                accessMode, collaborationMode, origin, null, null, requestedAt, null, Optional.empty());
    }

    /**
     * 传入准入时冻结的客户端环境；路径、环境和客户端执行能力仅留在 Ja 运行时，不进入 Core 持久状态。
     */
    public TurnRuntimeRequest(String threadId, String turnId, Path workspaceRoot, String workspaceId,
                              String providerId, String modelId, String reasoningLevel,
                              io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode accessMode,
                              io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode collaborationMode,
                              TurnOrigin origin, Instant requestedAt, NativeExecutionSnapshot executionContext) {
        this(threadId, turnId, workspaceRoot, workspaceId, providerId, modelId, reasoningLevel,
                accessMode, collaborationMode, origin, null, null, requestedAt, executionContext, Optional.empty());
    }

    /**
     * 固定运行时解析边界，出站 Adapter 不得读取或改写 Thread、Turn 与用户输入。
     */
    public TurnRuntimeRequest {
        threadId = ContractChecks.identifier(threadId, "threadId");
        if (turnId != null) {
            turnId = ContractChecks.identifier(turnId, "turnId");
        }
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
        if (planRevisionId != null) {
            planRevisionId = ContractChecks.identifier(planRevisionId, "planRevisionId");
        }
        if (runId != null) {
            runId = ContractChecks.identifier(runId, "runId");
        }
        switch (origin) {
            case PLAN_EXECUTION -> {
                if (planRevisionId == null || runId == null) {
                    throw new IllegalArgumentException("Plan execution requires a Run and Plan revision only");
                }
            }
            case USER, USER_CONTINUATION, CHILD_TASK -> {
                if (planRevisionId != null || runId != null) {
                    throw new IllegalArgumentException("ordinary Turn must not carry Plan Run identity");
                }
            }
        }
        Objects.requireNonNull(requestedAt, "requestedAt");
        reviewContext = Objects.requireNonNull(reviewContext, "reviewContext");
    }
}
