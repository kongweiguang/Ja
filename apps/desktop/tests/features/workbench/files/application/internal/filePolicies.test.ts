// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import {
  isSameOrDescendantPath,
  joinRelativePath,
  remapMovedPath,
  suggestedSaveAsPath,
  validateSaveAsPath,
} from "@/features/workbench/files/application/internal/filePathPolicy";
import {
  documentFromRead,
  mapSearchHits,
  revisionsEqual,
} from "@/features/workbench/files/application/internal/fileProjection";
import {
  isConflictError,
  isRecycleUnavailableError,
} from "@/features/workbench/files/application/internal/nativeErrorPolicy";
import type { FileRevision } from "@/features/workbench/files";

/** 构造完整 CAS identity，避免纯策略测试把缺失字段误当作相等。 */
function revision(sha256: string): FileRevision {
  return { kind: "file", size: 8, modifiedUnixMillis: 123, sha256 };
}

describe("Files application internal policies", () => {
  it("只按完整路径段判定和重映射移动范围", () => {
    expect(joinRelativePath("src", "main.ts")).toBe("src/main.ts");
    expect(joinRelativePath("", "main.ts")).toBe("main.ts");
    expect(isSameOrDescendantPath("src/main.ts", "src")).toBe(true);
    expect(isSameOrDescendantPath("src-next/main.ts", "src")).toBe(false);
    expect(remapMovedPath("src/nested/main.ts", "src", "lib")).toBe("lib/nested/main.ts");
    expect(remapMovedPath("src-next/main.ts", "src", "lib")).toBe("src-next/main.ts");
  });

  it("生成同目录副本建议并 fail-closed 拒绝不安全另存为路径", () => {
    expect(suggestedSaveAsPath("src/main.ts")).toBe("src/main.copy.ts");
    expect(suggestedSaveAsPath("README")).toBe("README.copy");
    expect(validateSaveAsPath(" src/copy.ts ", "src/main.ts")).toEqual({ path: "src/copy.ts" });
    expect(validateSaveAsPath("src/main.ts", "src/main.ts").error).toContain("不能覆盖");
    for (const unsafe of [
      "",
      "/root.ts",
      "C:/root.ts",
      "../escape.ts",
      "src//main.ts",
      "src\\main.ts",
      "src/\u0000main.ts",
    ]) {
      expect(validateSaveAsPath(unsafe, "src/main.ts").path).toBeUndefined();
    }
  });

  it("把 native 读取投影为安全文档并保留搜索定位", () => {
    const editable = documentFromRead(
      {
        path: "main.ts",
        kind: "text",
        content: "a\r\nb\r\n",
        revision: revision("a"),
        encoding: "utf8",
        newline: "crlf",
      },
      { line: 2, column: 1 },
    );
    expect(editable).toMatchObject({
      readOnly: false,
      newline: "crlf",
      status: "clean",
      reveal: { line: 2, column: 1 },
    });

    const uncertain = documentFromRead({
      path: "mixed.txt",
      kind: "text",
      content: "a\r\nb\n",
      revision: revision("b"),
      encoding: null,
    });
    expect(uncertain).toMatchObject({ readOnly: true, readOnlyReason: "文件编码未知，只读" });
    expect(mapSearchHits([{ id: "hit", path: "main.ts", line: 2, preview: "main" }])).toEqual([
      {
        id: "hit",
        path: "main.ts",
        line: 2,
        column: undefined,
        preview: "main",
        matchStart: undefined,
        matchLength: undefined,
      },
    ]);
  });

  it("按完整 revision 与稳定错误码收窄并发和回收站策略", () => {
    expect(revisionsEqual(revision("same"), revision("same"))).toBe(true);
    expect(revisionsEqual(revision("left"), revision("right"))).toBe(false);
    expect(revisionsEqual(revision("same"), null)).toBe(false);
    expect(isConflictError({ code: "REVISION_CONFLICT" })).toBe(true);
    expect(isConflictError(new Error("REVISION_CONFLICT"))).toBe(false);
    expect(isRecycleUnavailableError({ code: "RECYCLE_UNAVAILABLE" })).toBe(true);
    expect(isRecycleUnavailableError({ code: "CONFLICT" })).toBe(false);
  });
});
