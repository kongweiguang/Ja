// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
/* eslint-disable react-refresh/only-export-components */

import * as Label from "@radix-ui/react-label";
import * as Switch from "@radix-ui/react-switch";
import { CircleAlert, Cloud, Laptop, Server, Shield, Sparkles } from "lucide-react";
import { cloneElement, isValidElement, type ReactElement, type ReactNode } from "react";
import { type UseFormSetError } from "react-hook-form";
import { Select } from "@/shared/ui/primitives";
import type {
  McpServerDraft,
  ProviderDraft,
  ProviderModelDraft,
  SettingsSection,
  SkillSource,
} from "../domain/types";
import { mcpSchema, type McpServerSave } from "../domain/validation";

export { CREDENTIAL_REF_PATTERN } from "../domain/validation";
export type { McpServerSave } from "../domain/validation";

export const sections: ReadonlyArray<{ id: SettingsSection; label: string; icon: ReactElement }> = [
  { id: "models", label: "模型", icon: <Cloud size={16} aria-hidden="true" /> },
  { id: "skills", label: "Skills", icon: <Sparkles size={16} aria-hidden="true" /> },
  { id: "mcp", label: "MCP", icon: <Server size={16} aria-hidden="true" /> },
  { id: "permissions", label: "执行确认", icon: <Shield size={16} aria-hidden="true" /> },
  { id: "appearance", label: "外观", icon: <Laptop size={16} aria-hidden="true" /> },
];

export const apiOptions = [
  { value: "anthropic_messages", label: "Anthropic Messages" },
  { value: "openai_responses", label: "OpenAI Responses" },
] as const;

export const providerOptions = [
  { value: "anthropic", label: "Anthropic" },
  { value: "openai", label: "OpenAI" },
] as const;

export const themeOptions = [
  { value: "system", label: "跟随系统" },
  { value: "light", label: "浅色" },
  { value: "dark", label: "深色" },
] as const;

export const transportOptions = [
  { value: "stdio", label: "stdio" },
  { value: "streamable_http", label: "Streamable HTTP" },
] as const;

export const sourceLabels: Record<SkillSource, string> = {
  builtin: "内置",
  user: "用户",
  workspace: "Workspace",
};

/**
 * CAS 冲突使用固定可恢复文案，其它错误仍由调用点提供领域文案；不把任意原生错误正文
 * 或潜在敏感配置带进 Toast。
 */
export function settingsMutationErrorMessage(error: unknown, fallback: string): string {
  const value =
    error !== null && typeof error === "object" ? (error as Record<string, unknown>) : undefined;
  return value?.["code"] === "revision_conflict" ||
    (error instanceof Error && error.message.includes("其他窗口修改"))
    ? "设置已被其他窗口修改，当前值已刷新，请重新提交"
    : fallback;
}

/** 新 Provider 使用安全显式默认值，并在首个保存前要求用户确认连接字段。 */
export function emptyProviderDraft(): ProviderDraft {
  return {
    provider: "anthropic",
    api: "anthropic_messages",
    name: "Anthropic",
    baseUrl: "https://api.anthropic.com",
    credentialId: canonicalCredentialId(),
    networkTimeouts: { connectTimeoutMs: 10_000, requestTimeoutMs: 120_000 },
    agentDefaults: {
      context: { autoCompact: true },
      turnLimits: { maxModelRounds: 32, maxToolCalls: 128, wallTimeoutMs: 3_600_000 },
    },
  };
}

/** 新模型按未知自定义模型创建为 text-only；Renderer 只声明预算，不写输入能力。 */
export function emptyProviderModelDraft(): ProviderModelDraft {
  return {
    name: "",
    model: "",
    capabilities: {
      contextWindowTokens: 128_000,
      maxOutputTokens: 8_192,
    },
    reasoningLevelMap: {},
    defaultReasoningLevel: null,
  };
}

/**
 * 为新 Provider 分配 Credential Selector，使文档可保存而无需把 Secret 本身放入 React 状态；
 * 该 Selector 指向的值始终由原生 Auth Store 持有。
 */
function canonicalCredentialId(): string {
  const suffix = globalThis.crypto?.randomUUID?.().replaceAll("-", "") ?? Date.now().toString(36);
  return `cred_${suffix}`;
}

/** 创建 Wire-compatible 稳定 ID，显示名称与顺序变化均不修改身份。 */
export function canonicalRevision(prefix: "provider" | "model" | "mcp"): string {
  return `${prefix}_${globalThis.crypto?.randomUUID?.().replaceAll("-", "") ?? Date.now().toString(36)}`;
}

/** 把每行 KEY=VALUE 转为有界配置 map；校验层已负责拒绝格式与内联 Secret。 */
function parseKeyValueLines(value: string): Record<string, string> {
  return Object.fromEntries(
    value
      .split(/\r?\n/u)
      .map((line) => line.trim())
      .filter(Boolean)
      .map((line) => {
        const separator = line.indexOf("=");
        return [line.slice(0, separator).trim(), line.slice(separator + 1).trim()];
      }),
  );
}

