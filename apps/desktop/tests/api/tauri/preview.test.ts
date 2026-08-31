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
});
