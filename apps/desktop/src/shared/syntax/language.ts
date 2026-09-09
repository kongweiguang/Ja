// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { html } from "@codemirror/lang-html";
import { java } from "@codemirror/lang-java";
import { javascript } from "@codemirror/lang-javascript";
import { json } from "@codemirror/lang-json";
import { markdown } from "@codemirror/lang-markdown";
import { rust } from "@codemirror/lang-rust";
import { StreamLanguage, type StreamParser } from "@codemirror/language";
import { c, cpp, csharp, kotlin } from "@codemirror/legacy-modes/mode/clike";
import { css, less, sCSS } from "@codemirror/legacy-modes/mode/css";
import { dockerFile } from "@codemirror/legacy-modes/mode/dockerfile";
import { go } from "@codemirror/legacy-modes/mode/go";
import { powerShell } from "@codemirror/legacy-modes/mode/powershell";
import { properties } from "@codemirror/legacy-modes/mode/properties";
import { python } from "@codemirror/legacy-modes/mode/python";
import { ruby } from "@codemirror/legacy-modes/mode/ruby";
import { shell } from "@codemirror/legacy-modes/mode/shell";
import { standardSQL } from "@codemirror/legacy-modes/mode/sql";
import { toml } from "@codemirror/legacy-modes/mode/toml";
import { xml } from "@codemirror/legacy-modes/mode/xml";
import { yaml } from "@codemirror/legacy-modes/mode/yaml";
import type { Extension } from "@codemirror/state";

const STREAM_MODES: Record<string, StreamParser<unknown>> = {
  c,
  h: c,
  cpp,
  cc: cpp,
  cxx: cpp,
  hpp: cpp,
  hh: cpp,
  hxx: cpp,
  cs: csharp,
  csharp,
  kt: kotlin,
  kts: kotlin,
  kotlin,
  css,
  scss: sCSS,
  less,
  xml,
  svg: xml,
  xsd: xml,
  xsl: xml,
  py: python,
  pyw: python,
  python,
  go,
  rb: ruby,
  ruby,
  sql: standardSQL,
  yaml,
  yml: yaml,
  toml,
  ini: properties,
  properties,
  cfg: properties,
  env: properties,
  sh: shell,
  bash: shell,
  zsh: shell,
  shell,
  ps1: powerShell,
  psm1: powerShell,
  psd1: powerShell,
  powershell: powerShell,
  dockerfile: dockerFile,
};

/** 只看文件名，避免带点目录误判；文件名优先于后缀，覆盖 Windows 路径和环境配置变体。 */
function fileLanguage(filePath: string): string {
  const name = filePath.replaceAll("\\", "/").split("/").pop()?.toLowerCase() ?? "";
  if (name === "dockerfile" || name.startsWith("dockerfile.")) return "dockerfile";
  if (name === ".env" || name.startsWith(".env.")) return "env";
  if ([".bashrc", ".zshrc", ".bash_profile", ".profile"].includes(name)) return "sh";
  return name.includes(".") ? (name.split(".").pop() ?? "") : name;
}

/**
 * 编辑、只读和 Diff 共用官方语法包；补充语言使用 CodeMirror 6 StreamLanguage，
 * 不引入第二套渲染器。未知提示回到文件名，未知文件保持纯文本而不猜测正文。
 */
export function languageExtension(filePath: string, language?: string): Extension | undefined {
  const hint = language?.trim().toLowerCase();
  const normalized = hint || fileLanguage(filePath);
  switch (normalized) {
    case "html":
    case "htm":
      return html();
    case "java":
      return java();
    case "js":
    case "javascript":
    case "mjs":
    case "cjs":
    case "jsx":
    case "ts":
    case "typescript":
    case "mts":
    case "cts":
    case "tsx":
      return javascript({
        jsx: normalized.endsWith("x"),
        typescript: ["ts", "tsx", "typescript", "mts", "cts"].includes(normalized),
      });
    case "json":
    case "jsonc":
      return json();
    case "md":
    case "mdx":
    case "markdown":
      return markdown();
    case "rs":
    case "rust":
      return rust();
    default:
      if (Object.hasOwn(STREAM_MODES, normalized))
        return StreamLanguage.define(STREAM_MODES[normalized]!);
      return hint && hint !== fileLanguage(filePath) ? languageExtension(filePath) : undefined;
  }
}
