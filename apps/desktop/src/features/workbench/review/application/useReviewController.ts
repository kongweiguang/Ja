// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  type ReviewAction,
  type ReviewCatalog,
  type ReviewFile,
  type ReviewFileDiff,
  type ReviewInvalidatedEvent,
  type ReviewSnapshot,
  type ReviewSource,
  type ReviewTarget,
} from "../domain/types";
import {
  canReviewAction,
  diffMatchesSnapshot,
  filterReviewFiles,
  findReviewFile,
  retainFileSelection,
  sourceAllowsMutation,
  sourceKey,
  type ReviewFilter,
  type ReviewViewMode,
} from "../domain/model";
import {
  normalizeReviewFailure,
  reviewProtocolFailure,
  unavailableReviewPort,
  type ReviewFailure,
  type ReviewPort,
} from "./ports";

export interface ReviewControllerState {
  loading: boolean;
  catalogLoading: boolean;
  diffLoading: boolean;
  source: ReviewSource;
  catalog: ReviewCatalog | undefined;
  snapshot: ReviewSnapshot | undefined;
  diff: ReviewFileDiff | undefined;
  selectedFileId: string | undefined;
  filter: ReviewFilter;
  query: string;
  viewMode: ReviewViewMode;
  drawerOpen: boolean;
  error: ReviewFailure | undefined;
  diffError: ReviewFailure | undefined;
  notice: string | undefined;
  pendingOperationIds: ReadonlySet<string>;
}

export interface ReviewViewModel {
  state: ReviewControllerState;
  sourceOptions: ReviewSource[];
  visibleFiles: ReviewFile[];
  selectedFile: ReviewFile | undefined;
}

export interface ReviewActions {
  refresh: () => void;
  setSource: (source: ReviewSource) => void;
  selectFile: (fileId: string) => void;
  setFilter: (filter: ReviewFilter) => void;
  setQuery: (query: string) => void;
  setViewMode: (mode: ReviewViewMode) => void;
  setDrawerOpen: (open: boolean) => void;
  applyAction: (action: ReviewAction, target: ReviewTarget) => Promise<void>;
  cancelOperation: (operationId: string) => Promise<void>;
  clearNotice: () => void;
}

export interface ReviewController {
  viewModel: ReviewViewModel;
  actions: ReviewActions;
}

export interface UseReviewControllerOptions {
  workspaceId: string | undefined;
  generation?: number;
  adapter?: ReviewPort;
}

interface ScopedState extends ReviewControllerState {
  scopeKey: string;
  localGeneration: number;
  reloadToken: number;
}

interface ScopeIdentity {
  scopeKey: string;
  localGeneration: number;
}

/** 创建干净作用域，确保 workspace 或 runtime generation 切换后不暴露旧 Git 状态。 */
function initialState(
  source: ReviewSource,
  scopeKey: string,
  localGeneration: number,
  loading: boolean,
): ScopedState {
  return {
    scopeKey,
    localGeneration,
    reloadToken: 0,
    loading,
    catalogLoading: loading,
    diffLoading: false,
    source,
    catalog: undefined,
    snapshot: undefined,
    diff: undefined,
    selectedFileId: undefined,
    filter: "all",
    query: "",
    viewMode: "split",
    drawerOpen: false,
    error: undefined,
    diffError: undefined,
    notice: undefined,
    pendingOperationIds: new Set<string>(),
  };
}

/** 校验每个 native 响应都属于发起它的请求，避免同名 workspace 的旧结果串入。 */
function snapshotBelongsTo(
  snapshot: ReviewSnapshot,
  workspaceId: string,
  source: ReviewSource,
): boolean {
  return snapshot.workspaceId === workspaceId && sourceKey(snapshot.source) === sourceKey(source);
}

/** 只返回 catalog 声明的来源选项，React 不虚构 branch 或 commit 身份。 */
function sourceOptionsFor(catalog: ReviewCatalog | undefined): ReviewSource[] {
  const options: ReviewSource[] = [{ kind: "unstaged" }, { kind: "staged" }];
  for (const ref of catalog?.baseRefs ?? []) options.push({ kind: "branch", refId: ref.refId });
  for (const commit of catalog?.commits ?? [])
    options.push({ kind: "commit", commitId: commit.commitId });
  return options;
}

