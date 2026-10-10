// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import * as Tabs from "@radix-ui/react-tabs";
import { ArrowLeft, CircleAlert, Search, ShieldCheck, ShieldOff, X } from "lucide-react";
import {
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
} from "react";
import {
  Button,
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogTitle,
  DialogTrigger,
  IconButton,
  ScrollArea,
} from "@/shared/ui/primitives";
import type {
  ConfigurationIssue,
  ProjectProviderRuntimeOverride,
  SettingsSection,
  SettingsSnapshot,
} from "../domain/types";
import type { SettingsDesktopPort, SettingsPorts } from "../application/ports";
import type { SettingsInterfacePreferences } from "../application/ports";
import type { ExecutionScope } from "../domain/executionScope";
import type { WorkspaceProjection } from "@/features/workspace";
import { CapabilityProjectPicker } from "./CapabilityProjectPicker";
// 基础样式先于分类样式加载，避免通用旧选择器覆盖模型、Skills 和 MCP 的局部布局。
import "./settings.css";
import { ModelsSection } from "./models";
import { SubagentsSection } from "./subagents";
import { McpSection } from "./mcp";
import {
  PermissionsSection,
  GeneralSection,
  AppearanceSection,
  type DesktopNotificationPreference,
} from "./sections";
import { SectionHeader, sections } from "./shared";
import { SkillsSection } from "./skills";
import { AboutSection, SettingsUpdateAction } from "./about";
import { useAppUpdater } from "../application/useAppUpdater";
import { DataStorageSection, type DataStorageProps } from "./storage";

const sectionKeywords: Record<SettingsSection, string> = {
  general: "通用 交互澄清 选择框 反问 桌面通知 后台 完成 失败 确认 notification",
  models: "模型 服务商 provider api api key 明文 密钥 协议 地址 base url anthropic openai 活动",
  subagents:
    "子智能体 subagent agent spawn 启用 模型 跟随父任务 provider model 思考等级 reasoning off minimal low medium high xhigh max",
  skills: "skills 技能 指令 内置 用户 ja 项目 启用",
  mcp: "mcp 工具 server transport stdio http endpoint credential ref 协议版本 oauth",
  permissions: "权限 沙箱 access 安全 只读 工作区 完全访问 命令",
  appearance: "外观 主题 深色 浅色 系统 配色 动效 透明度 对比度 xcode ja jetbrains obsidian claude",
  about: "关于 Ja GitHub 开源 协议 GPL 版本 更新 软件更新",
  storage: "数据 存储 恢复 历史 备份",
};

interface SettingsSearchResult {
  id: string;
  section: SettingsSection;
  label: string;
  detail: string;
  search: string;
  providerId?: string;
  modelId?: string;
}

interface ProjectTrustRequest {
  workspaceId: string;
  displayName: string;
  rootPath: string;
  trust: "trusted" | "untrusted";
  apply: (workspaceId: string, trust: "trusted" | "untrusted") => Promise<void>;
}

/**
 * 搜索索引只包含页面真实可见的设置项与脱敏投影；每条结果保留所属分类和定位词，
 * 点击后再导航与聚焦，避免输入期间抢走搜索框焦点。
 */
function settingsSearchResults(
  snapshot: SettingsSnapshot,
  notificationsAvailable: boolean,
): SettingsSearchResult[] {
  return [
    {
      id: "general-close-behavior",
      section: "general" as const,
      label: "关闭窗口时",
      detail: "通用 · 留在后台或退出 Ja",
      search: "关闭 窗口 后台 托盘 退出 close",
    },
    {
      id: "general-send-shortcut",
      section: "general" as const,
      label: "发送快捷键",
      detail: "通用 · Enter 或 Ctrl / Cmd + Enter",
      search: "发送 换行 快捷键 键盘 enter ctrl cmd shift",
    },
    {
      id: "general-clarification",
      section: "general" as const,
      label: "交互澄清",
      detail:
        snapshot.clarificationEnabled === false
          ? "通用 · 已关闭，Plan 仍始终可用"
          : "通用 · 允许模型确认关键偏好",
      search: "交互澄清 选择框 反问 clarification",
    },
    {
      id: "appearance-ui-font",
      section: "appearance" as const,
      label: "界面字号",
      detail: "外观 · 调整界面文字大小",
      search: "字体 字号 文字 大小 界面 font size",
    },
    {
      id: "appearance-code-font",
      section: "appearance" as const,
      label: "代码字号",
      detail: "外观 · 调整代码与终端文字大小",
      search: "字体 字号 代码 终端 编辑器 diff font size",
    },
    ...(notificationsAvailable
      ? [
          {
            id: "general-notifications",
            section: "general" as const,
            label: "桌面通知",
            detail: "通用 · 后台完成、失败与待确认提醒",
            search: sectionKeywords.general,
          },
        ]
      : []),
    ...snapshot.providers.flatMap((provider) => [
      {
        id: `provider-${provider.providerId}`,
        providerId: provider.providerId,
        section: "models" as const,
        label: provider.name,
        detail: `${provider.api} · ${provider.models.length} 个模型`,
        search: `${provider.name} ${provider.api} ${provider.baseUrl}`,
      },
      ...provider.models.map((model) => ({
        id: `model-${provider.providerId}-${model.modelId}`,
        providerId: provider.providerId,
        modelId: model.modelId,
        section: "models" as const,
        label: model.name,
        detail: `${provider.name} · ${model.model}`,
        search: `${model.name} ${model.model} 上下文 输出 思考 ${Object.keys(model.reasoningLevelMap).join(" ")}`,
      })),
    ]),
    {
      id: "subagents",
      section: "subagents" as const,
      label: "子智能体",
      detail: snapshot.subagents.enabled ? "已启用 · 新建会话生效" : "已关闭 · 新建会话生效",
      search: sectionKeywords.subagents,
    },
    {
      id: "subagents-reasoning",
      section: "subagents" as const,
      label: "子智能体思考等级",
      detail:
        snapshot.subagents.providerId === null
          ? "子智能体 · 沿用父任务"
          : `子智能体 · ${snapshot.subagents.reasoningLevel ?? "模型默认"}`,
      search: `${sectionKeywords.subagents} 子智能体思考等级 reasoning level`,
    },
    ...snapshot.skills.map((skill) => ({
      id: `skill-${skill.id}`,
      section: "skills" as const,
      label: skill.name,
      detail: skill.description || "Skill",
      search: `${skill.name} ${skill.description} ${skill.source} ${skill.status}`,
    })),
    ...snapshot.mcpServers.map((server) => ({
      id: `mcp-${server.id}`,
      section: "mcp" as const,
      label: server.name,
      detail: `${server.transport} · ${server.endpoint}`,
      search: `${server.name} ${server.transport} ${server.endpoint} ${server.tools.map((tool) => tool.name).join(" ")}`,
    })),
    {
      id: "permission-mode",
      section: "permissions" as const,
      label: "执行确认",
      detail: snapshot.defaultAccessMode === "full_access" ? "完全访问" : "需要审批",
      search: sectionKeywords.permissions,
    },
    {
      id: "permission-scope",
      section: "permissions" as const,
      label: "生效范围",
      detail: "执行确认 · 全局、项目与会话",
      search: "权限 生效 范围 来源 全局 默认 项目 限制 会话 选择",
    },
    {
      id: "appearance-theme",
      section: "appearance" as const,
      label: "主题与显示",
      detail: "外观 · 主题、配色、动效与对比度",
      search: sectionKeywords.appearance,
    },
    {
      id: "about-product",
      section: "about" as const,
      label: "关于 Ja",
      detail: "版本、GitHub 与软件更新",
      search: sectionKeywords.about,
    },
  ];
}

