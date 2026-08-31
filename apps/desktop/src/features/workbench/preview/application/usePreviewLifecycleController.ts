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

const MAX_EARLY_PREVIEW_EVENTS = 512;
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

/**
 * Preview application controller 是 child WebView 生命周期的唯一前端 owner。
 * 它以 workspace generation、session generation 与 intent sequence 三重栅栏拒绝迟到结果，
 * 并把原生异常收口为固定文本，避免 URL、路径或内部诊断进入 renderer 状态。
 */
export function usePreviewLifecycleController(
  workspaceId: string | undefined,
  adapter: NativePreviewPort,
): PreviewLifecycleController {
  const [snapshot, setSnapshot] = useState<PreviewSessionSnapshot>();
  const [loading, setLoading] = useState(false);
  const [recovering, setRecovering] = useState(false);
  const [error, setError] = useState<string>();
  const projectGenerationRef = useRef(0);
  const sessionRef = useRef<PreviewSessionSnapshot | undefined>(undefined);
  const viewportRef = useRef<PreviewViewport | undefined>(undefined);
  const cleanupTaskRef = useRef<Promise<void> | undefined>(undefined);
  const closingSessionIdRef = useRef<string | undefined>(undefined);
  const openingRef = useRef<PendingPreviewOpen | undefined>(undefined);
  const recoveryTaskRef = useRef<PendingPreviewRecovery | undefined>(undefined);
  const recoveryBlockedRef = useRef(false);
  const eventCursorRef = useRef<{ sessionId: string; sequence: number } | undefined>(undefined);
  const navigationIntentRef = useRef(0);

  /** 提交权威快照前核对 workspace 与 session identity，并统一派生加载状态。 */
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
      if (committed.status === "closed") {
        setLoading(false);
      } else if (committed.load_status === "loading") {
        setLoading(true);
        setError(undefined);
      } else if (committed.load_status === "finished") {
        setLoading(false);
        setError(undefined);
      } else {
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
    [adapter, recoverPending],
  );

  /** 首次导航创建 session，后续导航携带 generation；每次 await 后都重验意图栅栏。 */
  const navigate = useCallback(
    (url: string): void => {
      if (workspaceId === undefined || cleanupTaskRef.current !== undefined) return;
      const generation = projectGenerationRef.current;
      const current = sessionRef.current;
      if (current === undefined && openingRef.current?.projectGeneration === generation) return;
      const navigationIntent = ++navigationIntentRef.current;
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

  /** DOM 几何只影响当前已打开 session，迟到 layout 错误不能污染新 workspace。 */
  const updateViewport = useCallback(
    (viewport: PreviewViewport): void => {
      viewportRef.current = viewport;
      const current = sessionRef.current;
      if (current === undefined || current.status !== "open") return;
      const generation = projectGenerationRef.current;
      void adapter.layout(current.id, viewport).catch(() => {
        if (projectGenerationRef.current === generation && sessionRef.current?.id === current.id)
          setError(PREVIEW_LOAD_ERROR);
      });
    },
    [adapter],
  );

  /** workspace identity 变化时先使全部 continuation 失效；真正切换由外层 ACK 生命周期保护。 */
  useEffect(() => {
    projectGenerationRef.current += 1;
    navigationIntentRef.current += 1;
    recoveryBlockedRef.current = workspaceId !== undefined;
    sessionRef.current = undefined;
    viewportRef.current = undefined;
    openingRef.current = undefined;
    eventCursorRef.current = undefined;
    setSnapshot(undefined);
    setLoading(false);
    setRecovering(false);
    setError(undefined);
    return () => {
      projectGenerationRef.current += 1;
      navigationIntentRef.current += 1;
      openingRef.current = undefined;
    };
  }, [workspaceId]);

  /** workspace 挂载后先证明 orphan queue 已清空，再允许创建新 child。 */
  useEffect(() => {
    if (workspaceId === undefined) return undefined;
    const generation = projectGenerationRef.current;
    void recoverPending(generation).catch(() => undefined);
    return undefined;
  }, [recoverPending, workspaceId]);

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
