// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@/shared/ui/primitives";
import type { SettingsDesktopPort } from "../application/ports";
import { Field, SettingsSelect } from "./shared";

type CloseBehavior = Awaited<ReturnType<SettingsDesktopPort["readCloseBehavior"]>>;

/** 原生拥有关闭策略；这里只保留已读取/保存的投影，不在浏览器复制第二份持久偏好。 */
export function WindowCloseField({
  desktop,
}: {
  desktop: SettingsDesktopPort;
}): React.ReactElement {
  const [value, setValue] = useState<CloseBehavior>();
  const [pending, setPending] = useState(true);
  const [error, setError] = useState<string>();
  const generation = useRef(0);
  const saving = useRef(false);

  /** 新读请求或卸载均撤销旧响应，避免 Strict Mode 或迟到回读覆盖已经保存的选择。 */
  const reload = useCallback(async (): Promise<void> => {
    if (saving.current) return;
    const token = ++generation.current;
    setPending(true);
    setError(undefined);
    try {
      const next = await desktop.readCloseBehavior();
      if (token === generation.current) setValue(next);
    } catch {
      if (token === generation.current) setError("未能读取关闭行为，请重试。");
    } finally {
      if (token === generation.current) setPending(false);
    }
  }, [desktop]);

  useEffect(() => {
    void reload();
    return () => {
      generation.current += 1;
    };
  }, [reload]);

  /** 只有原生原子保存成功才更新选择；失败时保留旧值，用户可重新选择重试。 */
  const save = async (next: string): Promise<void> => {
    if (saving.current || pending || (next !== "background" && next !== "exit") || next === value)
      return;
    saving.current = true;
    const token = ++generation.current;
    setPending(true);
    setError(undefined);
    try {
      await desktop.saveCloseBehavior(next);
      if (token === generation.current) setValue(next);
    } catch {
      if (token === generation.current) setError("未能保存，仍使用原来的关闭方式。");
    } finally {
      saving.current = false;
      if (token === generation.current) setPending(false);
    }
  };

  return (
    <div
      data-setting-id="general-close-behavior"
      data-setting-search="关闭窗口 后台 托盘 退出"
      tabIndex={-1}
    >
      <Field
        id="general-close-behavior"
        label="关闭窗口时"
        layout="row"
        error={error}
        hint={
          value === "background"
            ? "可从系统托盘重新打开。"
            : value === "exit"
              ? "保存更改后退出，后台任务一并结束。"
              : undefined
        }
      >
        <SettingsSelect
          id="general-close-behavior"
          value={value ?? ""}
          options={[
            { value: "background", label: "留在后台" },
            { value: "exit", label: "退出 Ja" },
          ]}
          ariaLabel="关闭窗口时"
          disabled={pending || value === undefined}
          onValueChange={(next) => void save(next)}
        />
      </Field>
      {error !== undefined && value === undefined ? (
        <Button variant="ghost" onClick={() => void reload()}>
          重试
        </Button>
      ) : null}
    </div>
  );
}
