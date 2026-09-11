// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { z } from "zod";
import { isSafeProviderUrl } from "@/shared/settings/validation";

const MAX_SAFE_INTEGER = Number.MAX_SAFE_INTEGER;
const MAX_TEXT = 512;
const MAX_CATALOG_ITEMS = 512;
const MAX_CATALOG_REFERENCES = 128;
const MAX_MAP_ENTRIES = 64;
const MAX_MAP_VALUE = 8_192;

/** 配置身份保持不透明，并只接受 schema v1 的稳定命名空间。 */
export const ConfigCredentialRefSchema = z
  .string()
  .regex(/^cred_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(100);
export const ConfigProviderIdSchema = z
  .string()
  .regex(/^provider_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(105);
export const ConfigModelIdSchema = z
  .string()
  .regex(/^model_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(102);
const configMcpIdSchema = z
  .string()
  .regex(/^mcp_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(100);
const configSkillIdSchema = z
  .string()
  .regex(/^skill_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(101);

/** 配置离开 renderer 前拒绝 Secret 形状键，避免高级 Map 成为凭据旁路。 */
function safeConfigMap(value: Record<string, string>): boolean {
  return (
    Object.keys(value).length <= MAX_MAP_ENTRIES &&
    Object.entries(value).every(
      ([key, item]) =>
        key.length > 0 &&
        key.length <= 128 &&
        !/(?:token|secret|password|api[_-]?key|authorization|bearer|credential|cookie)/i.test(
          key,
        ) &&
        item.length <= MAX_MAP_VALUE &&
        !/(?:token|secret|password|api[_-]?key|authorization|bearer|credential)\s*[:=]/i.test(item),
    )
  );
}

const configMapSchema = z
  .record(z.string().min(1).max(128), z.string().max(MAX_MAP_VALUE))
  .refine(safeConfigMap);
const configContextSchema = z.object({ auto_compact: z.boolean() }).strict();
const configTurnLimitsSchema = z
  .object({
    max_model_rounds: z.number().int().min(1).max(128),
    max_tool_calls: z.number().int().min(0).max(1_024),
    wall_timeout_ms: z.number().int().min(1_000).max(86_400_000),
  })
  .strict();
const configNetworkTimeoutsSchema = z
  .object({
    connect_timeout_ms: z.number().int().min(100).max(120_000),
    request_timeout_ms: z.number().int().min(1_000).max(3_600_000),
  })
  .strict();
const ConfigReasoningLevelSchema = z.enum([
  "off",
  "minimal",
  "low",
  "medium",
  "high",
  "xhigh",
  "max",
]);
const reasoningMapValueSchema = z.string().trim().min(1).max(128);
const configReasoningLevelMapSchema = z
  .object({
    off: reasoningMapValueSchema.optional(),
    minimal: reasoningMapValueSchema.optional(),
    low: reasoningMapValueSchema.optional(),
    medium: reasoningMapValueSchema.optional(),
    high: reasoningMapValueSchema.optional(),
    xhigh: reasoningMapValueSchema.optional(),
    max: reasoningMapValueSchema.optional(),
  })
  .strict();
const configModelCapabilitiesSchema = z
  .object({
    context_window_tokens: z.number().int().min(4_096).max(4_000_000),
    max_output_tokens: z.number().int().min(1).max(1_000_000),
  })
  .strict()
  .refine((value) => value.max_output_tokens < value.context_window_tokens);
const configAgentDefaultsSchema = z
  .object({
    context: configContextSchema,
    turn_limits: configTurnLimitsSchema,
  })
  .strict();

/** 模型能力与思考档位属于模型本身；Provider 不得为不支持的模型伪造默认档位。 */
export const ConfigModelSchema = z
  .object({
    model_id: ConfigModelIdSchema,
    name: z.string().trim().min(1).max(MAX_TEXT),
    model: z.string().trim().min(1).max(MAX_TEXT),
    capabilities: configModelCapabilitiesSchema,
    reasoning_level_map: configReasoningLevelMapSchema,
    default_reasoning_level: ConfigReasoningLevelSchema.nullable(),
  })
  .strict()
  .superRefine((model, context) => {
    if (
      model.default_reasoning_level !== null &&
      model.reasoning_level_map[model.default_reasoning_level] === undefined
    ) {
      context.addIssue({
        code: "custom",
        path: ["default_reasoning_level"],
        message: "unsupported",
      });
    }
  });

/** Provider 是稳定路由与凭据边界，一个 Provider 可以管理多个独立模型能力。 */
export const ConfigProviderSchema = z
  .object({
    provider_id: ConfigProviderIdSchema,
    name: z.string().trim().min(1).max(MAX_TEXT),
    api: z.enum(["openai_responses", "anthropic_messages", "openai_chat_completions"]),
    base_url: z.string().min(1).max(2_048).refine(isSafeProviderUrl),
    credential_id: ConfigCredentialRefSchema,
    network_timeouts: configNetworkTimeoutsSchema,
    agent_defaults: configAgentDefaultsSchema,
    models: z.array(ConfigModelSchema).min(1).max(MAX_CATALOG_ITEMS),
  })
  .strict()
  .superRefine((provider, context) => {
    if (new Set(provider.models.map((model) => model.model_id)).size !== provider.models.length) {
      context.addIssue({ code: "custom", path: ["models"], message: "duplicate model id" });
    }
  });

/** 子智能体策略只允许用户级模型引用；独立 Provider/Model 身份由配置目录持有。 */
export const ConfigSubagentsSchema = z
  .object({
    enabled: z.boolean(),
    provider_id: ConfigProviderIdSchema.nullable(),
    model_id: ConfigModelIdSchema.nullable(),
    reasoning_level: ConfigReasoningLevelSchema.nullable(),
  })
  .strict()
  .superRefine((selection, context) => {
    if ((selection.provider_id === null) !== (selection.model_id === null)) {
      context.addIssue({ code: "custom", path: ["model_id"], message: "incomplete selection" });
    }
    if (selection.provider_id === null && selection.reasoning_level !== null) {
      context.addIssue({
        code: "custom",
        path: ["reasoning_level"],
        message: "follow parent cannot override reasoning level",
      });
    }
  });

const ConfigMcpAuthSchema = z.discriminatedUnion("kind", [
  z.object({ kind: z.literal("none") }).strict(),
  z
    .object({
      kind: z.literal("env"),
      name: z.string().regex(/^[A-Za-z_][A-Za-z0-9_]{0,127}$/),
      credential_id: ConfigCredentialRefSchema,
    })
    .strict(),
  z.object({ kind: z.literal("bearer"), credential_id: ConfigCredentialRefSchema }).strict(),
  z
    .object({
      kind: z.literal("header"),
      name: z.string().regex(/^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}$/),
      credential_id: ConfigCredentialRefSchema,
    })
    .strict(),
]);

export const ConfigMcpServerSchema = z
  .object({
    mcp_id: configMcpIdSchema,
    name: z.string().min(1).max(MAX_TEXT),
    transport: z.enum(["stdio", "streamable_http"]),
    endpoint: z.string().min(1).max(4_096),
    args: z.array(z.string().max(4_096)).max(MAX_CATALOG_REFERENCES),
    env: configMapSchema,
    headers: configMapSchema,
    auth: ConfigMcpAuthSchema,
    enabled: z.boolean(),
  })
  .strict();

const ConfigSkillSchema = z
  .object({
    skill_id: configSkillIdSchema,
    name: z.string().min(1).max(MAX_TEXT),
    scope: z.enum(["builtin", "user", "ja", "project"]),
    enabled: z.boolean(),
    description: z.string().max(8_192),
  })
  .strict();

/** 只解析 app-server 返回的当前 v1 配置，并在 renderer 边界校验默认选择的真实引用。 */
export const ConfigDocumentSchema = z
  .object({
    schema_version: z.literal(1),
    config_revision: z.number().int().min(0).max(MAX_SAFE_INTEGER),
    default_access_mode: z.enum(["approval_required", "full_access"]),
    interaction: z.object({ clarification_enabled: z.boolean().optional() }).strict().optional(),
    default_provider_id: ConfigProviderIdSchema.nullable(),
    default_model_id: ConfigModelIdSchema.nullable(),
    default_reasoning_level: ConfigReasoningLevelSchema.nullable(),
    subagents: ConfigSubagentsSchema,
    providers: z.array(ConfigProviderSchema).max(MAX_CATALOG_ITEMS),
    mcp_servers: z.array(ConfigMcpServerSchema).max(MAX_CATALOG_ITEMS),
    skills: z.array(ConfigSkillSchema).max(MAX_CATALOG_ITEMS),
  })
  .strict()
  .superRefine((document, context) => {
    if (
      new Set(document.providers.map((provider) => provider.provider_id)).size !==
      document.providers.length
    ) {
      context.addIssue({ code: "custom", path: ["providers"], message: "duplicate provider id" });
    }
    if (
      new Set(document.providers.map((provider) => provider.credential_id)).size !==
      document.providers.length
    ) {
      context.addIssue({
        code: "custom",
        path: ["providers"],
        message: "duplicate provider credential id",
      });
    }
    if (document.subagents.provider_id !== null && document.subagents.model_id !== null) {
      const provider = document.providers.find(
        (candidate) => candidate.provider_id === document.subagents.provider_id,
      );
      const model = provider?.models.find(
        (candidate) => candidate.model_id === document.subagents.model_id,
      );
      if (model === undefined) {
        context.addIssue({
          path: ["subagents", "model_id"],
          code: "custom",
          message: "unknown selection",
        });
      } else if (
        document.subagents.reasoning_level !== null &&
        model.reasoning_level_map[document.subagents.reasoning_level] === undefined
      ) {
        context.addIssue({
          path: ["subagents", "reasoning_level"],
          code: "custom",
          message: "unsupported reasoning level",
        });
      }
    }
    const hasProvider = document.default_provider_id !== null;
    const hasModel = document.default_model_id !== null;
    if (hasProvider !== hasModel) {
      context.addIssue({
        code: "custom",
        path: ["default_model_id"],
        message: "incomplete selection",
      });
      return;
    }
    if (!hasProvider) {
      if (document.default_reasoning_level !== null) {
        context.addIssue({
          code: "custom",
          path: ["default_reasoning_level"],
          message: "orphan reasoning",
        });
      }
      return;
    }
    const provider = document.providers.find(
      (candidate) => candidate.provider_id === document.default_provider_id,
    );
    const model = provider?.models.find(
      (candidate) => candidate.model_id === document.default_model_id,
    );
    if (model === undefined) {
      context.addIssue({
        code: "custom",
        path: ["default_model_id"],
        message: "unknown selection",
      });
    } else if (
      document.default_reasoning_level !== null &&
      model.reasoning_level_map[document.default_reasoning_level] === undefined
    ) {
      context.addIssue({
        code: "custom",
        path: ["default_reasoning_level"],
        message: "unsupported",
      });
    }
  });
