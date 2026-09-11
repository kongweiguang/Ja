// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { FitAddon } from "@xterm/addon-fit";
import { SearchAddon } from "@xterm/addon-search";
import { Terminal, type ILink, type ITheme } from "@xterm/xterm";
import { X } from "lucide-react";
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type MouseEvent as ReactMouseEvent,
  type ReactElement,
} from "react";
import { useResolvedTheme, useUiPalette } from "@/shared/hooks/useResolvedTheme";
import { useCodeFontSize } from "@/shared/hooks/useInterfacePreferencesValue";
import { IconButton } from "@/shared/ui/primitives";
import type { TerminalOutputChunk } from "../application";
import "@xterm/xterm/css/xterm.css";
import "./TerminalPanel.css";

interface TerminalViewportSize {
  cols: number;
  rows: number;
}

export interface TerminalPanelProps {
  /** PTY 快照只在 mount 时渲染一次，后续 prop 变化不能把同一 scrollback 重放进 xterm。 */
  initialText?: string;
  /** 重连快照保持原始字节，避免破坏跨 chunk 的 UTF-8 或 ANSI 状态。 */
  initialData?: Uint8Array | readonly number[];
  /** 单个 append-only PTY 输出事件以 sequence 区分相邻的相同字节 chunk。 */
  output?: TerminalOutputChunk;
  /** controller 提供的有序未确认事件批次可跨 React state 合并，且不转换终端字节。 */
  outputs?: readonly TerminalOutputChunk[];
  /** 确认已渲染前缀，使 controller 能释放短暂字节。 */
  onOutputsConsumed?: (throughSequence: number | string) => void;
  theme?: ITheme;
  onAttach?: () => void;
  onDetach?: () => void;
  onData?: (data: string) => void;
  onResize?: (size: TerminalViewportSize) => void;
  /** copy 可使用浏览器写入面；paste 绝不隐式读取 clipboard。 */
  onCopy?: (text: string) => void | Promise<void>;
  onPaste?: () => string | Promise<string>;
  /** 只有用户按住 Ctrl/Command 时才允许打开 http(s) URL。 */
  onOpenExternalUrl?: (url: string) => void | Promise<void>;
  ariaLabel?: string;
}

const TERMINAL_THEME_TOKEN_NAMES = {
  background: "--ja-terminal-background",
  foreground: "--ja-terminal-foreground",
  cursor: "--ja-terminal-cursor",
  selectionBackground: "--ja-terminal-selection",
  black: "--ja-terminal-ansi-black",
  red: "--ja-terminal-ansi-red",
  green: "--ja-terminal-ansi-green",
  yellow: "--ja-terminal-ansi-yellow",
  blue: "--ja-terminal-ansi-blue",
  magenta: "--ja-terminal-ansi-magenta",
  cyan: "--ja-terminal-ansi-cyan",
  white: "--ja-terminal-ansi-white",
  brightBlack: "--ja-terminal-ansi-bright-black",
  brightRed: "--ja-terminal-ansi-bright-red",
  brightGreen: "--ja-terminal-ansi-bright-green",
  brightYellow: "--ja-terminal-ansi-bright-yellow",
  brightBlue: "--ja-terminal-ansi-bright-blue",
  brightMagenta: "--ja-terminal-ansi-bright-magenta",
  brightCyan: "--ja-terminal-ansi-bright-cyan",
  brightWhite: "--ja-terminal-ansi-bright-white",
} as const satisfies Partial<Record<keyof ITheme, string>>;

/**
 * 主题 token 是 WebView 与 xterm 的唯一配色合同；缺失时立即暴露集成错误，避免 xterm
 * 静默回退自身 ANSI 默认值并形成只在部分终端状态出现的混合色板。
 */
function readTerminalThemeToken(styles: CSSStyleDeclaration, tokenName: string): string {
  const value = styles.getPropertyValue(tokenName).trim();
  if (value.length === 0) throw new Error(`Missing terminal theme token: ${tokenName}`);
  return value;
}

/**
 * xterm 无法直接解析 CSS var，因此在 document 主题已由 ThemeProvider 原子应用后，
 * 将完整语义 token 投影为 ITheme；这里不缓存 DOM 样式，确保 system/light/dark 切换读取最新值。
 */
function readTerminalTheme(): ITheme {
  const styles = globalThis.getComputedStyle(globalThis.document.documentElement);
  return Object.fromEntries(
    Object.entries(TERMINAL_THEME_TOKEN_NAMES).map(([key, tokenName]) => [
      key,
      readTerminalThemeToken(styles, tokenName),
    ]),
  ) as ITheme;
}

