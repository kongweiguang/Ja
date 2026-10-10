// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.infrastructure.filesystem;

import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceBoundaryFactory;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceBoundaryPort;

import java.nio.file.Path;

/**
 * 将 Workspace 文件系统实现接入应用 Gateway；边界创建集中在 owner 内，Catalog 不依赖 adapter 类。
 */
public final class WorkspaceBoundaryFactoryAdapter implements WorkspaceBoundaryFactory {
    /**
     * 创建一次独立的路径边界，避免共享可变校验状态。
     */
    @Override
    public WorkspaceBoundaryPort open(Path workspaceRoot) {
        return new WorkspaceBoundary(workspaceRoot);
    }
}
