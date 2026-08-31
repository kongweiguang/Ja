// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  Command,
  FolderOpen,
  LoaderCircle,
  PanelRight,
  Plus,
  Search,
  Settings2,
  X,
} from "lucide-react";
import { useRef, type KeyboardEvent, type ReactElement } from "react";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from "@/shared/ui/primitives";
import type {
  CommandPaletteActions,
  CommandPaletteViewModel,
} from "../application/useCommandPaletteController";
import type { CommandIcon } from "../domain/commandRegistry";
import "./command-palette.css";

export interface CommandPaletteProps {
  readonly open: boolean;
  readonly viewModel: CommandPaletteViewModel;
  readonly actions: CommandPaletteActions;
}

const COMMAND_LIST_ID = "ja-command-list";

/** 生成安全且确定的 DOM id，供 aria-activedescendant 与 listbox option 共享。 */
function commandDomId(id: string): string {
  return `ja-command-${id.replace(/[^a-zA-Z0-9_-]/gu, "-")}`;
}

/** 将稳定语义图标映射为 UI 元素；未知值无法越过 CommandIcon 联合类型进入视图。 */
function commandIcon(icon: CommandIcon | undefined): ReactElement {
  switch (icon) {
    case "folder-open":
      return <FolderOpen />;
    case "panel-right":
      return <PanelRight />;
    case "plus":
      return <Plus />;
    case "settings":
      return <Settings2 />;
    case "command":
    case undefined:
      return <Command />;
  }
}

/**
 * UI 只把 viewModel 渲染成键盘可访问的 Dialog，并把 DOM/IME 意图转交 application actions；
 * 搜索、single-flight 与失败恢复不在组件内复制。
 */
