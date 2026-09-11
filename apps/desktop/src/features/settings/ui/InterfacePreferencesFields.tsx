// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useRef, useState, type ReactElement } from "react";
import { Button } from "@/shared/ui/primitives";
import {
  codeFontSizeOptions,
  sendShortcutOptions,
  type InterfacePreferences,
  uiFontSizeOptions,
  type SendShortcut,
} from "../application/interfacePreferences";
import { Field, SettingsSelect } from "./shared";

export interface InterfacePreferencesFieldsProps {
  interfacePreferences: InterfacePreferences;
  onChange: <K extends keyof InterfacePreferences>(
    key: K,
    value: InterfacePreferences[K],
  ) => Promise<void>;
  disabled?: boolean;
}

type PreferenceUpdate<K extends keyof InterfacePreferences> = (
  value: InterfacePreferences[K],
) => void;

/**
 * 保存失败时保留最近一次字段值，并用序号隔离迟到 Promise；用户再次选择后，旧失败不能覆盖新状态。
 */
function usePreferenceUpdate<K extends keyof InterfacePreferences>(
  key: K,
  onChange: InterfacePreferencesFieldsProps["onChange"],
): {
  update: PreferenceUpdate<K>;
  retry: () => void;
  failed: boolean;
  pending: boolean;
} {
  const [failedValue, setFailedValue] = useState<InterfacePreferences[K] | undefined>(undefined);
  const failedValueRef = useRef<InterfacePreferences[K] | undefined>(undefined);
  const requestId = useRef(0);
  const [pending, setPending] = useState(false);

  /** 新请求清除旧失败；只有当前请求的拒绝才能显示重试入口。 */
  const update = useCallback<PreferenceUpdate<K>>(
    (value) => {
      const currentRequestId = ++requestId.current;
      failedValueRef.current = undefined;
      setFailedValue(undefined);
      setPending(true);
      void onChange(key, value)
        .catch(() => {
          if (currentRequestId !== requestId.current) return;
          failedValueRef.current = value;
          setFailedValue(value);
        })
        .finally(() => {
          if (currentRequestId === requestId.current) setPending(false);
        });
    },
    [key, onChange],
  );

  /** 只重放最近一次失败值，不重新读取或猜测字段当前值。 */
  const retry = useCallback(() => {
    const value = failedValueRef.current;
    if (value !== undefined) update(value);
  }, [update]);

  return { update, retry, failed: failedValue !== undefined, pending };
}

/** 失败态才提供恢复动作，保持常态设置行紧凑且不引入额外操作噪音。 */
function PreferenceSaveFeedback({ onRetry }: { onRetry: () => void }): ReactElement {
  return (
    <div className="ja-settings-form-actions">
      <p className="ja-settings-feedback" role="status">
        已应用但未保存，请重试。
      </p>
      <Button type="button" variant="ghost" size="sm" onClick={onRetry}>
        重试
      </Button>
    </div>
  );
}

/** 发送键位只改变普通消息提交，命令、建议面板与 IME 仍由 Composer 的优先级处理。 */
export function SendShortcutField({
  interfacePreferences,
  onChange,
  disabled = false,
}: InterfacePreferencesFieldsProps): ReactElement {
  const modifierLabel =
    typeof navigator !== "undefined" && /Mac/i.test(navigator.platform)
      ? "Cmd + Enter"
      : "Ctrl + Enter";
  const options = sendShortcutOptions.map((option) =>
    option.value === "modifier-enter" ? { ...option, label: `${modifierLabel} 发送` } : option,
  );
  const { update, retry, failed, pending } = usePreferenceUpdate("sendShortcut", onChange);
  return (
    <div
      data-setting-id="general-send-shortcut"
      data-setting-search="通用 发送快捷键 Enter Ctrl Cmd 换行"
      aria-busy={pending}
    >
      <Field
        id="general-send-shortcut"
        label="发送快捷键"
        layout="row"
        hint={
          interfacePreferences.sendShortcut === "enter"
            ? "Enter 发送，Shift+Enter 换行。"
            : `${modifierLabel} 发送，Enter 换行。`
        }
      >
        <SettingsSelect
          id="general-send-shortcut"
          value={interfacePreferences.sendShortcut}
          options={options}
          onValueChange={(value) => update(value as SendShortcut)}
          ariaLabel="发送快捷键"
          disabled={disabled}
        />
      </Field>
      {failed ? <PreferenceSaveFeedback onRetry={retry} /> : null}
    </div>
  );
}

/** 字号保持两档职责：界面字号缩放 rem，代码字号只更新 CodeMirror/xterm 的可变字体选项。 */
export function TypographyFields({
  interfacePreferences,
  onChange,
  disabled = false,
}: InterfacePreferencesFieldsProps): ReactElement {
  const uiFontSizeUpdate = usePreferenceUpdate("uiFontSize", onChange);
  const codeFontSizeUpdate = usePreferenceUpdate("codeFontSize", onChange);
  return (
    <div className="ja-settings-form-grid">
      <div
        data-setting-id="appearance-ui-font"
        data-setting-search="外观 界面字号 字体大小"
        aria-busy={uiFontSizeUpdate.pending}
      >
        <Field
          id="general-ui-font-size"
          label="界面字号"
          hint="调整菜单、面板和对话文字大小。"
          layout="row"
        >
          <SettingsSelect
            id="general-ui-font-size"
            value={String(interfacePreferences.uiFontSize)}
            options={uiFontSizeOptions}
            onValueChange={(value) => uiFontSizeUpdate.update(Number(value))}
            ariaLabel="界面字号"
            disabled={disabled}
          />
        </Field>
      </div>
      <div
        data-setting-id="appearance-code-font"
        data-setting-search="外观 代码 终端 Diff 字号 字体大小"
        aria-busy={codeFontSizeUpdate.pending}
      >
        <Field
          id="general-code-font-size"
          label="代码与终端字号"
          hint="只调整代码、Diff 和终端内容，不改变面板布局。"
          layout="row"
        >
          <SettingsSelect
            id="general-code-font-size"
            value={String(interfacePreferences.codeFontSize)}
            options={codeFontSizeOptions}
            onValueChange={(value) => codeFontSizeUpdate.update(Number(value))}
            ariaLabel="代码与终端字号"
            disabled={disabled}
          />
        </Field>
      </div>
      {uiFontSizeUpdate.failed ? <PreferenceSaveFeedback onRetry={uiFontSizeUpdate.retry} /> : null}
      {codeFontSizeUpdate.failed ? (
        <PreferenceSaveFeedback onRetry={codeFontSizeUpdate.retry} />
      ) : null}
    </div>
  );
}
