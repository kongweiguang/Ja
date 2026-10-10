// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { StrictMode } from "react";
import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type {
  WorkspaceActivationRecord,
  WorkspaceHistoryPort,
  WorkspaceRuntimeState,
} from "@/features/workspace/application/ports";
import type { WorkspaceProjection } from "@/features/workspace/domain/workspace";
import {
  WorkspaceProjectUpdateError,
  useWorkspaceController,
} from "@/features/workspace/application/useWorkspaceController";
import type { WorkspaceRebindLease } from "@/features/workspace";

const SESSION_A: WorkspaceActivationRecord = {
  workspaceId: "ws_session_a",
  kind: "session",
  rootPath: "C:\\data\\workspaces\\thr_a",
  displayName: "会话 A",
  trust: "trusted",
};
const SESSION_B: WorkspaceActivationRecord = {
  ...SESSION_A,
  workspaceId: "ws_session_b",
  rootPath: "C:\\data\\workspaces\\thr_b",
  displayName: "会话 B",
};
const PROJECT = {
  workspaceId: "ws_project",
  kind: "project" as const,
  root: "C:\\demo",
  displayName: "demo",
  trust: "untrusted" as const,
  revision: 1,
};
const PROJECT_B = {
  ...PROJECT,
  workspaceId: "ws_project_b",
  root: "C:\\demo-b",
  displayName: "demo-b",
  trust: "trusted" as const,
};
const READY_RUNTIME_STATE = {
  status: "ready" as const,
  generation: 1,
  serverInstanceId: "srv_1",
};

/** 测试只需模拟 application port 的错误投影，避免把工作区用例绑定到 Tauri 实现类。 @author kongweiguang */
function workspacePortError(
  code: string,
  message: string,
  retryable: boolean,
): Error & { code: string; retryable: boolean } {
  return Object.assign(new Error(message), { code, retryable });
}

