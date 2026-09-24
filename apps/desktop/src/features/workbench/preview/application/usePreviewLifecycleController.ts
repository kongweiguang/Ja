// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { PreviewTarget } from "../domain/previewModel";
import { containsControlCharacters } from "@/shared/validation/previewUrl";
import type {
  NativePreviewPort,
  PreviewEvent,
  PreviewPageProjection,
  PreviewSessionSnapshot,
  PreviewUnsubscribe,
  PreviewViewport,
} from "./ports";
import type { PreviewSessionHintStorage } from "./previewSessionHintStorage";

const MAX_EARLY_PREVIEW_EVENTS = 512;
const PREVIEW_LOADING_RECONCILE_INTERVAL_MS = 500;
const PREVIEW_RENDERER_LOAD_DEADLINE_MS = 31_000;
const PREVIEW_LOAD_ERROR = "预览加载失败，请重试。";
const PREVIEW_OPEN_ERROR = "无法打开此页面，请重试。";
const PREVIEW_CLOSE_ERROR = "浏览器关闭失败，请重试。";
const PREVIEW_RECOVERY_ERROR = "浏览器恢复未完成，请重试。";
const PREVIEW_POPUP_BLOCKED = "网页弹窗已被拦截。";
const PREVIEW_DOWNLOAD_BLOCKED = "网页下载已被拦截。";
const HIDDEN_LAYOUT_FALLBACK: PreviewViewport = {
  x: 0,
  y: 0,
  width: 1024,
  height: 768,
  visible: false,
};

interface ManagedPreviewPage {
  snapshot: PreviewSessionSnapshot;
  error?: string;
  navigationIntent: number;
  failedNavigationIntent?: number;
}

interface PendingPreviewOpen {
  projectGeneration: number;
  cancelRequested: boolean;
  completion: Promise<void>;
}

interface PendingPreviewRecovery {
  projectGeneration: number;
  promise: Promise<void>;
}

interface PendingPreviewLayout {
  projectGeneration: number;
  sessionId: string;
  viewport: PreviewViewport;
}

export interface PreviewWorkspaceLifecycle {
  workspaceId: string;
  closeForWorkspaceChange: () => Promise<void>;
}

export interface PreviewLifecycleProjection {
  pages: readonly PreviewPageProjection[];
  activePageId?: string;
  url?: string;
  loading: boolean;
  recovering: boolean;
  error?: string;
  canGoBack: boolean;
  canGoForward: boolean;
  onOpenTarget: (target: PreviewTarget) => Promise<void>;
  onNewPage: () => Promise<void>;
  onSelectPage: (pageId: string) => void;
  onClosePage: (pageId: string) => Promise<void>;
  onNavigate: (url: string) => void;
  onNavigateFile: (path: string) => void;
  onGoBack: () => void;
  onGoForward: () => void;
  onReload: () => void;
  onRetryRecovery?: () => void;
  onViewportChange: (viewport: PreviewViewport) => void;
}

export interface PreviewLifecycleController {
  preview: PreviewLifecycleProjection;
  closePreview: () => Promise<void>;
  workspaceLifecycle: PreviewWorkspaceLifecycle | undefined;
}

/** 同一 WebView generation 的终态优先于迟到 loading 快照，同时保留最新历史与标题字段。 */
function mergePreviewSnapshot(
  current: PreviewSessionSnapshot,
  next: PreviewSessionSnapshot,
): PreviewSessionSnapshot {
  if (
    current.id !== next.id ||
    current.generation !== next.generation ||
    next.status === "closed" ||
    current.status === "closed" ||
    current.load_status === "loading" ||
    next.load_status !== "loading"
  )
    return next;
  return { ...next, load_status: current.load_status };
}

/** 比较完整矩形，让重复的 ResizeObserver 通知不重排原生 child WebView。 */
function previewViewportEquals(left: PreviewViewport | undefined, right: PreviewViewport): boolean {
  return (
    left !== undefined &&
    left.x === right.x &&
    left.y === right.y &&
    left.width === right.width &&
    left.height === right.height &&
    left.visible === right.visible
  );
}

/** 隐藏态仍须携带有效尺寸，首次打开可以先创建隐藏 child，再等待布局显示。 */
function hiddenViewport(viewport: PreviewViewport | undefined): PreviewViewport {
  return viewport === undefined
    ? HIDDEN_LAYOUT_FALLBACK
    : {
        ...viewport,
        width: Math.max(1, viewport.width),
        height: Math.max(1, viewport.height),
        visible: false,
      };
}

/** 仅允许已知的 adapter 文案穿过生命周期边界，其它错误一律收敛到固定提示。 */
function safePreviewError(error: unknown, fallback: string): string {
  if (!(error instanceof Error)) return fallback;
  const allowed = new Set([
    "预览请求参数无效",
    "预览返回数据无效",
    "预览操作失败",
    "文件路径无效。",
    "文件不存在或已被移动。",
    "当前没有读取此文件的权限。",
    "这是一个文件夹，无法在浏览器中打开。",
    "相对路径需要先打开工作区。",
    "当前工作区已关闭，请重新打开后重试。",
    "此文件类型暂不支持在浏览器中打开。",
  ]);
  return allowed.has(error.message) ? error.message : fallback;
}

/**
 * Preview lifecycle 以 Rust 签发的每页 session ID 为唯一身份；跨页状态只保存 UI 投影，
 * 历史由各自原生 WebView 管理，布局队列则保持 latest-wins 并串行隐藏旧页再显示当前页。
 */