/**
 * 每个词独立匹配，使中文标签与英文 Provider 名表现一致，同时避免把模糊搜索依赖引入桌面 Bundle。
 */
function matchesQuery(query: string, text: string): boolean {
  const normalizedText = text.toLocaleLowerCase();
  return query
    .trim()
    .toLocaleLowerCase()
    .split(/\s+/u)
    .filter(Boolean)
    .every((term) => normalizedText.includes(term));
}

/**
 * 以问题关联条目的稳定 ID 识别 MCP，而不是假定字段名总是 `mcp_servers`。
 *
 * 未知字段会保留其真实字段名，例如错位的 `schema_version`；实体身份仍是导航到正确设置区的
 * 唯一可靠依据，避免“编辑”把用户带到模型页。
 */
function isMcpIssue(issue: ConfigurationIssue): boolean {
  return issue.field === "mcp_servers" || issue.entityId?.startsWith("mcp_") === true;
}

/**
 * 将脱敏的作用域与条目身份投影为用户能立即判断的设置区域，不暴露原始文档或内部诊断码。
 */
function issueTitle(issue: ConfigurationIssue): string {
  if (issue.reason === "UNSUPPORTED_SCHEMA_VERSION") return "配置版本";
  if (isMcpIssue(issue)) return "MCP 服务";
  if (issue.field === "providers" || issue.entityId?.startsWith("provider_") === true)
    return "服务商";
  if (issue.field === "models" || issue.entityId?.startsWith("model_") === true) return "模型";
  if (issue.field === "skills") return "Skill";
  if (issue.scope === "credential") return "凭据";
  return "配置项";
}

/**
 * 把稳定影响码转换为简短处理结果，保证局部未知字段不会被误述为整份配置损坏。
 */
function issueDescription(issue: ConfigurationIssue): string {
  if (issue.reason === "UNSUPPORTED_SCHEMA_VERSION") {
    return "此配置声明的版本当前不受 Ja 支持，因此该层未能应用。请先备份，再使用现有修复操作恢复配置。";
  }
  if (issue.impact === "valid_prefix_in_use") {
    return issue.line === null
      ? "语法错误之后的内容已忽略，当前使用错误之前能读取的设置。"
      : `第 ${issue.line} 行之后的内容无法确认，当前只使用此前能读取的设置。`;
  }
  if (issue.impact === "defaults_in_use") return "正在使用默认设置。";
  if (issue.impact === "selection_required") return "需要选择一个可用模型。";
  if (issue.impact === "provider_unavailable") return "此服务商暂不可用。";
  if (issue.impact === "model_unavailable") return "此模型暂不可用。";
  if (issue.impact === "mcp_unavailable") return "此 MCP 暂不可用。";
  if (issue.impact === "skill_unavailable") return "此 Skill 暂不可用。";
  if (issue.impact === "credential_connections_unavailable") return "依赖该凭据的连接暂不可用。";
  if (issue.impact === "ignored") return "此字段暂不支持，已忽略。";
  if (issue.impact === "entry_skipped") return "此条目暂不可用，其他设置不受影响。";
  return "此设置正在使用安全降级。";
}

/** 将 App Server 冻结的来源枚举映射为用户能直接理解的短标签。 */
function sourceLabel(source: "default" | "user" | "project" | undefined): string {
  switch (source) {
    case "project":
      return "当前项目";
    case "user":
      return "全局";
    default:
      return "内置默认";
  }
}

/** 项目恢复继承只删除一个稀疏覆盖；失败时保留设置页并给出可重试反馈。 */
function InheritProjectOverrideButton({
  onClick,
}: {
  onClick: () => Promise<void>;
}): React.ReactElement {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string>();
  const inherit = async (): Promise<void> => {
    if (pending) return;
    setPending(true);
    setError(undefined);
    try {
      await onClick();
    } catch {
      setError("恢复全局设置失败，请重试。");
    } finally {
      setPending(false);
    }
  };
  return (
    <div className="ja-settings-form-actions">
      <Button
        type="button"
        variant="ghost"
        size="sm"
        loading={pending}
        onClick={() => void inherit()}
      >
        使用全局设置
      </Button>
      {error === undefined ? null : (
        <span className="ja-settings-error" role="alert">
          {error}
        </span>
      )}
    </div>
  );
}

/** 单个项目 Provider 卡片只编辑 timeout/context，保存不触碰全局 endpoint 与 credential。 */
function ProjectProviderRuntimeCard({
  provider,
  override,
  onSave,
  onInherit,
}: {
  provider: SettingsSnapshot["providers"][number];
  override: ProjectProviderRuntimeOverride | undefined;
  onSave: NonNullable<SettingsPorts["onProjectProviderRuntimeChange"]>;
  onInherit?: SettingsPorts["onInheritProjectProvider"];
}): React.ReactElement {
  const [connectTimeout, setConnectTimeout] = useState(
    String(provider.networkTimeouts.connectTimeoutMs),
  );
  const [requestTimeout, setRequestTimeout] = useState(
    String(provider.networkTimeouts.requestTimeoutMs),
  );
  const [autoCompact, setAutoCompact] = useState(provider.agentDefaults.context.autoCompact);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string>();
  const [saved, setSaved] = useState(false);
  const hasOverride =
    override?.networkTimeouts !== undefined || override?.autoCompact !== undefined;

  useEffect(() => {
    setConnectTimeout(String(provider.networkTimeouts.connectTimeoutMs));
    setRequestTimeout(String(provider.networkTimeouts.requestTimeoutMs));
    setAutoCompact(provider.agentDefaults.context.autoCompact);
    setSaved(false);
  }, [provider]);

  /** 验证有限边界后一次保存三个可见参数，避免保存表单中的半个 timeout 对。 */
  const save = async (): Promise<void> => {
    const connect = Number(connectTimeout);
    const request = Number(requestTimeout);
    if (
      !Number.isInteger(connect) ||
      connect < 100 ||
      connect > 120_000 ||
      !Number.isInteger(request) ||
      request < 1_000 ||
      request > 3_600_000
    ) {
      setError("连接时间须为 100–120000 ms，请求时间须为 1000–3600000 ms。");
      setSaved(false);
      return;
    }
    setPending(true);
    setError(undefined);
    setSaved(false);
    try {
      await onSave(provider.providerId, {
        connectTimeoutMs: connect,
        requestTimeoutMs: request,
        autoCompact,
      });
      setSaved(true);
    } catch {
      setError("项目运行参数保存失败，请重试。");
    } finally {
      setPending(false);
    }
  };

  return (
    <article className="ja-settings-project-provider" data-provider-id={provider.providerId}>
      <header>
        <strong>{provider.name}</strong>
        {hasOverride ? (
          <Button
            type="button"
            size="sm"
            variant="ghost"
            disabled={pending || onInherit === undefined}
            onClick={() => void onInherit?.(provider.providerId)}
          >
            使用全局设置
          </Button>
        ) : null}
      </header>
      <div className="ja-settings-project-provider-fields">
        <label>
          <span>连接超时（ms）</span>
          <input
            aria-label={`${provider.name} 连接超时（ms）`}
            type="number"
            min={100}
            max={120_000}
            value={connectTimeout}
            disabled={pending}
            onChange={(event) => setConnectTimeout(event.currentTarget.value)}
          />
        </label>
        <label>
          <span>请求超时（ms）</span>
          <input
            aria-label={`${provider.name} 请求超时（ms）`}
            type="number"
            min={1_000}
            max={3_600_000}
            value={requestTimeout}
            disabled={pending}
            onChange={(event) => setRequestTimeout(event.currentTarget.value)}
          />
        </label>
        <label className="ja-settings-project-provider-toggle">
          <input
            type="checkbox"
            checked={autoCompact}
            disabled={pending}
            onChange={(event) => setAutoCompact(event.currentTarget.checked)}
          />
          自动压缩上下文
        </label>
      </div>
      <div className="ja-settings-form-actions">
        <Button
          type="button"
          size="sm"
          variant="secondary"
          loading={pending}
          onClick={() => void save()}
        >
          保存项目参数
        </Button>
        {saved ? <span role="status">项目参数已保存</span> : null}
        {error === undefined ? null : (
          <span className="ja-settings-error" role="alert">
            {error}
          </span>
        )}
      </div>
    </article>
  );
}

