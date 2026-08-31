// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

interface MockTerminalLink {
  text: string;
  range: { start: { x: number; y: number }; end: { x: number; y: number } };
  activate: (event: MouseEvent, url: string) => void;
}

interface TerminalMock {
  activateFirstLink: (event: MouseEvent) => void;
  emitData: (data: string) => void;
  emitKey: (event: KeyboardEvent) => boolean;
  getLineCalls: number[];
  linksForLine: (lineNumber: number) => MockTerminalLink[] | undefined;
  options: { theme?: unknown };
  selectCalls: Array<{ column: number; row: number; length: number }>;
  setBufferLines: (lines: readonly { text: string; isWrapped: boolean }[], cols: number) => void;
  scrollToLineCalls: number[];
  writes: Array<string | Uint8Array>;
  disposed: boolean;
  focusCount: number;
  clearSelectionCount: number;
}

interface SearchAddonMock {
  clearDecorationsCount: number;
  disposed: boolean;
  findNextCalls: Array<{
    query: string;
    options?: { caseSensitive?: boolean; incremental?: boolean };
  }>;
  nextResult: boolean;
}

const mocks = vi.hoisted(() => {
  const terminals: TerminalMock[] = [];
  const searchAddons: SearchAddonMock[] = [];
  const observers: Array<{ observeCount: number; disconnectCount: number }> = [];
  class HoistedMockTerminal implements TerminalMock {
    cols = 80;
    rows = 24;
    options: { theme?: unknown } = {};
    selectCalls: Array<{ column: number; row: number; length: number }> = [];
    scrollToLineCalls: number[] = [];
    writes: Array<string | Uint8Array> = [];
    private dataHandler: ((data: string) => void) | undefined;
    private resizeHandler: ((size: { cols: number; rows: number }) => void) | undefined;
    private keyHandler: ((event: KeyboardEvent) => boolean) | undefined;
    private linkProvider:
      | {
          provideLinks: (
            lineNumber: number,
            callback: (links: MockTerminalLink[] | undefined) => void,
          ) => void;
        }
      | undefined;
    private bufferLines = [{ text: "visit https://example.com/docs", isWrapped: false }];
    getLineCalls: number[] = [];
    buffer = {
      active: {
        baseY: 0,
        cursorY: 0,
        viewportY: 0,
        length: 1,
        getLine: (index: number) => {
          this.getLineCalls.push(index);
          const line = this.bufferLines[index];
          if (line === undefined) return undefined;
          return {
            isWrapped: line.isWrapped,
            length: this.cols,
            getCell: (column: number) =>
              column < 0 || column >= this.cols
                ? undefined
                : {
                    getChars: () => line.text[column] ?? " ",
                    getWidth: () => 1,
                  },
            translateToString: (trimRight = false, startColumn = 0, endColumn = this.cols) => {
              const text = line.text.padEnd(this.cols, " ").slice(startColumn, endColumn);
              return trimRight ? text.trimEnd() : text;
            },
          };
        },
      },
    };
    disposed = false;
    focusCount = 0;
    clearSelectionCount = 0;
    constructor() {
      terminals.push(this);
    }
    loadAddon(): void {
      /* addon 的生命周期由下方唯一 owner 测试覆盖，避免在当前断言重复装配。 */
    }
    /** 复现 xterm 子节点 pointer 所有权，用于验证 capture 聚焦。 */
    open(parent: HTMLElement): void {
      parent.dataset["terminalOpen"] = "true";
      const screen = globalThis.document.createElement("div");
      screen.className = "xterm-screen";
      screen.addEventListener("pointerdown", (event) => event.stopPropagation());
      parent.append(screen);
    }
    /** 记录精确 xterm 输入，使测试能发现破坏跨 chunk sequence 的意外文本解码或复制。 */
    write(data: string | Uint8Array): void {
      this.writes.push(data);
    }
    onData(handler: (data: string) => void): { dispose: () => void } {
      this.dataHandler = handler;
      return {
        dispose: () => {
          this.dataHandler = undefined;
        },
      };
    }
    onResize(handler: (size: { cols: number; rows: number }) => void): { dispose: () => void } {
      this.resizeHandler = handler;
      return {
        dispose: () => {
          this.resizeHandler = undefined;
        },
      };
    }
    emitData(data: string): void {
      this.dataHandler?.(data);
    }
    /** 重放 xterm custom-key gate，使标准浏览器处理保持可观测。 */
    emitKey(event: KeyboardEvent): boolean {
      return this.keyHandler?.(event) ?? true;
    }
    emitResize(size: { cols: number; rows: number }): void {
      this.resizeHandler?.(size);
    }
    /** 保存 xterm key gate，以便直接断言快捷键所有权。 */
    attachCustomKeyEventHandler(handler: (event: KeyboardEvent) => boolean): void {
      this.keyHandler = handler;
    }
    /** 保存有界 provider，并暴露一个确定性激活接缝。 */
    registerLinkProvider(provider: {
      provideLinks: (
        lineNumber: number,
        callback: (links: MockTerminalLink[] | undefined) => void,
      ) => void;
    }): { dispose: () => void } {
      this.linkProvider = provider;
      return {
        dispose: () => {
          this.linkProvider = undefined;
        },
      };
    }
    /** 替换公共 buffer 投影，使换行范围行为保持确定。 */
    setBufferLines(lines: readonly { text: string; isWrapped: boolean }[], cols: number): void {
      this.bufferLines = lines.map((line) => ({ ...line }));
      this.cols = cols;
      this.buffer.active.length = lines.length;
    }
    /** 按 xterm 对 1-based hover 行的真实方式请求 provider。 */
    linksForLine(lineNumber: number): MockTerminalLink[] | undefined {
      let provided: MockTerminalLink[] | undefined;
      this.linkProvider?.provideLinks(lineNumber, (links) => {
        provided = links;
      });
      return provided;
    }
    /** 使用给定 modifier 状态激活首个生成 URL。 */
    activateFirstLink(event: MouseEvent): void {
      const link = this.linksForLine(1)?.[0];
      if (link !== undefined) link.activate(event, link.text);
    }
    /** 不在 JSDOM 重建 xterm 隐藏 textarea，仅记录表面驱动焦点。 */
    focus(): void {
      this.focusCount += 1;
    }
    /** 不在 JSDOM 复制 xterm selection model，仅记录搜索 cleanup。 */
    clearSelection(): void {
      this.clearSelectionCount += 1;
    }
    /** 记录 search controller 请求的精确 xterm 公共 selection。 */
    select(column: number, row: number, length: number): void {
      this.selectCalls.push({ column, row, length });
    }
    /** 不引入 renderer 依赖，仅记录公共 scroll 目标。 */
    scrollToLine(row: number): void {
      this.scrollToLineCalls.push(row);
    }
    dispose(): void {
      this.disposed = true;
    }
  }
  class HoistedMockResizeObserver {
    observeCount = 0;
    disconnectCount = 0;
    constructor() {
      observers.push(this);
    }
    observe(): void {
      this.observeCount += 1;
    }
    disconnect(): void {
      this.disconnectCount += 1;
    }
  }
  class HoistedMockSearchAddon implements SearchAddonMock {
    clearDecorationsCount = 0;
    disposed = false;
    findNextCalls: Array<{
      query: string;
      options?: { caseSensitive?: boolean; incremental?: boolean };
    }> = [];
    nextResult = true;
    constructor() {
      searchAddons.push(this);
    }
    /** 不在 JSDOM 复制 xterm 搜索引擎，仅记录官方 addon 边界。 */
    findNext(query: string, options?: { caseSensitive?: boolean; incremental?: boolean }): boolean {
      this.findNextCalls.push({ query, options });
      return this.nextResult;
    }
    /** 记录显式 overlay cleanup，防止旧搜索状态跨 session。 */
    clearDecorations(): void {
      this.clearDecorationsCount += 1;
    }
    dispose(): void {
      this.disposed = true;
    }
  }
  return {
    terminals,
    searchAddons,
    observers,
    HoistedMockTerminal,
    HoistedMockResizeObserver,
    HoistedMockSearchAddon,
  };
});

