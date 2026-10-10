// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { z } from "zod";
import {
  ConfigCredentialRefSchema,
  ConfigMcpServerSchema,
  ConfigModelIdSchema,
  ConfigProviderIdSchema,
  ConfigSkillReferenceSchema,
} from "./configDocument";
import { assertNoReadyTokenLeak, ParamsSchemaByMethod, ResultSchemaByMethod } from "./protocol";
import { isSafeHttpUrl } from "@/shared/settings/validation";

const ReasoningLevelSchema = z.enum(["off", "minimal", "low", "medium", "high", "xhigh", "max"]);
const ExplicitNullPatchSchema = z.object({ __ja_null: z.literal(true) }).strict();
const ConfigReasoningPatchSchema = z.union([ReasoningLevelSchema, ExplicitNullPatchSchema]);
const ConfigProviderReferencePatchSchema = z.union([
  ConfigProviderIdSchema,
  ExplicitNullPatchSchema,
]);
const ConfigModelReferencePatchSchema = z.union([ConfigModelIdSchema, ExplicitNullPatchSchema]);
const ConfigCapabilityPatchSchema = z
  .object({
    context_window_tokens: z.number().int().min(4_096).max(4_000_000).optional(),
    max_output_tokens: z.number().int().min(1).max(1_000_000).optional(),
    supports_output_schema: z.boolean().optional(),
  })
  .strict();
const ConfigReasoningMapPatchSchema = z
  .object({
    off: z.string().trim().min(1).max(128).nullable().optional(),
    minimal: z.string().trim().min(1).max(128).nullable().optional(),
    low: z.string().trim().min(1).max(128).nullable().optional(),
    medium: z.string().trim().min(1).max(128).nullable().optional(),
    high: z.string().trim().min(1).max(128).nullable().optional(),
    xhigh: z.string().trim().min(1).max(128).nullable().optional(),
    max: z.string().trim().min(1).max(128).nullable().optional(),
  })
  .strict();
const ConfigModelPatchEntitySchema = z
  .object({
    name: z.string().trim().min(1).max(512).optional(),
    model: z.string().trim().min(1).max(512).optional(),
    capabilities: ConfigCapabilityPatchSchema.optional(),
    reasoning_level_map: ConfigReasoningMapPatchSchema.optional(),
    default_reasoning_level: ConfigReasoningPatchSchema.optional(),
  })
  .strict();
const ConfigNetworkTimeoutPatchSchema = z
  .object({
    connect_timeout_ms: z.number().int().min(100).max(120_000).optional(),
    request_timeout_ms: z.number().int().min(1_000).max(3_600_000).optional(),
  })
  .strict();
const ConfigProviderPatchEntitySchema = z
  .object({
    name: z.string().trim().min(1).max(512).optional(),
    api: z.enum(["openai_responses", "anthropic_messages", "openai_chat_completions"]).optional(),
    base_url: z.string().min(1).max(2_048).refine(isSafeHttpUrl).optional(),
    credential_id: ConfigCredentialRefSchema.optional(),
    network_timeouts: ConfigNetworkTimeoutPatchSchema.optional(),
    agent_defaults: z
      .object({ context: z.object({ auto_compact: z.boolean().optional() }).strict().optional() })
      .strict()
      .optional(),
    models: z
      .record(ConfigModelIdSchema, z.union([ConfigModelPatchEntitySchema, z.null()]))
      .refine((models) => Object.keys(models).length <= 512)
      .optional(),
    model_order: z
      .array(ConfigModelIdSchema)
      .max(512)
      .refine((ids) => new Set(ids).size === ids.length)
      .optional(),
  })
  .strict();
const ConfigUserProvidersPatchSchema = z
  .record(ConfigProviderIdSchema, z.union([ConfigProviderPatchEntitySchema, z.null()]))
  .refine((providers) => Object.keys(providers).length <= 512);
