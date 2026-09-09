// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import type { TurnArtifactAdapter } from "@/api/tauri/turnArtifacts";
import { createConversationArtifactPort } from "@/app/composition/defaultAdapters";

/** 创建可控制读取结果的 adapter，验证组合层不会把 native 细节泄漏给 Timeline。 */
function adapter(overrides: Partial<TurnArtifactAdapter>): TurnArtifactAdapter {
  return {
    readToolPage: vi.fn(),
    readTurnDiff: vi.fn(),
    ...overrides,
  };
}

describe("conversation artifact composition", () => {
  it("拼接 Tool character pages，并一次读取完整 Turn diff", async () => {
    const artifactAdapter = adapter({
      readToolPage: vi
        .fn()
        .mockResolvedValueOnce({
          artifactId: "artifact_tool",
          offsetCharacters: 0,
          nextOffsetCharacters: 3,
          totalCharacters: 6,
          truncated: true,
          content: "前三",
        })
        .mockResolvedValueOnce({
          artifactId: "artifact_tool",
          offsetCharacters: 3,
          nextOffsetCharacters: null,
          totalCharacters: 6,
          truncated: false,
          content: "后三",
        }),
      readTurnDiff: vi.fn().mockResolvedValueOnce({
        artifactId: "artifact_diff",
        filePath: "src/main.ts",
        byteLength: 12,
        sha256: "a".repeat(64),
        content: "完整正文",
      }),
    });
    const port = createConversationArtifactPort(artifactAdapter);

    await expect(
      port.readToolArtifact({
        workspaceId: "ws_demo",
        threadId: "thr_demo",
        turnId: "turn_demo",
        callId: "call_demo",
        artifactId: "artifact_tool",
      }),
    ).resolves.toBe("前三后三");
    await expect(
      port.readTurnDiff({
        workspaceId: "ws_demo",
        threadId: "thr_demo",
        turnId: "turn_demo",
        artifactId: "artifact_diff",
        filePath: "src/main.ts",
      }),
    ).resolves.toMatchObject({ content: "完整正文", byteLength: 12 });
    expect(artifactAdapter.readToolPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ offsetCharacters: 3, limitCharacters: 65_536 }),
    );
    expect(artifactAdapter.readTurnDiff).toHaveBeenCalledTimes(1);
    expect(artifactAdapter.readTurnDiff).toHaveBeenLastCalledWith(
      expect.objectContaining({ filePath: "src/main.ts" }),
    );
  });

  it("拒绝无进展游标，避免错误 native Tool page 形成无限读取", async () => {
    const artifactAdapter = adapter({
      readToolPage: vi.fn(async () => ({
        artifactId: "artifact_tool",
        offsetCharacters: 0,
        nextOffsetCharacters: 0,
        totalCharacters: 3,
        truncated: true,
        content: "页",
      })),
    });
    const port = createConversationArtifactPort(artifactAdapter);
    await expect(
      port.readToolArtifact({
        workspaceId: "ws_demo",
        threadId: "thr_demo",
        turnId: "turn_demo",
        callId: "call_demo",
        artifactId: "artifact_tool",
      }),
    ).rejects.toThrow("tool artifact stalled");
  });

  /** 文件切换只让已开始的单次读取收口，结果返回后仍必须遵守取消栅栏。 */
  it("中止冻结 Diff 后不提交已返回的旧文件", async () => {
    const controller = new AbortController();
    const readTurnDiff = vi.fn(async () => {
      controller.abort();
      return {
        artifactId: "artifact_diff",
        filePath: "src/main.ts",
        byteLength: 3,
        sha256: "a".repeat(64),
        content: "前",
      };
    });
    const port = createConversationArtifactPort(adapter({ readTurnDiff }));

    await expect(
      port.readTurnDiff(
        {
          workspaceId: "ws_demo",
          threadId: "thr_demo",
          turnId: "turn_demo",
          artifactId: "artifact_diff",
          filePath: "src/main.ts",
        },
        controller.signal,
      ),
    ).rejects.toMatchObject({ name: "AbortError" });
    expect(readTurnDiff).toHaveBeenCalledTimes(1);
  });
});
