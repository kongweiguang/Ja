// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { FilesSaveCoordinator } from "../FilesSaveCoordinator";
import type { FilesWorkspaceOperations, WorkspaceChangedEvent } from "../ports";
import type { CloseDocumentRequest, OpenDocument, SaveAsRequest } from "../types";
import { createMutationId } from "../../domain/filesModel";
import type { FileRevision } from "../../domain/types";
import { documentFromRead, revisionsEqual } from "./fileProjection";
import { isConflictError } from "./nativeErrorPolicy";
import type { ControllerRef, StateWriter } from "./controllerPorts";

export type ExternalRefreshMode = "auto" | "conflict" | "force";

export interface DocumentUseCases {
  openDocument: (path: string, reveal?: { line: number; column?: number }) => Promise<void>;
  saveDocumentOnce: (path: string) => Promise<boolean>;
  flushDocument: (path: string) => Promise<void>;
  scheduleSave: (path: string) => void;
  editDocument: (path: string, content: string) => void;
  reloadExternal: (
    path: string,
    mode: ExternalRefreshMode,
    expectedRevision?: FileRevision,
  ) => Promise<void>;
  compareConflict: (path: string) => Promise<void>;
  reloadConflict: (path: string) => Promise<void>;
  discardDocument: (path: string) => void;
  closeDocument: (path: string) => void;
}

interface DocumentUseCasesContext {
  workspaceId: string;
  operations: Pick<FilesWorkspaceOperations, "readFile" | "saveFile">;
  saveCoordinator: FilesSaveCoordinator;
  documents: ControllerRef<Record<string, OpenDocument>>;
  documentOrder: ControllerRef<string[]>;
  activePath: ControllerRef<string | undefined>;
  workspaceGeneration: ControllerRef<number>;
  revisions: ControllerRef<Map<string, FileRevision>>;
  inFlight: ControllerRef<Map<string, number>>;
  pendingMutations: ControllerRef<Map<string, string>>;
  deferredWatch: ControllerRef<Map<string, WorkspaceChangedEvent>>;
  externalReads: ControllerRef<Map<string, number>>;
  externalConflictChecks: ControllerRef<Set<string>>;
  lifecycleFence: ControllerRef<boolean>;
  conflictAction: ControllerRef<{ path: string; kind: "compare" | "reload" } | undefined>;
  externalRefresh: ControllerRef<
    (path: string, mode: ExternalRefreshMode, expectedRevision?: FileRevision) => Promise<void>
  >;
  commitDocuments: (
    update: (current: Record<string, OpenDocument>) => Record<string, OpenDocument>,
  ) => void;
  setDocumentOrder: StateWriter<string[]>;
  setActivePath: StateWriter<string | undefined>;
  setSelectedPath: StateWriter<string | undefined>;
  setComparePath: StateWriter<string | undefined>;
  setConflictAction: StateWriter<{ path: string; kind: "compare" | "reload" } | undefined>;
  setSaveAsRequest: StateWriter<SaveAsRequest | undefined>;
  setCloseDocumentRequest: StateWriter<CloseDocumentRequest | undefined>;
  onNotice?: (message: string) => void;
}

const SAVE_DEBOUNCE_MILLIS = 500;

/**
 * 创建无状态文档协作者。草稿、CAS revision、single-flight 与冲突读取的全部事实
 * 继续由主 hook refs 持有；协作者只实现一次文档用例的阶段顺序与失败收敛。
 */