export function usePreviewLifecycleController(
  workspaceId: string | undefined,
  adapter: NativePreviewPort,
  sessionHints: PreviewSessionHintStorage,
): PreviewLifecycleController {
  const [pages, setPages] = useState<ManagedPreviewPage[]>([]);
  const [activePageId, setActivePageId] = useState<string | undefined>(undefined);
  const [recovering, setRecovering] = useState(false);
  const [globalError, setGlobalError] = useState<string>();
  const projectGenerationRef = useRef(0);
  const pagesRef = useRef(new Map<string, ManagedPreviewPage>());
  const activePageIdRef = useRef<string | undefined>(undefined);
  const viewportRef = useRef<PreviewViewport | undefined>(undefined);
  const pendingLayoutsRef = useRef(new Map<string, PendingPreviewLayout>());
  const lastRequestedLayoutRef = useRef(new Map<string, PreviewViewport>());
  const lastAppliedLayoutRef = useRef(new Map<string, PreviewViewport>());
  const layoutTaskRef = useRef<Promise<void> | undefined>(undefined);
  const cleanupTaskRef = useRef<Promise<void> | undefined>(undefined);
  const pendingOpensRef = useRef(new Set<PendingPreviewOpen>());
  const closeTasksRef = useRef(new Map<string, Promise<void>>());
  const closingSessionIdsRef = useRef(new Set<string>());
  const earlyEventsRef = useRef<PreviewEvent[]>([]);
  const eventCursorRef = useRef(new Map<string, number>());
  const loadTimersRef = useRef(new Map<string, ReturnType<typeof setTimeout>>());
  const recoveryTaskRef = useRef<PendingPreviewRecovery | undefined>(undefined);
  const recoveryBlockedRef = useRef(false);
  const navigationIntentRef = useRef(0);
  const initializationRef = useRef<{ generation: number; promise: Promise<void> } | undefined>(
    undefined,
  );

  /** 发布有序 page map；Map 自身只作为同步的 lifecycle authority，不直接暴露给 React。 */
  const publishPages = useCallback((): void => {
    setPages([...pagesRef.current.values()]);
  }, []);

  /** 修改单页快照时复制 entry，避免后台标签的更新触发状态倒退或共享对象突变。 */
  const updatePage = useCallback(
    (pageId: string, update: (current: ManagedPreviewPage) => ManagedPreviewPage): void => {
      const current = pagesRef.current.get(pageId);
      if (current === undefined) return;
      const next = update(current);
      pagesRef.current.set(pageId, next);
      publishPages();
    },
    [publishPages],
  );

  /** 清理单页 renderer deadline；隐藏页面不做轮询，但其超时仍由该 timer 有界收口。 */
  const clearLoadDeadline = useCallback((pageId: string): void => {
    const timer = loadTimersRef.current.get(pageId);
    if (timer !== undefined) clearTimeout(timer);
    loadTimersRef.current.delete(pageId);
  }, []);

  /** 页面保持 loading 超过 native watchdog 预算时展示静态恢复错误，不读取后台页面内容。 */
  const startLoadDeadline = useCallback(
    (pageId: string, navigationIntent: number): void => {
      clearLoadDeadline(pageId);
      const timer = setTimeout(() => {
        const current = pagesRef.current.get(pageId);
        if (
          current === undefined ||
          current.navigationIntent !== navigationIntent ||
          current.snapshot.status !== "open" ||
          current.snapshot.load_status !== "loading"
        )
          return;
        updatePage(pageId, (page) => ({
          ...page,
          error: PREVIEW_LOAD_ERROR,
          failedNavigationIntent: navigationIntent,
        }));
      }, PREVIEW_RENDERER_LOAD_DEADLINE_MS);
      loadTimersRef.current.set(pageId, timer);
    },
    [clearLoadDeadline, updatePage],
  );

  /** 页面启动新的用户导航时先投影 loading，再设置每页独立的失败预算。 */
  const beginPageNavigation = useCallback(
    (pageId: string): number | undefined => {
      const current = pagesRef.current.get(pageId);
      if (current === undefined || current.snapshot.status !== "open") return undefined;
      const navigationIntent = ++navigationIntentRef.current;
      updatePage(pageId, (page) => ({
        ...page,
        navigationIntent,
        failedNavigationIntent: undefined,
        error: undefined,
        snapshot: { ...page.snapshot, load_status: "loading" },
      }));
      startLoadDeadline(pageId, navigationIntent);
      return navigationIntent;
    },
    [startLoadDeadline, updatePage],
  );

  /** 事件或命令快照成为终态后撤销对应的 renderer 超时。 */
  const finishPageNavigation = useCallback(
    (pageId: string): void => {
      clearLoadDeadline(pageId);
    },
    [clearLoadDeadline],
  );

  /** 排空全局原生布局队列；每个 session 只保留最新 viewport，任何时候都不并发 show。 */
  const drainPendingLayouts = useCallback((): void => {
    if (layoutTaskRef.current !== undefined) return;
    const task = (async (): Promise<void> => {
      while (pendingLayoutsRef.current.size > 0) {
        const first = pendingLayoutsRef.current.entries().next().value as
          | [string, PendingPreviewLayout]
          | undefined;
        if (first === undefined) break;
        const [pageId, pending] = first;
        pendingLayoutsRef.current.delete(pageId);
        try {
          await adapter.layout(pending.sessionId, pending.viewport);
          lastAppliedLayoutRef.current.set(pageId, pending.viewport);
        } catch {
          const hasNewerRequest = pendingLayoutsRef.current.has(pageId);
          if (!hasNewerRequest) lastRequestedLayoutRef.current.delete(pageId);
          if (
            !hasNewerRequest &&
            projectGenerationRef.current === pending.projectGeneration &&
            pagesRef.current.has(pageId)
          )
            updatePage(pageId, (page) => ({ ...page, error: PREVIEW_LOAD_ERROR }));
        }
      }
    })().finally(() => {
      if (layoutTaskRef.current === task) layoutTaskRef.current = undefined;
      if (pendingLayoutsRef.current.size > 0) drainPendingLayouts();
    });
    layoutTaskRef.current = task;
  }, [adapter, updatePage]);

  /** 布局请求按 page ID 去重；inactive WebView 收到 false 后保持隐藏直至再次选中。 */
  const requestLayout = useCallback(
    (
      pageId: string,
      viewport: PreviewViewport,
      generation = projectGenerationRef.current,
    ): void => {
      if (!pagesRef.current.has(pageId) || generation !== projectGenerationRef.current) return;
      const previousRequest = lastRequestedLayoutRef.current.get(pageId);
      if (previewViewportEquals(previousRequest, viewport)) return;
      if (
        !pendingLayoutsRef.current.has(pageId) &&
        previewViewportEquals(lastAppliedLayoutRef.current.get(pageId), viewport)
      ) {
        lastRequestedLayoutRef.current.set(pageId, viewport);
        return;
      }
      lastRequestedLayoutRef.current.set(pageId, viewport);
      pendingLayoutsRef.current.set(pageId, {
        projectGeneration: generation,
        sessionId: pageId,
        viewport,
      });
      drainPendingLayouts();
    },
    [drainPendingLayouts],
  );

  /** 原生快照只能更新现存或刚 ACK 的 page，陈旧 workspace/session generation 会被丢弃。 */
  const commitSnapshot = useCallback(
    (next: PreviewSessionSnapshot, generation: number): boolean => {
      if (projectGenerationRef.current !== generation) return false;
      const current = pagesRef.current.get(next.id);
      if (current === undefined || next.generation < current.snapshot.generation) return false;
      const snapshot = mergePreviewSnapshot(current.snapshot, next);
      let error = current.error;
      let failedNavigationIntent = current.failedNavigationIntent;
      if (snapshot.status === "closed") {
        error = PREVIEW_CLOSE_ERROR;
        finishPageNavigation(next.id);
      } else if (snapshot.load_status === "finished") {
        if (current.failedNavigationIntent !== current.navigationIntent) {
          error = undefined;
          failedNavigationIntent = undefined;
        }
        finishPageNavigation(next.id);
      } else if (snapshot.load_status === "failed") {
        error ??= PREVIEW_LOAD_ERROR;
        failedNavigationIntent = current.navigationIntent;
        finishPageNavigation(next.id);
      }
      pagesRef.current.set(next.id, {
        ...current,
        snapshot,
        error,
        failedNavigationIntent,
      });
      publishPages();
      return true;
    },
    [finishPageNavigation, publishPages],
  );

  /** 对单页事件按 sequence 单调应用；打开 ACK 前的少量事件先进入有界缓冲。 */
  const applyEvent = useCallback(
    (event: PreviewEvent, generation: number): void => {
      if (projectGenerationRef.current !== generation) return;
      const current = pagesRef.current.get(event.session_id);
      if (current === undefined) {
        if (
          pendingOpensRef.current.size > 0 &&
          event.kind.type !== "closed" &&
          earlyEventsRef.current.length < MAX_EARLY_PREVIEW_EVENTS &&
          !earlyEventsRef.current.some(
            (candidate) =>
              candidate.session_id === event.session_id && candidate.sequence === event.sequence,
          )
        )
          earlyEventsRef.current.push(event);
        return;
      }
      if (
        closingSessionIdsRef.current.has(event.session_id) ||
        event.generation < current.snapshot.generation
      )
        return;
      const previousSequence = eventCursorRef.current.get(event.session_id);
      if (previousSequence !== undefined && event.sequence <= previousSequence) return;
      eventCursorRef.current.set(event.session_id, event.sequence);
      const base =
        event.generation === current.snapshot.generation
          ? current.snapshot
          : { ...current.snapshot, generation: event.generation };
      switch (event.kind.type) {
        case "opened":
        case "navigation_committed":
          commitSnapshot(
            {
              ...base,
              status: "open",
              load_status: "loading",
              url: event.kind.url,
              window: { ...base.window, url: event.kind.url },
            },
            generation,
          );
          break;
        case "title_changed":
          commitSnapshot({ ...base, title: event.kind.title }, generation);
          break;
        case "load_failed": {
          const message = event.kind.message;
          updatePage(event.session_id, (page) => ({
            ...page,
            error: message,
            failedNavigationIntent: page.navigationIntent,
            snapshot: { ...page.snapshot, ...base, load_status: "failed" },
          }));
          finishPageNavigation(event.session_id);
          break;
        }
        case "load_finished":
          commitSnapshot(
            {
              ...base,
              load_status: "finished",
              url: event.kind.url,
              window: { ...base.window, url: event.kind.url },
            },
            generation,
          );
          break;
        case "history_changed":
          commitSnapshot(
            {
              ...base,
              can_go_back: event.kind.can_go_back,
              can_go_forward: event.kind.can_go_forward,
            },
            generation,
          );
          break;
        case "action_blocked": {
          const message =
            event.kind.action === "popup" ? PREVIEW_POPUP_BLOCKED : PREVIEW_DOWNLOAD_BLOCKED;
          updatePage(event.session_id, (page) => ({
            ...page,
            error: message,
            snapshot: base,
          }));
          break;
        }
        case "closed":
          // 原生 close 事件只报告事实；UI 与 hint 必须等显式 close command ACK 才删除页面。
          updatePage(event.session_id, (page) => ({
            ...page,
            error: PREVIEW_CLOSE_ERROR,
            snapshot: { ...base, status: "closed" },
          }));
          finishPageNavigation(event.session_id);
          break;
      }
    },
    [commitSnapshot, finishPageNavigation, updatePage],
  );

  /** 先排空有界事件，再用 Rust 的权威 snapshot 更新指定 page 的 URL 与原生历史状态。 */
  const reconcileSession = useCallback(
    async (pageId: string, generation: number, minimumGeneration: number): Promise<void> => {
      if (projectGenerationRef.current !== generation || !pagesRef.current.has(pageId)) return;
      let events: PreviewEvent[] = [];
      try {
        events = await adapter.events(pageId, MAX_EARLY_PREVIEW_EVENTS);
      } catch {
        // live subscription 与 state 命令仍能提供降级路径。
      }
      if (projectGenerationRef.current !== generation || !pagesRef.current.has(pageId)) return;
      for (const event of [...events].sort((left, right) => left.sequence - right.sequence))
        applyEvent(event, generation);
      let authoritative: PreviewSessionSnapshot;
      try {
        authoritative = await adapter.state(pageId);
      } catch {
        return;
      }
      if (
        authoritative.id !== pageId ||
        authoritative.generation < minimumGeneration ||
        !commitSnapshot(authoritative, generation)
      )
        return;
      if (authoritative.load_status === "failed") {
        const failure = [...events]
          .reverse()
          .find(
            (event) =>
              event.session_id === pageId &&
              event.generation === authoritative.generation &&
              event.kind.type === "load_failed",
          );
        if (failure?.kind.type === "load_failed") {
          const message = failure.kind.message;
          updatePage(pageId, (page) => ({ ...page, error: message }));
        }
      }
    },
    [adapter, applyEvent, commitSnapshot, updatePage],
  );

  /** 每个 workspace generation 合并 orphan recovery；pending 不清零时拒绝创建新 WebView。 */
  const recoverPending = useCallback(
    (generation: number): Promise<void> => {
      const existing = recoveryTaskRef.current;
      if (existing?.projectGeneration === generation) return existing.promise;
      if (existing !== undefined)
        return existing.promise.catch(() => undefined).then(() => recoverPending(generation));
      const task = (async (): Promise<void> => {
        if (projectGenerationRef.current === generation) setRecovering(true);
        try {
          const report = await adapter.recoverPending();
          if (report.pending > 0) throw new Error(PREVIEW_RECOVERY_ERROR);
          if (projectGenerationRef.current === generation) {
            recoveryBlockedRef.current = false;
            setGlobalError((current) => (current === PREVIEW_RECOVERY_ERROR ? undefined : current));
          }
        } catch {
          if (projectGenerationRef.current === generation) {
            recoveryBlockedRef.current = true;
            setGlobalError(PREVIEW_RECOVERY_ERROR);
          }
          throw new Error(PREVIEW_RECOVERY_ERROR);
        }
      })().finally(() => {
        if (recoveryTaskRef.current?.promise === task) recoveryTaskRef.current = undefined;
        if (projectGenerationRef.current === generation) setRecovering(false);
      });
      recoveryTaskRef.current = { projectGeneration: generation, promise: task };
      return task;
    },
    [adapter],
  );

  /** 切换 active page 时先排入旧页 hide，再排入新页 show，避免两个原生窗口重叠。 */
  const selectPage = useCallback(
    (pageId: string): void => {
      if (!pagesRef.current.has(pageId) || activePageIdRef.current === pageId) return;
      const generation = projectGenerationRef.current;
      const viewport = viewportRef.current;
      const previousPageId = activePageIdRef.current;
      if (previousPageId !== undefined)
        requestLayout(previousPageId, hiddenViewport(viewport), generation);
      activePageIdRef.current = pageId;
      setActivePageId(pageId);
      if (viewport?.visible === true) requestLayout(pageId, viewport, generation);
      else requestLayout(pageId, hiddenViewport(viewport), generation);
      setGlobalError(undefined);
    },
    [requestLayout],
  );

  /** native close ACK 后才移除页面、hint 与布局缓存；失败时标签仍留在原位供重试。 */
  const closePage = useCallback(
    (pageId: string): Promise<void> => {
      const inFlight = closeTasksRef.current.get(pageId);
      if (inFlight !== undefined) return inFlight;
      const page = pagesRef.current.get(pageId);
      if (page === undefined) return Promise.resolve();
      const generation = projectGenerationRef.current;
      closingSessionIdsRef.current.add(pageId);
      const task = (async (): Promise<void> => {
        try {
          const acknowledged = await adapter.close(pageId);
          if (acknowledged.id !== pageId || acknowledged.status !== "closed")
            throw new Error(PREVIEW_CLOSE_ERROR);
          if (projectGenerationRef.current !== generation) return;
          const orderedPageIds = [...pagesRef.current.keys()];
          const wasActive = activePageIdRef.current === pageId;
          const index = orderedPageIds.indexOf(pageId);
          const nextActivePageId = orderedPageIds[index - 1] ?? orderedPageIds[index + 1];
          pagesRef.current.delete(pageId);
          eventCursorRef.current.delete(pageId);
          clearLoadDeadline(pageId);
          pendingLayoutsRef.current.delete(pageId);
          lastRequestedLayoutRef.current.delete(pageId);
          lastAppliedLayoutRef.current.delete(pageId);
          if (workspaceId !== undefined) sessionHints.forget(workspaceId, pageId);
          publishPages();
          if (wasActive) {
            const closingAllPages = cleanupTaskRef.current !== undefined;
            const selectedPageId = closingAllPages ? undefined : nextActivePageId;
            activePageIdRef.current = selectedPageId;
            setActivePageId(selectedPageId);
            const viewport = viewportRef.current;
            if (selectedPageId !== undefined) {
              requestLayout(
                selectedPageId,
                viewport?.visible === true ? viewport : hiddenViewport(viewport),
                generation,
              );
            }
          }
          setGlobalError(undefined);
        } catch {
          if (projectGenerationRef.current === generation) {
            updatePage(pageId, (current) => ({ ...current, error: PREVIEW_CLOSE_ERROR }));
            setGlobalError(PREVIEW_CLOSE_ERROR);
          }
          throw new Error(PREVIEW_CLOSE_ERROR);
        } finally {
          closingSessionIdsRef.current.delete(pageId);
          closeTasksRef.current.delete(pageId);
        }
      })();
      closeTasksRef.current.set(pageId, task);
      return task;
    },
    [
      adapter,
      clearLoadDeadline,
      publishPages,
      requestLayout,
      sessionHints,
      updatePage,
      workspaceId,
    ],
  );

  /** 等待 native close ACK；迟到或陈旧 open 也走同一 close 边界，不发布空白 tab。 */
  const createPage = useCallback(
    (
      openNative: (viewport: PreviewViewport) => ReturnType<NativePreviewPort["open"]>,
    ): Promise<void> => {
      if (workspaceId === undefined) return Promise.reject(new Error("当前没有打开工作区。"));
      if (cleanupTaskRef.current !== undefined)
        return Promise.reject(new Error("浏览器正在关闭，请稍后重试。"));
      const generation = projectGenerationRef.current;
      const pending: PendingPreviewOpen = {
        projectGeneration: generation,
        cancelRequested: false,
        completion: Promise.resolve(),
      };
      pendingOpensRef.current.add(pending);
      const operation = (async (): Promise<void> => {
        let nativeOpenStarted = false;
        try {
          const initialization = initializationRef.current;
          if (initialization?.generation === generation) await initialization.promise;
          else await recoverPending(generation);
          if (projectGenerationRef.current !== generation || pending.cancelRequested) return;
          if (recoveryBlockedRef.current) throw new Error(PREVIEW_RECOVERY_ERROR);
          const creationViewport = hiddenViewport(viewportRef.current);
          nativeOpenStarted = true;
          const result = await openNative(creationViewport);
          if (
            result.snapshot.status !== "open" ||
            projectGenerationRef.current !== generation ||
            pending.cancelRequested
          ) {
            const closed = await adapter.close(result.snapshot.id);
            if (closed.id !== result.snapshot.id || closed.status !== "closed")
              throw new Error(PREVIEW_CLOSE_ERROR);
            return;
          }
          const previousPageId = activePageIdRef.current;
          if (previousPageId !== undefined)
            requestLayout(previousPageId, hiddenViewport(viewportRef.current), generation);
          const entry: ManagedPreviewPage = {
            snapshot: result.snapshot,
            navigationIntent: ++navigationIntentRef.current,
          };
          pagesRef.current.set(result.snapshot.id, entry);
          eventCursorRef.current.delete(result.snapshot.id);
          if (workspaceId !== undefined) sessionHints.remember(workspaceId, result.snapshot.id);
          activePageIdRef.current = result.snapshot.id;
          setActivePageId(result.snapshot.id);
          publishPages();
          const currentViewport = viewportRef.current;
          if (currentViewport?.visible === true) {
            requestLayout(result.snapshot.id, currentViewport, generation);
          } else {
            // Native open contract guarantees hidden children start native_visible=false.
            lastRequestedLayoutRef.current.set(result.snapshot.id, creationViewport);
            lastAppliedLayoutRef.current.set(result.snapshot.id, creationViewport);
          }
          setGlobalError(undefined);
          if (result.snapshot.load_status === "loading")
            startLoadDeadline(result.snapshot.id, entry.navigationIntent);
          const early = earlyEventsRef.current.filter(
            (event) => event.session_id === result.snapshot.id,
          );
          earlyEventsRef.current = earlyEventsRef.current.filter(
            (event) => event.session_id !== result.snapshot.id,
          );
          for (const event of [...early].sort((left, right) => left.sequence - right.sequence))
            applyEvent(event, generation);
          await reconcileSession(result.snapshot.id, generation, result.snapshot.generation);
        } catch (error) {
          if (nativeOpenStarted) await recoverPending(generation).catch(() => undefined);
          const message = recoveryBlockedRef.current
            ? PREVIEW_RECOVERY_ERROR
            : safePreviewError(error, PREVIEW_OPEN_ERROR);
          if (projectGenerationRef.current === generation) setGlobalError(message);
          throw new Error(message);
        } finally {
          pendingOpensRef.current.delete(pending);
        }
      })();
      pending.completion = operation;
      return operation;
    },
    [
      adapter,
      applyEvent,
      publishPages,
      recoverPending,
      reconcileSession,
      requestLayout,
      sessionHints,
      startLoadDeadline,
      workspaceId,
    ],
  );

  /** 每次显式 URL/file 目标创建独立 hidden WebView session，ACK 后才公开 page identity。 */
  const openTarget = useCallback(
    (target: PreviewTarget): Promise<void> => {
      if (target.kind === "url") {
        const url = target.url.trim();
        if (!/^https?:\/\//iu.test(url) || containsControlCharacters(url))
          return Promise.reject(new Error("浏览器地址无效或暂不支持此协议。"));
        return createPage((viewport) => adapter.open(url, viewport)).catch((error: unknown) => {
          throw new Error(safePreviewError(error, PREVIEW_OPEN_ERROR));
        });
      }
      const path = target.path.trim();
      if (
        path.length === 0 ||
        path.length > 4_096 ||
        [...path].some((character) => {
          const codePoint = character.codePointAt(0) ?? 0;
          return codePoint <= 31 || codePoint === 127;
        }) ||
        (target.line !== undefined && (!Number.isSafeInteger(target.line) || target.line < 1)) ||
        (target.column !== undefined && (!Number.isSafeInteger(target.column) || target.column < 1))
      )
        return Promise.reject(new Error("文件路径无效。"));
      return createPage((viewport) => adapter.openFile(path, workspaceId, viewport)).catch(
        (error: unknown) => {
          throw new Error(safePreviewError(error, PREVIEW_OPEN_ERROR));
        },
      );
    },
    [adapter, createPage, workspaceId],
  );

  /** 创建空白页也由 Rust 签发 page ID，初始子 WebView 隐藏且不获得 Ja capability。 */
  const newPage = useCallback(
    (): Promise<void> => createPage((viewport) => adapter.openBlank(viewport)),
    [adapter, createPage],
  );

  /** 在当前 page 内导航时保留原生历史；没有选中页才创建一个新的原生页面。 */
  const navigateExisting = useCallback(
    (target: { kind: "url"; url: string } | { kind: "file"; path: string }): void => {
      if (workspaceId === undefined || cleanupTaskRef.current !== undefined) {
        setGlobalError("当前没有可用的工作区浏览器。");
        return;
      }
      const pageId = activePageIdRef.current;
      if (pageId === undefined) {
        void openTarget(target.kind === "url" ? target : { kind: "file", path: target.path }).catch(
          () => undefined,
        );
        return;
      }
      const current = pagesRef.current.get(pageId);
      if (current === undefined) return;
      const navigationIntent = beginPageNavigation(pageId);
      if (navigationIntent === undefined) return;
      const generation = projectGenerationRef.current;
      const operation = (async (): Promise<void> => {
        try {
          const next =
            target.kind === "url"
              ? await adapter.navigate(
                  current.snapshot.id,
                  current.snapshot.generation,
                  target.url,
                  "user",
                )
              : await adapter.navigateFile(
                  current.snapshot.id,
                  current.snapshot.generation,
                  target.path,
                  workspaceId,
                );
          const latest = pagesRef.current.get(pageId);
          if (
            projectGenerationRef.current !== generation ||
            latest === undefined ||
            latest.navigationIntent !== navigationIntent ||
            next.id !== pageId ||
            next.generation < latest.snapshot.generation
          )
            return;
          commitSnapshot(next, generation);
          await reconcileSession(pageId, generation, next.generation);
        } catch (error) {
          if (
            projectGenerationRef.current === generation &&
            pagesRef.current.get(pageId)?.navigationIntent === navigationIntent
          ) {
            const message = safePreviewError(error, PREVIEW_LOAD_ERROR);
            updatePage(pageId, (page) => ({ ...page, error: message }));
            finishPageNavigation(pageId);
          }
        }
      })();
      void operation;
    },
    [
      adapter,
      beginPageNavigation,
      commitSnapshot,
      finishPageNavigation,
      openTarget,
      reconcileSession,
      updatePage,
      workspaceId,
    ],
  );

  /** URL 导航入口是 void UI action；请求错误只写入当前页静态状态，不回显原生诊断。 */
  const navigate = useCallback(
    (url: string): void => {
      navigateExisting({ kind: "url", url });
    },
    [navigateExisting],
  );

  /** 地址栏本机路径留给 Rust 按当前 workspace 解析，并在当前 tab 使用原生 history 导航。 */
  const navigateFile = useCallback(
    (path: string): void => {
      navigateExisting({ kind: "file", path });
    },
    [navigateExisting],
  );

  /** 使用当前原生 history flags 执行回退，不在 renderer 里模拟页面历史栈。 */
  const navigateHistory = useCallback(
    (action: "back" | "forward" | "reload"): void => {
      const pageId = activePageIdRef.current;
      if (pageId === undefined) return;
      const current = pagesRef.current.get(pageId);
      if (current === undefined || current.snapshot.status !== "open") return;
      if (action === "back" && !current.snapshot.can_go_back) return;
      if (action === "forward" && !current.snapshot.can_go_forward) return;
      const navigationIntent = beginPageNavigation(pageId);
      if (navigationIntent === undefined) return;
      const generation = projectGenerationRef.current;
      void (async (): Promise<void> => {
        try {
          const next =
            action === "back"
              ? await adapter.goBack(pageId, current.snapshot.generation)
              : action === "forward"
                ? await adapter.goForward(pageId, current.snapshot.generation)
                : await adapter.reload(pageId, current.snapshot.generation);
          if (
            projectGenerationRef.current !== generation ||
            pagesRef.current.get(pageId)?.navigationIntent !== navigationIntent ||
            next.id !== pageId
          )
            return;
          commitSnapshot(next, generation);
          await reconcileSession(pageId, generation, next.generation);
        } catch (error) {
          if (
            projectGenerationRef.current === generation &&
            pagesRef.current.get(pageId)?.navigationIntent === navigationIntent
          ) {
            updatePage(pageId, (page) => ({
              ...page,
              error: safePreviewError(error, PREVIEW_LOAD_ERROR),
            }));
            finishPageNavigation(pageId);
          }
        }
      })();
    },
    [
      adapter,
      beginPageNavigation,
      commitSnapshot,
      finishPageNavigation,
      reconcileSession,
      updatePage,
    ],
  );

  /** DOM 几何变化只显示选中页；附件卸载或 Thread 隐藏时一次性隐藏该会话全部 child WebView。 */
  const updateViewport = useCallback(
    (viewport: PreviewViewport): void => {
      if (previewViewportEquals(viewportRef.current, viewport)) return;
      viewportRef.current = viewport;
      const generation = projectGenerationRef.current;
      if (!viewport.visible) {
        for (const pageId of pagesRef.current.keys())
          requestLayout(pageId, hiddenViewport(viewport), generation);
        return;
      }
      const active = activePageIdRef.current;
      if (active !== undefined) requestLayout(active, viewport, generation);
    },
    [requestLayout],
  );

  /** 关闭全部已确认 page 与 pending open；任一 close ACK 失败都会保留该页面并阻止 workspace 切换。 */
  const cleanupNativePreview = useCallback(
    (showFailure: boolean): Promise<void> => {
      if (cleanupTaskRef.current !== undefined) return cleanupTaskRef.current;
      const generation = projectGenerationRef.current;
      const pending = [...pendingOpensRef.current].filter(
        (item) => item.projectGeneration === generation,
      );
      for (const opening of pending) opening.cancelRequested = true;
      const task = (async (): Promise<void> => {
        await Promise.all(pending.map((opening) => opening.completion.catch(() => undefined)));
        await layoutTaskRef.current?.catch(() => undefined);
        const pageIds = [...pagesRef.current.keys()];
        let closeFailed = false;
        for (const pageId of pageIds) {
          try {
            await closePage(pageId);
          } catch {
            closeFailed = true;
          }
        }
        if (closeFailed || pagesRef.current.size > 0) {
          if (showFailure && projectGenerationRef.current === generation)
            setGlobalError(PREVIEW_CLOSE_ERROR);
          throw new Error(PREVIEW_CLOSE_ERROR);
        }
        try {
          await recoverPending(generation);
        } catch {
          if (showFailure && projectGenerationRef.current === generation)
            setGlobalError(PREVIEW_RECOVERY_ERROR);
          throw new Error(PREVIEW_RECOVERY_ERROR);
        }
        if (projectGenerationRef.current === generation) {
          activePageIdRef.current = undefined;
          setActivePageId(undefined);
          setGlobalError(undefined);
        }
      })().finally(() => {
        if (cleanupTaskRef.current === task) cleanupTaskRef.current = undefined;
      });
      cleanupTaskRef.current = task;
      return task;
    },
    [closePage, recoverPending],
  );

  /** 显式重试只重跑 orphan recovery，不创建新页或重放之前失败的浏览器操作。 */
  const retryRecovery = useCallback((): void => {
    void recoverPending(projectGenerationRef.current).catch(() => undefined);
  }, [recoverPending]);

  /** workspace identity 改变时先废弃旧 continuation，再恢复该 workspace 的 page ID 列表。 */
  useEffect(() => {
    const generation = ++projectGenerationRef.current;
    navigationIntentRef.current += 1;
    pagesRef.current.clear();
    activePageIdRef.current = undefined;
    viewportRef.current = undefined;
    pendingLayoutsRef.current.clear();
    lastRequestedLayoutRef.current.clear();
    lastAppliedLayoutRef.current.clear();
    earlyEventsRef.current = [];
    eventCursorRef.current.clear();
    for (const timer of loadTimersRef.current.values()) clearTimeout(timer);
    loadTimersRef.current.clear();
    setPages([]);
    setActivePageId(undefined);
    setGlobalError(undefined);
    setRecovering(false);
    recoveryBlockedRef.current = workspaceId !== undefined;
    if (workspaceId === undefined) {
      initializationRef.current = { generation, promise: Promise.resolve() };
      return () => {
        projectGenerationRef.current += 1;
      };
    }
    let disposed = false;
    const initialization = (async (): Promise<void> => {
      const pageIds = sessionHints.read(workspaceId);
      const restored = await Promise.all(
        pageIds.map(async (pageId) => {
          try {
            const snapshot = await adapter.state(pageId);
            return snapshot.id === pageId && snapshot.status === "open" ? snapshot : undefined;
          } catch {
            return undefined;
          }
        }),
      );
      if (disposed || projectGenerationRef.current !== generation) return;
      const validPages = restored.filter(
        (snapshot): snapshot is PreviewSessionSnapshot => snapshot !== undefined,
      );
      for (const pageId of pageIds)
        if (!validPages.some((snapshot) => snapshot.id === pageId))
          sessionHints.forget(workspaceId, pageId);
      for (const snapshot of validPages) {
        pagesRef.current.set(snapshot.id, {
          snapshot,
          navigationIntent: ++navigationIntentRef.current,
          error: snapshot.load_status === "failed" ? PREVIEW_LOAD_ERROR : undefined,
        });
        sessionHints.remember(workspaceId, snapshot.id);
      }
      if (validPages.length > 0) {
        const hidden = hiddenViewport(HIDDEN_LAYOUT_FALLBACK);
        await Promise.all(
          validPages.map((snapshot) => adapter.layout(snapshot.id, hidden).catch(() => undefined)),
        );
        if (disposed || projectGenerationRef.current !== generation) return;
        publishPages();
        const latestPageId = validPages.at(-1)?.id;
        activePageIdRef.current = latestPageId;
        setActivePageId(latestPageId);
        if (latestPageId !== undefined) {
          const activeSnapshot = validPages.find((snapshot) => snapshot.id === latestPageId);
          if (activeSnapshot !== undefined)
            await reconcileSession(latestPageId, generation, activeSnapshot.generation);
        }
      }
      await recoverPending(generation).catch(() => undefined);
    })();
    initializationRef.current = { generation, promise: initialization };
    const pendingOpens = pendingOpensRef.current;
    return () => {
      disposed = true;
      for (const opening of pendingOpens)
        if (opening.projectGeneration === generation) opening.cancelRequested = true;
      if (projectGenerationRef.current === generation) projectGenerationRef.current += 1;
    };
  }, [adapter, publishPages, reconcileSession, recoverPending, sessionHints, workspaceId]);

  /** 每个 workspace 只保留一份原生事件订阅，迟到 handle 在注册后立即释放。 */
  useEffect(() => {
    if (workspaceId === undefined) return undefined;
    const generation = projectGenerationRef.current;
    let disposed = false;
    let unsubscribe: PreviewUnsubscribe | undefined;
    void adapter
      .subscribe((event) => applyEvent(event, generation))
      .then((next) => {
        if (disposed || projectGenerationRef.current !== generation) void next();
        else unsubscribe = next;
      })
      .catch(() => undefined);
    return () => {
      disposed = true;
      void unsubscribe?.();
    };
  }, [adapter, applyEvent, workspaceId]);

  /** 只轮询当前且仍 loading 的 page；inactive tabs 依靠原生事件，不产生后台 IPC。 */
  const activePage = pages.find((page) => page.snapshot.id === activePageId);
  const activeSnapshot = activePage?.snapshot;
  const activeSnapshotId = activeSnapshot?.id;
  const activeSnapshotGeneration = activeSnapshot?.generation;
  const activeSnapshotStatus = activeSnapshot?.status;
  const activeSnapshotLoadStatus = activeSnapshot?.load_status;
  useEffect(() => {
    if (
      workspaceId === undefined ||
      activeSnapshotId === undefined ||
      activeSnapshotStatus !== "open" ||
      activeSnapshotLoadStatus !== "loading"
    )
      return undefined;
    const generation = projectGenerationRef.current;
    const pageId = activeSnapshotId;
    const minimumGeneration = activeSnapshotGeneration ?? 0;
    let disposed = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    /** 单轮同步结束后仅为仍 active 且 loading 的原生 page 安排下一次有界回读。 */
    const reconcileWhileLoading = async (): Promise<void> => {
      await reconcileSession(pageId, generation, minimumGeneration);
      const current = pagesRef.current.get(pageId);
      if (
        disposed ||
        projectGenerationRef.current !== generation ||
        activePageIdRef.current !== pageId ||
        current?.snapshot.status !== "open" ||
        current.snapshot.load_status !== "loading" ||
        current.error !== undefined
      )
        return;
      timer = setTimeout(() => void reconcileWhileLoading(), PREVIEW_LOADING_RECONCILE_INTERVAL_MS);
    };
    timer = setTimeout(() => void reconcileWhileLoading(), PREVIEW_LOADING_RECONCILE_INTERVAL_MS);
    return () => {
      disposed = true;
      if (timer !== undefined) clearTimeout(timer);
    };
  }, [
    activeSnapshotGeneration,
    activeSnapshotId,
    activeSnapshotLoadStatus,
    activeSnapshotStatus,
    reconcileSession,
    workspaceId,
  ]);

  /** 卸载时释放每页 renderer timer；native sessions 的关闭由 ACK-first workspace owner 管理。 */
  useEffect(
    () => () => {
      for (const timer of loadTimersRef.current.values()) clearTimeout(timer);
      loadTimersRef.current.clear();
    },
    [],
  );

  const pageProjections = useMemo<readonly PreviewPageProjection[]>(
    () =>
      pages.map((page) => ({
        pageId: page.snapshot.id,
        url: page.snapshot.url,
        title: page.snapshot.title,
        loading:
          page.snapshot.status === "open" &&
          page.snapshot.load_status === "loading" &&
          page.error === undefined,
        error: page.error,
        canGoBack: page.snapshot.can_go_back,
        canGoForward: page.snapshot.can_go_forward,
      })),
    [pages],
  );
  const activeError = activePage?.error ?? globalError;
  const loading =
    activeSnapshot?.status === "open" &&
    activeSnapshot.load_status === "loading" &&
    activePage?.error === undefined;

  /** 外层关闭能力与 workspace 切换共用完整 page ACK 清理，不触碰同 workspace 的其它会话。 */
  const workspaceLifecycle = useMemo<PreviewWorkspaceLifecycle | undefined>(
    () =>
      workspaceId === undefined
        ? undefined
        : { workspaceId, closeForWorkspaceChange: () => cleanupNativePreview(true) },
    [cleanupNativePreview, workspaceId],
  );

  return {
    closePreview: () => cleanupNativePreview(false),
    workspaceLifecycle,
    preview: {
      pages: pageProjections,
      activePageId,
      url: activeSnapshot?.url,
      loading,
      recovering,
      error: activeError,
      canGoBack: activeSnapshot?.can_go_back ?? false,
      canGoForward: activeSnapshot?.can_go_forward ?? false,
      onOpenTarget: openTarget,
      onNewPage: newPage,
      onSelectPage: selectPage,
      onClosePage: closePage,
      onNavigate: navigate,
      onNavigateFile: navigateFile,
      onGoBack: () => navigateHistory("back"),
      onGoForward: () => navigateHistory("forward"),
      onReload: () => navigateHistory("reload"),
      onRetryRecovery: recoveryBlockedRef.current ? retryRecovery : undefined,
      onViewportChange: updateViewport,
    },
  };
}
