// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useRef } from "react";
import type { FilesWorkspaceLifecycle } from "@/features/workbench/files";
import type { PreviewWorkspaceLifecycle } from "../useJaWorkbench";
import type { LifecycleReader, TerminalWorkspaceLifecycle } from "./workbenchLifecyclePorts";

interface TerminalCloseFallback {
  readonly closeAll: (workspaceId: string) => Promise<void>;
}

export interface WorkspaceChangeController {
  readonly registerTerminal: (lifecycle: TerminalWorkspaceLifecycle | undefined) => void;
  readonly registerPreview: (lifecycle: PreviewWorkspaceLifecycle | undefined) => void;
  readonly currentPreview: LifecycleReader<PreviewWorkspaceLifecycle>;
  readonly beforeChange: (
    previousWorkspaceId: string,
    nextWorkspaceId?: string,
  ) => Promise<(() => void) | undefined>;
}

/**
 * workspace 切换是 Files flush、Terminal close 与 Preview ACK 的事务边界；该协调器只保存
 * 三个窄 lifecycle port，不持有文件、PTY、URL 或 workspace 领域事实。
 */
export function useWorkspaceChangeController(
  currentFiles: LifecycleReader<FilesWorkspaceLifecycle>,
  terminalFallback: TerminalCloseFallback,
): WorkspaceChangeController {
  const terminalRef = useRef<TerminalWorkspaceLifecycle | undefined>(undefined);
  const previewRef = useRef<PreviewWorkspaceLifecycle | undefined>(undefined);

  /** Terminal controller 挂载期间注册精确 workspace 端口，卸载后立即清空旧 identity。 */
  const registerTerminal = useCallback(
    (lifecycle: TerminalWorkspaceLifecycle | undefined): void => {
      terminalRef.current = lifecycle;
    },
    [],
  );

  /** Preview controller 只注册 ACK-first close port，不向壳层泄漏 child window label。 */
  const registerPreview = useCallback((lifecycle: PreviewWorkspaceLifecycle | undefined): void => {
    previewRef.current = lifecycle;
  }, []);

  /** 窗口关闭协调器通过只读 getter 复用当前 Preview ACK port，不能改写 registry。 */
  const currentPreview = useCallback(
    (): PreviewWorkspaceLifecycle | undefined => previewRef.current,
    [],
  );

  /**
   * 先冻结并刷盘 Files，再关闭旧 workspace PTY，最后等待 Preview child 的 native ACK。
   * 任一阶段失败都释放 Files lease；若终端已关闭则重挂 controller，保持旧 workspace 可继续使用。
   */
  const beforeChange = useCallback(
    async (
      previousWorkspaceId: string,
      nextWorkspaceId?: string,
    ): Promise<(() => void) | undefined> => {
      void nextWorkspaceId;
      const filesLifecycle = currentFiles();
      const terminalLifecycle =
        terminalRef.current?.workspaceId === previousWorkspaceId ? terminalRef.current : undefined;
      const previewLifecycle =
        previewRef.current?.workspaceId === previousWorkspaceId ? previewRef.current : undefined;
      const lease =
        filesLifecycle?.workspaceId === previousWorkspaceId
          ? await filesLifecycle.flushForWorkspaceChange()
          : undefined;
      let terminalClosed = false;

      try {
        if (terminalLifecycle === undefined) await terminalFallback.closeAll(previousWorkspaceId);
        else await terminalLifecycle.closeForWorkspaceChange();
        terminalClosed = true;
        await previewLifecycle?.closeForWorkspaceChange();

        let released = false;
        /** 合并 lease 释放与终端恢复，抵御 controller finally 和晚 intent 的重复调用。 */
        const releaseWorkspaceChange = (): void => {
          if (released) return;
          released = true;
          lease?.release();
          terminalLifecycle?.resumeAfterWorkspaceChange();
        };
        return releaseWorkspaceChange;
      } catch (error: unknown) {
        lease?.release();
        if (terminalClosed) terminalLifecycle?.resumeAfterWorkspaceChange();
        throw error;
      }
    },
    [currentFiles, terminalFallback],
  );

  return { registerTerminal, registerPreview, currentPreview, beforeChange };
}
