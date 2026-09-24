// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { File, Folder, Sparkles, X } from "lucide-react";
import { useRef, useState, type KeyboardEvent, type MouseEvent, type ReactElement } from "react";
import { MenuItem, MenuSeparator, PointerContextMenu } from "@/shared/ui/primitives";
import type { ConversationContextReference } from "../../domain/userContent";
import { splitFileName } from "./filePresentation";

interface ContextChipMenuSession {
  readonly key: number;
  readonly referenceKey: string;
  readonly x: number;
  readonly y: number;
  readonly opener: HTMLElement;
}

export interface ComposerContextChipsProps {
  references: readonly ConversationContextReference[];
  onRemove?: (reference: ConversationContextReference) => void;
  onOpenWorkspaceReference?: (
    reference: Extract<ConversationContextReference, { type: "workspace_reference" }>,
    source: HTMLButtonElement,
  ) => void;
  label?: string;
  compact?: boolean;
}

/** 长路径只在 Chip 主标签显示末段，完整身份留在 title 与读屏名称中。 */
function referencePresentation(reference: ConversationContextReference): {
  label: string;
  detail: string;
} {
  if (reference.type === "skill_reference") {
    return {
      label: reference.name?.trim() || "Skill 不可用",
      detail: reference.description?.trim() || "该 Skill 已停用、删除或不属于当前配置代际",
    };
  }
  const segments = reference.relativePath.split("/");
  return {
    label: segments.at(-1) || reference.relativePath,
    detail: reference.relativePath,
  };
}

