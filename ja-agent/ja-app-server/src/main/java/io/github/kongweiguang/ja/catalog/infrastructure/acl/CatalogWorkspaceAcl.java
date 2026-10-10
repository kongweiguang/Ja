// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.infrastructure.acl;

import io.github.kongweiguang.ja.catalog.application.gateway.CatalogWorkspacePort;
import java.util.Objects;
import java.util.Optional;

/**
 * Catalog 读取 Workspace 的 ACL；只转发消费方端口已经压缩好的项目投影。
 */
public final class CatalogWorkspaceAcl {
    /** 该 ACL 无状态，禁止缓存 Workspace owner 的可变投影。 */
    private CatalogWorkspaceAcl() {
    }

    /**
     * 将 Workspace owner 的项目投影转交给 Catalog 用例。
     *
     * @param workspaces Workspace 只读端口
     * @param workspaceId 不透明工作区标识
     * @return 项目根路径和信任状态；非项目工作区返回空
     */
    public static Optional<CatalogWorkspacePort.ProjectBinding> project(
            CatalogWorkspacePort workspaces, String workspaceId) {
        Objects.requireNonNull(workspaces, "workspaces");
        if (workspaceId == null) {
            return Optional.empty();
        }
        return workspaces.project(workspaceId);
    }
}
