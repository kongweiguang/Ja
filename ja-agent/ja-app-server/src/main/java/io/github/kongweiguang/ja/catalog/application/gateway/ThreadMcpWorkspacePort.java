// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.application.gateway;

import io.github.kongweiguang.ja.workspace.domain.model.Workspace;

/**
 * Thread MCP 只需要已打开工作区的只读绑定；写入、信任变更等 Workspace 用例留在 Workspace owner。
 */
@FunctionalInterface
public interface ThreadMcpWorkspacePort {
    /** 读取 Thread 当前工作区的物理绑定。 */
    Workspace requireOpenWorkspace(String workspaceId);
}
