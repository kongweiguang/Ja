// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 图标只保存稳定语义，不携带 ReactNode；具体图形由 UI 层映射，domain 因此保持纯净。
 */
export type CommandIcon = "command" | "folder-open" | "panel-right" | "plus" | "settings";

/**
 * Command descriptor 只描述稳定身份与可搜索元数据；可用性和副作用由 application
 * 持有，避免 domain 依赖 React 或执行端口。
 */
export interface CommandDescriptor {
  id: string;
  label: string;
  keywords: readonly string[];
  shortcut?: string;
  description?: string;
  icon?: CommandIcon;
}

/**
 * 建立稳定 registry snapshot 并丢弃空白或重复 id。重复项应由调用方修复，但保留
 * 第一项可让动态 feature 组合 fail closed，避免重复 DOM/listbox id 破坏无障碍引用。
 */
export function createCommandRegistry<T extends CommandDescriptor>(
  actions: readonly T[],
): readonly T[] {
  const ids = new Set<string>();
  const registry: T[] = [];
  for (const action of actions) {
    const id = action.id.trim();
    if (id === "" || ids.has(id)) continue;
    ids.add(id);
    registry.push({ ...action, id });
  }
  return registry;
}

/** 统一 Unicode 与大小写，同时保留中文子串匹配语义。 */
function normalizeSearchText(value: string): string {
  return value.normalize("NFKC").toLocaleLowerCase().trim();
}

/**
 * 在 label、description 与显式 keywords 上做 token 匹配和稳定相关性排序；前缀高于
 * 普通子串，同分保持 registry 顺序，使键盘移动可预测。
 */
export function searchCommandActions<T extends CommandDescriptor>(
  actions: readonly T[],
  query: string,
): readonly T[] {
  const normalizedQuery = normalizeSearchText(query);
  if (normalizedQuery === "") return actions;
  const tokens = normalizedQuery.split(/\s+/u).filter(Boolean);

  const ranked = actions.flatMap((action, index) => {
    const fields = [
      action.label,
      action.description ?? "",
      action.shortcut ?? "",
      ...action.keywords,
    ].map(normalizeSearchText);
    const searchable = fields.join(" ");
    if (!tokens.every((token) => searchable.includes(token))) return [];

    const label = normalizeSearchText(action.label);
    const score = tokens.reduce((total, token) => {
      if (label === token) return total + 120;
      if (label.startsWith(token)) return total + 90;
      if (label.includes(token)) return total + 60;
      return total + (fields.some((field) => field.startsWith(token)) ? 35 : 15);
    }, 0);
    return [{ action, index, score }];
  });

  return ranked
    .sort((left, right) => right.score - left.score || left.index - right.index)
    .map(({ action }) => action);
}
