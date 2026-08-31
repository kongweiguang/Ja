// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  JA_APP_EXIT_COMMANDS,
  JA_APP_EXIT_EVENTS,
  invokeWindowAction,
  isCurrentWindowFocused,
  JA_NATIVE_SHORTCUT_COMMANDS,
  JA_NATIVE_SHORTCUT_EVENTS,
  observeAppExitRequested,
  observeWindowFocus,
  observeWindowFrameState,
  TauriNativeShortcutAdapter,
  type NativeShortcutContext,
  type NativeShortcutNativeBridge,
  type AppExitNativeBridge,
} from "@/api/tauri/window";

type ResizeHandler = () => void;
type FocusHandler = (event: { payload: boolean }) => void;

const nativeWindowListener = vi.hoisted(() => ({
  resize: undefined as ResizeHandler | undefined,
  focus: undefined as FocusHandler | undefined,
  unlisten: vi.fn(),
}));

const tauriWindow = vi.hoisted(() => ({
  minimize: vi.fn(() => Promise.resolve()),
  toggleMaximize: vi.fn(() => Promise.resolve()),
  hide: vi.fn(() => Promise.resolve()),
  isMaximized: vi.fn(() => Promise.resolve(false)),
  isFullscreen: vi.fn(() => Promise.resolve(false)),
  isMinimized: vi.fn(() => Promise.resolve(false)),
  isFocused: vi.fn(() => Promise.resolve(true)),
  onResized: vi.fn((handler: ResizeHandler) => {
    nativeWindowListener.resize = handler;
    return Promise.resolve(nativeWindowListener.unlisten);
  }),
  onFocusChanged: vi.fn((handler: FocusHandler) => {
    nativeWindowListener.focus = handler;
    return Promise.resolve(nativeWindowListener.unlisten);
  }),
}));
const getCurrentWindowMock = vi.hoisted(() => vi.fn(() => tauriWindow));
const NATIVE_EPOCH_A = "11111111-1111-4111-8111-111111111111";
const NATIVE_EPOCH_B = "22222222-2222-4222-8222-222222222222";

/** 暴露确定性 Promise 完成点，使 revision 串行化测试不依赖 sleep。 */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolvePromise!: (value: T) => void;
  const promise = new Promise<T>((resolve) => {
    resolvePromise = resolve;
  });
  return { promise, resolve: resolvePromise };
}

vi.mock("@tauri-apps/api/window", () => ({ getCurrentWindow: getCurrentWindowMock }));