/** 映射 MCP 表单值时完整保留 v4 transport 与认证字段，不把 Secret 放入文档。 */
export function toMcpSavePayload(
  values: McpServerDraft,
  revision = values.mcpRevision ?? canonicalRevision("mcp"),
): McpServerSave {
  const credentialRef = values.credentialRef.trim();
  const auth =
    values.authKind === "none"
      ? ({ kind: "none" } as const)
      : values.authKind === "bearer"
        ? ({ kind: "bearer", credentialRef } as const)
        : ({
            kind: values.authKind,
            name: values.authName.trim(),
            credentialRef,
          } as const);
  return {
    mcpRevision: revision,
    name: values.name.trim(),
    transport: values.transport,
    endpoint: values.endpoint.trim(),
    args:
      values.transport === "stdio"
        ? values.argsText
            .split(/\r?\n/u)
            .map((line) => line.trim())
            .filter(Boolean)
        : [],
    env: parseKeyValueLines(values.envText),
    headers: values.transport === "streamable_http" ? parseKeyValueLines(values.headersText) : {},
    auth,
    enabled: values.enabled,
  };
}

/** 复用同一 Zod 边界，同时保持 MCP 错误可定位到具体字段。 */
export function validateMcpDraft(
  values: McpServerDraft,
  setError: UseFormSetError<McpServerDraft>,
): boolean {
  const result = mcpSchema.safeParse(values);
  if (!result.success) {
    for (const issue of result.error.issues) {
      const field = issue.path[0];
      if (typeof field === "string")
        setError(field as keyof McpServerDraft, { type: "zod", message: issue.message });
    }
    return false;
  }
  return true;
}

/**
 * Settings 只把 Field 注入的原生 ARIA 属性翻译给共享 Select；Portal、键盘与视觉完全由
 * 公共 primitive 持有，避免设置页形成第二套下拉实现。
 */
export function SettingsSelect({
  id,
  value,
  options,
  onValueChange,
  ariaLabel,
  ariaDescribedBy,
  ariaInvalid,
  "aria-describedby": ariaDescribedByAttribute,
  "aria-invalid": ariaInvalidAttribute,
}: {
  id: string;
  value: string;
  options: ReadonlyArray<{ value: string; label: string; disabled?: boolean }>;
  onValueChange: (value: string) => void;
  ariaLabel?: string;
  ariaDescribedBy?: string;
  ariaInvalid?: boolean;
  "aria-describedby"?: string;
  "aria-invalid"?: boolean;
}): ReactElement {
  return (
    <Select
      id={id}
      value={value}
      options={options}
      onValueChange={onValueChange}
      ariaLabel={ariaLabel}
      ariaDescribedBy={ariaDescribedBy ?? ariaDescribedByAttribute}
      ariaInvalid={ariaInvalid ?? ariaInvalidAttribute}
    />
  );
}

/** 将 Label、Hint 与 Error 绑定为一个可访问字段单元，使校验结果不只依赖颜色。 */
export function Field({
  id,
  label,
  hint,
  error,
  children,
}: {
  id: string;
  label: string;
  hint?: string;
  error?: string;
  children: ReactNode;
}): ReactElement {
  const describedBy =
    [hint === undefined ? undefined : `${id}-hint`, error === undefined ? undefined : `${id}-error`]
      .filter((value): value is string => value !== undefined)
      .join(" ") || undefined;
  const control = isValidElement(children)
    ? cloneElement(
        children as ReactElement<{ "aria-describedby"?: string; "aria-invalid"?: boolean }>,
        { "aria-describedby": describedBy, "aria-invalid": error !== undefined },
      )
    : children;
  return (
    <div className="ja-settings-field" data-setting-search={`${label} ${hint ?? ""}`} tabIndex={-1}>
      <Label.Root htmlFor={id} className="ja-settings-label">
        {label}
      </Label.Root>
      {control}
      {hint === undefined ? null : (
        <p className="ja-settings-hint" id={`${id}-hint`}>
          {hint}
        </p>
      )}
      {error === undefined ? null : (
        <p className="ja-settings-error" role="alert" id={`${id}-error`}>
          <CircleAlert size={14} aria-hidden="true" />
          {error}
        </p>
      )}
    </div>
  );
}

/** 分区标题保持视觉稳定；Action 仍由各独立 Settings Slice 选择并定义类型。 */
export function SectionHeader({
  title,
  description,
  action,
}: {
  title: string;
  description?: string;
  action?: ReactNode;
}): ReactElement {
  return (
    <div
      className="ja-settings-section-header"
      data-setting-search={`${title} ${description ?? ""}`}
      tabIndex={-1}
    >
      <div>
        <h2>{title}</h2>
        {description === undefined ? null : <p>{description}</p>}
      </div>
      {action}
    </div>
  );
}

/** Radix Switch 配合可见 Label，使 Accent Color 不可用或启用高对比度时仍可识别选中状态。 */
export function SwitchField({
  id,
  label,
  checked,
  onCheckedChange,
  hint,
  disabled = false,
}: {
  id: string;
  label: string;
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
  hint?: string;
  disabled?: boolean;
}): ReactElement {
  return (
    <div
      className="ja-settings-switch-field"
      data-setting-search={`${label} ${hint ?? ""}`}
      tabIndex={-1}
    >
      <Switch.Root
        id={id}
        className="ja-settings-switch"
        checked={checked}
        onCheckedChange={onCheckedChange}
        aria-label={label}
        aria-describedby={hint === undefined ? undefined : `${id}-hint`}
        disabled={disabled}
      >
        <Switch.Thumb className="ja-settings-switch-thumb" />
      </Switch.Root>
      <div>
        <Label.Root htmlFor={id} className="ja-settings-switch-label">
          {label}
        </Label.Root>
        {hint === undefined ? null : (
          <p className="ja-settings-hint" id={`${id}-hint`}>
            {hint}
          </p>
        )}
      </div>
    </div>
  );
}
