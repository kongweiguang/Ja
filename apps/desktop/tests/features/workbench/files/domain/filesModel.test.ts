// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import {
  canMoveEntry,
  detectNewlineStyle,
  mergeTreePageWithLoadedDescendants,
  parentPath,
  replaceTreeChildren,
} from "@/features/workbench/files/domain/filesModel";

describe("file workspace model", () => {
  const file = (path: string) => ({
    id: `file:${path}`,
    name: path.split("/").pop() ?? path,
    path,
    kind: "file" as const,
  });
  const directory = (path: string) => ({
    id: `directory:${path}`,
    name: path.split("/").pop() ?? path,
    path,
    kind: "directory" as const,
    hasChildren: true,
  });

  it("rejects self, descendant and sibling collision moves before IPC", () => {
    const source = directory("src");
    expect(canMoveEntry(source, source)).toBe(false);
    expect(canMoveEntry(source, directory("src/nested"))).toBe(false);
    expect(canMoveEntry(source, directory("tests"), ["src"])).toBe(false);
    expect(canMoveEntry(source, directory("tests"), [])).toBe(true);
  });

  /** 目录 page 只能替换目标子树，无关兄弟节点与父目录推导必须保持稳定。 */
  it("replaces only one lazy directory", () => {
    const next = replaceTreeChildren(
      [{ ...directory("src"), children: [] }, file("README.md")],
      "src",
      [file("src/App.tsx")],
    );
    expect(next[0]?.children?.[0]?.path).toBe("src/App.tsx");
    expect(next[1]?.path).toBe("README.md");
    expect(parentPath("src/App.tsx")).toBe("src");
  });

  it("keeps an independently loaded subtree across parent-page refresh order", () => {
    const moved = file("target/renamed.txt");
    const current = [{ ...directory("target"), children: [moved], loading: false }];
    const incoming = [directory("target")];

    const merged = mergeTreePageWithLoadedDescendants(current, incoming);
    expect(merged).toMatchObject([{ path: "target", children: [{ path: "target/renamed.txt" }] }]);
    expect(merged[0]).toBe(current[0]);
    expect(mergeTreePageWithLoadedDescendants(current, [])).toEqual([]);
  });

  it("treats one-line text as LF and mixed line endings as unsafe", () => {
    expect(detectNewlineStyle("one line")).toBe("lf");
    expect(detectNewlineStyle("a\r\nb\r\nc")).toBe("crlf");
    expect(detectNewlineStyle("a\r\nb\nc")).toBe("mixed");
  });
});
