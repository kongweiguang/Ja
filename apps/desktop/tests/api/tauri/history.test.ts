// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import {
  JA_HISTORY_COMMANDS,
  TauriHistoryAdapter,
  type HistoryNativeBridge,
} from "@/api/tauri/history";
import { RuntimeHostError } from "@/api/tauri/runtime";

/** 构造权威 project Workspace shape，避免共享协议新增的所有权分类被旧夹具省略。 */
function workspace() {
  return {
    workspaceId: "ws_server",
    kind: "project" as const,
    root: "C:\\demo",
    displayName: "demo",
    trust: "untrusted" as const,
    revision: 0,
  };
}

/** 固定完整 Thread identity；测试只覆盖 adapter 路由，不用省略字段弱化服务端 DTO。 */
function thread(threadId = "thr_fixture") {
  return {
    threadId,
    workspaceId: "ws_server",
    workspaceKind: "project" as const,
    preferences: {
      providerId: "provider_fixture",
      modelId: "model_fixture",
      reasoningLevel: "medium" as const,
      accessMode: "approval_required" as const,
      collaborationMode: "default" as const,
      titleSource: "placeholder" as const,
    },
    title: "Fixture conversation",
    status: "active" as const,
    pinned: false,
    latestTurnStatus: "completed" as const,
    latestTurnSeen: false,
    revision: 0,
    createdAt: "2026-08-18T00:00:00Z",
    updatedAt: "2026-08-18T00:00:00Z",
  };
}

/** 正文预览的 code-point 长度与 nullable source identity 都是 thread/read 的权威字段。 */
function snapshot() {
  return {
    threadId: "thr_fixture",
    revision: 4,
    turns: [
      {
        turnId: "turn_fixture",
        origin: "native" as const,
        sourceMessageId: null,
        status: "completed",
        requestedAt: "2026-08-18T00:00:00Z",
        updatedAt: "2026-08-18T00:00:04Z",
        completedAt: "2026-08-18T00:00:04Z",
        errorCode: null,
        changeSet: null,
      },
    ],
    items: [
      {
        itemId: "item_user",
        turnId: "turn_fixture",
        origin: "native" as const,
        kind: "user_input",
        createdAt: "2026-08-18T00:00:00Z",
        content: [{ type: "text", text: "hello" }],
        totalCharacters: 5,
        truncated: false,
        attachments: [],
      },
      {
        itemId: "item_call",
        turnId: "turn_fixture",
        origin: "native" as const,
        kind: "tool_call",
        createdAt: "2026-08-18T00:00:01Z",
        callId: "call_fixture",
        toolName: "read",
        ordinal: 0,
        presentation: {
          kind: "read",
          title: "读取文件",
          status: "success",
          inputPreview: "README.md",
          outputPreview: "ok",
          relativePaths: ["README.md"],
          truncated: false,
        },
      },
      {
        itemId: "item_approval",
        kind: "approval",
        createdAt: "2026-08-18T00:00:03Z",
        origin: "native" as const,
        approvalId: "appr_fixture",
        turnId: "turn_fixture",
        callId: "call_fixture",
        toolName: "read",
        reason: "Tool requires approval",
        expiresAt: "2026-08-18T00:05:00Z",
        decision: "approve",
      },
    ],
    contextUsage: null,
    inputQueue: null,
    taskActivities: [],
    liveStream: null,
    nextCursor: null,
  };
}

