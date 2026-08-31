// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useRef, type ReactElement } from "react";
import {
  useTerminalWorkspaceController,
  type TerminalLayoutV1,
  type TerminalWorkspaceAdapter,
} from "@/features/workbench/terminal";
import { TerminalWorkspaceView } from "@/features/workbench/terminal/ui";

export interface TerminalWorkbenchSlotProps {
  workspaceId: string;
  adapter: TerminalWorkspaceAdapter;
  active: boolean;
  initialLayout?: unknown;
  onLayoutChange: (layout: TerminalLayoutV1) => void;
  onOpenExternalUrl: (url: string) => Promise<void>;
  onCopy: (text: string) => Promise<void>;
  onRegisterCloseAll: (closeAll: (() => Promise<void>) | undefined) => void;
}

/**
 * 延迟 composition slot 是 Terminal controller 与 DOM view 的装配边界；只有这里同时知道
 * native adapter、Workbench 激活态与 pane DOM，feature UI 始终只消费 controller 投影。
 */
export function TerminalWorkbenchSlot({
  workspaceId,
  adapter,
  active,
  initialLayout,
  onLayoutChange,
  onOpenExternalUrl,
  onCopy,
  onRegisterCloseAll,
}: TerminalWorkbenchSlotProps): ReactElement {
  const rootRef = useRef<HTMLElement>(null);

  /** 命中解析被收窄为 pane id，application controller 无需依赖 document 或 React ref。 */
  const resolveNativeDropPane = useCallback((x: number, y: number): string | undefined => {
    const root = rootRef.current;
    const target = globalThis.document?.elementFromPoint?.(x, y);
    if (root === null || !(target instanceof Element) || !root.contains(target)) return undefined;
    const pane = target.closest<HTMLElement>("[data-terminal-pane-id]");
    if (pane === null || !root.contains(pane)) return undefined;
    return pane.dataset["terminalPaneId"];
  }, []);

  const controller = useTerminalWorkspaceController({
    workspaceId,
    adapter,
    active,
    initialLayout,
    onLayoutChange,
    onOpenExternalUrl,
    onCopy,
    resolveNativeDropPane,
  });

  /** teardown 端口只在此实例存活期间注册，避免下一 workspace 调到旧 controller。 */
  useEffect(() => {
    onRegisterCloseAll(controller.closeAll);
    return () => onRegisterCloseAll(undefined);
  }, [controller.closeAll, onRegisterCloseAll]);

  return <TerminalWorkspaceView active={active} controller={controller} rootRef={rootRef} />;
}
