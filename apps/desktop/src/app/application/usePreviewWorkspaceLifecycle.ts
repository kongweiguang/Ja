// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useEffect } from "react";
import type { PreviewWorkspaceLifecycle } from "../useJaWorkbench";

/**
 * Preview lifecycle 注册与组件挂载严格同寿命，使 workspace/window 协调器只能调用当前
 * child 的 ACK-first close port，不能向已经卸载的 WebView 投递晚关闭意图。
 */
export function usePreviewWorkspaceLifecycle(
  lifecycle: PreviewWorkspaceLifecycle | undefined,
  onRegisterLifecycle: (lifecycle: PreviewWorkspaceLifecycle | undefined) => void,
): void {
  useEffect(() => {
    onRegisterLifecycle(lifecycle);
    return () => onRegisterLifecycle(undefined);
  }, [lifecycle, onRegisterLifecycle]);
}
