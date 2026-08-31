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

  it("使用 UTF-8 byte 页读取冻结差异并拒绝越界或畸形返回", async () => {
    const bridge = bridgeWith({
      artifactId: "artifact_diff",
      offsetBytes: 0,
      nextOffsetBytes: 32,
      byteLength: 64,
      truncated: true,
      content: "--- a/file\n+++ b/file",
    });
    const adapter = new TauriTurnArtifactAdapter(bridge);
    const input = {
      workspaceId: "ws_demo",
      threadId: "thr_demo",
      turnId: "turn_demo",
      artifactId: "artifact_diff",
      offsetBytes: 0,
      limitBytes: 65_536,
    };
    await expect(adapter.readTurnDiffPage(input)).resolves.toMatchObject({ nextOffsetBytes: 32 });
    expect(bridge.invoke).toHaveBeenCalledWith("ja_turn_change_set_read", { input });

    await expect(
      adapter.readTurnDiffPage({ ...input, offsetBytes: 2_097_153 }),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    expect(bridge.invoke).toHaveBeenCalledTimes(1);

    const malformed = new TauriTurnArtifactAdapter(
      bridgeWith({
        ...input,
        nextOffsetBytes: null,
        byteLength: 2,
        truncated: false,
        content: "x\0",
      }),
    );
    await expect(malformed.readTurnDiffPage(input)).rejects.toMatchObject({
      code: "RUNTIME_UNAVAILABLE",
    });
  });
});
