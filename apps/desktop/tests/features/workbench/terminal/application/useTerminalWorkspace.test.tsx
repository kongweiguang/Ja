// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { addTerminalTab, createDefaultTerminalLayout } from "@/features/workbench/terminal/domain";
import {
  useTerminalWorkspace,
  type TerminalEvent,
  type TerminalSessionInfo,
  type TerminalWorkspaceAdapter,
} from "@/features/workbench/terminal/application";

const SESSION: TerminalSessionInfo = {
  sessionId: "11111111-1111-4111-8111-111111111111",
  generation: 1,
};
const NEXT_SESSION: TerminalSessionInfo = {
  sessionId: "22222222-2222-4222-8222-222222222222",
  generation: 2,
};

/** 暴露确定性的竞态栅栏，测试不依赖墙钟等待或任意 sleep。 */
function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (error: unknown) => void;
} {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

/** 提供固定 profile 与有界假桥，确保生命周期测试不会启动真实 shell。 */
function fakeAdapter(overrides: Partial<TerminalWorkspaceAdapter> = {}): TerminalWorkspaceAdapter {
  return {
    profiles: vi.fn(async () => ["default", "power_shell", "cmd", "bash", "zsh", "fish"] as const),
    open: vi.fn(async () => SESSION),
    dropNativePaths: vi.fn(async () => undefined),
    input: vi.fn(async () => undefined),
    resize: vi.fn(async () => undefined),
    poll: vi.fn(async (): Promise<TerminalEvent | null> => new Promise(() => undefined)),
    scrollback: vi.fn(async () => Uint8Array.from([0x1b, 0x5b, 0x30, 0x6d])),
    close: vi.fn(async () => undefined),
    closeAll: vi.fn(async () => undefined),
    subscribeNativeDrop: vi.fn(async () => () => undefined),
    ...overrides,
  };
}

describe("useTerminalWorkspace", () => {
  it("opens a dormant pane once, preserves output bytes, and does not close on tab switch", async () => {
    const adapter = fakeAdapter();
    const initial = createDefaultTerminalLayout("ws_fixture");
    const { result } = renderHook(() =>
      useTerminalWorkspace({ workspaceId: "ws_fixture", adapter, initialLayout: initial }),
    );
    const paneId = initial.tabs[0]!.activePaneId;
    await act(async () => {
      await result.current.ensurePaneOpen(paneId);
    });
    expect(adapter.open).toHaveBeenCalledWith(
      expect.objectContaining({
        workspaceId: "ws_fixture",
        profile: "default",
        size: expect.any(Object),
      }),
    );
    expect(result.current.runtimes[paneId]?.lifecycle).toBe("running");
    act(() => result.current.addTab());
    expect(adapter.close).not.toHaveBeenCalled();
    await act(async () => {
      await result.current.closeAll();
    });
    expect(adapter.closeAll).toHaveBeenCalledWith("ws_fixture");
    expect(result.current.runtimes[paneId]?.lifecycle).toBe("closed");
  });

  /** Rust 返回 Windows 闭集后，持久化 Bash 会先修复并落盘，随后 open 只能收到 supported fallback。 */
  it("repairs an unsupported persisted profile before opening the pane", async () => {
    const profiles = vi.fn(async () => ["default", "power_shell", "cmd"] as const);
    const adapter = fakeAdapter({ profiles });
    const onLayoutChange = vi.fn();
    const initial = createDefaultTerminalLayout("ws_fixture", "bash");
    const paneId = initial.tabs[0]!.activePaneId;
    const { result } = renderHook(() =>
      useTerminalWorkspace({
        workspaceId: "ws_fixture",
        adapter,
        initialLayout: initial,
        onLayoutChange,
      }),
    );

    await waitFor(() => expect(result.current.profilesStatus).toBe("ready"));
    expect(result.current.profiles).toEqual(["default", "power_shell", "cmd"]);
    expect(result.current.layout.tabs[0]?.profile).toBe("default");
    expect(onLayoutChange).toHaveBeenLastCalledWith(
      expect.objectContaining({
        tabs: [expect.objectContaining({ profile: "default" })],
      }),
    );

    await act(async () => {
      await result.current.ensurePaneOpen(paneId);
    });
    expect(adapter.open).toHaveBeenCalledWith(expect.objectContaining({ profile: "default" }));
    expect(adapter.open).not.toHaveBeenCalledWith(expect.objectContaining({ profile: "bash" }));
  });

  /** runtime-bearing 输入直接回到新默认值，但读取过程不得主动覆盖介质。 */
  it("defaults rejected runtime-bearing persistence without a read-time rewrite", async () => {
    const adapter = fakeAdapter();
    const clean = createDefaultTerminalLayout("ws_fixture");
    const firstTab = clean.tabs[0]!;
    const legacy = {
      ...clean,
      environment: { SECRET: "top-level-secret" },
      tabs: [
        {
          ...firstTab,
          title: "旧终端标题",
          profile: "bash",
          sessionId: "legacy-session",
          command: "echo secret",
          environment: { TOKEN: "legacy-token" },
          root: {
            ...firstTab.root,
            session_id: "legacy-session-snake",
            generation: 42,
            scrollback: "legacy-scrollback",
            outputs: ["legacy-output"],
          },
        },
      ],
    };
    const onLayoutChange = vi.fn();
    const { result } = renderHook(() =>
      useTerminalWorkspace({
        workspaceId: "ws_fixture",
        adapter,
        initialLayout: legacy,
        onLayoutChange,
      }),
    );

    await waitFor(() => expect(result.current.profilesStatus).toBe("ready"));
    expect(result.current.layout).toEqual(clean);
    expect(onLayoutChange).not.toHaveBeenCalled();
    expect(result.current.runtimes).toEqual({});
    expect(adapter.open).not.toHaveBeenCalled();

    act(() => {
      expect(result.current.addTab()).toBe(true);
    });
    expect(onLayoutChange).toHaveBeenCalledTimes(1);
    expect(JSON.stringify(onLayoutChange.mock.calls[0]?.[0])).toEqual(
      expect.not.stringContaining("legacy-"),
    );
  });

  /** close-all 失败时保留同一原生代次并恢复轮询，避免丢失唯一可重试身份。 */
  it("fences terminal admission until in-flight opens settle and resumes the same session for an in-place retry", async () => {
    const openGate = deferred<TerminalSessionInfo>();
    const firstCloseGate = deferred<void>();
    const closeAll = vi
      .fn()
      .mockImplementationOnce(async () => firstCloseGate.promise)
      .mockResolvedValueOnce(undefined);
    const adapter = fakeAdapter({
      open: vi.fn(async () => openGate.promise),
      closeAll,
    });
    const initial = addTerminalTab(createDefaultTerminalLayout("ws_fixture"));
    const activeTab = initial.tabs.find((tab) => tab.tabId === initial.activeTabId)!;
    const otherTab = initial.tabs.find((tab) => tab.tabId !== initial.activeTabId)!;
    const { result } = renderHook(() =>
      useTerminalWorkspace({ workspaceId: "ws_fixture", adapter, initialLayout: initial }),
    );
    let opening!: Promise<void>;
    act(() => {
      opening = result.current.ensurePaneOpen(activeTab.activePaneId);
    });
    await waitFor(() => expect(adapter.open).toHaveBeenCalledOnce());

    let closing!: Promise<void>;
    let closeOutcome!: Promise<unknown>;
    act(() => {
      closing = result.current.closeAll();
      closeOutcome = closing.then(
        () => undefined,
        (error: unknown) => error,
      );
    });
    expect(closeAll).not.toHaveBeenCalled();
    const fencedLayout = result.current.layout;
    act(() => {
      expect(result.current.addTab()).toBe(false);
      result.current.splitPane(activeTab.tabId, activeTab.activePaneId, "horizontal");
      result.current.activateTab(otherTab.tabId);
      result.current.activatePane(otherTab.tabId, otherTab.activePaneId);
    });
    await act(async () => {
      await result.current.ensurePaneOpen(otherTab.activePaneId);
    });
    expect(result.current.layout).toBe(fencedLayout);
    expect(adapter.open).toHaveBeenCalledOnce();

    await act(async () => {
      openGate.resolve(SESSION);
      await opening;
    });
    await waitFor(() => expect(closeAll).toHaveBeenCalledOnce());
    await act(async () => {
      firstCloseGate.reject(new Error("close all failed"));
      expect(await closeOutcome).toBeInstanceOf(Error);
    });
    expect(result.current.runtimes[activeTab.activePaneId]).toMatchObject({
      lifecycle: "running",
      session: SESSION,
      error: undefined,
    });
    expect(adapter.poll).toHaveBeenCalledWith(SESSION, 500);
    act(() => {
      expect(result.current.addTab()).toBe(true);
    });

    await act(async () => {
      await result.current.closeAll();
    });
    expect(closeAll).toHaveBeenCalledTimes(2);
    act(() => {
      expect(result.current.addTab()).toBe(false);
    });
  });

  /** xterm 使用 void callback 边界，原生输入失败必须投影稳定重试态后再结束异步操作。 */
  it("absorbs native input rejection and projects a stable pane error", async () => {
    const input = vi.fn(async () => {
      throw new Error("native path leaked C:\\private\\workspace");
    });
    const adapter = fakeAdapter({ input });
    const initial = createDefaultTerminalLayout("ws_fixture");
    const paneId = initial.tabs[0]!.activePaneId;
    const { result } = renderHook(() =>
      useTerminalWorkspace({ workspaceId: "ws_fixture", adapter, initialLayout: initial }),
    );
    await act(async () => {
      await result.current.ensurePaneOpen(paneId);
    });

    await act(async () => {
      await expect(result.current.sendInput(paneId, "echo hello\r")).resolves.toBeUndefined();
    });

    expect(input).toHaveBeenCalledOnce();
    expect(result.current.runtimes[paneId]).toMatchObject({
      lifecycle: "failed",
      session: SESSION,
      error: "终端输入失败，请重启后重试",
    });
    expect(JSON.stringify(result.current.runtimes[paneId])).not.toContain("private\\workspace");
  });

  /** 空 IME composition 边界不是 PTY 写入，必须保持存活代次不变。 */
  it("ignores empty xterm composition input without failing the pane", async () => {
    const adapter = fakeAdapter();
    const initial = createDefaultTerminalLayout("ws_fixture");
    const paneId = initial.tabs[0]!.activePaneId;
    const { result } = renderHook(() =>
      useTerminalWorkspace({ workspaceId: "ws_fixture", adapter, initialLayout: initial }),
    );
    await act(async () => {
      await result.current.ensurePaneOpen(paneId);
    });

    await act(async () => {
      await result.current.sendInput(paneId, "");
    });

    expect(adapter.input).not.toHaveBeenCalled();
    expect(result.current.runtimes[paneId]).toMatchObject({
      lifecycle: "running",
      session: SESSION,
      error: undefined,
    });
  });

  /** 同一窗格的正文和 Enter 保持 FIFO，不同窗格继续使用各自原生通道。 */
  it("serializes accepted input per pane without blocking another pane", async () => {
    const firstInputGate = deferred<void>();
    const order: string[] = [];
    const sessions = [SESSION, NEXT_SESSION];
    const input = vi.fn(async (session: TerminalSessionInfo, data: Uint8Array) => {
      const text = new TextDecoder().decode(data);
      order.push(`start:${session.sessionId}:${text}`);
      if (session.sessionId === SESSION.sessionId && text === "echo marker")
        await firstInputGate.promise;
      order.push(`finish:${session.sessionId}:${text}`);
    });
    const adapter = fakeAdapter({
      open: vi.fn(async () => sessions.shift()!),
      input,
    });
    const initial = addTerminalTab(createDefaultTerminalLayout("ws_fixture"));
    const firstPaneId = initial.tabs[0]!.activePaneId;
    const secondPaneId = initial.tabs[1]!.activePaneId;
    const { result } = renderHook(() =>
      useTerminalWorkspace({ workspaceId: "ws_fixture", adapter, initialLayout: initial }),
    );
    await act(async () => {
      await result.current.ensurePaneOpen(firstPaneId);
      await result.current.ensurePaneOpen(secondPaneId);
    });

    act(() => {
      result.current.terminalInputProps(firstPaneId).onData("echo marker");
      result.current.terminalInputProps(firstPaneId).onData("\r");
      result.current.terminalInputProps(secondPaneId).onData("pwd");
    });
    await waitFor(() => expect(input).toHaveBeenCalledTimes(2));
    expect(order).toEqual([
      `start:${SESSION.sessionId}:echo marker`,
      `start:${NEXT_SESSION.sessionId}:pwd`,
      `finish:${NEXT_SESSION.sessionId}:pwd`,
    ]);

    act(() => firstInputGate.resolve());
    await waitFor(() => expect(input).toHaveBeenCalledTimes(3));
    expect(order).toEqual([
      `start:${SESSION.sessionId}:echo marker`,
      `start:${NEXT_SESSION.sessionId}:pwd`,
      `finish:${NEXT_SESSION.sessionId}:pwd`,
      `finish:${SESSION.sessionId}:echo marker`,
      `start:${SESSION.sessionId}:\r`,
      `finish:${SESSION.sessionId}:\r`,
    ]);
  });

  /** 窗格关闭不能越过 xterm 已接纳到 FIFO 队尾的输入。 */
  it("waits for accepted pane input before native close", async () => {
    const inputGate = deferred<void>();
    const order: string[] = [];
    const input = vi.fn(async () => {
      order.push("input:start");
      await inputGate.promise;
      order.push("input:finish");
    });
    const close = vi.fn(async () => {
      order.push("close");
    });
    const adapter = fakeAdapter({ input, close });
    const initial = createDefaultTerminalLayout("ws_fixture");
    const paneId = initial.tabs[0]!.activePaneId;
    const { result } = renderHook(() =>
      useTerminalWorkspace({ workspaceId: "ws_fixture", adapter, initialLayout: initial }),
    );
    await act(async () => {
      await result.current.ensurePaneOpen(paneId);
    });

    let inputPromise!: Promise<void>;
    let closePromise!: Promise<void>;
    act(() => {
      inputPromise = result.current.sendInput(paneId, "echo before close");
    });
    await waitFor(() => expect(input).toHaveBeenCalledOnce());
    act(() => {
      closePromise = result.current.closePane(paneId);
    });
    expect(close).not.toHaveBeenCalled();

    inputGate.resolve();
    await act(async () => {
      await Promise.all([inputPromise, closePromise]);
    });
    expect(order).toEqual(["input:start", "input:finish", "close"]);
  });

  /** 工作区 teardown 先升起准入栅栏，再排空全部已接受窗格队尾后执行 close-all。 */
  it("waits for accepted input before native close-all", async () => {
    const inputGate = deferred<void>();
    const input = vi.fn(async () => inputGate.promise);
    const closeAll = vi.fn(async () => undefined);
    const adapter = fakeAdapter({ input, closeAll });
    const initial = createDefaultTerminalLayout("ws_fixture");
    const paneId = initial.tabs[0]!.activePaneId;
    const { result } = renderHook(() =>
      useTerminalWorkspace({ workspaceId: "ws_fixture", adapter, initialLayout: initial }),
    );
    await act(async () => {
      await result.current.ensurePaneOpen(paneId);
    });

    let inputPromise!: Promise<void>;
    let closePromise!: Promise<void>;
    act(() => {
      inputPromise = result.current.sendInput(paneId, "echo before close-all");
    });
    await waitFor(() => expect(input).toHaveBeenCalledOnce());
    act(() => {
      closePromise = result.current.closeAll();
    });
    expect(closeAll).not.toHaveBeenCalled();

    inputGate.resolve();
    await act(async () => {
      await Promise.all([inputPromise, closePromise]);
    });
    expect(closeAll).toHaveBeenCalledOnce();
  });

  /** 原生 close rejection 必须可观测，且不能删除唯一重试身份。 */
  it("retains pane session state and deduplicates concurrent close failures", async () => {
    const close = vi.fn(async () => {
      throw new Error("close failed");
    });
    const adapter = fakeAdapter({ close });
    const initial = createDefaultTerminalLayout("ws_fixture");
    const { result } = renderHook(() =>
      useTerminalWorkspace({ workspaceId: "ws_fixture", adapter, initialLayout: initial }),
    );
    const paneId = initial.tabs[0]!.activePaneId;
    await act(async () => {
      await result.current.ensurePaneOpen(paneId);
    });

    await act(async () => {
      const first = result.current.closePane(paneId);
      const second = result.current.closePane(paneId);
      const outcomes = await Promise.allSettled([first, second]);
      expect(outcomes.every((outcome) => outcome.status === "rejected")).toBe(true);
    });

    expect(close).toHaveBeenCalledOnce();
    expect(result.current.runtimes[paneId]).toMatchObject({
      lifecycle: "failed",
      session: SESSION,
      error: "终端关闭失败，可重试",
    });
    expect(result.current.layout.activeTabId).toBe(initial.activeTabId);
  });

  /** 标签删除只能在 close 后提交；任一成员失败都要保留标签与活动选择。 */
  it("does not remove a tab when its native pane cannot close", async () => {
    const adapter = fakeAdapter({
      close: vi.fn(async () => {
        throw new Error("close failed");
      }),
    });
    const initial = createDefaultTerminalLayout("ws_fixture");
    const { result } = renderHook(() =>
      useTerminalWorkspace({ workspaceId: "ws_fixture", adapter, initialLayout: initial }),
    );
    const paneId = initial.tabs[0]!.activePaneId;
    const tabId = initial.tabs[0]!.tabId;
    await act(async () => {
      await result.current.ensurePaneOpen(paneId);
    });

    await act(async () => {
      await expect(result.current.closeTab(tabId)).rejects.toThrow("close failed");
    });

    expect(result.current.layout.tabs.map((tab) => tab.tabId)).toEqual([tabId]);
    expect(result.current.layout.activeTabId).toBe(tabId);
    expect(result.current.runtimes[paneId]?.session).toEqual(SESSION);
  });

  /** 窗格删除遵循同一 close 事务，因此失败时不能折叠布局。 */
  it("does not remove a pane when native close fails", async () => {
    const adapter = fakeAdapter({
      close: vi.fn(async () => {
        throw new Error("close failed");
      }),
    });
    const initial = createDefaultTerminalLayout("ws_fixture");
    const { result } = renderHook(() =>
      useTerminalWorkspace({ workspaceId: "ws_fixture", adapter, initialLayout: initial }),
    );
    const paneId = initial.tabs[0]!.activePaneId;
    const tabId = initial.tabs[0]!.tabId;
    await act(async () => {
      await result.current.ensurePaneOpen(paneId);
    });

    await act(async () => {
      await expect(result.current.removePane(tabId, paneId)).rejects.toThrow("close failed");
    });

    expect(result.current.layout.tabs[0]?.activePaneId).toBe(paneId);
    expect(result.current.runtimes[paneId]?.session).toEqual(SESSION);
  });

  /** 旧原生进程确认关闭前，restart 不能打开替换代次。 */
  it("blocks restart on close failure and retries with one fresh generation after close succeeds", async () => {
    const close = vi
      .fn()
      .mockRejectedValueOnce(new Error("close failed"))
      .mockResolvedValue(undefined);
    const open = vi.fn().mockResolvedValueOnce(SESSION).mockResolvedValueOnce(NEXT_SESSION);
    const adapter = fakeAdapter({ close, open });
    const initial = createDefaultTerminalLayout("ws_fixture");
    const { result } = renderHook(() =>
      useTerminalWorkspace({ workspaceId: "ws_fixture", adapter, initialLayout: initial }),
    );
    const paneId = initial.tabs[0]!.activePaneId;
    await act(async () => {
      await result.current.ensurePaneOpen(paneId);
    });

    await act(async () => {
      await expect(result.current.restartPane(paneId)).rejects.toThrow("close failed");
    });
    expect(open).toHaveBeenCalledOnce();
    expect(result.current.runtimes[paneId]?.session).toEqual(SESSION);

    await act(async () => {
      await Promise.all([result.current.restartPane(paneId), result.current.restartPane(paneId)]);
    });
    expect(close).toHaveBeenCalledTimes(2);
    expect(open).toHaveBeenCalledTimes(2);
    expect(result.current.runtimes[paneId]).toMatchObject({
      lifecycle: "running",
      session: NEXT_SESSION,
    });
  });

  /** 工作区 cleanup 失败时保留旧存活布局和 session，不能伪装切换成功。 */
  it("preserves the previous workspace projection when close-all fails", async () => {
    const closeAll = vi.fn(async () => {
      throw new Error("close all failed");
    });
    const adapter = fakeAdapter({ closeAll });
    const firstLayout = createDefaultTerminalLayout("ws_first");
    const secondLayout = createDefaultTerminalLayout("ws_second");
    const { result, rerender } = renderHook(
      ({ workspaceId, initialLayout }) =>
        useTerminalWorkspace({ workspaceId, adapter, initialLayout }),
      { initialProps: { workspaceId: "ws_first", initialLayout: firstLayout } },
    );
    const paneId = firstLayout.tabs[0]!.activePaneId;
    await act(async () => {
      await result.current.ensurePaneOpen(paneId);
    });

    rerender({ workspaceId: "ws_second", initialLayout: secondLayout });
    await waitFor(() => expect(closeAll).toHaveBeenCalledWith("ws_first"));
    await waitFor(() => expect(result.current.closeAllPending).toBe(false));

    expect(closeAll).toHaveBeenCalledWith("ws_first");
    expect(result.current.layout.workspaceId).toBe("ws_first");
    expect(result.current.runtimes[paneId]).toMatchObject({
      lifecycle: "running",
      session: SESSION,
      error: undefined,
    });
  });

  /** 新代次只消费事件队列，合并的 poll 结果在确认前保持有序。 */
  it("queues consecutive output events without replaying fresh scrollback or overwriting chunks", async () => {
    const events: TerminalEvent[] = [
      {
        session_id: SESSION.sessionId,
        generation: SESSION.generation,
        sequence: 1,
        kind: { type: "output", data: Uint8Array.from([0x61]) },
      },
      {
        session_id: SESSION.sessionId,
        generation: SESSION.generation,
        sequence: 2,
        kind: { type: "output", data: Uint8Array.from([0x62]) },
      },
    ];
    const adapter = fakeAdapter({
      poll: vi.fn(async () => events.shift() ?? new Promise<TerminalEvent | null>(() => undefined)),
    });
    const initial = createDefaultTerminalLayout("ws_fixture");
    const { result } = renderHook(() =>
      useTerminalWorkspace({ workspaceId: "ws_fixture", adapter, initialLayout: initial }),
    );
    const paneId = initial.tabs[0]!.activePaneId;
    await act(async () => {
      await result.current.ensurePaneOpen(paneId);
    });
    await waitFor(() => expect(result.current.runtimes[paneId]?.outputs).toHaveLength(2));

    expect(adapter.scrollback).not.toHaveBeenCalled();
    expect(
      result.current.runtimes[paneId]?.outputs?.map((chunk) => Array.from(chunk.data)),
    ).toEqual([[0x61], [0x62]]);
    act(() => result.current.terminalInputProps(paneId).onOutputsConsumed(2));
    expect(result.current.runtimes[paneId]?.outputs).toBeUndefined();
  });

  /** dropped-byte 增量在后续输出后仍保留，并且只归属于实际丢失它的代次。 */
  it("accumulates consecutive output drops, preserves the warning through output, and clears it on restart", async () => {
    const events: TerminalEvent[] = [
      {
        session_id: SESSION.sessionId,
        generation: SESSION.generation,
        sequence: 1,
        kind: { type: "output_dropped", bytes: 4 },
      },
      {
        session_id: SESSION.sessionId,
        generation: SESSION.generation,
        sequence: 2,
        kind: { type: "output_dropped", bytes: 7 },
      },
      {
        session_id: SESSION.sessionId,
        generation: SESSION.generation,
        sequence: 3,
        kind: { type: "output", data: Uint8Array.from([0x63]) },
      },
      {
        session_id: SESSION.sessionId,
        generation: SESSION.generation,
        sequence: 4,
        kind: { type: "exited", code: 0, signal: null },
      },
    ];
    const open = vi.fn().mockResolvedValueOnce(SESSION).mockResolvedValueOnce(NEXT_SESSION);
    const adapter = fakeAdapter({
      open,
      poll: vi.fn(async () => events.shift() ?? new Promise<TerminalEvent | null>(() => undefined)),
    });
    const initial = createDefaultTerminalLayout("ws_fixture");
    const { result } = renderHook(() =>
      useTerminalWorkspace({ workspaceId: "ws_fixture", adapter, initialLayout: initial }),
    );
    const paneId = initial.tabs[0]!.activePaneId;

    await act(async () => {
      await result.current.ensurePaneOpen(paneId);
    });
    await waitFor(() => expect(result.current.runtimes[paneId]?.lifecycle).toBe("exited"));
    expect(result.current.runtimes[paneId]).toMatchObject({
      lifecycle: "exited",
      droppedBytes: 11,
      outputs: [{ sequence: 3, data: Uint8Array.from([0x63]) }],
    });

    await act(async () => {
      await result.current.restartPane(paneId);
    });
    expect(result.current.runtimes[paneId]).toMatchObject({
      lifecycle: "running",
      session: NEXT_SESSION,
    });
    expect(result.current.runtimes[paneId]?.droppedBytes).toBeUndefined();
  });

  /** 原生增量在 UI 可精确表示的最大整数处饱和。 */
  it("saturates accumulated dropped bytes at Number.MAX_SAFE_INTEGER", async () => {
    const events: TerminalEvent[] = [
      {
        session_id: SESSION.sessionId,
        generation: SESSION.generation,
        sequence: 1,
        kind: { type: "output_dropped", bytes: Number.MAX_SAFE_INTEGER - 2 },
      },
      {
        session_id: SESSION.sessionId,
        generation: SESSION.generation,
        sequence: 2,
        kind: { type: "output_dropped", bytes: 10 },
      },
      {
        session_id: SESSION.sessionId,
        generation: SESSION.generation,
        sequence: 3,
        kind: { type: "exited", code: 0, signal: null },
      },
    ];
    const adapter = fakeAdapter({
      poll: vi.fn(async () => events.shift() ?? new Promise<TerminalEvent | null>(() => undefined)),
    });
    const initial = createDefaultTerminalLayout("ws_fixture");
    const { result } = renderHook(() =>
      useTerminalWorkspace({ workspaceId: "ws_fixture", adapter, initialLayout: initial }),
    );
    const paneId = initial.tabs[0]!.activePaneId;

    await act(async () => {
      await result.current.ensurePaneOpen(paneId);
    });
    await waitFor(() => expect(result.current.runtimes[paneId]?.lifecycle).toBe("exited"));
    expect(result.current.runtimes[paneId]?.droppedBytes).toBe(Number.MAX_SAFE_INTEGER);
  });

  it("serializes pane resize and treats a late generation event as stale", async () => {
    let releasePoll: ((event: TerminalEvent | null) => void) | undefined;
    const staleEvent: TerminalEvent = {
      session_id: SESSION.sessionId,
      generation: SESSION.generation + 1,
      sequence: 9,
      kind: { type: "output", data: Uint8Array.from([0x66]) },
    };
    const adapter = fakeAdapter({
      poll: vi.fn(
        async () =>
          new Promise<TerminalEvent | null>((resolve) => {
            releasePoll = resolve;
          }),
      ),
    });
    const initial = createDefaultTerminalLayout("ws_fixture");
    const { result } = renderHook(() =>
      useTerminalWorkspace({ workspaceId: "ws_fixture", adapter, initialLayout: initial }),
    );
    const paneId = initial.tabs[0]!.activePaneId;
    await act(async () => {
      await result.current.ensurePaneOpen(paneId);
    });
    await act(async () => {
      await result.current.resizePane(paneId, {
        rows: 30,
        cols: 100,
        pixel_width: 0,
        pixel_height: 0,
      });
    });
    expect(adapter.resize).toHaveBeenCalledOnce();
    await act(async () => {
      releasePoll?.(staleEvent);
    });
    expect(result.current.runtimes[paneId]?.outputs).toBeUndefined();
  });

  it("forwards a native token only to the active running pane", async () => {
    const adapter = fakeAdapter();
    const initial = createDefaultTerminalLayout("ws_fixture");
    const { result } = renderHook(() =>
      useTerminalWorkspace({ workspaceId: "ws_fixture", adapter, initialLayout: initial }),
    );
    const paneId = initial.tabs[0]!.activePaneId;

    await act(async () => {
      await result.current.dropNativePaths(paneId, "22222222-2222-4222-8222-222222222222");
    });
    expect(adapter.dropNativePaths).not.toHaveBeenCalled();

    await act(async () => {
      await result.current.ensurePaneOpen(paneId);
    });
    await act(async () => {
      await result.current.dropNativePaths("inactive-pane", "22222222-2222-4222-8222-222222222222");
    });
    expect(adapter.dropNativePaths).not.toHaveBeenCalled();

    await act(async () => {
      await result.current.dropNativePaths(paneId, "22222222-2222-4222-8222-222222222222");
    });
    expect(adapter.dropNativePaths).toHaveBeenCalledOnce();
    expect(adapter.dropNativePaths).toHaveBeenCalledWith(
      SESSION,
      "22222222-2222-4222-8222-222222222222",
    );
  });
});
