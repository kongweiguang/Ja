// @author kongweiguang
import { useState } from "react";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import "@testing-library/jest-dom/vitest";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AttachmentPreview } from "../../../../../ja-agent/example/ja-agent-example-springboot/frontend/src/AttachmentPreview";
import { ConversationPane } from "../../../../../ja-agent/example/ja-agent-example-springboot/frontend/src/ConversationPane";
import { emptySession } from "../../../../../ja-agent/example/ja-agent-example-springboot/frontend/src/state";
import type { AttachmentMetadata } from "../../../../../ja-agent/example/ja-agent-example-springboot/frontend/src/types";

const attachment: AttachmentMetadata = {
  id: "att_preview",
  threadId: "thr_preview",
  fileName: "preview.png",
  mediaType: "image/png",
  sizeBytes: 3,
  sha256: "a".repeat(64),
  createdAt: "2026-10-06T00:00:00Z",
  referenced: true,
};

/** 保留来源控件和 Composer；点击时快照返回焦点，避免渲染阶段读取 ref。 */
function PreviewFixture({
  activeThread = "thr_preview",
  readOnly = false,
}: {
  activeThread?: string;
  readOnly?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [returnFocusTo, setReturnFocusTo] = useState<HTMLButtonElement>();
  return (
    <>
      <form className="composer">
        <textarea aria-label="消息" data-thread-id={activeThread} disabled={readOnly} />
      </form>
      <button
        onClick={(event) => {
          setReturnFocusTo(event.currentTarget);
          setOpen(true);
        }}
      >
        预览资料
      </button>
      {open && (
        <AttachmentPreview
          threadId="thr_preview"
          attachment={attachment}
          returnFocusTo={returnFocusTo}
          onClose={() => setOpen(false)}
        />
      )}
    </>
  );
}

/** 每例释放挂载资源，再恢复浏览器原生边界，避免 Blob 或焦点替身污染其它工作台用例。 */
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("Spring attachment preview focus", () => {
  /** 轮次刷新可能再次聚焦后台 Composer；模态预览必须守住焦点，防止键盘误操作隐藏页面。 */
  it("预览打开时后台输入框不能夺走键盘焦点", async () => {
    const nativeUrl = URL;
    vi.stubGlobal(
      "URL",
      class extends nativeUrl {
        static override createObjectURL = vi.fn(() => "blob:preview-test");
        static override revokeObjectURL = vi.fn();
      },
    );
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(new Blob(["png"], { type: "image/png" }))),
    );
    const user = userEvent.setup();
    render(<PreviewFixture />);
    const composer = screen.getByRole("textbox", { name: "消息" });
    await user.click(screen.getByRole("button", { name: "预览资料" }));
    await screen.findByRole("img");
    expect(screen.getByRole("dialog")).toHaveAttribute("aria-modal", "true");
    act(() => composer.focus());
    expect(screen.getByRole("dialog")).toContainElement(document.activeElement as HTMLElement);
    await user.keyboard("{Tab}{Shift>}{Tab}{/Shift}");
    expect(screen.getByRole("dialog")).toContainElement(document.activeElement as HTMLElement);
  });

  /** 慢下载不得重复拉取大文件；关闭预览后取消原请求，即使迟到成功也不能触发下载。 */
  it("下载处理中阻止重复请求，关闭后丢弃迟到结果", async () => {
    let completeDownload!: (response: Response) => void;
    let downloadSignal: AbortSignal | undefined;
    const nativeUrl = URL;
    const createUrl = vi.fn(() => "blob:preview-test");
    vi.stubGlobal(
      "URL",
      class extends nativeUrl {
        static override createObjectURL = createUrl;
        static override revokeObjectURL = vi.fn();
      },
    );
    const fetch = vi.fn((url: RequestInfo | URL, options?: RequestInit) => {
      if (String(url).includes("download=true")) {
        downloadSignal = options?.signal ?? undefined;
        return new Promise<Response>((resolve) => {
          completeDownload = resolve;
        });
      }
      return Promise.resolve(new Response(new Blob(["png"], { type: "image/png" })));
    });
    vi.stubGlobal("fetch", fetch);
    const clickDownload = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => undefined);
    const user = userEvent.setup();
    render(<PreviewFixture />);
    await user.click(screen.getByRole("button", { name: "预览资料" }));
    await screen.findByRole("img");
    const download = screen.getByRole("button", { name: "下载附件" });
    await user.click(download);
    expect(download).toBeDisabled();
    await user.click(download);
    expect(fetch.mock.calls.filter(([url]) => String(url).includes("download=true"))).toHaveLength(
      1,
    );
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(downloadSignal?.aborted).toBe(true);
    await act(async () =>
      completeDownload(new Response(new Blob(["late png"], { type: "image/png" }))),
    );
    expect(createUrl).toHaveBeenCalledTimes(1);
    expect(clickDownload).not.toHaveBeenCalled();
  });

  /** 下载失败只影响当前动作，保留预览；再次点击应完成下载并释放临时 URL。 */
  it("下载失败后保留资料并允许原地重试", async () => {
    let downloadAttempt = 0;
    const nativeUrl = URL;
    const revoke = vi.fn();
    vi.stubGlobal(
      "URL",
      class extends nativeUrl {
        static override createObjectURL = vi
          .fn()
          .mockReturnValueOnce("blob:preview-test")
          .mockReturnValueOnce("blob:download-test");
        static override revokeObjectURL = revoke;
      },
    );
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: RequestInfo | URL) => {
        if (String(url).includes("download=true") && ++downloadAttempt === 1)
          return new Response(null, { status: 503 });
        return new Response(new Blob(["png"], { type: "image/png" }));
      }),
    );
    const clickDownload = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => undefined);
    const user = userEvent.setup();
    render(<PreviewFixture />);
    await user.click(screen.getByRole("button", { name: "预览资料" }));
    await screen.findByRole("img");
    const download = screen.getByRole("button", { name: "下载附件" });
    await user.click(download);
    expect(await screen.findByRole("alert")).toHaveTextContent("下载附件失败");
    expect(download).toBeEnabled();
    expect(screen.getByRole("img")).toHaveAttribute("src", "blob:preview-test");
    await user.click(download);
    await waitFor(() => expect(clickDownload).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole("alert")).toBeNull();
    expect(download).toBeEnabled();
    await waitFor(() => expect(revoke).toHaveBeenCalledWith("blob:download-test"));
  });

  /** 附件动作会替换或卸载当前按钮；统一回到 Composer，避免取消或重试后丢失键盘位置。 */
  it.each([
    { status: "uploaded" as const, label: "移除附件", retry: false },
    { status: "unknown" as const, label: "使用原文件重试", retry: true },
    { status: "uploading" as const, label: "取消上传", retry: false },
  ])("$label 后返回消息输入框", async ({ status, label, retry }) => {
    const user = userEvent.setup();
    const remove = vi.fn();
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(null, { status: 503 })),
    );
    render(
      <ConversationPane
        threadId="thr_preview"
        session={{
          ...emptySession(),
          historyLoaded: true,
          attachmentDrafts: [
            {
              operationKey: "upload_preview",
              fileName: attachment.fileName,
              mediaType: attachment.mediaType,
              sizeBytes: attachment.sizeBytes,
              status,
              attachment: status === "uploaded" ? attachment : undefined,
            },
          ],
        }}
        model="test-model"
        onLoadMoreHistory={async () => undefined}
        onSubmit={() => undefined}
        onSessionChange={() => undefined}
        onAnswer={async () => undefined}
        onResume={async () => undefined}
        onRetry={() => undefined}
        onControl={async () => undefined}
        onWorkbench={() => undefined}
        pendingRecoveries={{}}
        onRefreshRecoveries={async () => undefined}
        onRemoveAttachment={remove}
        onCancelAttachment={remove}
        onRetryAttachment={remove}
      />,
    );
    await user.click(screen.getByRole("button", { name: `${label} preview.png` }));
    if (retry)
      expect(remove).toHaveBeenCalledExactlyOnceWith("thr_preview", "upload_preview", undefined);
    else expect(remove).toHaveBeenCalledExactlyOnceWith("thr_preview", "upload_preview");
    expect(screen.getByRole("textbox", { name: "消息" })).toHaveFocus();
  });

  /** 关闭可编辑会话预览后直接续写；只读与其它 Thread 的输入框都不能成为错误焦点。 */
  it.each([
    { activeThread: "thr_preview", readOnly: false, focus: "composer" },
    { activeThread: "thr_preview", readOnly: true, focus: "source" },
    { activeThread: "thr_other", readOnly: false, focus: "source" },
  ])("关闭预览返回 $focus：$activeThread / readOnly=$readOnly", async (options) => {
    const revoke = vi.fn();
    const nativeUrl = URL;
    vi.stubGlobal(
      "URL",
      class extends nativeUrl {
        static override createObjectURL = vi.fn(() => "blob:preview-test");
        static override revokeObjectURL = revoke;
      },
    );
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(new Blob(["png"], { type: "image/png" }))),
    );
    const user = userEvent.setup();
    render(<PreviewFixture activeThread={options.activeThread} readOnly={options.readOnly} />);
    const trigger = screen.getByRole("button", { name: "预览资料" });
    await user.click(trigger);
    await waitFor(() =>
      expect(screen.getByRole("img")).toHaveAttribute("src", "blob:preview-test"),
    );
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    const expected =
      options.focus === "composer" ? screen.getByRole("textbox", { name: "消息" }) : trigger;
    await waitFor(() => expect(expected).toHaveFocus());
    expect(revoke).toHaveBeenCalledExactlyOnceWith("blob:preview-test");
  });
});
