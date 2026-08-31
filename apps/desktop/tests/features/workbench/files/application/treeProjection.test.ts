// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { mapTreeEntries, type FileRevision } from "@/features/workbench/files";

/** 构造完整 native CAS 身份，确保 DTO 映射测试不会弱化真实端口合同。 */
function revision(kind: FileRevision["kind"], sha256: string): FileRevision {
  return { kind, size: 10, modifiedUnixMillis: null, sha256 };
}

describe("tree projection", () => {
  /** application 只映射稳定相对路径与目录能力，不把 revision 复制进 UI 节点状态。 */
  it("maps native tree entries into Files domain nodes", () => {
    const tree = mapTreeEntries([
      {
        name: "src",
        relativePath: "src",
        kind: "directory",
        revision: revision("directory", "d1"),
      },
      {
        name: "README.md",
        relativePath: "README.md",
        kind: "file",
        revision: revision("file", "f1"),
      },
    ]);

    expect(tree).toEqual([
      { id: "directory:src", name: "src", path: "src", kind: "directory", hasChildren: true },
      {
        id: "file:README.md",
        name: "README.md",
        path: "README.md",
        kind: "file",
        hasChildren: false,
      },
    ]);
  });
});
