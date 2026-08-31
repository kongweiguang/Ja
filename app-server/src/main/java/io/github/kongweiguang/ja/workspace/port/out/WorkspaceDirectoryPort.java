// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.port.out;

import io.github.kongweiguang.ja.workspace.domain.WorkspaceDirectory;

import java.nio.file.Path;

/**
 * 文件系统出站端口只返回已验证目录，不把 Files、ACL 或平台细节带入 application。
 */
public interface WorkspaceDirectoryPort {
    /**
     * 验证用户提供的现有项目目录，拒绝链接、别名与非目录目标。
     */
    WorkspaceDirectory verifyProjectDirectory(Path requestedRoot);

    /**
     * 在 Java 数据边界内创建或验证固定通用工作区目录。
     */
    WorkspaceDirectory ensureGeneralDirectory();

    /**
     * 仅比较规范路径，不能创建目录或触发磁盘读取。
     */
    boolean isGeneralDirectory(Path root);
}
