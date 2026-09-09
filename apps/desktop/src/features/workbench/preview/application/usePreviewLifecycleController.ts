// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type {
  NativePreviewPort,
  PreviewEvent,
  PreviewSessionSnapshot,
  PreviewUnsubscribe,
  PreviewViewport,
} from "./ports";
import type { PreviewSessionHintStorage } from "./previewSessionHintStorage";

const MAX_EARLY_PREVIEW_EVENTS = 512;
const PREVIEW_LOADING_RECONCILE_INTERVAL_MS = 500;
const PREVIEW_RENDERER_LOAD_DEADLINE_MS = 31_000;
const PREVIEW_LOAD_ERROR = "预览加载失败，请重试。";
const PREVIEW_CLOSE_ERROR = "浏览器关闭失败，请重试。";
const PREVIEW_RECOVERY_ERROR = "浏览器恢复未完成，请重试。";
interface PendingPreviewOpen {
  projectGeneration: number;
  events: PreviewEvent[];
  cancelRequested: boolean;
  completion: Promise<void>;
}

interface PendingPreviewRecovery {
  projectGeneration: number;
  promise: Promise<void>;
}

interface PreviewLoadDeadline {
  navigationIntent: number;
  startedAt: number;
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
  url?: string;
  loading: boolean;
  recovering: boolean;
  error?: string;
  onNavigate: (url: string) => void;
  onReload?: () => void;
  onRetryRecovery?: () => void;
  onViewportChange: (viewport: PreviewViewport) => void;
}

export interface PreviewLifecycleController {
  preview: PreviewLifecycleProjection;
  closePreview: () => Promise<void>;
  workspaceLifecycle: PreviewWorkspaceLifecycle | undefined;
}

/** 同一 generation 的终态加载事实优先于迟到的 loading 快照，避免 UI 状态倒退。 */
function mergePreviewSnapshot(
  current: PreviewSessionSnapshot | undefined,
  next: PreviewSessionSnapshot,
): PreviewSessionSnapshot {
  if (
    current === undefined ||
    current.id !== next.id ||
    current.generation !== next.generation ||
    next.status === "closed" ||
    current.status === "closed" ||
    current.load_status === "loading" ||
    next.load_status !== "loading"
  ) {
    return next;
  }
  return { ...next, load_status: current.load_status };
}

/** 比较完整 viewport，避免 React/ResizeObserver 重复测量把相同矩形送入原生队列。 */
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

/**
 * Preview application controller 是 child WebView 生命周期的唯一前端 owner。
 * 它以 workspace generation、session generation 与 intent sequence 三重栅栏拒绝迟到结果，
 * 并把原生异常收口为固定文本，避免 URL、路径或内部诊断进入 renderer 状态。
 */
