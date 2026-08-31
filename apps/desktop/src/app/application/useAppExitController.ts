// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useEffect, useRef } from "react";
import type { FilesWorkspaceLifecycle } from "@/features/workbench/files";
import type { PreviewWorkspaceLifecycle } from "../useJaWorkbench";
import type { LifecycleReader } from "./workbenchLifecyclePorts";

interface AppExitObservation {
  readonly dispose: () => void;
}

/**
 * 应用层只依赖退出事务的提交/取消语义，不识别 Tauri event、command 或 window DTO；
 * composition 依靠结构化类型把 native adapter 收敛到这个窄端口。
 */
interface AppExitRequest {
  readonly commit: () => Promise<void>;
  readonly cancel: () => Promise<void>;
}

export interface AppExitObserverPort {
  readonly observe: (onExitRequested: (request: AppExitRequest) => void) => AppExitObservation;
}

/**
 * 托盘退出先取得 Files flush lease，再等待 Preview child close ACK，最后提交原生退出；
 * 普通窗口隐藏不经过这里，single-flight 只约束真正会销毁进程的动作。
 */
export function useAppExitController(
  { observe }: AppExitObserverPort,
  currentFiles: LifecycleReader<FilesWorkspaceLifecycle>,
  currentPreview: LifecycleReader<PreviewWorkspaceLifecycle>,
  onFailure: () => void,
): void {
  const attemptRef = useRef<Promise<void> | undefined>(undefined);

  useEffect(() => {
    let disposed = false;
    const observer = observe((request) => {
      if (disposed || attemptRef.current !== undefined) return;
      let lease:
        | Awaited<ReturnType<FilesWorkspaceLifecycle["flushForWorkspaceChange"]>>
        | undefined;
      const attempt = (async (): Promise<void> => {
        try {
          const lifecycle = currentFiles();
          lease = lifecycle === undefined ? undefined : await lifecycle.flushForWorkspaceChange();
          if (disposed) {
            lease?.release();
            await request.cancel().catch(() => undefined);
            return;
          }
          await currentPreview()?.closeForWorkspaceChange();
          if (disposed) {
            lease?.release();
            await request.cancel().catch(() => undefined);
            return;
          }
          await request.commit();
        } catch {
          lease?.release();
          await request.cancel().catch(() => undefined);
          if (!disposed) onFailure();
        } finally {
          attemptRef.current = undefined;
        }
      })();
      attemptRef.current = attempt;
    });

    return () => {
      disposed = true;
      observer.dispose();
    };
  }, [currentFiles, currentPreview, observe, onFailure]);
}
