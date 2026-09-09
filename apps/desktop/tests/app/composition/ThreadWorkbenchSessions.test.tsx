// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, waitFor } from "@testing-library/react";
import { createElement, StrictMode, useEffect, useRef, type ComponentProps } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThreadWorkbenchSessions } from "@/app/composition/ThreadWorkbenchSessions";
import type { WorkbenchHost } from "@/app/composition/WorkbenchHost";
import type { TerminalWorkspaceLifecycle } from "@/app/application/workbenchLifecyclePorts";
import type { FilesWorkspaceLifecycle } from "@/features/workbench/files";
import type { PreviewWorkspaceLifecycle } from "@/features/workbench/preview";

const mocks = vi.hoisted(() => ({
  hosts: vi.fn(),
  createThreadWorkbenchAdapters: vi.fn((adapters: object) => ({
    ...adapters,
    threadAdapterOrdinal: mocks.createThreadWorkbenchAdapters.mock.calls.length,
  })),
  filesByThread: new Map<string, FilesWorkspaceLifecycle>(),
  terminalByThread: new Map<string, TerminalWorkspaceLifecycle>(),
  previewByThread: new Map<string, PreviewWorkspaceLifecycle>(),
  toastError: vi.fn(),
  instanceOrdinal: 0,
}));

vi.mock("sonner", () => ({ toast: { error: mocks.toastError } }));
vi.mock("@/app/application/threadWorkbenchAdapters", () => ({
  createThreadWorkbenchAdapters: mocks.createThreadWorkbenchAdapters,
}));
vi.mock("@/app/composition/WorkbenchHost", () => ({
  /** Mock 仍遵守真实注册/卸载协议，以验证聚合 registry，而不加载 Workbench 重组件。 */
  WorkbenchHost: (props: ComponentProps<typeof WorkbenchHost>) => {
    mocks.hosts(props);
    const instance = useRef(++mocks.instanceOrdinal);
    const {
      onRegisterFilesLifecycle,
      onRegisterTerminalLifecycle,
      onRegisterPreviewLifecycle,
      rootThreadId,
    } = props;
    useEffect(() => {
      onRegisterFilesLifecycle(
        rootThreadId === undefined ? undefined : mocks.filesByThread.get(rootThreadId),
      );
      onRegisterTerminalLifecycle(
        rootThreadId === undefined ? undefined : mocks.terminalByThread.get(rootThreadId),
      );
      onRegisterPreviewLifecycle(
        rootThreadId === undefined ? undefined : mocks.previewByThread.get(rootThreadId),
      );
      return () => {
        onRegisterFilesLifecycle(undefined);
        onRegisterTerminalLifecycle(undefined);
        onRegisterPreviewLifecycle(undefined);
      };
    }, [
      onRegisterFilesLifecycle,
      onRegisterPreviewLifecycle,
      onRegisterTerminalLifecycle,
      rootThreadId,
    ]);
    return createElement("output", {
      "data-testid": `host-${props.rootThreadId}`,
      "data-active": String(props.active),
      "data-instance": String(instance.current),
    });
  },
}));

afterEach(() => {
  cleanup();
  mocks.hosts.mockClear();
  mocks.createThreadWorkbenchAdapters.mockClear();
  mocks.filesByThread.clear();
  mocks.terminalByThread.clear();
  mocks.previewByThread.clear();
  mocks.instanceOrdinal = 0;
});

