// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { useThreadWorkbenchState } from "@/app/application/useThreadWorkbenchState";

describe("会话工作面投影", () => {
  it("同项目切换不继承内容，迟到回调只更新原会话", () => {
    const { result, rerender } = renderHook(
      ({ scope }) => useThreadWorkbenchState<string | undefined>(scope, undefined),
      { initialProps: { scope: "1:ws_project:thr_a" } },
    );
    const updateA = result.current[1];
    act(() => updateA("a.txt"));
    rerender({ scope: "1:ws_project:thr_b" });
    expect(result.current[0]).toBeUndefined();
    act(() => result.current[1]("b.txt"));
    act(() => updateA((current) => `${current}:saved`));
    expect(result.current[0]).toBe("b.txt");
    rerender({ scope: "1:ws_project:thr_a" });
    expect(result.current[0]).toBe("a.txt:saved");
    rerender({ scope: "2:ws_project:thr_a" });
    expect(result.current[0]).toBeUndefined();
  });
});
