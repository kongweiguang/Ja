// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { z } from "zod";
import {
  CREDENTIAL_REF_PATTERN,
  isSafeHttpUrl,
  isSafeProviderUrl,
} from "@/shared/settings/validation";

export { CREDENTIAL_REF_PATTERN } from "@/shared/settings/validation";
export type { McpServerSave } from "@/shared/settings/types";

/** 拒绝 stdio Command 中的内联 Credential，保证 Secret 只能经由原生凭据端口写入。 */
function containsInlineSecret(value: string): boolean {
  return (
    /(?:^|\s)--?(?:token|secret|password|api[_-]?key|authorization|bearer)(?:\s|=|:)/i.test(
      value,
    ) || /(?:token|secret|password|api[_-]?key|authorization|bearer)\s*[:=]\s*\S+/i.test(value)
  );
}

/** 多行 map 只接受可读的 KEY=VALUE，避免保存后出现空键或解析歧义。 */
function validKeyValueLines(value: string): boolean {
  return value
    .split(/\r?\n/u)
    .map((line) => line.trim())
    .filter(Boolean)
    .every((line) => line.indexOf("=") > 0);
}

const optionalCredentialRef = z.preprocess(
  (value) => (typeof value === "string" ? value.trim() || undefined : value),
  z.string().regex(CREDENTIAL_REF_PATTERN, "credential ref 必须匹配 cred_... 格式。 ").optional(),
);

const optionalUrl = z.preprocess(
  (value) => (typeof value === "string" ? value.trim() || undefined : value),
  z.string().max(2048).optional(),
);

/** Renderer 只编辑模型预算；输入能力由 App Server 目录与 Codec 共同裁决。 */
const modelCapabilitiesSchema = z
  .object({
    contextWindowTokens: z.number().int().min(4_096).max(4_000_000),
    maxOutputTokens: z.number().int().min(1).max(1_000_000),
  })
  .strict();

const reasoningLevelSchema = z.enum(["off", "minimal", "low", "medium", "high", "xhigh", "max"]);
const reasoningMapValueSchema = z.string().trim().min(1).max(128);
const reasoningLevelMapSchema = z
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

/** 压缩配置只保留产品开关；具体阈值由 App Server 的版本化策略统一管理。 */
const providerContextSchema = z
  .object({
    autoCompact: z.boolean(),
  })
  .strict();

/** Turn 上限在保存前完成纯校验，避免 UI 依赖 Provider SDK 的可变默认行为。 */
const providerTurnLimitsSchema = z
  .object({
    maxModelRounds: z.number().int().min(1).max(128),
    maxToolCalls: z.number().int().min(0).max(1_024),
    wallTimeoutMs: z.number().int().min(1_000).max(86_400_000),
  })
  .strict();

/** 网络超时保持在 JA-RPC v1 可接受范围内，避免无界等待或立即超时。 */
const providerNetworkTimeoutsSchema = z
  .object({
    connectTimeoutMs: z.number().int().min(100).max(120_000),
    requestTimeoutMs: z.number().int().min(1_000).max(3_600_000),
  })
  .strict();

