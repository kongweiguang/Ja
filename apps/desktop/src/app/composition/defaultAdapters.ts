// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { createDesktopIntegrationAdapters } from "@/api/tauri/desktop";
import { discardAttachment, importAttachments } from "@/api/tauri/attachments";
import { pickDirectory } from "@/api/tauri/dialog";
import { createHistoryAdapter } from "@/api/tauri/history";
import { TauriPreviewAdapter } from "@/api/tauri/preview";
import { createReviewAdapter } from "@/api/tauri/review";
import { parseSettingsConfigurationChange, TauriSettingsAdapter } from "@/api/tauri/settings";
import { TauriTerminalAdapter } from "@/api/tauri/terminal";
import { createWorkspaceHostAdapter } from "@/api/tauri/workspace";
import { createTurnArtifactAdapter, type TurnArtifactAdapter } from "@/api/tauri/turnArtifacts";
import type { WorkspacePickerPort } from "@/features/workspace";
import type { ConversationArtifactPort, ConversationAttachmentPort } from "@/features/conversation";
import type { JaWorkbenchAdapters } from "../useJaWorkbench";

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
/**
 * Composer 默认附件能力只组合脱敏的 Tauri adapter；原生路径、staging token 与 App Server
 * 导入事务都留在 native command 内，不进入 React 状态。
 */
export const DEFAULT_ATTACHMENT_PORT: ConversationAttachmentPort = {
  importAttachments,
  discardAttachment,
};

const MAX_TOOL_CHARACTERS = 4 * 1024 * 1024;
const MAX_DIFF_BYTES = 8 * 1024 * 1024;

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

/** 分页读取冻结 unified diff，并拒绝无进展或超出前端审查上限的结果。 */
async function readCompleteTurnDiff(
  input: Parameters<ConversationArtifactPort["readTurnDiff"]>[0],
  artifactAdapter: TurnArtifactAdapter,
): Promise<string> {
  let offsetBytes = 0;
  let content = "";
  while (true) {
    const page = await artifactAdapter.readTurnDiffPage({
      ...input,
      offsetBytes,
      limitBytes: 65_536,
    });
    if (page.byteLength > MAX_DIFF_BYTES) throw new Error("turn diff too large");
    content += page.content;
    if (page.nextOffsetBytes === null) return content;
    if (page.nextOffsetBytes <= offsetBytes) throw new Error("turn diff stalled");
    offsetBytes = page.nextOffsetBytes;
  }
}

/** 由组合根注入分页 adapter，使总量与无进展保护可独立验证且组件不接触页游标。 */
export function createConversationArtifactPort(
  artifactAdapter: TurnArtifactAdapter,
): ConversationArtifactPort {
  return {
    readToolArtifact: (input) => readCompleteToolArtifact(input, artifactAdapter),
    readTurnDiff: (input) => readCompleteTurnDiff(input, artifactAdapter),
  };
}

export const DEFAULT_CONVERSATION_ARTIFACT_PORT = createConversationArtifactPort(
  createTurnArtifactAdapter(),
);

/**
 * 正式 project picker 只在 composition 层绑定 Tauri dialog；取消继续返回 null，
 * workspace application 不依赖 native API，也不建立第二套路径状态。
 */
export const DEFAULT_WORKSPACE_PICKER: WorkspacePickerPort = {
  pick: async () => pickDirectory(),
};
