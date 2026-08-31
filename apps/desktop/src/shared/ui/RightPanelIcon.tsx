// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { PanelRight } from "lucide-react";
import type { ReactElement } from "react";

/**
 * 右侧工作区开关始终使用同一面板轮廓，开合状态由按钮名称表达，避免状态切换时图形跳变。
 */
export function RightPanelIcon(): ReactElement {
  return <PanelRight aria-hidden="true" />;
}
