// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { createSyntaxBudget, highlightCodeLines } from "@/shared/syntax";

describe("shared syntax highlighting", () => {
  it.each([
    ["main.ts", 'const message = "hello";'],
    ["Main.java", 'class Main { String value = "hello"; }'],
    ["main.rs", 'let value = "hello";'],
    ["main.py", 'value = "hello"'],
    ["config.json", '{"value": "hello"}'],
    ["config.yaml", 'value: "hello"'],
    ["config.toml", 'value = "hello"'],
    ["main.ps1", '$value = "hello"'],
  ])("uses the Files parser for %s without changing text", (path, text) => {
    const highlighted = highlightCodeLines(path, [text], {
      deadline: performance.now() + 1000,
      remainingCharacters: 65536,
    });
    expect(highlighted?.[0]?.map((token) => token.text).join("")).toBe(text);
    expect(highlighted?.[0]?.some((token) => token.role === "string")).toBe(true);
  });

  it("preserves multi-line comments within a fragment", () => {
    const lines = ["/* comment", "still a comment", "*/ const value = 42;"];
    const tokens = highlightCodeLines("main.ts", lines, createSyntaxBudget());
    expect(tokens?.[1]).toEqual([{ text: lines[1], role: "comment" }]);
    expect(tokens?.[2]?.some((token) => token.role === "keyword")).toBe(true);
    expect(tokens?.[2]?.some((token) => token.role === "number")).toBe(true);
  });

  it("does not guess unknown languages or exceed the shared budget", () => {
    expect(
      highlightCodeLines("notes.unknown", ["const text = 2;"], createSyntaxBudget()),
    ).toBeUndefined();
    expect(
      highlightCodeLines("a.ts", ["const text = 2;"], { deadline: 0, remainingCharacters: 100 }),
    ).toBeUndefined();
    expect(
      highlightCodeLines("a.ts", ["const text = 2;"], {
        deadline: performance.now() + 1000,
        remainingCharacters: 2,
      }),
    ).toBeUndefined();
  });
});
