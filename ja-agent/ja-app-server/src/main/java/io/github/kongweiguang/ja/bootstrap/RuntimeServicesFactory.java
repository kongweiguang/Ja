// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.concurrent.ShutdownDeadline;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
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
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.workspace.application.api.WorkspacePathSearchUseCase;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 为单一 JA-RPC 连接一次性发布已经组合完成的应用端口。
 */
public final class RuntimeServicesFactory {
    private final WorkspaceUseCase workspaces;
    private final WorkspacePathSearchUseCase workspacePathSearch;
    private final ThreadUseCase threads;
    private final ThreadMcpUseCase threadMcp;
    private final TurnUseCase turns;
    private final ContextCompactionUseCase compactions;
    private final ApprovalUseCase approvals;
    private final CatalogUseCase catalog;
    private final AttachmentUseCase attachments;
    private final AttachmentPreviewUseCase attachmentPreviews;
    private final TaskUseCase tasks;
    private final PlanUseCase plans;
    private final InteractionUseCase interactions;
    private final ProcessSessionUseCase processes;
    private final ReviewUseCase reviews;
    private final JsonValueCodec jsonValueCodec;
    private final Consumer<ShutdownDeadline> closeAction;
    private final AtomicReference<RuntimeServices> opened = new AtomicReference<>();

    /**
     * 仅保存明确端口与一次性连接绑定，不延迟创建 Repository、配置或业务服务。
     */
    public RuntimeServicesFactory(WorkspaceUseCase workspaces, WorkspacePathSearchUseCase workspacePathSearch,
                                  ThreadUseCase threads, ThreadMcpUseCase threadMcp,
                                  TurnUseCase turns, ContextCompactionUseCase compactions,
                                  ApprovalUseCase approvals,
                                  CatalogUseCase catalog,
                                  AttachmentUseCase attachments, AttachmentPreviewUseCase attachmentPreviews,
                                  TaskUseCase tasks,
                                  PlanUseCase plans,
                                  InteractionUseCase interactions,
                                  ProcessSessionUseCase processes,
                                  ReviewUseCase reviews,
                                  JsonValueCodec jsonValueCodec,
                                  Consumer<ShutdownDeadline> closeAction) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.workspacePathSearch = Objects.requireNonNull(workspacePathSearch, "workspacePathSearch");
        this.threads = Objects.requireNonNull(threads, "threads");
        this.threadMcp = Objects.requireNonNull(threadMcp, "threadMcp");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.compactions = Objects.requireNonNull(compactions, "compactions");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.attachmentPreviews = Objects.requireNonNull(attachmentPreviews, "attachmentPreviews");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.processes = Objects.requireNonNull(processes, "processes");
        this.reviews = Objects.requireNonNull(reviews, "reviews");
        this.jsonValueCodec = Objects.requireNonNull(jsonValueCodec, "jsonValueCodec");
        if (attachments != attachmentPreviews) {
            throw new IllegalArgumentException("attachment and preview ports must share one owner");
        }
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
    }

    /**
     * 返回 Runtime 共用的 JSON 值边界，终答 Schema 不得另建解析策略。
     */
    JsonValueCodec jsonValueCodec() {
        return jsonValueCodec;
    }

    /**
     * 校验握手模式并一次性发布服务图；MCP 状态端口与 Thread 查询端口在同一连接代际冻结，
     * 实例所有权转移到字段并由应用关闭链回收，因此不能在本方法局部关闭。
     */
    @SuppressWarnings("PMD.CloseResource")
    public synchronized RpcServiceBindings open(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        if (opened.get() != null) {
            throw new IllegalStateException("runtime services were already opened");
        }
        RuntimeServices services = new RuntimeServices(
                workspaces, workspacePathSearch, threads, threadMcp, turns, compactions, approvals, catalog,
                attachments,
                attachmentPreviews, tasks, plans, interactions, processes, reviews, closeAction);
        opened.set(services);
        return services.bindings();
    }

    /**
     * 仅供组合身份测试读取已发布实例，不创建或修改运行时状态。
     */
    RuntimeServices openedServices() {
        return opened.get();
    }
}
