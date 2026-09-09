// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type {
  DefaultModelSelection,
  McpServerSave,
  McpTransport,
  ProviderModelSave,
  ProviderSave,
} from "@/shared/settings/types";
import type {
  AppearanceSettings,
  LoadedSettings,
  McpStatus,
  AccessMode,
  SettingsDocument,
  ThemeMode,
  UiPalette,
} from "../domain/types";

/**
 * Application action Port 只接受规范 DTO；UI 的 Probe/Status 投影不能被误写回设置文档。
 */
export interface SettingsPorts {
  /** 首次创建先提交 Provider 聚合，再把一次性 Secret 写入独立凭据 owner。 */
  onCreateProvider: (provider: ProviderSave, secret: string) => Promise<void>;
  onSaveProvider: (provider: ProviderSave) => Promise<void>;
  onDeleteProvider: (
    providerId: string,
    replacement: DefaultModelSelection | null,
  ) => Promise<void>;
  onMoveProvider: (providerId: string, direction: -1 | 1) => Promise<void>;
  onSaveModel: (providerId: string, model: ProviderModelSave) => Promise<void>;
  onTestModel: (providerId: string, modelId: string) => Promise<ModelTestResult>;
  onDeleteModel: (
    providerId: string,
    modelId: string,
    replacement: DefaultModelSelection | null,
  ) => Promise<void>;
  onMoveModel: (providerId: string, modelId: string, direction: -1 | 1) => Promise<void>;
  onDefaultSelectionChange: (selection: DefaultModelSelection) => Promise<void>;
  /** 通过原生一次性 Command 替换 Credential，且绝不把它返回状态层。 */
  onReplaceCredential: (credentialId: string, secret: string) => Promise<void>;
  /** 清除 Credential 时保留不含 Secret 的 Model 或 MCP Selector。 */
  onClearCredential: (credentialId: string) => Promise<void>;
  onSaveMcp: (server: McpServerSave) => Promise<void>;
  onDeleteMcp: (id: string) => Promise<void>;
  onTestMcp: (id: string) => Promise<McpStatus>;
  onCloseMcp: (id: string) => Promise<void>;
  onToggleSkill: (id: string, enabled: boolean) => Promise<void>;
  onAccessModeChange: (mode: AccessMode) => Promise<void>;
  onAppearanceChange: (
    appearance: AppearanceSettings,
    changed: keyof AppearanceSettings,
  ) => Promise<void>;
}

/**
 * 设置端口只表达领域用例：读取脱敏聚合、保存完整聚合和处理配置失效提示。
 * JA-RPC 方法、snake_case DTO、CAS token 与宽松事件解析均由 infrastructure adapter 独占。
 */
export interface SettingsAdapter {
  snapshot(input?: { workspaceId?: string }): Promise<LoadedSettings>;
  save(document: SettingsDocument, expectedVersion: string): Promise<string>;
  patch(input: {
    scope: "project";
    workspaceId: string;
    expectedVersion: string;
    patch: Record<string, unknown>;
  }): Promise<{ version: string }>;
  reset(input: {
    scope: "project";
    workspaceId: string;
    expectedVersion: string;
  }): Promise<{ version: string }>;
  setCredential(credentialId: string, secret: string, expectedVersion: string): Promise<string>;
  deleteCredential(credentialId: string, expectedVersion: string): Promise<string>;
}

/**
 * UI preference 通过组合层注入，避免 Settings application 直接绑定具体持久化 Store；
 * Port 只暴露本用例需要的外观事实和动作，不形成第二套持久化 owner。
 */
export interface SettingsAppearancePort {
  themeMode: ThemeMode;
  palette: UiPalette;
  reducedMotion: boolean;
  reducedTransparency: boolean;
  highContrast: boolean;
  setThemeMode(mode: ThemeMode): void;
  setPalette(palette: UiPalette): void;
  setHighContrast(enabled: boolean): void;
  setReduceMotion(enabled: boolean): void;
  setReducedTransparency(enabled: boolean): void;
}

export type SettingsUpdateCheckResult =
  | { kind: "unavailable" }
  | { kind: "up-to-date" }
  | { kind: "available"; currentVersion: string; version: string; publishedAt?: string };

export interface SettingsUpdateProgress {
  readonly downloadedBytes: number;
  readonly contentLength?: number;
  readonly percent?: number;
}

/** 桌面设置只消费外链与更新四个窄动作，不感知 Tauri plugin、Resource 或 command 名称。 */
export interface SettingsDesktopPort {
  openExternalUrl(url: string): Promise<void>;
  checkForUpdate(): Promise<SettingsUpdateCheckResult>;
  installUpdate(onProgress: (progress: SettingsUpdateProgress) => void): Promise<void>;
  relaunchAfterUpdate(): Promise<void>;
}

/** Runtime 生命周期只提供设置首次读取的 generation admission。 */
export interface SettingsRuntimeState {
  status:
    | "starting"
    | "ready"
    | "busy"
    | "stopping"
    | "stopped"
    | "recovery_required"
    | "crashed"
    | "incompatible"
    | "faulted";
  generation: number;
  serverInstanceId?: string | null;
}

/** native storage 端口只返回设置页面可展示的脱敏路径和打包状态。 */
export interface SkillListResult {
  items: Array<{
    skillId: string;
    name: string;
    scope: "builtin" | "user" | "ja" | "project";
    enabled: boolean;
    status: "healthy" | "invalid" | "unavailable";
    description: string;
  }>;
  nextCursor: string | null;
}

export interface McpListResult {
  items: Array<{
    mcpId: string;
    name: string;
    transport: McpTransport;
    status: "healthy" | "degraded" | "unavailable" | "disabled";
    toolCount: number;
  }>;
  nextCursor: string | null;
}

interface McpTestResult {
  mcpId: string;
  status: "healthy" | "degraded" | "unavailable";
  toolCount: number;
}

export interface McpToolsResult {
  items: Array<{ name: string; description: string }>;
  nextCursor: string | null;
}

interface ModelTestResult {
  responseModel: string;
  latencyMs: number;
}

/** Settings runtime 端口只暴露领域能力，JA-RPC method 与 params envelope 留在 composition/infrastructure。 */
export interface SettingsRuntimePort {
  listSkills(input?: { workspaceId?: string }): Promise<SkillListResult>;
  listMcpServers(): Promise<McpListResult>;
  testMcp(mcpRevision: string): Promise<McpTestResult>;
  listMcpTools(mcpRevision: string): Promise<McpToolsResult>;
  testModel(providerId: string, modelId: string): Promise<ModelTestResult>;
}
