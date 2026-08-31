// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { FilesSaveCoordinator } from "../FilesSaveCoordinator";
import type { FilesWorkspaceCloseLease, OpenDocument } from "../types";
import type { ControllerRef, StateWriter } from "./controllerPorts";

export interface LifecycleUseCases {
  releaseLifecycleFence: () => void;
  flushForWorkspaceChange: () => Promise<FilesWorkspaceCloseLease>;
}

interface LifecycleUseCasesContext {
  saveCoordinator: FilesSaveCoordinator;
  mounted: ControllerRef<boolean>;
  lifecycleFence: ControllerRef<boolean>;
  lifecycleLease: ControllerRef<Promise<void> | undefined>;
  lifecycleLeaseHolders: ControllerRef<number>;
  documents: ControllerRef<Record<string, OpenDocument>>;
  scheduledSave: ControllerRef<(path: string) => void>;
  flush: ControllerRef<(path: string) => Promise<void>>;
  inFlight: ControllerRef<Map<string, number>>;
  externalConflictChecks: ControllerRef<Set<string>>;
  setLifecycleClosing: StateWriter<boolean>;
  onNotice?: (message: string) => void;
}

/**
 * 创建无状态 workspace lifecycle 协作者。fence、共享 barrier 与 holder 计数均由主
 * hook 持有，协作者只实现冻结、flush、失败恢复和 lease 释放顺序。
 */
export function createLifecycleUseCases(context: LifecycleUseCasesContext): LifecycleUseCases {
  /**
   * 解除失败切换建立的编辑 fence，并只为尚未尝试的 dirty 草稿恢复末键计时；
   * saveError/conflict 保持暂停，避免无界自动重试。
   */
  function releaseLifecycleFence(): void {
    if (!context.mounted.current) return;
    context.lifecycleFence.current = false;
    context.lifecycleLease.current = undefined;
    context.lifecycleLeaseHolders.current = 0;
    context.setLifecycleClosing(false);
    /** fence 解除后只恢复 dirty 保存，错误与冲突必须由用户显式处理。 */
    for (const document of Object.values(context.documents.current)) {
      if (document.status === "dirty") context.scheduledSave.current(document.path);
    }
  }

  /**
   * workspace 替换前冻结新编辑、取消 debounce 并等待每条串行保存队列；任何
   * conflict、保存失败或外部核对未完成都会拒绝切换并恢复旧 UI。
   */
  async function flushForWorkspaceChange(): Promise<FilesWorkspaceCloseLease> {
    let barrier = context.lifecycleLease.current;
    if (barrier === undefined) {
      context.lifecycleFence.current = true;
      context.setLifecycleClosing(true);
      context.saveCoordinator.cancelScheduled();
      barrier = (async (): Promise<void> => {
        /** 已知冲突先失败，禁止在切换期间偷偷覆盖外部版本。 */
        const conflict = Object.values(context.documents.current).find(
          (document) => document.status === "conflict",
        );
        if (conflict !== undefined) throw new Error("存在尚未处理的文件冲突");
        const pendingPaths = Object.values(context.documents.current)
          .filter((document) => !document.readOnly && document.status !== "clean")
          .map((document) => document.path);
        await Promise.all(pendingPaths.map((path) => context.flush.current(path)));
        await Promise.all(context.saveCoordinator.pendingTasks());

        /** ACK 之后再次核对全部状态与竞态集合，不能只依赖 Promise 已完成。 */
        const unsafe =
          Object.values(context.documents.current).find(
            (document) => document.status !== "clean",
          ) ??
          Object.values(context.documents.current).find(
            (document) =>
              context.saveCoordinator.hasTask(document.path) ||
              context.inFlight.current.has(document.path) ||
              context.externalConflictChecks.current.has(document.path),
          );
        if (unsafe !== undefined) throw new Error("文件草稿未能安全保存");
      })().catch((error: unknown) => {
        releaseLifecycleFence();
        context.onNotice?.("当前工作区仍有未保存或冲突的文件，请处理后再切换。");
        throw error;
      });
      context.lifecycleLease.current = barrier;
    }
    await barrier;
    context.lifecycleLeaseHolders.current += 1;
    let released = false;
    return {
      /** 多个切换 intent 共享一次 flush，只有最后一个 lease 退出才重新开放编辑。 */
      release: () => {
        if (released) return;
        released = true;
        context.lifecycleLeaseHolders.current = Math.max(
          0,
          context.lifecycleLeaseHolders.current - 1,
        );
        if (context.lifecycleLeaseHolders.current === 0) releaseLifecycleFence();
      },
    };
  }

  return { releaseLifecycleFence, flushForWorkspaceChange };
}
