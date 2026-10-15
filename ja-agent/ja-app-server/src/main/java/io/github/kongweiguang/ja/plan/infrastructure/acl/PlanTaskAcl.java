// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.infrastructure.acl;

import io.github.kongweiguang.ja.collaboration.application.api.TaskUseCase;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.plan.application.gateway.PlanTaskPort;
import io.github.kongweiguang.ja.plan.application.api.PlanExecutionEventSink;

import java.util.Objects;

/** 将 TaskCoordinator 的续接投影能力压缩为 Plan 自有端口。 */
public final class PlanTaskAcl implements PlanTaskPort {
    private final TaskUseCase tasks;

    /** 组合根注入唯一 Task 协调器，禁止 Plan 直接持有 Collaboration service。 */
    public PlanTaskAcl(TaskUseCase tasks) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    /**
     * 只转发事件投影，不让 Plan 访问 Task 的状态仓储或 admission；同时把已登记身份解析器独立传过包装层，
     * 使自动压缩事件仍能按原 RpcSession 路由且不创建新的 Workspace owner。
     *
     * @author kongweiguang
     */
    @Override
    public ConversationTurnEventSink projectContinuationEvents(String threadId,
                                                                PlanExecutionEventSink downstream,
                                                                boolean registered) {
        Objects.requireNonNull(downstream, "downstream");
        ConversationTurnEventSink sink = registered
                ? ConversationTurnEventSink.turnEventsOnly(downstream, downstream::workspaceId)
                : ConversationTurnEventSink.noop();
        return tasks.projectContinuationEvents(threadId, sink);
    }
}
