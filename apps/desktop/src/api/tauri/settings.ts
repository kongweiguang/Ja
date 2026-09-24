// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { invoke as tauriInvoke } from "@tauri-apps/api/core";
import { z } from "zod";
import {
  ConfigCredentialRefSchema,
  ConfigDocumentSchema,
  ConfigMcpServerSchema,
  ConfigModelIdSchema,
  ConfigModelSchema,
  ConfigProjectDocumentSchema,
  ConfigProviderIdSchema,
  ConfigProviderSchema,
  ConfigSkillReferenceSchema,
} from "@/api/protocol/configDocument";
import { invokeNativeCommand } from "./nativeInvoke";

/** Settings 只暴露 app-server 配置与凭据白名单能力，不允许通用 RPC 穿过 WebView。 */
export const JA_SETTINGS_COMMANDS = {
  read: "ja_configuration_read",
  patch: "ja_configuration_patch",
  replace: "ja_configuration_replace",
  reset: "ja_configuration_reset",
  restore: "ja_configuration_restore",
  setCredential: "ja_credential_set",
  deleteCredential: "ja_credential_delete",
  revealProviderCredential: "ja_credential_reveal_provider",
} as const;
type SettingsCommand = (typeof JA_SETTINGS_COMMANDS)[keyof typeof JA_SETTINGS_COMMANDS];

const MAX_STRING = 512;
const MAX_ENTRIES = 512;
const MAX_SECRET_BYTES = 8_192;
const CredentialRefSchema = ConfigCredentialRefSchema;
const ConfigVersionSchema = z
  .string()
  .regex(/^cfg_(?:missing|[A-Za-z0-9_-]+)$/)
  .max(256);
