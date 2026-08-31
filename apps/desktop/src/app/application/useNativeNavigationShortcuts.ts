// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useEffect, useRef, useState } from "react";
import {
  matchNavigationShortcut,
  shouldPreserveEditableShortcut,
  type NavigationCommand,
} from "@/features/navigation";
import type {
  NativeShortcutCommand,
  NativeShortcutContext,
  NativeShortcutPort,
} from "./nativeShortcutPort";

/** Rust 五值命令映射到唯一壳层 command，native 事件不建立第二套路由协议。 */
function navigationCommandFromNative(command: NativeShortcutCommand): NavigationCommand {
  switch (command) {
    case "review":
      return "open-review";
    case "files":
      return "open-files";
    case "terminal":
      return "open-terminal";
    case "preview":
      return "open-preview";
    case "side_chat":
      return "focus-conversation";
  }
}

/**
 * DOM 与 native shortcut 共用一个 canonical dispatcher；listener 只注册一次，只有 dispatcher
 * 明确接纳动作才吞键，编辑器保留的快捷键和不可用能力继续交给原目标处理。
 */
export function useNativeNavigationShortcuts(
  adapter: NativeShortcutPort,
  platform: Parameters<typeof matchNavigationShortcut>[1],
  dispatch: (command: NavigationCommand) => boolean,
  context: NativeShortcutContext,
): void {
  const dispatchRef = useRef(dispatch);
  const [listeningAdapter, setListeningAdapter] = useState<NativeShortcutPort>();

  /** listener 通过 ref 读取最近提交的 dispatcher，避免每次视图变化都重订阅 native event。 */
  useEffect(() => {
    dispatchRef.current = dispatch;
  }, [dispatch]);

  /**
   * late subscription 在卸载后立即 unlisten；旧 adapter identity 不会向新 context 发布 ACK，
   * status unavailable 只停用 native context，DOM 快捷键仍保持工作。
   */
  useEffect(() => {
    let disposed = false;
    let unsubscribe: (() => void) | undefined;
    void adapter
      .subscribe({
        onCommand: (command) => {
          void dispatchRef.current(navigationCommandFromNative(command));
        },
        onStatus: () => {
          if (!disposed)
            setListeningAdapter((current) => (current === adapter ? undefined : current));
        },
      })
      .then((nextUnsubscribe) => {
        if (disposed) {
          nextUnsubscribe();
          return;
        }
        unsubscribe = nextUnsubscribe;
        setListeningAdapter(adapter);
      })
      .catch(() => {
        if (!disposed)
          setListeningAdapter((current) => (current === adapter ? undefined : current));
      });
    return () => {
      disposed = true;
      unsubscribe?.();
    };
  }, [adapter]);

  /** 只有 listener 与 Rust lease query 就绪后才发布能力，adapter 从 ACK 串行推进 revision。 */
  useEffect(() => {
    if (listeningAdapter !== adapter) return;
    void adapter.updateContext(context).catch(() => undefined);
  }, [adapter, context, listeningAdapter]);

  /**
   * capture 阶段先于 xterm/CodeMirror 收到 Workbench 组合键；RightAlt 使用独立 lease，
   * 因为 Windows WebView2 不保证暴露 AltGraph，blur 时必须 fail closed 清除陈旧状态。
   */
  useEffect(() => {
    let rightAltDown = false;
    /** 匹配前跟踪物理 RightAlt，避免把 AltGr 输入误判为应用快捷键。 */
    const handleWorkspaceShortcut = (event: KeyboardEvent): void => {
      if (platform !== "macos" && event.code === "AltRight") rightAltDown = true;
      const altGraphActive = rightAltDown || event.getModifierState("AltGraph");
      const command = matchNavigationShortcut(event, platform, altGraphActive);
      if (command === undefined || shouldPreserveEditableShortcut(command, event)) return;
      if (!dispatchRef.current(command)) return;
      event.preventDefault();
      event.stopPropagation();
    };
    /** keyup 结束物理 AltGr lease，即使它本身不映射应用命令。 */
    const handleModifierKeyUp = (event: KeyboardEvent): void => {
      if (platform !== "macos" && event.code === "AltRight") rightAltDown = false;
    };
    /** 失焦可能丢失 keyup，因此 blur 必须重置本地修饰键状态。 */
    const resetModifierState = (): void => {
      rightAltDown = false;
    };
    document.addEventListener("keydown", handleWorkspaceShortcut, true);
    document.addEventListener("keyup", handleModifierKeyUp, true);
    window.addEventListener("blur", resetModifierState);
    return () => {
      document.removeEventListener("keydown", handleWorkspaceShortcut, true);
      document.removeEventListener("keyup", handleModifierKeyUp, true);
      window.removeEventListener("blur", resetModifierState);
    };
  }, [platform]);
}
