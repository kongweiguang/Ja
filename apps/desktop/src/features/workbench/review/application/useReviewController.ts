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
  layerFilter: ReviewLayerFilter;
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
  setLayerFilter: (filter: ReviewLayerFilter) => void;
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

export type ReviewLayerFilter = "all" | "staged" | "unstaged" | "untracked";

export interface UseReviewControllerOptions {
  workspaceId: string | undefined;
  generation?: number;
  readonly selectionScopeId?: string;
  readonly snapshotEnabled: boolean;
  readonly catalogEnabled?: boolean;
  adapter?: ReviewPort;
}

interface ScopedState extends ReviewControllerState {
  scopeKey: string;
  localGeneration: number;
  reloadToken: number;
  catalogReloadToken: number;
}

interface ScopeIdentity {
  scopeKey: string;
  localGeneration: number;
}

interface ReviewSelectionHint {
  readonly path: string;
  readonly layer: ReviewFile["layer"];
}

interface QueuedDiffRequest {
  readonly key: string;
  readonly promise: Promise<ReviewFileDiff>;
  readonly start: () => Promise<ReviewFileDiff>;
  readonly resolve: (diff: ReviewFileDiff) => void;
  readonly reject: (error: unknown) => void;
}

interface DiffRequestLane {
  readonly groupKey: string;
  disposed: boolean;
  active?: QueuedDiffRequest;
  pending?: QueuedDiffRequest;
}

class SupersededDiffRequest extends Error {}

/** metadata-only 条目没有可读取正文，所有选择入口必须共用该判定以免留下永久 loading。 */
function requiresFileDiff(file: ReviewFile | undefined): boolean {
  return file !== undefined && !file.binary && !file.truncated;
}

/** 合并 React effect 重放产生的同键请求；完成后即释放，刷新仍会建立新的权威读取。 */
function singleflightRequest<T>(
  requests: Map<string, Promise<T>>,
  key: string,
  create: () => Promise<T>,
): Promise<T> {
  const pending = requests.get(key);
  if (pending !== undefined) return pending;
  const request = create();
  requests.set(key, request);
  void request.then(
    () => {
      if (requests.get(key) === request) requests.delete(key);
    },
    () => {
      if (requests.get(key) === request) requests.delete(key);
    },
  );
  return request;
}

/** 创建尚未进入 native 的 Diff 请求，允许更晚选择在 Host mutex 排队前替换它。 */
function queuedDiffRequest(key: string, start: () => Promise<ReviewFileDiff>): QueuedDiffRequest {
  let resolve!: (diff: ReviewFileDiff) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<ReviewFileDiff>((accept, decline) => {
    resolve = accept;
    reject = decline;
  });
  return { key, promise, start, resolve, reject };
}

/** 每个稳定 snapshot lane 同时只执行一个 native Diff；完成后只启动最后一次文件选择。 */
function startQueuedDiff(lane: DiffRequestLane, request: QueuedDiffRequest): void {
  if (lane.disposed) {
    request.reject(new SupersededDiffRequest());
    return;
  }
  lane.active = request;
  let nativeRequest: Promise<ReviewFileDiff>;
  try {
    nativeRequest = request.start();
  } catch (error) {
    nativeRequest = Promise.reject(error);
  }
  void nativeRequest.then(request.resolve, request.reject).finally(() => {
    if (lane.active === request) lane.active = undefined;
    if (lane.disposed) return;
    const next = lane.pending;
    lane.pending = undefined;
    if (next !== undefined) startQueuedDiff(lane, next);
  });
}

/** 同文件复用 active/pending Promise；不同文件仅保留 latest pending，避免中间点击进入 native。 */
function scheduleLatestDiff(
  lane: DiffRequestLane,
  key: string,
  start: () => Promise<ReviewFileDiff>,
): Promise<ReviewFileDiff> {
  if (lane.active?.key === key) {
    lane.pending?.reject(new SupersededDiffRequest());
    lane.pending = undefined;
    return lane.active.promise;
  }
  if (lane.pending?.key === key) return lane.pending.promise;
  const request = queuedDiffRequest(key, start);
  if (lane.active === undefined) startQueuedDiff(lane, request);
  else {
    lane.pending?.reject(new SupersededDiffRequest());
    lane.pending = request;
  }
  return request.promise;
}

