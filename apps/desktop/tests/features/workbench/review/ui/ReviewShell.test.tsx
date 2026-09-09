// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useRef, useState, type ReactElement } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ReviewShell } from "@/features/workbench/review/ui/ReviewShell";

let observedElement: Element | undefined;
let resizeCallback: ResizeObserverCallback | undefined;

/** 将 ResizeObserver 变成测试可控信号，断言针对容器而不是 window viewport。 */
function installResizeObserver(): void {
  observedElement = undefined;
  resizeCallback = undefined;
  vi.stubGlobal(
    "ResizeObserver",
    class {
      constructor(callback: ResizeObserverCallback) {
        resizeCallback = callback;
      }

      observe(target: Element): void {
        observedElement = target;
      }

      disconnect(): void {}

      unobserve(): void {}
    },
  );
}

/** 提交一次真实 observer entry；0 宽用于模拟容器隐藏期间的无效布局采样。 */
function emitWidth(width: number): void {
  const target = observedElement;
  const callback = resizeCallback;
  if (target === undefined || callback === undefined) throw new Error("ReviewShell 尚未订阅尺寸");
  act(() => {
    callback([{ target, contentRect: { width } } as ResizeObserverEntry], {} as ResizeObserver);
  });
}

/** jsdom 不实现完整 PointerEvent，只注入 ReviewShell 拖拽事务读取的字段。 */
function dispatchPointer(target: Element, type: string, values: Record<string, number>): void {
  const event = new Event(type, { bubbles: true, cancelable: true });
  for (const [key, value] of Object.entries(values))
    Object.defineProperty(event, key, { configurable: true, value });
  fireEvent(target, event);
}

/** 使用最小真实内容渲染共享外壳，避免把手写静态卡片当成布局实现。 */
function ShellHarness(): ReactElement {
  const [detailOpen, setDetailOpen] = useState(false);
  const selectedRef = useRef<HTMLButtonElement>(null);
  return (
    <ReviewShell
      ariaLabel="审阅"
      scopeLabel="未提交"
      stats={{ files: 1, additions: 2, deletions: 1 }}
      refreshing={false}
      onRefresh={vi.fn()}
      refreshLabel="刷新未提交变更"
      onPreviousFile={vi.fn()}
      onNextFile={vi.fn()}
      detailOpen={detailOpen}
      onBack={() => {
        setDetailOpen(false);
        requestAnimationFrame(() => selectedRef.current?.focus());
      }}
      tree={
        <div role="tree" aria-label="审查文件" style={{ overflow: "auto", height: 100 }}>
          <button
            ref={selectedRef}
            type="button"
            role="treeitem"
            onClick={() => setDetailOpen(true)}
          >
            src/main.ts
          </button>
          <div style={{ height: 1000 }} />
        </div>
      }
      diff={
        <div role="region" aria-label="文件差异">
          diff
        </div>
      }
    />
  );
}

beforeEach(() => {
  installResizeObserver();
  vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => {
    callback(performance.now());
    return 1;
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("ReviewShell", () => {
  it.each([
    [360, "narrow"],
    [520, "narrow"],
    [760, "wide"],
    [1000, "wide"],
  ] as const)("按 %ipx 审阅容器宽度选择 %s 布局", (width, expected) => {
    Object.defineProperty(window, "innerWidth", { configurable: true, value: 1440 });
    render(<ShellHarness />);
    emitWidth(width);

    expect(screen.getByRole("region", { name: "审阅" })).toHaveAttribute(
      "data-review-layout",
      expected,
    );
  });

  it("忽略隐藏期间的 0 宽采样，不污染最近一次有效布局", () => {
    render(<ShellHarness />);
    const shell = screen.getByRole("region", { name: "审阅" });

    emitWidth(520);
    expect(shell).toHaveAttribute("data-review-layout", "narrow");
    emitWidth(0);
    expect(shell).toHaveAttribute("data-review-layout", "narrow");
    emitWidth(1000);
    expect(shell).toHaveAttribute("data-review-layout", "wide");
  });

  it("窄栏从文件树进入 Diff，返回时保留树滚动并恢复文件焦点", () => {
    render(<ShellHarness />);
    emitWidth(520);
    const shell = screen.getByRole("region", { name: "审阅" });
    const tree = screen.getByRole("tree", { name: "审查文件" });
    const file = screen.getByRole("treeitem", { name: "src/main.ts" });
    tree.scrollTop = 128;

    fireEvent.click(file);
    expect(shell).toHaveClass("is-detail-open");
    expect(screen.getByRole("region", { name: "文件差异" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "返回变更文件" }));

    expect(shell).not.toHaveClass("is-detail-open");
    expect(tree.scrollTop).toBe(128);
    expect(file).toHaveFocus();
  });

  it("顶栏只保留刷新，并把前后文件导航放入第二控制行", () => {
    render(<ShellHarness />);
    const header = document.querySelector(".ja-review-shell-header");
    const toolbar = document.querySelector(".ja-review-shell-toolbar");

    expect(header).toContainElement(screen.getByRole("button", { name: "刷新未提交变更" }));
    expect(header).not.toContainElement(screen.getByRole("button", { name: "上一个文件" }));
    expect(toolbar).toContainElement(screen.getByRole("button", { name: "上一个文件" }));
    expect(toolbar).toContainElement(screen.getByRole("button", { name: "下一个文件" }));
  });

  it("键盘调整树宽并遵守 Home/End 边界", () => {
    render(<ShellHarness />);
    emitWidth(1000);
    const shell = screen.getByRole("region", { name: "审阅" });
    Object.defineProperty(shell, "clientWidth", { configurable: true, value: 1000 });
    const separator = screen.getByRole("separator", { name: "调整文件树宽度" });

    fireEvent.keyDown(separator, { key: "ArrowLeft" });
    expect(separator).toHaveAttribute("aria-valuenow", "256");
    fireEvent.keyDown(separator, { key: "ArrowRight" });
    expect(separator).toHaveAttribute("aria-valuenow", "240");
    fireEvent.keyDown(separator, { key: "Home" });
    expect(separator).toHaveAttribute("aria-valuenow", "208");
    fireEvent.keyDown(separator, { key: "End" });
    expect(separator).toHaveAttribute("aria-valuenow", "420");
  });

  it("pointercancel 终止当前拖拽，后续 move 不再改变树宽", () => {
    render(<ShellHarness />);
    emitWidth(1000);
    const shell = screen.getByRole("region", { name: "审阅" });
    Object.defineProperty(shell, "clientWidth", { configurable: true, value: 1000 });
    const separator = screen.getByRole("separator", { name: "调整文件树宽度" });
    Object.assign(separator, {
      setPointerCapture: vi.fn(),
      hasPointerCapture: vi.fn(() => true),
      releasePointerCapture: vi.fn(),
    });

    dispatchPointer(separator, "pointerdown", { button: 0, pointerId: 7, clientX: 600 });
    dispatchPointer(separator, "pointermove", { pointerId: 7, clientX: 550 });
    expect(separator).toHaveAttribute("aria-valuenow", "290");
    dispatchPointer(separator, "pointercancel", { pointerId: 7, clientX: 550 });
    dispatchPointer(separator, "pointermove", { pointerId: 7, clientX: 500 });

    expect(separator).toHaveAttribute("aria-valuenow", "290");
  });
});
