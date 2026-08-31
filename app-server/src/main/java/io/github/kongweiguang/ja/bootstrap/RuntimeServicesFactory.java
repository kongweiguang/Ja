// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase;
import io.github.kongweiguang.ja.catalog.port.in.CatalogUseCase;
import io.github.kongweiguang.ja.conversation.application.approval.ApprovalBroker;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.foundation.concurrent.ShutdownDeadline;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 为单一 JA-RPC 连接一次性发布已经组合完成的应用端口。
 */
public final class RuntimeServicesFactory {
    private final WorkspaceUseCase workspaces;
    private final ThreadUseCase threads;
    private final TurnUseCase turns;
    private final ContextCompactionUseCase compactions;
    private final ApprovalBroker approvals;
    private final CatalogUseCase catalog;
    private final AttachmentUseCase attachments;
    private final Consumer<ShutdownDeadline> closeAction;
    private final AtomicReference<RuntimeServices> opened = new AtomicReference<>();

    /**
     * 仅保存明确端口与一次性连接绑定，不延迟创建 Repository、配置或业务服务。
     */
    public RuntimeServicesFactory(WorkspaceUseCase workspaces, ThreadUseCase threads,
                                  TurnUseCase turns, ContextCompactionUseCase compactions,
                                  ApprovalBroker approvals,
                                  CatalogUseCase catalog,
                                  AttachmentUseCase attachments,
                                  Consumer<ShutdownDeadline> closeAction) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.threads = Objects.requireNonNull(threads, "threads");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.compactions = Objects.requireNonNull(compactions, "compactions");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
    }

    /**
     * 校验握手模式并一次性发布服务图；实例所有权转移到字段并由应用关闭链回收，
     * 因此不能在本方法局部关闭。
     */
    @SuppressWarnings("PMD.CloseResource")
    public synchronized RpcServiceBindings open(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        if (opened.get() != null) {
            throw new IllegalStateException("runtime services were already opened");
        }
        RuntimeServices services = new RuntimeServices(
                workspaces, threads, turns, compactions, approvals, catalog, attachments, closeAction);
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
