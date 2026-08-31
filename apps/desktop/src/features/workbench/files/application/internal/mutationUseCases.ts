// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { FilesSaveCoordinator } from "../FilesSaveCoordinator";
import type {
  FilesWorkspaceOperations,
  WorkspaceChangedEvent,
  WorkspaceTreeEntryDto,
} from "../ports";
import type { OpenDocument, TrashRequest } from "../types";
import { createMutationId, entryName, parentPath, removeTreeNode } from "../../domain/filesModel";
import type { FileRevision, WorkspaceFileNode } from "../../domain/types";
import { isSameOrDescendantPath, joinRelativePath, remapMovedPath } from "./filePathPolicy";
import { isRecycleUnavailableError } from "./nativeErrorPolicy";
import type { ControllerRef, StateWriter } from "./controllerPorts";

export interface TrashOperationSecret {
  workspaceId: string;
  workspaceGeneration: number;
  requestId: number;
  relativePath: string;
  expectedRevision: FileRevision;
  operationToken: string;
  expiresAtUnixMillis: number;
}

export interface MutationUseCases {
  createEntry: (parent: string, name: string, kind: "file" | "directory") => Promise<void>;
  moveEntry: (node: WorkspaceFileNode, targetDirectory: string, newName?: string) => Promise<void>;
  renameEntry: (node: WorkspaceFileNode, newName: string) => Promise<void>;
  prepareTrash: (node: WorkspaceFileNode) => Promise<void>;
  cancelTrash: () => void;
  confirmTrash: () => Promise<void>;
  importNativeDrop: (dropToken: string, targetDirectory: string) => Promise<void>;
}

interface MutationUseCasesContext {
  workspaceId: string;
  operations: FilesWorkspaceOperations;
  saveCoordinator: FilesSaveCoordinator;
  loadDirectory: (relativePath: string) => Promise<readonly WorkspaceTreeEntryDto[] | undefined>;
  openDocument: (path: string) => Promise<void>;
  commitDocuments: (
    update: (current: Record<string, OpenDocument>) => Record<string, OpenDocument>,
  ) => void;
  documents: ControllerRef<Record<string, OpenDocument>>;
  inFlight: ControllerRef<Map<string, number>>;
  pendingMutations: ControllerRef<Map<string, string>>;
  deferredWatch: ControllerRef<Map<string, WorkspaceChangedEvent>>;
  externalReads: ControllerRef<Map<string, number>>;
  externalConflictChecks: ControllerRef<Set<string>>;
  revisions: ControllerRef<Map<string, FileRevision>>;
  scheduledSave: ControllerRef<(path: string) => void>;
  workspaceGeneration: ControllerRef<number>;
  trashRequestSequence: ControllerRef<number>;
  trashOperation: ControllerRef<TrashOperationSecret | undefined>;
  trashRequest: ControllerRef<TrashRequest | undefined>;
  setNodes: StateWriter<WorkspaceFileNode[]>;
  setDocumentOrder: StateWriter<string[]>;
  setActivePath: StateWriter<string | undefined>;
  setSelectedPath: StateWriter<string | undefined>;
  setTrashRequest: StateWriter<TrashRequest | undefined>;
  onNotice?: (message: string) => void;
}

/** 特殊节点只参与树投影；link/reparse/other 不进入 Move、Rename 或 Trash mutation。 */
function isMutableEntry(node: WorkspaceFileNode): boolean {
  return node.kind === "file" || node.kind === "directory";
}

/**
 * 创建无状态文件 mutation 协作者。CAS cache、文档、Trash token 与 generation 全由
 * 主 hook 注入；本协作者只串联 Create/Move/Trash/Drop 用例及成功后的投影收敛。
 */
