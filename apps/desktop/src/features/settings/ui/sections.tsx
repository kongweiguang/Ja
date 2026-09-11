// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useState, type CSSProperties } from "react";
import { toast } from "sonner";
import { Button } from "@/shared/ui/primitives";
import {
  UI_PALETTE_LABELS,
  UI_PALETTE_ORDER,
  type AppearanceSettings,
  type AccessMode,
  type ThemeMode,
  type UiPalette,
} from "../domain/types";
import type { SettingsPorts } from "../application/ports";
import type { SettingsDesktopPort } from "../application/ports";
import type { SettingsInterfacePreferences } from "../application/ports";
import { SendShortcutField, TypographyFields } from "./InterfacePreferencesFields";
import { WindowCloseField } from "./WindowCloseField";
import { ExecutionScopeDetails } from "./ExecutionScopeDetails";
import type { ExecutionScope } from "../domain/executionScope";
import {
  Field,
  SectionHeader,
  settingsMutationErrorMessage,
  SettingsGroup,
  SettingsSelect,
  SwitchField,
  themeOptions,
} from "./shared";

export interface DesktopNotificationPreference {
  enabled: boolean;
  onChange: (enabled: boolean) => Promise<boolean>;
}

const PALETTE_SWATCHES: Readonly<Record<UiPalette, readonly [string, string, string]>> = {
  xcode: ["#f5f5f5", "#292a30", "#007aff"],
  ja: ["#f2f2f2", "#18191b", "#726cf9"],
  jetbrains: ["#e9eaee", "#191a1c", "#3871e1"],
  obsidian: ["#f6f6f6", "#242424", "#9873f7"],
  claude: ["#f5f4ed", "#1a1918", "#d97757"],
};

/**
 * Palette 选项用可见名称承载语义，三个色点仅帮助快速辨认且从无障碍树隐藏，
 * 避免屏幕阅读器重复朗读无法表达业务含义的颜色名称。
 */
function PaletteOptionLabel({ palette }: { palette: UiPalette }): React.ReactElement {
  return (
    <span className="ja-settings-palette-option">
      <span>{UI_PALETTE_LABELS[palette]}</span>
      <span className="ja-settings-palette-swatches" aria-hidden="true">
        {PALETTE_SWATCHES[palette].map((color) => (
          <span
            className="ja-settings-palette-swatch"
            key={color}
            style={{ "--ja-palette-swatch": color } as CSSProperties}
          />
        ))}
      </span>
    </span>
  );
}

/** 从偏好闭集派生下拉顺序，新增 Palette 时不会在设置页留下不可达状态。 */
const paletteOptions = UI_PALETTE_ORDER.map((palette) => ({
  value: palette,
  label: <PaletteOptionLabel palette={palette} />,
}));

/** 只展示协议真正支持的两档权限，避免 UI 重新引入策略语言。 */
export function PermissionsSection({
  mode: initialMode,
  onChange,
  scope,
}: {
  mode: AccessMode;
  onChange: SettingsPorts["onAccessModeChange"];
  scope: ExecutionScope;
}): React.ReactElement {
  const mode = initialMode;
  const [pending, setPending] = useState(false);
  const [failedMode, setFailedMode] = useState<AccessMode>();
  const [feedback, setFeedback] = useState<string>();
  const choices: ReadonlyArray<{ value: AccessMode; label: string; description: string }> = [
    {
      value: "full_access",
      label: "全部执行（默认）",
      description: "所有内置 Tool 和 MCP Tool 直接执行，不逐次确认。",
    },
    {
      value: "approval_required",
      label: "需要确认",
      description: "所有内置 Tool 和 MCP Tool 每次执行都请求批准。",
    },
  ];

  /** 直接等待 Java owner 保存后由父级快照刷新，避免局部乐观状态与配置代际分叉。 */
  const change = async (next: AccessMode): Promise<void> => {
    if (pending || next === mode) return;
    setPending(true);
    setFailedMode(undefined);
    setFeedback(undefined);
    try {
      await onChange(next);
      toast.success("权限模式已保存");
    } catch (error) {
      const message = settingsMutationErrorMessage(error, "权限模式保存失败，请重试。");
      setFailedMode(next);
      setFeedback(message);
      toast.error(message);
    } finally {
      setPending(false);
    }
  };

  return (
    <div
      className="ja-settings-section"
      data-setting-id="permission-mode"
      data-setting-search="执行确认 需要确认 全部执行"
      tabIndex={-1}
    >
      <SectionHeader title="执行确认" />
      <SettingsGroup title="工具执行方式">
        <fieldset
          className="ja-settings-permission-group"
          aria-label="工具执行方式"
          aria-busy={pending}
        >
          {choices.map((choice) => (
            <label
              className={`ja-settings-permission-card${mode === choice.value ? " is-selected" : ""}`}
              data-setting-search={`${choice.label} ${choice.description} ${choice.value}`}
              tabIndex={-1}
              key={choice.value}
            >
              <input
                type="radio"
                name="ja-permission-mode"
                value={choice.value}
                checked={mode === choice.value}
                disabled={pending}
                onChange={() => void change(choice.value)}
              />
              <span className="ja-settings-radio" aria-hidden="true" />
              <span>
                <strong>{choice.label}</strong>
                <small>{choice.description}</small>
              </span>
            </label>
          ))}
        </fieldset>
      </SettingsGroup>
      {feedback === undefined ? null : (
        <div className="ja-settings-form-actions">
          <p className="ja-settings-feedback" role="status">
            {feedback}
          </p>
          {failedMode === undefined ? null : (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => void change(failedMode)}
              disabled={pending}
            >
              重试
            </Button>
          )}
        </div>
      )}
      <ExecutionScopeDetails scope={scope} />
    </div>
  );
}

