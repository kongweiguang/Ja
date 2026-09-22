// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useMemo, useRef, type ReactElement } from "react";
import { cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import {
  shouldAdjustTimelineScrollPosition,
  TimelineScrollCache,
  useTimelineScroll,
  type UseTimelineScrollOptions,
  type TimelineScrollPosition,
} from "@/features/conversation/ui/timeline/timelineScroll";

/** 构造最小不可变快照，单元测试只验证缓存边界而不复制 Timeline 业务数据。 */
const position = (scrollOffset: number): TimelineScrollPosition => ({
  anchorKey: `row:${scrollOffset}`,
  anchorOffset: 12,
  scrollOffset,
  followingLatest: false,
});

/** 使用可控 Virtualizer 替身验证 row anchor 恢复，不依赖 jsdom 的真实布局测量。 */
function ScrollHarness({
  cache,
  threadId,
  latestOffset = 900,
  revision = threadId,
  rowStartOffset = 0,
}: {
  cache: TimelineScrollCache;
  threadId: string;
  latestOffset?: number;
  revision?: string;
  rowStartOffset?: number;
}): ReactElement {
  const scrollRef = useRef<HTMLDivElement>(null);
  const virtualizer = useMemo<UseTimelineScrollOptions["virtualizer"]>(
    () => ({
      getVirtualItemForOffset: (offset) => {
        const index = Math.max(0, Math.floor(offset / 100));
        return {
          key: `row:${index}`,
          index,
          start: index * 100,
          end: index * 100 + 100,
          size: 100,
          lane: 0,
        };
      },
      getOffsetForIndex: (index) => [index * 100 + rowStartOffset, "start"],
      scrollToOffset: (offset) => {
        if (scrollRef.current !== null) scrollRef.current.scrollTop = offset;
      },
    }),
    [rowStartOffset],
  );
  const scrollToLatest = (): void => {
    if (scrollRef.current !== null) scrollRef.current.scrollTop = latestOffset;
  };
  const { followingLatest } = useTimelineScroll({
    cache,
    threadId,
    rowCount: 10,
    revision,
    scrollRef,
    virtualizer,
    indexForKey: (key) =>
      typeof key === "string" && key.startsWith("row:") ? Number(key.slice(4)) : -1,
    scrollToLatest,
  });
  return <div ref={scrollRef} data-following={followingLatest ? "true" : "false"} />;
}

describe("TimelineScrollCache", () => {
  afterEach(() => cleanup());

  /** 快照上限必须固定，避免频繁切换 Thread 将 renderer 内存变成隐藏的历史缓存。 */
  it("keeps only a bounded session-local LRU window", () => {
    const cache = new TimelineScrollCache();
    for (let index = 0; index < 40; index += 1) {
      cache.set(`thread:${index}`, position(index));
    }

    expect(cache.get("thread:0")).toBeUndefined();
    expect(cache.get("thread:8")?.scrollOffset).toBe(8);
  });

  /** 读取会提升最近使用项，保证用户来回切换的会话优先保留自己的阅读位置。 */
  it("promotes a restored thread before the next eviction", () => {
    const cache = new TimelineScrollCache();
    for (let index = 0; index < 32; index += 1) {
      cache.set(`thread:${index}`, position(index));
    }
    expect(cache.get("thread:0")?.scrollOffset).toBe(0);
    cache.set("thread:new", position(99));

    expect(cache.get("thread:0")?.scrollOffset).toBe(0);
    expect(cache.get("thread:1")).toBeUndefined();
  });

  /** 卸载边界必须释放瞬态快照，且不提供任何持久化接口。 */
  it("clears transient positions explicitly", () => {
    const cache = new TimelineScrollCache();
    cache.set("thread:one", position(1));
    cache.clear();

    expect(cache.get("thread:one")).toBeUndefined();
  });

  /** 可见长行的尾部流式增长不能按整行 delta 推动 viewport；完全位于上方的行仍需补偿。 */
  it("only adjusts resize changes for rows fully above the reading viewport", () => {
    const instance = {
      scrollOffset: 200,
      scrollAdjustments: 0,
      scrollDirection: "forward" as const,
    };

    expect(shouldAdjustTimelineScrollPosition({ end: 280 }, 48, instance)).toBe(false);
    expect(shouldAdjustTimelineScrollPosition({ end: 180 }, 48, instance)).toBe(true);
    expect(
      shouldAdjustTimelineScrollPosition({ end: 180 }, 48, {
        ...instance,
        scrollDirection: "backward",
      }),
    ).toBe(false);
    expect(shouldAdjustTimelineScrollPosition({ end: 180 }, 48, instance, false)).toBe(false);
  });

  /** 目标 Thread 有缓存时应按 row anchor+offset 恢复，而不是沿用来源 Thread 的像素位置。 */
  it("restores the target thread anchor after switching away and back", async () => {
    const cache = new TimelineScrollCache();
    const { container, rerender } = render(<ScrollHarness cache={cache} threadId="thread:a" />);
    const scroll = container.firstElementChild;
    expect(scroll).not.toBeNull();
    if (scroll === null) return;
    Object.defineProperties(scroll, {
      clientHeight: { configurable: true, value: 100 },
      scrollHeight: { configurable: true, value: 1_000 },
      scrollTop: { configurable: true, writable: true, value: 220 },
    });
    fireEvent.scroll(scroll);

    rerender(<ScrollHarness cache={cache} threadId="thread:b" />);
    await waitFor(() => expect(scroll).toHaveProperty("scrollTop", 900));
    rerender(<ScrollHarness cache={cache} threadId="thread:a" />);
    await waitFor(() => expect(scroll).toHaveProperty("scrollTop", 220));
  });

  /** 恢复帧到达前的用户滚动优先级高于缓存，避免迟到的 rAF 抢回用户刚选定的位置。 */
  it("cancels a pending restore when the user wheels before the restore frame", async () => {
    const cache = new TimelineScrollCache();
    const { container, rerender } = render(<ScrollHarness cache={cache} threadId="thread:a" />);
    const scroll = container.firstElementChild;
    expect(scroll).not.toBeNull();
    if (scroll === null) return;
    Object.defineProperties(scroll, {
      clientHeight: { configurable: true, value: 100 },
      scrollHeight: { configurable: true, value: 1_000 },
      scrollTop: { configurable: true, writable: true, value: 220 },
    });
    fireEvent.scroll(scroll);

    rerender(<ScrollHarness cache={cache} threadId="thread:b" />);
    await waitFor(() => expect(scroll).toHaveProperty("scrollTop", 900));
    rerender(<ScrollHarness cache={cache} threadId="thread:a" />);
    scroll.scrollTop = 40;
    fireEvent.wheel(scroll, { deltaY: -20 });

    await new Promise((resolve) => setTimeout(resolve, 40));
    expect(scroll).toHaveProperty("scrollTop", 40);
  });

  /** 跟随尾部的会话切回后要追到新消息尾部，旧 offset 只适用于手动上滚的快照。 */
  it("follows the latest tail when a cached thread was following latest", async () => {
    const cache = new TimelineScrollCache();
    cache.set("thread:a", {
      anchorKey: "row:2",
      anchorOffset: 20,
      scrollOffset: 220,
      followingLatest: true,
    });
    const { container, rerender } = render(
      <ScrollHarness cache={cache} threadId="thread:b" latestOffset={900} />,
    );
    const scroll = container.firstElementChild;
    expect(scroll).not.toBeNull();
    if (scroll === null) return;
    Object.defineProperties(scroll, {
      clientHeight: { configurable: true, value: 100 },
      scrollHeight: { configurable: true, value: 1_000 },
      scrollTop: { configurable: true, writable: true, value: 0 },
    });
    await waitFor(() => expect(scroll).toHaveProperty("scrollTop", 900));

    rerender(<ScrollHarness cache={cache} threadId="thread:a" latestOffset={777} />);
    await waitFor(() => expect(scroll).toHaveProperty("scrollTop", 777));
  });

  /** Workspace owner 提供的 cache 跨 Timeline 短暂卸载保留，避免空会话读取阶段丢失阅读位置。 */
  it("keeps a supplied cache across timeline unmounts", async () => {
    const cache = new TimelineScrollCache();
    const first = render(<ScrollHarness cache={cache} threadId="thread:owner" />);
    const firstScroll = first.container.firstElementChild;
    expect(firstScroll).not.toBeNull();
    if (firstScroll === null) return;
    Object.defineProperties(firstScroll, {
      clientHeight: { configurable: true, value: 100 },
      scrollHeight: { configurable: true, value: 1_000 },
      scrollTop: { configurable: true, writable: true, value: 320 },
    });
    fireEvent.scroll(firstScroll);
    first.unmount();

    const second = render(<ScrollHarness cache={cache} threadId="thread:owner" />);
    const secondScroll = second.container.firstElementChild;
    expect(secondScroll).not.toBeNull();
    if (secondScroll === null) return;
    Object.defineProperties(secondScroll, {
      clientHeight: { configurable: true, value: 100 },
      scrollHeight: { configurable: true, value: 1_000 },
      scrollTop: { configurable: true, writable: true, value: 0 },
    });
    await waitFor(() => expect(secondScroll).toHaveProperty("scrollTop", 320));
  });

  /** 流式测量即使迟到改写虚拟行起点，也必须在 paint 前用用户保存的行锚点恢复阅读位置。 */
  it("reanchors an up-scrolled reader when a stream revision shifts virtual row geometry", async () => {
    const cache = new TimelineScrollCache();
    const rendered = render(
      <ScrollHarness cache={cache} threadId="thread:reading" revision="before" />,
    );
    const scroll = rendered.container.firstElementChild;
    expect(scroll).not.toBeNull();
    if (scroll === null) return;
    Object.defineProperties(scroll, {
      clientHeight: { configurable: true, value: 100 },
      scrollHeight: { configurable: true, value: 1_000 },
      scrollTop: { configurable: true, writable: true, value: 220 },
    });
    fireEvent.scroll(scroll);

    rendered.rerender(
      <ScrollHarness
        cache={cache}
        threadId="thread:reading"
        revision="stream-delta"
        rowStartOffset={48}
      />,
    );

    await waitFor(() => expect(scroll).toHaveProperty("scrollTop", 268));
  });
});
