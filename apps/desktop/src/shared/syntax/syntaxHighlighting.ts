// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { language } from "@codemirror/language";
import { EditorState } from "@codemirror/state";
import { highlightTree, tagHighlighter, tags } from "@lezer/highlight";
import { languageExtension } from "./language";

/** 文件编辑器与只读片段共用同一 tag 到主题角色映射，配色变化无需重新解析正文。 */
export const semanticSyntaxRules = [
  { tag: tags.comment, role: "comment" },
  { tag: tags.keyword, role: "keyword" },
  { tag: tags.name, role: "symbol" },
  { tag: [tags.typeName, tags.className, tags.namespace], role: "type" },
  { tag: tags.propertyName, role: "property" },
  { tag: [tags.attributeName, tags.annotation, tags.meta], role: "attribute" },
  { tag: [tags.variableName, tags.labelName, tags.macroName], role: "symbol" },
  { tag: [tags.string, tags.character, tags.regexp, tags.escape], role: "string" },
  { tag: [tags.number, tags.bool, tags.null, tags.atom], role: "number" },
  { tag: [tags.url, tags.link], role: "url" },
] as const;

const semanticHighlighter = tagHighlighter(
  semanticSyntaxRules.map(({ tag, role }) => ({ tag, class: role })),
);

export interface SyntaxToken {
  readonly text: string;
  readonly role?: string;
}

export interface SyntaxBudget {
  readonly deadline: number;
  remainingCharacters: number;
}

/** 同一次 Diff 投影共享预算，许多小 hunk 不能各自重置时限而长期占用 Worker。 */
export function createSyntaxBudget(): SyntaxBudget {
  return { deadline: performance.now() + 40, remainingCharacters: 256 * 1024 };
}

/**
 * 使用 Files 相同的官方 parser，不创建 EditorView 或额外 IO。片段保留跨行语境，未知语言
 * 与预算耗尽保持原始文本；不截断显示内容，也不让缺失的 hunk 间正文污染后续解析状态。
 */
export function highlightCodeLines(
  filePath: string,
  lines: readonly string[],
  budget: SyntaxBudget,
): readonly (readonly SyntaxToken[])[] | undefined {
  let length = 0;
  for (const line of lines) length += line.length + 1;
  if (length > budget.remainingCharacters || performance.now() >= budget.deadline) return;
  const extension = languageExtension(filePath);
  if (extension === undefined) return;
  const parser = EditorState.create({ extensions: [extension] }).facet(language)?.parser;
  if (parser === undefined) return;
  budget.remainingCharacters -= length;
  const content = lines.join("\n");
  const parse = parser.startParse(content);
  let tree = null;
  while (tree === null) {
    if (performance.now() >= budget.deadline) return;
    tree = parse.advance();
  }
  const offsets: number[] = [];
  let offset = 0;
  for (const line of lines) {
    offsets.push(offset);
    offset += line.length + 1;
  }
  const result: SyntaxToken[][] = lines.map(() => []);
  const cursors = lines.map(() => 0);
  let row = 0;
  highlightTree(tree, semanticHighlighter, (from, to, role) => {
    while (row < lines.length && offsets[row]! + lines[row]!.length < from) row += 1;
    for (let index = row; index < lines.length && offsets[index]! < to; index += 1) {
      const text = lines[index]!;
      const start = Math.max(from - offsets[index]!, cursors[index]!);
      const end = Math.min(to - offsets[index]!, text.length);
      if (start >= end) continue;
      if (start > cursors[index]!) result[index]!.push({ text: text.slice(cursors[index], start) });
      result[index]!.push({ text: text.slice(start, end), role });
      cursors[index] = end;
    }
  });
  for (let index = 0; index < lines.length; index += 1) {
    if (cursors[index]! < lines[index]!.length)
      result[index]!.push({ text: lines[index]!.slice(cursors[index]) });
  }
  return result;
}