/** 构造只覆盖会话缓存契约的最小 WorkbenchHost 属性，复杂 feature 均由 Host mock 隔离。 */
function makeProps(
  threadId: string,
  overrides: Partial<ComponentProps<typeof ThreadWorkbenchSessions>> = {},
): ComponentProps<typeof ThreadWorkbenchSessions> {
  return {
    scopeKey: threadId,
    workspace: { workspaceId: "workspace-1" } as ComponentProps<
      typeof ThreadWorkbenchSessions
    >["workspace"],
    generation: 7,
    adapters: { terminal: {} } as ComponentProps<typeof ThreadWorkbenchSessions>["adapters"],
    active: true,
    rootThreadId: threadId,
    taskPort: {} as ComponentProps<typeof ThreadWorkbenchSessions>["taskPort"],
    taskTranscriptPort: {} as ComponentProps<typeof ThreadWorkbenchSessions>["taskTranscriptPort"],
    taskThreadRenamePort: {} as ComponentProps<
      typeof ThreadWorkbenchSessions
    >["taskThreadRenamePort"],
    selectedTab: "files",
    onTabChange: vi.fn(),
    openTabs: ["files"],
    onOpenTabsChange: vi.fn(),
    capabilityShortcuts: {},
    onCopyText: vi.fn(async () => undefined),
    onOpenExternalUrl: vi.fn(async () => undefined),
    onClose: vi.fn(),
    onRegisterFilesLifecycle: vi.fn(),
    onRegisterTerminalLifecycle: vi.fn(),
    onRegisterPreviewLifecycle: vi.fn(),
    onCloseFilesCapability: vi.fn(async () => undefined),
    onAddWorkspaceReference: vi.fn(),
    onWorkspaceReferencePreviewSettled: vi.fn(),
    onGitBranchChange: vi.fn(),
    latestTurnReviewAvailable: false,
    turnReviewPort: {} as ComponentProps<typeof ThreadWorkbenchSessions>["turnReviewPort"],
    onShowRetainedTurnReview: vi.fn(),
    onShowLatestTurnReview: vi.fn(),
    onDismissTurnReview: vi.fn(),
    ...overrides,
  };
}

/** 读取指定 Thread 最近一次 Host props，用于验证后台 Host 不消费当前会话输入。 */
function latestHostProps(threadId: string): ComponentProps<typeof WorkbenchHost> {
  const call = [...mocks.hosts.mock.calls]
    .reverse()
    .find(([props]) => (props as ComponentProps<typeof WorkbenchHost>).rootThreadId === threadId);
  if (call === undefined) throw new Error(`missing host props for ${threadId}`);
  return call[0] as ComponentProps<typeof WorkbenchHost>;
}

/** 可控 Promise 让测试精确停在跨会话切换的异步边界，不依赖任意计时等待。 */
function deferred<T>(): {
  readonly promise: Promise<T>;
  readonly resolve: (value: T) => void;
} {
  let resolve!: (value: T) => void;
  return {
    promise: new Promise<T>((next) => {
      resolve = next;
    }),
    resolve,
  };
}

