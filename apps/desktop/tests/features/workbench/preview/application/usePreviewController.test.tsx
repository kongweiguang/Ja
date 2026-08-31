// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { PreviewPort } from "@/features/workbench/preview/application/ports";
import { usePreviewController } from "@/features/workbench/preview/application/usePreviewController";

afterEach(() => cleanup());

/** 构造稳定 fake port，使 hook 重渲染不会因测试对象抖动产生额外 effect。 */
function createPort(): PreviewPort {
  return {
    navigate: vi.fn(),
    reload: vi.fn(),
    retryRecovery: vi.fn(),
    changeViewport: vi.fn(),
  };
}

describe("usePreviewController", () => {
  it("校验失败时不调用导航 port", () => {
    const port = createPort();
    const { result } = renderHook(() =>
      usePreviewController({ url: "", loading: false, recovering: false, active: true, port }),
    );

    act(() => result.current.actions.changeDraft("javascript:alert(1)"));
    act(() => result.current.actions.submit());

    expect(result.current.viewModel.validationError).toContain("只支持 http:// 或 https://");
    expect(port.navigate).not.toHaveBeenCalled();
    expect(port.reload).not.toHaveBeenCalled();
  });

  it("把同 URL 路由为 reload，把新 URL 路由为 navigate", () => {
    const port = createPort();
    const { result } = renderHook(() =>
      usePreviewController({
        url: "https://example.com/path",
        loading: false,
        recovering: false,
        active: true,
        port,
      }),
    );

    act(() => result.current.actions.submit());
    expect(port.reload).toHaveBeenCalledOnce();

    act(() => result.current.actions.changeDraft("https://openai.com"));
    act(() => result.current.actions.submit());
    expect(port.navigate).toHaveBeenCalledWith("https://openai.com/");
  });

  it("native URL 变化后同步草稿并更新安全投影", () => {
    const port = createPort();
    const { result, rerender } = renderHook(
      ({ url }) =>
        usePreviewController({ url, loading: false, recovering: false, active: true, port }),
      { initialProps: { url: "https://example.com" } },
    );
    act(() => result.current.actions.changeDraft("https://draft.local"));

    rerender({ url: "https://openai.com/docs" });

    expect(result.current.viewModel.draft).toBe("https://openai.com/docs");
    expect(result.current.viewModel.projection?.origin).toBe("https://openai.com");
  });
});
