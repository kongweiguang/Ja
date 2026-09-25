// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import * as Tabs from "@radix-ui/react-tabs";
import { ArrowLeft, CircleAlert, Search, X } from "lucide-react";
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
  IconButton,
  ScrollArea,
} from "@/shared/ui/primitives";
import type { ConfigurationIssue, SettingsSection, SettingsSnapshot } from "../domain/types";
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
import { sections } from "./shared";
import { SkillsSection } from "./skills";
import { AboutSection, SettingsUpdateAction } from "./about";
import { useAppUpdater } from "../application/useAppUpdater";

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
      detail: snapshot.defaultAccessMode === "full_access" ? "全部执行" : "需要确认",
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
  if (issue.impact === "snapshot_in_use") return "正在使用上次可用设置。";
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

/**
 * 以 Codex 风格的紧凑分类布局承载设置内容；页面标题和恢复提示由外层
 * SettingsView 统一提供，避免同一屏重复渲染两套大标题，同时保持 snapshot
 * 和 ports 仍是唯一数据与副作用边界。
 */
export interface SettingsProps {
  snapshot: SettingsSnapshot;
  projects?: readonly WorkspaceProjection[];
  selectedProjectId?: string;
  onSelectProject?: (workspaceId: string) => void;
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
  onIssuesRestore?: () => Promise<void>;
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
 */
export function Settings({
  snapshot,
  projects = [],
  selectedProjectId,
  onSelectProject,
  projectLoading = false,
  skillSettings = { global: snapshot.skills, projectAvailable: false },
  mcpSettings = { global: snapshot.mcpServers, projectAvailable: false },
  issues = [],
  onIssuesRetry,
  onIssuesRestore,
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
  const [issueAction, setIssueAction] = useState<"retry" | "restore">();
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
  /** 未保存 MCP 草稿改变目标前要求明确放弃，普通筛选直接切换且不会改会话。 */
  const requestProjectSelection = (workspaceId: string): void => {
    if (workspaceId === selectedProjectId) return;
    if (mcpDraftDirty) setPendingProjectId(workspaceId);
    else onSelectProject?.(workspaceId);
  };
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
  const usingLastKnownGood = issues.some((issue) => issue.impact === "snapshot_in_use");
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
    action: "retry" | "restore",
    operation: (() => Promise<void>) | undefined,
  ): Promise<void> => {
    if (operation === undefined || issueAction !== undefined) return;
    setIssueActionError(undefined);
    setIssueAction(action);
    try {
      await operation();
      setIssuesOpen(false);
    } catch {
      setIssueActionError(
        action === "restore" ? "恢复失败，请稍后重试。" : "重新读取配置失败，请稍后重试。",
      );
    } finally {
      setIssueAction(undefined);
    }
  };

  /**
   * 运行中的恢复或重试必须阻止 Radix 默认 Escape 关闭，使完成或失败事实仍留在当前上下文；
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
          {usingLastKnownGood ? (
            <div className="ja-settings-configuration-notice" role="status">
              <span>配置有一处格式问题，正在使用上次可用设置。</span>
              <Button type="button" size="sm" variant="ghost" onClick={() => setIssuesOpen(true)}>
                查看问题
              </Button>
            </div>
          ) : null}
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
                if (trigger?.isConnected) {
                  event.preventDefault();
                  // Radix 已完成焦点域释放；同步归还可避免下一帧被设置页重渲染改写为 body。
                  trigger.focus({ preventScroll: true });
                }
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
                      {issue.actions.includes("restore") && onIssuesRestore !== undefined ? (
                        <Button
                          type="button"
                          size="sm"
                          variant="secondary"
                          loading={issueAction === "restore"}
                          disabled={issueAction !== undefined}
                          onClick={() => void runIssueAction("restore", onIssuesRestore)}
                        >
                          恢复
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
          <ScrollArea className="ja-settings-content">
            <Tabs.Content forceMount value="general" className="ja-settings-panel">
              <GeneralSection
                desktopNotifications={desktopNotifications}
                desktop={desktop}
                interfacePreferences={interfacePreferences}
                clarificationEnabled={snapshot.clarificationEnabled ?? true}
                onClarificationEnabledChange={ports.onClarificationEnabledChange}
              />
            </Tabs.Content>
            <Tabs.Content forceMount value="models" className="ja-settings-panel">
              <ModelsSection
                providers={snapshot.providers}
                defaultSelection={snapshot.defaultSelection}
                snapshotRevision={snapshot.revision}
                ports={ports}
                focusRequest={modelFocusRequest}
              />
            </Tabs.Content>
            <Tabs.Content forceMount value="subagents" className="ja-settings-panel">
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
                  projects.find((project) => project.workspaceId === selectedProjectId)?.displayName
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
    </section>
  );
}
