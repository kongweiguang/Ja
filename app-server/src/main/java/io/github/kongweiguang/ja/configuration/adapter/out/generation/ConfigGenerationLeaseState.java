// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.generation;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * ConfigGenerationLeaseState 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
 */
final class ConfigGenerationLeaseState {
    private final AtomicInteger activeLeases = new AtomicInteger();
    private final Runnable onFullyReleased;
    private boolean closed;

    /**
     * ConfigGenerationLeaseState 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    ConfigGenerationLeaseState(Runnable onFullyReleased) {
        this.onFullyReleased = java.util.Objects.requireNonNull(onFullyReleased, "onFullyReleased");
    }

    /**
     * acquire 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    synchronized void acquire() {
        if (closed) throw new IllegalStateException("configuration generation is closed");
        activeLeases.incrementAndGet();
    }

    /**
     * close 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    void close() {
        boolean notify;
        synchronized (this) {
            if (closed) return;
            closed = true;
            notify = activeLeases.get() == 0;
        }
        if (notify) onFullyReleased.run();
    }

    /**
     * isClosed 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    synchronized boolean isClosed() {
        return closed;
    }

    /**
     * release 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    void release() {
        boolean notify;
        synchronized (this) {
            int remaining = activeLeases.decrementAndGet();
            if (remaining < 0) {
                activeLeases.incrementAndGet();
                throw new IllegalStateException("configuration lease is already closed");
            }
            notify = closed && remaining == 0;
        }
        if (notify) onFullyReleased.run();
    }
}