const ConfigUserPatchSchema = z
  .object({
    default_access_mode: z.enum(["approval_required", "full_access"]).optional(),
    default_provider_id: ConfigProviderReferencePatchSchema.optional(),
    default_model_id: ConfigModelReferencePatchSchema.optional(),
    default_reasoning_level: ConfigReasoningPatchSchema.optional(),
    interaction: z.object({ clarification_enabled: z.boolean().optional() }).strict().optional(),
    subagents: z
      .object({
        enabled: z.boolean().optional(),
        provider_id: ConfigProviderReferencePatchSchema.optional(),
        model_id: ConfigModelReferencePatchSchema.optional(),
        reasoning_level: ConfigReasoningPatchSchema.optional(),
      })
      .strict()
      .optional(),
    providers: ConfigUserProvidersPatchSchema.optional(),
    provider_order: z
      .array(ConfigProviderIdSchema)
      .max(512)
      .refine((ids) => new Set(ids).size === ids.length)
      .optional(),
    mcp_servers: z.array(ConfigMcpServerSchema).max(512).optional(),
    disabled_skills: z
      .array(
        ConfigSkillReferenceSchema.refine((id) => id.startsWith("user:") || id.startsWith("ja:")),
      )
      .max(512)
      .optional(),
  })
  .strict();

type GenericConfigurationPatchParams = z.infer<
  (typeof ParamsSchemaByMethod)["configuration/patch"]
>;
type ConfigurationPatchParams =
  | (Omit<Extract<GenericConfigurationPatchParams, { scope: "user" }>, "patch"> & {
      patch: z.infer<typeof ConfigUserPatchSchema>;
    })
  | Extract<GenericConfigurationPatchParams, { scope: "project" }>;

/** 参数与结果 Schema 的共同键就是唯一客户端方法闭集，避免维护第二份类型专用目录。 */
export type ClientMethod = keyof typeof ParamsSchemaByMethod & keyof typeof ResultSchemaByMethod;
export type MethodParams<M extends ClientMethod> = M extends "configuration/patch"
  ? ConfigurationPatchParams
  : z.infer<(typeof ParamsSchemaByMethod)[M]>;
export type MethodResult<M extends ClientMethod> = z.infer<(typeof ResultSchemaByMethod)[M]>;

/** 以客户端方法名公开严格参数目录，调用方不需要接触内部元组结构。 */
/** 以客户端方法名公开严格结果目录，保证响应校验与请求方法一一对应。 */

/**
 * 请求占用 pending 槽位前完成协议和 user 配置 patch 双重校验；项目层仍沿用稀疏配置文档形状。
 * outputSchema 只在对应字段路径放行 JSON Schema 定义名。
 * @author kongweiguang
 */
export function parseMethodParams<M extends ClientMethod>(
  method: M,
  params: unknown,
): MethodParams<M> {
  const allowCredentialSecretPath = method === "credential/set" ? (["secret"] as const) : undefined;
  // attachment preview 的 authorization 是资源归属 tag；只在 params 根的精确键放行，
  // 其它方法或嵌套位置仍按潜在凭据泄漏拒绝。
  const allowAuthorizationPath =
    method === "attachment/preview/open" ? (["authorization"] as const) : undefined;
  const allowJsonSchemaPropertyNamesPath =
    method === "turn/start" ? (["runtimeOverrides", "outputSchema"] as const) : undefined;
  assertNoReadyTokenLeak(params, {
    allowCredentialSecretPath,
    allowAuthorizationPath,
    allowJsonSchemaPropertyNamesPath,
  });
  const parsed = ParamsSchemaByMethod[method].parse(params);
  if (method === "configuration/patch") {
    const configurationPatch = parsed as { scope: string; patch: unknown };
    if (configurationPatch.scope === "user") ConfigUserPatchSchema.parse(configurationPatch.patch);
  }
  return parsed as MethodParams<M>;
}

/** 按原始方法校验结果，防止不同方法的投影被错误关联。 */
export function parseMethodResult<M extends ClientMethod>(
  method: M,
  result: unknown,
): MethodResult<M> {
  // API Key 回显仅有一个精确方法和严格结果 Schema，其他响应仍按默认 Secret 边界失败关闭。
  assertNoReadyTokenLeak(result, {
    allowCredentialSecretPath:
      method === "credential/reveal-provider" ? (["secret"] as const) : undefined,
  });
  return ResultSchemaByMethod[method].parse(result) as MethodResult<M>;
}
