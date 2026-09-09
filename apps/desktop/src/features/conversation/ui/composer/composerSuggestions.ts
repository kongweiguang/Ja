// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { ConversationContextReference } from "../../domain/userContent";

export type ComposerSuggestionKind = "workspace" | "skill" | "command";

export interface ComposerTrigger {
  kind: ComposerSuggestionKind;
  marker: "@" | "$" | "/";
  query: string;
  start: number;
  end: number;
  raw: string;
}

export interface ComposerSkillSuggestion {
  skillId: string;
  name: string;
  description: string;
  scope: "builtin" | "user" | "ja" | "project";
}

export interface ComposerWorkspaceSuggestion {
  relativePath: string;
  kind: "file" | "directory";
}

export interface ComposerWorkspaceSearchResult {
  threadId: string;
  workspaceId: string;
  generation: number;
  query: string;
  items: ComposerWorkspaceSuggestion[];
  truncated: boolean;
}

export interface ComposerSlashCommand {
  id: string;
  name: string;
  aliases: readonly string[];
  group?: string;
  icon?: "command" | "plan" | "goal";
  label: string;
  description: string;
  shortcut?: string;
  available: boolean;
  unavailableReason?: string;
  argument?: {
    mode: "optional" | "required";
    label: string;
    placeholder: string;
  };
  execute: (context: ComposerSlashCommandContext) => void | Promise<void>;
}

export interface ComposerSlashCommandContext {
  argument: string;
}

export interface ComposerSlashInvocation {
  name: string;
  argument: string;
}

/** 按 Unicode 空白切分光标所在 token，避免 Tab 等原生输入边界失效或正文片段误开面板。 */
export function findComposerTrigger(text: string, caret: number): ComposerTrigger | undefined {
  const boundedCaret = Math.max(0, Math.min(caret, text.length));
  let tokenStart = boundedCaret;
  while (tokenStart > 0 && !/\s/u.test(text[tokenStart - 1] ?? "")) tokenStart -= 1;
  let tokenEnd = boundedCaret;
  while (tokenEnd < text.length && !/\s/u.test(text[tokenEnd] ?? "")) tokenEnd += 1;
  const raw = text.slice(tokenStart, tokenEnd);
  const marker = raw[0];
  if (marker !== "@" && marker !== "$" && marker !== "/") return undefined;
  if (marker === "/" && text.slice(0, tokenStart).trim() !== "") return undefined;
  return {
    kind: marker === "@" ? "workspace" : marker === "$" ? "skill" : "command",
    marker,
    query: raw.slice(1),
    start: tokenStart,
    end: tokenEnd,
    raw,
  };
}

/** 选择完成只移除触发 token，保留其前后正文与用户原有空白。 */
export function removeComposerTrigger(text: string, trigger: ComposerTrigger): string {
  return `${text.slice(0, trigger.start)}${text.slice(trigger.end)}`;
}

/**
 * 仅解析整条以 slash 开头的命令，参数保留为结构化 command context；普通正文中的路径或
 * `/command extra`（命令未声明参数时）仍由 Composer 当作模型消息处理。
 */
export function parseComposerSlashInvocation(text: string): ComposerSlashInvocation | undefined {
  const match = /^\s*\/([^\s/]+)(?:\s+([\s\S]*))?\s*$/u.exec(text);
  if (match === null || match[1] === undefined) return undefined;
  return { name: match[1], argument: match[2]?.trim() ?? "" };
}

/** Skill 搜索在本地配置投影上做稳定的名称优先匹配，不触发新的目录 IO。 */
export function filterComposerSkills(
  skills: readonly ComposerSkillSuggestion[],
  query: string,
): ComposerSkillSuggestion[] {
  const normalized = query.normalize("NFKC").toLocaleLowerCase().trim();
  return skills
    .flatMap((skill, index) => {
      const name = skill.name.normalize("NFKC").toLocaleLowerCase();
      const description = skill.description.normalize("NFKC").toLocaleLowerCase();
      if (normalized !== "" && !name.includes(normalized) && !description.includes(normalized))
        return [];
      const score =
        normalized === "" ? 0 : name === normalized ? 3 : name.startsWith(normalized) ? 2 : 1;
      return [{ skill, index, score }];
    })
    .sort((left, right) => right.score - left.score || left.index - right.index)
    .map(({ skill }) => skill);
}

/** Slash 搜索同时覆盖 canonical name、别名和中文说明，排序保持键盘导航稳定。 */
export function filterComposerCommands(
  commands: readonly ComposerSlashCommand[],
  query: string,
): ComposerSlashCommand[] {
  const normalized = query.normalize("NFKC").toLocaleLowerCase().trim();
  return commands
    .flatMap((command, index) => {
      const names = [command.name, ...command.aliases].map((value) =>
        value.normalize("NFKC").toLocaleLowerCase(),
      );
      const searchable = [...names, command.label, command.description]
        .join(" ")
        .normalize("NFKC")
        .toLocaleLowerCase();
      if (normalized !== "" && !searchable.includes(normalized)) return [];
      const score =
        normalized === ""
          ? 0
          : names.includes(normalized)
            ? 3
            : names.some((name) => name.startsWith(normalized))
              ? 2
              : 1;
      return [{ command, index, score }];
    })
    .sort((left, right) => right.score - left.score || left.index - right.index)
    .map(({ command }) => command);
}

/** 把选中的目录项转换为不含正文的稳定引用。 */
export function workspaceSuggestionReference(
  workspaceId: string,
  suggestion: ComposerWorkspaceSuggestion,
): ConversationContextReference {
  return { type: "workspace_reference", workspaceId, ...suggestion };
}

/** 把已加载 Skill 摘要转换为单消息 Draft 引用，正文仍由 App Server 激活时读取。 */
export function skillSuggestionReference(
  suggestion: ComposerSkillSuggestion,
): ConversationContextReference {
  return {
    type: "skill_reference",
    skillId: suggestion.skillId,
    name: suggestion.name,
    description: suggestion.description,
    scope: suggestion.scope,
    available: true,
  };
}