/** scope/source/revision/隐藏态改变时只撤销未发送项；匿名 native active 请求只能迟到后丢弃。 */
function disposeDiffLane(lane: DiffRequestLane | undefined): void {
  if (lane === undefined || lane.disposed) return;
  lane.disposed = true;
  lane.pending?.reject(new SupersededDiffRequest());
  lane.pending = undefined;
}

/** pending Diff 键包含作用域 epoch，scope A→B→A 不会复用第一次 A 的遗留请求。 */
function fileDiffRequestKey(
  scopeKey: string,
  localGeneration: number,
  source: ReviewSource,
  revision: string,
  fileId: string,
): string {
  return `${scopeKey}:${localGeneration}:${sourceKey(source)}:${revision}:${fileId}`;
}

/** 文件选择提示同时绑定 UI scope 与 Git source，避免同 Workspace 的不同 Thread 互相命中。 */
function selectionHintKey(scopeKey: string, source: ReviewSource): string {
  return `${scopeKey}:${sourceKey(source)}`;
}

/** 创建干净作用域；普通 Review 未激活时所有 Git 派生读取和 loading 都保持关闭。 */
function initialState(
  source: ReviewSource,
  scopeKey: string,
  localGeneration: number,
  workspaceAvailable: boolean,
  snapshotEnabled: boolean,
): ScopedState {
  return {
    scopeKey,
    localGeneration,
    reloadToken: 0,
    catalogReloadToken: 0,
    loading: workspaceAvailable && snapshotEnabled,
    catalogLoading: workspaceAvailable && snapshotEnabled,
    diffLoading: false,
    source,
    catalog: undefined,
    snapshot: undefined,
    diff: undefined,
    selectedFileId: undefined,
    filter: "all",
    layerFilter: "all",
    query: "",
    viewMode: "split",
    drawerOpen: false,
    error: undefined,
    diffError: undefined,
    notice: undefined,
    pendingOperationIds: new Set<string>(),
  };
}

/**
 * 隐藏时释放全部投影；终态阅读可仅保留轻量 Catalog，让用户仍能切回 Git 而不物化工作树。
 */
