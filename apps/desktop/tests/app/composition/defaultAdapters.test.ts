// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import type { TurnArtifactAdapter } from "@/api/tauri/turnArtifacts";
import { createConversationArtifactPort } from "@/app/composition/defaultAdapters";

/** 创建可控制页游标的 adapter，验证组合层不会把分页细节泄漏给 Timeline。 */
function adapter(overrides: Partial<TurnArtifactAdapter>): TurnArtifactAdapter {
  return {
    readToolPage: vi.fn(),
    readTurnDiffPage: vi.fn(),
    ...overrides,
  };
}

describe("conversation artifact composition", () => {
  it("拼接 Tool character pages 与 Turn diff byte pages", async () => {
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
      readTurnDiffPage: vi
        .fn()
        .mockResolvedValueOnce({
          artifactId: "artifact_diff",
          offsetBytes: 0,
          nextOffsetBytes: 4,
          byteLength: 8,
          truncated: true,
          content: "前页",
        })
        .mockResolvedValueOnce({
          artifactId: "artifact_diff",
          offsetBytes: 4,
          nextOffsetBytes: null,
          byteLength: 8,
          truncated: false,
          content: "后页",
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
      }),
    ).resolves.toBe("前页后页");
    expect(artifactAdapter.readToolPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ offsetCharacters: 3, limitCharacters: 65_536 }),
    );
    expect(artifactAdapter.readTurnDiffPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ offsetBytes: 4, limitBytes: 65_536 }),
    );
  });

  it("拒绝无进展游标，避免错误 native page 形成无限读取", async () => {
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
});