const WorkspaceIdSchema = z
  .string()
  .regex(/^ws_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(100);
const ConfigScopeSchema = z.enum(["user", "project"]);
const ConfigReadInputSchema = z.object({ workspaceId: WorkspaceIdSchema.optional() }).strict();
const ConfigValuesSchema = z.record(z.string().min(1).max(256), z.unknown());
const ConfigLayerSchema = z
  .object({
    present: z.boolean(),
    trusted: z.boolean(),
    status: z.enum(["missing", "valid", "untrusted", "corrupt", "io_error"]),
    document: ConfigValuesSchema.nullable(),
  })
  .strict();
const ConfigCasSchema = z
  .object({
    userVersion: ConfigVersionSchema,
    projectVersion: ConfigVersionSchema,
    credentialVersion: ConfigVersionSchema,
  })
  .strict();
const ConfigIssueSchema = z
  .object({
    id: z.string().regex(/^cfg_[A-Za-z0-9_-]{1,64}$/),
    scope: z.enum(["user", "project", "credential"]),
    field: z.string().min(1).max(128).nullable(),
    entityId: z.string().min(1).max(128).nullable(),
    line: z.number().int().min(1).nullable(),
    column: z.number().int().min(1).nullable(),
    reason: z.string().regex(/^[A-Z][A-Z0-9_]{0,63}$/),
    impact: z.string().regex(/^[a-z_]{1,64}$/),
    actions: z.array(z.enum(["edit", "retry", "restore"])).max(3),
  })
  .strict();
const ConfigReadResultSchema = z
  .object({
    workspaceId: WorkspaceIdSchema.nullable(),
    trusted: z.boolean(),
    effective: ConfigValuesSchema,
    user: ConfigLayerSchema,
    project: ConfigLayerSchema,
    credentials: z.record(CredentialRefSchema, z.object({ configured: z.boolean() }).strict()),
    cas: ConfigCasSchema,
    diagnostics: z.array(z.string().regex(/^[A-Z][A-Z0-9_]{0,63}$/)).max(32),
    issues: z.array(ConfigIssueSchema).max(64),
  })
  .strict();
const ConfigWriteResultSchema = z
  .object({
    accepted: z.literal(true),
    scope: ConfigScopeSchema,
    version: ConfigVersionSchema,
  })
  .strict();
const userConfigTarget = { scope: z.literal("user"), expectedVersion: ConfigVersionSchema };
const projectConfigTarget = {
  scope: z.literal("project"),
  workspaceId: WorkspaceIdSchema,
  expectedVersion: ConfigVersionSchema,
};
const ConfigPatchInputSchema = z.discriminatedUnion("scope", [
  z.object({ ...userConfigTarget, patch: ConfigValuesSchema }).strict(),
  z.object({ ...projectConfigTarget, patch: ConfigValuesSchema }).strict(),
]);
const ConfigReplaceInputSchema = z.discriminatedUnion("scope", [
  z.object({ ...userConfigTarget, document: ConfigDocumentSchema }).strict(),
  z.object({ ...projectConfigTarget, document: ConfigProjectDocumentSchema }).strict(),
]);
const ConfigResetInputSchema = z.discriminatedUnion("scope", [
  z.object(userConfigTarget).strict(),
  z.object(projectConfigTarget).strict(),
]);
const ConfigRestoreInputSchema = z.object({ expectedVersion: ConfigVersionSchema }).strict();

const UiReasoningLevelSchema = z.enum(["off", "minimal", "low", "medium", "high", "xhigh", "max"]);
const UiReasoningLevelMapSchema = z
  .object({
    off: z.string().optional(),
    minimal: z.string().optional(),
    low: z.string().optional(),
    medium: z.string().optional(),
    high: z.string().optional(),
    xhigh: z.string().optional(),
    max: z.string().optional(),
  })
  .strict();
const UiProviderModelSchema = z
  .object({
    modelId: ConfigModelIdSchema,
    name: z.string().min(1).max(MAX_STRING),
    model: z.string().min(1).max(MAX_STRING),
    capabilities: z
      .object({
        contextWindowTokens: z.number().int().min(4_096).max(4_000_000),
        maxOutputTokens: z.number().int().min(1).max(1_000_000),
      })
      .strict(),
    reasoningLevelMap: UiReasoningLevelMapSchema,
    defaultReasoningLevel: UiReasoningLevelSchema.nullable(),
  })
  .strict();
const UiProviderSchema = z
  .object({
    providerId: ConfigProviderIdSchema,
    name: z.string().min(1).max(MAX_STRING),
    api: z.enum(["anthropic_messages", "openai_responses", "openai_chat_completions"]),
    baseUrl: z.string().min(1).max(2_048),
    credentialId: CredentialRefSchema,
    credentialConfigured: z.boolean(),
    networkTimeouts: z
      .object({ connectTimeoutMs: z.number().int(), requestTimeoutMs: z.number().int() })
      .strict(),
    agentDefaults: z
      .object({
        context: z.object({ autoCompact: z.boolean() }).strict(),
        turnLimits: z
          .object({
            maxModelRounds: z.number().int(),
            maxToolCalls: z.number().int(),
            wallTimeoutMs: z.number().int(),
          })
          .strict(),
      })
      .strict(),
    models: z.array(UiProviderModelSchema).min(1).max(MAX_ENTRIES),
  })
  .strict();
const UiMcpSchema = z
  .object({
    mcpRevision: z.string().regex(/^mcp_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/),
    name: z.string().min(1).max(MAX_STRING),
    transport: z.enum(["stdio", "streamable_http"]),
    endpoint: z.string().min(1).max(4_096),
    protocolVersion: z.enum(["2024-11-05", "2025-03-26", "2025-06-18"]),
    args: z.array(z.string().max(4_096)),
    env: z.record(z.string(), z.string()),
    headers: z.record(z.string(), z.string()),
    auth: z.discriminatedUnion("kind", [
      z.object({ kind: z.literal("none") }).strict(),
      z.object({ kind: z.literal("bearer"), credentialRef: CredentialRefSchema }).strict(),
      z
        .object({
          kind: z.enum(["header", "env"]),
          name: z.string().min(1).max(128),
          credentialRef: CredentialRefSchema,
        })
        .strict(),
    ]),
    enabled: z.boolean(),
  })
  .strict();
const WindowSettingsSchema = z
  .object({
    width: z.number().int().min(640).max(16_384),
    height: z.number().int().min(480).max(16_384),
    maximized: z.boolean(),
  })
  .strict();

/** Renderer 聚合不含 Secret；credentialConfigured 只是 native read 的脱敏状态。 */
const SettingsDocumentSchema = z
  .object({
    schemaVersion: z.literal(2),
    revision: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
    theme: z.enum(["system", "light", "dark"]),
    defaultAccessMode: z.enum(["approval_required", "full_access"]),
    clarificationEnabled: z.boolean().default(true),
    defaultSelection: z
      .object({
        providerId: ConfigProviderIdSchema,
        modelId: ConfigModelIdSchema,
        reasoningLevel: UiReasoningLevelSchema.nullable(),
      })
      .strict()
      .nullable(),
    subagents: z
      .object({
        enabled: z.boolean(),
        providerId: ConfigProviderIdSchema.nullable(),
        modelId: ConfigModelIdSchema.nullable(),
        reasoningLevel: UiReasoningLevelSchema.nullable(),
      })
      .strict(),
    providers: z.array(UiProviderSchema).max(MAX_ENTRIES),
    mcpServers: z.array(UiMcpSchema).max(MAX_ENTRIES),
    skills: z
      .array(ConfigSkillReferenceSchema)
      .refine((skills) =>
        skills.every((skill) => skill.startsWith("user:") || skill.startsWith("ja:")),
      ),
    window: WindowSettingsSchema,
  })
  .strict();

const CredentialSetInputSchema = z
  .object({
    credentialId: CredentialRefSchema,
    secret: z.string().min(1).refine(secretFitsWireLimit),
    expectedVersion: ConfigVersionSchema,
  })
  .strict();
const CredentialDeleteInputSchema = z
  .object({ credentialId: CredentialRefSchema, expectedVersion: ConfigVersionSchema })
  .strict();
const CredentialRevealProviderInputSchema = z
  .object({ providerId: ConfigProviderIdSchema })
  .strict();
const CredentialRevealProviderResultSchema = z
  .object({ secret: z.string().min(1).refine(secretFitsWireLimit).nullable() })
  .strict();

export type SettingsDocument = z.infer<typeof SettingsDocumentSchema>;
type SettingsProvider = z.infer<typeof UiProviderSchema>;
type SettingsMcpServer = z.infer<typeof UiMcpSchema>;
export interface ProjectSkillSettingsDocument {
  schemaVersion: 2;
  revision: number;
  skills: string[];
  disabledSkills: string[];
}
export type ConfigReadInput = z.infer<typeof ConfigReadInputSchema>;
export type ConfigReadResult = z.infer<typeof ConfigReadResultSchema>;
/**
 * 仅表示用户配置可读取但不满足当前严格语义；它允许设置页提供恢复入口，不能用于放宽
 * 原生执行时的 Provider、凭据或文件安全校验。
 */
export interface LoadedSettings {
  document: SettingsDocument;
  userDocument: SettingsDocument;
  projectSkillDocument?: ProjectSkillSettingsDocument;
  projectMcpServers?: SettingsMcpServer[];
  projectOverrides: {
    defaultSelection: boolean;
    accessMode: boolean;
    disabledSkillReferences: string[];
  };
  cas: ConfigReadResult["cas"];
  /** App Server 返回的脱敏问题只用于就地引导，不能作为任何写入配置的基线。 */
  issues: ConfigReadResult["issues"];
}

/**
 * 从已脱敏的项目稀疏文档提取覆盖身份；失配形状不会被当成可写基线。
 */
function projectOverrides(layer: ConfigReadResult["project"]): LoadedSettings["projectOverrides"] {
  const document =
    layer.present && layer.trusted && layer.status === "valid" && layer.document !== null
      ? layer.document
      : {};
  const disabledSkills = Array.isArray(document["disabled_skills"])
    ? document["disabled_skills"].filter((value): value is string => typeof value === "string")
    : [];
  return {
    defaultSelection:
      Object.hasOwn(document, "default_provider_id") ||
      Object.hasOwn(document, "default_model_id") ||
      Object.hasOwn(document, "default_reasoning_level"),
    accessMode: Object.hasOwn(document, "default_access_mode"),
    disabledSkillReferences: disabledSkills,
  };
}

/**
 * 只有可信且完整回读的项目层才能成为保存基线；缺失层则延迟到首次项目操作由空文档创建。
 */
function projectSkillDocumentFrom(
  layer: ConfigReadResult["project"],
): ProjectSkillSettingsDocument | undefined {
  if (!layer.present && layer.trusted) {
    return { schemaVersion: 2, revision: 0, skills: [], disabledSkills: [] };
  }
  if (!layer.trusted || layer.status !== "valid" || layer.document === null) return undefined;
  const parsed = ConfigProjectDocumentSchema.safeParse(layer.document);
  if (!parsed.success) return undefined;
  return {
    schemaVersion: 2,
    revision: parsed.data.config_revision,
    skills: [...parsed.data.skills],
    disabledSkills: [...parsed.data.disabled_skills],
  };
}

/** 仅可信项目的完整 MCP 条目可进入编辑基线；坏文档不借默认值被写回。 */
function projectMcpServersFrom(
  layer: ConfigReadResult["project"],
): SettingsMcpServer[] | undefined {
  if (!layer.present && layer.trusted) return [];
  if (!layer.trusted || layer.status !== "valid" || layer.document === null) return undefined;
  const parsed = ConfigProjectDocumentSchema.safeParse(layer.document);
  return parsed.success ? parsed.data.mcp_servers.map(toUiMcp) : undefined;
}
export type ConfigPatchInput = z.infer<typeof ConfigPatchInputSchema>;
export type ConfigReplaceInput = z.infer<typeof ConfigReplaceInputSchema>;
export type ConfigResetInput = z.infer<typeof ConfigResetInputSchema>;
export type ConfigRestoreInput = z.infer<typeof ConfigRestoreInputSchema>;
export type ConfigWriteResult = z.infer<typeof ConfigWriteResultSchema>;

export interface SettingsConfigurationChangeProjection {
  serverInstanceId: string;
  generation: number;
  version: string;
  scope: "user" | "project";
  workspaceId?: string;
}

/** Runtime timeline 只产生 Settings 失效提示，不采纳事件携带的任意配置正文。 */
export function parseSettingsConfigurationChange(
  event: unknown,
): SettingsConfigurationChangeProjection | undefined {
  if (typeof event !== "object" || event === null) return undefined;
  const envelope = event as Record<string, unknown>;
  if (
    envelope["kind"] !== "timeline" ||
    typeof envelope["event"] !== "object" ||
    envelope["event"] === null
  )
    return undefined;
  const runtimeEvent = envelope["event"] as Record<string, unknown>;
  if (runtimeEvent["method"] !== "configuration/changed") return undefined;
  const params = runtimeEvent["params"];
  if (typeof params !== "object" || params === null) return undefined;
  const candidate = params as Record<string, unknown>;
  if (candidate["scope"] !== "user" && candidate["scope"] !== "project") return undefined;
  if (
    typeof candidate["serverInstanceId"] !== "string" ||
    typeof candidate["generation"] !== "number" ||
    !Number.isSafeInteger(candidate["generation"]) ||
    candidate["generation"] < 1 ||
    typeof candidate["version"] !== "string"
  )
    return undefined;
  if (candidate["workspaceId"] !== undefined && typeof candidate["workspaceId"] !== "string")
    return undefined;
  return {
    serverInstanceId: candidate["serverInstanceId"],
    generation: candidate["generation"],
    version: candidate["version"],
    scope: candidate["scope"],
    ...(candidate["workspaceId"] === undefined ? {} : { workspaceId: candidate["workspaceId"] }),
  };
}

export interface SettingsNativeBridge {
  invoke(command: SettingsCommand, args?: Record<string, unknown>): Promise<unknown>;
}
const defaultNativeBridge: SettingsNativeBridge = {
  invoke: (command, args) =>
    invokeNativeCommand(command, args, () => tauriInvoke<unknown>(command, args)),
};
export type SettingsAdapterErrorCode =
  | "invalid_input"
  | "invalid_response"
  | "command_failed"
  | "revision_conflict"
  | "storage_unavailable";
export class SettingsAdapterError extends Error {
  /** 错误只携带稳定 code 与脱敏文案，禁止原生路径、Secret 或任意 cause 穿过 renderer 边界。 */
  constructor(readonly code: SettingsAdapterErrorCode) {
    super(
      code === "invalid_input"
        ? "设置请求参数无效"
        : code === "invalid_response"
          ? "设置配置无效，需要先恢复；当前已禁止保存覆盖"
          : code === "revision_conflict"
            ? "设置已被其他窗口修改，请重新加载后再保存"
            : code === "storage_unavailable"
              ? "设置存储暂时不可用，请稍后重试"
              : "设置操作失败",
    );
    this.name = "SettingsAdapterError";
  }
}

/** Secret 使用 UTF-8 字节上限，防止多字节输入绕过原生边界。 */
function secretFitsWireLimit(secret: string): boolean {
  return new TextEncoder().encode(secret).byteLength <= MAX_SECRET_BYTES;
}
/** renderer/native 边界把 Zod 细节收敛为稳定错误。 */
function parseInput<T>(schema: z.ZodType<T>, value: unknown): T {
  try {
    return schema.parse(value);
  } catch {
    throw new SettingsAdapterError("invalid_input");
  }
}
/** 非法 native 响应必须脱敏，不能把路径或配置正文带进 UI。 */
function parseResult<T>(schema: z.ZodType<T>, value: unknown): T {
  try {
    return schema.parse(value);
  } catch {
    throw new SettingsAdapterError("invalid_response");
  }
}
/** 只按当前 JA-RPC 稳定错误码分类，原生 message、路径和存储诊断不得进入 Renderer。 */
function commandFailed(error: unknown): SettingsAdapterError {
  if (error instanceof SettingsAdapterError) return error;
  const value =
    error !== null && typeof error === "object" ? (error as Record<string, unknown>) : undefined;
  const code = value?.["code"] ?? value?.["error"] ?? error;
  if (code === "CONFIG_CONFLICT") return new SettingsAdapterError("revision_conflict");
  if (code === "STORAGE_UNAVAILABLE") return new SettingsAdapterError("storage_unavailable");
  if (code === "CONFIG_INVALID") return new SettingsAdapterError("invalid_input");
  if (code === "CONFIG_CORRUPTED") return new SettingsAdapterError("invalid_response");
  return new SettingsAdapterError("command_failed");
}
/** 固定白名单调用将原生异常收敛为稳定 Settings 错误。 */
async function invokeSettings(
  bridge: SettingsNativeBridge,
  command: SettingsCommand,
  args: Record<string, unknown>,
): Promise<unknown> {
  try {
    return await bridge.invoke(command, args);
  } catch (error) {
    throw commandFailed(error);
  }
}
/** MCP auth 只投影判别类型和不透明凭据引用，Secret 永不进入 Renderer。 */
function authFromNative(server: z.infer<typeof ConfigMcpServerSchema>): SettingsMcpServer["auth"] {
  if (server.auth.kind === "none") return { kind: "none" };
  if (server.auth.kind === "bearer")
    return { kind: "bearer", credentialRef: server.auth.credential_id };
  return {
    kind: server.auth.kind,
    name: server.auth.name,
    credentialRef: server.auth.credential_id,
  };
}

/** 项目与用户层使用同一完整 MCP 形状，避免项目表单产生另一套启动默认值。 */
function toUiMcp(server: z.infer<typeof ConfigMcpServerSchema>): SettingsMcpServer {
  return {
    mcpRevision: server.mcp_id,
    name: server.name,
    transport: server.transport,
    endpoint: server.endpoint,
    protocolVersion: "2025-06-18",
    args: [...server.args],
    env: { ...server.env },
    headers: { ...server.headers },
    auth: authFromNative(server),
    enabled: server.enabled,
  };
}

/** 根默认档位未指定时沿用默认模型的 medium，避免新 Thread 需要重复手动选择。 */
function defaultSelectionReasoning(
  config: z.infer<typeof ConfigDocumentSchema>,
): z.infer<typeof UiReasoningLevelSchema> | null {
  if (config.default_reasoning_level !== null) return config.default_reasoning_level;
  if (config.default_provider_id === null || config.default_model_id === null) return null;
  const model = config.providers
    .find((provider) => provider.provider_id === config.default_provider_id)
    ?.models.find((candidate) => candidate.model_id === config.default_model_id);
  return model?.reasoning_level_map.medium === undefined ? null : "medium";
}

/** 将严格 snake_case Provider 映射为 Settings 使用的 camelCase 脱敏投影。 */
function toUiProvider(
  provider: z.infer<typeof ConfigProviderSchema>,
  credentials: Record<string, { configured: boolean }>,
): SettingsProvider {
  return {
    providerId: provider.provider_id,
    name: provider.name,
    api: provider.api,
    baseUrl: provider.base_url,
    credentialId: provider.credential_id,
    credentialConfigured: credentials[provider.credential_id]?.configured === true,
    networkTimeouts: {
      connectTimeoutMs: provider.network_timeouts.connect_timeout_ms,
      requestTimeoutMs: provider.network_timeouts.request_timeout_ms,
    },
    agentDefaults: {
      context: { autoCompact: provider.agent_defaults.context.auto_compact },
      turnLimits: {
        maxModelRounds: provider.agent_defaults.turn_limits.max_model_rounds,
        maxToolCalls: provider.agent_defaults.turn_limits.max_tool_calls,
        wallTimeoutMs: provider.agent_defaults.turn_limits.wall_timeout_ms,
      },
    },
    models: provider.models.map((model) => ({
      modelId: model.model_id,
      name: model.name,
      model: model.model,
      capabilities: {
        contextWindowTokens: model.capabilities.context_window_tokens,
        maxOutputTokens: model.capabilities.max_output_tokens,
      },
      reasoningLevelMap: { ...model.reasoning_level_map },
      defaultReasoningLevel:
        model.default_reasoning_level ??
        (model.reasoning_level_map.medium === undefined ? null : "medium"),
    })),
  };
}
/** 完整 v2 native 文档只映射受信任字段，theme/window 留在 UI preference owner。 */
function toUiDocument(
  config: z.infer<typeof ConfigDocumentSchema>,
  credentials: Record<string, { configured: boolean }>,
): SettingsDocument {
  const hasDefault = config.default_provider_id !== null && config.default_model_id !== null;
  return {
    schemaVersion: 2,
    revision: config.config_revision,
    theme: "system",
    defaultAccessMode: config.default_access_mode,
    clarificationEnabled: config.interaction?.clarification_enabled ?? true,
    defaultSelection: hasDefault
      ? {
          providerId: config.default_provider_id!,
          modelId: config.default_model_id!,
          reasoningLevel: defaultSelectionReasoning(config),
        }
      : null,
    subagents: {
      enabled: config.subagents.enabled,
      providerId: config.subagents.provider_id,
      modelId: config.subagents.model_id,
      reasoningLevel: config.subagents.reasoning_level,
    },
    providers: config.providers.map((provider) => toUiProvider(provider, credentials)),
    mcpServers: config.mcp_servers.map(toUiMcp),
    skills: [...config.skills],
    window: { width: 1280, height: 800, maximized: false },
  };
}
/** 用户层确实缺失时建立合法空基线；该路径不用于损坏或无法解析的配置恢复。 */
function emptySettingsDocument(): SettingsDocument {
  return {
    schemaVersion: 2,
    revision: 0,
    theme: "system",
    defaultAccessMode: "full_access",
    clarificationEnabled: true,
    defaultSelection: null,
    subagents: { enabled: true, providerId: null, modelId: null, reasoningLevel: null },
    providers: [],
    mcpServers: [],
    skills: [],
    window: { width: 1280, height: 800, maximized: false },
  };
}

/** 配置读取必须完整命中严格 v2；非法 effective 文档仍然失败关闭。 */
function parseConfigResponse(value: unknown): z.infer<typeof ConfigDocumentSchema> {
  const parsed = ConfigDocumentSchema.safeParse(value);
  if (!parsed.success) throw new SettingsAdapterError("invalid_response");
  return parsed.data;
}
/** Provider 展示状态被明确丢弃，只生成 app-server 接受的严格配置。 */
function toNativeProvider(provider: SettingsProvider): z.infer<typeof ConfigProviderSchema> {
  return ConfigProviderSchema.parse({
    provider_id: provider.providerId,
    name: provider.name,
    api: provider.api,
    base_url: provider.baseUrl,
    credential_id: provider.credentialId,
    network_timeouts: {
      connect_timeout_ms: provider.networkTimeouts.connectTimeoutMs,
      request_timeout_ms: provider.networkTimeouts.requestTimeoutMs,
    },
    agent_defaults: {
      context: { auto_compact: provider.agentDefaults.context.autoCompact },
      turn_limits: {
        max_model_rounds: provider.agentDefaults.turnLimits.maxModelRounds,
        max_tool_calls: provider.agentDefaults.turnLimits.maxToolCalls,
        wall_timeout_ms: provider.agentDefaults.turnLimits.wallTimeoutMs,
      },
    },
    models: provider.models.map((model) =>
      ConfigModelSchema.parse({
        model_id: model.modelId,
        name: model.name,
        model: model.model,
        capabilities: {
          context_window_tokens: model.capabilities.contextWindowTokens,
          max_output_tokens: model.capabilities.maxOutputTokens,
        },
        reasoning_level_map: { ...model.reasoningLevelMap },
        default_reasoning_level: model.defaultReasoningLevel,
      }),
    ),
  });
}
/** UI MCP 投影按真实 v2 auth 判别联合写回，避免把认证类型降级成 transport 猜测。 */
function toNativeMcp(server: SettingsMcpServer): z.infer<typeof ConfigMcpServerSchema> {
  return {
    mcp_id: server.mcpRevision,
    name: server.name,
    transport: server.transport,
    endpoint: server.endpoint,
    args: [...server.args],
    env: { ...server.env },
    headers: { ...server.headers },
    auth:
      server.auth.kind === "none"
        ? { kind: "none" }
        : server.auth.kind === "bearer"
          ? { kind: "bearer", credential_id: server.auth.credentialRef }
          : {
              kind: server.auth.kind,
              name: server.auth.name,
              credential_id: server.auth.credentialRef,
            },
    enabled: server.enabled,
  };
}
/** UI 聚合生成完整 v2 用户文档，默认选择不变量由 ConfigDocumentSchema 再次验证。 */
function settingsDocumentValue(document: SettingsDocument): z.infer<typeof ConfigDocumentSchema> {
  return ConfigDocumentSchema.parse({
    schema_version: 2,
    config_revision: document.revision,
    default_access_mode: document.defaultAccessMode,
    interaction: { clarification_enabled: document.clarificationEnabled ?? true },
    default_provider_id: document.defaultSelection?.providerId ?? null,
    default_model_id: document.defaultSelection?.modelId ?? null,
    default_reasoning_level: document.defaultSelection?.reasoningLevel ?? null,
    subagents: {
      enabled: document.subagents.enabled,
      provider_id: document.subagents.providerId,
      model_id: document.subagents.modelId,
      reasoning_level: document.subagents.reasoningLevel,
    },
    providers: document.providers.map(toNativeProvider),
    mcp_servers: document.mcpServers.map(toNativeMcp),
    skills: [...document.skills],
  });
}

const USER_PATCH_FIELDS = [
  "default_access_mode",
  "interaction",
  "default_provider_id",
  "default_model_id",
  "default_reasoning_level",
  "subagents",
  "providers",
  "mcp_servers",
  "skills",
] as const;

/**
 * 由两份严格 UI 文档生成最小用户层 patch。`config_revision` 是 Java 侧发布序号，不能被 renderer
 * 当作写入意图；只发送真正改变的配置根字段，才能让后端保留原始 TOML 中未知或暂不可用的条目。
 */
export function settingsDocumentPatch(
  baseline: SettingsDocument,
  intended: SettingsDocument,
): Record<string, unknown> {
  const before = settingsDocumentValue(parseInput(SettingsDocumentSchema, baseline));
  const after = settingsDocumentValue(parseInput(SettingsDocumentSchema, intended));
  const patch: Record<string, unknown> = {};
  for (const field of USER_PATCH_FIELDS) {
    if (JSON.stringify(before[field]) !== JSON.stringify(after[field])) {
      patch[field] = after[field];
    }
  }
  return patch;
}

interface SettingsWireAdapter {
  read(input?: ConfigReadInput): Promise<ConfigReadResult>;
  patch(input: ConfigPatchInput): Promise<ConfigWriteResult>;
  replace(input: ConfigReplaceInput): Promise<ConfigWriteResult>;
  reset(input: ConfigResetInput): Promise<ConfigWriteResult>;
  restore(input: ConfigRestoreInput): Promise<ConfigWriteResult>;
  setCredential(credentialId: string, secret: string, expectedVersion: string): Promise<string>;
  deleteCredential(credentialId: string, expectedVersion: string): Promise<string>;
  revealProviderCredential(providerId: string): Promise<string | null>;
  snapshot(input?: ConfigReadInput): Promise<LoadedSettings>;
  saveProjectSkills(
    document: ProjectSkillSettingsDocument,
    workspaceId: string,
    expectedVersion: string,
  ): Promise<string>;
  saveProjectMcpServers(
    servers: SettingsMcpServer[],
    workspaceId: string,
    expectedVersion: string,
  ): Promise<string>;
}

export class TauriSettingsAdapter implements SettingsWireAdapter {
  /** 注入 bridge 仅用于合同测试；生产实例固定走 invokeNativeCommand 白名单。 */
  constructor(private readonly bridge: SettingsNativeBridge = defaultNativeBridge) {}
  /** 读取服务端脱敏投影，响应 Schema 不允许 Secret 字段。 */
  async read(input: ConfigReadInput = {}): Promise<ConfigReadResult> {
    const parsed = parseInput(ConfigReadInputSchema, input);
    const raw = await invokeSettings(this.bridge, JA_SETTINGS_COMMANDS.read, { input: parsed });
    try {
      assertConfigValuesSafe(raw);
    } catch {
      throw new SettingsAdapterError("invalid_response");
    }
    return parseResult(ConfigReadResultSchema, raw);
  }
  /** CAS patch 不保存 adapter 内部版本，避免跨 workspace 误覆盖。 */
  async patch(input: ConfigPatchInput): Promise<ConfigWriteResult> {
    const parsed = parseInput(ConfigPatchInputSchema, input);
    assertConfigValuesSafe(parsed.patch);
    return parseResult(
      ConfigWriteResultSchema,
      await invokeSettings(this.bridge, JA_SETTINGS_COMMANDS.patch, { input: parsed }),
    );
  }
  /** 原子替换只接受严格 v1 文档与显式 CAS，renderer 不提供版本转换。 */
  async replace(input: ConfigReplaceInput): Promise<ConfigWriteResult> {
    const parsed = parseInput(ConfigReplaceInputSchema, input);
    assertConfigValuesSafe(parsed.document);
    return parseResult(
      ConfigWriteResultSchema,
      await invokeSettings(this.bridge, JA_SETTINGS_COMMANDS.replace, { input: parsed }),
    );
  }
  /** reset 要求显式 CAS，不能使用 renderer 的 revision 猜测版本。 */
  async reset(input: ConfigResetInput): Promise<ConfigWriteResult> {
    const parsed = parseInput(ConfigResetInputSchema, input);
    return parseResult(
      ConfigWriteResultSchema,
      await invokeSettings(this.bridge, JA_SETTINGS_COMMANDS.reset, { input: parsed }),
    );
  }
  /** 用户层恢复只发送当前 CAS；快照、备份和原子写入都由 Java 配置 owner 完成。 */
  async restore(input: ConfigRestoreInput): Promise<ConfigWriteResult> {
    const parsed = parseInput(ConfigRestoreInputSchema, input);
    return parseResult(
      ConfigWriteResultSchema,
      await invokeSettings(this.bridge, JA_SETTINGS_COMMANDS.restore, { input: parsed }),
    );
  }
  /**
   * effective 与 user layer 都是 App Server 的可用投影。读取绝不写回原文件；语义问题在
   * `issues` 中就地解释，文件级故障则由后端选择最近有效快照或安全默认值。
   */
  async snapshot(input?: ConfigReadInput): Promise<LoadedSettings> {
    const read = await this.read(input);
    const effective = parseConfigResponse(read.effective);
    const userMissing =
      !read.user.present && read.user.status === "missing" && read.user.document === null;
    if (!userMissing && (!read.user.present || !read.user.trusted)) {
      throw new SettingsAdapterError("invalid_response");
    }
    const userDocument = userMissing
      ? emptySettingsDocument()
      : read.user.document === null
        ? toUiDocument(effective, read.credentials)
        : toUiDocument(parseConfigResponse(read.user.document), read.credentials);
    const projectSkillDocument = projectSkillDocumentFrom(read.project);
    const projectMcpServers = projectMcpServersFrom(read.project);
    return {
      document: toUiDocument(effective, read.credentials),
      userDocument,
      ...(projectSkillDocument === undefined ? {} : { projectSkillDocument }),
      ...(projectMcpServers === undefined ? {} : { projectMcpServers }),
      projectOverrides: projectOverrides(read.project),
      cas: read.cas,
      issues: read.issues,
    };
  }
  /**
   * application 提交已验证的用户编辑结果，但写入改为 Java 侧以原始文档为底的 patch，避免
   * renderer 的有效投影覆盖未知字段或尚未修复的局部条目。
   */
  async save(
    document: SettingsDocument,
    expectedVersion: string,
    baseline?: SettingsDocument,
  ): Promise<string> {
    let patch: Record<string, unknown>;
    try {
      const intended = parseInput(SettingsDocumentSchema, document);
      patch =
        baseline === undefined
          ? settingsDocumentValue(intended)
          : settingsDocumentPatch(parseInput(SettingsDocumentSchema, baseline), intended);
    } catch {
      throw new SettingsAdapterError("invalid_input");
    }
    return (await this.patch({ scope: "user", expectedVersion, patch })).version;
  }

  /** 只用当前用户层 CAS 触发恢复，成功后由调用方权威重读而不是复用旧页面快照。 */
  async restoreLastKnownGood(expectedVersion: string): Promise<string> {
    return (await this.restore({ expectedVersion })).version;
  }
  /**
   * 项目 Skill 仅 patch 自身字段；并发 MCP 编辑由相同 CAS 保护而不会被整文替换清除。
   */
  async saveProjectSkills(
    document: ProjectSkillSettingsDocument,
    workspaceId: string,
    expectedVersion: string,
  ): Promise<string> {
    return (
      await this.patch({
        scope: "project",
        workspaceId,
        expectedVersion,
        patch: {
          skills: [...document.skills],
          disabled_skills:
            document.disabledSkills.length === 0 ? null : [...document.disabledSkills],
        },
      })
    ).version;
  }

  /** 项目 MCP 只 patch 其目录字段，服务端仍以 CAS、信任和严格策略决定是否接受。 */
  async saveProjectMcpServers(
    servers: SettingsMcpServer[],
    workspaceId: string,
    expectedVersion: string,
  ): Promise<string> {
    const values = servers.map(toNativeMcp);
    const validated = ConfigProjectDocumentSchema.parse({
      schema_version: 2,
      config_revision: 0,
      skills: [],
      mcp_servers: values,
    }).mcp_servers;
    return (
      await this.patch({
        scope: "project",
        workspaceId,
        expectedVersion,
        patch: { mcp_servers: validated },
      })
    ).version;
  }
  /** Secret 仅通过专用 command 一次性发送，不进入 adapter 状态。 */
  async setCredential(
    credentialId: string,
    secret: string,
    expectedVersion: string,
  ): Promise<string> {
    const input = parseInput(CredentialSetInputSchema, { credentialId, secret, expectedVersion });
    const result = parseResult(
      z
        .object({
          accepted: z.literal(true),
          credentialId: CredentialRefSchema,
          configured: z.literal(true),
          version: ConfigVersionSchema,
        })
        .strict(),
      await invokeSettings(this.bridge, JA_SETTINGS_COMMANDS.setCredential, { input }),
    );
    return result.version;
  }
  /** 删除 Secret 但保留 Provider/MCP 的不透明引用。 */
  async deleteCredential(credentialId: string, expectedVersion: string): Promise<string> {
    const input = parseInput(CredentialDeleteInputSchema, { credentialId, expectedVersion });
    const result = parseResult(
      z
        .object({
          accepted: z.literal(true),
          credentialId: CredentialRefSchema,
          configured: z.literal(false),
          version: ConfigVersionSchema,
        })
        .strict(),
      await invokeSettings(this.bridge, JA_SETTINGS_COMMANDS.deleteCredential, { input }),
    );
    return result.version;
  }
  /** 仅在编辑一个 Provider 时读取其绑定 API Key，绝不写入快照或 adapter 字段。 */
  async revealProviderCredential(providerId: string): Promise<string | null> {
    const input = parseInput(CredentialRevealProviderInputSchema, { providerId });
    const result = parseResult(
      CredentialRevealProviderResultSchema,
      await invokeSettings(this.bridge, JA_SETTINGS_COMMANDS.revealProviderCredential, { input }),
    );
    return result.secret;
  }
}

/** 配置值进入 IPC 前递归拒绝 Secret 形状键，同时允许不透明 credential reference。 */
function assertConfigValuesSafe(value: unknown, seen = new WeakSet<object>()): void {
  if (value === null || typeof value !== "object") return;
  if (seen.has(value)) throw new SettingsAdapterError("invalid_input");
  seen.add(value);
  for (const [key, child] of Object.entries(value)) {
    const opaqueReference =
      key === "credentialId" || key === "credentialRef" || key === "credential_id";
    const credentialVersion = key === "credentialVersion";
    const credentialProjection = key === "credentials";
    if (opaqueReference) {
      if (typeof child !== "string" || !CredentialRefSchema.safeParse(child).success)
        throw new SettingsAdapterError("invalid_input");
    } else if (credentialVersion) {
      if (
        child !== null &&
        (typeof child !== "string" || !ConfigVersionSchema.safeParse(child).success)
      )
        throw new SettingsAdapterError("invalid_input");
    } else if (!credentialProjection && isSensitiveConfigKey(key)) {
      throw new SettingsAdapterError("invalid_input");
    }
    assertConfigValuesSafe(child, seen);
  }
  seen.delete(value);
}
/** Secret key 检查避开 token budget 等正常能力字段。 */
function isSensitiveConfigKey(key: string): boolean {
  return (
    /(?:api[_-]?key|secret|password|authorization|cookie)/i.test(key) ||
    /^(?:access|auth|refresh)[_-]?token$/i.test(key) ||
    /^token(?:value|secret)?$/i.test(key) ||
    /^credential(?:value|secret)?$/i.test(key)
  );
}
