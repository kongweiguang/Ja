// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type PointerEvent as ReactPointerEvent,
  type ReactElement,
} from "react";
import { useResizeHandleSpotlight } from "@/shared/hooks/useResizeHandleSpotlight";

export interface WorkbenchResizeHandleProps {
  readonly size: number;
  readonly minSize: number;
  readonly maxSize: number;
  readonly onPreview: (size: number) => void;
  readonly onCommit: (size: number) => void;
}

/** 尺寸边界由 Shell 注入，Workbench 视图不读取持久化 store 或猜测响应式模式。 */
function clampSize(size: number, minSize: number, maxSize: number): number {
  return Math.min(maxSize, Math.max(minSize, size));
}

/**
 * 为桌面分栏提供可中断的 Pointer Capture 事务；拖动只更新预览，释放、取消或失焦时
 * 才提交一次持久值，既保持跟手也避免 localStorage 高频写入。
 */
export function WorkbenchResizeHandle({
  size,
  minSize,
  maxSize,
  onPreview,
  onCommit,
}: WorkbenchResizeHandleProps): ReactElement {
  const [dragging, setDragging] = useState(false);
  const handleRef = useRef<HTMLDivElement>(null);
  const pointerIdRef = useRef<number | undefined>(undefined);
  const startXRef = useRef(0);
  const layoutWidthRef = useRef(1);
  const startSizeRef = useRef(size);
  const previewSizeRef = useRef(size);
  const spotlight = useResizeHandleSpotlight(handleRef);

  /** 外部持久值变化时刷新提交基线，避免下一次拖动从过期比例开始。 */
  useEffect(() => {
    previewSizeRef.current = size;
  }, [size]);

  /** 分隔线向左移动会放大右栏，因此横向位移需要反向换算为 Workbench 百分比。 */
  const previewFromClientX = useCallback(
    (clientX: number): void => {
      const deltaSize = ((startXRef.current - clientX) / layoutWidthRef.current) * 100;
      const next = clampSize(startSizeRef.current + deltaSize, minSize, maxSize);
      previewSizeRef.current = next;
      onPreview(next);
    },
    [maxSize, minSize, onPreview],
  );

  /** 统一结束所有 pointer 生命周期，并同步清理非正常结束的光带状态。 */
  const finishResize = useCallback(
    (pointerId?: number, forceSpotlightClear = false): void => {
      const activePointerId = pointerIdRef.current;
      if (
        activePointerId === undefined ||
        (pointerId !== undefined && pointerId !== activePointerId)
      )
        return;
      pointerIdRef.current = undefined;
      const handle = handleRef.current;
      if (handle?.hasPointerCapture(activePointerId)) handle.releasePointerCapture(activePointerId);
      onCommit(previewSizeRef.current);
      setDragging(false);
      spotlight.finishDrag(forceSpotlightClear);
    },
    [onCommit, spotlight],
  );

  /** window 监听保证光标越过会话或 Workbench 内容后仍持续响应，不依赖狭窄元素命中。 */
  useEffect(() => {
    if (!dragging) return undefined;
    /** 忽略非活动触点，防止多指输入改变本次布局事务。 */
    const preview = (event: PointerEvent): void => {
      if (pointerIdRef.current !== event.pointerId) return;
      spotlight.updateDrag(event.clientY);
      previewFromClientX(event.clientX);
    };
    /** 正常释放保留真实 hover，系统取消则同时清除失效的光带位置。 */
    const finish = (event: PointerEvent): void =>
      finishResize(event.pointerId, event.type === "pointercancel");
    /** WebView2 失焦可能吞掉 pointerup，故提交最后一个已经显示的尺寸。 */
    const finishOnBlur = (): void => finishResize(undefined, true);
    window.addEventListener("pointermove", preview);
    window.addEventListener("pointerup", finish);
    window.addEventListener("pointercancel", finish);
    window.addEventListener("blur", finishOnBlur);
    return () => {
      window.removeEventListener("pointermove", preview);
      window.removeEventListener("pointerup", finish);
      window.removeEventListener("pointercancel", finish);
      window.removeEventListener("blur", finishOnBlur);
    };
  }, [dragging, finishResize, previewFromClientX, spotlight]);

  /** 只允许主 pointer 开始拖动，并以真实分栏容器宽度换算百分比。 */
  const startResize = (event: ReactPointerEvent<HTMLDivElement>): void => {
    if (event.button !== 0) return;
    event.preventDefault();
    pointerIdRef.current = event.pointerId;
    spotlight.beginDrag(event.clientY);
    startXRef.current = event.clientX;
    startSizeRef.current = size;
    previewSizeRef.current = size;
    const layout = event.currentTarget.closest<HTMLElement>(".ja-workspace-panels");
    const measuredWidth = layout?.getBoundingClientRect().width ?? 0;
    layoutWidthRef.current = Math.max(1, measuredWidth > 0 ? measuredWidth : window.innerWidth);
    event.currentTarget.setPointerCapture(event.pointerId);
    setDragging(true);
  };

  /** 键盘方向按分隔线移动解释：左移放大右栏、右移缩小右栏，Home/End 跳到边界。 */
  const resizeWithKeyboard = (event: KeyboardEvent<HTMLDivElement>): void => {
    const step = event.shiftKey ? 2 : 0.5;
    const next =
      event.key === "Home"
        ? minSize
        : event.key === "End"
          ? maxSize
          : event.key === "ArrowLeft"
            ? size + step
            : event.key === "ArrowRight"
              ? size - step
              : undefined;
    if (next === undefined) return;
    event.preventDefault();
    const bounded = clampSize(next, minSize, maxSize);
    onPreview(bounded);
    onCommit(bounded);
  };

  return (
    <div
      ref={handleRef}
      className="ja-resize-handle ja-workbench-resize-handle"
      data-dragging={dragging || undefined}
      role="separator"
      aria-label="调整工作台宽度"
      aria-orientation="vertical"
      aria-valuemin={minSize}
      aria-valuemax={maxSize}
      aria-valuenow={size}
      aria-valuetext={`${size.toFixed(1)}%`}
      tabIndex={0}
      onFocus={spotlight.onFocus}
      onPointerEnter={spotlight.onPointerEnter}
      onPointerMove={spotlight.onPointerMove}
      onPointerLeave={spotlight.onPointerLeave}
      onPointerDown={startResize}
      onLostPointerCapture={(event) => finishResize(event.pointerId, true)}
      onKeyDown={resizeWithKeyboard}
    />
  );
}
