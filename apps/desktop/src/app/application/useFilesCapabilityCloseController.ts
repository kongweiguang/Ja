// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useRef } from "react";
import type { FilesWorkspaceCloseLease, FilesWorkspaceLifecycle } from "@/features/workbench/files";
import type { LifecycleReader } from "./workbenchLifecyclePorts";

interface PendingFilesCloseLease {
  readonly workspaceId: string;
  readonly lifecycle: FilesWorkspaceLifecycle;
  readonly lease: FilesWorkspaceCloseLease;
}

export interface FilesCapabilityCloseController {
  readonly current: LifecycleReader<FilesWorkspaceLifecycle>;
  readonly register: (lifecycle: FilesWorkspaceLifecycle | undefined) => void;
  readonly closeCapability: (workspaceId: string) => Promise<void>;
}

/**
 * Files 顶级 Tab 关闭需要跨越 flush ACK 与 React unmount 两个阶段，因此由独立 controller
 * 持有 single-flight task 和 lease；它不读取编辑 buffer，也不接管 Files domain 状态。
 */
export function useFilesCapabilityCloseController(): FilesCapabilityCloseController {
  const lifecycleRef = useRef<FilesWorkspaceLifecycle | undefined>(undefined);
  const closeTaskRef = useRef<
    { readonly workspaceId: string; readonly promise: Promise<void> } | undefined
  >(undefined);
  const closeLeaseRef = useRef<PendingFilesCloseLease | undefined>(undefined);

  /**
   * 注册回调只维护当前 Files lifecycle；组件卸载或 identity 变化时消费旧 lease，确保
   * flush 期间禁止的新编辑在受控 Tab 真正提交关闭后才恢复。
   */
  const register = useCallback((lifecycle: FilesWorkspaceLifecycle | undefined): void => {
    const previous = lifecycleRef.current;
    lifecycleRef.current = lifecycle;
    const pending = closeLeaseRef.current;
    if (
      pending !== undefined &&
      (lifecycle === undefined ? pending.lifecycle === previous : pending.lifecycle !== lifecycle)
    ) {
      pending.lease.release();
      closeLeaseRef.current = undefined;
    }
  }, []);

  /**
   * getter 只返回窄 lifecycle port，workspace/window 协调器无法访问 Files controller 的
   * 保存队列和编辑状态，因此不会形成第二个业务事实 owner。
   */
  const current = useCallback((): FilesWorkspaceLifecycle | undefined => lifecycleRef.current, []);

  /**
   * 同一 workspace 的重复关闭共用一个 flush；成功 lease 保留至 FilesWorkspace cleanup，
   * controller identity 已变化时立即释放，避免冻结后来挂载的新工作区。
   */
  const closeCapability = useCallback((workspaceId: string): Promise<void> => {
    const pendingLease = closeLeaseRef.current;
    if (pendingLease?.workspaceId === workspaceId) return Promise.resolve();
    const pendingTask = closeTaskRef.current;
    if (pendingTask?.workspaceId === workspaceId) return pendingTask.promise;
    const lifecycle = lifecycleRef.current;
    if (lifecycle?.workspaceId !== workspaceId) return Promise.resolve();

    const task = (async (): Promise<void> => {
      const lease = await lifecycle.flushForWorkspaceChange();
      if (lifecycleRef.current !== lifecycle) {
        lease.release();
        return;
      }
      closeLeaseRef.current = { workspaceId, lifecycle, lease };
    })().finally(() => {
      closeTaskRef.current = undefined;
    });
    closeTaskRef.current = { workspaceId, promise: task };
    return task;
  }, []);

  /**
   * 热重载或壳层卸载可能绕过 FilesWorkspace cleanup，最后一道 cleanup 必须释放 lease，
   * 但不主动触发保存，避免卸载阶段制造新的异步副作用。
   */
  useEffect(
    () => () => {
      closeLeaseRef.current?.lease.release();
      closeLeaseRef.current = undefined;
    },
    [],
  );

  return { current, register, closeCapability };
}
