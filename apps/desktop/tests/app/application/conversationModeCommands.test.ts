// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import { conversationModeCommands } from "@/app/application/conversationModeCommands";

/** 只提供命令实际依赖的会话能力，避免测试通过伪造完整主会话掩盖 owner 绑定。 */
function fixture(threadId = "thr_side") {
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
  const goal = { model: undefined, busyAction: undefined, create: vi.fn().mockResolvedValue(true) };
  const commands = conversationModeCommands({ available: true, threadId, interaction, goal });
  return { interaction, goal, commands };
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

  it("creates a Goal only for the explicitly bound side conversation", async () => {
    const side = fixture();
    const parent = fixture("thr_parent");
    await side.commands[1]!.execute({ argument: "验证侧边目标" });
    expect(side.goal.create).toHaveBeenCalledExactlyOnceWith("thr_side", "验证侧边目标");
    expect(parent.goal.create).not.toHaveBeenCalled();
  });

  it("retains the command failure when Goal admission is not acknowledged", async () => {
    const { commands, goal } = fixture();
    goal.create.mockResolvedValue(false);
    await expect(commands[1]!.execute({ argument: "目标" })).rejects.toThrow("not acknowledged");
  });

  it("uses the same busy and missing-identity availability gates", () => {
    const { interaction, goal } = fixture();
    const busy = conversationModeCommands({
      available: true,
      threadId: "thr_side",
      interaction: { ...interaction, activeTurn: true },
      goal,
    });
    expect(busy.every((command) => !command.available)).toBe(true);
    const draft = conversationModeCommands({ available: true, interaction, goal });
    expect(draft[0]!.available).toBe(true);
    expect(draft[1]!.available).toBe(false);
  });
});
