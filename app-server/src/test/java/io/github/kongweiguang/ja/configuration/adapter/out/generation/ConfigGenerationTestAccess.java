// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.generation;

import java.util.Objects;

/** 为跨职责测试保留只读生命周期探针，不把测试可见性扩散到生产 API。 */
public final class ConfigGenerationTestAccess {
    /** 禁止构造无状态测试桥，所有探针都显式接收被观察的代际。 */
    private ConfigGenerationTestAccess() {
    }

    /**
     * 只读取 generation 的关闭事实，避免根 Facade 测试为了验证租约回收而要求生产方法公开。
     */
    public static boolean isClosed(ConfigGeneration generation) {
        return Objects.requireNonNull(generation, "generation").isClosed();
    }
}