/** 写入前确认 target 仍属于当前权威快照，拒绝旧文件和旧区块身份。 */
function targetBelongsToSnapshot(snapshot: ReviewSnapshot, target: ReviewTarget): boolean {
  if (target.kind === "all") return snapshot.files.length > 0;
  const file = snapshot.files.find((candidate) => candidate.fileId === target.fileId);
  if (file === undefined) return false;
  return target.kind === "file" || file.hunks.some((hunk) => hunk.hunkId === target.hunkId);
}

/** 应用已加载快照时只保留仍存在的文件选择，并清空依赖旧 revision 的 Diff。 */
function withSnapshot(previous: ScopedState, snapshot: ReviewSnapshot): ScopedState {
  return {
    ...previous,
    loading: false,
    snapshot,
    selectedFileId: retainFileSelection(snapshot.files, previous.selectedFileId),
    diff: undefined,
    diffLoading: false,
    diffError: undefined,
    error: undefined,
  };
}

/**
 * 将失效事件与当前 workspace generation 对齐。generation 缺失时必须拒绝：
 * sidecar admission fence 建立前挂载的 Review 不能让同 workspace 的旧事件
 * 驱动另一个 native 进程重新读取。
 */
function eventMatches(
  event: ReviewInvalidatedEvent,
  workspaceId: string,
  generation: number | undefined,
): boolean {
  return (
    generation !== undefined && event.workspaceId === workspaceId && event.generation === generation
  );
}