export function usePreviewLifecycleController(
  workspaceId: string | undefined,
  adapter: NativePreviewPort,
  sessionHints: PreviewSessionHintStorage,
): PreviewLifecycleController {
  const [snapshot, setSnapshot] = useState<PreviewSessionSnapshot>();
  const [loading, setLoading] = useState(false);
  const [recovering, setRecovering] = useState(false);
  const [error, setError] = useState<string>();
  const projectGenerationRef = useRef(0);
  const sessionRef = useRef<PreviewSessionSnapshot | undefined>(undefined);
  const viewportRef = useRef<PreviewViewport | undefined>(undefined);
  const pendingLayoutRef = useRef<PendingPreviewLayout | undefined>(undefined);
  const layoutTaskRef = useRef<Promise<void> | undefined>(undefined);
  const cleanupTaskRef = useRef<Promise<void> | undefined>(undefined);
  const closingSessionIdRef = useRef<string | undefined>(undefined);
  const openingRef = useRef<PendingPreviewOpen | undefined>(undefined);
  const recoveryTaskRef = useRef<PendingPreviewRecovery | undefined>(undefined);
  const recoveryBlockedRef = useRef(false);
  const eventCursorRef = useRef<{ sessionId: string; sequence: number } | undefined>(undefined);
  const navigationIntentRef = useRef(0);
  const failedNavigationRef = useRef<{ sessionId: string; navigationIntent: number } | undefined>(
    undefined,
  );
  const [loadDeadline, setLoadDeadline] = useState<PreviewLoadDeadline>();

  /**
   * 提交权威快照前核对 workspace 与 session identity，并统一派生加载状态。本次用户导航一旦
   * 被判定失败，迟到的 WebView2 内部导航事实只能更新诊断快照，不能覆盖恢复面板。
   */
  const commitSnapshot = useCallback(
    (next: PreviewSessionSnapshot, generation: number, expectedSessionId?: string): boolean => {
      if (
        projectGenerationRef.current !== generation ||
        (expectedSessionId !== undefined && next.id !== expectedSessionId)
      )
        return false;
      const current = sessionRef.current;
      if (current !== undefined && (current.id !== next.id || next.generation < current.generation))
        return false;
      const committed = mergePreviewSnapshot(current, next);
      sessionRef.current = committed;
      setSnapshot(committed);
      const navigationFailed =
        failedNavigationRef.current?.sessionId === committed.id &&
        failedNavigationRef.current.navigationIntent === navigationIntentRef.current;
      if (committed.status === "closed") {
        failedNavigationRef.current = undefined;
        setLoadDeadline(undefined);
        setLoading(false);
      } else if (navigationFailed) {
        setLoadDeadline(undefined);
        setLoading(false);
        setError(PREVIEW_LOAD_ERROR);
      } else if (committed.load_status === "loading") {
        setLoading(true);
        setError(undefined);
      } else if (committed.load_status === "finished") {
        failedNavigationRef.current = undefined;
        setLoadDeadline(undefined);
        setLoading(false);
        setError(undefined);
      } else {
        failedNavigationRef.current = {
          sessionId: committed.id,
          navigationIntent: navigationIntentRef.current,
        };
        setLoadDeadline(undefined);
        setLoading(false);
        setError((message) => message ?? PREVIEW_LOAD_ERROR);
      }
      return true;
    },
    [],
  );

  /**
   * 事件只允许单调推进当前 session；open ACK 前使用有界缓冲承接同步事件，
   * 从而不因跨进程回调先于命令结果到达而丢失状态。
   */
  const applyEvent = useCallback(
    (event: PreviewEvent, generation: number): void => {
      if (projectGenerationRef.current !== generation) return;
      const current = sessionRef.current;
      if (current === undefined) {
        const opening = openingRef.current;
        if (
          opening?.projectGeneration === generation &&
          opening.events.length < MAX_EARLY_PREVIEW_EVENTS &&
          !opening.events.some(
            (candidate) =>
              candidate.session_id === event.session_id && candidate.sequence === event.sequence,
          )
        ) {
          opening.events.push(event);
        }
        return;
      }
      if (current.id !== event.session_id || event.generation < current.generation) return;
      const cursor = eventCursorRef.current;
      if (cursor?.sessionId === event.session_id && event.sequence <= cursor.sequence) return;
      eventCursorRef.current = { sessionId: event.session_id, sequence: event.sequence };
      const base =
        event.generation === current.generation
          ? current
          : { ...current, generation: event.generation };
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
            event.session_id,
          );
          break;
        case "title_changed":
          commitSnapshot({ ...base, title: event.kind.title }, generation, event.session_id);
          break;
        case "load_failed":
          failedNavigationRef.current = {
            sessionId: event.session_id,
            navigationIntent: navigationIntentRef.current,
          };
          commitSnapshot({ ...base, load_status: "failed" }, generation, event.session_id);
          setError(event.kind.message);
          break;
        case "load_finished":
          commitSnapshot(
            {
              ...base,
              load_status: "finished",
              url: event.kind.url,
              window: { ...base.window, url: event.kind.url },
            },
            generation,
            event.session_id,
          );
          break;
        case "closed":
          // close ACK 返回前保留 identity，失败时才能让同一 session 显式重试。
          if (
            cleanupTaskRef.current !== undefined &&
            (closingSessionIdRef.current === undefined ||
              closingSessionIdRef.current === event.session_id)
          )
            return;
          sessionRef.current = undefined;
          setSnapshot({ ...base, status: "closed" });
          setLoadDeadline(undefined);
          setLoading(false);
          setError(undefined);
          break;
      }
    },
    [commitSnapshot],
  );

  /** 先按序排空事件，再用权威 state 覆盖队列截断或重放造成的中间态。 */
  const reconcileSession = useCallback(
    async (sessionId: string, generation: number, minimumGeneration: number): Promise<void> => {
      let events: PreviewEvent[] = [];
      try {
        events = await adapter.events(sessionId, MAX_EARLY_PREVIEW_EVENTS);
      } catch {
        // subscription 与权威 state 仍能提供安全降级路径。
      }
      if (projectGenerationRef.current !== generation || sessionRef.current?.id !== sessionId)
        return;
      for (const event of [...events].sort((left, right) => left.sequence - right.sequence))
        applyEvent(event, generation);
      let authoritative: PreviewSessionSnapshot;
      try {
        authoritative = await adapter.state(sessionId);
      } catch {
        return;
      }
      if (
        authoritative.generation < minimumGeneration ||
        !commitSnapshot(authoritative, generation, sessionId)
      )
        return;
      if (authoritative.load_status === "failed") {
        const failure = [...events]
          .reverse()
          .find(
            (event) =>
              event.session_id === sessionId &&
              event.generation === authoritative.generation &&
              event.kind.type === "load_failed",
          );
        if (failure?.kind.type === "load_failed") setError(failure.kind.message);
      }
    },
    [adapter, applyEvent, commitSnapshot],
  );

  /** 每个 workspace generation 串行执行一次 orphan recovery，pending 不清零就保持阻塞。 */
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
            setError((current) => (current === PREVIEW_RECOVERY_ERROR ? undefined : current));
          }
        } catch {
          if (projectGenerationRef.current === generation) {
            recoveryBlockedRef.current = true;
            setError(PREVIEW_RECOVERY_ERROR);
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

  /** 显式恢复重试复用当前 generation，不创建第二个 session identity。 */
  const retryRecovery = useCallback((): void => {
    void recoverPending(projectGenerationRef.current).catch(() => undefined);
  }, [recoverPending]);

  /**
   * teardown 先加入 pending open，再执行 ACK-first close，最后排空 orphan queue；
   * 已知 session 关闭失败时保留 identity，禁止用全局恢复伪装关闭成功。
   */
  const cleanupNativePreview = useCallback(
    (showFailure: boolean): Promise<void> => {
      navigationIntentRef.current += 1;
      if (cleanupTaskRef.current !== undefined) return cleanupTaskRef.current;
      const generation = projectGenerationRef.current;
      const opening =
        openingRef.current?.projectGeneration === generation ? openingRef.current : undefined;
      if (opening !== undefined) opening.cancelRequested = true;
      const task = (async (): Promise<void> => {
        if (opening !== undefined) await opening.completion;
        const current = sessionRef.current;
        if (current !== undefined) {
          closingSessionIdRef.current = current.id;
          try {
            const acknowledged = await adapter.close(current.id);
            if (acknowledged.id !== current.id || acknowledged.status !== "closed")
              throw new Error(PREVIEW_CLOSE_ERROR);
          } catch {
            if (
              showFailure &&
              projectGenerationRef.current === generation &&
              sessionRef.current?.id === current.id
            )
              setError(PREVIEW_CLOSE_ERROR);
            throw new Error(PREVIEW_CLOSE_ERROR);
          } finally {
            if (closingSessionIdRef.current === current.id) closingSessionIdRef.current = undefined;
          }
          if (sessionRef.current?.id === current.id) {
            sessionRef.current = undefined;
            eventCursorRef.current = undefined;
          }
          if (workspaceId !== undefined) sessionHints.forget(workspaceId, current.id);
        }
        await recoverPending(generation);
        if (projectGenerationRef.current === generation) {
          setSnapshot(undefined);
          setLoading(false);
          setError(undefined);
        }
      })().finally(() => {
        if (cleanupTaskRef.current === task) cleanupTaskRef.current = undefined;
      });
      cleanupTaskRef.current = task;
      return task;
    },
    [adapter, recoverPending, sessionHints, workspaceId],
  );

  /** 首次导航创建 session，后续导航携带 generation；每次 await 后都重验意图栅栏。 */
  const navigate = useCallback(
    (url: string): void => {
      if (workspaceId === undefined || cleanupTaskRef.current !== undefined) return;
      const generation = projectGenerationRef.current;
      const current = sessionRef.current;
      if (current === undefined && openingRef.current?.projectGeneration === generation) return;
      const navigationIntent = ++navigationIntentRef.current;
      failedNavigationRef.current = undefined;
      setLoadDeadline({ navigationIntent, startedAt: Date.now() });
      setLoading(true);
      setError(undefined);
      const opening: PendingPreviewOpen | undefined =
        current === undefined || current.status === "closed"
          ? {
              projectGeneration: generation,
              events: [],
              cancelRequested: false,
              completion: Promise.resolve(),
            }
          : undefined;
      let nativeOpenStarted = false;
      if (opening !== undefined) openingRef.current = opening;
      const operation = (async (): Promise<void> => {
        try {
          if (opening !== undefined) {
            await recoverPending(generation);
            if (
              projectGenerationRef.current !== generation ||
              navigationIntentRef.current !== navigationIntent
            )
              return;
            const viewport = viewportRef.current;
            if (
              viewport === undefined ||
              !viewport.visible ||
              viewport.width < 1 ||
              viewport.height < 1
            )
              throw new Error(PREVIEW_LOAD_ERROR);
            nativeOpenStarted = true;
            const result = await adapter.open(url, viewport);
            if (opening.cancelRequested) {
              if (projectGenerationRef.current === generation) sessionRef.current = result.snapshot;
              else await adapter.close(result.snapshot.id).catch(() => undefined);
              return;
            }
            if (
              projectGenerationRef.current !== generation ||
              navigationIntentRef.current !== navigationIntent
            ) {
              await adapter.close(result.snapshot.id).catch(() => undefined);
              return;
            }
            if (sessionRef.current !== undefined && sessionRef.current.id !== result.snapshot.id) {
              await adapter.close(result.snapshot.id);
              return;
            }
            sessionRef.current = undefined;
            eventCursorRef.current = undefined;
            commitSnapshot(result.snapshot, generation, result.snapshot.id);
            if (openingRef.current === opening) openingRef.current = undefined;
            for (const event of [...opening.events]
              .filter((candidate) => candidate.session_id === result.snapshot.id)
              .sort((left, right) => left.sequence - right.sequence))
              applyEvent(event, generation);
            await reconcileSession(result.snapshot.id, generation, result.snapshot.generation);
          } else if (current !== undefined) {
            const minimumGeneration = current.generation + 1;
            const next = await adapter.navigate(current.id, current.generation, url, "user");
            if (
              projectGenerationRef.current !== generation ||
              navigationIntentRef.current !== navigationIntent
            )
              return;
            const active = sessionRef.current;
            if (
              active === undefined ||
              active.id !== next.id ||
              next.generation < active.generation
            )
              return;
            if (next.generation >= minimumGeneration) commitSnapshot(next, generation, current.id);
            await reconcileSession(current.id, generation, minimumGeneration);
          }
        } catch {
          if (opening !== undefined && nativeOpenStarted)
            await recoverPending(generation).catch(() => undefined);
          if (
            projectGenerationRef.current === generation &&
            navigationIntentRef.current === navigationIntent
          ) {
            setLoadDeadline(undefined);
            setLoading(false);
            setError(recoveryBlockedRef.current ? PREVIEW_RECOVERY_ERROR : PREVIEW_LOAD_ERROR);
          }
        } finally {
          if (opening !== undefined && openingRef.current === opening)
            openingRef.current = undefined;
        }
      })();
      if (opening !== undefined) opening.completion = operation;
      void operation;
    },
    [adapter, applyEvent, commitSnapshot, reconcileSession, recoverPending, workspaceId],
  );

  /**
   * 串行排空原生 layout，并在一个调用未完成时只保留最新矩形；WebView2 resize burst
   * 因此不会积压并回放过期 bounds，也不会让旧 session 的失败污染当前 Workspace。
   */
  const drainPendingLayout = useCallback((): void => {
    if (layoutTaskRef.current !== undefined) return;
    const task = (async (): Promise<void> => {
      while (pendingLayoutRef.current !== undefined) {
        const pending = pendingLayoutRef.current;
        pendingLayoutRef.current = undefined;
        try {
          await adapter.layout(pending.sessionId, pending.viewport);
        } catch {
          const newerLayoutPending = pendingLayoutRef.current !== undefined;
          if (
            !newerLayoutPending &&
            projectGenerationRef.current === pending.projectGeneration &&
            sessionRef.current?.id === pending.sessionId
          )
            setError(PREVIEW_LOAD_ERROR);
        }
      }
    })().finally(() => {
      if (layoutTaskRef.current === task) layoutTaskRef.current = undefined;
    });
    layoutTaskRef.current = task;
  }, [adapter]);

  /** DOM 几何只影响当前已打开 session；相同矩形去重，变化矩形进入 latest-wins 通道。 */
  const updateViewport = useCallback(
    (viewport: PreviewViewport): void => {
      if (previewViewportEquals(viewportRef.current, viewport)) return;
      viewportRef.current = viewport;
      const current = sessionRef.current;
      if (current === undefined || current.status !== "open") return;
      pendingLayoutRef.current = {
        projectGeneration: projectGenerationRef.current,
        sessionId: current.id,
        viewport,
      };
      drainPendingLayout();
    },
    [drainPendingLayout],
  );

  /** workspace identity 变化时先使全部 continuation 失效；真正切换由外层 ACK 生命周期保护。 */
  const deadlineSessionId = snapshot?.id;
  const deadlineSessionGeneration = snapshot?.generation;
  const deadlineSessionStatus = snapshot?.status;
  const deadlineLoadStatus = snapshot?.load_status;
  useEffect(() => {
    projectGenerationRef.current += 1;
    navigationIntentRef.current += 1;
    recoveryBlockedRef.current = workspaceId !== undefined;
    sessionRef.current = undefined;
    viewportRef.current = undefined;
    pendingLayoutRef.current = undefined;
    openingRef.current = undefined;
    eventCursorRef.current = undefined;
    failedNavigationRef.current = undefined;
    setSnapshot(undefined);
    setLoadDeadline(undefined);
    setLoading(false);
    setRecovering(false);
    setError(undefined);
    return () => {
      projectGenerationRef.current += 1;
      navigationIntentRef.current += 1;
      openingRef.current = undefined;
    };
  }, [workspaceId]);

  /** open 快照提交后保存 opaque hint；closed 事件会同步撤销，undefined 初态不得擦掉 reload 线索。 */
  useEffect(() => {
    if (workspaceId === undefined || snapshot === undefined) return;
    if (snapshot.status === "open") sessionHints.remember(workspaceId, snapshot.id);
    else sessionHints.forget(workspaceId, snapshot.id);
  }, [sessionHints, snapshot, workspaceId]);

  /**
   * workspace 挂载后优先用 Rust state 复核 reload 前的 session；不存在或已关闭时清除 hint，
   * 再执行既有 orphan recovery。hint 介质从不单独恢复 URL、状态或授权事实。
   */
  useEffect(() => {
    if (workspaceId === undefined) return undefined;
    const generation = projectGenerationRef.current;
    let disposed = false;
    void (async (): Promise<void> => {
      const hintedSessionId = sessionHints.read(workspaceId);
      if (hintedSessionId !== undefined) {
        try {
          const restored = await adapter.state(hintedSessionId);
          if (disposed || projectGenerationRef.current !== generation) return;
          if (restored.id === hintedSessionId && restored.status === "open") {
            sessionRef.current = restored;
            eventCursorRef.current = undefined;
            recoveryBlockedRef.current = false;
            commitSnapshot(restored, generation, hintedSessionId);
            await reconcileSession(hintedSessionId, generation, restored.generation);
            return;
          }
        } catch {
          // 无效、过期或不属于当前 native owner 的 hint 与不存在使用同一恢复路径。
        }
        sessionHints.forget(workspaceId, hintedSessionId);
      }
      await recoverPending(generation).catch(() => undefined);
    })();
    return () => {
      disposed = true;
    };
  }, [adapter, commitSnapshot, reconcileSession, recoverPending, sessionHints, workspaceId]);

  /** 每个 workspace 只保留一个原生订阅，异步返回的迟到 handle 立即释放。 */
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

  /**
   * loading 期间周期性回读有界事件与权威快照，补偿 WebView2 completion 和 Tauri live event
   * 之间的时序窗口；终态、identity 变化或 renderer 截止后立即停止，避免后台 Preview
   * 产生持续 IPC。
   */
  useEffect(() => {
    if (
      workspaceId === undefined ||
      deadlineSessionId === undefined ||
      deadlineSessionGeneration === undefined ||
      deadlineSessionStatus !== "open" ||
      deadlineLoadStatus !== "loading"
    )
      return undefined;
    const projectGeneration = projectGenerationRef.current;
    const sessionId = deadlineSessionId;
    const minimumGeneration = deadlineSessionGeneration;
    let disposed = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    /** 单次回读结束后仅为仍处于同一 loading identity 的 session 安排下一轮。 */
    const reconcileWhileLoading = async (): Promise<void> => {
      await reconcileSession(sessionId, projectGeneration, minimumGeneration);
      const current = sessionRef.current;
      const deadlineReached =
        failedNavigationRef.current?.sessionId === sessionId &&
        failedNavigationRef.current.navigationIntent === navigationIntentRef.current;
      if (
        disposed ||
        deadlineReached ||
        projectGenerationRef.current !== projectGeneration ||
        current?.id !== sessionId ||
        current.status !== "open" ||
        current.load_status !== "loading"
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
    deadlineLoadStatus,
    deadlineSessionGeneration,
    deadlineSessionId,
    deadlineSessionStatus,
    reconcileSession,
    workspaceId,
  ]);

  /**
   * Rust 的 30 秒 watchdog 是权威终态；renderer 多保留 1 秒传播余量后提供脱敏故障保险。
   * 预算绑定用户导航 intent 的首次开始时间，而不是 WebView2 内部 generation；重定向或错误页
   * 重试可以推进 generation，但不能无限延长同一次用户操作。新导航、终态或 workspace 切换会
   * 更换该 intent，旧 timer 因此不能污染恢复后的页面。
   */
  useEffect(() => {
    if (
      workspaceId === undefined ||
      deadlineSessionId === undefined ||
      deadlineSessionGeneration === undefined ||
      deadlineSessionStatus !== "open" ||
      deadlineLoadStatus !== "loading" ||
      loadDeadline === undefined
    )
      return undefined;
    const projectGeneration = projectGenerationRef.current;
    const sessionId = deadlineSessionId;
    const navigationIntent = loadDeadline.navigationIntent;
    const elapsed = Math.max(0, Date.now() - loadDeadline.startedAt);
    const remaining = Math.max(0, PREVIEW_RENDERER_LOAD_DEADLINE_MS - elapsed);
    const timer = setTimeout(() => {
      const current = sessionRef.current;
      if (
        projectGenerationRef.current !== projectGeneration ||
        navigationIntentRef.current !== navigationIntent ||
        current?.id !== sessionId ||
        current.status !== "open" ||
        current.load_status !== "loading"
      )
        return;
      failedNavigationRef.current = { sessionId, navigationIntent };
      setLoading(false);
      setError(PREVIEW_LOAD_ERROR);
    }, remaining);
    return () => clearTimeout(timer);
  }, [
    deadlineLoadStatus,
    deadlineSessionGeneration,
    deadlineSessionId,
    deadlineSessionStatus,
    loadDeadline,
    workspaceId,
  ]);

  /** 外层只获得 ACK-first close port，不获得 session identity 或 native handle。 */
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
      url: snapshot?.url,
      loading,
      recovering,
      error,
      onNavigate: navigate,
      onReload: snapshot?.status === "open" ? () => navigate(snapshot.url) : undefined,
      onRetryRecovery: recoveryBlockedRef.current ? retryRecovery : undefined,
      onViewportChange: updateViewport,
    },
  };
}