/** 只在已选可信项目时展示 Project 的 Provider 运行参数；配置源仍按字段保留。 */
function ProjectProviderRuntimeSection({
  providers,
  overrides,
  onSave,
  onInherit,
}: {
  providers: SettingsSnapshot["providers"];
  overrides: readonly ProjectProviderRuntimeOverride[];
  onSave?: SettingsPorts["onProjectProviderRuntimeChange"];
  onInherit?: SettingsPorts["onInheritProjectProvider"];
}): React.ReactElement {
  if (onSave === undefined) return <></>;
  return (
    <section className="ja-settings-project-provider-list" aria-label="项目 Provider 运行参数">
      <SectionHeader title="项目请求参数" />
      <p className="ja-settings-scope-note">
        超时和上下文压缩单独继承；项目连接由下方表单管理，实际密钥仍留在全局凭据库。
      </p>
      {providers.map((provider) => (
        <ProjectProviderRuntimeCard
          key={provider.providerId}
          provider={provider}
          override={overrides.find((value) => value.providerId === provider.providerId)}
          onSave={onSave}
          onInherit={onInherit}
        />
      ))}
    </section>
  );
}

/** 连接覆盖一次提交 API、地址和用户凭据引用，避免路由变更后沿用旧目标的密钥。 */
function ProjectProviderConnectionCard({
  provider,
  credentials,
  override,
  onSave,
  onInherit,
}: {
  provider: SettingsSnapshot["providers"][number];
  credentials: SettingsSnapshot["providers"];
  override: ProjectProviderRuntimeOverride | undefined;
  onSave: NonNullable<SettingsPorts["onProjectProviderConnectionChange"]>;
  onInherit?: SettingsPorts["onInheritProjectProviderConnection"];
}): React.ReactElement {
  const [api, setApi] = useState(provider.api);
  const [baseUrl, setBaseUrl] = useState(provider.baseUrl);
  const [credentialId, setCredentialId] = useState(provider.credentialId);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string>();
  const hasOverride = override?.connection !== undefined;

  useEffect(() => {
    setApi(override?.connection?.api ?? provider.api);
    setBaseUrl(override?.connection?.baseUrl ?? provider.baseUrl);
    setCredentialId(override?.connection?.credentialId ?? provider.credentialId);
    setError(undefined);
  }, [override?.connection, provider]);

  /** 只接纳无凭据、查询或片段的 HTTP(S) 地址，并在保存前确认选择了已有用户凭据。 */
  const save = async (): Promise<void> => {
    let safeUrl = false;
    try {
      const parsed = new URL(baseUrl);
      safeUrl =
        (parsed.protocol === "https:" || parsed.protocol === "http:") &&
        parsed.hostname.length > 0 &&
        parsed.username === "" &&
        parsed.password === "" &&
        parsed.search === "" &&
        parsed.hash === "";
    } catch {
      safeUrl = false;
    }
    if (!safeUrl) {
      setError("请输入有效的 HTTP 或 HTTPS 地址，不要在地址中填写密钥。");
      return;
    }
    if (
      !credentials.some((item) => item.credentialId === credentialId && item.credentialConfigured)
    ) {
      setError("请选择全局设置中已配置的凭据。");
      return;
    }
    setPending(true);
    setError(undefined);
    try {
      await onSave(provider.providerId, { api, baseUrl, credentialId });
    } catch {
      setError("项目连接保存失败，请检查字段并重试。");
    } finally {
      setPending(false);
    }
  };

  return (
    <article className="ja-settings-project-provider" data-provider-id={provider.providerId}>
      <header>
        <strong>{provider.name}</strong>
        {hasOverride ? (
          <Button
            type="button"
            size="sm"
            variant="ghost"
            disabled={pending || onInherit === undefined}
            onClick={() => void onInherit?.(provider.providerId)}
          >
            继承全局连接
          </Button>
        ) : null}
      </header>
      <div className="ja-settings-project-provider-fields ja-settings-project-provider-connection-fields">
        <label>
          <span>连接协议</span>
          <select
            aria-label={`${provider.name} 连接协议`}
            value={api}
            disabled={pending}
            onChange={(event) => setApi(event.currentTarget.value as typeof api)}
          >
            <option value="openai_responses">OpenAI Responses</option>
            <option value="openai_chat_completions">OpenAI Chat Completions</option>
            <option value="anthropic_messages">Anthropic Messages</option>
          </select>
        </label>
        <label>
          <span>项目连接地址</span>
          <input
            aria-label={`${provider.name} 项目连接地址`}
            type="url"
            value={baseUrl}
            disabled={pending}
            onChange={(event) => setBaseUrl(event.currentTarget.value)}
          />
        </label>
        <label>
          <span>项目凭据</span>
          <select
            aria-label={`${provider.name} 项目凭据`}
            value={credentialId}
            disabled={pending || credentials.every((item) => !item.credentialConfigured)}
            onChange={(event) => setCredentialId(event.currentTarget.value)}
          >
            {credentials
              .filter((item) => item.credentialConfigured)
              .map((item) => (
                <option key={item.credentialId} value={item.credentialId}>
                  {item.name}
                </option>
              ))}
            {credentials.some(
              (item) => item.credentialId === credentialId && !item.credentialConfigured,
            ) ? (
              <option value={credentialId}>当前凭据不可用</option>
            ) : null}
          </select>
        </label>
      </div>
      <p className="ja-settings-scope-note">项目只保存凭据引用；密钥保留在全局凭据库。</p>
      <div className="ja-settings-form-actions">
        <Button
          type="button"
          size="sm"
          variant="secondary"
          loading={pending}
          onClick={() => void save()}
        >
          保存项目连接
        </Button>
        {error === undefined ? null : (
          <span className="ja-settings-error" role="alert">
            {error}
          </span>
        )}
      </div>
    </article>
  );
}