describe("window adapter", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    nativeWindowListener.resize = undefined;
    nativeWindowListener.focus = undefined;
    tauriWindow.hide.mockReset().mockResolvedValue(undefined);
    tauriWindow.isMaximized.mockResolvedValue(false);
    tauriWindow.isFullscreen.mockResolvedValue(false);
    tauriWindow.isMinimized.mockResolvedValue(false);
    tauriWindow.isFocused.mockResolvedValue(true);
    getCurrentWindowMock.mockImplementation(() => tauriWindow);
  });

  it("routes every bounded action to the current native window", async () => {
    await invokeWindowAction("minimize");
    await invokeWindowAction("toggle-maximize");
    await invokeWindowAction("hide");
    expect(tauriWindow.minimize).toHaveBeenCalledOnce();
    expect(tauriWindow.toggleMaximize).toHaveBeenCalledOnce();
    expect(tauriWindow.hide).toHaveBeenCalledOnce();
  });

  it("redacts native lookup and asynchronous operation failures", async () => {
    tauriWindow.hide.mockRejectedValueOnce(new Error("window already closed at C:\\private"));
    await expect(invokeWindowAction("hide")).rejects.toThrow("native window action failed");
    getCurrentWindowMock.mockImplementationOnce(() => {
      throw new Error("browser host");
    });
    await expect(invokeWindowAction("minimize")).rejects.toThrow("native window action failed");
  });

  it("acknowledges the tray exit listener and routes commit, cancel, and disposal", async () => {
    let exitHandler: (() => void) | undefined;
    const unlisten = vi.fn();
    const bridge: AppExitNativeBridge = {
      invoke: vi.fn(async () => undefined),
      listen: vi.fn(async (_event, handler) => {
        exitHandler = handler;
        return unlisten;
      }),
    };
    const onRequest = vi.fn();
    const observer = observeAppExitRequested(onRequest, bridge);
    await vi.waitFor(() =>
      expect(bridge.invoke).toHaveBeenCalledWith(JA_APP_EXIT_COMMANDS.listenerReady, {}),
    );
    expect(bridge.listen).toHaveBeenCalledWith(JA_APP_EXIT_EVENTS.requested, expect.any(Function));

    exitHandler?.();
    expect(onRequest).toHaveBeenCalledOnce();
    const request = onRequest.mock.calls[0]?.[0];
    await request?.commit();
    await request?.cancel();
    expect(bridge.invoke).toHaveBeenCalledWith(JA_APP_EXIT_COMMANDS.commit, {});
    expect(bridge.invoke).toHaveBeenCalledWith(JA_APP_EXIT_COMMANDS.cancel, {});

    observer.dispose();
    expect(unlisten).toHaveBeenCalledOnce();
    await vi.waitFor(() =>
      expect(bridge.invoke).toHaveBeenCalledWith(JA_APP_EXIT_COMMANDS.listenerUnready, {}),
    );
  });

  it("reads native focus and suppresses background assumptions on unknown hosts", async () => {
    tauriWindow.isFocused.mockResolvedValueOnce(false);
    await expect(isCurrentWindowFocused()).resolves.toBe(false);
    getCurrentWindowMock.mockImplementationOnce(() => {
      throw new Error("browser host");
    });
    await expect(isCurrentWindowFocused()).resolves.toBe(true);
  });

  it("observes native focus transitions and releases the listener", async () => {
    const onChange = vi.fn();
    const observer = observeWindowFocus(onChange);
    await vi.waitFor(() => expect(nativeWindowListener.focus).toBeDefined());

    nativeWindowListener.focus?.({ payload: false });
    nativeWindowListener.focus?.({ payload: true });
    expect(onChange.mock.calls).toEqual([[false], [true]]);

    observer.dispose();
    expect(nativeWindowListener.unlisten).toHaveBeenCalledTimes(2);
  });

  it("reports a focused restore even when Windows omits a focus transition", async () => {
    const onChange = vi.fn();
    const observer = observeWindowFocus(onChange);
    await vi.waitFor(() => expect(nativeWindowListener.resize).toBeDefined());
    await vi.waitFor(() => expect(tauriWindow.isMinimized).toHaveBeenCalled());

    tauriWindow.isMinimized.mockResolvedValue(true);
    nativeWindowListener.resize?.();
    await vi.waitFor(() => expect(tauriWindow.isMinimized).toHaveBeenCalledTimes(2));
    tauriWindow.isMinimized.mockResolvedValue(false);
    nativeWindowListener.resize?.();

    await vi.waitFor(() => expect(onChange).toHaveBeenCalledWith(true));
    observer.dispose();
  });

  it("observes system-driven maximize and fullscreen changes and cleans up", async () => {
    const onChange = vi.fn();
    const observer = observeWindowFrameState(onChange);

    await vi.waitFor(() =>
      expect(onChange).toHaveBeenCalledWith({ maximized: false, fullscreen: false }),
    );
    tauriWindow.isMaximized.mockResolvedValue(true);
    tauriWindow.isFullscreen.mockResolvedValue(true);
    nativeWindowListener.resize?.();
    await vi.waitFor(() =>
      expect(onChange).toHaveBeenLastCalledWith({ maximized: true, fullscreen: true }),
    );

    observer.dispose();
    expect(nativeWindowListener.unlisten).toHaveBeenCalledOnce();
  });

  it("releases a resize listener that resolves after the observer was disposed", async () => {
    let resolveListener: ((unlisten: typeof nativeWindowListener.unlisten) => void) | undefined;
    tauriWindow.onResized.mockImplementationOnce((handler: ResizeHandler) => {
      nativeWindowListener.resize = handler;
      return new Promise((resolve) => {
        resolveListener = resolve;
      });
    });
    const observer = observeWindowFrameState(vi.fn());
    observer.dispose();
    resolveListener?.(nativeWindowListener.unlisten);
    await Promise.resolve();

    expect(nativeWindowListener.unlisten).toHaveBeenCalledOnce();
  });

  it("queries after both listeners and keeps only exact events through revocation ACK and unlisten", async () => {
    const handlers = new Map<string, (payload: unknown) => void>();
    const unlisteners = new Map<string, ReturnType<typeof vi.fn>>();
    const callOrder: string[] = [];
    let rustRevision = 6;
    let rustReady = false;
    let rustContext:
      | {
          epoch: string;
          revision: number;
          projectCapabilitiesEnabled: boolean;
          conversationFocusEnabled: boolean;
        }
      | undefined;
    /** mock 严格复现 query→prepare(false)→activate(true)，而不是把 prepare 当激活 ACK。 */
    const invoke = vi.fn(async (command: string, args: Record<string, unknown>) => {
      callOrder.push(command);
      if (command === JA_NATIVE_SHORTCUT_COMMANDS.leaseQuery) {
        rustReady = false;
        return {
          epoch: NATIVE_EPOCH_A,
          revision: rustRevision,
          ready: rustReady,
          mainHandlerStatus: "ready",
        };
      }
      if (command === JA_NATIVE_SHORTCUT_COMMANDS.contextUpdate) {
        rustContext = args["input"] as typeof rustContext;
        if (rustContext === undefined) throw new Error("missing prepared context");
        rustRevision = rustContext.revision;
        rustReady = false;
        return { ...rustContext, ready: false, mainHandlerStatus: "ready" };
      }
      const activation = args["input"] as { epoch: string; revision: number };
      if (rustContext === undefined) throw new Error("activation without prepare");
      rustReady = true;
      return { ...rustContext, ...activation, ready: true, mainHandlerStatus: "ready" };
    });
    const bridge: NativeShortcutNativeBridge = {
      invoke,
      /** 保留原始 handler，以确定性重放 malformed、stale 与 late payload。 */
      listen: vi.fn(async (event, handler) => {
        callOrder.push(event);
        handlers.set(event, handler);
        const unlisten = vi.fn();
        unlisteners.set(event, unlisten);
        return unlisten;
      }),
    };
    const adapter = new TauriNativeShortcutAdapter(bridge);
    const commands: string[] = [];
    const statuses: string[] = [];
    const unsubscribe = await adapter.subscribe({
      onCommand: (command) => commands.push(command),
      onStatus: (status) => statuses.push(status),
    });
    expect(callOrder).toEqual([
      JA_NATIVE_SHORTCUT_EVENTS.command,
      JA_NATIVE_SHORTCUT_EVENTS.status,
      JA_NATIVE_SHORTCUT_COMMANDS.leaseQuery,
    ]);
    expect(invoke).toHaveBeenCalledWith(JA_NATIVE_SHORTCUT_COMMANDS.leaseQuery, {});

    const first = await adapter.updateContext({
      projectCapabilitiesEnabled: false,
      conversationFocusEnabled: true,
    });
    expect(first).toMatchObject({ epoch: NATIVE_EPOCH_A, revision: 7, ready: true });
    handlers.get(JA_NATIVE_SHORTCUT_EVENTS.command)?.({
      epoch: NATIVE_EPOCH_A,
      command: "review",
      revision: 7,
    });
    handlers.get(JA_NATIVE_SHORTCUT_EVENTS.command)?.({
      epoch: NATIVE_EPOCH_A,
      command: "side_chat",
      revision: 7,
    });
    expect(commands).toEqual(["side_chat"]);

    const second = await adapter.updateContext({
      projectCapabilitiesEnabled: true,
      conversationFocusEnabled: true,
    });
    expect(second.revision).toBe(8);
    handlers.get(JA_NATIVE_SHORTCUT_EVENTS.command)?.({
      epoch: NATIVE_EPOCH_A,
      command: "files",
      revision: 7,
    });
    handlers.get(JA_NATIVE_SHORTCUT_EVENTS.command)?.({
      epoch: NATIVE_EPOCH_B,
      command: "files",
      revision: 8,
    });
    handlers.get(JA_NATIVE_SHORTCUT_EVENTS.command)?.({
      epoch: NATIVE_EPOCH_A,
      command: "unknown",
      revision: 8,
    });
    handlers.get(JA_NATIVE_SHORTCUT_EVENTS.command)?.({
      epoch: NATIVE_EPOCH_A,
      command: "terminal",
      revision: 8,
      extra: true,
    });
    handlers.get(JA_NATIVE_SHORTCUT_EVENTS.command)?.({
      epoch: NATIVE_EPOCH_A,
      command: "preview",
      revision: 8,
    });
    expect(commands).toEqual(["side_chat", "preview"]);
    expect(
      invoke.mock.calls
        .filter(([command]) => command === JA_NATIVE_SHORTCUT_COMMANDS.contextUpdate)
        .map(([command, args]) => ({ command, input: args["input"] })),
    ).toEqual([
      {
        command: JA_NATIVE_SHORTCUT_COMMANDS.contextUpdate,
        input: {
          epoch: NATIVE_EPOCH_A,
          revision: 7,
          projectCapabilitiesEnabled: false,
          conversationFocusEnabled: true,
        },
      },
      {
        command: JA_NATIVE_SHORTCUT_COMMANDS.contextUpdate,
        input: {
          epoch: NATIVE_EPOCH_A,
          revision: 8,
          projectCapabilitiesEnabled: true,
          conversationFocusEnabled: true,
        },
      },
    ]);
    expect(
      invoke.mock.calls
        .filter(([command]) => command === JA_NATIVE_SHORTCUT_COMMANDS.contextActivate)
        .map(([command, args]) => ({ command, input: args["input"] })),
    ).toEqual([
      {
        command: JA_NATIVE_SHORTCUT_COMMANDS.contextActivate,
        input: { epoch: NATIVE_EPOCH_A, revision: 7 },
      },
      {
        command: JA_NATIVE_SHORTCUT_COMMANDS.contextActivate,
        input: { epoch: NATIVE_EPOCH_A, revision: 8 },
      },
    ]);

    handlers.get(JA_NATIVE_SHORTCUT_EVENTS.command)?.({
      epoch: NATIVE_EPOCH_A,
      command: "review",
      revision: 8,
    });
    handlers.get(JA_NATIVE_SHORTCUT_EVENTS.status)?.({ status: "unavailable" });
    rustReady = false;
    handlers.get(JA_NATIVE_SHORTCUT_EVENTS.status)?.("unavailable");
    // backend revocation 前已取得 identity 的 callback 可以晚到；ACK 前 listener 必须仍消费。
    handlers.get(JA_NATIVE_SHORTCUT_EVENTS.command)?.({
      epoch: NATIVE_EPOCH_A,
      command: "files",
      revision: 8,
    });
    expect(commands).toEqual(["side_chat", "preview", "review", "files"]);
    expect(statuses).toEqual(["unavailable"]);
    await vi.waitFor(() =>
      expect(
        invoke.mock.calls.filter(([command]) => command === JA_NATIVE_SHORTCUT_COMMANDS.leaseQuery),
      ).toHaveLength(4),
    );
    await Promise.resolve();
    handlers.get(JA_NATIVE_SHORTCUT_EVENTS.command)?.({
      epoch: NATIVE_EPOCH_A,
      command: "terminal",
      revision: 8,
    });
    expect(commands).toEqual(["side_chat", "preview", "review", "files"]);

    unsubscribe();
    unsubscribe();
    await vi.waitFor(() =>
      expect(unlisteners.get(JA_NATIVE_SHORTCUT_EVENTS.command)).toHaveBeenCalledOnce(),
    );
    expect(unlisteners.get(JA_NATIVE_SHORTCUT_EVENTS.status)).toHaveBeenCalledOnce();
    handlers.get(JA_NATIVE_SHORTCUT_EVENTS.command)?.({
      epoch: NATIVE_EPOCH_A,
      command: "review",
      revision: 8,
    });
    expect(commands).toEqual(["side_chat", "preview", "review", "files"]);
    expect(rustReady).toBe(false);
    expect(invoke).toHaveBeenLastCalledWith(JA_NATIVE_SHORTCUT_COMMANDS.leaseQuery, {});
  });

  it("keeps the exact listener until unsubscribe suspend ACK and gates a rapid resubscribe", async () => {
    const pendingSuspend = deferred<unknown>();
    const listeners = new Map<string, Array<(payload: unknown) => void>>();
    const unlisteners: Array<ReturnType<typeof vi.fn>> = [];
    let leaseCalls = 0;
    let rustRevision = 0;
    let preparedContext: Record<string, unknown> | undefined;
    const bridge: NativeShortcutNativeBridge = {
      invoke: vi.fn(async (command, args) => {
        if (command === JA_NATIVE_SHORTCUT_COMMANDS.leaseQuery) {
          leaseCalls += 1;
          if (leaseCalls === 3) return pendingSuspend.promise;
          return {
            epoch: NATIVE_EPOCH_A,
            revision: rustRevision,
            ready: false,
            mainHandlerStatus: "ready",
          };
        }
        if (command === JA_NATIVE_SHORTCUT_COMMANDS.contextUpdate) {
          preparedContext = args["input"] as Record<string, unknown>;
          rustRevision = preparedContext["revision"] as number;
          return { ...preparedContext, ready: false, mainHandlerStatus: "ready" };
        }
        if (preparedContext === undefined) throw new Error("activation without prepare");
        return { ...preparedContext, ready: true, mainHandlerStatus: "ready" };
      }),
      /** 分代保留 handler，验证第二次 subscribe 不会在旧 suspend ACK 前抢占 identity。 */
      listen: vi.fn(async (event, handler) => {
        const eventListeners = listeners.get(event) ?? [];
        eventListeners.push(handler);
        listeners.set(event, eventListeners);
        const unlisten = vi.fn();
        unlisteners.push(unlisten);
        return unlisten;
      }),
    };
    const adapter = new TauriNativeShortcutAdapter(bridge);
    const firstCommands: string[] = [];
    const firstUnsubscribe = await adapter.subscribe({
      onCommand: (command) => firstCommands.push(command),
    });
    await adapter.updateContext({
      projectCapabilitiesEnabled: true,
      conversationFocusEnabled: false,
    });
    const firstCommandListener = listeners.get(JA_NATIVE_SHORTCUT_EVENTS.command)?.[0];
    expect(firstCommandListener).toBeDefined();

    firstUnsubscribe();
    const secondSubscription = adapter.subscribe({ onCommand: vi.fn() });
    await vi.waitFor(() => expect(leaseCalls).toBe(3));
    firstCommandListener?.({ epoch: NATIVE_EPOCH_A, revision: 1, command: "review" });
    expect(firstCommands).toEqual(["review"]);
    expect(bridge.listen).toHaveBeenCalledTimes(2);
    expect(unlisteners[0]).not.toHaveBeenCalled();

    pendingSuspend.resolve({
      epoch: NATIVE_EPOCH_A,
      revision: 1,
      ready: false,
      mainHandlerStatus: "ready",
    });
    const secondUnsubscribe = await secondSubscription;
    expect(bridge.listen).toHaveBeenCalledTimes(4);
    expect(unlisteners[0]).toHaveBeenCalledOnce();
    expect(unlisteners[1]).toHaveBeenCalledOnce();
    firstCommandListener?.({ epoch: NATIVE_EPOCH_A, revision: 1, command: "review" });
    expect(firstCommands).toEqual(["review"]);

    secondUnsubscribe();
    await vi.waitFor(() => expect(unlisteners[2]).toHaveBeenCalledOnce());
    expect(unlisteners[3]).toHaveBeenCalledOnce();
  });

  it("rebases a fresh renderer from the queried epoch and serializes concurrent context revisions", async () => {
    const firstUpdate = deferred<unknown>();
    const inputs: Array<Record<string, unknown>> = [];
    let contextCalls = 0;
    let rustRevision = 41;
    let preparedContext: Record<string, unknown> | undefined;
    const bridge: NativeShortcutNativeBridge = {
      /** 返回 reload 后的非零 revision，证明 renderer 不再从本地 1 猜身份。 */
      invoke: vi.fn(async (command, args) => {
        if (command === JA_NATIVE_SHORTCUT_COMMANDS.leaseQuery) {
          return {
            epoch: NATIVE_EPOCH_B,
            revision: rustRevision,
            ready: false,
            mainHandlerStatus: "ready",
          };
        }
        if (command === JA_NATIVE_SHORTCUT_COMMANDS.contextUpdate) {
          const input = args["input"] as Record<string, unknown>;
          preparedContext = input;
          inputs.push(input);
          rustRevision = input["revision"] as number;
          contextCalls += 1;
          if (contextCalls === 1) return firstUpdate.promise;
          return { ...input, ready: false, mainHandlerStatus: "ready" };
        }
        if (preparedContext === undefined) throw new Error("activation without prepare");
        return { ...preparedContext, ready: true, mainHandlerStatus: "ready" };
      }),
      listen: vi.fn(async () => vi.fn()),
    };
    const adapter = new TauriNativeShortcutAdapter(bridge);
    await adapter.subscribe({ onCommand: vi.fn() });

    const first = adapter.updateContext({
      projectCapabilitiesEnabled: true,
      conversationFocusEnabled: false,
    });
    const second = adapter.updateContext({
      projectCapabilitiesEnabled: false,
      conversationFocusEnabled: true,
    });
    await vi.waitFor(() => expect(inputs).toHaveLength(1));
    expect(inputs[0]).toMatchObject({
      epoch: NATIVE_EPOCH_B,
      revision: 42,
      projectCapabilitiesEnabled: false,
      conversationFocusEnabled: true,
    });
    firstUpdate.resolve({ ...inputs[0], ready: false, mainHandlerStatus: "ready" });
    await expect(first).resolves.toMatchObject({ epoch: NATIVE_EPOCH_B, revision: 42 });
    await expect(second).resolves.toMatchObject({ epoch: NATIVE_EPOCH_B, revision: 43 });
    expect(inputs[1]).toMatchObject({ epoch: NATIVE_EPOCH_B, revision: 43 });
  });

  it("handles an event before a lost activation ACK, then suspends, rebases, and retries once", async () => {
    const handlers = new Map<string, (payload: unknown) => void>();
    const inputs: Array<Record<string, unknown>> = [];
    let rustRevision = 0;
    let activationCalls = 0;
    let preparedContext: Record<string, unknown> | undefined;
    const bridge: NativeShortcutNativeBridge = {
      /** 首个 activate 已落 Rust但丢 ACK；listener 必须已预置 rev1，第二次 query 再 suspend。 */
      invoke: vi.fn(async (command, args) => {
        if (command === JA_NATIVE_SHORTCUT_COMMANDS.leaseQuery) {
          return {
            epoch: NATIVE_EPOCH_A,
            revision: rustRevision,
            ready: false,
            mainHandlerStatus: "ready",
          };
        }
        if (command === JA_NATIVE_SHORTCUT_COMMANDS.contextUpdate) {
          const input = args["input"] as Record<string, unknown>;
          preparedContext = input;
          inputs.push(input);
          rustRevision = input["revision"] as number;
          return { ...input, ready: false, mainHandlerStatus: "ready" };
        }
        if (preparedContext === undefined) throw new Error("activation without prepare");
        activationCalls += 1;
        if (activationCalls === 1) {
          handlers.get(JA_NATIVE_SHORTCUT_EVENTS.command)?.({
            epoch: NATIVE_EPOCH_A,
            revision: 1,
            command: "review",
          });
          throw new Error("ACK lost at C:\\private");
        }
        return { ...preparedContext, ready: true, mainHandlerStatus: "ready" };
      }),
      listen: vi.fn(async (event, handler) => {
        handlers.set(event, handler);
        return vi.fn();
      }),
    };
    const adapter = new TauriNativeShortcutAdapter(bridge);
    const commands: string[] = [];
    await adapter.subscribe({ onCommand: (command) => commands.push(command) });

    await expect(
      adapter.updateContext({ projectCapabilitiesEnabled: true, conversationFocusEnabled: false }),
    ).resolves.toMatchObject({ revision: 2, ready: true });
    expect(inputs).toEqual([
      {
        epoch: NATIVE_EPOCH_A,
        revision: 1,
        projectCapabilitiesEnabled: true,
        conversationFocusEnabled: false,
      },
      {
        epoch: NATIVE_EPOCH_A,
        revision: 2,
        projectCapabilitiesEnabled: true,
        conversationFocusEnabled: false,
      },
    ]);
    expect(commands).toEqual(["review"]);
    handlers.get(JA_NATIVE_SHORTCUT_EVENTS.command)?.({
      epoch: NATIVE_EPOCH_A,
      revision: 1,
      command: "review",
    });
    handlers.get(JA_NATIVE_SHORTCUT_EVENTS.command)?.({
      epoch: NATIVE_EPOCH_A,
      revision: 2,
      command: "review",
    });
    expect(commands).toEqual(["review", "review"]);
  });

  it("does not let a late lease query overwrite an unavailable status received during setup", async () => {
    const pendingLease = deferred<unknown>();
    const handlers = new Map<string, (payload: unknown) => void>();
    const unlisteners = new Map<string, ReturnType<typeof vi.fn>>();
    const bridge: NativeShortcutNativeBridge = {
      invoke: vi.fn(async () => pendingLease.promise),
      /** 暴露 setup 期 status，使 query 与 unavailable 的顺序可确定复现。 */
      listen: vi.fn(async (event, handler) => {
        handlers.set(event, handler);
        const unlisten = vi.fn();
        unlisteners.set(event, unlisten);
        return unlisten;
      }),
    };
    const adapter = new TauriNativeShortcutAdapter(bridge);
    const statuses: string[] = [];
    const subscription = adapter.subscribe({
      onCommand: vi.fn(),
      onStatus: (status) => statuses.push(status),
    });
    await vi.waitFor(() => expect(handlers.size).toBe(2));
    handlers.get(JA_NATIVE_SHORTCUT_EVENTS.status)?.("unavailable");
    pendingLease.resolve({
      epoch: NATIVE_EPOCH_A,
      revision: 0,
      ready: false,
      mainHandlerStatus: "unavailable",
    });

    await expect(subscription).rejects.toMatchObject({ code: "subscription_failed" });
    expect(statuses).toEqual(["unavailable"]);
    expect(unlisteners.get(JA_NATIVE_SHORTCUT_EVENTS.command)).toHaveBeenCalledOnce();
    expect(unlisteners.get(JA_NATIVE_SHORTCUT_EVENTS.status)).toHaveBeenCalledOnce();
    await expect(
      adapter.updateContext({ projectCapabilitiesEnabled: true, conversationFocusEnabled: true }),
    ).rejects.toMatchObject({ code: "command_failed" });
  });

  it("fails lease queries, context updates, and partial subscriptions with redacted errors and no active identity", async () => {
    const commandUnlisten = vi.fn();
    const partialBridge: NativeShortcutNativeBridge = {
      invoke: vi.fn(),
      listen: vi.fn(async (event, handler) => {
        if (event === JA_NATIVE_SHORTCUT_EVENTS.status)
          throw new Error("COM failure at C:\\private");
        void handler;
        return commandUnlisten;
      }),
    };
    const partialAdapter = new TauriNativeShortcutAdapter(partialBridge);

    await expect(partialAdapter.subscribe({ onCommand: vi.fn() })).rejects.toMatchObject({
      code: "subscription_failed",
      message: "原生快捷键监听失败",
    });
    expect(commandUnlisten).toHaveBeenCalledOnce();
    expect(partialBridge.invoke).toHaveBeenCalledTimes(2);
    expect(partialBridge.invoke).toHaveBeenNthCalledWith(
      1,
      JA_NATIVE_SHORTCUT_COMMANDS.leaseQuery,
      {},
    );
    expect(partialBridge.invoke).toHaveBeenNthCalledWith(
      2,
      JA_NATIVE_SHORTCUT_COMMANDS.leaseQuery,
      {},
    );
    await expect(
      partialAdapter.updateContext({
        projectCapabilitiesEnabled: true,
        conversationFocusEnabled: true,
      }),
    ).rejects.toMatchObject({ code: "command_failed", message: "原生快捷键更新失败" });
    await expect(
      partialAdapter.updateContext({
        projectCapabilitiesEnabled: true,
        conversationFocusEnabled: true,
        extra: true,
      } as NativeShortcutContext),
    ).rejects.toMatchObject({ code: "invalid_input", message: "原生快捷键上下文无效" });

    const invalidLeaseAdapter = new TauriNativeShortcutAdapter({
      invoke: vi.fn(async () => ({
        epoch: NATIVE_EPOCH_A,
        revision: 0,
        ready: false,
        mainHandlerStatus: "ready",
        privatePath: "C:\\private",
      })),
      listen: vi.fn(async () => vi.fn()),
    });
    await expect(invalidLeaseAdapter.subscribe({ onCommand: vi.fn() })).rejects.toMatchObject({
      code: "subscription_failed",
      message: "原生快捷键监听失败",
    });

    const malformedContextBridge: NativeShortcutNativeBridge = {
      /** 首次 query 合法，随后 context ACK 故意夹带未知字段。 */
      invoke: vi.fn(async (command, args) =>
        command === JA_NATIVE_SHORTCUT_COMMANDS.leaseQuery
          ? { epoch: NATIVE_EPOCH_A, revision: 0, ready: false, mainHandlerStatus: "ready" }
          : {
              ...(args["input"] as Record<string, unknown>),
              ready: true,
              mainHandlerStatus: "ready",
              privatePath: "C:\\private",
            },
      ),
      listen: vi.fn(async () => vi.fn()),
    };
    const malformedContextAdapter = new TauriNativeShortcutAdapter(malformedContextBridge);
    await malformedContextAdapter.subscribe({ onCommand: vi.fn() });
    const failure = await malformedContextAdapter
      .updateContext({ projectCapabilitiesEnabled: false, conversationFocusEnabled: false })
      .catch((error: unknown) => error);
    expect(failure).toMatchObject({ code: "invalid_response", message: "原生快捷键返回无效" });
    expect(JSON.stringify(failure)).not.toContain("private");
  });
});
