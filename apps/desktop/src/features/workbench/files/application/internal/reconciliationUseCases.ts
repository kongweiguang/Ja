// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type {
  FilesWorkspaceOperations,
  WorkspaceChangedEvent,
  WorkspaceTreeEntryDto,
} from "../ports";
import type { OpenDocument } from "../types";
import { parentPath } from "../../domain/filesModel";
import type { FileRevision } from "../../domain/types";
import type { ExternalRefreshMode } from "./documentUseCases";
import type { ControllerRef } from "./controllerPorts";

export interface ReconciliationUseCases {
  reconcileAuthoritativeWorkspace: (options?: ReconciliationOptions) => void;
  handleWorkspaceChanged: (event: WorkspaceChangedEvent) => void;
}

export interface ReconciliationOptions {
  readonly rescanWatcher?: boolean;
}

interface ReconciliationUseCasesContext {
  workspaceId: string;
  watchRescan: FilesWorkspaceOperations["watchRescan"];
  watcherReady: ControllerRef<boolean>;
  documents: ControllerRef<Record<string, OpenDocument>>;
  workspaceGeneration: ControllerRef<number>;
  reconciliationTask: ControllerRef<Promise<void> | undefined>;
  inFlight: ControllerRef<Map<string, number>>;
  pendingMutations: ControllerRef<Map<string, string>>;
  deferredWatch: ControllerRef<Map<string, WorkspaceChangedEvent>>;
  loadDirectory: (relativePath: string) => Promise<readonly WorkspaceTreeEntryDto[] | undefined>;
  reloadExternal: (
    path: string,
    mode: ExternalRefreshMode,
    expectedRevision?: FileRevision,
  ) => Promise<void>;
  onNotice?: (message: string) => void;
}

/**
 * 创建无状态 Watcher reconciliation 协作者。Watcher event 永远只是 hint；主 hook
 * 持有 generation、共享任务与 buffer，协作者负责回到权威 Tree/Read 收敛。
 */
export function createReconciliationUseCases(
  context: ReconciliationUseCasesContext,
): ReconciliationUseCases {
  /**
   * 合并同一 generation 的全量对账；面板重新激活时 Watcher 尚未完成启动，调用方可跳过
   * rescan 并直接读取权威 Tree/Read，避免把正常启动时序误报成扫描失败。
   */
  function reconcileAuthoritativeWorkspace(options?: ReconciliationOptions): void {
    if (context.reconciliationTask.current !== undefined) return;
    const generation = context.workspaceGeneration.current;
    const rescanWatcher = (options?.rescanWatcher ?? true) && context.watcherReady.current;
    const baselines = Object.entries(context.documents.current).map(([path, document]) => ({
      path,
      revision: document.revision,
    }));
    const task = (async (): Promise<void> => {
      if (rescanWatcher) {
        try {
          await context.watchRescan?.({ workspaceId: context.workspaceId });
        } catch {
          if (generation === context.workspaceGeneration.current)
            context.onNotice?.("工作区重新扫描失败，正在直接刷新文件状态。");
        }
      }
      if (generation !== context.workspaceGeneration.current) return;
      await context.loadDirectory("");
      if (generation !== context.workspaceGeneration.current) return;
      await Promise.all(
        baselines.map(async ({ path, revision }) => {
          const document = context.documents.current[path];
          if (document !== undefined)
            await context.reloadExternal(
              path,
              document.status === "clean" ? "auto" : "conflict",
              revision,
            );
        }),
      );
    })().catch(() => {
      if (generation === context.workspaceGeneration.current)
        context.onNotice?.("工作区状态刷新失败，请手动刷新。");
    });
    context.reconciliationTask.current = task;
    void task.finally(() => {
      if (context.reconciliationTask.current === task)
        context.reconciliationTask.current = undefined;
    });
  }

  /**
   * 有界 Watcher event 只刷新父 page 和可选已打开文件；保存事务中的 hint 延迟到
   * ACK 后判断回声，overflow 则转全量权威对账。
   */
  function handleWorkspaceChanged(event: WorkspaceChangedEvent): void {
    if (event.requiresRescan) {
      reconcileAuthoritativeWorkspace();
      return;
    }
    void context.loadDirectory(parentPath(event.relativePath));
    const document = context.documents.current[event.relativePath];
    if (document === undefined) return;
    if (
      context.inFlight.current.has(event.relativePath) ||
      context.pendingMutations.current.has(event.relativePath)
    ) {
      context.deferredWatch.current.set(event.relativePath, event);
      return;
    }
    void context.reloadExternal(
      event.relativePath,
      document.status === "clean" ? "auto" : "conflict",
      document.revision,
    );
  }

  return { reconcileAuthoritativeWorkspace, handleWorkspaceChanged };
}
