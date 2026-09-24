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

    /** 按 Thread identity 创建全新的空目录，重试时只接受仍为空的失败残留。 */
    WorkspaceDirectory createSessionDirectory(String threadId);

    /** 按登记的 Thread identity 重验已有会话目录，不接受任意客户端路径。 */
    WorkspaceDirectory verifySessionDirectory(String threadId, Path registeredRoot);

    /** 按固定旧数据目录重验 legacy root，不创建或改变其中内容。 */
    WorkspaceDirectory verifyLegacySharedDirectory(Path registeredRoot);

    /**
     * 仅比较规范路径，不能创建目录或触发磁盘读取。
     */
    boolean isLegacySharedDirectory(Path root);
}
