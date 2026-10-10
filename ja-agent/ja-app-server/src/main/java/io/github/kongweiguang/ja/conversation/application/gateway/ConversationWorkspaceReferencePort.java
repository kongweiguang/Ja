// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import java.util.Objects;

/**
 * Conversation 消费方定义的 Workspace 引用校验端口；应用服务只处理规范化相对引用。
 */
@FunctionalInterface
public interface ConversationWorkspaceReferencePort {
    /**
     * 按 Thread 所属 Workspace 重新验证结构化引用，拒绝未经 owner 确认的路径。
     *
     * @param threadWorkspaceId Thread 权威 Workspace identity
     * @param workspaceId 引用声明中的 Workspace identity
     * @param relativePath 相对路径
     * @param kind 仅允许 file 或 directory
     * @return 已规范化的引用
     */
    ValidatedReference validate(String threadWorkspaceId, String workspaceId,
                                String relativePath, String kind);

    /**
     * Workspace owner 返回给 Conversation 的最小值对象；绝对路径和底层异常不会跨边界。
     *
     * @param workspaceId 引用所属工作区
     * @param relativePath 工作区内相对路径
     * @param kind 引用类型
     */
    record ValidatedReference(String workspaceId,
                              String relativePath,
                              String kind) {
        /**
         * 冻结路径身份与闭集类型，防止后续应用流程绕过 owner 重新解释引用。
         */
        public ValidatedReference {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(relativePath, "relativePath");
            if (!"file".equals(kind) && !"directory".equals(kind)) {
                throw new IllegalArgumentException("unsupported workspace reference kind");
            }
        }
    }
}
