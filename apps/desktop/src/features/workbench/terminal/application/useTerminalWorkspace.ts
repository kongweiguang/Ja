// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useRef, useState } from "react";
import type {
  TerminalEvent,
  TerminalOpenInput,
  TerminalOutputChunk,
  TerminalSessionInfo,
  TerminalSize,
  TerminalWorkspaceAdapter,
} from "./terminalPorts";
import {
  addTerminalTab,
  canAddTerminalTab,
  canSplitTerminalPane,
  findTerminalPane,
  MAX_TERMINAL_PANES,
  removeTerminalPane,
  removeTerminalTab,
  repairTerminalLayoutProfiles,
  sanitizeTerminalLayout,
  setActiveTerminalPane,
  setActiveTerminalTab,
  setTerminalSplitRatio,
  splitTerminalPane,
  TERMINAL_PROFILES,
  type TerminalLayoutV1,
  type TerminalLayoutNode,
  type TerminalProfile,
  type TerminalSplitOrientation,
  type TerminalTabCreateOptions,
} from "../domain";

type TerminalPaneLifecycle =
  | "dormant"
  | "opening"
  | "running"
  | "exited"
  | "failed"
  | "closing"
  | "closed";
export type TerminalWorkspaceCloseAll = () => Promise<void>;

export interface TerminalPaneRuntime {
  lifecycle: TerminalPaneLifecycle;
  session?: TerminalSessionInfo;
  initialData?: Uint8Array;
  outputs?: readonly TerminalOutputChunk[];
  pendingOutputBytes?: number;
  droppedBytes?: number;
  error?: string;
}

/** profile 状态单独投影，使 UI 能区分等待、可用和失败而不读取 adapter 异常。 */
type TerminalProfilesStatus = "loading" | "ready" | "failed";

export interface UseTerminalWorkspaceOptions {
  workspaceId: string;
  adapter: TerminalWorkspaceAdapter;
  initialLayout?: unknown;
  onLayoutChange?: (layout: TerminalLayoutV1) => void;
  onOpenExternalUrl?: (url: string) => void | Promise<void>;
  onCopy?: (text: string) => void | Promise<void>;
  onPaste?: () => string | Promise<string>;
}

export interface TerminalWorkspaceController {
  layout: TerminalLayoutV1;
  runtimes: Readonly<Record<string, TerminalPaneRuntime>>;
  profiles: readonly TerminalProfile[];
  profilesStatus: TerminalProfilesStatus;
  closeAllPending: boolean;
  canAddTab: boolean;
  canSplitPane: (tabId: string, paneId: string) => boolean;
  ensurePaneOpen: (paneId: string) => Promise<void>;
  sendInput: (paneId: string, data: string) => Promise<void>;
  dropNativePaths: (paneId: string, dropToken: string) => Promise<void>;
  resizePane: (paneId: string, size: TerminalSize) => Promise<void>;
  closePane: (paneId: string) => Promise<void>;
  restartPane: (paneId: string) => Promise<void>;
  addTab: (options?: TerminalTabCreateOptions) => boolean;
  closeTab: (tabId: string) => Promise<void>;
  activateTab: (tabId: string) => void;
  activatePane: (tabId: string, paneId: string) => void;
  splitPane: (tabId: string, paneId: string, orientation: TerminalSplitOrientation) => void;
  removePane: (tabId: string, paneId: string) => Promise<void>;
  setSplitRatio: (tabId: string, splitId: string, ratio: number) => void;
  closeAll: () => Promise<void>;
  terminalInputProps: (paneId: string) => {
    initialData?: Uint8Array;
    outputs?: readonly TerminalOutputChunk[];
    onOutputsConsumed: (throughSequence: number | string) => void;
    onData: (data: string) => void;
    onResize: (size: { cols: number; rows: number }) => void;
    onOpenExternalUrl?: (url: string) => void | Promise<void>;
    onCopy?: (text: string) => void | Promise<void>;
    onPaste?: () => string | Promise<string>;
  };
}

