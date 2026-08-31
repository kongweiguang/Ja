// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { FilesWorkspaceOperations, WorkspaceTreeEntryDto } from "../ports";
import type { OpenDocument, SaveAsRequest } from "../types";
import { createMutationId, parentPath } from "../../domain/filesModel";
import type { FileRevision } from "../../domain/types";
import { suggestedSaveAsPath, validateSaveAsPath } from "./filePathPolicy";
import type { ControllerRef, StateWriter } from "./controllerPorts";

export interface SaveAsUseCases {
  beginSaveAs: (path: string) => void;
  submitSaveAs: () => Promise<void>;
}

interface SaveAsUseCasesContext {
  workspaceId: string;
  operations: Pick<FilesWorkspaceOperations, "createEntry">;
  request: ControllerRef<SaveAsRequest | undefined>;
  documents: ControllerRef<Record<string, OpenDocument>>;
  workspaceGeneration: ControllerRef<number>;
  revisions: ControllerRef<Map<string, FileRevision>>;
  loadDirectory: (relativePath: string) => Promise<readonly WorkspaceTreeEntryDto[] | undefined>;
  commitDocuments: (
    update: (current: Record<string, OpenDocument>) => Record<string, OpenDocument>,
  ) => void;
  setDocumentOrder: StateWriter<string[]>;
  setSelectedPath: StateWriter<string | undefined>;
  setActivePath: StateWriter<string | undefined>;
  setComparePath: StateWriter<string | undefined>;
  setSaveAsRequest: StateWriter<SaveAsRequest | undefined>;
}

/**
 * 创建无状态 Save As 协作者。请求状态与文档事实留在主 hook；协作者只负责
 * 携带初始正文的一次原子 Create；Rust 在最终路径可见前完成编码、staging 与 no-replace。
 */
export function createSaveAsUseCases(context: SaveAsUseCasesContext): SaveAsUseCases {
  /** 打开相对路径表单并给出低碰撞建议，目标冲突仍由 Rust CAS 权威判定。 */
  function beginSaveAs(path: string): void {
    if (context.operations.createEntry === undefined) return;
    context.setSaveAsRequest({
      sourcePath: path,
      targetPath: suggestedSaveAsPath(path),
      pending: false,
    });
  }

  /** 使用 CreateEntry.initialContent 一次落盘，避免“空文件已出现、正文保存失败”的两阶段残留。 */
  async function submitSaveAs(): Promise<void> {
    const request = context.request.current;
    const createEntry = context.operations.createEntry;
    const source =
      request === undefined ? undefined : context.documents.current[request.sourcePath];
    if (
      request === undefined ||
      request.pending ||
      createEntry === undefined ||
      source === undefined ||
      source.status !== "conflict"
    )
      return;
    const validated = validateSaveAsPath(request.targetPath, request.sourcePath);
    /** renderer 校验失败不触发 native 副作用，目标存在性仍交给 CAS create。 */
    if (validated.path === undefined) {
      context.setSaveAsRequest((current) =>
        current === undefined ? current : { ...current, error: validated.error },
      );
      return;
    }
    const targetPath = validated.path;
    const generation = context.workspaceGeneration.current;
    context.setSaveAsRequest((current) =>
      current === undefined ? current : { ...current, targetPath, pending: true, error: undefined },
    );
    try {
      const mutationId = createMutationId();
      const createResult = await createEntry({
        workspaceId: context.workspaceId,
        relativePath: targetPath,
        expectedRevision: null,
        mutationId,
        kind: "file",
        initialContent: {
          content: source.content,
          encoding: source.encoding,
          newline: source.newline,
        },
      });
      if (generation !== context.workspaceGeneration.current) return;
      const createdDocument: OpenDocument = {
        path: targetPath,
        content: source.content,
        savedContent: source.content,
        revision: createResult.revision,
        encoding: source.encoding,
        newline: source.newline,
        kind: "text",
        readOnly: false,
        status: "clean",
        draftGeneration: 0,
        lastMutationId: mutationId,
      };
      context.revisions.current.set(targetPath, createResult.revision);
      context.commitDocuments((current) => ({ ...current, [targetPath]: createdDocument }));
      context.setDocumentOrder((current) =>
        current.includes(targetPath) ? current : [...current, targetPath],
      );
      context.setSelectedPath(targetPath);
      context.setActivePath(targetPath);
      context.setComparePath(undefined);
      context.setSaveAsRequest(undefined);
      void context.loadDirectory(parentPath(targetPath));
    } catch {
      if (generation === context.workspaceGeneration.current) {
        context.setSaveAsRequest((current) =>
          current === undefined
            ? current
            : {
                ...current,
                pending: false,
                error: "另存为失败，目标可能已存在或工作区已变化。",
              },
        );
      }
    }
  }

  return { beginSaveAs, submitSaveAs };
}
