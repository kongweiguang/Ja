// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
/* eslint-disable react-refresh/only-export-components */

import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { Settings } from "@/features/settings";
import type {
  SettingsDesktopPort,
  SettingsInterfacePreferences,
  SettingsPorts,
  SettingsSection,
  SettingsSnapshot,
} from "@/features/settings";
import { applyTheme } from "@/shared/styles/theme";
import "@/shared/styles/tokens.css";
import "@/shared/styles/primitives.css";
import "@/app/App.css";

/**
 * 预览数据覆盖设置页真实状态面，使用长名称、错误信息、空分组和真实来源，
 * 让浏览器矩阵可以发现布局与恢复路径问题，而不是只验证理想的静态卡片。
 */
const SETTINGS_STYLE_SNAPSHOT: SettingsSnapshot = {
  revision: 17,
  defaultSelection: { providerId: "provider_local", modelId: "model_sol", reasoningLevel: "high" },
  subagents: {
    enabled: true,
    providerId: "provider_local",
    modelId: "model_mini",
    reasoningLevel: null,
  },
  providers: [
    {
      providerId: "provider_local",
      name: "local-cockpit · 长名称验收 Provider",
      api: "openai_responses",
      baseUrl: "http://127.0.0.1:8765/v1/very-long-test-endpoint",
      credentialId: "cred_local_preview",
      credentialConfigured: true,
      networkTimeouts: { connectTimeoutMs: 10000, requestTimeoutMs: 120000 },
      agentDefaults: {
        context: { autoCompact: true },
        turnLimits: { maxModelRounds: 32, maxToolCalls: 128, wallTimeoutMs: 3600000 },
      },
      models: [
        {
          modelId: "model_sol",
          name: "GPT 5.6 Sol · 当前默认",
          model: "gpt-5.6-sol",
          capabilities: { contextWindowTokens: 256000, maxOutputTokens: 32000 },
          reasoningLevelMap: { low: "low", medium: "medium", high: "high" },
          defaultReasoningLevel: "high",
        },
        {
          modelId: "model_mini",
          name: "GPT 5.6 Mini · 快速回复",
          model: "gpt-5.6-mini",
          capabilities: { contextWindowTokens: 128000, maxOutputTokens: 8192 },
          reasoningLevelMap: {},
          defaultReasoningLevel: null,
        },
      ],
    },
    {
      providerId: "provider_deepseek",
      name: "DeepSeek",
      api: "openai_chat_completions",
      baseUrl: "https://api.deepseek.com/v1",
      credentialId: "cred_deepseek_preview",
      credentialConfigured: false,
      networkTimeouts: { connectTimeoutMs: 10000, requestTimeoutMs: 120000 },
      agentDefaults: {
        context: { autoCompact: true },
        turnLimits: { maxModelRounds: 16, maxToolCalls: 64, wallTimeoutMs: 1800000 },
      },
      models: [],
    },
  ],
  skills: [
    {
      id: "skill_builtin_review",
      name: "code-review",
      source: "builtin",
      description: "审查代码变更，检查错误、边界条件和测试覆盖。",
      enabled: true,
      status: "ready",
    },
    {
      id: "skill_user_long",
      name: "release-checklist-with-a-very-long-name",
      source: "user",
      description: "验证发布前的构建、测试、产物和回滚条件。",
      enabled: false,
      status: "disabled",
    },
    {
      id: "skill_ja_tools",
      name: "ja-tools",
      source: "ja",
      description: "Ja 内置工具和桌面工作流能力。",
      enabled: true,
      status: "ready",
    },
    {
      id: "skill_project_rules",
      name: "project-rules",
      source: "project",
      description: "当前工作区的项目约束和团队规则。",
      enabled: true,
      status: "error",
      error: "本次预览故意保留的恢复状态：规则加载失败，可重试。",
    },
  ],
  mcpServers: [
    {
      id: "mcp_files",
      mcpRevision: "mcp_files_r1",
      name: "本地文件工具",
      transport: "stdio",
      endpoint: "node ./tools/files-server.mjs --workspace",
      args: ["./tools/files-server.mjs", "--workspace"],
      env: {},
      headers: {},
      auth: { kind: "none" },
      enabled: true,
      protocolVersion: "2025-06-18",
      status: "connected",
      tools: [
        { name: "list_files", policy: "allow" },
        { name: "read_file", policy: "ask" },
      ],
    },
    {
      id: "mcp_remote",
      mcpRevision: "mcp_remote_r3",
      name: "远程知识库（连接失败）",
      transport: "streamable_http",
      endpoint: "https://127.0.0.1:9443/mcp/very-long-resource-path",
      args: [],
      env: {},
      headers: { "X-Preview": "true" },
      auth: { kind: "bearer", credentialRef: "cred_mcp_preview" },
      enabled: true,
      protocolVersion: "2025-03-26",
      status: "error",
      tools: [],
      lastError: "连接失败：预览 fixture 不会启动外部服务。可编辑后重试。",
    },
  ],
  defaultAccessMode: "approval_required",
  clarificationEnabled: true,
  appearance: {
    theme: "system",
    palette: "ja",
    reducedMotion: false,
    reducedTransparency: false,
    highContrast: false,
  },
};

