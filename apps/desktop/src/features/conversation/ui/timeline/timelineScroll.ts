// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useLayoutEffect, useRef, useState, type RefObject } from "react";
import type { Virtualizer, VirtualItem } from "@tanstack/react-virtual";

export type TimelineScrollKey = string | number | bigint;

export interface TimelineScrollPosition {
  readonly anchorKey?: TimelineScrollKey;
  readonly anchorOffset: number;
  readonly scrollOffset: number;
  readonly followingLatest: boolean;
}

const MAX_TIMELINE_SCROLL_POSITIONS = 32;

/**
 * 限定 Renderer 会话内的滚动快照数量，并以最近使用顺序淘汰；滚动位置是瞬态 UI 状态，
 * 不应写入用户文件，也不能让切换过多 Thread 时无界持有 DOM 相关 identity。
 */
export class TimelineScrollCache {
  private readonly entries = new Map<string, TimelineScrollPosition>();

  /** 读取并提升最近使用项，保证反复切回的会话不会被冷门快照立即淘汰。 */
  get(threadId: string): TimelineScrollPosition | undefined {
    const snapshot = this.entries.get(threadId);
    if (snapshot === undefined) return undefined;
    this.entries.delete(threadId);
    this.entries.set(threadId, snapshot);
    return snapshot;
  }

  /** 写入不可变快照并执行固定上限淘汰，避免缓存成为第二份 Timeline 数据。 */
  set(threadId: string, snapshot: TimelineScrollPosition): void {
    if (threadId.trim() === "") return;
    this.entries.delete(threadId);
    this.entries.set(threadId, snapshot);
    while (this.entries.size > MAX_TIMELINE_SCROLL_POSITIONS) {
      const oldest = this.entries.keys().next().value;
      if (oldest === undefined) return;
      this.entries.delete(oldest);
    }
  }

  /** 组件卸载时释放瞬态 UI identity，防止跨生命周期保留旧项目/会话状态。 */
  clear(): void {
    this.entries.clear();
  }
}

type TimelineVirtualizer = Pick<
  Virtualizer<HTMLDivElement, HTMLDivElement>,
  "getOffsetForIndex" | "scrollToOffset" | "getVirtualItemForOffset"
>;

interface TimelineResizeVirtualizer {
  readonly scrollOffset: number | null;
  readonly scrollAdjustments: number;
  readonly scrollDirection: "forward" | "backward" | null;
}

/**
 * 仅补偿完全位于 viewport 上方的行高变化。流式正文常在当前可见 Turn 行尾增长，即使这是
 * 首次测量也不能按整行 delta 推动 scrollTop；向上滚动期间同样让用户手势优先。
 */
export function shouldAdjustTimelineScrollPosition(
  item: Pick<VirtualItem, "end">,
  _delta: number,
  instance: TimelineResizeVirtualizer,
  followingLatest = true,
): boolean {
  // 用户已离开最新消息时，任何自动尺寸补偿都可能改变正在阅读的文本位置；行几何只能在追随尾部时参与判断。
  if (!followingLatest) return false;
  const scrollOffset = (instance.scrollOffset ?? 0) + instance.scrollAdjustments;
  return item.end <= scrollOffset && instance.scrollDirection !== "backward";
}

interface PendingTimelineRestore {
  readonly threadId: string | undefined;
  readonly snapshot: TimelineScrollPosition | undefined;
}

export interface UseTimelineScrollOptions {
  readonly cache?: TimelineScrollCache;
  readonly threadId?: string;
  readonly rowCount: number;
  readonly revision: string;
  readonly scrollRef: RefObject<HTMLDivElement | null>;
  readonly virtualizer: TimelineVirtualizer;
  readonly indexForKey: (key: TimelineScrollKey) => number;
  readonly scrollToLatest: () => void;
}

export interface UseTimelineScrollResult {
  readonly followingLatest: boolean;
  /** ResizeObserver 在 React commit 外触发，因此提供即时读取而不依赖异步的 state re-render。 */
  readonly isFollowingLatest: () => boolean;
  readonly scrollToLatest: () => void;
}

