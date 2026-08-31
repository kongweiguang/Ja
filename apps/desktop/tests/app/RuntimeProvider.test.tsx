// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { StrictMode, type ReactElement } from "react";
import {
  RuntimeProvider,
  useRuntimeLifecycle,
  useRuntimeState,
  useRuntimeTurns,
} from "@/app/RuntimeProvider";
import {
  RuntimeApplicationError,
  type RuntimeHostEvent,
  type RuntimeHostPort,
  type RuntimeProjectionPort,
  type RuntimeStatusProjection,
  type RuntimeStatus,
} from "@/app/application/runtimePorts";

const ready: RuntimeStatus = { status: "ready", generation: 1, serverInstanceId: "srv_fixture" };
const stopped: RuntimeStatus = { status: "stopped", generation: 0, serverInstanceId: null };
const generalWorkspace = {
  workspaceId: "ws_runtime_a" as const,
  displayName: "无项目" as const,
  trust: "trusted" as const,
  rootPath: "C:\\data\\ja\\general-workspace",
};

interface FakeProjection {
  readonly port: RuntimeProjectionPort;
  readonly calls: string[];
}

/**
 * 记录 Runtime 发布的 projection intent，而不复制 Conversation reducer；controller 测试只验证
 * admission 与重放顺序，Store 语义由 composition adapter 测试独立覆盖。
 */
function fakeProjection(): FakeProjection {
  let generation = 0;
  const calls: string[] = [];
  return {
    calls,
    port: {
      currentGeneration: () => generation,
      applyRuntimeStatus: (status: RuntimeStatusProjection) => {
        generation = status.generation;
        calls.push(`status:${status.status}:${status.generation}`);
      },
      applyTurnAccepted: (accepted) => {
        calls.push(`accepted:${accepted.turnId}:${accepted.threadRevision}`);
      },
      applyHostEvent: (event) => {
        calls.push(
          event.kind === "timeline" ? `event:${event.event.method}` : `event:${event.kind}`,
        );
      },
    },
  };
}

/** 从冷启动或复用既有 owner 的 WebView reload 构造 lifecycle fixture，覆盖两种所有权入口。 */
function fakeRuntime(initialState: RuntimeStatus = stopped): {
  runtime: RuntimeHostPort;
  projection: FakeProjection;
  calls: string[];
  turnInputs: unknown[];
  emit: (event: RuntimeHostEvent) => void;
  setState: (next: RuntimeStatus) => void;
} {
  let state = initialState;
  const listeners = new Set<(event: RuntimeHostEvent) => void>();
  const calls: string[] = [];
  const turnInputs: unknown[] = [];
  const projection = fakeProjection();
  const runtime: RuntimeHostPort = {
    recoveryState: vi.fn(async () => ({
      required: false,
      acknowledgeable: false,
      recoveryId: null,
      revision: null,
    })),
    subscribe: vi.fn(async (listener) => {
      listeners.add(listener);
      calls.push("subscribe");
      return () => {
        listeners.delete(listener);
        calls.push("unsubscribe");
      };
    }),
    state: vi.fn(async () => {
      calls.push("state");
      return state;
    }),
    start: vi.fn(async () => {
      calls.push("start");
      state = ready;
      listeners.forEach((listener) =>
        listener({
          kind: "status",
          status: ready,
          eventId: "evt_ready",
          occurredAt: "2026-08-26T00:00:00Z",
        }),
      );
      return ready;
    }),
    stop: vi.fn(async () => {
      calls.push("stop");
      state = stopped;
      return stopped;
    }),
    storageInfo: vi.fn(async () => ({
      nativeImage: false,
      dataPath: "C:\\data\\ja",
      logPath: null,
      cachePath: null,
      lastBackup: null,
    })),
    generalWorkspace: vi.fn(async () => generalWorkspace),
    turnStart: vi.fn(async (input) => {
      calls.push("turnStart");
      turnInputs.push(input);
      return { accepted: true as const, turnId: "turn_fixture", queued: false, threadRevision: 1 };
    }),
    turnCancel: vi.fn(async (input) => ({
      accepted: true as const,
      turnId: input.turnId,
      status: "cancelled" as const,
      threadRevision: input.expectedThreadRevision + 1,
    })),
    turnSteer: vi.fn(async (input) => ({
      accepted: true as const,
      inputId: "input_steer",
      turnId: input.turnId,
      kind: "steering" as const,
      status: "queued" as const,
    })),
    turnFollowUp: vi.fn(async (input) => ({
      accepted: true as const,
      inputId: "input_follow_up",
      turnId: input.turnId,
      kind: "follow_up" as const,
      status: "queued" as const,
    })),
    approvalRespond: vi.fn(async () => undefined),
    query: (async () => ({ items: [], nextCursor: null })) as RuntimeHostPort["query"],
    acknowledgeRecovery: vi.fn(async () => ({
      required: false,
      acknowledgeable: false,
      recoveryId: null,
      revision: null,
    })),
  };
  return {
    runtime,
    projection,
    calls,
    turnInputs,
    emit: (event) => listeners.forEach((listener) => listener(event)),
    setState: (next) => {
      state = next;
    },
  };
}

