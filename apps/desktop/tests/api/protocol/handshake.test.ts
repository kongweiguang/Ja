// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { parseEvent, parseInitializedNotification, type ReadyToken } from "@/api/protocol/protocol";
import { ReadyHandshake } from "@/api/protocol/handshake";

const READY_TOKEN = "0123456789abcdef0123456789abcdef" as ReadyToken;

function readyEvent() {
  return {
    jsonrpc: "2.0" as const,
    method: "runtime/status-changed" as const,
    params: {
      serverInstanceId: "srv_demo",
      eventId: "evt_ready",
      sequence: 1,
      occurredAt: "2026-08-25T12:00:00Z",
      status: "ready" as const,
      generation: 1,
      readyToken: READY_TOKEN,
    },
  };
}

describe("Ja ready-token handshake", () => {
  it("requires the exact outbound challenge before accepting ready", () => {
    const handshake = new ReadyHandshake();
    handshake.acceptInitialized(READY_TOKEN);
    expect(handshake.state.phase).toBe("awaiting_ready");
    const event = parseEvent(readyEvent());
    if (event.method !== "runtime/status-changed") throw new Error("expected runtime status");
    handshake.acceptRuntimeStatus(event.params.status, event.params.readyToken);
    expect(handshake.state.phase).toBe("ready");
    expect(JSON.stringify(handshake.state)).not.toContain(READY_TOKEN);
  });

  it("rejects early, duplicate, wrong, and stale ready transitions", () => {
    const early = new ReadyHandshake();
    early.acceptRuntimeStatus("ready", READY_TOKEN);
    expect(early.state.phase).toBe("reconnect_required");

    const duplicate = new ReadyHandshake();
    duplicate.acceptInitialized(READY_TOKEN);
    duplicate.acceptRuntimeStatus("ready", READY_TOKEN);
    duplicate.acceptRuntimeStatus("ready", READY_TOKEN);
    expect(duplicate.state.phase).toBe("reconnect_required");

    const wrong = new ReadyHandshake();
    wrong.acceptInitialized(READY_TOKEN);
    wrong.acceptRuntimeStatus("ready", "fedcba9876543210fedcba9876543210");
    expect(wrong.state.phase).toBe("reconnect_required");
  });

  it("keeps initialized notification strict and ready token-only", () => {
    expect(
      parseInitializedNotification({
        jsonrpc: "2.0",
        method: "runtime/initialized",
        params: { readyToken: READY_TOKEN },
      }).params.readyToken,
    ).toBe(READY_TOKEN);
    expect(() =>
      parseInitializedNotification({
        jsonrpc: "2.0",
        method: "runtime/initialized",
        params: { readyToken: READY_TOKEN.toUpperCase() },
      }),
    ).toThrow();
    expect(() =>
      parseInitializedNotification({
        jsonrpc: "2.0",
        method: "runtime/initialized",
        params: { readyToken: READY_TOKEN, extension: true },
      }),
    ).toThrow();
    expect(() =>
      parseEvent({
        ...readyEvent(),
        params: { ...readyEvent().params, status: "starting", readyToken: READY_TOKEN },
      }),
    ).toThrow();
    const failed = parseEvent({
      jsonrpc: "2.0",
      method: "runtime/status-changed",
      params: {
        serverInstanceId: "srv_demo",
        eventId: "evt_failed",
        sequence: 2,
        occurredAt: "2026-08-25T12:00:00Z",
        status: "failed",
        generation: 1,
        reason: "runtime_lifecycle",
      },
    });
    if (failed.method !== "runtime/status-changed") throw new Error("expected runtime status");
    expect(failed.params.status).toBe("failed");
    expect(() =>
      parseEvent({
        jsonrpc: "2.0",
        method: "runtime/status-changed",
        params: {
          serverInstanceId: "srv_demo",
          eventId: "evt_failed_invalid",
          sequence: 3,
          occurredAt: "2026-08-25T12:00:00Z",
          status: "failed",
          generation: 1,
        },
      }),
    ).toThrow();
  });

  it("exposes frozen token-free projections and opaque fingerprints only", () => {
    const handshake = new ReadyHandshake();
    const projections: unknown[] = [];
    const fingerprints: Array<readonly string[]> = [];
    handshake.onChange((state, opaque) => {
      projections.push(state);
      fingerprints.push(opaque);
    });
    handshake.acceptInitialized(READY_TOKEN);
    expect(Object.isFrozen(handshake.state)).toBe(true);
    expect(projections.every(Object.isFrozen)).toBe(true);
    expect(fingerprints.at(-1)?.[0]).toMatch(/^[0-9a-f]{32}$/);
    expect(JSON.stringify(handshake)).not.toContain(READY_TOKEN);
  });
});