export function createMutationUseCases(context: MutationUseCasesContext): MutationUseCases {
  /** 通过类型化 mutation 创建条目，并只刷新所属父 page。 */
  async function createEntry(
    parent: string,
    name: string,
    kind: "file" | "directory",
  ): Promise<void> {
    if (context.operations.createEntry === undefined) return;
    const relativePath = joinRelativePath(parent, name);
    try {
      await context.operations.createEntry({
        workspaceId: context.workspaceId,
        relativePath,
        expectedRevision: null,
        mutationId: createMutationId(),
        kind,
      });
      await context.loadDirectory(parent);
      if (kind === "file") await context.openDocument(relativePath);
    } catch {
      context.onNotice?.("创建失败，文件可能已存在。");
    }
  }

  /** 执行类型化 Move/Rename，成功后以同一映射原子迁移所有已打开后代。 */
  async function moveEntry(
    node: WorkspaceFileNode,
    targetDirectory: string,
    newName?: string,
  ): Promise<void> {
    if (context.operations.moveEntry === undefined) return;
    if (!isMutableEntry(node)) {
      context.onNotice?.("该特殊文件系统节点仅供查看，不能在 Ja 中移动或重命名。");
      return;
    }
    const saveInProgress =
      [...context.inFlight.current.keys()].some((path) =>
        isSameOrDescendantPath(path, node.path),
      ) ||
      [...context.pendingMutations.current.keys()].some((path) =>
        isSameOrDescendantPath(path, node.path),
      ) ||
      [...context.externalConflictChecks.current].some((path) =>
        isSameOrDescendantPath(path, node.path),
      );
    if (saveInProgress) {
      context.onNotice?.("文件正在保存，请保存完成后再移动。");
      return;
    }
    const expectedRevision =
      context.revisions.current.get(node.path) ?? context.documents.current[node.path]?.revision;
    /** Move 没有完整 CAS identity 时 fail-closed，禁止凭路径猜测覆盖目标。 */
    if (expectedRevision === undefined) {
      context.onNotice?.("文件版本未知，请先刷新文件树。");
      return;
    }
    try {
      const result = await context.operations.moveEntry({
        workspaceId: context.workspaceId,
        relativePath: node.path,
        expectedRevision,
        mutationId: createMutationId(),
        targetDirectory,
        ...(newName === undefined ? {} : { newName }),
      });
      const oldParent = parentPath(node.path);
      const nextPath = joinRelativePath(targetDirectory, newName ?? entryName(node.path));
      /** native ACK 后先迁移 renderer identity，再刷新父 page；旧路径不能继续接收保存。 */
      if (nextPath !== node.path) {
        const reschedulePaths: string[] = [];
        for (const path of Object.keys(context.documents.current)) {
          if (isSameOrDescendantPath(path, node.path)) context.saveCoordinator.cancel(path);
        }
        /** 旧路径的 Watcher hint 和外部读取晚结果全部失效，不能伪装成新路径事实。 */
        for (const path of context.deferredWatch.current.keys()) {
          if (isSameOrDescendantPath(path, node.path)) context.deferredWatch.current.delete(path);
        }
        for (const path of context.externalReads.current.keys()) {
          if (isSameOrDescendantPath(path, node.path)) context.externalReads.current.delete(path);
        }
        const nextRevisions = new Map<string, FileRevision>();
        for (const [path, revision] of context.revisions.current) {
          const mappedPath = remapMovedPath(path, node.path, nextPath);
          nextRevisions.set(mappedPath, mappedPath === nextPath ? result.revision : revision);
        }
        nextRevisions.set(nextPath, result.revision);
        context.revisions.current = nextRevisions;
        context.commitDocuments((current) => {
          const next: Record<string, OpenDocument> = {};
          /** 目录 Move 连同已打开后代迁移，确保每个相对路径始终只有一个 document owner。 */
          for (const [path, document] of Object.entries(current)) {
            const mappedPath = remapMovedPath(path, node.path, nextPath);
            next[mappedPath] =
              mappedPath === path
                ? document
                : {
                    ...document,
                    path: mappedPath,
                    ...(path === node.path ? { revision: result.revision } : {}),
                  };
            if (mappedPath !== path && document.status === "dirty")
              reschedulePaths.push(mappedPath);
          }
          return next;
        });
        context.setDocumentOrder((current) =>
          current.map((path) => remapMovedPath(path, node.path, nextPath)),
        );
        context.setActivePath((current) =>
          current === undefined ? undefined : remapMovedPath(current, node.path, nextPath),
        );
        context.setSelectedPath((current) =>
          current === undefined ? undefined : remapMovedPath(current, node.path, nextPath),
        );
        for (const path of reschedulePaths) context.scheduledSave.current(path);
      }
      await Promise.all(
        oldParent === targetDirectory
          ? [context.loadDirectory(oldParent)]
          : [context.loadDirectory(oldParent), context.loadDirectory(targetDirectory)],
      );
    } catch {
      context.onNotice?.("移动失败，文件可能已被修改或目标已存在。");
    }
  }

  /** 原地 Rename 复用 Move 的 CAS 与投影迁移规则，避免形成第二套 mutation owner。 */
  function renameEntry(node: WorkspaceFileNode, newName: string): Promise<void> {
    return moveEntry(node, parentPath(node.path), newName);
  }

  /** Trash 对话框取消时同步使请求序号和 opaque token 失效，晚 prepare 不得复活 UI。 */
  function cancelTrash(): void {
    context.trashRequestSequence.current += 1;
    context.trashOperation.current = undefined;
    context.setTrashRequest(undefined);
  }

  /** 仅在 native 成功或权威对账确认路径缺失后移除 renderer 投影。 */
  function removeTrashedProjection(relativePath: string): void {
    context.setNodes((current) => removeTreeNode(current, relativePath));
    context.commitDocuments((current) => {
      const removedPrefix = `${relativePath}/`;
      const next = { ...current };
      for (const path of Object.keys(next)) {
        if (path === relativePath || path.startsWith(removedPrefix)) delete next[path];
      }
      return next;
    });
    context.setDocumentOrder((current) =>
      current.filter((path) => path !== relativePath && !path.startsWith(`${relativePath}/`)),
    );
    context.setActivePath((current) =>
      current === relativePath || current?.startsWith(`${relativePath}/`) ? undefined : current,
    );
    context.setSelectedPath((current) =>
      current === relativePath || current?.startsWith(`${relativePath}/`) ? undefined : current,
    );
  }

  /**
   * 显示破坏性确认前完成 native prepare；opaque token 只写主 hook 私有 ref，React
   * state 和 DOM 只能收到有界摘要。
   */
  async function prepareTrash(node: WorkspaceFileNode): Promise<void> {
    if (
      context.operations.trashPrepare === undefined ||
      context.operations.trashCommit === undefined
    )
      return;
    if (!isMutableEntry(node)) {
      context.onNotice?.("该特殊文件系统节点仅供查看，不能在 Ja 中移入回收站。");
      return;
    }
    const unsafeDocument = Object.values(context.documents.current).find(
      (document) =>
        isSameOrDescendantPath(document.path, node.path) &&
        (document.status !== "clean" ||
          context.inFlight.current.has(document.path) ||
          context.externalConflictChecks.current.has(document.path)),
    );
    if (unsafeDocument !== undefined) {
      context.onNotice?.("存在未保存、保存中或冲突的文件，请先完成保存或处理冲突后再移入回收站。");
      return;
    }
    const expectedRevision =
      context.revisions.current.get(node.path) ?? context.documents.current[node.path]?.revision;
    if (expectedRevision === undefined) {
      context.onNotice?.("文件版本未知，请先刷新文件树。");
      return;
    }
    const requestId = ++context.trashRequestSequence.current;
    const workspaceGeneration = context.workspaceGeneration.current;
    context.trashOperation.current = undefined;
    context.setTrashRequest({
      workspaceId: context.workspaceId,
      requestId,
      relativePath: node.path,
      phase: "preparing",
    });
    try {
      const prepared = await context.operations.trashPrepare({
        workspaceId: context.workspaceId,
        relativePath: node.path,
        expectedRevision,
        mutationId: createMutationId(),
      });
      if (
        workspaceGeneration !== context.workspaceGeneration.current ||
        requestId !== context.trashRequestSequence.current
      )
        return;
      context.trashOperation.current = {
        workspaceId: context.workspaceId,
        workspaceGeneration,
        requestId,
        relativePath: node.path,
        expectedRevision,
        operationToken: prepared.operationToken,
        expiresAtUnixMillis: prepared.expiresAtUnixMillis,
      };
      context.setTrashRequest({
        workspaceId: context.workspaceId,
        requestId,
        relativePath: node.path,
        phase: "ready",
        fileCount: prepared.fileCount,
        totalBytes: prepared.totalBytes,
      });
    } catch {
      if (
        workspaceGeneration !== context.workspaceGeneration.current ||
        requestId !== context.trashRequestSequence.current
      )
        return;
      context.trashOperation.current = undefined;
      context.setTrashRequest({
        workspaceId: context.workspaceId,
        requestId,
        relativePath: node.path,
        phase: "error",
        error: "无法确认回收站范围。文件没有被删除，请取消后重试。",
      });
      context.onNotice?.("回收站准备失败，文件没有被删除。");
    }
  }

  /** 只提交当前可见且未过期的 prepare；晚响应和 workspace 切换均 fail-closed。 */
  async function confirmTrash(): Promise<void> {
    const request = context.trashRequest.current;
    const operation = context.trashOperation.current;
    if (
      request === undefined ||
      request.phase !== "ready" ||
      context.operations.trashCommit === undefined
    )
      return;
    if (
      operation === undefined ||
      operation.requestId !== request.requestId ||
      operation.workspaceId !== context.workspaceId ||
      operation.workspaceGeneration !== context.workspaceGeneration.current ||
      operation.relativePath !== request.relativePath
    ) {
      context.trashOperation.current = undefined;
      context.setTrashRequest((current) =>
        current?.requestId === request.requestId
          ? {
              ...current,
              phase: "error",
              error: "回收站确认已失效。文件没有被删除，请取消后重试。",
            }
          : current,
      );
      return;
    }
    /** opaque token 过期后禁止 commit，避免把旧确认应用到已变化的文件。 */
    if (Date.now() >= operation.expiresAtUnixMillis) {
      context.trashOperation.current = undefined;
      context.setTrashRequest((current) =>
        current?.requestId === request.requestId
          ? {
              ...current,
              phase: "error",
              error: "回收站确认已过期。文件没有被删除，请取消后重试。",
            }
          : current,
      );
      return;
    }
    context.setTrashRequest((current) =>
      current?.requestId === request.requestId
        ? { ...current, phase: "committing", error: undefined }
        : current,
    );
    try {
      await context.operations.trashCommit({
        workspaceId: context.workspaceId,
        relativePath: operation.relativePath,
        expectedRevision: operation.expectedRevision,
        mutationId: createMutationId(),
        operationToken: operation.operationToken,
      });
      if (
        operation.workspaceGeneration !== context.workspaceGeneration.current ||
        operation.requestId !== context.trashRequestSequence.current
      )
        return;
      context.trashOperation.current = undefined;
      removeTrashedProjection(operation.relativePath);
      context.setTrashRequest(undefined);
      await context.loadDirectory(parentPath(operation.relativePath));
    } catch (error) {
      if (
        operation.workspaceGeneration !== context.workspaceGeneration.current ||
        operation.requestId !== context.trashRequestSequence.current
      )
        return;
      context.trashOperation.current = undefined;
      const entries = await context.loadDirectory(parentPath(operation.relativePath));
      if (
        operation.workspaceGeneration !== context.workspaceGeneration.current ||
        operation.requestId !== context.trashRequestSequence.current
      )
        return;
      const targetStillExists = entries?.some(
        (entry) => entry.relativePath === operation.relativePath,
      );
      /** 不确定响应但权威树确认目标消失时，收敛为完成并清理本地投影。 */
      if (targetStillExists === false) {
        removeTrashedProjection(operation.relativePath);
        context.setTrashRequest(undefined);
        context.onNotice?.("已重新读取文件树，目标不再存在；系统回收站操作可能已经完成。");
        return;
      }
      const recycleUnavailable = isRecycleUnavailableError(error) && targetStillExists === true;
      context.setTrashRequest((current) =>
        current?.requestId === request.requestId
          ? {
              ...current,
              phase: "error",
              error: recycleUnavailable
                ? "当前磁盘未启用系统回收站，文件没有被删除。请在 Windows 设置中启用后重试。"
                : targetStillExists === true
                  ? "回收站提交未完成，目标仍在工作区。请取消后重试。"
                  : "无法确认回收站提交结果。请取消后刷新文件树核对。",
            }
          : current,
      );
      context.onNotice?.(
        recycleUnavailable
          ? "当前磁盘未启用系统回收站，文件没有被删除。"
          : targetStillExists === true
            ? "回收站提交未完成，目标仍在工作区。"
            : "无法确认回收站提交结果，请刷新文件树核对。",
      );
    }
  }

  /** opaque drop token 只消费一次，并只刷新通过 CAS 导入的目标目录。 */
  async function importNativeDrop(dropToken: string, targetDirectory: string): Promise<void> {
    if (context.operations.importDrop === undefined) return;
    const expectedRevision = context.revisions.current.get(targetDirectory);
    if (expectedRevision === undefined) {
      context.onNotice?.("目标目录版本未知，请先刷新文件树。");
      return;
    }
    try {
      await context.operations.importDrop({
        workspaceId: context.workspaceId,
        targetDirectory,
        dropToken,
        expectedRevision,
        mutationId: createMutationId(),
      });
      await context.loadDirectory(targetDirectory);
    } catch {
      context.onNotice?.("拖入导入失败，请重新拖入。");
    }
  }

  return {
    createEntry,
    moveEntry,
    renameEntry,
    prepareTrash,
    cancelTrash,
    confirmTrash,
    importNativeDrop,
  };
}
