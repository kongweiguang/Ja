// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { StrictMode } from "react";
import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type {
  WorkspaceActivationRecord,
  WorkspaceHistoryPort,
} from "@/features/workspace/application/ports";
import { useWorkspaceController } from "@/features/workspace/application/useWorkspaceController";

const SESSION_A: WorkspaceActivationRecord = {
  workspaceId: "ws_session_a",
  kind: "session",
  legacySharedWorkspaceId: "ws_legacy_shared",
  rootPath: "C:\\data\\workspaces\\thr_a",
  displayName: "会话 A",
  trust: "trusted",
};
const SESSION_B: WorkspaceActivationRecord = {
  ...SESSION_A,
  workspaceId: "ws_session_b",
  legacySharedWorkspaceId: null,
  rootPath: "C:\\data\\workspaces\\thr_b",
  displayName: "会话 B",
};
const PROJECT = {
  workspaceId: "ws_project",
  kind: "project" as const,
  legacySharedWorkspaceId: null,
  root: "C:\\demo",
  displayName: "demo",
  trust: "untrusted" as const,
  revision: 1,
};
const LEGACY_SHARED = {
  workspaceId: "ws_legacy_shared",
  kind: "legacy_shared" as const,
  legacySharedWorkspaceId: null,
  root: "C:\\data\\general-workspace",
  displayName: "旧共享目录",
  trust: "trusted" as const,
  revision: 1,
};
const READY_RUNTIME_STATE = {
  status: "ready" as const,
  generation: 1,
  serverInstanceId: "srv_1",
};

/** workspace list 按 Java kind filter 模拟，避免测试掩盖 mixed page 的目录遗漏。 */
function historyPort(): WorkspaceHistoryPort {
  return {
    workspaceOpen: vi.fn(async () => PROJECT),
    workspaceList: vi.fn(async (input) => ({
      items: [PROJECT, LEGACY_SHARED].filter(
        (candidate) => input?.kind === undefined || candidate.kind === input.kind,
      ),
      nextCursor: null,
    })),
  };
}

/** session Workspace 只能通过 Java 签发的 workspaceId 激活，测试不派生或回传路径。 */
function activationPort(): (workspaceId: string) => Promise<WorkspaceActivationRecord> {
  return vi.fn(async (workspaceId: string) => {
    if (workspaceId === SESSION_A.workspaceId) return SESSION_A;
    if (workspaceId === SESSION_B.workspaceId) return SESSION_B;
    throw new Error(`unexpected workspace ${workspaceId}`);
  });
}

/** 统一创建 controller 并注入切换探针，保证每个用例都能验证资源 fence 边界。 */
function renderWorkspaceController(input?: {
  history?: WorkspaceHistoryPort;
  activateWorkspace?: (workspaceId: string) => Promise<WorkspaceActivationRecord>;
  beforeWorkspaceChange?: (
    previousWorkspaceId: string,
    nextWorkspaceId?: string,
  ) => Promise<(() => void) | void>;
  picker?: () => Promise<string | null>;
}) {
  const history = input?.history ?? historyPort();
  const activateWorkspace = input?.activateWorkspace ?? activationPort();
  const beforeWorkspaceChange = input?.beforeWorkspaceChange ?? vi.fn(async () => undefined);
  const onWorkspaceCommitted = vi.fn();
  const picker = { pick: input?.picker ?? (async () => null) };
  const hook = renderHook(() =>
    useWorkspaceController({
      history,
      picker,
      activateWorkspace,
      runtimeState: READY_RUNTIME_STATE,
      configurationReady: true,
      beforeWorkspaceChange,
      onWorkspaceCommitted,
    }),
  );
  return { ...hook, history, activateWorkspace, beforeWorkspaceChange, onWorkspaceCommitted };
}

