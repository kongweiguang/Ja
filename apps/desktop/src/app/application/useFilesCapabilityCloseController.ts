// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useRef } from "react";
import type { FilesWorkspaceLifecycle } from "@/features/workbench/files";
import type { LifecycleReader } from "./workbenchLifecyclePorts";

export interface FilesCapabilityCloseController {
  readonly current: LifecycleReader<FilesWorkspaceLifecycle>;
  readonly register: (lifecycle: FilesWorkspaceLifecycle | undefined) => void;
  readonly closeCapability: (workspaceId: string) => Promise<void>;
}

/**
 * Files 顶级 Tab 关闭只把 flush ACK 作为提交门槛；controller 会为保留编辑器草稿持续挂载，
 * 因此 ACK 后必须释放写入 fence，不能等待并不会发生的 React unmount。
 */
export function useFilesCapabilityCloseController(): FilesCapabilityCloseController {
  const lifecycleRef = useRef<FilesWorkspaceLifecycle | undefined>(undefined);
  const closeTaskRef = useRef<
    { readonly workspaceId: string; readonly promise: Promise<void> } | undefined
  >(undefined);

  /**
   * 注册回调只维护当前 Files lifecycle；Files 自身仍是编辑 buffer 和 flush 状态的唯一 owner。
   */
  const register = useCallback((lifecycle: FilesWorkspaceLifecycle | undefined): void => {
    lifecycleRef.current = lifecycle;
  }, []);

  /**
   * getter 只返回窄 lifecycle port，workspace/window 协调器无法访问 Files controller 的
   * 保存队列和编辑状态，因此不会形成第二个业务事实 owner。
   */
  const current = useCallback((): FilesWorkspaceLifecycle | undefined => lifecycleRef.current, []);

  /**
   * 同一 workspace 的重复关闭共用一个 flush。lease 仅覆盖 flush 事务并在 ACK 后立即释放；
   * Workspace 切换和 App Exit 由各自协调器持有更长事务，不能复用这里的短生命周期。
   */
  const closeCapability = useCallback((workspaceId: string): Promise<void> => {
    const pendingTask = closeTaskRef.current;
    if (pendingTask?.workspaceId === workspaceId) return pendingTask.promise;
    const lifecycle = lifecycleRef.current;
    if (lifecycle?.workspaceId !== workspaceId) return Promise.resolve();

    const task = (async (): Promise<void> => {
      const lease = await lifecycle.flushForWorkspaceChange();
      // flush ACK 已证明保存队列收敛；Tab Shell 随后提交受控关闭，不再需要保持编辑冻结。
      lease.release();
    })().finally(() => {
      closeTaskRef.current = undefined;
    });
    closeTaskRef.current = { workspaceId, promise: task };
    return task;
  }, []);

  return { current, register, closeCapability };
}