/** 通用页承接应用行为；通知继续通过既有原生权限端口保存，不混入外观或 Agent 配置。 */
export function GeneralSection({
  desktopNotifications,
  desktop,
  interfacePreferences,
  clarificationEnabled,
  onClarificationEnabledChange,
}: {
  desktopNotifications?: DesktopNotificationPreference;
  desktop: SettingsDesktopPort;
  interfacePreferences: SettingsInterfacePreferences;
  clarificationEnabled: boolean;
  onClarificationEnabledChange: SettingsPorts["onClarificationEnabledChange"];
}): React.ReactElement {
  const [notificationPending, setNotificationPending] = useState(false);
  const [feedback, setFeedback] = useState<string>();
  const [clarificationPending, setClarificationPending] = useState(false);

  /** 仅在 OS 接受显式启用请求后持久化偏好；关闭属于本地动作，不重复提示或打开系统设置。 */
  const updateDesktopNotifications = async (enabled: boolean): Promise<void> => {
    if (desktopNotifications === undefined) {
      return;
    }
    setNotificationPending(true);
    setFeedback(undefined);
    try {
      const accepted = await desktopNotifications.onChange(enabled);
      setFeedback(
        enabled && !accepted
          ? "系统未授予通知权限，桌面通知保持关闭。"
          : accepted
            ? "桌面通知已开启，仅在 Ja 位于后台时发送。"
            : "桌面通知已关闭。",
      );
    } catch {
      setFeedback("通知权限操作失败，桌面通知保持原状态。");
      toast.error("通知设置失败");
    } finally {
      setNotificationPending(false);
    }
  };

  /** 保存失败保留服务端快照，由 controller 重读恢复真实状态，避免开关与权限状态漂移。 */
  const updateClarification = async (enabled: boolean): Promise<void> => {
    setClarificationPending(true);
    try {
      await onClarificationEnabledChange(enabled);
      toast.success(enabled ? "交互澄清已开启" : "交互澄清已关闭");
    } catch {
      toast.error("交互澄清设置保存失败");
    } finally {
      setClarificationPending(false);
    }
  };

  return (
    <div className="ja-settings-section">
      <SectionHeader title="通用" />
      <SettingsGroup title="应用行为">
        <WindowCloseField desktop={desktop} />
        <SendShortcutField
          interfacePreferences={interfacePreferences}
          onChange={interfacePreferences.onChange}
        />
      </SettingsGroup>
      <SettingsGroup title="助手交互">
        <SwitchField
          id="general-clarification"
          label="交互澄清"
          checked={clarificationEnabled ?? true}
          onCheckedChange={(checked) => void updateClarification(checked)}
          hint="普通模式下确认关键偏好。Plan 始终可澄清，执行审批不受影响。"
          disabled={clarificationPending}
          settingId="general-clarification"
        />
      </SettingsGroup>
      {desktopNotifications === undefined ? null : (
        <SettingsGroup title="通知">
          <SwitchField
            id="general-notifications"
            label="桌面通知"
            checked={desktopNotifications.enabled}
            onCheckedChange={(checked) => void updateDesktopNotifications(checked)}
            hint="默认关闭；窗口失焦或后台时提示完成、失败和待确认，不包含对话正文。"
            disabled={notificationPending}
            settingId="general-notifications"
          />
        </SettingsGroup>
      )}
      {feedback === undefined ? null : (
        <p className="ja-settings-feedback" role="status">
          {feedback}
        </p>
      )}
    </div>
  );
}

