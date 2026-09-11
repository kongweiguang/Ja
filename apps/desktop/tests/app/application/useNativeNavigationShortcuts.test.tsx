// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { cleanup, render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ReactElement } from "react";
import { useNativeNavigationShortcuts } from "@/app/application/useNativeNavigationShortcuts";
import type {
  NativeShortcutContext,
  NativeShortcutPort,
  NativeShortcutSubscription,
} from "@/app/application/nativeShortcutPort";
import type { NavigationCommand } from "@/features/navigation";

interface Deferred<T> {
  readonly promise: Promise<T>;
  readonly resolve: (value: T) => void;
}

/** 用可控 Promise 暴露 listener 注册窗口，避免用 sleep 掩盖卸载与 adapter 切换竞态。 */
function deferred<T>(): Deferred<T> {
  let resolvePromise: ((value: T) => void) | undefined;
  const promise = new Promise<T>((resolve) => {
    resolvePromise = resolve;
  });
  return { promise, resolve: (value) => resolvePromise?.(value) };
}

interface FakeShortcutPort {
  readonly port: NativeShortcutPort;
  readonly registration: Deferred<() => void>;
  readonly updateContext: ReturnType<typeof vi.fn>;
  readonly unsubscribe: ReturnType<typeof vi.fn>;
  subscription(): NativeShortcutSubscription | undefined;
}

/** 构造只有显式 resolve 后才完成订阅的窄端口，用于验证 lease 发布顺序与资源回收。 */
function fakeShortcutPort(): FakeShortcutPort {
  const registration = deferred<() => void>();
  const unsubscribe = vi.fn();
  const updateContext = vi.fn(async (context: NativeShortcutContext) => ({
    ...context,
    epoch: "00000000-0000-4000-8000-000000000001",
    revision: 1,
    ready: true,
    mainHandlerStatus: "ready" as const,
  }));
  let currentSubscription: NativeShortcutSubscription | undefined;
  return {
    port: {
      subscribe: vi.fn(async (subscription) => {
        currentSubscription = subscription;
        return registration.promise;
      }),
      updateContext,
    },
    registration,
    updateContext,
    unsubscribe,
    subscription: () => currentSubscription,
  };
}

interface ShortcutProbeProps {
  readonly port: NativeShortcutPort;
  readonly context: NativeShortcutContext;
  readonly dispatch: (command: NavigationCommand) => boolean;
}

/** Probe 只承载 hook lifecycle，不引入组件状态，确保断言针对 application controller。 */
function ShortcutProbe({ port, context, dispatch }: ShortcutProbeProps): ReactElement | null {
  useNativeNavigationShortcuts(port, "windows", dispatch, context);
  return null;
}

afterEach(() => cleanup());

describe("useNativeNavigationShortcuts", () => {
  /** 输入焦点不能屏蔽新建会话；重复 keydown 不得重复创建。 */
  it("Ctrl+N 在 Composer 输入框内只分发一次新建会话", () => {
    const fake = fakeShortcutPort();
    const dispatch = vi.fn(() => true);
    render(
      <ShortcutProbe
        port={fake.port}
        context={{ projectCapabilitiesEnabled: false, conversationFocusEnabled: true }}
        dispatch={dispatch}
      />,
    );
    const input = document.createElement("textarea");
    document.body.append(input);
    try {
      const event = new KeyboardEvent("keydown", {
        key: "n",
        ctrlKey: true,
        bubbles: true,
        cancelable: true,
      });
      input.dispatchEvent(event);
      expect(event.defaultPrevented).toBe(true);
      expect(dispatch).toHaveBeenCalledExactlyOnceWith("new-conversation");
      input.dispatchEvent(
        new KeyboardEvent("keydown", { key: "n", ctrlKey: true, repeat: true, bubbles: true }),
      );
      expect(dispatch).toHaveBeenCalledTimes(1);
    } finally {
      input.remove();
    }
  });

  it("等待 listener 就绪后才发布当前 context，并映射封闭 native command", async () => {
    const fake = fakeShortcutPort();
    const dispatch = vi.fn(() => true);
    const context = { projectCapabilitiesEnabled: true, conversationFocusEnabled: false };
    render(<ShortcutProbe port={fake.port} context={context} dispatch={dispatch} />);

    expect(fake.updateContext).not.toHaveBeenCalled();
    fake.registration.resolve(fake.unsubscribe);
    await waitFor(() => expect(fake.updateContext).toHaveBeenCalledWith(context));

    fake.subscription()?.onCommand("terminal");
    expect(dispatch).toHaveBeenCalledWith("open-terminal");
  });

  it("卸载期间晚完成的订阅立即释放，且不得发布过期 context", async () => {
    const fake = fakeShortcutPort();
    const view = render(
      <ShortcutProbe
        port={fake.port}
        context={{ projectCapabilitiesEnabled: true, conversationFocusEnabled: true }}
        dispatch={() => true}
      />,
    );

    view.unmount();
    fake.registration.resolve(fake.unsubscribe);
    await waitFor(() => expect(fake.unsubscribe).toHaveBeenCalledOnce());
    expect(fake.updateContext).not.toHaveBeenCalled();
  });

  it("切换 port identity 时回收旧订阅，只允许新 lease 接收最新 context", async () => {
    const first = fakeShortcutPort();
    const second = fakeShortcutPort();
    const dispatch = vi.fn(() => true);
    const firstContext = { projectCapabilitiesEnabled: false, conversationFocusEnabled: true };
    const secondContext = { projectCapabilitiesEnabled: true, conversationFocusEnabled: false };
    const view = render(
      <ShortcutProbe port={first.port} context={firstContext} dispatch={dispatch} />,
    );

    view.rerender(<ShortcutProbe port={second.port} context={secondContext} dispatch={dispatch} />);
    first.registration.resolve(first.unsubscribe);
    second.registration.resolve(second.unsubscribe);

    await waitFor(() => expect(first.unsubscribe).toHaveBeenCalledOnce());
    await waitFor(() => expect(second.updateContext).toHaveBeenCalledWith(secondContext));
    expect(first.updateContext).not.toHaveBeenCalled();
  });

  it("native unavailable 后停止 context 发布，但 DOM dispatcher 生命周期不受影响", async () => {
    const fake = fakeShortcutPort();
    const dispatch = vi.fn(() => true);
    const firstContext = { projectCapabilitiesEnabled: true, conversationFocusEnabled: true };
    const view = render(
      <ShortcutProbe port={fake.port} context={firstContext} dispatch={dispatch} />,
    );
    fake.registration.resolve(fake.unsubscribe);
    await waitFor(() => expect(fake.updateContext).toHaveBeenCalledOnce());

    fake.subscription()?.onStatus?.("unavailable");
    view.rerender(
      <ShortcutProbe
        port={fake.port}
        context={{ projectCapabilitiesEnabled: false, conversationFocusEnabled: false }}
        dispatch={dispatch}
      />,
    );
    expect(fake.updateContext).toHaveBeenCalledOnce();
  });
});