/** 统一拥有 catalog、snapshot、Diff 与 mutation 编排，并用 scope/revision 栅栏拒绝迟到响应。 */
export function useReviewController({
  workspaceId,
  generation,
  adapter = unavailableReviewPort,
}: UseReviewControllerOptions): ReviewController {
  const scopeKey = `${workspaceId ?? ""}:${generation ?? ""}`;
  const scopeRef = useRef<ScopeIdentity>({ scopeKey, localGeneration: 0 });
  if (scopeRef.current.scopeKey !== scopeKey) {
    scopeRef.current = { scopeKey, localGeneration: scopeRef.current.localGeneration + 1 };
  }
  const activeScope = scopeRef.current;
  const defaultSource: ReviewSource = { kind: "unstaged" };
  const [ownedState, setOwnedState] = useState<ScopedState>(() =>
    initialState(defaultSource, scopeKey, activeScope.localGeneration, workspaceId !== undefined),
  );
  const state =
    ownedState.scopeKey === scopeKey
      ? ownedState
      : initialState(
          defaultSource,
          scopeKey,
          activeScope.localGeneration,
          workspaceId !== undefined,
        );
  const operationKeysRef = useRef(new Set<string>());

  /** 递增当前作用域重读令牌，旧作用域不能借此使新作用域失效。 */
  const refreshScope = useCallback(() => {
    setOwnedState((previous) =>
      previous.scopeKey === scopeKey
        ? { ...previous, reloadToken: previous.reloadToken + 1 }
        : previous,
    );
  }, [scopeKey]);

  /** 收到 native invalidation 后只请求权威 catalog 与 snapshot，不在前端增量修补。 */
  const refresh = useCallback(() => {
    if (workspaceId !== undefined) refreshScope();
  }, [refreshScope, workspaceId]);

  useEffect(() => {
    if (workspaceId === undefined) return undefined;
    let active = true;
    const requestScope = activeScope;
    const requestReload = state.reloadToken;
    setOwnedState((previous) =>
      previous.scopeKey === scopeKey && previous.reloadToken === requestReload
        ? { ...previous, catalogLoading: true, error: undefined }
        : previous,
    );
    void adapter
      .catalog({ workspaceId, maxCommits: 50 })
      .then((catalog) => {
        if (!active) return;
        setOwnedState((previous) => {
          if (
            previous.scopeKey !== requestScope.scopeKey ||
            previous.localGeneration !== requestScope.localGeneration ||
            previous.reloadToken !== requestReload
          )
            return previous;
          if (catalog.workspaceId !== workspaceId)
            return { ...previous, catalogLoading: false, error: reviewProtocolFailure() };
          return { ...previous, catalogLoading: false, catalog, error: undefined };
        });
      })
      .catch((error: unknown) => {
        if (!active) return;
        setOwnedState((previous) =>
          previous.scopeKey === requestScope.scopeKey &&
          previous.localGeneration === requestScope.localGeneration &&
          previous.reloadToken === requestReload
            ? { ...previous, catalogLoading: false, error: normalizeReviewFailure(error) }
            : previous,
        );
      });
    return () => {
      active = false;
    };
  }, [activeScope, adapter, scopeKey, state.reloadToken, workspaceId]);

  useEffect(() => {
    if (workspaceId === undefined) return undefined;
    let active = true;
    const requestScope = activeScope;
    const requestReload = state.reloadToken;
    const requestSource = state.source;
    setOwnedState((previous) =>
      previous.scopeKey === scopeKey && previous.reloadToken === requestReload
        ? { ...previous, loading: true, error: undefined, diff: undefined, diffError: undefined }
        : previous,
    );
    void adapter
      .snapshot({ workspaceId, source: requestSource })
      .then((snapshot) => {
        if (!active) return;
        setOwnedState((previous) => {
          if (
            previous.scopeKey !== requestScope.scopeKey ||
            previous.localGeneration !== requestScope.localGeneration ||
            previous.reloadToken !== requestReload ||
            sourceKey(previous.source) !== sourceKey(requestSource)
          )
            return previous;
          if (!snapshotBelongsTo(snapshot, workspaceId, requestSource))
            return { ...previous, loading: false, error: reviewProtocolFailure() };
          return withSnapshot(previous, snapshot);
        });
      })
      .catch((error: unknown) => {
        if (!active) return;
        setOwnedState((previous) =>
          previous.scopeKey === requestScope.scopeKey &&
          previous.localGeneration === requestScope.localGeneration &&
          previous.reloadToken === requestReload &&
          sourceKey(previous.source) === sourceKey(requestSource)
            ? {
                ...previous,
                loading: false,
                snapshot: undefined,
                diff: undefined,
                diffError: undefined,
                error: normalizeReviewFailure(error),
              }
            : previous,
        );
      });
    return () => {
      active = false;
    };
  }, [activeScope, adapter, scopeKey, state.reloadToken, state.source, workspaceId]);

  useEffect(() => {
    if (
      workspaceId === undefined ||
      state.loading ||
      state.selectedFileId === undefined ||
      state.snapshot === undefined
    )
      return undefined;
    const selectedFile = findReviewFile(state.snapshot, state.selectedFileId);
    if (selectedFile === undefined) return undefined;
    let active = true;
    const requestScope = activeScope;
    const requestReload = state.reloadToken;
    const requestSource = state.source;
    const requestRevision = state.snapshot.revision;
    const requestFileId = selectedFile.fileId;
    setOwnedState((previous) =>
      previous.scopeKey === scopeKey &&
      previous.reloadToken === requestReload &&
      previous.selectedFileId === requestFileId
        ? { ...previous, diffLoading: true, diff: undefined, diffError: undefined }
        : previous,
    );
    void adapter
      .fileDiff({
        workspaceId,
        source: requestSource,
        revision: requestRevision,
        fileId: requestFileId,
      })
      .then((diff) => {
        if (!active) return;
        setOwnedState((previous) => {
          if (
            previous.scopeKey !== requestScope.scopeKey ||
            previous.localGeneration !== requestScope.localGeneration ||
            previous.reloadToken !== requestReload ||
            previous.selectedFileId !== requestFileId ||
            previous.snapshot?.revision !== requestRevision
          )
            return previous;
          if (!diffMatchesSnapshot(diff, previous.snapshot))
            return {
              ...previous,
              diffLoading: false,
              diff: undefined,
              diffError: reviewProtocolFailure(),
            };
          return { ...previous, diffLoading: false, diff, diffError: undefined };
        });
      })
      .catch((error: unknown) => {
        if (!active) return;
        setOwnedState((previous) =>
          previous.scopeKey === requestScope.scopeKey &&
          previous.localGeneration === requestScope.localGeneration &&
          previous.reloadToken === requestReload &&
          previous.selectedFileId === requestFileId &&
          previous.snapshot?.revision === requestRevision
            ? {
                ...previous,
                diffLoading: false,
                diff: undefined,
                diffError: normalizeReviewFailure(error),
              }
            : previous,
        );
      });
    return () => {
      active = false;
    };
  }, [
    activeScope,
    adapter,
    scopeKey,
    state.loading,
    state.reloadToken,
    state.selectedFileId,
    state.snapshot,
    state.source,
    workspaceId,
  ]);

  useEffect(() => {
    if (workspaceId === undefined) return undefined;
    let active = true;
    let unsubscribe: (() => void | Promise<void>) | undefined;
    void adapter
      .subscribeInvalidated((event) => {
        if (active && eventMatches(event, workspaceId, generation)) refreshScope();
      })
      .then((unlisten) => {
        if (active) unsubscribe = unlisten;
        else void unlisten();
      })
      .catch((error: unknown) => {
        if (active)
          setOwnedState((previous) =>
            previous.scopeKey === scopeKey
              ? { ...previous, error: normalizeReviewFailure(error) }
              : previous,
          );
      });
    return () => {
      active = false;
      if (unsubscribe !== undefined) void unsubscribe();
    };
  }, [adapter, generation, refreshScope, scopeKey, workspaceId]);

  const sourceOptions = useMemo(() => sourceOptionsFor(state.catalog), [state.catalog]);
  const visibleFiles = useMemo(
    () => filterReviewFiles(state.snapshot?.files ?? [], state.filter, state.query),
    [state.filter, state.query, state.snapshot?.files],
  );
  const selectedFile = useMemo(
    () => findReviewFile(state.snapshot, state.selectedFileId),
    [state.selectedFileId, state.snapshot],
  );

  /** 只允许切换到 catalog 支持的来源，并立即清空依赖旧来源的读取结果。 */
  const setSource = useCallback(
    (source: ReviewSource) => {
      if (!sourceOptions.some((candidate) => sourceKey(candidate) === sourceKey(source))) return;
      setOwnedState((previous) =>
        previous.scopeKey === scopeKey
          ? {
              ...previous,
              source,
              loading: true,
              snapshot: undefined,
              diff: undefined,
              selectedFileId: undefined,
              error: undefined,
              diffError: undefined,
            }
          : previous,
      );
    },
    [scopeKey, sourceOptions],
  );

  /** 只选择当前权威快照中的文件，随后让 Diff effect 按新身份读取。 */
  const selectFile = useCallback(
    (fileId: string) => {
      setOwnedState((previous) =>
        previous.scopeKey === scopeKey &&
        previous.snapshot?.files.some((file) => file.fileId === fileId) === true
          ? {
              ...previous,
              selectedFileId: fileId,
              diff: undefined,
              diffError: undefined,
              drawerOpen: false,
            }
          : previous,
      );
    },
    [scopeKey],
  );

  /** 更新本地状态筛选，但不改变 native revision 或文件身份。 */
  const setFilter = useCallback(
    (filter: ReviewFilter) => {
      setOwnedState((previous) => {
        if (previous.scopeKey !== scopeKey) return previous;
        const nextFiles = filterReviewFiles(previous.snapshot?.files ?? [], filter, previous.query);
        return {
          ...previous,
          filter,
          selectedFileId: retainFileSelection(nextFiles, previous.selectedFileId),
          diff: undefined,
          diffError: undefined,
        };
      });
    },
    [scopeKey],
  );

  /** 更新本地文件查询并保留权威快照，筛选结果不回写 native。 */
  const setQuery = useCallback(
    (query: string) => {
      setOwnedState((previous) => {
        if (previous.scopeKey !== scopeKey) return previous;
        const nextFiles = filterReviewFiles(previous.snapshot?.files ?? [], previous.filter, query);
        return {
          ...previous,
          query,
          selectedFileId: retainFileSelection(nextFiles, previous.selectedFileId),
          diff: undefined,
          diffError: undefined,
        };
      });
    },
    [scopeKey],
  );

  /** 仅切换本地显示模式，不生成 patch 或伪造 native 状态。 */
  const setViewMode = useCallback(
    (viewMode: ReviewViewMode) => {
      setOwnedState((previous) =>
        previous.scopeKey === scopeKey ? { ...previous, viewMode } : previous,
      );
    },
    [scopeKey],
  );

  /** 开关窄屏文件抽屉而不改变当前文件选择。 */
  const setDrawerOpen = useCallback(
    (drawerOpen: boolean) => {
      setOwnedState((previous) =>
        previous.scopeKey === scopeKey ? { ...previous, drawerOpen } : previous,
      );
    },
    [scopeKey],
  );

  /** 以 revision 与 scope 栅栏执行一次 native all/file/hunk 操作，并用 single-flight 防止重复提交。 */
  const applyAction = useCallback(
    async (action: ReviewAction, target: ReviewTarget): Promise<void> => {
      if (
        workspaceId === undefined ||
        state.snapshot === undefined ||
        !sourceAllowsMutation(state.source) ||
        !canReviewAction(state.snapshot.capabilities, action) ||
        !targetBelongsToSnapshot(state.snapshot, target)
      )
        return;
      const requestScope = activeScope;
      const requestSource = state.source;
      const requestRevision = state.snapshot.revision;
      const key = `${sourceKey(requestSource)}:${requestRevision}:${action}:${JSON.stringify(target)}`;
      if (operationKeysRef.current.has(key)) return;
      operationKeysRef.current.add(key);
      const requestOperationId = createOperationId(action);
      setOwnedState((previous) =>
        previous.scopeKey === scopeKey && previous.snapshot?.revision === requestRevision
          ? {
              ...previous,
              pendingOperationIds: new Set([...previous.pendingOperationIds, requestOperationId]),
              error: undefined,
              notice: undefined,
            }
          : previous,
      );
      try {
        const result = await adapter.apply({
          workspaceId,
          source: requestSource,
          revision: requestRevision,
          action,
          target,
          operationId: requestOperationId,
        });
        setOwnedState((previous) => {
          if (
            previous.scopeKey !== requestScope.scopeKey ||
            previous.localGeneration !== requestScope.localGeneration ||
            previous.snapshot?.revision !== requestRevision ||
            sourceKey(previous.source) !== sourceKey(requestSource)
          )
            return previous;
          if (
            result.workspaceId !== workspaceId ||
            result.operationId !== requestOperationId ||
            !snapshotBelongsTo(result.snapshot, workspaceId, requestSource)
          )
            return { ...previous, error: reviewProtocolFailure(), notice: undefined };
          return {
            ...withSnapshot(previous, result.snapshot),
            notice:
              action === "stage" ? "已暂存。" : action === "unstage" ? "已取消暂存。" : "已撤销。",
          };
        });
      } catch (error) {
        setOwnedState((previous) =>
          previous.scopeKey === requestScope.scopeKey &&
          previous.localGeneration === requestScope.localGeneration
            ? { ...previous, error: normalizeReviewFailure(error) }
            : previous,
        );
      } finally {
        operationKeysRef.current.delete(key);
        setOwnedState((previous) => {
          if (
            previous.scopeKey !== requestScope.scopeKey ||
            previous.localGeneration !== requestScope.localGeneration
          )
            return previous;
          const pendingOperationIds = new Set(previous.pendingOperationIds);
          pendingOperationIds.delete(requestOperationId);
          return { ...previous, pendingOperationIds };
        });
      }
    },
    [activeScope, adapter, scopeKey, state.snapshot, state.source, workspaceId],
  );

  /** 只取消当前作用域仍 pending 的操作；已经提交的 native 写入继续保持权威。 */
  const cancelOperation = useCallback(
    async (requestOperationId: string): Promise<void> => {
      if (workspaceId === undefined || !state.pendingOperationIds.has(requestOperationId)) return;
      try {
        await adapter.cancel({ workspaceId, operationId: requestOperationId });
      } catch (error) {
        setOwnedState((previous) =>
          previous.scopeKey === scopeKey
            ? { ...previous, error: normalizeReviewFailure(error) }
            : previous,
        );
      }
    },
    [adapter, scopeKey, state.pendingOperationIds, workspaceId],
  );

  /** 只清理当前作用域的瞬时通知，不能影响 error 或权威快照。 */
  const clearNotice = useCallback(() => {
    setOwnedState((previous) =>
      previous.scopeKey === scopeKey ? { ...previous, notice: undefined } : previous,
    );
  }, [scopeKey]);

  return {
    viewModel: { state, sourceOptions, visibleFiles, selectedFile },
    actions: {
      refresh,
      setSource,
      selectFile,
      setFilter,
      setQuery,
      setViewMode,
      setDrawerOpen,
      applyAction,
      cancelOperation,
      clearNotice,
    },
  };
}

/** 为每次写入生成独立幂等键；native 仍负责最终去重和事务语义。 */
function createOperationId(prefix: string): string {
  const random =
    typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `review_${prefix}_${random}`.slice(0, 128);
}
