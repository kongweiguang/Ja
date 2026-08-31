// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.security.windows;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * WindowsPinnedDirectoryChain 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
 */
final class WindowsPinnedDirectoryChain implements AutoCloseable {
    private final WindowsWin32Native owner;
    private final List<MemorySegment> handles;
    private boolean closed;

    /**
     * WindowsPinnedDirectoryChain 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    WindowsPinnedDirectoryChain(WindowsWin32Native owner,
                                List<MemorySegment> handles) {
        this.owner = owner;
        this.handles = handles;
    }

    /**
     * close 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (int index = handles.size() - 1; index >= 0; index--) {
            try {
                owner.closeFileHandle(handles.get(index));
            } catch (IOException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        handles.clear();
        if (failure != null) throw failure;
    }
}
