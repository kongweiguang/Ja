// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type {
  DefaultModelSelection,
  McpServerSave,
  McpAuth,
  McpTransport,
  ProviderModelSave,
  ProviderSave,
} from "@/shared/settings/types";
export type {
  DefaultModelSelection,
  McpServerSave,
  ProviderModelSave,
  ProviderSave,
} from "@/shared/settings/types";

export type ThemeMode = "system" | "light" | "dark";
type UiPalette = "xcode";
export type SettingsSection = "models" | "skills" | "mcp" | "permissions" | "appearance";
export type AccessMode = "approval_required" | "full_access";
export type SkillSource = "builtin" | "user" | "workspace";
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
  globallyEnabled?: boolean;
  projectOverridden?: boolean;
}
export interface SkillProjection {
  id: string;
  name: string;
  source: SkillSource;
  description: string;
  enabled: boolean;
  status: SkillStatus;
  lastGood?: string;
  error?: string;
  globallyEnabled?: boolean;
  projectOverridden?: boolean;
}
export interface AppearanceSettings {
  theme: ThemeMode;
  palette: UiPalette;
  reducedMotion: boolean;
  highContrast: boolean;
}

/** camelCase v4 聚合是 application 唯一可见的配置事实，不泄漏 JA-RPC wire 字段。 */
export interface SettingsDocument {
  schemaVersion: 4;
  revision: number;
  theme: ThemeMode;
  defaultAccessMode: AccessMode;
  defaultSelection: DefaultModelSelection | null;
  providers: ProviderProjection[];
  mcpServers: SettingsMcpServer[];
  skills?: SettingsSkill[];
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

interface SettingsSkill {
  skillId: string;
  name: string;
  scope: "builtin" | "user" | "workspace";
  enabled: boolean;
  description: string;
}

export interface LoadedSettings {
  document: SettingsDocument;
  userDocument: SettingsDocument;
  projectOverrides: ProjectSettingsOverrides;
  source: "Default" | "Primary" | "Backup";
  recovered: boolean;
  cas: { userVersion: string; projectVersion: string; credentialVersion: string };
}

/** 项目稀疏层只向 UI 暴露覆盖存在性，不泄漏或复制任意配置正文。 */
interface ProjectSettingsOverrides {
  defaultSelection: boolean;
  accessMode: boolean;
  disabledSkillIds: string[];
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
  providers: ProviderProjection[];
  skills: SkillProjection[];
  mcpServers: McpServerProjection[];
  defaultAccessMode: AccessMode;
  globalAccessMode: AccessMode;
  projectOverrides: ProjectSettingsOverrides;
  appearance: AppearanceSettings;
}