const MAX_LINK_PHYSICAL_LINES = 64;
const MAX_LINK_CHARACTERS = 8_192;

interface TerminalLinkCell {
  start: { x: number; y: number };
  end: { x: number; y: number };
}

interface TerminalLogicalLine {
  text: string;
  cells: readonly TerminalLinkCell[];
}

/**
 * 从 xterm 公共 buffer 重建一条换行逻辑行；cell 坐标保证 CJK/emoji 前缀后的 URL 范围准确，
 * 固定行数和字符上限阻止恶意 PTY 触发无界扫描。
 */
function readTerminalLogicalLine(
  terminal: Terminal,
  bufferLineNumber: number,
): TerminalLogicalLine | undefined {
  const buffer = terminal.buffer.active;
  const requestedIndex = bufferLineNumber - 1;
  if (!Number.isInteger(requestedIndex) || requestedIndex < 0 || requestedIndex >= buffer.length)
    return undefined;

  let firstIndex = requestedIndex;
  let physicalLineCount = 1;
  while (firstIndex > 0 && buffer.getLine(firstIndex)?.isWrapped === true) {
    if (physicalLineCount >= MAX_LINK_PHYSICAL_LINES) return undefined;
    firstIndex -= 1;
    physicalLineCount += 1;
  }

  let lastIndex = requestedIndex;
  while (lastIndex + 1 < buffer.length && buffer.getLine(lastIndex + 1)?.isWrapped === true) {
    if (lastIndex - firstIndex + 1 >= MAX_LINK_PHYSICAL_LINES) return undefined;
    lastIndex += 1;
  }

  let text = "";
  const cells: TerminalLinkCell[] = [];
  for (let lineIndex = firstIndex; lineIndex <= lastIndex; lineIndex += 1) {
    const line = buffer.getLine(lineIndex);
    if (line === undefined) return undefined;
    let rowText = "";
    const rowCells: TerminalLinkCell[] = [];
    for (let column = 0; column < terminal.cols; column += 1) {
      const cell = line.getCell(column);
      const width = cell?.getWidth() ?? 1;
      if (width === 0) continue;
      const characters = cell?.getChars() || " ";
      const position = {
        start: { x: column + 1, y: lineIndex + 1 },
        end: { x: Math.min(terminal.cols, column + Math.max(1, width)), y: lineIndex + 1 },
      };
      rowText += characters;
      for (let characterIndex = 0; characterIndex < characters.length; characterIndex += 1)
        rowCells.push(position);
    }
    if (lineIndex === lastIndex) {
      const trimmedLength = rowText.trimEnd().length;
      rowText = rowText.slice(0, trimmedLength);
      rowCells.length = trimmedLength;
    }
    if (text.length + rowText.length > MAX_LINK_CHARACTERS) return undefined;
    text += rowText;
    cells.push(...rowCells);
  }
  return text.length === 0 ? undefined : { text, cells };
}

