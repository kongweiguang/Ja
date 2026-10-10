// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;

import java.util.List;

/**
 * 为 USER admission 提供可原子绑定的宿主队列输入，不把业务模块记录泄漏给 Conversation。
 */
@FunctionalInterface
public interface PendingQueueInputSource {
    /**
     * 按目标 Thread 的持久顺序读取至多 limit 条待处理输入；读取不得 claim 或改变其业务状态。
     */
    List<AgentInputCommand.Enqueue> loadPendingInputs(String threadId, int limit);
}
