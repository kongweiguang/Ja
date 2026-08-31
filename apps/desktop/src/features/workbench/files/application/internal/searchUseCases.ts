// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { SaveTimerPort } from "../FilesSaveCoordinator";
import type { FilesWorkspaceOperations } from "../ports";
import type { FilesSearchResult, FilesSearchSummary } from "../types";
import { mapSearchHits } from "./fileProjection";
import type { ControllerRef, StateWriter } from "./controllerPorts";

export interface SearchUseCases {
  runSearch: (query: string) => void;
}

interface SearchUseCasesContext {
  workspaceId: string;
  search: FilesWorkspaceOperations["search"];
  timer: SaveTimerPort;
  searchTimer: ControllerRef<unknown | undefined>;
  searchRequest: ControllerRef<number>;
  workspaceGeneration: ControllerRef<number>;
  setSearchQuery: StateWriter<string>;
  setSearchResults: StateWriter<FilesSearchResult[]>;
  setSearchSummary: StateWriter<FilesSearchSummary | undefined>;
  setSearchLoading: StateWriter<boolean>;
  setSearchError: StateWriter<string | undefined>;
}

/**
 * 创建无状态搜索协作者；debounce handle、request epoch 和结果状态继续由主 hook
 * 持有，协作者只负责把一次输入线性化成当前 workspace 的搜索投影。
 */
export function createSearchUseCases(context: SearchUseCasesContext): SearchUseCases {
  /** 取消旧 debounce 并用 request/generation 双栅栏丢弃晚结果，不建立 renderer 索引。 */
  function runSearch(query: string): void {
    const requestId = ++context.searchRequest.current;
    const workspaceGeneration = context.workspaceGeneration.current;
    context.setSearchQuery(query);
    if (context.searchTimer.current !== undefined) context.timer.clear(context.searchTimer.current);
    const normalized = query.trim();
    if (context.search === undefined || normalized.length === 0) {
      context.setSearchResults([]);
      context.setSearchSummary(undefined);
      context.setSearchLoading(false);
      context.setSearchError(undefined);
      return;
    }
    context.setSearchLoading(true);
    context.setSearchError(undefined);
    context.searchTimer.current = context.timer.set(250, () => {
      context.searchTimer.current = undefined;
      void context
        .search?.({ workspaceId: context.workspaceId, relativePath: "", query: normalized })
        .then((result) => {
          if (
            requestId !== context.searchRequest.current ||
            workspaceGeneration !== context.workspaceGeneration.current
          )
            return;
          context.setSearchResults(mapSearchHits(result.hits));
          context.setSearchSummary({
            truncated: result.truncated,
            scannedEntries: result.scannedEntries,
            skippedFiles: result.skippedFiles,
          });
        })
        .catch(() => {
          if (
            requestId !== context.searchRequest.current ||
            workspaceGeneration !== context.workspaceGeneration.current
          )
            return;
          context.setSearchSummary(undefined);
          context.setSearchError("搜索失败，请重试。");
        })
        .finally(() => {
          if (
            requestId === context.searchRequest.current &&
            workspaceGeneration === context.workspaceGeneration.current
          )
            context.setSearchLoading(false);
        });
    });
  }

  return { runSearch };
}