const DEFAULT_TERMINAL_SIZE: TerminalSize = { rows: 24, cols: 80, pixel_width: 0, pixel_height: 0 };
const MAX_PENDING_OUTPUT_EVENTS = 2_048;
const MAX_PENDING_OUTPUT_BYTES = 8 * 1024 * 1024;

interface TerminalProfilesGate {
  promise: Promise<void>;
  resolve: () => void;
}

/** 创建一次性加载栅栏，让主动打开和 React effect 共用同一份 Rust 探测结果。 */
function createTerminalProfilesGate(): TerminalProfilesGate {
  let resolve!: () => void;
  const promise = new Promise<void>((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}

/** 将每个 `output_dropped` 视为增量，并在 JavaScript 丢失整数精度前饱和，保证累计背压可观测。 */
function accumulateDroppedBytes(current: number | undefined, increment: number): number {
  const previous = current ?? 0;
  if (increment <= 0) return previous;
  if (previous >= Number.MAX_SAFE_INTEGER - increment) return Number.MAX_SAFE_INTEGER;
  return previous + increment;
}

/** 将 PTY 生命周期与持久化树分离，并在任何启动前等待 Rust profile 真值完成加载。 */
export function useTerminalWorkspace(
  options: UseTerminalWorkspaceOptions,
): TerminalWorkspaceController {
  const {
    workspaceId,
    adapter,
    initialLayout,
    onLayoutChange,
    onOpenExternalUrl,
    onCopy,
    onPaste,
  } = options;
  const initialLayoutRef = useRef(initialLayout);
  const [layout, setLayout] = useState<TerminalLayoutV1>(() =>
    sanitizeTerminalLayout(initialLayoutRef.current, workspaceId),
  );
  const layoutRef = useRef(layout);
  const [runtimes, setRuntimes] = useState<Record<string, TerminalPaneRuntime>>({});
  const [profiles, setProfiles] = useState<readonly TerminalProfile[]>([]);
  const [profilesStatus, setProfilesStatus] = useState<TerminalProfilesStatus>("loading");
  const [closeAllFenced, setCloseAllFenced] = useState(false);
  const adapterRef = useRef(adapter);
  const workspaceRef = useRef(workspaceId);
  const runtimesRef = useRef(runtimes);
  const openingRef = useRef(new Map<string, Promise<void>>());
  const pollingRef = useRef(new Map<string, Promise<void>>());
  const closingRef = useRef(new Map<string, Promise<void>>());
  const inputTailsRef = useRef(new Map<string, Promise<void>>());
  const restartingRef = useRef(new Map<string, Promise<void>>());
  const tabClosingRef = useRef(new Map<string, Promise<void>>());
  const closeAllRef = useRef<Promise<void> | undefined>(undefined);
  const closeAllAdmissionRef = useRef(false);
  const switchPromiseRef = useRef<Promise<void>>(Promise.resolve());
  const switchFailedRef = useRef(false);
  const supportedProfilesRef = useRef<readonly TerminalProfile[]>([]);
  const profilesStatusRef = useRef<TerminalProfilesStatus>("loading");
  const profilesGateRef = useRef<TerminalProfilesGate | null>(null);
  if (profilesGateRef.current === null) profilesGateRef.current = createTerminalProfilesGate();

  useEffect(() => {
    adapterRef.current = adapter;
  }, [adapter]);

  useEffect(() => {
    initialLayoutRef.current = initialLayout;
  }, [initialLayout]);

  /** 每次提交都重新投影为 dormant 白名单，防止未来状态扩展把 PTY runtime 或 secret 带入偏好存储。 */
  const commitLayout = useCallback(
    (next: TerminalLayoutV1): void => {
      const dormant = sanitizeTerminalLayout(
        next,
        next.workspaceId,
        profilesStatusRef.current === "ready" ? supportedProfilesRef.current : TERMINAL_PROFILES,
      );
      layoutRef.current = dormant;
      setLayout(dormant);
      onLayoutChange?.(dormant);
    },
    [onLayoutChange],
  );

  const commitLayoutRef = useRef(commitLayout);

  /** 让异步 profile 查询提交到最新持久化回调，同时避免回调身份变化触发重复原生查询。 */
  useEffect(() => {
    commitLayoutRef.current = commitLayout;
  }, [commitLayout]);

  /** Rust 是平台可用 profile 的唯一事实源；成功后先修复布局，再放行任何 PTY open。 */
  useEffect(() => {
    let disposed = false;
    const gate = profilesGateRef.current ?? createTerminalProfilesGate();
    profilesGateRef.current = gate;
    supportedProfilesRef.current = [];
    profilesStatusRef.current = "loading";
    setProfiles([]);
    setProfilesStatus("loading");

    void adapter
      .profiles()
      .then((available) => {
        if (disposed) return;
        supportedProfilesRef.current = available;
        const repaired = repairTerminalLayoutProfiles(layoutRef.current, available);
        if (repaired !== layoutRef.current) commitLayoutRef.current(repaired);
        profilesStatusRef.current = "ready";
        setProfiles(available);
        setProfilesStatus("ready");
        gate.resolve();
      })
      .catch(() => {
        if (disposed) return;
        profilesStatusRef.current = "failed";
        setProfilesStatus("failed");
        gate.resolve();
      });

    return () => {
      disposed = true;
      gate.resolve();
      if (profilesGateRef.current === gate) profilesGateRef.current = createTerminalProfilesGate();
    };
  }, [adapter]);

  /** 先更新权威 ref 再调度 React state，防止连续 poll 从同一旧 render 派生并覆盖字节。 */
  const updateRuntime = useCallback((paneId: string, patch: Partial<TerminalPaneRuntime>): void => {
    const current = runtimesRef.current;
    const next = {
      ...current,
      [paneId]: { ...(current[paneId] ?? { lifecycle: "dormant" }), ...patch },
    };
    runtimesRef.current = next;
    setRuntimes(next);
  }, []);

  /**
   * 在 xterm 确认 sequence 前保留所有未渲染事件；超过队列预算时显式进入失败态，
   * 不能在 React 批处理窗口中静默丢弃输出。
   */
  const appendRuntimeOutput = useCallback(
    (paneId: string, output: TerminalOutputChunk): boolean => {
      const runtime = runtimesRef.current[paneId] ?? { lifecycle: "dormant" as const };
      const outputs = [...(runtime.outputs ?? []), output];
      const pendingOutputBytes = (runtime.pendingOutputBytes ?? 0) + output.data.length;
      if (
        outputs.length > MAX_PENDING_OUTPUT_EVENTS ||
        pendingOutputBytes > MAX_PENDING_OUTPUT_BYTES
      ) {
        updateRuntime(paneId, { lifecycle: "failed", error: "终端输出积压过多，请重启终端" });
        return false;
      }
      updateRuntime(paneId, { lifecycle: "running", outputs, pendingOutputBytes });
      return true;
    },
    [updateRuntime],
  );

  /** 只移除 xterm 已确认前缀，保留 render effect 执行期间新到达的输出。 */
  const acknowledgeOutputs = useCallback(
    (paneId: string, throughSequence: number | string): void => {
      const runtime = runtimesRef.current[paneId];
      const outputs = runtime?.outputs;
      if (runtime === undefined || outputs === undefined) return;
      const index = outputs.findIndex((output) => output.sequence === throughSequence);
      if (index < 0) return;
      const consumedBytes = outputs
        .slice(0, index + 1)
        .reduce((total, output) => total + output.data.length, 0);
      const remaining = outputs.slice(index + 1);
      updateRuntime(paneId, {
        outputs: remaining.length === 0 ? undefined : remaining,
        pendingOutputBytes: Math.max(0, (runtime.pendingOutputBytes ?? 0) - consumedBytes),
      });
    },
    [updateRuntime],
  );

  /** 仅在两个不透明 session 字段仍匹配时应用事件；已关闭或重启代次的迟到事件直接忽略。 */
  const applyEvent = useCallback(
    (paneId: string, session: TerminalSessionInfo, event: TerminalEvent): boolean => {
      if (event.session_id !== session.sessionId || event.generation !== session.generation)
        return false;
      switch (event.kind.type) {
        case "output":
          return appendRuntimeOutput(paneId, { sequence: event.sequence, data: event.kind.data });
        case "output_dropped": {
          const current = runtimesRef.current[paneId];
          updateRuntime(paneId, {
            droppedBytes: accumulateDroppedBytes(current?.droppedBytes, event.kind.bytes),
          });
          return true;
        }
        case "exited":
          updateRuntime(paneId, { lifecycle: "exited" });
          return false;
        case "closed":
          updateRuntime(paneId, {
            lifecycle: "closed",
            session: undefined,
            outputs: undefined,
            pendingOutputBytes: 0,
            droppedBytes: undefined,
          });
          return false;
        case "error":
          updateRuntime(paneId, { lifecycle: "failed", error: "终端后台任务发生错误" });
          return false;
        case "resized":
          return true;
      }
    },
    [appendRuntimeOutput, updateRuntime],
  );

  /** 每个窗格只启动一个串行 poll loop，使并发 ensure-open 不会创建重复读取者。 */
  const startPolling = useCallback(
    (paneId: string, session: TerminalSessionInfo): void => {
      if (pollingRef.current.has(paneId)) return;
      const loop = (async (): Promise<void> => {
        try {
          while (true) {
            const current = runtimesRef.current[paneId];
            const activeSession = current?.session;
            if (
              current === undefined ||
              activeSession === undefined ||
              activeSession.sessionId !== session.sessionId ||
              activeSession.generation !== session.generation ||
              ["closing", "closed", "failed", "exited"].includes(current.lifecycle)
            )
              return;
            const event = await adapterRef.current.poll(session, 500);
            const after = runtimesRef.current[paneId];
            const afterSession = after?.session;
            if (
              after === undefined ||
              afterSession === undefined ||
              afterSession.sessionId !== session.sessionId ||
              afterSession.generation !== session.generation
            )
              return;
            if (["closing", "closed", "failed", "exited"].includes(after.lifecycle)) return;
            if (event !== null && !applyEvent(paneId, session, event)) return;
          }
        } catch {
          const current = runtimesRef.current[paneId];
          if (
            current?.session?.sessionId === session.sessionId &&
            current.session.generation === session.generation &&
            current.lifecycle === "running"
          ) {
            updateRuntime(paneId, { lifecycle: "failed", error: "终端输出读取失败" });
          }
        } finally {
          pollingRef.current.delete(paneId);
        }
      })();
      pollingRef.current.set(paneId, loop);
    },
    [applyEvent, updateRuntime],
  );

  /** 仅在 profile 探测与关闭栅栏都允许时启动一次休眠窗格，并在 await 后重复校验。 */
  const ensurePaneOpen = useCallback(
    async (paneId: string): Promise<void> => {
      if (closeAllAdmissionRef.current || profilesStatus === "failed") return;
      const gate = profilesGateRef.current;
      if (gate === null) return;
      await gate.promise;
      if (closeAllAdmissionRef.current || profilesStatusRef.current !== "ready") return;
      const existing = openingRef.current.get(paneId);
      if (existing !== undefined) return existing;
      if (
        closingRef.current.has(paneId) ||
        findTerminalPane(layoutRef.current, paneId) === undefined
      )
        return;
      const current = runtimesRef.current[paneId];
      if (
        current?.lifecycle === "opening" ||
        current?.lifecycle === "running" ||
        current?.session !== undefined
      )
        return;
      const operation = (async (): Promise<void> => {
        await switchPromiseRef.current;
        if (closeAllAdmissionRef.current) return;
        if (workspaceRef.current !== workspaceId) return;
        if (switchFailedRef.current) {
          updateRuntime(paneId, { lifecycle: "failed", error: "旧工作区终端未能安全关闭" });
          return;
        }
        const location = findTerminalPane(layoutRef.current, paneId);
        if (location === undefined || closingRef.current.has(paneId)) return;
        const livePanes = Object.values(runtimesRef.current).filter(
          (runtime) => runtime.lifecycle === "opening" || runtime.lifecycle === "running",
        ).length;
        if (livePanes >= MAX_TERMINAL_PANES) {
          updateRuntime(paneId, { lifecycle: "failed", error: "已达到终端窗格上限" });
          return;
        }
        updateRuntime(paneId, {
          lifecycle: "opening",
          error: undefined,
          outputs: undefined,
          pendingOutputBytes: 0,
          initialData: undefined,
          droppedBytes: undefined,
        });
        const tab = layoutRef.current.tabs.find((candidate) => candidate.tabId === location.tabId);
        if (tab === undefined) return;
        if (!supportedProfilesRef.current.includes(tab.profile)) {
          updateRuntime(paneId, { lifecycle: "failed", error: "当前平台不支持此终端环境" });
          return;
        }
        const input: TerminalOpenInput = {
          workspaceId,
          profile: tab.profile,
          ...(tab.relativeCwd === undefined ? {} : { relativeCwd: tab.relativeCwd }),
          size: DEFAULT_TERMINAL_SIZE,
        };
        try {
          const session = await adapterRef.current.open(input);
          // `open` 总会创建新代次，启动输出已经进入事件队列；此处读取 scrollback 会重复播放字节。
          updateRuntime(paneId, { lifecycle: "running", session });
          if (!closeAllAdmissionRef.current) startPolling(paneId, session);
        } catch {
          updateRuntime(paneId, { lifecycle: "failed", error: "终端无法启动" });
        }
      })();
      openingRef.current.set(paneId, operation);
      try {
        await operation;
      } finally {
        openingRef.current.delete(paneId);
      }
    },
    [profilesStatus, startPolling, updateRuntime, workspaceId],
  );

  /**
   * 对每个窗格串行化已接受的 xterm 输入，保证正文与随后 Enter 不会跨独立 Tauri 调用乱序；
   * 不同窗格保留各自队尾，因此仍可并发执行。
   */
  const sendInput = useCallback(
    async (paneId: string, data: string): Promise<void> => {
      if (closeAllAdmissionRef.current) return;
      const session = runtimesRef.current[paneId]?.session;
      if (session === undefined || runtimesRef.current[paneId]?.lifecycle !== "running") return;
      const bytes = new TextEncoder().encode(data);
      // xterm/IME 可能发布空 composition 边界；Rust 会拒绝零字节 PTY 写入，因此在 typed IPC 前丢弃，
      // 避免用合成输入错误污染仍然存活的代次。
      if (bytes.length === 0) return;
      const previous = inputTailsRef.current.get(paneId) ?? Promise.resolve();
      const operation = previous.then(async (): Promise<void> => {
        try {
          await adapterRef.current.input(session, bytes);
        } catch {
          const current = runtimesRef.current[paneId];
          if (
            current?.lifecycle === "running" &&
            current.session?.sessionId === session.sessionId &&
            current.session.generation === session.generation
          ) {
            updateRuntime(paneId, { lifecycle: "failed", error: "终端输入失败，请重启后重试" });
          }
        }
      });
      inputTailsRef.current.set(paneId, operation);
      void operation.finally(() => {
        if (inputTailsRef.current.get(paneId) === operation) inputTailsRef.current.delete(paneId);
      });
      await operation;
    },
    [updateRuntime],
  );

  /** 只为活动标签选中的运行窗格消费 native drop，并在 IPC 前再次核对组件命中结果。 */
  const dropNativePaths = useCallback(async (paneId: string, dropToken: string): Promise<void> => {
    const activeTab = layoutRef.current.tabs.find(
      (tab) => tab.tabId === layoutRef.current.activeTabId,
    );
    if (activeTab?.activePaneId !== paneId) return;
    const runtime = runtimesRef.current[paneId];
    if (runtime?.lifecycle !== "running" || runtime.session === undefined) return;
    await adapterRef.current.dropNativePaths(runtime.session, dropToken);
  }, []);

  /** 按窗格合并 resize 调用，防止拖拽或 IME 布局变化堆积过期尺寸。 */
  const resizeInFlightRef = useRef(new Map<string, { size: TerminalSize; running: boolean }>());
  const resizePane = useCallback(async (paneId: string, size: TerminalSize): Promise<void> => {
    const session = runtimesRef.current[paneId]?.session;
    if (session === undefined || runtimesRef.current[paneId]?.lifecycle !== "running") return;
    const slot = resizeInFlightRef.current.get(paneId) ?? { size, running: false };
    slot.size = {
      ...size,
      pixel_width: size.pixel_width ?? 0,
      pixel_height: size.pixel_height ?? 0,
    };
    resizeInFlightRef.current.set(paneId, slot);
    if (slot.running) return;
    slot.running = true;
    try {
      while (true) {
        const next = resizeInFlightRef.current.get(paneId);
        if (next === undefined) return;
        const before = next.size;
        await adapterRef.current.resize(session, before);
        const after = resizeInFlightRef.current.get(paneId)?.size;
        if (
          after === before ||
          (after !== undefined && after.cols === before.cols && after.rows === before.rows)
        )
          break;
      }
    } catch {
      // resize 竞态可恢复，下一次 FitAddon 测量会重新提交最新尺寸。
    } finally {
      resizeInFlightRef.current.delete(paneId);
    }
  }, []);

  /**
   * 以事务语义关闭窗格：先等待在途 open，原生 close 失败时保留精确 session 身份并向调用方抛错，
   * 防止布局删除或重启在资源尚未释放时伪报成功。
   */
  const closePane = useCallback(
    async (paneId: string): Promise<void> => {
      const existing = closingRef.current.get(paneId);
      if (existing !== undefined) return existing;
      const operation = (async (): Promise<void> => {
        const opening = openingRef.current.get(paneId);
        if (opening !== undefined) await opening;
        const session = runtimesRef.current[paneId]?.session;
        if (session === undefined) {
          updateRuntime(paneId, {
            lifecycle: "closed",
            session: undefined,
            outputs: undefined,
            pendingOutputBytes: 0,
            initialData: undefined,
            droppedBytes: undefined,
            error: undefined,
          });
          return;
        }
        updateRuntime(paneId, { lifecycle: "closing" });
        try {
          const inputTail = inputTailsRef.current.get(paneId);
          if (inputTail !== undefined) await inputTail;
          await adapterRef.current.close(session);
          updateRuntime(paneId, {
            lifecycle: "closed",
            session: undefined,
            outputs: undefined,
            pendingOutputBytes: 0,
            initialData: undefined,
            droppedBytes: undefined,
            error: undefined,
          });
        } catch (error) {
          updateRuntime(paneId, { lifecycle: "failed", error: "终端关闭失败，可重试" });
          throw error;
        }
      })();
      closingRef.current.set(paneId, operation);
      try {
        await operation;
      } finally {
        closingRef.current.delete(paneId);
      }
    },
    [updateRuntime],
  );

  /** 显式重启必须先关闭旧不透明代次，再打开全新 PTY。 */
  const restartPane = useCallback(
    async (paneId: string): Promise<void> => {
      const existing = restartingRef.current.get(paneId);
      if (existing !== undefined) return existing;
      const operation = (async (): Promise<void> => {
        await closePane(paneId);
        await ensurePaneOpen(paneId);
      })();
      restartingRef.current.set(paneId, operation);
      try {
        await operation;
      } finally {
        restartingRef.current.delete(paneId);
      }
    },
    [closePane, ensurePaneOpen],
  );

  const canAddTab =
    profilesStatus === "ready" &&
    profiles.length > 0 &&
    !closeAllFenced &&
    canAddTerminalTab(layout);

  /** 仅提交属于当前 Rust 探测闭集的休眠意图，活动窗格 effect 仍是唯一 PTY 启动入口。 */
  const addTab = useCallback(
    (options?: TerminalTabCreateOptions): boolean => {
      if (closeAllAdmissionRef.current || profilesStatusRef.current !== "ready") return false;
      const current = layoutRef.current;
      if (!canAddTerminalTab(current)) return false;
      const next = addTerminalTab(current, options, supportedProfilesRef.current);
      if (next === current) return false;
      commitLayout(next);
      return true;
    },
    [commitLayout],
  );

  /** 提交标签删除前关闭其全部成员，并合并重复关闭点击。 */
  const closeTab = useCallback(
    async (tabId: string): Promise<void> => {
      const existing = tabClosingRef.current.get(tabId);
      if (existing !== undefined) return existing;
      const tab = layoutRef.current.tabs.find((candidate) => candidate.tabId === tabId);
      if (tab === undefined) return;
      const operation = (async (): Promise<void> => {
        await Promise.all(flattenPaneIds(tab.root).map((paneId) => closePane(paneId)));
        commitLayout(removeTerminalTab(layoutRef.current, tabId));
      })();
      tabClosingRef.current.set(tabId, operation);
      try {
        await operation;
      } finally {
        tabClosingRef.current.delete(tabId);
      }
    },
    [closePane, commitLayout],
  );

  /** 仅在工作区仍接纳窗格时激活持久意图，防止 teardown 期间重新取得原生所有权。 */
  const activateTab = useCallback(
    (tabId: string): void => {
      if (closeAllAdmissionRef.current) return;
      const next = setActiveTerminalTab(layoutRef.current, tabId);
      commitLayout(next);
      const tab = next.tabs.find((candidate) => candidate.tabId === tabId);
      if (tab !== undefined)
        void Promise.all(flattenPaneIds(tab.root).map((paneId) => ensurePaneOpen(paneId)));
    },
    [commitLayout, ensurePaneOpen],
  );

  /** 在最新布局中维护焦点身份，同时拒绝失败 session 替换与 close-all 竞态。 */
  const activatePane = useCallback(
    (tabId: string, paneId: string): void => {
      if (closeAllAdmissionRef.current) return;
      commitLayout(setActiveTerminalPane(layoutRef.current, tabId, paneId));
      void ensurePaneOpen(paneId);
    },
    [commitLayout, ensurePaneOpen],
  );

  /** 分屏沿用标签 profile，因而也必须确认它仍属于 Rust 当前探测闭集。 */
  const canSplitPane = useCallback((tabId: string, paneId: string): boolean => {
    const tab = layoutRef.current.tabs.find((candidate) => candidate.tabId === tabId);
    return (
      profilesStatusRef.current === "ready" &&
      tab !== undefined &&
      supportedProfilesRef.current.includes(tab.profile) &&
      !closeAllAdmissionRef.current &&
      !tabClosingRef.current.has(tabId) &&
      canSplitTerminalPane(layoutRef.current, tabId, paneId)
    );
  }, []);

  /** 点击时重查当前布局，防止旧控件越过窗格预算。 */
  const splitPane = useCallback(
    (tabId: string, paneId: string, orientation: TerminalSplitOrientation): void => {
      if (!canSplitPane(tabId, paneId)) return;
      const next = splitTerminalPane(layoutRef.current, tabId, paneId, orientation);
      commitLayout(next);
      const tab = next.tabs.find((candidate) => candidate.tabId === tabId);
      if (tab !== undefined) {
        const paneIds = flattenPaneIds(tab.root);
        const fresh = paneIds.find(
          (candidate) => candidate !== paneId && runtimesRef.current[candidate] === undefined,
        );
        if (fresh !== undefined) void ensurePaneOpen(fresh);
      }
    },
    [canSplitPane, commitLayout, ensurePaneOpen],
  );

  /** 仅在窗格原生代次确认关闭后删除布局意图。 */
  const removePane = useCallback(
    async (tabId: string, paneId: string): Promise<void> => {
      await closePane(paneId);
      commitLayout(removeTerminalPane(layoutRef.current, tabId, paneId));
    },
    [closePane, commitLayout],
  );

  /** 对 ref 权威布局应用拖拽比例，防止高频 pointer 事件回退已提交结果。 */
  const setSplitRatio = useCallback(
    (tabId: string, splitId: string, ratio: number): void => {
      commitLayout(setTerminalSplitRatio(layoutRef.current, tabId, splitId, ratio));
    },
    [commitLayout],
  );

  /**
   * 先同步升起准入栅栏并等待全部已接纳 open，再请求 Rust 关闭整个工作区 PTY 作用域。
   * 原生 ACK 前保留运行身份，使失败时能恢复同一 session；成功后持续保持栅栏，直到 controller
   * 卸载或安装新工作区，避免 teardown 与重新打开交错。
   */
  const closeAll = useCallback(async (): Promise<void> => {
    const existing = closeAllRef.current;
    if (existing !== undefined) return existing;
    closeAllAdmissionRef.current = true;
    setCloseAllFenced(true);
    const currentWorkspace = workspaceRef.current;
    const operation = (async (): Promise<void> => {
      await Promise.allSettled([...openingRef.current.values()]);
      await Promise.allSettled([...inputTailsRef.current.values()]);
      try {
        await adapterRef.current.closeAll(currentWorkspace);
        const next: Record<string, TerminalPaneRuntime> = {};
        for (const paneId of Object.keys(runtimesRef.current))
          next[paneId] = { lifecycle: "closed" };
        runtimesRef.current = next;
        setRuntimes(next);
      } catch (error) {
        closeAllAdmissionRef.current = false;
        setCloseAllFenced(false);
        // 已接纳的 open 可能在栅栏升起后完成并跳过 poll loop；这里只恢复未变化的不透明代次，
        // 已有 loop 仍由 pollingRef 去重。
        for (const [paneId, runtime] of Object.entries(runtimesRef.current)) {
          if (runtime.lifecycle === "running" && runtime.session !== undefined) {
            startPolling(paneId, runtime.session);
          }
        }
        throw error;
      }
    })();
    closeAllRef.current = operation;
    try {
      await operation;
    } finally {
      closeAllRef.current = undefined;
    }
  }, [startPolling]);

  /** 工作区切换只在旧 PTY 所有权释放后提交布局，并复用已探测 profile 修复持久化值。 */
  useEffect(() => {
    const previousWorkspace = workspaceRef.current;
    if (previousWorkspace === workspaceId) return;
    switchFailedRef.current = false;
    switchPromiseRef.current = closeAll()
      .then(() => {
        workspaceRef.current = workspaceId;
        const nextLayout =
          profilesStatusRef.current === "ready"
            ? sanitizeTerminalLayout(
                initialLayoutRef.current,
                workspaceId,
                supportedProfilesRef.current,
              )
            : sanitizeTerminalLayout(initialLayoutRef.current, workspaceId);
        commitLayout(nextLayout);
        runtimesRef.current = {};
        setRuntimes({});
        closeAllAdmissionRef.current = false;
        setCloseAllFenced(false);
      })
      .catch(() => {
        switchFailedRef.current = true;
      });
  }, [closeAll, commitLayout, workspaceId]);

  /** 投影有界输出批次和确认回调，避免使用易被覆盖的单事件 prop。 */
  const terminalInputProps = useCallback(
    (paneId: string) => {
      const runtime = runtimes[paneId];
      return {
        ...(runtime?.initialData === undefined ? {} : { initialData: runtime.initialData }),
        ...(runtime?.outputs === undefined ? {} : { outputs: runtime.outputs }),
        onOutputsConsumed: (throughSequence: number | string): void =>
          acknowledgeOutputs(paneId, throughSequence),
        onData: (data: string): void => {
          void sendInput(paneId, data);
        },
        onResize: (size: { cols: number; rows: number }): void => {
          void resizePane(paneId, { ...size, pixel_width: 0, pixel_height: 0 });
        },
        ...(onOpenExternalUrl === undefined ? {} : { onOpenExternalUrl }),
        ...(onCopy === undefined ? {} : { onCopy }),
        ...(onPaste === undefined ? {} : { onPaste }),
      };
    },
    [acknowledgeOutputs, onCopy, onOpenExternalUrl, onPaste, resizePane, runtimes, sendInput],
  );

  return {
    layout,
    runtimes,
    profiles,
    profilesStatus,
    closeAllPending: closeAllFenced,
    canAddTab,
    canSplitPane,
    ensurePaneOpen,
    sendInput,
    dropNativePaths,
    resizePane,
    closePane,
    restartPane,
    addTab,
    closeTab,
    activateTab,
    activatePane,
    splitPane,
    removePane,
    setSplitRatio,
    closeAll,
    terminalInputProps,
  };
}

/** 按视觉顺序收集叶子 ID，供标签关闭和删除事务使用。 */
function flattenPaneIds(node: TerminalLayoutNode): string[] {
  if (node.kind === "pane") return [node.paneId];
  return [...flattenPaneIds(node.first), ...flattenPaneIds(node.second)];
}
