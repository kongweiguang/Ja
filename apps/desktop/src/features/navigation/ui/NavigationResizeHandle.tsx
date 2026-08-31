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

interface NavigationResizeHandleProps {
  ratio: number;
  minRatio: number;
  maxRatio: number;
  onPreview: (ratio: number) => void;
  onCommit: (ratio: number) => void;
}

/** 尺寸边界由 composition 注入；视图只执行确定性约束，不读取 preferences store。 */
function clampRatio(ratio: number, minRatio: number, maxRatio: number): number {
  return Math.min(maxRatio, Math.max(minRatio, ratio));
}

/** 使用相对视口比例，使参考宽度的导航栏在宽屏上可超过已删除的 360px 上限，同时保持 DPI 稳定。 */
export function NavigationResizeHandle({
  ratio,
  minRatio,
  maxRatio,
  onPreview,
  onCommit,
}: NavigationResizeHandleProps): ReactElement {
  const [dragging, setDragging] = useState(false);
  const handleRef = useRef<HTMLDivElement>(null);
  const pointerIdRef = useRef<number | undefined>(undefined);
  const startXRef = useRef(0);
  const layoutWidthRef = useRef(1);
  const startRatioRef = useRef(ratio);
  const previewRatioRef = useRef(ratio);

  useEffect(() => {
    previewRatioRef.current = ratio;
  }, [ratio]);

  /** 根据窗口坐标计算预览，window 监听让指针离开窄命中区后仍能连续拖动。 */
  const previewFromClientX = useCallback(
    (clientX: number): void => {
      const deltaRatio = ((clientX - startXRef.current) / layoutWidthRef.current) * 100;
      const next = clampRatio(startRatioRef.current + deltaRatio, minRatio, maxRatio);
      previewRatioRef.current = next;
      onPreview(next);
    },
    [maxRatio, minRatio, onPreview],
  );

  /** 统一收口 pointerup、cancel、capture 丢失与窗口失焦，并且只提交一次最终尺寸。 */
  const finishResize = useCallback(
    (pointerId?: number): void => {
      const activePointerId = pointerIdRef.current;
      if (
        activePointerId === undefined ||
        (pointerId !== undefined && pointerId !== activePointerId)
      )
        return;
      pointerIdRef.current = undefined;
      const handle = handleRef.current;
      if (handle?.hasPointerCapture(activePointerId)) handle.releasePointerCapture(activePointerId);
      onCommit(previewRatioRef.current);
      setDragging(false);
    },
    [onCommit],
  );

  /** 在 window 上维持完整 pointer 事务，避免 WebView2 中分隔线被相邻层遮挡后拖动中断。 */
  useEffect(() => {
    if (!dragging) return undefined;
    /** 只接收本次捕获的 pointer，第二根触点不能改变当前布局。 */
    const preview = (event: PointerEvent): void => {
      if (pointerIdRef.current === event.pointerId) previewFromClientX(event.clientX);
    };
    /** 正常释放与系统取消共用相同提交路径。 */
    const finish = (event: PointerEvent): void => finishResize(event.pointerId);
    /** 原生窗口失焦时没有可靠 pointerup，因此提交最后可见预览。 */
    const finishOnBlur = (): void => finishResize();
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
  }, [dragging, finishResize, previewFromClientX]);

  /** 捕获单个 pointer，使光标离开狭窄分隔线命中区后仍能确定性调整尺寸。 */
  const startResize = (event: ReactPointerEvent<HTMLDivElement>): void => {
    if (event.button !== 0) return;
    event.preventDefault();
    pointerIdRef.current = event.pointerId;
    startXRef.current = event.clientX;
    startRatioRef.current = ratio;
    previewRatioRef.current = ratio;
    const layout = event.currentTarget.closest<HTMLElement>(".ja-layout");
    const measuredWidth = layout?.getBoundingClientRect().width ?? 0;
    layoutWidthRef.current = Math.max(1, measuredWidth > 0 ? measuredWidth : window.innerWidth);
    event.currentTarget.setPointerCapture(event.pointerId);
    setDragging(true);
  };

  /** 为键盘和辅助技术用户提供同样的有界宽度控制，同时不恢复固定像素上限。 */
  const resizeWithKeyboard = (event: KeyboardEvent<HTMLDivElement>): void => {
    const step = event.shiftKey ? 2 : 0.5;
    const next =
      event.key === "Home"
        ? minRatio
        : event.key === "End"
          ? maxRatio
          : event.key === "ArrowLeft"
            ? ratio - step
            : event.key === "ArrowRight"
              ? ratio + step
              : undefined;
    if (next === undefined) return;
    event.preventDefault();
    const bounded = clampRatio(next, minRatio, maxRatio);
    onPreview(bounded);
    onCommit(bounded);
  };

  return (
    <div
      ref={handleRef}
      className="ja-navigation-resize-handle"
      data-dragging={dragging || undefined}
      role="separator"
      aria-label="调整导航栏宽度"
      aria-orientation="vertical"
      aria-valuemin={minRatio}
      aria-valuemax={maxRatio}
      aria-valuenow={ratio}
      aria-valuetext={`${ratio.toFixed(1)}%`}
      tabIndex={0}
      onPointerDown={startResize}
      onLostPointerCapture={(event) => finishResize(event.pointerId)}
      onKeyDown={resizeWithKeyboard}
    />
  );
}
