// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.application.gateway;

/**
 * 让 Turn、Plan 与 Workspace 重绑共用同一短期准入屏障，不把目录或运行时租约泄漏到彼此模块。
 */
public interface WorkspaceAdmissionPort {
    /**
     * 为一个 Workspace 的同步持久准入保留读侧租约，直到 admission 已经可被重绑检查看见。
     */
    Lease acquire(String workspaceId);

    /**
     * 只供未涉及 Workspace 准入的旧测试构造器使用；生产组合根必须注入 Workspace owner。
     */
    static Lease noOpLease() {
        return () -> { };
    }

    /**
     * 兼容不经过 Workspace 边界的纯隔离测试；生产组合根不可使用该实现。
     */
    static WorkspaceAdmissionPort unlocked() {
        return ignored -> noOpLease();
    }

    /**
     * 用 try-with-resources 保证 admission 异常路径也释放对应工作区读锁。
     */
    @FunctionalInterface
    interface Lease extends AutoCloseable {
        /**
         * 释放当前线程持有的准入屏障。
         */
        @Override
        void close();
    }
}