/** Appearance 值只属于本地 UI preference；类型化回调不会进入 App Server 配置保存链。 */
export function AppearanceSection({
  appearance,
  onChange,
  interfacePreferences,
}: {
  appearance: AppearanceSettings;
  onChange: SettingsPorts["onAppearanceChange"];
  interfacePreferences: SettingsInterfacePreferences;
}): React.ReactElement {
  const [pending, setPending] = useState<keyof AppearanceSettings>();
  const [feedback, setFeedback] = useState<string>();
  const [failedUpdate, setFailedUpdate] = useState<{
    key: keyof AppearanceSettings;
    value: AppearanceSettings[keyof AppearanceSettings];
  }>();

  /** 保存失败不回滚已应用的会话外观，反馈必须区分未保存与未应用。 */
  const update = async <K extends keyof AppearanceSettings>(
    key: K,
    value: AppearanceSettings[K],
  ): Promise<void> => {
    const next = { ...appearance, [key]: value };
    setPending(key);
    setFeedback(undefined);
    setFailedUpdate(undefined);
    try {
      await onChange(next, key);
    } catch {
      setFeedback("已应用但未保存，请检查本地存储后重试。");
      setFailedUpdate({ key, value });
      toast.error("外观已应用但未保存");
    } finally {
      setPending(undefined);
    }
  };

  return (
    <div
      className="ja-settings-section"
      data-setting-id="appearance-theme"
      data-setting-search="主题 配色 外观 动效 透明度 对比度 Xcode Ja JetBrains Obsidian Claude"
      tabIndex={-1}
    >
      <SectionHeader title="外观" />
      <SettingsGroup title="主题">
        <Field
          id="appearance-theme"
          label="外观模式"
          hint="跟随系统会随 Windows 的浅色或深色外观自动切换。"
          layout="row"
        >
          <SettingsSelect
            id="appearance-theme"
            value={appearance.theme}
            options={themeOptions}
            onValueChange={(value) => void update("theme", value as ThemeMode)}
            ariaLabel="外观模式"
            disabled={pending !== undefined}
          />
        </Field>
        <Field
          id="appearance-palette"
          label="配色主题"
          hint="只改变色彩气质，明暗由外观模式控制。"
          layout="row"
        >
          <SettingsSelect
            id="appearance-palette"
            value={appearance.palette}
            options={paletteOptions}
            onValueChange={(value) => void update("palette", value as UiPalette)}
            ariaLabel="配色主题"
            disabled={pending !== undefined}
          />
        </Field>
      </SettingsGroup>
      <SettingsGroup title="文字">
        <TypographyFields
          interfacePreferences={interfacePreferences}
          onChange={interfacePreferences.onChange}
        />
      </SettingsGroup>
      <SettingsGroup title="辅助功能">
        <SwitchField
          id="appearance-motion"
          label="减少动效"
          checked={appearance.reducedMotion}
          onCheckedChange={(checked) => void update("reducedMotion", checked)}
          hint="保留状态变化，同时减少位移与过渡。"
          disabled={pending !== undefined}
          settingId="appearance-motion"
        />
        <SwitchField
          id="appearance-transparency"
          label="降低透明度"
          checked={appearance.reducedTransparency}
          onCheckedChange={(checked) => void update("reducedTransparency", checked)}
          hint="使用不透明材质和更清晰的边界。"
          disabled={pending !== undefined}
          settingId="appearance-transparency"
        />
        <SwitchField
          id="appearance-contrast"
          label="提高对比度"
          checked={appearance.highContrast}
          onCheckedChange={(checked) => void update("highContrast", checked)}
          hint="增强边框和焦点提示。"
          disabled={pending !== undefined}
          settingId="appearance-contrast"
        />
      </SettingsGroup>
      {feedback === undefined ? null : (
        <div className="ja-settings-form-actions">
          <p className="ja-settings-feedback" role="status">
            {feedback}
          </p>
          {failedUpdate === undefined ? null : (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => {
                const retry = failedUpdate;
                void update(retry.key, retry.value as never);
              }}
              disabled={pending !== undefined}
            >
              重试
            </Button>
          )}
        </div>
      )}
    </div>
  );
}
