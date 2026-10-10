// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.application.gateway;

import java.nio.file.Path;

/**
 * 创建受 Workspace owner 约束的文件边界；调用方只依赖 ACL，不直接持有文件系统实现。
 */
@FunctionalInterface
public interface WorkspaceBoundaryFactory {
    /**
     * 为一次工作区操作创建边界视图；实现必须复用 Workspace 的路径和链接校验策略。
     */
    WorkspaceBoundaryPort open(Path workspaceRoot);
}
