// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.aot;

import io.github.kongweiguang.ja.foundation.error.StorageException;

/**
 * 将 Solon/Graal metadata 分析与真实运行时 I/O 隔离。
 */
public final class AotSideEffectGuard {
    /**
     * 纯静态策略不允许实例化，避免出现第二个可变 AOT 状态源。
     */
    private AotSideEffectGuard() {
    }

    /**
     * 判断当前进程是否处于 Solon metadata 生成阶段。
     */
    public static boolean processing() {
        return System.getProperty("solon.aot.processing") != null;
    }

    /**
     * 在文件、锁、数据源或网络副作用发生前拒绝 AOT 分析期调用。
     */
    public static void requireRuntimeIo() {
        if (processing()) {
            throw new StorageException(StorageException.Code.INVALID_CONFIGURATION,
                    "runtime resources cannot open during Solon AOT processing");
        }
    }
}
