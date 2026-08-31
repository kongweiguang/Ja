// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { WorkspaceHistoryPort } from "@/features/workspace/application/ports";
import { useWorkspaceController } from "@/features/workspace/application/useWorkspaceController";

const GENERAL = {
  workspaceId: "ws_general",
  displayName: "无项目",
  trust: "trusted" as const,
  rootPath: "C:\\data\\general",
};
const PROJECT = {
  workspaceId: "ws_project",
  root: "C:\\demo",
  displayName: "demo",
  trust: "untrusted" as const,
  revision: 1,
};

/** 构造只覆盖 workspace 用例的 fake port，避免测试把 Thread 行为误归给本 controller。 */
function historyPort(): WorkspaceHistoryPort {
  return {
    workspaceOpen: vi.fn(async () => PROJECT),
    workspaceList: vi.fn(async () => ({ items: [PROJECT], nextCursor: null })),
  };
}

describe("useWorkspaceController", () => {
  afterEach(cleanup);

  it("只提交 Java 签发的 general identity，且不读取 Thread", async () => {
    const history = historyPort();
    const generalWorkspace = vi.fn(async () => GENERAL);
    const { result } = renderHook(() =>
      useWorkspaceController({
        history,
        picker: { pick: async () => null },
        generalWorkspace,
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        configurationReady: true,
        beforeWorkspaceChange: async () => undefined,
        onWorkspaceCommitted: vi.fn(),
      }),
    );

    await waitFor(() => expect(result.current.workspace?.workspaceId).toBe("ws_general"));
    expect(history.workspaceOpen).not.toHaveBeenCalled();
  });

  it("重开持久项目时先取得资源 fence，再调用 workspace/open", async () => {
    const history = historyPort();
    const beforeWorkspaceChange = vi.fn(async () => undefined);
    const { result } = renderHook(() =>
      useWorkspaceController({
        history,
        picker: { pick: async () => null },
        generalWorkspace: async () => GENERAL,
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        configurationReady: true,
        beforeWorkspaceChange,
        onWorkspaceCommitted: vi.fn(),
      }),
    );
    await waitFor(() => expect(result.current.projects).toHaveLength(1));

    await act(async () => result.current.select("ws_project"));

    expect(result.current.workspace?.workspaceId).toBe("ws_project");
    expect(beforeWorkspaceChange.mock.invocationCallOrder[0]).toBeLessThan(
      (history.workspaceOpen as ReturnType<typeof vi.fn>).mock.invocationCallOrder[0] ??
        Number.MAX_SAFE_INTEGER,
    );
    expect(history.workspaceOpen).toHaveBeenCalledWith({ cwd: "C:\\demo", displayName: "demo" });
  });

  it("从项目返回 general 时先关闭旧 capability，再让 native Host 重绑", async () => {
    const history = historyPort();
    const order: string[] = [];
    const release = vi.fn();
    const beforeWorkspaceChange = vi.fn(async () => {
      order.push("fence");
      return release;
    });
    const generalWorkspace = vi.fn(async () => {
      order.push("general");
      return GENERAL;
    });
    const { result } = renderHook(() =>
      useWorkspaceController({
        history,
        picker: { pick: async () => null },
        generalWorkspace,
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        configurationReady: true,
        beforeWorkspaceChange,
        onWorkspaceCommitted: vi.fn(),
      }),
    );
    await waitFor(() => expect(result.current.projects).toHaveLength(1));
    await act(async () => result.current.select("ws_project"));
    expect(result.current.workspace?.kind).toBe("project");
    order.length = 0;
    release.mockClear();

    await act(async () => result.current.selectGeneral());

    expect(result.current.workspace).toMatchObject({ kind: "general", workspaceId: "ws_general" });
    expect(order).toEqual(["fence", "general"]);
    expect(beforeWorkspaceChange).toHaveBeenLastCalledWith("ws_project", undefined);
    expect(release).toHaveBeenCalledOnce();
  });
});
