// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState, type ReactElement } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  SendShortcutField,
  TypographyFields,
} from "@/features/settings/ui/InterfacePreferencesFields";
import type { InterfacePreferences } from "@/features/settings/application/interfacePreferences";

const PREFERENCES: InterfacePreferences = {
  sendShortcut: "enter",
  uiFontSize: 16,
  codeFontSize: 13,
};

afterEach(cleanup);

describe("InterfacePreferencesFields", () => {
  it("显示当前平台的发送提示并把拒绝保存变成可见反馈", async () => {
    const user = userEvent.setup();
    const onChange = vi
      .fn()
      .mockRejectedValueOnce(new Error("storage unavailable"))
      .mockResolvedValueOnce(undefined);
    render(<SendShortcutField interfacePreferences={PREFERENCES} onChange={onChange} />);
    expect(screen.getByText("Enter 发送，Shift+Enter 换行。")).toBeVisible();

    await user.click(screen.getByRole("combobox", { name: "发送快捷键" }));
    await user.click(
      await screen.findByRole("option", { name: /Ctrl \+ Enter 发送|Cmd \+ Enter 发送/ }),
    );
    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent("已应用但未保存，请重试。"),
    );
    expect(screen.getByRole("button", { name: "重试" })).toBeVisible();
    await user.click(screen.getByRole("button", { name: "重试" }));
    await waitFor(() => expect(screen.queryByRole("status")).toBeNull());
    expect(onChange).toHaveBeenNthCalledWith(2, "sendShortcut", "modifier-enter");
  });

  it("忽略较新选择之后才到达的旧保存失败", async () => {
    const user = userEvent.setup();
    let rejectFirst: ((reason?: unknown) => void) | undefined;
    const firstSave = new Promise<void>((_, reject) => {
      rejectFirst = reject;
    });
    const onChange = vi.fn().mockReturnValueOnce(firstSave).mockResolvedValueOnce(undefined);
    /** 模拟 store 在选择后立即应用值，让第二次选择真实覆盖第一次请求。 */
    function Harness(): ReactElement {
      const [preferences, setPreferences] = useState(PREFERENCES);
      return (
        <SendShortcutField
          interfacePreferences={preferences}
          onChange={async (key, value) => {
            setPreferences((current) => ({ ...current, [key]: value }));
            await onChange(key, value);
          }}
        />
      );
    }
    render(<Harness />);

    await user.click(screen.getByRole("combobox", { name: "发送快捷键" }));
    await user.click(
      await screen.findByRole("option", { name: /Ctrl \+ Enter 发送|Cmd \+ Enter 发送/ }),
    );
    await user.click(screen.getByRole("combobox", { name: "发送快捷键" }));
    await user.click(screen.getByRole("option", { name: "Enter 发送" }));
    rejectFirst?.(new Error("late storage failure"));
    await waitFor(() => expect(onChange).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("只暴露紧凑的界面与代码字号字段", () => {
    render(
      <TypographyFields
        interfacePreferences={PREFERENCES}
        onChange={vi.fn(async () => undefined)}
      />,
    );
    expect(screen.getByText("界面字号")).toBeVisible();
    expect(screen.getByText("代码与终端字号")).toBeVisible();
    expect(screen.getByRole("combobox", { name: "界面字号" })).toHaveTextContent("默认");
    expect(screen.getByRole("combobox", { name: "代码与终端字号" })).toHaveTextContent("13px");
  });
});