describe("TauriHistoryAdapter v1", () => {
  /** 真窗启动 effect 会解构恢复端口；空账本仍必须保留 adapter 实例以免击穿 ErrorBoundary。 */
  it("keeps fork recovery ports bound when the controller extracts them", async () => {
    const invoke = vi.fn();
    const adapter = new TauriHistoryAdapter({ invoke } as HistoryNativeBridge);
    const { recoverThreadForks, acknowledgeThreadForkOpened } = adapter;
    await expect(recoverThreadForks()).resolves.toEqual([]);
    await expect(
      acknowledgeThreadForkOpened("op_00000000000000000000000000000001"),
    ).resolves.toBeUndefined();
    expect(invoke).not.toHaveBeenCalled();
  });
  /** 长答复只通过稳定消息身份读取一页；错配或多余字段不能进入 Renderer。 */
  it("按消息身份分页读取完整公开正文", async () => {
    const invoke = vi.fn(async (command: string): Promise<unknown> => {
      expect(command).toBe(JA_HISTORY_COMMANDS.itemContentRead);
      return {
        itemId: "item_answer",
        offsetCharacters: 0,
        nextOffsetCharacters: null,
        totalCharacters: 2,
        truncated: false,
        content: "甲😀",
      };
    }) as unknown as HistoryNativeBridge["invoke"];
    const adapter = new TauriHistoryAdapter({ invoke });
    await expect(
      adapter.itemContentRead({
        threadId: "thr_fixture",
        itemId: "item_answer",
        offsetCharacters: 0,
        limitCharacters: 65_536,
      }),
    ).resolves.toMatchObject({ content: "甲😀", totalCharacters: 2 });
    expect(invoke).toHaveBeenCalledWith(JA_HISTORY_COMMANDS.itemContentRead, {
      input: {
        threadId: "thr_fixture",
        itemId: "item_answer",
        offsetCharacters: 0,
        limitCharacters: 65_536,
      },
    });
  });

  it("通过独立强类型命令建立与释放 Thread 观察，并拒绝错配 ACK", async () => {
    const invoke = vi.fn(async (_command: string, args: unknown): Promise<unknown> => {
      const input = (args as { input: { threadId: string } }).input;
      return { accepted: true, threadId: input.threadId };
    });
    const adapter = new TauriHistoryAdapter({ invoke: invoke as HistoryNativeBridge["invoke"] });
    await expect(adapter.threadObserve({ threadId: "thr_fixture" })).resolves.toEqual({
      accepted: true,
      threadId: "thr_fixture",
    });
    await expect(adapter.threadUnobserve({ threadId: "thr_fixture" })).resolves.toEqual({
      accepted: true,
      threadId: "thr_fixture",
    });
    expect(invoke.mock.calls.map(([command]) => command)).toEqual([
      JA_HISTORY_COMMANDS.threadObserve,
      JA_HISTORY_COMMANDS.threadUnobserve,
    ]);

    const wrong = new TauriHistoryAdapter({
      invoke: vi.fn(async () => ({
        accepted: true,
        threadId: "thr_other",
      })) as HistoryNativeBridge["invoke"],
    });
    await expect(wrong.threadObserve({ threadId: "thr_fixture" })).rejects.toMatchObject({
      code: "RUNTIME_UNAVAILABLE",
    });
  });

  it("uses the dedicated runtime workspace command instead of the file open-with command", () => {
    expect(JA_HISTORY_COMMANDS.workspaceOpen).toBe("ja_runtime_workspace_open");
    expect(JA_HISTORY_COMMANDS.workspaceRead).toBe("ja_runtime_workspace_read");
    expect(JA_HISTORY_COMMANDS.workspaceUpdate).toBe("ja_runtime_workspace_update");
    expect(JA_HISTORY_COMMANDS.workspaceSetTrust).toBe("ja_workspace_set_trust");
  });

  /** 已登记项目只交付身份，避免前端重推路径而绕过服务端登记资料或恢复已失效目录。 */
  it("reopens registered workspaces by ID through the typed workspace command", async () => {
    const invoke = vi.fn(async () => workspace()) as unknown as HistoryNativeBridge["invoke"];
    const adapter = new TauriHistoryAdapter({ invoke });

    await expect(adapter.workspaceOpen({ workspaceId: "ws_server" })).resolves.toEqual(workspace());
    expect(invoke).toHaveBeenCalledExactlyOnceWith(JA_HISTORY_COMMANDS.workspaceOpen, {
      input: { workspaceId: "ws_server" },
    });
    await expect(
      adapter.workspaceOpen({ workspaceId: "ws_server", cwd: "D:\\other" } as never),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    expect(invoke).toHaveBeenCalledTimes(1);
  });

  /** 读写以稳定 ID 定位项目；CAS revision 与参数闭集由共享 schema 在 invoke 前校验。 */
  it("reads and updates workspace metadata through dedicated runtime commands", async () => {
    const invoke = vi.fn(async () => workspace()) as unknown as HistoryNativeBridge["invoke"];
    const adapter = new TauriHistoryAdapter({ invoke });

    await expect(adapter.workspaceRead({ workspaceId: "ws_server" })).resolves.toEqual(workspace());
    await expect(
      adapter.workspaceUpdate({
        workspaceId: "ws_server",
        expectedRevision: 0,
        displayName: "Ja 项目",
      }),
    ).resolves.toEqual(workspace());
    expect(invoke).toHaveBeenNthCalledWith(1, JA_HISTORY_COMMANDS.workspaceRead, {
      input: { workspaceId: "ws_server" },
    });
    expect(invoke).toHaveBeenNthCalledWith(2, JA_HISTORY_COMMANDS.workspaceUpdate, {
      input: { workspaceId: "ws_server", expectedRevision: 0, displayName: "Ja 项目" },
    });

    await expect(
      adapter.workspaceUpdate({
        workspaceId: "ws_server",
        expectedRevision: -1,
        cwd: "D:\\new",
      }),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    await expect(
      adapter.workspaceRead({ workspaceId: "ws_server", cwd: "D:\\old" } as never),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    expect(invoke).toHaveBeenCalledTimes(2);
  });

  /** Java 的真实 WORKSPACE_BUSY 摘要必须穿过 typed adapter 回到编辑 Dialog，而不是折叠成通用故障。 @author kongweiguang */
  it("preserves the bounded Java workspace-busy summary from the runtime command", async () => {
    const message = "暂时无法更改项目目录：项目诊断会话正在工作";
    const invoke = vi.fn(async () => {
      throw { code: "WORKSPACE_BUSY", message, retryable: true };
    }) as unknown as HistoryNativeBridge["invoke"];
    const adapter = new TauriHistoryAdapter({ invoke });

    await expect(
      adapter.workspaceUpdate({
        workspaceId: "ws_server",
        expectedRevision: 0,
        displayName: "Ja 项目",
        cwd: "D:\\next",
      }),
    ).rejects.toMatchObject({ code: "WORKSPACE_BUSY", message, retryable: true });
    expect(invoke).toHaveBeenCalledExactlyOnceWith(JA_HISTORY_COMMANDS.workspaceUpdate, {
      input: {
        workspaceId: "ws_server",
        expectedRevision: 0,
        displayName: "Ja 项目",
        cwd: "D:\\next",
      },
    });
  });

  /** 打开只登记目录；权限提升必须通过独立、可审计的命令提交。 */
  it("sets workspace trust through the dedicated command", async () => {
    const invoke = vi.fn(async () => ({ accepted: true }));
    const adapter = new TauriHistoryAdapter({
      invoke: invoke as unknown as HistoryNativeBridge["invoke"],
    });

    await expect(
      adapter.workspaceSetTrust({ workspaceId: "ws_demo", trust: "trusted" }),
    ).resolves.toEqual({ accepted: true });
    expect(invoke).toHaveBeenCalledWith(JA_HISTORY_COMMANDS.workspaceSetTrust, {
      input: { workspaceId: "ws_demo", trust: "trusted" },
    });
  });

  it("uses server-owned workspace/open and cwd-bound Thread creation", async () => {
    const invokeMock = vi.fn(async (command: string): Promise<unknown> => {
      switch (command) {
        case JA_HISTORY_COMMANDS.workspaceOpen:
          return workspace();
        case JA_HISTORY_COMMANDS.workspaceList:
          return { items: [workspace()], nextCursor: null };
        case JA_HISTORY_COMMANDS.threadCreate:
          return thread("thr_created");
        case JA_HISTORY_COMMANDS.threadList:
          return { items: [thread()], nextCursor: null };
        case JA_HISTORY_COMMANDS.threadSearch:
          return { items: [{ ...thread("thr_legacy"), preferences: null }], nextCursor: null };
        case JA_HISTORY_COMMANDS.threadRead:
          return snapshot();
        case JA_HISTORY_COMMANDS.threadRename:
          return {
            ...thread(),
            title: "Renamed conversation",
            revision: 4,
            preferences: { ...thread().preferences, titleSource: "manual" },
          };
        case JA_HISTORY_COMMANDS.threadPreferencesUpdate:
          return {
            ...thread(),
            revision: 5,
            preferences: {
              ...thread().preferences,
              reasoningLevel: "high",
              accessMode: "full_access",
            },
          };
        case JA_HISTORY_COMMANDS.threadPin:
          return { ...thread(), pinned: true, revision: 4 };
        case JA_HISTORY_COMMANDS.threadSeen:
          return { ...thread(), latestTurnSeen: true, revision: 4 };
        case JA_HISTORY_COMMANDS.threadArchive:
          return { ...thread(), status: "archived", revision: 4 };
        case JA_HISTORY_COMMANDS.threadRestore:
          return { ...thread(), revision: 5 };
        case JA_HISTORY_COMMANDS.threadDelete:
          return { accepted: true };
        case JA_HISTORY_COMMANDS.threadCompact:
          return {
            outcome: "compacted",
            compactionId: "cmp_fixture",
            checkpointId: "checkpoint_fixture",
            threadRevision: 5,
            inputTokensBefore: 12_000,
            inputTokensAfter: 5_000,
          };
        case JA_HISTORY_COMMANDS.threadCompactCancel:
          return { accepted: false };
        default:
          throw new Error(`unexpected command: ${command}`);
      }
    });
    const adapter = new TauriHistoryAdapter({
      invoke: invokeMock as unknown as HistoryNativeBridge["invoke"],
    });

    await expect(adapter.workspaceOpen({ cwd: "C:\\demo", displayName: "demo" })).resolves.toEqual(
      workspace(),
    );
    await adapter.workspaceList();
    await adapter.workspaceList({ kind: "project", limit: 200 });
    await adapter.threadCreate({
      cwd: "C:\\demo",
      title: "Fixture conversation",
      providerId: "provider_fixture",
      modelId: "model_fixture",
      reasoningLevel: "medium",
      accessMode: "approval_required",
      collaborationMode: "default",
    });
    await expect(
      adapter.threadCreate({
        cwd: null,
        title: "Invalid explicit null",
        providerId: "provider_fixture",
        modelId: "model_fixture",
        reasoningLevel: null,
        accessMode: "approval_required",
        collaborationMode: "default",
      } as never),
    ).rejects.toThrow();
    await adapter.threadList({ workspaceId: "ws_demo", limit: 200 });
    await expect(
      adapter.threadSearch({ workspaceId: "ws_demo", query: "legacy", limit: 20 }),
    ).resolves.toMatchObject({ items: [{ threadId: "thr_legacy", preferences: null }] });
    await adapter.threadRead({ threadId: "thr_fixture" });
    await adapter.threadRename({
      threadId: "thr_fixture",
      title: "Renamed conversation",
      expectedThreadRevision: 3,
    });
    await adapter.threadPreferencesUpdate({
      threadId: "thr_fixture",
      providerId: "provider_fixture",
      modelId: "model_fixture",
      reasoningLevel: "high",
      accessMode: "full_access",
      collaborationMode: "plan",
      expectedThreadRevision: 4,
    });
    await adapter.threadPin({ threadId: "thr_fixture", pinned: true, expectedThreadRevision: 3 });
    await adapter.threadSeen({ threadId: "thr_fixture", expectedThreadRevision: 3 });
    await adapter.threadArchive({ threadId: "thr_fixture", expectedThreadRevision: 3 });
    await adapter.threadRestore({ threadId: "thr_fixture", expectedThreadRevision: 4 });
    await adapter.threadDelete({ threadId: "thr_fixture", expectedThreadRevision: 4 });
    await adapter.threadCompact({ threadId: "thr_fixture", expectedThreadRevision: 4 });
    expect(await adapter.threadCompactCancel({ threadId: "thr_fixture" })).toEqual({
      accepted: false,
    });

    expect(invokeMock.mock.calls).toEqual([
      [JA_HISTORY_COMMANDS.workspaceOpen, { input: { cwd: "C:\\demo", displayName: "demo" } }],
      [JA_HISTORY_COMMANDS.workspaceList, { input: {} }],
      [JA_HISTORY_COMMANDS.workspaceList, { input: { kind: "project", limit: 200 } }],
      [
        JA_HISTORY_COMMANDS.threadCreate,
        {
          input: {
            cwd: "C:\\demo",
            title: "Fixture conversation",
            providerId: "provider_fixture",
            modelId: "model_fixture",
            reasoningLevel: "medium",
            accessMode: "approval_required",
            collaborationMode: "default",
          },
        },
      ],
      [JA_HISTORY_COMMANDS.threadList, { input: { workspaceId: "ws_demo", limit: 200 } }],
      [
        JA_HISTORY_COMMANDS.threadSearch,
        { input: { workspaceId: "ws_demo", query: "legacy", limit: 20 } },
      ],
      [JA_HISTORY_COMMANDS.threadRead, { input: { threadId: "thr_fixture" } }],
      [
        JA_HISTORY_COMMANDS.threadRename,
        {
          input: {
            threadId: "thr_fixture",
            title: "Renamed conversation",
            expectedThreadRevision: 3,
          },
        },
      ],
      [
        JA_HISTORY_COMMANDS.threadPreferencesUpdate,
        {
          input: {
            threadId: "thr_fixture",
            providerId: "provider_fixture",
            modelId: "model_fixture",
            reasoningLevel: "high",
            accessMode: "full_access",
            collaborationMode: "plan",
            expectedThreadRevision: 4,
          },
        },
      ],
      [
        JA_HISTORY_COMMANDS.threadPin,
        { input: { threadId: "thr_fixture", pinned: true, expectedThreadRevision: 3 } },
      ],
      [
        JA_HISTORY_COMMANDS.threadSeen,
        { input: { threadId: "thr_fixture", expectedThreadRevision: 3 } },
      ],
      [
        JA_HISTORY_COMMANDS.threadArchive,
        { input: { threadId: "thr_fixture", expectedThreadRevision: 3 } },
      ],
      [
        JA_HISTORY_COMMANDS.threadRestore,
        { input: { threadId: "thr_fixture", expectedThreadRevision: 4 } },
      ],
      [
        JA_HISTORY_COMMANDS.threadDelete,
        { input: { threadId: "thr_fixture", expectedThreadRevision: 4 } },
      ],
      [
        JA_HISTORY_COMMANDS.threadCompact,
        { input: { threadId: "thr_fixture", expectedThreadRevision: 4 } },
      ],
      [JA_HISTORY_COMMANDS.threadCompactCancel, { input: { threadId: "thr_fixture" } }],
    ]);
  });

  it("discovers minimal cross-workspace thread identities without reading a transcript", async () => {
    const invoke = vi.fn(async (command: string): Promise<unknown> => {
      expect(command).toBe(JA_HISTORY_COMMANDS.threadDiscover);
      return {
        items: [
          {
            threadId: "thr_side",
            title: "临时旁支",
            kind: "side_chat",
            workspaceId: "ws_other",
            status: "running",
          },
        ],
        nextCursor: null,
      };
    }) as unknown as HistoryNativeBridge["invoke"];
    const adapter = new TauriHistoryAdapter({ invoke });

    await expect(
      adapter.threadDiscover({ scope: "all", query: "旁支", limit: 20 }),
    ).resolves.toEqual({
      items: [
        {
          threadId: "thr_side",
          title: "临时旁支",
          kind: "side_chat",
          workspaceId: "ws_other",
          status: "running",
        },
      ],
      nextCursor: null,
    });
    await expect(
      adapter.threadDiscover({ scope: "workspace", query: "旁支" } as never),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    await expect(
      adapter.threadDiscover({ scope: "all", query: "旁\u0003支" } as never),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    expect(invoke).toHaveBeenCalledTimes(1);
  });

  it("rejects client-owned workspace ids, revisions, and legacy replay fields before invoke", async () => {
    const invoke = vi.fn(
      async (): Promise<unknown> => snapshot(),
    ) as unknown as HistoryNativeBridge["invoke"];
    const adapter = new TauriHistoryAdapter({ invoke });
    await expect(adapter.workspaceOpen({ cwd: "", displayName: "demo" })).rejects.toMatchObject({
      code: "INVALID_INPUT",
    });
    await expect(
      adapter.threadCreate({ workspaceId: "ws_fixture", title: "old" } as never),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    await expect(
      adapter.threadRead({ threadId: "thr_fixture", view: "snapshot" } as never),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    await expect(
      adapter.threadArchive({
        threadId: "thr_fixture",
        expectedThreadRevision: 1,
        reason: "legacy",
      } as never),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    await expect(
      adapter.threadCompact({
        threadId: "thr_fixture",
        expectedThreadRevision: 1,
        strategy: "legacy",
      } as never),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    await expect(
      adapter.threadSearch({
        workspaceId: "ws_fixture",
        query: "x",
        profileId: "profile_old",
      } as never),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    await expect(
      adapter.threadPreferencesUpdate({
        threadId: "thr_fixture",
        providerId: "provider_fixture",
        modelId: "model_fixture",
        reasoningLevel: "maximum",
        accessMode: "full_access",
        expectedThreadRevision: 1,
      } as never),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    expect(invoke).not.toHaveBeenCalled();
  });

  it("maps malformed native results and errors to stable redacted errors", async () => {
    const malformed = new TauriHistoryAdapter({
      invoke: vi.fn(
        async (): Promise<unknown> => ({ threadId: "bad" }),
      ) as unknown as HistoryNativeBridge["invoke"],
    });
    await expect(
      malformed.threadCreate({
        cwd: "C:\\demo",
        title: "Fixture conversation",
        providerId: "provider_fixture",
        modelId: "model_fixture",
        reasoningLevel: "medium",
        accessMode: "approval_required",
        collaborationMode: "default",
      }),
    ).rejects.toMatchObject({ code: "RUNTIME_UNAVAILABLE" });

    const rejected = new TauriHistoryAdapter({
      invoke: vi.fn(async () => {
        throw { code: "UNKNOWN_INTERNAL", detail: "C:\\private" };
      }) as unknown as HistoryNativeBridge["invoke"],
    });
    await expect(rejected.threadList({ workspaceId: "ws_demo" })).rejects.toBeInstanceOf(
      RuntimeHostError,
    );
    await expect(rejected.threadList({ workspaceId: "ws_demo" })).rejects.toMatchObject({
      code: "RUNTIME_UNAVAILABLE",
      message: "运行时暂不可用",
    });
  });

  it("accepts complete Tool and approval snapshots but rejects incomplete kind payloads", async () => {
    const valid = new TauriHistoryAdapter({
      invoke: vi.fn(
        async (): Promise<unknown> => snapshot(),
      ) as unknown as HistoryNativeBridge["invoke"],
    });
    await expect(valid.threadRead({ threadId: "thr_fixture" })).resolves.toEqual(snapshot());

    const invalid = new TauriHistoryAdapter({
      invoke: vi.fn(
        async (): Promise<unknown> => ({
          ...snapshot(),
          items: [
            {
              itemId: "item_call",
              kind: "tool_call",
              createdAt: "2026-08-18T00:00:01Z",
              callId: "call_fixture",
            },
          ],
        }),
      ) as unknown as HistoryNativeBridge["invoke"],
    });
    await expect(invalid.threadRead({ threadId: "thr_fixture" })).rejects.toMatchObject({
      code: "RUNTIME_UNAVAILABLE",
    });
  });

  /** MCP 标题状态通过显式的 Thread 级 IPC 查询，未知计数保持缺省。 */
  it("maps the thread MCP read to its strict native command", async () => {
    const status = {
      threadId: "thr_fixture",
      source: "last_observed",
      notices: [],
      observedAt: "2026-09-23T12:30:00Z",
      servers: [
        {
          serverId: "mcp_local",
          name: "Local MCP",
          scope: "global",
          state: "available",
          toolCount: 68,
        },
        { serverId: "mcp_pending", name: "Pending", scope: "project", state: "not_discovered" },
      ],
    } as const;
    const invoke = vi.fn(async (): Promise<unknown> => status);
    const adapter = new TauriHistoryAdapter({
      invoke: invoke as unknown as HistoryNativeBridge["invoke"],
    });

    await expect(adapter.threadMcpRead({ threadId: "thr_fixture" })).resolves.toEqual(status);
    expect(invoke).toHaveBeenLastCalledWith(JA_HISTORY_COMMANDS.threadMcpRead, {
      input: { threadId: "thr_fixture" },
    });
  });

  /** 可选的未知状态字段必须省略；脱敏投影不得携带端点数据。 */
  it("rejects null optional MCP fields and sensitive status data", async () => {
    const adapterFor = (result: unknown) =>
      new TauriHistoryAdapter({
        invoke: vi.fn(async () => result) as unknown as HistoryNativeBridge["invoke"],
      });
    const input = { threadId: "thr_fixture" };

    await expect(
      adapterFor({
        threadId: "thr_fixture",
        source: "unchecked",
        catalogRevision: null,
        servers: [],
      }).threadMcpRead(input),
    ).rejects.toMatchObject({ code: "RUNTIME_UNAVAILABLE" });
    await expect(
      adapterFor({
        threadId: "thr_fixture",
        source: "unchecked",
        servers: [
          {
            serverId: "mcp_local",
            name: "Local MCP",
            state: "available",
            endpoint: "http://localhost",
          },
        ],
      }).threadMcpRead(input),
    ).rejects.toMatchObject({ code: "RUNTIME_UNAVAILABLE" });
  });
});