vi.mock("@xterm/xterm", () => ({ Terminal: mocks.HoistedMockTerminal }));
vi.mock("@xterm/addon-fit", () => ({
  FitAddon: class {
    fit(): void {}
    dispose(): void {}
  },
}));
vi.mock("@xterm/addon-search", () => ({ SearchAddon: mocks.HoistedMockSearchAddon }));

import { TerminalPanel } from "@/features/workbench/terminal/ui/TerminalPanel";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  mocks.terminals.length = 0;
  mocks.searchAddons.length = 0;
  mocks.observers.length = 0;
});

describe("TerminalPanel", () => {
  /** 验证真实表面 pointer 动作被显式交给 xterm。 */
  it("focuses xterm when the visible terminal surface receives a pointer", () => {
    const rendered = render(<TerminalPanel />);
    fireEvent.pointerDown(rendered.container.querySelector(".xterm-screen") as HTMLElement);
    expect(mocks.terminals[0]?.focusCount).toBe(1);
  });

  it("keeps one instance and observer across rerenders, appends output once, and detaches only on unmount", () => {
    vi.stubGlobal("ResizeObserver", mocks.HoistedMockResizeObserver);
    const onData = vi.fn();
    const onDetach = vi.fn();
    const firstTheme = { background: "#111111" };
    const nextTheme = { background: "#222222" };
    const rendered = render(
      <TerminalPanel
        initialText={"boot\n"}
        output={{ sequence: 1, data: Uint8Array.from([0x66, 0x69, 0x72, 0x73, 0x74, 0x0a]) }}
        theme={firstTheme}
        onData={onData}
        onDetach={onDetach}
      />,
    );
    expect(mocks.terminals).toHaveLength(1);
    expect(mocks.terminals[0]?.writes).toEqual([
      "boot\n",
      Uint8Array.from([0x66, 0x69, 0x72, 0x73, 0x74, 0x0a]),
    ]);
    expect(mocks.observers).toHaveLength(1);
    expect(mocks.observers[0]?.observeCount).toBe(1);
    mocks.terminals[0]?.emitData("ls\n");
    expect(onData).toHaveBeenCalledWith("ls\n");
    rendered.rerender(
      <TerminalPanel
        initialText={"replayed\n"}
        output={{ sequence: 1, data: Uint8Array.from([0x66, 0x69, 0x72, 0x73, 0x74, 0x0a]) }}
        theme={nextTheme}
        onData={onData}
        onDetach={onDetach}
      />,
    );
    expect(mocks.terminals).toHaveLength(1);
    expect(mocks.terminals[0]?.writes).toEqual([
      "boot\n",
      Uint8Array.from([0x66, 0x69, 0x72, 0x73, 0x74, 0x0a]),
    ]);
    expect(mocks.terminals[0]?.options.theme).toBe(nextTheme);
    expect(mocks.observers[0]?.disconnectCount).toBe(0);
    rendered.rerender(
      <TerminalPanel
        output={{ sequence: 2, data: Uint8Array.from([0x66, 0x69, 0x72, 0x73, 0x74, 0x0a]) }}
        theme={nextTheme}
        onData={onData}
        onDetach={onDetach}
      />,
    );
    rendered.rerender(
      <TerminalPanel
        output={{ sequence: 2, data: Uint8Array.from([0x66, 0x69, 0x72, 0x73, 0x74, 0x0a]) }}
        theme={nextTheme}
        onData={onData}
        onDetach={onDetach}
      />,
    );
    expect(mocks.terminals[0]?.writes).toEqual([
      "boot\n",
      Uint8Array.from([0x66, 0x69, 0x72, 0x73, 0x74, 0x0a]),
      Uint8Array.from([0x66, 0x69, 0x72, 0x73, 0x74, 0x0a]),
    ]);
    rendered.unmount();
    expect(mocks.terminals[0]?.disposed).toBe(true);
    expect(mocks.observers[0]?.disconnectCount).toBe(1);
    expect(onDetach).toHaveBeenCalledOnce();
  });

  /** React 可能合并提交多个原生 poll 结果，每个批次成员必须恰好写入并确认一次。 */
  it("writes a coalesced output batch in order without replaying it", () => {
    const onOutputsConsumed = vi.fn();
    const outputs = [
      { sequence: 11, data: Uint8Array.from([0x61]) },
      { sequence: 12, data: Uint8Array.from([0x62]) },
    ] as const;
    const rendered = render(
      <TerminalPanel outputs={outputs} onOutputsConsumed={onOutputsConsumed} />,
    );

    expect(mocks.terminals[0]?.writes).toEqual([Uint8Array.from([0x61]), Uint8Array.from([0x62])]);
    expect(onOutputsConsumed).toHaveBeenLastCalledWith(12);
    rendered.rerender(<TerminalPanel outputs={outputs} onOutputsConsumed={onOutputsConsumed} />);
    expect(mocks.terminals[0]?.writes).toHaveLength(2);
  });

  /** StrictMode 会创建替换 xterm，该实例也必须收到当前未确认批次。 */
  it("replays pending output only into the replacement xterm during StrictMode effect verification", () => {
    const outputs = [{ sequence: 21, data: Uint8Array.from([0x73]) }] as const;
    const onData = vi.fn();

    const rendered = render(
      <StrictMode>
        <TerminalPanel outputs={outputs} onData={onData} />
      </StrictMode>,
    );

    expect(mocks.terminals).toHaveLength(2);
    expect(mocks.terminals[0]?.disposed).toBe(true);
    expect(mocks.terminals[1]?.writes).toEqual([Uint8Array.from([0x73])]);
    expect(rendered.container.querySelectorAll(".xterm-screen")).toHaveLength(1);
    mocks.terminals[0]?.emitData("stale");
    mocks.terminals[1]?.emitData("current");
    expect(onData).toHaveBeenCalledOnce();
    expect(onData).toHaveBeenCalledWith("current");
  });

  /** 分割 code point 必须按原有有序字节 chunk 到达，因为增量 UTF-8 解码由 xterm 而非 React 边界拥有。 */
  it("passes split UTF-8 bytes to xterm without decoding or reordering", () => {
    const first = Uint8Array.from([0xe4, 0xbd]);
    const second = Uint8Array.from([0xa0]);
    const rendered = render(<TerminalPanel output={{ sequence: "utf8-1", data: first }} />);
    rendered.rerender(<TerminalPanel output={{ sequence: "utf8-2", data: second }} />);

    const writes = mocks.terminals[0]?.writes ?? [];
    expect(writes).toHaveLength(2);
    expect(writes[0]).toBe(first);
    expect(writes[1]).toBe(second);
    expect(
      Array.from(writes.flatMap((write) => (write instanceof Uint8Array ? Array.from(write) : []))),
    ).toEqual([0xe4, 0xbd, 0xa0]);
  });

  /** ANSI 控制状态同样跨事件，因此 Rust 数组形态 Vec<u8> 必须在不改变字节顺序的前提下规范化。 */
  it("passes split ANSI bytes to xterm in sequence, including Rust array payloads", () => {
    const escapePrefix = [0x1b, 0x5b, 0x33] as const;
    const escapeSuffix = [0x31, 0x6d, 0x6a, 0x61] as const;
    const rendered = render(<TerminalPanel output={{ sequence: "ansi-1", data: escapePrefix }} />);
    rendered.rerender(<TerminalPanel output={{ sequence: "ansi-2", data: escapeSuffix }} />);

    const writes = mocks.terminals[0]?.writes ?? [];
    expect(writes).toHaveLength(2);
    expect(writes[0]).toEqual(Uint8Array.from(escapePrefix));
    expect(writes[1]).toEqual(Uint8Array.from(escapeSuffix));
    expect(
      Array.from(writes.flatMap((write) => (write instanceof Uint8Array ? Array.from(write) : []))),
    ).toEqual([...escapePrefix, ...escapeSuffix]);
  });

  /** 没有可信 paste hook 时 Ctrl+V 留给 xterm/WebView，不能触发程序化 clipboard 读取能力。 */
  it("leaves standard paste handling to xterm when no paste hook is provided", () => {
    const readText = vi.fn(async () => "private clipboard");
    vi.stubGlobal("navigator", { clipboard: { readText } });
    render(<TerminalPanel />);

    const allowed = mocks.terminals[0]?.emitKey(
      new KeyboardEvent("keydown", { key: "v", ctrlKey: true }),
    );

    expect(allowed).toBe(true);
    expect(readText).not.toHaveBeenCalled();
  });

  /** 显式 host hook 拥有 paste 文本并阻止快捷键两次进入 xterm，组件仍不读取 navigator.clipboard。 */
  it("uses only the explicit paste hook and renders the search close icon", async () => {
    const readText = vi.fn(async () => "untrusted");
    const onPaste = vi.fn(async () => "trusted paste");
    const onData = vi.fn();
    vi.stubGlobal("navigator", { clipboard: { readText } });
    const rendered = render(<TerminalPanel onPaste={onPaste} onData={onData} />);

    expect(
      mocks.terminals[0]?.emitKey(new KeyboardEvent("keydown", { key: "v", ctrlKey: true })),
    ).toBe(false);
    await waitFor(() => expect(onData).toHaveBeenCalledWith("trusted paste"));
    expect(readText).not.toHaveBeenCalled();

    expect(
      mocks.terminals[0]?.emitKey(new KeyboardEvent("keydown", { key: "f", ctrlKey: true })),
    ).toBe(false);
    const close = await waitFor(() => rendered.getByRole("button", { name: "关闭终端搜索" }));
    expect(close.querySelector("svg")).not.toBeNull();
    expect(close).not.toHaveTextContent("×");
  });

  /** 把 IME 和 VK_PACKET key 交给 xterm 官方 CompositionHelper，不提前短路。 */
  it("leaves composition and packet-like text keys to xterm's textarea input path", () => {
    render(<TerminalPanel />);
    const terminal = mocks.terminals[0];

    expect(
      terminal?.emitKey(new KeyboardEvent("keydown", { key: "Process", isComposing: true })),
    ).toBe(true);
    expect(terminal?.emitKey(new KeyboardEvent("keydown", { key: "Unidentified" }))).toBe(true);
    expect(terminal?.emitKey(new KeyboardEvent("keydown", { key: "x", code: "" }))).toBe(true);
    expect(terminal?.emitKey(new KeyboardEvent("keydown", { key: "x", code: "KeyX" }))).toBe(true);
  });

  /** 搜索控件短暂拥有焦点，React 删除 overlay 后 xterm 必须重新取得焦点。 */
  it("restores xterm focus after the search overlay detaches", async () => {
    const rendered = render(<TerminalPanel />);
    const terminal = mocks.terminals[0]!;
    expect(terminal.emitKey(new KeyboardEvent("keydown", { key: "f", ctrlKey: true }))).toBe(false);
    const close = await waitFor(() => rendered.getByRole("button", { name: "关闭终端搜索" }));

    fireEvent.pointerDown(close);
    expect(terminal.focusCount).toBe(1);
    fireEvent.click(close);

    await waitFor(() => expect(rendered.queryByRole("search")).not.toBeInTheDocument());
    await waitFor(() => expect(terminal.focusCount).toBe(2));
    expect(terminal.clearSelectionCount).toBe(1);
  });

  /** 可信 Enter 可能早于 React 提交受控值，官方 addon 必须收到 input 当前 DOM 值而不是旧 state。 */
  it("searches scrollback through the official addon with the submitted DOM value", async () => {
    const rendered = render(<TerminalPanel />);
    const terminal = mocks.terminals[0]!;
    expect(terminal.emitKey(new KeyboardEvent("keydown", { key: "f", ctrlKey: true }))).toBe(false);
    const input = (await waitFor(() =>
      rendered.getByRole("textbox", { name: "终端搜索" }),
    )) as HTMLInputElement;
    const valueSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set;
    if (valueSetter === undefined) throw new Error("HTMLInputElement value setter is unavailable");
    valueSetter.call(input, "ja_终端_输入");

    fireEvent.keyDown(input, { key: "Enter" });

    await waitFor(() => expect(rendered.getByText("已找到")).toBeInTheDocument());
    expect(mocks.searchAddons[0]?.findNextCalls).toEqual([
      {
        query: "ja_终端_输入",
        options: { caseSensitive: false, incremental: false },
      },
    ]);
  });

  /** 换行窗格和 scrollback 继续由 addon 拥有；组件只报告真实 miss，并在搜索关闭时清除 addon selection。 */
  it("reports addon misses and clears search decorations on close", async () => {
    const rendered = render(<TerminalPanel />);
    const terminal = mocks.terminals[0]!;
    const searchAddon = mocks.searchAddons[0]!;
    searchAddon.nextResult = false;
    expect(terminal.emitKey(new KeyboardEvent("keydown", { key: "f", ctrlKey: true }))).toBe(false);
    const input = await waitFor(() => rendered.getByRole("textbox", { name: "终端搜索" }));
    fireEvent.change(input, { target: { value: "missing marker" } });

    fireEvent.keyDown(input, { key: "Enter" });
    await waitFor(() => expect(rendered.getByText("未找到")).toBeInTheDocument());

    fireEvent.click(rendered.getByRole("button", { name: "关闭终端搜索" }));
    await waitFor(() => expect(rendered.queryByRole("search")).not.toBeInTheDocument());
    expect(searchAddon.clearDecorationsCount).toBe(1);
  });

  /** 普通 URL 点击保留选择语义，只有显式 Ctrl/Command modifier 才跨越外部 opener 边界。 */
  it("opens terminal URLs only with Ctrl or Command", () => {
    const onOpenExternalUrl = vi.fn();
    render(<TerminalPanel onOpenExternalUrl={onOpenExternalUrl} />);

    mocks.terminals[0]?.activateFirstLink(new MouseEvent("click"));
    expect(onOpenExternalUrl).not.toHaveBeenCalled();

    vi.spyOn(Date, "now").mockReturnValueOnce(1_000).mockReturnValueOnce(1_300);
    mocks.terminals[0]?.activateFirstLink(new MouseEvent("click", { ctrlKey: true }));
    mocks.terminals[0]?.activateFirstLink(new MouseEvent("click", { metaKey: true }));
    expect(onOpenExternalUrl).toHaveBeenNthCalledWith(1, "https://example.com/docs");
    expect(onOpenExternalUrl).toHaveBeenNthCalledWith(2, "https://example.com/docs");
  });

  /** WebView2 可能丢失 Linkifier mouseup 激活，host click 必须复用公共 buffer 范围且不改变普通选择点击。 */
  it("falls back to the public xterm grid for modified URL clicks", () => {
    const onOpenExternalUrl = vi.fn();
    const rendered = render(<TerminalPanel onOpenExternalUrl={onOpenExternalUrl} />);
    const screen = rendered.container.querySelector(".xterm-screen") as HTMLElement;
    const rows = globalThis.document.createElement("div");
    rows.className = "xterm-rows";
    const firstRow = globalThis.document.createElement("div");
    rows.append(firstRow);
    screen.append(rows);
    vi.spyOn(screen, "getBoundingClientRect").mockReturnValue({
      left: 0,
      top: 0,
      right: 800,
      bottom: 240,
      width: 800,
      height: 240,
      x: 0,
      y: 0,
      toJSON: () => ({}),
    });
    vi.spyOn(firstRow, "getBoundingClientRect").mockReturnValue({
      left: 0,
      top: 10,
      right: 800,
      bottom: 20,
      width: 800,
      height: 10,
      x: 0,
      y: 10,
      toJSON: () => ({}),
    });

    fireEvent.click(screen, { button: 0, clientX: 85, clientY: 15 });
    expect(onOpenExternalUrl).not.toHaveBeenCalled();

    fireEvent.click(screen, { button: 0, clientX: 85, clientY: 15, ctrlKey: true });
    mocks.terminals[0]?.activateFirstLink(new MouseEvent("click", { ctrlKey: true }));
    expect(onOpenExternalUrl).toHaveBeenCalledOnce();
    expect(onOpenExternalUrl).toHaveBeenCalledWith("https://example.com/docs");
  });

  /** hover 中间物理行时必须恢复完整换行 URL 及其 1-based 范围。 */
  it("provides one URL range across three wrapped buffer rows", () => {
    render(<TerminalPanel />);
    const terminal = mocks.terminals[0]!;
    terminal.setBufferLines(
      [
        { text: "> https://", isWrapped: false },
        { text: "example.co", isWrapped: true },
        { text: "m/docs end", isWrapped: true },
      ],
      10,
    );
    terminal.getLineCalls.length = 0;

    const links = terminal.linksForLine(2);

    expect(terminal.getLineCalls[0]).toBe(1);
    expect(links).toHaveLength(1);
    expect(links?.[0]).toMatchObject({
      text: "https://example.com/docs",
      range: { start: { x: 3, y: 1 }, end: { x: 6, y: 3 } },
    });
  });
});