/** workspace list 按 Java kind filter 模拟，避免测试掩盖 mixed page 的目录遗漏。 */
function historyPort(): WorkspaceHistoryPort {
  return {
    workspaceOpen: vi.fn(async () => PROJECT),
    workspaceSetTrust: vi.fn(async () => ({ accepted: true as const })),
    workspaceList: vi.fn(async (input) => ({
      items: [PROJECT].filter(
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

/** 测试装配可独立推进 Runtime identity 与配置准入，验证同身份投影不会误取消激活。 @author kongweiguang */
function renderWorkspaceController(input?: {
  history?: WorkspaceHistoryPort;
  activateWorkspace?: (workspaceId: string) => Promise<WorkspaceActivationRecord>;
  runtimeState?: WorkspaceRuntimeState | undefined;
  configurationReady?: boolean;
  beforeWorkspaceChange?: (
    previousWorkspaceId: string,
    nextWorkspaceId?: string,
  ) => Promise<(() => void) | void>;
  beforeWorkspaceRebind?: (workspaceId: string) => Promise<WorkspaceRebindLease | undefined>;
  resolveProjectDirectory?: (cwd: string) => Promise<string>;
  picker?: () => Promise<string | null>;
}) {
  const history = input?.history ?? historyPort();
  const activateWorkspace = input?.activateWorkspace ?? activationPort();
  const beforeWorkspaceChange = input?.beforeWorkspaceChange ?? vi.fn(async () => undefined);
  const onWorkspaceCommitted = vi.fn();
  const picker = { pick: input?.picker ?? (async () => null) };
  let runtimeState =
    input !== undefined && "runtimeState" in input ? input.runtimeState : READY_RUNTIME_STATE;
  let configurationReady = input?.configurationReady ?? true;
  const hook = renderHook(() =>
    useWorkspaceController({
      history,
      picker,
      activateWorkspace,
      resolveProjectDirectory: input?.resolveProjectDirectory,
      runtimeState,
      configurationReady,
      beforeWorkspaceChange,
      beforeWorkspaceRebind: input?.beforeWorkspaceRebind,
      onWorkspaceCommitted,
    }),
  );
  return {
    ...hook,
    history,
    activateWorkspace,
    beforeWorkspaceChange,
    onWorkspaceCommitted,
    setRuntimeState(next: WorkspaceRuntimeState | undefined) {
      runtimeState = next;
      hook.rerender();
    },
    setConfigurationReady(next: boolean) {
      configurationReady = next;
      hook.rerender();
    },
  };
}

describe("useWorkspaceController", () => {
  afterEach(cleanup);

  it("空白无项目入口不会激活任何默认工作目录", async () => {
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

  /** 数据库身份足以载入路径失效项目的历史；仅修名称时不向服务端提交失效 cwd。 @author kongweiguang */
  it("保留目录失效项目并允许不带 cwd 的名称更新", async () => {
    let persisted = { ...PROJECT, root: "C:\\removed\\demo" };
    const history = historyPort();
    history.workspaceList = vi.fn(async () => ({ items: [persisted], nextCursor: null }));
    history.workspaceRead = vi.fn(async ({ workspaceId }) => {
      if (workspaceId !== persisted.workspaceId) throw new Error("not found");
      return persisted;
    });
    history.workspaceOpen = vi.fn(async () => {
      throw new Error("directory does not exist");
    });
    history.workspaceUpdate = vi.fn(async ({ workspaceId, displayName }) => {
      persisted = {
        ...persisted,
        workspaceId,
        displayName: displayName ?? persisted.displayName,
        revision: 2,
      };
      return persisted;
    });
    const beforeWorkspaceRebind = vi.fn(async () => undefined);
    const { result } = renderWorkspaceController({ history, beforeWorkspaceRebind });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));
    await act(async () => result.current.select(PROJECT.workspaceId));

    expect(result.current.workspace).toMatchObject({
      workspaceId: PROJECT.workspaceId,
      rootPath: "C:\\removed\\demo",
    });
    expect(result.current.bindingReady).toBe(false);
    expect(result.current.directoryState).toBe("unavailable");
    const failedOpenCount = vi.mocked(history.workspaceOpen!).mock.calls.length;

    await act(async () => {
      await result.current.activateForConversation(PROJECT.workspaceId);
      await result.current.activateForConversation(PROJECT.workspaceId);
    });
    expect(history.workspaceOpen).toHaveBeenCalledTimes(failedOpenCount);
    expect(result.current.busy).toBe(false);

    await act(async () => {
      await result.current.updateProject({
        workspaceId: PROJECT.workspaceId,
        expectedRevision: 1,
        displayName: "保留历史",
        cwd: "C:\\removed\\demo",
      });
    });

    expect(history.workspaceUpdate).toHaveBeenCalledExactlyOnceWith({
      workspaceId: PROJECT.workspaceId,
      expectedRevision: 1,
      displayName: "保留历史",
    });
    expect(beforeWorkspaceRebind).not.toHaveBeenCalled();
    expect(result.current.workspace).toMatchObject({
      workspaceId: PROJECT.workspaceId,
      displayName: "保留历史",
      rootPath: "C:\\removed\\demo",
    });
    expect(result.current.bindingReady).toBe(false);

    await act(async () => result.current.activateForConversation(PROJECT.workspaceId));
    expect(history.workspaceOpen).toHaveBeenCalledTimes(failedOpenCount);
    expect(result.current.directoryState).toBe("unavailable");
  });

  /** Thread 连续选择失效项目时保留数据库历史投影；只有重试或 Runtime 换代才重新 open。 @author kongweiguang */
  it("项目目录失效后会话激活不重复探测且显式重试与 Runtime 换代各尝试一次", async () => {
    const missingRoot = { ...PROJECT, root: "C:\\removed\\conversation" };
    const history = historyPort();
    history.workspaceList = vi.fn(async () => ({ items: [missingRoot], nextCursor: null }));
    history.workspaceRead = vi.fn(async () => missingRoot);
    history.workspaceOpen = vi.fn(async (input) => {
      if ("workspaceId" in input)
        throw workspacePortError("WORKSPACE_NOT_FOUND", "项目目录不存在", false);
      return missingRoot;
    });
    const { result, setRuntimeState } = renderWorkspaceController({ history });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));

    await act(async () => result.current.select(PROJECT.workspaceId));
    expect(history.workspaceOpen).toHaveBeenCalledTimes(1);
    expect(result.current.workspace).toMatchObject({
      workspaceId: PROJECT.workspaceId,
      rootPath: missingRoot.root,
    });
    expect(result.current.directoryState).toBe("unavailable");

    const readCountAfterSelect = vi.mocked(history.workspaceRead!).mock.calls.length;
    const shownError = result.current.error;
    await act(async () => {
      for (let index = 0; index < 8; index += 1)
        await result.current.activateForConversation(PROJECT.workspaceId);
    });
    expect(history.workspaceOpen).toHaveBeenCalledTimes(1);
    expect(history.workspaceRead).toHaveBeenCalledTimes(readCountAfterSelect);
    expect(result.current.directoryState).toBe("unavailable");
    expect(result.current.error).toBe(shownError);
    expect(result.current.busy).toBe(false);

    await act(async () => result.current.readProject(PROJECT.workspaceId));
    expect(history.workspaceRead).toHaveBeenCalledTimes(readCountAfterSelect + 1);

    await act(async () => result.current.retryBinding());
    expect(history.workspaceOpen).toHaveBeenCalledTimes(2);
    expect(result.current.directoryState).toBe("unavailable");
    expect(result.current.busy).toBe(false);

    await act(async () => {
      setRuntimeState({ status: "ready", generation: 2, serverInstanceId: "srv_2" });
    });
    await waitFor(() => expect(history.workspaceOpen).toHaveBeenCalledTimes(3));
    await waitFor(() => expect(result.current.busy).toBe(false));
    await act(async () => result.current.activateForConversation(PROJECT.workspaceId));

    expect(history.workspaceOpen).toHaveBeenCalledTimes(3);
    expect(result.current.directoryState).toBe("unavailable");
    expect(result.current.busy).toBe(false);
  });

  /** 复核一开始就锁住当前项目动作，并且同 root 缺失目录不排空无关 Thread 资源。 @author kongweiguang */
  it("重新检查在首个权威读取前进入 busy，失效目录只尝试一次 ID-open", async () => {
    const missingRoot = { ...PROJECT, root: "C:\\removed\\recheck-pending" };
    let readCount = 0;
    let releaseRead!: (value: typeof missingRoot) => void;
    const history = historyPort();
    history.workspaceList = vi.fn(async () => ({ items: [missingRoot], nextCursor: null }));
    history.workspaceRead = vi.fn(async () => {
      readCount += 1;
      if (readCount === 2)
        return await new Promise<typeof missingRoot>((resolve) => {
          releaseRead = resolve;
        });
      return missingRoot;
    });
    history.workspaceOpen = vi.fn(async () => {
      throw workspacePortError("WORKSPACE_NOT_FOUND", "项目目录不存在", false);
    });
    const beforeWorkspaceRebind = vi.fn(async () => undefined);
    const { result } = renderWorkspaceController({ history, beforeWorkspaceRebind });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));
    await act(async () => result.current.select(PROJECT.workspaceId));
    expect(result.current.directoryState).toBe("unavailable");
    expect(history.workspaceOpen).toHaveBeenCalledTimes(1);

    let recheck!: Promise<void>;
    act(() => {
      recheck = result.current.retryBinding();
    });
    expect(result.current.busy).toBe(true);
    expect(history.workspaceOpen).toHaveBeenCalledTimes(1);

    await act(async () => {
      releaseRead(missingRoot);
      await recheck;
    });
    expect(history.workspaceOpen).toHaveBeenCalledTimes(2);
    expect(beforeWorkspaceRebind).not.toHaveBeenCalled();
    expect(result.current.directoryState).toBe("unavailable");
    expect(result.current.busy).toBe(false);

    await act(async () => result.current.activateForConversation(PROJECT.workspaceId));
    expect(history.workspaceOpen).toHaveBeenCalledTimes(2);
  });

  /** 同一失败身份的会话激活必须在推进全局 intent 前返回，避免取消独立目录刷新。 @author kongweiguang */
  it("失效项目的会话激活不取消正在进行的项目列表读取", async () => {
    const missingRoot = { ...PROJECT, root: "C:\\removed\\catalog-fence" };
    const history = historyPort();
    history.workspaceList = vi.fn(async () => ({ items: [missingRoot], nextCursor: null }));
    history.workspaceRead = vi.fn(async () => missingRoot);
    history.workspaceOpen = vi.fn(async (input) => {
      if ("workspaceId" in input)
        throw workspacePortError("WORKSPACE_NOT_FOUND", "项目目录不存在", false);
      return missingRoot;
    });
    const { result } = renderWorkspaceController({ history });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));
    await act(async () => result.current.select(PROJECT.workspaceId));
    expect(result.current.directoryState).toBe("unavailable");

    let releaseCatalog!: (value: { items: (typeof missingRoot)[]; nextCursor: null }) => void;
    const pendingCatalog = new Promise<{ items: (typeof missingRoot)[]; nextCursor: null }>(
      (resolve) => {
        releaseCatalog = resolve;
      },
    );
    vi.mocked(history.workspaceList).mockImplementationOnce(async () => pendingCatalog);
    let retryPromise!: Promise<void>;
    act(() => {
      retryPromise = result.current.retryCatalog();
    });
    await waitFor(() => expect(result.current.catalogLoading).toBe(true));

    await act(async () => result.current.activateForConversation(PROJECT.workspaceId));
    expect(result.current.catalogLoading).toBe(true);
    expect(history.workspaceOpen).toHaveBeenCalledTimes(1);

    await act(async () => {
      releaseCatalog({ items: [missingRoot], nextCursor: null });
      await retryPromise;
    });
    expect(result.current.catalogLoading).toBe(false);
    expect(result.current.projects).toHaveLength(1);
  });

  /** 旧 Runtime 的迟到成功不得清除新 Runtime 已记录的缺失目录结果。 @author kongweiguang */
  it("迟到的旧 Runtime 成功回包不会抹掉新 Runtime 的失败闩锁", async () => {
    const missingRoot = { ...PROJECT, root: "C:\\removed\\stale-runtime" };
    const history = historyPort();
    history.workspaceList = vi.fn(async () => ({ items: [missingRoot], nextCursor: null }));
    history.workspaceRead = vi.fn(async () => missingRoot);
    let releaseOldOpen!: (value: typeof missingRoot) => void;
    let openCount = 0;
    history.workspaceOpen = vi.fn(async (input) => {
      if (!("workspaceId" in input)) return missingRoot;
      openCount += 1;
      if (openCount === 1)
        return await new Promise<typeof missingRoot>((resolve) => {
          releaseOldOpen = resolve;
        });
      throw workspacePortError("WORKSPACE_NOT_FOUND", "项目目录不存在", false);
    });
    const { result, setRuntimeState } = renderWorkspaceController({ history });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));

    let oldRuntimeRequest!: Promise<WorkspaceProjection | undefined>;
    await act(async () => {
      oldRuntimeRequest = result.current.activateForConversation(PROJECT.workspaceId);
      await waitFor(() => expect(history.workspaceOpen).toHaveBeenCalledTimes(1));
    });
    await act(async () => {
      setRuntimeState({ status: "ready", generation: 2, serverInstanceId: "srv_2" });
    });
    await act(async () => result.current.activateForConversation(PROJECT.workspaceId));
    expect(history.workspaceOpen).toHaveBeenCalledTimes(2);
    expect(result.current.directoryState).toBe("unavailable");

    await act(async () => {
      releaseOldOpen(missingRoot);
      await oldRuntimeRequest;
    });
    await act(async () => result.current.activateForConversation(PROJECT.workspaceId));

    expect(history.workspaceOpen).toHaveBeenCalledTimes(2);
    expect(result.current.directoryState).toBe("unavailable");
    expect(result.current.bindingReady).toBe(false);
  });

  /** 权威 metadata 把目录改到新 root 后应绕过旧 root 失败闩锁并只绑定一次新目录。 @author kongweiguang */
  it("失效项目收到真实 root 变更后自动尝试新目录并恢复绑定", async () => {
    let persisted = { ...PROJECT, root: "C:\\removed\\before" };
    const history = historyPort();
    history.workspaceList = vi.fn(async () => ({ items: [persisted], nextCursor: null }));
    history.workspaceRead = vi.fn(async () => persisted);
    history.workspaceOpen = vi.fn(async () => {
      if (persisted.root === "C:\\removed\\before")
        throw workspacePortError("WORKSPACE_NOT_FOUND", "项目目录不存在", false);
      return persisted;
    });
    const { result } = renderWorkspaceController({ history });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));
    await act(async () => result.current.select(PROJECT.workspaceId));
    expect(result.current.directoryState).toBe("unavailable");
    expect(history.workspaceOpen).toHaveBeenCalledTimes(1);

    persisted = { ...persisted, root: "D:\\restored\\after", revision: 2 };
    await act(async () => result.current.refreshProjectMetadata(PROJECT.workspaceId, 2));

    expect(history.workspaceOpen).toHaveBeenCalledTimes(2);
    expect(result.current.workspace).toMatchObject({ rootPath: persisted.root, revision: 2 });
    expect(result.current.bindingReady).toBe(true);
    expect(result.current.directoryState).toBe("available");

    await act(async () => result.current.activateForConversation(PROJECT.workspaceId));
    expect(history.workspaceOpen).toHaveBeenCalledTimes(2);
  });

  /** 名称写入的回包丢失时只用数据库 root 回读确认，缺失目录不触发文件系统解析。 */
  it("失效目录的名称更新可在回包丢失后按数据库事实确认", async () => {
    let persisted = { ...PROJECT, root: "C:\\removed\\demo" };
    const history = historyPort();
    history.workspaceList = vi.fn(async () => ({ items: [persisted], nextCursor: null }));
    history.workspaceUpdate = vi.fn(async ({ displayName }) => {
      persisted = {
        ...persisted,
        displayName: displayName ?? persisted.displayName,
        revision: 2,
      };
      throw new Error("write response lost");
    });
    const resolveProjectDirectory = vi.fn(async () => {
      throw new Error("directory does not exist");
    });
    const { result } = renderWorkspaceController({ history, resolveProjectDirectory });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));

    await act(async () => {
      await expect(
        result.current.updateProject({
          workspaceId: PROJECT.workspaceId,
          expectedRevision: PROJECT.revision,
          displayName: "保留历史名称",
          cwd: persisted.root,
        }),
      ).resolves.toMatchObject({ displayName: "保留历史名称", rootPath: persisted.root });
    });

    expect(resolveProjectDirectory).not.toHaveBeenCalled();
    expect(history.workspaceUpdate).toHaveBeenCalledExactlyOnceWith({
      workspaceId: PROJECT.workspaceId,
      expectedRevision: PROJECT.revision,
      displayName: "保留历史名称",
    });
  });

  /** Busy Runtime 仍可保存元数据；改目录才交给 Java 共享门禁并保留其冲突摘要。 @author kongweiguang */
  it("运行中的项目允许只改名，改目录则保留 Java WORKSPACE_BUSY 摘要", async () => {
    const busyMessage = "暂时无法更改项目目录：项目诊断会话正在工作";
    let persisted = { ...PROJECT };
    const history = historyPort();
    history.workspaceList = vi.fn(async () => ({ items: [persisted], nextCursor: null }));
    history.workspaceRead = vi.fn(async () => persisted);
    history.workspaceUpdate = vi.fn(async (input) => {
      if (input.cwd !== undefined) throw workspacePortError("WORKSPACE_BUSY", busyMessage, true);
      persisted = {
        ...persisted,
        displayName: input.displayName ?? persisted.displayName,
        revision: persisted.revision + 1,
      };
      return persisted;
    });
    const resolveProjectDirectory = vi.fn(async (cwd: string) => cwd);
    const lease = { commit: vi.fn(), release: vi.fn() };
    const beforeWorkspaceRebind = vi.fn(async () => lease);
    const { result, setRuntimeState } = renderWorkspaceController({
      history,
      resolveProjectDirectory,
      beforeWorkspaceRebind,
    });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));
    await act(async () => result.current.select(PROJECT.workspaceId));
    await act(async () => {
      setRuntimeState({ status: "busy", generation: 1, serverInstanceId: "srv_1" });
    });

    await act(async () => {
      await expect(
        result.current.updateProject({
          workspaceId: PROJECT.workspaceId,
          expectedRevision: PROJECT.revision,
          displayName: "运行中改名",
          cwd: PROJECT.root,
        }),
      ).resolves.toMatchObject({ displayName: "运行中改名" });
    });

    expect(history.workspaceUpdate).toHaveBeenCalledExactlyOnceWith({
      workspaceId: PROJECT.workspaceId,
      expectedRevision: PROJECT.revision,
      displayName: "运行中改名",
    });
    expect(resolveProjectDirectory).not.toHaveBeenCalled();
    expect(beforeWorkspaceRebind).not.toHaveBeenCalled();
    expect(result.current.bindingReady).toBe(true);

    await act(async () => {
      await expect(
        result.current.updateProject({
          workspaceId: PROJECT.workspaceId,
          expectedRevision: persisted.revision,
          displayName: "运行中改目录",
          cwd: "D:\\busy-target",
        }),
      ).rejects.toMatchObject({ code: "WORKSPACE_BUSY", message: busyMessage });
    });

    expect(history.workspaceUpdate).toHaveBeenLastCalledWith({
      workspaceId: PROJECT.workspaceId,
      expectedRevision: persisted.revision,
      displayName: "运行中改目录",
      cwd: "D:\\busy-target",
    });
    expect(resolveProjectDirectory).toHaveBeenNthCalledWith(1, "D:\\busy-target");
    expect(resolveProjectDirectory).toHaveBeenNthCalledWith(2, PROJECT.root);
    expect(beforeWorkspaceRebind).toHaveBeenCalledExactlyOnceWith(PROJECT.workspaceId);
    expect(lease.commit).not.toHaveBeenCalled();
    expect(lease.release).toHaveBeenCalledOnce();
    expect(result.current.busy).toBe(false);
  });

  /** Settings scope 失败不能锁死项目身份操作；重试绑定、切换项目和退出项目都仍可完成。 */
  it("配置未就绪时仍可重试失效项目、切换登记项目并退出项目 scope", async () => {
    let projectAAvailable = false;
    const history = historyPort();
    history.workspaceList = vi.fn(async (input) => ({
      items: [PROJECT, PROJECT_B].filter(
        (candidate) => input?.kind === undefined || candidate.kind === input.kind,
      ),
      nextCursor: null,
    }));
    history.workspaceOpen = vi.fn(async (input) => {
      if (!("workspaceId" in input)) throw new Error("expected stable workspace identity");
      if (input.workspaceId === PROJECT.workspaceId && !projectAAvailable)
        throw new Error("project directory unavailable");
      return input.workspaceId === PROJECT.workspaceId ? PROJECT : PROJECT_B;
    });
    const { result, setConfigurationReady } = renderWorkspaceController({ history });
    await waitFor(() => expect(result.current.projects).toHaveLength(2));

    await act(async () => result.current.select(PROJECT.workspaceId));
    expect(result.current.workspace).toMatchObject({ workspaceId: PROJECT.workspaceId });
    expect(result.current.bindingReady).toBe(false);

    setConfigurationReady(false);
    projectAAvailable = true;
    await act(async () => result.current.retryBinding());
    expect(result.current.bindingReady).toBe(true);

    await act(async () => result.current.select(PROJECT_B.workspaceId));
    expect(result.current.workspace).toMatchObject({ workspaceId: PROJECT_B.workspaceId });
    expect(result.current.bindingReady).toBe(true);

    await act(async () => result.current.selectNoProject());
    expect(result.current.workspace).toBeUndefined();
  });

  /** 重绑 lease 在 CAS 成功后提交，再按同一 ID 激活新目录；lease 总会释放。 */
  it("成功换目录时保留项目身份并按 update、commit、open 次序重绑", async () => {
    let persisted = { ...PROJECT };
    const events: string[] = [];
    const moved = {
      ...PROJECT,
      root: "D:\\资料 目录\\demo",
      revision: 2,
    };
    const history = historyPort();
    history.workspaceList = vi.fn(async () => ({ items: [persisted], nextCursor: null }));
    history.workspaceRead = vi.fn(async () => persisted);
    history.workspaceUpdate = vi.fn(async () => {
      events.push("update");
      persisted = moved;
      return moved;
    });
    history.workspaceOpen = vi.fn(async (input) => {
      if (!("workspaceId" in input) || input.workspaceId !== PROJECT.workspaceId)
        throw new Error("expected stable project identity");
      events.push("open");
      return persisted;
    });
    const lease: WorkspaceRebindLease = {
      commit: () => events.push("commit"),
      release: () => events.push("release"),
    };
    const beforeWorkspaceRebind = vi.fn(async (workspaceId: string) => {
      expect(workspaceId).toBe(PROJECT.workspaceId);
      events.push("freeze");
      return lease;
    });
    const { result, onWorkspaceCommitted } = renderWorkspaceController({
      history,
      beforeWorkspaceRebind,
    });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));
    await act(async () => result.current.select(PROJECT.workspaceId));
    onWorkspaceCommitted.mockClear();
    events.length = 0;

    await act(async () => {
      await result.current.updateProject({
        workspaceId: PROJECT.workspaceId,
        expectedRevision: PROJECT.revision,
        displayName: "Ja 工程",
        cwd: moved.root,
      });
    });

    expect(events).toEqual(["freeze", "update", "commit", "open", "release"]);
    expect(history.workspaceUpdate).toHaveBeenCalledExactlyOnceWith({
      workspaceId: PROJECT.workspaceId,
      expectedRevision: PROJECT.revision,
      displayName: "Ja 工程",
      cwd: moved.root,
    });
    expect(history.workspaceOpen).toHaveBeenLastCalledWith({ workspaceId: PROJECT.workspaceId });
    const openCallOrder = vi.mocked(history.workspaceOpen!).mock.invocationCallOrder.at(-1);
    const commitCallOrder = onWorkspaceCommitted.mock.invocationCallOrder[0];
    expect(openCallOrder).toBeDefined();
    expect(commitCallOrder).toBeDefined();
    expect(commitCallOrder).toBeGreaterThan(openCallOrder!);
    expect(result.current.workspace).toMatchObject({
      workspaceId: PROJECT.workspaceId,
      rootPath: moved.root,
      displayName: moved.displayName,
      revision: moved.revision,
    });
    expect(result.current.bindingReady).toBe(true);
  });

  /** 目录别名只改变输入拼写时应绕过 lease 和 workspace/open，避免误关同一物理目录资源。 */
  it("路径解析确认物理目录相同时只保存名称而不重建绑定", async () => {
    let persisted = { ...PROJECT };
    const history = historyPort();
    history.workspaceList = vi.fn(async () => ({ items: [persisted], nextCursor: null }));
    history.workspaceRead = vi.fn(async () => persisted);
    history.workspaceUpdate = vi.fn(async ({ displayName }) => {
      persisted = { ...persisted, displayName: displayName ?? persisted.displayName, revision: 2 };
      return persisted;
    });
    const resolveProjectDirectory = vi.fn(async (cwd: string) =>
      cwd === "C:\\alias\\demo" ? "\\\\?\\C:\\demo\\" : cwd,
    );
    const beforeWorkspaceRebind = vi.fn(async () => undefined);
    const { result } = renderWorkspaceController({
      history,
      beforeWorkspaceRebind,
      resolveProjectDirectory,
    });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));
    await act(async () => result.current.select(PROJECT.workspaceId));
    const openCalls = vi.mocked(history.workspaceOpen!).mock.calls.length;

    await act(async () => {
      await result.current.updateProject({
        workspaceId: PROJECT.workspaceId,
        expectedRevision: PROJECT.revision,
        displayName: "别名路径项目",
        cwd: "C:\\alias\\demo",
      });
    });

    expect(resolveProjectDirectory).toHaveBeenNthCalledWith(1, "C:\\alias\\demo");
    expect(resolveProjectDirectory).toHaveBeenNthCalledWith(2, PROJECT.root);
    expect(history.workspaceUpdate).toHaveBeenCalledExactlyOnceWith({
      workspaceId: PROJECT.workspaceId,
      expectedRevision: PROJECT.revision,
      displayName: "别名路径项目",
    });
    expect(beforeWorkspaceRebind).not.toHaveBeenCalled();
    expect(history.workspaceOpen).toHaveBeenCalledTimes(openCalls);
    expect(result.current.bindingReady).toBe(true);
  });

  /** 新目录解析失败必须先于资源 lease，避免无效目标关闭仍有效的旧工作面。 */
  it("目标目录无法解析时保留当前 binding 且不启动资源清理", async () => {
    const history = historyPort();
    history.workspaceUpdate = vi.fn(async () => PROJECT);
    const beforeWorkspaceRebind = vi.fn(async () => undefined);
    const resolveFailure = new Error("目标目录不存在");
    const resolveProjectDirectory = vi.fn(async () => {
      throw resolveFailure;
    });
    const { result } = renderWorkspaceController({
      history,
      beforeWorkspaceRebind,
      resolveProjectDirectory,
    });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));
    await act(async () => result.current.select(PROJECT.workspaceId));

    await act(async () => {
      await expect(
        result.current.updateProject({
          workspaceId: PROJECT.workspaceId,
          expectedRevision: PROJECT.revision,
          cwd: "D:\\missing",
        }),
      ).rejects.toBe(resolveFailure);
    });

    expect(beforeWorkspaceRebind).not.toHaveBeenCalled();
    expect(history.workspaceUpdate).not.toHaveBeenCalled();
    expect(result.current.bindingReady).toBe(true);
  });

  /** 丢失写入回包时以同 revision 前进、显示名和 canonical root 确认一次提交，不重放 RPC。 */
  it("传输失败回读到同一草稿时按已提交结果完成重绑", async () => {
    let persisted = { ...PROJECT };
    const moved = {
      ...PROJECT,
      root: "D:\\new alias",
      displayName: "新目录项目",
      revision: 2,
    };
    const history = historyPort();
    history.workspaceList = vi.fn(async () => ({ items: [persisted], nextCursor: null }));
    history.workspaceRead = vi.fn(async () => persisted);
    history.workspaceUpdate = vi.fn(async () => {
      persisted = moved;
      throw new Error("write response lost");
    });
    history.workspaceOpen = vi.fn(async () => persisted);
    const resolveProjectDirectory = vi.fn(async (cwd: string) => {
      if (cwd === "C:\\demo") return "\\\\?\\C:\\demo";
      if (cwd === "D:\\new alias") return "\\\\?\\D:\\new";
      throw new Error("unexpected path");
    });
    const beforeWorkspaceRebind = vi.fn(async () => ({
      commit: vi.fn(),
      release: vi.fn(),
    }));
    const { result } = renderWorkspaceController({
      history,
      beforeWorkspaceRebind,
      resolveProjectDirectory,
    });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));
    await act(async () => result.current.select(PROJECT.workspaceId));

    await act(async () => {
      await result.current.updateProject({
        workspaceId: PROJECT.workspaceId,
        expectedRevision: PROJECT.revision,
        displayName: moved.displayName,
        cwd: moved.root,
      });
    });

    expect(history.workspaceUpdate).toHaveBeenCalledExactlyOnceWith({
      workspaceId: PROJECT.workspaceId,
      expectedRevision: PROJECT.revision,
      displayName: moved.displayName,
      cwd: moved.root,
    });
    expect(history.workspaceOpen).toHaveBeenLastCalledWith({ workspaceId: PROJECT.workspaceId });
    expect(result.current.workspace).toMatchObject({
      workspaceId: PROJECT.workspaceId,
      rootPath: moved.root,
      revision: moved.revision,
    });
    expect(result.current.bindingReady).toBe(true);
  });

  /** 双读均失败后禁止盲目重提；显式复核先排空旧 Thread 资源，再按同一 ID 重绑。 @author kongweiguang */
  it("目录更新双失败保持关闭，显式权威复核后恢复原项目", async () => {
    const persisted = { ...PROJECT };
    let failRecoveryRead = false;
    const history = historyPort();
    history.workspaceList = vi.fn(async () => ({ items: [persisted], nextCursor: null }));
    history.workspaceRead = vi.fn(async ({ workspaceId }) => {
      if (workspaceId !== PROJECT.workspaceId) throw new Error("workspace missing");
      if (failRecoveryRead) {
        failRecoveryRead = false;
        throw new Error("authority read unavailable");
      }
      return persisted;
    });
    history.workspaceOpen = vi.fn(async () => persisted);
    history.workspaceUpdate = vi.fn(async () => {
      failRecoveryRead = true;
      throw new Error("update response unavailable");
    });
    const leases: WorkspaceRebindLease[] = [];
    const beforeWorkspaceRebind = vi.fn(async () => {
      const lease = { commit: vi.fn(), release: vi.fn() };
      leases.push(lease);
      return lease;
    });
    const resolveProjectDirectory = vi.fn(async (cwd: string) => cwd);
    const { result } = renderWorkspaceController({
      history,
      beforeWorkspaceRebind,
      resolveProjectDirectory,
    });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));
    await act(async () => result.current.select(PROJECT.workspaceId));
    expect(result.current.bindingReady).toBe(true);

    let failure: unknown;
    await act(async () => {
      try {
        await result.current.updateProject({
          workspaceId: PROJECT.workspaceId,
          expectedRevision: PROJECT.revision,
          cwd: "D:\\unknown-outcome",
        });
      } catch (error: unknown) {
        failure = error;
      }
    });
    expect(failure).toBeInstanceOf(WorkspaceProjectUpdateError);
    expect(failure).toMatchObject({
      reason: "result_unknown",
      currentWorkspace: undefined,
    });
    expect(result.current.bindingReady).toBe(false);
    expect(result.current.directoryState).toBe("unavailable");
    expect(history.workspaceUpdate).toHaveBeenCalledTimes(1);
    expect(leases[0]?.commit).toHaveBeenCalledOnce();

    await act(async () => {
      await expect(
        result.current.updateProject({
          workspaceId: PROJECT.workspaceId,
          expectedRevision: PROJECT.revision,
          cwd: "D:\\unknown-outcome",
        }),
      ).rejects.toMatchObject({ reason: "result_unknown" });
    });
    expect(history.workspaceUpdate).toHaveBeenCalledTimes(1);

    await act(async () => {
      await result.current.recheckProjectBinding(PROJECT.workspaceId);
    });
    expect(history.workspaceRead).toHaveBeenLastCalledWith({
      workspaceId: PROJECT.workspaceId,
    });
    expect(history.workspaceOpen).toHaveBeenLastCalledWith({
      workspaceId: PROJECT.workspaceId,
    });
    expect(leases[1]?.commit).toHaveBeenCalledOnce();
    expect(result.current.workspace).toMatchObject({
      workspaceId: PROJECT.workspaceId,
      rootPath: PROJECT.root,
    });
    expect(result.current.bindingReady).toBe(true);
  });

  /** 即使编辑只有名称，双失败也先封住新 native IO；未保存资源只在用户显式复核时处置。 @author kongweiguang */
  it("名称更新双失败先失效 binding，显式复核才请求资源 lease", async () => {
    const persisted = { ...PROJECT };
    let failRecoveryRead = false;
    const history = historyPort();
    history.workspaceList = vi.fn(async () => ({ items: [persisted], nextCursor: null }));
    history.workspaceRead = vi.fn(async () => {
      if (failRecoveryRead) {
        failRecoveryRead = false;
        throw new Error("authority read unavailable");
      }
      return persisted;
    });
    history.workspaceOpen = vi.fn(async () => persisted);
    history.workspaceUpdate = vi.fn(async ({ displayName }) => {
      failRecoveryRead = true;
      throw new Error(`reply lost for ${displayName}`);
    });
    const lease: WorkspaceRebindLease = { commit: vi.fn(), release: vi.fn() };
    const beforeWorkspaceRebind = vi.fn(async () => lease);
    const { result } = renderWorkspaceController({ history, beforeWorkspaceRebind });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));
    await act(async () => result.current.select(PROJECT.workspaceId));
    expect(result.current.bindingReady).toBe(true);

    await act(async () => {
      await expect(
        result.current.updateProject({
          workspaceId: PROJECT.workspaceId,
          expectedRevision: PROJECT.revision,
          displayName: "名称暂不确定",
        }),
      ).rejects.toMatchObject({ reason: "result_unknown", currentWorkspace: undefined });
    });
    expect(result.current.bindingReady).toBe(false);
    expect(result.current.directoryState).toBe("unavailable");
    expect(beforeWorkspaceRebind).not.toHaveBeenCalled();
    expect(history.workspaceUpdate).toHaveBeenCalledTimes(1);

    await act(async () => result.current.recheckProjectBinding(PROJECT.workspaceId));
    expect(beforeWorkspaceRebind).toHaveBeenCalledExactlyOnceWith(PROJECT.workspaceId);
    expect(lease.commit).toHaveBeenCalledOnce();
    expect(result.current.bindingReady).toBe(true);
    expect(result.current.workspace).toMatchObject({
      workspaceId: PROJECT.workspaceId,
      rootPath: PROJECT.root,
    });
  });

  /** 项目复核由项目 mutation 所有；同项目导航推进全局 intent 时，失败仍须退出 checking。 @author kongweiguang */
  it("复核等待 ID-open 时同项目选择不会让失败状态卡在 checking", async () => {
    let rejectRecheck!: (cause: unknown) => void;
    let openCalls = 0;
    const history = historyPort();
    history.workspaceList = vi.fn(async () => ({ items: [PROJECT], nextCursor: null }));
    history.workspaceOpen = vi.fn(async () => {
      openCalls += 1;
      if (openCalls === 2) throw new Error("runtime restart");
      if (openCalls === 3)
        return await new Promise<typeof PROJECT>((_resolve, reject) => {
          rejectRecheck = reject;
        });
      return PROJECT;
    });
    const { result, setRuntimeState } = renderWorkspaceController({ history });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));
    await act(async () => result.current.select(PROJECT.workspaceId));
    expect(result.current.bindingReady).toBe(true);

    await act(async () => {
      setRuntimeState({ status: "ready", generation: 2, serverInstanceId: "srv_recheck" });
    });
    await waitFor(() => expect(history.workspaceOpen).toHaveBeenCalledTimes(2));
    expect(result.current.directoryState).toBe("unavailable");

    let recheck!: Promise<WorkspaceProjection>;
    act(() => {
      recheck = result.current.recheckProjectBinding(PROJECT.workspaceId);
    });
    await waitFor(() => expect(history.workspaceOpen).toHaveBeenCalledTimes(3));
    expect(result.current.directoryState).toBe("checking");

    await act(async () => result.current.select(PROJECT.workspaceId));
    await act(async () => {
      rejectRecheck(new Error("directory is still unavailable"));
      await expect(recheck).rejects.toThrow("directory is still unavailable");
    });

    expect(history.workspaceOpen).toHaveBeenCalledTimes(3);
    expect(result.current.busy).toBe(false);
    expect(result.current.directoryState).toBe("unavailable");
    expect(result.current.bindingReady).toBe(false);
  });

  /** 未选中项目复核只读取/排空该项目，不夺走 active ID；新 root CAS 可在后续明确保存中修复 native fence。 @author kongweiguang */
  it("未选中项目双失败复核不激活项目且允许按最新 revision 继续保存", async () => {
    const projectA = { ...PROJECT };
    let projectB = { ...PROJECT_B };
    let failRecoveryRead = false;
    let updates = 0;
    const history = historyPort();
    history.workspaceList = vi.fn(async () => ({
      items: [projectA, projectB],
      nextCursor: null,
    }));
    history.workspaceRead = vi.fn(async ({ workspaceId }) => {
      if (failRecoveryRead && workspaceId === projectB.workspaceId) {
        failRecoveryRead = false;
        throw new Error("authority read unavailable");
      }
      if (workspaceId === projectA.workspaceId) return projectA;
      if (workspaceId === projectB.workspaceId) return projectB;
      throw new Error("workspace missing");
    });
    history.workspaceOpen = vi.fn(async ({ workspaceId }) =>
      workspaceId === projectA.workspaceId ? projectA : projectB,
    );
    history.workspaceUpdate = vi.fn(async (input) => {
      updates += 1;
      if (updates === 1) {
        failRecoveryRead = true;
        throw new Error("update response unavailable");
      }
      projectB = {
        ...projectB,
        root: input.cwd ?? projectB.root,
        displayName: input.displayName ?? projectB.displayName,
        revision: projectB.revision + 1,
      };
      return projectB;
    });
    const leases: WorkspaceRebindLease[] = [];
    const beforeWorkspaceRebind = vi.fn(async () => {
      const lease = { commit: vi.fn(), release: vi.fn() };
      leases.push(lease);
      return lease;
    });
    const { result, activateWorkspace } = renderWorkspaceController({
      history,
      beforeWorkspaceRebind,
      resolveProjectDirectory: async (cwd) => cwd,
    });
    await waitFor(() => expect(result.current.projects).toHaveLength(2));
    await act(async () => result.current.select(projectA.workspaceId));
    const activeProject = result.current.workspace;

    await act(async () => {
      await expect(
        result.current.updateProject({
          workspaceId: projectB.workspaceId,
          expectedRevision: projectB.revision,
          cwd: "D:\\unknown-b",
        }),
      ).rejects.toMatchObject({ reason: "result_unknown" });
    });
    await act(async () => result.current.recheckProjectBinding(projectB.workspaceId));

    expect(result.current.workspace).toBe(activeProject);
    expect(result.current.bindingReady).toBe(true);
    expect(history.workspaceOpen).not.toHaveBeenCalledWith({
      workspaceId: projectB.workspaceId,
    });
    expect(activateWorkspace).not.toHaveBeenCalledWith(projectB.workspaceId);
    expect(leases[1]?.commit).toHaveBeenCalledOnce();
    expect(updates).toBe(1);

    await act(async () => {
      await result.current.updateProject({
        workspaceId: projectB.workspaceId,
        expectedRevision: projectB.revision,
        cwd: "E:\\recovered-b",
      });
    });
    expect(updates).toBe(2);
    expect(projectB).toMatchObject({ root: "E:\\recovered-b", revision: 2 });
    expect(result.current.workspace).toBe(activeProject);
    expect(history.workspaceOpen).not.toHaveBeenCalledWith({
      workspaceId: projectB.workspaceId,
    });
  });

  /** CAS 读回他人已改的 root 时旧 UI capability 立即失效，并把权威投影交给冲突恢复入口。 @author kongweiguang */
  it("CAS 冲突发现他人换 root 后收回旧绑定并携带权威资料", async () => {
    let persisted = { ...PROJECT };
    const history = historyPort();
    history.workspaceList = vi.fn(async () => ({ items: [persisted], nextCursor: null }));
    history.workspaceRead = vi.fn(async () => persisted);
    history.workspaceOpen = vi.fn(async () => persisted);
    history.workspaceUpdate = vi.fn(async () => {
      persisted = {
        ...PROJECT,
        root: "E:\\other-writer",
        displayName: "另一个修改",
        revision: 2,
      };
      throw workspacePortError("CONFLICT", "revision changed", true);
    });
    const lease: WorkspaceRebindLease = { commit: vi.fn(), release: vi.fn() };
    const beforeWorkspaceRebind = vi.fn(async () => lease);
    const { result } = renderWorkspaceController({ history, beforeWorkspaceRebind });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));
    await act(async () => result.current.select(PROJECT.workspaceId));
    expect(result.current.bindingReady).toBe(true);

    let failure: unknown;
    await act(async () => {
      try {
        await result.current.updateProject({
          workspaceId: PROJECT.workspaceId,
          expectedRevision: PROJECT.revision,
          displayName: "我的修改",
        });
      } catch (error: unknown) {
        failure = error;
      }
    });
    expect(failure).toBeInstanceOf(WorkspaceProjectUpdateError);
    expect(failure).toMatchObject({
      reason: "revision_conflict",
      currentWorkspace: { rootPath: persisted.root, revision: persisted.revision },
    });
    expect(result.current.workspace).toMatchObject({ rootPath: persisted.root });
    expect(result.current.bindingReady).toBe(false);
    expect(result.current.directoryState).toBe("unavailable");
    expect(lease.commit).toHaveBeenCalledOnce();
  });

  /** 外部 metadata 已先改变 DB root；取消先关闭旧绑定，成功重试后再发布新设置 scope。 */
  it("目录变更取消后不发布未绑定 root，重新检查成功才刷新设置 scope", async () => {
    let persisted = { ...PROJECT };
    const history = historyPort();
    history.workspaceList = vi.fn(async () => ({ items: [persisted], nextCursor: null }));
    history.workspaceRead = vi.fn(async () => persisted);
    history.workspaceOpen = vi.fn(async () => persisted);
    let cancelFirstRebind = true;
    const beforeWorkspaceRebind = vi.fn(async () => {
      if (cancelFirstRebind) {
        cancelFirstRebind = false;
        throw new DOMException("保留未保存文件", "AbortError");
      }
      return undefined;
    });
    const { result, onWorkspaceCommitted } = renderWorkspaceController({
      history,
      beforeWorkspaceRebind,
    });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));
    await act(async () => result.current.select(PROJECT.workspaceId));
    onWorkspaceCommitted.mockClear();
    expect(result.current.bindingReady).toBe(true);

    persisted = { ...PROJECT, root: "D:\\新目录\\demo", revision: 2 };
    await act(async () => result.current.refreshProjectMetadata(PROJECT.workspaceId, 2));

    expect(beforeWorkspaceRebind).toHaveBeenCalledExactlyOnceWith(PROJECT.workspaceId);
    expect(result.current.workspace).toMatchObject({ rootPath: persisted.root });
    expect(result.current.projects[0]).toMatchObject({ rootPath: persisted.root });
    expect(result.current.bindingReady).toBe(false);
    expect(result.current.directoryState).toBe("unavailable");
    expect(onWorkspaceCommitted).not.toHaveBeenCalled();

    await act(async () => result.current.retryBinding());

    expect(result.current.bindingReady).toBe(true);
    expect(onWorkspaceCommitted).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({
        workspaceId: PROJECT.workspaceId,
        rootPath: persisted.root,
      }),
    );
  });

  /** revision 回读优先解释 CAS 冲突；原生 CONFLICT 且版本未变对应 Java 的目录唯一性冲突。 */
  it("保存失败按 authoritative revision 区分并发编辑与目录占用", async () => {
    let persisted = { ...PROJECT };
    const conflictHistory = historyPort();
    conflictHistory.workspaceList = vi.fn(async () => ({ items: [persisted], nextCursor: null }));
    conflictHistory.workspaceUpdate = vi.fn(async () => {
      persisted = { ...PROJECT, displayName: "其他人的修改", revision: 2 };
      throw workspacePortError("CONFLICT", "对话状态已变化，请刷新后重试", true);
    });
    const conflict = renderWorkspaceController({ history: conflictHistory });
    await waitFor(() => expect(conflict.result.current.projects).toHaveLength(1));

    await act(async () => {
      await expect(
        conflict.result.current.updateProject({
          workspaceId: PROJECT.workspaceId,
          expectedRevision: PROJECT.revision,
          displayName: "我的修改",
        }),
      ).rejects.toThrow("项目资料已被其他修改");
    });

    conflict.unmount();
    const occupiedHistory = historyPort();
    occupiedHistory.workspaceUpdate = vi.fn(async () => {
      throw workspacePortError("CONFLICT", "对话状态已变化，请刷新后重试", true);
    });
    const occupied = renderWorkspaceController({ history: occupiedHistory });
    await waitFor(() => expect(occupied.result.current.projects).toHaveLength(1));

    await act(async () => {
      await expect(
        occupied.result.current.updateProject({
          workspaceId: PROJECT.workspaceId,
          expectedRevision: PROJECT.revision,
          displayName: PROJECT.displayName,
          cwd: "D:\\occupied",
        }),
      ).rejects.toThrow("该目录已被另一个项目占用，请选择其他目录。");
    });

    occupied.unmount();
    const rawErrorHistory = historyPort();
    const rawError = new Error("项目目录缺少访问权限。");
    rawErrorHistory.workspaceUpdate = vi.fn(async () => {
      throw rawError;
    });
    const rawFailure = renderWorkspaceController({ history: rawErrorHistory });
    await waitFor(() => expect(rawFailure.result.current.projects).toHaveLength(1));

    await act(async () => {
      await expect(
        rawFailure.result.current.updateProject({
          workspaceId: PROJECT.workspaceId,
          expectedRevision: PROJECT.revision,
          displayName: PROJECT.displayName,
          cwd: "D:\\no-access",
        }),
      ).rejects.toBe(rawError);
    });
  });

  /** 本地 CAS 尚未结束时缓存外部事件；确认本地冲突后再读权威 root，避免双重资源关闭弹窗。 */
  it("本地重绑守卫期间到达的外部 root 变更只使用一次 lease", async () => {
    let persisted = { ...PROJECT };
    let releaseGuard!: (lease: WorkspaceRebindLease) => void;
    const guard = new Promise<WorkspaceRebindLease>((resolve) => {
      releaseGuard = resolve;
    });
    const history = historyPort();
    history.workspaceList = vi.fn(async () => ({ items: [persisted], nextCursor: null }));
    history.workspaceRead = vi.fn(async () => persisted);
    history.workspaceOpen = vi.fn(async () => persisted);
    history.workspaceUpdate = vi.fn(async () => {
      throw { code: "CONFLICT" };
    });
    const beforeWorkspaceRebind = vi.fn(async () => guard);
    const { result } = renderWorkspaceController({ history, beforeWorkspaceRebind });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));
    await act(async () => result.current.select(PROJECT.workspaceId));

    let localUpdate!: Promise<WorkspaceProjection>;
    act(() => {
      localUpdate = result.current.updateProject({
        workspaceId: PROJECT.workspaceId,
        expectedRevision: PROJECT.revision,
        cwd: "D:\\local-change",
      });
    });
    await waitFor(() => expect(beforeWorkspaceRebind).toHaveBeenCalledOnce());
    persisted = { ...PROJECT, root: "E:\\external-change", revision: 3 };

    await act(async () => result.current.refreshProjectMetadata(PROJECT.workspaceId, 3));
    expect(result.current.workspace).toMatchObject({ rootPath: persisted.root });
    expect(result.current.bindingReady).toBe(false);
    expect(beforeWorkspaceRebind).toHaveBeenCalledOnce();

    const lease: WorkspaceRebindLease = { commit: vi.fn(), release: vi.fn() };
    await act(async () => {
      releaseGuard(lease);
      await expect(localUpdate).rejects.toThrow("项目资料已被其他修改");
      await Promise.resolve();
    });

    expect(beforeWorkspaceRebind).toHaveBeenCalledOnce();
    expect(lease.commit).toHaveBeenCalledOnce();
    expect(lease.release).toHaveBeenCalledOnce();
    expect(result.current.workspace).toMatchObject({ rootPath: persisted.root });
    expect(result.current.bindingReady).toBe(false);
  });

  /** Trust 是独立的服务端权限状态；回读不得伪装成 workspace 切换或新 revision。 */
  it("信任变更回读权威项目状态且不提交 workspace 切换或新 revision", async () => {
    let listedProject = { ...PROJECT, trust: "trusted" as const };
    const history = historyPort();
    history.workspaceOpen = vi.fn(async () => listedProject);
    history.workspaceList = vi.fn(async () => ({ items: [listedProject], nextCursor: null }));
    history.workspaceSetTrust = vi.fn(async ({ trust }) => {
      listedProject = { ...listedProject, trust };
      return { accepted: true as const };
    });
    const beforeWorkspaceChange = vi.fn(async () => undefined);
    const { result, onWorkspaceCommitted } = renderWorkspaceController({
      history,
      beforeWorkspaceChange,
    });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));
    await act(async () => result.current.select(PROJECT.workspaceId));
    const revision = result.current.revision;
    const committedCount = onWorkspaceCommitted.mock.calls.length;

    let changed: Awaited<ReturnType<typeof result.current.setProjectTrust>> | undefined;
    await act(async () => {
      changed = await result.current.setProjectTrust(PROJECT.workspaceId, "untrusted");
    });

    expect(history.workspaceSetTrust).toHaveBeenCalledExactlyOnceWith({
      workspaceId: PROJECT.workspaceId,
      trust: "untrusted",
    });
    expect(changed?.trust).toBe("untrusted");
    expect(result.current.projects[0]?.trust).toBe("untrusted");
    expect(result.current.workspace).toMatchObject({
      workspaceId: PROJECT.workspaceId,
      trust: "untrusted",
    });
    expect(result.current.revision).toBe(revision);
    expect(onWorkspaceCommitted).toHaveBeenCalledTimes(committedCount);
    expect(beforeWorkspaceChange).not.toHaveBeenCalled();
  });

  /** 服务端可能先持久化后返回错误，因此失败结果也要回读权威状态供同目标重试。 */
  it("mutation 报错后仍回读已持久化的 trust，供同目标重试", async () => {
    let listedProject = { ...PROJECT, trust: "trusted" as const };
    const history = historyPort();
    history.workspaceList = vi.fn(async () => ({ items: [listedProject], nextCursor: null }));
    history.workspaceSetTrust = vi.fn(async ({ trust }) => {
      listedProject = { ...listedProject, trust };
      throw new Error("sync failed after persistence");
    });
    const { result } = renderWorkspaceController({ history });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));

    await act(async () => {
      await expect(
        result.current.setProjectTrust(PROJECT.workspaceId, "untrusted"),
      ).rejects.toThrow("sync failed after persistence");
    });

    expect(history.workspaceList).toHaveBeenCalledTimes(2);
    expect(result.current.projects[0]?.trust).toBe("untrusted");
    expect(history.workspaceSetTrust).toHaveBeenCalledExactlyOnceWith({
      workspaceId: PROJECT.workspaceId,
      trust: "untrusted",
    });
  });

  /** 异步 trust 响应按固定项目 ID 合并，不能覆盖期间切换到的当前项目投影。 */
  it("信任响应晚到时只更新原项目，不覆盖当前已切换的项目", async () => {
    let resolveTrust!: (value: { accepted: true }) => void;
    let listedProjects = [PROJECT, PROJECT_B];
    const history = historyPort();
    history.workspaceList = vi.fn(async () => ({ items: listedProjects, nextCursor: null }));
    history.workspaceOpen = vi.fn(async (input) => {
      const project =
        "workspaceId" in input
          ? listedProjects.find((candidate) => candidate.workspaceId === input.workspaceId)
          : listedProjects.find((candidate) => candidate.root === input.cwd);
      if (project === undefined) throw new Error("unexpected project");
      return project;
    });
    history.workspaceSetTrust = vi.fn(
      () => new Promise<{ accepted: true }>((resolve) => (resolveTrust = resolve)),
    );
    const { result } = renderWorkspaceController({ history });
    await waitFor(() => expect(result.current.projects).toHaveLength(2));
    let trustPromise!: Promise<Awaited<ReturnType<typeof result.current.setProjectTrust>>>;
    act(() => {
      trustPromise = result.current.setProjectTrust(PROJECT.workspaceId, "trusted");
    });
    await act(async () => result.current.select(PROJECT_B.workspaceId));
    const revision = result.current.revision;
    listedProjects = [{ ...PROJECT, trust: "trusted" }, PROJECT_B];
    resolveTrust({ accepted: true });
    await act(async () => trustPromise);

    expect(result.current.workspace).toMatchObject({
      workspaceId: PROJECT_B.workspaceId,
      trust: "trusted",
    });
    expect(result.current.projects).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ workspaceId: PROJECT.workspaceId, trust: "trusted" }),
        expect.objectContaining({ workspaceId: PROJECT_B.workspaceId, trust: "trusted" }),
      ]),
    );
    expect(result.current.revision).toBe(revision);
  });

  /** 先发的目录快照不能覆盖信任回读结果，所有返回顺序都必须释放 loading 状态。 */
  it("信任回读后丢弃写入前启动的旧目录快照并结束加载态", async () => {
    let releaseCatalog!: (value: { items: (typeof PROJECT)[]; nextCursor: null }) => void;
    let listedProject = { ...PROJECT, trust: "trusted" as const };
    let listCalls = 0;
    const history = historyPort();
    history.workspaceList = vi.fn(async () => {
      listCalls += 1;
      if (listCalls === 1)
        return new Promise<{ items: (typeof PROJECT)[]; nextCursor: null }>(
          (resolve) => (releaseCatalog = resolve),
        );
      return { items: [listedProject], nextCursor: null };
    });
    history.workspaceSetTrust = vi.fn(async ({ trust }) => {
      listedProject = { ...listedProject, trust };
      return { accepted: true as const };
    });
    const { result } = renderWorkspaceController({ history });
    await waitFor(() => expect(history.workspaceList).toHaveBeenCalledOnce());
    let trustPromise!: Promise<Awaited<ReturnType<typeof result.current.setProjectTrust>>>;
    act(() => {
      trustPromise = result.current.setProjectTrust(PROJECT.workspaceId, "untrusted");
    });
    await act(async () => trustPromise);
    expect(result.current.projects[0]?.trust).toBe("untrusted");
    expect(result.current.catalogLoading).toBe(false);

    releaseCatalog({ items: [PROJECT], nextCursor: null });
    await waitFor(() => expect(result.current.catalogLoading).toBe(false));
    expect(result.current.projects[0]?.trust).toBe("untrusted");
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
    });
  });

  /** 同一 ready identity 的配置准入只允许补目录读取，不能夺走已发起的会话激活 intent。 @author kongweiguang */
  it("配置从未就绪变为就绪时保留同一 Runtime 下已发起的 session 激活", async () => {
    let resolveActivation!: (workspace: WorkspaceActivationRecord) => void;
    const activation = new Promise<WorkspaceActivationRecord>((resolve) => {
      resolveActivation = resolve;
    });
    const activateWorkspace = vi.fn(async () => activation);
    const { result, setConfigurationReady } = renderWorkspaceController({
      activateWorkspace,
      configurationReady: false,
      runtimeState: READY_RUNTIME_STATE,
    });
    let selection!: Promise<WorkspaceProjection | undefined>;

    await act(async () => {
      selection = result.current.activateForConversation(SESSION_A.workspaceId);
      await waitFor(() => expect(activateWorkspace).toHaveBeenCalledOnce());
    });
    await act(async () => setConfigurationReady(true));

    let activated: WorkspaceProjection | undefined;
    await act(async () => {
      resolveActivation(SESSION_A);
      activated = await selection;
    });

    expect(activated).toMatchObject({
      kind: "session",
      workspaceId: SESSION_A.workspaceId,
    });
    expect(result.current.workspace).toMatchObject({ workspaceId: SESSION_A.workspaceId });
    expect(result.current.bindingReady).toBe(true);
  });

  /** 身份未知期间发起的激活不能在新 SID/generation 到达后冒充新实例的绑定。 @author kongweiguang */
  it("Runtime identity 从未知变为 ready 时拒绝未知身份下的迟到 session 激活", async () => {
    let resolveActivation!: (workspace: WorkspaceActivationRecord) => void;
    const activation = new Promise<WorkspaceActivationRecord>((resolve) => {
      resolveActivation = resolve;
    });
    const activateWorkspace = vi.fn(async () => activation);
    const { result, setRuntimeState } = renderWorkspaceController({
      activateWorkspace,
      runtimeState: undefined,
    });
    let selection!: Promise<WorkspaceProjection | undefined>;

    await act(async () => {
      selection = result.current.activateForConversation(SESSION_A.workspaceId);
      await waitFor(() => expect(activateWorkspace).toHaveBeenCalledOnce());
    });
    await act(async () => {
      setRuntimeState({ status: "ready", generation: 1, serverInstanceId: "srv_new" });
    });

    let activated: WorkspaceProjection | undefined;
    await act(async () => {
      resolveActivation(SESSION_A);
      activated = await selection;
    });

    expect(activated).toBeUndefined();
    expect(result.current.workspace).toBeUndefined();
    expect(result.current.bindingReady).toBe(false);
  });

  /** 服务端代际在不同进程间可复用；恢复不能改 Thread scope 或关闭当前原生资源。 */
  it("新 Runtime 实例即使 generation 相同也重绑当前会话目录且不执行 workspace fence", async () => {
    const activateWorkspace = activationPort();
    const beforeWorkspaceChange = vi.fn(async () => undefined);
    const { result, setRuntimeState } = renderWorkspaceController({
      activateWorkspace,
      beforeWorkspaceChange,
    });
    await act(async () => result.current.activateForConversation(SESSION_A.workspaceId));
    const revision = result.current.revision;
    vi.mocked(activateWorkspace).mockClear();

    await act(async () => {
      setRuntimeState({ status: "ready", generation: 1, serverInstanceId: "srv_2" });
    });

    await waitFor(() =>
      expect(activateWorkspace).toHaveBeenCalledExactlyOnceWith(SESSION_A.workspaceId),
    );
    await waitFor(() => expect(result.current.bindingReady).toBe(true));
    expect(result.current.workspace).toMatchObject({
      workspaceId: SESSION_A.workspaceId,
      rootPath: SESSION_A.rootPath,
    });
    expect(result.current.revision).toBe(revision);
    expect(beforeWorkspaceChange).not.toHaveBeenCalled();
  });

  /** Runtime 投影对象可随 App render 重建；相同 server/generation identity 不应再次读写绑定。 */
  it("同 Runtime identity 的新投影对象不重复恢复项目 binding", async () => {
    const history = historyPort();
    const { result, setRuntimeState } = renderWorkspaceController({ history });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));
    await act(async () => result.current.select(PROJECT.workspaceId));
    const openCalls = vi.mocked(history.workspaceOpen!).mock.calls.length;

    await act(async () => {
      setRuntimeState({ ...READY_RUNTIME_STATE });
      setRuntimeState({ ...READY_RUNTIME_STATE });
      setRuntimeState({ ...READY_RUNTIME_STATE });
    });

    expect(history.workspaceOpen).toHaveBeenCalledTimes(openCalls);
    expect(result.current.bindingReady).toBe(true);
    expect(result.current.busy).toBe(false);
  });

  /** DTO 拒绝 ID-only open 后，同一 Runtime 的投影重建不得形成重试闪烁或永久 busy。 @author kongweiguang */
  it("项目恢复失败后同 Runtime 新投影保持不可用状态且不重复请求", async () => {
    const history = historyPort();
    let rejectIdentityOpen = false;
    history.workspaceOpen = vi.fn(async (input) => {
      if ("workspaceId" in input && rejectIdentityOpen)
        throw new Error("invalid type: map, expected string");
      return PROJECT;
    });
    const { result, setRuntimeState } = renderWorkspaceController({ history });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));
    await act(async () => result.current.select(PROJECT.workspaceId));
    rejectIdentityOpen = true;

    await act(async () => {
      setRuntimeState({ status: "ready", generation: 2, serverInstanceId: "srv_2" });
    });

    await waitFor(() => {
      expect(result.current.directoryState).toBe("unavailable");
      expect(result.current.busy).toBe(false);
    });
    expect(result.current.bindingReady).toBe(false);
    expect(result.current.error).toContain("项目目录不可用");
    const failedOpenCalls = vi
      .mocked(history.workspaceOpen!)
      .mock.calls.filter(([input]) => "workspaceId" in input).length;

    await act(async () => {
      setRuntimeState({ status: "ready", generation: 2, serverInstanceId: "srv_2" });
      setRuntimeState({ status: "ready", generation: 2, serverInstanceId: "srv_2" });
    });

    expect(
      vi.mocked(history.workspaceOpen!).mock.calls.filter(([input]) => "workspaceId" in input),
    ).toHaveLength(failedOpenCalls);
    expect(result.current.directoryState).toBe("unavailable");
    expect(result.current.bindingReady).toBe(false);
    expect(result.current.busy).toBe(false);
    expect(result.current.error).toContain("项目目录不可用");
  });

  /** Runtime 恢复按稳定项目 ID 查找目录，不能靠路径重新推导身份或 flush 工作面。 */
  it("新 Runtime 实例按当前项目身份恢复且保持同一 Workspace revision", async () => {
    const history = historyPort();
    const beforeWorkspaceChange = vi.fn(async () => undefined);
    const { result, setRuntimeState } = renderWorkspaceController({
      history,
      beforeWorkspaceChange,
    });
    await waitFor(() => expect(result.current.projects).toHaveLength(1));
    await act(async () => result.current.select(PROJECT.workspaceId));
    const revision = result.current.revision;
    const originalWorkspaceOpenCount = vi.mocked(history.workspaceOpen!).mock.calls.length;

    await act(async () => {
      setRuntimeState({ status: "ready", generation: 1, serverInstanceId: "srv_2" });
    });

    await waitFor(() => expect(result.current.bindingReady).toBe(true));
    expect(history.workspaceOpen).toHaveBeenCalledTimes(originalWorkspaceOpenCount + 1);
    expect(history.workspaceOpen).toHaveBeenLastCalledWith({ workspaceId: PROJECT.workspaceId });
    expect(result.current.workspace?.workspaceId).toBe(PROJECT.workspaceId);
    expect(result.current.revision).toBe(revision);
    expect(beforeWorkspaceChange).not.toHaveBeenCalled();
  });

  /** Runtime 重启后原 native binding 已无效，后续显式切换不能 flush 旧 Workbench 资源。 */
  it("恢复失败后切换项目跳过失效 binding fence 并进入新项目", async () => {
    const history = historyPort();
    let failNextOpen = false;
    history.workspaceOpen = vi.fn(async (input) => {
      if (failNextOpen) {
        failNextOpen = false;
        throw new Error("runtime restarted");
      }
      if ("workspaceId" in input)
        return input.workspaceId === PROJECT_B.workspaceId ? PROJECT_B : PROJECT;
      return input.cwd === PROJECT_B.root ? PROJECT_B : PROJECT;
    });
    history.workspaceList = vi.fn(async (input) => ({
      items: [PROJECT, PROJECT_B].filter(
        (candidate) => input?.kind === undefined || candidate.kind === input.kind,
      ),
      nextCursor: null,
    }));
    const beforeWorkspaceChange = vi.fn(async () => undefined);
    const { result, setRuntimeState } = renderWorkspaceController({
      history,
      beforeWorkspaceChange,
    });
    await waitFor(() => expect(result.current.projects).toHaveLength(2));
    await act(async () => result.current.select(PROJECT.workspaceId));
    failNextOpen = true;

    await act(async () => {
      setRuntimeState({ status: "ready", generation: 1, serverInstanceId: "srv_2" });
    });
    await waitFor(() => expect(result.current.directoryState).toBe("unavailable"));
    expect(result.current.bindingReady).toBe(false);

    await act(async () => result.current.select(PROJECT_B.workspaceId));

    expect(result.current.workspace?.workspaceId).toBe(PROJECT_B.workspaceId);
    expect(result.current.bindingReady).toBe(true);
    expect(beforeWorkspaceChange).not.toHaveBeenCalled();
  });

  /** 新用户选择推进同一 intent fence，旧目录回包不得覆盖新的 Thread workspace。 */
  it("Runtime 重绑中的迟到会话回包不能覆盖用户随后选择的目录", async () => {
    let releaseOldBinding!: (workspace: WorkspaceActivationRecord) => void;
    let deferOldBinding = false;
    const activateWorkspace = vi.fn(async (workspaceId: string) => {
      if (workspaceId === SESSION_A.workspaceId && deferOldBinding) {
        return await new Promise<WorkspaceActivationRecord>((resolve) => {
          releaseOldBinding = resolve;
        });
      }
      if (workspaceId === SESSION_A.workspaceId) return SESSION_A;
      if (workspaceId === SESSION_B.workspaceId) return SESSION_B;
      throw new Error(`unexpected workspace ${workspaceId}`);
    });
    const { result, setRuntimeState } = renderWorkspaceController({ activateWorkspace });
    await act(async () => result.current.activateForConversation(SESSION_A.workspaceId));

    deferOldBinding = true;
    await act(async () => {
      setRuntimeState({ status: "ready", generation: 1, serverInstanceId: "srv_2" });
    });
    await waitFor(() => expect(activateWorkspace).toHaveBeenCalledTimes(2));
    expect(result.current.bindingReady).toBe(false);
    await act(async () => result.current.activateForConversation(SESSION_B.workspaceId));
    await act(async () => releaseOldBinding({ ...SESSION_A, rootPath: "C:\\stale\\root" }));

    expect(result.current.workspace).toMatchObject({
      workspaceId: SESSION_B.workspaceId,
      rootPath: SESSION_B.rootPath,
    });
  });

  /** 旧实例回包必须同时通过 serverInstanceId 与 generation fence。 */
  it("较旧 Runtime 实例的激活回包不能覆盖新实例的同代际恢复", async () => {
    let releaseOldBinding!: (workspace: WorkspaceActivationRecord) => void;
    let activationCount = 0;
    const activateWorkspace = vi.fn(async () => {
      activationCount += 1;
      if (activationCount === 1) return SESSION_A;
      if (activationCount === 2)
        return await new Promise<WorkspaceActivationRecord>((resolve) => {
          releaseOldBinding = resolve;
        });
      return { ...SESSION_A, rootPath: "C:\\fresh\\root" };
    });
    const { result, setRuntimeState } = renderWorkspaceController({ activateWorkspace });
    await act(async () => result.current.activateForConversation(SESSION_A.workspaceId));

    await act(async () => {
      setRuntimeState({ status: "ready", generation: 1, serverInstanceId: "srv_2" });
    });
    await waitFor(() => expect(activateWorkspace).toHaveBeenCalledTimes(2));
    await act(async () => {
      setRuntimeState({ status: "ready", generation: 1, serverInstanceId: "srv_3" });
    });
    await waitFor(() => expect(activateWorkspace).toHaveBeenCalledTimes(3));
    await waitFor(() => expect(result.current.bindingReady).toBe(true));
    expect(result.current.workspace?.rootPath).toBe("C:\\fresh\\root");

    await act(async () => releaseOldBinding({ ...SESSION_A, rootPath: "C:\\stale\\root" }));

    expect(result.current.workspace?.rootPath).toBe("C:\\fresh\\root");
    expect(result.current.bindingReady).toBe(true);
  });

  /** 失败提示维持既有侧栏重试入口；重试只绑定当前 Java identity。 */
  it("重绑失败保留可重试状态，重试只重新激活当前 Java workspaceId", async () => {
    let failNextActivation = false;
    const activateWorkspace = vi.fn(async (workspaceId: string) => {
      if (failNextActivation) {
        failNextActivation = false;
        throw new Error("runtime restarted");
      }
      return workspaceId === SESSION_A.workspaceId ? SESSION_A : SESSION_B;
    });
    const { result, setRuntimeState } = renderWorkspaceController({ activateWorkspace });
    await act(async () => result.current.activateForConversation(SESSION_A.workspaceId));
    failNextActivation = true;

    await act(async () => {
      setRuntimeState({ status: "ready", generation: 1, serverInstanceId: "srv_2" });
    });
    await waitFor(() => expect(result.current.bindingReady).toBe(false));
    expect(result.current.workspace?.workspaceId).toBe(SESSION_A.workspaceId);
    expect(result.current.error).toBeDefined();
    expect(result.current.catalogError).toBeDefined();

    await act(async () => result.current.retryCatalog());

    expect(activateWorkspace).toHaveBeenLastCalledWith(SESSION_A.workspaceId);
    expect(result.current.bindingReady).toBe(true);
    expect(result.current.error).toBeUndefined();
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
      workspaceId: PROJECT.workspaceId,
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

  it("进入项目时聚合清理保留 session，项目通过稳定 ID 重开", async () => {
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
    expect(history.workspaceOpen).toHaveBeenCalledWith({ workspaceId: PROJECT.workspaceId });
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