/** 空态 fixture 保留生产 Settings 的引导路径，验证首次配置不会重复渲染新增入口。 */
const EMPTY_SETTINGS_STYLE_SNAPSHOT: SettingsSnapshot = {
  ...SETTINGS_STYLE_SNAPSHOT,
  defaultSelection: null,
  subagents: { enabled: false, providerId: null, modelId: null, reasoningLevel: null },
  providers: [],
  skills: [],
  mcpServers: [],
};

/** 返回不会触碰宿主或网络的设置动作，失败态由脚本通过真实页面反馈验证。 */
function createPreviewPorts(): SettingsPorts {
  return {
    onCreateProvider: async () => undefined,
    onSaveProvider: async () => undefined,
    onDeleteProvider: async () => undefined,
    onMoveProvider: async () => undefined,
    onSaveModel: async () => undefined,
    onTestModel: async () => ({ responseModel: "preview-model", latencyMs: 18 }),
    onDeleteModel: async () => undefined,
    onMoveModel: async () => undefined,
    onDefaultSelectionChange: async () => undefined,
    onSubagentSettingsChange: async () => undefined,
    onReplaceCredential: async () => undefined,
    onClearCredential: async () => undefined,
    onSaveMcp: async () => undefined,
    onDeleteMcp: async () => undefined,
    onTestMcp: async () => "error",
    onCloseMcp: async () => undefined,
    onToggleSkill: async () => undefined,
    onAccessModeChange: async () => undefined,
    onClarificationEnabledChange: async () => undefined,
    onAppearanceChange: async () => undefined,
  };
}

/** 桌面端口保持更新、外链和关闭行为均为本地成功结果，阻止浏览器预览产生副作用。 */
function createPreviewDesktop(): SettingsDesktopPort {
  return {
    readCloseBehavior: async () => "background",
    saveCloseBehavior: async () => undefined,
    openExternalUrl: async () => undefined,
    checkForUpdate: async () => ({ kind: "up-to-date" }),
    installUpdate: async () => undefined,
    relaunchAfterUpdate: async () => undefined,
  };
}

const interfacePreferences: SettingsInterfacePreferences = {
  sendShortcut: "enter",
  uiFontSize: 16,
  codeFontSize: 13,
  onChange: async () => undefined,
};

/** 预览 shell 复现 Tauri 根布局的全高 flex 约束，避免父容器剩余空间伪装成产品留白。 */
function installPreviewShellStyles(): void {
  const style = document.createElement("style");
  style.textContent = `
    html, body, #root { width: 100%; height: 100%; min-height: 100%; }
    html, body { margin: 0; }
    body { display: flex; overflow: hidden; }
    #root { display: flex; min-width: 0; min-height: 0; flex: 1 1 auto; }
  `;
  document.head.appendChild(style);
}

/** 复用生产主题原子投影与系统媒体事实，避免预览用另一套 data-theme 逻辑掩盖真实问题。 */
function PreviewThemeBridge({ children }: { children: React.ReactNode }): React.ReactElement {
  const [prefersDark, setPrefersDark] = useState(
    () => window.matchMedia("(prefers-color-scheme: dark)").matches,
  );

  useEffect(() => {
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const update = (): void => setPrefersDark(media.matches);
    update();
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);

  useEffect(() => {
    applyTheme(document.documentElement, {
      mode: "system",
      palette: "ja",
      highContrast: false,
      reduceMotion: false,
      reducedTransparency: false,
      prefersDark,
    });
  }, [prefersDark]);

  return <>{children}</>;
}

/** 受控分类保留真实导航行为，支持脚本逐分类截图、键盘切换和搜索定位。 */
function SettingsStylePreview(): React.ReactElement {
  const [section, setSection] = useState<SettingsSection>("general");
  const isEmptyState = new URLSearchParams(window.location.search).get("state") === "empty";
  return (
    <PreviewThemeBridge>
      <Settings
        snapshot={isEmptyState ? EMPTY_SETTINGS_STYLE_SNAPSHOT : SETTINGS_STYLE_SNAPSHOT}
        interfacePreferences={interfacePreferences}
        executionScope={{
          scopedDefault: "approval_required",
          projectOverride: true,
          scopeReady: true,
        }}
        ports={createPreviewPorts()}
        desktop={createPreviewDesktop()}
        section={section}
        onSectionChange={setSection}
        onReturnToApp={() => undefined}
        desktopNotifications={{ enabled: true, onChange: async () => true }}
      />
    </PreviewThemeBridge>
  );
}

const root = document.getElementById("root");
if (root === null) throw new Error("settings style preview root is missing");
installPreviewShellStyles();
createRoot(root).render(<SettingsStylePreview />);
