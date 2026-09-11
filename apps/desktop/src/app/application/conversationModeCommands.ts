// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type {
  ComposerSlashCommand,
  ConversationCollaborationMode,
  ConversationInteractionController,
} from "@/features/conversation";
import type { GoalController } from "@/features/goals";

interface ConversationModeCommandOptions {
  readonly available: boolean;
  readonly threadId?: string;
  readonly interaction: Pick<
    ConversationInteractionController,
    "preferences" | "preferenceBusy" | "activeTurn" | "changeCollaborationMode"
  >;
  readonly goal: Pick<GoalController, "model" | "busyAction" | "create">;
}

/** 主会话与侧边会话共享严格参数解析；输入错误不能意外切换当前模式。 */
function planModeFromArgument(
  current: ConversationCollaborationMode,
  argument: string,
): ConversationCollaborationMode {
  const normalized = argument.normalize("NFKC").trim().toLocaleLowerCase();
  if (normalized === "" || normalized === "toggle") return current === "plan" ? "default" : "plan";
  if (["on", "plan", "开启", "打开", "计划"].includes(normalized)) return "plan";
  if (["off", "default", "关闭", "执行"].includes(normalized)) return "default";
  throw new Error("unsupported plan mode argument");
}

/**
 * 命令只绑定调用方明确提供的会话 controller，不推断主任务或侧边任务身份；两处入口由同一
 * 定义保持标签、参数、可用条件和执行语义一致，Goal 与权限、Plan 批准始终相互独立。
 */
export function conversationModeCommands({
  available,
  threadId,
  interaction,
  goal,
}: ConversationModeCommandOptions): ComposerSlashCommand[] {
  return [
    {
      id: "plan",
      name: "plan",
      aliases: ["计划"],
      group: "添加",
      icon: "plan",
      label: "计划",
      description: "先制定计划再决定是否执行",
      available:
        available &&
        interaction.preferences !== undefined &&
        !interaction.preferenceBusy &&
        !interaction.activeTurn,
      unavailableReason: interaction.activeTurn ? "当前运行结束后可切换" : "当前会话偏好尚未就绪",
      argument: { mode: "optional", label: "计划模式", placeholder: "输入 on 或 off" },
      /** 只更新指定会话协作模式，不发送 slash 原文或隐式批准计划。 */
      execute: async ({ argument }) => {
        const preferences = interaction.preferences;
        if (preferences === undefined) throw new Error("thread preferences unavailable");
        await interaction.changeCollaborationMode(
          planModeFromArgument(preferences.collaborationMode, argument),
        );
      },
    },
    {
      id: "goal",
      name: "goal",
      aliases: ["目标"],
      group: "添加",
      icon: "goal",
      label: "目标",
      description: "设置要持续追求的目标",
      available:
        available &&
        threadId !== undefined &&
        threadId !== "" &&
        goal.model === undefined &&
        !interaction.activeTurn &&
        goal.busyAction === undefined,
      unavailableReason:
        goal.model !== undefined
          ? "当前会话已有活跃目标"
          : interaction.activeTurn
            ? "当前运行结束后可创建目标"
            : "当前会话尚未就绪",
      argument: { mode: "required", label: "目标", placeholder: "描述目标" },
      /** Goal 的 owner 由注入的 controller 决定，ACK 失败保留用户命令供重试。 */
      execute: async ({ argument }) => {
        if (threadId === undefined || threadId === "" || !(await goal.create(threadId, argument)))
          throw new Error("goal creation was not acknowledged");
      },
    },
  ];
}
