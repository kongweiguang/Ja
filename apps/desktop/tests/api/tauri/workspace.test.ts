// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import {
  JA_WORKSPACE_COMMANDS,
  JA_WORKSPACE_EVENTS,
  parseWorkspaceNativeDropEvent,
  parseWorkspaceChangedEvent,
  TauriWorkspaceHostAdapter,
  type WorkspaceNativeBridge,
} from "@/api/tauri/workspace";

const metadata = {
  kind: "file" as const,
  size: 3,
  modifiedUnixMillis: 7,
  revision: { kind: "file" as const, size: 3, modifiedUnixMillis: 7, sha256: "abc" },
};

const treeFixture = {
  entries: [{ name: "main.rs", relativePath: "src/main.rs", metadata, canExpand: false }],
  directoryRevision: {
    kind: "directory" as const,
    size: 0,
    modifiedUnixMillis: null,
    sha256: "directory-snapshot",
  },
  nextCursor: null,
  snapshotToken: "snapshot_fixture",
  totalEntries: 1,
  depth: 1,
};

const fileFixture = {
  metadata,
  kind: "text" as const,
  encoding: "utf8" as const,
  text: "fn main() {}",
  bytesRead: 12,
  truncated: false,
};

const searchFixture = {
  hits: [
    {
      relativePath: "src/main.rs",
      line: 1,
      column: 1,
      snippet: "fn main()",
      encoding: "utf8" as const,
    },
  ],
  truncated: false,
  scannedEntries: 1,
  skippedFiles: 0,
};

const openTargetsFixture = {
  targets: [
    { target: "vscode" as const, displayName: "VS Code", available: true, reason: null },
    {
      target: "visual_studio" as const,
      displayName: "Visual Studio",
      available: false,
      reason: "not_installed" as const,
    },
    { target: "zed" as const, displayName: "Zed", available: true, reason: null },
    {
      target: "file_explorer" as const,
      displayName: "文件资源管理器",
      available: true,
      reason: null,
    },
    { target: "terminal" as const, displayName: "终端", available: true, reason: null },
    {
      target: "git_bash" as const,
      displayName: "Git Bash",
      available: false,
      reason: "not_installed" as const,
    },
    {
      target: "wsl" as const,
      displayName: "WSL",
      available: false,
      reason: "unsupported_platform" as const,
    },
    {
      target: "pycharm" as const,
      displayName: "PyCharm",
      available: false,
      reason: "not_installed" as const,
    },
    {
      target: "webstorm" as const,
      displayName: "WebStorm",
      available: false,
      reason: "not_installed" as const,
    },
  ],
};

const openResultFixture = {
  opened: true as const,
  target: "vscode" as const,
  relativePath: "src/main.rs",
  entryKind: "file" as const,
};

/** 不观察事件的合同用例仍提供完整 bridge；生产 Port 不为测试 fake 放宽能力。 */
const unusedListen = (async () => () => undefined) as WorkspaceNativeBridge["listen"];

/** 为 command 合同测试补齐未使用的事件端口，同时保留 invoke mock 的精确调用记录。 */
function bridgeWithInvoke(invoke: WorkspaceNativeBridge["invoke"]): WorkspaceNativeBridge {
  return { invoke, listen: unusedListen };
}

