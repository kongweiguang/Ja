// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { InterfacePreferences } from "./interfacePreferences";
import type { CloseBehavior } from "@/shared/settings/closeBehavior";

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
  ProjectSkillSettingsDocument,
  SubagentSettings,
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
  /** 从已保存 Provider 读取上游模型目录；结果仅合并到编辑草稿，不能在这里隐式保存。 */
  onDiscoverModels: (providerId: string) => Promise<ModelDiscoveryResult>;
  onDeleteModel: (
    providerId: string,
    modelId: string,
    replacement: DefaultModelSelection | null,
  ) => Promise<void>;
  onMoveModel: (providerId: string, modelId: string, direction: -1 | 1) => Promise<void>;
  onDefaultSelectionChange: (selection: DefaultModelSelection) => Promise<void>;
  /** 子智能体只保存用户级策略；空模型引用表示在新会话中跟随父任务。 */
  onSubagentSettingsChange: (settings: SubagentSettings) => Promise<void>;
  /** 通过原生一次性 Command 替换 Credential，且绝不把它返回状态层。 */
  onReplaceCredential: (credentialId: string, secret: string) => Promise<void>;
  /** 清除 Credential 时保留不含 Secret 的 Model 或 MCP Selector。 */
  onClearCredential: (credentialId: string) => Promise<void>;
  /** Provider 编辑框短时读取其绑定 API Key，调用方关闭编辑框后不得保留返回值。 */
  onRevealProviderCredential: (providerId: string) => Promise<string | null>;
  onSaveMcp: (server: McpServerSave) => Promise<void>;
  onDeleteMcp: (id: string) => Promise<void>;
  onTestMcp: (id: string) => Promise<McpStatus>;
  onCloseMcp: (id: string) => Promise<void>;
  /** Skill 标识始终携带来源，scope 指向唯一允许写入的用户或当前项目文档。 */
  onToggleSkill: (id: string, enabled: boolean, scope: "user" | "project") => Promise<void>;
  onAccessModeChange: (mode: AccessMode) => Promise<void>;
  /** 普通模式是否允许模型发起结构化澄清；Plan 不受该开关影响。 */
  onClarificationEnabledChange: (enabled: boolean) => Promise<void>;
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
  /** 保存时携带读取基线，适配器据此只提交用户实际修改的配置字段。 */
  save(
    document: SettingsDocument,
    expectedVersion: string,
    baseline?: SettingsDocument,
  ): Promise<string>;
  saveProjectSkills(
    document: ProjectSkillSettingsDocument,
    workspaceId: string,
    expectedVersion: string,
  ): Promise<string>;
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
  restoreLastKnownGood(expectedVersion: string): Promise<string>;
  setCredential(credentialId: string, secret: string, expectedVersion: string): Promise<string>;
  deleteCredential(credentialId: string, expectedVersion: string): Promise<string>;
  revealProviderCredential(providerId: string): Promise<string | null>;
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

/** 本地界面偏好在 composition 连接唯一 store，设置组件只接收值和字段级保存动作。 */
export interface SettingsInterfacePreferences extends InterfacePreferences {
  onChange: <K extends keyof InterfacePreferences>(
    key: K,
    value: InterfacePreferences[K],
  ) => Promise<void>;
}

/** 设置页消费窄原生动作，不感知 Tauri plugin、持久化文件或 command 名称。 */
export interface SettingsDesktopPort {
  readCloseBehavior(): Promise<CloseBehavior>;
  saveCloseBehavior(value: CloseBehavior): Promise<void>;
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
    status: "healthy" | "available" | "degraded" | "unavailable" | "disabled" | "configured";
    toolCount: number;
  }>;
  nextCursor: string | null;
}

interface McpTestResult {
  mcpId: string;
  status: "healthy" | "available" | "degraded" | "unavailable";
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

/** 上游目录只公开安全模型标识与单页截断事实，避免将厂商原始对象带入设置状态。 */
export interface ModelDiscoveryResult {
  items: string[];
  truncated: boolean;
}

/** Settings runtime 端口只暴露领域能力，JA-RPC method 与 params envelope 留在 composition/infrastructure。 */
export interface SettingsRuntimePort {
  listSkills(input?: { workspaceId?: string }): Promise<SkillListResult>;
  listMcpServers(): Promise<McpListResult>;
  testMcp(mcpRevision: string): Promise<McpTestResult>;
  listMcpTools(mcpRevision: string): Promise<McpToolsResult>;
  testModel(providerId: string, modelId: string): Promise<ModelTestResult>;
  /** 目录读取走单独的受限 JA-RPC 方法，Provider endpoint 和 API Key 始终由 App Server 解析。 */
  discoverModels(providerId: string): Promise<ModelDiscoveryResult>;
}
