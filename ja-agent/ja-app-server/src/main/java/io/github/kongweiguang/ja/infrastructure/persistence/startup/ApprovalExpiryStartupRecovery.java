// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.startup;

import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.collaboration.application.service.TaskCoordinator;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;

import java.util.Objects;

/**
 * Solon 启动屏障：只有 SideChat purge、Engine 与 Task owner 都就绪后才重挂审批到期，不恢复任何 Driver。
 */
public final class ApprovalExpiryStartupRecovery {
    private final int scannedPages;

    /**
     * Bean 构造时同步完成有界恢复扫描，使 RPC ready 前所有可恢复审批都已登记或持久收敛。
     */
    public ApprovalExpiryStartupRecovery(StartupMaintenanceService maintenance,
                                         ApprovalExpiryRecoveryListener listener,
                                         AgentEngine engine,
                                         ThreadUseCase threads,
                                         TaskCoordinator tasks) {
        Objects.requireNonNull(maintenance, "maintenance").requireInitialized();
        scannedPages = Objects.requireNonNull(listener, "listener").start(
                Objects.requireNonNull(engine, "engine"), Objects.requireNonNull(threads, "threads"),
                Objects.requireNonNull(tasks, "tasks"), maintenance);
    }

    /**
     * 暴露安全摘要计数供启动集成测试确认扫描确实运行，不返回 Thread 或审批正文。
     */
    public int scannedPages() {
        return scannedPages;
    }
}
