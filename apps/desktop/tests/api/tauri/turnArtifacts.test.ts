// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import type { RuntimeNativeBridge } from "@/api/tauri/runtime";
import { TauriTurnArtifactAdapter } from "@/api/tauri/turnArtifacts";

/** 构造只实现 invoke 的窄 bridge，避免测试依赖 Tauri 全局对象。 */
function bridgeWith(result: unknown): Pick<RuntimeNativeBridge, "invoke"> {
  return {
    invoke: vi.fn(async () => result) as RuntimeNativeBridge["invoke"],
  };
}

describe("TauriTurnArtifactAdapter", () => {
  it("使用固定 command 与严格 identity 读取 Tool 字符页", async () => {
    const bridge = bridgeWith({
      artifactId: "artifact_tool",
      offsetCharacters: 0,
      nextOffsetCharacters: null,
      totalCharacters: 4,
      truncated: false,
      content: "完成",
    });
    const adapter = new TauriTurnArtifactAdapter(bridge);
    const input = {
      workspaceId: "ws_demo",
      threadId: "thr_demo",
      turnId: "turn_demo",
      callId: "call_demo",
      artifactId: "artifact_tool",
      offsetCharacters: 0,
      limitCharacters: 65_536,
    };

    await expect(adapter.readToolPage(input)).resolves.toMatchObject({ content: "完成" });
    expect(bridge.invoke).toHaveBeenCalledWith("ja_tool_artifact_read", { input });
  });

  it("一次读取完整本轮修改并拒绝非法路径或畸形返回", async () => {
    const bridge = bridgeWith({
      artifactId: "artifact_diff",
      filePath: "src/main.ts",
      byteLength: 4,
      sha256: "a".repeat(64),
      content: "abcd",
    });
    const adapter = new TauriTurnArtifactAdapter(bridge);
    const input = {
      workspaceId: "ws_demo",
      threadId: "thr_demo",
      turnId: "turn_demo",
      artifactId: "artifact_diff",
      filePath: "src/main.ts",
    };

    await expect(adapter.readTurnDiff(input)).resolves.toMatchObject({ content: "abcd" });
    expect(bridge.invoke).toHaveBeenCalledWith("ja_turn_change_set_read", { input });
    await expect(adapter.readTurnDiff({ ...input, filePath: "../main.ts" })).rejects.toMatchObject({
      code: "INVALID_INPUT",
    });
    expect(bridge.invoke).toHaveBeenCalledTimes(1);

    const malformed = new TauriTurnArtifactAdapter(
      bridgeWith({
        ...input,
        byteLength: 2,
        sha256: "a".repeat(64),
        content: "x\0",
      }),
    );
    await expect(malformed.readTurnDiff(input)).rejects.toMatchObject({
      code: "RUNTIME_UNAVAILABLE",
    });
  });

  /** artifact、path、UTF-8 总长度与摘要任一不闭合都必须在 WebView 边界失败。 */
  it.each([
    [
      "artifact identity",
      {
        artifactId: "artifact_other",
        filePath: "src/main.ts",
        byteLength: 1,
        sha256: "a".repeat(64),
        content: "x",
      },
    ],
    [
      "file identity",
      {
        artifactId: "artifact_diff",
        filePath: "src/other.ts",
        byteLength: 1,
        sha256: "a".repeat(64),
        content: "x",
      },
    ],
    [
      "UTF-8 byte length",
      {
        artifactId: "artifact_diff",
        filePath: "src/main.ts",
        byteLength: 2,
        sha256: "a".repeat(64),
        content: "x",
      },
    ],
    [
      "digest",
      {
        artifactId: "artifact_diff",
        filePath: "src/main.ts",
        byteLength: 3,
        sha256: "invalid",
        content: "界",
      },
    ],
  ])("拒绝不一致的%s", async (_label, response) => {
    const adapter = new TauriTurnArtifactAdapter(bridgeWith(response));
    await expect(
      adapter.readTurnDiff({
        workspaceId: "ws_demo",
        threadId: "thr_demo",
        turnId: "turn_demo",
        artifactId: "artifact_diff",
        filePath: "src/main.ts",
      }),
    ).rejects.toMatchObject({ code: "RUNTIME_UNAVAILABLE" });
  });
});
