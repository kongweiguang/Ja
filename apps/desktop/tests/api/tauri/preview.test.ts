// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import {
  JA_PREVIEW_COMMANDS,
  JA_PREVIEW_EVENTS,
  PreviewAdapterError,
  TauriPreviewAdapter,
  type PreviewNativeBridge,
} from "@/api/tauri/preview";

const SESSION_ID = "22222222-2222-4222-8222-222222222222";
const VIEWPORT = { x: 840, y: 250, width: 430, height: 540, visible: true } as const;
const BASE_SNAPSHOT = {
  id: SESSION_ID,
  generation: 1,
  status: "open",
  load_status: "loading",
  url: "https://example.com/",
  title: "Example",
  can_go_back: false,
  can_go_forward: false,
  window: { label: "preview_22222222222222222222222222222222", url: "https://example.com/" },
  dropped_events: 0,
};

/** 捕获固定 Preview command envelope，测试不接触通用 Tauri invoke mock。 */
function bridgeWith(responses: Record<string, unknown>): {
  bridge: PreviewNativeBridge;
  calls: Array<{ command: string; args?: Record<string, unknown> }>;
} {
  const calls: Array<{ command: string; args?: Record<string, unknown> }> = [];
  const bridge: PreviewNativeBridge = {
    invoke: async (command, args) => {
      calls.push({ command, args });
      return responses[command];
    },
    listen: async () => () => undefined,
  };
  return { bridge, calls };
}

