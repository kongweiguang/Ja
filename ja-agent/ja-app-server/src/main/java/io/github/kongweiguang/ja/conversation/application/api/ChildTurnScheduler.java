// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.api;

import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;

/**
 * 为 Child Thread 暴露队列预留后的原子接纳接缝，避免 Task 先建 Thread 再启动 Turn。
 */
public interface ChildTurnScheduler {
    /**
     * 复用普通 Turn 的请求级运行时、取消、Deadline 与执行循环；Child 的业务元数据由请求绑定的
     * AgentStore 参与者在唯一 admission 事务中提交，调度端不接受可独立提交的回调。
     */
    TurnUseCase.Accepted startChild(TurnStartRequest request, ConversationTurnEventSink sink,
                                    String sourceThreadId, String sourceTurnId);
}