/** 单模型规则验证模型能力与思考档位，默认档位不得脱离支持集合。 */
export const providerModelSchema = z
  .object({
    modelId: z
      .string()
      .regex(/^model_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
      .optional(),
    name: z.string().trim().min(1, "请填写显示名称。"),
    model: z.string().trim().min(1, "请填写模型名称。"),
    capabilities: modelCapabilitiesSchema,
    reasoningLevelMap: reasoningLevelMapSchema,
    defaultReasoningLevel: reasoningLevelSchema.nullable(),
  })
  .strict()
  .superRefine((values, context) => {
    if (
      values.defaultReasoningLevel !== null &&
      values.reasoningLevelMap[values.defaultReasoningLevel] === undefined
    ) {
      context.addIssue({
        code: "custom",
        path: ["defaultReasoningLevel"],
        message: "默认思考档位必须属于支持集合。",
      });
    }
    if (values.capabilities.maxOutputTokens >= values.capabilities.contextWindowTokens) {
      context.addIssue({
        code: "custom",
        path: ["capabilities", "maxOutputTokens"],
        message: "最大输出 Tokens 必须小于上下文窗口 Tokens。",
      });
    }
  });

/** Provider 表单规则只管理连接与 Agent 默认值，模型由独立子编辑器保存。 */
export const providerSchema = z
  .object({
    providerId: z
      .string()
      .regex(/^provider_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
      .optional(),
    name: z.string().trim().min(1, "请填写 Provider 名称。"),
    api: z.enum(["anthropic_messages", "openai_responses", "openai_chat_completions"]),
    baseUrl: optionalUrl,
    credentialId: z
      .string()
      .regex(CREDENTIAL_REF_PATTERN, "credential id 必须匹配 cred_... 格式。"),
    networkTimeouts: providerNetworkTimeoutsSchema,
    agentDefaults: z
      .object({
        context: providerContextSchema,
        turnLimits: providerTurnLimitsSchema,
      })
      .strict(),
  })
  .strict()
  .superRefine((values, context) => {
    if (values.baseUrl !== undefined && !isSafeProviderUrl(values.baseUrl)) {
      context.addIssue({
        code: "custom",
        path: ["baseUrl"],
        message: "请输入无凭据参数的 HTTPS 地址，或本机回环 HTTP 地址。",
      });
    }
  });

/** v1 只提供 Provider 聚合入口，Provider 连接与模型能力必须分别校验。 */

/** MCP 表单规则同时约束安全 URL 与 stdio Secret 边界，不执行任何 native IO。 */
export const mcpSchema = z
  .object({
    name: z.string().trim().min(1, "请填写 Server 名称。"),
    transport: z.enum(["stdio", "streamable_http"]),
    endpoint: z.string().trim().min(1, "请填写 executable 或 URL。"),
    argsText: z.string().max(32_768, "参数内容过长。"),
    envText: z.string().max(32_768, "环境变量内容过长。"),
    headersText: z.string().max(32_768, "Header 内容过长。"),
    authKind: z.enum(["none", "bearer", "header", "env"]),
    authName: z.string().trim().max(128, "认证名称不能超过 128 个字符。"),
    credentialRef: optionalCredentialRef,
    enabled: z.boolean(),
  })
  .superRefine((values, context) => {
    if (values.transport === "streamable_http" && !isSafeHttpUrl(values.endpoint)) {
      context.addIssue({
        code: "custom",
        path: ["endpoint"],
        message: "MCP HTTP 地址必须是无 userinfo、query、fragment 的 HTTP/HTTPS URL。",
      });
    }
    if (values.transport === "stdio" && containsInlineSecret(values.endpoint)) {
      context.addIssue({
        code: "custom",
        path: ["endpoint"],
        message: "stdio 配置不能包含 token、password 或 authorization 明文。",
      });
    }
    if (values.transport === "stdio" && values.headersText.trim() !== "") {
      context.addIssue({
        code: "custom",
        path: ["headersText"],
        message: "stdio transport 不使用 HTTP Headers。",
      });
    }
    if (values.transport === "streamable_http" && values.argsText.trim() !== "") {
      context.addIssue({
        code: "custom",
        path: ["argsText"],
        message: "Streamable HTTP transport 不使用进程参数。",
      });
    }
    if (values.transport === "stdio" && !["none", "env"].includes(values.authKind)) {
      context.addIssue({
        code: "custom",
        path: ["authKind"],
        message: "stdio 仅支持无认证或环境变量凭据。",
      });
    }
    if (values.transport === "streamable_http" && values.authKind === "env") {
      context.addIssue({
        code: "custom",
        path: ["authKind"],
        message: "Streamable HTTP 不使用环境变量认证。",
      });
    }
    if (values.authKind !== "none" && (values.credentialRef ?? "").trim() === "") {
      context.addIssue({
        code: "custom",
        path: ["credentialRef"],
        message: "请选择凭据引用。",
      });
    }
    if (["header", "env"].includes(values.authKind) && values.authName.trim() === "") {
      context.addIssue({
        code: "custom",
        path: ["authName"],
        message: "请填写认证字段名称。",
      });
    }
    if (containsInlineSecret(`${values.envText}\n${values.headersText}`)) {
      context.addIssue({
        code: "custom",
        path: [values.transport === "stdio" ? "envText" : "headersText"],
        message: "配置不能包含 token、password 或 authorization 明文。",
      });
    }
    for (const [field, value] of [
      ["envText", values.envText],
      ["headersText", values.headersText],
    ] as const) {
      if (!validKeyValueLines(value)) {
        context.addIssue({
          code: "custom",
          path: [field],
          message: "每个非空行都必须使用 KEY=VALUE 格式。",
        });
      }
    }
  });
