// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { z } from "zod";
import { isSafeHttpUrl } from "@/shared/settings/validation";

const MAX_SAFE_INTEGER = Number.MAX_SAFE_INTEGER;
const MAX_TEXT = 512;
const MAX_CATALOG_ITEMS = 512;
const MAX_CATALOG_REFERENCES = 128;
const MAX_MAP_ENTRIES = 64;
const MAX_MAP_VALUE = 8_192;

/** 配置引用沿用 wire 的 ASCII kebab 名称，避免已保存身份与运行时目录使用不同语法。 */
const isConfigSkillReferenceName = (value: string): boolean =>
  value.length >= 1 && value.length <= 256 && /^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(value);

/** 配置身份保持不透明，并只接受 schema v2 的稳定命名空间。 */
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
/**
 * Skill 授权以来源与名称共同标识；名称禁止分隔符，使同名项目包不能继承全局许可。
 */
export const ConfigSkillReferenceSchema = z
  .string()
  .regex(/^(?:user|ja|project):/)
  .refine((value) => isConfigSkillReferenceName(value.slice(value.indexOf(":") + 1)))
  .max(264);
const ConfigUserSkillReferenceSchema = ConfigSkillReferenceSchema.refine(
  (value) => value.startsWith("user:") || value.startsWith("ja:"),
);
const ConfigProjectSkillReferenceSchema = ConfigSkillReferenceSchema;

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
    supports_output_schema: z.boolean().optional(),
  })
  .strict()
  .refine((value) => value.max_output_tokens < value.context_window_tokens);
const configAgentDefaultsSchema = z
  .object({
    context: configContextSchema,
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

/** Provider 的共同连接形状；effective 项目覆盖可显式得到空模型目录。 */
const ConfigProviderBaseSchema = z
  .object({
    provider_id: ConfigProviderIdSchema,
    name: z.string().trim().min(1).max(MAX_TEXT),
    api: z.enum(["openai_responses", "anthropic_messages", "openai_chat_completions"]),
    base_url: z.string().min(1).max(2_048).refine(isSafeHttpUrl),
    credential_id: ConfigCredentialRefSchema,
    network_timeouts: configNetworkTimeoutsSchema,
    agent_defaults: configAgentDefaultsSchema,
    models: z.array(ConfigModelSchema).max(MAX_CATALOG_ITEMS),
  })
  .strict();

/** Provider 是稳定路由与凭据边界；用户层可执行连接至少需要一个模型。 */
export const ConfigProviderSchema = ConfigProviderBaseSchema.extend({
  models: z.array(ConfigModelSchema).min(1).max(MAX_CATALOG_ITEMS),
})
  .strict()
  .superRefine(validateUniqueProviderModels);

/** Effective Provider 允许项目显式空目录；模型运行前的有效选择仍在 root 引用处校验。 */
export const ConfigEffectiveProviderSchema = ConfigProviderBaseSchema.superRefine(
  validateUniqueProviderModels,
);

/** Provider 的模型集合始终按稳定 model_id 去重，与数组位置和显示名无关。 */
function validateUniqueProviderModels(
  provider: { models: Array<{ model_id: string }> },
  context: z.RefinementCtx,
): void {
  if (new Set(provider.models.map((model) => model.model_id)).size !== provider.models.length) {
    context.addIssue({ code: "custom", path: ["models"], message: "duplicate model id" });
  }
}

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
    enabled: z.boolean().default(true),
  })
  .strict();

const ConfigDocumentBaseSchema = z
  .object({
    schema_version: z.literal(2),
    config_revision: z.number().int().min(0).max(MAX_SAFE_INTEGER),
    default_access_mode: z.enum(["approval_required", "full_access"]),
    interaction: z.object({ clarification_enabled: z.boolean().optional() }).strict().optional(),
    default_provider_id: ConfigProviderIdSchema.nullable(),
    default_model_id: ConfigModelIdSchema.nullable(),
    default_reasoning_level: ConfigReasoningLevelSchema.nullable(),
    subagents: ConfigSubagentsSchema,
    providers: z.array(ConfigProviderSchema).max(MAX_CATALOG_ITEMS),
    mcp_servers: z.array(ConfigMcpServerSchema).max(MAX_CATALOG_ITEMS),
    disabled_skills: z.array(ConfigUserSkillReferenceSchema).max(MAX_CATALOG_ITEMS),
  })
  .strict();
const ConfigEffectiveDocumentBaseSchema = ConfigDocumentBaseSchema.extend({
  providers: z.array(ConfigEffectiveProviderSchema).max(MAX_CATALOG_ITEMS),
}).strict();

