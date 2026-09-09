// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createReviewSyntaxHighlighter,
  SupersededReviewSyntaxRequest,
  type ReviewSyntaxRequest,
} from "@/features/workbench/review/application/reviewSyntaxHighlightClient";

interface FakeWorkerInstance {
  readonly postMessage: ReturnType<typeof vi.fn>;
  readonly terminate: ReturnType<typeof vi.fn>;
  dispatch(type: "message" | "error" | "messageerror", data?: unknown): void;
}

/** 构造严格保留原文的一行请求，便于观察 Worker 消息队列而不耦合 parser。 */
function request(identity: string): ReviewSyntaxRequest {
  return {
    identity,
    filePath: `${identity}.ts`,
    lineCount: 1,
    fragments: [{ lineIndexes: [0], lines: [identity] }],
  };
}

/** 安装可手动派发事件的 Worker，测试真实客户端生命周期而不启动浏览器线程。 */
function installWorker(): FakeWorkerInstance[] {
  const instances: FakeWorkerInstance[] = [];
  vi.stubGlobal(
    "Worker",
    class {
      readonly postMessage = vi.fn();
      readonly terminate = vi.fn();
      readonly listeners = new Map<string, Set<(event: MessageEvent) => void>>();

      constructor() {
        instances.push(this as unknown as FakeWorkerInstance);
      }

      addEventListener(type: string, listener: (event: MessageEvent) => void): void {
        const listeners = this.listeners.get(type) ?? new Set();
        listeners.add(listener);
        this.listeners.set(type, listeners);
      }

      removeEventListener(type: string, listener: (event: MessageEvent) => void): void {
        this.listeners.get(type)?.delete(listener);
      }

      dispatch(type: string, data?: unknown): void {
        for (const listener of this.listeners.get(type) ?? []) listener({ data } as MessageEvent);
      }
    },
  );
  return instances;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("review syntax highlight client", () => {
  it("Worker 队列始终只有 active 与 latest，并淘汰中间文件", async () => {
    const workers = installWorker();
    const client = createReviewSyntaxHighlighter();
    const worker = workers[0]!;
    const a = client.highlight(request("A"));
    const b = client.highlight(request("B"));
    const c = client.highlight(request("C"));

    expect(worker.postMessage).toHaveBeenCalledTimes(1);
    await expect(b).rejects.toBeInstanceOf(SupersededReviewSyntaxRequest);
    const firstId = worker.postMessage.mock.calls[0]?.[0].requestId as number;
    worker.dispatch("message", { requestId: firstId, lines: [[{ text: "A" }]] });
    await expect(a).resolves.toEqual([[{ text: "A" }]]);
    expect(worker.postMessage).toHaveBeenCalledTimes(2);
    const latestId = worker.postMessage.mock.calls[1]?.[0].requestId as number;
    worker.dispatch("message", { requestId: latestId, lines: [[{ text: "C" }]] });
    await expect(c).resolves.toEqual([[{ text: "C" }]]);
    client.dispose();
  });

  it("完成的高亮结果不缓存，再次查看同一文件仍重新进入 Worker", async () => {
    const workers = installWorker();
    const client = createReviewSyntaxHighlighter();
    const worker = workers[0]!;
    const first = client.highlight(request("A"));
    const firstId = worker.postMessage.mock.calls[0]?.[0].requestId as number;
    worker.dispatch("message", { requestId: firstId, lines: [[{ text: "A" }]] });
    await first;

    const second = client.highlight(request("A"));
    expect(worker.postMessage).toHaveBeenCalledTimes(2);
    const secondId = worker.postMessage.mock.calls[1]?.[0].requestId as number;
    expect(secondId).not.toBe(firstId);
    worker.dispatch("message", { requestId: secondId, lines: [[{ text: "A" }]] });
    await second;
    client.dispose();
  });

  it("拒绝改变正文或使用未知 role 的 Worker 响应", async () => {
    const workers = installWorker();
    const client = createReviewSyntaxHighlighter();
    const result = client.highlight(request("source"));
    const requestId = workers[0]!.postMessage.mock.calls[0]?.[0].requestId as number;
    workers[0]!.dispatch("message", {
      requestId,
      lines: [[{ text: "injected", role: "arbitrary" }]],
    });
    await expect(result).rejects.toThrow("highlight failed");
    client.dispose();
  });

  it("超预算正文保持纯文本并且不进入 Worker 队列", async () => {
    const workers = installWorker();
    const client = createReviewSyntaxHighlighter();
    await expect(
      client.highlight({
        identity: "large",
        filePath: "large.ts",
        lineCount: 1,
        fragments: [{ lineIndexes: [0], lines: ["x".repeat(512 * 1024 + 1)] }],
      }),
    ).resolves.toBeUndefined();
    expect(workers[0]!.postMessage).not.toHaveBeenCalled();
    client.dispose();
  });

  it("Worker 启动失败会收口活动回调，dispose 后不再接受请求", async () => {
    const workers = installWorker();
    const client = createReviewSyntaxHighlighter();
    const pending = client.highlight(request("A"));
    workers[0]!.dispatch("error");
    await expect(pending).rejects.toThrow("worker unavailable");
    await expect(client.highlight(request("B"))).rejects.toThrow("worker unavailable");
    client.dispose();
    await expect(client.highlight(request("C"))).rejects.toThrow("disposed");
    expect(workers[0]!.terminate).toHaveBeenCalledTimes(1);
  });
});
