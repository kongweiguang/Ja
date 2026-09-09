// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import * as Tabs from "@radix-ui/react-tabs";
import { ArrowLeft, Search, X } from "lucide-react";
import { useMemo, useState } from "react";
import { Button, IconButton, ScrollArea } from "@/shared/ui/primitives";
import type { SettingsSection, SettingsSnapshot } from "../domain/types";
import type { SettingsDesktopPort, SettingsPorts } from "../application/ports";
import { ModelsSection } from "./models";
import { McpSection } from "./mcp";
import {
  PermissionsSection,
  AppearanceSection,
  type DesktopNotificationPreference,
} from "./sections";
import { sections } from "./shared";
import { SkillsSection } from "./skills";
import { AboutSection, SettingsUpdateAction } from "./about";
import { useAppUpdater } from "../application/useAppUpdater";
import "./settings.css";

const sectionKeywords: Record<SettingsSection, string> = {
  models: "模型 服务商 provider api api key 明文 密钥 协议 地址 base url anthropic openai 活动",
  skills: "skills 技能 指令 内置 用户 ja 项目 启用",
  mcp: "mcp 工具 server transport stdio http endpoint credential ref 协议版本 oauth",
  permissions: "权限 沙箱 access 安全 只读 工作区 完全访问 命令",
  appearance: "外观 主题 深色 浅色 系统 动效 对比度 xcode 桌面通知 后台 完成 失败 确认",
  about: "关于 Ja GitHub 开源 协议 GPL 版本 更新 软件更新",
};

interface SettingsSearchResult {
  id: string;
  section: SettingsSection;
  label: string;
  detail: string;
  search: string;
}

/**
 * 搜索索引只包含页面真实可见的设置项与脱敏投影；每条结果保留所属分类和定位词，
 * 点击后再导航与聚焦，避免输入期间抢走搜索框焦点。
 */
function settingsSearchResults(snapshot: SettingsSnapshot): SettingsSearchResult[] {
  return [
    ...snapshot.providers.flatMap((provider) => [
      {
        id: `provider-${provider.providerId}`,
        section: "models" as const,
        label: provider.name,
        detail: `${provider.api} · ${provider.models.length} 个模型`,
        search: `${provider.name} ${provider.api} ${provider.baseUrl}`,
      },
      ...provider.models.map((model) => ({
        id: `model-${provider.providerId}-${model.modelId}`,
        section: "models" as const,
        label: model.name,
        detail: `${provider.name} · ${model.model}`,
        search: `${model.name} ${model.model} 上下文 输出 思考 ${Object.keys(model.reasoningLevelMap).join(" ")}`,
      })),
    ]),
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
      id: "appearance-theme",
      section: "appearance" as const,
      label: "主题与显示",
      detail: "主题、动效、对比度与桌面通知",
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
 * Category 由 Desktop Shell 唯一持有，Settings 仅投影受控 section，避免独立/受控双轨。
 */
export function Settings({
  snapshot,
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
  const indexedResults = useMemo(() => settingsSearchResults(snapshot), [snapshot]);
  const results = useMemo(
    () =>
      query.trim() === ""
        ? []
        : indexedResults.filter((result) =>
            matchesQuery(query, `${result.label} ${result.detail} ${result.search}`),
          ),
    [indexedResults, query],
  );

  /** 只把合法分类提交给 Shell owner，Settings 内不复制第二份路由状态。 */
  const changeSection = (value: string): void => {
    onSectionChange(value as SettingsSection);
  };

  /** 结果激活后才切换分类、定位并聚焦；系统或应用要求减少动效时禁止平滑位移。 */
  const openSearchResult = (result: SettingsSearchResult): void => {
    onSectionChange(result.section);
    setQuery("");
    window.requestAnimationFrame(() => {
      const targets = Array.from(document.querySelectorAll<HTMLElement>("[data-setting-search]"));
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
    <section className="ja-settings" aria-label="Ja 设置" aria-busy={disabled} inert={disabled}>
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
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="搜索设置"
              aria-label="搜索设置"
            />
            {query === "" ? null : (
              <IconButton label="清空设置搜索" onClick={() => setQuery("")}>
                <X aria-hidden="true" />
              </IconButton>
            )}
          </label>
          {query.trim() === "" ? (
            <Tabs.List className="ja-settings-nav" aria-label="设置分类">
              {sections.map((item) => (
                <Tabs.Trigger key={item.id} className="ja-settings-nav-item" value={item.id}>
                  {item.icon}
                  <span>{item.label}</span>
                </Tabs.Trigger>
              ))}
            </Tabs.List>
          ) : (
            <div className="ja-settings-search-results" role="listbox" aria-label="设置搜索结果">
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
              <button type="button" onClick={() => setQuery("")}>
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
            <Tabs.Content forceMount value="models" className="ja-settings-panel">
              <ModelsSection
                providers={snapshot.providers}
                defaultSelection={snapshot.defaultSelection}
                snapshotRevision={snapshot.revision}
                ports={ports}
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
                onChange={ports.onAccessModeChange}
              />
            </Tabs.Content>
            <Tabs.Content forceMount value="appearance" className="ja-settings-panel">
              <AppearanceSection
                appearance={snapshot.appearance}
                onChange={ports.onAppearanceChange}
                desktopNotifications={desktopNotifications}
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
