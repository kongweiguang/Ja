// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc;

import io.github.kongweiguang.ja.agent.application.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentPreviewUseCase;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase;
import io.github.kongweiguang.ja.catalog.application.api.CatalogUseCase;
import io.github.kongweiguang.ja.catalog.application.api.ThreadMcpUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ApprovalUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ContextCompactionUseCase;
import io.github.kongweiguang.ja.conversation.application.api.InteractionUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase;
import io.github.kongweiguang.ja.review.application.api.ReviewUseCase;
import io.github.kongweiguang.ja.plan.application.api.PlanUseCase;
import io.github.kongweiguang.ja.collaboration.application.api.TaskUseCase;
import io.github.kongweiguang.ja.workspace.application.api.WorkspacePathSearchUseCase;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;

import java.util.Objects;

/**
 * 汇集一次 RPC 连接需要的明确入站端口。
 *
 * <p>该值只负责组合明确入站端口，不定义业务 DTO 或实现方法，因此各域 adapter 不会反向依赖 transport。</p>
 *
 * @param workspaces 工作区列表
 * @param workspacePathSearch 工作区路径搜索
 * @param threads 对话列表
 * @param threadMcp 对话的 MCP 服务
 * @param turns 执行轮次列表
 * @param compactions 上下文压缩列表
 * @param approvals 审批列表
 * @param catalog 目录
 * @param attachments 附件列表
 * @param attachmentPreviews 附件预览列表
 * @param tasks 任务列表
 * @param plans Plan 列表
 * @param interactions 交互列表
 * @param processes Java 唯一进程会话所有者
 * @param reviews 复用 Turn Runtime 的审查用例
 * @param lifecycle 生命周期
 */
public record RpcServiceBindings(WorkspaceUseCase workspaces,
                                 WorkspacePathSearchUseCase workspacePathSearch,
                                 ThreadUseCase threads,
                                 ThreadMcpUseCase threadMcp,
                                 TurnUseCase turns,
                                 ContextCompactionUseCase compactions,
                                 ApprovalUseCase approvals,
                                 CatalogUseCase catalog,
                                 AttachmentUseCase attachments,
                                 AttachmentPreviewUseCase attachmentPreviews,
                                 TaskUseCase tasks,
                                 PlanUseCase plans,
                                 InteractionUseCase interactions,
                                 ProcessSessionUseCase processes,
                                 ReviewUseCase reviews,
                                 DeadlineCloseable lifecycle) {
    /**
     * 在握手发布服务图前一次性验证所有必需端口，Thread MCP 状态也必须来自显式会话级用例。
     */
    public RpcServiceBindings {
        Objects.requireNonNull(workspaces, "workspaces");
        Objects.requireNonNull(workspacePathSearch, "workspacePathSearch");
        Objects.requireNonNull(threads, "threads");
        Objects.requireNonNull(threadMcp, "threadMcp");
        Objects.requireNonNull(turns, "turns");
        Objects.requireNonNull(compactions, "compactions");
        Objects.requireNonNull(approvals, "approvals");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(attachments, "attachments");
        Objects.requireNonNull(attachmentPreviews, "attachmentPreviews");
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(plans, "plans");
        Objects.requireNonNull(interactions, "interactions");
        Objects.requireNonNull(processes, "processes");
        Objects.requireNonNull(reviews, "reviews");
        Objects.requireNonNull(lifecycle, "lifecycle");
    }
}
