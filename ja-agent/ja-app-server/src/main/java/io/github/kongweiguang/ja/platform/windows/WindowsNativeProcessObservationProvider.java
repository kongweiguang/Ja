// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.platform.windows;

import io.github.kongweiguang.ja.foundation.lifecycle.NativeProcessObservation;
import io.github.kongweiguang.ja.foundation.lifecycle.NativeProcessObservationProvider;

/**
 * Classpath ServiceLoader provider；实例本身无状态，只有打开进程时才创建 Windows FFM 会话和原生句柄。
 * @author kongweiguang
 */
public final class WindowsNativeProcessObservationProvider implements NativeProcessObservationProvider {
    /**
     * ServiceLoader 要求公开无参构造；仅创建无状态 provider，避免非 Windows 平台初始化 Kernel32。
     * @author kongweiguang
     */
    public WindowsNativeProcessObservationProvider() {
    }

    /**
     * 延迟委派给 Windows 观察对象，保持权限掩码、进程代际和句柄 owner 的既有实现语义。
     * @author kongweiguang
     */
    @Override
    public NativeProcessObservation open(long processId) {
        return WindowsNativeProcessObservation.open(processId);
    }
}
