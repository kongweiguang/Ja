// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.port.in;

import java.nio.file.Path;

/**
 * 为 Turn admission 提供原子代际租约，不暴露 Watcher、文件或缓存实现。
 */
public interface ConfigurationGenerationUseCase {
    /**
     * 解析并持有工作区当前配置代际。
     *
     * <p>null 表示 general workspace；非 null 路径必须已经由 workspace 域解析，配置域仍会
     * 重新规范化以防止跨边界符号链接竞态。</p>
     */
    ConfigurationGenerationLease acquire(Path workspaceRoot);
}