/** Context 栏仅展示已选择身份；右键仅转发真实 Workspace 打开与引用移除回调，不新增状态所有者。 */
export function ComposerContextChips({
  references,
  onRemove,
  onOpenWorkspaceReference,
  label = "消息上下文",
  compact = false,
}: ComposerContextChipsProps): ReactElement | null {
  const [contextMenu, setContextMenu] = useState<ContextChipMenuSession>();
  const contextMenuKey = useRef(0);

  /** 文本选区和显式链接保留原始右键语义，不把普通文本动作误投到整条引用。 */
  const shouldPreserveContextMenu = (target: EventTarget | null): boolean => {
    if (window.getSelection()?.isCollapsed === false) return true;
    if (!(target instanceof Element)) return false;
    return target.closest("a, [data-file-reference], input, textarea, select") !== null;
  };

  /** 新目标递增键值，确保连续右击不同 Chip 时 Radix 重新读取指针锚点。 */
  const openContextMenu = (
    event: MouseEvent<HTMLLIElement> | KeyboardEvent<HTMLLIElement>,
    referenceKey: string,
  ): void => {
    const opener =
      event.target instanceof Element
        ? (event.target.closest<HTMLElement>("button, [role='button']") ?? event.currentTarget)
        : event.currentTarget;
    const bounds = event.currentTarget.getBoundingClientRect();
    contextMenuKey.current += 1;
    setContextMenu({
      key: contextMenuKey.current,
      referenceKey,
      x: "clientX" in event ? event.clientX : bounds.left,
      y: "clientY" in event ? event.clientY : bounds.bottom,
      opener,
    });
  };

  /** 键盘菜单入口在不抢走表单键的前提下使用 ContextMenu 与 Shift+F10。 */
  const isContextMenuKey = (event: KeyboardEvent<HTMLLIElement>): boolean =>
    event.key === "ContextMenu" || (event.key === "F10" && event.shiftKey);

  if (references.length === 0) return null;
  const activeReference =
    contextMenu === undefined
      ? undefined
      : references.find((reference) => {
          const identity =
            reference.type === "workspace_reference"
              ? `${reference.workspaceId}:${reference.relativePath}`
              : reference.skillId;
          return `${reference.type}:${identity}` === contextMenu.referenceKey;
        });
  const activeCanOpen =
    !compact &&
    activeReference?.type === "workspace_reference" &&
    onOpenWorkspaceReference !== undefined;
  const activeHasMenuActions =
    activeReference !== undefined && (activeCanOpen || onRemove !== undefined);
  return (
    <>
      <ul className={`ja-composer-context-list${compact ? " is-compact" : ""}`} aria-label={label}>
        {references.map((reference) => {
          const presentation = referencePresentation(reference);
          const fileName =
            reference.type === "workspace_reference" && reference.kind === "file"
              ? splitFileName(presentation.label)
              : undefined;
          const identity =
            reference.type === "workspace_reference"
              ? `${reference.workspaceId}:${reference.relativePath}`
              : reference.skillId;
          const canOpenWorkspaceReference =
            !compact &&
            reference.type === "workspace_reference" &&
            onOpenWorkspaceReference !== undefined;
          const referenceKey = `${reference.type}:${identity}`;
          const hasContextActions = canOpenWorkspaceReference || onRemove !== undefined;
          const content = (
            <>
              <span className="ja-composer-context__icon" aria-hidden="true">
                {reference.type === "skill_reference" ? (
                  <Sparkles />
                ) : reference.kind === "directory" ? (
                  <Folder />
                ) : (
                  <File />
                )}
              </span>
              <span className="ja-composer-context__copy" title={presentation.detail}>
                <strong>
                  {fileName === undefined ? (
                    presentation.label
                  ) : (
                    <>
                      <span>{fileName.stem}</span>
                      {fileName.extension === undefined ? null : <span>{fileName.extension}</span>}
                    </>
                  )}
                </strong>
                {compact ? null : <small>{presentation.detail}</small>}
              </span>
            </>
          );
          return (
            <li
              key={`${reference.type}:${identity}`}
              className="ja-composer-context__chip"
              data-reference-type={reference.type === "workspace_reference" ? "workspace" : "skill"}
              data-reference-kind={
                reference.type === "workspace_reference" ? reference.kind : undefined
              }
              data-available={
                reference.type === "skill_reference" ? reference.available !== false : undefined
              }
              data-openable={canOpenWorkspaceReference || undefined}
              tabIndex={hasContextActions ? -1 : undefined}
              aria-keyshortcuts={hasContextActions ? "ContextMenu Shift+F10" : undefined}
              onContextMenu={(event: MouseEvent<HTMLLIElement>) => {
                if (!hasContextActions || shouldPreserveContextMenu(event.target)) return;
                event.preventDefault();
                event.stopPropagation();
                openContextMenu(event, referenceKey);
              }}
              onKeyDown={(event: KeyboardEvent<HTMLLIElement>) => {
                if (!hasContextActions || !isContextMenuKey(event)) return;
                event.preventDefault();
                event.stopPropagation();
                openContextMenu(event, referenceKey);
              }}
            >
              {canOpenWorkspaceReference && reference.type === "workspace_reference" ? (
                <button
                  type="button"
                  className="ja-composer-context__open"
                  aria-label={
                    reference.kind === "directory"
                      ? `在文件中定位 ${presentation.label}`
                      : `在文件中预览 ${presentation.label}`
                  }
                  onClick={(event) => onOpenWorkspaceReference(reference, event.currentTarget)}
                >
                  {content}
                </button>
              ) : (
                content
              )}
              {onRemove === undefined ? null : (
                <button
                  type="button"
                  className="ja-composer-context__remove"
                  aria-label={`移除上下文 ${presentation.label}`}
                  title="移除"
                  onClick={() => onRemove(reference)}
                >
                  <X aria-hidden="true" />
                </button>
              )}
            </li>
          );
        })}
      </ul>
      {contextMenu === undefined ||
      activeReference === undefined ||
      !activeHasMenuActions ? null : (
        <PointerContextMenu
          key={contextMenu.key}
          x={contextMenu.x}
          y={contextMenu.y}
          label="引用操作"
          onOpenChange={(open) => {
            if (!open) {
              setContextMenu((current) => (current?.key === contextMenu.key ? undefined : current));
            }
          }}
          onRestoreFocus={() => {
            if (contextMenu.opener.isConnected) contextMenu.opener.focus();
          }}
        >
          {activeCanOpen && activeReference.type === "workspace_reference" ? (
            <MenuItem
              onSelect={() => {
                const chip = contextMenu.opener.closest(".ja-composer-context__chip");
                const source = chip?.querySelector<HTMLButtonElement>(".ja-composer-context__open");
                if (source !== null && source !== undefined) {
                  onOpenWorkspaceReference?.(activeReference, source);
                }
              }}
            >
              {activeReference.kind === "directory" ? (
                <Folder aria-hidden="true" />
              ) : (
                <File aria-hidden="true" />
              )}
              <span>{activeReference.kind === "directory" ? "打开文件夹" : "打开文件"}</span>
            </MenuItem>
          ) : null}
          {activeCanOpen && onRemove !== undefined ? <MenuSeparator /> : null}
          {onRemove === undefined ? null : (
            <MenuItem onSelect={() => onRemove(activeReference)}>
              <X aria-hidden="true" />
              <span>移除引用</span>
            </MenuItem>
          )}
        </PointerContextMenu>
      )}
    </>
  );
}