/** 只展示当前已有 Provider，凭据下拉从全局脱敏状态中取值，不暴露 Secret。 */
function ProjectProviderConnectionSection({
  providers,
  credentials,
  overrides,
  onSave,
  onInherit,
}: {
  providers: SettingsSnapshot["providers"];
  credentials: SettingsSnapshot["providers"];
  overrides: readonly ProjectProviderRuntimeOverride[];
  onSave?: SettingsPorts["onProjectProviderConnectionChange"];
  onInherit?: SettingsPorts["onInheritProjectProviderConnection"];
}): React.ReactElement {
  if (onSave === undefined) return <></>;
  return (
    <section className="ja-settings-project-provider-list" aria-label="项目 Provider 连接">
      <SectionHeader title="项目连接" />
      <p className="ja-settings-scope-note">
        项目模型按稳定 ID 覆盖全局；默认模型选择按项目保存。更换连接时须选择已配置的全局凭据。
      </p>
      {providers.map((provider) => (
        <ProjectProviderConnectionCard
          key={provider.providerId}
          provider={provider}
          credentials={credentials}
          override={overrides.find((value) => value.providerId === provider.providerId)}
          onSave={onSave}
          onInherit={onInherit}
        />
      ))}
    </section>
  );
}

/**
 * 以 Codex 风格的紧凑分类布局承载设置内容；页面标题和恢复提示由外层
 * SettingsView 统一提供，避免同一屏重复渲染两套大标题，同时保持 snapshot
 * 和 ports 仍是唯一数据与副作用边界；项目 trust 确认固定 identity 与目标状态，失败保留同目标重试。
 */
export interface SettingsProps {
  dataStorage?: DataStorageProps;
  snapshot: SettingsSnapshot;
  projects?: readonly WorkspaceProjection[];
  selectedProjectId?: string;
  onSelectProject?: (workspaceId: string) => void;
  onSetProjectTrust?: ProjectTrustRequest["apply"];
  configScope?: "user" | "project";
  onConfigScopeChange?: (scope: "user" | "project") => void;
  projectConfigAvailable?: boolean;
  globalProviders?: SettingsSnapshot["providers"];
  projectOverrides?: {
    defaultSelection: boolean;
    accessMode: boolean;
    clarification?: boolean;
    subagents?: boolean;
    providerIds?: string[];
    providerRuntime?: ProjectProviderRuntimeOverride[];
    disabledSkillReferences?: string[];
  };
  sources?: Record<string, "default" | "user" | "project">;
  projectLoading?: boolean;
  skillSettings?: {
    global: SettingsSnapshot["skills"];
    project?: SettingsSnapshot["skills"];
    projectAvailable: boolean;
  };
  mcpSettings?: {
    global: SettingsSnapshot["mcpServers"];
    project?: SettingsSnapshot["mcpServers"];
    projectAvailable: boolean;
    projectWorkspaceId?: string;
  };
  /** 问题由 App Server 产生；页面只展示实际影响与允许操作，不从诊断代码猜配置内容。 */
  issues?: readonly ConfigurationIssue[];
  onIssuesRetry?: () => Promise<void>;
  onIssuesRepair?: (scope: "user" | "project") => Promise<void>;
  interfacePreferences: SettingsInterfacePreferences;
  executionScope: ExecutionScope;
  ports: SettingsPorts;
  section: SettingsSection;
  onSectionChange: (section: SettingsSection) => void;
  desktopNotifications?: DesktopNotificationPreference;
  desktop: SettingsDesktopPort;
  disabled?: boolean;
  required?: boolean;
  onReturnToApp?: () => void;
}

/**
 * Category 由 Desktop Shell 唯一持有；页面仅保存滚动和搜索定位，不复制配置或路由事实。
 * 项目信任弹窗的 Radix Trigger 必须对应真实工具栏按钮，确保关闭后自然归还焦点。
 */
