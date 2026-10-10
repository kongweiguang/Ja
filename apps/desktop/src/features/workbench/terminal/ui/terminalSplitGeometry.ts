// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { TerminalLayoutNode, TerminalSplitLayout } from "../domain";

export interface TerminalSplitMeasurement {
  axisExtent: number;
  handleExtent: number;
  paneMinimumExtent: number;
}

export interface TerminalSplitGeometry {
  availableExtent: number;
  firstExtent: number;
  minimumFirstExtent: number;
  minimumSecondExtent: number;
  minimumRatio: number;
  maximumRatio: number;
  ratio: number;
  fitsMinimums: boolean;
}

/**
 * 递归汇总指定主轴上的最小空间；同向分屏累加子树和句柄，垂直方向只取较大子树，
 * 避免祖先 split 只按直接子项比例分配而挤出深层 pane。
 */
export function terminalNodeMinimumExtent(
  node: TerminalLayoutNode,
  orientation: TerminalSplitLayout["orientation"],
  paneMinimumExtent: number,
  handleExtent: number,
): number {
  if (node.kind === "pane") return 0;

  const firstExtent = terminalNodeMinimumExtent(
    node.first,
    orientation,
    paneMinimumExtent,
    handleExtent,
  );
  const secondExtent = terminalNodeMinimumExtent(
    node.second,
    orientation,
    paneMinimumExtent,
    handleExtent,
  );
  if (node.orientation !== orientation) return Math.max(firstExtent, secondExtent);

  return (
    Math.max(paneMinimumExtent, firstExtent) +
    handleExtent +
    Math.max(paneMinimumExtent, secondExtent)
  );
}

/**
 * 依据整个子树的最小轴长限制当前比例；窄视口无法满足所有最小时按子树需求分配，
 * 并由调用方临时解除 CSS 最小值，保证布局仍在容器内且各 pane 都保留可见区域；
 * 比较只吸收比例换算带来的双精度舍入误差，真实空间不足仍按不适配处理。
 */
export function terminalSplitGeometry(
  split: TerminalSplitLayout,
  measurement: TerminalSplitMeasurement,
  preferredRatio: number,
): TerminalSplitGeometry {
  const axisExtent = finiteNonNegative(measurement.axisExtent);
  const handleExtent = Math.min(axisExtent, finiteNonNegative(measurement.handleExtent));
  const paneMinimumExtent = finiteNonNegative(measurement.paneMinimumExtent);
  const availableExtent = Math.max(0, axisExtent - handleExtent);
  const minimumFirstExtent = Math.max(
    paneMinimumExtent,
    terminalNodeMinimumExtent(split.first, split.orientation, paneMinimumExtent, handleExtent),
  );
  const minimumSecondExtent = Math.max(
    paneMinimumExtent,
    terminalNodeMinimumExtent(split.second, split.orientation, paneMinimumExtent, handleExtent),
  );
  const totalMinimumExtent = minimumFirstExtent + minimumSecondExtent;
  const roundingAllowance = Number.EPSILON * Math.max(1, totalMinimumExtent, availableExtent) * 2;
  const minimumOverflow = totalMinimumExtent - availableExtent;
  const fitsMinimums =
    availableExtent > 0 &&
    (minimumOverflow <= 0 ||
      (Number.isFinite(minimumOverflow) && minimumOverflow <= roundingAllowance));

  let minimumRatio = 0;
  let maximumRatio = 1;
  if (fitsMinimums) {
    minimumRatio = minimumFirstExtent / availableExtent;
    maximumRatio = Math.max(minimumRatio, 1 - minimumSecondExtent / availableExtent);
  } else if (availableExtent > 0 && totalMinimumExtent > 0) {
    minimumRatio = minimumFirstExtent / totalMinimumExtent;
    maximumRatio = minimumRatio;
  }

  const ratio = clampTerminalSplitRatio(preferredRatio, minimumRatio, maximumRatio);
  return {
    availableExtent,
    firstExtent: availableExtent * ratio,
    minimumFirstExtent,
    minimumSecondExtent,
    minimumRatio,
    maximumRatio,
    ratio,
    fitsMinimums,
  };
}

/** 以同一有效范围限制拖拽、键盘和持久偏好，避免非法数值进入布局状态。 */
export function clampTerminalSplitRatio(ratio: number, minimumRatio = 0, maximumRatio = 1): number {
  const fallback = clampRange(0.5, minimumRatio, maximumRatio);
  return Number.isFinite(ratio) ? clampRange(ratio, minimumRatio, maximumRatio) : fallback;
}

/** 非数值测量按零空间处理，隐藏或未布局的容器不会产生 NaN 几何。 */
function finiteNonNegative(value: number): number {
  return Number.isFinite(value) ? Math.max(0, value) : 0;
}

/** 统一处理反向区间，窄容器约束收敛为唯一可见分配比例。 */
function clampRange(value: number, minimum: number, maximum: number): number {
  const lower = Math.max(0, Math.min(1, finiteNonNegative(minimum)));
  const upper = Math.max(lower, Math.min(1, finiteNonNegative(maximum)));
  return Math.min(upper, Math.max(lower, value));
}
