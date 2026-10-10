// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import { TauriProcessSessionAdapter } from "@/api/tauri/process";
import type { RuntimeNativeBridge } from "@/api/tauri/runtime";

const workspaceId = "ws_project";
const threadId = "thr_project";
const sessionId = "proc_0123456789abcdef0123456789abcdef";

/** Tauri fake preserves the native command envelope while keeping returned process identity distinct. */
function nativeBridge(result: unknown) {
  const invoke = vi.fn(async () => result);
  return {
    invoke,
    bridge: { invoke, listen: vi.fn() } as unknown as RuntimeNativeBridge,
  };
}

describe("TauriProcessSessionAdapter", () => {
  /** List obtains the registry generation that fences subsequent operations. */
  it("uses a fixed command and validates list generation separately from session generation", async () => {
    const { bridge, invoke } = nativeBridge({
      workspaceId,
      threadId,
      generation: 8,
      sessions: [
        {
          workspaceId,
          threadId,
          generation: 7,
          sessionId,
          startedTurnId: "turn_build",
          startedCallId: "call_build",
          createdAt: "2026-10-06T00:00:00Z",
          state: "running",
          outputSequence: 0,
          outputTruncated: false,
          exitCode: null,
        },
      ],
      nextCursor: null,
    });

    const result = await new TauriProcessSessionAdapter(bridge).list({
      workspaceId,
      threadId,
      limit: 20,
      cursor: null,
      startedCallId: "call_build",
    });

    expect(invoke).toHaveBeenCalledWith("ja_process_list", {
      input: { workspaceId, threadId, limit: 20, cursor: null, startedCallId: "call_build" },
    });
    expect(result.generation).toBe(8);
    expect(result.sessions[0]?.generation).toBe(7);
  });

  /** Read forwards the registry fence and rejects malformed native response before UI sees it. */
  it("keeps registry fencing on read and fails closed for malformed output", async () => {
    const { bridge, invoke } = nativeBridge({
      workspaceId,
      threadId,
      generation: 7,
      sessionId,
      startedTurnId: "turn_build",
      startedCallId: "call_build",
      createdAt: "2026-10-06T00:00:00Z",
      state: "running",
      outputSequence: 0,
      outputTruncated: false,
      exitCode: null,
      output: "ready",
      nextSequence: 5,
    });

    const result = await new TauriProcessSessionAdapter(bridge).read({
      workspaceId,
      threadId,
      generation: 8,
      sessionId,
      afterSequence: 0,
      maxBytes: 65_536,
    });

    expect(invoke).toHaveBeenCalledWith("ja_process_read", {
      input: {
        workspaceId,
        threadId,
        generation: 8,
        sessionId,
        afterSequence: 0,
        maxBytes: 65_536,
      },
    });
    expect(result.generation).toBe(7);
    expect(result.output).toBe("ready");
  });
});
