// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { threadWorkbenchStorage } from "@/app/composition/threadWorkbenchStorage";

describe("会话工作面存储", () => {
  it("布局和预览 hint 不读取旧项目键、不删除其它会话", () => {
    localStorage.clear();
    localStorage.setItem("layout", "old-project");
    const a = threadWorkbenchStorage(localStorage, "ws_a:thr_a");
    const b = threadWorkbenchStorage(localStorage, "ws_a:thr_b");
    expect(a.getItem("layout")).toBeNull();
    a.setItem("layout", "A");
    b.setItem("layout", "B");
    a.removeItem("layout");
    expect(b.getItem("layout")).toBe("B");
    expect(localStorage.getItem("layout")).toBe("old-project");
    localStorage.clear();
  });
});
