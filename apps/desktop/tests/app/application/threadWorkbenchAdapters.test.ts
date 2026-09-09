// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import {
  createThreadTerminalAdapter,
  createThreadWorkbenchAdapters,
} from "@/app/application/threadWorkbenchAdapters";
import type { TerminalSessionInfo, TerminalWorkspaceAdapter } from "@/features/workbench/terminal";
import type { JaWorkbenchAdapters } from "@/app/useJaWorkbench";

interface Deferred<T> {
  readonly promise: Promise<T>;
  readonly resolve: (value: T) => void;
  readonly reject: (reason?: unknown) => void;
}

/** 用可控 Promise 精确排列 open/close 竞态，不用任意计时等待。 */
function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

/** 构造完整窄端口，并允许测试只覆盖与当前断言相关的原生边界。 */
function terminalAdapter(
  overrides: Partial<TerminalWorkspaceAdapter> = {},
): TerminalWorkspaceAdapter {
  return {
    profiles: vi.fn(async () => ["default"] as const),
    open: vi.fn(async () => ({ sessionId: "session-default", generation: 1 })),
    dropNativePaths: vi.fn(async () => undefined),
    input: vi.fn(async () => undefined),
    resize: vi.fn(async () => undefined),
    poll: vi.fn(async () => null),
    scrollback: vi.fn(async () => new Uint8Array()),
    close: vi.fn(async () => undefined),
    closeAll: vi.fn(async () => undefined),
    subscribeNativeDrop: vi.fn(async () => () => undefined),
    ...overrides,
  };
}

/** 固定使用真实 TerminalOpenInput 形状，证明 wrapper 不伪造 workspace identity。 */
function openInput(
  workspaceId = "workspace-shared",
): Parameters<TerminalWorkspaceAdapter["open"]>[0] {
  return {
    workspaceId,
    profile: "default",
    relativeCwd: "src",
    size: { rows: 24, cols: 80, pixel_width: 0, pixel_height: 0 },
  };
}

describe("threadWorkbenchAdapters", () => {
  it("同一 workspace 的会话 A closeAll 不关闭会话 B 的原生终端", async () => {
    const sessions: TerminalSessionInfo[] = [
      { sessionId: "session-a", generation: 11 },
      { sessionId: "session-b", generation: 22 },
    ];
    const nativeClose = vi.fn(async () => undefined);
    const nativeCloseAll = vi.fn(async () => undefined);
    const shared = terminalAdapter({
      open: vi.fn(async () => sessions.shift()!),
      close: nativeClose,
      closeAll: nativeCloseAll,
    });
    const threadA = createThreadTerminalAdapter(shared);
    const threadB = createThreadTerminalAdapter(shared);

    const sessionA = await threadA.open(openInput());
    const sessionB = await threadB.open(openInput());
    await threadA.closeAll("workspace-shared");

    expect(nativeClose).toHaveBeenCalledTimes(1);
    expect(nativeClose).toHaveBeenCalledWith(sessionA);
    expect(nativeClose).not.toHaveBeenCalledWith(sessionB);
    expect(nativeCloseAll).not.toHaveBeenCalled();
    await expect(threadB.scrollback(sessionB)).resolves.toEqual(new Uint8Array());
  });

  it("透传原生 open 参数并拒绝跨会话使用 session", async () => {
    const nativeOpen = vi.fn(async () => ({ sessionId: "session-a", generation: 3 }));
    const nativeInput = vi.fn(async () => undefined);
    const shared = terminalAdapter({ open: nativeOpen, input: nativeInput });
    const threadA = createThreadTerminalAdapter(shared);
    const threadB = createThreadTerminalAdapter(shared);
    const input = openInput("workspace-native-id");
    const session = await threadA.open(input);

    expect(nativeOpen).toHaveBeenCalledWith(input);
    expect(() => threadB.input(session, new Uint8Array([65]))).toThrow(
      "not owned by this conversation",
    );
    expect(nativeInput).not.toHaveBeenCalled();
  });

  it("closeAll 等待迟到 open 并在返回 renderer 前关闭其原生 session", async () => {
    const openGate = deferred<TerminalSessionInfo>();
    const lateSession = { sessionId: "session-late", generation: 9 };
    const nativeClose = vi.fn(async () => undefined);
    const thread = createThreadTerminalAdapter(
      terminalAdapter({ open: vi.fn(() => openGate.promise), close: nativeClose }),
    );

    const opening = thread.open(openInput());
    const closing = thread.closeAll("workspace-shared");
    openGate.resolve(lateSession);

    await expect(opening).rejects.toThrow("terminal workspace scope is closing");
    await expect(closing).resolves.toBeUndefined();
    expect(nativeClose).toHaveBeenCalledTimes(1);
    expect(nativeClose).toHaveBeenCalledWith(lateSession);
  });

  it("closeAll 失败后保留 session owner 并允许下一次重试", async () => {
    const session = { sessionId: "session-retry", generation: 7 };
    const nativeClose = vi
      .fn<() => Promise<void>>()
      .mockRejectedValueOnce(new Error("native close failed"))
      .mockResolvedValueOnce(undefined);
    const thread = createThreadTerminalAdapter(
      terminalAdapter({ open: vi.fn(async () => session), close: nativeClose }),
    );
    await thread.open(openInput());

    await expect(thread.closeAll("workspace-shared")).rejects.toThrow("native close failed");
    await expect(thread.closeAll("workspace-shared")).resolves.toBeUndefined();
    expect(nativeClose).toHaveBeenCalledTimes(2);
    expect(nativeClose).toHaveBeenNthCalledWith(1, session);
    expect(nativeClose).toHaveBeenNthCalledWith(2, session);
  });

  it("并发单 session close 与 closeAll 共享同一个原生关闭 ACK", async () => {
    const session = { sessionId: "session-closing", generation: 13 };
    const closeGate = deferred<void>();
    const nativeClose = vi.fn(() => closeGate.promise);
    const thread = createThreadTerminalAdapter(
      terminalAdapter({ open: vi.fn(async () => session), close: nativeClose }),
    );
    await thread.open(openInput());

    const closingSession = thread.close(session);
    const closingWorkspace = thread.closeAll("workspace-shared");
    expect(nativeClose).toHaveBeenCalledTimes(1);
    closeGate.resolve(undefined);

    await expect(closingSession).resolves.toBeUndefined();
    await expect(closingWorkspace).resolves.toBeUndefined();
    expect(nativeClose).toHaveBeenCalledTimes(1);
  });

  it("Workbench 工厂每次只替换 Terminal 并保留其它共享端口", () => {
    const sharedTerminal = terminalAdapter();
    const shared = {
      workspace: { list: vi.fn() },
      review: { snapshot: vi.fn() },
      terminal: sharedTerminal,
      preview: { open: vi.fn() },
    } as unknown as JaWorkbenchAdapters;

    const first = createThreadWorkbenchAdapters(shared);
    const second = createThreadWorkbenchAdapters(shared);

    expect(first.workspace).toBe(shared.workspace);
    expect(first.review).toBe(shared.review);
    expect(first.preview).toBe(shared.preview);
    expect(first.terminal).not.toBe(sharedTerminal);
    expect(second.terminal).not.toBe(first.terminal);
  });
});
