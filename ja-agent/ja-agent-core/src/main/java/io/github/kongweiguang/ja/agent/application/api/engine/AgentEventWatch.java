// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.api.engine;

import java.time.Duration;
import java.util.OptionalLong;


/**
 * 轻量水位唤醒句柄；事件事实仍由 Store 持有并按游标读取。
 */
public interface AgentEventWatch extends AutoCloseable {
    /**
     * 等待会话出现高于调用方游标的事件水位。
     * 返回空值表示超时；关闭句柄会以 {@link CancellationException} 唤醒等待者。
     */
    OptionalLong awaitNext(long afterSequence, Duration timeout) throws InterruptedException;

    /**
     * 返回 Engine 或调用方是否已关闭此句柄。
     */
    boolean isClosed();

    /**
     * 立即关闭并唤醒阻塞中的等待者。
     */
    @Override
    void close();
}
