// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { afterEach, describe, expect, it, vi } from "vitest";

interface WorkerHarness {
  readonly dispatch: (unified: string) => void;
  readonly postMessage: ReturnType<typeof vi.fn>;
}

/** 在 jsdom 中提供最小 WorkerGlobalScope，并重新加载模块以捕获唯一 message listener。 */
async function loadWorker(): Promise<WorkerHarness> {
  vi.resetModules();
  let listener: ((event: MessageEvent<{ requestId: number; unified: string }>) => void) | undefined;
  const postMessage = vi.fn();
  vi.stubGlobal("self", {
    postMessage,
    addEventListener: vi.fn(
      (
        type: string,
        next: (event: MessageEvent<{ requestId: number; unified: string }>) => void,
      ) => {
        if (type === "message") listener = next;
      },
    ),
  });
  await import("@/features/workbench/review/application/turnReviewDiffWorker");
  if (listener === undefined) throw new Error("worker listener unavailable");
  return {
    dispatch: (unified) => listener!({ data: { requestId: 7, unified } } as MessageEvent),
    postMessage,
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("turnReviewDiffWorker", () => {
  /** Worker 成功响应只包含结构化文件，不回显原始请求正文。 */
  it("返回严格解析后的文件", async () => {
    const worker = await loadWorker();
    worker.dispatch("--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-old\n+new\n");

    expect(worker.postMessage).toHaveBeenCalledWith({
      requestId: 7,
      files: [expect.objectContaining({ path: "a.txt", additions: 1, deletions: 1 })],
    });
  });

  /** 整文件 fallback 可在旧、新末行各带 marker，但 Worker 只返回四条真实增删行。 */
  it("过滤 fallback 双侧 EOF marker", async () => {
    const worker = await loadWorker();
    worker.dispatch(
      "--- a/a.txt\n+++ b/a.txt\n@@ -1,2 +1,2 @@\n-old1\n-old2\n\\ No newline at end of file\n+new1\n+new2\n\\ No newline at end of file\n",
    );

    const response = worker.postMessage.mock.calls[0]?.[0] as {
      files?: Array<{ lines: Array<{ text: string }> }>;
    };
    expect(response.files?.[0]?.lines.map((line) => line.text)).toEqual([
      "old1",
      "old2",
      "new1",
      "new2",
    ]);
  });

  /** 非完整 grammar 统一映射为稳定错误码，禁止空数组冒充可审查结果。 */
  it("将尾随垃圾收敛为 invalid_diff", async () => {
    const worker = await loadWorker();
    worker.dispatch("garbage\n");

    expect(worker.postMessage).toHaveBeenCalledWith({ requestId: 7, error: "invalid_diff" });
  });
});
