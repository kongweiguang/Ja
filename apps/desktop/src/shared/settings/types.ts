// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** Settings v1 的共享值只描述 Provider、Model 与脱敏引用，不承载 runtime 观测状态。 */
export type ProviderApi = "anthropic_messages" | "openai_responses" | "openai_chat_completions";
export type ReasoningLevel = "off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max";
export type McpTransport = "stdio" | "streamable_http";

export type McpAuth =
  | { kind: "none" }
  | { kind: "bearer"; credentialRef: string }
  | { kind: "header" | "env"; name: string; credentialRef: string };

interface ModelCapabilities {
  contextWindowTokens: number;
  maxOutputTokens: number;
}

export interface ProviderModelSave {
  modelId: string;
  name: string;
  model: string;
  capabilities: ModelCapabilities;
  reasoningLevelMap: Partial<Record<ReasoningLevel, string>>;
  defaultReasoningLevel: ReasoningLevel | null;
}

interface AgentDefaults {
  context: { autoCompact: boolean };
  turnLimits: { maxModelRounds: number; maxToolCalls: number; wallTimeoutMs: number };
}

export interface ProviderSave {
  providerId: string;
  name: string;
  api: ProviderApi;
  baseUrl: string;
  credentialId: string;
  networkTimeouts: { connectTimeoutMs: number; requestTimeoutMs: number };
  agentDefaults: AgentDefaults;
  models: ProviderModelSave[];
}

export interface DefaultModelSelection {
  providerId: string;
  modelId: string;
  reasoningLevel: ReasoningLevel | null;
}

/**
 * 把 Provider/Model 稳定身份编码为纯 UI 模型选择 key；长度前缀使任意合法 ID 组合都无歧义，
 * 且不把组合标识误传给拥有 Provider/Model 事实的 App Server。
 */
export function modelSelectionId(providerId: string, modelId: string): string {
  return `selection_${providerId.length}_${providerId}_${modelId}`;
}

export interface McpServerSave {
  mcpRevision: string;
  name: string;
  transport: McpTransport;
  endpoint: string;
  args: string[];
  env: Record<string, string>;
  headers: Record<string, string>;
  auth: McpAuth;
  enabled: boolean;
}
