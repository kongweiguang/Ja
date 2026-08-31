// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.port.in;

import java.nio.file.Path;

/**
 * 接收 workspace 域已提交的信任事实，隔离工作区应用服务与配置文件适配器。
 */
public interface ConfigurationWorkspaceTrustUseCase {
    /**
     * 幂等同步工作区信任；路径仍由配置适配器重新规范化以抵御符号链接竞态。
     */
    void synchronize(Path workspaceRoot, boolean trusted);
}