describe("useWorkspaceController", () => {
  afterEach(cleanup);

  it("空白无项目入口不激活旧共享或任何默认工作目录", async () => {
    const activateWorkspace = activationPort();
    const history = historyPort();
    const { result } = renderWorkspaceController({ history, activateWorkspace });

    await waitFor(() => expect(result.current.projects).toHaveLength(1));

    expect(result.current.workspace).toBeUndefined();
    expect(history.workspaceOpen).not.toHaveBeenCalled();
    expect(activateWorkspace).not.toHaveBeenCalled();
    expect(result.current.projects[0]).toMatchObject({
      kind: "project",
      workspaceId: PROJECT.workspaceId,
    });
  });

  it("会话导航先按服务端 workspaceId 激活专属目录", async () => {
    const activateWorkspace = activationPort();
    const { result } = renderWorkspaceController({ activateWorkspace });

    await act(async () => result.current.activateForConversation(SESSION_A.workspaceId));

    expect(activateWorkspace).toHaveBeenCalledExactlyOnceWith(SESSION_A.workspaceId);
    expect(result.current.workspace).toMatchObject({
      kind: "session",
      workspaceId: SESSION_A.workspaceId,
      rootPath: SESSION_A.rootPath,
      legacySharedWorkspaceId: LEGACY_SHARED.workspaceId,
    });
  });

  /** 项目数超过首屏时仍完整加载；创建/恢复后的 ID 查找也必须按 project 分页。 */
  it("按服务端 project kind 遍历目录分页并能在后续页恢复项目", async () => {
    const firstPage = Array.from({ length: 200 }, (_, index) => ({
      ...PROJECT,
      workspaceId: "ws_project_" + index,
      displayName: "项目 " + index,
      root: "C:\\projects\\" + index,
    }));
    const history = historyPort();
    history.workspaceList = vi.fn(async (input) =>
      input?.cursor === undefined
        ? { items: firstPage, nextCursor: "project_page_2" }
        : { items: [PROJECT], nextCursor: null },
    );
    const { result } = renderWorkspaceController({ history });
    await waitFor(() => expect(result.current.projects).toHaveLength(201));

    const uncachedHistory = historyPort();
    uncachedHistory.workspaceList = vi.fn(async (input) =>
      input?.cursor === undefined
        ? { items: firstPage, nextCursor: "project_page_2" }
        : { items: [PROJECT], nextCursor: null },
    );
    const uncached = renderWorkspaceController({ history: uncachedHistory });
    await act(async () => uncached.result.current.activateForConversation(PROJECT.workspaceId));

    expect(uncachedHistory.workspaceList).toHaveBeenCalledWith({
      kind: "project",
      limit: 200,
      cursor: undefined,
    });
    expect(uncachedHistory.workspaceList).toHaveBeenCalledWith({
      kind: "project",
      limit: 200,
      cursor: "project_page_2",
    });
    expect(uncachedHistory.workspaceOpen).toHaveBeenCalledWith({
      cwd: PROJECT.root,
      displayName: PROJECT.displayName,
    });
    uncached.unmount();
  });

  it("session A/B 与 session/空白切换保留 PTY，不申请 workspace fence", async () => {
    const beforeWorkspaceChange = vi.fn(async () => undefined);
    const { result } = renderWorkspaceController({ beforeWorkspaceChange });

    await act(async () => result.current.activateForConversation(SESSION_A.workspaceId));
    await act(async () => result.current.activateForConversation(SESSION_B.workspaceId));
    expect(result.current.workspace?.workspaceId).toBe(SESSION_B.workspaceId);
    expect(beforeWorkspaceChange).not.toHaveBeenCalled();

    await act(async () => result.current.selectNoProject());
    expect(result.current.workspace).toBeUndefined();
    expect(beforeWorkspaceChange).not.toHaveBeenCalled();
  });

  it("进入项目时聚合清理保留 session，项目 workspace/open 使用权威 cwd", async () => {
    const beforeWorkspaceChange = vi.fn(async () => undefined);
    const { result, history } = renderWorkspaceController({ beforeWorkspaceChange });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));
    await act(async () => result.current.activateForConversation(SESSION_A.workspaceId));

    await act(async () => result.current.select(PROJECT.workspaceId));

    expect(result.current.workspace).toMatchObject({
      kind: "project",
      workspaceId: PROJECT.workspaceId,
    });
    expect(beforeWorkspaceChange).toHaveBeenCalledOnce();
    expect(beforeWorkspaceChange).toHaveBeenCalledWith(SESSION_A.workspaceId, PROJECT.workspaceId);
    expect(history.workspaceOpen).toHaveBeenCalledWith({
      cwd: PROJECT.root,
      displayName: PROJECT.displayName,
    });
  });

  it("目录查询尚未返回时仍立即展示 workspace/open 确认的新项目", async () => {
    const history = historyPort();
    history.workspaceList = vi.fn(
      () => new Promise<{ items: (typeof PROJECT)[]; nextCursor: null }>(() => undefined),
    );
    const { result } = renderWorkspaceController({ history, picker: async () => PROJECT.root });

    await act(async () => result.current.choose());

    expect(result.current.workspace).toMatchObject({
      kind: "project",
      workspaceId: PROJECT.workspaceId,
    });
    expect(result.current.projects).toEqual([
      expect.objectContaining({ workspaceId: PROJECT.workspaceId, kind: "project" }),
    ]);
  });

  it("StrictMode 重放后只恢复服务端项目目录，不自动激活旧共享根", async () => {
    const history = historyPort();
    const activateWorkspace = activationPort();
    const { result } = renderHook(
      () =>
        useWorkspaceController({
          history,
          picker: { pick: async () => null },
          activateWorkspace,
          runtimeState: READY_RUNTIME_STATE,
          configurationReady: true,
          beforeWorkspaceChange: async () => undefined,
          onWorkspaceCommitted: vi.fn(),
        }),
      { wrapper: ({ children }) => <StrictMode>{children}</StrictMode> },
    );

    await waitFor(() => expect(result.current.projects).toHaveLength(1));
    expect(result.current.workspace).toBeUndefined();
    expect(activateWorkspace).not.toHaveBeenCalled();
  });
});