function Probe(): ReactElement {
  const state = useRuntimeState();
  const lifecycle = useRuntimeLifecycle();
  const turns = useRuntimeTurns();
  return (
    <div>
      <output data-testid="boot">{state.boot.status}</output>
      <output data-testid="admission">{String(state.turnAdmissionReady)}</output>
      <button type="button" onClick={() => void lifecycle.startRuntime().catch(() => undefined)}>
        retry
      </button>
      <button
        type="button"
        onClick={() =>
          void turns.submitTurn({
            threadId: "thr_fixture",
            content: [{ type: "text", text: "hello" }],
          })
        }
      >
        submit
      </button>
    </div>
  );
}

describe("RuntimeProvider v2 lifecycle", () => {
  afterEach(() => {
    cleanup();
  });

  it("starts a stopped sidecar without a configuration snapshot and opens turn admission", async () => {
    const fake = fakeRuntime();
    render(
      <RuntimeProvider runtime={fake.runtime} projection={fake.projection.port}>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("boot")).toHaveTextContent("ready"));
    expect(fake.calls).toContain("start");
    await waitFor(() => expect(screen.getByTestId("admission")).toHaveTextContent("true"));
    screen.getByRole("button", { name: "submit" }).click();
    await waitFor(() => expect(fake.calls).toContain("turnStart"));
    expect(fake.turnInputs[0]).toEqual({
      threadId: "thr_fixture",
      content: [{ type: "text", text: "hello" }],
    });
  });

  it("restores turn admission when WebView reloads over an already-ready sidecar", async () => {
    const fake = fakeRuntime(ready);
    render(
      <RuntimeProvider runtime={fake.runtime} projection={fake.projection.port}>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("boot")).toHaveTextContent("ready"));
    await waitFor(() => expect(screen.getByTestId("admission")).toHaveTextContent("true"));
    expect(fake.calls).not.toContain("start");
    screen.getByRole("button", { name: "submit" }).click();
    await waitFor(() => expect(fake.calls).toContain("turnStart"));
  });

  it("keeps the replacement owner alive across StrictMode cleanup replay", async () => {
    const fake = fakeRuntime();
    render(
      <StrictMode>
        <RuntimeProvider runtime={fake.runtime} projection={fake.projection.port}>
          <Probe />
        </RuntimeProvider>
      </StrictMode>,
    );
    await waitFor(() => expect(screen.getByTestId("boot")).toHaveTextContent("ready"));
    await waitFor(() => expect(screen.getByTestId("admission")).toHaveTextContent("true"));
    expect(fake.calls.filter((call) => call === "start")).toHaveLength(1);
  });

  /**
   * WebView reload 会销毁旧 React tree，但 Rust host 与 sidecar 继续存在；旧 renderer 的
   * cleanup 只能退订事件，绝不能把已由新 renderer 复用的 generation 停掉。
   */
  it("does not stop the Rust-owned runtime when an old renderer unmounts", async () => {
    const fake = fakeRuntime();
    const first = render(
      <RuntimeProvider runtime={fake.runtime} projection={fake.projection.port}>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("boot")).toHaveTextContent("ready"));

    first.unmount();
    await waitFor(() => expect(fake.calls).toContain("unsubscribe"));
    expect(fake.calls).not.toContain("stop");

    render(
      <RuntimeProvider runtime={fake.runtime} projection={fake.projection.port}>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("boot")).toHaveTextContent("ready"));
    await waitFor(() => expect(screen.getByTestId("admission")).toHaveTextContent("true"));
    expect(fake.calls.filter((call) => call === "start")).toHaveLength(1);
    expect(fake.calls).not.toContain("stop");
  });

  it("retains the event subscription so a failed startup can be retried", async () => {
    const fake = fakeRuntime();
    let attempts = 0;
    fake.runtime.start = vi.fn(async () => {
      fake.calls.push("start");
      attempts += 1;
      if (attempts === 1) {
        throw new RuntimeApplicationError("RUNTIME_UNAVAILABLE", "运行时进程启动失败", true);
      }
      fake.setState(ready);
      return ready;
    });
    render(
      <RuntimeProvider runtime={fake.runtime} projection={fake.projection.port}>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("boot")).toHaveTextContent("failed"));
    expect(fake.calls).not.toContain("unsubscribe");

    screen.getByRole("button", { name: "retry" }).click();
    await waitFor(() => expect(screen.getByTestId("boot")).toHaveTextContent("ready"));
    await waitFor(() => expect(screen.getByTestId("admission")).toHaveTextContent("true"));
    expect(fake.calls).not.toContain("unsubscribe");
    expect(fake.runtime.start).toHaveBeenCalledTimes(2);
  });

  it("replays terminal events that arrive before the turn/start response", async () => {
    const fake = fakeRuntime(ready);
    let resolveStart:
      | ((accepted: {
          accepted: true;
          turnId: string;
          queued: boolean;
          threadRevision: number;
        }) => void)
      | undefined;
    fake.runtime.turnStart = vi.fn(
      () =>
        new Promise<{ accepted: true; turnId: string; queued: boolean; threadRevision: number }>(
          (resolve) => {
            resolveStart = resolve;
          },
        ),
    );
    render(
      <RuntimeProvider runtime={fake.runtime} projection={fake.projection.port}>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("admission")).toHaveTextContent("true"));

    screen.getByRole("button", { name: "submit" }).click();
    await waitFor(() => expect(fake.runtime.turnStart).toHaveBeenCalledTimes(1));
    fake.emit({
      kind: "timeline",
      event: {
        jsonrpc: "2.0",
        method: "turn/state-changed",
        params: {
          serverInstanceId: "srv_fixture",
          eventId: "evt_running",
          sequence: 2,
          generation: 1,
          workspaceId: "ws_runtime_a",
          threadId: "thr_fixture",
          turnId: "turn_fixture",
          threadRevision: 2,
          occurredAt: "2026-08-26T00:00:01Z",
          from: "queued",
          to: "running",
        },
      },
    });
    fake.emit({
      kind: "timeline",
      event: {
        jsonrpc: "2.0",
        method: "turn/terminal",
        params: {
          serverInstanceId: "srv_fixture",
          eventId: "evt_terminal",
          sequence: 3,
          generation: 1,
          workspaceId: "ws_runtime_a",
          threadId: "thr_fixture",
          turnId: "turn_fixture",
          threadRevision: 3,
          occurredAt: "2026-08-26T00:00:02Z",
          state: "completed",
          summary: "done",
          finalMessage: { messageId: "item_final", text: "done" },
          usage: { modelRound: 1, inputTokens: 2, outputTokens: 1, totalTokens: 3 },
        },
      },
    });
    expect(fake.projection.calls.some((call) => call.startsWith("accepted:"))).toBe(false);
    expect(fake.projection.calls.some((call) => call.startsWith("event:turn/"))).toBe(false);

    resolveStart?.({ accepted: true, turnId: "turn_fixture", queued: false, threadRevision: 1 });
    await waitFor(() =>
      expect(fake.projection.calls.filter((call) => call.startsWith("event:turn/"))).toHaveLength(
        2,
      ),
    );
    expect(
      fake.projection.calls.filter((call) => /^(?:accepted|event:turn\/)/u.test(call)),
    ).toEqual(["accepted:turn_fixture:1", "event:turn/state-changed", "event:turn/terminal"]);
  });

  it("rejects late status from an older generation after current admission is ready", async () => {
    const current = { status: "ready" as const, generation: 2, serverInstanceId: "srv_current" };
    const fake = fakeRuntime(current);
    render(
      <RuntimeProvider runtime={fake.runtime} projection={fake.projection.port}>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("admission")).toHaveTextContent("true"));

    fake.emit({
      kind: "status",
      status: { status: "crashed", generation: 1, serverInstanceId: "srv_stale" },
      eventId: "evt_stale_generation",
      occurredAt: "2026-08-26T00:00:03Z",
    });

    expect(screen.getByTestId("boot")).toHaveTextContent("ready");
    expect(screen.getByTestId("admission")).toHaveTextContent("true");
  });

  it("does not expose configureAndStart or allow old turn override fields", () => {
    const fake = fakeRuntime();
    function Shape(): ReactElement {
      const lifecycle = useRuntimeLifecycle() as unknown as Record<string, unknown>;
      return (
        <output data-testid="shape">
          {JSON.stringify({
            configureAndStart: "configureAndStart" in lifecycle,
            runtimeConfigure: "configure" in lifecycle,
          })}
        </output>
      );
    }
    render(
      <RuntimeProvider runtime={fake.runtime} projection={fake.projection.port}>
        <Shape />
      </RuntimeProvider>,
    );
    expect(screen.getByTestId("shape")).toHaveTextContent(
      '{"configureAndStart":false,"runtimeConfigure":false}',
    );
  });
});