/**
 * 仅在会话组件生命周期内恢复 Timeline viewport：按稳定 row key 保存锚点与偏移，首次会话跟随尾部，
 * 用户向上浏览后让出尾部控制权。thread identity 改变时先隔离旧状态，避免新会话继承旧会话的 scrollTop。
 */
export function useTimelineScroll({
  cache: providedCache,
  threadId,
  rowCount,
  revision,
  scrollRef,
  virtualizer,
  indexForKey,
  scrollToLatest,
}: UseTimelineScrollOptions): UseTimelineScrollResult {
  const [localCache] = useState(() => new TimelineScrollCache());
  const cache = providedCache ?? localCache;
  const activeThreadRef = useRef<string | undefined>(threadId);
  const initializedRef = useRef(false);
  const followingRef = useRef(true);
  const pendingRestoreRef = useRef<PendingTimelineRestore | undefined>(undefined);
  const [followingLatest, setFollowingLatest] = useState(true);

  /** 以当前已测量的首个可见 row 作为锚点；raw offset 只作为 key 暂不可用时的有界后备。 */
  const capturePosition = useCallback(
    (currentThreadId: string | undefined, following: boolean): void => {
      if (currentThreadId === undefined) return;
      const element = scrollRef.current;
      if (element === null) return;
      const scrollOffset = Math.max(0, element.scrollTop);
      const item: VirtualItem | undefined = virtualizer.getVirtualItemForOffset(scrollOffset);
      cache.set(currentThreadId, {
        anchorKey: item?.key,
        anchorOffset: item === undefined ? 0 : Math.max(0, scrollOffset - item.start),
        scrollOffset,
        followingLatest: following,
      });
    },
    [cache, scrollRef, virtualizer],
  );

  /** 使用 row identity 计算恢复 offset，避免固定像素位置因动态消息高度变化而漂移。 */
  const restorePosition = useCallback(
    (snapshot: TimelineScrollPosition): void => {
      const anchorIndex = snapshot.anchorKey === undefined ? -1 : indexForKey(snapshot.anchorKey);
      const anchorOffset =
        anchorIndex < 0 ? undefined : virtualizer.getOffsetForIndex(anchorIndex, "start")?.[0];
      virtualizer.scrollToOffset(
        Math.max(
          0,
          (anchorOffset ?? snapshot.scrollOffset) +
            (anchorOffset === undefined ? 0 : snapshot.anchorOffset),
        ),
        { behavior: "auto" },
      );
    },
    [indexForKey, virtualizer],
  );

  /** 统一更新 live-tail 状态；按钮与滚动事件都必须同步 ref，避免下一帧旧状态抢回 viewport。 */
  const setFollowing = useCallback((following: boolean): void => {
    followingRef.current = following;
    setFollowingLatest(following);
  }, []);

  /**
   * identity 切换在 paint 前清理旧 viewport，并准备目标快照；有可用 rows 时立即恢复，
   * 没有 rows 则交给 revision effect 在历史到达后处理，避免把旧会话位置短暂展示出来。
   */
  useLayoutEffect(() => {
    const previousThreadId = activeThreadRef.current;
    const identityChanged = !initializedRef.current || previousThreadId !== threadId;
    if (!identityChanged) return undefined;

    initializedRef.current = true;
    activeThreadRef.current = threadId;
    const snapshot = threadId === undefined ? undefined : cache.get(threadId);
    followingRef.current = snapshot?.followingLatest ?? true;
    const pending: PendingTimelineRestore = { threadId, snapshot };
    pendingRestoreRef.current = pending;

    const element = scrollRef.current;
    if (element !== null) element.scrollTop = 0;
    if (rowCount > 0) {
      if (snapshot === undefined || snapshot.followingLatest) scrollToLatest();
      else restorePosition(snapshot);
    }
    if (element !== null) {
      element.dispatchEvent(new Event("scroll"));
    }
    return undefined;
  }, [
    capturePosition,
    cache,
    providedCache,
    restorePosition,
    rowCount,
    scrollRef,
    scrollToLatest,
    setFollowing,
    threadId,
  ]);

  /** 监听真实用户滚动并实时保存快照；向上 Wheel 先解除尾部跟随，防止 stream 重新抢焦点。 */
  useEffect(() => {
    const element = scrollRef.current;
    if (element === null) return undefined;
    const onScroll = (): void => {
      const following = element.scrollHeight - element.scrollTop - element.clientHeight <= 64;
      setFollowing(following);
      capturePosition(activeThreadRef.current, following);
    };
    const onWheel = (event: WheelEvent): void => {
      // 用户在恢复帧前开始滚动时，当前阅读意图优先于缓存锚点，不能再被迟到的 rAF 抢回。
      pendingRestoreRef.current = undefined;
      if (event.deltaY >= 0 || !followingRef.current) return;
      setFollowing(false);
      capturePosition(activeThreadRef.current, false);
    };
    element.addEventListener("scroll", onScroll, { passive: true });
    element.addEventListener("wheel", onWheel, { passive: true });
    onScroll();
    return () => {
      capturePosition(activeThreadRef.current, followingRef.current);
      element.removeEventListener("scroll", onScroll);
      element.removeEventListener("wheel", onWheel);
    };
  }, [capturePosition, scrollRef, setFollowing]);

  /** stream/reload 后仅在当前会话仍跟随尾部时滚动；手动上滚和已恢复锚点都不会被抢回。 */
  useLayoutEffect(() => {
    if (rowCount === 0 || followingRef.current || threadId === undefined) return;
    const snapshot = cache.get(threadId);
    if (snapshot === undefined || snapshot.followingLatest) return;
    // ResizeObserver 仍可能在 callback policy 外改写虚拟行的视觉起点；在 paint 前以同一行锚点收敛，
    // 让长流式正文的尾部增长既不抢走阅读位置，也不闪出一次错误的 offset。
    restorePosition(snapshot);
  }, [cache, restorePosition, revision, rowCount, threadId]);

  /** stream/reload 后仅在当前会话仍跟随尾部时滚动；手动上滚和已恢复锚点都不会被抢回。 */
  useEffect(() => {
    if (rowCount === 0) return undefined;
    const pending = pendingRestoreRef.current;
    if (pending !== undefined) {
      const frame =
        typeof window.requestAnimationFrame === "function"
          ? window.requestAnimationFrame(() => {
              if (
                pendingRestoreRef.current !== pending ||
                activeThreadRef.current !== pending.threadId
              )
                return;
              if (pending.snapshot === undefined || pending.snapshot.followingLatest) {
                scrollToLatest();
              } else restorePosition(pending.snapshot);
              pendingRestoreRef.current = undefined;
            })
          : window.setTimeout(() => {
              if (
                pendingRestoreRef.current !== pending ||
                activeThreadRef.current !== pending.threadId
              )
                return;
              if (pending.snapshot === undefined || pending.snapshot.followingLatest) {
                scrollToLatest();
              } else restorePosition(pending.snapshot);
              pendingRestoreRef.current = undefined;
            }, 0);
      return () => {
        if (typeof window.cancelAnimationFrame === "function" && typeof frame === "number") {
          window.cancelAnimationFrame(frame);
        } else {
          window.clearTimeout(frame);
        }
      };
    }
    if (!followingRef.current) return undefined;
    const frame =
      typeof window.requestAnimationFrame === "function"
        ? window.requestAnimationFrame(scrollToLatest)
        : window.setTimeout(scrollToLatest, 0);
    return () => {
      if (typeof window.cancelAnimationFrame === "function" && typeof frame === "number") {
        window.cancelAnimationFrame(frame);
      } else {
        window.clearTimeout(frame);
      }
    };
  }, [revision, restorePosition, rowCount, scrollToLatest]);

  /** 清理仅属于本次挂载的 viewport 快照；不会写入或修改用户持久化数据。 */
  useEffect(() => {
    return () => {
      capturePosition(activeThreadRef.current, followingRef.current);
      if (providedCache === undefined) cache.clear();
    };
  }, [cache, capturePosition, providedCache]);

  const jumpToLatest = useCallback((): void => {
    setFollowing(true);
    scrollToLatest();
  }, [scrollToLatest, setFollowing]);

  /** 流式布局测量必须读取 ref，避免 Wheel 与下一次 React render 之间仍沿用旧的跟随状态。 */
  const isFollowingLatest = useCallback((): boolean => followingRef.current, []);

  return { followingLatest, isFollowingLatest, scrollToLatest: jumpToLatest };
}
