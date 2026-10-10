// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.gateway;

import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceAdmissionPort;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Plan 消费方定义的 Workspace 绑定端口；绝对路径只在隐藏 Turn 请求冻结时短暂使用。
 */
@FunctionalInterface
public interface PlanWorkspacePort {
    /** 按稳定 Workspace 工作区标识 读取已打开的根目录绑定。 */
    Binding requireOpenWorkspace(String workspaceId);

    /**
     * 在 Plan 的持久批准/执行准入与 Workspace 根变更之间串行化；窄测试端口默认不持有生产资源。
     */
    default WorkspaceAdmissionPort.Lease acquireAdmissionForThread(String threadId) {
        return WorkspaceAdmissionPort.noOpLease();
    }

    /**
     * 已由 Workspace owner 校验的最小绑定值。
     * @param workspaceId Workspace 工作区标识
     * @param root 工作区根目录
     */
    record Binding(String workspaceId,
                   Path root) {
        /** 保留路径归属和值域校验，阻止空 identity 进入 Plan admission。 */
        public Binding {
            Objects.requireNonNull(workspaceId, "workspaceId");
            root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        }
    }
}