describe("TauriPreviewAdapter", () => {
  it("accepts only HTTP(S), maps command DTOs, and keeps generation identity", async () => {
    const { bridge, calls } = bridgeWith({
      [JA_PREVIEW_COMMANDS.recoverPending]: { observed: 1, recovered: 1, failed: 0, pending: 0 },
      [JA_PREVIEW_COMMANDS.open]: { snapshot: BASE_SNAPSHOT, window: BASE_SNAPSHOT.window },
      [JA_PREVIEW_COMMANDS.navigate]: {
        ...BASE_SNAPSHOT,
        generation: 2,
        url: "https://example.com/docs",
        window: { ...BASE_SNAPSHOT.window, url: "https://example.com/docs" },
      },
      [JA_PREVIEW_COMMANDS.layout]: BASE_SNAPSHOT,
      [JA_PREVIEW_COMMANDS.close]: { ...BASE_SNAPSHOT, status: "closed" },
      [JA_PREVIEW_COMMANDS.events]: [],
      [JA_PREVIEW_COMMANDS.state]: BASE_SNAPSHOT,
    });
    const adapter = new TauriPreviewAdapter(bridge);

    await expect(adapter.recoverPending()).resolves.toEqual({
      observed: 1,
      recovered: 1,
      failed: 0,
      pending: 0,
    });
    const opened = await adapter.open(" https://example.com/ ", VIEWPORT);
    await adapter.navigate(
      opened.snapshot.id,
      opened.snapshot.generation,
      "https://example.com/docs",
    );
    await adapter.layout(SESSION_ID, { ...VIEWPORT, visible: false });
    await adapter.events(SESSION_ID, 16);
    await adapter.state(SESSION_ID);
    await adapter.close(SESSION_ID);

    expect(calls.map((call) => call.command)).toEqual([
      JA_PREVIEW_COMMANDS.recoverPending,
      JA_PREVIEW_COMMANDS.open,
      JA_PREVIEW_COMMANDS.navigate,
      JA_PREVIEW_COMMANDS.layout,
      JA_PREVIEW_COMMANDS.events,
      JA_PREVIEW_COMMANDS.state,
      JA_PREVIEW_COMMANDS.close,
    ]);
    expect(calls[0]?.args).toEqual({});
    expect(calls[1]?.args).toEqual({ input: { url: "https://example.com/", viewport: VIEWPORT } });
    expect(calls[2]?.args).toEqual({
      input: {
        sessionId: SESSION_ID,
        generation: 1,
        source: "user",
        url: "https://example.com/docs",
      },
    });
    expect(calls[3]?.args).toEqual({
      input: { sessionId: SESSION_ID, viewport: { ...VIEWPORT, visible: false } },
    });
    expect(calls[4]?.args).toEqual({ input: { sessionId: SESSION_ID, maxEvents: 16 } });
  });

  it.each([
    "javascript:alert(1)",
    "file:///tmp/app.html",
    "data:text/html,hello",
    "tauri://localhost",
  ])("rejects unsafe preview scheme %s before invoke", async (url) => {
    const { bridge, calls } = bridgeWith({});
    await expect(new TauriPreviewAdapter(bridge).open(url, VIEWPORT)).rejects.toMatchObject({
      code: "invalid_input",
    });
    expect(calls).toHaveLength(0);
  });

  it("drops malformed events, redacts suspicious load errors, and rejects malformed state", async () => {
    let eventHandler: ((payload: unknown) => void) | undefined;
    const bridge: PreviewNativeBridge = {
      invoke: async (command) => (command === JA_PREVIEW_COMMANDS.state ? { invalid: true } : []),
      listen: async (event, handler) => {
        expect(event).toBe(JA_PREVIEW_EVENTS.preview);
        eventHandler = handler;
        return () => undefined;
      },
    };
    const received: unknown[] = [];
    await new TauriPreviewAdapter(bridge).subscribe((event) => received.push(event));
    eventHandler?.({
      session_id: SESSION_ID,
      generation: 1,
      sequence: 1,
      kind: { type: "load_failed", message: "C:\\workspace\\secret-token" },
    });
    eventHandler?.({
      session_id: SESSION_ID,
      generation: 1,
      sequence: 2,
      kind: { type: "load_finished", url: "https://example.com/" },
    });
    eventHandler?.({ session_id: "bad", generation: 1, sequence: 3, kind: { type: "closed" } });

    expect(received).toHaveLength(2);
    expect(received[0]).toMatchObject({ kind: { type: "load_failed", message: "预览加载失败" } });
    expect(received[1]).toMatchObject({
      kind: { type: "load_finished", url: "https://example.com/" },
    });
    await expect(new TauriPreviewAdapter(bridge).state(SESSION_ID)).rejects.toMatchObject({
      code: "invalid_response",
    });
    expect(
      Object.values(JA_PREVIEW_COMMANDS).some((command) => command.includes("executable")),
    ).toBe(false);
  });

  it("redacts native command failures", async () => {
    const failed: PreviewNativeBridge = {
      invoke: async () => {
        throw new Error("https://secret.example/api-key");
      },
      listen: async () => () => undefined,
    };
    try {
      await new TauriPreviewAdapter(failed).open("https://example.com", VIEWPORT);
    } catch (error) {
      expect(error).toBeInstanceOf(PreviewAdapterError);
      expect((error as Error).message).not.toContain("secret.example");
      expect((error as Error).message).not.toContain("api-key");
    }
  });

  it("rejects malformed recovery reports without exposing native fields", async () => {
    const { bridge } = bridgeWith({
      [JA_PREVIEW_COMMANDS.recoverPending]: {
        observed: 1,
        recovered: 0,
        failed: 1,
        pending: 1,
        privatePath: "C:\\private",
      },
    });

    await expect(new TauriPreviewAdapter(bridge).recoverPending()).rejects.toMatchObject({
      code: "invalid_response",
      message: "预览返回数据无效",
    });
  });

  /** 隐藏创建与本机目标只通过专用 typed commands 传递，裸本机路径不进入 navigate。 */
  it("maps blank/local page commands and canonical file resolution DTOs", async () => {
    const blankSnapshot = {
      ...BASE_SNAPSHOT,
      url: "about:blank",
      title: "",
      window: { label: "preview_blank", url: "about:blank" },
    };
    const fileUrl = "file:///C:/workspace/%E9%A1%B5%E9%9D%A2.html";
    const localSnapshot = {
      ...BASE_SNAPSHOT,
      url: fileUrl,
      window: { label: "preview_local", url: fileUrl },
    };
    const resolution = {
      canonicalPath: "C:\\workspace\\页面.html",
      displayName: "页面.html",
      workspaceId: "ws_fixture",
      workspaceRelativePath: "页面.html",
      withinWorkspace: true,
      kind: "browser",
      mimeType: "text/html",
      fileUrl,
      content: null,
      truncated: false,
      line: 2,
      column: 4,
      readOnly: true,
    };
    const { bridge, calls } = bridgeWith({
      [JA_PREVIEW_COMMANDS.openBlank]: {
        snapshot: blankSnapshot,
        window: blankSnapshot.window,
      },
      [JA_PREVIEW_COMMANDS.resolveFile]: resolution,
      [JA_PREVIEW_COMMANDS.openFile]: { snapshot: localSnapshot, window: localSnapshot.window },
      [JA_PREVIEW_COMMANDS.navigateFile]: { ...localSnapshot, generation: 2 },
      [JA_PREVIEW_COMMANDS.goBack]: {
        ...localSnapshot,
        generation: 3,
        can_go_back: false,
        can_go_forward: true,
      },
      [JA_PREVIEW_COMMANDS.goForward]: {
        ...localSnapshot,
        generation: 4,
        can_go_back: true,
        can_go_forward: false,
      },
      [JA_PREVIEW_COMMANDS.reload]: { ...localSnapshot, generation: 5 },
    });
    const adapter = new TauriPreviewAdapter(bridge);
    const hiddenViewport = { ...VIEWPORT, visible: false };

    await expect(adapter.openBlank(hiddenViewport)).resolves.toMatchObject({
      snapshot: { url: "about:blank" },
    });
    await expect(adapter.resolveFile("页面.html", "ws_fixture", 2, 4)).resolves.toEqual(resolution);
    await expect(
      adapter.openFile("页面.html", "ws_fixture", hiddenViewport),
    ).resolves.toMatchObject({
      snapshot: { url: fileUrl },
    });
    await adapter.navigateFile(SESSION_ID, 1, "页面.html", "ws_fixture");
    await adapter.goBack(SESSION_ID, 2);
    await adapter.goForward(SESSION_ID, 3);
    await adapter.reload(SESSION_ID, 4);

    expect(calls.map((call) => call.command)).toEqual([
      JA_PREVIEW_COMMANDS.openBlank,
      JA_PREVIEW_COMMANDS.resolveFile,
      JA_PREVIEW_COMMANDS.openFile,
      JA_PREVIEW_COMMANDS.navigateFile,
      JA_PREVIEW_COMMANDS.goBack,
      JA_PREVIEW_COMMANDS.goForward,
      JA_PREVIEW_COMMANDS.reload,
    ]);
    expect(calls[0]?.args).toEqual({ input: { viewport: hiddenViewport } });
    expect(calls[1]?.args).toEqual({
      input: { target: "页面.html", workspaceId: "ws_fixture", line: 2, column: 4 },
    });
    expect(calls[2]?.args).toEqual({
      input: { target: "页面.html", workspaceId: "ws_fixture", viewport: hiddenViewport },
    });
    expect(calls[3]?.args).toEqual({
      input: {
        sessionId: SESSION_ID,
        generation: 1,
        target: "页面.html",
        workspaceId: "ws_fixture",
      },
    });
  });

  /** Ctrl+点击只发送固定 reveal command，异常不回显本机路径。 */
  it("reveals a file through the typed native command", async () => {
    const { bridge, calls } = bridgeWith({ [JA_PREVIEW_COMMANDS.revealFile]: null });
    const adapter = new TauriPreviewAdapter(bridge);
    await adapter.revealFile("C:\\资料 空间\\图.svg", "ws_fixture");
    expect(calls).toEqual([
      {
        command: JA_PREVIEW_COMMANDS.revealFile,
        args: { input: { target: "C:\\资料 空间\\图.svg", workspaceId: "ws_fixture" } },
      },
    ]);
    await expect(adapter.revealFile("")).rejects.toMatchObject({ code: "invalid_input" });
    expect(calls).toHaveLength(1);
  });

  /** history/action-blocked event DTO 与 Rust snake_case wire shape 保持严格一致。 */
  it("accepts native history and blocked-popup/download events", async () => {
    let eventHandler: ((payload: unknown) => void) | undefined;
    const bridge: PreviewNativeBridge = {
      invoke: async () => [],
      listen: async (_event, handler) => {
        eventHandler = handler;
        return () => undefined;
      },
    };
    const events: unknown[] = [];
    await new TauriPreviewAdapter(bridge).subscribe((event) => events.push(event));
    eventHandler?.({
      session_id: SESSION_ID,
      generation: 1,
      sequence: 1,
      kind: { type: "history_changed", can_go_back: true, can_go_forward: false },
    });
    eventHandler?.({
      session_id: SESSION_ID,
      generation: 1,
      sequence: 2,
      kind: { type: "action_blocked", action: "popup" },
    });
    eventHandler?.({
      session_id: SESSION_ID,
      generation: 1,
      sequence: 3,
      kind: { type: "action_blocked", action: "download" },
    });

    expect(events).toEqual([
      {
        session_id: SESSION_ID,
        generation: 1,
        sequence: 1,
        kind: { type: "history_changed", can_go_back: true, can_go_forward: false },
      },
      {
        session_id: SESSION_ID,
        generation: 1,
        sequence: 2,
        kind: { type: "action_blocked", action: "popup" },
      },
      {
        session_id: SESSION_ID,
        generation: 1,
        sequence: 3,
        kind: { type: "action_blocked", action: "download" },
      },
    ]);
  });

  /** 本机目标稳定错误码转为固定中文，不允许 command rejection 回显敏感路径。 */
  it("maps native file errors to static redacted messages", async () => {
    const bridge: PreviewNativeBridge = {
      invoke: async () => {
        throw { code: "FileNotFound", path: "C:\\private\\secret.html" };
      },
      listen: async () => () => undefined,
    };

    await expect(
      new TauriPreviewAdapter(bridge).resolveFile("missing.html", "ws_fixture"),
    ).rejects.toMatchObject({ code: "file_not_found", message: "文件不存在或已被移动。" });
  });
});
