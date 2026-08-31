// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import {
  JA_HISTORY_COMMANDS,
  TauriHistoryAdapter,
  type HistoryNativeBridge,
} from "@/api/tauri/history";
import { RuntimeHostError } from "@/api/tauri/runtime";

function workspace() {
  return {
    workspaceId: "ws_server",
    root: "C:\\demo",
    displayName: "demo",
    trust: "untrusted" as const,
    revision: 0,
  };
}

function thread(threadId = "thr_fixture") {
  return {
    threadId,
    workspaceId: "ws_server",
    preferences: {
      providerId: "provider_fixture",
      modelId: "model_fixture",
      reasoningLevel: "medium" as const,
      accessMode: "approval_required" as const,
      titleSource: "placeholder" as const,
    },
    title: "Fixture conversation",
    status: "active" as const,
    revision: 0,
    createdAt: "2026-08-18T00:00:00Z",
    updatedAt: "2026-08-18T00:00:00Z",
  };
}

/** 构造完整 Java thread/read 词汇，使 adapter 测试能够捕获 wire drift。 */
function snapshot() {
  return {
    threadId: "thr_fixture",
    revision: 4,
    turns: [
      {
        turnId: "turn_fixture",
        status: "completed",
        runtime: {
          providerId: "provider_fixture",
          modelId: "model_fixture",
          provider: "openai",
          api: "openai_responses",
          upstreamModel: "gpt-5.6-sol",
          reasoningLevel: "medium",
          accessMode: "full_access",
          configGeneration: "cfg_fixture",
        },
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
        kind: "user_input",
        createdAt: "2026-08-18T00:00:00Z",
        text: "hello",
      },
      {
        itemId: "item_call",
        turnId: "turn_fixture",
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
    nextCursor: null,
  };
}

describe("TauriHistoryAdapter v2", () => {
  it("uses the dedicated runtime workspace command instead of the file open-with command", () => {
    expect(JA_HISTORY_COMMANDS.workspaceOpen).toBe("ja_runtime_workspace_open");
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
        case JA_HISTORY_COMMANDS.threadArchive:
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
    await adapter.threadCreate({
      cwd: "C:\\demo",
      title: "Fixture conversation",
      providerId: "provider_fixture",
      modelId: "model_fixture",
      reasoningLevel: "medium",
      accessMode: "approval_required",
    });
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
      expectedThreadRevision: 4,
    });
    await adapter.threadArchive({ threadId: "thr_fixture", expectedThreadRevision: 3 });
    await adapter.threadDelete({ threadId: "thr_fixture", expectedThreadRevision: 4 });
    await adapter.threadCompact({ threadId: "thr_fixture", expectedThreadRevision: 4 });

    expect(invokeMock.mock.calls).toEqual([
      [
        JA_HISTORY_COMMANDS.workspaceOpen,
        { input: { cwd: "C:\\demo", displayName: "demo", trust: "trusted" } },
      ],
      [JA_HISTORY_COMMANDS.workspaceList, { input: {} }],
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
            expectedThreadRevision: 4,
          },
        },
      ],
      [
        JA_HISTORY_COMMANDS.threadArchive,
        { input: { threadId: "thr_fixture", expectedThreadRevision: 3 } },
      ],
      [
        JA_HISTORY_COMMANDS.threadDelete,
        { input: { threadId: "thr_fixture", expectedThreadRevision: 4 } },
      ],
      [
        JA_HISTORY_COMMANDS.threadCompact,
        { input: { threadId: "thr_fixture", expectedThreadRevision: 4 } },
      ],
    ]);
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
});
