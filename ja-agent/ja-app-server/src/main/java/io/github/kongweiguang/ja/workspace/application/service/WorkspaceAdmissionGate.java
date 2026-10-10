// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.application.service;

import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceAdmissionPort;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspaceBusyMessage;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 按 Workspace identity 共享 Turn/Plan 准入读锁与根目录重绑写锁；只保留有持有者的短期锁单元。
 */
public final class WorkspaceAdmissionGate implements WorkspaceAdmissionPort {
    private final ConcurrentHashMap<String, LockCell> cells = new ConcurrentHashMap<>();

    /**
     * admission 只保护同步提交窗口，确保 SQLite 中的运行事实先于重绑忙碌检查可见。
     */
    @Override
    public Lease acquire(String workspaceId) {
        String identity = requireWorkspaceId(workspaceId);
        LockCell cell = retain(identity);
        if (!cell.lock.readLock().tryLock()) {
            releaseReference(identity, cell);
            throw busy();
        }
        return () -> release(identity, cell, false);
    }

    /**
     * 根更新不能排队等待活动调用；锁竞争立即返回可重试忙碌，持锁后再查 durable Turn/Plan。
     */
    public Lease acquireRebind(String workspaceId) {
        String identity = requireWorkspaceId(workspaceId);
        LockCell cell = retain(identity);
        if (!cell.lock.writeLock().tryLock()) {
            releaseReference(identity, cell);
            throw busy();
        }
        return () -> release(identity, cell, true);
    }

    /**
     * 仅在 map 原子更新中增减引用数，避免已释放锁和新竞争者持有两个不同锁实例。
     */
    private LockCell retain(String workspaceId) {
        return cells.compute(workspaceId, (identity, cell) -> {
            LockCell retained = cell == null ? new LockCell() : cell;
            retained.references++;
            return retained;
        });
    }

    /**
     * 先解锁再归还引用；并发持有者若已取得同一单元，原子引用数会阻止其被移出 map。
     */
    private void release(String workspaceId, LockCell cell, boolean exclusive) {
        if (exclusive) {
            cell.lock.writeLock().unlock();
        } else {
            cell.lock.readLock().unlock();
        }
        releaseReference(workspaceId, cell);
    }

    /**
     * 清除最后一个租约释放的锁单元，避免长时间运行后以历史 Workspace 数量增长内存。
     */
    private void releaseReference(String workspaceId, LockCell cell) {
        cells.compute(workspaceId, (identity, current) -> {
            if (current != cell || current.references < 1) {
                throw new IllegalStateException("Workspace admission lock ownership changed");
            }
            current.references--;
            return current.references == 0 ? null : current;
        });
    }

    /**
     * 防止空 identity 进入共享锁表并造成无意义的全局争用。
     */
    private static String requireWorkspaceId(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId is required");
        }
        return workspaceId;
    }

    /** 根重绑撞上尚未完成持久提交的 admission 时不猜任务类别，只提示稍后重试。 */
    private static WorkspaceFailure busy() {
        return new WorkspaceFailure(WorkspaceFailure.Code.WORKSPACE_BUSY,
                WorkspaceBusyMessage.admissionInProgress());
    }

    /**
     * 锁单元被 map.compute 独占修改；公平锁避免长期 admission 持有者饿死重绑请求。
     */
    private static final class LockCell {
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
        private int references;
    }
}
