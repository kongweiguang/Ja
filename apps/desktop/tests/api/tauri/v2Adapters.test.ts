// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import { TauriHistoryAdapter, JA_HISTORY_COMMANDS } from "@/api/tauri/history";
import { TauriRuntimeHostAdapter, JA_RUNTIME_COMMANDS } from "@/api/tauri/runtime";
import { TauriSettingsAdapter, JA_SETTINGS_COMMANDS } from "@/api/tauri/settings";

/** 让 v2 wrapper 测试聚焦 wire envelope，不依赖活动 sidecar。 */
describe("Ja v2 desktop adapters", () => {
  it("sends only cwd intent when opening a workspace and creating a thread", async () => {
    const invoke = vi.fn(
      async (command: string): Promise<unknown> =>
        command === JA_HISTORY_COMMANDS.workspaceOpen
          ? {
              workspaceId: "ws_server",
              root: "C:\\demo",
              displayName: "demo",
              trust: "trusted",
              revision: 0,
            }
          : {
              threadId: "thr_server",
              workspaceId: "ws_server",
              preferences: {
                providerId: "provider_demo",
                modelId: "model_demo",
                reasoningLevel: "medium",
                accessMode: "approval_required",
                titleSource: "placeholder",
              },
              title: "New",
              status: "active",
              revision: 0,
              createdAt: "2026-08-25T00:00:00Z",
              updatedAt: "2026-08-25T00:00:00Z",
            },
    );
    const adapter = new TauriHistoryAdapter({ invoke: invoke as never });

    const workspace = await adapter.workspaceOpen({ cwd: "C:\\demo" });
    await adapter.threadCreate({
      cwd: workspace.root,
      title: "New",
      providerId: "provider_demo",
      modelId: "model_demo",
      reasoningLevel: "medium",
      accessMode: "approval_required",
    });

    expect(invoke).toHaveBeenNthCalledWith(1, JA_HISTORY_COMMANDS.workspaceOpen, {
      input: { cwd: "C:\\demo", trust: "trusted" },
    });
    expect(invoke).toHaveBeenNthCalledWith(2, JA_HISTORY_COMMANDS.threadCreate, {
      input: {
        cwd: "C:\\demo",
        title: "New",
        providerId: "provider_demo",
        modelId: "model_demo",
        reasoningLevel: "medium",
        accessMode: "approval_required",
      },
    });
    expect(JSON.stringify(invoke.mock.calls)).not.toContain("configRevision");
  });

  it("sends a turn intent without cwd, profile, or config revision", async () => {
    const invoke = vi.fn(
      async (command: string): Promise<unknown> =>
        command === JA_RUNTIME_COMMANDS.turnStart
          ? { accepted: true, queued: false, turnId: "turn_server", threadRevision: 1 }
          : undefined,
    );
    const adapter = new TauriRuntimeHostAdapter({
      invoke: invoke as never,
      listen: async () => () => undefined,
    });

    await adapter.turnStart({
      threadId: "thr_server",
      content: [{ type: "text", text: "hello" }],
      deadlineMs: 30_000,
    });

    expect(invoke).toHaveBeenCalledWith(JA_RUNTIME_COMMANDS.turnStart, {
      input: {
        threadId: "thr_server",
        content: [{ type: "text", text: "hello" }],
        deadlineMs: 30_000,
      },
    });
    expect(JSON.stringify(invoke.mock.calls)).not.toMatch(/cwd|profileId|configRevision/u);
  });

  it("uses patch/document CAS fields and keeps credentials on dedicated commands", async () => {
    const secret = "fixture-secret";
    const invoke = vi.fn(
      async (command: string): Promise<unknown> =>
        command === JA_SETTINGS_COMMANDS.setCredential
          ? { accepted: true, credentialId: "cred_demo", configured: true, version: "cfg_B" }
          : { accepted: true, scope: "user", version: "cfg_A" },
    );
    const adapter = new TauriSettingsAdapter({ invoke: invoke as never });

    await adapter.patch({
      scope: "user",
      patch: { default_provider_id: "provider_demo", default_model_id: "model_demo" },
      expectedVersion: "cfg_missing",
    });
    await adapter.replace({
      scope: "user",
      document: {
        schema_version: 4,
        config_revision: 0,
        default_access_mode: "full_access",
        default_provider_id: null,
        default_model_id: null,
        default_reasoning_level: null,
        providers: [],
        mcp_servers: [],
        skills: [],
      },
      expectedVersion: "cfg_A",
    });
    await adapter.setCredential("cred_demo", secret, "cfg_missing");

    expect(invoke).toHaveBeenNthCalledWith(1, JA_SETTINGS_COMMANDS.patch, {
      input: {
        scope: "user",
        patch: { default_provider_id: "provider_demo", default_model_id: "model_demo" },
        expectedVersion: "cfg_missing",
      },
    });
    expect(invoke).toHaveBeenNthCalledWith(2, JA_SETTINGS_COMMANDS.replace, {
      input: {
        scope: "user",
        document: {
          schema_version: 4,
          config_revision: 0,
          default_access_mode: "full_access",
          default_provider_id: null,
          default_model_id: null,
          default_reasoning_level: null,
          providers: [],
          mcp_servers: [],
          skills: [],
        },
        expectedVersion: "cfg_A",
      },
    });
    expect(invoke).toHaveBeenNthCalledWith(3, JA_SETTINGS_COMMANDS.setCredential, {
      input: { credentialId: "cred_demo", secret, expectedVersion: "cfg_missing" },
    });
    const replaceCall = (invoke.mock.calls as unknown as Array<[string, { input?: unknown }]>)[1];
    expect(replaceCall?.[1]?.input).not.toHaveProperty("secret");
  });
});
