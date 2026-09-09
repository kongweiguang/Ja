// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { Shield } from "lucide-react";
import { useState, type CSSProperties } from "react";
import { toast } from "sonner";
import {
  UI_PALETTE_LABELS,
  UI_PALETTE_ORDER,
  type AppearanceSettings,
  type AccessMode,
  type ThemeMode,
  type UiPalette,
} from "../domain/types";
import type { SettingsPorts } from "../application/ports";
import {
  Field,
  SectionHeader,
  settingsMutationErrorMessage,
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
  fleet: ["#f2f2f2", "#18191b", "#726cf9"],
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
}: {
  mode: AccessMode;
  onChange: SettingsPorts["onAccessModeChange"];
}): React.ReactElement {
  const mode = initialMode;
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
    try {
      await onChange(next);
      toast.success("权限模式已保存");
    } catch (error) {
      toast.error(settingsMutationErrorMessage(error, "权限模式保存失败"));
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
      <fieldset className="ja-settings-permission-group">
        <legend>工具执行方式</legend>
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
      <div className="ja-settings-callout">
        <Shield size={16} aria-hidden="true" />
        <span>
          <strong>“全部执行”会跳过确认。</strong>Agent 将继承 Ja 桌面进程当前账户的文件和命令权限。
        </span>
      </div>
    </div>
  );
}

/** Appearance 值只属于本地 UI preference；类型化回调不会进入 App Server 配置保存链。 */
export function AppearanceSection({
  appearance,
  onChange,
  desktopNotifications,
}: {
  appearance: AppearanceSettings;
  onChange: SettingsPorts["onAppearanceChange"];
  desktopNotifications?: DesktopNotificationPreference;
}): React.ReactElement {
  const [pending, setPending] = useState<keyof AppearanceSettings>();
  const [notificationPending, setNotificationPending] = useState(false);
  const [feedback, setFeedback] = useState<string>();

  /**
   * 更新单个字段时仍向 Host 提交完整 Snapshot；持久化失败不会回滚已经生效的会话主题，
   * 因而错误反馈必须如实区分“未保存”与“未应用”。
   */
  const update = async <K extends keyof AppearanceSettings>(
    key: K,
    value: AppearanceSettings[K],
  ): Promise<void> => {
    const next = { ...appearance, [key]: value };
    setPending(key);
    setFeedback(undefined);
    try {
      await onChange(next, key);
    } catch {
      setFeedback("已应用但未保存，请检查本地存储后重试。");
      toast.error("外观已应用但未保存");
    } finally {
      setPending(undefined);
    }
  };

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

  return (
    <div
      className="ja-settings-section"
      data-setting-id="appearance-theme"
      data-setting-search="主题 配色 外观 动效 透明度 对比度 桌面通知 Xcode Fleet Obsidian Claude"
      tabIndex={-1}
    >
      <SectionHeader title="外观" />
      <div className="ja-settings-form-grid">
        <Field
          id="appearance-theme"
          label="外观模式"
          hint="跟随系统会随 Windows 的浅色或深色外观自动切换。"
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
        <Field id="appearance-palette" label="配色主题" hint="只改变色彩气质，明暗由外观模式控制。">
          <SettingsSelect
            id="appearance-palette"
            value={appearance.palette}
            options={paletteOptions}
            onValueChange={(value) => void update("palette", value as UiPalette)}
            ariaLabel="配色主题"
            disabled={pending !== undefined}
          />
        </Field>
      </div>
      <div className="ja-settings-switch-list">
        <SwitchField
          id="appearance-motion"
          label="减少动效"
          checked={appearance.reducedMotion}
          onCheckedChange={(checked) => void update("reducedMotion", checked)}
          hint="保留状态变化，同时减少位移与过渡。"
          disabled={pending !== undefined}
        />
        <SwitchField
          id="appearance-transparency"
          label="降低透明度"
          checked={appearance.reducedTransparency}
          onCheckedChange={(checked) => void update("reducedTransparency", checked)}
          hint="使用不透明材质和更清晰的边界。"
          disabled={pending !== undefined}
        />
        <SwitchField
          id="appearance-contrast"
          label="提高对比度"
          checked={appearance.highContrast}
          onCheckedChange={(checked) => void update("highContrast", checked)}
          hint="增强边框和焦点提示。"
          disabled={pending !== undefined}
        />
        {desktopNotifications === undefined ? null : (
          <SwitchField
            id="appearance-notifications"
            label="桌面通知"
            checked={desktopNotifications.enabled}
            onCheckedChange={(checked) => void updateDesktopNotifications(checked)}
            hint="默认关闭；只在窗口失焦或后台时提示完成、失败和待确认，不包含对话内容。"
            disabled={notificationPending}
          />
        )}
      </div>
      {feedback === undefined ? null : (
        <p className="ja-settings-feedback" role="status">
          {feedback}
        </p>
      )}
    </div>
  );
}