describe("typed workspace host adapter", () => {
  it("uses fixed command names and only workspace-relative typed inputs", async () => {
    const invoke = vi.fn(async (command: string) => {
      if (command === JA_WORKSPACE_COMMANDS.tree) return treeFixture;
      if (command === JA_WORKSPACE_COMMANDS.readFile) return fileFixture;
      return searchFixture;
    });
    const bridge = bridgeWithInvoke(invoke as WorkspaceNativeBridge["invoke"]);
    const adapter = new TauriWorkspaceHostAdapter(bridge);

    await expect(
      adapter.tree({ workspaceId: "ws_fixture", relativePath: "", pageSize: 25 }),
    ).resolves.toEqual(treeFixture);
    await expect(
      adapter.readFile({ workspaceId: "ws_fixture", relativePath: "src/main.rs" }),
    ).resolves.toEqual(fileFixture);
    await expect(
      adapter.search({ workspaceId: "ws_fixture", relativePath: "", query: "main" }),
    ).resolves.toEqual(searchFixture);

    expect(invoke).toHaveBeenNthCalledWith(1, JA_WORKSPACE_COMMANDS.tree, {
      input: { workspaceId: "ws_fixture", relativePath: "", pageSize: 25 },
    });
    expect(invoke).toHaveBeenNthCalledWith(2, JA_WORKSPACE_COMMANDS.readFile, {
      input: { workspaceId: "ws_fixture", relativePath: "src/main.rs" },
    });
    expect(invoke).toHaveBeenNthCalledWith(3, JA_WORKSPACE_COMMANDS.search, {
      input: { workspaceId: "ws_fixture", relativePath: "", query: "main" },
    });
    const methods = Object.getOwnPropertyNames(Object.getPrototypeOf(adapter));
    expect(methods).toEqual(expect.arrayContaining(["tree", "readFile", "search"]));
    expect(methods).not.toContain("invoke");
  });

  it("rejects absolute roots, executable/env/generic fields, and malformed output", async () => {
    const invoke = vi.fn(async () => treeFixture);
    const bridge = bridgeWithInvoke(invoke as WorkspaceNativeBridge["invoke"]);
    const adapter = new TauriWorkspaceHostAdapter(bridge);

    for (const relativePath of [
      ".",
      "..",
      "src/../main.rs",
      "src/./main.rs",
      "src\\main.rs",
      "src:main.rs",
      "C:\\private",
    ]) {
      await expect(adapter.tree({ workspaceId: "ws_fixture", relativePath })).rejects.toMatchObject(
        {
          code: "INVALID_INPUT",
          message: "请求参数无效",
        },
      );
    }
    await expect(
      adapter.readFile({ workspaceId: "ws_fixture", relativePath: "" }),
    ).rejects.toMatchObject({
      code: "INVALID_INPUT",
      message: "请求参数无效",
    });
    await expect(
      adapter.tree({
        workspaceId: "ws_fixture",
        relativePath: "src",
        rootPath: "C:\\private",
        executable: "java",
        env: {},
      } as never),
    ).rejects.toMatchObject({
      code: "INVALID_INPUT",
      message: "请求参数无效",
    });
    expect(invoke).not.toHaveBeenCalled();

    const malformed = vi.fn(async () => ({ ...treeFixture, unexpected: true }));
    await expect(
      new TauriWorkspaceHostAdapter(
        bridgeWithInvoke(malformed as WorkspaceNativeBridge["invoke"]),
      ).tree({ workspaceId: "ws_fixture", relativePath: "" }),
    ).rejects.toMatchObject({ code: "RUNTIME_UNAVAILABLE", message: "运行时暂不可用" });

    const missingDirectoryRevision = vi.fn(async () => ({
      ...treeFixture,
      directoryRevision: undefined,
    }));
    await expect(
      new TauriWorkspaceHostAdapter(
        bridgeWithInvoke(missingDirectoryRevision as WorkspaceNativeBridge["invoke"]),
      ).tree({
        workspaceId: "ws_fixture",
        relativePath: "",
      }),
    ).rejects.toMatchObject({ code: "RUNTIME_UNAVAILABLE", message: "运行时暂不可用" });
  });

  it("maps unknown native errors to a stable local message", async () => {
    const invoke = vi.fn(async () => {
      throw { code: "NATIVE_PRIVATE_CODE", message: "C:\\private\\workspace" };
    });
    const adapter = new TauriWorkspaceHostAdapter(
      bridgeWithInvoke(invoke as WorkspaceNativeBridge["invoke"]),
    );
    const error = await adapter
      .readFile({ workspaceId: "ws_fixture", relativePath: "src/main.rs" })
      .catch((value: unknown) => value);
    expect(error).toMatchObject({ code: "RUNTIME_UNAVAILABLE", message: "运行时暂不可用" });
    expect(JSON.stringify(error)).not.toContain("private");
  });

  it("discovers only the closed target set and opens a relative file", async () => {
    const invoke = vi.fn(async (command: string) => {
      if (command === JA_WORKSPACE_COMMANDS.openTargets) return openTargetsFixture;
      return openResultFixture;
    });
    const adapter = new TauriWorkspaceHostAdapter(
      bridgeWithInvoke(invoke as WorkspaceNativeBridge["invoke"]),
    );

    await expect(adapter.openTargets({ workspaceId: "ws_fixture" })).resolves.toEqual(
      openTargetsFixture,
    );
    await expect(
      adapter.open({
        workspaceId: "ws_fixture",
        target: "vscode",
        relativePath: "src/main.rs",
      }),
    ).resolves.toEqual(openResultFixture);
    expect(invoke).toHaveBeenNthCalledWith(1, JA_WORKSPACE_COMMANDS.openTargets, {
      input: { workspaceId: "ws_fixture" },
    });
    expect(invoke).toHaveBeenNthCalledWith(2, JA_WORKSPACE_COMMANDS.open, {
      input: { workspaceId: "ws_fixture", target: "vscode", relativePath: "src/main.rs" },
    });

    const rootInvoke = vi.fn(async () => ({
      opened: true,
      target: "file_explorer",
      relativePath: "",
      entryKind: "directory",
    }));
    const rootAdapter = new TauriWorkspaceHostAdapter(
      bridgeWithInvoke(rootInvoke as WorkspaceNativeBridge["invoke"]),
    );
    await expect(
      rootAdapter.open({ workspaceId: "ws_fixture", target: "file_explorer", relativePath: "" }),
    ).resolves.toMatchObject({
      opened: true,
      target: "file_explorer",
      relativePath: "",
      entryKind: "directory",
    });
    expect(rootInvoke).toHaveBeenCalledWith(JA_WORKSPACE_COMMANDS.open, {
      input: { workspaceId: "ws_fixture", target: "file_explorer", relativePath: "" },
    });
  });

  it("rejects unknown targets, traversal and arbitrary launcher fields before IPC", async () => {
    const invoke = vi.fn(async () => openResultFixture);
    const adapter = new TauriWorkspaceHostAdapter(
      bridgeWithInvoke(invoke as WorkspaceNativeBridge["invoke"]),
    );

    await expect(
      adapter.open({ workspaceId: "ws_fixture", target: "powershell" as never }),
    ).rejects.toMatchObject({
      code: "INVALID_INPUT",
      message: "请求参数无效",
    });
    await expect(
      adapter.open({ workspaceId: "ws_fixture", target: "vscode", relativePath: "../escape" }),
    ).rejects.toMatchObject({
      code: "INVALID_INPUT",
      message: "请求参数无效",
    });
    await expect(
      adapter.open({
        workspaceId: "ws_fixture",
        target: "vscode",
        relativePath: "src/main.rs",
        executable: "cmd.exe",
        args: ["/c", "whoami"],
      } as never),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    expect(invoke).not.toHaveBeenCalled();
  });

  it("rejects malformed discovery and open results without returning native details", async () => {
    const malformedTargets = vi.fn(async () => ({
      targets: [{ target: "vscode", displayName: "VS Code", available: false, reason: null }],
    }));
    const adapter = new TauriWorkspaceHostAdapter(
      bridgeWithInvoke(malformedTargets as WorkspaceNativeBridge["invoke"]),
    );
    await expect(adapter.openTargets({ workspaceId: "ws_fixture" })).rejects.toMatchObject({
      code: "RUNTIME_UNAVAILABLE",
      message: "运行时暂不可用",
    });

    const malformedOpen = vi.fn(async () => ({
      ...openResultFixture,
      executablePath: "C:\\private\\Code.exe",
    }));
    const openAdapter = new TauriWorkspaceHostAdapter(
      bridgeWithInvoke(malformedOpen as WorkspaceNativeBridge["invoke"]),
    );
    const error = await openAdapter
      .open({ workspaceId: "ws_fixture", target: "vscode" })
      .catch((value: unknown) => value);
    expect(error).toMatchObject({ code: "RUNTIME_UNAVAILABLE", message: "运行时暂不可用" });
    expect(JSON.stringify(error)).not.toContain("Code.exe");
  });

  it("uses the typed mutation/watch command set and preserves the input envelope", async () => {
    const revision = metadata.revision;
    const invoke = vi.fn(async (command: string) => {
      switch (command) {
        case JA_WORKSPACE_COMMANDS.createEntry:
          return { relativePath: "src/new.txt", kind: "file", revision };
        case JA_WORKSPACE_COMMANDS.saveFile:
          return { relativePath: "src/main.rs", revision };
        case JA_WORKSPACE_COMMANDS.moveEntry:
          return { fromRelativePath: "src/main.rs", toRelativePath: "src/app.rs", revision };
        case JA_WORKSPACE_COMMANDS.trashPrepare:
          return {
            operationToken: "trash-token",
            fileCount: 1,
            totalBytes: 3,
            expiresAtUnixMillis: 99,
          };
        case JA_WORKSPACE_COMMANDS.trashCommit:
          return { committed: true, revision: null };
        case JA_WORKSPACE_COMMANDS.watchStart:
          return { started: true, generation: 1 };
        case JA_WORKSPACE_COMMANDS.watchStop:
          return { stopped: true };
        case JA_WORKSPACE_COMMANDS.watchRescan:
          return { generation: 2, requiresRescan: false, emittedPaths: 0 };
        default:
          return { importedRelativePaths: ["src/dropped.txt"] };
      }
    });
    const adapter = new TauriWorkspaceHostAdapter(
      bridgeWithInvoke(invoke as WorkspaceNativeBridge["invoke"]),
    );

    await expect(
      adapter.createEntry({
        workspaceId: "ws_fixture",
        relativePath: "src/new.txt",
        kind: "file",
        expectedRevision: null,
        mutationId: "create-1",
        content: { text: "hello\n", encoding: "utf8", lineEnding: "lf" },
      }),
    ).resolves.toMatchObject({ relativePath: "src/new.txt" });
    await expect(
      adapter.saveFile({
        workspaceId: "ws_fixture",
        relativePath: "src/main.rs",
        expectedRevision: revision,
        mutationId: "save-1",
        text: "fn main() {}\n",
        encoding: "utf8",
        lineEnding: "lf",
      }),
    ).resolves.toMatchObject({ relativePath: "src/main.rs" });
    await expect(
      adapter.moveEntry({
        workspaceId: "ws_fixture",
        fromRelativePath: "src/main.rs",
        toRelativePath: "src/app.rs",
        expectedRevision: revision,
        mutationId: "move-1",
      }),
    ).resolves.toMatchObject({ toRelativePath: "src/app.rs" });
    await expect(
      adapter.trashPrepare({
        workspaceId: "ws_fixture",
        relativePath: "src/main.rs",
        expectedRevision: revision,
        mutationId: "trash-prepare-1",
      }),
    ).resolves.toMatchObject({ operationToken: "trash-token" });
    await expect(
      adapter.trashCommit({
        workspaceId: "ws_fixture",
        relativePath: "src/main.rs",
        expectedRevision: revision,
        operationToken: "trash-token",
        mutationId: "trash-commit-1",
      }),
    ).resolves.toEqual({ committed: true, revision: null });
    await expect(adapter.watchStart({ workspaceId: "ws_fixture", generation: 0 })).resolves.toEqual(
      { started: true, generation: 1 },
    );
    await expect(
      adapter.watchRescan({ workspaceId: "ws_fixture", generation: 1 }),
    ).resolves.toEqual({ generation: 2, requiresRescan: false, emittedPaths: 0 });
    await expect(adapter.watchStop({ workspaceId: "ws_fixture", generation: 1 })).resolves.toEqual({
      stopped: true,
    });
    await expect(
      adapter.importDrop({
        workspaceId: "ws_fixture",
        destinationRelativePath: "",
        expectedRevision: revision,
        dropToken: "drop-token",
        mutationId: "drop-1",
      }),
    ).resolves.toEqual({ importedRelativePaths: ["src/dropped.txt"] });

    expect(invoke).toHaveBeenNthCalledWith(
      1,
      JA_WORKSPACE_COMMANDS.createEntry,
      expect.objectContaining({ input: expect.objectContaining({ mutationId: "create-1" }) }),
    );
    expect(invoke).toHaveBeenNthCalledWith(
      2,
      JA_WORKSPACE_COMMANDS.saveFile,
      expect.objectContaining({
        input: expect.objectContaining({ expectedRevision: revision, text: "fn main() {}\n" }),
      }),
    );
    expect(invoke).toHaveBeenCalledWith(
      JA_WORKSPACE_COMMANDS.importDrop,
      expect.objectContaining({
        input: expect.not.objectContaining({ absolutePath: expect.anything() }),
      }),
    );
  });

  it("redacts malformed watcher payloads and rejects mutation path injection before IPC", async () => {
    expect(
      parseWorkspaceChangedEvent({
        relativePath: "src/main.rs",
        generation: 3,
        revision: metadata.revision,
        requiresRescan: false,
      }),
    ).toMatchObject({ relativePath: "src/main.rs", generation: 3 });
    expect(() =>
      parseWorkspaceChangedEvent({
        relativePath: "C:\\private\\x",
        generation: 3,
        revision: null,
        requiresRescan: false,
      }),
    ).toThrowError("工作区变更事件无效");

    const invoke = vi.fn(async () => ({
      relativePath: "src/new.txt",
      kind: "file",
      revision: metadata.revision,
    }));
    const adapter = new TauriWorkspaceHostAdapter(
      bridgeWithInvoke(invoke as WorkspaceNativeBridge["invoke"]),
    );
    await expect(
      adapter.createEntry({
        workspaceId: "ws_fixture",
        relativePath: "../escape",
        kind: "file",
        expectedRevision: null,
        mutationId: "create-2",
      }),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    await expect(
      adapter.saveFile({
        workspaceId: "ws_fixture",
        relativePath: "src/main.rs",
        expectedRevision: metadata.revision,
        mutationId: "bad\nmutation",
        text: "x",
        encoding: "utf8",
        lineEnding: "lf",
      }),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    expect(invoke).not.toHaveBeenCalled();
  });

  it("enforces the native 4 MiB text ceiling on writes and read projections", async () => {
    const maxText = "a".repeat(4 * 1024 * 1024);
    const oversizedText = `${maxText}a`;
    const invoke = vi.fn(async () => ({
      relativePath: "src/main.rs",
      revision: metadata.revision,
    }));
    const adapter = new TauriWorkspaceHostAdapter(
      bridgeWithInvoke(invoke as WorkspaceNativeBridge["invoke"]),
    );

    await expect(
      adapter.saveFile({
        workspaceId: "ws_fixture",
        relativePath: "src/main.rs",
        expectedRevision: metadata.revision,
        mutationId: "save-max",
        text: maxText,
        encoding: "utf8",
        lineEnding: "lf",
      }),
    ).resolves.toMatchObject({ relativePath: "src/main.rs" });
    await expect(
      adapter.saveFile({
        workspaceId: "ws_fixture",
        relativePath: "src/main.rs",
        expectedRevision: metadata.revision,
        mutationId: "save-too-large",
        text: oversizedText,
        encoding: "utf8",
        lineEnding: "lf",
      }),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    await expect(
      adapter.createEntry({
        workspaceId: "ws_fixture",
        relativePath: "src/large.txt",
        kind: "file",
        expectedRevision: null,
        mutationId: "create-too-large",
        content: { text: oversizedText, encoding: "utf8", lineEnding: "lf" },
      }),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    expect(invoke).toHaveBeenCalledTimes(1);

    const oversizedRead = vi.fn(async () => ({
      ...fileFixture,
      text: oversizedText,
      bytesRead: oversizedText.length,
    }));
    await expect(
      new TauriWorkspaceHostAdapter(
        bridgeWithInvoke(oversizedRead as WorkspaceNativeBridge["invoke"]),
      ).readFile({
        workspaceId: "ws_fixture",
        relativePath: "src/main.rs",
      }),
    ).rejects.toMatchObject({ code: "RUNTIME_UNAVAILABLE", message: "运行时暂不可用" });
  });

  it("subscribes through the fixed watcher event and strictly parses payloads", async () => {
    const invoke = vi.fn(async () => treeFixture);
    const listener = vi.fn();
    const unlisten = vi.fn();
    const bridge: WorkspaceNativeBridge = {
      invoke: invoke as WorkspaceNativeBridge["invoke"],
      listen: vi.fn(async (event: string, handler: (payload: unknown) => void) => {
        expect(event).toBe(JA_WORKSPACE_EVENTS.changed);
        handler({
          relativePath: "src/main.rs",
          generation: 1,
          revision: metadata.revision,
          requiresRescan: false,
        });
        handler({
          relativePath: "C:\\private\\secret",
          generation: 1,
          revision: null,
          requiresRescan: false,
        });
        return unlisten;
      }) as WorkspaceNativeBridge["listen"],
    };
    const adapter = new TauriWorkspaceHostAdapter(bridge);
    await expect(adapter.subscribeChanged(listener)).resolves.toBe(unlisten);
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith(
      expect.objectContaining({ relativePath: "src/main.rs", generation: 1 }),
    );
  });

  it("accepts only the token-and-point native drop event", async () => {
    const dropToken = "550e8400-e29b-41d4-a716-446655440000";
    expect(
      parseWorkspaceNativeDropEvent({
        phase: "drop",
        dropToken,
        x: 12.5,
        y: 48,
        count: 1,
      }),
    ).toEqual({
      dropToken,
      x: 12.5,
      y: 48,
    });
    for (const payload of [
      { phase: "drop", dropToken, x: 12.5, count: 1 },
      { phase: "drop", dropToken, x: Number.NaN, y: 48, count: 1 },
      { phase: "enter", x: 12.5, y: 48, count: 1 },
      { phase: "drop", dropToken, x: 12.5, y: 48, count: 1, absolutePath: "C:\\private\\secret" },
    ]) {
      expect(() => parseWorkspaceNativeDropEvent(payload)).toThrowError("原生拖入事件无效");
    }
  });

  it("subscribes to native drop without forwarding malformed or path-bearing payloads", async () => {
    const dropToken = "550e8400-e29b-41d4-a716-446655440000";
    const listener = vi.fn();
    const unlisten = vi.fn();
    const bridge: WorkspaceNativeBridge = {
      invoke: vi.fn(async () => treeFixture) as WorkspaceNativeBridge["invoke"],
      listen: vi.fn(async (event: string, handler: (payload: unknown) => void) => {
        expect(event).toBe(JA_WORKSPACE_EVENTS.nativeDrop);
        handler({ phase: "enter", x: 1, y: 2, count: 1 });
        handler({ phase: "drop", dropToken, x: 1, y: 2, count: 1 });
        handler({ phase: "drop", dropToken, x: 1, y: 2, count: 1, path: "C:\\private\\secret" });
        return unlisten;
      }) as WorkspaceNativeBridge["listen"],
    };

    const unsubscribe = await new TauriWorkspaceHostAdapter(bridge).subscribeNativeDrop(listener);
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith({ dropToken, x: 1, y: 2 });
    expect(JSON.stringify(listener.mock.calls)).not.toContain("private");
    await unsubscribe();
    expect(unlisten).toHaveBeenCalledOnce();
  });
});
