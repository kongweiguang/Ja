// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.concurrent;

/**
 * 复用进程级单调关闭期限的资源协议，防止下游组件为自己重新申请完整超时。
 */
public interface DeadlineCloseable extends AutoCloseable {
    /**
     * 按给定的绝对 {@link System#nanoTime()} 期限关闭资源，不得在实现内重置预算。
     */
    void closeAt(long shutdownDeadlineNanos);
}
