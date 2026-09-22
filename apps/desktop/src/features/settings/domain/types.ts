// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type {
  DefaultModelSelection,
  McpServerSave,
  McpAuth,
  McpTransport,
  ProviderModelSave,
  ProviderSave,
  ReasoningLevel,
} from "@/shared/settings/types";
import type { ThemeMode, UiPalette } from "@/shared/styles/theme";
export type {
  DefaultModelSelection,
  McpServerSave,
  ProviderModelSave,
  ProviderSave,
} from "@/shared/settings/types";

export { UI_PALETTE_LABELS, UI_PALETTE_ORDER } from "@/shared/styles/theme";
export type { ThemeMode, UiPalette } from "@/shared/styles/theme";
export type SettingsSection =
  | "general"
  | "appearance"
  | "models"
  | "subagents"
  | "permissions"
  | "skills"
  | "mcp"
  | "about";
export type AccessMode = "approval_required" | "full_access";
export type SkillSource = "builtin" | "user" | "ja" | "project";
type SkillStatus = "ready" | "disabled" | "reloading" | "error";
export type McpStatus = "unknown" | "connected" | "disabled" | "testing" | "error";
type McpProtocolVersion = "2024-11-05" | "2025-03-26" | "2025-06-18";
type McpToolPolicy = "allow" | "ask" | "deny";

/** Provider 表单编辑稳定路由字段，模型通过独立子列表管理，避免修改名称时改变身份。 */
export interface ProviderDraft extends Omit<ProviderSave, "providerId" | "models"> {
  providerId?: string;
}

/** Model 表单只编辑单个 Provider 内的能力，不复制 Provider 连接信息。 */
export interface ProviderModelDraft extends Omit<ProviderModelSave, "modelId"> {
  modelId?: string;
}

/** Settings 卡片增加脱敏 Credential 状态，但该状态永远不会写回配置文档。 */
export interface ProviderProjection extends ProviderSave {
  credentialConfigured: boolean;
}

export interface McpServerDraft {
  mcpRevision?: string;
  name: string;
  transport: McpTransport;
  endpoint: string;
  argsText: string;
  envText: string;
  headersText: string;
  authKind: McpAuth["kind"];
  authName: string;
  credentialRef: string;
  enabled: boolean;
}

export interface McpToolProjection {
  name: string;
  policy: McpToolPolicy;
}
export interface McpServerProjection extends McpServerSave {
  id: string;
  protocolVersion: McpProtocolVersion;
  status: McpStatus;
  tools: McpToolProjection[];
  lastError?: string;
}
export interface SkillProjection {
  id: string;
  name: string;
  source: SkillSource;
  description: string;
  enabled: boolean;
  /** 已登记但发现阶段未找到 `SKILL.md` 的记录仍可在设置中显式移除。 */
  missing?: boolean;
  status: SkillStatus;
  lastGood?: string;
  error?: string;
}
export interface AppearanceSettings {
  theme: ThemeMode;
  palette: UiPalette;
  reducedMotion: boolean;
  reducedTransparency: boolean;
  highContrast: boolean;
}

/** 子智能体策略是用户级配置的唯一前端投影；引用为空表示派发时跟随父任务模型。 */
export interface SubagentSettings {
  enabled: boolean;
  providerId: string | null;
  modelId: string | null;
  reasoningLevel: ReasoningLevel | null;
}

/** camelCase v2 聚合是 application 唯一可见的用户配置事实，不泄漏 JA-RPC wire 字段。 */
export interface SettingsDocument {
  schemaVersion: 2;
  revision: number;
  theme: ThemeMode;
  defaultAccessMode: AccessMode;
  clarificationEnabled?: boolean;
  defaultSelection: DefaultModelSelection | null;
  subagents: SubagentSettings;
  providers: ProviderProjection[];
  mcpServers: SettingsMcpServer[];
  /** 只保存 `user:name` 或 `ja:name` 引用，描述、路径和开关均来自运行时发现。 */
  skills: string[];
  window: { width: number; height: number; maximized: boolean };
}

export interface SettingsMcpServer {
  mcpRevision: string;
  name: string;
  transport: McpTransport;
  endpoint: string;
  protocolVersion: "2024-11-05" | "2025-03-26" | "2025-06-18";
  args: string[];
  env: Record<string, string>;
  headers: Record<string, string>;
  auth: McpAuth;
  enabled: boolean;
}

/** 项目 Skill 文档保持最小独立模型；首次项目操作才由 App Server 创建其持久化文件。 */
export interface ProjectSkillSettingsDocument {
  schemaVersion: 2;
  revision: number;
  skills: string[];
  disabledSkills: string[];
}

/** App Server 问题投影只描述当前影响与可执行动作，永不携带配置正文、路径或 Secret。 */
export interface ConfigurationIssue {
  id: string;
  scope: "user" | "project" | "credential";
  field: string | null;
  entityId: string | null;
  line: number | null;
  column: number | null;
  reason: string;
  impact: string;
  actions: Array<"edit" | "retry" | "restore">;
}

export interface LoadedSettings {
  document: SettingsDocument;
  userDocument: SettingsDocument;
  projectSkillDocument?: ProjectSkillSettingsDocument;
  projectOverrides: ProjectSettingsOverrides;
  cas: { userVersion: string; projectVersion: string; credentialVersion: string };
  /** 问题不改变可编辑 userDocument；保存路径必须只提交用户明确的变化。 */
  issues: ConfigurationIssue[];
}

/** 项目稀疏层只向 UI 暴露覆盖存在性，不泄漏或复制任意配置正文。 */
interface ProjectSettingsOverrides {
  defaultSelection: boolean;
  accessMode: boolean;
  disabledSkillReferences: string[];
  disabledMcpIds: string[];
}
export interface SettingsConfigurationChange {
  serverInstanceId: string;
  generation: number;
  version: string;
  scope: "user" | "project";
  workspaceId?: string;
}
export interface SettingsSnapshot {
  revision: number;
  defaultSelection: DefaultModelSelection | null;
  subagents: SubagentSettings;
  providers: ProviderProjection[];
  skills: SkillProjection[];
  mcpServers: McpServerProjection[];
  defaultAccessMode: AccessMode;
  clarificationEnabled?: boolean;
  appearance: AppearanceSettings;
}
