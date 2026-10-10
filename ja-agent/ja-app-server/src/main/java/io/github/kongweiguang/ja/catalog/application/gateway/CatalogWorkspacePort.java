// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.application.gateway;

import java.nio.file.Path;
import java.util.Optional;

/** Catalog 消费方定义的 Workspace 项目只读投影端口。 */
@FunctionalInterface
public interface CatalogWorkspacePort {
    /** 读取项目根目录和受信状态；普通 Workspace 返回空。 */
    Optional<ProjectBinding> project(String workspaceId);

    /**
     * Workspace owner 已校验的项目绑定。
     * @param root 项目根目录
     * @param trusted 是否通过 Workspace 信任校验
     */
    record ProjectBinding(Path root,
                          boolean trusted) {
    }
}
