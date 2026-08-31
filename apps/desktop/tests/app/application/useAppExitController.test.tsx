// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  useAppExitController,
  type AppExitObserverPort,
} from "@/app/application/useAppExitController";
import type { AppExitRequest } from "@/api/tauri/window";

/** 创建可同步触发的托盘退出端口，使测试只关注应用层编排顺序。 */
function exitObserver() {
  let listener: ((request: AppExitRequest) => void) | undefined;
  const dispose = vi.fn();
  const port: AppExitObserverPort = {
    observe: vi.fn((next) => {
      listener = next;
      return { dispose };
    }),
  };
  return { port, dispose, emit: (request: AppExitRequest) => listener?.(request) };
}

afterEach(() => cleanup());

describe("useAppExitController", () => {
  it("按 Files、Preview、native commit 顺序完成托盘退出", async () => {
    const order: string[] = [];
    const observer = exitObserver();
    const release = vi.fn();
    const files = {
      workspaceId: "workspace-1",
      flushForWorkspaceChange: vi.fn(async () => {
        order.push("files");
        return { release };
      }),
    };
    const preview = {
      workspaceId: "workspace-1",
      closeForWorkspaceChange: vi.fn(async () => {
        order.push("preview");
      }),
    };
    const request: AppExitRequest = {
      commit: vi.fn(async () => {
        order.push("commit");
      }),
      cancel: vi.fn(async () => undefined),
    };
    renderHook(() =>
      useAppExitController(
        observer.port,
        () => files,
        () => preview,
        vi.fn(),
      ),
    );

    act(() => observer.emit(request));
    await waitFor(() => expect(request.commit).toHaveBeenCalledOnce());
    expect(order).toEqual(["files", "preview", "commit"]);
    expect(request.cancel).not.toHaveBeenCalled();
    expect(release).not.toHaveBeenCalled();
  });

  it("清理失败时释放 lease、取消 native 请求并只报告一次", async () => {
    const observer = exitObserver();
    const release = vi.fn();
    const files = {
      workspaceId: "workspace-1",
      flushForWorkspaceChange: vi.fn(async () => ({ release })),
    };
    const preview = {
      workspaceId: "workspace-1",
      closeForWorkspaceChange: vi.fn(async () => Promise.reject(new Error("conflict"))),
    };
    const onFailure = vi.fn();
    const request: AppExitRequest = {
      commit: vi.fn(async () => undefined),
      cancel: vi.fn(async () => undefined),
    };
    renderHook(() =>
      useAppExitController(
        observer.port,
        () => files,
        () => preview,
        onFailure,
      ),
    );

    act(() => observer.emit(request));
    await waitFor(() => expect(onFailure).toHaveBeenCalledOnce());
    expect(release).toHaveBeenCalledOnce();
    expect(request.cancel).toHaveBeenCalledOnce();
    expect(request.commit).not.toHaveBeenCalled();
  });
});
