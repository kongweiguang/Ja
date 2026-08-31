// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 只向 workspace 切换协调器暴露终端的关闭与恢复能力，PTY session、布局和渲染状态
 * 继续由 Terminal controller 独占，避免 App 壳层成为第二个终端状态 owner。
 */
export interface TerminalWorkspaceLifecycle {
  readonly workspaceId: string;
  readonly closeForWorkspaceChange: () => Promise<void>;
  readonly resumeAfterWorkspaceChange: () => void;
}

/**
 * getter 让多个 lifecycle 协调器读取同一份已挂载端口，但不允许它们修改 registry，
 * 从类型边界上阻止窗口关闭与 workspace 切换竞争 owner 身份。
 */
export type LifecycleReader<T> = () => T | undefined;
