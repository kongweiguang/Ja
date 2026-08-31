// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { Shield } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { Button } from "@/shared/ui/primitives";
import type { AppearanceSettings, AccessMode, ThemeMode } from "../domain/types";
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

/** 只展示协议真正支持的两档权限，避免 UI 重新引入策略语言。 */
export function PermissionsSection({
  mode: initialMode,
  globalMode,
  projectMode = false,
  projectOverridden = false,
  onChange,
}: {
  mode: AccessMode;
  globalMode: AccessMode;
  projectMode?: boolean;
  projectOverridden?: boolean;
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
      <SectionHeader
        title="执行确认"
        action={
          projectMode && projectOverridden ? (
            <Button type="button" variant="ghost" size="sm" onClick={() => void change(globalMode)}>
              恢复继承
            </Button>
          ) : projectMode ? (
            <span className="ja-settings-override-state">继承全局</span>
          ) : undefined
        }
      />
      <fieldset className="ja-settings-permission-group">
        <legend>工具执行方式</legend>
        {choices.map((choice) => (
          <label
            className={`ja-settings-permission-card${mode === choice.value ? " is-selected" : ""}${projectMode && globalMode === "approval_required" && choice.value === "full_access" ? " is-disabled" : ""}`}
            data-setting-search={`${choice.label} ${choice.description} ${choice.value}`}
            tabIndex={-1}
            key={choice.value}
          >
            <input
              type="radio"
              name="ja-permission-mode"
              value={choice.value}
              checked={mode === choice.value}
              disabled={
                projectMode && globalMode === "approval_required" && choice.value === "full_access"
              }
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
      {projectMode && globalMode === "approval_required" ? (
        <p className="ja-settings-hint">项目只能继承或收紧全局执行确认，不能扩大权限。</p>
      ) : null}
      <div className="ja-settings-callout">
        <Shield size={16} aria-hidden="true" />
        <span>
          <strong>“全部执行”会跳过确认。</strong>Agent 将继承 Ja 桌面进程当前账户的文件和命令权限。
        </span>
      </div>
    </div>
  );
}

/** Appearance 值只属于展示层；Document 副作用通过类型化回调交给 Host ThemeProvider。 */
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

  /** 更新单个字段时仍向 Host 提交完整 Snapshot，避免局部状态 owner 分裂。 */
  const update = async <K extends keyof AppearanceSettings>(
    key: K,
    value: AppearanceSettings[K],
  ): Promise<void> => {
    const next = { ...appearance, [key]: value };
    setPending(key);
    setFeedback(undefined);
    try {
      await onChange(next);
    } catch {
      setFeedback("外观保存失败，仍保留上一次设置。 ");
      toast.error("外观保存失败");
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
      data-setting-search="主题 外观 动效 对比度 桌面通知"
      tabIndex={-1}
    >
      <SectionHeader title="外观" />
      <div className="ja-settings-form-grid">
        <Field id="appearance-theme" label="主题" hint="系统模式会随 Windows 外观自动切换。">
          <SettingsSelect
            id="appearance-theme"
            value={appearance.theme}
            options={themeOptions}
            onValueChange={(value) => void update("theme", value as ThemeMode)}
            ariaLabel="主题"
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
