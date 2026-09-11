// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import * as Tabs from "@radix-ui/react-tabs";
import { ArrowLeft, Search, X } from "lucide-react";
import { useLayoutEffect, useMemo, useRef, useState } from "react";
import { Button, IconButton, ScrollArea } from "@/shared/ui/primitives";
import type { SettingsSection, SettingsSnapshot } from "../domain/types";
import type { SettingsDesktopPort, SettingsPorts } from "../application/ports";
import type { SettingsInterfacePreferences } from "../application/ports";
import type { ExecutionScope } from "../domain/executionScope";
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
 * 以 Codex 风格的紧凑分类布局承载设置内容；页面标题和恢复提示由外层
 * SettingsView 统一提供，避免同一屏重复渲染两套大标题，同时保持 snapshot
 * 和 ports 仍是唯一数据与副作用边界。
 */
export interface SettingsProps {
  snapshot: SettingsSnapshot;
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
  const rootRef = useRef<HTMLElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const scrollPositions = useRef<Partial<Record<SettingsSection, number>>>({});
  const searchNavigation = useRef<SettingsSection | undefined>(undefined);
  const [modelFocusRequest, setModelFocusRequest] = useState<{
    providerId: string;
    modelId?: string;
    requestId: number;
  }>();
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
            <SettingsUpdateAction updater={updater} />
          </header>
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
              <SkillsSection skills={snapshot.skills} onToggleSkill={ports.onToggleSkill} />
            </Tabs.Content>
            <Tabs.Content forceMount value="mcp" className="ja-settings-panel">
              <McpSection
                servers={snapshot.mcpServers}
                snapshotRevision={snapshot.revision}
                onSaveMcp={ports.onSaveMcp}
                onDeleteMcp={ports.onDeleteMcp}
                onTestMcp={ports.onTestMcp}
                onCloseMcp={ports.onCloseMcp}
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
    </section>
  );
}
