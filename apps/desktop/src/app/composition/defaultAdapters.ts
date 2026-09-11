// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { createDesktopIntegrationAdapters } from "@/api/tauri/desktop";
import { TauriAttachmentPreviewAdapter } from "@/api/tauri/attachmentPreview";
import {
  cancelImport,
  clipboardImport,
  discardAttachment,
  discardAttempt,
  dropImport,
  pickerImport,
  retryImport,
} from "@/api/tauri/attachments";
import { pickDirectory } from "@/api/tauri/dialog";
import { createHistoryAdapter } from "@/api/tauri/history";
import { TauriPreviewAdapter } from "@/api/tauri/preview";
import { createReviewAdapter } from "@/api/tauri/review";
import { parseSettingsConfigurationChange, TauriSettingsAdapter } from "@/api/tauri/settings";
import { TauriTerminalAdapter } from "@/api/tauri/terminal";
import { createWorkspaceHostAdapter } from "@/api/tauri/workspace";
import { nativeDropRouterFor } from "@/api/tauri/nativeDrop";
import { defaultNativeBridge } from "@/api/tauri/runtime";
import { createGoalAdapter } from "@/api/tauri/goals";
import { createInteractionAdapter } from "@/api/tauri/interaction";
import { createInteractionPort, subscribeInteractionHostEvents } from "@/features/conversation";
import { createTurnArtifactAdapter, type TurnArtifactAdapter } from "@/api/tauri/turnArtifacts";
import type { WorkspacePickerPort } from "@/features/workspace";
import type { AttachmentPreviewPort } from "@/features/workbench/preview";
import type { TurnReviewPort } from "@/features/workbench/review";
import type { ConversationArtifactPort, ConversationAttachmentPort } from "@/features/conversation";
import type { JaWorkbenchAdapters } from "../useJaWorkbench";
import { createGoalPort, subscribeGoalHostEvents } from "@/features/goals";

export const DEFAULT_DESKTOP_INTEGRATIONS = createDesktopIntegrationAdapters();
export const DEFAULT_WORKBENCH_ADAPTERS: JaWorkbenchAdapters = {
  workspace: createWorkspaceHostAdapter(),
  review: createReviewAdapter(),
  terminal: new TauriTerminalAdapter(),
  preview: new TauriPreviewAdapter(),
};
export const DEFAULT_SETTINGS_ADAPTER = new TauriSettingsAdapter();
/** 在 composition adapter 模块暴露类型化失效投影，避免应用组合组件接触 API/wire 模块。 */
export const projectSettingsConfigurationChange = parseSettingsConfigurationChange;
export const DEFAULT_HISTORY_ADAPTER = createHistoryAdapter();
/** Goal feature 与 Task 共用唯一 Runtime 事件订阅，mutation 仍走专用 Tauri command。 */
export const DEFAULT_GOAL_PORT = createGoalPort(createGoalAdapter(), {
  subscribe: subscribeGoalHostEvents,
});
/** 问答动作走专用 typed adapter，事件复用唯一宿主监听器。 */
export const DEFAULT_INTERACTION_PORT = createInteractionPort(createInteractionAdapter(), {
  subscribe: subscribeInteractionHostEvents,
});
/**
 * Composer 默认附件能力只组合脱敏的 Tauri adapter；原生路径、staging token 与 App Server
 * 导入事务都留在 native command 内，不进入 React 状态。
 */
export const DEFAULT_ATTACHMENT_PORT: ConversationAttachmentPort = {
  pickerImport,
  dropImport,
  clipboardImport,
  retryImport,
  cancelImport,
  discardAttempt,
  discardAttachment,
};
/** 附件预览 adapter 只公开 opaque session 与受控协议资源，不把通用 Runtime bridge 下放给视图。 */
export const DEFAULT_ATTACHMENT_PREVIEW_PORT: AttachmentPreviewPort =
  new TauriAttachmentPreviewAdapter();
/** Composer、Files 与 Terminal 通过同一 bridge identity 复用唯一原生拖放 listener。 */
export const DEFAULT_NATIVE_DROP_PORT = nativeDropRouterFor(defaultNativeBridge);

const MAX_TOOL_CHARACTERS = 4 * 1024 * 1024;

/** 分页读取 Tool artifact，并在进入 React 状态前执行总量上限。 */
async function readCompleteToolArtifact(
  input: Parameters<ConversationArtifactPort["readToolArtifact"]>[0],
  artifactAdapter: TurnArtifactAdapter,
): Promise<string> {
  let offsetCharacters = 0;
  let content = "";
  while (true) {
    const page = await artifactAdapter.readToolPage({
      ...input,
      offsetCharacters,
      limitCharacters: 65_536,
    });
    if (page.totalCharacters > MAX_TOOL_CHARACTERS) throw new Error("tool artifact too large");
    content += page.content;
    if (page.nextOffsetCharacters === null) return content;
    if (page.nextOffsetCharacters <= offsetCharacters) throw new Error("tool artifact stalled");
    offsetCharacters = page.nextOffsetCharacters;
  }
}

/** 一次读取一个有界冻结 Diff；identity 与长度已在 Tauri adapter 边界完成校验。 */
async function readTurnDiff(
  input: Parameters<ConversationArtifactPort["readTurnDiff"]>[0],
  artifactAdapter: TurnArtifactAdapter,
  signal?: AbortSignal,
): ReturnType<TurnArtifactAdapter["readTurnDiff"]> {
  signal?.throwIfAborted();
  return artifactAdapter.readTurnDiff(input).then((result) => {
    signal?.throwIfAborted();
    return result;
  });
}

/** 由组合根注入分页 adapter，使总量与无进展保护可独立验证且组件不接触页游标。 */
export function createConversationArtifactPort(
  artifactAdapter: TurnArtifactAdapter,
): ConversationArtifactPort {
  return {
    readToolArtifact: (input) => readCompleteToolArtifact(input, artifactAdapter),
    readTurnDiff: (input, signal) => readTurnDiff(input, artifactAdapter, signal),
  };
}

export const DEFAULT_CONVERSATION_ARTIFACT_PORT = createConversationArtifactPort(
  createTurnArtifactAdapter(),
);

/** 冻结 Review 只代理持久 artifact；运行中修改统一通过 Git 视图查看。 */
export function createTurnReviewPort(artifactPort: ConversationArtifactPort): TurnReviewPort {
  return {
    readFrozen: (target, file, signal) => {
      return artifactPort.readTurnDiff(
        {
          workspaceId: target.workspaceId,
          threadId: target.threadId,
          turnId: target.turnId,
          artifactId: target.artifactId,
          filePath: file.path,
        },
        signal,
      );
    },
  };
}

export const DEFAULT_TURN_REVIEW_PORT = createTurnReviewPort(DEFAULT_CONVERSATION_ARTIFACT_PORT);

/**
 * 正式 project picker 只在 composition 层绑定 Tauri dialog；取消继续返回 null，
 * workspace application 不依赖 native API，也不建立第二套路径状态。
 */
export const DEFAULT_WORKSPACE_PICKER: WorkspacePickerPort = {
  pick: async () => pickDirectory(),
};
