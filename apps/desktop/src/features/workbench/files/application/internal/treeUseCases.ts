// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { FilesWorkspaceOperations, WorkspaceTreeEntryDto } from "../ports";
import { mapTreeEntries } from "../treeProjection";
import {
  hasNewerTreeRequest,
  mergeTreePageWithLoadedDescendants,
  replaceTreeChildren,
} from "../../domain/filesModel";
import type { FileRevision, WorkspaceFileNode } from "../../domain/types";
import type { ControllerRef, StateWriter } from "./controllerPorts";

export interface TreeUseCases {
  loadDirectory: (relativePath: string) => Promise<readonly WorkspaceTreeEntryDto[] | undefined>;
}

interface TreeUseCasesContext {
  workspaceId: string;
  tree: FilesWorkspaceOperations["tree"];
  treeRequestSequence: ControllerRef<number>;
  treeRequests: ControllerRef<Map<string, number>>;
  workspaceGeneration: ControllerRef<number>;
  rootTreeReady: ControllerRef<boolean>;
  revisions: ControllerRef<Map<string, FileRevision>>;
  setNodes: StateWriter<WorkspaceFileNode[]>;
  setTreeLoading: StateWriter<boolean>;
  setTreeError: StateWriter<string | undefined>;
}

/**
 * 创建无状态 Tree 查询协作者。所有 request epoch、generation 和 revision cache 均由
 * 主 hook 注入；协作者只执行一次目录查询用例，不持久化第二份树事实。
 */
export function createTreeUseCases(context: TreeUseCasesContext): TreeUseCases {
  /**
   * 投影一个权威非递归 page；父 page 只替换直属条目，已由独立请求读取的子 page
   * 必须保留，否则稍晚的根 Watcher 刷新会清空展开目录。
   */
  function updateTreeFromPage(
    path: string,
    entries: readonly WorkspaceTreeEntryDto[],
    directoryRevision: FileRevision,
    requestEpoch: number,
  ): void {
    context.revisions.current.set(path, directoryRevision);
    const mapped = mapTreeEntries(entries);
    for (const entry of entries) {
      if (
        entry.revision !== undefined &&
        (!hasNewerTreeRequest(entry.relativePath, requestEpoch, context.treeRequests.current) ||
          !context.revisions.current.has(entry.relativePath))
      ) {
        context.revisions.current.set(entry.relativePath, entry.revision);
      }
    }
    context.setNodes((current) => {
      const merged = mergeTreePageWithLoadedDescendants(current, mapped);
      if (path.length !== 0) return replaceTreeChildren(current, path, merged);
      /** 相同根投影保留 Arborist identity，重复 Watcher hint 不会折叠用户展开态。 */
      return merged.length === current.length &&
        merged.every((node, index) => node === current[index])
        ? current
        : merged;
    });
  }

  /**
   * 分页读取目录并丢弃旧 generation 的晚结果；根目录仅首次阻塞 loading，后续
   * 对账保留现有 Tree 实例，从而兼顾竞态安全和交互连续性。
   */
  async function loadDirectory(
    relativePath: string,
  ): Promise<readonly WorkspaceTreeEntryDto[] | undefined> {
    const requestId = ++context.treeRequestSequence.current;
    context.treeRequests.current.set(relativePath, requestId);
    const generation = context.workspaceGeneration.current;
    if (relativePath.length === 0) {
      if (!context.rootTreeReady.current) context.setTreeLoading(true);
      context.setTreeError(undefined);
    } else {
      context.setNodes((current) => replaceTreeChildren(current, relativePath, [], true));
    }
    try {
      let cursor: string | undefined;
      let snapshotToken: string | undefined;
      let directoryRevision: FileRevision | undefined;
      const entries: WorkspaceTreeEntryDto[] = [];
      const seenPaths = new Set<string>();
      const seenCursors = new Set<string>();
      do {
        /** cursor 必须单调推进；重复 cursor 代表 adapter 合同损坏，必须终止而非循环。 */
        if (cursor !== undefined) {
          if (seenCursors.has(cursor)) throw new Error("repeated workspace tree cursor");
          seenCursors.add(cursor);
        }
        const page = await context.tree({
          workspaceId: context.workspaceId,
          relativePath,
          ...(cursor === undefined ? {} : { cursor, snapshotToken }),
        });
        if (
          generation !== context.workspaceGeneration.current ||
          requestId !== context.treeRequests.current.get(relativePath)
        )
          return undefined;
        snapshotToken ??= page.snapshotToken;
        directoryRevision ??= page.directoryRevision;
        /** 同一 snapshot 只接收每条相对路径一次，避免 native 重页污染树投影。 */
        for (const entry of page.entries) {
          if (!seenPaths.has(entry.relativePath)) {
            seenPaths.add(entry.relativePath);
            entries.push(entry);
          }
        }
        cursor = page.nextCursor ?? undefined;
      } while (cursor !== undefined);
      if (
        generation !== context.workspaceGeneration.current ||
        requestId !== context.treeRequests.current.get(relativePath)
      )
        return undefined;
      if (directoryRevision === undefined) throw new Error("workspace directory revision missing");
      if (relativePath.length === 0) context.rootTreeReady.current = true;
      updateTreeFromPage(relativePath, entries, directoryRevision, requestId);
      return entries;
    } catch {
      /** 只有当前 generation/request 能发布错误，过时请求必须静默结束。 */
      if (
        generation === context.workspaceGeneration.current &&
        requestId === context.treeRequests.current.get(relativePath)
      ) {
        if (relativePath.length === 0) context.setTreeError("文件树读取失败，请重试。");
        else
          context.setNodes((current) =>
            replaceTreeChildren(current, relativePath, [], false, "目录读取失败"),
          );
      }
      return undefined;
    } finally {
      if (
        generation === context.workspaceGeneration.current &&
        requestId === context.treeRequests.current.get(relativePath) &&
        relativePath.length === 0
      )
        context.setTreeLoading(false);
    }
  }

  return { loadDirectory };
}
