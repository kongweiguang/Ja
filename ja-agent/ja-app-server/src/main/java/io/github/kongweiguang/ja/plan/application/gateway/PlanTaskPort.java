// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.gateway;

import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.plan.application.api.PlanExecutionEventSink;

/**
 * Plan 消费方定义的续接事件投影端口；Task owner 的调度和持久化细节留在 ACL。
 */
@FunctionalInterface
public interface PlanTaskPort {
    /** 将隐藏 Turn 事件投影到当前 Task 的公开出口。 */
    ConversationTurnEventSink projectContinuationEvents(String threadId, PlanExecutionEventSink downstream,
                                                        boolean registered);
}
