// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import { conversationModeCommands } from "@/app/application/conversationModeCommands";

/** 只提供 Plan 命令依赖的偏好，避免测试把目标创建误当成模式切换。 */
function fixture() {
  const interaction = {
    preferences: {
      providerId: "provider_one",
      modelId: "model_one",
      reasoningLevel: null,
      accessMode: "approval_required" as const,
      collaborationMode: "default" as const,
      titleSource: "manual" as const,
    },
    preferenceBusy: false,
    activeTurn: false,
    changeCollaborationMode: vi.fn().mockResolvedValue(undefined),
  };
  const commands = conversationModeCommands({ available: true, interaction });
  return { interaction, commands };
}

describe("conversationModeCommands", () => {
  it("rejects invalid plan arguments without toggling either conversation", async () => {
    const { commands, interaction } = fixture();
    await expect(commands[0]!.execute({ argument: "unknown" })).rejects.toThrow();
    expect(interaction.changeCollaborationMode).not.toHaveBeenCalled();
  });

  it.each(["on", "打开", "ｐｌａｎ"])("shares normalized Plan arguments: %s", async (argument) => {
    const { commands, interaction } = fixture();
    await commands[0]!.execute({ argument });
    expect(interaction.changeCollaborationMode).toHaveBeenCalledExactlyOnceWith("plan");
    expect(interaction.preferences.accessMode).toBe("approval_required");
  });

  it("exposes only Plan and disables it while a turn is active", () => {
    const { interaction } = fixture();
    const busy = conversationModeCommands({
      available: true,
      interaction: { ...interaction, activeTurn: true },
    });
    expect(busy).toHaveLength(1);
    expect(busy[0]!.available).toBe(false);
    expect(conversationModeCommands({ available: true, interaction })[0]!.available).toBe(true);
  });
});