function withoutReviewData(previous: ScopedState, preserveCatalog = false): ScopedState {
  if (
    !previous.loading &&
    (preserveCatalog || !previous.catalogLoading) &&
    !previous.diffLoading &&
    (preserveCatalog || previous.catalog === undefined) &&
    previous.snapshot === undefined &&
    previous.diff === undefined &&
    previous.selectedFileId === undefined &&
    previous.diffError === undefined
  )
    return previous;
  return {
    ...previous,
    loading: false,
    catalogLoading: preserveCatalog ? previous.catalogLoading : false,
    diffLoading: false,
    catalog: preserveCatalog ? previous.catalog : undefined,
    snapshot: undefined,
    diff: undefined,
    selectedFileId: undefined,
    diffError: undefined,
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
  const options: ReviewSource[] = [
    { kind: "uncommitted" },
    { kind: "unstaged" },
    { kind: "staged" },
  ];
  for (const ref of catalog?.baseRefs ?? []) options.push({ kind: "branch", refId: ref.refId });
  for (const commit of catalog?.commits ?? [])
    options.push({ kind: "commit", commitId: commit.commitId });
  return options;
}

/** 文件身份来自快照；lazy hunk 身份必须来自当前 revision 已验证 Diff，拒绝 UI 自造区块。 */
function targetBelongsToSnapshot(
  snapshot: ReviewSnapshot,
  diff: ReviewFileDiff | undefined,
  target: ReviewTarget,
): boolean {
  if (target.kind === "all") return snapshot.files.length > 0;
  const file = snapshot.files.find((candidate) => candidate.fileId === target.fileId);
  if (file === undefined) return false;
  if (target.kind === "file") return true;
  return (
    diff !== undefined &&
    diff.fileId === file.fileId &&
    diffMatchesSnapshot(diff, snapshot) &&
    diff.hunks.some((hunk) => hunk.hunkId === target.hunkId)
  );
}

/** 应用已加载快照时只保留仍存在的文件选择，并清空依赖旧 revision 的 Diff。 */
function withSnapshot(
  previous: ScopedState,
  snapshot: ReviewSnapshot,
  hint: ReviewSelectionHint | undefined,
): ScopedState {
  const hinted =
    hint === undefined
      ? undefined
      : snapshot.files.find((file) => file.path === hint.path && file.layer === hint.layer);
  const selectedFileId =
    hinted?.fileId ?? retainFileSelection(snapshot.files, previous.selectedFileId);
  const selectedFile = snapshot.files.find((file) => file.fileId === selectedFileId);
  return {
    ...previous,
    loading: false,
    snapshot,
    selectedFileId,
    diff: undefined,
    diffLoading: requiresFileDiff(selectedFile),
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

/**
 * 统一拥有 Catalog、按可见性激活的 snapshot/Diff 与 mutation 编排；scope/revision 和
 * effect cleanup 共同拒绝 workspace、source 或激活状态变化后的迟到响应。
 */
export function useReviewController({
  workspaceId,
  generation,
  selectionScopeId,
  snapshotEnabled,
  catalogEnabled = snapshotEnabled,
  adapter = unavailableReviewPort,
}: UseReviewControllerOptions): ReviewController {
  const scopeKey = `${workspaceId ?? ""}:${generation ?? ""}:${selectionScopeId ?? ""}`;
  const scopeRef = useRef<ScopeIdentity>({ scopeKey, localGeneration: 0 });
  if (scopeRef.current.scopeKey !== scopeKey) {
    scopeRef.current = { scopeKey, localGeneration: scopeRef.current.localGeneration + 1 };
  }
  const activeScope = scopeRef.current;
  const defaultSource: ReviewSource = { kind: "uncommitted" };
  const [ownedState, setOwnedState] = useState<ScopedState>(() =>
    initialState(
      defaultSource,
      scopeKey,
      activeScope.localGeneration,
      workspaceId !== undefined,
      snapshotEnabled,
    ),
  );
  const scopedState =
    ownedState.scopeKey === scopeKey
      ? ownedState
      : initialState(
          defaultSource,
          scopeKey,
          activeScope.localGeneration,
          workspaceId !== undefined,
          snapshotEnabled,
        );
  // scope 改变时在 effect 启动前同步迁移 owner，否则首批异步响应无法通过新 scope 的 CAS。
  if (ownedState.scopeKey !== scopeKey) setOwnedState(scopedState);
  const state = snapshotEnabled ? scopedState : withoutReviewData(scopedState, catalogEnabled);
  const operationKeysRef = useRef(new Set<string>());
  const selectionHintsRef = useRef(new Map<string, ReviewSelectionHint>());
  const catalogRequestsRef = useRef(new Map<string, Promise<ReviewCatalog>>());
  const snapshotRequestsRef = useRef(new Map<string, Promise<ReviewSnapshot>>());
  const diffLaneRef = useRef<DiffRequestLane | undefined>(undefined);
  const diffLaneLifetimeRef = useRef<object | undefined>(undefined);
  const activeSnapshotRequestRef = useRef<string | undefined>(undefined);
  const pendingSnapshotRefreshRef = useRef(false);

  /**
   * 真正卸载后撤销未发送 Diff；微任务身份栅栏让 React StrictMode 的 cleanup/setup 重放
   * 继续复用同一 active Promise，不会为了开发期探测重复进入 native。
   */
  useEffect(() => {
    const lifetime = {};
    diffLaneLifetimeRef.current = lifetime;
    return () => {
      queueMicrotask(() => {
        if (diffLaneLifetimeRef.current !== lifetime) return;
        diffLaneLifetimeRef.current = undefined;
        disposeDiffLane(diffLaneRef.current);
        diffLaneRef.current = undefined;
      });
    };
  }, []);

  /** 递增当前作用域重读令牌，旧作用域不能借此使新作用域失效。 */
  const refreshSnapshotScope = useCallback(() => {
    setOwnedState((previous) =>
      previous.scopeKey === scopeKey
        ? { ...previous, reloadToken: previous.reloadToken + 1 }
        : previous,
    );
  }, [scopeKey]);

  /** catalog 只在首次进入和显式刷新时重读，文件 watcher 不重复枚举 branch/commit。 */
  const refreshCatalogScope = useCallback(() => {
    setOwnedState((previous) =>
      previous.scopeKey === scopeKey
        ? { ...previous, catalogReloadToken: previous.catalogReloadToken + 1 }
        : previous,
    );
  }, [scopeKey]);

  /** 只有普通 Review 激活时才接受刷新，隐藏失效通知不得排队触发后续 Git 读取。 */
  const refresh = useCallback(() => {
    if (workspaceId === undefined || !snapshotEnabled) return;
    refreshCatalogScope();
    if (activeSnapshotRequestRef.current !== undefined) {
      pendingSnapshotRefreshRef.current = true;
      return;
    }
    refreshSnapshotScope();
  }, [refreshCatalogScope, refreshSnapshotScope, snapshotEnabled, workspaceId]);

  useEffect(() => {
    if (snapshotEnabled) return;
    if (!catalogEnabled) catalogRequestsRef.current.clear();
    snapshotRequestsRef.current.clear();
    disposeDiffLane(diffLaneRef.current);
    diffLaneRef.current = undefined;
    activeSnapshotRequestRef.current = undefined;
    pendingSnapshotRefreshRef.current = false;
    setOwnedState((previous) =>
      previous.scopeKey === scopeKey ? withoutReviewData(previous, catalogEnabled) : previous,
    );
  }, [catalogEnabled, scopeKey, snapshotEnabled]);

  useEffect(() => {
    if (workspaceId === undefined || !catalogEnabled) return undefined;
    let active = true;
    const requestScope = activeScope;
    const requestReload = state.catalogReloadToken;
    setOwnedState((previous) =>
      previous.scopeKey === scopeKey && previous.catalogReloadToken === requestReload
        ? { ...previous, catalogLoading: true, error: undefined }
        : previous,
    );
    const requestKey = `${scopeKey}:${requestScope.localGeneration}:catalog:${requestReload}`;
    void singleflightRequest(catalogRequestsRef.current, requestKey, () =>
      adapter.catalog({ workspaceId, maxCommits: 50 }),
    )
      .then((catalog) => {
        if (!active) return;
        setOwnedState((previous) => {
          if (
            previous.scopeKey !== requestScope.scopeKey ||
            previous.localGeneration !== requestScope.localGeneration ||
            previous.catalogReloadToken !== requestReload
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
          previous.catalogReloadToken === requestReload
            ? { ...previous, catalogLoading: false, error: normalizeReviewFailure(error) }
            : previous,
        );
      });
    return () => {
      active = false;
    };
  }, [activeScope, adapter, catalogEnabled, scopeKey, state.catalogReloadToken, workspaceId]);

  useEffect(() => {
    if (workspaceId === undefined || !snapshotEnabled) return undefined;
    let active = true;
    const requestScope = activeScope;
    const requestReload = state.reloadToken;
    const requestSource = state.source;
    const requestKey = `${scopeKey}:${requestScope.localGeneration}:snapshot:${sourceKey(requestSource)}:${requestReload}`;
    activeSnapshotRequestRef.current = requestKey;
    disposeDiffLane(diffLaneRef.current);
    diffLaneRef.current = undefined;
    setOwnedState((previous) =>
      previous.scopeKey === scopeKey && previous.reloadToken === requestReload
        ? { ...previous, loading: true, error: undefined, diff: undefined, diffError: undefined }
        : previous,
    );
    void singleflightRequest(snapshotRequestsRef.current, requestKey, () =>
      adapter.snapshot({ workspaceId, source: requestSource }),
    )
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
          return withSnapshot(
            previous,
            snapshot,
            selectionHintsRef.current.get(selectionHintKey(scopeKey, requestSource)),
          );
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
      })
      .finally(() => {
        if (!active || activeSnapshotRequestRef.current !== requestKey) return;
        activeSnapshotRequestRef.current = undefined;
        if (!pendingSnapshotRefreshRef.current) return;
        pendingSnapshotRefreshRef.current = false;
        refreshSnapshotScope();
      });
    return () => {
      active = false;
    };
  }, [
    activeScope,
    adapter,
    scopeKey,
    snapshotEnabled,
    refreshSnapshotScope,
    state.reloadToken,
    state.source,
    workspaceId,
  ]);

  useEffect(() => {
    if (
      workspaceId === undefined ||
      !snapshotEnabled ||
      state.loading ||
      state.selectedFileId === undefined ||
      state.snapshot === undefined
    )
      return undefined;
    const selectedFile = findReviewFile(state.snapshot, state.selectedFileId);
    if (selectedFile === undefined) return undefined;
    if (!requiresFileDiff(selectedFile)) return undefined;
    let active = true;
    const requestScope = activeScope;
    const requestReload = state.reloadToken;
    const requestSource = state.source;
    const requestRevision = state.snapshot.revision;
    const requestFileId = selectedFile.fileId;
    const requestSnapshot = state.snapshot;
    const requestKey = fileDiffRequestKey(
      scopeKey,
      requestScope.localGeneration,
      requestSource,
      requestRevision,
      requestFileId,
    );
    const requestGroupKey = `${scopeKey}:${requestScope.localGeneration}:${sourceKey(requestSource)}:${requestRevision}`;
    let requestLane = diffLaneRef.current;
    if (requestLane === undefined || requestLane.groupKey !== requestGroupKey) {
      disposeDiffLane(requestLane);
      requestLane = { groupKey: requestGroupKey, disposed: false };
      diffLaneRef.current = requestLane;
    }
    setOwnedState((previous) =>
      previous.scopeKey === scopeKey &&
      previous.reloadToken === requestReload &&
      previous.selectedFileId === requestFileId
        ? { ...previous, diffLoading: true, diff: undefined, diffError: undefined }
        : previous,
    );
    void scheduleLatestDiff(requestLane, requestKey, () =>
      adapter.fileDiff({
        workspaceId,
        source: requestSource,
        revision: requestRevision,
        fileId: requestFileId,
      }),
    )
      .then((diff) => {
        if (!active) return;
        const valid = diff.fileId === requestFileId && diffMatchesSnapshot(diff, requestSnapshot);
        setOwnedState((previous) => {
          if (
            previous.scopeKey !== requestScope.scopeKey ||
            previous.localGeneration !== requestScope.localGeneration ||
            previous.reloadToken !== requestReload ||
            previous.selectedFileId !== requestFileId ||
            previous.snapshot?.revision !== requestRevision
          )
            return previous;
          if (!valid || !diffMatchesSnapshot(diff, previous.snapshot))
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
        if (!active || error instanceof SupersededDiffRequest) return;
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
    snapshotEnabled,
    state.loading,
    state.reloadToken,
    state.selectedFileId,
    state.snapshot,
    state.source,
    workspaceId,
  ]);

  useEffect(() => {
    if (workspaceId === undefined || !snapshotEnabled) return undefined;
    let active = true;
    let unsubscribe: (() => void | Promise<void>) | undefined;
    void adapter
      .subscribeInvalidated((event) => {
        if (!active || !eventMatches(event, workspaceId, generation)) return;
        if (activeSnapshotRequestRef.current !== undefined) {
          pendingSnapshotRefreshRef.current = true;
          return;
        }
        refreshSnapshotScope();
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
  }, [adapter, generation, refreshSnapshotScope, scopeKey, snapshotEnabled, workspaceId]);

  const sourceOptions = useMemo(() => sourceOptionsFor(state.catalog), [state.catalog]);
  const visibleFiles = useMemo(() => {
    const filtered = filterReviewFiles(state.snapshot?.files ?? [], state.filter, state.query);
    return state.layerFilter === "all"
      ? filtered
      : filtered.filter((file) => file.layer === state.layerFilter);
  }, [state.filter, state.layerFilter, state.query, state.snapshot?.files]);
  const selectedFile = useMemo(
    () => findReviewFile(state.snapshot, state.selectedFileId),
    [state.selectedFileId, state.snapshot],
  );

  /** 首次默认选择也按 UI scope 写入轻量 path/layer 提示，刷新可恢复但不会串到另一 Thread。 */
  useEffect(() => {
    if (selectedFile === undefined) return;
    selectionHintsRef.current.set(selectionHintKey(scopeKey, state.source), {
      path: selectedFile.path,
      layer: selectedFile.layer,
    });
  }, [scopeKey, selectedFile, state.source]);

  /** 只允许切换到 catalog 支持的来源，并立即清空依赖旧来源的读取结果。 */
  const setSource = useCallback(
    (source: ReviewSource) => {
      if (!sourceOptions.some((candidate) => sourceKey(candidate) === sourceKey(source))) return;
      setOwnedState((previous) =>
        previous.scopeKey === scopeKey && sourceKey(previous.source) !== sourceKey(source)
          ? {
              ...previous,
              source,
              layerFilter: source.kind === "uncommitted" ? previous.layerFilter : "all",
              loading: snapshotEnabled,
              snapshot: undefined,
              diff: undefined,
              selectedFileId: undefined,
              error: undefined,
              diffError: undefined,
            }
          : previous,
      );
    },
    [scopeKey, snapshotEnabled, sourceOptions],
  );

  /** 只选择当前权威快照中的文件，随后让 Diff effect 按新身份读取。 */
  const selectFile = useCallback(
    (fileId: string) => {
      setOwnedState((previous) => {
        if (previous.scopeKey !== scopeKey) return previous;
        const file = previous.snapshot?.files.find((candidate) => candidate.fileId === fileId);
        if (file === undefined) return previous;
        selectionHintsRef.current.set(selectionHintKey(scopeKey, previous.source), {
          path: file.path,
          layer: file.layer,
        });
        if (previous.selectedFileId === fileId) return previous;
        return {
          ...previous,
          selectedFileId: fileId,
          diff: undefined,
          diffLoading: requiresFileDiff(file),
          diffError: undefined,
          drawerOpen: false,
        };
      });
    },
    [scopeKey],
  );

  /** 更新本地状态筛选，但不改变 native revision 或文件身份。 */
  const setFilter = useCallback(
    (filter: ReviewFilter) => {
      setOwnedState((previous) => {
        if (previous.scopeKey !== scopeKey) return previous;
        const statusFiles = filterReviewFiles(
          previous.snapshot?.files ?? [],
          filter,
          previous.query,
        );
        const nextFiles =
          previous.layerFilter === "all"
            ? statusFiles
            : statusFiles.filter((file) => file.layer === previous.layerFilter);
        const selectedFileId = retainFileSelection(nextFiles, previous.selectedFileId);
        const selectedFile = nextFiles.find((file) => file.fileId === selectedFileId);
        const selectionChanged = selectedFileId !== previous.selectedFileId;
        return {
          ...previous,
          filter,
          selectedFileId,
          diff: selectionChanged ? undefined : previous.diff,
          diffLoading: selectionChanged ? requiresFileDiff(selectedFile) : previous.diffLoading,
          diffError: selectionChanged ? undefined : previous.diffError,
        };
      });
    },
    [scopeKey],
  );

  /** layer 筛选只作用于未提交聚合；选择仍保留 native 返回的跨层 fileId。 */
  const setLayerFilter = useCallback(
    (layerFilter: ReviewLayerFilter) => {
      setOwnedState((previous) => {
        if (previous.scopeKey !== scopeKey) return previous;
        const statusFiles = filterReviewFiles(
          previous.snapshot?.files ?? [],
          previous.filter,
          previous.query,
        );
        const nextFiles =
          layerFilter === "all"
            ? statusFiles
            : statusFiles.filter((file) => file.layer === layerFilter);
        const selectedFileId = retainFileSelection(nextFiles, previous.selectedFileId);
        const selectedFile = nextFiles.find((file) => file.fileId === selectedFileId);
        const selectionChanged = selectedFileId !== previous.selectedFileId;
        return {
          ...previous,
          layerFilter,
          selectedFileId,
          diff: selectionChanged ? undefined : previous.diff,
          diffLoading: selectionChanged ? requiresFileDiff(selectedFile) : previous.diffLoading,
          diffError: selectionChanged ? undefined : previous.diffError,
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
        const statusFiles = filterReviewFiles(
          previous.snapshot?.files ?? [],
          previous.filter,
          query,
        );
        const nextFiles =
          previous.layerFilter === "all"
            ? statusFiles
            : statusFiles.filter((file) => file.layer === previous.layerFilter);
        const selectedFileId = retainFileSelection(nextFiles, previous.selectedFileId);
        const selectedFile = nextFiles.find((file) => file.fileId === selectedFileId);
        const selectionChanged = selectedFileId !== previous.selectedFileId;
        return {
          ...previous,
          query,
          selectedFileId,
          diff: selectionChanged ? undefined : previous.diff,
          diffLoading: selectionChanged ? requiresFileDiff(selectedFile) : previous.diffLoading,
          diffError: selectionChanged ? undefined : previous.diffError,
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
        !snapshotEnabled ||
        state.snapshot === undefined ||
        !sourceAllowsMutation(state.source) ||
        !canReviewAction(state.snapshot.capabilities, action) ||
        !targetBelongsToSnapshot(state.snapshot, state.diff, target)
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
            ...withSnapshot(
              previous,
              result.snapshot,
              selectionHintsRef.current.get(selectionHintKey(scopeKey, requestSource)),
            ),
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
    [
      activeScope,
      adapter,
      scopeKey,
      snapshotEnabled,
      state.diff,
      state.snapshot,
      state.source,
      workspaceId,
    ],
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
      setLayerFilter,
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
