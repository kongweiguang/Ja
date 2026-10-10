// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.gateway;

import io.github.kongweiguang.ja.agent.domain.turn.TurnState;

import java.util.Objects;

/**
 * Plan 消费方定义的对话读取端口；只暴露隐藏 Turn admission 所需的冻结字段。
 */
public interface PlanConversationPort {
    /** 读取 owner Thread 的不可变 admission 绑定。 */
    ThreadBinding readThread(String threadId);

    /** 读取指定隐藏 Turn 的当前状态，用于取消前的幂等判断。 */
    TurnBinding readTurn(String threadId, String turnId);

    /**
     * Plan 所需的 Thread 最小快照，避免跨域实体泄漏到应用服务。
     * @param threadId Thread 会话标识
     * @param workspaceId Workspace 工作区标识
     * @param revision Thread 版本
     * @param providerId Provider 标识
     * @param modelId 模型标识
     * @param reasoningLevel 推理级别；null 表示沿用模型默认，由 Conversation 唯一准入入口解释
     * @param accessMode 访问模式
     * @param collaborationMode 协作模式
     */
    record ThreadBinding(String threadId,
                         String workspaceId,
                         long revision,
                         String providerId,
                         String modelId,
                         String reasoningLevel,
                         String accessMode,
                         String collaborationMode) {
        /** 冻结身份和运行参数；推理级别允许为空以沿用模型默认，由 Conversation 唯一准入入口解释。 */
        public ThreadBinding {
            require(threadId, "threadId");
            require(workspaceId, "workspaceId");
            require(providerId, "providerId");
            require(modelId, "modelId");
            require(accessMode, "accessMode");
            require(collaborationMode, "collaborationMode");
            if (revision < 0) {
                throw new IllegalArgumentException("invalid thread revision");
            }
        }

        /** 校验标识字段，避免失效快照进入隐藏准入。 */
        private static void require(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
        }
    }

    /**
     * Plan 取消路径所需的最小 Turn 状态。
     * @param turnId Turn 执行标识
     * @param state 当前 Turn 状态
     */
    record TurnBinding(String turnId,
                       TurnState state) {
        /** Turn 执行标识 必须稳定，状态由 Conversation owner 提供。 */
        public TurnBinding {
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(state, "state");
        }
    }
}

