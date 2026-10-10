// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.api;

/**
 * 临时草稿发布端只入有界队列，不等待网络或消费者，也不拥有执行的取消权。
 */
@FunctionalInterface
public interface TurnEventSink {
    /**
     * 无草稿订阅时仍完整执行；持久消息和事件不会经过这个可丢弃通道。
     */
    static TurnEventSink noop() {
        return ignored -> {
        };
    }

    /**
     * 队列溢出由订阅端要求重同步，不能阻塞或回滚已经发生的模型与工具工作。
     */
    void publish(TurnEvent event);
}
