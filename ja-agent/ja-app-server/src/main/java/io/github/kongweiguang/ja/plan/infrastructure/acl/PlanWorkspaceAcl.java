// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.infrastructure.acl;

import io.github.kongweiguang.ja.conversation.application.gateway.ConversationProjectionRepository;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadProjection;
import io.github.kongweiguang.ja.plan.application.gateway.PlanWorkspacePort;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceAdmissionPort;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;

import java.util.Objects;

/** 将 Workspace owner 的打开目录能力收敛为 Plan 绑定值。 */
public final class PlanWorkspaceAcl implements PlanWorkspacePort {
    private final WorkspaceUseCase workspaces;
    private final ConversationProjectionRepository conversations;

    /** 组合根注入 Workspace 与 Thread 投影 owner，计划层只看到所属工作区的稳定身份。 */
    public PlanWorkspaceAcl(WorkspaceUseCase workspaces, ConversationProjectionRepository conversations) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
    }

    /** 只返回 identity 与根路径，隐藏 Workspace 实体及其策略。 */
    @Override
    public Binding requireOpenWorkspace(String workspaceId) {
        Workspace workspace = workspaces.requireOpenWorkspace(workspaceId);
        return new Binding(workspace.workspaceId(), workspace.root());
    }

    /**
     * 从 owner Thread 投影解析共享 Workspace gate，不因 Plan admission 再打开或探测物理目录。
     */
    @Override
    public WorkspaceAdmissionPort.Lease acquireAdmissionForThread(String threadId) {
        ThreadProjection thread = conversations.readThread(Objects.requireNonNull(threadId, "threadId"))
                .orElseThrow(() -> new IllegalStateException("Plan owner Thread is unavailable"));
        return workspaces.acquireAdmission(thread.workspaceId());
    }
}
