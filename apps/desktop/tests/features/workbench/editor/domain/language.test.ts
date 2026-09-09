// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { EditorState } from "@codemirror/state";
import { ensureSyntaxTree } from "@codemirror/language";
import { highlightTree, classHighlighter } from "@lezer/highlight";
import { describe, expect, it } from "vitest";
import { languageExtension } from "@/shared/syntax";

const samples = [
  ["main.py", 'def hello():\n  return "hello"'],
  ["main.go", 'package main\nvar name = "hello"'],
  ["main.c", "int main() { return 0; }"],
  ["main.cpp", "class Example { public: int count = 1; };"],
  ["main.cs", 'public class Example { string name = "hello"; }'],
  ["main.kt", 'val name = "hello"'],
  ["index.html", '<div class="hello">Hello</div>'],
  ["style.css", ".hello { color: red; }"],
  ["style.scss", "$color: red; .hello { color: $color; }"],
  ["style.less", "@color: red; .hello { color: @color; }"],
  ["pom.xml", '<project version="1"/>'],
  ["icon.svg", '<svg width="12"/>'],
  ["query.sql", "SELECT name FROM users WHERE id = 1;"],
  ["config.yaml", 'name: "hello"'],
  ["config.yml", "enabled: true"],
  ["Cargo.toml", '[package]\nname = "hello"'],
  ["config.ini", "[section]\nname=hello"],
  ["app.properties", "name=hello"],
  [".env.local", 'NAME="hello"'],
  ["Dockerfile.dev", 'FROM node:24\nRUN echo "hello"'],
  ["script.sh", 'echo "hello"'],
  [".zshrc", 'export NAME="hello"'],
  ["script.ps1", '$name = "hello"'],
  ["main.rb", 'def hello\n puts "hello"\nend'],
  ["main.mts", 'const name: string = "hello";'],
  ["main.cjs", 'const name = "hello";'],
  ["main.java", "class Example { int count = 1; }"],
  ["main.rs", 'let name = "hello";'],
  ["config.json", '{"name": "hello"}'],
  ["readme.md", "# Hello"],
] as const;

describe("shared file syntax", () => {
  /** 验证真实 parser 产出语义 token，而不是只检查映射非空导致假阳性。 */
  it.each(samples)("highlights %s", (path, content) => {
    const extension = languageExtension(path);
    expect(extension).toBeDefined();
    const state = EditorState.create({ doc: content, extensions: [extension!] });
    const tree = ensureSyntaxTree(state, content.length, 1000);
    expect(tree).not.toBeNull();
    const tokens: string[] = [];
    highlightTree(tree!, classHighlighter, (_from, _to, classes) => tokens.push(classes));
    expect(tokens.length).toBeGreaterThan(0);
  });

  /** Windows 路径、显式别名和未知提示不能丢失文件名所提供的语法信息。 */
  it("normalizes paths and falls back from unknown hints", () => {
    for (const [path, hint] of [
      ["C:\\dir.ext\\MAIN.PY", undefined],
      ["snippet", "typescript"],
      ["main.py", "text"],
      ["main.py", " "],
      ["main.py", "constructor"],
    ]) {
      expect(languageExtension(path!, hint)).toBeDefined();
    }
    expect(languageExtension("dir.py/README")).toBeUndefined();
    expect(languageExtension("notes.txt")).toBeUndefined();
    expect(languageExtension("constructor")).toBeUndefined();
  });
});
