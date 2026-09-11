// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc;

import io.github.kongweiguang.ja.catalog.port.in.CatalogUseCase;
import io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase;
import io.github.kongweiguang.ja.attachment.port.in.AttachmentPreviewUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ApprovalUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionUseCase;
import io.github.kongweiguang.ja.conversation.port.in.InteractionUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.goal.port.in.GoalUseCase;
import io.github.kongweiguang.ja.task.port.in.TaskUseCase;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import io.github.kongweiguang.ja.workspace.port.in.WorkspacePathSearchUseCase;

import java.util.Objects;

/**
 * 汇集一次 RPC 连接需要的明确入站端口。
 *
 * <p>该值只负责组合，不定义业务 DTO、校验或实现方法，因此各域 adapter 不会反向依赖 transport。</p>
 */
public record RpcServiceBindings(WorkspaceUseCase workspaces, WorkspacePathSearchUseCase workspacePathSearch,
                                 ThreadUseCase threads,
                                 TurnUseCase turns, ContextCompactionUseCase compactions,
                                 ApprovalUseCase approvals,
                                 CatalogUseCase catalog, AttachmentUseCase attachments,
                                 AttachmentPreviewUseCase attachmentPreviews,
                                 TaskUseCase tasks,
                                 GoalUseCase goals,
                                 InteractionUseCase interactions,
                                 DeadlineCloseable lifecycle) {
    /**
     * 在握手发布服务图前一次性验证所有必需端口，禁止运行中降级为空实现。
     */
    public RpcServiceBindings {
        Objects.requireNonNull(workspaces, "workspaces");
        Objects.requireNonNull(workspacePathSearch, "workspacePathSearch");
        Objects.requireNonNull(threads, "threads");
        Objects.requireNonNull(turns, "turns");
        Objects.requireNonNull(compactions, "compactions");
        Objects.requireNonNull(approvals, "approvals");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(attachments, "attachments");
        Objects.requireNonNull(attachmentPreviews, "attachmentPreviews");
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(goals, "goals");
        Objects.requireNonNull(interactions, "interactions");
        Objects.requireNonNull(lifecycle, "lifecycle");
    }
}
