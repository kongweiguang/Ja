// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.api;

import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ReviewContext;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 入站 Turn 启动意图；不包含 Provider、配置 Adapter 或已解析 Tool 等出站对象。
 *
 * @param initialContextSeed 仅 Child 首轮可携带创建时冻结的背景快照，映射器会拒绝其它来源。
 *
 * @param threadId 所属对话的稳定标识
 * @param turnId 本次可恢复执行的稳定标识
 * @param workspaceId 工作区稳定标识
 * @param workspaceRoot 工作区根目录
 * @param content 内容
 * @param providerId 模型供应商标识
 * @param modelId 模型标识
 * @param reasoningLevel 模型推理等级
 * @param accessMode 访问模式
 * @param collaborationMode 协作模式
 * @param expectedThreadRevision 预期对话修订版本
 * @param initialTurnMutationVersion 初始执行的状态变更版本
 * @param requestedAt 请求时间
 * @param outputSchema 本次终答必须通过的 Schema，完整保存以支持恢复，不修改 Thread 默认值
 * @param reviewContext 仅内部审查入口可设置的冻结附件身份，不能从用户正文推断
 * @param runtimeOverridden 用户运行级选择或可信只读审查的偏好覆盖事实，恢复不能重新选择或修改 Thread 默认值
 */
public record TurnStartRequest(String threadId,
                               String turnId,
                               String workspaceId,
                               Path workspaceRoot,
                               UserContent content,
                               String providerId,
                               String modelId,
                               String reasoningLevel,
                               AccessMode accessMode,
                               CollaborationMode collaborationMode,
                               long expectedThreadRevision,
                               long initialTurnMutationVersion,
                               Instant requestedAt,
                               Optional<InitialContextSeed> initialContextSeed,
                               Optional<JsonObject> outputSchema,
                               Optional<ReviewContext> reviewContext,
                               boolean runtimeOverridden) {

    /**
     * 在解析运行时前固定身份、路径和可选输出/审查约束，避免 Resolver 接收未经约束的 Wire DTO。
     */
    public TurnStartRequest {
        threadId = TurnStartRequestValidation.identifier(threadId, "thr_", "threadId");
        turnId = TurnStartRequestValidation.identifier(turnId, "turn_", "turnId");
        workspaceId = TurnStartRequestValidation.identifier(workspaceId, "ws_", "workspaceId");
        workspaceRoot = TurnStartRequestValidation.workspaceRoot(workspaceRoot);
        content = Objects.requireNonNull(content, "content");
        initialContextSeed = Objects.requireNonNull(initialContextSeed, "initialContextSeed");
        outputSchema = Objects.requireNonNull(outputSchema, "outputSchema");
        reviewContext = Objects.requireNonNull(reviewContext, "reviewContext");
        providerId = TurnStartRequestValidation.identifier(providerId, "provider_", "providerId");
        modelId = TurnStartRequestValidation.identifier(modelId, "model_", "modelId");
        TurnStartRequestValidation.validateRuntime(reasoningLevel, accessMode, collaborationMode,
                expectedThreadRevision, initialTurnMutationVersion, requestedAt);
    }

    /**
     * 返回正文派生视图，标题等纯文本策略不得把路径引用误当作已读取正文。
     */
    public String input() {
        return content.text();
    }

    /**
     * 返回独立附件 ID 快照，受管附件仍由 admission 事务核验所有权。
     */
    public List<String> attachmentIds() {
        return content.attachmentIds();
    }

}