export function createDocumentUseCases(context: DocumentUseCasesContext): DocumentUseCases {
  /** 每个路径只读取一次；已有 Tab 只聚焦，避免复制 document 状态。 */
  async function openDocument(
    path: string,
    reveal?: { line: number; column?: number },
  ): Promise<void> {
    const existing = context.documents.current[path];
    if (existing !== undefined) {
      context.setActivePath(path);
      context.setSelectedPath(path);
      if (reveal !== undefined)
        context.commitDocuments((current) => {
          const document = current[path];
          return document === undefined ? current : { ...current, [path]: { ...document, reveal } };
        });
      return;
    }
    context.setSelectedPath(path);
    const generation = context.workspaceGeneration.current;
    try {
      const result = await context.operations.readFile({
        workspaceId: context.workspaceId,
        relativePath: path,
      });
      if (generation !== context.workspaceGeneration.current) return;
      const document = documentFromRead(result, reveal);
      context.revisions.current.set(path, document.revision);
      context.commitDocuments((current) =>
        current[path] === undefined ? { ...current, [path]: document } : current,
      );
      context.setDocumentOrder((current) =>
        current.includes(path) ? current : [...current, path],
      );
      context.setActivePath(path);
    } catch {
      context.onNotice?.("文件读取失败，请重试。");
    }
  }

  /**
   * 执行单次 CAS 保存并回写 ACK revision；更晚草稿由外层 coordinator 串行处理，
   * 同一路径不会产生并发 expectedRevision。
   */
  async function saveDocumentOnce(path: string): Promise<boolean> {
    const document = context.documents.current[path];
    if (
      document === undefined ||
      document.readOnly ||
      document.status === "clean" ||
      document.status === "conflict" ||
      context.externalConflictChecks.current.has(path)
    )
      return true;
    const draft = document.content;
    const draftGeneration = document.draftGeneration;
    const workspaceGeneration = context.workspaceGeneration.current;
    const mutationId = createMutationId();
    context.inFlight.current.set(path, workspaceGeneration);
    context.pendingMutations.current.set(path, mutationId);
    context.commitDocuments((current) =>
      current[path] === undefined
        ? current
        : {
            ...current,
            [path]: {
              ...current[path],
              status: "saving",
              error: undefined,
              lastMutationId: mutationId,
            },
          },
    );
    try {
      const result = await context.operations.saveFile({
        workspaceId: context.workspaceId,
        relativePath: path,
        expectedRevision: document.revision,
        mutationId,
        content: draft,
        encoding: document.encoding,
        newline: document.newline,
      });
      if (workspaceGeneration !== context.workspaceGeneration.current) return false;
      const latest = context.documents.current[path];
      if (latest === undefined) return true;
      const deferredWatch = context.deferredWatch.current.get(path);
      context.deferredWatch.current.delete(path);
      context.revisions.current.set(path, result.revision);
      const changedWhileSaving =
        latest.draftGeneration !== draftGeneration || latest.content !== draft;
      const remainsConflict = latest.status === "conflict";
      context.commitDocuments((current) => {
        const currentDocument = current[path];
        if (currentDocument === undefined) return current;
        return {
          ...current,
          [path]: {
            ...currentDocument,
            revision: result.revision,
            savedContent: draft,
            status: remainsConflict ? "conflict" : changedWhileSaving ? "dirty" : "clean",
            error: remainsConflict ? currentDocument.error : undefined,
            lastMutationId: result.mutationId,
          },
        };
      });
      /** ACK 后再核对延迟 Watcher hint，避免把本次 mutation 回声误报为冲突。 */
      if (deferredWatch !== undefined && !revisionsEqual(deferredWatch.revision, result.revision)) {
        void context.externalRefresh.current(path, "conflict", result.revision);
      }
      return true;
    } catch (error: unknown) {
      if (workspaceGeneration !== context.workspaceGeneration.current) return false;
      const latest = context.documents.current[path];
      /** 失败只回写仍对应本次草稿的 document，更晚输入保持 dirty 等待下一队列。 */
      if (
        latest !== undefined &&
        latest.draftGeneration === draftGeneration &&
        latest.content === draft
      ) {
        context.commitDocuments((current) =>
          current[path] === undefined
            ? current
            : {
                ...current,
                [path]: {
                  ...current[path],
                  status: isConflictError(error) ? "conflict" : "saveError",
                  error: isConflictError(error)
                    ? "文件已在外部修改，请比较或重新加载。"
                    : "保存失败，工作区可能已被修改。",
                },
              },
        );
      }
      const deferredWatch = context.deferredWatch.current.get(path);
      context.deferredWatch.current.delete(path);
      /** native 失败不能证明外部状态未变，延迟事件仍需走权威读取。 */
      if (deferredWatch !== undefined)
        void context.externalRefresh.current(
          path,
          "conflict",
          context.revisions.current.get(path) ?? document.revision,
        );
      return false;
    } finally {
      if (context.inFlight.current.get(path) === workspaceGeneration)
        context.inFlight.current.delete(path);
      if (context.pendingMutations.current.get(path) === mutationId)
        context.pendingMutations.current.delete(path);
    }
  }

  /** 立即取消 debounce 并复用唯一保存队列，Ctrl+S/失焦始终刷新最新草稿。 */
  async function flushDocument(path: string): Promise<void> {
    await context.saveCoordinator.flush(path);
  }

  /** 每次末键重置 500ms 时钟，到期后进入同一路径 single-flight 队列。 */
  function scheduleSave(path: string): void {
    context.saveCoordinator.schedule(path, SAVE_DEBOUNCE_MILLIS);
  }

  /** 编辑 fence 生效后拒绝新输入；正常输入只推进主 hook 中的草稿 generation。 */
  function editDocument(path: string, content: string): void {
    if (context.lifecycleFence.current) return;
    const document = context.documents.current[path];
    if (
      document === undefined ||
      document.readOnly ||
      document.status === "conflict" ||
      document.content === content
    )
      return;
    context.commitDocuments((current) => {
      const currentDocument = current[path];
      if (
        currentDocument === undefined ||
        currentDocument.readOnly ||
        currentDocument.status === "conflict"
      )
        return current;
      return {
        ...current,
        [path]: {
          ...currentDocument,
          content,
          status: "dirty",
          error: undefined,
          draftGeneration: currentDocument.draftGeneration + 1,
        },
      };
    });
    scheduleSave(path);
  }

  /**
   * 用权威读取核对 Watcher hint；auto 不覆盖读取期间产生的新草稿，force 只允许
   * 用户显式触发，conflict 期间冻结该路径保存。
   */
  async function reloadExternal(
    path: string,
    mode: ExternalRefreshMode,
    expectedRevision?: FileRevision,
  ): Promise<void> {
    const workspaceGeneration = context.workspaceGeneration.current;
    const requestId = (context.externalReads.current.get(path) ?? 0) + 1;
    const baselineRevision =
      expectedRevision ??
      context.documents.current[path]?.revision ??
      context.revisions.current.get(path);
    let resumeDirtySave = false;
    if (mode === "conflict") {
      context.externalConflictChecks.current.add(path);
      context.saveCoordinator.cancel(path);
    }
    context.externalReads.current.set(path, requestId);
    try {
      const result = await context.operations.readFile({
        workspaceId: context.workspaceId,
        relativePath: path,
      });
      if (
        workspaceGeneration !== context.workspaceGeneration.current ||
        context.externalReads.current.get(path) !== requestId
      )
        return;
      const external = documentFromRead(result);
      if (mode !== "force" && revisionsEqual(external.revision, baselineRevision)) {
        resumeDirtySave = mode === "conflict";
        return;
      }
      let acceptedRevision = false;
      context.commitDocuments((current) => {
        const document = current[path];
        if (document === undefined) return current;
        const shouldConflict =
          mode === "conflict" || (mode === "auto" && document.status !== "clean");
        /** 自动刷新绝不覆盖 dirty buffer，只记录外部快照并进入冲突态。 */
        if (shouldConflict) {
          context.saveCoordinator.cancel(path);
          return {
            ...current,
            [path]: {
              ...document,
              status: "conflict",
              externalContent: external.content,
              externalRevision: external.revision,
              error: "文件已在外部修改，请比较或重新加载。",
            },
          };
        }
        acceptedRevision = true;
        return { ...current, [path]: { ...external, reveal: document.reveal } };
      });
      if (acceptedRevision) {
        context.revisions.current.set(path, external.revision);
        context.setComparePath((current) => (current === path ? undefined : current));
      }
    } catch {
      context.commitDocuments((current) => {
        const document = current[path];
        const error =
          mode === "force"
            ? "重新加载失败，外部文件可能已删除或无法读取。"
            : "文件已在外部删除或无法读取，请重新加载或另存为。";
        return document === undefined
          ? current
          : {
              ...current,
              [path]: {
                ...document,
                status: "conflict",
                externalContent: undefined,
                externalRevision: undefined,
                error,
              },
            };
      });
    } finally {
      const ownsRequest = context.externalReads.current.get(path) === requestId;
      if (ownsRequest) context.externalReads.current.delete(path);
      /** 仅最后一条冲突读取可解除冻结，旧请求不得重启保存。 */
      if (mode === "conflict" && ownsRequest) {
        context.externalConflictChecks.current.delete(path);
        if (resumeDirtySave && context.documents.current[path]?.status === "dirty")
          scheduleSave(path);
      }
    }
  }

  /** 按需读取权威外部快照后再显示 MergeView，禁止用旧缓存制造伪 Diff。 */
  async function compareConflict(path: string): Promise<void> {
    if (context.conflictAction.current !== undefined) return;
    const current = context.documents.current[path];
    if (current === undefined || current.status !== "conflict") return;
    context.setConflictAction({ path, kind: "compare" });
    try {
      if (current.externalContent === undefined)
        await reloadExternal(path, "conflict", current.revision);
      const refreshed = context.documents.current[path];
      if (refreshed?.externalContent === undefined) {
        context.commitDocuments((documents) =>
          documents[path] === undefined
            ? documents
            : {
                ...documents,
                [path]: {
                  ...documents[path],
                  error: "无法读取可比较的外部版本；可以重新加载或另存为。",
                },
              },
        );
        return;
      }
      context.setComparePath(path);
    } finally {
      context.setConflictAction(undefined);
    }
  }

  /** 只有用户显式确认重新加载后才允许丢弃冲突草稿。 */
  async function reloadConflict(path: string): Promise<void> {
    if (context.conflictAction.current !== undefined) return;
    context.setConflictAction({ path, kind: "reload" });
    try {
      await reloadExternal(path, "force");
    } finally {
      context.setConflictAction(undefined);
    }
  }

  /** 真正移除 renderer 草稿；调用方必须已确认 clean 或取得应用内放弃授权。 */
  function discardDocument(path: string): void {
    if (context.documents.current[path] === undefined) return;
    context.saveCoordinator.cancel(path);
    context.setComparePath((current) => (current === path ? undefined : current));
    context.setSaveAsRequest((current) => (current?.sourcePath === path ? undefined : current));
    context.setCloseDocumentRequest((current) => (current?.path === path ? undefined : current));
    context.commitDocuments((current) => {
      const next = { ...current };
      delete next[path];
      return next;
    });
    context.setDocumentOrder((current) => {
      const index = current.indexOf(path);
      const next = current.filter((candidate) => candidate !== path);
      if (context.activePath.current === path)
        context.setActivePath(next[index] ?? next[index - 1]);
      return next;
    });
  }

  /** clean Tab 直接关闭；任何未完成草稿必须进入应用内确认对话框。 */
  function closeDocument(path: string): void {
    const document = context.documents.current[path];
    if (document === undefined) return;
    if (document.status === "clean") discardDocument(path);
    else context.setCloseDocumentRequest({ path });
  }

  return {
    openDocument,
    saveDocumentOnce,
    flushDocument,
    scheduleSave,
    editDocument,
    reloadExternal,
    compareConflict,
    reloadConflict,
    discardDocument,
    closeDocument,
  };
}
