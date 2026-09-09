// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  useCallback,
  useEffect,
  useRef,
  type PointerEvent as ReactPointerEvent,
  type RefObject,
} from "react";

const POINTER_Y_PROPERTY = "--ja-resize-pointer-y";

interface ResizeHandleBounds {
  readonly top: number;
  readonly height: number;
}

/** 把有限坐标限制并规整到三位小数，避免异常事件和高频 pointer 写入污染局部 CSS。 */
function formatPointerPosition(clientY: number, bounds: ResizeHandleBounds): string {
  const percentage = ((clientY - bounds.top) / bounds.height) * 100;
  const bounded = Math.min(100, Math.max(0, percentage));
  return `${Number(bounded.toFixed(3))}%`;
}

/**
 * 只在 pointer 进入或拖动开始时读取布局，之后将高频位置更新合并到动画帧；视觉位置不进入
 * React state，也不触碰尺寸 preference，因此不会把装饰反馈扩散为组件重渲染或持久化写入。
 */
export function useResizeHandleSpotlight<T extends HTMLElement>(handleRef: RefObject<T | null>) {
  const boundsRef = useRef<ResizeHandleBounds | undefined>(undefined);
  const frameRef = useRef<number | undefined>(undefined);
  const pendingPositionRef = useRef<string | undefined>(undefined);
  const pointerInsideRef = useRef(false);
  const draggingRef = useRef(false);

  /** 每帧只写一次局部自定义属性，把重绘限制在 11px 分隔器及其伪元素。 */
  const flushPosition = useCallback((): void => {
    frameRef.current = undefined;
    const position = pendingPositionRef.current;
    pendingPositionRef.current = undefined;
    if (position !== undefined) handleRef.current?.style.setProperty(POINTER_Y_PROPERTY, position);
  }, [handleRef]);

  /** 使用缓存边界换算纵向百分比；边界无效时保持 CSS 的 50% 键盘默认值。 */
  const updatePosition = useCallback(
    (clientY: number, refreshBounds = false): void => {
      const handle = handleRef.current;
      if (handle === null || !Number.isFinite(clientY)) return;
      if (refreshBounds || boundsRef.current === undefined) {
        const rect = handle.getBoundingClientRect();
        boundsRef.current = rect.height > 0 ? { top: rect.top, height: rect.height } : undefined;
      }
      const bounds = boundsRef.current;
      if (bounds === undefined) return;
      pendingPositionRef.current = formatPointerPosition(clientY, bounds);
      if (frameRef.current !== undefined) return;
      if (typeof window !== "undefined" && typeof window.requestAnimationFrame === "function") {
        frameRef.current = window.requestAnimationFrame(flushPosition);
      } else {
        flushPosition();
      }
    },
    [flushPosition, handleRef],
  );

  /** 取消待绘制帧但保留最后已绘制坐标，让 CSS 在原地淡出，避免透明度归零前跳回中心。 */
  const clearPosition = useCallback((): void => {
    if (
      frameRef.current !== undefined &&
      typeof window !== "undefined" &&
      typeof window.cancelAnimationFrame === "function"
    ) {
      window.cancelAnimationFrame(frameRef.current);
    }
    frameRef.current = undefined;
    pendingPositionRef.current = undefined;
    boundsRef.current = undefined;
  }, []);

  /** 非鼠标聚焦才恢复中心；离开和失焦不重置位置，以免打断仍可见的淡出动画。 */
  const onFocus = useCallback((): void => {
    if (pointerInsideRef.current || draggingRef.current) return;
    clearPosition();
    handleRef.current?.style.removeProperty(POINTER_Y_PROPERTY);
  }, [clearPosition, handleRef]);

  /** pointer 进入时刷新一次真实边界，让 DPI、窗口高度和响应式变化立即生效。 */
  const onPointerEnter = useCallback(
    (event: ReactPointerEvent<T>): void => {
      pointerInsideRef.current = true;
      updatePosition(event.clientY, true);
    },
    [updatePosition],
  );

  /** 普通 hover 只更新视觉坐标，拖动尺寸仍由所属 ResizeHandle 的 window 事务负责。 */
  const onPointerMove = useCallback(
    (event: ReactPointerEvent<T>): void => updatePosition(event.clientY),
    [updatePosition],
  );

  /** 拖动越过命中区仍跟踪坐标；普通离开只停止更新，由 CSS 在最后位置完成淡出。 */
  const onPointerLeave = useCallback((): void => {
    pointerInsideRef.current = false;
    if (!draggingRef.current) clearPosition();
  }, [clearPosition]);

  /** 拖动开始重新测量边界，避免 hover 后窗口尺寸变化导致位置基线过期。 */
  const beginDrag = useCallback(
    (clientY: number): void => {
      draggingRef.current = true;
      updatePosition(clientY, true);
    },
    [updatePosition],
  );

  /** window-level pointermove 复用缓存边界，使光带在离开狭窄命中区后仍与触点同步。 */
  const updateDrag = useCallback(
    (clientY: number): void => updatePosition(clientY),
    [updatePosition],
  );

  /** 正常释放保留 hover；取消、失焦或外部释放停止更新，但不移动尚在淡出的光带。 */
  const finishDrag = useCallback(
    (forceClear = false): void => {
      draggingRef.current = false;
      if (forceClear || !pointerInsideRef.current) clearPosition();
    },
    [clearPosition],
  );

  /** 卸载时取消未执行的帧，避免旧元素被异步回调保留。 */
  useEffect(() => clearPosition, [clearPosition]);

  return {
    onFocus,
    onPointerEnter,
    onPointerMove,
    onPointerLeave,
    beginDrag,
    updateDrag,
    finishDrag,
  } as const;
}