/** 校验真实 ID 引用；仅用户文档禁止重复绑定凭据，effective 允许项目显式重用 vault 引用。 */
function validateConfigDocumentReferences(
  document: z.infer<typeof ConfigDocumentBaseSchema>,
  context: z.RefinementCtx,
  requireUniqueCredentials: boolean,
): void {
  if (new Set(document.disabled_skills).size !== document.disabled_skills.length) {
    context.addIssue({
      code: "custom",
      path: ["disabled_skills"],
      message: "duplicate skill reference",
    });
  }
  if (
    new Set(document.providers.map((provider) => provider.provider_id)).size !==
    document.providers.length
  ) {
    context.addIssue({ code: "custom", path: ["providers"], message: "duplicate provider id" });
  }
  if (
    requireUniqueCredentials &&
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
}

/** 读取和写入用户层时拒绝重复 credential owner，避免凭据身份含糊。 */
export const ConfigDocumentSchema = ConfigDocumentBaseSchema.superRefine((document, context) =>
  validateConfigDocumentReferences(document, context, true),
);

/** Effective 可含项目显式引用的其它全局凭据；普通用户配置仍由 ConfigDocumentSchema 校验。 */
export const ConfigEffectiveDocumentSchema = ConfigEffectiveDocumentBaseSchema.superRefine(
  (document, context) => validateConfigDocumentReferences(document, context, false),
);

/** 项目可切换现有 Provider 的连接目标，但连接目标必须带明示的用户凭据引用。 */
const ConfigProjectProviderSchema = z
  .object({
    provider_id: ConfigProviderIdSchema,
    api: z.enum(["openai_responses", "anthropic_messages", "openai_chat_completions"]).optional(),
    base_url: z.string().min(1).max(2_048).refine(isSafeHttpUrl).optional(),
    credential_id: ConfigCredentialRefSchema.optional(),
    network_timeouts: z
      .object({
        connect_timeout_ms: z.number().int().min(100).max(120_000).optional(),
        request_timeout_ms: z.number().int().min(1_000).max(3_600_000).optional(),
      })
      .strict()
      .optional(),
    agent_defaults: z
      .object({
        context: z.object({ auto_compact: z.boolean().optional() }).strict().optional(),
      })
      .strict()
      .optional(),
    models: z.array(ConfigModelSchema).max(MAX_CATALOG_ITEMS).optional(),
    removed_model_ids: z.array(ConfigModelIdSchema).max(MAX_CATALOG_ITEMS).optional(),
  })
  .strict()
  .superRefine((provider, context) => {
    const routeFields = [provider.api, provider.base_url, provider.credential_id];
    if (
      routeFields.some((value) => value !== undefined) &&
      routeFields.some((value) => value === undefined)
    ) {
      context.addIssue({
        code: "custom",
        path: ["credential_id"],
        message: "project provider route requires an explicit credential reference",
      });
    }
    if (provider.models !== undefined && provider.removed_model_ids !== undefined) {
      const modelIds = new Set(provider.models.map((model) => model.model_id));
      if (provider.removed_model_ids.some((modelId) => modelIds.has(modelId))) {
        context.addIssue({
          code: "custom",
          path: ["removed_model_ids"],
          message: "a model cannot be overridden and removed together",
        });
      }
    }
    if (
      provider.models !== undefined &&
      new Set(provider.models.map((model) => model.model_id)).size !== provider.models.length
    ) {
      context.addIssue({ code: "custom", path: ["models"], message: "duplicate project model id" });
    }
    if (
      provider.removed_model_ids !== undefined &&
      new Set(provider.removed_model_ids).size !== provider.removed_model_ids.length
    ) {
      context.addIssue({
        code: "custom",
        path: ["removed_model_ids"],
        message: "duplicate removed model id",
      });
    }
  });
export const ConfigProjectDocumentSchema = z
  .object({
    schema_version: z.literal(2),
    config_revision: z.number().int().min(0).max(MAX_SAFE_INTEGER),
    default_access_mode: z.enum(["approval_required", "full_access"]).optional(),
    default_provider_id: ConfigProviderIdSchema.nullable().optional(),
    default_model_id: ConfigModelIdSchema.nullable().optional(),
    default_reasoning_level: ConfigReasoningLevelSchema.nullable().optional(),
    interaction: z.object({ clarification_enabled: z.boolean().optional() }).strict().optional(),
    subagents: z
      .object({
        enabled: z.boolean().optional(),
        provider_id: ConfigProviderIdSchema.nullable().optional(),
        model_id: ConfigModelIdSchema.nullable().optional(),
        reasoning_level: ConfigReasoningLevelSchema.nullable().optional(),
      })
      .strict()
      .optional(),
    providers: z.array(ConfigProjectProviderSchema).max(MAX_CATALOG_ITEMS).optional(),
    disabled_skills: z.array(ConfigProjectSkillReferenceSchema).max(MAX_CATALOG_ITEMS).optional(),
    mcp_servers: z.array(ConfigMcpServerSchema).max(MAX_CATALOG_ITEMS).optional(),
  })
  .strict()
  .superRefine((document, context) => {
    if (
      document.disabled_skills !== undefined &&
      new Set(document.disabled_skills).size !== document.disabled_skills.length
    ) {
      context.addIssue({
        code: "custom",
        path: ["disabled_skills"],
        message: "duplicate skill reference",
      });
    }
    if (
      document.mcp_servers !== undefined &&
      new Set(document.mcp_servers.map((server) => server.mcp_id)).size !==
        document.mcp_servers.length
    ) {
      context.addIssue({ code: "custom", path: ["mcp_servers"], message: "duplicate MCP id" });
    }
    if (
      document.providers !== undefined &&
      new Set(document.providers.map((provider) => provider.provider_id)).size !==
        document.providers.length
    ) {
      context.addIssue({ code: "custom", path: ["providers"], message: "duplicate Provider id" });
    }
  });
