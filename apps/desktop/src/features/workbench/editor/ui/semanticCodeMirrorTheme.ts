// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import type { Extension } from "@codemirror/state";
import { EditorView } from "@codemirror/view";
import { semanticSyntaxRules } from "@/shared/syntax";
import type { UiPalette } from "@/shared/styles/theme";
import type { ResolvedTheme } from "@/shared/styles/theme";

const THEME_CACHE = new Map<string, Extension>();

/**
 * Editor 与 MergeView 只绑定稳定语义角色，具体 Palette 值继续由根节点 token 决定；
 * `dark` 标记仍交给 CodeMirror，以保留其原生控件在明暗模式下的正确基线。
 */
function createEditorTheme(resolvedTheme: ResolvedTheme): Extension {
  return EditorView.theme(
    {
      "&": {
        color: "var(--ja-syntax-plain)",
        backgroundColor: "var(--ja-editor-background)",
      },
      ".cm-scroller": {
        backgroundColor: "var(--ja-editor-background)",
      },
      ".cm-content": {
        caretColor: "var(--ja-syntax-url)",
      },
      ".cm-cursor, .cm-dropCursor": {
        borderLeftColor: "var(--ja-syntax-url)",
      },
      "&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection": {
        backgroundColor:
          "color-mix(in srgb, var(--ja-syntax-url) 28%, var(--ja-editor-background))",
      },
      ".cm-activeLine": {
        backgroundColor:
          "color-mix(in srgb, var(--ja-syntax-plain) 4%, var(--ja-editor-background))",
      },
      ".cm-gutters": {
        color: "var(--ja-syntax-comment)",
        backgroundColor: "var(--ja-editor-background)",
        borderRight:
          "1px solid color-mix(in srgb, var(--ja-syntax-plain) 10%, var(--ja-editor-background))",
      },
      ".cm-activeLineGutter": {
        color: "var(--ja-syntax-plain)",
        backgroundColor:
          "color-mix(in srgb, var(--ja-syntax-plain) 6%, var(--ja-editor-background))",
      },
      ".cm-matchingBracket": {
        backgroundColor:
          "color-mix(in srgb, var(--ja-syntax-url) 22%, var(--ja-editor-background))",
        outline: "1px solid var(--ja-syntax-url)",
      },
      ".cm-nonmatchingBracket": {
        color: "var(--ja-syntax-keyword)",
        backgroundColor:
          "color-mix(in srgb, var(--ja-syntax-keyword) 18%, var(--ja-editor-background))",
      },
    },
    { dark: resolvedTheme === "dark" },
  );
}

/**
 * 明暗两套高亮都消费相同的语义角色，使四套 Palette 能完整覆盖语法色；不依赖
 * CodeMirror 默认浅色，从而避免浅色与深色切换时出现厂商气质断层。
 */
function createHighlightStyle(resolvedTheme: ResolvedTheme): Extension {
  return syntaxHighlighting(
    HighlightStyle.define(
      semanticSyntaxRules.map(({ tag, role }) => ({ tag, color: `var(--ja-syntax-${role})` })),
      { all: { color: "var(--ja-syntax-plain)" }, themeType: resolvedTheme },
    ),
  );
}

/**
 * 每个 `Palette x resolved mode` 组合只创建一次 extension；热切换重配 Compartment 时复用
 * 稳定对象，避免长期反复切换向 CodeMirror style module 累积等价规则。
 */
export function semanticCodeMirrorThemeExtension(
  palette: UiPalette,
  resolvedTheme: ResolvedTheme,
): Extension {
  const cacheKey = `${palette}-${resolvedTheme}`;
  const cached = THEME_CACHE.get(cacheKey);
  if (cached !== undefined) return cached;

  const extension: Extension = [
    EditorView.editorAttributes.of({ "data-ja-code-theme": cacheKey }),
    createEditorTheme(resolvedTheme),
    createHighlightStyle(resolvedTheme),
  ];
  THEME_CACHE.set(cacheKey, extension);
  return extension;
}
