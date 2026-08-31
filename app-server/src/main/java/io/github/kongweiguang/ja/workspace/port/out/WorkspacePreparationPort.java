// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.port.out;

import java.nio.file.Path;

/**
 * 跨域预热端口；composition 可组合配置租约与 Turn Runtime，workspace 不依赖其 adapter。
 */
@FunctionalInterface
public interface WorkspacePreparationPort {
    /**
     * 为项目根准备当前配置代际；失败可由应用按最佳努力语义降级。
     */
    void prepare(Path projectRoot);
}