describe("ThreadWorkbenchSessions", () => {
  it("Java 重连只更新代际投影，不卸载同一 Thread 的原生工作面", () => {
    const props = makeProps("thread-a");
    const view = render(<ThreadWorkbenchSessions {...props} />);
    const original = view.getByTestId("host-thread-a").getAttribute("data-instance");
    const adapter = latestHostProps("thread-a").adapters;
    view.rerender(<ThreadWorkbenchSessions {...props} generation={8} />);
    expect(view.getByTestId("host-thread-a").getAttribute("data-instance")).toBe(original);
    expect(latestHostProps("thread-a").adapters).toBe(adapter);
    expect(latestHostProps("thread-a").generation).toBe(8);
  });

  it("在 A/B 会话切换后保留两个 Host 实例，并为每个会话只创建一次 adapter", async () => {
    const view = render(<ThreadWorkbenchSessions {...makeProps("thread-a")} />);
    const firstA = view.getByTestId("host-thread-a").dataset["instance"];

    view.rerender(<ThreadWorkbenchSessions {...makeProps("thread-b")} />);
    await waitFor(() => expect(view.getByTestId("host-thread-b")).toBeInTheDocument());
    expect(
      view.getByTestId("host-thread-a").closest(".ja-thread-workbench-session"),
    ).toHaveAttribute("hidden");

    view.rerender(<ThreadWorkbenchSessions {...makeProps("thread-a")} />);
    expect(view.getByTestId("host-thread-a").dataset["instance"]).toBe(firstA);
    expect(view.getByTestId("host-thread-b")).toBeInTheDocument();
    expect(mocks.createThreadWorkbenchAdapters).toHaveBeenCalledTimes(2);
  });

  it("只让当前 Host active，后台 Host 保留原 Thread props 且不可见", async () => {
    const view = render(
      <ThreadWorkbenchSessions {...makeProps("thread-a", { requestedTurnReviewPath: "a.ts" })} />,
    );
    view.rerender(
      <ThreadWorkbenchSessions {...makeProps("thread-b", { requestedTurnReviewPath: "b.ts" })} />,
    );
    await waitFor(() => expect(view.getByTestId("host-thread-b")).toBeInTheDocument());

    expect(latestHostProps("thread-a").active).toBe(false);
    expect(latestHostProps("thread-a").requestedTurnReviewPath).toBe("a.ts");
    expect(latestHostProps("thread-b").active).toBe(true);
    expect(
      view.getByTestId("host-thread-b").closest(".ja-thread-workbench-session"),
    ).not.toHaveAttribute("hidden");
  });

  it("关闭 Files capability 只 flush 当前会话并在成功后发送焦点通知", async () => {
    const releaseA = vi.fn();
    const releaseB = vi.fn();
    const flushA = vi.fn(async () => ({ release: releaseA }));
    const flushB = vi.fn(async () => ({ release: releaseB }));
    mocks.filesByThread.set("thread-a", {
      workspaceId: "workspace-1",
      flushForWorkspaceChange: flushA,
    });
    mocks.filesByThread.set("thread-b", {
      workspaceId: "workspace-1",
      flushForWorkspaceChange: flushB,
    });
    const parentClose = vi.fn(async () => undefined);
    const notified = vi.fn();
    const view = render(
      <ThreadWorkbenchSessions
        {...makeProps("thread-a", {
          onCloseFilesCapability: parentClose,
          onFilesCapabilityClosed: notified,
        })}
      />,
    );
    view.rerender(
      <ThreadWorkbenchSessions
        {...makeProps("thread-b", {
          onCloseFilesCapability: parentClose,
          onFilesCapabilityClosed: notified,
        })}
      />,
    );
    await waitFor(() => expect(view.getByTestId("host-thread-b")).toBeInTheDocument());

    await latestHostProps("thread-b").onCloseFilesCapability("workspace-1");
    expect(flushB).toHaveBeenCalledOnce();
    expect(releaseB).toHaveBeenCalledOnce();
    expect(flushA).not.toHaveBeenCalled();
    expect(releaseA).not.toHaveBeenCalled();
    expect(parentClose).not.toHaveBeenCalled();
    expect(notified).toHaveBeenCalledWith("workspace-1");
  });

  it("聚合 Files flush 失败时释放其它会话已取得的 lease", async () => {
    const releaseA = vi.fn();
    const flushFailure = new Error("thread-b save failed");
    mocks.filesByThread.set("thread-a", {
      workspaceId: "workspace-1",
      flushForWorkspaceChange: vi.fn(async () => ({ release: releaseA })),
    });
    mocks.filesByThread.set("thread-b", {
      workspaceId: "workspace-1",
      flushForWorkspaceChange: vi.fn(async () => Promise.reject(flushFailure)),
    });
    const registerFiles = vi.fn();
    const view = render(
      <ThreadWorkbenchSessions
        {...makeProps("thread-a", { onRegisterFilesLifecycle: registerFiles })}
      />,
    );
    view.rerender(
      <ThreadWorkbenchSessions
        {...makeProps("thread-b", { onRegisterFilesLifecycle: registerFiles })}
      />,
    );
    await waitFor(() => expect(view.getByTestId("host-thread-b")).toBeInTheDocument());
    const aggregate = [...registerFiles.mock.calls]
      .reverse()
      .find(([lifecycle]) => lifecycle !== undefined)?.[0] as FilesWorkspaceLifecycle;

    await expect(aggregate.flushForWorkspaceChange()).rejects.toBe(flushFailure);
    expect(releaseA).toHaveBeenCalledOnce();
  });

  it("当前 Thread 暂时未定义时保留并隐藏已访问 Host", () => {
    const view = render(<ThreadWorkbenchSessions {...makeProps("thread-a")} />);
    const instance = view.getByTestId("host-thread-a").dataset["instance"];

    view.rerender(
      <ThreadWorkbenchSessions {...makeProps("", { scopeKey: "", rootThreadId: undefined })} />,
    );
    expect(view.getByTestId("host-thread-a").dataset["instance"]).toBe(instance);
    expect(
      view.getByTestId("host-thread-a").closest(".ja-thread-workbench-session"),
    ).toHaveAttribute("hidden");

    view.rerender(<ThreadWorkbenchSessions {...makeProps("thread-a")} />);
    expect(view.getByTestId("host-thread-a").dataset["instance"]).toBe(instance);
  });

  it("后台 A 的迟到 Files flush 只调用 A 缓存的完成通知", async () => {
    const pending = deferred<{ release: () => void }>();
    mocks.filesByThread.set("thread-a", {
      workspaceId: "workspace-1",
      flushForWorkspaceChange: vi.fn(() => pending.promise),
    });
    const notifyA = vi.fn();
    const notifyB = vi.fn();
    const view = render(
      <ThreadWorkbenchSessions {...makeProps("thread-a", { onFilesCapabilityClosed: notifyA })} />,
    );
    view.rerender(
      <ThreadWorkbenchSessions {...makeProps("thread-b", { onFilesCapabilityClosed: notifyB })} />,
    );

    const closeA = latestHostProps("thread-a").onCloseFilesCapability("workspace-1");
    pending.resolve({ release: vi.fn() });
    await closeA;
    expect(notifyA).toHaveBeenCalledOnce();
    expect(notifyB).not.toHaveBeenCalled();
  });

  it("Terminal 聚合关闭部分失败时恢复全部 Host 并允许重试", async () => {
    const closeA = vi.fn(async () => undefined);
    const closeB = vi
      .fn<() => Promise<void>>()
      .mockRejectedValueOnce(new Error("thread-b close failed"))
      .mockResolvedValueOnce(undefined);
    const resumeA = vi.fn();
    const resumeB = vi.fn();
    mocks.terminalByThread.set("thread-a", {
      workspaceId: "workspace-1",
      closeForWorkspaceChange: closeA,
      resumeAfterWorkspaceChange: resumeA,
    });
    mocks.terminalByThread.set("thread-b", {
      workspaceId: "workspace-1",
      closeForWorkspaceChange: closeB,
      resumeAfterWorkspaceChange: resumeB,
    });
    const registerTerminal = vi.fn();
    const view = render(
      <ThreadWorkbenchSessions
        {...makeProps("thread-a", { onRegisterTerminalLifecycle: registerTerminal })}
      />,
    );
    view.rerender(
      <ThreadWorkbenchSessions
        {...makeProps("thread-b", { onRegisterTerminalLifecycle: registerTerminal })}
      />,
    );
    await waitFor(() => expect(view.getByTestId("host-thread-b")).toBeInTheDocument());
    const aggregate = [...registerTerminal.mock.calls]
      .reverse()
      .find(([lifecycle]) => lifecycle !== undefined)?.[0] as TerminalWorkspaceLifecycle;

    await expect(aggregate.closeForWorkspaceChange()).rejects.toThrow("thread-b close failed");
    expect(resumeA).toHaveBeenCalledOnce();
    expect(resumeB).toHaveBeenCalledOnce();
    await expect(aggregate.closeForWorkspaceChange()).resolves.toBeUndefined();
    expect(closeA).toHaveBeenCalledTimes(2);
    expect(closeB).toHaveBeenCalledTimes(2);
  });

  it("Preview 聚合 lifecycle 关闭所有已访问 Host 而不是最后注册者", async () => {
    const closeA = vi.fn(async () => undefined);
    const closeB = vi.fn(async () => undefined);
    mocks.previewByThread.set("thread-a", {
      workspaceId: "workspace-1",
      closeForWorkspaceChange: closeA,
    });
    mocks.previewByThread.set("thread-b", {
      workspaceId: "workspace-1",
      closeForWorkspaceChange: closeB,
    });
    const registerPreview = vi.fn();
    const view = render(
      <ThreadWorkbenchSessions
        {...makeProps("thread-a", { onRegisterPreviewLifecycle: registerPreview })}
      />,
    );
    view.rerender(
      <ThreadWorkbenchSessions
        {...makeProps("thread-b", { onRegisterPreviewLifecycle: registerPreview })}
      />,
    );
    await waitFor(() => expect(view.getByTestId("host-thread-b")).toBeInTheDocument());
    const aggregate = [...registerPreview.mock.calls]
      .reverse()
      .find(([lifecycle]) => lifecycle !== undefined)?.[0] as PreviewWorkspaceLifecycle;

    await expect(aggregate.closeForWorkspaceChange()).resolves.toBeUndefined();
    expect(closeA).toHaveBeenCalledOnce();
    expect(closeB).toHaveBeenCalledOnce();
  });

  it("真实卸载先 flush Files，再等待 Terminal close fence，最后关闭 Preview 并释放 lease", async () => {
    const order: string[] = [];
    const terminalGate = deferred<void>();
    const release = vi.fn(() => order.push("files-release"));
    const flushFiles = vi.fn(async () => {
      order.push("files-flush");
      return { release };
    });
    const closeTerminal = vi.fn(() => {
      order.push("terminal-close");
      return terminalGate.promise;
    });
    const closePreview = vi.fn(async () => {
      order.push("preview-close");
    });
    mocks.filesByThread.set("thread-a", {
      workspaceId: "workspace-1",
      flushForWorkspaceChange: flushFiles,
    });
    mocks.terminalByThread.set("thread-a", {
      workspaceId: "workspace-1",
      closeForWorkspaceChange: closeTerminal,
      resumeAfterWorkspaceChange: vi.fn(),
    });
    mocks.previewByThread.set("thread-a", {
      workspaceId: "workspace-1",
      closeForWorkspaceChange: closePreview,
    });
    const view = render(<ThreadWorkbenchSessions {...makeProps("thread-a")} />);

    view.unmount();
    await waitFor(() => expect(closeTerminal).toHaveBeenCalledOnce());
    expect(flushFiles).toHaveBeenCalledOnce();
    expect(closePreview).not.toHaveBeenCalled();
    terminalGate.resolve(undefined);
    await waitFor(() => expect(closePreview).toHaveBeenCalledOnce());
    expect(release).toHaveBeenCalledOnce();
    expect(order).toEqual(["files-flush", "terminal-close", "preview-close", "files-release"]);
  });

  it("卸载快照关闭所有缓存 Host，而不是被子级注销清空", async () => {
    const closeTerminalA = vi.fn(async () => undefined);
    const closeTerminalB = vi.fn(async () => undefined);
    const closePreviewA = vi.fn(async () => undefined);
    const closePreviewB = vi.fn(async () => undefined);
    mocks.terminalByThread.set("thread-a", {
      workspaceId: "workspace-1",
      closeForWorkspaceChange: closeTerminalA,
      resumeAfterWorkspaceChange: vi.fn(),
    });
    mocks.terminalByThread.set("thread-b", {
      workspaceId: "workspace-1",
      closeForWorkspaceChange: closeTerminalB,
      resumeAfterWorkspaceChange: vi.fn(),
    });
    mocks.previewByThread.set("thread-a", {
      workspaceId: "workspace-1",
      closeForWorkspaceChange: closePreviewA,
    });
    mocks.previewByThread.set("thread-b", {
      workspaceId: "workspace-1",
      closeForWorkspaceChange: closePreviewB,
    });
    const view = render(<ThreadWorkbenchSessions {...makeProps("thread-a")} />);
    view.rerender(<ThreadWorkbenchSessions {...makeProps("thread-b")} />);
    await waitFor(() => expect(view.getByTestId("host-thread-b")).toBeInTheDocument());

    view.unmount();
    await waitFor(() => expect(closePreviewB).toHaveBeenCalledOnce());
    expect(closeTerminalA).toHaveBeenCalledOnce();
    expect(closeTerminalB).toHaveBeenCalledOnce();
    expect(closePreviewA).toHaveBeenCalledOnce();
  });

  it("StrictMode 模拟 cleanup 不关闭仍挂载实例，真实卸载只关闭一次", async () => {
    const flushFiles = vi.fn(async () => ({ release: vi.fn() }));
    const closeTerminal = vi.fn(async () => undefined);
    const closePreview = vi.fn(async () => undefined);
    mocks.filesByThread.set("thread-a", {
      workspaceId: "workspace-1",
      flushForWorkspaceChange: flushFiles,
    });
    mocks.terminalByThread.set("thread-a", {
      workspaceId: "workspace-1",
      closeForWorkspaceChange: closeTerminal,
      resumeAfterWorkspaceChange: vi.fn(),
    });
    mocks.previewByThread.set("thread-a", {
      workspaceId: "workspace-1",
      closeForWorkspaceChange: closePreview,
    });
    const view = render(
      <StrictMode>
        <ThreadWorkbenchSessions {...makeProps("thread-a")} />
      </StrictMode>,
    );

    await Promise.resolve();
    expect(flushFiles).not.toHaveBeenCalled();
    expect(closeTerminal).not.toHaveBeenCalled();
    expect(closePreview).not.toHaveBeenCalled();

    view.unmount();
    await waitFor(() => expect(closePreview).toHaveBeenCalledOnce());
    expect(flushFiles).toHaveBeenCalledOnce();
    expect(closeTerminal).toHaveBeenCalledOnce();
  });

  it("卸载 flush 失败仍关闭其它原生资源、释放已取得 lease 并报告失败", async () => {
    const releaseA = vi.fn();
    const closeTerminal = vi.fn(async () => undefined);
    const closePreview = vi.fn(async () => undefined);
    mocks.filesByThread.set("thread-a", {
      workspaceId: "workspace-1",
      flushForWorkspaceChange: vi.fn(async () => ({ release: releaseA })),
    });
    mocks.filesByThread.set("thread-b", {
      workspaceId: "workspace-1",
      flushForWorkspaceChange: vi.fn(async () => Promise.reject(new Error("save failed"))),
    });
    mocks.terminalByThread.set("thread-a", {
      workspaceId: "workspace-1",
      closeForWorkspaceChange: closeTerminal,
      resumeAfterWorkspaceChange: vi.fn(),
    });
    mocks.previewByThread.set("thread-b", {
      workspaceId: "workspace-1",
      closeForWorkspaceChange: closePreview,
    });
    const view = render(<ThreadWorkbenchSessions {...makeProps("thread-a")} />);
    view.rerender(<ThreadWorkbenchSessions {...makeProps("thread-b")} />);
    await waitFor(() => expect(view.getByTestId("host-thread-b")).toBeInTheDocument());

    view.unmount();
    await waitFor(() => expect(mocks.toastError).toHaveBeenCalledOnce());
    expect(releaseA).toHaveBeenCalledOnce();
    expect(closeTerminal).toHaveBeenCalledOnce();
    expect(closePreview).toHaveBeenCalledOnce();
    expect(mocks.toastError).toHaveBeenCalledWith(
      "部分文件或工作区资源未能完成清理，请重新打开项目确认。",
      { id: "ja-retained-workbench-cleanup-failed" },
    );
  });
});
