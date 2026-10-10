// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.gateway;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 为对话请求提供已经打开且身份匹配的 Workspace 根目录。
 */
public interface WorkspaceBindingResolver {
    /**
     * 只解析现存的打开工作区；缺失或关闭必须由实现明确失败。
     */
    WorkspaceBinding requireOpenWorkspace(String workspaceId);

    /**
     * 将宿主工作区模型收敛为对话运行时所需的最小不可变值。
     *
     * @param workspaceId 工作区稳定标识
     * @param root 根
     */
    record WorkspaceBinding(String workspaceId,
                            Path root) {
        /**
         * 绑定保留原始业务身份与规范化根路径，避免适配器返回错配工作区。
         */
        public WorkspaceBinding {
            if (workspaceId == null || workspaceId.isBlank()) {
                throw new IllegalArgumentException("workspaceId is required");
            }
            root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        }
    }
}
