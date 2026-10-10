// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  usePreviewLifecycleController,
  type NativePreviewPort,
  type PreviewEvent,
  type PreviewSessionHintStorage,
  type PreviewSessionSnapshot,
  type PreviewViewport,
} from "@/features/workbench/preview";
import type { WorkspaceProjection } from "@/features/workspace";

interface JaWorkbenchAdapters {
  preview: NativePreviewPort;
  sessionHints: PreviewSessionHintStorage;
}

const PREVIEW_VIEWPORT: PreviewViewport = {
  x: 840,
  y: 250,
  width: 430,
  height: 540,
  visible: true,
};
const RECOVERY_CLEAR = { observed: 0, recovered: 0, failed: 0, pending: 0 } as const;

const project: WorkspaceProjection = {
  kind: "project",
  workspaceId: "ws_fixture",
  rootPath: "C:\\dev\\ja",
  displayName: "ja",
  trust: "trusted",
};

const otherProject: WorkspaceProjection = {
  kind: "project",
  workspaceId: "ws_other",
  rootPath: "C:\\dev\\other",
  displayName: "other",
  trust: "trusted",
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

/** 让竞态测试显式控制 native completion，避免依赖不稳定 sleep。 */
function createDeferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason?: unknown) => void;
} {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

/** 等待原生 Promise 完成后刷新异步 Preview 控制器，确保断言观察到终态。 */
async function flushMicrotasks(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

/**
 * 将 project 投影为 Preview controller 输入；别名只保持测试叙事中的 workspace lifecycle
 * 名称，不重新创建 App composition 或其它领域 adapter。
 */
function useJaWorkbench(
  selectedProject: WorkspaceProjection | undefined,
  adapters: JaWorkbenchAdapters,
) {
  const controller = usePreviewLifecycleController(
    selectedProject?.workspaceId,
    adapters.preview,
    adapters.sessionHints,
  );
  return { ...controller, previewWorkspaceLifecycle: controller.workspaceLifecycle };
}

/** 只创建 Preview fake，因为此 hook 已不再拥有 Files、Review 或 PTY lifecycle。 */
function createAdapters(): JaWorkbenchAdapters & {
  previewEvents: (event: PreviewEvent) => void;
} {
  const hints = new Map<string, string[]>();
  const snapshot: PreviewSessionSnapshot = {
    id: "00000000-0000-4000-8000-000000000002",
    generation: 0,
    status: "open",
    load_status: "loading",
    url: "https://example.com/",
    title: "Example",
    can_go_back: false,
    can_go_forward: false,
    window: { label: "ja-preview", url: "https://example.com/" },
    dropped_events: 0,
  };
  let previewListener: (event: PreviewEvent) => void = () => undefined;
  const preview: JaWorkbenchAdapters["preview"] = {
    recoverPending: vi.fn(async () => RECOVERY_CLEAR),
    open: vi.fn(async (url) => ({
      snapshot: {
        ...snapshot,
        url,
        window: { ...snapshot.window, url },
      },
      window: { ...snapshot.window, url },
    })),
    openBlank: vi.fn(async () => ({
      snapshot: {
        ...snapshot,
        url: "about:blank",
        title: "",
        window: { ...snapshot.window, url: "about:blank" },
      },
      window: { ...snapshot.window, url: "about:blank" },
    })),
    resolveFile: vi.fn(async (target) => ({
      canonicalPath: target,
      displayName: target.split(/[\\/]/u).at(-1) ?? target,
      workspaceId: project.workspaceId,
      workspaceRelativePath: target,
      withinWorkspace: true,
      kind: "browser" as const,
      mimeType: "text/html",
      fileUrl: "file:///C:/dev/ja/index.html",
      content: null,
      truncated: false,
      line: null,
      column: null,
      readOnly: true,
    })),
    revealFile: vi.fn(async () => undefined),
    openFile: vi.fn(async (target) => ({
      snapshot: {
        ...snapshot,
        url: target.startsWith("file:") ? target : "file:///C:/dev/ja/index.html",
        window: {
          ...snapshot.window,
          url: target.startsWith("file:") ? target : "file:///C:/dev/ja/index.html",
        },
      },
      window: { ...snapshot.window, url: "file:///C:/dev/ja/index.html" },
    })),
    navigate: vi.fn(async (_id, generation, url) => ({
      ...snapshot,
      generation,
      url,
      window: { ...snapshot.window, url },
    })),
    navigateFile: vi.fn(async (_id, generation, target) => ({
      ...snapshot,
      generation,
      url: target.startsWith("file:") ? target : "file:///C:/dev/ja/index.html",
      window: { ...snapshot.window, url: "file:///C:/dev/ja/index.html" },
    })),
    goBack: vi.fn(async (_id, generation) => ({
      ...snapshot,
      generation,
      can_go_back: false,
      can_go_forward: true,
    })),
    goForward: vi.fn(async (_id, generation) => ({
      ...snapshot,
      generation,
      can_go_back: true,
      can_go_forward: false,
    })),
    reload: vi.fn(async (_id, generation) => ({ ...snapshot, generation })),
    layout: vi.fn(async (_id, viewport) => ({
      ...snapshot,
      window: {
        ...snapshot.window,
        ...(viewport.visible ? {} : { url: snapshot.url }),
      },
    })),
    close: vi.fn(async () => ({ ...snapshot, status: "closed" as const })),
    events: vi.fn(async () => []),
    state: vi.fn(async () => snapshot),
    subscribe: vi.fn(async (listener) => {
      previewListener = listener;
      return () => undefined;
    }),
  };
  return {
    preview,
    sessionHints: {
      read: (workspaceId) => hints.get(workspaceId) ?? [],
      remember: (workspaceId, pageId) =>
        hints.set(workspaceId, [...new Set([...(hints.get(workspaceId) ?? []), pageId])]),
      forget: (workspaceId, expectedPageId) => {
        if (expectedPageId === undefined) hints.delete(workspaceId);
        else {
          const remaining = (hints.get(workspaceId) ?? []).filter(
            (pageId) => pageId !== expectedPageId,
          );
          if (remaining.length > 0) hints.set(workspaceId, remaining);
          else hints.delete(workspaceId);
        }
      },
    },
    previewEvents: (event) => previewListener(event),
  };
}

describe("usePreviewLifecycleController", () => {
  /** 恢复期间的最后一次几何/显隐意图决定最终布局，后台恢复不得显示原生页面。 */
  it.each([true, false])(
    "restores the latest viewport (visible=%s) after its native hide ACK without another resize",
    async (visible) => {
      const adapters = createAdapters();
      const sessionId = "00000000-0000-4000-8000-000000000002";
      const session = await adapters.preview.state(sessionId);
      const restored = createDeferred<PreviewSessionSnapshot>();
      const hidden = createDeferred<PreviewSessionSnapshot>();
      adapters.sessionHints.remember(project.workspaceId, sessionId);
      adapters.preview.state = vi
        .fn()
        .mockImplementationOnce(() => restored.promise)
        .mockResolvedValue(session);
      adapters.preview.layout = vi
        .fn()
        .mockImplementationOnce(() => hidden.promise)
        .mockResolvedValue(session);
      const { result } = renderHook(() => useJaWorkbench(project, adapters));

      act(() => result.current.preview.onViewportChange(PREVIEW_VIEWPORT));
      await act(async () => {
        restored.resolve(session);
        await flushMicrotasks();
      });
      await waitFor(() => expect(adapters.preview.layout).toHaveBeenCalledOnce());
      act(() =>
        result.current.preview.onViewportChange({ ...PREVIEW_VIEWPORT, width: 500, visible }),
      );
      await act(async () => {
        hidden.resolve(session);
        await flushMicrotasks();
      });

      await waitFor(() => expect(result.current.preview.activePageId).toBe(sessionId));
      await waitFor(() =>
        expect(adapters.preview.layout).toHaveBeenLastCalledWith(sessionId, {
          ...PREVIEW_VIEWPORT,
          width: 500,
          visible,
        }),
      );
      expect(adapters.preview.layout).toHaveBeenCalledTimes(2);
    },
  );

  it("rehydrates a reload hint only through authoritative native state", async () => {
    const adapters = createAdapters();
    const sessionId = "00000000-0000-4000-8000-000000000002";
    adapters.sessionHints.remember(project.workspaceId, sessionId);

    const { result } = renderHook(() => useJaWorkbench(project, adapters));

    await waitFor(() => expect(result.current.preview.url).toBe("https://example.com/"));
    expect(adapters.preview.state).toHaveBeenCalledWith(sessionId);
    expect(adapters.preview.events).toHaveBeenCalledWith(sessionId, 512);
    expect(adapters.preview.recoverPending).toHaveBeenCalledOnce();
  });

  it("waits for initial orphan recovery before admitting the first native open", async () => {
    const adapters = createAdapters();
    const recovery =
      createDeferred<Awaited<ReturnType<JaWorkbenchAdapters["preview"]["recoverPending"]>>>();
    adapters.preview.recoverPending = vi.fn(() => recovery.promise);
    const { result } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.recoverPending).toHaveBeenCalledOnce());
    expect(result.current.preview.recovering).toBe(true);

    act(() => result.current.preview.onViewportChange!(PREVIEW_VIEWPORT));
    act(() => result.current.preview.onNavigate!("https://example.com/"));
    await act(flushMicrotasks);
    expect(adapters.preview.open).not.toHaveBeenCalled();

    recovery.resolve(RECOVERY_CLEAR);
    await waitFor(() => expect(adapters.preview.open).toHaveBeenCalledOnce());
    expect(result.current.preview.recovering).toBe(false);
  });

  it("keeps pending orphan recovery visible and exposes one explicit retry", async () => {
    const adapters = createAdapters();
    vi.mocked(adapters.preview.recoverPending)
      .mockResolvedValueOnce({ observed: 1, recovered: 0, failed: 1, pending: 1 })
      .mockResolvedValue(RECOVERY_CLEAR);
    const { result } = renderHook(() => useJaWorkbench(project, adapters));

    await waitFor(() => expect(result.current.preview.error).toBe("浏览器恢复未完成，请重试。"));
    expect(result.current.preview.onRetryRecovery).toBeTypeOf("function");
    act(() => result.current.preview.onRetryRecovery!());
    await waitFor(() => expect(result.current.preview.error).toBeUndefined());
    expect(result.current.preview.onRetryRecovery).toBeUndefined();
    expect(adapters.preview.recoverPending).toHaveBeenCalledTimes(2);
  });

  it("runs orphan recovery after an open error and blocks retry while Rust still reports pending", async () => {
    const adapters = createAdapters();
    vi.mocked(adapters.preview.recoverPending)
      .mockResolvedValueOnce(RECOVERY_CLEAR)
      .mockResolvedValue({ observed: 1, recovered: 0, failed: 1, pending: 1 });
    adapters.preview.open = vi.fn(async () => {
      throw new Error("native child rollback failed at C:\\private");
    });
    const { result } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.recoverPending).toHaveBeenCalledOnce());
    act(() => result.current.preview.onViewportChange!(PREVIEW_VIEWPORT));
    act(() => result.current.preview.onNavigate!("https://example.com/"));

    await waitFor(() => expect(result.current.preview.error).toBe("浏览器恢复未完成，请重试。"));
    expect(adapters.preview.open).toHaveBeenCalledOnce();
    expect(adapters.preview.recoverPending).toHaveBeenCalledTimes(2);
    expect(JSON.stringify(result.current.preview)).not.toContain("private");
  });

  it("opens, navigates and lays out one generation-fenced Preview session", async () => {
    const adapters = createAdapters();
    const { result, unmount } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.subscribe).toHaveBeenCalledTimes(1));

    act(() => result.current.preview.onViewportChange!(PREVIEW_VIEWPORT));
    act(() => result.current.preview.onNavigate!("https://example.com/"));
    await waitFor(() =>
      expect(adapters.preview.open).toHaveBeenCalledWith("https://example.com/", {
        ...PREVIEW_VIEWPORT,
        visible: false,
      }),
    );
    await waitFor(() => expect(result.current.preview.url).toBe("https://example.com/"));
    await waitFor(() =>
      expect(adapters.preview.layout).toHaveBeenCalledWith(
        "00000000-0000-4000-8000-000000000002",
        PREVIEW_VIEWPORT,
      ),
    );

    act(() => result.current.preview.onNavigate!("https://example.org/"));
    await waitFor(() =>
      expect(adapters.preview.navigate).toHaveBeenCalledWith(
        "00000000-0000-4000-8000-000000000002",
        0,
        "https://example.org/",
        "user",
      ),
    );

    act(() =>
      adapters.previewEvents({
        session_id: "00000000-0000-4000-8000-000000000002",
        generation: 1,
        sequence: 1,
        kind: {
          type: "navigation_committed",
          source: "user",
          url: "https://example.org/",
        },
      }),
    );
    expect(result.current.preview.url).toBe("https://example.org/");

    act(() =>
      adapters.previewEvents({
        session_id: "00000000-0000-4000-8000-000000000002",
        generation: 0,
        sequence: 2,
        kind: {
          type: "navigation_committed",
          source: "user",
          url: "https://stale.example/",
        },
      }),
    );
    expect(result.current.preview.url).toBe("https://example.org/");

    act(() =>
      result.current.preview.onViewportChange!({
        ...PREVIEW_VIEWPORT,
        width: 500,
      }),
    );
    await waitFor(() =>
      expect(adapters.preview.layout).toHaveBeenCalledWith(
        "00000000-0000-4000-8000-000000000002",
        expect.objectContaining({ width: 500 }),
      ),
    );

    await act(async () => result.current.previewWorkspaceLifecycle?.closeForWorkspaceChange());
    expect(adapters.preview.close).toHaveBeenCalledWith("00000000-0000-4000-8000-000000000002");
    unmount();
    expect(adapters.preview.close).toHaveBeenCalledOnce();
  });

  it("coalesces a resize burst to the latest native Preview layout", async () => {
    const adapters = createAdapters();
    const firstLayout = createDeferred<PreviewSessionSnapshot>();
    const { result } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.subscribe).toHaveBeenCalledTimes(1));

    act(() => result.current.preview.onViewportChange(PREVIEW_VIEWPORT));
    act(() => result.current.preview.onNavigate("https://example.com/"));
    await waitFor(() => expect(result.current.preview.url).toBe("https://example.com/"));
    const session = await adapters.preview.state("00000000-0000-4000-8000-000000000002");
    adapters.preview.layout = vi
      .fn()
      .mockImplementationOnce(() => firstLayout.promise)
      .mockResolvedValue(session);

    act(() => result.current.preview.onViewportChange({ ...PREVIEW_VIEWPORT, width: 500 }));
    await waitFor(() => expect(adapters.preview.layout).toHaveBeenCalledOnce());
    act(() => {
      result.current.preview.onViewportChange({ ...PREVIEW_VIEWPORT, width: 520 });
      result.current.preview.onViewportChange({ ...PREVIEW_VIEWPORT, width: 540 });
    });
    expect(adapters.preview.layout).toHaveBeenCalledOnce();

    await act(async () => {
      firstLayout.resolve(session);
      await flushMicrotasks();
    });
    await waitFor(() => expect(adapters.preview.layout).toHaveBeenCalledTimes(2));
    expect(adapters.preview.layout).toHaveBeenLastCalledWith(
      "00000000-0000-4000-8000-000000000002",
      expect.objectContaining({ width: 540 }),
    );

    act(() => result.current.preview.onViewportChange({ ...PREVIEW_VIEWPORT, width: 540 }));
    await act(flushMicrotasks);
    expect(adapters.preview.layout).toHaveBeenCalledTimes(2);
  });

  /** 用受控 ACK 固定布局回到原值的竞态，不能拿在途请求前的 applied cache 吞掉最新意图。 */
  it.each(["hide", "resize"] as const)(
    "restores the original viewport before the native %s completes",
    async (operation) => {
      const adapters = createAdapters();
      const { result } = renderHook(() => useJaWorkbench(project, adapters));
      act(() => result.current.preview.onViewportChange(PREVIEW_VIEWPORT));
      await act(async () =>
        result.current.preview.onOpenTarget({ kind: "file", path: "index.html" }),
      );
      const sessionId = result.current.preview.activePageId!;
      const session = await adapters.preview.state(sessionId);
      const hidden = createDeferred<PreviewSessionSnapshot>();
      adapters.preview.layout = vi
        .fn()
        .mockImplementationOnce(() => hidden.promise)
        .mockResolvedValue(session);

      act(() =>
        result.current.preview.onViewportChange(
          operation === "hide"
            ? { ...PREVIEW_VIEWPORT, visible: false }
            : { ...PREVIEW_VIEWPORT, width: 500 },
        ),
      );
      expect(adapters.preview.layout).toHaveBeenCalledOnce();
      act(() => result.current.preview.onViewportChange(PREVIEW_VIEWPORT));
      expect(adapters.preview.layout).toHaveBeenCalledOnce();
      await act(async () => {
        hidden.resolve(session);
        await flushMicrotasks();
      });

      await waitFor(() => expect(adapters.preview.layout).toHaveBeenCalledTimes(2));
      expect(adapters.preview.layout).toHaveBeenLastCalledWith(sessionId, PREVIEW_VIEWPORT);
      act(() => result.current.preview.onViewportChange(PREVIEW_VIEWPORT));
      await act(flushMicrotasks);
      expect(adapters.preview.layout).toHaveBeenCalledTimes(2);
    },
  );

  it("reconciles an early higher-generation load event against state after open ACK", async () => {
    const adapters = createAdapters();
    const pendingOpen =
      createDeferred<Awaited<ReturnType<JaWorkbenchAdapters["preview"]["open"]>>>();
    const authoritative: PreviewSessionSnapshot = {
      id: "00000000-0000-4000-8000-000000000002",
      generation: 2,
      status: "open",
      load_status: "finished",
      url: "https://redirect.example/",
      title: "Redirected",
      can_go_back: false,
      can_go_forward: false,
      window: { label: "ja-preview", url: "https://redirect.example/" },
      dropped_events: 0,
    };
    const finished: PreviewEvent = {
      session_id: authoritative.id,
      generation: 2,
      sequence: 2,
      kind: { type: "load_finished", url: authoritative.url },
    };
    adapters.preview.open = vi.fn(() => pendingOpen.promise);
    adapters.preview.events = vi.fn(async () => [finished]);
    adapters.preview.state = vi.fn(async () => authoritative);
    const { result } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.subscribe).toHaveBeenCalledTimes(1));
    act(() => result.current.preview.onViewportChange!(PREVIEW_VIEWPORT));
    act(() => result.current.preview.onNavigate!("https://example.com/"));
    await waitFor(() => expect(adapters.preview.open).toHaveBeenCalledOnce());

    act(() => adapters.previewEvents(finished));
    pendingOpen.resolve({
      snapshot: {
        ...authoritative,
        generation: 1,
        load_status: "loading",
        url: "https://example.com/",
        title: "",
        can_go_back: false,
        can_go_forward: false,
        window: { ...authoritative.window, url: "https://example.com/" },
      },
      window: { ...authoritative.window, url: "https://example.com/" },
    });

    await waitFor(() => expect(result.current.preview.url).toBe("https://redirect.example/"));
    expect(result.current.preview.loading).toBe(false);
    expect(adapters.preview.events).toHaveBeenCalledWith(authoritative.id, 512);
    expect(adapters.preview.state).toHaveBeenCalledWith(authoritative.id);
  });

  it("keeps navigation loading until load_finished instead of treating commitment as completion", async () => {
    const adapters = createAdapters();
    const { result } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.subscribe).toHaveBeenCalledTimes(1));
    act(() => result.current.preview.onViewportChange!(PREVIEW_VIEWPORT));
    act(() => result.current.preview.onNavigate!("https://example.com/"));
    await waitFor(() => expect(result.current.preview.url).toBe("https://example.com/"));
    expect(result.current.preview.loading).toBe(true);

    act(() =>
      adapters.previewEvents({
        session_id: "00000000-0000-4000-8000-000000000002",
        generation: 1,
        sequence: 1,
        kind: { type: "navigation_committed", source: "user", url: "https://example.org/" },
      }),
    );
    expect(result.current.preview.loading).toBe(true);
    expect(result.current.preview.url).toBe("https://example.org/");

    act(() =>
      adapters.previewEvents({
        session_id: "00000000-0000-4000-8000-000000000002",
        generation: 1,
        sequence: 2,
        kind: { type: "load_finished", url: "https://example.org/" },
      }),
    );
    expect(result.current.preview.loading).toBe(false);
    expect(result.current.preview.error).toBeUndefined();
  });

  it("closes the live Preview only when the host explicitly requests tab teardown", async () => {
    const adapters = createAdapters();
    const { result } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.subscribe).toHaveBeenCalledTimes(1));
    act(() => result.current.preview.onViewportChange!(PREVIEW_VIEWPORT));
    act(() => result.current.preview.onNavigate!("https://example.com/"));
    await waitFor(() => expect(adapters.preview.open).toHaveBeenCalledTimes(1));

    expect(adapters.preview.close).not.toHaveBeenCalled();
    await act(async () => result.current.closePreview());

    expect(adapters.preview.close).toHaveBeenCalledOnce();
    expect(adapters.preview.close).toHaveBeenCalledWith("00000000-0000-4000-8000-000000000002");
    expect(result.current.preview.url).toBeUndefined();
  });

  it("reconciles a native load failure when the live Preview event is missed", async () => {
    const adapters = createAdapters();
    const loading: PreviewSessionSnapshot = {
      id: "00000000-0000-4000-8000-000000000002",
      generation: 0,
      status: "open",
      load_status: "loading",
      url: "https://unavailable.example/",
      title: "Unavailable",
      can_go_back: false,
      can_go_forward: false,
      window: { label: "ja-preview", url: "https://unavailable.example/" },
      dropped_events: 0,
    };
    const failed: PreviewSessionSnapshot = { ...loading, load_status: "failed" };
    adapters.preview.open = vi.fn(async () => ({ snapshot: loading, window: loading.window }));
    vi.mocked(adapters.preview.state).mockResolvedValueOnce(loading).mockResolvedValue(failed);
    const { result } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.subscribe).toHaveBeenCalledTimes(1));
    act(() => result.current.preview.onViewportChange(PREVIEW_VIEWPORT));
    act(() => result.current.preview.onNavigate("https://unavailable.example/"));

    await waitFor(() => expect(result.current.preview.error).toBe("预览加载失败，请重试。"), {
      timeout: 2_000,
    });
    expect(result.current.preview.loading).toBe(false);
    expect(adapters.preview.events).toHaveBeenCalledWith(loading.id, 512);
    expect(adapters.preview.state).toHaveBeenCalledTimes(2);
  });

  it("fails closed after the renderer deadline when native loading never settles", async () => {
    const adapters = createAdapters();
    const stalled: PreviewSessionSnapshot = {
      id: "00000000-0000-4000-8000-000000000002",
      generation: 0,
      status: "open",
      load_status: "loading",
      url: "https://stalled.example/",
      title: "Stalled",
      can_go_back: false,
      can_go_forward: false,
      window: { label: "ja-preview", url: "https://stalled.example/" },
      dropped_events: 0,
    };
    adapters.preview.open = vi.fn(async () => ({ snapshot: stalled, window: stalled.window }));
    adapters.preview.state = vi.fn(async () => stalled);
    const { result, unmount } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.recoverPending).toHaveBeenCalledOnce());
    act(() => result.current.preview.onViewportChange(PREVIEW_VIEWPORT));
    vi.useFakeTimers();
    try {
      act(() => result.current.preview.onNavigate("https://stalled.example/"));
      await act(flushMicrotasks);
      await vi.waitFor(() => expect(result.current.preview.url).toBe("https://stalled.example/"));
      expect(result.current.preview.loading).toBe(true);

      await act(() => vi.advanceTimersByTimeAsync(31_000));
      expect(result.current.preview.loading).toBe(false);
      expect(result.current.preview.error).toBe("预览加载失败，请重试。");
    } finally {
      unmount();
      vi.useRealTimers();
    }
  });

  it("does not renew the renderer deadline when WebView2 advances loading generations", async () => {
    const adapters = createAdapters();
    const stalled: PreviewSessionSnapshot = {
      id: "00000000-0000-4000-8000-000000000002",
      generation: 0,
      status: "open",
      load_status: "loading",
      url: "https://stalled.example/",
      title: "Stalled",
      can_go_back: false,
      can_go_forward: false,
      window: { label: "ja-preview", url: "https://stalled.example/" },
      dropped_events: 0,
    };
    let generation = 0;
    adapters.preview.open = vi.fn(async () => ({ snapshot: stalled, window: stalled.window }));
    adapters.preview.state = vi.fn(async () => ({ ...stalled, generation: generation++ }));
    const { result, unmount } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.recoverPending).toHaveBeenCalledOnce());
    act(() => result.current.preview.onViewportChange(PREVIEW_VIEWPORT));
    vi.useFakeTimers();
    try {
      act(() => result.current.preview.onNavigate("https://stalled.example/"));
      await act(flushMicrotasks);
      await vi.waitFor(() => expect(result.current.preview.url).toBe("https://stalled.example/"));

      await act(() => vi.advanceTimersByTimeAsync(31_000));

      expect(generation).toBeGreaterThan(1);
      expect(result.current.preview.loading).toBe(false);
      expect(result.current.preview.error).toBe("预览加载失败，请重试。");
    } finally {
      unmount();
      vi.useRealTimers();
    }
  });

  /**
   * 原生 watchdog 后 WebView2 仍可能补发错误页的 committed/finished；同一用户导航必须保持
   * 可恢复错误，避免真窗从错误面板倒退成看似成功的空白预览。
   */
  it("keeps a failed navigation terminal when late WebView2 events arrive", async () => {
    const adapters = createAdapters();
    const stalled: PreviewSessionSnapshot = {
      id: "00000000-0000-4000-8000-000000000002",
      generation: 1,
      status: "open",
      load_status: "loading",
      url: "https://stalled.example/",
      title: "Stalled",
      can_go_back: false,
      can_go_forward: false,
      window: { label: "ja-preview", url: "https://stalled.example/" },
      dropped_events: 0,
    };
    adapters.preview.open = vi.fn(async () => ({ snapshot: stalled, window: stalled.window }));
    adapters.preview.state = vi.fn(async () => stalled);
    const { result } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.recoverPending).toHaveBeenCalledOnce());
    act(() => result.current.preview.onViewportChange(PREVIEW_VIEWPORT));
    act(() => result.current.preview.onNavigate("https://stalled.example/"));
    await waitFor(() => expect(result.current.preview.url).toBe("https://stalled.example/"));

    act(() =>
      adapters.previewEvents({
        session_id: stalled.id,
        generation: 1,
        sequence: 1,
        kind: { type: "load_failed", message: "预览加载失败，请重试。" },
      }),
    );
    expect(result.current.preview.error).toBe("预览加载失败，请重试。");
    expect(result.current.preview.loading).toBe(false);

    act(() =>
      adapters.previewEvents({
        session_id: stalled.id,
        generation: 2,
        sequence: 2,
        kind: {
          type: "navigation_committed",
          source: "redirect",
          url: "edge-error://edgewebdata/",
        },
      }),
    );
    act(() =>
      adapters.previewEvents({
        session_id: stalled.id,
        generation: 2,
        sequence: 3,
        kind: { type: "load_finished", url: "edge-error://edgewebdata/" },
      }),
    );

    expect(result.current.preview.error).toBe("预览加载失败，请重试。");
    expect(result.current.preview.loading).toBe(false);
  });

  it("retains the Preview identity after a redacted close failure and clears it only after retry succeeds", async () => {
    const adapters = createAdapters();
    const firstClose =
      createDeferred<Awaited<ReturnType<JaWorkbenchAdapters["preview"]["close"]>>>();
    const closedSnapshot: PreviewSessionSnapshot = {
      id: "00000000-0000-4000-8000-000000000002",
      generation: 0,
      status: "closed",
      load_status: "finished",
      url: "https://example.com/",
      title: "Example",
      can_go_back: false,
      can_go_forward: false,
      window: { label: "ja-preview", url: "https://example.com/" },
      dropped_events: 0,
    };
    adapters.preview.close = vi
      .fn()
      .mockImplementationOnce(() => firstClose.promise)
      .mockResolvedValueOnce(closedSnapshot);
    const { result } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.subscribe).toHaveBeenCalledTimes(1));
    act(() => result.current.preview.onViewportChange!(PREVIEW_VIEWPORT));
    act(() => result.current.preview.onNavigate!("https://example.com/"));
    await waitFor(() => expect(result.current.preview.url).toBe("https://example.com/"));
    const recoveryCallsBeforeClose = vi.mocked(adapters.preview.recoverPending).mock.calls.length;

    const closeAttempt = result.current.closePreview();
    act(() =>
      adapters.previewEvents({
        session_id: closedSnapshot.id,
        generation: 0,
        sequence: 1,
        kind: { type: "closed" },
      }),
    );
    expect(result.current.preview.url).toBe("https://example.com/");
    firstClose.reject(new Error("native close failed at C:\\private\\workspace"));
    await expect(closeAttempt).rejects.toThrow("浏览器关闭失败，请重试。");

    expect(result.current.preview.url).toBe("https://example.com/");
    expect(JSON.stringify(result.current.preview)).not.toContain("private");
    expect(adapters.preview.recoverPending).toHaveBeenCalledTimes(recoveryCallsBeforeClose);
    await act(async () => result.current.closePreview());
    expect(adapters.preview.close).toHaveBeenNthCalledWith(2, closedSnapshot.id);
    expect(result.current.preview.url).toBeUndefined();
  });

  it("joins a pending open, closes its returned identity, and recovers before workspace switch resolves", async () => {
    const adapters = createAdapters();
    const pendingOpen =
      createDeferred<Awaited<ReturnType<JaWorkbenchAdapters["preview"]["open"]>>>();
    const pendingClose =
      createDeferred<Awaited<ReturnType<JaWorkbenchAdapters["preview"]["close"]>>>();
    adapters.preview.open = vi.fn(() => pendingOpen.promise);
    adapters.preview.close = vi.fn(() => pendingClose.promise);
    const { result } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.recoverPending).toHaveBeenCalledOnce());
    act(() => result.current.preview.onViewportChange!(PREVIEW_VIEWPORT));
    act(() => result.current.preview.onNavigate!("https://switch.example/"));
    await waitFor(() => expect(adapters.preview.open).toHaveBeenCalledOnce());
    const recoveryCallsBeforeSwitch = vi.mocked(adapters.preview.recoverPending).mock.calls.length;

    let switched = false;
    const switchAttempt = result.current
      .previewWorkspaceLifecycle!.closeForWorkspaceChange()
      .then(() => {
        switched = true;
      });
    await act(flushMicrotasks);
    expect(adapters.preview.close).not.toHaveBeenCalled();
    expect(switched).toBe(false);

    pendingOpen.resolve({
      snapshot: {
        id: "00000000-0000-4000-8000-000000000077",
        generation: 1,
        status: "open",
        load_status: "loading",
        url: "https://switch.example/",
        title: "Switch",
        can_go_back: false,
        can_go_forward: false,
        window: { label: "ja-preview-switch", url: "https://switch.example/" },
        dropped_events: 0,
      },
      window: { label: "ja-preview-switch", url: "https://switch.example/" },
    });
    await waitFor(() =>
      expect(adapters.preview.close).toHaveBeenCalledWith("00000000-0000-4000-8000-000000000077"),
    );
    expect(switched).toBe(false);

    pendingClose.resolve({
      id: "00000000-0000-4000-8000-000000000077",
      generation: 1,
      status: "closed",
      load_status: "loading",
      url: "https://switch.example/",
      title: "Switch",
      can_go_back: false,
      can_go_forward: false,
      window: { label: "ja-preview-switch", url: "https://switch.example/" },
      dropped_events: 0,
    });
    await act(async () => switchAttempt);
    expect(switched).toBe(true);
    expect(adapters.preview.recoverPending).toHaveBeenCalledTimes(recoveryCallsBeforeSwitch + 1);
    expect(result.current.preview.url).toBeUndefined();
  });

  it("keeps workspace Preview identity and a visible retry error until close ACK succeeds", async () => {
    const adapters = createAdapters();
    const firstClose =
      createDeferred<Awaited<ReturnType<JaWorkbenchAdapters["preview"]["close"]>>>();
    adapters.preview.close = vi
      .fn()
      .mockImplementationOnce(() => firstClose.promise)
      .mockResolvedValueOnce({
        id: "00000000-0000-4000-8000-000000000002",
        generation: 0,
        status: "closed" as const,
        load_status: "finished" as const,
        url: "https://example.com/",
        title: "Example",
        can_go_back: false,
        can_go_forward: false,
        window: { label: "ja-preview", url: "https://example.com/" },
        dropped_events: 0,
      });
    const { result } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.subscribe).toHaveBeenCalledTimes(1));
    act(() => result.current.preview.onViewportChange!(PREVIEW_VIEWPORT));
    act(() => result.current.preview.onNavigate!("https://example.com/"));
    await waitFor(() => expect(result.current.preview.url).toBe("https://example.com/"));

    const closeAttempt = result.current.previewWorkspaceLifecycle!.closeForWorkspaceChange();
    firstClose.reject(new Error("private child detail"));
    await expect(closeAttempt).rejects.toThrow("浏览器关闭失败，请重试。");
    expect(result.current.preview.url).toBe("https://example.com/");
    await waitFor(() => expect(result.current.preview.error).toBe("浏览器关闭失败，请重试。"));

    await act(async () => result.current.previewWorkspaceLifecycle!.closeForWorkspaceChange());
    expect(result.current.preview.url).toBeUndefined();
  });

  it("closes a Preview returned after its project generation was replaced", async () => {
    const adapters = createAdapters();
    const pendingOpen =
      createDeferred<Awaited<ReturnType<JaWorkbenchAdapters["preview"]["open"]>>>();
    adapters.preview.open = vi.fn(() => pendingOpen.promise);
    const { result, rerender } = renderHook(
      ({ selectedProject }: { selectedProject: WorkspaceProjection }) =>
        useJaWorkbench(selectedProject, adapters),
      { initialProps: { selectedProject: project } },
    );
    await waitFor(() => expect(adapters.preview.subscribe).toHaveBeenCalledTimes(1));

    act(() => result.current.preview.onViewportChange!(PREVIEW_VIEWPORT));
    act(() => result.current.preview.onNavigate!("https://late.example/"));
    await waitFor(() => expect(adapters.preview.open).toHaveBeenCalledTimes(1));
    rerender({ selectedProject: otherProject });

    pendingOpen.resolve({
      snapshot: {
        id: "00000000-0000-4000-8000-000000000099",
        generation: 0,
        status: "open",
        load_status: "loading",
        url: "https://late.example/",
        title: "Late",
        can_go_back: false,
        can_go_forward: false,
        window: { label: "ja-preview", url: "https://late.example/" },
        dropped_events: 0,
      },
      window: { label: "ja-preview", url: "https://late.example/" },
    });
    await act(flushMicrotasks);

    await waitFor(() =>
      expect(adapters.preview.close).toHaveBeenCalledWith("00000000-0000-4000-8000-000000000099"),
    );
    expect(result.current.preview.url).toBeUndefined();
  });

  it("closes a late open result after unmount without publishing renderer state", async () => {
    const adapters = createAdapters();
    const pendingOpen =
      createDeferred<Awaited<ReturnType<JaWorkbenchAdapters["preview"]["open"]>>>();
    adapters.preview.open = vi.fn(() => pendingOpen.promise);
    const { result, unmount } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.subscribe).toHaveBeenCalledTimes(1));
    act(() => result.current.preview.onViewportChange!(PREVIEW_VIEWPORT));
    act(() => result.current.preview.onNavigate!("https://late.example/"));
    await waitFor(() => expect(adapters.preview.open).toHaveBeenCalledOnce());
    unmount();

    pendingOpen.resolve({
      snapshot: {
        id: "00000000-0000-4000-8000-000000000098",
        generation: 1,
        status: "open",
        load_status: "loading",
        url: "https://late.example/",
        title: "Late",
        can_go_back: false,
        can_go_forward: false,
        window: { label: "ja-preview-late", url: "https://late.example/" },
        dropped_events: 0,
      },
      window: { label: "ja-preview-late", url: "https://late.example/" },
    });
    await act(flushMicrotasks);

    await waitFor(() =>
      expect(adapters.preview.close).toHaveBeenCalledWith("00000000-0000-4000-8000-000000000098"),
    );
  });

  it("recovers a failed open rollback even when the workspace unmounts before native rejection", async () => {
    const adapters = createAdapters();
    const pendingOpen =
      createDeferred<Awaited<ReturnType<JaWorkbenchAdapters["preview"]["open"]>>>();
    adapters.preview.open = vi.fn(() => pendingOpen.promise);
    const { result, unmount } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.recoverPending).toHaveBeenCalledOnce());
    act(() => result.current.preview.onViewportChange!(PREVIEW_VIEWPORT));
    act(() => result.current.preview.onNavigate!("https://late-failure.example/"));
    await waitFor(() => expect(adapters.preview.open).toHaveBeenCalledOnce());
    const recoveryCallsBeforeUnmount = vi.mocked(adapters.preview.recoverPending).mock.calls.length;
    unmount();

    pendingOpen.reject(new Error("native rollback failed at C:\\private"));
    await waitFor(() =>
      expect(adapters.preview.recoverPending).toHaveBeenCalledTimes(recoveryCallsBeforeUnmount + 1),
    );
  });

  it("creates an unmeasured page hidden and waits for its first real viewport", async () => {
    const adapters = createAdapters();
    const { result } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.subscribe).toHaveBeenCalledTimes(1));

    act(() => result.current.preview.onNavigate!("https://example.com/"));

    await waitFor(() => expect(adapters.preview.open).toHaveBeenCalledOnce());
    expect(adapters.preview.open).toHaveBeenCalledWith("https://example.com/", {
      x: 0,
      y: 0,
      width: 1024,
      height: 768,
      visible: false,
    });
    await waitFor(() => expect(result.current.preview.url).toBe("https://example.com/"));
  });

  /** 多页 session 用 native ID 隔离，切换时严格 hide 旧页后 show 新页。 */
  it("creates independent hidden tabs and shows only the selected page", async () => {
    const adapters = createAdapters();
    const secondPageId = "00000000-0000-4000-8000-000000000003";
    const blank: PreviewSessionSnapshot = {
      ...(await adapters.preview.state("00000000-0000-4000-8000-000000000002")),
      id: secondPageId,
      url: "about:blank",
      title: "",
      window: { label: "ja-preview-second", url: "about:blank" },
    };
    vi.mocked(adapters.preview.openBlank).mockResolvedValue({
      snapshot: blank,
      window: blank.window,
    });
    const { result } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.recoverPending).toHaveBeenCalledOnce());
    act(() => result.current.preview.onViewportChange(PREVIEW_VIEWPORT));
    act(() => result.current.preview.onNavigate("https://example.com/"));
    await waitFor(() =>
      expect(result.current.preview.activePageId).toBe("00000000-0000-4000-8000-000000000002"),
    );

    await act(async () => result.current.preview.onNewPage());
    await waitFor(() => expect(result.current.preview.pages).toHaveLength(2));
    expect(adapters.preview.openBlank).toHaveBeenCalledWith({
      ...PREVIEW_VIEWPORT,
      visible: false,
    });
    expect(result.current.preview.activePageId).toBe(secondPageId);
    await waitFor(() =>
      expect(adapters.preview.layout).toHaveBeenCalledWith("00000000-0000-4000-8000-000000000002", {
        ...PREVIEW_VIEWPORT,
        visible: false,
      }),
    );
    await waitFor(() =>
      expect(adapters.preview.layout).toHaveBeenCalledWith(secondPageId, PREVIEW_VIEWPORT),
    );

    act(() => result.current.preview.onSelectPage("00000000-0000-4000-8000-000000000002"));
    await waitFor(() =>
      expect(result.current.preview.activePageId).toBe("00000000-0000-4000-8000-000000000002"),
    );
    await waitFor(() =>
      expect(adapters.preview.layout).toHaveBeenCalledWith(secondPageId, {
        ...PREVIEW_VIEWPORT,
        visible: false,
      }),
    );
    await waitFor(() =>
      expect(adapters.preview.layout).toHaveBeenLastCalledWith(
        "00000000-0000-4000-8000-000000000002",
        PREVIEW_VIEWPORT,
      ),
    );
  });

  /** 附件 / inactive Thread 的 false viewport 必须隐藏全部保留的原生页面。 */
  it("hides every retained native page when the preview surface becomes inactive", async () => {
    const adapters = createAdapters();
    const secondPageId = "00000000-0000-4000-8000-000000000003";
    const blank: PreviewSessionSnapshot = {
      ...(await adapters.preview.state("00000000-0000-4000-8000-000000000002")),
      id: secondPageId,
      url: "about:blank",
      title: "",
      window: { label: "ja-preview-second", url: "about:blank" },
    };
    vi.mocked(adapters.preview.openBlank).mockResolvedValue({
      snapshot: blank,
      window: blank.window,
    });
    const { result } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.recoverPending).toHaveBeenCalledOnce());
    act(() => result.current.preview.onViewportChange(PREVIEW_VIEWPORT));
    act(() => result.current.preview.onNavigate("https://example.com/"));
    await waitFor(() => expect(result.current.preview.pages).toHaveLength(1));
    await act(async () => result.current.preview.onNewPage());

    act(() => result.current.preview.onViewportChange({ ...PREVIEW_VIEWPORT, visible: false }));

    await waitFor(() =>
      expect(adapters.preview.layout).toHaveBeenCalledWith("00000000-0000-4000-8000-000000000002", {
        ...PREVIEW_VIEWPORT,
        visible: false,
      }),
    );
    await waitFor(() =>
      expect(adapters.preview.layout).toHaveBeenCalledWith(secondPageId, {
        ...PREVIEW_VIEWPORT,
        visible: false,
      }),
    );
  });

  /** 文件目标打开失败时调用方收到脱敏 rejection，且 ACK 前不会出现空标签。 */
  it("opens local browser targets in a new hidden page and leaves no tab on failure", async () => {
    const adapters = createAdapters();
    const fileUrl = "file:///C:/dev/ja/%E9%A1%B5%E9%9D%A2/index.html";
    const fileSnapshot: PreviewSessionSnapshot = {
      ...(await adapters.preview.state("00000000-0000-4000-8000-000000000002")),
      url: fileUrl,
      window: { label: "ja-preview-file", url: fileUrl },
    };
    vi.mocked(adapters.preview.openFile).mockResolvedValue({
      snapshot: fileSnapshot,
      window: fileSnapshot.window,
    });
    adapters.preview.state = vi.fn(async () => fileSnapshot);
    const { result } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.recoverPending).toHaveBeenCalledOnce());

    await act(async () =>
      result.current.preview.onOpenTarget({
        kind: "file",
        path: "C:\\dev\\ja\\页面\\index.html",
        line: 3,
      }),
    );
    expect(adapters.preview.openFile).toHaveBeenCalledWith(
      "C:\\dev\\ja\\页面\\index.html",
      project.workspaceId,
      { x: 0, y: 0, width: 1024, height: 768, visible: false },
      "browser",
    );
    expect(result.current.preview.url).toBe(fileUrl);
    expect(result.current.preview.pages).toHaveLength(1);

    adapters.preview.openFile = vi.fn(async () => {
      throw new Error("native failure at C:\\private\\secret.html");
    });
    await expect(
      result.current.preview.onOpenTarget({ kind: "file", path: "missing.html" }),
    ).rejects.toThrow("无法打开此页面，请重试。");
    expect(result.current.preview.pages).toHaveLength(1);
    expect(JSON.stringify(result.current.preview)).not.toContain("private");
  });

  /** Port 注入稳定错误文案即可验证应用生命周期；文件拒绝不应丢失原生页面事实。 */
  it("keeps an existing page after file navigation is rejected and clears the error on retry", async () => {
    const adapters = createAdapters();
    const pageId = "00000000-0000-4000-8000-000000000002";
    const base = await adapters.preview.state(pageId);
    const original: PreviewSessionSnapshot = {
      ...base,
      generation: 4,
      status: "open",
      load_status: "finished",
      url: "https://original.example/",
      title: "Original page",
      can_go_back: true,
      can_go_forward: false,
      window: { ...base.window, url: "https://original.example/" },
    };
    let nativeSnapshot = original;
    const successful: PreviewSessionSnapshot = {
      ...original,
      generation: 5,
      load_status: "finished",
      url: "file:///C:/dev/ja/src/index.html",
      title: "index.html",
      can_go_back: true,
      window: { ...original.window, url: "file:///C:/dev/ja/src/index.html" },
    };
    adapters.preview.open = vi.fn(async () => ({ snapshot: original, window: original.window }));
    adapters.preview.state = vi.fn(async () => nativeSnapshot);
    vi.mocked(adapters.preview.navigateFile)
      .mockRejectedValueOnce(new Error("这是一个文件夹，无法在浏览器中打开。"))
      .mockImplementationOnce(async () => {
        nativeSnapshot = successful;
        return successful;
      });
    const { result } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.recoverPending).toHaveBeenCalledOnce());
    await act(async () => result.current.preview.onOpenTarget({ kind: "url", url: original.url }));
    await waitFor(() => expect(result.current.preview.url).toBe(original.url));

    act(() => result.current.preview.onNavigateFile("src"));
    await waitFor(() =>
      expect(result.current.preview.error).toBe("这是一个文件夹，无法在浏览器中打开。"),
    );
    expect(result.current.preview.pages).toHaveLength(1);
    expect(result.current.preview.activePageId).toBe(pageId);
    expect(result.current.preview.url).toBe(original.url);
    expect(result.current.preview.loading).toBe(false);
    expect(result.current.preview.pages[0]).toMatchObject({
      pageId,
      url: original.url,
      loading: false,
      error: "这是一个文件夹，无法在浏览器中打开。",
      canGoBack: true,
      canGoForward: false,
    });
    expect(adapters.preview.open).toHaveBeenCalledOnce();
    expect(adapters.preview.openFile).not.toHaveBeenCalled();

    act(() =>
      adapters.previewEvents({
        session_id: pageId,
        generation: original.generation,
        sequence: 1,
        kind: { type: "load_finished", url: original.url },
      }),
    );
    expect(result.current.preview.error).toBe("这是一个文件夹，无法在浏览器中打开。");
    expect(result.current.preview.url).toBe(original.url);

    act(() => result.current.preview.onNavigateFile("src/index.html"));
    await waitFor(() => expect(result.current.preview.url).toBe(successful.url));
    expect(adapters.preview.open).toHaveBeenCalledOnce();
    expect(adapters.preview.navigateFile).toHaveBeenNthCalledWith(
      2,
      pageId,
      original.generation,
      "src/index.html",
      project.workspaceId,
      "browser",
    );
    expect(result.current.preview.error).toBeUndefined();
    expect(result.current.preview.pages).toHaveLength(1);
  });

  /** Port 只输入稳定失败文案；旧失败请求的 state 回读不能结束随后启动的新导航。 */
  it("ignores a rejected navigation snapshot after a newer page navigation starts", async () => {
    const adapters = createAdapters();
    const pageId = "00000000-0000-4000-8000-000000000002";
    const base = await adapters.preview.state(pageId);
    const original: PreviewSessionSnapshot = {
      ...base,
      generation: 7,
      status: "open",
      load_status: "finished",
      url: "https://original.example/",
      window: { ...base.window, url: "https://original.example/" },
    };
    const nextNavigation = createDeferred<PreviewSessionSnapshot>();
    const lateState = createDeferred<PreviewSessionSnapshot>();
    let nativeSnapshot = original;
    let deferNextState = false;
    adapters.preview.open = vi.fn(async () => ({ snapshot: original, window: original.window }));
    adapters.preview.state = vi.fn(async () => {
      if (deferNextState) {
        deferNextState = false;
        return lateState.promise;
      }
      return nativeSnapshot;
    });
    vi.mocked(adapters.preview.navigateFile)
      .mockRejectedValueOnce(new Error("这是一个文件夹，无法在浏览器中打开。"))
      .mockImplementationOnce(() => nextNavigation.promise);
    const { result } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.recoverPending).toHaveBeenCalledOnce());
    await act(async () => result.current.preview.onOpenTarget({ kind: "url", url: original.url }));
    await waitFor(() => expect(result.current.preview.url).toBe(original.url));

    deferNextState = true;
    const stateCallsBeforeFailure = vi.mocked(adapters.preview.state).mock.calls.length;
    act(() => result.current.preview.onNavigateFile("src"));
    await waitFor(() =>
      expect(result.current.preview.error).toBe("这是一个文件夹，无法在浏览器中打开。"),
    );
    await waitFor(() =>
      expect(vi.mocked(adapters.preview.state).mock.calls.length).toBeGreaterThanOrEqual(
        stateCallsBeforeFailure + 1,
      ),
    );

    act(() => result.current.preview.onNavigateFile("src/index.html"));
    expect(result.current.preview.error).toBeUndefined();
    expect(result.current.preview.loading).toBe(true);
    await act(async () => {
      lateState.resolve(original);
      await flushMicrotasks();
    });
    expect(result.current.preview.loading).toBe(true);
    expect(result.current.preview.url).toBe(original.url);

    const successful: PreviewSessionSnapshot = {
      ...original,
      generation: 8,
      load_status: "finished",
      url: "file:///C:/dev/ja/src/index.html",
      window: { ...original.window, url: "file:///C:/dev/ja/src/index.html" },
    };
    nativeSnapshot = successful;
    await act(async () => {
      nextNavigation.resolve(successful);
      await flushMicrotasks();
    });
    await waitFor(() => expect(result.current.preview.url).toBe(successful.url));
    expect(result.current.preview.loading).toBe(false);
    expect(result.current.preview.error).toBeUndefined();
  });

  /** ACK 前仍处在旧 native generation 时，旧 failure 不应结束新页面的导航 deadline。 */
  it("ignores an old failed navigation ACK before the new page commits and finishes", async () => {
    const adapters = createAdapters();
    const pageId = "00000000-0000-4000-8000-000000000002";
    const base = await adapters.preview.state(pageId);
    const original: PreviewSessionSnapshot = {
      ...base,
      generation: 7,
      status: "open",
      load_status: "finished",
      url: "https://original.example/",
      window: { ...base.window, url: "https://original.example/" },
    };
    const staleFailure: PreviewSessionSnapshot = {
      ...original,
      load_status: "failed",
    };
    const nextUrl = "file:///C:/dev/ja/src/index.html";
    const committed: PreviewSessionSnapshot = {
      ...original,
      generation: 8,
      load_status: "loading",
      url: nextUrl,
      window: { ...original.window, url: nextUrl },
    };
    adapters.preview.open = vi.fn(async () => ({ snapshot: original, window: original.window }));
    adapters.preview.state = vi.fn(async () => original);
    vi.mocked(adapters.preview.navigateFile).mockResolvedValue(staleFailure);
    const { result } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.recoverPending).toHaveBeenCalledOnce());
    await act(async () => result.current.preview.onOpenTarget({ kind: "url", url: original.url }));
    await waitFor(() => expect(result.current.preview.url).toBe(original.url));

    adapters.preview.state = vi.fn(async () => staleFailure);
    act(() => result.current.preview.onNavigateFile("src/index.html"));
    await waitFor(() => expect(adapters.preview.navigateFile).toHaveBeenCalledOnce());
    await act(flushMicrotasks);
    expect(result.current.preview.loading).toBe(true);
    expect(result.current.preview.error).toBeUndefined();
    expect(result.current.preview.url).toBe(original.url);

    act(() =>
      adapters.previewEvents({
        session_id: pageId,
        generation: committed.generation,
        sequence: 1,
        kind: { type: "navigation_committed", source: "user", url: nextUrl },
      }),
    );
    expect(result.current.preview.loading).toBe(true);
    expect(result.current.preview.error).toBeUndefined();

    act(() =>
      adapters.previewEvents({
        session_id: pageId,
        generation: committed.generation,
        sequence: 2,
        kind: { type: "load_finished", url: nextUrl },
      }),
    );
    expect(result.current.preview.loading).toBe(false);
    expect(result.current.preview.error).toBeUndefined();
    expect(result.current.preview.url).toBe(nextUrl);
  });

  /** 起点代际的迟到失败要丢弃，新代际失败仍须显示且同 intent 的 finished 不能抹除诊断。 */
  it("ignores an old load failure while preserving a real failure for the new generation", async () => {
    const adapters = createAdapters();
    const pageId = "00000000-0000-4000-8000-000000000002";
    const base = await adapters.preview.state(pageId);
    const original: PreviewSessionSnapshot = {
      ...base,
      generation: 7,
      status: "open",
      load_status: "finished",
      url: "https://original.example/",
      window: { ...base.window, url: "https://original.example/" },
    };
    const nextUrl = "file:///C:/dev/ja/src/index.html";
    const navigation = createDeferred<PreviewSessionSnapshot>();
    adapters.preview.open = vi.fn(async () => ({ snapshot: original, window: original.window }));
    adapters.preview.state = vi.fn(async () => original);
    vi.mocked(adapters.preview.navigateFile).mockImplementation(() => navigation.promise);
    const { result } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.recoverPending).toHaveBeenCalledOnce());
    await act(async () => result.current.preview.onOpenTarget({ kind: "url", url: original.url }));
    await waitFor(() => expect(result.current.preview.url).toBe(original.url));

    act(() => result.current.preview.onNavigateFile("src/index.html"));
    await waitFor(() => expect(adapters.preview.navigateFile).toHaveBeenCalledOnce());
    act(() =>
      adapters.previewEvents({
        session_id: pageId,
        generation: original.generation,
        sequence: 1,
        kind: { type: "load_failed", message: "旧页面失败" },
      }),
    );
    expect(result.current.preview.loading).toBe(true);
    expect(result.current.preview.error).toBeUndefined();

    act(() =>
      adapters.previewEvents({
        session_id: pageId,
        generation: original.generation + 1,
        sequence: 2,
        kind: { type: "navigation_committed", source: "user", url: nextUrl },
      }),
    );
    act(() =>
      adapters.previewEvents({
        session_id: pageId,
        generation: original.generation + 1,
        sequence: 3,
        kind: { type: "load_failed", message: "新页面加载失败" },
      }),
    );
    expect(result.current.preview.loading).toBe(false);
    expect(result.current.preview.error).toBe("新页面加载失败");

    act(() =>
      adapters.previewEvents({
        session_id: pageId,
        generation: original.generation + 1,
        sequence: 4,
        kind: { type: "load_finished", url: nextUrl },
      }),
    );
    expect(result.current.preview.loading).toBe(false);
    expect(result.current.preview.error).toBe("新页面加载失败");

    await act(async () => {
      navigation.resolve({
        ...original,
        generation: original.generation + 1,
        load_status: "failed",
        url: nextUrl,
        window: { ...original.window, url: nextUrl },
      });
      await flushMicrotasks();
    });
    expect(result.current.preview.error).toBe("新页面加载失败");
  });

  /** 历史 capability 来自 native snapshot/event，并将后退请求发送到原生引擎。 */
  it("uses native history state and navigates a local path within the active tab", async () => {
    const adapters = createAdapters();
    const { result } = renderHook(() => useJaWorkbench(project, adapters));
    await waitFor(() => expect(adapters.preview.recoverPending).toHaveBeenCalledOnce());
    act(() => result.current.preview.onViewportChange(PREVIEW_VIEWPORT));
    act(() => result.current.preview.onNavigate("https://example.com/"));
    await waitFor(() => expect(result.current.preview.pages).toHaveLength(1));
    const pageId = result.current.preview.activePageId!;

    act(() =>
      adapters.previewEvents({
        session_id: pageId,
        generation: 0,
        sequence: 1,
        kind: { type: "history_changed", can_go_back: true, can_go_forward: false },
      }),
    );
    expect(result.current.preview.canGoBack).toBe(true);
    act(() => result.current.preview.onGoBack());
    await waitFor(() => expect(adapters.preview.goBack).toHaveBeenCalledWith(pageId, 0));

    act(() => result.current.preview.onNavigateFile("src/页面.html"));
    await waitFor(() =>
      expect(adapters.preview.navigateFile).toHaveBeenCalledWith(
        pageId,
        0,
        "src/页面.html",
        project.workspaceId,
        "browser",
      ),
    );
  });

  it("does not subscribe or open Preview without a project", async () => {
    const adapters = createAdapters();
    const { result } = renderHook(() => useJaWorkbench(undefined, adapters));

    act(() => result.current.preview.onViewportChange!(PREVIEW_VIEWPORT));
    act(() => result.current.preview.onNavigate!("https://example.com/"));
    await act(flushMicrotasks);

    expect(adapters.preview.subscribe).not.toHaveBeenCalled();
    expect(adapters.preview.open).not.toHaveBeenCalled();
  });
});
