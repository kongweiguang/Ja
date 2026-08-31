// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase;
import io.github.kongweiguang.ja.catalog.port.in.CatalogUseCase;
import io.github.kongweiguang.ja.conversation.application.approval.ApprovalBroker;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseCoordinator;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.foundation.concurrent.ShutdownDeadline;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 只发布组合完成的入站端口并拥有进程关闭边界，不承载任何业务解析逻辑。
 */
public final class RuntimeServices implements DeadlineCloseable {
    private final RpcServiceBindings bindings;
    private final Consumer<ShutdownDeadline> closeAction;
    private final DeadlineCloseCoordinator closeCoordinator = new DeadlineCloseCoordinator();

    /**
     * 固定一次连接需要的明确入站端口，避免 transport 取得 Solon 容器或 Service Locator。
     */
    public RuntimeServices(WorkspaceUseCase workspaces, ThreadUseCase threads, TurnUseCase turns,
                           ContextCompactionUseCase compactions, ApprovalBroker approvals, CatalogUseCase catalog,
                           AttachmentUseCase attachments,
                           Consumer<ShutdownDeadline> closeAction) {
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        this.bindings = new RpcServiceBindings(
                workspaces, threads, turns, compactions, approvals, catalog, attachments, this);
    }

    /**
     * 返回构造期冻结的端口集合；关闭开始后禁止新连接继续取得服务图。
     */
    public RpcServiceBindings bindings() {
        ensureOpen();
        return bindings;
    }

    /**
     * 启动一次默认期限关闭，重复调用共享同一完成结果。
     */
    @Override
    public void close() {
        close(ShutdownDeadline.start());
    }

    /**
     * 复用 RpcServer 创建的绝对期限，禁止组合根重新申请完整关闭预算。
     */
    @Override
    public void closeAt(long shutdownDeadlineNanos) {
        close(ShutdownDeadline.at(shutdownDeadlineNanos));
    }

    /**
     * 关闭整个 Solon 资源图，并把相同成功或失败结果发布给并发调用者。
     */
    public void close(ShutdownDeadline deadline) {
        closeCoordinator.close(deadline, "runtime services", this::closeOwnedResources);
    }

    /**
     * 执行组合根关闭动作，并在动作返回后拒绝把已耗尽的预算报告为正常完成。
     */
    private void closeOwnedResources(ShutdownDeadline deadline) {
        closeAction.accept(deadline);
        if (deadline.expired()) {
            throw ShutdownDeadline.forced("runtime services close budget expired", null);
        }
    }

    /**
     * 在关闭栅栏之后拒绝取得服务，防止持久化开始回收时出现新调用。
     */
    private void ensureOpen() {
        if (closeCoordinator.started()) {
            throw new IllegalStateException("runtime services are closed");
        }
    }
}
