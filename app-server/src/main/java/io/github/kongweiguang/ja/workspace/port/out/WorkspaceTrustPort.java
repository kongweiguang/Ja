// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.port.out;

import io.github.kongweiguang.ja.workspace.domain.Workspace;

import java.nio.file.Path;

/**
 * 跨域信任同步端口，阻止 workspace application 直接调用 configuration adapter。
 */
@FunctionalInterface
public interface WorkspaceTrustPort {
    /**
     * 将持久化后的信任事实同步到项目配置 owner；实现必须支持相同值重试。
     */
    void synchronize(Path projectRoot, Workspace.Trust trust);
}
