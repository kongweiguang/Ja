// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { Command, File, Folder, Lightbulb, RotateCw, Sparkles, Target } from "lucide-react";
import type { MouseEvent, ReactElement } from "react";
import type {
  ComposerSkillSuggestion,
  ComposerSlashCommand,
  ComposerSuggestionKind,
  ComposerWorkspaceSuggestion,
} from "./composerSuggestions";

export type ComposerSuggestionItem =
  | { kind: "workspace"; id: string; value: ComposerWorkspaceSuggestion }
  | { kind: "skill"; id: string; value: ComposerSkillSuggestion }
  | { kind: "command"; id: string; value: ComposerSlashCommand };

export interface ComposerSuggestionPanelProps {
  id: string;
  kind: ComposerSuggestionKind;
  state: "loading" | "ready" | "error";
  items: readonly ComposerSuggestionItem[];
  activeId?: string;
  error?: string;
  truncated?: boolean;
  onSelect: (item: ComposerSuggestionItem) => void;
  onActiveChange: (id: string) => void;
  onRetry?: () => void;
}

/** 建议行只展示目录摘要、Skill 摘要或内置命令，不读取引用目标内容。 */
function suggestionPresentation(item: ComposerSuggestionItem): {
  label: string;
  detail: string;
  suffix?: string;
  disabled: boolean;
} {
  if (item.kind === "workspace") {
    const parts = item.value.relativePath.split("/");
    return {
      label: parts.at(-1) || item.value.relativePath,
      detail: item.value.relativePath,
      disabled: false,
    };
  }
  if (item.kind === "skill") {
    return {
      label: item.value.name,
      detail: item.value.description || "本条消息启用",
      suffix: item.value.scope,
      disabled: false,
    };
  }
  return {
    label: item.value.label,
    detail: item.value.available
      ? item.value.description
      : (item.value.unavailableReason ?? "当前不可用"),
    suffix: item.value.shortcut ?? `/${item.value.name}`,
    disabled: !item.value.available,
  };
}

/** 映射统一的轻量线性图标，维持三类数据源的快速视觉辨认。 */
function suggestionIcon(item: ComposerSuggestionItem): ReactElement {
  if (item.kind === "skill") return <Sparkles />;
  if (item.kind === "command") {
    if (item.value.icon === "plan") return <Lightbulb />;
    if (item.value.icon === "goal") return <Target />;
    return <Command />;
  }
  return item.value.kind === "directory" ? <Folder /> : <File />;
}

/** Slash 命令按连续 group 分段，未分组项仍归入稳定的“常用”组，键盘 option 顺序不变。 */
function suggestionGroups(items: readonly ComposerSuggestionItem[]): readonly {
  key: string;
  label?: string;
  items: readonly ComposerSuggestionItem[];
}[] {
  if (!items.some((item) => item.kind === "command" && item.value.group !== undefined))
    return [{ key: "all", items }];
  const grouped = new Map<string, ComposerSuggestionItem[]>();
  for (const item of items) {
    const label = item.kind === "command" ? (item.value.group ?? "常用") : "";
    const current = grouped.get(label) ?? [];
    current.push(item);
    grouped.set(label, current);
  }
  return [...grouped.entries()].map(([label, entries]) => ({
    key: label || "all",
    label: label || undefined,
    items: entries,
  }));
}

/** 单行 option 保持非模态点击语义，group 包装不能改变 activeId 或 textarea 焦点。 */
function SuggestionOption({
  item,
  activeId,
  onSelect,
  onActiveChange,
}: {
  item: ComposerSuggestionItem;
  activeId?: string;
  onSelect: ComposerSuggestionPanelProps["onSelect"];
  onActiveChange: ComposerSuggestionPanelProps["onActiveChange"];
}): ReactElement {
  const presentation = suggestionPresentation(item);
  return (
    <button
      type="button"
      role="option"
      id={item.id}
      className={item.id === activeId ? "is-active" : undefined}
      aria-selected={item.id === activeId}
      aria-disabled={presentation.disabled || undefined}
      onMouseDown={(event: MouseEvent<HTMLButtonElement>) => event.preventDefault()}
      onPointerMove={() => onActiveChange(item.id)}
      onClick={() => {
        if (!presentation.disabled) onSelect(item);
      }}
    >
      <span className="ja-composer-suggestions__icon" aria-hidden="true">
        {suggestionIcon(item)}
      </span>
      <span className="ja-composer-suggestions__copy">
        <strong>{presentation.label}</strong>
        <small>{presentation.detail}</small>
      </span>
      {presentation.suffix === undefined ? null : <kbd>{presentation.suffix}</kbd>}
    </button>
  );
}

/** 面板保持非模态且不夺取 textarea 焦点；pointer down 由行阻止默认聚焦。 */
export function ComposerSuggestionPanel({
  id,
  kind,
  state,
  items,
  activeId,
  error,
  truncated = false,
  onSelect,
  onActiveChange,
  onRetry,
}: ComposerSuggestionPanelProps): ReactElement {
  const emptyCopy =
    kind === "workspace"
      ? "没有匹配的文件或目录"
      : kind === "skill"
        ? "没有匹配的已启用 Skill"
        : "没有匹配的 Ja 指令";
  const groups = suggestionGroups(items);
  return (
    <section className="ja-composer-suggestions" aria-label="输入建议">
      <div
        id={id}
        className="ja-composer-suggestions__list"
        role="listbox"
        aria-label={kind === "workspace" ? "文件与目录" : kind === "skill" ? "Skills" : "指令"}
        aria-busy={state === "loading" || undefined}
      >
        {state === "loading" ? (
          <div className="ja-composer-suggestions__loading" role="status">
            {[0, 1, 2].map((index) => (
              <span key={index} aria-hidden="true" />
            ))}
            <span className="ja-visually-hidden">正在加载建议…</span>
          </div>
        ) : state === "error" ? (
          <div className="ja-composer-suggestions__empty" role="alert">
            <strong>暂时无法读取建议</strong>
            <span>{error ?? "请稍后重试。"}</span>
            {onRetry === undefined ? null : (
              <button
                type="button"
                onMouseDown={(event) => event.preventDefault()}
                onClick={onRetry}
              >
                <RotateCw aria-hidden="true" />
                重试
              </button>
            )}
          </div>
        ) : items.length === 0 ? (
          <div className="ja-composer-suggestions__empty">
            <strong>{emptyCopy}</strong>
            <span>继续输入可缩小范围，Esc 保留原文字。</span>
          </div>
        ) : (
          groups.map((group) => (
            <div
              key={group.key}
              className="ja-composer-suggestions__group"
              role={group.label === undefined ? "presentation" : "group"}
              aria-label={group.label}
            >
              {group.label === undefined ? null : (
                <div className="ja-composer-suggestions__group-label" aria-hidden="true">
                  {group.label}
                </div>
              )}
              {group.items.map((item) => (
                <SuggestionOption
                  key={item.id}
                  item={item}
                  activeId={activeId}
                  onSelect={onSelect}
                  onActiveChange={onActiveChange}
                />
              ))}
            </div>
          ))
        )}
      </div>
      {truncated ? (
        <small className="ja-composer-suggestions__hint">继续输入以缩小范围</small>
      ) : null}
    </section>
  );
}