/** 只返回与请求物理行相交的链接；xterm 逐行请求，但范围可以覆盖完整换行 URL。 */
function terminalLinksForLine(
  terminal: Terminal,
  bufferLineNumber: number,
  openExternalUrl: (url: string) => void,
): ILink[] | undefined {
  const logicalLine = readTerminalLogicalLine(terminal, bufferLineNumber);
  if (logicalLine === undefined) return undefined;
  const links: ILink[] = [];
  const matcher = /https?:\/\/[^\s<>'"`]+/gu;
  for (const match of logicalLine.text.matchAll(matcher)) {
    const text = match[0];
    const startOffset = match.index ?? 0;
    const start = logicalLine.cells[startOffset];
    const end = logicalLine.cells[startOffset + text.length - 1];
    if (
      start === undefined ||
      end === undefined ||
      start.start.y > bufferLineNumber ||
      end.end.y < bufferLineNumber
    )
      continue;
    links.push({
      text,
      range: { start: start.start, end: end.end },
      decorations: { pointerCursor: true, underline: false },
      activate: (event, url): void => {
        if (event.ctrlKey || event.metaKey) openExternalUrl(url);
      },
    });
  }
  return links.length === 0 ? undefined : links;
}

/** 不读取 xterm 私有状态，定位点击点下的精确 UTF-16 渲染字符。 */
function renderedCharacterOffsetAtPoint(
  row: Element,
  clientX: number,
  clientY: number,
): number | undefined {
  const walker = globalThis.document.createTreeWalker(row, globalThis.NodeFilter.SHOW_TEXT);
  let rowOffset = 0;
  while (walker.nextNode()) {
    const node = walker.currentNode;
    const length = node.textContent?.length ?? 0;
    for (let index = 0; index < length; index += 1) {
      const range = globalThis.document.createRange();
      range.setStart(node, index);
      range.setEnd(node, index + 1);
      const rect = range.getBoundingClientRect();
      if (
        rect.width > 0 &&
        rect.height > 0 &&
        clientX >= rect.left &&
        clientX < rect.right &&
        clientY >= rect.top &&
        clientY < rect.bottom
      ) {
        return rowOffset + index;
      }
    }
    rowOffset += length;
  }
  return undefined;
}

/** 将渲染字符偏移映射回 xterm 公共 buffer 列，并正确处理宽字符。 */
function bufferColumnForCharacterOffset(
  terminal: Terminal,
  bufferLineNumber: number,
  characterOffset: number,
): number | undefined {
  const line = terminal.buffer.active.getLine(bufferLineNumber - 1);
  if (line === undefined || characterOffset < 0) return undefined;
  let renderedOffset = 0;
  for (let column = 0; column < terminal.cols; column += 1) {
    const cell = line.getCell(column);
    if (cell?.getWidth() === 0) continue;
    const characters = cell?.getChars() || " ";
    if (characterOffset < renderedOffset + characters.length) return column + 1;
    renderedOffset += characters.length;
  }
  return undefined;
}

/**
 * 通过 xterm 公共 grid 和 buffer 投影解析渲染点击；该窄 fallback 只覆盖 WebView2 中
 * Linkifier hover 有效但 mouseup 丢失的路径，不能阻止普通选择。
 */
function terminalLinkAtViewportPoint(
  terminal: Terminal,
  screen: Element,
  clientX: number,
  clientY: number,
  openExternalUrl: (url: string) => void,
): ILink | undefined {
  const rect = screen.getBoundingClientRect();
  if (
    rect.width <= 0 ||
    rect.height <= 0 ||
    clientX < rect.left ||
    clientX >= rect.right ||
    clientY < rect.top ||
    clientY >= rect.bottom
  )
    return undefined;
  const renderedRows = [...screen.querySelectorAll(".xterm-rows > div")];
  const renderedRowIndex = renderedRows.findIndex((row) => {
    const rowRect = row.getBoundingClientRect();
    return rowRect.height > 0 && clientY >= rowRect.top && clientY < rowRect.bottom;
  });
  const viewportY =
    renderedRowIndex >= 0
      ? renderedRowIndex + 1
      : Math.min(
          terminal.rows,
          Math.max(1, Math.floor(((clientY - rect.top) * terminal.rows) / rect.height) + 1),
        );
  const bufferY = terminal.buffer.active.viewportY + viewportY;
  const renderedCharacterOffset =
    renderedRowIndex >= 0
      ? renderedCharacterOffsetAtPoint(renderedRows[renderedRowIndex]!, clientX, clientY)
      : undefined;
  const x =
    renderedCharacterOffset === undefined
      ? Math.min(
          terminal.cols,
          Math.max(1, Math.floor(((clientX - rect.left) * terminal.cols) / rect.width) + 1),
        )
      : bufferColumnForCharacterOffset(terminal, bufferY, renderedCharacterOffset);
  if (x === undefined) return undefined;
  const links = terminalLinksForLine(terminal, bufferY, openExternalUrl);
  const current = bufferY * terminal.cols + x;
  return links?.find((link) => {
    const lower = link.range.start.y * terminal.cols + link.range.start.x;
    const upper = link.range.end.y * terminal.cols + link.range.end.x;
    return lower <= current && current <= upper;
  });
}

/** 已挂载面板只拥有一个 xterm 实例且仅暴露 PTY 回调；进程创建与终端协议仍由 Rust 负责。 */
export function TerminalPanel({
  initialText = "",
  initialData,
  output,
  outputs,
  theme,
  onAttach,
  onDetach,
  onData,
  onResize,
  onCopy,
  onPaste,
  onOpenExternalUrl,
  onOutputsConsumed,
  ariaLabel = "工作区终端",
}: TerminalPanelProps): ReactElement {
  const resolvedTheme = useResolvedTheme();
  const palette = useUiPalette();
  const codeFontSize = useCodeFontSize();
  const hostRef = useRef<HTMLDivElement>(null);
  const terminalRef = useRef<Terminal | null>(null);
  const fitAddonRef = useRef<FitAddon | null>(null);
  const searchAddonRef = useRef<SearchAddon | null>(null);
  const lastOpenedLinkRef = useRef<{ url: string; at: number } | undefined>(undefined);
  const initialTextRef = useRef(initialText);
  const initialDataRef = useRef(initialData);
  const initialThemeRef = useRef(theme);
  const initialCodeFontSizeRef = useRef(codeFontSize);
  const consumedOutputSequencesRef = useRef(new Set<string>());
  const restoreFocusAfterSearchRef = useRef(false);
  const callbacks = useRef({
    onAttach,
    onDetach,
    onData,
    onResize,
    onCopy,
    onPaste,
    onOpenExternalUrl,
    onOutputsConsumed,
  });
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [searchMatch, setSearchMatch] = useState<string>();
  useEffect(() => {
    callbacks.current = {
      onAttach,
      onDetach,
      onData,
      onResize,
      onCopy,
      onPaste,
      onOpenExternalUrl,
      onOutputsConsumed,
    };
  }, [onAttach, onCopy, onData, onDetach, onOpenExternalUrl, onOutputsConsumed, onPaste, onResize]);

  /** 不保留 URL 历史，仅对 Linkifier mouseup 与 WebView2 click fallback 去重。 */
  const openExternalUrlOnce = useCallback((url: string): void => {
    const now = Date.now();
    const previous = lastOpenedLinkRef.current;
    if (previous?.url === url && now - previous.at < 250) return;
    lastOpenedLinkRef.current = { url, at: now };
    void callbacks.current.onOpenExternalUrl?.(url);
  }, []);

  /**
   * xterm 拥有 DOM 与 observer 生命周期，因此 owner effect 刻意不依赖 prop；
   * theme 和 output 由后续窄 effect 应用，避免重建 PTY view。
   */
  useEffect(() => {
    const host = hostRef.current;
    if (host === null) return undefined;
    // StrictMode 会用新 xterm 实例重放 owner effect，sequence ledger 必须同步重置，否则替换视图会丢字节。
    consumedOutputSequencesRef.current.clear();
    // 即使 xterm 版本或 renderer fallback 在重放时残留已释放表面，React host 仍是唯一所有权边界。
    host.replaceChildren();
    const terminal = new Terminal({
      convertEol: true,
      cursorBlink: true,
      fontFamily: "ui-monospace, SFMono-Regular, Consolas, monospace",
      fontSize: initialCodeFontSizeRef.current,
      theme: initialThemeRef.current ?? readTerminalTheme(),
    });
    terminalRef.current = terminal;
    const fitAddon = new FitAddon();
    fitAddonRef.current = fitAddon;
    const searchAddon = new SearchAddon();
    terminal.loadAddon(fitAddon);
    terminal.loadAddon(searchAddon);
    searchAddonRef.current = searchAddon;
    terminal.open(host);
    if (initialDataRef.current !== undefined && initialDataRef.current.length > 0) {
      terminal.write(
        initialDataRef.current instanceof Uint8Array
          ? initialDataRef.current
          : Uint8Array.from(initialDataRef.current),
      );
    } else if (initialTextRef.current.length > 0) {
      terminal.write(initialTextRef.current);
    }
    callbacks.current.onAttach?.();
    const dataSubscription = terminal.onData((data) => callbacks.current.onData?.(data));
    const resizeSubscription = terminal.onResize((size) => callbacks.current.onResize?.(size));
    /** 复制当前选择时不转换终端字节，也不改变选择语义。 */
    const copySelection = async (): Promise<void> => {
      const selection = terminal.getSelection();
      if (selection.length === 0) return;
      if (callbacks.current.onCopy !== undefined) {
        await callbacks.current.onCopy(selection);
        return;
      }
      await globalThis.navigator?.clipboard?.writeText(selection);
    };
    /** 只使用显式注入的可信 paste hook；否则普通 paste 事件与隐藏 textarea 仍由 xterm 拥有。 */
    const pasteSelection = async (readPaste: () => string | Promise<string>): Promise<void> => {
      const data = await readPaste();
      if (data !== undefined && data.length > 0) callbacks.current.onData?.(data);
    };
    /** Ctrl/Command 快捷键留在终端 UX 内，同时保留 Ctrl+C 中断行为。 */
    const keyHandler = (event: KeyboardEvent): boolean => {
      const modifier = event.ctrlKey || event.metaKey;
      // IME/process key 与 VK_PACKET 辅助输入必须继续进入 xterm CompositionHelper；此处返回 false
      // 会在官方状态机观察 keyCode 229 或 textarea 输入前提前短路。
      const textareaOwnedInput =
        !modifier &&
        !event.altKey &&
        (event.isComposing ||
          event.key === "Process" ||
          event.key === "Unidentified" ||
          event.keyCode === 229 ||
          event.keyCode === 231 ||
          (event.code.length === 0 && event.key.length === 1));
      if (textareaOwnedInput) return true;
      if (modifier && event.key.toLowerCase() === "f") {
        setSearchOpen(true);
        return false;
      }
      if (modifier && event.shiftKey && event.key.toLowerCase() === "c") {
        void copySelection();
        return false;
      }
      if (modifier && !event.shiftKey && event.key.toLowerCase() === "v") {
        const readPaste = callbacks.current.onPaste;
        if (readPaste === undefined) return true;
        void pasteSelection(readPaste);
        return false;
      }
      return true;
    };
    const terminalExtensions = terminal as Terminal & {
      attachCustomKeyEventHandler?: (handler: (event: KeyboardEvent) => boolean) => void;
      registerLinkProvider?: (provider: {
        provideLinks: (lineNumber: number, callback: (links: ILink[] | undefined) => void) => void;
      }) => { dispose: () => void };
    };
    terminalExtensions.attachCustomKeyEventHandler?.(keyHandler);
    const linkProvider =
      terminalExtensions.registerLinkProvider === undefined
        ? undefined
        : terminalExtensions.registerLinkProvider({
            provideLinks: (lineNumber, callback): void => {
              callback(terminalLinksForLine(terminal, lineNumber, openExternalUrlOnce));
            },
          });
    const fit = (): void => {
      try {
        fitAddon.fit();
        callbacks.current.onResize?.({ cols: terminal.cols, rows: terminal.rows });
      } catch {
        // 隐藏标签可能拥有零尺寸布局，下一次 ResizeObserver 事件会重试。
      }
    };
    const frame: number | undefined =
      typeof requestAnimationFrame === "undefined" ? undefined : requestAnimationFrame(fit);
    const observer = typeof ResizeObserver === "undefined" ? undefined : new ResizeObserver(fit);
    observer?.observe(host);
    if (observer === undefined) fit();
    return () => {
      if (frame !== undefined && typeof cancelAnimationFrame !== "undefined")
        cancelAnimationFrame(frame);
      observer?.disconnect();
      dataSubscription.dispose();
      resizeSubscription.dispose();
      linkProvider?.dispose();
      callbacks.current.onDetach?.();
      fitAddon.dispose();
      if (fitAddonRef.current === fitAddon) fitAddonRef.current = null;
      searchAddon.dispose();
      terminal.dispose();
      if (terminalRef.current === terminal) {
        terminalRef.current = null;
        searchAddonRef.current = null;
        host.replaceChildren();
      }
    };
  }, [openExternalUrlOnce]);

  /** 字号变化只更新 xterm option 并重新拟合网格，保留同一 PTY、scrollback、选区和订阅。 */
  useEffect(() => {
    const terminal = terminalRef.current;
    if (terminal === null) return;
    terminal.options.fontSize = codeFontSize;
    try {
      fitAddonRef.current?.fit();
      callbacks.current.onResize?.({ cols: terminal.cols, rows: terminal.rows });
    } catch {
      terminal.refresh(0, Math.max(0, terminal.rows - 1));
    }
  }, [codeFontSize]);

  /**
   * 仅更新 xterm 暴露的可变 theme option；resolvedTheme 与 palette 只作为根 token 已切换的
   * 触发器，保持实例、scrollback、选择、搜索状态和 PTY 订阅原地不变。
   */
  useEffect(() => {
    const terminal = terminalRef.current;
    if (terminal !== null) {
      terminal.options.theme = theme ?? readTerminalTheme();
    }
  }, [palette, resolvedTheme, theme]);

  /** 写入已确认批次中的全部未见成员；由 xterm owner 跟踪身份，避免 React 批处理丢失与 rerender 重放。 */
  useEffect(() => {
    const terminal = terminalRef.current;
    if (terminal === null) return;
    const pending = outputs ?? (output === undefined ? [] : [output]);
    let throughSequence: number | string | undefined;
    for (const chunk of pending) {
      const sequenceKey = `${typeof chunk.sequence}:${String(chunk.sequence)}`;
      if (!consumedOutputSequencesRef.current.has(sequenceKey) && chunk.data.length > 0) {
        // 保持 PTY 字节原样和原序，xterm 才能有状态地解码跨 chunk UTF-8 与 ANSI sequence。
        terminal.write(chunk.data instanceof Uint8Array ? chunk.data : Uint8Array.from(chunk.data));
      }
      consumedOutputSequencesRef.current.add(sequenceKey);
      throughSequence = chunk.sequence;
    }
    while (consumedOutputSequencesRef.current.size > 4_096) {
      const oldest = consumedOutputSequencesRef.current.values().next().value as string | undefined;
      if (oldest === undefined) break;
      consumedOutputSequencesRef.current.delete(oldest);
    }
    if (throughSequence !== undefined) callbacks.current.onOutputsConsumed?.(throughSequence);
  }, [output, outputs]);

  /**
   * 从整个渲染表面聚焦 xterm；IME textarea 刻意位于屏幕外，WebView2 不能稳定地把
   * 子 canvas 点击转发给该隐藏输入。
   */
  const focusTerminal = (): void => {
    terminalRef.current?.focus();
  };

  /**
   * 普通点击只用于选择，WebView2 激活 fallback 复用同一 provider 范围；短身份栅栏阻止
   * xterm mouseup 与 React click 重复打开同一 URL。
   */
  const openTerminalLink = (event: ReactMouseEvent<HTMLDivElement>): void => {
    if (event.button !== 0 || (!event.ctrlKey && !event.metaKey)) return;
    const terminal = terminalRef.current;
    const screen = hostRef.current?.querySelector(".xterm-screen");
    if (terminal === null || screen === null || screen === undefined) return;
    const link = terminalLinkAtViewportPoint(
      terminal,
      screen,
      event.clientX,
      event.clientY,
      openExternalUrlOnce,
    );
    link?.activate(event.nativeEvent, link.text);
  };

  /**
   * 等 React 删除搜索输入后再恢复 xterm 焦点；仅在 click handler 聚焦不足以避免即将消失的
   * 按钮或输入在 handler 返回后重新取得或清除 WebView2 焦点。
   */
  const closeSearch = (): void => {
    restoreFocusAfterSearchRef.current = true;
    setSearchOpen(false);
    searchAddonRef.current?.clearDecorations();
    terminalRef.current?.clearSelection();
  };

  /** 搜索 overlay commit 完成后才恢复隐藏 xterm textarea。 */
  useEffect(() => {
    if (searchOpen || !restoreFocusAfterSearchRef.current) return;
    restoreFocusAfterSearchRef.current = false;
    terminalRef.current?.focus();
  }, [searchOpen]);

  /**
   * 换行与 scrollback 遍历委托给 xterm 官方 search addon；Enter 仍传递 DOM 当前值，
   * 因为原生输入可能早于 React 提交受控 input render。
   */
  const findNext = (rawQuery: string = searchQuery): void => {
    const searchAddon = searchAddonRef.current;
    const query = rawQuery.trim();
    if (searchAddon === null || query.length === 0) {
      searchAddon?.clearDecorations();
      setSearchMatch(undefined);
      return;
    }
    const found = searchAddon.findNext(query, { caseSensitive: false, incremental: false });
    setSearchMatch(found ? "已找到" : "未找到");
  };

  return (
    <div
      className="ja-terminal-panel"
      ref={hostRef}
      role="application"
      aria-label={ariaLabel}
      onPointerDownCapture={focusTerminal}
      onClickCapture={openTerminalLink}
    >
      {searchOpen ? (
        <div className="ja-terminal-search" role="search">
          <input
            autoFocus
            aria-label="终端搜索"
            value={searchQuery}
            onChange={(event) => setSearchQuery(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                findNext(event.currentTarget.value);
              }
              if (event.key === "Escape") {
                event.preventDefault();
                closeSearch();
              }
            }}
            placeholder="搜索终端输出"
          />
          <span aria-live="polite">{searchMatch ?? ""}</span>
          <IconButton label="关闭终端搜索" onClick={closeSearch}>
            <X aria-hidden="true" size={13} />
          </IconButton>
        </div>
      ) : null}
    </div>
  );
}