export function CommandPalette({ open, viewModel, actions }: CommandPaletteProps): ReactElement {
  const inputRef = useRef<HTMLInputElement>(null);
  const composingRef = useRef(false);
  const restoreFocusRef = useRef<HTMLElement | null>(null);
  const activeCommand = viewModel.commands.find(
    (command) => command.id === viewModel.activeCommandId,
  );

  /**
   * 输入框只翻译键盘意图；忽略 composition 与 keyCode 229，让 Enter 先提交 CJK IME，
   * 选择边界和循环规则仍由 application controller 统一处理。
   */
  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>): void => {
    const nativeEvent = event.nativeEvent as KeyboardEvent<HTMLInputElement>["nativeEvent"] & {
      keyCode?: number;
    };
    if (composingRef.current || nativeEvent.isComposing || nativeEvent.keyCode === 229) return;
    if (viewModel.commands.length === 0) return;

    const moveBy = (delta: number): void => {
      event.preventDefault();
      actions.moveSelection(delta);
    };

    if (event.key === "ArrowDown" || (event.key.toLocaleLowerCase() === "n" && event.ctrlKey)) {
      moveBy(1);
    } else if (
      event.key === "ArrowUp" ||
      (event.key.toLocaleLowerCase() === "p" && event.ctrlKey)
    ) {
      moveBy(-1);
    } else if (event.key === "Home") {
      event.preventDefault();
      actions.selectCommand(viewModel.commands[0]?.id ?? "");
    } else if (event.key === "End") {
      event.preventDefault();
      actions.selectCommand(viewModel.commands.at(-1)?.id ?? "");
    } else if (event.key === "PageDown") {
      moveBy(Math.max(1, Math.min(8, viewModel.commands.length - 1)));
    } else if (event.key === "PageUp") {
      moveBy(-Math.max(1, Math.min(8, viewModel.commands.length - 1)));
    } else if (event.key === "Enter" && activeCommand !== undefined) {
      event.preventDefault();
      actions.executeCommand(activeCommand.id);
    }
  };

  /** IME 正在提交文本时阻止 Radix 关闭 Palette，避免丢失组合输入。 */
  const handleEscape = (event: globalThis.KeyboardEvent): void => {
    if (composingRef.current) event.preventDefault();
  };

  return (
    <Dialog open={open} onOpenChange={actions.changeOpen}>
      <DialogContent
        className="ja-command-palette"
        overlayClassName="ja-command-overlay"
        aria-describedby="ja-command-description"
        onOpenAutoFocus={(event) => {
          const content = event.currentTarget as HTMLDivElement;
          const active = content.ownerDocument.activeElement;
          if (active instanceof HTMLElement && !content.contains(active))
            restoreFocusRef.current = active;
          event.preventDefault();
          requestAnimationFrame(() => inputRef.current?.focus());
        }}
        onCloseAutoFocus={(event) => {
          event.preventDefault();
          const target = restoreFocusRef.current;
          if (target?.isConnected) target.focus({ preventScroll: true });
          restoreFocusRef.current = null;
        }}
        onEscapeKeyDown={handleEscape}
      >
        <DialogTitle className="ja-visually-hidden">命令面板</DialogTitle>
        <DialogDescription className="ja-visually-hidden" id="ja-command-description">
          搜索并执行当前可用的 Ja 操作。
        </DialogDescription>
        <div className="ja-command-search">
          <Search aria-hidden="true" />
          <input
            ref={inputRef}
            autoComplete="off"
            value={viewModel.query}
            onChange={(event) => actions.changeQuery(event.target.value)}
            onCompositionStart={() => {
              composingRef.current = true;
            }}
            onCompositionEnd={() => {
              composingRef.current = false;
            }}
            onKeyDown={handleKeyDown}
            placeholder="搜索命令…"
            aria-label="搜索命令"
            aria-controls={COMMAND_LIST_ID}
            aria-expanded={open}
            aria-haspopup="listbox"
            aria-autocomplete="list"
            aria-busy={viewModel.busy}
            aria-activedescendant={
              activeCommand === undefined ? undefined : commandDomId(activeCommand.id)
            }
          />
          <DialogClose className="ja-command-close" aria-label="关闭命令面板">
            <X aria-hidden="true" />
          </DialogClose>
        </div>
        {viewModel.executionError === undefined ? null : (
          <p className="ja-command-error" role="alert">
            {viewModel.executionError}
          </p>
        )}
        <div
          className="ja-command-list"
          id={COMMAND_LIST_ID}
          role="listbox"
          aria-label="可用命令"
          aria-busy={viewModel.busy}
        >
          {viewModel.commands.length === 0 ? (
            <div className="ja-command-empty">
              <Command aria-hidden="true" />
              <strong>{viewModel.query === "" ? "暂无可用命令" : "没有匹配的命令"}</strong>
              <span>
                {viewModel.query === ""
                  ? "当前页面没有可执行的 Ja 操作。"
                  : "换一个关键词，或清空搜索。"}
              </span>
            </div>
          ) : (
            viewModel.commands.map((command) => (
              <button
                type="button"
                id={commandDomId(command.id)}
                key={command.id}
                className={`ja-command-item${command.id === viewModel.activeCommandId ? " is-active" : ""}${command.running ? " is-running" : ""}`}
                role="option"
                aria-selected={command.id === viewModel.activeCommandId}
                aria-posinset={viewModel.commands.indexOf(command) + 1}
                aria-setsize={viewModel.commands.length}
                aria-busy={command.running}
                disabled={command.running}
                onPointerMove={() => {
                  if (!command.running) actions.selectCommand(command.id);
                }}
                onClick={() => {
                  if (!command.running) actions.executeCommand(command.id);
                }}
              >
                <span className="ja-command-icon" aria-hidden="true">
                  {command.running ? (
                    <LoaderCircle className="ja-command-spinner" />
                  ) : (
                    commandIcon(command.icon)
                  )}
                </span>
                <span className="ja-command-copy">
                  <strong>{command.label}</strong>
                  {command.description === undefined ? null : <small>{command.description}</small>}
                </span>
                {command.shortcut === undefined ? null : <kbd>{command.shortcut}</kbd>}
              </button>
            ))
          )}
        </div>
        <footer className="ja-command-footer">
          <span>
            <kbd>↑</kbd>
            <kbd>↓</kbd> 选择
          </span>
          <span>
            <kbd>Enter</kbd> 执行
          </span>
          <span>
            <kbd>Esc</kbd> 关闭
          </span>
          {viewModel.busy ? (
            <span className="ja-command-running" role="status">
              执行中…
            </span>
          ) : null}
        </footer>
      </DialogContent>
    </Dialog>
  );
}
