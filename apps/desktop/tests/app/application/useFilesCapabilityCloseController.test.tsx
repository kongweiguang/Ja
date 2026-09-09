// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useFilesCapabilityCloseController } from "@/app/application/useFilesCapabilityCloseController";
import type { FilesWorkspaceCloseLease, FilesWorkspaceLifecycle } from "@/features/workbench/files";

/** 构造可控 Promise，以精确验证 flush ACK 前后的写入 fence 生命周期。 */
function deferred<T>(): {
  readonly promise: Promise<T>;
  readonly resolve: (value: T) => void;
  readonly reject: (reason: unknown) => void;
} {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

/** 生成最窄 Files lifecycle fake，避免测试接管真实编辑 buffer 状态。 */
function lifecycle(
  workspaceId: string,
  flushForWorkspaceChange: () => Promise<FilesWorkspaceCloseLease>,
): FilesWorkspaceLifecycle {
  return { workspaceId, flushForWorkspaceChange };
}

afterEach(() => cleanup());

describe("useFilesCapabilityCloseController", () => {
  it("flush ACK 前保持 fence，ACK 后立即释放并允许重新关闭", async () => {
    const firstFlush = deferred<FilesWorkspaceCloseLease>();
    const firstRelease = vi.fn();
    const secondRelease = vi.fn();
    const flushForWorkspaceChange = vi
      .fn<FilesWorkspaceLifecycle["flushForWorkspaceChange"]>()
      .mockImplementationOnce(() => firstFlush.promise)
      .mockResolvedValueOnce({ release: secondRelease });
    const { result } = renderHook(() => useFilesCapabilityCloseController());

    act(() => result.current.register(lifecycle("workspace-1", flushForWorkspaceChange)));
    const firstClose = result.current.closeCapability("workspace-1");
    const duplicateClose = result.current.closeCapability("workspace-1");
    expect(flushForWorkspaceChange).toHaveBeenCalledOnce();
    expect(firstRelease).not.toHaveBeenCalled();

    firstFlush.resolve({ release: firstRelease });
    await expect(Promise.all([firstClose, duplicateClose])).resolves.toEqual([
      undefined,
      undefined,
    ]);
    expect(firstRelease).toHaveBeenCalledOnce();

    await expect(result.current.closeCapability("workspace-1")).resolves.toBeUndefined();
    expect(flushForWorkspaceChange).toHaveBeenCalledTimes(2);
    expect(secondRelease).toHaveBeenCalledOnce();
  });

  it("identity 切换或组件卸载不会遗留或重复释放已取得的 lease", async () => {
    const pendingFlush = deferred<FilesWorkspaceCloseLease>();
    const release = vi.fn();
    const oldLifecycle = lifecycle("workspace-1", () => pendingFlush.promise);
    const newLifecycle = lifecycle("workspace-2", async () => ({ release: vi.fn() }));
    const { result, unmount } = renderHook(() => useFilesCapabilityCloseController());

    act(() => result.current.register(oldLifecycle));
    const close = result.current.closeCapability("workspace-1");
    act(() => result.current.register(newLifecycle));
    unmount();
    pendingFlush.resolve({ release });

    await expect(close).resolves.toBeUndefined();
    expect(release).toHaveBeenCalledOnce();
  });

  it("flush 失败时保留拒绝结果并允许同一 workspace 重试", async () => {
    const release = vi.fn();
    const flushForWorkspaceChange = vi
      .fn<FilesWorkspaceLifecycle["flushForWorkspaceChange"]>()
      .mockRejectedValueOnce(new Error("save failed"))
      .mockResolvedValueOnce({ release });
    const { result } = renderHook(() => useFilesCapabilityCloseController());

    act(() => result.current.register(lifecycle("workspace-1", flushForWorkspaceChange)));
    await expect(result.current.closeCapability("workspace-1")).rejects.toThrow("save failed");
    await expect(result.current.closeCapability("workspace-1")).resolves.toBeUndefined();

    expect(flushForWorkspaceChange).toHaveBeenCalledTimes(2);
    expect(release).toHaveBeenCalledOnce();
  });
});
