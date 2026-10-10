// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { StrictMode } from "react";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { Notification, NotificationCenter } from "@/shared/ui/notifications";
import { useUiPreferencesStore } from "@/shared/preferences/uiPreferences";

beforeEach(() => useUiPreferencesStore.setState({ themeMode: "light" }));
afterEach(() => cleanup());

describe("shared notifications", () => {
  /** 切换作用域撤回旧反馈，动画结束的迟到回调不能误关闭新会话状态。 */
  it("updates in place and removes only its own scoped notice", async () => {
    const onDismiss = vi.fn();
    const { rerender } = render(
      <>
        <NotificationCenter />
        <Notification scope="a" message="第一条" onDismiss={onDismiss} />
        <Notification scope="other" message="另一个会话" />
      </>,
    );
    await screen.findByText("第一条");
    rerender(
      <>
        <NotificationCenter />
        <Notification scope="a" message="原位更新" onDismiss={onDismiss} />
        <Notification scope="other" message="另一个会话" />
      </>,
    );
    await screen.findByText("原位更新");
    expect(document.querySelectorAll("[data-sonner-toast]")).toHaveLength(2);
    rerender(
      <>
        <NotificationCenter />
        <Notification scope="b" message="新的会话" onDismiss={onDismiss} />
        <Notification scope="other" message="另一个会话" />
      </>,
    );
    await screen.findByText("新的会话");
    await waitFor(() => expect(screen.queryByText("原位更新")).not.toBeInTheDocument());
    expect(screen.getByText("另一个会话")).toBeVisible();
    expect(onDismiss).not.toHaveBeenCalled();
  });

  /** 主动关闭只隐藏当前消息，重新渲染不能让已经关闭的提示再次弹出。 */
  it("keeps dismissed content hidden until the message changes", async () => {
    const user = userEvent.setup();
    const onDismiss = vi.fn();
    const { rerender } = render(
      <>
        <NotificationCenter />
        <Notification scope="a" message="可以关闭" onDismiss={onDismiss} />
      </>,
    );
    await user.click(await screen.findByRole("button", { name: "关闭提示" }));
    await waitFor(() => expect(screen.queryByText("可以关闭")).not.toBeInTheDocument());
    rerender(
      <>
        <NotificationCenter />
        <Notification scope="a" message="可以关闭" onDismiss={onDismiss}>
          <span>重渲染</span>
        </Notification>
      </>,
    );
    expect(screen.queryByText("可以关闭")).not.toBeInTheDocument();
    expect(onDismiss).toHaveBeenCalledOnce();
    rerender(
      <>
        <NotificationCenter />
        <Notification scope="a" message="新的错误" tone="error" onDismiss={onDismiss} />
      </>,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent("新的错误");
  });

  /** StrictMode 的清理重挂不得吞掉提示，也不能触发 owner 的关闭动作。 */
  it("preserves a non-dismissible pending notice under StrictMode", async () => {
    const onDismiss = vi.fn();
    render(
      <StrictMode>
        <NotificationCenter />
        <Notification scope="pending" message="待核实" dismissible={false} onDismiss={onDismiss} />
      </StrictMode>,
    );
    expect(await screen.findByRole("status")).toHaveTextContent("待核实");
    await waitFor(() =>
      expect(document.querySelectorAll("[data-sonner-toast][data-removed='false']")).toHaveLength(
        1,
      ),
    );
    expect(screen.queryByRole("button", { name: "关闭提示" })).not.toBeInTheDocument();
    expect(onDismiss).not.toHaveBeenCalled();
  });

  /** 快速隐藏后恢复必须复用同一 Sonner id，不能让旧退出回调清掉新 owner。 @author kongweiguang */
  it("reopens the same toast owner after visibility is restored", async () => {
    const onDismiss = vi.fn();
    const { rerender } = render(
      <>
        <NotificationCenter />
        <Notification
          scope="workspace-directory"
          message="项目目录不可用"
          enabled
          dismissible={false}
          onDismiss={onDismiss}
        />
      </>,
    );
    await screen.findByText("项目目录不可用");
    const originalId = document
      .querySelector<HTMLElement>("[data-sonner-toast]")
      ?.getAttribute("data-sonner-toast");
    expect(originalId).not.toBeNull();

    rerender(
      <>
        <NotificationCenter />
        <Notification
          scope="workspace-directory"
          message="项目目录不可用"
          enabled={false}
          dismissible={false}
          onDismiss={onDismiss}
        />
      </>,
    );
    rerender(
      <>
        <NotificationCenter />
        <Notification
          scope="workspace-directory"
          message="项目目录不可用"
          enabled
          dismissible={false}
          onDismiss={onDismiss}
        />
      </>,
    );

    await screen.findByText("项目目录不可用");
    await waitFor(() => {
      const activeToasts = Array.from(document.querySelectorAll<HTMLElement>("[data-sonner-toast]"))
        .filter((toast) => toast.dataset["removed"] === "false");
      expect(activeToasts).toHaveLength(1);
      expect(activeToasts[0]?.getAttribute("data-sonner-toast")).toBe(originalId);
    });
    expect(onDismiss).not.toHaveBeenCalled();
  });
});
