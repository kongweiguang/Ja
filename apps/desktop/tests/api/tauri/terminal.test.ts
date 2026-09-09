// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import {
  JA_TERMINAL_COMMANDS,
  JA_TERMINAL_EVENTS,
  parseTerminalNativeDropEvent,
  TerminalAdapterError,
  TauriTerminalAdapter,
  type TerminalNativeBridge,
} from "@/api/tauri/terminal";

const SESSION_ID = "11111111-1111-4111-8111-111111111111";
const DROP_TOKEN = "22222222-2222-4222-8222-222222222222";

/** 记录封闭 command surface 并返回确定性原生 fixture，避免测试依赖真实 PTY。 */
function bridgeWith(responses: Record<string, unknown>): {
  bridge: TerminalNativeBridge;
  calls: Array<{ command: string; args?: Record<string, unknown> }>;
} {
  const calls: Array<{ command: string; args?: Record<string, unknown> }> = [];
  const bridge: TerminalNativeBridge = {
    invoke: async (command, args) => {
      calls.push({ command, args });
      return responses[command];
    },
  };
  return { bridge, calls };
}

describe("TauriTerminalAdapter", () => {
  it("maps every PTY command to its closed Rust DTO surface and preserves bytes/identity", async () => {
    const { bridge, calls } = bridgeWith({
      [JA_TERMINAL_COMMANDS.profiles]: ["default", "power_shell", "cmd"],
      [JA_TERMINAL_COMMANDS.open]: { sessionId: SESSION_ID, generation: 3 },
      [JA_TERMINAL_COMMANDS.dropNativePaths]: undefined,
      [JA_TERMINAL_COMMANDS.input]: undefined,
      [JA_TERMINAL_COMMANDS.resize]: undefined,
      [JA_TERMINAL_COMMANDS.poll]: {
        session_id: SESSION_ID,
        generation: 3,
        sequence: 7,
        kind: { type: "output", data: [0xe4, 0xbd] },
      },
      [JA_TERMINAL_COMMANDS.scrollback]: [0xa0, 0x1b, 0x5b, 0x30, 0x6d],
      [JA_TERMINAL_COMMANDS.close]: undefined,
      [JA_TERMINAL_COMMANDS.closeAll]: undefined,
    });
    const adapter = new TauriTerminalAdapter(bridge);

    const profiles = await adapter.profiles();
    const session = await adapter.open({ workspaceId: "ws_fixture", relativeCwd: "src" });
    await adapter.dropNativePaths(session, DROP_TOKEN);
    await adapter.input(session, Uint8Array.from([0xe4, 0xbd]));
    await adapter.resize(session, { rows: 30, cols: 120 });
    const event = await adapter.poll(session, 250);
    const scrollback = await adapter.scrollback(session);
    await adapter.close(session);
    await adapter.closeAll("ws_fixture");

    expect(calls.map((call) => call.command)).toEqual([
      JA_TERMINAL_COMMANDS.profiles,
      JA_TERMINAL_COMMANDS.open,
      JA_TERMINAL_COMMANDS.dropNativePaths,
      JA_TERMINAL_COMMANDS.input,
      JA_TERMINAL_COMMANDS.resize,
      JA_TERMINAL_COMMANDS.poll,
      JA_TERMINAL_COMMANDS.scrollback,
      JA_TERMINAL_COMMANDS.close,
      JA_TERMINAL_COMMANDS.closeAll,
    ]);
    expect(calls[0]?.args).toEqual({});
    expect(calls[1]?.args).toEqual({
      input: {
        workspaceId: "ws_fixture",
        profile: "default",
        relativeCwd: "src",
        size: { rows: 24, cols: 80, pixel_width: 0, pixel_height: 0 },
      },
    });
    expect(calls[2]?.args).toEqual({
      input: { sessionId: SESSION_ID, generation: 3, dropToken: DROP_TOKEN },
    });
    expect(calls[2]?.args).toEqual({
      input: expect.not.objectContaining({
        path: expect.anything(),
        workspaceId: expect.anything(),
      }),
    });
    expect(calls[3]?.args).toEqual({
      input: { sessionId: SESSION_ID, generation: 3, data: [0xe4, 0xbd] },
    });
    expect(calls[4]?.args).toEqual({
      input: {
        sessionId: SESSION_ID,
        generation: 3,
        size: { rows: 30, cols: 120, pixel_width: 0, pixel_height: 0 },
      },
    });
    expect(calls[5]?.args).toEqual({
      input: { sessionId: SESSION_ID, generation: 3, timeoutMs: 250 },
    });
    expect(calls[8]?.args).toEqual({ input: { workspaceId: "ws_fixture" } });
    expect(profiles).toEqual(["default", "power_shell", "cmd"]);
    expect(event?.kind.type).toBe("output");
    expect(event?.kind.type === "output" ? Array.from(event.kind.data) : []).toEqual([0xe4, 0xbd]);
    expect(Array.from(scrollback)).toEqual([0xa0, 0x1b, 0x5b, 0x30, 0x6d]);
  });

  it("rejects oversized or malformed bytes before invoke and never accepts an executable", async () => {
    const { bridge, calls } = bridgeWith({});
    const adapter = new TauriTerminalAdapter(bridge);

    await expect(
      adapter.open({ workspaceId: "ws_fixture", executable: "cmd.exe" } as never),
    ).rejects.toMatchObject({ code: "invalid_input" });
    await expect(adapter.open({ workspaceId: "C:\\workspace" } as never)).rejects.toMatchObject({
      code: "invalid_input",
    });
    await expect(
      adapter.open({ workspaceId: "ws_fixture", relativeCwd: "..\\outside" } as never),
    ).rejects.toMatchObject({ code: "invalid_input" });
    await expect(
      adapter.dropNativePaths({ sessionId: SESSION_ID, generation: 1 }, "C:\\private\\file.txt"),
    ).rejects.toMatchObject({ code: "invalid_input" });
    await expect(
      adapter.input({ sessionId: SESSION_ID, generation: 1 }, new Uint8Array(64 * 1024 + 1)),
    ).rejects.toMatchObject({ code: "invalid_input" });
    expect(calls).toHaveLength(0);
    expect(
      Object.values(JA_TERMINAL_COMMANDS).some((command) => command.includes("executable")),
    ).toBe(false);
  });

  it("fails closed for malformed native output and redacts command failures", async () => {
    const malformed: TerminalNativeBridge = {
      invoke: async () => ({ sessionId: "not-a-uuid", generation: 0 }),
    };
    const adapter = new TauriTerminalAdapter(malformed);
    await expect(adapter.open({ workspaceId: "ws_fixture" })).rejects.toMatchObject({
      code: "invalid_response",
    });

    const failed: TerminalNativeBridge = {
      invoke: async () => {
        throw new Error("C:\\workspace\\secret-token");
      },
    };
    await expect(new TauriTerminalAdapter(failed).closeAll("ws_fixture")).rejects.toMatchObject({
      code: "command_failed",
    });
    try {
      await new TauriTerminalAdapter(failed).closeAll("ws_fixture");
    } catch (error) {
      expect(error).toBeInstanceOf(TerminalAdapterError);
      expect((error as Error).message).not.toContain("secret-token");
      expect((error as Error).message).not.toContain("ws_fixture");
    }
  });

  /** profile 响应必须是无重复的枚举数组，任何路径包装对象或未知值都在 IPC 边界失败。 */
  it("strictly rejects malformed or path-bearing terminal profile responses", async () => {
    const invalidResponses: unknown[] = [
      null,
      ["default", "default"],
      ["powershell"],
      [{ profile: "cmd", executable: "C:\\Windows\\System32\\cmd.exe" }],
      ["default", "power_shell", "cmd", "bash", "zsh", "fish", "default"],
    ];

    for (const response of invalidResponses) {
      const bridge: TerminalNativeBridge = { invoke: async () => response };
      await expect(new TauriTerminalAdapter(bridge).profiles()).rejects.toMatchObject({
        code: "invalid_response",
      });
    }
  });

  it("accepts only path-redacted native drop events and unregisters through the fixed channel", async () => {
    const listener = vi.fn();
    const unlisten = vi.fn();
    const bridge: TerminalNativeBridge = {
      invoke: vi.fn(async () => undefined) as TerminalNativeBridge["invoke"],
      listen: vi.fn(async (event: string, handler: (payload: unknown) => void) => {
        expect(event).toBe(JA_TERMINAL_EVENTS.nativeDrop);
        handler({ phase: "enter", x: 12.5, y: 48, count: 1 });
        handler({ phase: "drop", dropToken: DROP_TOKEN, x: 12.5, y: 48, count: 1 });
        handler({
          phase: "drop",
          dropToken: DROP_TOKEN,
          x: 12.5,
          y: 48,
          count: 1,
          path: "C:\\private\\secret",
        });
        return unlisten;
      }) as TerminalNativeBridge["listen"],
    };

    const unsubscribe = await new TauriTerminalAdapter(bridge).subscribeNativeDrop(listener);
    expect(listener).toHaveBeenCalledOnce();
    expect(listener).toHaveBeenCalledWith({ dropToken: DROP_TOKEN, x: 12.5, y: 48 });
    expect(JSON.stringify(listener.mock.calls)).not.toContain("private");
    expect(
      parseTerminalNativeDropEvent({
        phase: "drop",
        dropToken: DROP_TOKEN,
        x: 1,
        y: 2,
        count: 1,
      }),
    ).toEqual({ dropToken: DROP_TOKEN, x: 1, y: 2 });
    expect(() =>
      parseTerminalNativeDropEvent({
        phase: "drop",
        dropToken: DROP_TOKEN,
        x: 1,
        y: 2,
        count: 1,
        absolutePath: "C:\\private",
      }),
    ).toThrowError(TerminalAdapterError);
    await unsubscribe();
    expect(unlisten).toHaveBeenCalledOnce();
  });
});