export function Settings({
  dataStorage,
  snapshot,
  projects = [],
  selectedProjectId,
  onSelectProject,
  onSetProjectTrust,
  configScope = "user",
  onConfigScopeChange,
  projectConfigAvailable = false,
  globalProviders = snapshot.providers,
  projectOverrides,
  sources = {},
  projectLoading = false,
  skillSettings = { global: snapshot.skills, projectAvailable: false },
  mcpSettings = { global: snapshot.mcpServers, projectAvailable: false },
  issues = [],
  onIssuesRetry,
  onIssuesRepair,
  interfacePreferences,
  executionScope,
  ports,
  section,
  onSectionChange,
  desktopNotifications,
  desktop,
  disabled = false,
  required = false,
  onReturnToApp,
}: SettingsProps): React.ReactElement {
  const updater = useAppUpdater(desktop);
  const [query, setQuery] = useState("");
  const [issuesOpen, setIssuesOpen] = useState(false);
  const [issueAction, setIssueAction] = useState<"retry" | "repair">();
  const [repairIssue, setRepairIssue] = useState<ConfigurationIssue>();
  const [issueActionError, setIssueActionError] = useState<string>();
  const rootRef = useRef<HTMLElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const issuesTriggerRef = useRef<HTMLSpanElement>(null);
  const scrollPositions = useRef<Partial<Record<SettingsSection, number>>>({});
  const searchNavigation = useRef<SettingsSection | undefined>(undefined);
  const [modelFocusRequest, setModelFocusRequest] = useState<{
    providerId: string;
    modelId?: string;
    requestId: number;
  }>();
  const [mcpDraftDirty, setMcpDraftDirty] = useState(false);
  const [mcpBusy, setMcpBusy] = useState(false);
  const [skillsBusy, setSkillsBusy] = useState(false);
  const [pendingProjectId, setPendingProjectId] = useState<string>();
  const [projectTrustRequest, setProjectTrustRequest] = useState<ProjectTrustRequest>();
  const [projectTrustPending, setProjectTrustPending] = useState(false);
  const [projectTrustError, setProjectTrustError] = useState<string>();
  const projectTrustFailureFocusRef = useRef(false);
  const selectedProject = projects.find((project) => project.workspaceId === selectedProjectId);
  /** 未保存 MCP 草稿改变目标前要求明确放弃，普通筛选直接切换且不会改会话。 */
  const requestProjectSelection = (workspaceId: string): void => {
    if (workspaceId === selectedProjectId) return;
    if (mcpDraftDirty) setPendingProjectId(workspaceId);
    else onSelectProject?.(workspaceId);
  };
  /** 固定当前项目、目标 trust 与 callback，异步返回时不改写后来选择的项目。 */
  const requestProjectTrustChange = (): void => {
    if (selectedProject === undefined || onSetProjectTrust === undefined || disabled) return;
    projectTrustFailureFocusRef.current = false;
    setProjectTrustError(undefined);
    setProjectTrustRequest({
      workspaceId: selectedProject.workspaceId,
      displayName: selectedProject.displayName,
      rootPath: selectedProject.rootPath,
      trust: selectedProject.trust === "trusted" ? "untrusted" : "trusted",
      apply: onSetProjectTrust,
    });
  };
  /** 写入、权威投影和所选项目设置回读全部完成后才结束确认流程。 */
  const confirmProjectTrustChange = async (): Promise<void> => {
    if (projectTrustRequest === undefined || projectTrustPending) return;
    setProjectTrustPending(true);
    setProjectTrustError(undefined);
    try {
      await projectTrustRequest.apply(projectTrustRequest.workspaceId, projectTrustRequest.trust);
      setProjectTrustRequest(undefined);
    } catch {
      projectTrustFailureFocusRef.current = true;
      setProjectTrustError(
        "信任状态或项目设置尚未完成确认。修改可能已生效；可重试同一操作，或取消后重新读取实际状态。",
      );
    } finally {
      setProjectTrustPending(false);
    }
  };
  /** 忙态结束后只修复失焦或失效焦点；确认层内已有可操作焦点时尊重用户选择。 */
  useEffect(() => {
    if (projectTrustPending || !projectTrustFailureFocusRef.current) return;
    projectTrustFailureFocusRef.current = false;
    if (projectTrustRequest === undefined) return;
    const active = document.activeElement;
    const hasOperableFocus =
      active instanceof HTMLElement &&
      active.closest(".ja-settings-trust-dialog") !== null &&
      active.matches("button, input, select, textarea") &&
      !active.matches(":disabled");
    if (!hasOperableFocus) document.getElementById("project-trust-confirm")?.focus();
  }, [projectTrustPending, projectTrustRequest]);
  const projectPicker = (
    <CapabilityProjectPicker
      projects={projects}
      selectedProjectId={selectedProjectId}
      disabled={disabled || mcpBusy || skillsBusy}
      onSelectProject={requestProjectSelection}
    />
  );
  const projectUnavailableMessage =
    selectedProjectId === undefined
      ? "选择项目后管理项目能力"
      : projectLoading
        ? "正在读取项目设置…"
        : "项目未获信任或配置不可用";
  const notificationsAvailable = desktopNotifications !== undefined;
  const indexedResults = useMemo(
    () => settingsSearchResults(snapshot, notificationsAvailable),
    [snapshot, notificationsAvailable],
  );
  const results = useMemo(
    () =>
      query.trim() === ""
        ? []
        : indexedResults.filter((result) =>
            matchesQuery(query, `${result.label} ${result.detail} ${result.search}`),
          ),
    [indexedResults, query],
  );

  /** Settings 页面由 Shell 路由进入时接管已卸载来源的焦点；当前焦点仍在页内则保留用户位置。 */
  useEffect(() => {
    if (disabled) return;
    const root = rootRef.current;
    if (root === null || root.contains(document.activeElement)) return;
    root
      .querySelector<HTMLElement>('[role="tab"][aria-selected="true"]')
      ?.focus({ preventScroll: true });
  }, [disabled, section]);

  /** 分类各自保留阅读位置；切换在布局阶段恢复，避免先闪现上一页的深滚动位置。 */
  useLayoutEffect(() => {
    // 搜索拥有这一次定位，父级不能在子级聚焦后又恢复该分类的旧滚动位置。
    if (searchNavigation.current === section) {
      searchNavigation.current = undefined;
      return;
    }
    const viewport = rootRef.current?.querySelector<HTMLElement>(".ja-scroll-area-viewport");
    if (viewport !== undefined && viewport !== null) {
      viewport.scrollTop = scrollPositions.current[section] ?? 0;
    }
  }, [section]);

  /** 只保存当前分类的视口位置，避免搜索与侧栏导航使用两套位置规则。 */
  const rememberScroll = (): void => {
    scrollPositions.current[section] =
      rootRef.current?.querySelector<HTMLElement>(".ja-scroll-area-viewport")?.scrollTop ?? 0;
  };

  /** 只把合法分类提交给 Shell owner，保留离开前的位置而不重建表单草稿。 */
  const changeSection = (value: string): void => {
    rememberScroll();
    onSectionChange(value as SettingsSection);
  };

  /** 结果激活后才切换分类、定位并聚焦；系统或应用要求减少动效时禁止平滑位移。 */
  const openSearchResult = (result: SettingsSearchResult): void => {
    rememberScroll();
    searchNavigation.current = section === result.section ? undefined : result.section;
    onSectionChange(result.section);
    setQuery("");
    if (result.providerId !== undefined) {
      setModelFocusRequest((current) => ({
        providerId: result.providerId!,
        modelId: result.modelId,
        requestId: (current?.requestId ?? 0) + 1,
      }));
      return;
    }
    window.requestAnimationFrame(() => {
      const targets = Array.from(
        rootRef.current?.querySelectorAll<HTMLElement>("[data-setting-search]") ?? [],
      );
      const target = targets.find((candidate) => candidate.dataset["settingId"] === result.id);
      if (target === undefined) return;
      target.classList.add("is-search-match");
      const reducedMotion =
        snapshot.appearance.reducedMotion ||
        window.matchMedia?.("(prefers-reduced-motion: reduce)").matches === true;
      target.scrollIntoView({ block: "center", behavior: reducedMotion ? "auto" : "smooth" });
      const focusable = target.querySelector<HTMLElement>(
        "input, button, [role='switch'], [role='combobox'], textarea, select",
      );
      (focusable ?? target).focus({ preventScroll: true });
      window.setTimeout(() => target.classList.remove("is-search-match"), 1400);
    });
  };

  /**
   * 配置问题操作只等待对应的权威回读，成功后关闭 Sheet；失败时保留当前问题与焦点，不能以局部
   * 前端状态假装已恢复。按钮级 pending 避免阻断无关设置操作。
   */
  const runIssueAction = async (
    action: "retry" | "repair",
    operation: (() => Promise<void>) | undefined,
  ): Promise<void> => {
    if (operation === undefined || issueAction !== undefined) return;
    setIssueActionError(undefined);
    setIssueAction(action);
    try {
      await operation();
      setIssuesOpen(false);
      if (action === "repair") setRepairIssue(undefined);
    } catch {
      setIssueActionError(
        action === "repair"
          ? "修复失败，原文件仍保留，请稍后重试。"
          : "重新读取配置失败，请稍后重试。",
      );
    } finally {
      setIssueAction(undefined);
    }
  };

  /**
   * 运行中的修复或重试必须阻止 Radix 默认 Escape 关闭，使完成或失败事实仍留在当前上下文；
   * 非提交状态则由内容节点的键盘兜底与 Radix 关闭生命周期共同完成退出和焦点归还。
   */
  const handleIssuesEscape = (event: KeyboardEvent): void => {
    if (issueAction !== undefined) event.preventDefault();
  };

  /**
   * WebView2 的模态层偶尔会让 Radix 的 dismiss listener 看不到 Escape；内容节点捕获阶段
   * 仍属于当前焦点域，因此在非提交状态直接关闭，避免诊断 Sheet 成为无法键盘退出的死角。
   */
  const handleIssuesKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>): void => {
    if (event.key !== "Escape" || issueAction !== undefined) return;
    event.preventDefault();
    event.stopPropagation();
    setIssuesOpen(false);
  };

  return (
    <Dialog
      modal
      open={projectTrustRequest !== undefined}
      onOpenChange={(open) => {
        if (!open && !projectTrustPending) {
          projectTrustFailureFocusRef.current = false;
          setProjectTrustRequest(undefined);
          setProjectTrustError(undefined);
        }
      }}
    >
      <section
        ref={rootRef}
        className="ja-settings"
        aria-label="Ja 设置"
        aria-busy={disabled}
        inert={disabled}
      >
        <h1 className="ja-visually-hidden">Ja 设置</h1>
        <Tabs.Root
          className="ja-settings-tabs"
          value={section}
          onValueChange={changeSection}
          orientation="vertical"
        >
          <aside className="ja-settings-sidebar">
            {required || onReturnToApp === undefined ? null : (
              <Button className="ja-settings-return" variant="ghost" onClick={onReturnToApp}>
                <ArrowLeft aria-hidden="true" />
                返回应用
              </Button>
            )}
            <label className="ja-settings-search">
              <Search aria-hidden="true" />
              <span className="ja-visually-hidden">搜索设置</span>
              <input
                ref={searchRef}
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="搜索设置"
                aria-label="搜索设置"
                onKeyDown={(event) => {
                  if (event.key === "Escape") {
                    setQuery("");
                    return;
                  }
                  if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
                  const options = rootRef.current?.querySelectorAll<HTMLElement>("[role='option']");
                  if (!options?.length) return;
                  event.preventDefault();
                  options[event.key === "ArrowDown" ? 0 : options.length - 1]?.focus();
                }}
              />
              {query === "" ? null : (
                <IconButton
                  label="清空设置搜索"
                  onClick={() => {
                    setQuery("");
                    searchRef.current?.focus();
                  }}
                >
                  <X aria-hidden="true" />
                </IconButton>
              )}
            </label>
            {query.trim() === "" ? (
              <Tabs.List className="ja-settings-nav" aria-label="设置分类">
                {sections.map((item) => (
                  <Tabs.Trigger
                    key={item.id}
                    className="ja-settings-nav-item"
                    value={item.id}
                    data-group-start={item.id === "models" || item.id === "about" || undefined}
                  >
                    {item.icon}
                    <span>{item.label}</span>
                  </Tabs.Trigger>
                ))}
              </Tabs.List>
            ) : (
              <div
                className="ja-settings-search-results"
                role="listbox"
                aria-label="设置搜索结果"
                onKeyDown={(event) => {
                  if (event.key === "Escape") {
                    event.preventDefault();
                    setQuery("");
                    searchRef.current?.focus();
                    return;
                  }
                  if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
                  const options = Array.from(
                    event.currentTarget.querySelectorAll<HTMLButtonElement>("[role='option']"),
                  );
                  const index = options.indexOf(document.activeElement as HTMLButtonElement);
                  const next =
                    event.key === "Home"
                      ? 0
                      : event.key === "End"
                        ? options.length - 1
                        : index + (event.key === "ArrowDown" ? 1 : -1);
                  event.preventDefault();
                  if (next < 0) searchRef.current?.focus();
                  else options[Math.min(next, options.length - 1)]?.focus();
                }}
              >
                {results.map((result) => (
                  <button
                    key={result.id}
                    type="button"
                    role="option"
                    onClick={() => openSearchResult(result)}
                  >
                    <strong>{result.label}</strong>
                    <span>{result.detail}</span>
                  </button>
                ))}
              </div>
            )}
            {query.trim() !== "" && results.length === 0 ? (
              <div className="ja-settings-search-empty">
                <strong>没有匹配的设置</strong>
                <button
                  type="button"
                  onClick={() => {
                    setQuery("");
                    searchRef.current?.focus();
                  }}
                >
                  清空搜索
                </button>
              </div>
            ) : null}
          </aside>
          <div className="ja-settings-main">
            <header className="ja-settings-toolbar">
              <strong>设置</strong>
              <div className="ja-settings-scope-picker" role="group" aria-label="项目与配置范围">
                <span>应用范围</span>
                <Button
                  type="button"
                  size="sm"
                  variant={configScope === "user" ? "primary" : "ghost"}
                  aria-pressed={configScope === "user"}
                  disabled={disabled || onConfigScopeChange === undefined}
                  onClick={() => onConfigScopeChange?.("user")}
                >
                  全局
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant={configScope === "project" ? "primary" : "ghost"}
                  aria-pressed={configScope === "project"}
                  disabled={
                    disabled || onConfigScopeChange === undefined || !projectConfigAvailable
                  }
                  onClick={() => onConfigScopeChange?.("project")}
                >
                  当前项目
                </Button>
                {selectedProject === undefined ? null : (
                  <DialogTrigger asChild>
                    <IconButton
                      label={
                        selectedProject.trust === "trusted"
                          ? `撤销项目“${selectedProject.displayName}”的信任`
                          : `信任项目“${selectedProject.displayName}”`
                      }
                      tooltip={
                        selectedProject.trust === "trusted" ? "撤销项目的信任" : "信任此项目"
                      }
                      aria-haspopup="dialog"
                      aria-expanded={projectTrustRequest !== undefined}
                      disabled={disabled || projectTrustPending || onSetProjectTrust === undefined}
                      onClick={requestProjectTrustChange}
                    >
                      {selectedProject.trust === "trusted" ? (
                        <ShieldOff aria-hidden="true" />
                      ) : (
                        <ShieldCheck aria-hidden="true" />
                      )}
                    </IconButton>
                  </DialogTrigger>
                )}
              </div>
              <span className="ja-settings-scope-source">
                模型：{sourceLabel(sources["default_provider_id"])} · 权限：
                {sourceLabel(sources["default_access_mode"])}
              </span>
              <div className="ja-settings-toolbar-actions">
                {issues.length === 0 ? null : (
                  <span ref={issuesTriggerRef}>
                    <Button
                      type="button"
                      size="sm"
                      variant="ghost"
                      aria-haspopup="dialog"
                      aria-expanded={issuesOpen}
                      onClick={() => {
                        setIssueActionError(undefined);
                        setIssuesOpen(true);
                      }}
                    >
                      <CircleAlert aria-hidden="true" />
                      配置问题
                    </Button>
                  </span>
                )}
                <SettingsUpdateAction updater={updater} />
              </div>
            </header>
            <Dialog
              modal
              open={issuesOpen}
              onOpenChange={(open) => {
                if (!open && issueAction === undefined) setIssuesOpen(false);
              }}
            >
              <DialogContent
                className="ja-settings-sheet ja-settings-issues-sheet"
                overlayClassName="ja-settings-dialog-overlay"
                onKeyDownCapture={handleIssuesKeyDown}
                onEscapeKeyDown={handleIssuesEscape}
                onCloseAutoFocus={(event) => {
                  const trigger =
                    issuesTriggerRef.current?.querySelector<HTMLButtonElement>("button");
                  const fallback = trigger?.isConnected
                    ? trigger
                    : (rootRef.current?.querySelector<HTMLElement>(
                        '[role="tab"][aria-selected="true"]',
                      ) ?? searchRef.current);
                  if (!fallback?.isConnected) return;
                  event.preventDefault();
                  // 修复可能移除最后一个问题并卸载入口；此时把焦点交还当前分类，而不是 body。
                  fallback.focus({ preventScroll: true });
                }}
              >
                <div className="ja-settings-dialog-header">
                  <div>
                    <DialogTitle className="ja-settings-dialog-title">配置问题</DialogTitle>
                    <DialogDescription className="ja-settings-dialog-description">
                      未受影响的模型、项目和历史仍可继续使用。
                    </DialogDescription>
                  </div>
                  <DialogClose asChild>
                    <IconButton label="关闭配置问题" disabled={issueAction !== undefined}>
                      <X aria-hidden="true" />
                    </IconButton>
                  </DialogClose>
                </div>
                <div className="ja-settings-sheet-body ja-settings-issues-list">
                  {issues.map((issue) => (
                    <article key={issue.id} className="ja-settings-issue">
                      <div>
                        <strong>{issueTitle(issue)}</strong>
                        <p>{issueDescription(issue)}</p>
                      </div>
                      <div>
                        {issue.actions.includes("edit") ? (
                          <Button
                            type="button"
                            size="sm"
                            variant="secondary"
                            disabled={issueAction !== undefined}
                            onClick={() => {
                              setIssuesOpen(false);
                              onSectionChange(isMcpIssue(issue) ? "mcp" : "models");
                            }}
                          >
                            编辑
                          </Button>
                        ) : null}
                        {issue.actions.includes("retry") && onIssuesRetry !== undefined ? (
                          <Button
                            type="button"
                            size="sm"
                            variant="ghost"
                            loading={issueAction === "retry"}
                            disabled={issueAction !== undefined}
                            onClick={() => void runIssueAction("retry", onIssuesRetry)}
                          >
                            重试
                          </Button>
                        ) : null}
                        {issue.actions.includes("repair") && onIssuesRepair !== undefined ? (
                          <Button
                            type="button"
                            size="sm"
                            variant="secondary"
                            disabled={issueAction !== undefined}
                            onClick={() => setRepairIssue(issue)}
                          >
                            备份并修复
                          </Button>
                        ) : null}
                      </div>
                    </article>
                  ))}
                  {issueActionError === undefined ? null : (
                    <p className="ja-settings-error" role="alert">
                      {issueActionError}
                    </p>
                  )}
                </div>
              </DialogContent>
            </Dialog>
            <Dialog
              modal
              open={repairIssue !== undefined}
              onOpenChange={(open) => {
                if (!open && issueAction === undefined) setRepairIssue(undefined);
              }}
            >
              <DialogContent
                className="ja-settings-confirm-dialog ja-settings-repair-confirm-dialog"
                onEscapeKeyDown={(event) => {
                  if (issueAction !== undefined) event.preventDefault();
                }}
              >
                <div className="ja-settings-dialog-header">
                  <div>
                    <DialogTitle className="ja-settings-dialog-title">备份并修复配置？</DialogTitle>
                    <DialogDescription className="ja-settings-dialog-description">
                      {repairIssue?.reason === "UNSUPPORTED_SCHEMA_VERSION"
                        ? "Ja 会先备份当前原文件，再写入此版本可识别的配置；原文件内容保留在备份中。"
                        : "Ja 会先备份当前原文件，再保存可识别的配置。无法识别的尾部内容将从活动配置中移除。"}
                    </DialogDescription>
                  </div>
                </div>
                <div className="ja-settings-sheet-body">
                  <p>
                    {repairIssue?.scope === "project" ? "当前项目" : "全局"}配置中可识别的模型连接：
                    {snapshot.providers.length} 个；默认权限：
                    {snapshot.defaultAccessMode === "full_access" ? "完全访问" : "需要审批"}。
                  </p>
                  {repairIssue?.reason !== "TOML_PARSE_FAILED" ||
                  repairIssue.line === null ||
                  repairIssue.line === undefined ? null : (
                    <p>解析在第 {repairIssue.line} 行中断；该行之后的内容会保留在备份中。</p>
                  )}
                  {issueActionError === undefined ? null : (
                    <p className="ja-settings-error" role="alert">
                      {issueActionError}
                    </p>
                  )}
                  <div className="ja-settings-form-actions">
                    <Button
                      type="button"
                      variant="ghost"
                      disabled={issueAction !== undefined}
                      onClick={() => setRepairIssue(undefined)}
                    >
                      取消
                    </Button>
                    <Button
                      type="button"
                      variant="primary"
                      loading={issueAction === "repair"}
                      disabled={repairIssue === undefined || onIssuesRepair === undefined}
                      onClick={() => {
                        if (repairIssue === undefined || onIssuesRepair === undefined) return;
                        const scope = repairIssue.scope === "project" ? "project" : "user";
                        void runIssueAction("repair", () => onIssuesRepair(scope));
                      }}
                    >
                      备份并修复
                    </Button>
                  </div>
                </div>
              </DialogContent>
            </Dialog>
            <ScrollArea className="ja-settings-content">
              <Tabs.Content forceMount value="general" className="ja-settings-panel">
                {configScope === "project" &&
                projectOverrides?.clarification === true &&
                ports.onInheritProjectOverride !== undefined ? (
                  <InheritProjectOverrideButton
                    onClick={() =>
                      ports.onInheritProjectOverride?.("clarification") ?? Promise.resolve()
                    }
                  />
                ) : null}
                <GeneralSection
                  desktopNotifications={desktopNotifications}
                  desktop={desktop}
                  interfacePreferences={interfacePreferences}
                  clarificationEnabled={snapshot.clarificationEnabled ?? true}
                  onClarificationEnabledChange={ports.onClarificationEnabledChange}
                />
              </Tabs.Content>
              <Tabs.Content forceMount value="models" className="ja-settings-panel">
                {configScope === "project" ? (
                  <>
                    <p className="ja-settings-scope-note">
                      Provider 连接与凭据保存在全局；模型目录、默认模型和普通运行策略可按项目覆盖。
                    </p>
                    <ProjectProviderRuntimeSection
                      providers={snapshot.providers}
                      overrides={projectOverrides?.providerRuntime ?? []}
                      onSave={ports.onProjectProviderRuntimeChange}
                      onInherit={ports.onInheritProjectProvider}
                    />
                    <ProjectProviderConnectionSection
                      providers={snapshot.providers}
                      credentials={globalProviders}
                      overrides={projectOverrides?.providerRuntime ?? []}
                      onSave={ports.onProjectProviderConnectionChange}
                      onInherit={ports.onInheritProjectProviderConnection}
                    />
                  </>
                ) : null}
                {configScope === "project" &&
                projectOverrides?.defaultSelection === true &&
                ports.onInheritProjectOverride !== undefined ? (
                  <InheritProjectOverrideButton
                    onClick={() =>
                      ports.onInheritProjectOverride?.("defaultSelection") ?? Promise.resolve()
                    }
                  />
                ) : null}
                <ModelsSection
                  providers={snapshot.providers}
                  globalProviders={globalProviders}
                  defaultSelection={snapshot.defaultSelection}
                  snapshotRevision={snapshot.revision}
                  ports={ports}
                  projectScope={configScope === "project"}
                  projectProviderOverrides={projectOverrides?.providerRuntime ?? []}
                  focusRequest={modelFocusRequest}
                />
              </Tabs.Content>
              <Tabs.Content forceMount value="subagents" className="ja-settings-panel">
                {configScope === "project" &&
                projectOverrides?.subagents === true &&
                ports.onInheritProjectOverride !== undefined ? (
                  <InheritProjectOverrideButton
                    onClick={() =>
                      ports.onInheritProjectOverride?.("subagents") ?? Promise.resolve()
                    }
                  />
                ) : null}
                <SubagentsSection
                  settings={snapshot.subagents}
                  providers={snapshot.providers}
                  onChange={ports.onSubagentSettingsChange}
                  onOpenModels={() => onSectionChange("models")}
                />
              </Tabs.Content>
              <Tabs.Content forceMount value="skills" className="ja-settings-panel">
                <SkillsSection
                  globalSkills={skillSettings.global}
                  projectSkills={skillSettings.project}
                  projectAvailable={skillSettings.projectAvailable}
                  projectUnavailableMessage={projectUnavailableMessage}
                  projectPicker={projectPicker}
                  disabled={disabled}
                  onBusyChange={setSkillsBusy}
                  onToggleSkill={ports.onToggleSkill}
                />
              </Tabs.Content>
              <Tabs.Content forceMount value="mcp" className="ja-settings-panel">
                <McpSection
                  servers={mcpSettings.global}
                  projectServers={mcpSettings.project}
                  projectAvailable={mcpSettings.projectAvailable}
                  projectUnavailableMessage={projectUnavailableMessage}
                  projectPicker={projectPicker}
                  onDraftStateChange={setMcpDraftDirty}
                  onBusyChange={setMcpBusy}
                  projectWorkspaceId={selectedProjectId}
                  projectName={
                    projects.find((project) => project.workspaceId === selectedProjectId)
                      ?.displayName
                  }
                  snapshotRevision={snapshot.revision}
                  onSaveMcp={ports.onSaveMcp}
                  onDeleteMcp={ports.onDeleteMcp}
                  onTestMcp={ports.onTestMcp}
                  onReplaceCredential={ports.onReplaceCredential}
                  onClearCredential={ports.onClearCredential}
                />
              </Tabs.Content>
              <Tabs.Content forceMount value="permissions" className="ja-settings-panel">
                {configScope === "project" &&
                projectOverrides?.accessMode === true &&
                ports.onInheritProjectOverride !== undefined ? (
                  <InheritProjectOverrideButton
                    onClick={() =>
                      ports.onInheritProjectOverride?.("accessMode") ?? Promise.resolve()
                    }
                  />
                ) : null}
                <PermissionsSection
                  mode={snapshot.defaultAccessMode}
                  scope={executionScope}
                  onChange={ports.onAccessModeChange}
                />
              </Tabs.Content>
              <Tabs.Content forceMount value="appearance" className="ja-settings-panel">
                <AppearanceSection
                  appearance={snapshot.appearance}
                  onChange={ports.onAppearanceChange}
                  interfacePreferences={interfacePreferences}
                />
              </Tabs.Content>
              <Tabs.Content forceMount value="about" className="ja-settings-panel">
                <AboutSection updater={updater} openExternalUrl={desktop.openExternalUrl} />
              </Tabs.Content>
              <Tabs.Content forceMount value="storage" className="ja-settings-panel">
                <DataStorageSection {...dataStorage} />
              </Tabs.Content>
            </ScrollArea>
          </div>
        </Tabs.Root>
        <Dialog
          modal
          open={pendingProjectId !== undefined}
          onOpenChange={(open) => {
            if (!open) setPendingProjectId(undefined);
          }}
        >
          <DialogContent
            className="ja-settings-dialog"
            aria-describedby="ja-project-switch-description"
          >
            <DialogTitle>切换设置项目</DialogTitle>
            <DialogDescription id="ja-project-switch-description">
              MCP 编辑中有未保存的修改。切换项目会放弃这份草稿。
            </DialogDescription>
            <div className="ja-settings-dialog-actions">
              <Button
                type="button"
                variant="secondary"
                onClick={() => setPendingProjectId(undefined)}
              >
                继续编辑
              </Button>
              <Button
                type="button"
                onClick={() => {
                  if (pendingProjectId !== undefined) onSelectProject?.(pendingProjectId);
                  setPendingProjectId(undefined);
                }}
              >
                放弃草稿并切换
              </Button>
            </div>
          </DialogContent>
        </Dialog>
        <DialogContent
          className="ja-settings-dialog ja-settings-trust-dialog"
          aria-describedby="ja-project-trust-description"
          onEscapeKeyDown={(event) => {
            if (projectTrustPending) event.preventDefault();
          }}
        >
          <div className="ja-settings-dialog-header">
            <DialogTitle className="ja-settings-dialog-title">
              {projectTrustRequest?.trust === "trusted" ? "信任此项目？" : "撤销项目的信任？"}
            </DialogTitle>
            <DialogDescription
              id="ja-project-trust-description"
              className="ja-settings-dialog-description"
            >
              {projectTrustRequest?.trust === "trusted"
                ? "信任后，Ja 会读取项目配置并启用项目 Skills 和 MCP。只信任你认可的目录。"
                : "撤销后，项目配置和 Skills 不再生效，后续项目 MCP 调用将被拒绝；运行中的 Turn 保持其已冻结的工具目录。"}
            </DialogDescription>
            {projectTrustRequest === undefined ? null : (
              <code className="ja-settings-trust-path">{projectTrustRequest.rootPath}</code>
            )}
          </div>
          {projectTrustError === undefined ? null : (
            <p className="ja-settings-error" role="alert">
              {projectTrustError}
            </p>
          )}
          <div className="ja-settings-dialog-actions">
            <Button
              type="button"
              variant="secondary"
              disabled={projectTrustPending}
              onClick={() => {
                setProjectTrustRequest(undefined);
                setProjectTrustError(undefined);
              }}
            >
              取消
            </Button>
            <Button
              id="project-trust-confirm"
              type="button"
              variant={projectTrustRequest?.trust === "untrusted" ? "danger" : "primary"}
              loading={projectTrustPending}
              onClick={() => void confirmProjectTrustChange()}
            >
              {projectTrustRequest?.trust === "trusted" ? "信任项目" : "撤销信任"}
            </Button>
          </div>
        </DialogContent>
      </section>
    </Dialog>
  );
}
